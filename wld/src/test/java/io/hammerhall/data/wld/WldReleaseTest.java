package io.hammerhall.data.wld;

import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.io.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Выпуск из крошечного снимка целиком: таблицы, манифест, тексты, повторяемость. */
class WldReleaseTest {
    static Release.Result run(WldDataset d, String profile, Path out) throws IOException {
        Map<String, String> opt = new LinkedHashMap<>();
        opt.put("profile", profile);
        opt.put("out", out.toString());
        opt.put("source", TinySnapshot.path().toString());
        return Cli.run(d, opt);
    }

    static List<String> lines(Path gz) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(gz)), StandardCharsets.UTF_8))) {
            for (String s; (s = r.readLine()) != null; ) out.add(s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> manifest(Path dir) throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Выпуск: самопроверка, порядок и запись строк, столица ссылается на город, NULL — пустое поле")
    void releaseRows(@TempDir Path tmp) throws IOException {
        Release.Result r = run(new WldDataset(), "small", tmp);
        assertTrue(r.ok(), r.report().render());
        List<String> countries = lines(tmp.resolve("countries.tsv.gz"));
        assertEquals("code\tcode2\tname\tname_ru\tlocal_name\tcontinent\tregion\tregion_ru\tsurface_area\tpopulation\t"
                + "life_expectancy\tgnp\tgnp_old\tcapital", countries.getFirst());
        assertEquals("QMA\tQM\tQumland\tКумландия\tKumland\tEurope\tWestern Europe\tЗападная Европа\t41285\t5000000\t72.124\t"
                + "2345.68\t1234.56\t1001", countries.get(1));
        assertEquals("QTA\tQT\tTundra\tТундра\t\tAntarctica\tOutlying Oceania\tВнешняя Океания\t14000000\t\t\t\t\t", countries.get(5));
        List<String> cities = lines(tmp.resolve("cities.tsv.gz"));
        assertEquals("id\tname\tname_ru\tcountry_code\tdistrict\tpopulation\tlatitude\tlongitude\ttimezone", cities.getFirst());
        assertEquals("1001\tAlpha City\tАльфа-Сити\tQMA\tUpper Province\t2500000\t48.1\t11.5\tEurope/Berlin", cities.get(1));
        assertEquals("2001\tBetaville\tБетавиль\tQNA\tCoast\t900\t-33.86785\t151.20732\tAsia/Tokyo", cities.get(4));
        assertEquals(7, cities.size());
        List<String> langs = lines(tmp.resolve("country_languages.tsv.gz"));
        assertEquals("QMA\tde\tGerman\tнемецкий\tt\t60", langs.get(1));
        assertEquals(List.of("country_code\tneighbour_code", "QMA\tQNA", "QMA\tQPA", "QMA\tQRA", "QNA\tQMA", "QPA\tQMA", "QRA\tQMA"),
                lines(tmp.resolve("country_borders.tsv.gz")));
        assertEquals("QRA\t2020\t\t\t1\t", lines(tmp.resolve("country_years.tsv.gz")).getLast());
    }

    @Test
    @DisplayName("Повторяемость: тот же снимок и профиль — тот же выпуск; профили различаются только городами")
    @SuppressWarnings("unchecked")
    void sameSnapshotSameRelease(@TempDir Path tmp) throws IOException {
        assertTrue(run(new WldDataset(), "medium", tmp.resolve("a")).ok());
        assertTrue(run(new WldDataset(), "medium", tmp.resolve("b")).ok());
        assertTrue(run(new WldDataset(), "full", tmp.resolve("f")).ok());
        Map<String, Object> a = manifest(tmp.resolve("a")), b = manifest(tmp.resolve("b")), f = manifest(tmp.resolve("f"));
        assertEquals(a, b);
        Map<String, String> sa = new LinkedHashMap<>(), sf = new LinkedHashMap<>();
        for (Map<String, Object> x : (List<Map<String, Object>>) a.get("files")) sa.put((String) x.get("table"), (String) x.get("sha256_tsv"));
        for (Map<String, Object> x : (List<Map<String, Object>>) f.get("files")) sf.put((String) x.get("table"), (String) x.get("sha256_tsv"));
        for (String t : List.of("countries", "country_languages", "country_borders", "country_years")) assertEquals(sf.get(t), sa.get(t), t);
        assertNotEquals(sf.get("cities"), sa.get("cities"));
        assertEquals(WldSchema.TABLES, new ArrayList<>(sa.keySet()));
        Map<String, Object> in = ((List<Map<String, Object>>) a.get("inputs")).getFirst();
        assertEquals(WldDataset.SNAPSHOT + ".zip", in.get("name"), "имя — из fetch.json, не из имени файла");
        assertEquals(15_000L, ((Map<String, Object>) a.get("cities")).get("min_population"));
        assertEquals("1960-2022", a.get("world_bank_years"));
    }

    @Test
    @DisplayName("Тексты выпуска: LICENSE — все источники и текст Unicode из снимка; SOURCE.md — без кодов исключений")
    void texts(@TempDir Path tmp) throws IOException {
        assertTrue(run(new WldDataset(), "small", tmp).ok());
        String license = Files.readString(tmp.resolve("LICENSE"), StandardCharsets.UTF_8);
        for (String s : List.of("GeoNames", "World Bank", "Wikidata", "CC0", "CC BY 4.0", "Unicode License v3", WldDataset.SNAPSHOT,
                TinySnapshot.CLDR_LICENSE.strip())) {
            assertTrue(license.contains(s), s);
        }
        String md = Files.readString(tmp.resolve("SOURCE.md"), StandardCharsets.UTF_8);
        assertTrue(md.contains("## Профиль `small`"));
        assertTrue(md.contains("без нескольких записей источников"));
        assertTrue(md.contains("Unicode CLDR 49.0.0"));
        for (String code : Exclusions.COUNTRIES) assertFalse(md.contains("`" + code + "`") || md.contains(" " + code + " "), code);
        assertTrue(md.contains("| `cities.tsv.gz` | 6 |"), md);
    }

    @Test
    @DisplayName("Входы: имя снимка в fetch.json не то — предупреждение, со strict — ошибка; sha256 снимка — 64 знака hex")
    void strictInputs(@TempDir Path tmp) throws IOException {
        Path other = TinySnapshot.with(tmp, Map.of("fetch.json",
                TinySnapshot.utf8("{\"snapshot\":\"wld-source-20990101\",\"fetched_at\":\"2099-01-01T00:00:00Z\"}")));
        WldDataset d = new WldDataset();
        Map<String, String> opt = Map.of("profile", "small", "out", tmp.resolve("o").toString(), "source", other.toString());
        assertThrows(IllegalStateException.class, () -> Cli.run(d, Cli.config(d, opt), true));
        assertTrue(Cli.run(d, opt).ok());
        assertTrue(WldDataset.SOURCE.sha256().matches("[0-9a-f]{64}") && !WldDataset.SOURCE.sha256().matches("0{64}"),
                "sha256 снимка — настоящая сумма, не заглушка");
    }
}
