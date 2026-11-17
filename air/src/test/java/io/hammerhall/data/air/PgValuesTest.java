package io.hammerhall.data.air;

import io.hammerhall.data.air.dump.PgValues;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Преобразования значений — те же, что у SQL сборки {@code air-data-v1} (DuckDB). */
class PgValuesTest {
    @Test
    void timestamptzWithOffsets() {
        long t = 1759276800L; // 2025-10-01 00:00:00 UTC
        assertEquals(t, PgValues.epoch("2025-10-01 00:00:00+00"));
        assertEquals(t, PgValues.epoch("2025-10-01 03:00:00+03"));
        assertEquals(t, PgValues.epoch("2025-09-30 19:00:00-05"));
        assertEquals(t, PgValues.epoch("2025-10-01 05:30:00+05:30"));
        assertEquals(t, PgValues.epoch("2025-09-30 20:30:00-03:30"));
        assertEquals(t, PgValues.epoch("2025-10-01 02:30:17+02:30:17"), "смещение с секундами");
        assertEquals(t, PgValues.epoch("2025-10-01 00:00:00"), "без смещения — UTC");
        assertNull(PgValues.epoch(null));
        for (String bad : List.of("2025-10-01", "2025-10-01 00:00:00+0", "infinity", "2025-10-01 00:00:00+00 BC",
                "2025-10-01 00:00:00.1234567+00", "2025-13-01 00:00:00+00")) {
            assertThrows(RuntimeException.class, () -> PgValues.epoch(bad), bad);
        }
    }

    /** Доли секунды — округлением к ближайшему, половина — к чётному (как {@code double → bigint} в DuckDB). */
    @Test
    void fractionsRoundHalfEven() {
        assertEquals(PgValues.Rounding.NEAREST_EVEN, PgValues.ROUNDING);
        // последняя бронь выгрузки air-data-v1 — в выпуске 1819756799
        assertEquals(1819756799L, PgValues.epoch("2027-08-31 23:59:58.579152+00"));
        assertEquals(1819756798L, PgValues.epoch("2027-08-31 23:59:58.499999+00"));
        assertEquals(1819756798L, PgValues.epoch("2027-08-31 23:59:58.5+00"), "половина — к чётному: 98");
        assertEquals(1819756800L, PgValues.epoch("2027-08-31 23:59:59.5+00"), "половина — к чётному: 800");
        assertEquals(1819756799L, PgValues.epoch("2027-08-31 23:59:59.000001+00"));
        assertEquals(1819756798L, PgValues.seconds(1819756798_400_000L));
        assertEquals(1819756799L, PgValues.seconds(1819756798_600_000L));
    }

    @Test
    void intervalToMinutes() {
        assertEquals(125, PgValues.durationMinutes("02:05:00"));
        assertEquals(0, PgValues.durationMinutes("00:00:00"));
        assertEquals(24 * 60 + 60, PgValues.durationMinutes("1 day 01:00:00"));
        assertEquals(2 * 24 * 60, PgValues.durationMinutes("2 days"));
        assertEquals(30 * 24 * 60, PgValues.durationMinutes("1 mon"));
        assertEquals(-90, PgValues.durationMinutes("-01:30:00"));
        assertEquals(90, PgValues.durationMinutes("01:30:30"), "90,5 → 90 (к чётному)");
        assertEquals(92, PgValues.durationMinutes("01:31:30"), "91,5 → 92 (к чётному)");
        assertEquals(91, PgValues.durationMinutes("01:30:30.000001"));
        assertNull(PgValues.durationMinutes(null));
        for (String bad : List.of("", "2 weeks", "1:2", "abc")) assertThrows(RuntimeException.class, () -> PgValues.durationMinutes(bad), bad);
    }

    @Test
    void rangePointArrayAndTrim() {
        String r = "[\"2025-10-01 00:00:00+00\",\"2025-11-01 03:00:00+03\")";
        assertEquals(1759276800L, PgValues.rangeBound(r, 1));
        assertEquals(1761955200L, PgValues.rangeBound(r, 2));
        assertThrows(RuntimeException.class, () -> PgValues.rangeBound("[\"2025-10-01 00:00:00+00\",)", 2));
        assertEquals("-145.51", PgValues.pointPart("(-145.51,-17.3526)", 1));
        assertEquals("-17.3526", PgValues.pointPart("(-145.51,-17.3526)", 2));
        assertEquals("1,2,3,5,6,7", PgValues.trim("{1,2,3,5,6,7}", "{}"));
        assertEquals("", PgValues.trim("{}", "{}"));
        assertEquals("b", PgValues.splitPart("a,b,c", ',', 2));
        assertEquals("", PgValues.splitPart("a", ',', 2));
    }

    @Test
    void jsonFields() {
        String j = "{\"en\": \"Kot d'Ivoire \\\"x\\\" \\u00e9\\\\\", \"ru\": \"Кот д'Ивуар\", \"n\": 12, \"z\": null, \"o\": {\"a\": [1, \"}\"]}}";
        assertEquals("Kot d'Ivoire \"x\" é\\", PgValues.jsonString(j, "en"));
        assertEquals("Кот д'Ивуар", PgValues.jsonString(j, "ru"));
        assertEquals("12", PgValues.jsonString(j, "n"));
        assertNull(PgValues.jsonString(j, "z"));
        assertNull(PgValues.jsonString(j, "missing"));
        assertEquals("{\"a\": [1, \"}\"]}", PgValues.jsonString(j, "o"));
        assertEquals("b", PgValues.jsonString("{\"ru\":\"a\",\"en\":\"b\"}", "en"));
        assertNull(PgValues.jsonString(null, "en"));
        assertThrows(RuntimeException.class, () -> PgValues.jsonString("{\"en\": \"x\"", "en"));
    }

    /** Значения «как есть»: запись выпуска совпадает с текстом выгрузки, иначе — ошибка, а не тихо другая запись. */
    @Test
    void asIsOrFail() {
        assertEquals(new BigDecimal("5000.00"), PgValues.moneyAsIs("5000.00"));
        assertThrows(RuntimeException.class, () -> PgValues.moneyAsIs("5000"));
        assertThrows(RuntimeException.class, () -> PgValues.moneyAsIs("5000.000"));
        assertEquals(new BigDecimal("38"), PgValues.decimalAsIs("38", 6));
        assertEquals(new BigDecimal("-0.5"), PgValues.decimalAsIs("-0.5", 6));
        for (String bad : List.of("1e-05", "38.0", "1.1234567", "-0", "+1")) {
            assertThrows(RuntimeException.class, () -> PgValues.decimalAsIs(bad, 6), bad);
        }
        assertEquals(6500, PgValues.intAsIs("6500"));
        assertThrows(RuntimeException.class, () -> PgValues.intAsIs("06500"));
        assertEquals(Boolean.TRUE, PgValues.bool("t"));
        assertThrows(RuntimeException.class, () -> PgValues.bool("true"));
    }
}
