package io.hammerhall.data.auc.bid;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static io.hammerhall.data.auc.bid.Fx.rub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Таблица шага (тестовая — шаги дома): ступени, минимальная следующая ставка, округление к сетке, пороги. */
class IncrementTableTest {
    final IncrementTable t = IncrementTable.standard();

    @Test
    void stepByBand() {
        assertEquals(rub(50), t.step(0));
        assertEquals(rub(50), t.step(rub(999) + 99));
        assertEquals(rub(100), t.step(rub(1_000)));
        assertEquals(rub(250), t.step(rub(3_000)));               // порог — уже следующая полоса
        assertEquals(rub(500), t.step(rub(9_500)));
        assertEquals(rub(1_000), t.step(rub(10_000)));
        assertEquals(rub(2_500), t.step(rub(30_000)));
        assertEquals(rub(50_000), t.step(rub(999_999)));
        assertEquals(rub(1_000_000), t.step(rub(25_000_000)));
        assertEquals(14, t.bands());
    }

    @Test
    void nextBidIsCurrentPlusStep() {
        assertEquals(rub(1_100), t.minNext(rub(1_000)));
        assertEquals(rub(10_000), t.minNext(rub(9_500)));   // шаг полосы 6–10 тыс. доводит ровно до порога
        assertEquals(rub(11_000), t.minNext(rub(10_000)));
        assertEquals(rub(32_500), t.minNext(rub(30_000)));
        assertEquals(rub(1_100_000), t.minNext(rub(1_000_000)));
    }

    /** Пример сетки шагов дома: 2 400 → 2 500 → … → 2 900 → 3 000 → 3 250 → … → 5 750 → 6 000 → 6 500. */
    @Test
    void gridExampleFromSpec() {
        StringBuilder sb = new StringBuilder();
        for (long p = rub(2_400); p <= rub(6_500); p = t.minNext(p)) sb.append(p / 100).append(' ');
        assertEquals("2400 2500 2600 2700 2800 2900 3000 3250 3500 3750 4000 4250 4500 4750 5000 5250 5500 5750 6000 6500 ",
                sb.toString());
    }

    @Test
    void roundingToTheGrid() {
        // вниз — для максимумов и ставок, вверх — для стартовой цены; копейки — тоже
        assertEquals(rub(12_000), t.floor(rub(12_345)));
        assertEquals(rub(13_000), t.ceil(rub(12_345)));
        assertEquals(rub(1_900), t.floor(rub(1_950) + 50));
        assertEquals(rub(3_000), t.ceil(rub(2_950) + 50));      // вверх через порог полосы
        assertEquals(rub(30_000), t.floor(rub(31_000)));         // полоса 30–60 тыс. — шаг 2 500
        assertEquals(rub(12_000), t.ceil(rub(12_000)));
        assertEquals(0, t.floor(0));
        assertTrue(t.onGrid(rub(15_000)));
        assertFalse(t.onGrid(rub(15_500)));
        assertFalse(t.onGrid(rub(3_100)));
        assertFalse(t.onGrid(rub(100) + 1));
    }

    /** Из сетки: a < b, обе на сетке ⇒ b ≥ a + шаг(a) — «второй максимум + шаг» не перескакивает первый. */
    @Test
    void gridKeepsSecondMaxPlusStepWithinFirst() {
        SplittableRandom r = new SplittableRandom(7);
        for (int i = 0; i < 200_000; i++) {
            long a = t.floor(1 + r.nextLong(rub(30_000_000)));
            long b = t.floor(1 + r.nextLong(rub(30_000_000)));
            if (a == b || a == 0 || b == 0) continue;
            long lo = Math.min(a, b), hi = Math.max(a, b);
            assertTrue(hi >= t.minNext(lo), lo + " " + hi);
            assertTrue(t.onGrid(t.minNext(lo)), "minNext сходит с сетки: " + lo);
        }
    }

    @Test
    void ownTableAndValidation() {
        IncrementTable own = IncrementTable.rub(0, 500, 10_000, 1_000, 50_000, 5_000);
        assertEquals(rub(500), own.step(rub(9_999)));
        assertEquals(rub(10_000), own.minNext(rub(9_500)));
        assertThrows(IllegalArgumentException.class, () -> IncrementTable.rub(0, 500, 10_250, 1_000));   // порог не кратен шагам
        assertThrows(IllegalArgumentException.class, () -> IncrementTable.rub(100, 500));                // первый порог не 0
        assertThrows(IllegalArgumentException.class, () -> IncrementTable.rub(0, 500, 0, 1_000));        // пороги не растут
        assertThrows(IllegalArgumentException.class, () -> IncrementTable.rub(0, 0));                    // шаг 0
    }
}
