package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.money.MoneyRules.SettlementAmounts;
import io.hammerhall.data.auc.money.MoneyTables.RowOut;
import io.hammerhall.data.auc.money.PaymentPlanner.Detail;
import io.hammerhall.data.auc.money.PaymentPlanner.Doc;
import io.hammerhall.data.auc.money.PaymentPlanner.Pay;
import io.hammerhall.data.auc.money.PaymentPlanner.PayKind;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.AuctionWorld.Consignment;
import io.hammerhall.data.auc.world.AuctionWorld.InvoicePlan;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.InvoiceKind;
import io.hammerhall.data.auc.world.LotResult;
import io.hammerhall.data.auc.world.LotResultCode;
import io.hammerhall.data.auc.world.PayMethod;
import io.hammerhall.data.auc.world.PaymentPlanner.InvoiceDraft;
import io.hammerhall.data.auc.world.Registrations;
import io.hammerhall.data.core.gen.Planted;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.RuCalendar;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * Сборка денег: по итогам торгов ({@code AUCTION}) и планам оплаты ({@link PaymentPlanner}) — пять таблиц на срез.
 * Моделируется всё (планы не знают среза), в выпуск идёт только то, что раньше среза; поля «на „сейчас“» — на срез.
 *
 * <ol>
 *   <li><b>Счета</b> по моменту выставления: исходный и переизданный; строки (M1–M3), состояние на срез (M6),
 *       платежи плана (с неудачными, депозитом в зачёт, дублями и их возвратами).</li>
 *   <li><b>Депозиты</b> регистраций: внесён до допуска; судьба — ровно одна: в зачёт счёта (план), удержан при отказе
 *       (в момент отмены), иначе возврат через 5 рабочих дней после торгов.</li>
 *   <li><b>Отчёты комитентам</b> — на следующий рабочий день после торгов: строка на лот (итог на срез), строки второго
 *       шанса; выплата по строке — по M7, по дню выплаты один платёж на отчёт; минусы (каталог непроданных, снятие)
 *       удерживаются из первой выплаты, которая их покрывает, иначе — долг комитента.</li>
 *   <li><b>Платежи</b> — все виды, {@code id} по моменту.</li>
 * </ol>
 */
final class MoneyBook {
    /** Доля строк с выплатой раньше оплаты ({@code payout_before_payment}) в {@code full}; в малых профилях — выше (÷ √масштаба). */
    static final double PAYOUT_EARLY_SHARE_FULL = 5e-5;

    private final HouseRules house;
    private final MoneyRules money;
    private final Calendar calendar;
    private final AuctionWorld aw;
    private final PaymentPlanner planner;
    private final RuCalendar ru;
    private final long cut;
    private final double payoutEarlyShare;
    private final Planted planted;
    private final RowOut out;
    private final MoneyAudit audit;
    private final RandomStreams.Stream stlStream, depStream;

    // лоты: ключ → индекс (lots.id − 1)
    private final LongIntMap lotIdx;
    private final int lots;
    // оплата лота по видам счёта: момент полной оплаты на срез (0 — нет); второй шанс — цена и выставление
    private final long[] paidSale, paidSaleModel, paidAfter, paidSecond, secondPrice, secondIssued;
    private final int[] lotBuyerInvoice;

    // счета
    private final LongIntMap invoiceIdByDocKey = new LongIntMap(1 << 16);
    private final LongIntMap payDocKeyIdx = new LongIntMap(1 << 16);
    private final List<Long> payDocKeyOfDraft = new ArrayList<>();
    // депозиты: ключ регистрации → судьба из плана счёта
    private final LongIntMap depIdx = new LongIntMap(1 << 10);
    private final List<long[]> depFate = new ArrayList<>(); // {applied 0/1, forfeitAt, invoiceId, invoiceIssuedAt}

    final PayRows pays = new PayRows();
    final MoneyEvents events = new MoneyEvents();
    final Stats stats = new Stats();

    /** Счётчики прогона — в журнал и самопроверку. */
    static final class Stats {
        long invoices, lines, reissued, oldSchedule, cancelledNonPayment, deposits, depositApplied, depositRefund,
                depositForfeit, settlements, settlementLines, payouts, payoutEarly, duplicates, planMismatch;
    }

