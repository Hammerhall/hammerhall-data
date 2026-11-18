package io.hammerhall.data.auc.auction.intake;

import io.hammerhall.data.auc.auction.calendar.Depts;
import io.hammerhall.data.auc.bid.IncrementTable;
import io.hammerhall.data.core.random.Rng;

/**
 * Деньги лота — эстимейт, резерв, стартовая — на сетке шагов. Деньги — копейки.
 *
 * <ul>
 *   <li><b>Эстимейт</b> ставит специалист при приёме: середина ≈ V × поправка отдела × ошибка специалиста
 *       (логнормально, σ {@link #ESTIMATE_ERROR}); ширина (верхняя / нижняя) 1,33 — у 20 %, 1,5 — у 55 %, 2,0 — у 25 %;
 *       нижняя — к сетке вниз, верхняя — вверх; нижняя не меньше 1 000 ₽ (онлайн; в живые — от 5 000 ₽).</li>
 *   <li><b>Резерв</b> — у ≈ 85 % лотов: нижняя × равномерно 0,6–1,0, к сетке вниз, не выше нижней; без резерва —
 *       дешёвые онлайн-лоты и коллекционные. Заложенное {@code reserve_above_low} — резерв выше нижней «по настоянию
 *       комитента» (×1,05–1,3).</li>
 *   <li><b>Стартовая</b>: живые — 60–80 % нижней, онлайн — 70 % (не меньше 500 ₽), к сетке вниз, не выше резерва: в
 *       отраслевой практике старт — 60–80 % рыночной цены, до нижней оценки; ниже — лишние шаги от старта до молотка
 *       (ставок на лот — медиана 6–8).</li>
 *   <li><b>Повторное выставление</b> — эстимейт и резерв ниже на 25 % (разброс 15–35 %).</li>
 * </ul>
 * Поправка отдела — скрытая модель: подобрана вместе со спросом ({@code auction.demand}) под доли «ниже нижней /
 * в пределах / выше верхней» и долю проданных.
 */
public final class Pricing {
    private Pricing() {
    }

    /** Середина эстимейта к V по отделам: меньше 1 — осторожная оценка (монеты), больше — смелая (мебель, фарфор). */
    static final double[] ADJUST = {1.24, 0.98, 0.94, 1.09, 1.34, 1.05};
    /** Ошибка специалиста — σ логарифма. */
    public static final double ESTIMATE_ERROR = 0.11;
    /** «Спящие» лоты — специалист не узнал вещь: эстимейт в 3–15 раз ниже V (хвост молотка ≥ 5× верхней). */
    public static final double SLEEPER_SHARE = 0.006;
    /** Осторожная оценка — эстимейт ниже рынка в 1,5–3 раза (отсюда молоток ≥ 2× верхней у ≈ 6 % проданных). */
    public static final double CAUTIOUS_SHARE = 0.05;
    public static final long MIN_LOW_TIMED = 1_000_00L, MIN_LOW_LIVE = 5_000_00L, MIN_START_TIMED = 500_00L;
    /** Резерв выше нижней оценки — доля лотов. */
    public static final double RESERVE_ABOVE_LOW_SHARE = 0.001;

    /** Эстимейт: нижняя и верхняя оценка, копейки. */
    public record Estimate(long low, long high) {
        public Estimate {
            if (low <= 0 || high <= low) throw new IllegalArgumentException("эстимейт " + low + "–" + high);
        }

        public long mid() {
            return (low + high) / 2;
        }
    }

    /** Ширина эстимейта: 1,33 — 20 %, 1,5 — 55 %, 2,0 — 25 %. */
    static double width(Rng r) {
        double u = r.nextDouble();
        return u < 0.20 ? 4.0 / 3.0 : u < 0.75 ? 1.5 : 2.0;
    }

    /** Эстимейт при приёме: от скрытой стоимости V ({@code valueKop}) и отдела. */
    public static Estimate estimate(IncrementTable t, long valueKop, int dept, Rng r) {
        double mid = valueKop * ADJUST[dept] * StrictMath.exp(ESTIMATE_ERROR * r.gaussian());
        double u = r.nextDouble();
        if (u < SLEEPER_SHARE) mid = valueKop / r.uniform(3, 15);
        else if (u < SLEEPER_SHARE + CAUTIOUS_SHARE) mid *= r.uniform(0.35, 0.65);
        return of(t, mid, width(r));
    }

