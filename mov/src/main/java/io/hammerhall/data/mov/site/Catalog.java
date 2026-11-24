package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Каталог сервиса (§1.3): не все фильмы профиля, а ≈ {@link #SHARE} их — чаще известные, на русском языке или из
 * России, новые (2015+); у фильма с хронометражем — окна прав: приходит, уходит, часть возвращается. Окна
 * моделируются до {@link SiteRules#HORIZON} — функция фильма и seed, от периода не зависит. Доступ окна: по подписке,
 * прокат и покупка (новинки и часть известных; через 60–120 дней — и по подписке), бесплатно с рекламой (старое и
 * документальное). Изменения каталога — в 00:00 по Москве.
 *
 * <p>Выбор фильма для просмотра — {@link #sample}: жанр, затем окно по постоянному весу (известность и рейтинг) из
 * пула доступа и жанра; доступность на момент проверяет вызывающий.
 */
final class Catalog {
    /**
     * Доля фильмов профиля, у которых есть окно прав до {@link SiteRules#HORIZON}; к срезу 01.09.2027 в каталоге побывало
     * ≈ 17 % (часть окон — позже).
     */
    static final double SHARE = 0.19;
    static final byte SUB = 0, RENT_BUY = 1, FREE = 2;
    static final long NEVER = Long.MAX_VALUE;

    final SiteInput in;
    /** Окна по (фильм, приход): индекс фильма во входе, моменты, доступ, цены, вход в подписку ({@code -1} — нет). */
    final int n;
    final int[] movie;
    final long[] added, removed, subFrom, rentKop, buyKop;
    final byte[] access;
    final boolean[] premiere;
    /** Окна фильма: {@code first[m]} … {@code first[m + 1]}. */
    final int[] first;
    /** Пулы выбора: [доступ][жанр 0–24] → окна и накопленные веса. */
    private final int[][][] pool;
    private final double[][][] poolCum;
    /** Окна по приходу — для «новинок каталога». */
    final int[] byAdded;
    final double[] weight;

    private Catalog(SiteInput in, List<long[]> rows) {
        this.in = in;
        n = rows.size();
        movie = new int[n];
        added = new long[n];
        removed = new long[n];
        subFrom = new long[n];
        rentKop = new long[n];
        buyKop = new long[n];
        access = new byte[n];
        premiere = new boolean[n];
        weight = new double[n];
        first = new int[in.movies().size() + 1];
        for (int i = 0; i < n; i++) {
            long[] w = rows.get(i);
            movie[i] = (int) w[0];
            added[i] = w[1];
            removed[i] = w[2];
            access[i] = (byte) w[3];
            rentKop[i] = w[4];
            buyKop[i] = w[5];
            subFrom[i] = w[6];
            premiere[i] = w[7] != 0;
            first[movie[i] + 1]++;
            weight[i] = staticWeight(in.movies().get(movie[i]), in.movies().size());
        }
        for (int m = 0; m < in.movies().size(); m++) first[m + 1] += first[m];
        pool = new int[3][25][];
        poolCum = new double[3][25][];
        for (int a = 0; a < 3; a++) {
            for (int g = 0; g < 25; g++) {
                List<Integer> l = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    if (access[i] != a) continue;
                    int[] gs = in.movies().get(movie[i]).genres();
                    boolean has = g == 0 ? gs.length == 0 : contains(gs, g);
                    if (has) l.add(i);
                }
                pool[a][g] = l.stream().mapToInt(Integer::intValue).toArray();
                double[] c = new double[pool[a][g].length];
                double s = 0;
                for (int k = 0; k < c.length; k++) c[k] = s += weight[pool[a][g][k]];
                poolCum[a][g] = c;
            }
        }
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        Arrays.sort(ord, (x, y) -> added[x] != added[y] ? Long.compare(added[x], added[y]) : Integer.compare(x, y));
        byAdded = new int[n];
        for (int i = 0; i < n; i++) byAdded[i] = ord[i];
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    /** Постоянный вес фильма в выборе: известность (место в профиле) и рейтинг. */
    static double staticWeight(SiteInput.Movie m, int size) {
        double k = Math.max(20.0, size / 200.0);
        return StrictMath.pow(1.0 / (1.0 + m.popularity() / k), 0.9) * StrictMath.exp(0.3 * (m.rank() - 6.0));
    }

    /** Вес фильма при отборе в каталог. */
    static double pickWeight(SiteInput.Movie m, int size) {
        double q = (m.popularity() + 0.5) / size;
        double w = StrictMath.pow(1.0 / (1.0 + 20.0 * q), 1.2);
        if (m.languages().contains("ru") || m.countries().contains("RUS")) w *= 3.0;
        if (m.year() >= 2015) w *= 1.8;
        return w;
    }

    static boolean eligible(SiteInput.Movie m) {
        return m.runtime() != null && m.runtime() >= 3;
    }

    /** Множитель вероятности отбора: Σ min(0.95, c·w) = доля × фильмов (деление пополам, детерминированно). */
    static double scaleFor(double[] w, double target) {
        double lo = 0, hi = 1e9;
        for (int it = 0; it < 200; it++) {
            double mid = (lo + hi) / 2, s = 0;
            for (double x : w) s += Math.min(0.95, mid * x);
            if (s < target) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    static Catalog build(SiteInput in, RandomStreams streams) {
        List<SiteInput.Movie> ms = in.movies();
        int size = ms.size();
        double[] w = new double[size];
        for (int i = 0; i < size; i++) w[i] = eligible(ms.get(i)) ? pickWeight(ms.get(i), size) : 0;
        double c = scaleFor(w, SHARE * size);
        RandomStreams.Stream pick = streams.stream("site.catalog.pick"), win = streams.stream("site.catalog.windows");
        List<long[]> rows = new ArrayList<>();
        long horizon = SiteRules.mskMidnight(SiteRules.HORIZON);
        for (int i = 0; i < size; i++) {
            SiteInput.Movie m = ms.get(i);
            if (w[i] <= 0 || pick.unit(m.id()) >= Math.min(0.95, c * w[i])) continue;
            Rng r = win.at(m.id());
            LocalDate release = m.releaseDate() != null ? m.releaseDate() : LocalDate.of(m.year(), 7, 1);
            LocalDate earliest = release.isBefore(SiteRules.SERVICE_START) ? SiteRules.SERVICE_START : release;
            boolean recent = !release.isBefore(SiteRules.SERVICE_START.minusYears(1));
            LocalDate start;
            if (recent && r.chance(0.7)) {
                start = earliest.plusDays(Math.min(364, (long) r.exponential(80)));
            } else if (!recent && r.chance(0.45)) {
                start = SiteRules.SERVICE_START.plusDays(r.nextInt(120));
            } else {
                long span = SiteRules.HORIZON.minusYears(1).toEpochDay() - earliest.toEpochDay();
                start = earliest.plusDays(span <= 0 ? 0 : r.nextInt((int) span));
            }
            double q = (m.popularity() + 0.5) / size;
            for (int k = 0; k < 3 && start.isBefore(SiteRules.HORIZON); k++) {
                long days = Math.max(60, Math.round(r.logNormal(700, 0.6)));
                LocalDate end = start.plusDays(days);
                boolean isNew = !start.isAfter(release.plusDays(365));
                byte a;
                if (isNew) a = r.chance(0.85) ? RENT_BUY : SUB;
                else if (m.year() < 1975 || m.hasGenre(8)) a = r.chance(0.4) ? FREE : SUB;
                else if (q < 0.2) a = r.chance(k == 0 ? 0.2 : 0.08) ? RENT_BUY : (r.chance(0.03) ? FREE : SUB);
                else a = r.chance(0.08) ? FREE : (r.chance(0.05) ? RENT_BUY : SUB);
                long at = SiteRules.mskMidnight(start);
                long rm = end.isBefore(SiteRules.HORIZON) ? SiteRules.mskMidnight(end) : NEVER;
                long rent = -1, buy = -1, sf = -1;
                if (a == RENT_BUY) {
                    double rk = Math.max(0, Math.min(1, (m.rank() - 4.0) / 5.0));
                    rent = isNew ? SiteRules.nine(199 + 50 * rk) : SiteRules.nine(149 + 50 * rk);
                    buy = isNew ? SiteRules.nine(599 + 200 * rk) : SiteRules.nine(299 + 200 * rk);
                    sf = SiteRules.mskMidnight(start.plusDays(r.between(60, 120)));
                }
                rows.add(new long[]{i, at, rm, a, rent, buy, sf, isNew && a == RENT_BUY ? 1 : 0});
                if (rm == NEVER || rm >= horizon || !r.chance(k == 0 ? 0.35 : 0.25)) break;
                start = end.plusDays(r.between(90, 365));
            }
        }
        return new Catalog(in, rows);
    }

    // ---------------------------------------------------------------- доступность

    boolean open(int w, long t) {
        return added[w] <= t && t < removed[w];
    }

    /** Окно фильма, открытое в момент {@code t}, или {@code -1}. */
    int windowAt(int movieIdx, long t) {
        for (int w = first[movieIdx]; w < first[movieIdx + 1]; w++) if (open(w, t)) return w;
        return -1;
    }

    /** Фильм окна доступен подписчику в момент {@code t} (окно открыто). */
    boolean bySubscription(int w, long t) {
        return access[w] == SUB || access[w] == FREE || (access[w] == RENT_BUY && subFrom[w] <= t);
    }

    /** Цена проката окна в момент {@code t}: первые 60 дней — премьерная, позже — {@link SiteRules#rentLater}. */
    long rentPrice(int w, long t) {
        return t < added[w] + SiteRules.PREMIERE_DAYS * SiteRules.DAY ? rentKop[w] : SiteRules.rentLater(rentKop[w]);
    }

    /** Окно из пула (доступ, жанр) по постоянному весу или {@code -1}, если пул пуст. */
    int sample(Rng r, int acc, int genre) {
        double[] c = poolCum[acc][genre];
        if (c.length == 0) return -1;
        double x = r.nextDouble() * c[c.length - 1];
        int lo = 0, hi = c.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (c[mid] > x) hi = mid;
            else lo = mid + 1;
        }
        return pool[acc][genre][lo];
    }

    double poolWeight(int acc, int genre) {
        double[] c = poolCum[acc][genre];
        return c.length == 0 ? 0 : c[c.length - 1];
    }

    /** Первое окно в {@link #byAdded} с приходом не раньше {@code t}. */
    int addedFrom(long t) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (added[byAdded[mid]] >= t) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }
}