    MoneyBook(HouseRules house, Calendar calendar, AuctionWorld aw, PaymentPlanner planner, RandomStreams streams,
              RuCalendar ru, long cut, double scale, Planted planted, RowOut out, MoneyAudit audit) {
        this.house = house;
        this.money = planner.money();
        this.calendar = calendar;
        this.aw = aw;
        this.planner = planner;
        this.ru = ru;
        this.cut = cut;
        this.payoutEarlyShare = PAYOUT_EARLY_SHARE_FULL / StrictMath.sqrt(Math.max(1e-4, scale));
        this.planted = planted;
        this.out = out;
        this.audit = audit;
        this.stlStream = streams.stream("money.settlements");
        this.depStream = streams.stream("money.deposits");
        this.lots = aw.lotCount();
        this.lotIdx = new LongIntMap(Math.max(16, lots));
        for (int id = 1; id <= lots; id++) lotIdx.put(aw.lot(id).lotKey(), id - 1);
        paidSale = new long[lots];
        paidSaleModel = new long[lots];
        paidAfter = new long[lots];
        paidSecond = new long[lots];
        secondPrice = new long[lots];
        secondIssued = new long[lots];
        lotBuyerInvoice = new int[lots];
    }

    void run() throws IOException {
        invoices();
        deposits();
        List<Stl> stl = settlements();
        writeSettlements(stl);
        writePayments();
        events.seal();
    }

    // ---------------------------------------------------------------- счета

    private record Pending(Doc doc, Detail detail, InvoiceDraft draft, boolean pay) {
    }

    private static final Comparator<Pending> PENDING = Comparator.<Pending>comparingLong(p -> p.doc.issuedAt())
            .thenComparingLong(p -> p.doc.key());

    private void invoices() throws IOException {
        List<InvoicePlan> plans = new ArrayList<>(aw.invoices());
        plans.sort(Comparator.<InvoicePlan>comparingLong(p -> p.draft().issuedAt()).thenComparingLong(p -> p.draft().key()));
        PriorityQueue<Pending> q = new PriorityQueue<>(PENDING);
        int[] perYear = new int[3000];
        int seq = 0;
        for (int k = 0; k <= plans.size(); k++) {
            long horizon = k < plans.size() ? plans.get(k).draft().issuedAt() : Long.MAX_VALUE;
            while (!q.isEmpty() && q.peek().doc.issuedAt() < horizon) {
                Pending p = q.poll();
                if (p.doc.issuedAt() >= cut) continue;
                emit(p, ++seq, perYear);
            }
            if (k == plans.size()) break;
            InvoicePlan ip = plans.get(k);
            InvoiceDraft d = ip.draft();
            Detail x = planner.detail(d);
            if (ip.plan() != null && !ip.plan().equals(x.plan())) stats.planMismatch++;
            payDocKeyIdx.put(d.key(), payDocKeyOfDraft.size());
            payDocKeyOfDraft.add(x.payDoc().key());
            if (d.kind() == InvoiceKind.SALE && d.depositKop() > 0) {
                depIdx.put(d.regKey(), depFate.size());
                depFate.add(new long[]{x.depositKop() > 0 ? 1 : 0, x.defaults() ? x.cancelAt() : 0, 0, 0});
            }
            q.add(new Pending(x.original(), x, d, x.reissue() == null));
            if (x.reissue() != null) q.add(new Pending(x.reissue(), x, d, true));
        }
        out.close("invoices");
        out.close("invoice_lines");
    }

