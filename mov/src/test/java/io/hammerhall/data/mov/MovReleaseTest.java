package io.hammerhall.data.mov;

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
class MovReleaseTest {
    static Release.Result run(MovDataset d, String profile, Path out) throws IOException {
        Map<String, String> opt = new LinkedHashMap<>();
        opt.put("profile", profile);
        opt.put("out", out.toString());
        opt.put("source", TinySnapshot.path().toString());
        opt.put("cities", TinySnapshot.citiesPath().toString());
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
    @DisplayName("Выпуск: самопроверка, заголовки и порядок колонок, запись строк, NULL — пустое поле, жанров — 24")
    void releaseRows(@TempDir Path tmp) throws IOException {
        Release.Result r = run(new MovDataset(TinySnapshot.EXCLUSIONS), "full", tmp);
        assertTrue(r.ok(), r.report().render());
        List<String> genres = lines(tmp.resolve("genres.tsv.gz"));
        assertEquals(25, genres.size());
        assertEquals(List.of("id\tname", "1\tAction", "2\tAdult"), genres.subList(0, 3));
        assertEquals("12\tFilm noir", genres.get(12));
        assertEquals("19\tSci-Fi", genres.get(19));
        List<String> movies = lines(tmp.resolve("movies.tsv.gz"));
        assertEquals("id\ttitle\ttitle_ru\tyear\trelease_date\truntime\trank\tbudget_usd\tbox_office_usd", movies.getFirst());
        assertTrue(movies.get(1).startsWith("101\tAlpha Story\tАльфа\t1999\t1999-03-31\t90\t"), movies.get(1));
        assertTrue(movies.get(1).endsWith("\t1000000\t2500000"), movies.get(1));
        assertTrue(movies.get(2).startsWith("107\tThe Story\tИстория\t1950\t\t\t"), movies.get(2));
        assertTrue(movies.get(2).endsWith("\t\t"), movies.get(2));
        assertEquals("id\tfirst_name\tlast_name\tgender\tname_ru", lines(tmp.resolve("actors.tsv.gz")).getFirst());
        assertTrue(lines(tmp.resolve("actors.tsv.gz")).contains("505\t\tZelda\tF\t"));
        assertEquals(List.of("id\tfirst_name\tlast_name\tname_ru", "501\tSteven\tHalden\tСтивен Халден", "506\tJohn\tSmith\t"),
                lines(tmp.resolve("directors.tsv.gz")));
        List<String> roles = lines(tmp.resolve("roles.tsv.gz"));
        assertEquals("actor_id\tmovie_id\trole", roles.getFirst());
        assertEquals("502\t101\tCaptain Rook / Narrator", roles.get(1));
        assertEquals("502\t107\tSelf", roles.get(2));
        assertEquals("director_id\tmovie_id", lines(tmp.resolve("movies_directors.tsv.gz")).getFirst());
        assertEquals("movie_id\tgenre_id", lines(tmp.resolve("movies_genres.tsv.gz")).getFirst());
        List<String> dg = lines(tmp.resolve("directors_genres.tsv.gz"));
        assertEquals("director_id\tgenre_id\tprob", dg.getFirst());
        assertTrue(dg.contains("501\t6\t0.6667") && dg.contains("506\t20\t0.5"), dg.toString());
        assertEquals(List.of("movie_id\tcountry_code", "101\tQXA", "101\tUSA", "112\tUSA"), lines(tmp.resolve("movies_countries.tsv.gz")));
        assertEquals(List.of("movie_id\tlanguage_code", "101\ten", "101\tqya"), lines(tmp.resolve("movies_languages.tsv.gz")));
    }

    @Test
    @DisplayName("Выпуск, новые таблицы: заголовки, порядок по ключу, NULL — пустое поле, won — t/f")
    void releaseRowsV2(@TempDir Path tmp) throws IOException {
        Release.Result r = run(new MovDataset(TinySnapshot.EXCLUSIONS), "full", tmp);
        assertTrue(r.ok(), r.report().render());
        List<String> people = lines(tmp.resolve("people.tsv.gz"));
        assertEquals("id\tfirst_name\tlast_name\tname_ru\tgender\tbirth_date\tbirth_year\tdeath_date\tdeath_year\tbirth_city_id"
                + "\tbirth_place_id", people.getFirst());
        assertEquals("501\tSteven\tHalden\tСтивен Халден\tM\t1946-12-18\t1946\t\t\t1000001\t6000", people.get(1));
        assertTrue(people.contains("506\tJohn\tSmith\t\t\t1900-03-04\t1900\t1999-12-31\t1999\t\t"), people.toString());
        assertEquals(17, people.size());
        assertEquals(List.of("person_id\tcountry_code", "501\tUSA", "510\tQXA", "510\tUSA"), lines(tmp.resolve("people_countries.tsv.gz")));
        assertEquals(List.of("movie_id\tperson_id\tjob", "101\t501\tproducer", "101\t502\twriter", "101\t516\twriter",
                "101\t517\tcomposer", "101\t518\tcinematographer", "112\t516\twriter", "112\t518\teditor"),
                lines(tmp.resolve("movies_crew.tsv.gz")));
        assertEquals(List.of("id\tname\tname_ru\tcountry_code\thq_place_id\thq_city_id",
                "7001\tNorthwind Pictures\tНордвинд\tUSA\t6001\t1000003", "7002\tBluebird Films\t\t\t\t",
                "7004\tHarbor Media\t\t\t\t"), lines(tmp.resolve("companies.tsv.gz")));
        assertEquals(List.of("movie_id\tcompany_id\trole", "101\t7001\tdistribution", "101\t7001\tproduction",
                "101\t7002\tdistribution", "112\t7004\tdistribution"), lines(tmp.resolve("movies_companies.tsv.gz")));
        assertEquals(List.of("id\tname\tname_ru", "7101\tAlpha Saga\tСага об Альфе", "7102\tNight Saga\t"),
                lines(tmp.resolve("series.tsv.gz")));
        assertEquals(List.of("movie_id\tseries_id\tordinal", "101\t7101\t1", "112\t7101\t", "112\t7102\t", "113\t7102\t10000",
                "117\t7101\t"), lines(tmp.resolve("movies_series.tsv.gz")));
        assertEquals(List.of("movie_id\tnext_movie_id", "101\t112", "112\t117", "119\t120"), lines(tmp.resolve("movies_sequels.tsv.gz")));
        assertEquals(List.of("id\tname\tname_ru", "7201\tGolden Reel\tЗолотая катушка", "7202\tSilver Frame\t"),
                lines(tmp.resolve("awards.tsv.gz")));
        assertEquals(List.of("movie_id\taward_id\twon\tyear", "101\t7201\tf\t", "101\t7201\tt\t1999", "101\t7202\tf\t",
                "112\t7202\tf\t2002"), lines(tmp.resolve("movies_awards.tsv.gz")));
        assertEquals(List.of("movie_id\tcountry_code\trelease_date", "101\tQXA\t1999-04-10", "101\tUSA\t1999-03-31",
                "112\tUSA\t2003-03-03"), lines(tmp.resolve("movies_releases.tsv.gz")));
        List<String> titles = lines(tmp.resolve("movies_titles.tsv.gz"));
        assertEquals("movie_id\tlang\ttitle", titles.getFirst());
        assertEquals(List.of("101\tde\tAlpha-Geschichte", "101\ten\tAlpha Story", "101\tru\tАльфа"), titles.subList(1, 4));
        assertTrue(titles.contains("124\tcs\tAlternativní název") && titles.contains("124\tja\t日本語の題名"), titles.toString());
        assertEquals(26, titles.size());
        assertEquals(List.of("movie_id\tplace_id\tcity_id", "101\t6000\t1000001", "101\t6001\t1000003", "101\t6002\t1000001",
                "101\t6005\t", "112\t6000\t1000001", "113\t6010\t", "113\t6012\t1000001"), lines(tmp.resolve("movies_locations.tsv.gz")));
        assertEquals(List.of("id\tname\tname_ru\tkind\tlatitude\tlongitude\tcountry_code\tcity_id\tcity_km",
                "30\tUnited States\tСоединённые Штаты\tcountry\t39.8\t-98.5\tUSA\t\t",
                "6000\tRivermouth\tРивермут\tplace\t40\t-74.0000002\tUSA\t1000001\t0",
                "6001\tHillford\t\tplace\t40.08\t-74\tUSA\t1000003\t2.2",
                "6002\tOld Mill\t\tplace\t40\t-74.25\tUSA\t1000001\t21.3",
                "6005\tHillford North\t\tplace\t\t\tUSA\t\t",
                "6006\tFar Away\t\tplace\t45\t-74\tUSA\t\t",
                "6009\tOpen Sea\t\tplace\t40\t-74.01\t\t\t",
                "6010\tBroken Coords\t\tplace\t\t\tUSA\t\t",
                "6012\tMill Pond\t\tplace\t40\t-74\tUSA\t1000001\t0",
                "6015\tLakeside Village\t\tplace\t\t\tUSA\t1000004\t0"), lines(tmp.resolve("places.tsv.gz")));
        assertEquals(List.of("movie_id\tplace_id\tcity_id", "101\t30\t", "112\t6006\t", "117\t6009\t"),
                lines(tmp.resolve("movies_settings.tsv.gz")));
        String constraints = Files.readString(tmp.resolve("schema-constraints.sql"), StandardCharsets.UTF_8);
        assertTrue(constraints.contains("FOREIGN KEY (person_id) REFERENCES people (id)"), constraints);
        assertTrue(constraints.contains("FOREIGN KEY (next_movie_id) REFERENCES movies (id)"), constraints);
        for (String fk : List.of("movies_locations_place_id_fkey FOREIGN KEY (place_id) REFERENCES places (id)",
                "movies_settings_place_id_fkey FOREIGN KEY (place_id) REFERENCES places (id)",
                "people_birth_place_id_fkey FOREIGN KEY (birth_place_id) REFERENCES places (id)",
                "companies_hq_place_id_fkey FOREIGN KEY (hq_place_id) REFERENCES places (id)")) {
            assertTrue(constraints.contains(fk), fk);
        }
        assertFalse(constraints.contains("city_id) REFERENCES"), "город — мягкая связь с «Миром», без внешнего ключа");
    }

    @Test
    @DisplayName("Повторяемость: тот же снимок, профиль и seed — тот же выпуск; рейтинг фильма — один во всех профилях")
    @SuppressWarnings("unchecked")
    void sameSnapshotSameRelease(@TempDir Path tmp) throws IOException {
        assertTrue(run(new MovDataset(TinySnapshot.EXCLUSIONS), "medium", tmp.resolve("a")).ok());
        assertTrue(run(new MovDataset(TinySnapshot.EXCLUSIONS), "medium", tmp.resolve("b")).ok());
        assertTrue(run(new MovDataset(TinySnapshot.EXCLUSIONS), "full", tmp.resolve("f")).ok());
        Map<String, Object> a = manifest(tmp.resolve("a")), b = manifest(tmp.resolve("b")), f = manifest(tmp.resolve("f"));
        assertEquals(a, b);
        Map<String, String> sa = new LinkedHashMap<>(), sf = new LinkedHashMap<>();
        for (Map<String, Object> x : (List<Map<String, Object>>) a.get("files")) sa.put((String) x.get("table"), (String) x.get("sha256_tsv"));
        for (Map<String, Object> x : (List<Map<String, Object>>) f.get("files")) sf.put((String) x.get("table"), (String) x.get("sha256_tsv"));
        assertEquals(MovSchema.TABLES, new ArrayList<>(sa.keySet()).subList(0, MovSchema.TABLES.size()));
        assertEquals(sf.get("genres"), sa.get("genres"));
        assertNotEquals(sf.get("movies"), sa.get("movies"));
        List<String> fm = lines(tmp.resolve("f").resolve("movies.tsv.gz"));
        for (String line : lines(tmp.resolve("a").resolve("movies.tsv.gz"))) assertTrue(fm.contains(line), line);
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) a.get("inputs");
        Map<String, Object> in = inputs.getFirst();
        assertEquals(MovDataset.SNAPSHOT + ".zip", in.get("name"), "имя — из fetch.json, не из имени файла");
        assertEquals(2, inputs.size());
        assertEquals("cities.tsv.gz", inputs.get(1).get("name"));
        assertEquals("wld-data-v1", inputs.get(1).get("release"));
        assertEquals(5L, inputs.get(1).get("rows"));
        assertEquals(Map.of("geonames", 2L, "nearest", 3L, "none", 4L, "country", 1L), f.get("places_city"));
        assertEquals(Map.of("excluded_country", 1L, "in_excluded_country", 1L, "listed", 1L), f.get("places_excluded"));
        Map<String, Object> sel = (Map<String, Object>) a.get("selection");
        assertEquals(18L, sel.get("movies_full"));
        assertEquals(2L, sel.get("movies"));
        Map<String, Object> g = (Map<String, Object>) a.get("actors_gender");
        assertEquals(6L, g.get("p21"));
        assertEquals(3L, g.get("given_name"));
        assertEquals(2L, g.get("seeded"));
        assertEquals("0.4444", g.get("seeded_female_share"));
        assertEquals(1L, g.get("not_taken_other_p21"));
        Map<String, Object> pg = (Map<String, Object>) a.get("people_gender");
        assertEquals(Map.of("p21", 7L, "given_name", 4L, "seeded", 3L, "null_other_p21", 1L), pg);
        Map<String, Object> y = (Map<String, Object>) f.get("movies_year");
        assertEquals(List.of("p577", "p1191", "p571", "p2754"), new ArrayList<>(y.keySet()));
        assertEquals(Map.of("p577", 14L, "p1191", 2L, "p571", 1L, "p2754", 1L), y);
        Map<String, Object> t = (Map<String, Object>) f.get("movies_title");
        assertEquals(List.of("label_en", "label_mul", "p1476", "label_latin", "label_ru", "label_any"), new ArrayList<>(t.keySet()));
        assertEquals(Map.of("label_en", 13L, "label_mul", 1L, "p1476", 1L, "label_latin", 1L, "label_ru", 1L, "label_any", 1L), t);
        assertEquals(Map.of("label_en", 2L, "label_mul", 0L, "p1476", 0L, "label_latin", 0L, "label_ru", 0L, "label_any", 0L),
                a.get("movies_title"));
    }

