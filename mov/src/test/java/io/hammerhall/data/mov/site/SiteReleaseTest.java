package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.io.Json;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;
import io.hammerhall.data.mov.MovSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Синтетика целиком на выдуманных фильмах ({@link StubInput}, профиль {@code small}): самопроверка проходит, заложенное
 * на месте, два прогона — побайтно один выпуск, продление окна не меняет историю (как
 * {@code AucReleaseTest.extendingThePeriodKeepsHistory}).
 */
class SiteReleaseTest {
    static Path root, a, b, shortRun;
    static Release.Result ra, rb, rs;
    static SiteOnlyDataset da;

    @BeforeAll
    static void build() throws IOException {
        root = Files.createTempDirectory("mov-site-");
        SiteInput in = StubInput.build(7, true);
        da = new SiteOnlyDataset(in);
        ra = SiteOnlyDataset.run(da, "small", root.resolve("a"), null);
        rb = SiteOnlyDataset.run(new SiteOnlyDataset(in), "small", root.resolve("b"), null);
        rs = SiteOnlyDataset.run(new SiteOnlyDataset(in), "small", root.resolve("short"), "2027-03-31");
        a = root.resolve("a");
        b = root.resolve("b");
        shortRun = root.resolve("short");
    }

    @AfterAll
    static void clean() throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    static String failures(Release.Result r) {
        StringBuilder sb = new StringBuilder();
        for (CheckReport.Result x : r.report().results()) if (!x.ok()) sb.append(x.name()).append(" — ").append(x.detail()).append('\n');
        return sb.toString();
    }