    private void emit(Pending p, int id, int[] perYear) throws IOException {
        Doc doc = p.doc;
        Detail x = p.detail;
        InvoiceDraft d = p.draft;
        MoneyRules.Amounts a = doc.amounts();
        invoiceIdByDocKey.put(doc.key(), id);
        int year = MoneyTime.date(doc.issuedAt()).getYear();
        String number = String.format(java.util.Locale.ROOT, "INV-%d-%06d", year, ++perYear[year]);
        Integer related = null;
        if (doc.kind() == InvoiceKind.REISSUE) {
            related = idOf(x.original().key());
        } else if (d.relatedKey() != 0) {
            int di = payDocKeyIdx.get(d.relatedKey());
            related = di < 0 ? null : idOf(payDocKeyOfDraft.get(di));
            if (related == null) audit.fail("связь счёта", "нет счёта неплательщика для " + number);
        }
        int regId = aw.registrations().id(d.regKey());
        long total = a.total();
        long paidTotal = 0, paidAt = 0, cancelledAt = 0;
        String cancelReason = null;
        if (p.pay) {
            for (Pay q : x.payments()) {
                if (q.at() >= cut || !q.ok() || q.duplicate()) continue;
                if (q.kind() == PayKind.INVOICE || q.kind() == PayKind.DEPOSIT_APPLIED) paidTotal += q.amountKop();
            }
            if (x.paidAt() != 0 && x.paidAt() < cut) paidAt = x.paidAt();
            if (x.defaults() && x.cancelAt() < cut) {
                cancelledAt = x.cancelAt();
                cancelReason = "non_payment";
            }
        } else if (x.reissue().issuedAt() < cut) {
            cancelledAt = x.reissue().issuedAt();
            cancelReason = "house_error";
        }
        long lateAt = paidAt != 0 ? paidAt : cancelledAt != 0 ? cancelledAt : cut;
        long lateFee = MoneyRules.lateFee(total, MoneyTime.lateDays(doc.dueAt(), lateAt));
        String status = cancelledAt != 0 ? "cancelled" : paidAt != 0 ? "paid" : doc.dueAt() < cut ? "overdue"
                : paidTotal > 0 ? "part_paid" : "issued";
        out.add("invoices", id, number, d.saleId(), regId, d.clientId(), doc.kind().code(), doc.issuedAt(), doc.dueAt(),
                a.hammerTotal(), a.premiumTotal(), a.platformTotal(), a.vatTotal(), total, lateFee, paidTotal, status,
                paidAt == 0 ? null : paidAt, cancelledAt == 0 ? null : cancelledAt, cancelReason, related);
        stats.invoices++;
        Calendar.Sale sale = calendar.sale(d.saleId());
        audit.invoice(id, number, doc, x, d, sale, regId, paidTotal, paidAt, cancelledAt, cancelReason, lateFee, status,
                cut, p.pay);
        for (int i = 0; i < a.lines(); i++) {
            long lotKey = d.lines().get(i).lotKey();
            int li = lotIdx.get(lotKey);
            if (li < 0) throw new IllegalStateException("счёт " + number + ": лот " + lotKey + " не из AUCTION");
            out.add("invoice_lines", id, i + 1, li + 1, a.price()[i], a.scheduleId(), a.premium()[i], a.platformFee()[i],
                    BigDecimal.valueOf(a.vatPct()), a.vat()[i], a.lineTotal()[i]);
            stats.lines++;
            audit.invoiceLine(id, number, i + 1, aw.lot(li + 1), sale, doc.kind(), a, i,
                    x.oldSchedule() && doc.kind() == InvoiceKind.SALE);
            if (p.pay) {
                long pa = paidAt;
                switch (doc.kind()) {
                    case SALE, REISSUE -> {
                        paidSale[li] = pa;
                        paidSaleModel[li] = x.paidAt();
                        lotBuyerInvoice[li] = id;
                    }
                    case AFTER_SALE -> paidAfter[li] = pa;
                    case SECOND_CHANCE -> {
                        paidSecond[li] = pa;
                        secondPrice[li] = a.price()[i];
                        secondIssued[li] = doc.issuedAt();
                    }
                    case SHORTFALL -> {
                    }
                }
            }
        }
        if (x.oldSchedule() && doc.kind() == InvoiceKind.SALE) {
            planted.add("premium_old_schedule", doc.key());
            stats.oldSchedule++;
        }
        if (doc.kind() == InvoiceKind.REISSUE) stats.reissued++;
        if ("non_payment".equals(cancelReason)) stats.cancelledNonPayment++;
        events.add(MoneyEvents.INVOICE_ISSUED, d.clientId(), d.saleId(), id, doc.issuedAt(), id, 0);
        if (cancelledAt != 0) events.add(MoneyEvents.INVOICE_CANCELLED, d.clientId(), d.saleId(), id, cancelledAt, id, 0);
        if (!p.pay) return;
        // напоминания: за 2 дня до срока, на следующий день после срока, за неделю до отмены — если не оплачен
        long issueDay = MoneyTime.epochDay(doc.issuedAt());
        int[] days = {8, 11, 24};
        for (int n = 0; n < days.length; n++) {
            long at = MoneyTime.at(issueDay + days[n], 10 * 3600);
            if (at >= cut || (x.paidAt() != 0 && x.paidAt() <= at) || (x.defaults() && x.cancelAt() <= at)) continue;
            events.add(MoneyEvents.PAYMENT_REMINDER, d.clientId(), d.saleId(), id, at, id, n + 1);
        }
        for (Pay q : x.payments()) {
            if (q.at() >= cut) continue;
            byte kind = switch (q.kind()) {
                case INVOICE -> PayRows.INVOICE;
                case DEPOSIT_APPLIED -> PayRows.DEPOSIT_APPLIED;
                case REFUND -> PayRows.REFUND;
            };
            int reg = q.kind() == PayKind.DEPOSIT_APPLIED ? regId : 0;
            int row = pays.add(kind, q.at(), q.amountKop(), d.clientId(), q.method(), id, reg, 0, q.ok(), q.failure(),
                    q.providerRef(), PayRows.OWNER_INVOICE, id, q.seq(), q.duplicate());
            if (q.duplicate()) {
                planted.add("duplicate_payment", doc.key() * 64 + q.seq());
                stats.duplicates++;
            }
            if (q.kind() == PayKind.DEPOSIT_APPLIED) stats.depositApplied++;
            if (kind == PayRows.INVOICE && q.ok() && !q.duplicate()) events.payment(row, MoneyEvents.PAYMENT_RECEIVED,
                    d.clientId(), d.saleId(), id, q.at());
        }
        int di = depIdx.get(d.regKey());
        if (di >= 0 && d.kind() == InvoiceKind.SALE) {
            depFate.get(di)[2] = id;
            depFate.get(di)[3] = doc.issuedAt();
        }
    }

