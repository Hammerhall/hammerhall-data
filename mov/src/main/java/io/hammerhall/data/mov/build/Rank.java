package io.hammerhall.data.mov.build;

import io.hammerhall.data.core.random.RandomStreams;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * Рейтинг фильма ({@code movies.rank}) — синтетический: не оценка качества и не чужие оценки (P444 и рейтинги других
 * сервисов не берутся), а функция открытых признаков Wikidata и псевдослучайного шума от seed набора и id фильма.
 * Считается по всем фильмам {@code full}; у фильма он один во всех профилях.
 *
 * <ol>
 *   <li>Популярность {@link #popularity}: {@code p = ln(1 + sitelinks) + 0.5·ln(1 + awards) + 0.25·ln(1 + nominations)
 *       + 0.1·ln(1 + cast)} ({@code cast} — строк {@code roles} фильма в {@code full}).</li>
 *   <li>Оценка {@code s = 0.6·z(p) + 0.4·n}: {@code z} — нормировка по фильмам с {@code p > 0} (среднее 0, СКО 1;
 *       СКО — по генеральной совокупности, делитель — их число); у фильма без признаков ({@code p = 0}) {@code z = 0}
 *       — его рейтинг «примерный», шум вокруг середины; {@code n} — нормальный шум N(0, 1) из ядра:
 *       {@code Rng.gaussian()} (Бокс — Мюллер из двух равномерных SplitMix64, {@code StrictMath}) потока
 *       {@value #NOISE_STREAM} под id фильма.</li>
 *   <li>Квантиль: место {@code s} среди всех фильмов по возрастанию (при равенстве — по id) {@code i = 0…N−1} →
 *       {@code q = (i + 0.5) / N} (Хазен).</li>
 *   <li>Рейтинг — обратная функция распределения прежнего набора, кусочно-линейная по точкам {@link #Q} → {@link #R};
 *       считается точно, в целых, и округляется половиной к чётному до 0,1 ({@link #ofQuantile}).</li>
 * </ol>
 * Вся математика — {@link StrictMath}, суммы — по возрастанию id: тот же снимок и seed — тот же рейтинг на любой
 * машине.
 */
public final class Rank {
    private Rank() {
    }

    /** Имя потока шума рейтинга. */
    public static final String NOISE_STREAM = "movies.rank";
    public static final double W_SITELINKS = 1.0, W_AWARDS = 0.5, W_NOMINATIONS = 0.25, W_CAST = 0.1;
    public static final double W_POPULARITY = 0.6, W_NOISE = 0.4;
    /**
     * Точки обратной функции распределения (по квантилям прежнего набора): квантиль в сотых → рейтинг в десятых:
     * 0 → 1.0, 0.01 → 1.7, 0.05 → 2.9, 0.10 → 3.6, 0.25 → 4.9, 0.50 → 6.0, 0.75 → 7.0, 0.90 → 7.8, 0.95 → 8.4,
     * 0.99 → 9.2, 1 → 9.9.
     */
    static final long[] Q = {0, 1, 5, 10, 25, 50, 75, 90, 95, 99, 100};
    static final long[] R = {10, 17, 29, 36, 49, 60, 70, 78, 84, 92, 99};
    public static final BigDecimal MIN = new BigDecimal("1.0"), MAX = new BigDecimal("9.9");

    /** Популярность фильма (п. 1): {@code ln(1 + x)} — {@code StrictMath.log} от {@code 1 + x} (целое — точно). */
    public static double popularity(int sitelinks, int awards, int nominations, int cast) {
        return W_SITELINKS * StrictMath.log(1.0 + sitelinks) + W_AWARDS * StrictMath.log(1.0 + awards)
                + W_NOMINATIONS * StrictMath.log(1.0 + nominations) + W_CAST * StrictMath.log(1.0 + cast);
    }

    /**
     * Рейтинг по квантилю {@code q = num / den ∈ [0, 1]} (п. 4) — точно, в целых: отрезок — первый, в котором лежит
     * {@code q}; значение на отрезке — дробь; округление половины к чётному до десятых.
     */
    public static BigDecimal ofQuantile(long num, long den) {
        if (den <= 0 || num < 0 || num > den) throw new IllegalArgumentException("квантиль вне [0, 1]: " + num + "/" + den);
        long q100 = 100 * num; // q в сотых, умноженное на den
        int k = 0;
        while (k < Q.length - 2 && q100 > Q[k + 1] * den) k++;
        long a = (q100 - Q[k] * den) * (R[k + 1] - R[k]), b = (Q[k + 1] - Q[k]) * den;
        long f = a / b, rem = a % b, tenths = R[k] + f;
        if (2 * rem > b || (2 * rem == b && (tenths & 1) == 1)) tenths++;
        return BigDecimal.valueOf(tenths, 1);
    }

    /**
     * Рейтинги всех фильмов (п. 2–4).
     *
     * @param ids        id фильмов {@code full} по возрастанию
     * @param popularity популярность тех же фильмов
     * @param streams    потоки случайности набора (seed)
     * @return рейтинг фильма {@code ids[i]} — в {@code [i]}
     */
    public static BigDecimal[] ranks(int[] ids, double[] popularity, RandomStreams streams) {
        int n = ids.length;
        if (popularity.length != n) throw new IllegalArgumentException("ids и popularity разной длины");
        for (int i = 1; i < n; i++) if (ids[i] <= ids[i - 1]) throw new IllegalArgumentException("ids — не по возрастанию");
        BigDecimal[] out = new BigDecimal[n];
        if (n == 0) return out;
        double sum = 0;
        int base = 0;
        for (double p : popularity) {
            if (p > 0) {
                sum += p;
                base++;
            }
        }
        double mean = base == 0 ? 0 : sum / base, sq = 0;
        for (double p : popularity) if (p > 0) sq += (p - mean) * (p - mean);
        double sd = base == 0 ? 0 : StrictMath.sqrt(sq / base);
        RandomStreams.Stream noise = streams.stream(NOISE_STREAM);
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            double z = popularity[i] > 0 && sd > 0 ? (popularity[i] - mean) / sd : 0;
            s[i] = W_POPULARITY * z + W_NOISE * noise.at(ids[i]).gaussian();
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int c = Double.compare(s[a], s[b]);
            return c != 0 ? c : Integer.compare(ids[a], ids[b]);
        });
        for (int pos = 0; pos < n; pos++) out[order[pos]] = ofQuantile(2L * pos + 1, 2L * n);
        return out;
    }
}
