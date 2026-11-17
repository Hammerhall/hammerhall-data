package io.hammerhall.data.air;

import io.hammerhall.data.air.select.LongSet;
import io.hammerhall.data.air.select.Radix;
import io.hammerhall.data.air.select.Seasonality;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.io.Json;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Модель сезонности: MD5-доля, праздники, месяц × день недели, описание модели в манифесте; сортировка и множество. */
class SeasonalityTest {
    /** Первые 4 байта MD5 little-endian / 2^32 (значения посчитаны {@code hashlib.md5} в Python). */
    @Test
    void md5Unit() {
        assertEquals(2248567528L / 4294967296.0, Seasonality.unit("6TAS39"));
        assertEquals(1103250337L / 4294967296.0, Seasonality.unit("GGY5EU"));
        assertEquals(3657805298L / 4294967296.0, Seasonality.unit("JD369U"));
    }

    @Test
    void shareByHolidayRegionAndWeekday() {
        // 2026-01-12 — понедельник, Европа, январь: 0.84 × 0.95
        assertEquals(0.84 * 0.95, Seasonality.share("Germany", LocalDate.of(2026, 1, 12)));
        // 2026-01-04 — последний день рождественского окна Европы
        assertEquals(1.0, Seasonality.share("France", LocalDate.of(2026, 1, 4)));
        assertEquals(0.84 * 1.0, Seasonality.share("France", LocalDate.of(2026, 1, 11)), "воскресенье");
        // окно страны (не региона): День благодарения — у США, не у Канады
        assertEquals(1.0, Seasonality.share("United States", LocalDate.of(2025, 11, 24)));
        assertEquals(0.90 * 0.95, Seasonality.share("Canada", LocalDate.of(2025, 11, 24)));
        // Чили — и своё окно, и окно региона SH
        assertEquals(1.0, Seasonality.share("Chile", LocalDate.of(2026, 9, 18)));
        assertEquals(0.9 * 0.98, Seasonality.share("New Zealand", LocalDate.of(2026, 9, 18)));
        assertTrue(Double.isNaN(Seasonality.share("Testland", LocalDate.of(2026, 1, 12))), "страны нет в модели");
        assertFalse(Seasonality.keep("6TAS39", "Testland", LocalDate.of(2026, 1, 12)));
    }

    /** Описание модели в манифесте — то же, что у выпуска air-data-v1 (поле selection ожиданий). */
    @Test
    @SuppressWarnings("unchecked")
    void manifestSelectionMatchesRelease() throws Exception {
        Path exp = Cli.dataDir(new AirDataset()).resolve("expected").resolve("air-data-v1-full.json");
        Map<String, Object> e = (Map<String, Object>) Json.parse(Files.readString(exp, StandardCharsets.UTF_8));
        Object want = ((Map<String, Object>) e.get("manifest")).get("selection");
        assertEquals(Json.write(want), Json.write(Seasonality.manifest()));
    }

    @Test
    void radixAndSet() {
        Random r = new Random(1);
        int n = 200_000;
        long[] k = new long[n], v = new long[n];
        for (int i = 0; i < n; i++) {
            k[i] = r.nextLong() >>> 1 >>> (r.nextInt(40));
            v[i] = k[i] ^ 0x5555;
        }
        long[] sorted = k.clone();
        java.util.Arrays.sort(sorted);
        Radix.sort(k, v, n);
        for (int i = 0; i < n; i++) {
            assertEquals(sorted[i], k[i]);
            assertEquals(k[i] ^ 0x5555, v[i], "значение едет с ключом");
        }
        LongSet s = new LongSet(n);
        for (long x : sorted) if (x != 0) s.add(x);
        for (long x : sorted) if (x != 0) assertTrue(s.contains(x));
        assertFalse(s.contains(Long.MAX_VALUE - 7));
    }
}
