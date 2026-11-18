package io.hammerhall.data.auc.site;

import io.hammerhall.data.core.random.RandomStreams;

/**
 * Правила сайта и уведомлений — одно место для генерации и самопроверки: откуда клиент заходит в сутки, тихие часы,
 * часы активности, коэффициенты дня, нормы визитов. Числа подобраны под объём набора (30 просмотров на лот).
 */
final class Rules {
    // ---------------------------------------------------------------- коды событий и уведомлений (индексы SiteTables)
    static final byte E_SEARCH = 0, E_PAGE = 1, E_VIEW = 2, E_WATCH = 3, E_UNWATCH = 4, E_FORM = 5, E_BID = 6, E_ORDER = 7;
    static final byte N_REG = 0, N_OUTBID = 1, N_CLOSING = 2, N_WON = 3, N_SUMMARY = 4, N_INVOICE = 5, N_REMINDER = 6,
            N_PAID = 7, N_CANCELLED = 8, N_REFUND = 9, N_SETTLEMENT = 10, N_PAYOUT = 11;
    static final byte CH_EMAIL = 0, CH_PUSH = 1;
    static final byte ST_DELIVERED = 0, ST_FAILED = 1, ST_SUPPRESSED = 2, ST_PENDING = 3;
    static final byte SRC_DIRECT = 0, SRC_SEARCH = 1, SRC_EMAIL = 2, SRC_PUSH = 3, SRC_REFERRAL = 4;

    /** Просмотров на лот за его жизнь в каталоге. */
    static final double VIEWS_PER_LOT = 30;
    /** Средний интерес лота (1 + участников) — нормировка, если у торгов нет лотов с публикацией каталога. */
    static final double POP_NORM = 4.2;
    /**
     * Анонимные визиты добирают просмотры дня до нормы ({@link #VIEWS_PER_LOT} по весам видимых лотов), но дают не
     * меньше этой доли; {@code ANON_VIEWS} — средние просмотры на такой визит (по прогону, постоянная).
     */
    static final double ANON_SHARE = 0.12, ANON_VIEWS = 6.6;
    /** Тихие часы push по местному времени клиента: с 23:00 до 08:00. */
    static final int QUIET_FROM = 23 * 3600, QUIET_TO = 8 * 3600;
    /** Повтор отправки уведомления: через 1 мин, 5 мин, 30 мин, 2 ч; попыток — не больше 5. */
    static final long[] BACKOFF = {60, 300, 1800, 7200};
    static final int MAX_ATTEMPTS = 5;
    /** Разрыв между действиями одного клиента, после которого начинается новый визит. */
    static final long SESSION_GAP = 1800;
    /** Доля повторно отправленных уведомлений (заложенное {@code notification_duplicate}). */
    static final double DUPLICATE_SHARE = 0.001;
    /** Доля визитов клиента без входа (устройство его, клиента нет). */
    static final double ANON_CLIENT_SHARE = 0.08;

    /** Часы активности по местному времени (вес часа 0–23). */
    static final double[] HOURS = {0.35, 0.2, 0.1, 0.06, 0.05, 0.08, 0.2, 0.45, 0.75, 0.95, 1.1, 1.15, 1.2, 1.2, 1.15, 1.15,
            1.2, 1.3, 1.5, 1.8, 2.0, 1.95, 1.5, 0.85};
    static final double[] HOURS_CUM;

    static {
        HOURS_CUM = new double[24];
        double a = 0;
        for (int i = 0; i < 24; i++) HOURS_CUM[i] = a += HOURS[i];
    }

    private final RandomStreams.Stream viaStream;

    Rules(RandomStreams.Stream viaStream) {
        this.viaStream = viaStream;
    }

    /**
     * Откуда клиент действует в эти сутки: у кого есть приложение — из приложения чуть чаще половины суток, иначе —
     * сайт. Одна функция для наблюдения ({@code watchlist.via}) и визита (устройство), поэтому они совпадают.
     */
    byte via(SiteInput in, int c, long day) {
        if (in.appDev[c] < 0) return SiteInput.VIA_SITE;
        if (in.webDev[c] < 0) return SiteInput.VIA_APP;
        return viaStream.unit(in.cKey[c] * 1_000_003L + day) < 0.55 ? SiteInput.VIA_APP : SiteInput.VIA_SITE;
    }

    /** Тихие часы по местному времени клиента. */
    static boolean quiet(SiteInput in, int c, long t) {
        int s = in.localSecond(c, t);
        return s >= QUIET_FROM || s < QUIET_TO;
    }

    /** Коэффициент суток: выходные — чаще, лето — реже. */
    static double dayFactor(long day) {
        int dow = Math.floorMod(day + 3, 7); // 0 — понедельник
        java.time.LocalDate d = java.time.LocalDate.ofEpochDay(day);
        double f = dow >= 5 ? 1.2 : 1.0;
        int m = d.getMonthValue();
        if (m >= 6 && m <= 8) f *= 0.85;
        return f;
    }

    /** Индекс коэффициента суток (для заранее посчитанных вероятностей): 0–3. */
    static int dayFactorIndex(long day) {
        int dow = Math.floorMod(day + 3, 7);
        int m = java.time.LocalDate.ofEpochDay(day).getMonthValue();
        return (dow >= 5 ? 1 : 0) + (m >= 6 && m <= 8 ? 2 : 0);
    }

    static final double[] DAY_FACTORS = {1.0, 1.2, 0.85, 1.02};

    /**
     * Вес суток лота в каталоге: {@code k} — сутки от публикации, {@code kk} — последние (закрытие или слот). Чаще всего
     * смотрят в день закрытия, затем в день публикации и накануне закрытия.
     */
    static double dayWeight(int k, int kk) {
        if (kk <= 0) return 1;
        if (k == kk) return 4.0;
        if (k == kk - 1) return 1.8;
        if (k == 0) return 2.0;
        return 1.0;
    }

    static double daySum(int kk) {
        double s = 0;
        for (int k = 0; k <= Math.max(0, kk); k++) s += dayWeight(k, kk);
        return s;
    }

    /** Час по весам {@link #HOURS} для доли {@code u} ∈ [0, 1). */
    static int hour(double u) {
        double x = u * HOURS_CUM[23];
        for (int h = 0; h < 24; h++) if (x < HOURS_CUM[h]) return h;
        return 23;
    }
}
