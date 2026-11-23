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
        Release.Result r = run(new MovDataset(), "full", tmp);
        assertTrue(r.ok(), r.report().render());
        List<String> genres = lines(tmp.resolve("genres.tsv.gz"));
        assertEquals(25, genres.size());
        assertEquals(List.of("id\tname", "1\tAction", "2\tAdult"), genres.subList(0, 3));
        assertEquals("12\tFilm noir", genres.get(12));
        assertEquals("19\tSci-Fi", genres.get(19));
        List<String> movies = lines(tmp.resolve("movies.tsv.gz"));
        assertEquals("id\ttitle\ttitle_ru\tyear\trelease_date\truntime\trank", movies.getFirst());
        assertTrue(movies.get(1).startsWith("101\tAlpha Story\tАльфа\t1999\t1999-03-31\t90\t"), movies.get(1));
        assertTrue(movies.get(2).startsWith("107\tThe Story\tИстория\t1950\t\t\t"), movies.get(2));
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
    @DisplayName("Повторяемость: тот же снимок, профиль и seed — тот же выпуск; рейтинг фильма — один во всех профилях")
    @SuppressWarnings("unchecked")
    void sameSnapshotSameRelease(@TempDir Path tmp) throws IOException {
        assertTrue(run(new MovDataset(), "medium", tmp.resolve("a")).ok());
        assertTrue(run(new MovDataset(), "medium", tmp.resolve("b")).ok());
        assertTrue(run(new MovDataset(), "full", tmp.resolve("f")).ok());
        Map<String, Object> a = manifest(tmp.resolve("a")), b = manifest(tmp.resolve("b")), f = manifest(tmp.resolve("f"));
        assertEquals(a, b);
        Map<String, String> sa = new LinkedHashMap<>(), sf = new LinkedHashMap<>();
        for (Map<String, Object> x : (List<Map<String, Object>>) a.get("files")) sa.put((String) x.get("table"), (String) x.get("sha256_tsv"));
        for (Map<String, Object> x : (List<Map<String, Object>>) f.get("files")) sf.put((String) x.get("table"), (String) x.get("sha256_tsv"));
        assertEquals(MovSchema.TABLES, new ArrayList<>(sa.keySet()));
        assertEquals(sf.get("genres"), sa.get("genres"));
        assertNotEquals(sf.get("movies"), sa.get("movies"));
        List<String> fm = lines(tmp.resolve("f").resolve("movies.tsv.gz"));
        for (String line : lines(tmp.resolve("a").resolve("movies.tsv.gz"))) assertTrue(fm.contains(line), line);
        Map<String, Object> in = ((List<Map<String, Object>>) a.get("inputs")).getFirst();
        assertEquals(MovDataset.SNAPSHOT + ".zip", in.get("name"), "имя — из fetch.json, не из имени файла");
        Map<String, Object> sel = (Map<String, Object>) a.get("selection");
        assertEquals(12L, sel.get("movies_full"));
        assertEquals(2L, sel.get("movies"));
        Map<String, Object> g = (Map<String, Object>) a.get("actors_gender");
        assertEquals(6L, g.get("p21"));
        assertEquals(3L, g.get("given_name"));
        assertEquals(2L, g.get("seeded"));
        assertEquals("0.4444", g.get("seeded_female_share"));
        assertEquals(1L, g.get("not_taken_other_p21"));
    }

    @Test
    @DisplayName("Другой seed — другой рейтинг, а фильмы, люди и связи — те же")
    @SuppressWarnings("unchecked")
    void otherSeed(@TempDir Path tmp) throws IOException {
        MovDataset d = new MovDataset();
        Map<String, String> opt = new LinkedHashMap<>();
        opt.put("profile", "full");
        opt.put("source", TinySnapshot.path().toString());
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
                "movies_countries", "movies_languages")) {
            assertEquals(x.get(t), y.get(t), t);
        }
    }

    @Test
    @DisplayName("Тексты выпуска: LICENSE — CC0 1.0 и снимок; SOURCE.md — профиль, рейтинг синтетический, пол по путям")
    void texts(@TempDir Path tmp) throws IOException {
        assertTrue(run(new MovDataset(), "small", tmp).ok());
        String license = Files.readString(tmp.resolve("LICENSE"), StandardCharsets.UTF_8);
        for (String s : List.of("Wikidata", "CC0 1.0", "publicdomain/zero/1.0", MovDataset.SNAPSHOT)) assertTrue(license.contains(s), s);
        String md = Files.readString(tmp.resolve("SOURCE.md"), StandardCharsets.UTF_8);
        assertTrue(md.contains("## Профиль `small`"), md);
        assertTrue(md.contains("1 % самых известных (1 из 12"), md);
        assertTrue(md.contains("синтетический, не оценка качества фильма"), md);
        assertTrue(md.contains("по P21 (мужской — `M`, женский — `F`) — 6;"), md);
        assertTrue(md.contains("по первому имени P735 (мужское — `M`, женское — `F`) — 2;"), md);
        assertTrue(md.contains("(0.4444) — 2."), md);
        assertTrue(md.contains("| `roles.tsv.gz` | 10 |"), md);
        for (String code : Exclusions.COUNTRIES3) assertFalse(md.contains(code), code);
    }

    @Test
    @DisplayName("Входы: имя снимка в fetch.json не то — предупреждение, со strict — ошибка; sha256 в паспорте — 64 знака hex")
    void strictInputs(@TempDir Path tmp) throws IOException {
        Path other = TinySnapshot.with(tmp, Map.of("fetch.json",
                TinySnapshot.utf8("{\"snapshot\":\"mov-source-20990101\",\"fetched_at\":\"2099-01-01T00:00:00Z\"}")));
        MovDataset d = new MovDataset();
        Map<String, String> opt = Map.of("profile", "small", "out", tmp.resolve("o").toString(), "source", other.toString());
        assertThrows(IllegalStateException.class, () -> Cli.run(d, Cli.config(d, opt), true));
        assertTrue(Cli.run(d, opt).ok());
        assertTrue(MovDataset.SOURCE.sha256().matches("[0-9a-f]{64}"), "sha256 снимка — 64 знака hex (заглушка — нули)");
        assertThrows(IllegalArgumentException.class, () -> Cli.run(d, Cli.config(d, Map.of("profile", "small", "to", "2020-01-01",
                "out", tmp.resolve("t").toString(), "source", other.toString())), false), "--to не поддерживается");
    }
}