    private Integer idOf(long docKey) {
        int v = invoiceIdByDocKey.get(docKey);
        return v < 0 ? null : v;
    }

    // ---------------------------------------------------------------- депозиты

    private void deposits() {
        for (Calendar.Sale sale : calendar.sales()) {
            List<Registrations.Registration> regs = aw.registrations().ofSale(sale.id());
            long end = -1;
            for (Registrations.Registration r : regs) {
                if (r.depositKop() <= 0 || r.id() == 0) continue;
                Rng g = depStream.at(r.key());
                long at = r.registeredAt() + 300 + g.nextInt(3 * 3600);
                if (r.premiumApprovedAt() > 0 && at >= r.premiumApprovedAt()) {
                    at = Math.max(r.registeredAt() + 1, r.premiumApprovedAt() - 60 - g.nextInt(600));
                }
                if (at >= cut) continue;
                PayMethod m = g.chance(0.5) ? PayMethod.BANK_TRANSFER : PayMethod.CARD;
                long ref = m == PayMethod.CARD ? g.nextLong() | 1 : 0;
                pays.add(PayRows.DEPOSIT, at, r.depositKop(), r.clientId(), m, 0, r.id(), 0, true, null, ref,
                        PayRows.OWNER_REGISTRATION, r.id(), 0, false);
                stats.deposits++;
                int di = depIdx.get(r.key());
                long[] f = di >= 0 ? depFate.get(di) : null;
                MoneyAudit.Deposit dep = audit.deposit(r.id(), sale.id(), r.depositKop(), at);
                if (f != null && f[0] == 1) {
                    dep.fate("applied", f[3]);
                    continue;
                }
                if (f != null && f[1] != 0) {
                    if (f[1] < cut) {
                        pays.add(PayRows.DEPOSIT_FORFEIT, f[1], r.depositKop(), r.clientId(), PayMethod.DEPOSIT,
                                (int) f[2], r.id(), 0, true, null, 0, PayRows.OWNER_REGISTRATION, r.id(), 1, false);
                        stats.depositForfeit++;
                        dep.fate("forfeit", f[1]);
                    }
                    continue;
                }
                // возврат: через 5 рабочих дней после торгов (или после отказа в регистрации)
                long base;
                if (r.approved()) {
                    if (end < 0) end = saleEnd(sale.id());
                    base = end;
                } else {
                    base = Math.max(r.decidedAt(), at);
                }
                if (base <= 0) continue; // торги не закончились к срезу — депозит ещё у дома
                LocalDate day = workdaysAfter(MoneyTime.date(base), 5);
                long rat = MoneyTime.at(day.toEpochDay(), 11 * 3600 + g.nextInt(6 * 3600));
                if (rat >= cut) continue;
                int row = pays.add(PayRows.DEPOSIT_REFUND, rat, r.depositKop(), r.clientId(), m, 0, r.id(), 0, true, null,
                        m == PayMethod.CARD ? g.nextLong() | 1 : 0, PayRows.OWNER_REGISTRATION, r.id(), 1, false);
                events.payment(row, MoneyEvents.DEPOSIT_REFUNDED, r.clientId(), sale.id(), 0, rat);
                stats.depositRefund++;
                dep.fate("refund", rat);
            }
        }
    }

