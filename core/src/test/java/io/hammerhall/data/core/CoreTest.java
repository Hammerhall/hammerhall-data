package io.hammerhall.data.core;

import io.hammerhall.data.core.geo.GeoMath;
import io.hammerhall.data.core.geo.Polygon;
import io.hammerhall.data.core.io.Json;
import io.hammerhall.data.core.io.RowFormat;
import io.hammerhall.data.core.money.Money;
import io.hammerhall.data.core.money.Tax;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.core.time.RuCalendar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreTest {

    @Test
    void rngIsKeyedNotOrdered() {
        RandomStreams s = new RandomStreams(42);
        RandomStreams.Stream a = s.stream("orders.daily");
        long x1 = a.at(7, LocalDate.of(2026, 3, 1)).nextLong();
        a.at(8).nextLong();
        long x2 = new RandomStreams(42).stream("orders.daily").at(7, LocalDate.of(2026, 3, 1)).nextLong();
        assertEquals(x1, x2, "одна сущность — одно значение, независимо от прочих вызовов");
        assertNotEquals(x1, s.stream("orders.other").at(7, LocalDate.of(2026, 3, 1)).nextLong());
        assertNotEquals(x1, new RandomStreams(43).stream("orders.daily").at(7, LocalDate.of(2026, 3, 1)).nextLong());
    }

    @Test
    void rngAlgorithmIsFrozen() {
        // SplitMix64: первые значения от нуля — эталон алгоритма (смена = новая версия генератора)
        Rng r = new Rng(0);
        assertEquals(0xe220a8397b1dcdafL, r.nextLong());
        assertEquals(0x6e789e6aa1b965f4L, r.nextLong());
        assertEquals(0xaf63dc4c8601ec8cL, RandomStreams.fnv64("a"));
    }

    @Test
    void rngBounds() {
        Rng r = new Rng(1);
        int[] hist = new int[7];
        for (int i = 0; i < 70_000; i++) hist[r.nextInt(7)]++;
        for (int h : hist) assertTrue(h > 9_000 && h < 11_000);
        for (int i = 0; i < 1000; i++) {
            int v = r.between(3, 5);
            assertTrue(v >= 3 && v <= 5);
            double d = r.nextDouble();
            assertTrue(d >= 0 && d < 1);
        }
    }

    @Test
    void money() {
        assertEquals("1290.00", Money.format(129000));
        assertEquals("-5.07", Money.format(-507));
        assertEquals("0.09", Money.format(9));
        assertEquals(22000, Money.vatIncluded(122000, 22));
        assertEquals(20000, Money.vatIncluded(120000, 20));
        assertEquals(1667, Money.vatIncluded(10001, 20), "1/6 от 100.01 = 16.668… → 16.67");
        assertEquals(3, Money.mulDiv(5, 1, 2), "половина — вверх");
        assertEquals(199_90, Money.retail(199.6));
        assertEquals(1_290_00, Money.retail(1312));
        assertEquals(12_490_00, Money.retail(12_380));
        assertEquals(54_990_00, Money.retail(54_700));
        assertTrue(Money.retailAtLeast(1000, 1_100_00) >= 1_100_00);
    }

    @Test
    void taxes() {
        assertEquals(20, Tax.vatRate(LocalDate.of(2025, 12, 31)));
        assertEquals(22, Tax.vatRate(LocalDate.of(2026, 1, 1)));
        assertEquals(0, Tax.techFeeKop("smartphone", LocalDate.of(2026, 11, 30)));
        assertEquals(250_00, Tax.techFeeKop("smartphone", LocalDate.of(2026, 12, 1)));
        assertEquals(500_00, Tax.techFeeKop("laptop", LocalDate.of(2027, 1, 1)));
        assertEquals(0, Tax.techFeeKop("kettle", LocalDate.of(2027, 1, 1)));
    }

    @Test
    void calendar() {
        RuCalendar c = new RuCalendar();
        assertTrue(c.isNonWorking(LocalDate.of(2026, 1, 9)), "перенос с 3 января");
        assertTrue(c.isNonWorking(LocalDate.of(2026, 3, 9)), "8 марта в воскресенье");
        assertTrue(c.isNonWorking(LocalDate.of(2026, 11, 4)));
        assertTrue(c.isNonWorking(LocalDate.of(2025, 11, 3)));
        assertFalse(c.isNonWorking(LocalDate.of(2025, 11, 1)), "рабочая суббота");
        assertFalse(c.isNonWorking(LocalDate.of(2026, 1, 12)));
        assertEquals(LocalDate.of(2025, 11, 28), RuCalendar.blackFriday(2025));
        assertEquals(LocalDate.of(2026, 11, 27), RuCalendar.blackFriday(2026));
        assertEquals(LocalDate.of(2027, 11, 26), RuCalendar.blackFriday(2027));
        assertTrue(c.eventsOn(LocalDate.of(2026, 11, 11)).contains(RuCalendar.SalesEvent.ELEVEN_ELEVEN));
        assertTrue(c.demand(LocalDate.of(2026, 7, 10), RuCalendar.Season.COOLING)
                > 3 * c.demand(LocalDate.of(2026, 1, 20), RuCalendar.Season.COOLING));
        List<RuCalendar.EventWindow> w = c.eventWindows(LocalDate.of(2025, 10, 1), LocalDate.of(2027, 10, 30));
        assertTrue(w.size() >= 17 && w.size() <= 20, "событий в окне: " + w.size());
    }

    @Test
    void period() {
        Period p = new Period(LocalDate.of(2025, 10, 1), LocalDate.of(2027, 10, 30), java.time.Instant.parse("2027-09-01T00:00:00Z"),
                Period.MOSCOW);
        assertEquals(1759276800L, p.startEpoch());
        assertEquals(1824940800L, p.endEpochExclusive(), "2027-10-31T00:00Z");
        assertEquals(1764536400L, p.businessMidnight(LocalDate.of(2025, 12, 1)), "00:00 по Москве = 21:00 UTC");
        assertEquals(LocalDate.of(2025, 12, 1), p.businessDate(1764536400L));
    }

    @Test
    void formatting() {
        assertEquals("55.751244", RowFormat.fixed(55.7512441, 6));
        assertEquals("-0.500000", RowFormat.fixed(-0.5, 6));
        assertEquals("0.000000", RowFormat.fixed(-0.0000001, 6));
        assertEquals("6.1", RowFormat.trimZeros("6.100"));
        assertEquals("128", RowFormat.trimZeros("128.000"));
        assertEquals("0", RowFormat.trimZeros("-0.000"));
    }

    @Test
    void json() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", "sto-data-v1");
        m.put("rows", 12L);
        m.put("files", List.of(Map.of("name", "a\"b"), List.of()));
        String s = Json.write(m);
        assertTrue(s.startsWith("{\n \"version\": \"sto-data-v1\",\n \"rows\": 12,"));
        assertEquals(Json.write(Json.parse(s)), s);
        // дробные — из чужого JSON (манифест, собранный Python): тот же текст туда и обратно
        String air = "{\n \"month\": [\n  0.84,\n  1.0\n ],\n \"n\": -3\n}\n";
        assertEquals(air, Json.write(Json.parse(air)));
        assertEquals(new java.math.BigDecimal("0.84"), ((List<?>) ((Map<?, ?>) Json.parse(air)).get("month")).getFirst());
    }

    @Test
    void polygon() {
        double[] lat = new double[16], lon = new double[16];
        for (int i = 0; i < 16; i++) {
            double[] p = GeoMath.polar(55.75, 37.62, 20, 360.0 * i / 16);
            lat[i] = p[0];
            lon[i] = p[1];
        }
        Polygon cw = new Polygon(lon, lat);
        assertTrue(cw.contains(37.62, 55.75));
        assertFalse(cw.contains(37.62, 56.2));
        assertEquals(cw.wkt(), Polygon.parseWkt(cw.wkt()).wkt());
        assertEquals(cw.lon(0), cw.lon(cw.vertices() - 1));
        double d = GeoMath.haversineKm(55.75, 37.62, 59.94, 30.31);
        assertTrue(d > 630 && d < 640, "Москва — Петербург ≈ 634 км: " + d);
    }
}
