package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.HouseRules.FeeSchedule;
import io.hammerhall.data.auc.world.InvoiceKind;

import java.time.LocalDate;

/**
 * Суммы денег дома по правилам M1–M7 — от правил дома ({@link HouseRules}: шкалы, ступени, НДС), копейки.
 * Округление — до копейки, половина вверх, по строке.
 */
public final class MoneyRules {
    /** Пени (M6): 0,1 % суммы счёта в день — тысячные доли процента. */
    public static final int LATE_MILLI_PCT_PER_DAY = 100;

    private final HouseRules rules;
    private final FeeSchedule platform, insurance, catLive, catTimed, withdrawal, late, deposit;

    public MoneyRules(HouseRules rules) {
        this.rules = rules;
        this.platform = rules.fee(HouseRules.PLT_2024);
        this.insurance = rules.fee(HouseRules.INS_2024);
        this.catLive = rules.fee(HouseRules.CAT_LIVE);
        this.catTimed = rules.fee(HouseRules.CAT_TIMED);
        this.withdrawal = rules.fee(HouseRules.WDR_2024);
        this.late = rules.fee(HouseRules.LATE_2024);
        this.deposit = rules.fee(HouseRules.DEP_2024);
        if (late.rateMilliPct() != LATE_MILLI_PCT_PER_DAY) throw new IllegalStateException("пени — не 0,1 % в день");
    }

    public HouseRules house() {
        return rules;
    }

    // ---------------------------------------------------------------- счёт покупателю (M1–M3)

    /**
     * Суммы счёта: строки и итоги.
     *
     * @param scheduleId шкала премии строк ({@code invoice_lines.premium_schedule_id})
     * @param vatPct     ставка НДС на дату торгов
     */
    public record Amounts(int scheduleId, int vatPct, long[] price, long[] premium, long[] platformFee, long[] vat,
                          long[] lineTotal, long hammerTotal, long premiumTotal, long platformTotal, long vatTotal,
                          long total) {
        public int lines() {
            return price.length;
        }
    }

    /**
     * Строки счёта по M1–M3: премия по шкале {@code schedule} (у {@code shortfall} — 0), сбор площадки 3,5 % у строк с
     * победой ставки площадки, НДС на премию и сбор по дате торгов.
     */
    public Amounts invoice(InvoiceKind kind, FeeSchedule schedule, LocalDate saleDate, long[] priceKop,
                           boolean[] viaPlatform) {
        int n = priceKop.length;
        int vatPct = HouseRules.vatPct(saleDate);
        long[] prem = new long[n], plat = new long[n], vat = new long[n], tot = new long[n];
        long h = 0, p = 0, f = 0, v = 0, t = 0;
        for (int i = 0; i < n; i++) {
            long price = priceKop[i];
            if (price <= 0) throw new IllegalArgumentException("строка счёта без цены: " + price);
            boolean shortfall = kind == InvoiceKind.SHORTFALL;
            prem[i] = shortfall ? 0 : HouseRules.premium(schedule, price);
            plat[i] = !shortfall && viaPlatform[i] ? HouseRules.percent(price, platform.rateMilliPct()) : 0;
            vat[i] = HouseRules.vat(prem[i] + plat[i], saleDate);
            tot[i] = price + prem[i] + plat[i] + vat[i];
            h += price;
            p += prem[i];
            f += plat[i];
            v += vat[i];
            t += tot[i];
        }
        return new Amounts(schedule.id(), vatPct, priceKop.clone(), prem, plat, vat, tot, h, p, f, v, t);
    }

    /** Шкала премии торгов (M1): у монет — {@code BP-COIN}, иначе — по дате торгов. */
    public FeeSchedule premiumSchedule(Dept dept, LocalDate saleDate) {
        return rules.premiumSchedule(dept, saleDate);
    }

    /** Пени (M6): 0,1 % × сумма счёта × дней, до копейки. */
    public static long lateFee(long totalKop, int days) {
        return days <= 0 ? 0 : HouseRules.percent(totalKop, LATE_MILLI_PCT_PER_DAY * days);
    }

    /** Депозит на дорогой лот: 10 % нижней оценки. */
    public long deposit(long lowKop) {
        return HouseRules.percent(lowKop, deposit.rateMilliPct());
    }

    // ---------------------------------------------------------------- отчёт комитенту (M4, M5)

    /** Строка отчёта: удержания, НДС, к выплате. */
    public record SettlementAmounts(long price, long commission, long insurance, long catalogue, long withdrawal,
                                    long vat, long net) {
        public long fees() {
            return insurance + catalogue + withdrawal;
        }
    }

    /** Сбор за каталог за лот: живые — 400 ₽, онлайн — 150 ₽. */
    public long catalogueFee(Format f) {
        return (f == Format.LIVE ? catLive : catTimed).fixedKop();
    }

    /**
     * Проданный лот (M4, M5): комиссия по ступеням {@code SC-2024} (у торговца — на 5 п. п. ниже) или единая ставка
     * договора; страхование 1,5 % цены; каталог ({@code withCatalogue}); НДС на удержания по дате торгов.
     */
    public SettlementAmounts sold(long priceKop, boolean dealerDiscount, Integer flatMilliPct, Format f,
                                  boolean withCatalogue, LocalDate saleDate) {
        long com = rules.commission(priceKop, dealerDiscount, flatMilliPct);
        long ins = HouseRules.percent(priceKop, insurance.rateMilliPct());
        long cat = withCatalogue ? catalogueFee(f) : 0;
        long vat = HouseRules.vat(com + ins + cat, saleDate);
        return new SettlementAmounts(priceKop, com, ins, cat, 0, vat, priceKop - com - ins - cat - vat);
    }

    /** Не продан: сбор за каталог и НДС — к выплате минус. */
    public SettlementAmounts boughtIn(Format f, LocalDate saleDate) {
        long cat = catalogueFee(f);
        long vat = HouseRules.vat(cat, saleDate);
        return new SettlementAmounts(0, 0, 0, cat, 0, vat, -cat - vat);
    }

    /** Снят комитентом после публикации: 12 % нижней оценки + каталог, НДС; снят домом — без сборов. */
    public SettlementAmounts withdrawn(boolean byHouse, long lowKop, Format f, LocalDate saleDate) {
        if (byHouse) return ZERO;
        long wdr = HouseRules.percent(lowKop, withdrawal.rateMilliPct());
        long cat = catalogueFee(f);
        long vat = HouseRules.vat(wdr + cat, saleDate);
        return new SettlementAmounts(0, 0, 0, cat, wdr, vat, -wdr - cat - vat);
    }

    /** Строка {@code buyer_default} — нули (M7). */
    public static final SettlementAmounts ZERO = new SettlementAmounts(0, 0, 0, 0, 0, 0, 0);
}