    /** Конец торгов — последний закрытый лот; 0 — не все лоты закрыты к срезу. */
    private long saleEnd(int saleId) {
        long end = 0;
        for (LotResult l : aw.lotsOfSale(saleId)) {
            if (l.result() == LotResultCode.OPEN) return 0;
            end = Math.max(end, l.closedAt());
        }
        return end;
    }

    LocalDate workdaysAfter(LocalDate d, int n) {
        int k = 0;
        while (k < n) {
            d = d.plusDays(1);
            if (ru.isWorking(d)) k++;
        }
        return d;
    }

    private LocalDate nextWorkday(LocalDate d) {
        do d = d.plusDays(1); while (!ru.isWorking(d));
        return d;
    }

    private LocalDate workdayOnOrAfter(LocalDate d) {
        while (!ru.isWorking(d)) d = d.plusDays(1);
        return d;
    }

    // ---------------------------------------------------------------- отчёты комитентам

    /** Отчёт: шапка, строки (индексы в {@link #ln}), выплаты. */
    static final class Stl {
        int id, saleId, consignorId;
        long saleKey, createdAt, hammer, commission, fees, vat, net, paidTotal;
        String status;
        int from, to;
        long[] payoutAt = new long[0], payoutAmount = new long[0];
    }

    /** Строки отчётов компактно. */
    final class Lines {
        int size;
        int[] lotId = new int[1024];
        byte[] outcome = new byte[1024];
        long[] price = new long[1024], com = new long[1024], ins = new long[1024], cat = new long[1024],
                wdr = new long[1024], vat = new long[1024], net = new long[1024], payable = new long[1024],
                paid = new long[1024];

        int add(int lot, byte oc, SettlementAmounts s, long payableAt) {
            if (size == lotId.length) {
                int n = size * 2;
                lotId = Arrays.copyOf(lotId, n);
                outcome = Arrays.copyOf(outcome, n);
                price = Arrays.copyOf(price, n);
                com = Arrays.copyOf(com, n);
                ins = Arrays.copyOf(ins, n);
                cat = Arrays.copyOf(cat, n);
                wdr = Arrays.copyOf(wdr, n);
                vat = Arrays.copyOf(vat, n);
                net = Arrays.copyOf(net, n);
                payable = Arrays.copyOf(payable, n);
                paid = Arrays.copyOf(paid, n);
            }
            int i = size++;
            lotId[i] = lot;
            outcome[i] = oc;
            price[i] = s.price();
            com[i] = s.commission();
            ins[i] = s.insurance();
            cat[i] = s.catalogue();
            wdr[i] = s.withdrawal();
            vat[i] = s.vat();
            net[i] = s.net();
            payable[i] = payableAt;
            paid[i] = 0;
            return i;
        }
    }

