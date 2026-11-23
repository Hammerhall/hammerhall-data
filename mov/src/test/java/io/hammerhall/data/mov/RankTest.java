package io.hammerhall.data.mov;

import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.mov.build.Rank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Рейтинг: формула популярности, обратная функция распределения, свойства распределения, повторяемость. */
class RankTest {
    static BigDecimal b(String s) {
        return new BigDecimal(s);
    }

    @Test
    @DisplayName("Популярность: ln(1+sitelinks) + 0.5 ln(1+awards) + 0.25 ln(1+nominations) + 0.1 ln(1+cast)")
    void popularity() {
        assertEquals(0.0, Rank.popularity(0, 0, 0, 0));
        double want = StrictMath.log(51) + 0.5 * StrictMath.log(4) + 0.25 * StrictMath.log(6) + 0.1 * StrictMath.log(11);
        assertEquals(want, Rank.popularity(50, 3, 5, 10), 1e-12);
    }

    @Test
    @DisplayName("Обратная функция: точки прежнего набора, линейно между ними, точно, половина — к чётному до 0,1")
    void inverseCdf() {
        assertEquals(b("1.0"), Rank.ofQuantile(0, 1));
        assertEquals(b("1.7"), Rank.ofQuantile(1, 100));
        assertEquals(b("2.9"), Rank.ofQuantile(5, 100));
        assertEquals(b("4.9"), Rank.ofQuantile(25, 100));
        assertEquals(b("6.0"), Rank.ofQuantile(1, 2));
        assertEquals(b("7.0"), Rank.ofQuantile(3, 4));
        assertEquals(b("9.2"), Rank.ofQuantile(99, 100));
        assertEquals(b("9.9"), Rank.ofQuantile(1, 1));
        assertEquals(b("6.5"), Rank.ofQuantile(5, 8), "середина между 6.0 и 7.0");
        assertEquals(b("1.4"), Rank.ofQuantile(1, 200), "ровно 1.35 → 1.4 (к чётному)");
        assertEquals(b("6.2"), Rank.ofQuantile(9, 16), "ровно 6.25 → 6.2 (к чётному)");
        assertEquals(b("9.6"), Rank.ofQuantile(199, 200), "ровно 9.55 → 9.6");
        assertEquals(b("6.3"), Rank.ofQuantile(9_000_001, 16_000_000), "чуть больше 6.25 → 6.3");
    }

    static BigDecimal[] synthetic(int n, long seed) {
        int[] ids = new int[n];
        double[] p = new double[n];
        Rng r = new Rng(7);
        for (int i = 0; i < n; i++) {
            ids[i] = 3 * i + 1;
            // признаки как у Wikidata: у многих фильмов статей нет, у немногих — сотни
            int sitelinks = r.chance(0.4) ? 0 : (int) r.logNormal(4, 1.2);
            int awards = r.chance(0.95) ? 0 : r.between(1, 30);
            p[i] = Rank.popularity(sitelinks, awards, r.chance(0.9) ? 0 : r.between(1, 20), r.between(0, 40));
        }
        return Rank.ranks(ids, p, new RandomStreams(seed));
    }

    @Test
    @DisplayName("Распределение: у всех 1.0…9.9 и один знак; медиана 6.0, квантили — около точек прежнего набора")
    void distribution() {
        BigDecimal[] r = synthetic(200_000, 20261123L);
        for (BigDecimal x : r) {
            assertEquals(1, x.scale());
            assertTrue(x.compareTo(b("1.0")) >= 0 && x.compareTo(b("9.9")) <= 0, x.toPlainString());
        }
        BigDecimal[] s = r.clone();
        Arrays.sort(s);
        assertEquals(b("6.0"), s[s.length / 2]);
        assertEquals(1.7, s[s.length / 100].doubleValue(), 0.1);
        assertEquals(3.6, s[s.length / 10].doubleValue(), 0.1);
        assertEquals(7.8, s[s.length * 9 / 10].doubleValue(), 0.1);
        assertEquals(9.2, s[s.length * 99 / 100].doubleValue(), 0.1);
        long high = Arrays.stream(r).filter(x -> x.compareTo(b("8.5")) >= 0).count();
        assertTrue(high > 200_000 * 0.03 && high < 200_000 * 0.06, "≥ 8.5 — около 4 %: " + high);
    }

    @Test
    @DisplayName("Повторяемость: тот же seed — тот же рейтинг; другой seed — другой; рейтинг тянется за популярностью")
    void deterministic() {
        BigDecimal[] a = synthetic(20_000, 1), b = synthetic(20_000, 1), c = synthetic(20_000, 2);
        assertArrayEquals(a, b);
        assertFalse(Arrays.equals(a, c));
        int[] ids = new int[1000];
        double[] p = new double[1000];
        for (int i = 0; i < 1000; i++) {
            ids[i] = i + 1;
            p[i] = i;
        }
        BigDecimal[] r = Rank.ranks(ids, p, new RandomStreams(5));
        double lo = 0, hi = 0;
        for (int i = 0; i < 100; i++) {
            lo += r[i].doubleValue();
            hi += r[999 - i].doubleValue();
        }
        assertTrue(hi / 100 > lo / 100 + 2, "известные в среднем выше: " + lo / 100 + " и " + hi / 100);
    }

    @Test
    @DisplayName("Крайние случаи: один фильм — медиана 6.0; одинаковая популярность — решает шум; пусто — пусто")
    void edges() {
        assertArrayEquals(new BigDecimal[]{b("6.0")}, Rank.ranks(new int[]{42}, new double[]{3.0}, new RandomStreams(1)));
        BigDecimal[] same = Rank.ranks(new int[]{1, 2, 3, 4}, new double[]{1, 1, 1, 1}, new RandomStreams(1));
        assertEquals(4, Arrays.stream(same).distinct().count(), Arrays.toString(same));
        assertEquals(0, Rank.ranks(new int[0], new double[0], new RandomStreams(1)).length);
    }
}
