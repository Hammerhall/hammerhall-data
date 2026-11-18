package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.auction.engine.Codes;
import io.hammerhall.data.auc.auction.engine.LotRows;
import io.hammerhall.data.auc.auction.outcome.ClientLedger;
import io.hammerhall.data.auc.auction.outcome.ItemLog;
import io.hammerhall.data.auc.bid.IncrementTable;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.InvoiceKind;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.LotResult;
import io.hammerhall.data.auc.world.LotResultCode;
import io.hammerhall.data.auc.world.PaymentPlanner;
import io.hammerhall.data.auc.world.Registrations;
import io.hammerhall.data.auc.world.SalePlan;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Исход лотов торгов: продано или нет, продажа после торгов, счета и их планы оплаты
 * (функцией модуля денег), отказ на 31-й день и второй шанс, недостача неплательщику после повторной продажи дешевле,
 * состояние клиентов (блокировка, оплаченные счета, просрочка), журнал вещи и решение подготовки «повтор или возврат».
 * Моделирование — без среза; срез — при записи. Случайность — поток {@code auction.outcome} от ключей лота и счёта.
 */
final class Outcomes {
    /** Доля продаж после торгов среди непроданных: со ставками ниже резерва и без ставок (в сумме ≈ 8 %). */
    static final double AFTER_SALE_WITH_BIDS = 0.16, AFTER_SALE_NO_BIDS = 0.04;
    static final long HOUR = 3_600, DAY = 86_400;

    private final HouseRules house;
    private final Prep prep;
    private final PaymentPlanner planner;
    private final ClientLedger ledger;
    private final ItemLog itemLog;
    private final RandomStreams.Stream stream;
    private final IncrementTable inc;
    private final long cut;
    /** Счета до среза — для мира (сортируются в конце). */
    final List<AuctionWorld.InvoicePlan> invoices = new ArrayList<>();
    /** Отказ без второго шанса: вещь → кто не заплатил (для недостачи после повторной продажи). */
    private final Map<Integer, Default> defaults = new HashMap<>();
    long stAfterSales, stInvoices, stDefaults, stSecondChance, stShortfalls;
    /** Коридор «непроданное»: исход лотов, не проданных с молотка, закрытых до среза. */
    io.hammerhall.data.auc.auction.audit.Corridors corridors;

    private record Default(long lotKey, int saleId, Format format, Dept dept, LocalDate saleDate, long regKey,
                           int clientId, long priceKop, long invoiceKey) {
    }

    Outcomes(HouseRules house, Prep prep, PaymentPlanner planner, ClientLedger ledger, ItemLog itemLog,
             RandomStreams.Stream stream, long cut) {
        this.house = house;
        this.prep = prep;
        this.planner = planner;
        this.ledger = ledger;
        this.itemLog = itemLog;
        this.stream = stream;
        this.inc = house.increments();
        this.cut = cut;
    }

    // ---------------------------------------------------------------- торги

