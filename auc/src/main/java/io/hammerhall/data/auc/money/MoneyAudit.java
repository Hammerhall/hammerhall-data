package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.money.PaymentPlanner.Detail;
import io.hammerhall.data.auc.money.PaymentPlanner.Doc;
import io.hammerhall.data.auc.world.AuctionWorld.Consignment;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.HouseRules.FeeSchedule;
import io.hammerhall.data.auc.world.HouseRules.FeeTier;
import io.hammerhall.data.auc.world.InvoiceKind;
import io.hammerhall.data.auc.world.LotResult;
import io.hammerhall.data.auc.world.LotResultCode;
import io.hammerhall.data.auc.world.PaymentPlanner.InvoiceDraft;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.money.Money;
import io.hammerhall.data.core.time.RuCalendar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Самопроверка денег (M1–M7) — пересчёт своими формулами ({@link BigDecimal} по ступеням шкал, ставки НДС
 * числами), не кодом, которым суммы считались. Заложенные отклонения ({@code premium_old_schedule},
 * {@code payout_before_payment}, {@code duplicate_payment}) пропускаются ровно они; их число — в журнал. Коридоры
 * (оплата, отказы, второй шанс) — по счетам, чей исход окончателен к срезу.
 */
final class MoneyAudit {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final LocalDate VAT22 = LocalDate.of(2026, 1, 1);

    private final HouseRules house;
    private final RuCalendar ru;
    private final long cut;
    private final double scale;
    private final Map<Integer, FeeSchedule> scheduleById = new TreeMap<>();
    private final Map<String, List<String>> violations = new TreeMap<>();
    private final Map<String, Long> counts = new TreeMap<>();
    private final Map<String, Long> skipped = new TreeMap<>();

    // по счёту (id − 1): записанное — для сверки с платежами
    private long[] invTotalDue = new long[1024], invPaidTotal = new long[1024], invPaidAt = new long[1024],
            invIssued = new long[1024];
    private int invCount;

    // коридоры
    private long finalCount, paid2, paid5, paid10, paid30;
    private final long[] grp = new long[3], grpDef = new long[3];
    private long defaultsSale, offered, accepted;

    // депозиты
    private final List<Deposit> deposits = new ArrayList<>();

    MoneyAudit(HouseRules house, RuCalendar ru, long cut, double scale) {
        this.house = house;
        this.ru = ru;
        this.cut = cut;
        this.scale = scale;
        for (FeeSchedule f : house.fees()) scheduleById.put(f.id(), f);
    }

    void fail(String check, String example) {
        counts.merge(check, 1L, Long::sum);
        List<String> l = violations.computeIfAbsent(check, k -> new ArrayList<>());
        if (l.size() < 5) l.add(example);
    }

    private void expect(boolean ok, String check, String example) {
        counts.merge(check, 0L, Long::sum);
        if (!ok) fail(check, example);
    }

    private void skip(String code) {
        skipped.merge(code, 1L, Long::sum);
    }

    private static String rub(long kop) {
        return Money.format(kop);
    }

    // ---------------------------------------------------------------- независимые формулы

    static BigDecimal r(long kop) {
        return BigDecimal.valueOf(kop, 2);
    }

