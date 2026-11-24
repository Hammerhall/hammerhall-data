package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.random.Rng;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * Выдуманные фильмы для тестов синтетики — похожие на профиль {@code small} настоящего снимка: 3 561 фильм, рейтинг
 * 8,4–9,9 (медиана 9,5), хронометраж около 113 минут, годы 1940–2026, жанры с долями как в снимке (драма, комедия,
 * боевик…), языки — в основном английский, часть — русский. Названия и имена — выдуманные латиницей и по-русски.
 * Серии — по желанию ({@code series}): тройки самых известных фильмов подряд по месту — одна серия.
 */
final class StubInput {
    private StubInput() {
    }

    static final int MOVIES = 3561, MOVIES_FULL = 356_088;
    private static final double[] RANK_Q = {0, 0.01, 0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.99, 1};
    private static final double[] RANK_V = {8.0, 8.4, 8.8, 9.0, 9.1, 9.5, 9.7, 9.8, 9.9, 9.9};
    /** Доля фильмов с жанром (индекс — id жанра) — как в small настоящего снимка. */
    private static final double[] GENRE_SHARE = {0, 0.26, 0, 0.17, 0.10, 0.10, 0.33, 0.16, 0.012, 0.65, 0.08, 0.15, 0.02, 0.06,
            0.08, 0.001, 0.09, 0.07, 0.20, 0.13, 0.004, 0.02, 0.19, 0.08, 0.02};
    private static final String[] SYL = {"ka", "ro", "mi", "ta", "lo", "ve", "sa", "nor", "dal", "ri", "an", "tor", "bel", "ku"};
    private static final String[] RU = {"Тень", "Река", "Город", "Ветер", "Огонь", "Дорога", "Сад", "Остров", "Звезда", "Мост"};

    static SiteInput build(long seed, boolean series) {
        return build(seed, series, MOVIES, MOVIES_FULL);
    }

    static SiteInput build(long seed, boolean series, int n, int moviesFull) {
        Rng r = new Rng(seed);
        List<double[]> raw = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double q = r.nextDouble();
            int k = 1;
            while (k < RANK_Q.length - 1 && q > RANK_Q[k]) k++;
            double v = RANK_V[k - 1] + (RANK_V[k] - RANK_V[k - 1]) * (q - RANK_Q[k - 1]) / (RANK_Q[k] - RANK_Q[k - 1]);
            raw.add(new double[]{Math.round(v * 10) / 10.0, v + 0.6 * r.gaussian()});
        }
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        Arrays.sort(ord, (a, b) -> Double.compare(raw.get(b)[1], raw.get(a)[1]));
        int[] place = new int[n];
        for (int i = 0; i < n; i++) place[ord[i]] = i;
        List<SiteInput.Movie> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int id = 1000 + i * 7;
            String title = cap(SYL[r.nextInt(SYL.length)] + SYL[r.nextInt(SYL.length)]) + " " + cap(SYL[r.nextInt(SYL.length)] + SYL[r.nextInt(SYL.length)]);
            String ru = r.chance(0.98) ? RU[r.nextInt(RU.length)] + " " + (i + 1) : null;
            int year = Math.min(2026, Math.max(1925, (int) Math.round(2000 + 22 * r.gaussian())));
            LocalDate rel = r.chance(0.8) ? LocalDate.of(year, 1 + r.nextInt(12), 1 + r.nextInt(28)) : null;
            Integer runtime = r.chance(0.998) ? Math.max(20, (int) Math.round(113 + 25 * r.gaussian())) : null;
            TreeSet<Integer> g = new TreeSet<>();
            for (int x = 1; x < GENRE_SHARE.length; x++) if (r.chance(GENRE_SHARE[x])) g.add(x);
            if (runtime != null && runtime <= 40) g.add(20);
            int[] gs = g.stream().mapToInt(Integer::intValue).toArray();
            boolean ruLang = r.chance(0.05);
            List<String> langs = ruLang ? List.of("ru") : List.of(r.chance(0.8) ? "en" : "fr");
            List<String> countries = ruLang ? List.of("RUS") : List.of(r.chance(0.7) ? "USA" : "FRA");
            int dir = 50_000 + r.nextInt(n / 2);
            // серии — у самых известных (по три фильма подряд по месту): чаще в каталоге и в просмотрах
            Integer sId = null, sNo = null;
            if (series && place[i] < 900) {
                sId = place[i] / 3 + 1;
                sNo = place[i] % 3 + 1;
            }
            out.add(new SiteInput.Movie(id, title, ru, year, rel, runtime, raw.get(i)[0], place[i], gs, langs, countries,
                    new int[]{dir}, List.of("Режиссёр " + (dir % 997)), List.of("Актёр " + r.nextInt(5000), "Актриса " + r.nextInt(5000)),
                    sId, sNo, new int[0]));
        }
        return new SiteInput(List.copyOf(out), moviesFull);
    }

    private static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
