package io.hammerhall.data.auc.auction;

import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.gen.Release;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Прогоны {@code small} тестовым драйвером подготовки торгов — по одному на JVM, и чтение TSV выпуска. */
final class PrepRuns {
    private PrepRuns() {
    }

    record Run(Path dir, Release.Result result, io.hammerhall.data.auc.auction.intake.AuctionPrep prep,
               Map<Integer, Long> lotLow, Map<Integer, Integer> lotSale, Map<Long, Long> regAtDemand, List<String> early) {
    }

    private static final Map<String, Run> RUNS = new HashMap<>();

    /** {@code small}; {@code to} — конец периода ({@code null} — как у набора). */
    static synchronized Run small(String to) throws IOException {
        PrepDriver.PrepDataset d = new PrepDriver.PrepDataset();
        DatasetContract.assumeInputs(d);
        String k = to == null ? "" : to;
        Run r = RUNS.get(k);
        if (r == null) {
            Path dir = Files.createTempDirectory("auc-prep-small-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(dir)));
            Map<String, String> opt = new HashMap<>(Map.of("profile", "small", "out", dir.toString()));
            if (to != null) opt.put("to", to);
            Release.Result res = Cli.run(d, opt);
            r = new Run(dir, res, PrepDriver.last, new HashMap<>(PrepDriver.LOT_LOW), new HashMap<>(PrepDriver.LOT_SALE),
                    new HashMap<>(PrepDriver.REG_AT_DEMAND), new ArrayList<>(PrepDriver.EARLY));
            RUNS.put(k, r);
        }
        return r;
    }

    static void delete(Path dir) {
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            // временная папка
        }
    }

    /** Таблица выпуска: заголовок → номер поля, строки. */
    record Table(Map<String, Integer> col, List<String[]> rows) {
        String get(String[] row, String name) {
            Integer i = col.get(name);
            if (i == null) throw new IllegalArgumentException("нет поля " + name);
            return row[i];
        }

        long num(String[] row, String name) {
            String v = get(row, name);
            return v.isEmpty() ? 0 : v.contains(".") ? Math.round(Double.parseDouble(v) * 100) : Long.parseLong(v);
        }
    }

    static Table read(Path dir, String table) throws IOException {
        Path p = dir.resolve(table + ".tsv.gz");
        List<String[]> rows = new ArrayList<>();
        Map<String, Integer> col = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(p)),
                StandardCharsets.UTF_8))) {
            String[] h = in.readLine().split("\t", -1);
            for (int i = 0; i < h.length; i++) col.put(h[i], i);
            for (String line = in.readLine(); line != null; line = in.readLine()) rows.add(line.split("\t", -1));
        }
        return new Table(col, rows);
    }
}