    /** Эстимейт с серединой {@code mid} и шириной {@code w} на сетке. */
    static Estimate of(IncrementTable t, double mid, double w) {
        double low = 2 * mid / (1 + w);
        long lo = Math.max(MIN_LOW_TIMED, t.floor((long) low));
        long hi = t.ceil((long) StrictMath.ceil(lo * w));
        if (hi <= lo) hi = t.minNext(lo);
        return new Estimate(lo, hi);
    }

    /** Повторное выставление: эстимейт ниже на {@code cut} (0,15–0,35); не ниже наименьшего. */
    public static Estimate reduce(IncrementTable t, Estimate e, double cut) {
        long lo = Math.max(MIN_LOW_TIMED, t.floor((long) (e.low() * (1 - cut))));
        long hi = t.ceil((long) StrictMath.ceil(e.high() * (1 - cut)));
        if (hi <= lo) hi = t.minNext(lo);
        return new Estimate(lo, hi);
    }

    /** Скидка эстимейта при повторе: 25 % в среднем, 15–35 %. */
    public static double reofferCut(Rng r) {
        return r.uniform(0.15, 0.35);
    }

    /** Без резерва: дешёвые онлайн-лоты и коллекционные — в среднем ≈ 15 % лотов. */
    public static boolean noReserve(long low, int dept, Rng r) {
        double p = low <= 3_000_00L ? 0.32 : low <= 10_000_00L ? 0.14 : 0.06;
        if (dept == Depts.COLLECTIBLES) p += 0.10;
        return r.chance(p);
    }

    /**
     * Нижняя граница резерва к нижней оценке по отделам: у живописи, книг, декоративно-прикладного и коллекционных
     * комитенты требовательнее (доли проданных онлайн — 68 / 75 / 62 / 80 %).
     */
    static final double[] RESERVE_FROM = {0.66, 0.6, 0.6, 0.68, 0.68, 0.70};

    /** Резерв по договору: нижняя × (от {@link #RESERVE_FROM} до 1,0) к сетке вниз; 0 — без резерва. */
    public static long reserve(IncrementTable t, long low, int dept, Rng r) {
        long res = t.floor((long) (low * r.uniform(RESERVE_FROM[dept], 1.0)));
        return Math.max(t.minNext(0), Math.min(res, low));
    }

    /** Заложенное {@code reserve_above_low}: резерв выше нижней ×1,05–1,3 (к сетке вверх, строго выше нижней). */
    public static long reserveAboveLow(IncrementTable t, long low, Rng r) {
        return Math.max(t.minNext(low), t.ceil((long) (low * r.uniform(1.05, 1.30))));
    }

    /** Стартовая: живые — доля нижней от {@link #START_LIVE_FROM} до {@link #START_LIVE_TO}, онлайн — {@link #START_TIMED}. */
    static final double START_LIVE_FROM = 0.6, START_LIVE_TO = 0.8, START_TIMED = 0.7;

    /** Стартовая цена: живые — 60–80 % нижней, онлайн — 70 % (не меньше 500 ₽); к сетке вниз, не выше резерва. */
    public static long start(IncrementTable t, long low, long reserve, boolean live, Rng r) {
        long s = live ? t.floor((long) (low * r.uniform(START_LIVE_FROM, START_LIVE_TO))) : Math.max(MIN_START_TIMED, t.floor((long) (low * START_TIMED)));
        if (reserve > 0 && s > reserve) s = reserve;
        return Math.max(t.minNext(0), s);
    }

    /** Резерв повторного выставления: ниже на ту же долю, не выше новой нижней. */
    public static long reduceReserve(IncrementTable t, long reserve, long newLow, double cut) {
        if (reserve <= 0) return 0;
        return Math.max(t.minNext(0), Math.min(newLow, t.floor((long) (reserve * (1 - cut)))));
    }
}
