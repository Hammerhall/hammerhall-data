package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.mov.site.Events.Collection;
import io.hammerhall.data.mov.site.Events.Device;
import io.hammerhall.data.mov.site.Events.Intent;
import io.hammerhall.data.mov.site.Events.Pay;
import io.hammerhall.data.mov.site.Events.Purchase;
import io.hammerhall.data.mov.site.Events.Review;
import io.hammerhall.data.mov.site.Events.Sub;
import io.hammerhall.data.mov.site.Events.View;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Жизнь пользователя в окне: устройства, подписки и списания, просмотры (с прокатом и покупкой), оценки, рецензии
 * (без текста — его пишет модуль по порядку времени), теги, список «посмотреть», подборки и намерения подписаться на
 * людей. Пользователи независимы друг от друга; случайность — от естественного ключа пользователя ({@link Audience#key})
 * и суток или номера (подписка, период, попытка).
 *
 * <p><b>Продление.</b> Моделирование идёт до конца пользователя {@code end = min(удаление, срез)} и не смотрит на срез
 * ни в одном решении: ни одна ветка не зависит от того, попадёт ли следствие в выпуск. Следствия после среза
 * (оценка после просмотра, правка рецензии, удаление из списка) создаются как обычно и отсекаются при записи; состояние
 * «в пути» (подписка, идущий просмотр) — на срез. Так тот же seed на длинном периоде даёт ту же историю до прежнего
 * среза.
 */
final class Life {
    // ---------------------------------------------------------------- модель (постоянные — для узоров и SOURCE.md)
    /** Просмотров в сутки у подписчика и у пользователя без подписки (до активности и сезона). */
    static final double L_SUB = 0.030, L_FREE = 0.007;
    /** Прокат вместо подписки (премьера) у подписчика и прокат у пользователя без подписки — доля просмотров. */
    static final double P_RENT_SUB = 0.09, P_RENT_FREE = 0.30;
    /** Подписчик, остывший к сервису (после срока интереса), смотрит меньше. */
    static final double COLD = 0.3;
    static final double P_TRIAL_NEW = 0.75, P_PAID_NEW = 0.2, P_PRE_ACTIVE = 0.75, P_PRE_LATER = 0.35;
    /** Пробный → платный по каналу (органика, реклама, партнёр, приглашение). */
    static final double[] CONVERT = {0.395, 0.30, 0.41, 0.545};
    static final double P_YEARLY = 0.12;
    /** Отмена платной подписки: в сутки (≈ 4,5 % в месяц), в марте 2026 — вдвое. */
    static final double CANCEL_PER_DAY = 0.045 / 30.4, MARCH_2026 = 2.3;
    /** Доля отмен, которые — переход на тариф ниже: в марте 2026 и в прочие месяцы. */
    static final double SWITCH_MARCH = 0.35, SWITCH_OTHER = 0.06;
    static final double P_RETURN = 0.5;
    /** Неудачное списание по способу (карта, СБП, кошелёк, телефон); у «хрупких» карт — {@link #FAIL_FRAGILE}. */
    static final double[] FAIL = {0.045, 0.03, 0.04, 0.08};
    static final double FAIL_FRAGILE = 0.35;
    /** Повторы списания: через 1 и 3 дня от первой попытки. */
    static final long[] RETRY = {0, SiteRules.DAY, 3 * SiteRules.DAY};
    static final double P_DOUBLE = 0.002, P_REFUND_PURCHASE = 0.01, P_REFUND_SUB = 0.002;
    static final double P_LONG_VIEW = 0.01;
    static final double P_NOVEL = 0.16;
    static final double P_NO_VIEW_RATING = 0.0005;
    /** Рецензия на новую оценку у рецензента (× вес рецензента, до 2). */
    static final double P_REVIEW = 1.0;
    /** Оценка: досмотрел, бросил на трети и больше, у не-оценщика — досмотрел. */
    static final double P_RATE_DONE = 0.85, P_RATE_PART = 0.35, P_RATE_OTHER = 0.06;
    static final long RENT_TIME = 48 * SiteRules.HOUR;
    /** Список «посмотреть»: добавлений в сутки (× активность), средний срок до «передумал», доля выбора из списка. */
    static final double P_WATCH_ADD = 0.022, WATCH_DAYS = 240, P_FROM_LIST = 0.45;

    final SiteInput in;
    final Catalog cat;
    final Audience aud;
    final long w0, cutoff;
    private final List<SiteInput.Movie> movies;
    private final RandomStreams.Stream devSt, subSt, paySt, cancelSt, viewSt, wlSt, rxSt, colSt, socSt;
    private final double[] mood;
    private final boolean[] reviewable;
    private final int[][] seriesNext;
    private final int[] seriesPrev;
    /** Фильмы профиля по жанрам с постоянным весом — для оценок «смотрел раньше» и подборок. */
    private final int[][] profPool;
    private final double[][] profCum;
    /** Полночь местных суток по поясу города: [пояс справочника][сутки от {@link #dayBase}]. */
    private final long[][] midnight;
    private final long dayBase;

    // ---------------------------------------------------------------- итоги прогона (все пользователи)
    final List<Device> devices = new ArrayList<>();
    final List<Sub> subs = new ArrayList<>();
    final List<Pay> pays = new ArrayList<>();
    final List<Purchase> purchases = new ArrayList<>();
    final List<View> views = new ArrayList<>();
    final List<Review> reviews = new ArrayList<>();
    final List<Collection> collections = new ArrayList<>();
    final List<Intent> intents = new ArrayList<>();
    /** Ключи заложенного: оценки без просмотра (пользователь, фильм). */
    final List<long[]> noViewRatings = new ArrayList<>();

    private final Cities cities;
    private final List<Promos.Promo> promoList;
    private final int tagCount;
    private final RandomStreams.Stream tagSt;

    Life(SiteInput in, Catalog cat, Audience aud, Cities cities, List<Promos.Promo> promos, int tagCount, RandomStreams st,
         long w0, long cutoff) {
        this.in = in;
        this.cat = cat;
        this.aud = aud;
        this.cities = cities;
        this.promoList = promos;
        this.tagCount = tagCount;
        this.tagSt = st.stream("site.tags.movie");
        this.w0 = w0;
        this.cutoff = cutoff;
        this.movies = in.movies();
        devSt = st.stream("site.devices");
        subSt = st.stream("site.subscriptions");
        paySt = st.stream("site.payments");
        cancelSt = st.stream("site.cancel");
        viewSt = st.stream("site.views");
        wlSt = st.stream("site.watchlist");
        rxSt = st.stream("site.ratings.extra");
        colSt = st.stream("site.collections");
        socSt = st.stream("site.follows.intents");
        RandomStreams.Stream moodSt = st.stream("site.film_mood");
        int nm = movies.size();
        reviewable = new boolean[nm];
        for (int i = 0; i < nm; i++) reviewable[i] = SiteModule.reviewable(movies.get(i));
        mood = new double[nm];
        for (int i = 0; i < nm; i++) {
            Rng r = moodSt.at(movies.get(i).id());
            double x = r.uniform(-MOOD, MOOD);
            if (r.chance(CULT)) {
                double rk = movies.get(i).rank();
                x = (rk < 6.5 ? 1 : -1) * r.uniform(2.6, 3.4);
            }
            mood[i] = x;
        }
        // серии и продолжения: следующие фильмы и (первый) предыдущий — по парам «предыдущий → следующий»
        seriesNext = new int[nm][];
        seriesPrev = new int[nm];
        Arrays.fill(seriesPrev, -1);
        for (int[] p : in.successions()) {
            int[] cur = seriesNext[p[0]];
            seriesNext[p[0]] = cur == null ? new int[]{p[1]} : append(cur, p[1]);
            if (seriesPrev[p[1]] < 0) seriesPrev[p[1]] = p[0];
        }
        profPool = new int[25][];
        profCum = new double[25][];
        for (int g = 0; g < 25; g++) {
            List<Integer> l = new ArrayList<>();
            for (int i = 0; i < nm; i++) {
                int[] gs = movies.get(i).genres();
                if (g == 0 ? gs.length == 0 : movies.get(i).hasGenre(g)) l.add(i);
            }
            profPool[g] = l.stream().mapToInt(Integer::intValue).toArray();
            profCum[g] = new double[profPool[g].length];
            double s = 0;
            for (int k = 0; k < profPool[g].length; k++) profCum[g][k] = s += Catalog.staticWeight(movies.get(profPool[g][k]), nm);
        }
        dayBase = SiteRules.SERVICE_START.toEpochDay() - 2;
        int nd = (int) (SiteRules.HORIZON.toEpochDay() - dayBase + 4);
        midnight = new long[cities.zones.length][nd];
        for (int z = 0; z < cities.zones.length; z++) {
            ZoneId zone = cities.zones[z];
            for (int d = 0; d < nd; d++) midnight[z][d] = LocalDate.ofEpochDay(dayBase + d).atStartOfDay(zone).toEpochSecond();
        }
    }

    private static int[] append(int[] a, int v) {
        int[] b = Arrays.copyOf(a, a.length + 1);
        b[a.length] = v;
        return b;
    }

    /** Народная поправка оценки фильма: равномерно ±{@code MOOD}, у {@link #CULT} фильмов — расхождение на 2,6–3,4. */
    static final double MOOD = 0.8, CULT = 0.03;

    double mood(int movieIdx) {
        return mood[movieIdx];
    }

    // ================================================================ пользователь

    /** Оценка пользователя фильму: события (момент, значение) по порядку; без просмотра — заложенное. */
    static final class Rating {
        final int movie;
        long[] at = new long[2];
        int[] value = new int[2];
        int n;
        boolean noView;

        Rating(int movie) {
            this.movie = movie;
        }

        void add(long t, int v) {
            if (n == at.length) {
                at = Arrays.copyOf(at, n * 2);
                value = Arrays.copyOf(value, n * 2);
            }
            at[n] = t;
            value[n] = v;
            n++;
        }

        int valueAt(long t) {
            int v = -1;
            for (int i = 0; i < n; i++) if (at[i] <= t) v = value[i];
            return v;
        }
    }

    /** Запись списка «посмотреть». */
    static final class Watch {
        int movie;
        long added, dropAt, removed = -1;
        boolean watched;
    }

    /** Тег пользователя фильму. */
    record Tag(int movie, int tag, long at) {
    }

    /** Итог пользователя для таблиц по пользователю (оценки, список, теги). */
    static final class UserOut {
        final Map<Integer, Rating> ratings = new HashMap<>();
        final List<Watch> watch = new ArrayList<>();
        final List<Tag> tags = new ArrayList<>();
    }

    /** Недосмотренный фильм — к продолжению. */
    private static final class Progress {
        int movie, window;
        byte source;
        Purchase purchase;
        int watched;
        long lastAt;
    }

    // состояние пользователя на время моделирования
    private int u, zone, seqDev, seqSub, seqPay, seqPur, seqView, seqRev, seqCol, nTags;
    private long key, end, start;
    private final List<Device> myDev = new ArrayList<>();
    private final List<Sub> mySub = new ArrayList<>();
    private final List<View> recent = new ArrayList<>();
    private final List<Progress> progress = new ArrayList<>();
    private final List<Purchase> owned = new ArrayList<>();
    private final Map<Integer, Integer> completedAt = new HashMap<>();
    private int pendingNext = -1;
    private long pendingFrom, pendingTo;
    private UserOut out;

    UserOut simulate(int user) {
        u = user;
        key = aud.key[u];
        zone = cities.zone[aud.city[u]];
        end = Math.min(aud.deletedAt[u], cutoff);
        start = Math.max(aud.regAt[u], w0);
        seqDev = seqSub = seqPay = seqPur = seqView = seqRev = seqCol = nTags = 0;
        myDev.clear();
        mySub.clear();
        recent.clear();
        progress.clear();
        owned.clear();
        completedAt.clear();
        pendingNext = -1;
        out = new UserOut();
        int firstReview = reviews.size();
        buildDevices();
        simulateSubscriptions();
        if (start < end) {
            days();
            collections();
        }
        intents();
        // оценка автора на момент рецензии (оценку могли поменять позже)
        for (int i = firstReview; i < reviews.size(); i++) {
            Review v = reviews.get(i);
            v.rating = out.ratings.get(v.movie).valueAt(v.at);
        }
        return out;
    }

    // ================================================================ устройства

    private void buildDevices() {
        Rng r = devSt.at(key);
        int h = aud.household[u];
        int k = 1 + r.poisson(0.35 + 0.45 * (h - 1));
        long t = aud.regAt[u];
        for (int i = 0; i < k; i++) {
            if (i > 0) t += (long) (r.exponential(300.0 / h) * SiteRules.DAY) + SiteRules.HOUR;
            Device d = new Device();
            d.at = t;
            d.user = u;
            d.seq = seqDev++;
            double x = r.nextDouble();
            if (i == 0) d.kind = (byte) (x < 0.45 ? 1 : x < 0.70 ? 3 : x < 0.90 ? 0 : x < 0.97 ? 2 : 4);
            else d.kind = (byte) (x < 0.35 ? 0 : x < 0.65 ? 1 : x < 0.80 ? 2 : x < 0.90 ? 3 : 4);
            double y = r.nextDouble();
            d.os = switch (d.kind) {
                case 0 -> (byte) (y < 0.35 ? 6 : y < 0.65 ? 7 : 8);
                case 1 -> (byte) (y < 0.70 ? 0 : 1);
                case 2 -> (byte) (y < 0.55 ? 0 : 2);
                case 3 -> (byte) (y < 0.75 ? 3 : y < 0.95 ? 4 : 5);
                default -> (byte) (y < 0.70 ? 8 : 9);
            };
            d.retire = i == k - 1 ? Long.MAX_VALUE : t + (long) ((200 + r.exponential(900)) * SiteRules.DAY);
            d.last = t;
            myDev.add(d);
        }
        for (Device d : myDev) if (d.at < end) devices.add(d);
    }

    // ================================================================ подписки и списания

    private static final byte ST_TRIAL = 0, ST_ACTIVE = 1, ST_CANCELLED = 2, ST_EXPIRED = 3, ST_SWITCHED = 4;
    private static final byte R_PRICE = 0, R_CONTENT = 1, R_PAUSE = 2, R_TECH = 3, R_OTHER = 4, R_FAILED = 5, R_SWITCH = 6,
            R_DELETED = 7;

    private int tierFor(Rng r) {
        int h = aud.household[u];
        double x = r.nextDouble();
        if (h == 1) return x < 0.62 ? 1 : x < 0.95 ? 2 : 3;
        if (h == 2) return x < 0.30 ? 1 : x < 0.85 ? 2 : 3;
        return x < 0.10 ? 1 : x < 0.45 ? 2 : 3;
    }

    private void simulateSubscriptions() {
        Rng r = subSt.at(key, 0);
        long regAt = aud.regAt[u];
        long next = -1;
        int trialDays = 0;
        boolean prehistory = false;
        int c = aud.channel[u];
        Promos.Promo promo = null; // промокод пользователя (вид и значение) — из модуля
        if (aud.promo[u] >= 0) promo = promoList.get(aud.promo[u]);
        double a1 = r.nextDouble(), a2 = r.nextDouble(), a3 = r.nextDouble(), a4 = r.nextDouble();
        if (regAt < w0) {
            if (aud.lifeEnd[u] > w0 && aud.deletedAt[u] > w0 && a1 < P_PRE_ACTIVE * aud.subProp[u]) {
                prehistory = true;
                long back = (long) Math.min(w0 - regAt - SiteRules.HOUR, (1 + r.exponential(420)) * SiteRules.DAY);
                next = Math.max(regAt + 60, w0 - Math.max(SiteRules.HOUR, back));
            } else if (a2 < P_PRE_LATER) {
                next = w0 + (long) (r.exponential(300) * SiteRules.DAY);
            }
        } else if (a3 < P_TRIAL_NEW * aud.subProp[u] / 0.6) {
            boolean promoTrial = promo != null && promo.kind() == Promos.TRIAL;
            trialDays = promoTrial ? promo.value() : (c == Audience.PARTNER || c == Audience.REFERRAL ? 14 : 7);
            next = regAt + (r.chance(0.6) ? r.between(60, 7200) : (long) (r.exponential(12) * SiteRules.DAY) + 600);
        } else if (a4 < P_PAID_NEW) {
            next = regAt + (long) (r.exponential(40) * SiteRules.DAY) + 600;
        }
        int tier = -1;
        boolean firstEver = !prehistory && regAt >= w0;
        while (next >= 0 && next < end) {
            seqSub++;
            Rng rs = subSt.at(key, seqSub);
            int t = tier > 0 ? tier : tierFor(rs);
            boolean yearly = trialDays == 0 && tier <= 0 && rs.chance(P_YEARLY);
            Plans.Plan plan = Plans.of(t, yearly ? 12 : 1);
            // тариф, которого ещё не было, — с его ввода (у шедших на начало окна)
            long intro = SiteRules.mskMidnight(plan.introduced()) + 10 * SiteRules.HOUR;
            if (next < intro) next = Math.max(intro, regAt + SiteRules.HOUR);
            if (next >= end) break;
            int discount = firstEver && promo != null && promo.kind() == Promos.DISCOUNT ? promo.value() : 0;
            long[] res = runSub(next, plan, trialDays, discount, prehistory, rs);
            prehistory = false;
            firstEver = false;
            trialDays = 0;
            tier = -1;
            if (res[0] == 1) {
                // переход на тариф ниже — с конца оплаченного периода
                next = res[1];
                tier = (int) res[2];
            } else if (res[0] == 2) {
                Rng rr = subSt.at(key, seqSub, 99);
                next = rr.chance(P_RETURN * aud.subProp[u] / 0.6) ? res[1] + (long) ((1 + rr.exponential(100)) * SiteRules.DAY) : -1;
            } else {
                next = -1;
            }
        }
    }

    /**
     * Одна подписка от {@code startedAt}: пробный период, списания по периодам с повторами, отмена или переход.
     * Возврат: {@code [вид, момент, ступень]} — вид 0 — идёт на конце пользователя, 1 — переход на ступень ниже с
     * момента, 2 — закончилась в момент.
     */
    private long[] runSub(long startedAt, Plans.Plan plan, int trialDays, int discount, boolean prehistory, Rng rs) {
        Sub s = new Sub();
        s.at = startedAt;
        s.user = u;
        s.seq = seqSub;
        s.plan = plan.id();
        s.autoRenew = true;
        subs.add(s);
        mySub.add(s);
        long chargeAt;
        double conv = CONVERT[aud.channel[u]] * (0.55 + 0.75 * aud.subProp[u]);
        double convDraw = rs.nextDouble(), cancelFrac = rs.uniform(0.2, 0.97);
        byte trialReason = (byte) rs.nextInt(5);
        if (trialDays > 0) {
            s.trialEnd = startedAt + trialDays * SiteRules.DAY;
            if (convDraw >= conv) {
                long cAt = startedAt + (long) (trialDays * SiteRules.DAY * cancelFrac);
                if (cAt >= end) return running(s, ST_TRIAL, s.trialEnd);
                finish(s, ST_CANCELLED, cAt, s.trialEnd, trialReason);
                return new long[]{2, s.trialEnd, 0};
            }
            chargeAt = s.trialEnd;
        } else {
            chargeAt = startedAt;
        }
        long anchor = chargeAt;
        int k = 0, paidBefore = -1;
        if (prehistory) {
            // шедшая на начало окна: периоды до окна оплачены (списаний в выпуске нет), отмена — с начала окна
            while (SiteRules.plusMonths(anchor, (k + 1) * plan.months()) <= w0) k++;
            paidBefore = k;
        }
        while (true) {
            long ps = SiteRules.plusMonths(anchor, k * plan.months());
            long pe = SiteRules.plusMonths(anchor, (k + 1) * plan.months());
            if (k > paidBefore) {
                Rng rp = paySt.at(key, seqSub, k);
                boolean paid = false;
                long lastAt = ps;
                for (int a = 0; a < RETRY.length; a++) {
                    long at = ps + RETRY[a] + rp.nextInt(900);
                    boolean fail = rp.chance(failP());
                    if (at >= end) {
                        // на конце пользователя: период до списания идёт до ps, во время повторов — доступ до pe
                        if (ps >= end) return running(s, trialDays > 0 && k == 0 ? ST_TRIAL : ST_ACTIVE, ps);
                        return running(s, ST_ACTIVE, pe);
                    }
                    long amount = Plans.priceKop(plan.id(), SiteRules.mskDate(at));
                    if (k == 0 && discount > 0) amount = Plans.discounted(amount, discount);
                    pay(s, null, at, amount, fail ? (byte) 1 : (byte) 0, rp);
                    lastAt = at;
                    if (!fail) {
                        paid = true;
                        break;
                    }
                }
                if (!paid) {
                    finish(s, ST_EXPIRED, lastAt, lastAt, R_FAILED);
                    return new long[]{2, lastAt, 0};
                }
            }
            // отмена или переход в оплаченном периоде
            Rng rc = cancelSt.at(key, seqSub, k);
            double e = rc.exponential(1.0);
            double mult = (1.55 - aud.subProp[u]) * (plan.months() == 12 ? 0.25 : 1.0) * (k == 0 ? 1.3 : 1.0);
            long from = Math.max(ps, w0);
            long cancel = -1;
            for (long t = from; t < pe; t += SiteRules.DAY) {
                LocalDate d = SiteRules.mskDate(t);
                double h = CANCEL_PER_DAY * mult * (t > aud.lifeEnd[u] ? 3.0 : 1.0)
                        * (d.getYear() == 2026 && d.getMonthValue() == 3 ? MARCH_2026 : 1.0);
                long step = Math.min(SiteRules.DAY, pe - t);
                double hh = h * step / SiteRules.DAY;
                if (e < hh) {
                    cancel = t + (long) (step * (e / hh));
                    break;
                }
                e -= hh;
            }
            boolean march = cancel >= 0 && isMarch2026(cancel);
            boolean sw = cancel >= 0 && plan.tier() > 1 && rc.chance(march ? SWITCH_MARCH : SWITCH_OTHER);
            byte reason = (byte) (march && rc.chance(0.6) ? R_PRICE : rc.nextInt(5));
            if (aud.deletedAt[u] < cutoff && aud.deletedAt[u] >= ps && aud.deletedAt[u] < pe
                    && (cancel < 0 || aud.deletedAt[u] < cancel)) {
                // удаление учётной записи посреди периода: доступ — до удаления
                return running(s, ST_ACTIVE, pe);
            }
            if (cancel >= 0 && cancel < end) {
                if (sw) {
                    finish(s, ST_SWITCHED, cancel, pe, R_SWITCH);
                    return new long[]{1, pe, plan.tier() - 1};
                }
                finish(s, ST_CANCELLED, cancel, pe, reason);
                return new long[]{2, pe, 0};
            }
            if (pe >= end) return running(s, ST_ACTIVE, pe);
            k++;
        }
    }

    private static boolean isMarch2026(long t) {
        LocalDate d = SiteRules.mskDate(t);
        return d.getYear() == 2026 && d.getMonthValue() == 3;
    }

    private double failP() {
        return aud.has(u, Audience.FRAGILE) ? FAIL_FRAGILE : FAIL[aud.method[u]];
    }

    private void finish(Sub s, byte status, long cancelledAt, long ends, byte reason) {
        s.status = status;
        s.cancelledAt = cancelledAt;
        s.ends = ends;
        s.reason = reason;
        s.autoRenew = false;
        s.accessEnd = ends;
    }

    /** Подписка идёт на конце пользователя: на срез — как есть; удалённый до среза — отмена в момент удаления. */
    private long[] running(Sub s, byte status, long ends) {
        if (aud.deletedAt[u] < cutoff && aud.deletedAt[u] == end) {
            // пробный период, оборванный удалением, кончается удалением
            long d = Math.max(end, s.at);
            finish(s, ST_CANCELLED, d, d, R_DELETED);
            if (s.trialEnd > d) s.trialEnd = d;
            return new long[]{0, d, 0};
        }
        s.status = status;
        s.ends = Math.max(ends, s.trialEnd);
        s.autoRenew = true;
        s.accessEnd = Long.MAX_VALUE;
        return new long[]{0, ends, 0};
    }

    private void pay(Sub s, Purchase p, long at, long amount, byte status, Rng r) {
        Pay x = new Pay();
        x.at = at;
        x.user = u;
        x.seq = seqPay++;
        x.sub = s;
        x.purchase = p;
        x.amount = amount;
        x.method = aud.method[u];
        x.status = status;
        boolean refund = status == 0 && r.chance(p != null ? P_REFUND_PURCHASE : P_REFUND_SUB);
        long refundAt = at + SiteRules.HOUR + r.nextInt((int) (3 * SiteRules.DAY));
        boolean dup = status == 0 && r.chance(P_DOUBLE);
        long dupAt = at + 2 + r.nextInt(40), dupRefund = at + SiteRules.DAY + r.nextInt((int) (4 * SiteRules.DAY));
        if (refund) {
            x.status = 2;
            x.refundedAt = refundAt;
        }
        pays.add(x);
        if (dup) {
            Pay d = new Pay();
            d.at = dupAt;
            d.user = u;
            d.seq = seqPay++;
            d.sub = s;
            d.purchase = p;
            d.amount = amount;
            d.method = x.method;
            d.status = 2;
            d.refundedAt = dupRefund;
            d.duplicate = true;
            pays.add(d);
        }
    }

    /** Подписка, дающая доступ в момент {@code t}, или {@code null}. */
    private Sub subAt(long t) {
        for (int i = mySub.size() - 1; i >= 0; i--) {
            Sub s = mySub.get(i);
            if (s.at <= t && t < s.accessEnd) return s;
            if (s.at <= t) return null;
        }
        return null;
    }

    // ================================================================ сутки: просмотры, список, оценки «смотрел раньше»

    private long midnight(LocalDate d) {
        return midnight[zone][(int) (d.toEpochDay() - dayBase)];
    }

    /**
     * Запрос события пользователя: просмотр (обычный, второй зритель семьи, досмотр проката по льготе), добавление в
     * список, оценка «смотрел раньше». Запросы идут строго по времени (куча), у каждого — свой генератор: решение по
     * запросу видит только то, что решено по более ранним, — так история до среза не зависит от того, где срез.
     */
    private static final class Req {
        long at;
        byte kind;
        long day;
        int idx;
        Rng r;
        int tries;
        Progress forced;
    }

    private static final byte K_VIEW = 0, K_COMPANION = 1, K_GRACE = 2, K_WATCH = 3, K_NOVIEW = 4;

    private static final java.util.Comparator<Req> REQ_ORDER = (a, b) -> {
        if (a.at != b.at) return Long.compare(a.at, b.at);
        if (a.kind != b.kind) return Byte.compare(a.kind, b.kind);
        if (a.day != b.day) return Long.compare(a.day, b.day);
        return Integer.compare(a.idx, b.idx);
    };

    private final java.util.PriorityQueue<Req> heap = new java.util.PriorityQueue<>(REQ_ORDER);

    private Req req(long at, byte kind, long day, int idx, Rng r) {
        Req q = new Req();
        q.at = at;
        q.kind = kind;
        q.day = day;
        q.idx = idx;
        q.r = r;
        return q;
    }

    private void days() {
        heap.clear();
        LocalDate d = Period.localDate(start, cities.zones[zone]);
        while (true) {
            while (midnight(d) < end && (heap.isEmpty() || heap.peek().at >= midnight(d))) {
                plan(d);
                d = d.plusDays(1);
            }
            Req q = heap.poll();
            if (q == null || q.at >= end) break;
            if (q.at < start) continue;
            switch (q.kind) {
                case K_WATCH -> watchAdd(q.at, q.r);
                case K_NOVIEW -> noViewRating(q.at, q.r);
                default -> {
                    long again = view(q);
                    if (again >= 0 && q.tries < 6) {
                        q.at = again;
                        q.tries++;
                        heap.add(q);
                    }
                }
            }
        }
        heap.clear();
    }

    /** Запросы местных суток {@code d}: число просмотров — от подписки, интереса, активности и сезона. */
    private void plan(LocalDate d) {
        long mid = midnight(d);
        long noon = mid + 14 * SiteRules.HOUR;
        Sub s = subAt(noon);
        boolean engaged = noon < aud.lifeEnd[u];
        double act = aud.activity[u];
        double base = s != null ? (engaged ? L_SUB : L_SUB * COLD) : (engaged ? L_FREE : 0);
        long day = d.toEpochDay();
        boolean we = SiteRules.weekendOrHoliday(d);
        if (base > 0) {
            Rng r = viewSt.at(key, day);
            int k = r.poisson(base * act * SiteRules.season(d));
            for (int i = 0; i < k; i++) heap.add(req(mid + SiteRules.secondOfDay(r, we), K_VIEW, day, i, viewSt.at(key, day, i + 1)));
        }
        if (engaged && aud.has(u, Audience.WATCHLISTER)) {
            Rng r = wlSt.at(key, day);
            int k = r.poisson(P_WATCH_ADD * act * SiteRules.season(d));
            for (int i = 0; i < k; i++) heap.add(req(mid + SiteRules.secondOfDay(r, we), K_WATCH, day, i, wlSt.at(key, day, i + 1)));
        }
        if (engaged && aud.has(u, Audience.RATER)) {
            Rng r = rxSt.at(key, day);
            if (r.chance(P_NO_VIEW_RATING * act)) heap.add(req(mid + SiteRules.secondOfDay(r, we), K_NOVIEW, day, 0, r));
        }
    }

    private void noViewRating(long t, Rng r) {
        int m = profilePick(r);
        if (m < 0 || out.ratings.containsKey(m)) return;
        Rating rt = new Rating(m);
        rt.noView = true;
        rt.add(t, rate(m, true, t, r));
        out.ratings.put(m, rt);
        noViewRatings.add(new long[]{u, m});
    }

    /** Фильм профиля по вкусу (для оценок без просмотра и подборок): жанр — из любимых, фильм — по постоянному весу. */
    private int profilePick(Rng r) {
        return profilePick(r, aud.favoriteGenre(u, r));
    }

    private int profilePick(Rng r, int g) {
        double[] c = profCum[g];
        if (c.length == 0) return -1;
        double x = r.nextDouble() * c[c.length - 1];
        int lo = 0, hi = c.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (c[mid] > x) hi = mid;
            else lo = mid + 1;
        }
        return profPool[g][lo];
    }

    private static final int M_SUB = 0, M_FREE = 1, M_RENT = 2;

    /** Окно каталога для просмотра в момент {@code t} в режиме доступа или {@code -1}. */
    private int choose(long t, int mode, Rng r) {
        if (mode != M_RENT && r.chance(P_NOVEL)) {
            int lo = cat.addedFrom(t - 21 * SiteRules.DAY), hi = cat.addedFrom(t + 1);
            for (int tries = 0; hi > lo && tries < 6; tries++) {
                int w = cat.byAdded[lo + r.nextInt(hi - lo)];
                double age = (t - cat.added[w]) / (double) SiteRules.DAY;
                if (!r.chance(StrictMath.exp(-age / 8.0))) continue;
                if (!fits(w, t, mode)) continue;
                if (aud.affinity(u, movies.get(cat.movie[w]).genres()) < 0 && r.chance(0.7)) continue;
                if (seen(cat.movie[w]) && !r.chance(0.03)) continue;
                return w;
            }
        }
        for (int tries = 0; tries < 30; tries++) {
            int g = r.chance(0.85) ? aud.favoriteGenre(u, r) : r.nextInt(25);
            int acc;
            if (mode == M_FREE) acc = Catalog.FREE;
            else if (mode == M_RENT) acc = Catalog.RENT_BUY;
            else {
                double ws = cat.poolWeight(Catalog.SUB, g), wf = cat.poolWeight(Catalog.FREE, g),
                        wr = 0.4 * cat.poolWeight(Catalog.RENT_BUY, g);
                double x = r.nextDouble() * (ws + wf + wr);
                acc = x < ws ? Catalog.SUB : x < ws + wf ? Catalog.FREE : Catalog.RENT_BUY;
            }
            int w = cat.sample(r, acc, g);
            if (w < 0 || !fits(w, t, mode)) continue;
            SiteInput.Movie m = movies.get(cat.movie[w]);
            if (!r.chance(StrictMath.exp(aud.rankSens[u] * 0.45 * Math.min(0, m.rank() - 9.5)))) continue;
            int mi = cat.movie[w];
            if (seen(mi) && !r.chance(0.04)) continue;
            if (seriesPrev[mi] >= 0 && !seen(seriesPrev[mi]) && r.chance(0.85)) continue;
            return w;
        }
        return -1;
    }

    private boolean seen(int movie) {
        return completedAt.containsKey(movie);
    }

    private boolean fits(int w, long t, int mode) {
        if (!cat.open(w, t)) return false;
        return switch (mode) {
            case M_SUB -> cat.bySubscription(w, t);
            case M_FREE -> cat.access[w] == Catalog.FREE;
            default -> cat.access[w] == Catalog.RENT_BUY;
        };
    }

    /** Можно ли продолжить недосмотренное в момент {@code t}: доступ тот же; прокат — до конца, по льготе — сутки. */
    private boolean canResume(Progress p, long t, Sub s) {
        if (p.lastAt > t || t - p.lastAt > 7 * SiteRules.DAY) return false;
        int w = cat.windowAt(p.movie, t);
        if (p.purchase != null && !p.purchase.buy) return t < p.purchase.expires && (w >= 0 || graceOk(p.purchase, t));
        if (p.source == 0) return w >= 0 && s != null && cat.bySubscription(w, t);
        if (p.source == 3) return w >= 0 && cat.access[w] == Catalog.FREE && s == null;
        return w >= 0;
    }

    /**
     * Просмотр по запросу: что смотреть (продолжение, список, следующий фильм серии, купленное, выбор по вкусу; прокат),
     * на каком устройстве, сколько. Занято устройство или исчерпаны потоки тарифа — возврат: момент, на который запрос
     * переносится (ничего не записано); иначе — {@code -1}.
     */
    private long view(Req q) {
        long t = q.at;
        Rng r = q.r;
        boolean companion = q.kind == K_COMPANION;
        Sub s = subAt(t);
        if (companion && s == null) return -1;
        Plans.Plan plan = s != null ? Plans.get(s.plan) : Plans.get(Plans.FREE);
        int movie, window = -1, watchedBefore = 0;
        byte source;
        Purchase pur = null;
        boolean newRent = false, newBuy = false;
        Progress resume = null;
        Watch fromList = null;
        boolean usedPending = false;
        byte mark = Events.V_NORMAL;
        double x = r.nextDouble(), y = r.nextDouble(), z = r.nextDouble();
        if (q.forced != null && progress.contains(q.forced) && canResume(q.forced, t, s)) {
            resume = q.forced;
        } else if (!companion && x < 0.5) {
            for (int i = progress.size() - 1; i >= 0 && resume == null; i--) if (canResume(progress.get(i), t, s)) resume = progress.get(i);
        }
        if (resume != null) {
            movie = resume.movie;
            window = cat.windowAt(movie, t);
            source = resume.source;
            pur = resume.purchase;
            watchedBefore = resume.watched;
            if (window < 0) {
                if (pur == null || pur.buy || !graceOk(pur, t)) return -1;
                mark = Events.V_GRACE;
            }
        } else {
            int mode;
            if (companion) mode = M_SUB;
            else if (s != null) mode = y < P_RENT_SUB ? M_RENT : M_SUB;
            else mode = y < P_RENT_FREE ? M_RENT : M_FREE;
            if (pendingNext >= 0 && t >= pendingFrom && t < pendingTo && !companion) {
                int w = cat.windowAt(pendingNext, t);
                if (w >= 0 && fits(w, t, mode)) window = w;
                else if (w >= 0 && fits(w, t, M_RENT)) {
                    window = w;
                    mode = M_RENT;
                }
                usedPending = true;
            }
            if (window < 0 && z < P_FROM_LIST && !out.watch.isEmpty()) {
                List<Watch> active = new ArrayList<>();
                for (Watch e : out.watch) if (e.removed < 0 && e.added <= t && t < e.dropAt) active.add(e);
                Watch wl = active.isEmpty() ? null : active.get(r.nextInt(active.size()));
                if (wl != null) {
                    int w = cat.windowAt(wl.movie, t);
                    if (w >= 0 && fits(w, t, mode)) {
                        window = w;
                        fromList = wl;
                    } else if (w >= 0 && s == null && fits(w, t, M_RENT) && r.chance(0.5)) {
                        window = w;
                        mode = M_RENT;
                        fromList = wl;
                    }
                }
            }
            if (window < 0 && !owned.isEmpty() && r.chance(0.04)) {
                Purchase b = owned.get(r.nextInt(owned.size()));
                int w = cat.windowAt(b.movie, t);
                if (b.at < t && w >= 0) {
                    window = w;
                    pur = b;
                }
            }
            if (window < 0) window = choose(t, mode, r);
            if (window < 0 && mode == M_RENT) {
                mode = s != null ? M_SUB : M_FREE;
                window = choose(t, mode, r);
            }
            if (window < 0) return -1;
            movie = cat.movie[window];
            if (pur != null) {
                source = 2;
            } else if (mode == M_RENT && s != null && cat.bySubscription(window, t)) {
                source = 0;
            } else if (mode == M_RENT) {
                newBuy = r.chance(0.12);
                newRent = !newBuy;
                source = (byte) (newBuy ? 2 : 1);
            } else {
                source = (byte) (mode == M_SUB ? 0 : 3);
            }
        }
        // устройство
        List<Device> avail = new ArrayList<>(4);
        for (Device d : myDev) if (d.at <= t && t < d.retire) avail.add(d);
        if (avail.isEmpty()) return -1;
        int streams = source == 0 ? plan.streams() : 1;
        Device dev;
        if (source == 0) {
            int lim = Math.min(streams, avail.size());
            int pick = companion ? r.nextInt(lim) : (lim == 1 || r.chance(0.7) ? 0 : r.nextInt(lim));
            dev = avail.get(avail.size() - 1 - pick);
        } else {
            dev = r.chance(0.8) ? avail.getLast() : avail.get(r.nextInt(avail.size()));
        }
        // занято: то же устройство или все потоки тарифа — перенос запроса
        long busyTill = -1;
        int sameTime = 0;
        long firstFree = Long.MAX_VALUE;
        for (View v : recent) {
            long ve = v.at + v.watched * SiteRules.MINUTE;
            if (v.at <= t && t < ve) {
                if (v.device == dev) busyTill = Math.max(busyTill, ve);
                if (source == 0 && v.source == 0) {
                    sameTime++;
                    firstFree = Math.min(firstFree, ve);
                }
            }
        }
        if (source == 0 && sameTime >= streams) busyTill = Math.max(busyTill, firstFree);
        if (busyTill >= 0) return busyTill + 60 + r.nextInt(1200);

        SiteInput.Movie m = movies.get(movie);
        int runtime = m.runtime() != null ? m.runtime() : 90;
        int watched;
        boolean completed;
        double aff = aud.affinity(u, m.genres());
        double pc = Math.max(0.25, Math.min(0.92, 0.62 + 0.18 * aff + 0.04 * (m.rank() - 6)));
        double d1 = r.nextDouble(), d2 = r.nextDouble(), d3 = r.nextDouble();
        if (resume != null) {
            int left = Math.max(1, runtime - watchedBefore);
            completed = d1 < 0.7;
            watched = completed ? left : Math.max(1, (int) Math.round(left * (0.1 + 0.8 * d2)));
            if (watchedBefore + watched >= 0.9 * runtime) completed = true;
        } else if (d3 < P_LONG_VIEW && source != 3) {
            completed = true;
            watched = (int) Math.round(runtime * (1.35 + 1.0 * d2));
            mark = Events.V_LONG;
        } else {
            completed = d1 < pc;
            watched = completed ? (int) Math.round(runtime * (0.92 + 0.08 * d2))
                    : Math.max(1, (int) Math.round(runtime * (0.04 + 0.8 * d2)));
        }
        // запись: покупка, просмотр, состояние
        if (newRent || newBuy) {
            long pAt = Math.max(start, t - 30 - r.nextInt(270));
            pur = purchase(window, pAt, newBuy, r);
        }
        if (resume != null) progress.remove(resume);
        if (usedPending) pendingNext = -1;
        View v = new View();
        v.at = t;
        v.user = u;
        v.seq = seqView++;
        v.device = dev;
        v.movie = movie;
        v.watched = watched;
        v.completed = completed;
        v.source = source;
        v.mark = mark;
        int devCap = dev.kind == 0 || dev.kind == 4 ? 3 : 2;
        int srcCap = switch (source) {
            case 0 -> plan.quality();
            case 3 -> 0;
            default -> m.year() >= 2010 ? 3 : 2;
        };
        int qv = Math.min(devCap, srcCap);
        if (qv > 0 && r.chance(0.12)) qv--;
        v.quality = (byte) qv;
        views.add(v);
        if (t > dev.last && t < end) dev.last = t;
        recent.add(v);
        if (recent.size() > 24) recent.removeFirst();
        long vEnd = t + watched * SiteRules.MINUTE;
        if (fromList != null || !out.watch.isEmpty()) {
            for (Watch wl : out.watch) {
                if (wl.movie == movie && wl.removed < 0 && wl.added <= t && t < wl.dropAt) {
                    wl.removed = t;
                    wl.watched = true;
                }
            }
        }
        if (!completed) {
            Progress p = new Progress();
            p.movie = movie;
            p.window = window;
            p.source = source;
            p.purchase = pur;
            p.watched = watchedBefore + watched;
            p.lastAt = vEnd;
            progress.add(p);
            if (progress.size() > 6) progress.removeFirst();
            // прокат на исходе окна каталога: досмотр после ухода фильма (льгота, не дольше суток)
            if (source == 1 && pur != null && window >= 0 && cat.removed[window] != Catalog.NEVER
                    && cat.removed[window] - t < 30 * SiteRules.HOUR && cat.removed[window] > vEnd && r.chance(0.6)) {
                long g = cat.removed[window] + SiteRules.HOUR + r.nextInt((int) (19 * SiteRules.HOUR));
                if (g < pur.expires) {
                    Req gq = req(g, K_GRACE, q.day, 2000 + q.idx, viewSt.at(key, q.day, 2000 + q.idx));
                    gq.forced = p;
                    heap.add(gq);
                }
            }
        } else {
            completedAt.put(movie, (int) (t / SiteRules.DAY));
        }
        afterView(movie, completed, watchedBefore + watched >= 0.3 * runtime, vEnd, r);
        // второй зритель семьи — почти одновременно на другом устройстве
        if (!companion && q.kind == K_VIEW && source == 0 && plan.streams() > 1 && avail.size() > 1
                && r.chance(plan.streams() >= 4 ? 0.35 : 0.12)) {
            heap.add(req(t + 1 + r.nextInt(1800), K_COMPANION, q.day, 1000 + q.idx, viewSt.at(key, q.day, 1000 + q.idx)));
        }
        return -1;
    }

    /** Льгота проката: окно каталога ушло не больше суток назад, прокат ещё действует. */
    private boolean graceOk(Purchase p, long t) {
        int w = p.window;
        return w >= 0 && cat.removed[w] != Catalog.NEVER && t >= cat.removed[w] && t < cat.removed[w] + SiteRules.DAY
                && t < p.expires;
    }

    private Purchase purchase(int window, long at, boolean buy, Rng r) {
        Purchase p = new Purchase();
        p.at = at;
        p.user = u;
        p.seq = seqPur++;
        p.movie = cat.movie[window];
        p.window = window;
        p.buy = buy;
        p.price = buy ? cat.buyKop[window] : cat.rentPrice(window, at);
        p.expires = buy ? -1 : at + RENT_TIME;
        purchases.add(p);
        if (buy) owned.add(p);
        pay(null, p, at, p.price, (byte) 0, r);
        return p;
    }

    // ================================================================ оценки, рецензии, теги

    /** Оценка по скрытой модели (§1.5): рейтинг + щедрость + 0.8·сродство − 0.6·усталость + поправка фильма + шум. */
    int rate(int movie, boolean completed, long t, Rng r) {
        SiteInput.Movie m = movies.get(movie);
        int recentViews = 0;
        for (View v : recent) if (t - v.at < SiteRules.DAY) recentViews++;
        double fatigue = Math.min(1.0, Math.max(0, (recentViews - 2) / 3.0));
        double x = m.rank() + aud.generosity[u] + 0.8 * aud.affinity(u, m.genres()) - 0.6 * fatigue + mood[movie]
                + 1.1 * r.gaussian();
        if (!completed) x -= 1.5;
        if (aud.has(u, Audience.EXTREME)) x = x >= 7 ? 10 : x < 4.5 ? 1 : x;
        return (int) Math.max(1, Math.min(10, Math.round(x)));
    }

    private void afterView(int movie, boolean completed, boolean enough, long vEnd, Rng r) {
        boolean rater = aud.has(u, Audience.RATER);
        Rating rt = out.ratings.get(movie);
        double a = r.nextDouble(), lag1 = r.nextDouble();
        long lag = lag1 < 0.85 ? 30 + (long) (r.exponential(25) * SiteRules.MINUTE) : (long) (r.exponential(1.5) * SiteRules.DAY) + 60;
        long t = vEnd + lag;
        if (rt == null) {
            double p = rater ? (completed ? P_RATE_DONE : enough ? P_RATE_PART : 0) : (completed ? P_RATE_OTHER : 0);
            if (a >= p) return;
            rt = new Rating(movie);
            int v = rate(movie, completed, t, r);
            rt.add(t, v);
            out.ratings.put(movie, rt);
            if (completed && v >= 8 && seriesNext[movie] != null && r.chance(0.45)) {
                pendingNext = seriesNext[movie][0];
                pendingFrom = t + SiteRules.DAY;
                pendingTo = t + 10 * SiteRules.DAY;
            } else if (completed && seriesNext[movie] != null && v < 6 && r.chance(0.08)) {
                pendingNext = seriesNext[movie][0];
                pendingFrom = t + SiteRules.DAY;
                pendingTo = t + 10 * SiteRules.DAY;
            }
            if (aud.has(u, Audience.REVIEWER) && r.chance(P_REVIEW * Math.min(2.0, aud.quality[u])) && reviewable[movie]) review(movie, t, r);
            if (aud.has(u, Audience.TAGGER) && r.chance(0.6)) tags(movie, t, r);
        } else if (rater && completed && a < 0.05 && t > rt.at[rt.n - 1]) {
            int old = rt.value[rt.n - 1];
            int step = r.chance(0.5) ? 1 : -1;
            if (r.chance(0.3)) step *= 2;
            rt.add(t, Math.max(1, Math.min(10, old + step)));
        }
    }

    private void review(int movie, long ratedAt, Rng r) {
        Review v = new Review();
        v.at = ratedAt + 120 + (long) (Math.min(3.0, r.exponential(0.25)) * SiteRules.DAY);
        v.user = u;
        v.seq = seqRev++;
        v.movie = movie;
        double e = r.nextDouble();
        v.edited = e < 0.12 ? v.at + SiteRules.HOUR + (long) (r.exponential(25) * SiteRules.DAY) : -1;
        v.hidden = r.chance(0.015);
        v.weight = aud.quality[u];
        reviews.add(v);
    }

    private void tags(int movie, long ratedAt, Rng r) {
        int k = r.between(1, 3);
        int nTagsAll = tagCount;
        if (nTagsAll == 0) return;
        Rng mt = tagSt.at(movies.get(movie).id());
        int[] own = new int[5];
        for (int i = 0; i < own.length; i++) own[i] = mt.nextInt(nTagsAll);
        java.util.Set<Integer> done = new java.util.HashSet<>();
        for (Tag tg : out.tags) if (tg.movie() == movie) done.add(tg.tag());
        for (int i = 0; i < k; i++) {
            int tag = r.chance(0.8) ? own[r.nextInt(own.length)] : r.nextInt(nTagsAll);
            if (!done.add(tag)) continue;
            out.tags.add(new Tag(movie, tag, ratedAt + 5 + r.nextInt(600)));
            nTags++;
        }
    }

    // ================================================================ список «посмотреть»

    private void watchAdd(long t, Rng r) {
        int w = -1;
        for (int tries = 0; tries < 8 && w < 0; tries++) {
            int g = aud.favoriteGenre(u, r);
            double ws = cat.poolWeight(Catalog.SUB, g), wr = cat.poolWeight(Catalog.RENT_BUY, g), wf = cat.poolWeight(Catalog.FREE, g);
            double x = r.nextDouble() * (ws + wr + wf);
            int acc = x < ws ? Catalog.SUB : x < ws + wr ? Catalog.RENT_BUY : Catalog.FREE;
            int c = cat.sample(r, acc, g);
            if (c >= 0 && cat.open(c, t) && !seen(cat.movie[c])) w = c;
        }
        long drop = t + SiteRules.HOUR + (long) (r.exponential(WATCH_DAYS) * SiteRules.DAY);
        if (w < 0) return;
        int movie = cat.movie[w];
        for (Watch x : out.watch) if (x.movie == movie && (x.removed < 0 ? t < x.dropAt : t < x.removed)) return;
        for (Watch x : out.watch) if (x.movie == movie && x.added == t) return;
        Watch e = new Watch();
        e.movie = movie;
        e.added = t;
        e.dropAt = drop;
        out.watch.add(e);
    }

    // ================================================================ подборки и подписки на людей

    private void collections() {
        if (!aud.has(u, Audience.COLLECTOR)) return;
        Rng r = colSt.at(key);
        int k = 1 + r.poisson(0.6);
        long t = start;
        for (int c = 0; c < k; c++) {
            t += (long) (r.exponential(120) * SiteRules.DAY) + SiteRules.HOUR;
            Collection col = new Collection();
            col.at = t;
            col.user = u;
            col.seq = seqCol++;
            col.genre = aud.favoriteGenre(u, r);
            if (col.genre == 0) col.genre = 9;
            col.open = r.chance(0.7);
            int items = (int) Math.max(4, Math.min(40, Math.round(r.logNormal(12, 0.45))));
            long it = t + 60 + r.nextInt(600);
            java.util.Set<Integer> inCol = new java.util.HashSet<>();
            // оценённые фильмы жанра — по моменту первой оценки: к пункту подборки — только оценённые раньше него
            List<Rating> liked = new ArrayList<>();
            for (Rating rt : out.ratings.values()) if (movies.get(rt.movie).hasGenre(col.genre)) liked.add(rt);
            liked.sort((p, q) -> p.at[0] != q.at[0] ? Long.compare(p.at[0], q.at[0]) : Integer.compare(p.movie, q.movie));
            for (int i = 0; i < items; i++) {
                if (i > 0) it += (long) (r.exponential(i < 5 ? 0.02 : 3) * SiteRules.DAY) + 30;
                int before = 0;
                while (before < liked.size() && liked.get(before).at[0] <= it) before++;
                int movie = -1;
                for (int tries = 0; tries < 10 && movie < 0; tries++) {
                    int cand;
                    if (before > 0 && r.chance(0.6)) {
                        Rating rt = liked.get(r.nextInt(before));
                        cand = rt.valueAt(it) < 7 ? -1 : rt.movie;
                    } else {
                        cand = profilePick(r, col.genre);
                    }
                    if (cand >= 0 && !inCol.contains(cand)) movie = cand;
                }
                if (movie < 0) continue;
                inCol.add(movie);
                col.items.add(new long[]{movie, it});
            }
            if (!col.items.isEmpty()) collections.add(col);
        }
    }

    private void intents() {
        if (!aud.has(u, Audience.SOCIAL)) return;
        Rng r = socSt.at(key);
        int k = (int) Math.min(400, Math.floor(r.logNormal(aud.has(u, Audience.REVIEWER) ? 30 : 18, 1.0)));
        long t = Math.max(aud.regAt[u], w0);
        for (int i = 0; i < k; i++) {
            t += (long) (r.exponential(45) * SiteRules.DAY) + 60;
            if (t >= aud.lifeEnd[u] + 30 * SiteRules.DAY || t >= end) break;
            Intent x = new Intent();
            x.at = t;
            x.user = u;
            x.seq = i;
            intents.add(x);
        }
    }
}