    static List<String> lines(Path dir, String table) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = open(dir, table)) {
            for (String s; (s = r.readLine()) != null; ) out.add(s);
        }
        return out;
    }

    static BufferedReader open(Path dir, String table) throws IOException {
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(dir.resolve(table + ".tsv.gz"))),
                StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Самопроверка проходит; все 19 таблиц записаны; заложенные отклонения §6 — на месте")
    void selfCheckPasses() throws IOException {
        assertTrue(ra.ok(), "самопроверка:\n" + failures(ra));
        assertTrue(rs.ok(), "самопроверка короткого:\n" + failures(rs));
        for (String t : SiteSchema.TABLES) assertTrue(Files.isRegularFile(a.resolve(t + ".tsv.gz")), t);
        Map<String, Long> p = ra.planted();
        for (String code : List.of("double_charge", "rating_without_view", "long_view")) {
            assertTrue(p.getOrDefault(code, 0L) > 0, "заложено " + code + ": " + p);
        }
        long checks = ra.report().results().stream().filter(x -> x.name().startsWith("site:")).count();
        assertTrue(checks >= 40, "проверок site: " + checks);
        assertTrue(lines(a, "views").size() > 5_000, "просмотров мало");
        assertTrue(lines(a, "reviews").size() > 50, "рецензий мало");
    }

    @Test
    @DisplayName("Узоры на выпуске: средняя оценка растёт с рейтингом фильма; серии смотрят по порядку")
    void ratingsFollowRankAndSeriesInOrder() throws IOException {
        Map<Integer, SiteInput.Movie> byId = new HashMap<>();
        for (SiteInput.Movie m : da.input.movies()) byId.put(m.id(), m);
        double hi = 0, lo = 0;
        int nh = 0, nl = 0;
        for (String line : lines(a, "ratings").subList(1, lines(a, "ratings").size())) {
            String[] f = line.split("\t", -1);
            double rank = byId.get(Integer.parseInt(f[1])).rank();
            if (rank >= 9.7) {
                hi += Integer.parseInt(f[2]);
                nh++;
            } else if (rank <= 9.0) {
                lo += Integer.parseInt(f[2]);
                nl++;
            }
        }
        assertTrue(nh > 100 && nl > 100 && hi / nh > lo / nl + 0.3, hi / nh + " / " + lo / nl);
        // серии: первый просмотр следующего фильма серии — после первого просмотра предыдущего
        Map<String, Long> first = new HashMap<>();
        for (String line : lines(a, "views").subList(1, lines(a, "views").size())) {
            String[] f = line.split("\t", -1);
            first.putIfAbsent(f[1] + "/" + f[3], Long.parseLong(f[4]));
        }
        Map<String, Integer> prevOf = new HashMap<>();
        for (SiteInput.Movie m : da.input.movies()) {
            if (m.seriesId() == null || m.seriesNo() == 1) continue;
            for (SiteInput.Movie p : da.input.movies()) {
                if (m.seriesId().equals(p.seriesId()) && p.seriesNo() == m.seriesNo() - 1) prevOf.put(String.valueOf(m.id()), p.id());
            }
        }
        int pairs = 0, inOrder = 0;
        for (Map.Entry<String, Long> e : first.entrySet()) {
            String[] k = e.getKey().split("/");
            Integer prev = prevOf.get(k[1]);
            if (prev == null) continue;
            Long pv = first.get(k[0] + "/" + prev);
            if (pv == null) continue;
            pairs++;
            if (pv < e.getValue()) inOrder++;
        }
        assertTrue(pairs >= 5, "пар " + pairs);
        assertTrue(inOrder >= 0.7 * pairs, inOrder + " из " + pairs);
    }

    @Test
    @DisplayName("Два прогона small — побайтно один выпуск")
    void twoRunsAreByteIdentical() throws IOException {
        for (String t : SiteSchema.TABLES) {
            assertArrayEquals(Files.readAllBytes(a.resolve(t + ".tsv.gz")), Files.readAllBytes(b.resolve(t + ".tsv.gz")), t);
        }
        assertEquals(Files.readString(a.resolve("manifest.json")), Files.readString(b.resolve("manifest.json")));
    }

    /** Поля, которые вправе смениться при продлении: состояние «в пути» на срез. */
    static final Map<String, Set<String>> MAY_CHANGE = Map.ofEntries(
            Map.entry("users", Set.of("deleted_at")),
            Map.entry("devices", Set.of("last_seen_at")),
            Map.entry("subscriptions", Set.of("trial_ends_at", "ends_at", "status", "auto_renew", "cancelled_at", "cancel_reason")),
            Map.entry("payments", Set.of("status", "refunded_at")),
            Map.entry("catalog", Set.of("removed_at")),
            Map.entry("views", Set.of("watched_min", "completed")),
            Map.entry("ratings", Set.of("rating", "rated_at", "changes")),
            Map.entry("reviews", Set.of("status", "edited_at")),
            Map.entry("watchlist", Set.of("removed_at", "removed_reason")),
            Map.entry("follows", Set.of("unfollowed_at")));
    /** Таблицы с id по времени: короткий — начало длинного по id. */
    static final List<String> BY_TIME = List.of("users", "devices", "subscriptions", "payments", "purchases", "views", "reviews",
            "collections");

    @Test
    @DisplayName("Продление: small с --to 2027-03-31 — каждая строка есть в длинном по ключу, поля те же (кроме «в пути»)")
    void extendingThePeriodKeepsHistory() throws IOException {
        Schema schema = MovSchema.build();
        Map<String, String> problems = new LinkedHashMap<>();
        Map<String, Integer> inTransit = new TreeMap<>();
        for (String name : SiteSchema.TABLES) {
            Table t = schema.table(name);
            List<String> sl = lines(shortRun, name), ll = lines(a, name);
            assertEquals(sl.getFirst(), ll.getFirst(), name + ": заголовок");
            String[] header = sl.getFirst().split("\t", -1);
            int[] key = new int[t.primaryKey().size()];
            for (int i = 0; i < key.length; i++) key[i] = t.indexOf(t.primaryKey().get(i));
            Map<String, String> longRows = new HashMap<>();
            for (String line : ll.subList(1, ll.size())) longRows.put(keyOf(line, key), line);
            Set<String> may = MAY_CHANGE.getOrDefault(name, Set.of());
            int changed = 0;
            for (String line : sl.subList(1, sl.size())) {
                String other = longRows.get(keyOf(line, key));
                if (other == null) {
                    problems.putIfAbsent(name, "нет в длинном: " + line);
                    continue;
                }
                if (other.equals(line)) continue;
                changed++;
                String[] f = line.split("\t", -1), g = other.split("\t", -1);
                for (int c = 0; c < f.length; c++) {
                    if (f[c].equals(g[c]) || may.contains(header[c]) || f[c].isEmpty()) continue;
                    problems.putIfAbsent(name, header[c] + ": «" + f[c] + "» → «" + g[c] + "» в " + line);
                }
            }
            if (changed > 0) inTransit.put(name, changed);
            if (BY_TIME.contains(name)) {
                assertTrue(ll.size() >= sl.size(), name);
                for (int i = 1; i < sl.size(); i++) {
                    String s0 = sl.get(i).substring(0, sl.get(i).indexOf('\t')), l0 = ll.get(i).substring(0, ll.get(i).indexOf('\t'));
                    if (!s0.equals(l0)) {
                        problems.putIfAbsent(name, "id строки " + i + ": " + s0 + " ≠ " + l0);
                        break;
                    }
                }
            }
        }
        System.out.println("продление: в пути на коротком срезе — " + inTransit);
        assertEquals(Map.of(), problems, "история короткого прогона изменилась при продлении");
        assertTrue(lines(shortRun, "views").size() < lines(a, "views").size(), "длинный — длиннее");
    }

    private static String keyOf(String line, int[] key) {
        String[] f = line.split("\t", -1);
        StringBuilder sb = new StringBuilder();
        for (int k : key) sb.append(f[k]).append('\u0001');
        return sb.toString();
    }

    @Test
    @DisplayName("Срез: короткий прогон — без событий после 01.04.2027; манифест — синтетика, срез и тексты")
    @SuppressWarnings("unchecked")
    void cutoffAndManifest() throws IOException {
        long cut = SiteRules.mskMidnight(java.time.LocalDate.of(2027, 4, 1)) + 3 * SiteRules.HOUR;
        for (String line : lines(shortRun, "views").subList(1, lines(shortRun, "views").size())) {
            String[] f = line.split("\t", -1);
            assertTrue(Long.parseLong(f[4]) + Long.parseLong(f[5]) * 60 <= cut, line);
        }
        Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(shortRun.resolve("manifest.json")));
        assertEquals("2027-03-31", ((Map<String, Object>) m.get("period")).get("to"));
        assertEquals(List.of("cinema", "site"), m.get("modules"));
        assertEquals(cut, SiteModule.cutoff(rs.context()));
    }
}
