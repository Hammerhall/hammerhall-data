package io.hammerhall.data.mov;

import io.hammerhall.data.core.geo.GeoMath;
import io.hammerhall.data.mov.build.Places;
import io.hammerhall.data.mov.source.SourceData;
import io.hammerhall.data.mov.source.WorldCities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Места: расстояние и его округление, ближайший город «Мира», чтение городов, вид и страна места. */
class PlacesTest {
    static WorldCities cities() throws IOException {
        return WorldCities.read(TinySnapshot.citiesPath());
    }

    @Test
    @DisplayName("Расстояние: половина — к чётному до 0,1 км по точной десятичной записи double")
    void km() {
        assertEquals(new BigDecimal("21.2"), Places.km(21.25), "21,25 — точно в double: к чётному");
        assertEquals(new BigDecimal("2.2"), Places.km(2.25));
        assertEquals(new BigDecimal("2.4"), Places.km(2.35), "2,35 в double — чуть больше 2,35");
        assertEquals(new BigDecimal("0.1"), Places.km(0.05), "0,05 в double — чуть больше 0,05");
        assertEquals(new BigDecimal("0.0"), Places.km(0.0));
        assertEquals(new BigDecimal("30.0"), Places.km(30.0));
    }

    @Test
    @DisplayName("Координата места: половина — к чётному до 7 знаков; нет — пусто")
    void coord() {
        assertEquals(new BigDecimal("40.0000000"), Places.coord(new BigDecimal("40.00000005")));
        assertEquals(new BigDecimal("40.0000002"), Places.coord(new BigDecimal("40.00000015")));
        assertEquals(new BigDecimal("-74.0000002"), Places.coord(new BigDecimal("-74.00000015")));
        assertEquals(new BigDecimal("12.5000000"), Places.coord(new BigDecimal("12.5")));
        assertNull(Places.coord(null));
    }

    @Test
    @DisplayName("Ближайший город: только той же страны, не дальше 30 км, при равенстве — меньший id; гаверсинус StrictMath")
    void nearest() throws IOException {
        WorldCities w = cities();
        WorldCities.Nearest n = w.nearest("USA", 40.08, -74, 30);
        assertEquals(1_000_003, n.id(), "Otherland (QXA) ближе, но в другой стране");
        assertEquals(GeoMath.haversineKm(40.08, -74, 40.1, -74), n.km());
        assertEquals(1_000_001, w.nearest("USA", 40, -74.25, 30).id(), "Rivermouth и Lakeside — на равном расстоянии");
        assertEquals(w.nearest("USA", 40, -74.25, 30).km(), GeoMath.haversineKm(40, -74.25, 40, -74.5));
        assertEquals(1_000_006, w.nearest("QXA", 40.08, -74, 30).id());
        assertNull(w.nearest("USA", 45, -74, 30), "Farvale — дальше 30 км");
        assertEquals(1_000_007, w.nearest("USA", 45, -74, 50).id());
        assertNull(w.nearest("FRA", 40, -74, 30), "городов страны нет");
        assertNull(w.nearest(null, 40, -74, 30));
        assertEquals(0.0, w.nearest("USA", 40, -74, 30).km());
        assertEquals(6371.0088, GeoMath.EARTH_RADIUS_KM);
    }