    void process(SaleRun r) {
        Calendar.Sale s = r.sale;
        LotRows rows = r.rows;
        Registrations regs = prep.registrations();
        // 1. итоги: продан, не продан (продажа после торгов), снят
        for (int i = 0; i < r.lots; i++) {
            SalePlan.Lot l = r.lot(i);
            byte res = rows.result[i];
            r.result[i] = res;
            if (res == Codes.RES_SOLD) {
                r.price[i] = rows.hammer[i];
                r.buyerReg[i] = rows.topReg[i];
                r.buyerClient[i] = rows.topClient[i];
            } else if (res == Codes.RES_BOUGHT_IN) {
                afterSale(r, i, l, regs);
            }
        }
        // 2. счета по итогам торгов: один на покупателя
        Map<Integer, List<Integer>> byBuyer = new TreeMap<>();
        for (int i = 0; i < r.lots; i++) {
            if (r.result[i] == Codes.RES_SOLD) byBuyer.computeIfAbsent(r.buyerClient[i], k -> new ArrayList<>()).add(i);
        }
        long saleEnd = saleEndSec(r);
        for (Map.Entry<Integer, List<Integer>> e : byBuyer.entrySet()) {
            int client = e.getKey();
            List<Integer> ls = e.getValue();
            long regKey = r.buyerReg[ls.get(0)];
            long key = Keys.invoice(s.id(), client, InvoiceKind.SALE, 0);
            Rng rnd = stream.at(key);
            long issued = s.live() ? sessionEndSec(r, ls) + 1_200 + rnd.nextInt(4_200) : saleEnd + HOUR + rnd.nextInt(7_200);
            List<PaymentPlanner.Line> lines = new ArrayList<>();
            for (int i : ls) lines.add(new PaymentPlanner.Line(r.lot(i).key(), r.price[i], r.platform[i]));
            Registrations.Registration reg = regs.get(regKey);
            long deposit = reg == null ? 0 : reg.depositKop();
            PaymentPlanner.PaymentPlan p = invoice(key, InvoiceKind.SALE, s, regKey, client, issued, lines, deposit, 0);
            for (int i : ls) settle(r, i, p, key, regKey, client, true);
        }
        // 3. счета продаж после торгов — по лоту
        Map<Integer, Integer> afterNo = new TreeMap<>();
        for (int i = 0; i < r.lots; i++) {
            if (r.result[i] != Codes.RES_SOLD_AFTER_SALE) continue;
            int client = r.afterSaleClient[i];
            int no = afterNo.merge(client, 1, Integer::sum) - 1;
            long key = Keys.invoice(s.id(), client, InvoiceKind.AFTER_SALE, no);
            Rng rnd = stream.at(key);
            long issued = r.afterSaleAt[i] + 600 + rnd.nextInt(6_600);
            PaymentPlanner.PaymentPlan p = invoice(key, InvoiceKind.AFTER_SALE, s, r.afterSaleReg[i], client, issued,
                    List.of(new PaymentPlanner.Line(r.lot(i).key(), r.price[i], false)), 0, 0);
            settle(r, i, p, key, r.afterSaleReg[i], client, false);
        }
        // 4. журнал вещи, недостача, итог лота подготовке торгов — по лотам
        for (int i = 0; i < r.lots; i++) finishLot(r, i);
    }

    /** Продажа после торгов: предложение не ниже резерва в течение 7 дней — от наибольшей ставки или участника торгов. */
    private void afterSale(SaleRun r, int i, SalePlan.Lot l, Registrations regs) {
        LotRows rows = r.rows;
        Rng rnd = stream.at(l.key(), 1);
        boolean hasBids = rows.topReg[i] != 0;
        if (!rnd.chance(hasBids ? AFTER_SALE_WITH_BIDS : AFTER_SALE_NO_BIDS)) return;
        long reg;
        int client;
        if (hasBids) {
            reg = rows.topReg[i];
            client = rows.topClient[i];
        } else {
            List<Registrations.Registration> rs = regs.ofSale(r.sale.id());
            List<Registrations.Registration> ok = new ArrayList<>();
            for (Registrations.Registration x : rs) if (x.approved() && x.clientId() != l.consignorId()) ok.add(x);
            if (ok.isEmpty()) return;
            Registrations.Registration x = ok.get(rnd.nextInt(ok.size()));
            reg = x.key();
            client = x.clientId();
        }
        long base = Math.max(l.reserveKop(), l.startKop());
        long price = base;
        if (rnd.chance(0.35)) price = inc.minNext(price);
        long closed = rows.closedMs[i] / 1000;
        r.result[i] = Codes.RES_SOLD_AFTER_SALE;
        r.afterSaleAt[i] = closed + HOUR + rnd.nextInt((int) (7 * DAY - HOUR));
        r.afterSalePrice[i] = price;
        r.afterSaleReg[i] = reg;
        r.afterSaleClient[i] = client;
        r.price[i] = price;
        r.buyerReg[i] = reg;
        r.buyerClient[i] = client;
        stAfterSales++;
    }

