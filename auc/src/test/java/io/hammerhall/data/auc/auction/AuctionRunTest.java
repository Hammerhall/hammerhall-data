package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.AucDataset;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.io.GzipTsvFile;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модуль торгов в сборке {@code small}: самопроверка проходит; один поток и несколько дают один выпуск (торги недели
 * считаются параллельно); при более коротком периоде ({@code --to}) ставки — начало длинного прогона строка в строку,
 * лоты и журнал лота короткого — в длинном (кроме полей «в пути»).
 */
class AuctionRunTest {
    static final AucDataset AUC = new AucDataset();

    @Test
    void oneThreadGivesTheSameRelease() throws IOException {
        DatasetContract.Small s = DatasetContract.small(AUC);
        assertTrue(s.result().ok(), s.result().report().render());
        Path tmp = Files.createTempDirectory("auc-threads-");
        String old = System.getProperty("auc.threads");
        System.setProperty("auc.threads", "1");
        Release.Result one;
        try {
            one = Cli.run(AUC, Map.of("profile", "small", "out", tmp.toString()));
        } finally {
            if (old == null) System.clearProperty("auc.threads");
            else System.setProperty("auc.threads", old);
        }
        assertTrue(one.ok(), one.report().render());
        Map<String, String> a = hashes(s.result()), b = hashes(one);
        for (String t : List.of("lots", "bids", "lot_events", "item_status_log", "bid_orders", "registrations")) {
            assertEquals(a.get(t), b.get(t), "таблица " + t + ": один поток ≠ несколько");
        }
        assertEquals(a, b);
    }

    @Test
    void shorterPeriodIsAPrefix() throws IOException {
        DatasetContract.Small s = DatasetContract.small(AUC);
        Path tmp = Files.createTempDirectory("auc-short-");
        Release.Result shortRun = Cli.run(AUC, Map.of("profile", "small", "out", tmp.toString(), "to", "2027-03-31"));
        assertTrue(shortRun.ok(), shortRun.report().render());
        List<String> sb = lines(tmp.resolve("bids.tsv.gz")), lb = lines(s.dir().resolve("bids.tsv.gz"));
        assertTrue(sb.size() > 100 && sb.size() < lb.size(), sb.size() + " / " + lb.size());
        assertEquals(sb, lb.subList(0, sb.size()), "ставки короткого прогона — начало длинного");
        // лоты: всё, кроме полей «в пути» — то же; журнал лота — только дописывается
        List<String> sl = lines(tmp.resolve("lots.tsv.gz")), ll = lines(s.dir().resolve("lots.tsv.gz"));
        String[] head = sl.get(0).split("\t", -1);
        List<String> mayChange = List.of("opened_at", "closed_at", "closed_ms", "extensions", "bid_count", "result",
                "hammer_price", "winning_bid_id", "buyer_registration_id", "underbidder_registration_id", "after_sale_price",
                "after_sale_at", "withdrawn_at", "withdrawn_by");
        for (int i = 1; i < sl.size(); i++) {
            String[] x = sl.get(i).split("\t", -1), y = ll.get(i).split("\t", -1);
            for (int c = 0; c < head.length; c++) {
                if (mayChange.contains(head[c])) continue;
                assertEquals(x[c], y[c], "лот " + x[0] + ", поле " + head[c]);
            }
        }
        Map<String, String> le = byKey(lines(s.dir().resolve("lot_events.tsv.gz")));
        for (Map.Entry<String, String> e : byKey(lines(tmp.resolve("lot_events.tsv.gz"))).entrySet()) {
            assertEquals(e.getValue(), le.get(e.getKey()), "журнал лота " + e.getKey());
        }
    }

    private static Map<String, String> hashes(Release.Result r) {
        Map<String, String> m = new TreeMap<>();
        for (GzipTsvFile.Info f : r.files()) m.put(f.name().replace(".tsv.gz", ""), f.sha256Tsv());
        return m;
    }

    private static Map<String, String> byKey(List<String> rows) {
        Map<String, String> m = new TreeMap<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] x = rows.get(i).split("\t", 3);
            m.put(x[0] + "\t" + x[1], rows.get(i));
        }
        return m;
    }

    static List<String> lines(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(p)),
                StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) out.add(l);
        }
        return out;
    }
}