    @Test
    @DisplayName("Города «Мира»: колонки по заголовку, sha256 содержимого, id; без нужной колонки — ошибка с именем файла")
    void read(@TempDir Path tmp) throws IOException, NoSuchAlgorithmException {
        WorldCities w = cities();
        assertEquals(5, w.size());
        assertTrue(w.has(1_000_001));
        assertFalse(w.has(1_000_002));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TinySnapshot.CITIES.getBytes(StandardCharsets.UTF_8)));
        assertEquals(sha, w.sha256Tsv());
        Path bad = tmp.resolve("cities.tsv.gz");
        Files.write(bad, TinySnapshot.gz("id\tname\tcountry_code\tlatitude\n1\tA\tUSA\t1\n"));
        IOException e = assertThrows(IOException.class, () -> WorldCities.read(bad));
        assertTrue(e.getMessage().contains("cities.tsv.gz") && e.getMessage().contains("longitude"), e.getMessage());
        Files.write(bad, TinySnapshot.gz("id\tcountry_code\tlatitude\tlongitude\n1\tUSA\t1\t2\n1\tUSA\t3\t4\n"));
        assertThrows(IOException.class, () -> WorldCities.read(bad), "город дважды");
        assertThrows(IOException.class, () -> WorldCities.read(tmp.resolve("nope.tsv.gz")));
    }

    @Test
    @DisplayName("Место: без названия — нет; страна — сама (ISO3) или по P17; исключённые (страна, место в ней, из списка) — нет")
    void resolve() throws IOException {
        WorldCities w = cities();
        Map<Integer, SourceData.Codes> countries = Map.of(30, new SourceData.Codes(List.of("US"), List.of("USA")),
                9001, new SourceData.Codes(List.of("EH"), List.of("ESH")), 9002, new SourceData.Codes(List.of("XK"), List.of("XKS")),
                6000, new SourceData.Codes(List.of(), List.of()));
        Exclusions ex = new Exclusions(Exclusions.COUNTRIES3, Exclusions.COUNTRIES2, java.util.Set.of(77));
        SourceData.Place town = new SourceData.Place(1_000_001, 30, "Town", null, null, new BigDecimal("40"), new BigDecimal("-74"));
        Places.Resolved r = Places.resolve(6000, town, countries, ex, w);
        assertEquals("place", r.place().kind(), "строка с пустыми кодами — не страна");
        assertEquals(Places.CityPath.GEONAMES, r.path());
        assertNull(r.excluded());
        assertEquals("USA", r.place().countryCode());
        Places.Resolved us = Places.resolve(30, new SourceData.Place(0, 30, "US", null, null, null, null), countries, ex, w);
        assertEquals("country", us.place().kind());
        assertEquals(Places.CityPath.COUNTRY, us.path());
        assertNull(us.place().cityId());
        assertEquals(Places.Excluded.EXCLUDED_COUNTRY, Places.resolve(9001, new SourceData.Place(0, 9001, "Zone", null, null, null,
                null), countries, ex, w).excluded(), "(а) страна с исключённым кодом");
        assertEquals(Places.Excluded.EXCLUDED_COUNTRY, Places.resolve(9002, new SourceData.Place(0, 9002, "Zone", null, null, null,
                null), countries, ex, w).excluded(), "(а) по ISO2, хотя ISO3 не из списка");
        Places.Resolved in = Places.resolve(7, new SourceData.Place(1_000_001, 9001, "Camp", null, null,
                new BigDecimal("40"), new BigDecimal("-74")), countries, ex, w);
        assertEquals(Places.Excluded.IN_EXCLUDED_COUNTRY, in.excluded(), "(б) место в исключённой стране");
        assertNull(in.place());
        assertEquals(Places.Excluded.LISTED, Places.resolve(77, town, countries, ex, w).excluded(), "(в) из списка мест");
        assertNull(Places.resolve(77, town, countries, Exclusions.DEFAULT, w).excluded(), "список подменён");
        assertNull(Places.resolve(9, new SourceData.Place(0, 9001, null, null, "Только ru", null, null), countries, ex, w),
                "ни en, ни mul — места нет (не «исключено»)");
        assertEquals("Mul", Places.resolve(9, new SourceData.Place(0, 30, null, "Mul", null, null, null), countries, ex, w)
                .place().name(), "en нет — mul");
        assertEquals("E", Places.resolve(9, new SourceData.Place(0, 30, "E", "Mul", null, null, null), countries, ex, w)
                .place().name(), "en — раньше mul");
        assertEquals(Places.CityPath.NONE, Places.resolve(8, new SourceData.Place(0, 0, "Y", null, null, new BigDecimal("40"),
                new BigDecimal("-74")), countries, ex, w).path(), "страны нет — ближайшего не ищем");
        assertNull(Places.resolve(10, null, countries, ex, w), "нет в places");
    }

    @Test
    @DisplayName("Список мест из ресурса: номера Q по строке, # — комментарий, пустые строки — мимо; иное — ошибка")
    void placesList() {
        assertEquals(java.util.Set.of(865, 42), Exclusions.parsePlaces("# заголовок\nQ865\n\n  Q42  # пояснение\n", "x"));
        assertThrows(IllegalArgumentException.class, () -> Exclusions.parsePlaces("Q12\n865\n", "x"));
        assertThrows(IllegalArgumentException.class, () -> Exclusions.parsePlaces("Q0\n", "x"));
        assertThrows(IllegalArgumentException.class, () -> Exclusions.parsePlaces("Q99999999999\n", "x"));
        assertTrue(Exclusions.DEFAULT.places().contains(865), "ресурс выпуска прочитан");
        assertTrue(Exclusions.DEFAULT.places().size() >= 50);
        assertEquals(Exclusions.PLACES, Exclusions.DEFAULT.places());
        assertEquals(java.util.Set.of(), new Exclusions(List.of(), List.of()).places());
    }
}