    static final String[] OUTCOME = {"sold", "sold_after_sale", "second_chance", "bought_in", "withdrawn", "buyer_default"};
    static final byte SOLD = 0, SOLD_AFTER_SALE = 1, SECOND_CHANCE = 2, BOUGHT_IN = 3, WITHDRAWN = 4, BUYER_DEFAULT = 5;

    final Lines ln = new Lines();

    private List<Stl> settlements() {
        List<Stl> all = new ArrayList<>();
        for (Calendar.Sale sale : calendar.sales()) {
            List<LotResult> sl = new ArrayList<>(aw.lotsOfSale(sale.id()));
            if (sl.isEmpty()) continue;
            long end = 0;
            boolean open = false;
            for (LotResult l : sl) {
                if (l.result() == LotResultCode.OPEN) open = true;
                end = Math.max(end, l.closedAt());
            }
            if (open || end == 0 || end >= cut) continue;
            LocalDate endDay = MoneyTime.date(end);
            LocalDate createdDay = nextWorkday(endDay);
            audit.saleEnd(sale.id(), endDay);
            sl.sort(Comparator.comparingLong(LotResult::lotKey));
            TreeMap<Integer, List<LotResult>> byConsignor = new TreeMap<>();
            for (LotResult l : sl) {
                Consignment c = aw.consignment(l.consignmentId());
                byConsignor.computeIfAbsent(c.consignorId(), k -> new ArrayList<>()).add(l);
            }
            for (var e : byConsignor.entrySet()) {
                Rng g = stlStream.at(sale.key(), e.getKey());
                long createdAt = MoneyTime.at(createdDay.toEpochDay(), 10 * 3600 + g.nextInt(5 * 3600));
                if (createdAt >= cut) continue;
                Stl s = new Stl();
                s.saleId = sale.id();
                s.saleKey = sale.key();
                s.consignorId = e.getKey();
                s.createdAt = createdAt;
                s.from = ln.size;
                settlementLines(s, sale, endDay, e.getValue());
                s.to = ln.size;
                all.add(s);
            }
        }
        all.sort(Comparator.<Stl>comparingLong(s -> s.createdAt).thenComparingInt(s -> s.saleId)
                .thenComparingInt(s -> s.consignorId));
        for (int i = 0; i < all.size(); i++) all.get(i).id = i + 1;
        return all;
    }

    private void settlementLines(Stl s, Calendar.Sale sale, LocalDate endDay, List<LotResult> lotsOf) {
        LocalDate saleDate = sale.saleDate();
        List<Integer> second = new ArrayList<>();
        for (LotResult l : lotsOf) {
            int li = l.lotId() - 1;
            Consignment c = aw.consignment(l.consignmentId());
            switch (l.result()) {
                case WITHDRAWN -> ln.add(l.lotId(), WITHDRAWN, money.withdrawn(withdrawnByHouse(l), l.lowKop(), sale.format(),
                        saleDate), 0);
                case BOUGHT_IN -> ln.add(l.lotId(), BOUGHT_IN, money.boughtIn(sale.format(), saleDate), 0);
                case SOLD_AFTER_SALE -> ln.add(l.lotId(), SOLD_AFTER_SALE, money.sold(l.priceKop(), c.dealerDiscount(),
                        c.commissionMilliPct(), sale.format(), true, saleDate), payable(endDay, paidAfter[li]));
                case SOLD -> {
                    if (l.buyerDefault() && l.defaultAt() > 0 && l.defaultAt() < cut) {
                        ln.add(l.lotId(), BUYER_DEFAULT, MoneyRules.ZERO, 0);
                        if (secondIssued[li] != 0 && secondIssued[li] < cut) second.add(li);
                    } else {
                        ln.add(l.lotId(), SOLD, money.sold(l.priceKop(), c.dealerDiscount(), c.commissionMilliPct(),
                                sale.format(), true, saleDate), payable(endDay, paidSale[li]));
                    }
                }
                case OPEN -> throw new IllegalStateException("лот " + l.lotId() + " открыт в законченных торгах");
            }
        }
        second.sort(Comparator.comparingLong(li -> secondIssued[li]));
        for (int li : second) {
            LotResult l = aw.lot(li + 1);
            Consignment c = aw.consignment(l.consignmentId());
            ln.add(l.lotId(), SECOND_CHANCE, money.sold(secondPrice[li], c.dealerDiscount(), c.commissionMilliPct(),
                    sale.format(), true, saleDate), payable(endDay, paidSecond[li]));
        }
        payouts(s);
    }

