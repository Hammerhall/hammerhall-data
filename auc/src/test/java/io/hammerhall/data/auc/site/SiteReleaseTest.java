package io.hammerhall.data.auc.site;

import io.hammerhall.data.auc.AucDataset;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.Release;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Продление таблиц сайта на настоящем наборе ({@link AucDataset}: дом, люди, вещи, торги, клиенты, деньги, сайт):
 * {@code small} с {@code --to 2027-03-31} против полного. Каждая строка короткого — в длинном по ключу с теми же полями,
 * кроме «в пути» ({@code sessions.ended_at}, {@code watchlist.removed_at}, у уведомлений — отправка и статус); события
 * визитов только дописываются; {@code id} визитов и уведомлений короткого — начало длинного (по моменту).
 */
class SiteReleaseTest {
    static final String SHORT_TO = "2027-03-31";
    static Path tmp, full, shortRun;
    static Release.Result rf, rs;

    @BeforeAll
    static void build() throws IOException {
        tmp = Files.createTempDirectory("auc-site-release-");
        full = tmp.resolve("full");
        shortRun = tmp.resolve("short");
        rf = Cli.run(new AucDataset(), Map.of("profile", "small", "out", full.toString(), "modules", SiteModule.NAME));
        rs = Cli.run(new AucDataset(), Map.of("profile", "small", "out", shortRun.toString(), "modules", SiteModule.NAME,
                "to", SHORT_TO));
    }

    @AfterAll
    static void clean() throws IOException {
        if (tmp == null) return;
        try (Stream<Path> s = Files.walk(tmp)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /** Самопроверка сайта и ключи и ссылки его таблиц (коридоры других модулей — их тесты). */
    @Test
    void bothRunsPassSiteSelfCheck() {
        for (Release.Result r : List.of(rf, rs)) {
            List<String> bad = new ArrayList<>();
            for (CheckReport.Result x : r.report().results()) {
                String n = x.name();
                boolean mine = n.startsWith("сайт") || n.startsWith("уведомления") || n.startsWith("коридор «воронка»")
                        || n.startsWith("коридор «местное время»");
                for (String t : SiteTables.TABLES) mine |= n.startsWith("ключ " + t) || n.startsWith("ссылка " + t + "(");
                if (mine && !x.ok()) bad.add(n + " — " + x.detail());
            }
            assertEquals(List.of(), bad);
        }
    }

    @Test
    void watchlistKeepsHistory() throws IOException {
        assertEquals(List.of(), diff("watchlist", 2, Set.of(3)));
    }

    @Test
    void sessionsKeepHistory() throws IOException {
        assertEquals(List.of(), diff("sessions", 1, Set.of(7)));
    }

    @Test
    void sessionEventsOnlyGrow() throws IOException {
        assertEquals(List.of(), diff("session_events", 2, Set.of()));
    }

    @Test
    void notificationsKeepHistory() throws IOException {
        assertEquals(List.of(), diff("notifications", 1, Set.of(9, 10, 11, 12)));
    }

    /** Расхождения строк короткого прогона с длинным (по первым {@code keyCols} полям); пустое поле может заполниться. */
    private static List<String> diff(String table, int keyCols, Set<Integer> mayChange) throws IOException {
        Map<String, String[]> longRows = new HashMap<>();
        rows(full, table, f -> longRows.put(String.join("\t", Arrays.copyOf(f, keyCols)), f));
        List<String> diffs = new ArrayList<>();
        int[] n = {0};
        rows(shortRun, table, f -> {
            n[0]++;
            String[] g = longRows.get(String.join("\t", Arrays.copyOf(f, keyCols)));
            if (g == null) {
                if (diffs.size() < 5) diffs.add("нет в длинном: " + String.join("\t", f));
                return;
            }
            for (int k = 0; k < f.length; k++) {
                if (f[k].equals(g[k]) || mayChange.contains(k) || f[k].isEmpty()) continue;
                if (diffs.size() < 5) diffs.add(k + ": «" + f[k] + "» → «" + g[k] + "» в " + String.join("\t", f));
            }
        });
        assertTrue(n[0] > 100, table + ": строк короткого " + n[0]);
        return diffs;
    }

    private static void rows(Path dir, String table, Consumer<String[]> row) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(dir.resolve(table + ".tsv.gz"))), StandardCharsets.UTF_8))) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) row.accept(line.split("\t", -1));
        }
    }
}
