package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.gen.Planted;
import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.core.time.Period;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Самопроверка «Свартальвхейма» (§7) — по записанным таблицам выпуска, заново и независимо от хода генерации: ключи и
 * ссылки проверяет ядро, здесь — правила домена (доступ к просмотру, деньги, подписки, время, тексты), ограничения
 * DDL ({@code CHECK} — их ядро не проверяет) и цели узоров §5 коридорами (у {@code full} — уже, у {@code medium} — вдвое,
 * у {@code small} — втрое шире; при малой выборке — только печать). Заложенные отклонения §6 ({@link Planted}) правила
 * пропускают ровно их. Провал любой проверки — выпуск не годен.
 */
final class SiteAudit {
    private SiteAudit() {
    }

    /** Что самопроверке известно, кроме таблиц: фильмы профиля, справочник городов, окно и срез, профиль. */
    record Inputs(SiteInput in, Cities cities, long w0, long cutoff, Profile profile, int tags) {
    }

    static final long DAY = SiteRules.DAY;
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9_]{4,20}");

    // ---------------------------------------------------------------- цели узоров (своё: по модели генератора)
    static final double EVENING = 0.535, EVENING_HALF = 0.02;
    static final double WEEKEND = 1.42, WEEKEND_HALF = 0.10;
    static final double NEW_YEAR = 1.62, NEW_YEAR_HALF = 0.15;
    static final double JULY = 0.86, JULY_HALF = 0.06;
    static final double NOVELTY_MIN = 1.6;
    static final double MARCH = 2.0, MARCH_HALF = 0.3, DOWNGRADE_MIN = 2.0;
    static final double CONVERSION = 0.40, CONVERSION_HALF = 0.02, REFERRAL_PLUS = 0.05;
    /**
     * Доли оценок 8–10 и 1–3 — по профилю (small, medium, full): оценка опирается на рейтинг фильма, а в {@code small}
     * — 1 % самых известных фильмов с рейтингом 8,4–9,9, поэтому там почти все оценки — 9 и 10.
     */
    static final double[] HIGH = {0.89, 0.85, 0.78}, LOW = {0.002, 0.004, 0.01};
    static final double HIGH_HALF = 0.02, LOW_HALF = 0.02;
    static final double CORR_LO = 0.6, CORR_HI = 0.8, CORR_LO_MEDIUM = 0.25;
    static final double DIVERGE_LO = 0.01, DIVERGE_HI = 0.04;
    static final double PARETO = 0.50, PARETO_HALF = 0.08, FOLLOW_CORR = 0.25;
    /** Из списка «посмотреть» потом смотрят — по профилю (small, medium, full): в small каталог мал, выбор теснее. */
    static final double[] WATCHED = {0.35, 0.30, 0.29};
    static final double WATCHED_HALF = 0.02;
    static final double FAMILY_DEVICES = 1.3, BASIC_DEVICES_MAX = 1.15, FAMILY_CONCURRENT = 0.15;

    // ================================================================ таблицы

    static final class Data {
        // пользователи (индекс = id − 1)
        int nu;
        long[] uReg, uDel;
        int[] uCity, uRef, uPromo, uBirth;
        byte[] uChannel;
        String[] uLogin, uName;
        // промокоды
        Map<Integer, long[]> promo = new HashMap<>(); // id → kind, value, partner, from(epochDay), to(epochDay), max
        // тарифы: id → months, streams, quality; цены: id → (epochDay от, коп)
        Map<Integer, int[]> plan = new HashMap<>();
        Map<Integer, List<long[]>> price = new HashMap<>();
        // устройства
        int nd;
        int[] dUser;
        long[] dFirst, dLast;
        // подписки
        int ns;
        int[] sUser, sPlan;
        long[] sStart, sTrial, sEnds, sCancel;
        byte[] sStatus, sReason;
        boolean[] sAuto;
        // оплаты
        int np;
        int[] pUser, pSub, pPur;
        long[] pAmount, pAt, pRefund;
        byte[] pStatus;
        // каталог: окна по фильму
        Map<Integer, List<long[]>> windows = new HashMap<>(); // movie → [added, removed(-1), access, rent, buy, subFrom]
        // покупки
        int nb;
        int[] bUser, bMovie;
        boolean[] bBuy;
        long[] bPrice, bAt, bExp;
        // просмотры
        int nv;
        int[] vUser, vDev, vMovie, vWatched;
        long[] vAt;
        boolean[] vDone;
        byte[] vQuality, vSource;
        // оценки
        int nr;
        int[] rUser, rMovie, rValue, rChanges;
        long[] rAt;
        // рецензии
        int nw;
        int[] wUser, wMovie, wRating;
        long[] wAt, wEdit;
        boolean[] wHidden;
        String[] wTitle, wBody;
        // голоса, список, подписки на людей, подборки, теги
        List<long[]> votes = new ArrayList<>(), watch = new ArrayList<>(), follows = new ArrayList<>(), colItems = new ArrayList<>(),
                movieTags = new ArrayList<>();
        Map<Integer, long[]> collections = new HashMap<>();
        Map<Integer, String> collectionTitle = new HashMap<>();
        List<String> tagNames = new ArrayList<>();
        Map<Integer, Integer> movieIndex = new HashMap<>();
    }

    private static long[] grow(long[] a, int n) {
        return n < a.length ? a : Arrays.copyOf(a, Math.max(16, a.length * 2));
    }

    private static int[] grow(int[] a, int n) {
        return n < a.length ? a : Arrays.copyOf(a, Math.max(16, a.length * 2));
    }

    private static byte[] grow(byte[] a, int n) {
        return n < a.length ? a : Arrays.copyOf(a, Math.max(16, a.length * 2));
    }

    private static boolean[] grow(boolean[] a, int n) {
        return n < a.length ? a : Arrays.copyOf(a, Math.max(16, a.length * 2));
    }

    private static String[] grow(String[] a, int n) {
        return n < a.length ? a : Arrays.copyOf(a, Math.max(16, a.length * 2));
    }

    static int idx(String[] values, String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return -1;
    }

    static Data load(Path dir, SiteInput in) throws IOException {
        Data d = new Data();
        for (int i = 0; i < in.movies().size(); i++) d.movieIndex.put(in.movies().get(i).id(), i);
        Tsv.read(dir, "plans", "id\tcode\tname\tmonthly_rub\tperiod_months\tmax_streams\tmax_quality\tads\tintroduced_on\tretired_on",
                f -> d.plan.put(Tsv.i(f[0]), new int[]{Tsv.i(f[4]), Tsv.i(f[5]), idx(SiteSchema.QUALITIES, f[6])}));
        Tsv.read(dir, "plan_prices", "plan_id\tvalid_from\tprice_rub", f -> d.price.computeIfAbsent(Tsv.i(f[0]), k -> new ArrayList<>())
                .add(new long[]{LocalDate.parse(f[1]).toEpochDay(), Tsv.kop(f[2])}));
        Tsv.read(dir, "promo_codes", "id\tcode\tkind\tvalue\tpartner\tvalid_from\tvalid_to\tmax_uses", f -> d.promo.put(Tsv.i(f[0]),
                new long[]{idx(SiteSchema.PROMO_KINDS, f[2]), Tsv.i(f[3]), Tsv.bool(f[4]) ? 1 : 0, LocalDate.parse(f[5]).toEpochDay(),
                        LocalDate.parse(f[6]).toEpochDay(), Tsv.num(f[7])}));
        d.uReg = new long[1024];
        d.uDel = new long[1024];
        d.uCity = new int[1024];
        d.uRef = new int[1024];
        d.uPromo = new int[1024];
        d.uBirth = new int[1024];
        d.uChannel = new byte[1024];
        d.uLogin = new String[1024];
        d.uName = new String[1024];
        Tsv.read(dir, "users", "id\tlogin\tdisplay_name\tgender\tbirth_year\tcity_id\tregistered_at\tchannel\treferrer_id\tpromo_code_id\tdeleted_at", f -> {
            int n = d.nu;
            d.uReg = grow(d.uReg, n);
            d.uDel = grow(d.uDel, n);
            d.uCity = grow(d.uCity, n);
            d.uRef = grow(d.uRef, n);
            d.uPromo = grow(d.uPromo, n);
            d.uBirth = grow(d.uBirth, n);
            d.uChannel = grow(d.uChannel, n);
            d.uLogin = grow(d.uLogin, n);
            d.uName = grow(d.uName, n);
            if (Tsv.i(f[0]) != n + 1) throw new IllegalStateException("users: id " + f[0] + " не по порядку");
            d.uLogin[n] = f[1];
            d.uName[n] = f[2];
            d.uBirth[n] = Tsv.i(f[4]);
            d.uCity[n] = Tsv.i(f[5]);
            d.uReg[n] = Tsv.num(f[6]);
            d.uChannel[n] = (byte) idx(SiteSchema.CHANNELS, f[7]);
            d.uRef[n] = Tsv.i(f[8]);
            d.uPromo[n] = Tsv.i(f[9]);
            d.uDel[n] = Tsv.num(f[10]);
            d.nu++;
        });
        d.dUser = new int[1024];
        d.dFirst = new long[1024];
        d.dLast = new long[1024];
        Tsv.read(dir, "devices", "id\tuser_id\tkind\tos\tfirst_seen_at\tlast_seen_at", f -> {
            int n = d.nd;
            d.dUser = grow(d.dUser, n);
            d.dFirst = grow(d.dFirst, n);
            d.dLast = grow(d.dLast, n);
            d.dUser[n] = Tsv.i(f[1]);
            d.dFirst[n] = Tsv.num(f[4]);
            d.dLast[n] = Tsv.num(f[5]);
            d.nd++;
        });
        d.sUser = new int[1024];
        d.sPlan = new int[1024];
        d.sStart = new long[1024];
        d.sTrial = new long[1024];
        d.sEnds = new long[1024];
        d.sCancel = new long[1024];
        d.sStatus = new byte[1024];
        d.sReason = new byte[1024];
        d.sAuto = new boolean[1024];
        Tsv.read(dir, "subscriptions", "id\tuser_id\tplan_id\tstarted_at\ttrial_ends_at\tends_at\tstatus\tauto_renew\tcancelled_at\tcancel_reason", f -> {
            int n = d.ns;
            d.sUser = grow(d.sUser, n);
            d.sPlan = grow(d.sPlan, n);
            d.sStart = grow(d.sStart, n);
            d.sTrial = grow(d.sTrial, n);
            d.sEnds = grow(d.sEnds, n);
            d.sCancel = grow(d.sCancel, n);
            d.sStatus = grow(d.sStatus, n);
            d.sReason = grow(d.sReason, n);
            d.sAuto = grow(d.sAuto, n);
            d.sUser[n] = Tsv.i(f[1]);
            d.sPlan[n] = Tsv.i(f[2]);
            d.sStart[n] = Tsv.num(f[3]);
            d.sTrial[n] = Tsv.num(f[4]);
            d.sEnds[n] = Tsv.num(f[5]);
            d.sStatus[n] = (byte) idx(SiteSchema.SUB_STATUS, f[6]);
            d.sAuto[n] = Tsv.bool(f[7]);
            d.sCancel[n] = Tsv.num(f[8]);
            d.sReason[n] = (byte) (f[9].isEmpty() ? -1 : idx(SiteSchema.CANCEL_REASONS, f[9]));
            d.ns++;
        });
        d.pUser = new int[1024];
        d.pSub = new int[1024];
        d.pPur = new int[1024];
        d.pAmount = new long[1024];
        d.pAt = new long[1024];
        d.pRefund = new long[1024];
        d.pStatus = new byte[1024];
        Tsv.read(dir, "payments", "id\tuser_id\tsubscription_id\tpurchase_id\tamount_rub\tmethod\tstatus\tcreated_at\trefunded_at", f -> {
            int n = d.np;
            d.pUser = grow(d.pUser, n);
            d.pSub = grow(d.pSub, n);
            d.pPur = grow(d.pPur, n);
            d.pAmount = grow(d.pAmount, n);
            d.pAt = grow(d.pAt, n);
            d.pRefund = grow(d.pRefund, n);
            d.pStatus = grow(d.pStatus, n);
            d.pUser[n] = Tsv.i(f[1]);
            d.pSub[n] = Tsv.i(f[2]);
            d.pPur[n] = Tsv.i(f[3]);
            d.pAmount[n] = Tsv.kop(f[4]);
            d.pStatus[n] = (byte) idx(SiteSchema.PAY_STATUS, f[6]);
            d.pAt[n] = Tsv.num(f[7]);
            d.pRefund[n] = Tsv.num(f[8]);
            d.np++;
        });
        Tsv.read(dir, "catalog", "movie_id\tadded_at\tremoved_at\taccess\trent_price_rub\tbuy_price_rub\tsubscription_from",
                f -> d.windows.computeIfAbsent(Tsv.i(f[0]), k -> new ArrayList<>()).add(new long[]{Tsv.num(f[1]), Tsv.num(f[2]),
                        idx(SiteSchema.ACCESS, f[3]), Tsv.kop(f[4]), Tsv.kop(f[5]), Tsv.num(f[6])}));
        d.bUser = new int[1024];
        d.bMovie = new int[1024];
        d.bBuy = new boolean[1024];
        d.bPrice = new long[1024];
        d.bAt = new long[1024];
        d.bExp = new long[1024];
        Tsv.read(dir, "purchases", "id\tuser_id\tmovie_id\tkind\tprice_rub\tcreated_at\texpires_at", f -> {
            int n = d.nb;
            d.bUser = grow(d.bUser, n);
            d.bMovie = grow(d.bMovie, n);
            d.bBuy = grow(d.bBuy, n);
            d.bPrice = grow(d.bPrice, n);
            d.bAt = grow(d.bAt, n);
            d.bExp = grow(d.bExp, n);
            d.bUser[n] = Tsv.i(f[1]);
            d.bMovie[n] = Tsv.i(f[2]);
            d.bBuy[n] = f[3].equals("buy");
            d.bPrice[n] = Tsv.kop(f[4]);
            d.bAt[n] = Tsv.num(f[5]);
            d.bExp[n] = Tsv.num(f[6]);
            d.nb++;
        });
        d.vUser = new int[1024];
        d.vDev = new int[1024];
        d.vMovie = new int[1024];
        d.vWatched = new int[1024];
        d.vAt = new long[1024];
        d.vDone = new boolean[1024];
        d.vQuality = new byte[1024];
        d.vSource = new byte[1024];
        Tsv.read(dir, "views", "id\tuser_id\tdevice_id\tmovie_id\tstarted_at\twatched_min\tcompleted\tquality\tsource", f -> {
            int n = d.nv;
            d.vUser = grow(d.vUser, n);
            d.vDev = grow(d.vDev, n);
            d.vMovie = grow(d.vMovie, n);
            d.vWatched = grow(d.vWatched, n);
            d.vAt = grow(d.vAt, n);
            d.vDone = grow(d.vDone, n);
            d.vQuality = grow(d.vQuality, n);
            d.vSource = grow(d.vSource, n);
            d.vUser[n] = Tsv.i(f[1]);
            d.vDev[n] = Tsv.i(f[2]);
            d.vMovie[n] = Tsv.i(f[3]);
            d.vAt[n] = Tsv.num(f[4]);
            d.vWatched[n] = Tsv.i(f[5]);
            d.vDone[n] = Tsv.bool(f[6]);
            d.vQuality[n] = (byte) idx(SiteSchema.QUALITIES, f[7]);
            d.vSource[n] = (byte) idx(SiteSchema.SOURCES, f[8]);
            d.nv++;
        });
        d.rUser = new int[1024];
        d.rMovie = new int[1024];
        d.rValue = new int[1024];
        d.rChanges = new int[1024];
        d.rAt = new long[1024];
        Tsv.read(dir, "ratings", "user_id\tmovie_id\trating\trated_at\tchanges", f -> {
            int n = d.nr;
            d.rUser = grow(d.rUser, n);
            d.rMovie = grow(d.rMovie, n);
            d.rValue = grow(d.rValue, n);
            d.rChanges = grow(d.rChanges, n);
            d.rAt = grow(d.rAt, n);
            d.rUser[n] = Tsv.i(f[0]);
            d.rMovie[n] = Tsv.i(f[1]);
            d.rValue[n] = Tsv.i(f[2]);
            d.rAt[n] = Tsv.num(f[3]);
            d.rChanges[n] = Tsv.i(f[4]);
            d.nr++;
        });
        d.wUser = new int[1024];
        d.wMovie = new int[1024];
        d.wRating = new int[1024];
        d.wAt = new long[1024];
        d.wEdit = new long[1024];
        d.wHidden = new boolean[1024];
        d.wTitle = new String[1024];
        d.wBody = new String[1024];
        Tsv.read(dir, "reviews", "id\tuser_id\tmovie_id\trating\ttitle\tbody\tcreated_at\tedited_at\tstatus", f -> {
            int n = d.nw;
            d.wUser = grow(d.wUser, n);
            d.wMovie = grow(d.wMovie, n);
            d.wRating = grow(d.wRating, n);
            d.wAt = grow(d.wAt, n);
            d.wEdit = grow(d.wEdit, n);
            d.wHidden = grow(d.wHidden, n);
            d.wTitle = grow(d.wTitle, n);
            d.wBody = grow(d.wBody, n);
            d.wUser[n] = Tsv.i(f[1]);
            d.wMovie[n] = Tsv.i(f[2]);
            d.wRating[n] = Tsv.i(f[3]);
            d.wTitle[n] = f[4];
            d.wBody[n] = f[5];
            d.wAt[n] = Tsv.num(f[6]);
            d.wEdit[n] = Tsv.num(f[7]);
            d.wHidden[n] = f[8].equals("hidden");
            d.nw++;
        });
        Tsv.read(dir, "review_votes", "review_id\tuser_id\tuseful\tvoted_at",
                f -> d.votes.add(new long[]{Tsv.i(f[0]), Tsv.i(f[1]), Tsv.bool(f[2]) ? 1 : 0, Tsv.num(f[3])}));
        Tsv.read(dir, "watchlist", "user_id\tmovie_id\tadded_at\tremoved_at\tremoved_reason", f -> d.watch.add(new long[]{Tsv.i(f[0]),
                Tsv.i(f[1]), Tsv.num(f[2]), Tsv.num(f[3]), f[4].isEmpty() ? -1 : f[4].equals("watched") ? 1 : 0}));
        Tsv.read(dir, "follows", "follower_id\tfollowee_id\tfollowed_at\tunfollowed_at",
                f -> d.follows.add(new long[]{Tsv.i(f[0]), Tsv.i(f[1]), Tsv.num(f[2]), Tsv.num(f[3])}));
        Tsv.read(dir, "collections", "id\tuser_id\ttitle\tis_public\tcreated_at", f -> {
            d.collections.put(Tsv.i(f[0]), new long[]{Tsv.i(f[1]), Tsv.num(f[4])});
            d.collectionTitle.put(Tsv.i(f[0]), f[2]);
        });
        Tsv.read(dir, "collection_items", "collection_id\tmovie_id\tposition\tadded_at",
                f -> d.colItems.add(new long[]{Tsv.i(f[0]), Tsv.i(f[1]), Tsv.i(f[2]), Tsv.num(f[3])}));
        Tsv.read(dir, "tags", "id\tname", f -> d.tagNames.add(f[1]));
        Tsv.read(dir, "movie_tags", "user_id\tmovie_id\ttag_id\ttagged_at",
                f -> d.movieTags.add(new long[]{Tsv.i(f[0]), Tsv.i(f[1]), Tsv.i(f[2]), Tsv.num(f[3])}));
        return d;
    }

    /** Отображение long → long открытой адресацией (ключ ≠ {@link Long#MIN_VALUE}) — без упаковки в объекты. */
    static final class LongMap {
        private static final long EMPTY = Long.MIN_VALUE;
        private long[] keys, vals;
        private int size, mask;

        LongMap(int expected) {
            int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
            keys = new long[cap];
            vals = new long[cap];
            Arrays.fill(keys, EMPTY);
            mask = cap - 1;
        }

        private int slot(long k) {
            int h = (int) io.hammerhall.data.core.random.Rng.mix64(k) & mask;
            while (keys[h] != EMPTY && keys[h] != k) h = (h + 1) & mask;
            return h;
        }

        /** Значение или {@code def}. */
        long get(long k, long def) {
            int h = slot(k);
            return keys[h] == EMPTY ? def : vals[h];
        }

        boolean has(long k) {
            return keys[slot(k)] != EMPTY;
        }

        /** Положить, если ключа ещё нет. */
        void putIfAbsent(long k, long v) {
            int h = slot(k);
            if (keys[h] != EMPTY) return;
            keys[h] = k;
            vals[h] = v;
            if (++size * 2 > keys.length) rehash();
        }

        private void rehash() {
            long[] ok = keys, ov = vals;
            keys = new long[ok.length * 2];
            vals = new long[ok.length * 2];
            Arrays.fill(keys, EMPTY);
            mask = keys.length - 1;
            size = 0;
            for (int i = 0; i < ok.length; i++) if (ok[i] != EMPTY) putIfAbsent(ok[i], ov[i]);
        }
    }

    /** Ключ пары (пользователь, фильм). */
    static long pair(long user, long movie) {
        return (user << 32) | (movie & 0xffffffffL);
    }

    // ================================================================ проверки

    /** Нарушения проверки: число и первые примеры. */
    private static final class V {
        long n;
        final List<String> ex = new ArrayList<>();

        void add(String s) {
            n++;
            if (ex.size() < 5) ex.add(s);
        }

        void report(CheckReport r, String name, String ok) {
            r.expectNone(name, n, ex, ok);
        }
    }

    static void run(Path dir, Inputs x, Planted planted, CheckReport r) throws IOException {
        run(dir, x, planted, r, PublicNames::isInternal);
    }

    /** То же с другим признаком внутреннего имени (тесты — на подставных словах: сами имена в коде не пишем). */
    static void run(Path dir, Inputs x, Planted planted, CheckReport r, java.util.function.Predicate<String> internal) throws IOException {
        Data d = load(dir, x.in());
        Run run = new Run(d, x, planted, r);
        run.internal = internal;
        run.all();
    }

    /** Прогон проверок над прочитанными таблицами. */
    static final class Run {
        final Data d;
        final Inputs x;
        final Planted planted;
        final CheckReport r;
        final double widen;
        java.util.function.Predicate<String> internal = PublicNames::isInternal;
        /** Подписки пользователя (индексы по началу), просмотры пользователя (по времени). */
        final int[][] subsOf, viewsOf;

        Run(Data d, Inputs x, Planted planted, CheckReport r) {
            this.d = d;
            this.x = x;
            this.planted = planted;
            this.r = r;
            widen = switch (x.profile()) {
                case FULL -> 1.0;
                case MEDIUM -> 2.0;
                case SMALL -> 3.0;
            };
            subsOf = group(d.sUser, d.ns, d.nu);
            viewsOf = group(d.vUser, d.nv, d.nu);
        }

        /** Индексы строк по пользователю (id с 1), в порядке строк. */
        static int[][] group(int[] user, int n, int nu) {
            int[] cnt = new int[nu + 2];
            for (int i = 0; i < n; i++) if (user[i] >= 1 && user[i] <= nu) cnt[user[i]]++;
            int[][] out = new int[nu + 1][];
            for (int u = 0; u <= nu; u++) out[u] = new int[cnt[u]];
            int[] fill = new int[nu + 2];
            for (int i = 0; i < n; i++) if (user[i] >= 1 && user[i] <= nu) out[user[i]][fill[user[i]]++] = i;
            return out;
        }

        String t(long s) {
            return s < 0 ? "—" : java.time.Instant.ofEpochSecond(s).toString();
        }

        void all() {
            Runnable[] parts = {this::ddl, this::users, this::devices, this::subscriptions, this::payments, this::catalog,
                    this::purchases, this::views, this::concurrency, this::ratings, this::reviews, this::votesAndFollows,
                    this::watchAndCollections, this::time, this::texts, this::patterns};
            String[] names = {"ограничения DDL", "пользователи", "устройства", "подписки", "оплаты", "каталог", "покупки",
                    "просмотры", "одновременность", "оценки", "рецензии", "голоса и подписки на людей", "список и подборки",
                    "время", "тексты", "узоры"};
            for (int i = 0; i < parts.length; i++) {
                try {
                    parts[i].run();
                } catch (RuntimeException e) {
                    // испорченные таблицы не роняют самопроверку: провал с причиной
                    r.fail("site: проверки — " + names[i], "не выполнены: " + e);
                }
            }
        }

        // ------------------------------------------------------------ ограничения DDL

        void ddl() {
            V v = new V();
            for (Map.Entry<Integer, long[]> p : d.promo.entrySet()) if (p.getValue()[4] < p.getValue()[3]) v.add("promo " + p.getKey());
            for (int i = 0; i < d.nu; i++) {
                if ((d.uChannel[i] == Audience.REFERRAL) != (d.uRef[i] > 0)) v.add("user " + (i + 1) + ": referral");
                if (d.uDel[i] >= 0 && d.uDel[i] <= d.uReg[i]) v.add("user " + (i + 1) + ": deleted");
            }
            for (int i = 0; i < d.nd; i++) if (d.dLast[i] < d.dFirst[i]) v.add("device " + (i + 1));
            for (int i = 0; i < d.ns; i++) {
                if (d.sEnds[i] < d.sStart[i]) v.add("subscription " + (i + 1) + ": ends");
                if (d.sTrial[i] >= 0 && (d.sTrial[i] < d.sStart[i] || d.sTrial[i] > d.sEnds[i])) v.add("subscription " + (i + 1) + ": trial");
            }
            for (int i = 0; i < d.np; i++) {
                if ((d.pSub[i] > 0) == (d.pPur[i] > 0)) v.add("payment " + (i + 1) + ": one_target");
                if ((d.pStatus[i] == 2) != (d.pRefund[i] >= 0) || (d.pRefund[i] >= 0 && d.pRefund[i] <= d.pAt[i])) {
                    v.add("payment " + (i + 1) + ": refund");
                }
            }
            for (Map.Entry<Integer, List<long[]>> e : d.windows.entrySet()) {
                for (long[] w : e.getValue()) {
                    if (w[1] >= 0 && w[1] <= w[0]) v.add("catalog " + e.getKey() + ": window");
                    if ((w[2] == Catalog.RENT_BUY) != (w[3] >= 0)) v.add("catalog " + e.getKey() + ": prices");
                }
            }
            for (int i = 0; i < d.nr; i++) if (d.rValue[i] < 1 || d.rValue[i] > 10) v.add("rating " + d.rUser[i] + "/" + d.rMovie[i]);
            for (int i = 0; i < d.nw; i++) if (d.wRating[i] < 1 || d.wRating[i] > 10) v.add("review " + (i + 1) + ": rating");
            for (long[] w : d.watch) if ((w[3] < 0) != (w[4] < 0)) v.add("watchlist " + w[0] + "/" + w[1]);
            for (long[] f : d.follows) {
                if (f[0] == f[1]) v.add("follow " + f[0] + " → себя");
                if (f[3] >= 0 && f[3] <= f[2]) v.add("follow " + f[0] + "/" + f[1] + ": unfollow");
            }
            for (int[] p : d.plan.values()) if (p[0] != 1 && p[0] != 12) v.add("plan: period");
            v.report(r, "site: ограничения DDL (CHECK) соблюдены", "промокоды, пользователи, устройства, подписки, оплаты, каталог, "
                    + "оценки, рецензии, список, подписки на людей, тарифы");
        }

        // ------------------------------------------------------------ пользователи и устройства

        void users() {
            Set<Integer> cityIds = new HashSet<>();
            for (int c : x.cities().id) cityIds.add(c);
            V city = new V(), order = new V(), promo = new V(), login = new V(), age = new V();
            Map<Integer, Integer> uses = new HashMap<>();
            for (int i = 0; i < d.nu; i++) {
                if (!cityIds.contains(d.uCity[i])) city.add("user " + (i + 1) + ": city " + d.uCity[i]);
                if (i > 0 && d.uReg[i] < d.uReg[i - 1]) order.add("user " + (i + 1) + ": регистрация раньше предыдущего");
                if (d.uRef[i] > 0 && d.uReg[d.uRef[i] - 1] >= d.uReg[i]) order.add("user " + (i + 1) + ": пригласивший позже");
                if (d.uPromo[i] > 0) {
                    long[] p = d.promo.get(d.uPromo[i]);
                    long day = SiteRules.mskDate(d.uReg[i]).toEpochDay();
                    if (p == null || day < p[3] || day > p[4]) promo.add("user " + (i + 1) + ": промокод не действовал");
                    else if ((p[2] == 1) != (d.uChannel[i] == Audience.PARTNER)) promo.add("user " + (i + 1) + ": партнёрский код не у партнёрского канала");
                    uses.merge(d.uPromo[i], 1, Integer::sum);
                }
                if (!LOGIN.matcher(d.uLogin[i]).matches()) login.add("user " + (i + 1) + ": «" + d.uLogin[i] + "»");
                int years = SiteRules.mskDate(d.uReg[i]).getYear() - d.uBirth[i];
                if (years < 16 || years > 75) age.add("user " + (i + 1) + ": " + years + " лет при регистрации");
            }
            for (Map.Entry<Integer, Integer> e : uses.entrySet()) {
                long max = d.promo.get(e.getKey())[5];
                if (max >= 0 && e.getValue() > max) promo.add("promo " + e.getKey() + ": применений " + e.getValue() + " > " + max);
            }
            city.report(r, "site: город пользователя — из справочника городов World", "пользователей " + d.nu + ", городов " + cityIds.size());
            order.report(r, "site: пользователи — по порядку регистрации, пригласивший — раньше", "пользователей " + d.nu);
            promo.report(r, "site: промокод при регистрации — действовал, вид по каналу, не сверх предела", "с промокодом "
                    + uses.values().stream().mapToInt(Integer::intValue).sum());
            login.report(r, "site: логин — латиница, цифры, «_», 4–20 знаков", "логинов " + d.nu);
            age.report(r, "site: возраст пользователя при регистрации — 16–75 лет", "пользователей " + d.nu);
        }

        void devices() {
            V v = new V();
            for (int i = 0; i < d.nd; i++) {
                int u = d.dUser[i];
                if (d.dFirst[i] < d.uReg[u - 1]) v.add("device " + (i + 1) + ": раньше регистрации");
                if (i > 0 && d.dFirst[i] < d.dFirst[i - 1]) v.add("device " + (i + 1) + ": id не по первому появлению");
            }
            v.report(r, "site: устройство — после регистрации пользователя, id по первому появлению", "устройств " + d.nd);
        }

        // ------------------------------------------------------------ подписки

        void subscriptions() {
            V overlap = new V(), state = new V();
            for (int u = 1; u <= d.nu; u++) {
                int[] s = subsOf[u];
                for (int k = 1; k < s.length; k++) {
                    if (d.sStart[s[k]] < d.sEnds[s[k - 1]]) overlap.add("user " + u + ": подписки " + (s[k - 1] + 1) + " и " + (s[k] + 1));
                }
                for (int k = 0; k < s.length; k++) {
                    int i = s[k];
                    if (d.sStart[i] < d.uReg[u - 1]) state.add("subscription " + (i + 1) + ": раньше регистрации");
                    if (d.sTrial[i] >= 0) {
                        long len = d.sTrial[i] - d.sStart[i];
                        boolean cut = d.sReason[i] == 7;
                        if (!cut && len != 7 * DAY && len != 14 * DAY) state.add("subscription " + (i + 1) + ": пробных " + len / (double) DAY + " дн.");
                    }
                    byte st = d.sStatus[i];
                    boolean open = st == 0 || st == 1;
                    if (open != d.sAuto[i]) state.add("subscription " + (i + 1) + ": auto_renew при " + SiteSchema.SUB_STATUS[st]);
                    if (open != (d.sCancel[i] < 0)) state.add("subscription " + (i + 1) + ": cancelled_at при " + SiteSchema.SUB_STATUS[st]);
                    if (open && d.sEnds[i] < x.cutoff()) state.add("subscription " + (i + 1) + ": идёт, а ends_at до среза");
                    if (st == 0 && (d.sTrial[i] < 0 || d.sTrial[i] < x.cutoff())) state.add("subscription " + (i + 1) + ": trial без пробного на срез");
                    if (!open && d.sCancel[i] > d.sEnds[i]) state.add("subscription " + (i + 1) + ": отмена после конца");
                    if (st == 3 && (d.sReason[i] != 5 || d.sCancel[i] != d.sEnds[i])) state.add("subscription " + (i + 1) + ": expired не по неудачам");
                    if (st == 4) {
                        if (d.sReason[i] != 6) state.add("subscription " + (i + 1) + ": switched без plan_change");
                        long uEnd = d.uDel[u - 1] >= 0 ? Math.min(d.uDel[u - 1], x.cutoff()) : x.cutoff();
                        if (d.sEnds[i] < uEnd) {
                            boolean next = k + 1 < s.length && d.sStart[s[k + 1]] == d.sEnds[i]
                                    && Plans.get(d.sPlan[s[k + 1]]).tier() < Plans.get(d.sPlan[i]).tier();
                            if (!next) state.add("subscription " + (i + 1) + ": переход без подписки ниже с конца периода");
                        }
                    }
                    if (st == 2 && (d.sReason[i] == 5 || d.sReason[i] == 6)) state.add("subscription " + (i + 1) + ": cancelled с причиной перехода");
                    if (d.sPlan[i] == Plans.FREE) state.add("subscription " + (i + 1) + ": на бесплатном тарифе");
                }
            }
            overlap.report(r, "site: подписки пользователя не пересекаются", "подписок " + d.ns);
            state.report(r, "site: подписка — пробный 7 или 14 дней, состояние на срез согласовано с моментами",
                    "подписок " + d.ns);
        }

        /** Опора списаний подписки: конец пробного или начало. */
        long anchor(int s) {
            return d.sTrial[s] >= 0 ? d.sTrial[s] : d.sStart[s];
        }

        /** Номер периода оплаты подписки для момента. */
        int period(int s, long at) {
            int months = d.plan.get(d.sPlan[s])[0];
            long a = anchor(s);
            int k = 0;
            while (SiteRules.plusMonths(a, (k + 1) * months) <= at) k++;
            return k;
        }

        long priceOn(int plan, long at) {
            long day = SiteRules.mskDate(at).toEpochDay();
            long v = -1;
            for (long[] p : d.price.getOrDefault(plan, List.of())) if (p[0] <= day) v = p[1];
            return v;
        }

        void payments() {
            V inside = new V(), amount = new V(), retry = new V(), once = new V(), purchase = new V();
            // первая подписка пользователя, пришедшего в окне, — со скидкой промокода на первый период
            int[] firstSub = new int[d.nu + 1];
            Arrays.fill(firstSub, -1);
            for (int u = 1; u <= d.nu; u++) if (subsOf[u].length > 0 && d.uReg[u - 1] >= x.w0()) firstSub[u] = subsOf[u][0];
            int[] purchasePays = new int[d.nb];
            int[] subOf = new int[d.np];
            for (int i = 0; i < d.np; i++) {
                subOf[i] = d.pSub[i];
                if (d.pSub[i] > 0) {
                    int s = d.pSub[i] - 1;
                    if (d.pUser[i] != d.sUser[s]) inside.add("payment " + (i + 1) + ": не того пользователя");
                    if (d.pAt[i] < d.sStart[s] || d.pAt[i] > d.sEnds[s]) inside.add("payment " + (i + 1) + ": вне подписки " + (s + 1));
                } else if (d.pPur[i] > 0) {
                    int b = d.pPur[i] - 1;
                    if (d.pAmount[i] != d.bPrice[b] || d.pUser[i] != d.bUser[b] || d.pStatus[i] == 1) {
                        purchase.add("payment " + (i + 1) + ": не по покупке " + (b + 1));
                    }
                    if (!planted.has("double_charge", i + 1)) {
                        if (d.pAt[i] != d.bAt[b]) purchase.add("payment " + (i + 1) + ": не в момент покупки");
                        purchasePays[b]++;
                    }
                }
            }
            for (int b = 0; b < d.nb; b++) if (purchasePays[b] != 1) purchase.add("purchase " + (b + 1) + ": оплат " + purchasePays[b]);
            // оплаты подписки — по периодам: попытки периода, оплата, повторы
            int[][] bySub = group(subOf, d.np, d.ns);
            long periods = 0;
            for (int s = 0; s < d.ns; s++) {
                int[] ps = bySub[s + 1];
                // по моменту (id — по моменту, но порча может добавить строку в конец)
                Integer[] ord = new Integer[ps.length];
                for (int j = 0; j < ps.length; j++) ord[j] = ps[j];
                Arrays.sort(ord, (p1, p2) -> d.pAt[p1] != d.pAt[p2] ? Long.compare(d.pAt[p1], d.pAt[p2]) : Integer.compare(p1, p2));
                for (int j = 0; j < ps.length; j++) ps[j] = ord[j];
                int u = d.sUser[s];
                int lastK = -1, paid = 0, attempt = 0, fails = 0;
                long first = 0, lastAt = 0;
                for (int j = 0; j <= ps.length; j++) {
                    int i = j < ps.length ? ps[j] : -1;
                    int k = i >= 0 ? period(s, d.pAt[i]) : -2;
                    if (k != lastK && lastK >= 0) {
                        periods++;
                        if (paid > 1) once.add("subscription " + (s + 1) + ": оплат периода " + paid);
                        if (paid == 0 && attempt == 3 && !(d.sStatus[s] == 3 && d.sEnds[s] == lastAt)) {
                            retry.add("subscription " + (s + 1) + ": три неудачи, а не истекла");
                        }
                        if (paid == 0 && attempt < 3) {
                            long next = first + (attempt == 1 ? DAY : 3 * DAY);
                            long end = d.uDel[u - 1] >= 0 ? Math.min(d.uDel[u - 1], x.cutoff()) : x.cutoff();
                            if (next < end - 900 || k >= 0) retry.add("subscription " + (s + 1) + ": нет повтора списания");
                        }
                        if (d.sStatus[s] == 3 && i < 0 && fails != 3) retry.add("subscription " + (s + 1) + ": expired, неудач " + fails);
                    }
                    if (i < 0) break;
                    if (k != lastK) {
                        lastK = k;
                        paid = 0;
                        attempt = 0;
                        fails = 0;
                        first = d.pAt[i];
                    }
                    long want = priceOn(d.sPlan[s], d.pAt[i]);
                    if (k == 0 && firstSub[u] == s && d.uPromo[u - 1] > 0) {
                        long[] p = d.promo.get(d.uPromo[u - 1]);
                        if (p[0] == Promos.DISCOUNT) want = Plans.discounted(want, (int) p[1]);
                    }
                    if (d.pAmount[i] != want) amount.add("payment " + (i + 1) + ": " + d.pAmount[i] + " ≠ " + want);
                    if (planted.has("double_charge", i + 1)) continue;
                    long off = d.pAt[i] - first;
                    long wantOff = attempt == 0 ? 0 : attempt == 1 ? DAY : 3 * DAY;
                    if (Math.abs(off - wantOff) > 900) retry.add("payment " + (i + 1) + ": попытка " + (attempt + 1) + " через " + off + " с");
                    if (d.pStatus[i] != 1) paid++;
                    else {
                        if (paid > 0) retry.add("payment " + (i + 1) + ": неудача после оплаты периода");
                        fails++;
                    }
                    attempt++;
                    lastAt = d.pAt[i];
                }
                if (d.sStatus[s] == 3 && ps.length == 0) retry.add("subscription " + (s + 1) + ": expired без списаний");
            }
            inside.report(r, "site: оплата подписки — её пользователя, внутри её периода", "оплат " + d.np);
            amount.report(r, "site: сумма списания = цена тарифа на дату (со скидкой промокода на первый период)", "периодов " + periods);
            retry.report(r, "site: неудачное списание — повтор через 1 и 3 дня, три неудачи — подписка истекает", "периодов " + periods);
            once.report(r, "site: за период подписки — одна оплата (кроме заложенных двойных списаний)", "двойных списаний заложено "
                    + planted.count("double_charge"));
            purchase.report(r, "site: оплата покупки — её сумма, пользователь и момент, одна на покупку", "покупок " + d.nb);
        }

        // ------------------------------------------------------------ каталог и покупки

        /** Окно фильма, открытое в момент {@code t}, или {@code null}. */
        long[] windowAt(int movieId, long t) {
            List<long[]> ws = d.windows.get(movieId);
            if (ws == null) return null;
            for (long[] w : ws) if (w[0] <= t && (w[1] < 0 || t < w[1])) return w;
            return null;
        }

        void catalog() {
            V v = new V();
            int share = 0;
            for (Map.Entry<Integer, List<long[]>> e : d.windows.entrySet()) {
                if (!d.movieIndex.containsKey(e.getKey())) v.add("catalog: фильм " + e.getKey() + " не профиля");
                List<long[]> ws = e.getValue();
                share++;
                for (int k = 0; k < ws.size(); k++) {
                    long[] w = ws.get(k);
                    if (k > 0 && (ws.get(k - 1)[1] < 0 || ws.get(k - 1)[1] > w[0])) v.add("catalog " + e.getKey() + ": окна пересекаются");
                    if (w[0] != SiteRules.mskMidnight(SiteRules.mskDate(w[0]))) v.add("catalog " + e.getKey() + ": приход не в 00:00 по Москве");
                    if (w[2] == Catalog.RENT_BUY) {
                        long days = (w[5] - w[0]) / DAY;
                        if (w[4] < 0 || w[5] < 0 || days < 60 || days > 120) v.add("catalog " + e.getKey() + ": rent_buy без цен или входа в подписку");
                    } else if (w[4] >= 0 || w[5] >= 0) {
                        v.add("catalog " + e.getKey() + ": цены у окна без проката");
                    }
                }
            }
            v.report(r, "site: каталог — фильмы профиля, окна не пересекаются, у проката — цены и вход в подписку через 60–120 дней",
                    String.format(Locale.ROOT, "фильмов в каталоге %d из %d (%.1f %%)", share, x.in().movies().size(),
                            100.0 * share / Math.max(1, x.in().movies().size())));
        }

        void purchases() {
            V v = new V();
            for (int b = 0; b < d.nb; b++) {
                long[] w = windowAt(d.bMovie[b], d.bAt[b]);
                if (w == null || w[2] != Catalog.RENT_BUY) {
                    v.add("purchase " + (b + 1) + ": фильм не в прокате");
                    continue;
                }
                long want = d.bBuy[b] ? w[4] : (d.bAt[b] < w[0] + SiteRules.PREMIERE_DAYS * DAY ? w[3] : SiteRules.rentLater(w[3]));
                if (d.bPrice[b] != want) v.add("purchase " + (b + 1) + ": цена " + d.bPrice[b] + " ≠ " + want);
                if (d.bBuy[b] ? d.bExp[b] >= 0 : d.bExp[b] != d.bAt[b] + Life.RENT_TIME) v.add("purchase " + (b + 1) + ": срок");
            }
            v.report(r, "site: покупка — фильм в прокате, цена окна (премьерная — первые 60 дней), прокат — 48 ч", "покупок " + d.nb);
        }

        // ------------------------------------------------------------ просмотры

        /** Подписка пользователя, дающая доступ в момент (индекс) или −1. */
        int subAt(int u, long t) {
            int[] s = subsOf[u];
            for (int k = s.length - 1; k >= 0; k--) {
                int i = s[k];
                if (d.sStart[i] <= t && t < d.sEnds[i]) return i;
                if (d.sStart[i] <= t) return -1;
            }
            return -1;
        }

        void views() {
            V inCat = new V(), access = new V(), length = new V(), who = new V();
            int[][] pg = group(d.bUser, d.nb, d.nu);
            for (int i = 0; i < d.nv; i++) {
                int u = d.vUser[i];
                long t = d.vAt[i];
                int mi = d.movieIndex.getOrDefault(d.vMovie[i], -1);
                SiteInput.Movie m = mi >= 0 ? x.in().movies().get(mi) : null;
                long[] w = windowAt(d.vMovie[i], t);
                boolean grace = planted.has("rent_grace", i + 1);
                if (w == null && !grace) inCat.add("view " + (i + 1) + ": фильм " + d.vMovie[i] + " не в каталоге в " + t(t));
                byte src = d.vSource[i];
                switch (src) {
                    case 0 -> {
                        int s = subAt(u, t);
                        if (s < 0) access.add("view " + (i + 1) + ": по подписке без подписки");
                        else {
                            if (d.vQuality[i] > d.plan.get(d.sPlan[s])[2]) access.add("view " + (i + 1) + ": качество выше тарифа");
                            if (w != null && !(w[2] != Catalog.RENT_BUY || w[5] <= t)) access.add("view " + (i + 1) + ": фильм ещё не в подписке");
                        }
                    }
                    case 3 -> {
                        if (w != null && w[2] != Catalog.FREE) access.add("view " + (i + 1) + ": бесплатно, а фильм не бесплатный");
                        if (d.vQuality[i] != 0) access.add("view " + (i + 1) + ": бесплатно не в SD");
                        if (subAt(u, t) >= 0) access.add("view " + (i + 1) + ": бесплатно у подписчика");
                    }
                    default -> {
                        boolean ok = false;
                        for (int b : pg[u]) {
                            if (d.bMovie[b] != d.vMovie[i] || d.bAt[b] > t) continue;
                            if (src == 2 && d.bBuy[b]) ok = true;
                            if (src == 1 && !d.bBuy[b] && t < d.bExp[b]) ok = true;
                        }
                        if (!ok) access.add("view " + (i + 1) + ": " + SiteSchema.SOURCES[src] + " без покупки");
                    }
                }
                if (m != null && m.runtime() != null && d.vWatched[i] > m.runtime() * 1.3 && !planted.has("long_view", i + 1)) {
                    length.add("view " + (i + 1) + ": " + d.vWatched[i] + " мин при хронометраже " + m.runtime());
                }
                int dev = d.vDev[i] - 1;
                if (d.dUser[dev] != u) who.add("view " + (i + 1) + ": устройство чужое");
                else if (t < d.dFirst[dev] || t > d.dLast[dev]) who.add("view " + (i + 1) + ": вне появления устройства");
                if (t <= d.uReg[u - 1]) who.add("view " + (i + 1) + ": раньше регистрации");
                if (d.uDel[u - 1] >= 0 && t >= d.uDel[u - 1]) who.add("view " + (i + 1) + ": после удаления");
                if (i > 0 && t < d.vAt[i - 1]) who.add("view " + (i + 1) + ": id не по началу");
            }
            inCat.report(r, "site: просмотр — фильм в каталоге в момент начала (кроме льготы проката)", "льгот заложено "
                    + planted.count("rent_grace"));
            access.report(r, "site: просмотр — доступ был (подписка, прокат, покупка, бесплатно), качество — по тарифу", "просмотров " + d.nv);
            length.report(r, "site: просмотр — не дольше хронометража + 30 % (кроме паузы)", "пауз заложено " + planted.count("long_view"));
            who.report(r, "site: просмотр — устройство пользователя, пользователь зарегистрирован и не удалён", "просмотров " + d.nv);
        }

        void concurrency() {
            V v = new V();
            for (int u = 1; u <= d.nu; u++) {
                int[] vs = viewsOf[u];
                for (int a = 0; a < vs.length; a++) {
                    int i = vs[a];
                    long ti = d.vAt[i];
                    int same = 1;
                    for (int b = a - 1; b >= 0 && a - b <= 30; b--) {
                        int j = vs[b];
                        long je = d.vAt[j] + d.vWatched[j] * SiteRules.MINUTE;
                        if (je <= ti) continue;
                        if (d.vDev[j] == d.vDev[i]) v.add("user " + u + ": два просмотра на устройстве " + d.vDev[i] + " в " + t(ti));
                        if (d.vSource[i] == 0 && d.vSource[j] == 0) same++;
                    }
                    if (d.vSource[i] == 0) {
                        int s = subAt(u, ti);
                        if (s >= 0 && same > d.plan.get(d.sPlan[s])[1]) v.add("user " + u + ": " + same + " потоков в " + t(ti));
                    }
                }
            }
            v.report(r, "site: одновременных просмотров по подписке — не больше потоков тарифа, на устройстве — один", "просмотров " + d.nv);
        }

        // ------------------------------------------------------------ оценки, рецензии, голоса, люди

        void ratings() {
            V v = new V();
            LongMap firstView = firstViews();
            for (int i = 0; i < d.nr; i++) {
                long k = pair(d.rUser[i], d.rMovie[i]);
                long fv = firstView.get(k, Long.MAX_VALUE);
                if (fv > d.rAt[i] && !planted.has("rating_without_view", k)) {
                    v.add("rating " + d.rUser[i] + "/" + d.rMovie[i] + ": без просмотра");
                }
            }
            v.report(r, "site: оценка — после просмотра фильма (кроме «смотрел раньше»)", "оценок " + d.nr + ", без просмотра заложено "
                    + planted.count("rating_without_view"));
        }

        private LongMap firstViews;

        /** Первый просмотр (пользователь, фильм) → момент (просмотры — по времени). */
        LongMap firstViews() {
            if (firstViews == null) {
                firstViews = new LongMap(d.nv);
                for (int i = 0; i < d.nv; i++) firstViews.putIfAbsent(pair(d.vUser[i], d.vMovie[i]), d.vAt[i]);
            }
            return firstViews;
        }

        void reviews() {
            V v = new V(), hidden = new V();
            Map<Long, Integer> rating = new HashMap<>();
            for (int i = 0; i < d.nr; i++) rating.put(((long) d.rUser[i] << 32) | d.rMovie[i], d.rValue[i]);
            for (int i = 0; i < d.nw; i++) {
                Integer rv = rating.get(((long) d.wUser[i] << 32) | d.wMovie[i]);
                if (rv == null) v.add("review " + (i + 1) + ": нет оценки фильма");
                else if (rv != d.wRating[i] && !planted.has("review_rating_changed", i + 1)) v.add("review " + (i + 1) + ": оценка " + d.wRating[i] + " ≠ " + rv);
                if (d.wAt[i] <= d.uReg[d.wUser[i] - 1]) v.add("review " + (i + 1) + ": раньше регистрации");
                if (d.wEdit[i] >= 0 && d.wEdit[i] <= d.wAt[i]) v.add("review " + (i + 1) + ": правка раньше рецензии");
                boolean deleted = d.uDel[d.wUser[i] - 1] >= 0;
                if (deleted && !d.wHidden[i]) hidden.add("review " + (i + 1) + ": удалённого пользователя — видна");
                if (deleted != planted.has("deleted_user_review", i + 1)) hidden.add("review " + (i + 1) + ": не учтена как заложенная");
                if (i > 0 && d.wAt[i] < d.wAt[i - 1]) v.add("review " + (i + 1) + ": id не по моменту");
            }
            v.report(r, "site: рецензия — есть оценка фильма, оценка та же (кроме изменённой позже)", "рецензий " + d.nw
                    + ", с изменённой оценкой заложено " + planted.count("review_rating_changed"));
            hidden.report(r, "site: рецензии удалённого пользователя — скрыты", "заложено " + planted.count("deleted_user_review"));
        }

        void votesAndFollows() {
            V v = new V(), f = new V();
            for (long[] x0 : d.votes) {
                int w = (int) x0[0] - 1, u = (int) x0[1];
                if (d.wUser[w] == u) v.add("vote " + (w + 1) + "/" + u + ": за свою рецензию");
                if (x0[3] <= d.wAt[w]) v.add("vote " + (w + 1) + "/" + u + ": раньше рецензии");
                if (x0[3] <= d.uReg[u - 1] || (d.uDel[u - 1] >= 0 && x0[3] >= d.uDel[u - 1])) v.add("vote " + (w + 1) + "/" + u + ": голосующего нет");
            }
            for (long[] x0 : d.follows) {
                int a = (int) x0[0], b = (int) x0[1];
                if (a == b) f.add("follow " + a + " на себя");
                if (x0[2] <= d.uReg[a - 1] || x0[2] <= d.uReg[b - 1]) f.add("follow " + a + "/" + b + ": раньше регистрации");
                if (x0[3] >= 0 && x0[3] <= x0[2]) f.add("follow " + a + "/" + b + ": отписка не позже подписки");
            }
            v.report(r, "site: голос — не за свою рецензию, после неё, голосующий зарегистрирован", "голосов " + d.votes.size());
            f.report(r, "site: подписка на людей — не на себя, после регистрации обоих, отписка позже", "подписок " + d.follows.size());
        }

        /** Ключ (пользователь, фильм, момент) — перемешанный, для множества просмотров. */
        static long at(long user, long movie, long t) {
            long h = io.hammerhall.data.core.random.Rng.mix64(user * 0x9e3779b97f4a7c15L ^ movie);
            h = io.hammerhall.data.core.random.Rng.mix64(h ^ t);
            return h == Long.MIN_VALUE ? 0 : h;
        }

        void watchAndCollections() {
            V w = new V(), c = new V();
            LongMap viewAt = new LongMap(d.nv);
            for (int i = 0; i < d.nv; i++) viewAt.putIfAbsent(at(d.vUser[i], d.vMovie[i], d.vAt[i]), 1);
            for (long[] e : d.watch) {
                if (e[2] <= d.uReg[(int) e[0] - 1]) w.add("watchlist " + e[0] + "/" + e[1] + ": раньше регистрации");
                if (e[3] >= 0 && e[3] <= e[2]) w.add("watchlist " + e[0] + "/" + e[1] + ": убран раньше добавления");
                if (e[4] == 1 && !viewAt.has(at(e[0], e[1], e[3]))) w.add("watchlist " + e[0] + "/" + e[1] + ": «посмотрел» без просмотра");
            }
            Map<Long, List<long[]>> items = new HashMap<>();
            for (long[] it : d.colItems) items.computeIfAbsent(it[0], k -> new ArrayList<>()).add(it);
            for (Map.Entry<Integer, long[]> e : d.collections.entrySet()) {
                long[] col = e.getValue();
                if (col[1] <= d.uReg[(int) col[0] - 1]) c.add("collection " + e.getKey() + ": раньше регистрации");
                List<long[]> its = items.getOrDefault((long) e.getKey(), List.of());
                List<long[]> sorted = new ArrayList<>(its);
                sorted.sort((a, b) -> Long.compare(a[2], b[2]));
                for (int k = 0; k < sorted.size(); k++) {
                    long[] it = sorted.get(k);
                    if (it[2] != k + 1) c.add("collection " + e.getKey() + ": места не подряд");
                    if (it[3] < col[1]) c.add("collection " + e.getKey() + ": фильм раньше подборки");
                    if (k > 0 && it[3] < sorted.get(k - 1)[3]) c.add("collection " + e.getKey() + ": места не по порядку добавления");
                }
            }
            for (long[] tg : d.movieTags) {
                if (tg[3] <= d.uReg[(int) tg[0] - 1]) c.add("movie_tags " + tg[0] + "/" + tg[1] + ": раньше регистрации");
            }
            w.report(r, "site: список «посмотреть» — после регистрации, «посмотрел» — в момент просмотра", "записей " + d.watch.size());
            c.report(r, "site: подборки и теги — после регистрации, фильмы подборки — после её создания, места 1…n по порядку",
                    "подборок " + d.collections.size() + ", фильмов в них " + d.colItems.size() + ", тегов фильмов " + d.movieTags.size());
        }

        // ------------------------------------------------------------ время

        void time() {
            long cut = x.cutoff(), w0 = x.w0();
            V v = new V();
            for (int i = 0; i < d.nu; i++) {
                fact(v, "users.registered_at", d.uReg[i], -1, cut);
                fact(v, "users.deleted_at", d.uDel[i], -1, cut);
            }
            for (int i = 0; i < d.nd; i++) {
                fact(v, "devices.first_seen_at", d.dFirst[i], -1, cut);
                fact(v, "devices.last_seen_at", d.dLast[i], -1, cut);
            }
            for (int i = 0; i < d.ns; i++) {
                fact(v, "subscriptions.started_at", d.sStart[i], -1, cut);
                fact(v, "subscriptions.cancelled_at", d.sCancel[i], w0, cut);
            }
            for (int i = 0; i < d.np; i++) {
                fact(v, "payments.created_at", d.pAt[i], w0, cut);
                fact(v, "payments.refunded_at", d.pRefund[i], w0, cut);
            }
            for (List<long[]> ws : d.windows.values()) {
                for (long[] w : ws) {
                    fact(v, "catalog.added_at", w[0], -1, cut);
                    fact(v, "catalog.removed_at", w[1], -1, cut);
                }
            }
            for (int i = 0; i < d.nb; i++) fact(v, "purchases.created_at", d.bAt[i], w0, cut);
            for (int i = 0; i < d.nv; i++) {
                fact(v, "views.started_at", d.vAt[i], w0, cut);
                if (d.vAt[i] + d.vWatched[i] * SiteRules.MINUTE > cut) v.add("views " + (i + 1) + ": досмотрен после среза");
            }
            for (int i = 0; i < d.nr; i++) fact(v, "ratings.rated_at", d.rAt[i], w0, cut);
            for (int i = 0; i < d.nw; i++) {
                fact(v, "reviews.created_at", d.wAt[i], w0, cut);
                fact(v, "reviews.edited_at", d.wEdit[i], w0, cut);
            }
            for (long[] e : d.votes) fact(v, "review_votes.voted_at", e[3], w0, cut);
            for (long[] e : d.watch) {
                fact(v, "watchlist.added_at", e[2], w0, cut);
                fact(v, "watchlist.removed_at", e[3], w0, cut);
            }
            for (long[] e : d.follows) {
                fact(v, "follows.followed_at", e[2], w0, cut);
                fact(v, "follows.unfollowed_at", e[3], w0, cut);
            }
            for (long[] e : d.collections.values()) fact(v, "collections.created_at", e[1], w0, cut);
            for (long[] e : d.colItems) fact(v, "collection_items.added_at", e[3], w0, cut);
            for (long[] e : d.movieTags) fact(v, "movie_tags.tagged_at", e[3], w0, cut);
            for (Map.Entry<Integer, long[]> p : d.promo.entrySet()) {
                if (SiteRules.mskMidnight(LocalDate.ofEpochDay(p.getValue()[3])) >= cut) v.add("promo " + p.getKey() + ": начал действовать после среза");
            }
            v.report(r, "site: нет событий после среза, события — внутри окна (предыстория — у пользователей, устройств, подписок, "
                    + "каталога)", "срез " + t(cut));
        }

        private void fact(V v, String what, long t, long from, long cut) {
            if (t < 0) return;
            if (t >= cut) v.add(what + " " + t(t) + " — после среза");
            else if (from >= 0 && t < from) v.add(what + " " + t(t) + " — до начала окна");
        }

        // ------------------------------------------------------------ тексты

        void texts() {
            V names = new V(), dup = new V(), shape = new V();
            for (int i = 0; i < d.nu; i++) {
                if (internal.test(d.uLogin[i]) || internal.test(d.uName[i])) names.add("user " + (i + 1));
            }
            Set<String> bodies = new HashSet<>();
            for (int i = 0; i < d.nw; i++) {
                if (internal.test(d.wTitle[i]) || internal.test(d.wBody[i])) names.add("review " + (i + 1));
                if (!bodies.add(d.wBody[i])) dup.add("review " + (i + 1) + ": текст повторяется");
                int words = words(d.wTitle[i]);
                int most = sentences(d.wBody[i], false), least = sentences(d.wBody[i], true);
                if (words < 3 || words > 8) shape.add("review " + (i + 1) + ": заголовок " + words + " слов");
                if (most < 2 || least > 6) shape.add("review " + (i + 1) + ": предложений " + least + "…" + most);
            }
            for (Map.Entry<Integer, String> e : d.collectionTitle.entrySet()) if (internal.test(e.getValue())) names.add("collection " + e.getKey());
            for (String tg : d.tagNames) if (internal.test(tg)) names.add("tag «" + tg + "»");
            if (d.tagNames.size() != x.tags()) shape.add("тегов " + d.tagNames.size() + " ≠ " + x.tags());
            names.report(r, "site: тексты (логины, имена, рецензии, подборки, теги) — без внутренних имён платформы", "рецензий "
                    + d.nw + ", подборок " + d.collections.size() + ", тегов " + d.tagNames.size());
            dup.report(r, "site: рецензии не повторяются целиком", "разных текстов " + bodies.size());
            shape.report(r, "site: рецензия — заголовок 3–8 слов, текст 2–6 предложений", "рецензий " + d.nw);
        }

        /** Слов в заголовке; название фильма в «…» — одно слово (у настоящих фильмов бывает и из десяти слов). */
        static int words(String s) {
            String flat = s.replaceAll("«[^»]*»", "«»").trim();
            return flat.isEmpty() ? 0 : flat.split("\\s+").length;
        }

        /**
         * Предложений в тексте: знак конца (. ! ? …), за ним — конец текста или пробел и заглавная буква, цифра («10 из
         * 10») или «; внутри «…» — не
         * считается (названия фильмов). {@code strict} — только после слова со строчной буквы или числа: инициалы и
         * сокращения в именах («Дж.», «Ста.») не конец — счёт не больше настоящего; без {@code strict} — не меньше.
         */
        static int sentences(String s, boolean strict) {
            int n = 0, quote = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '«') quote++;
                if (c == '»' && quote > 0) quote--;
                if (quote > 0 || (c != '.' && c != '!' && c != '?' && c != '…')) continue;
                int w = i;
                while (w > 0 && Character.isLetterOrDigit(s.charAt(w - 1))) w--;
                boolean word = !strict || (i > w && (Character.isLowerCase(s.charAt(w)) || Character.isDigit(s.charAt(i - 1))))
                        || (i > 0 && (s.charAt(i - 1) == '»' || s.charAt(i - 1) == ')'));
                int j = i + 1;
                while (j < s.length() && ".!?…\")".indexOf(s.charAt(j)) >= 0) j++;
                if (j >= s.length()) {
                    n++;
                    break;
                }
                if (word && s.charAt(j) == ' ' && j + 1 < s.length() && (Character.isUpperCase(s.charAt(j + 1))
                        || Character.isDigit(s.charAt(j + 1)) || s.charAt(j + 1) == '«')) n++;
                i = j - 1;
            }
            return n;
        }

        // ------------------------------------------------------------ узоры

        /** Коридор: значение в цели ± полуширина (у medium и small — шире); мало данных — только печать. */
        void corridor(String name, double value, double target, double half, long n, long nMin, String detail) {
            if (n < nMin) {
                r.ok(name, String.format(Locale.ROOT, "мало данных (%d < %d) — только печать: %.3f, цель %.3f; %s", n, nMin, value, target, detail));
                return;
            }
            double h = half * widen;
            boolean ok = Double.isFinite(value) && Math.abs(value - target) <= h;
            r.expect(name, ok, String.format(Locale.ROOT, "%.3f, цель %.3f ± %.3f; %s", value, target, h, detail));
        }

        void atLeast(String name, double value, double min, long n, long nMin, String detail) {
            if (n < nMin) {
                r.ok(name, String.format(Locale.ROOT, "мало данных (%d < %d) — только печать: %.3f; %s", n, nMin, value, detail));
                return;
            }
            double m = x.profile() == Profile.FULL ? min : x.profile() == Profile.MEDIUM ? 1 + (min - 1) * 0.6 : 1 + (min - 1) * 0.3;
            if (min < 1) m = min - 0.05 * (widen - 1);
            r.expect(name, Double.isFinite(value) && value >= m, String.format(Locale.ROOT, "%.3f, не меньше %.3f; %s", value, m, detail));
        }

        void patterns() {
            long cut = x.cutoff(), w0 = x.w0();
            // 1. вечер, выходные, каникулы, июль
            Map<Integer, ZoneId> zoneByCity = new HashMap<>();
            for (int i = 0; i < x.cities().size(); i++) zoneByCity.put(x.cities().id[i], x.cities().zoneOf(i));
            long evening = 0;
            Map<Long, Long> perDay = new HashMap<>();
            for (int i = 0; i < d.nv; i++) {
                ZoneId z = zoneByCity.getOrDefault(d.uCity[d.vUser[i] - 1], SiteRules.MSK);
                if (Period.localDateTime(d.vAt[i], z).getHour() >= SiteRules.EVENING_FROM) evening++;
                perDay.merge(SiteRules.mskDate(d.vAt[i]).toEpochDay(), 1L, Long::sum);
            }
            corridor("site: узор — просмотры вечером (19–23 ч по поясу города)", evening / (double) Math.max(1, d.nv), EVENING,
                    EVENING_HALF, d.nv, 2000, "просмотров " + d.nv);
            double[] wk = new double[4];
            for (LocalDate day = SiteRules.mskDate(w0); SiteRules.mskMidnight(day.plusDays(1)) <= cut; day = day.plusDays(1)) {
                if (SiteRules.holiday(day) || (day.getMonthValue() == 12 && day.getDayOfMonth() > 20)) continue;
                long n = perDay.getOrDefault(day.toEpochDay(), 0L);
                DayOfWeek dw = day.getDayOfWeek();
                if (dw == DayOfWeek.SATURDAY || dw == DayOfWeek.SUNDAY) {
                    wk[0] += n;
                    wk[1]++;
                } else if (dw.getValue() <= 4) {
                    wk[2] += n;
                    wk[3]++;
                }
            }
            corridor("site: узор — выходные: просмотров в сутки сб–вс к пн–чт", wk[0] / Math.max(1, wk[1]) / Math.max(1e-9, wk[2] / Math.max(1, wk[3])),
                    WEEKEND, WEEKEND_HALF, d.nv, 2000, "сб–вс " + (long) wk[0] + " за " + (long) wk[1] + " сут.");
            double[] ny = new double[4], jul = new double[6];
            for (LocalDate day = SiteRules.mskDate(w0); SiteRules.mskMidnight(day.plusDays(1)) <= cut; day = day.plusDays(1)) {
                long n = perDay.getOrDefault(day.toEpochDay(), 0L);
                if (SiteRules.holiday(day) && day.getYear() == 2026) {
                    ny[0] += n;
                    ny[1]++;
                } else if (day.getYear() == 2025 && day.getMonthValue() == 12 && day.getDayOfMonth() <= 20) {
                    ny[2] += n;
                    ny[3]++;
                }
                if (day.getYear() == 2026 && day.getMonthValue() >= 6 && day.getMonthValue() <= 8) {
                    int k = (day.getMonthValue() - 6) * 2;
                    jul[k] += n;
                    jul[k + 1]++;
                }
            }
            boolean nyOk = ny[1] == 8 && ny[3] == 20;
            corridor("site: узор — новогодние каникулы: просмотров в сутки 1–8.01.2026 к 1–20.12.2025",
                    ny[0] / Math.max(1, ny[1]) / Math.max(1e-9, ny[2] / Math.max(1, ny[3])), NEW_YEAR, NEW_YEAR_HALF,
                    nyOk ? d.nv : 0, 2000, "суток " + (long) ny[1] + " и " + (long) ny[3]);
            boolean julOk = jul[1] == 30 && jul[3] == 31 && jul[5] == 31;
            double jr = jul[2] / Math.max(1, jul[3]) / Math.max(1e-9, (jul[0] / Math.max(1, jul[1]) + jul[4] / Math.max(1, jul[5])) / 2);
            corridor("site: узор — июль: просмотров в сутки к среднему июня и августа 2026", jr, JULY, JULY_HALF, julOk ? d.nv : 0,
                    2000, "июль " + (long) jul[2]);
            // 2. новинки каталога: первые 3 недели к следующим 6
            int nm = x.in().movies().size();
            int[] vm = new int[d.nv];
            for (int i = 0; i < d.nv; i++) vm[i] = d.movieIndex.getOrDefault(d.vMovie[i], 0) + 1;
            int[][] viewsOfMovie = group(vm, d.nv, nm);
            long a = 0, b = 0, wins = 0;
            for (Map.Entry<Integer, List<long[]>> e : d.windows.entrySet()) {
                Integer mi = d.movieIndex.get(e.getKey());
                if (mi == null) continue;
                for (long[] w : e.getValue()) {
                    if (w[0] < w0 || w[0] + 63 * DAY > cut || (w[1] >= 0 && w[1] < w[0] + 63 * DAY)) continue;
                    wins++;
                    for (int vi : viewsOfMovie[mi + 1]) {
                        long t = d.vAt[vi];
                        if (t >= w[0] && t < w[0] + 21 * DAY) a++;
                        else if (t >= w[0] + 21 * DAY && t < w[0] + 63 * DAY) b++;
                    }
                }
            }
            atLeast("site: узор — новинка каталога: просмотров в сутки первые 3 недели к следующим 6", (a / 21.0) / Math.max(1e-9, b / 42.0),
                    NOVELTY_MIN, a + b, 300, "окон " + wins + ", просмотров " + a + " и " + b);
            // 3. отмены в марте 2026
            double[] canc = new double[2];
            long[] sw = new long[2];
            int months = 0;
            for (int s = 0; s < d.ns; s++) {
                if (d.sCancel[s] < 0) continue;
                LocalDate cd = SiteRules.mskDate(d.sCancel[s]);
                int ym = cd.getYear() * 12 + cd.getMonthValue() - 1;
                boolean march = ym == 2026 * 12 + 2;
                boolean other = ym >= 2025 * 12 + 10 && ym <= 2026 * 12 + 5 && !march;
                if (!march && !other) continue;
                boolean paidCancel = d.sStatus[s] == 2 && d.sReason[s] <= 4 && (d.sTrial[s] < 0 || d.sCancel[s] > d.sTrial[s]);
                if (paidCancel) canc[march ? 0 : 1]++;
                if (d.sStatus[s] == 4) sw[march ? 0 : 1]++;
            }
            months = 7;
            boolean marchOk = cut >= SiteRules.mskMidnight(LocalDate.of(2026, 7, 1));
            corridor("site: узор — повышение цен 01.03.2026: отмен в марте к среднему ноября — июня", canc[0] / Math.max(1e-9, canc[1] / months),
                    MARCH, MARCH_HALF, marchOk ? (long) (canc[0] + canc[1]) : 0, 100, "март " + (long) canc[0] + ", прочие " + (long) canc[1]);
            atLeast("site: узор — повышение цен: переходов на тариф ниже в марте к среднему прочих месяцев", sw[0] / Math.max(1e-9, sw[1] / (double) months),
                    DOWNGRADE_MIN, marchOk ? sw[0] + sw[1] : 0, 40, "март " + sw[0] + ", прочие " + sw[1]);
            // 4. пробный → платный
            Set<Integer> paidSubs = new HashSet<>();
            for (int i = 0; i < d.np; i++) if (d.pSub[i] > 0 && d.pStatus[i] != 1) paidSubs.add(d.pSub[i] - 1);
            long trials = 0, conv = 0, rt = 0, rc = 0;
            for (int s = 0; s < d.ns; s++) {
                if (d.sTrial[s] < 0 || d.sTrial[s] >= cut - 4 * DAY || d.sReason[s] == 7) continue;
                boolean c = paidSubs.contains(s);
                trials++;
                if (c) conv++;
                if (d.uChannel[d.sUser[s] - 1] == Audience.REFERRAL) {
                    rt++;
                    if (c) rc++;
                }
            }
            double cr = conv / (double) Math.max(1, trials), rr = rc / (double) Math.max(1, rt);
            corridor("site: узор — пробный период → платная подписка", cr, CONVERSION, CONVERSION_HALF, trials, 300,
                    "пробных " + trials + ", оплатили " + conv);
            atLeast("site: узор — у пришедших по приглашению конверсия выше", rr - cr + 1, 1 + REFERRAL_PLUS, rt, 300,
                    String.format(Locale.ROOT, "приглашённые %.3f (%d), все %.3f", rr, rt, cr));
            // 6. оценки
            long[] hist = new long[11];
            for (int i = 0; i < d.nr; i++) if (d.rValue[i] >= 1 && d.rValue[i] <= 10) hist[d.rValue[i]]++;
            double high = (hist[8] + hist[9] + hist[10]) / (double) Math.max(1, d.nr), low = (hist[1] + hist[2] + hist[3]) / (double) Math.max(1, d.nr);
            String h = Arrays.toString(Arrays.copyOfRange(hist, 1, 11));
            int pi = x.profile().ordinal();
            corridor("site: узор — оценки J-образные: доля 8–10", high, HIGH[pi], HIGH_HALF, d.nr, 2000, "по баллам " + h);
            corridor("site: узор — оценки J-образные: доля 1–3", low, LOW[pi], LOW_HALF, d.nr, 2000, "по баллам " + h);
            // «горб» у 1 — только когда низких оценок достаточно для сравнения
            boolean jShape = hist[10] >= hist[9] && hist[9] >= hist[7] && (hist[1] + hist[2] < 500 || hist[1] >= hist[2]);
            if (d.nr < 2000) r.ok("site: узор — оценки J-образные: 10 чаще 9, 9 чаще 7, 1 чаще 2 (при ≥ 500 низких)", "мало данных — только печать: " + h);
            else r.expect("site: узор — оценки J-образные: 10 чаще 9, 9 чаще 7, 1 чаще 2 (при ≥ 500 низких)", jShape, "по баллам " + h);
            Map<Integer, double[]> byMovie = new HashMap<>();
            for (int i = 0; i < d.nr; i++) {
                double[] s = byMovie.computeIfAbsent(d.rMovie[i], k -> new double[2]);
                s[0] += d.rValue[i];
                s[1]++;
            }
            double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            long n20 = 0, n50 = 0, div = 0;
            for (Map.Entry<Integer, double[]> e : byMovie.entrySet()) {
                double[] s = e.getValue();
                Integer mi = d.movieIndex.get(e.getKey());
                if (mi == null) continue;
                double rank = x.in().movies().get(mi).rank(), mean = s[0] / s[1];
                if (s[1] >= 20) {
                    n20++;
                    sx += rank;
                    sy += mean;
                    sxx += rank * rank;
                    syy += mean * mean;
                    sxy += rank * mean;
                }
                if (s[1] >= 50) {
                    n50++;
                    if (Math.abs(mean - rank) > 2) div++;
                }
            }
            double corr = (n20 * sxy - sx * sy) / Math.sqrt(Math.max(1e-12, (n20 * sxx - sx * sx) * (n20 * syy - sy * sy)));
            // у medium (10 % известных фильмов) разброс рейтинга уже — корреляция ниже
            double lo = x.profile() == Profile.FULL ? CORR_LO : CORR_LO_MEDIUM, hi = CORR_HI;
            if (x.profile() == Profile.SMALL) r.ok("site: узор — средняя оценка фильма растёт с рейтингом", String.format(Locale.ROOT,
                    "small — 1 %% самых известных фильмов, рейтинг 8,4–9,9 упирается в 10: только печать, корреляция %.3f по %d фильмам", corr, n20));
            else if (n20 < 30) r.ok("site: узор — средняя оценка фильма растёт с рейтингом", String.format(Locale.ROOT,
                    "мало данных (%d фильмов с ≥ 20 оценками) — только печать: %.3f", n20, corr));
            else r.expect("site: узор — средняя оценка фильма растёт с рейтингом", corr >= lo && corr <= hi,
                    String.format(Locale.ROOT, "корреляция %.3f по %d фильмам с ≥ 20 оценками, коридор %.2f…%.2f", corr, n20, lo, hi));
            double dv = div / (double) Math.max(1, n50);
            if (n50 < 100) r.ok("site: узор — «народная» оценка расходится с рейтингом больше чем на 2 балла", String.format(Locale.ROOT,
                    "мало данных (%d фильмов с ≥ 50 оценками) — только печать: %.3f", n50, dv));
            else r.expect("site: узор — «народная» оценка расходится с рейтингом больше чем на 2 балла",
                    dv >= DIVERGE_LO / widen && dv <= DIVERGE_HI * widen, String.format(Locale.ROOT, "%.3f (%d из %d фильмов с ≥ 50 оценками), "
                            + "коридор %.3f…%.3f", dv, div, n50, DIVERGE_LO / widen, DIVERGE_HI * widen));
            // 7. голоса — Парето; рецензенты с полезными рецензиями — с подписчиками
            long[] per = new long[d.nw];
            long[] usefulOf = new long[d.nu + 1];
            for (long[] e : d.votes) {
                per[(int) e[0] - 1]++;
                if (e[2] == 1) usefulOf[d.wUser[(int) e[0] - 1]]++;
            }
            long[] sorted = per.clone();
            Arrays.sort(sorted);
            int top = (int) Math.ceil(d.nw * 0.05);
            long topVotes = 0;
            for (int i = 0; i < top; i++) topVotes += sorted[sorted.length - 1 - i];
            corridor("site: узор — голоса за рецензии: доля голосов у 5 % рецензий", topVotes / (double) Math.max(1, d.votes.size()), PARETO,
                    PARETO_HALF, d.votes.size(), 3000, "рецензий " + d.nw + ", голосов " + d.votes.size());
            long[] followers = new long[d.nu + 1];
            for (long[] e : d.follows) followers[(int) e[1]]++;
            List<double[]> auth = new ArrayList<>();
            Set<Integer> authors = new HashSet<>();
            for (int i = 0; i < d.nw; i++) authors.add(d.wUser[i]);
            for (int u : authors) auth.add(new double[]{usefulOf[u], followers[u]});
            double sp = spearman(auth);
            atLeast("site: узор — у рецензентов с полезными рецензиями больше подписчиков (ранговая корреляция)", sp, FOLLOW_CORR,
                    auth.size(), 100, "рецензентов " + auth.size());
            // 8. серии
            boolean series = !x.in().successions().isEmpty();
            if (!series) r.ok("site: узор — серии смотрят по порядку", "серий и продолжений во входе нет — узор не проверяется");
            else seriesPattern();
            // 9. список «посмотреть»
            long old = 0, watched = 0;
            for (long[] e : d.watch) {
                if (e[2] >= cut - 180 * DAY) continue;
                old++;
                if (e[4] == 1) watched++;
            }
            corridor("site: узор — из списка «посмотреть» потом смотрят", watched / (double) Math.max(1, old), WATCHED[x.profile().ordinal()], WATCHED_HALF,
                    old, 2000, "записей старше 180 дней " + old);
            // 10. семейный тариф — несколько устройств и одновременность
            familyPattern();
        }

        static double spearman(List<double[]> xy) {
            int n = xy.size();
            if (n < 3) return Double.NaN;
            double[] rx = ranks(xy, 0), ry = ranks(xy, 1);
            double mx = 0, my = 0;
            for (int i = 0; i < n; i++) {
                mx += rx[i];
                my += ry[i];
            }
            mx /= n;
            my /= n;
            double sxy = 0, sxx = 0, syy = 0;
            for (int i = 0; i < n; i++) {
                sxy += (rx[i] - mx) * (ry[i] - my);
                sxx += (rx[i] - mx) * (rx[i] - mx);
                syy += (ry[i] - my) * (ry[i] - my);
            }
            return sxy / Math.sqrt(Math.max(1e-12, sxx * syy));
        }

        private static double[] ranks(List<double[]> xy, int c) {
            int n = xy.size();
            Integer[] o = new Integer[n];
            for (int i = 0; i < n; i++) o[i] = i;
            Arrays.sort(o, (p, q) -> Double.compare(xy.get(p)[c], xy.get(q)[c]));
            double[] rk = new double[n];
            for (int i = 0; i < n; ) {
                int j = i;
                while (j + 1 < n && xy.get(o[j + 1])[c] == xy.get(o[i])[c]) j++;
                for (int k = i; k <= j; k++) rk[o[k]] = (i + j) / 2.0;
                i = j + 1;
            }
            return rk;
        }

        void seriesPattern() {
            // предыдущий фильм (id) для следующего — по парам серий и продолжений входа
            Map<Integer, Integer> prevOf = new HashMap<>();
            for (int[] p : x.in().successions()) prevOf.putIfAbsent(x.in().movies().get(p[1]).id(), x.in().movies().get(p[0]).id());
            long pairs = 0, inOrder = 0;
            LongMap firstView = firstViews();
            LongMap counted = new LongMap(1024);
            for (int i = 0; i < d.nv; i++) {
                Integer prev = prevOf.get(d.vMovie[i]);
                if (prev == null) continue;
                long k = pair(d.vUser[i], d.vMovie[i]);
                if (counted.has(k) || firstView.get(k, -1) != d.vAt[i]) continue;
                counted.putIfAbsent(k, 1);
                long pv = firstView.get(pair(d.vUser[i], prev), -1);
                if (pv < 0) continue;
                pairs++;
                if (pv < d.vAt[i]) inOrder++;
            }
            atLeast("site: узор — серии смотрят по порядку", inOrder / (double) Math.max(1, pairs), 0.8, pairs, 50,
                    "пар соседних фильмов серии у зрителя " + pairs);
        }

        void familyPattern() {
            // (подписка, месяц) пользователя: разные устройства и одновременность — по просмотрам пользователя подряд
            double[] fam = new double[3], basic = new double[2];
            for (int u = 1; u <= d.nu; u++) {
                int[] vs = viewsOf[u];
                Map<Long, Set<Integer>> devs = new HashMap<>();
                Map<Long, Boolean> conc = new HashMap<>();
                Map<Long, Integer> nViews = new HashMap<>();
                for (int a = 0; a < vs.length; a++) {
                    int i = vs[a];
                    if (d.vSource[i] != 0) continue;
                    int s = subAt(u, d.vAt[i]);
                    if (s < 0) continue;
                    LocalDate md = SiteRules.mskDate(d.vAt[i]);
                    long key = ((long) s << 20) | (md.getYear() * 12L + md.getMonthValue());
                    devs.computeIfAbsent(key, k -> new HashSet<>()).add(d.vDev[i]);
                    nViews.merge(key, 1, Integer::sum);
                    for (int b = a - 1; b >= 0 && a - b <= 30; b--) {
                        int j = vs[b];
                        if (d.vSource[j] == 0 && d.vAt[j] + d.vWatched[j] * SiteRules.MINUTE > d.vAt[i]) conc.put(key, true);
                    }
                }
                for (Map.Entry<Long, Set<Integer>> e : devs.entrySet()) {
                    if (nViews.get(e.getKey()) < 2) continue;
                    int s = (int) (e.getKey() >> 20);
                    int tier = Plans.get(d.sPlan[s]).tier();
                    if (tier == 3) {
                        fam[0] += e.getValue().size();
                        fam[1]++;
                        if (conc.getOrDefault(e.getKey(), false)) fam[2]++;
                    } else if (tier == 1) {
                        basic[0] += e.getValue().size();
                        basic[1]++;
                    }
                }
            }
            double f = fam[0] / Math.max(1, fam[1]), bsc = basic[0] / Math.max(1, basic[1]);
            atLeast("site: узор — семейный тариф: устройств в месяц к базовому", f / Math.max(1e-9, bsc), FAMILY_DEVICES,
                    (long) Math.min(fam[1], basic[1]), 100, String.format(Locale.ROOT, "семейный %.2f, базовый %.2f", f, bsc));
            if (basic[1] < 100) r.ok("site: узор — базовый тариф: одно устройство", String.format(Locale.ROOT, "мало данных — только печать: %.3f", bsc));
            else r.expect("site: узор — базовый тариф: одно устройство", bsc <= BASIC_DEVICES_MAX + 0.05 * (widen - 1),
                    String.format(Locale.ROOT, "устройств в месяц %.3f (не больше %.2f)", bsc, BASIC_DEVICES_MAX + 0.05 * (widen - 1)));
            atLeast("site: узор — семейный тариф: месяцы с одновременными просмотрами", fam[2] / Math.max(1, fam[1]), FAMILY_CONCURRENT,
                    (long) fam[1], 100, "месяцев семейных подписок " + (long) fam[1]);
        }
    }
}