    @Test
    @DisplayName("Другой seed — другой рейтинг, а фильмы, люди и связи — те же")
    @SuppressWarnings("unchecked")
    void otherSeed(@TempDir Path tmp) throws IOException {
        MovDataset d = new MovDataset(TinySnapshot.EXCLUSIONS);
        Map<String, String> opt = new LinkedHashMap<>();
        opt.put("profile", "full");
        opt.put("source", TinySnapshot.path().toString());
        opt.put("cities", TinySnapshot.citiesPath().toString());
        opt.put("out", tmp.resolve("x").toString());
        assertTrue(Cli.run(d, opt).ok());
        opt.put("out", tmp.resolve("y").toString());
        opt.put("seed", "7");
        assertTrue(Cli.run(d, opt).ok());
        Map<String, String> x = new LinkedHashMap<>(), y = new LinkedHashMap<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) manifest(tmp.resolve("x")).get("files")) x.put((String) m.get("table"), (String) m.get("sha256_tsv"));
        for (Map<String, Object> m : (List<Map<String, Object>>) manifest(tmp.resolve("y")).get("files")) y.put((String) m.get("table"), (String) m.get("sha256_tsv"));
        assertNotEquals(x.get("movies"), y.get("movies"));
        for (String t : List.of("genres", "directors", "roles", "movies_directors", "movies_genres", "directors_genres",
                "movies_countries", "movies_languages", "people_countries", "movies_crew", "companies", "movies_companies",
                "series", "movies_series", "movies_sequels", "awards", "movies_awards", "movies_releases", "movies_titles",
                "movies_locations", "places", "movies_settings")) {
            assertEquals(x.get(t), y.get(t), t);
        }
    }

    @Test
    @DisplayName("Тексты выпуска: LICENSE — CC0 1.0 и снимок; SOURCE.md — профиль, рейтинг синтетический, пол по путям")
    void texts(@TempDir Path tmp) throws IOException {
        assertTrue(run(new MovDataset(TinySnapshot.EXCLUSIONS), "small", tmp).ok());
        String license = Files.readString(tmp.resolve("LICENSE"), StandardCharsets.UTF_8);
        for (String s : List.of("Wikidata", "CC0 1.0", "publicdomain/zero/1.0", MovDataset.SNAPSHOT, "GeoNames", "CC BY 4.0",
                "licenses/by/4.0")) {
            assertTrue(license.contains(s), s);
        }
        String md = Files.readString(tmp.resolve("SOURCE.md"), StandardCharsets.UTF_8);
        assertTrue(md.contains("## Профиль `small`"), md);
        assertTrue(md.contains("1 % самых известных (1 из 18"), md);
        assertTrue(md.contains("синтетический, не оценка качества фильма"), md);
        assertTrue(md.contains("по P21 (мужской — `M`, женский — `F`) — 6;"), md);
        assertTrue(md.contains("по первому имени P735 (мужское — `M`, женское — `F`) — 2;"), md);
        assertTrue(md.contains("(0.4444) — 2."), md);
        assertTrue(md.contains("| `roles.tsv.gz` | 10 |"), md);
        assertTrue(md.contains("Фильмов профиля по пути года: P577 — 1, P1191 — 0, P571 — 0, P2754 — 0."), md);
        assertTrue(md.contains("Фильмов профиля по пути названия: `en` — 1, `mul` — 0, P1476 — 0, запасной язык — 0, `ru` — 0, "
                + "другой язык — 0."), md);
        assertTrue(md.contains("Людей по пути\n   пола: P21 — 7, имя — 3, от seed — 3, пусто — 1."), md);
        assertTrue(md.contains("| `people.tsv.gz` | 14 |"), md);
        assertTrue(md.contains("| `movies_locations.tsv.gz` | 4 |"), md);
        assertTrue(md.contains("| `places.tsv.gz` | 6 |"), md);
        assertTrue(md.contains("| `movies_settings.tsv.gz` | 1 |"), md);
        assertTrue(md.contains("Мест профиля по пути города: по GeoNames ID — 2, ближайший — 2, нет — 1, страна — 1;\n"
                + "    мест, на которые ссылается профиль, нет по спискам исключений: страна — 0, место в такой стране — 1, из "
                + "списка мест — 1."), md);
        assertTrue(md.contains("Города — `cities.tsv.gz` выпуска `wld-data-v1`"), md);
        assertTrue(md.contains("| GeoNames — "), md);
        assertTrue(md.contains("`schema-studios.svg` — Студии и награды"), md);
        for (String code : Exclusions.COUNTRIES3) assertFalse(md.contains(code), code);
    }

    @Test
    @DisplayName("Входы: имя снимка в fetch.json не то — предупреждение, со strict — ошибка; sha256 в паспорте — 64 знака hex")
    void strictInputs(@TempDir Path tmp) throws IOException {
        Path other = TinySnapshot.with(tmp, Map.of("fetch.json",
                TinySnapshot.utf8("{\"snapshot\":\"mov-source-20990101\",\"fetched_at\":\"2099-01-01T00:00:00Z\"}")));
        MovDataset d = new MovDataset(TinySnapshot.EXCLUSIONS);
        Map<String, String> opt = Map.of("profile", "small", "out", tmp.resolve("o").toString(), "source", other.toString(),
                "cities", TinySnapshot.citiesPath().toString());
        assertThrows(IllegalStateException.class, () -> Cli.run(d, Cli.config(d, opt), true));
        assertTrue(Cli.run(d, opt).ok());
        assertTrue(MovDataset.SOURCE.sha256().matches("[0-9a-f]{64}"), "sha256 снимка — 64 знака hex (заглушка — нули)");
        assertThrows(IllegalArgumentException.class, () -> Cli.run(d, Cli.config(d, Map.of("profile", "small", "to", "2020-01-01",
                "out", tmp.resolve("t").toString(), "source", other.toString(), "cities", TinySnapshot.citiesPath().toString())), false),
                "--to не поддерживается");
    }
}
