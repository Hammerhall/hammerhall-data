package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Постоянные мира «Свартальвхейма»: начало сервиса, рост аудитории, страны и пояса зрителей, суточный и недельный ход
 * просмотров, сезоны, повышение цен, доли поведения. Всё — функции даты и постоянных, ни одна не смотрит на конец
 * периода и «сейчас»: так продление не меняет историю.
 */
final class SiteRules {
    private SiteRules() {
    }

    static final long DAY = 86_400L, HOUR = 3_600L, MINUTE = 60L;
    static final ZoneId MSK = Period.MOSCOW;

    /** Сервис работает с этой даты: первые регистрации и устройства. */
    static final LocalDate SERVICE_START = LocalDate.of(2019, 3, 1);
    /** Опора кривой роста аудитории — начало окна v1 (постоянная, не начало периода прогона). */
    static final LocalDate GROWTH_ANCHOR = LocalDate.of(2025, 10, 1);
    /** Регистраций в сутки на опоре в {@code full}. */
    static final double REG_PER_DAY = 70.0;
    /** Рост регистраций в год (экспонента). */
    static final double REG_GROWTH = 0.22;
    /** Фильмов {@code full}, на которые рассчитана аудитория; на снимке меньше — пропорционально меньше зрителей. */
    static final double AUDIENCE_MOVIES = 300_000.0;
    /** Не меньше этой доли аудитории — на крошечном снимке тестов. */
    static final double AUDIENCE_MIN_SHARE = 0.002;
    /** Повышение цен: с 01.03.2026 (00:00 по Москве). */
    static final LocalDate PRICE_RISE = LocalDate.of(2026, 3, 1);
    /** Каталог и права моделируются до этой даты (дольше любого продления окна). */
    static final LocalDate HORIZON = LocalDate.of(2031, 1, 1);

    // ---------------------------------------------------------------- страны и пояса

    /** Страны зрителей и их веса (§1.1): Россия — 85 %, остальные 15 % — русскоязычные зрители за рубежом. */
    static final String[] COUNTRIES = {"RUS", "KAZ", "BLR", "DEU", "UZB", "ISR", "USA", "TUR", "ARE", "ARM", "GEO", "KGZ", "SRB"};
    static final double[] COUNTRY_WEIGHTS = {85, 3, 2, 2, 1.5, 1.5, 1.5, 1, 0.5, 0.5, 0.5, 0.5, 0.5};
    static int countryIndex(String iso3) {
        for (int i = 0; i < COUNTRIES.length; i++) if (COUNTRIES[i].equals(iso3)) return i;
        return -1;
    }

    // ---------------------------------------------------------------- суточный и недельный ход, сезоны

    /** Начало просмотра по местному часу: будни — вечер 19–23 ч, днём мало. */
    static final double[] HOUR_WEEKDAY = {1.0, 0.45, 0.22, 0.10, 0.06, 0.06, 0.12, 0.25, 0.35, 0.40, 0.45, 0.50, 0.60,
            0.62, 0.62, 0.68, 0.80, 1.10, 1.70, 2.80, 3.50, 3.60, 3.00, 1.90};
    /** Выходные и праздники — днём больше. */
    static final double[] HOUR_WEEKEND = {1.30, 0.75, 0.40, 0.18, 0.10, 0.08, 0.10, 0.20, 0.40, 0.70, 0.95, 1.15, 1.30,
            1.40, 1.45, 1.50, 1.60, 1.80, 2.20, 2.90, 3.30, 3.30, 2.80, 2.00};
    /** Вечер — 19:00–23:59 по местному. */
    static final int EVENING_FROM = 19;
    /** День недели (пн … вс): выходные — выше. */
    static final double[] WEEKDAY = {0.90, 0.90, 0.92, 0.95, 1.05, 1.32, 1.28};
    /** Месяц: зима выше, июль — провал. */
    static final double[] MONTH = {1.12, 1.06, 1.02, 0.97, 0.92, 0.92, 0.80, 0.90, 0.96, 1.00, 1.04, 1.08};
    /** Новогодние каникулы (1–8 января) — пик. */
    static final double NEW_YEAR = 1.55;

    static boolean holiday(LocalDate d) {
        return d.getMonthValue() == 1 && d.getDayOfMonth() <= 8;
    }

    static boolean weekendOrHoliday(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY || holiday(d);
    }

    /** Множитель просмотров за местные сутки: месяц × день недели × каникулы. */
    static double season(LocalDate d) {
        double f = MONTH[d.getMonthValue() - 1] * WEEKDAY[d.getDayOfWeek().getValue() - 1];
        if (holiday(d)) f *= NEW_YEAR;
        return f;
    }

    private static final double[][] HOUR_CUM = {cum(HOUR_WEEKDAY), cum(HOUR_WEEKEND)};

    private static double[] cum(double[] w) {
        double[] c = new double[w.length];
        double s = 0;
        for (int i = 0; i < w.length; i++) c[i] = s += w[i];
        return c;
    }

    /** Секунда суток начала просмотра по местному времени. */
    static int secondOfDay(Rng r, boolean weekend) {
        double[] c = HOUR_CUM[weekend ? 1 : 0];
        double x = r.nextDouble() * c[c.length - 1];
        int h = 0;
        while (h < 23 && x >= c[h]) h++;
        return h * 3600 + r.nextInt(3600);
    }

    /** Доля вечерних начал по модели (будни и выходные — по их доле в неделе), для коридора. */
    static double eveningShareModel() {
        double wd = 0, we = 0;
        for (int i = 0; i < 5; i++) wd += WEEKDAY[i];
        for (int i = 5; i < 7; i++) we += WEEKDAY[i];
        return (wd * share(HOUR_WEEKDAY) + we * share(HOUR_WEEKEND)) / (wd + we);
    }

    private static double share(double[] w) {
        double e = 0, t = 0;
        for (int h = 0; h < 24; h++) {
            t += w[h];
            if (h >= EVENING_FROM) e += w[h];
        }
        return e / t;
    }

    // ---------------------------------------------------------------- время

    static long epoch(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    static long mskMidnight(LocalDate d) {
        return d.atStartOfDay(MSK).toEpochSecond();
    }

    static LocalDate mskDate(long t) {
        return Period.localDate(t, MSK);
    }

    /** Через {@code months} месяцев по московскому календарю (день месяца — как у опоры, в коротком месяце — последний). */
    static long plusMonths(long anchor, int months) {
        java.time.ZonedDateTime z = java.time.Instant.ofEpochSecond(anchor).atZone(MSK);
        return z.plusMonths(months).toEpochSecond();
    }

    // ---------------------------------------------------------------- деньги

    /** Цена «на 9» в рублях: 199, 249, 599 … (копейки). */
    static long nine(double rub) {
        long r = Math.round(rub / 10.0) * 10 - 1;
        return Math.max(9, r) * 100;
    }

    /** Цена проката после первых 60 дней окна: на 40 % дешевле, «на 9», не ниже 99 ₽. */
    static long rentLater(long premiereKop) {
        return Math.max(99_00, nine(premiereKop / 100.0 * 0.6));
    }

    /** Первые дни окна проката — по премьерной цене. */
    static final long PREMIERE_DAYS = 60;
}