    /** Счёт: черновик → план оплаты (модуль денег) → состояние клиента; в мир — если выставлен до среза. */
    private PaymentPlanner.PaymentPlan invoice(long key, InvoiceKind kind, Calendar.Sale s, long regKey, int client,
                                               long issued, List<PaymentPlanner.Line> lines, long deposit, long related) {
        boolean newClient = ledger.paidBefore(client, issued) < HouseRules.PAID_INVOICES_FOR_NO_LIMIT;
        long due = HouseRules.dueAt(issued, s.paymentDueDays());
        PaymentPlanner.InvoiceDraft d = new PaymentPlanner.InvoiceDraft(key, kind, s.id(), s.format(), s.dept(),
                s.saleDate(), regKey, client, newClient, issued, due, lines, deposit, related);
        PaymentPlanner.PaymentPlan p = planner.plan(d);
        stInvoices++;
        if (p.paidAt() > 0) ledger.paid(client, p.paidAt());
        if (p.paidAt() == 0 || p.paidAt() > due) ledger.overdue(client, due, p.paidAt() > 0 ? p.paidAt() : p.cancelAt());
        if (p.defaults()) {
            ledger.block(client, p.cancelAt(), ClientLedger.BR_NON_PAYMENT);
            stDefaults++;
        }
        long total = 0;
        for (PaymentPlanner.Line l : lines) total += l.priceKop();
        if (kind != InvoiceKind.SHORTFALL && total >= HouseRules.ENHANCED_CHECK_FROM) ledger.enhanced(client, issued);
        if (issued < cut) invoices.add(new AuctionWorld.InvoicePlan(d, p));
        return p;
    }

    /** Оплата или отказ по лоту; при отказе — второй шанс (по строке, если вторая ставка не ниже резерва). */
    private void settle(SaleRun r, int i, PaymentPlanner.PaymentPlan p, long invoiceKey, long regKey, int client,
                        boolean secondChancePossible) {
        SalePlan.Lot l = r.lot(i);
        LotRows rows = r.rows;
        long lotKey = l.key();
        if (p.paidAt() > 0) {
            r.paidAt[i] = p.paidAt();
            return;
        }
        if (!p.defaults()) return;
        r.buyerDefault[i] = true;
        r.defaultAt[i] = p.cancelAt();
        long sc = rows.secondReg[i];
        long scAmount = rows.secondAmount[i];
        if (secondChancePossible && p.secondChanceOffered() && p.secondChanceAccepted() && sc != 0
                && scAmount >= l.reserveKop() && scAmount > 0) {
            int scClient = rows.secondClient[i];
            long key = Keys.invoice(r.sale.id(), scClient, InvoiceKind.SECOND_CHANCE,
                    next(numbers, (long) r.sale.id() << 32 | scClient, InvoiceKind.SECOND_CHANCE));
            Rng rnd = stream.at(key);
            long issued = p.cancelAt() + DAY + rnd.nextInt((int) (2 * DAY));
            PaymentPlanner.PaymentPlan q = invoice(key, InvoiceKind.SECOND_CHANCE, r.sale, sc, scClient, issued,
                    List.of(new PaymentPlanner.Line(lotKey, scAmount, false)), 0, invoiceKey);
            r.scClient[i] = scClient;
            r.scPrice[i] = scAmount;
            scIssued.put(lotKey, issued);
            scPlans.put(lotKey, q);
            stSecondChance++;
            if (q.paidAt() > 0) {
                r.paidAt[i] = q.paidAt();
                return;
            }
            if (q.defaults()) r.freeAt[i] = q.cancelAt();
            return;
        }
        r.freeAt[i] = p.cancelAt();
        // недостача — только по молотку (счёт торгов): сравнивается с молотком ближайшего повторного выставления
        if (secondChancePossible) defaults.put(l.itemId(), new Default(lotKey, r.sale.id(), r.sale.format(), r.sale.dept(), r.sale.saleDate(),
                regKey, client, r.price[i], invoiceKey));
    }

    /** Второй шанс по лоту: выставлен, план (для журнала вещи). */
    private final Map<Long, Long> scIssued = new HashMap<>();
    private final Map<Long, PaymentPlanner.PaymentPlan> scPlans = new HashMap<>();
    /** Номера счетов вида у клиента в торгах ((торги, клиент) → следующий номер; второй шанс и недостача). */
    private final Map<Long, int[]> numbers = new HashMap<>();

    private static int next(Map<Long, int[]> m, long saleClient, InvoiceKind kind) {
        int[] c = m.computeIfAbsent(saleClient, k -> new int[InvoiceKind.values().length]);
        int n = c[kind.ordinal()]++;
        if (n > 255) throw new IllegalStateException("больше 256 счетов вида " + kind.code() + " у клиента в торгах");
        return n;
    }

