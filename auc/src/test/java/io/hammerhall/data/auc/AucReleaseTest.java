package io.hammerhall.data.auc;

import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.io.Json;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты набора: два прогона — один манифест; продление периода — история короткого прогона есть в
 * длинном, меняются только поля «в пути»; {@code items} и {@code makers} не меняются; постоянные таблицы одинаковы в
 * профилях.
 */
class AucReleaseTest {
    static final AucDataset AUC = new AucDataset();
    static final String SHORT_TO = "2027-03-31";
    static Path tmp, b, shortRun;
    /** Итоги прогонов без контекста (контекст держит весь мир — не копим его в куче тестов). */
    static boolean okA, okB, okShort;
    static String reportShort;
    static Path a;

    /**
     * Поля, которые вправе смениться при продлении (строки отчётов комитентам — на срез).
     * Пустое поле может заполниться в любой таблице; строки журналов и отчётов — дописываться.
     */
    static final Map<String, Set<String>> MAY_CHANGE = new TreeMap<>();

    static {
        MAY_CHANGE.put("sales", Set.of("status", "ends_at"));
        MAY_CHANGE.put("sale_sessions", Set.of("ended_at"));
        MAY_CHANGE.put("lots", Set.of("result", "hammer_price", "winning_bid_id", "buyer_registration_id",
                "underbidder_registration_id", "after_sale_price", "after_sale_at", "withdrawn_at", "withdrawn_by",
                "closed_at", "closed_ms", "extensions", "bid_count"));
        MAY_CHANGE.put("bid_orders", Set.of("status", "ended_at"));
        MAY_CHANGE.put("registrations", Set.of("status", "decided_at", "decided_by_id"));
        MAY_CHANGE.put("consignment_items", Set.of("ended_at", "outcome"));
        MAY_CHANGE.put("invoices", Set.of("status", "late_fee", "paid_total", "paid_at", "cancelled_at", "cancel_reason"));
        MAY_CHANGE.put("settlements", Set.of("paid_total", "status", "hammer_total", "commission_total", "fees_total",
                "vat_total", "net_total"));
        MAY_CHANGE.put("settlement_lines", Set.of("payable_at", "paid_at", "outcome", "price", "commission",
                "insurance_fee", "catalogue_fee", "withdrawal_fee", "vat", "net"));
        MAY_CHANGE.put("clients", Set.of("blocked_at", "block_reason"));
        MAY_CHANGE.put("watchlist", Set.of("removed_at"));
        MAY_CHANGE.put("notifications", Set.of("sent_at", "delivered_at", "attempts", "status"));
        MAY_CHANGE.put("sessions", Set.of("ended_at"));
    }

    /** Таблицы, у которых короткий прогон — начало длинного строка в строку (порядок — по времени). */
    static final Set<String> PREFIX = Set.of("bids", "payments", "items");

    @BeforeAll
    static void build() throws IOException {
        a = DatasetContract.small(AUC).dir();
        okA = DatasetContract.small(AUC).result().ok();
        tmp = Files.createTempDirectory("auc-release-test-");
        b = tmp.resolve("b");
        shortRun = tmp.resolve("short");
        okB = Cli.run(AUC, Map.of("profile", "small", "out", b.toString())).ok();
        Release.Result rs = Cli.run(AUC, Map.of("profile", "small", "out", shortRun.toString(), "to", SHORT_TO));
        okShort = rs.ok();
        reportShort = rs.report().render();
    }

