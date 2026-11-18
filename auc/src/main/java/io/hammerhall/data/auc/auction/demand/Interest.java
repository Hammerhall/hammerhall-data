package io.hammerhall.data.auc.auction.demand;

import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

/**
 * Интерес к лоту — скрытая модель: сколько участников придёт и какую цену даст каждый. Генератор решает,
 * кто придёт и с каким максимумом; что принято — решает движок.
 *
 * <ul>
 *   <li><b>Число желающих</b> — отрицательное биномиальное (гамма-пуассон, форма {@link #SHAPE}) со средним
 *       λ = μ(отдел, формат) × (V / середина эстимейта)<sup>β</sup> + 1 на каждое удвоение верхней оценки выше
 *       100 000 ₽: «недооценённые» лоты собирают больше участников, переоценённые остаются без ставок. Целевые числа
 *       (у лотов со ставками): медиана 3, среднее 3,8, p90 7, до ≈ 40; без ставок — 16 % лотов.</li>
 *   <li><b>Своя цена</b> участника — V × вкус (логнормально, σ {@link #TASTE}), не выше бюджета клиента; максимум —
 *       к сетке вниз, не выше доли лимита (T11).</li>
 * </ul>
 * Параметры подобраны прогоном движка на модельных лотах (коридоры: доля проданных по отделам и формату, молоток к
 * эстимейту, лоты без ставок и со ставками ниже резерва).
 */
public final class Interest {
    private Interest() {
    }

    /** Среднее число желающих у «честно оценённого» лота: онлайн, по отделам. */
    static final double[] MU_TIMED = {6.4, 6.8, 7.7, 6.8, 6.4, 6.5};
    /** Лот «не заметили»: никто не пришёл — по отделам (доля лотов без ставок — ≈ 2/3 непроданных). */
    static final double[] UNNOTICED = {0.13, 0.036, 0.016, 0.10, 0.18, 0.05};
    /**
     * Живые торги — множитель числа желающих по отделам и доля «не заметили» ({@code UNNOTICED × LIVE_UNNOTICED}): живой
     * каталог — лучшие и дорогие вещи отдела (≈ 2,3 % лотов), у дорогого лота меньше клиентов с бюджетом.
     */
    static final double[] LIVE_MU = {2.15, 2.1, 1.85, 1.70, 1.28, 2.6};
    static final double LIVE_UNNOTICED = 0.3;
    /** Чувствительность к недооценке: λ ∝ (V / середина)^β. */
    static final double BETA = 1.0;
    /** Форма гамма-смеси (дисперсия отрицательного биномиального). */
    static final int SHAPE = 2;
    /** Разброс своей цены участника — одним числом, без черты клиента. */
    public static final double TASTE = 0.24;
    /**
     * Своя цена = V × склонность клиента к отделу (постоянная черта: одни платят щедрее других — так одни и те же
     * покупатели выигрывают из года в год) × вкус на лот. σ логарифма — {@link #AFFINITY} и {@link #LOT_TASTE}.
     */
    public static final double AFFINITY = 0.36, LOT_TASTE = 0.14;
    /**
     * Частые участники платят щедрее (коллекционеры и торговцы — за нужную вещь, редкие — «по случаю»): множитель
     * (торгов в год / 5)<sup>γ</sup>. Так одни и те же покупатели выигрывают из года в год, а редкие участники чаще
     * остаются без покупки (участников ≈ 2,5 на покупателя, 60 % покупателей года покупали и в прошлом).
     */
    public static final double ACTIVITY_GAMMA = 0.12;
    /** Новичок платит осторожнее: множитель от {@link #TENURE_LOW} до 1 за первые {@link #TENURE_YEARS} года в доме. */
    public static final double TENURE_LOW = 0.75, TENURE_YEARS = 3;
    /** Средний множитель активности у участников (они выбираются пропорционально числу торгов в год) — цены не растут. */
    public static final double ACTIVITY_NORM = 0.88;
    /** Наибольшее число желающих на лот. */
    static final int MAX_BIDDERS = 40;

    /** Среднее число желающих на лот. */
    public static double lambda(int dept, boolean live, double valueKop, double midKop, long highKop) {
        double l = MU_TIMED[dept] * (live ? LIVE_MU[dept] : 1.0) * StrictMath.pow(valueKop / midKop, BETA);
        if (highKop > 100_000_00L) l += StrictMath.log(highKop / 100_000_00.0) / StrictMath.log(2);
        return Math.min(l, 30);
    }

    /** Лот никто не заметил (до числа желающих). */
    public static boolean unnoticed(int dept, boolean live, Rng r) {
        return r.chance(UNNOTICED[dept] * (live ? LIVE_UNNOTICED : 1.0));
    }

    /** Число желающих: гамма(2, λ/2)-пуассон, не больше 40. */
    public static int count(double lambda, Rng r) {
        double g = -(lambda / SHAPE) * (StrictMath.log(1 - r.nextDouble()) + StrictMath.log(1 - r.nextDouble()));
        return Math.min(MAX_BIDDERS, r.poisson(g));
    }

    /** Своя цена участника, копейки (без черты клиента). */
    public static double valuation(double valueKop, Rng r) {
        return valueKop * StrictMath.exp(TASTE * r.gaussian());
    }

    /** Своя цена участника со склонностью {@code affinity} (множитель, см. {@link #affinity}). */
    public static double valuation(double valueKop, double affinity, Rng r) {
        return valueKop * affinity * StrictMath.exp(LOT_TASTE * r.gaussian());
    }

    /** Склонность клиента к отделу: {@code (торгов в год / 5)^γ · exp(σ·z)}, z — от потока по (клиент, отдел). */
    public static double affinity(RandomStreams.Stream st, int clientId, int dept, double salesPerYear, double tenureYears) {
        double act = StrictMath.pow(Math.max(0.5, salesPerYear) / 5.0, ACTIVITY_GAMMA) / ACTIVITY_NORM;
        act *= TENURE_LOW + (1 - TENURE_LOW) * Math.min(1.0, Math.max(0.0, tenureYears) / TENURE_YEARS);
        return act * StrictMath.exp(AFFINITY * st.at(clientId, 1_000 + dept).gaussian() - AFFINITY * AFFINITY / 2);
    }
}