    private void finishLot(SaleRun r, int i) {
        SalePlan.Lot l = r.lot(i);
        LotRows rows = r.rows;
        long lotKey = l.key();
        int item = l.itemId();
        int cons = l.consignmentId();
        AuctionWorld.Consignment c = cons == 0 ? null : prep.consignment(cons);
        if (c != null && "resale".equals(c.kind()) && l.offeringNo() == 1) {
            itemLog.add(item, c.signedAt(), Codes.IS_RESALE_RECEIVED, 0, cons);
        }
        itemLog.add(item, l.publishedAt(), Codes.IS_CATALOGUED, lotKey, cons);
        byte res = r.result[i];
        long closed = rows.closedMs[i] / 1000;
        if (res == Codes.RES_WITHDRAWN) {
            itemLog.add(item, l.withdrawnAt(), Codes.IS_WITHDRAWN, lotKey, cons);
            r.freeAt[i] = l.withdrawnAt();
        } else if (res == Codes.RES_BOUGHT_IN) {
            itemLog.add(item, closed, Codes.IS_BOUGHT_IN, lotKey, cons);
            r.freeAt[i] = closed;
        } else {
            if (res == Codes.RES_SOLD) {
                itemLog.add(item, closed, Codes.IS_SOLD, lotKey, cons);
            } else {
                itemLog.add(item, closed, Codes.IS_BOUGHT_IN, lotKey, cons);
                itemLog.add(item, r.afterSaleAt[i], Codes.IS_AFTER_SALE, lotKey, cons);
            }
            paymentChain(r, i, item, lotKey, cons);
        }
        shortfall(r, i, l);
        LotResult lr = modelResult(r, i);
        Prep.Disposition d = prep.result(lr);
        if (corridors != null && (res == Codes.RES_BOUGHT_IN || res == Codes.RES_SOLD_AFTER_SALE) && closed < cut) {
            corridors.unsold(res == Codes.RES_SOLD_AFTER_SALE, d.kind() == Prep.Disposition.Kind.REOFFER,
                    d.kind() == Prep.Disposition.Kind.RETURN);
        }
        if (d.kind() == Prep.Disposition.Kind.REOFFER) itemLog.add(item, d.at(), Codes.IS_REOFFER_PLANNED, lotKey, cons);
        else if (d.kind() == Prep.Disposition.Kind.RETURN) itemLog.add(item, d.at(), Codes.IS_RETURNED, lotKey, cons);
    }

    /** Журнал вещи после продажи: оплата и передача, или отказ (и второй шанс). */
    private void paymentChain(SaleRun r, int i, int item, long lotKey, int cons) {
        Rng rnd = stream.at(lotKey, 2);
        if (r.buyerDefault[i]) {
            itemLog.add(item, r.defaultAt[i], Codes.IS_BUYER_DEFAULT, lotKey, cons);
            if (r.scClient[i] != 0) {
                itemLog.add(item, scIssued.remove(lotKey), Codes.IS_AFTER_SALE, lotKey, cons);
                PaymentPlanner.PaymentPlan q = scPlans.remove(lotKey);
                if (q.paidAt() > 0) {
                    itemLog.add(item, q.paidAt(), Codes.IS_BUYER_PAID, lotKey, cons);
                    itemLog.add(item, q.paidAt() + DAY + rnd.nextInt((int) (13 * DAY)), Codes.IS_RELEASED, lotKey, cons);
                } else if (q.defaults()) {
                    itemLog.add(item, q.cancelAt(), Codes.IS_BUYER_DEFAULT, lotKey, cons);
                }
            }
        } else if (r.paidAt[i] > 0) {
            itemLog.add(item, r.paidAt[i], Codes.IS_BUYER_PAID, lotKey, cons);
            itemLog.add(item, r.paidAt[i] + DAY + rnd.nextInt((int) (13 * DAY)), Codes.IS_RELEASED, lotKey, cons);
        }
    }