    @AfterAll
    static void clean() throws IOException {
        if (tmp == null) return;
        try (var s = Files.walk(tmp)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    @Test
    void selfCheckPasses() {
        assertTrue(okA && okB && okShort, reportShort);
    }

    @Test
    void twoRunsGiveOneManifest() throws IOException {
        assertEquals(Files.readString(a.resolve("manifest.json")), Files.readString(b.resolve("manifest.json")));
    }

    @Test
    void schemaHasAll31Tables() {
        Schema s = AUC.schema();
        assertEquals(AucSchema.TABLES, s.tables().size());
        assertEquals(List.of(), s.validate());
        List<String> mine = new ArrayList<>();
        for (var m : AUC.modules()) mine.addAll(m.tables());
        for (String t : mine) s.table(t);
    }

    // ---------------------------------------------------------------- продление

    static List<String> read(Path dir, String table) throws IOException {
        Path p = dir.resolve(table + ".tsv.gz");
        if (!Files.exists(p)) return null;
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(p)),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        return out;
    }

    /** Строки таблицы по одной (без заголовка); {@code null} — таблицы нет в выпуске. */
    static BufferedReader open(Path dir, String table) throws IOException {
        Path p = dir.resolve(table + ".tsv.gz");
        if (!Files.exists(p)) return null;
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(p), 1 << 16),
                StandardCharsets.UTF_8));
    }

    @Test
    void extendingThePeriodKeepsHistory() throws IOException {
        Schema schema = AUC.schema();
        Map<String, String> problems = new LinkedHashMap<>();
        Map<String, Integer> inTransit = new TreeMap<>();
        for (Table t : schema.tables()) {
            try (BufferedReader s = open(shortRun, t.name()); BufferedReader l = open(a, t.name())) {
                if (s == null || l == null) continue;
                String hs = s.readLine(), hl = l.readLine();
                assertEquals(hs, hl, t.name() + ": заголовок");
                String[] header = hs.split("	", -1);
                if (PREFIX.contains(t.name())) {
                    String x;
                    int i = 0;
                    while ((x = s.readLine()) != null) {
                        i++;
                        String y = l.readLine();
                        if (!x.equals(y)) {
                            problems.put(t.name(), "строка " + i + " короткого — не та же в длинном: " + x);
                            break;
                        }
                    }
                    continue;
                }
                int[] key = new int[t.primaryKey().size()];
                for (int i = 0; i < key.length; i++) key[i] = t.indexOf(t.primaryKey().get(i));
                // длинный — по ключу строкой целиком (разбор — только у несовпавших)
                Map<String, String> longRows = new HashMap<>();
                String line;
                while ((line = l.readLine()) != null) longRows.put(keyOf(line, key), line);
                Set<String> may = MAY_CHANGE.getOrDefault(t.name(), Set.of());
                int changed = 0;
                while ((line = s.readLine()) != null) {
                    String other = longRows.get(keyOf(line, key));
                    if (other == null) {
                        problems.putIfAbsent(t.name(), "нет в длинном: " + line);
                        continue;
                    }
                    if (other.equals(line)) continue;
                    changed++;
                    String[] f = line.split("	", -1), g = other.split("	", -1);
                    for (int c = 0; c < f.length; c++) {
                        if (f[c].equals(g[c]) || may.contains(header[c]) || f[c].isEmpty()) continue;
                        problems.putIfAbsent(t.name(), header[c] + ": «" + f[c] + "» → «" + g[c] + "» в " + line);
                    }
                }
                if (changed > 0) inTransit.put(t.name(), changed);
            }
        }
        System.out.println("продление: в пути на коротком срезе — " + inTransit);
        assertEquals(Map.of(), problems, "история короткого прогона изменилась при продлении");
    }

    /** Ключ строки — поля первичного ключа (по номерам), без разбора всей строки. */
    private static String keyOf(String line, int[] key) {
        StringBuilder sb = new StringBuilder(24);
        int col = 0, start = 0;
        for (int i = 0; i <= line.length(); i++) {
            if (i == line.length() || line.charAt(i) == '	') {
                for (int k : key) {
                    if (k == col) {
                        sb.append(line, start, i).append('');
                        break;
                    }
                }
                col++;
                start = i + 1;
            }
        }
        return sb.toString();
    }

    @Test
    void itemsAndMakersDoNotChangeWithTo() throws IOException {
        assertEquals(read(a, "makers"), read(shortRun, "makers"), "мастера — постоянный справочник");
        List<String> s = read(shortRun, "items"), l = read(a, "items");
        assertTrue(s.size() > 1000 && l.size() > s.size(), s.size() + " / " + l.size());
        assertEquals(s, l.subList(0, s.size()), "вещи короткого прогона — начало длинного строка в строку");
    }

    // ---------------------------------------------------------------- профили

    @Test
    void constantTablesAreTheSameInProfiles() throws IOException {
        Path m = tmp.resolve("medium-catalog");
        Release.Result r = Cli.run(AUC, Map.of("profile", "medium", "out", m.toString(), "modules", "catalog"));
        assertTrue(r.ok(), r.report().render());
        Map<String, Object> small = sha(a), medium = sha(m);
        for (String t : List.of("departments", "categories", "cities", "offices", "staff", "increment_bands",
                "fee_schedules", "fee_tiers", "makers")) {
            assertEquals(small.get(t), medium.get(t), t + ": одинаково во всех профилях");
        }
        // живой поток — × масштаб: вещей в medium ≈ в 10 раз больше
        long si = rows(a, "items"), mi = rows(m, "items");
        assertTrue(mi > 8 * si && mi < 12 * si, si + " → " + mi);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sha(Path dir) throws IOException {
        Map<String, Object> man = (Map<String, Object>) Json.parse(Files.readString(dir.resolve("manifest.json")));
        Map<String, Object> out = new TreeMap<>();
        for (Object o : (List<Object>) man.get("files")) {
            Map<String, Object> f = (Map<String, Object>) o;
            out.put((String) f.get("table"), f.get("sha256_tsv"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static long rows(Path dir, String table) throws IOException {
        Map<String, Object> man = (Map<String, Object>) Json.parse(Files.readString(dir.resolve("manifest.json")));
        for (Object o : (List<Object>) man.get("files")) {
            Map<String, Object> f = (Map<String, Object>) o;
            if (table.equals(f.get("table"))) return ((Number) f.get("rows")).longValue();
        }
        return -1;
    }
}