    private static boolean withdrawnByHouse(LotResult l) {
        return "house".equals(l.withdrawnBy());
    }

    /** M7: выплата положена с max(торги + 35 суток, оплата + 5 рабочих дней), 00:00 по Москве; 0 — лот не оплачен. */
    private long payable(LocalDate endDay, long paidAt) {
        if (paidAt == 0 || paidAt >= cut) return 0;
        LocalDate a = endDay.plusDays(HouseRules.PAYOUT_DAY);
        LocalDate b = workdaysAfter(MoneyTime.date(paidAt), HouseRules.PAYOUT_AFTER_PAYMENT_WORKDAYS);
        return MoneyTime.dayStart(a.isAfter(b) ? a : b);
    }

    /**
     * Выплаты отчёта: по дню выплаты (первый рабочий день не раньше положенного) — один платёж на отчёт. Минусы строк
     * (каталог непроданных, снятие) удерживаются из первой выплаты, которая их покрывает; пока не покрыты — плюсы ждут.
     */
    private void payouts(Stl s) {
        long debt = 0;
        List<Integer> neg = new ArrayList<>();
        TreeMap<Long, List<Integer>> byDay = new TreeMap<>();
        for (int i = s.from; i < ln.size; i++) {
            long net = ln.net[i];
            s.hammer += ln.price[i];
            s.commission += ln.com[i];
            s.fees += ln.ins[i] + ln.cat[i] + ln.wdr[i];
            s.vat += ln.vat[i];
            s.net += net;
            if (net < 0) {
                debt -= net;
                neg.add(i);
            } else if (net > 0) {
                // заложено: выплата раньше оплаты покупателем — у строк, которые покупатель оплатит (план, не срез)
                LotResult l = aw.lot(ln.lotId[i]);
                long model = ln.outcome[i] == SOLD ? paidSaleModel[ln.lotId[i] - 1] : 0;
                if (model != 0 && stlStream.unit(l.lotKey()) < payoutEarlyShare) {
                    long at = earlyPayout(s, model, stlStream.at(l.lotKey(), 1));
                    if (at != 0) {
                        if (at < cut) {
                            ln.paid[i] = at;
                            addPayout(s, at, net);
                            planted.add("payout_before_payment", l.lotKey());
                            stats.payoutEarly++;
                        }
                        continue;
                    }
                }
                if (ln.payable[i] == 0) continue;
                long day = workdayOnOrAfter(MoneyTime.date(ln.payable[i])).toEpochDay();
                byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(i);
            }
        }
        List<Integer> waiting = new ArrayList<>();
        long pos = 0;
        for (var e : byDay.entrySet()) {
            waiting.addAll(e.getValue());
            for (int i : e.getValue()) pos += ln.net[i];
            if (pos <= debt) continue;
            long at = MoneyTime.at(e.getKey(), 11 * 3600 + stlStream.at(s.saleKey, s.consignorId, e.getKey()).nextInt(6 * 3600));
            if (at >= cut) break;
            addPayout(s, at, pos - debt);
            for (int i : waiting) ln.paid[i] = at;
            for (int i : neg) ln.paid[i] = at;
            waiting.clear();
            neg.clear();
            pos = 0;
            debt = 0;
        }
        boolean allPaid = true;
        for (int i = s.from; i < ln.size; i++) if (ln.net[i] != 0 && ln.paid[i] == 0) allPaid = false;
        s.status = s.net < 0 ? "owed_by_consignor" : allPaid ? "paid" : s.paidTotal > 0 ? "part_paid" : "awaiting_buyers";
    }