    /**
     * Ближайшее повторное выставление вещи неплательщика: продано с молотка дешевле — недостача ему же
     * (счёт {@code shortfall}, разница молотков); не продано или не дешевле — недостачи нет.
     */
    private void shortfall(SaleRun r, int i, SalePlan.Lot l) {
        Default d = defaults.get(l.itemId());
        if (d == null || d.lotKey() == l.key()) return;
        defaults.remove(l.itemId());
        if (r.result[i] != Codes.RES_SOLD || r.price[i] >= d.priceKop()) return;
        long key = Keys.invoice(d.saleId(), d.clientId(), InvoiceKind.SHORTFALL,
                next(numbers, (long) d.saleId() << 32 | d.clientId(), InvoiceKind.SHORTFALL));
        Rng rnd = stream.at(key);
        long issued = r.rows.closedMs[i] / 1000 + 2 * DAY + rnd.nextInt((int) (5 * DAY));
        Calendar.Sale orig = prep.calendar().sale(d.saleId());
        invoice(key, InvoiceKind.SHORTFALL, orig, d.regKey(), d.clientId(), issued,
                List.of(new PaymentPlanner.Line(d.lotKey(), d.priceKop() - r.price[i], false)), 0, d.invoiceKey());
        stShortfalls++;
    }

    /** Модельный итог лота (без среза) — подготовке торгов для решения «повтор или возврат». */
    LotResult modelResult(SaleRun r, int i) {
        SalePlan.Lot l = r.lot(i);
        LotRows rows = r.rows;
        LotResultCode code = switch (r.result[i]) {
            case Codes.RES_SOLD -> LotResultCode.SOLD;
            case Codes.RES_SOLD_AFTER_SALE -> LotResultCode.SOLD_AFTER_SALE;
            case Codes.RES_WITHDRAWN -> LotResultCode.WITHDRAWN;
            default -> LotResultCode.BOUGHT_IN;
        };
        boolean sold = code == LotResultCode.SOLD;
        return new LotResult(l.key(), r.lotId[i], r.sale.id(), l.itemId(), l.consignmentId(), r.sale.dept(),
                r.sale.format(), l.offeringNo(), code, r.price[i], l.lowKop(), l.highKop(), l.reserveKop(),
                code == LotResultCode.WITHDRAWN ? 0 : rows.closedMs[i] / 1000, r.buyerReg[i], r.buyerClient[i],
                sold ? rows.secondReg[i] : 0, sold ? rows.secondAmount[i] : 0, r.buyerDefault[i], r.defaultAt[i],
                r.scClient[i], r.scPrice[i], r.paidAt[i], r.freeAt[i], l.withdrawnAt(), l.withdrawnBy());
    }

    // ---------------------------------------------------------------- время торгов

    /** Конец торгов — последний закрытый лот (секунды); у торгов без лотов — начало. */
    static long saleEndSec(SaleRun r) {
        long end = 0;
        for (int i = 0; i < r.lots; i++) if (r.rows.result[i] != Codes.RES_WITHDRAWN) end = Math.max(end, r.rows.closedMs[i]);
        return end == 0 ? r.sale.startsAt() : end / 1000;
    }

    /** Конец сессии, в которой продан последний (по номеру) лот покупателя, — секунды. */
    private static long sessionEndSec(SaleRun r, List<Integer> lots) {
        int session = 0;
        for (int i : lots) session = Math.max(session, r.lot(i).sessionNo());
        long end = 0;
        for (int i = 0; i < r.lots; i++) {
            if (r.lot(i).sessionNo() == session && r.rows.result[i] != Codes.RES_WITHDRAWN) end = Math.max(end, r.rows.closedMs[i]);
        }
        return end / 1000;
    }

    /** Концы сессий живых торгов (секунды; 0 — в сессии нет лотов). */
    static long[] sessionEnds(SaleRun r) {
        int n = r.plan.sessions().size();
        long[] out = new long[n];
        for (int i = 0; i < r.lots; i++) {
            int s = r.lot(i).sessionNo();
            if (s < 1 || s > n || r.rows.result[i] == Codes.RES_WITHDRAWN) continue;
            out[s - 1] = Math.max(out[s - 1], r.rows.closedMs[i] / 1000);
        }
        return out;
    }

    HouseRules house() {
        return house;
    }
}
