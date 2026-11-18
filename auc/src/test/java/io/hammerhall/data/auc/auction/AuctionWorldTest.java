package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.AucDataset;
import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.gen.Release;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Мир торгов ({@code AUCTION}) против таблиц выпуска {@code small}: ставки компактно и флаг формы сайта, максимумы с
 * сайта ({@code OrderLog}), план лота ({@code LotPlan}), смоделированные участники ({@code ParticipantLog}) — и что
 * участники лотов не зависят от среза (продление). Максимумы, планы и участники — по выпуску без сайта: сайт, последний
 * генератор, собрав вход, отпускает их ({@code AuctionWorld.generatorsDone}).
 */
class AuctionWorldTest {
    static final AucDataset AUC = new AucDataset();

    /** Выпуск {@code small} до модуля {@code auction} (без сайта) и его папка. */
    private static Release.Result auctionOnly;
    private static Path auctionOnlyDir;

    static synchronized Release.Result auctionOnly() throws IOException {
        if (auctionOnly == null) {
            auctionOnlyDir = Files.createTempDirectory("auc-world-auction-");
            auctionOnly = Cli.run(AUC, Map.of("profile", "small", "out", auctionOnlyDir.toString(), "modules", "auction"));
        }
        return auctionOnly;
    }

    @Test
    void siteReleasesGeneratorParts() throws IOException {
        DatasetContract.Small s = DatasetContract.small(AUC);
        AuctionWorld w = world(s.result());
        assertTrue(w.lotCount() > 1000 && w.bids().size() > 1000, "лоты и ставки — для проверок — остаются");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, w::notices);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, w::invoices);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, w::registrations);
    }

    static AuctionWorld world(Release.Result r) {
        return r.context().get(AucKeys.AUCTION);
    }

    static Map<String, String[]> byId(List<String> rows) {
        Map<String, String[]> m = new HashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] x = rows.get(i).split("\t", -1);
            m.put(x[0], x);
        }
        return m;
    }

    static int col(List<String> rows, String name) {
        return List.of(rows.get(0).split("\t", -1)).indexOf(name);
    }

    @Test
    void bidLogAndSiteFlag() throws IOException {
        DatasetContract.Small s = DatasetContract.small(AUC);
        AuctionWorld w = world(s.result());
        List<String> bids = AuctionRunTest.lines(s.dir().resolve("bids.tsv.gz"));
        AuctionWorld.BidLog log = w.bids();
        assertEquals(bids.size() - 1, log.size());
        int ch = col(bids, "channel"), kind = col(bids, "kind"), reason = col(bids, "reject_reason"), lot = col(bids, "lot_id"),
                at = col(bids, "placed_at"), ms = col(bids, "placed_ms");
        int manual = 0;
        for (int i = 0; i < log.size(); i++) {
            String[] x = bids.get(i + 1).split("\t", -1);
            assertEquals(Long.parseLong(x[0]), log.id(i));
            assertEquals(Integer.parseInt(x[lot]), log.lotId(i));
            assertEquals(Long.parseLong(x[at]) * 1000 + Integer.parseInt(x[ms]), log.atMs(i));
            boolean form = x[ch].equals("online") && x[kind].equals("manual") && !x[reason].equals("off_increment");
            if (log.manualOnline(i)) {
                manual++;
                assertTrue(form, "ставка " + x[0] + " — не форма сайта");
            }
        }
        assertTrue(manual > 1000, "ручных онлайн-ставок формой " + manual);
    }

    @Test
    void ordersFromTheSiteMatchBidOrders() throws IOException {
        AuctionWorld.OrderLog o = world(auctionOnly()).orders();
        List<String> rows = AuctionRunTest.lines(auctionOnlyDir.resolve("bid_orders.tsv.gz"));
        Map<String, String[]> by = byId(rows);
        int kind = col(rows, "kind"), via = col(rows, "via"), lot = col(rows, "lot_id"), at = col(rows, "placed_at"),
                ms = col(rows, "placed_ms");
        long expect = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] x = rows.get(i).split("\t", -1);
            if (x[kind].equals("auto") && (x[via].equals("site") || x[via].equals("app"))) expect++;
        }
        assertEquals(expect, o.size());
        assertTrue(o.size() > 100, "максимумов с сайта " + o.size());
        int prev = 0;
        for (int i = 0; i < o.size(); i++) {
            assertTrue(o.id(i) > prev, "по bid_orders.id");
            prev = o.id(i);
            String[] x = by.get(String.valueOf(o.id(i)));
            assertNotNull(x);
            assertEquals(Integer.parseInt(x[lot]), o.lotId(i));
            assertEquals(Long.parseLong(x[at]) * 1000 + Integer.parseInt(x[ms]), o.atMs(i));
            assertEquals(x[via].equals("app"), o.app(i));
        }
    }

    @Test
    void lotPlanMatchesLots() throws IOException {
        AuctionWorld w = world(auctionOnly());
        List<String> lots = AuctionRunTest.lines(auctionOnlyDir.resolve("lots.tsv.gz"));
        int pub = col(lots, "published_at"), no = col(lots, "lot_no"), plan = col(lots, "closes_at_planned"),
                opened = col(lots, "opened_at");
        assertEquals(lots.size() - 1, w.lotCount());
        for (int i = 1; i < lots.size(); i++) {
            String[] x = lots.get(i).split("\t", -1);
            AuctionWorld.LotPlan p = w.lotPlan(Integer.parseInt(x[0]));
            assertEquals(Long.parseLong(x[pub]), p.publishedAt());
            assertEquals(Integer.parseInt(x[no]), p.lotNo());
            if (!x[plan].isEmpty()) assertEquals(Long.parseLong(x[plan]), p.plannedAt());
            else if (!x[opened].isEmpty()) assertEquals(Long.parseLong(x[opened]), p.plannedAt());
        }
    }

    @Test
    void participantsDoNotDependOnTheCut() throws IOException {
        Release.Result fullRun = auctionOnly();
        AuctionWorld.ParticipantLog full = world(fullRun).participants();
        assertTrue(full.size() > 1000, "участников " + full.size());
        // первая ставка — как в таблице ставок (у тех, кто ставил до среза)
        List<String> bids = AuctionRunTest.lines(auctionOnlyDir.resolve("bids.tsv.gz"));
        Map<String, Long> first = new HashMap<>();
        Map<String, String> regClient = new HashMap<>();
        List<String> regs = AuctionRunTest.lines(auctionOnlyDir.resolve("registrations.tsv.gz"));
        int rc = col(regs, "client_id");
        for (int i = 1; i < regs.size(); i++) {
            String[] x = regs.get(i).split("\t", -1);
            regClient.put(x[0], x[rc]);
        }
        int lot = col(bids, "lot_id"), reg = col(bids, "registration_id"), at = col(bids, "placed_at");
        for (int i = 1; i < bids.size(); i++) {
            String[] x = bids.get(i).split("\t", -1);
            if (x[reg].isEmpty()) continue;
            first.merge(x[lot] + ":" + regClient.get(x[reg]), Long.parseLong(x[at]), Math::min);
        }
        int checked = 0;
        for (int i = 0; i < full.size(); i++) {
            Long f = first.get(full.lotId(i) + ":" + full.clientId(i));
            if (f == null) continue;
            assertEquals((long) f, full.firstBidAt(i), "лот " + full.lotId(i) + ", клиент " + full.clientId(i));
            checked++;
        }
        assertTrue(checked > 1000);
        // продление: у лотов короткого выпуска — те же участники (и первая ставка — та же, хоть она и после короткого среза)
        Path tmp = Files.createTempDirectory("auc-world-short-");
        Release.Result shortRun = Cli.run(AUC, Map.of("profile", "small", "out", tmp.toString(), "to", "2027-03-31",
                "modules", "auction"));
        assertTrue(shortRun.ok(), shortRun.report().render());
        AuctionWorld.ParticipantLog sp = world(shortRun).participants();
        int shortLots = world(shortRun).lotCount();
        Map<String, String> a = new TreeMap<>(), b = new TreeMap<>();
        for (int i = 0; i < sp.size(); i++) a.put(sp.lotId(i) + ":" + sp.clientId(i), sp.firstBidAt(i) + ":" + sp.online(i));
        long cutShort = io.hammerhall.data.auc.world.Horizon.cut(shortRun.context().period());
        for (int i = 0; i < full.size(); i++) {
            if (full.lotId(i) > shortLots) continue;
            int c = full.clientId(i);
            if (fullRun.context().get(AucKeys.CLIENTS).registeredAt(c) >= cutShort) continue;
            b.put(full.lotId(i) + ":" + c, full.firstBidAt(i) + ":" + full.online(i));
        }
        assertEquals(a, b, "участники лотов короткого выпуска — те же, что в длинном");
    }
}