    static long kop(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).unscaledValue().longValueExact();
    }

    /** Ставка, % — из тысячных долей. */
    private static BigDecimal pct(int milli) {
        return BigDecimal.valueOf(milli, 3);
    }

    /** Сумма по ступеням: ставка ступени (минус скидка, п. п.) — на часть от начала ступени (не включая) до следующей. */
    static long byTiers(List<FeeTier> tiers, long amountKop, BigDecimal discountPct) {
        BigDecimal a = r(amountKop), acc = BigDecimal.ZERO;
        for (int i = 0; i < tiers.size(); i++) {
            BigDecimal lo = r(tiers.get(i).fromKop());
            BigDecimal hi = i + 1 < tiers.size() ? r(tiers.get(i + 1).fromKop()) : null;
            if (a.compareTo(lo) <= 0) break;
            BigDecimal part = (hi == null || a.compareTo(hi) < 0 ? a : hi).subtract(lo);
            BigDecimal rate = pct(tiers.get(i).rateMilliPct()).subtract(discountPct).max(BigDecimal.ZERO);
            acc = acc.add(part.multiply(rate));
        }
        return kop(acc.divide(HUNDRED, 10, RoundingMode.HALF_UP));
    }

    static long percentOf(long kop, BigDecimal ratePct) {
        return kop(r(kop).multiply(ratePct).divide(HUNDRED, 10, RoundingMode.HALF_UP));
    }

    static int vatPct(LocalDate saleDate) {
        return saleDate.isBefore(VAT22) ? 20 : 22;
    }

    private long premiumOf(FeeSchedule s, long price) {
        return s.tiered() ? byTiers(s.tiers(), price, BigDecimal.ZERO) : percentOf(price, pct(s.rateMilliPct()));
    }

    // ---------------------------------------------------------------- счета (M1–M3, M6)

    void invoiceLine(int invoiceId, String number, int lineNo, LotResult lot, Calendar.Sale sale, InvoiceKind kind,
                     MoneyRules.Amounts a, int i, boolean plantedOld) {
        String at = number + "/" + lineNo;
        FeeSchedule s = scheduleById.get(sale.premiumScheduleId());
        long price = a.price()[i];
        long want = kind == InvoiceKind.SHORTFALL ? 0 : premiumOf(s, price);
        boolean premOk = want == a.premium()[i] && a.scheduleId() == sale.premiumScheduleId();
        if (!premOk && plantedOld) {
            skip("premium_old_schedule");
        } else {
            expect(premOk, "M1 премия по шкале торгов", at + ": премия " + rub(a.premium()[i]) + ", по шкале "
                    + s.code() + " — " + rub(want));
        }
        long plat = a.platformFee()[i];
        expect(plat == 0 || (sale.format() == Format.LIVE && kind != InvoiceKind.SHORTFALL
                && plat == percentOf(price, new BigDecimal("3.5"))), "сбор площадки 3,5 % (живые)", at + ": " + rub(plat));
        int vp = vatPct(sale.saleDate());
        expect(a.vatPct() == vp, "НДС по дате торгов", at + ": " + a.vatPct() + " %, на " + sale.saleDate() + " — " + vp);
        long vat = percentOf(a.premium()[i] + plat, BigDecimal.valueOf(vp));
        expect(vat == a.vat()[i], "M2 НДС строки", at + ": " + rub(a.vat()[i]) + " ≠ " + rub(vat));
        expect(a.lineTotal()[i] == price + a.premium()[i] + plat + a.vat()[i], "M3 итог строки", at);
        // деньги ↔ лоты
        switch (kind) {
            case SALE, REISSUE -> expect(price == lot.priceKop(), "цена строки = молоток", at + ": " + rub(price) + " ≠ "
                    + rub(lot.priceKop()));
            case AFTER_SALE -> expect(lot.result() == LotResultCode.SOLD_AFTER_SALE && price == lot.priceKop(),
                    "продажа после торгов = цена лота", at);
            case SECOND_CHANCE -> expect(price == lot.secondChancePriceKop() && price >= lot.reserveKop(),
                    "второй шанс — последняя ставка второго, не ниже резерва", at + ": " + rub(price));
            case SHORTFALL -> expect(price > 0 && price < lot.priceKop(), "недостача меньше первоначальной цены", at);
        }
    }

    void invoice(int id, String number, Doc doc, Detail x, InvoiceDraft d, Calendar.Sale sale, int regId, long paidTotal,
                 long paidAt, long cancelledAt, String cancelReason, long lateFee, String status, long cut, boolean pay) {
        MoneyRules.Amounts a = doc.amounts();
        long h = 0, p = 0, f = 0, v = 0, t = 0;
        for (int i = 0; i < a.lines(); i++) {
            h += a.price()[i];
            p += a.premium()[i];
            f += a.platformFee()[i];
            v += a.vat()[i];
            t += a.lineTotal()[i];
        }
        expect(h == a.hammerTotal() && p == a.premiumTotal() && f == a.platformTotal() && v == a.vatTotal()
                && t == a.total(), "M3 итоги счёта = суммы строк", number);
        expect(regId > 0, "счёт — по регистрации покупателя", number);
        LocalDate issue = MoneyTime.date(doc.issuedAt());
        expect(MoneyTime.date(doc.dueAt()).equals(issue.plusDays(sale.paymentDueDays()))
                        && (doc.dueAt() + MoneyTime.MSK + 1) % MoneyTime.DAY == 0, "срок оплаты — 23:59:59 дня +10",
                number);
        long lateAt = paidAt != 0 ? paidAt : cancelledAt != 0 ? cancelledAt : cut;
        long days = Math.max(0, MoneyTime.epochDay(lateAt) - MoneyTime.epochDay(doc.dueAt()));
        long wantLate = percentOf(a.total(), new BigDecimal("0.1").multiply(BigDecimal.valueOf(days)));
        expect(wantLate == lateFee, "M6 пени", number + ": " + rub(lateFee) + " ≠ " + rub(wantLate));
        expect((paidAt != 0) == (paidTotal == a.total() + lateFee) || cancelledAt != 0, "M6 оплачен ⇔ оплачено всё", number);
        String want = cancelledAt != 0 ? "cancelled" : paidAt != 0 ? "paid" : doc.dueAt() < cut ? "overdue"
                : paidTotal > 0 ? "part_paid" : "issued";
        expect(want.equals(status), "статус счёта на срез", number + ": " + status + " ≠ " + want);
        if ("non_payment".equals(cancelReason)) {
            expect(MoneyTime.epochDay(cancelledAt) - MoneyTime.epochDay(doc.issuedAt()) == 31, "отмена non_payment — 31-й день",
                    number);
        }
        if (paidAt != 0) {
            long day = MoneyTime.epochDay(paidAt) - MoneyTime.epochDay(doc.issuedAt());
            expect(day >= 0 && day <= 30, "полная оплата — к 30-му дню", number + ": день " + day);
        }
        if (!pay) return;
        // для сверки с платежами
        if (id > invTotalDue.length) {
            int c = invTotalDue.length * 2;
            invTotalDue = java.util.Arrays.copyOf(invTotalDue, c);
            invPaidTotal = java.util.Arrays.copyOf(invPaidTotal, c);
            invPaidAt = java.util.Arrays.copyOf(invPaidAt, c);
            invIssued = java.util.Arrays.copyOf(invIssued, c);
        }
        invCount = Math.max(invCount, id);
        invTotalDue[id - 1] = a.total() + lateFee;
        invPaidTotal[id - 1] = paidTotal;
        invPaidAt[id - 1] = paidAt;
        invIssued[id - 1] = doc.issuedAt();
        // коридоры — счета, чей исход окончателен к срезу
        if (doc.kind() == InvoiceKind.SHORTFALL || doc.issuedAt() >= cut - 32 * MoneyTime.DAY) return;
        finalCount++;
        int g = d.newClient() ? 2 : d.format() == Format.LIVE ? 0 : 1;
        grp[g]++;
        if (x.defaults()) {
            grpDef[g]++;
            if (d.kind() == InvoiceKind.SALE) {
                defaultsSale++;
                if (x.secondChanceOffered()) offered++;
                if (x.secondChanceAccepted()) accepted++;
            }
        } else {
            long day = MoneyTime.epochDay(x.paidAt()) - MoneyTime.epochDay(doc.issuedAt());
            if (day <= 2) paid2++;
            if (day <= 5) paid5++;
            if (day <= 10) paid10++;
            if (day <= 30) paid30++;
        }
    }

    // ---------------------------------------------------------------- платежи

    void payments(PayRows pr) {
        long[] sum = new long[invCount];
        long[] last = new long[invCount];
        boolean[] dup = new boolean[invCount];
        TreeMap<Long, Integer> refs = new TreeMap<>();
        long dupRefs = 0;
        for (int i = 0; i < pr.size; i++) {
            expect(pr.at[i] < cut, "платежи — до среза", "строка " + i);
            expect(pr.ok[i] == (pr.failure[i] < 0), "причина — только у неудачных", "платёж " + pr.id[i]);
            if (pr.ref[i] != 0) {
                Integer prev = refs.put(pr.ref[i], i);
                if (prev != null) {
                    if (pr.duplicate[i] || pr.duplicate[prev]) {
                        dupRefs++;
                        skip("duplicate_payment");
                    } else {
                        fail("номер провайдера уникален", "платежи " + pr.id[prev] + " и " + pr.id[i]);
                    }
                }
            }
            int inv = pr.invoice[i];
            if (inv == 0 || inv > invCount) continue;
            byte k = pr.kind[i];
            if (k == PayRows.INVOICE || k == PayRows.DEPOSIT_APPLIED || k == PayRows.REFUND) {
                expect(pr.at[i] > invIssued[inv - 1], "платёж — после выставления счёта", "платёж " + pr.id[i]);
            }
            if (!pr.ok[i]) continue;
            if (k == PayRows.INVOICE || k == PayRows.DEPOSIT_APPLIED) {
                sum[inv - 1] += pr.amount[i];
                last[inv - 1] = Math.max(last[inv - 1], pr.at[i]);
            } else if (k == PayRows.REFUND) {
                sum[inv - 1] -= pr.amount[i];
            }
            if (pr.duplicate[i]) dup[inv - 1] = true;
        }
        for (int j = 0; j < invCount; j++) {
            if (invIssued[j] == 0) continue;
            boolean ok = sum[j] == invPaidTotal[j];
            if (!ok && dup[j]) {
                skip("duplicate_payment (оплачено больше)");
                continue;
            }
            expect(ok, "M6 оплачено = Σ платежей и депозита − возвраты", "счёт " + (j + 1) + ": " + rub(invPaidTotal[j])
                    + " ≠ " + rub(sum[j]));
            if (invPaidAt[j] != 0) {
                expect(last[j] == invPaidAt[j] || dup[j], "M6 paid_at — последний платёж", "счёт " + (j + 1));
            }
        }
        counts.merge("дублей номера провайдера (заложено)", 0L, Long::sum);
        if (dupRefs == 0) counts.remove("дублей номера провайдера (заложено)");
    }

    // ---------------------------------------------------------------- депозиты

    /** Депозит регистрации и его судьба — ровно одна. */
    final class Deposit {
        final int registrationId, saleId;
        final long amount, at;
        int fates;
        String fate;

        Deposit(int registrationId, int saleId, long amount, long at) {
            this.registrationId = registrationId;
            this.saleId = saleId;
            this.amount = amount;
            this.at = at;
        }

        void fate(String kind, long when) {
            fates++;
            fate = kind;
            expect(when > at, "судьба депозита — после внесения", "регистрация " + registrationId);
        }
    }

    Deposit deposit(int registrationId, int saleId, long amount, long at) {
        Deposit d = new Deposit(registrationId, saleId, amount, at);
        deposits.add(d);
        return d;
    }

    // ---------------------------------------------------------------- отчёты комитентам (M4, M5, M7)

    void settlementLine(MoneyBook.Stl s, int no, LotResult l, Consignment c, Calendar.Sale sale, String outcome,
                        long price, long com, long ins, long cat, long wdr, long vat, long net, long payable, long paid,
                        long buyerPaid, boolean plantedEarly, boolean byHouse) {
        String at = "отчёт " + s.id + "/" + no;
        boolean sold = outcome.equals("sold") || outcome.equals("sold_after_sale") || outcome.equals("second_chance");
        long wantCom = 0, wantIns = 0, wantCat = 0, wantWdr = 0;
        BigDecimal catFee = sale.format() == Format.LIVE ? new BigDecimal("400") : new BigDecimal("150");
        if (sold) {
            wantCom = c.commissionMilliPct() != null ? percentOf(price, pct(c.commissionMilliPct()))
                    : byTiers(house.fee(HouseRules.SC_2024).tiers(), price, c.dealerDiscount() ? new BigDecimal("5") : BigDecimal.ZERO);
            wantIns = percentOf(price, new BigDecimal("1.5"));
            wantCat = kop(catFee);
        } else if (outcome.equals("bought_in")) {
            wantCat = kop(catFee);
        } else if (outcome.equals("withdrawn") && !byHouse) {
            wantCat = kop(catFee);
            wantWdr = percentOf(l.lowKop(), new BigDecimal("12"));
        }
        expect(com == wantCom, "M4 комиссия", at + ": " + rub(com) + " ≠ " + rub(wantCom));
        expect(ins == wantIns && cat == wantCat && wdr == wantWdr, "M5 сборы", at);
        long wantVat = percentOf(com + ins + cat + wdr, BigDecimal.valueOf(vatPct(sale.saleDate())));
        expect(vat == wantVat, "M5 НДС удержаний", at + ": " + rub(vat) + " ≠ " + rub(wantVat));
        expect(net == (sold ? price : 0) - com - ins - cat - wdr - vat, "M5 к выплате", at);
        // итог строки ↔ итог лота
        boolean consistent = switch (outcome) {
            case "sold", "buyer_default", "second_chance" -> l.result() == LotResultCode.SOLD;
            case "sold_after_sale" -> l.result() == LotResultCode.SOLD_AFTER_SALE;
            case "bought_in" -> l.result() == LotResultCode.BOUGHT_IN;
            case "withdrawn" -> l.result() == LotResultCode.WITHDRAWN;
            default -> false;
        };
        expect(consistent, "строка отчёта ↔ итог лота", at + ": " + outcome + " / " + l.result().code());
        if (outcome.equals("sold")) expect(price == l.priceKop(), "строка отчёта: цена = молоток", at);
        // M7
        if (sold) {
            expect((payable != 0) == (buyerPaid != 0), "M7 выплата положена ⇔ лот оплачен", at);
            if (payable != 0) {
                LocalDate a = MoneyTime.date(s.createdAt).minusDays(0);
                LocalDate end = endDayOf(s);
                LocalDate want1 = end.plusDays(35);
                LocalDate want2 = MoneyTime.date(buyerPaid);
                for (int k = 0; k < 5; ) {
                    want2 = want2.plusDays(1);
                    if (ru.isWorking(want2)) k++;
                }
                LocalDate want = want1.isAfter(want2) ? want1 : want2;
                expect(payable == MoneyTime.dayStart(want) && !a.isAfter(want), "M7 день выплаты", at);
            }
            if (paid != 0) {
                boolean early = buyerPaid == 0 || paid < buyerPaid || paid < payable;
                if (early && plantedEarly) {
                    skip("payout_before_payment");
                } else {
                    expect(!early, "выплата — не раньше оплаты покупателем (M7)", at);
                }
            }
        } else {
            expect(payable == 0, "выплата положена только проданным", at);
        }
    }

    private final Map<Integer, LocalDate> endDays = new TreeMap<>();

    /** День торгов для M7 — по дню отчёта: следующий рабочий день после торгов, назад — до рабочего дня торгов. */
    void saleEnd(int saleId, LocalDate endDay) {
        endDays.put(saleId, endDay);
    }

    private LocalDate endDayOf(MoneyBook.Stl s) {
        LocalDate d = endDays.get(s.saleId);
        if (d == null) throw new IllegalStateException("нет дня торгов " + s.saleId);
        return d;
    }

    void settlement(MoneyBook.Stl s, MoneyBook.Lines ln, Calendar.Sale sale) {
        long h = 0, com = 0, fees = 0, vat = 0, net = 0, paidNet = 0;
        boolean allPaid = true;
        for (int i = s.from; i < s.to; i++) {
            h += ln.price[i];
            com += ln.com[i];
            fees += ln.ins[i] + ln.cat[i] + ln.wdr[i];
            vat += ln.vat[i];
            net += ln.net[i];
            if (ln.paid[i] != 0) paidNet += ln.net[i];
            if (ln.net[i] != 0 && ln.paid[i] == 0) allPaid = false;
        }
        expect(h == s.hammer && com == s.commission && fees == s.fees && vat == s.vat && net == s.net,
                "итоги отчёта = суммы строк", "отчёт " + s.id);
        long payouts = 0;
        for (long v : s.payoutAmount) payouts += v;
        expect(payouts == s.paidTotal && payouts == paidNet, "Σ выплат = Σ выплаченных строк", "отчёт " + s.id + ": "
                + rub(payouts) + " / " + rub(paidNet));
        String want = net < 0 ? "owed_by_consignor" : allPaid ? "paid" : s.paidTotal > 0 ? "part_paid" : "awaiting_buyers";
        expect(want.equals(s.status), "статус отчёта на срез", "отчёт " + s.id);
        expect(s.createdAt < cut && sale.format() != null, "отчёт — до среза", "отчёт " + s.id);
    }

    // ---------------------------------------------------------------- итог

    void report(CheckReport rep, MoneyBook.Stats st) {
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            String name = "money: " + e.getKey();
            List<String> ex = violations.getOrDefault(e.getKey(), List.of());
            rep.expectNone(name, e.getValue(), ex, "нарушений нет");
        }
        int fates0 = 0, fates2 = 0;
        List<String> ex = new ArrayList<>();
        for (Deposit d : deposits) {
            if (d.fates > 1) {
                fates2++;
                if (ex.size() < 5) ex.add("регистрация " + d.registrationId + ": судеб " + d.fates);
            }
            if (d.fates == 0 && d.at < cut - 100 * MoneyTime.DAY) {
                fates0++;
                if (ex.size() < 5) ex.add("регистрация " + d.registrationId + ": судьбы нет");
            }
        }
        rep.expectNone("money: депозит — ровно одна судьба", fates0 + fates2, ex, deposits.size() + " депозитов");
        rep.ok("money: заложено", String.format(Locale.ROOT,
                "старая шкала — счетов %d (переиздано %d), выплат раньше оплаты %d, дублей платежей %d; пропущено проверкой %s",
                st.oldSchedule, st.reissued, st.payoutEarly, st.duplicates, skipped));
        if (st.planMismatch > 0) rep.fail("money: план цикла торгов = план денег", st.planMismatch + " счетов с другим планом");
        else rep.ok("money: план цикла торгов = план денег", "совпадает");
        corridors(rep);
    }

    private void corridors(CheckReport rep) {
        boolean full = scale >= 0.999;
        double tol = full ? 0.02 : 0.04;
        if (finalCount < 50) {
            rep.ok("money: коридоры оплаты", "счетов с окончательным исходом " + finalCount + " — мало для коридоров");
            return;
        }
        double n = finalCount;
        double def = (grpDef[0] + grpDef[1] + grpDef[2]) / n;
        double paying = n - grpDef[0] - grpDef[1] - grpDef[2];
        // день оплаты — среди платящих (цели — среди всех при отказах 4 %: делим на 0,96); все платящие — к 30-му
        corridor(rep, "оплачено к 2-му дню (среди платящих)", PaymentPlanner.PAID_BY_2 / PaymentPlanner.PAID_BY_30, paid2 / paying,
                paying, tol);
        corridor(rep, "оплачено к 5-му дню (среди платящих)", PaymentPlanner.PAID_BY_5 / PaymentPlanner.PAID_BY_30, paid5 / paying,
                paying, tol);
        corridor(rep, "оплачено к 10-му дню (среди платящих)", PaymentPlanner.PAID_BY_10 / PaymentPlanner.PAID_BY_30,
                paid10 / paying, paying, tol);
        rep.expect("money: коридор — платящие оплатили к 30-му дню", paid30 == (long) paying, paid30 + " из " + (long) paying);
        rep.ok("money: доли среди всех счетов", String.format(Locale.ROOT,
                "к 2 / 5 / 10 / 30-му дню — %s / %s / %s / %s, отказов %s (цели — 35 / 70 / 90 / 96 %% при отказах 4 %%; "
                        + "отказы — по группам ниже)", pctOf(paid2 / n), pctOf(paid5 / n), pctOf(paid10 / n), pctOf(paid30 / n),
                pctOf(def)));
        corridor(rep, "отказы, живые", PaymentPlanner.DEFAULT_LIVE, ratio(grpDef[0], grp[0]), grp[0], tol);
        corridor(rep, "отказы, онлайн", PaymentPlanner.DEFAULT_TIMED, ratio(grpDef[1], grp[1]), grp[1], tol);
        corridor(rep, "отказы, новые клиенты", PaymentPlanner.DEFAULT_NEW, ratio(grpDef[2], grp[2]), grp[2], tol);
        corridor(rep, "второй шанс предложен", PaymentPlanner.SECOND_CHANCE_OFFER, ratio(offered, defaultsSale), defaultsSale, tol);
        corridor(rep, "второй шанс принят", PaymentPlanner.SECOND_CHANCE_ACCEPT, ratio(accepted, offered), offered, tol);
    }

    private static double ratio(long a, long b) {
        return b == 0 ? Double.NaN : a / (double) b;
    }

    /** Коридор доли: ±{@code tol} или 3σ выборки, что шире (малые выборки {@code small}). */
    private static void corridor(CheckReport rep, String what, double want, double got, double n, double tol) {
        String name = "money: коридор — " + what;
        if (Double.isNaN(got) || n < 30) {
            rep.ok(name, String.format(Locale.ROOT, "выборка %.0f — мала, %s", n, Double.isNaN(got) ? "—" : pctOf(got)));
            return;
        }
        double t = Math.max(tol, 3 * Math.sqrt(want * (1 - want) / n));
        rep.expect(name, Math.abs(got - want) <= t, String.format(Locale.ROOT, "%s (цель %s ± %s, из %.0f)", pctOf(got),
                pctOf(want), pctOf(t), n));
    }

    private static String pctOf(double v) {
        return String.format(Locale.ROOT, "%.2f %%", v * 100);
    }
}