    /** Заложенная выплата раньше оплаты: рабочий день после отчёта и до дня оплаты покупателем; 0 — не помещается. */
    private long earlyPayout(Stl s, long buyerPaidAt, Rng g) {
        long created = MoneyTime.epochDay(s.createdAt), payDay = MoneyTime.epochDay(buyerPaidAt);
        LocalDate ed = LocalDate.ofEpochDay(Math.max(created + 1, payDay - 1 - g.nextInt(4)));
        while (!ru.isWorking(ed) && ed.toEpochDay() > created) ed = ed.minusDays(1);
        if (ed.toEpochDay() <= created || ed.toEpochDay() >= payDay) return 0;
        return MoneyTime.at(ed.toEpochDay(), 12 * 3600 + g.nextInt(4 * 3600));
    }

    private void addPayout(Stl s, long at, long amount) {
        int n = s.payoutAt.length;
        s.payoutAt = Arrays.copyOf(s.payoutAt, n + 1);
        s.payoutAmount = Arrays.copyOf(s.payoutAmount, n + 1);
        s.payoutAt[n] = at;
        s.payoutAmount[n] = amount;
        s.paidTotal += amount;
    }

    private void writeSettlements(List<Stl> all) throws IOException {
        for (Stl s : all) {
            out.add("settlements", s.id, s.saleId, s.consignorId, s.createdAt, s.hammer, s.commission, s.fees, s.vat, s.net,
                    s.paidTotal, s.status);
            stats.settlements++;
            events.add(MoneyEvents.SETTLEMENT_READY, s.consignorId, s.saleId, 0, s.createdAt, s.id, 0);
        }
        out.close("settlements");
        for (Stl s : all) {
            Calendar.Sale sale = calendar.sale(s.saleId);
            for (int i = s.from, no = 1; i < s.to; i++, no++) {
                byte oc = ln.outcome[i];
                boolean hasPrice = oc == SOLD || oc == SOLD_AFTER_SALE || oc == SECOND_CHANCE;
                out.add("settlement_lines", s.id, no, ln.lotId[i], OUTCOME[oc], hasPrice ? ln.price[i] : null, ln.com[i],
                        ln.ins[i], ln.cat[i], ln.wdr[i], ln.vat[i], ln.net[i], ln.payable[i] == 0 ? null : ln.payable[i],
                        ln.paid[i] == 0 ? null : ln.paid[i]);
                stats.settlementLines++;
                LotResult l = aw.lot(ln.lotId[i]);
                long buyerPaid = switch (oc) {
                    case SOLD -> paidSale[l.lotId() - 1];
                    case SOLD_AFTER_SALE -> paidAfter[l.lotId() - 1];
                    case SECOND_CHANCE -> paidSecond[l.lotId() - 1];
                    default -> 0;
                };
                audit.settlementLine(s, no, l, aw.consignment(l.consignmentId()), sale, OUTCOME[oc], ln.price[i],
                        ln.com[i], ln.ins[i], ln.cat[i], ln.wdr[i], ln.vat[i], ln.net[i], ln.payable[i], ln.paid[i],
                        buyerPaid, planted.has("payout_before_payment", l.lotKey()), withdrawnByHouse(l));
            }
            audit.settlement(s, ln, sale);
            for (int k = 0; k < s.payoutAt.length; k++) {
                int row = pays.add(PayRows.PAYOUT, s.payoutAt[k], s.payoutAmount[k], s.consignorId, PayMethod.BANK_TRANSFER,
                        0, 0, s.id, true, null, 0, PayRows.OWNER_SETTLEMENT, s.id, k, false);
                events.payment(row, MoneyEvents.SETTLEMENT_PAID, s.consignorId, s.saleId, 0, s.payoutAt[k]);
                stats.payouts++;
            }
        }
        out.close("settlement_lines");
    }

    // ---------------------------------------------------------------- платежи

    private void writePayments() throws IOException {
        Integer[] order = pays.order();
        for (Integer i : order) {
            out.add("payments", pays.id[i], pays.client[i], PayRows.KIND[pays.kind[i]], pays.method(i).code(),
                    pays.invoice[i] == 0 ? null : pays.invoice[i], pays.registration[i] == 0 ? null : pays.registration[i],
                    pays.settlement[i] == 0 ? null : pays.settlement[i], pays.amount[i], pays.at[i],
                    pays.ok[i] ? "succeeded" : "failed", pays.failure(i), pays.providerRef(i));
        }
        out.close("payments");
        audit.payments(pays);
        events.paymentIds(pays.id);
    }
}
