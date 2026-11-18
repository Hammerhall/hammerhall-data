package io.hammerhall.data.auc.site;

import io.hammerhall.data.auc.AucSchema;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты модуля сайта на упрощённом мире ({@link StubWorld}; поверх настоящего мира модуль проверяют тесты
 * набора — {@code AucContractTest}, {@code AucReleaseTest}): воронка сходится, «перебита» — после перебившей ставки,
 * тихие часы, визиты на срезе и продление; скорость — сутки параллельно: один и четыре потока дают один манифест.
 */
class SiteModuleTest {
    static final String SHORT_TO = "2026-03-31";
    static Path tmp, one, four, shortRun;
    static Release.Result r1, r4, rs;

    @BeforeAll
    static void build() throws IOException {
        tmp = Files.createTempDirectory("auc-site-test-");
        one = tmp.resolve("one");
        four = tmp.resolve("four");
        shortRun = tmp.resolve("short");
        r1 = Cli.run(new SiteOnlyDataset(1), Map.of("profile", "small", "out", one.toString()));
        r4 = Cli.run(new SiteOnlyDataset(4), Map.of("profile", "small", "out", four.toString()));
        rs = Cli.run(new SiteOnlyDataset(2), Map.of("profile", "small", "out", shortRun.toString(), "to", SHORT_TO));
    }

    @AfterAll
    static void clean() throws IOException {
        if (tmp == null) return;
        try (Stream<Path> s = Files.walk(tmp)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static boolean passed(Release.Result r, String prefix) {
        boolean any = false;
        for (CheckReport.Result x : r.report().results()) {
            if (!x.name().startsWith(prefix)) continue;
            any = true;
            if (!x.ok()) return false;
        }
        return any;
    }

    @Test
    void selfCheckPasses() {
        assertTrue(r1.ok(), r1.report().render());
        assertTrue(r4.ok(), r4.report().render());
        assertTrue(rs.ok(), rs.report().render());
    }

    @Test
    void funnelConverges() {
        assertTrue(passed(r1, "сайт: bid_submit ↔ ручные онлайн-ставки формой"));
        assertTrue(passed(r1, "сайт: order_set ↔ максимумы"));
        assertTrue(passed(r1, "сайт: watch_add/watch_remove ↔ watchlist"));
        assertTrue(passed(r1, "уведомления: моменты, статусы, тихие часы, адресаты"));
        assertTrue(passed(r1, "коридор «воронка»"));
        assertTrue(passed(r1, "коридор «местное время»"));
    }

    @Test
    void oneAndFourThreadsGiveOneManifest() throws IOException {
        assertEquals(Files.readString(one.resolve("manifest.json")).replace(one.toString(), ""),
                Files.readString(four.resolve("manifest.json")).replace(four.toString(), ""));
    }

    /** Коды модуля — те же, что в одном описании схемы ({@code AucSchema}). */
    @Test
    void codesMatchSchema() {
        Schema s = AucSchema.build();
        assertEquals(List.of(SiteTables.DEVICES), s.table("sessions").column("device").values());
        assertEquals(List.of(SiteTables.SOURCES), s.table("sessions").column("source").values());
        assertEquals(List.of(SiteTables.EVENT_KINDS), s.table("session_events").column("kind").values());
        assertEquals(List.of(SiteTables.VIA), s.table("watchlist").column("via").values());
        assertEquals(List.of(SiteTables.NOTIFY_KINDS), s.table("notifications").column("kind").values());
        assertEquals(List.of(SiteTables.CHANNELS), s.table("notifications").column("channel").values());
        assertEquals(List.of(SiteTables.NOTIFY_STATUS), s.table("notifications").column("status").values());
    }

    // ---------------------------------------------------------------- «перебита»

    /** Мир из трёх клиентов и одного онлайн-лота; ставки — (клиент, момент мс, принята). */
    private static SiteInput tiny(long[][] bids) {
        SiteInput in = new SiteInput();
        in.cutoff = 2_000_000_000L;
        in.scale = 0.01;
        in.firstDay = 0;
        in.nClients = 3;
        in.cKey = new long[]{0, 1, 2, 3};
        in.cCity = new int[4];
        in.cRegAt = new long[4];
        in.cFlags = new byte[4];
        in.cVisits = new float[4];
        in.cDept = new byte[4];
        in.cDevFrom = new int[]{0, 0, 0, 0, 0};
        in.devKey = new long[0];
        in.devKind = new byte[0];
        in.nSales = 1;
        in.sFormat = new byte[]{0, SiteInput.F_TIMED};
        in.sDept = new byte[2];
        in.sPub = new long[]{0, 1};
        in.sOpens = new long[]{0, 1};
        in.sStart = new long[]{0, 1_000_000};
        in.sEnd = new long[2];
        in.nLots = 1;
        in.lKey = new long[]{0, 1};
        in.lSale = new int[]{0, 1};
        in.lNo = new int[]{0, 1};
        in.lPub = new long[]{0, 1};
        in.lPlan = new long[]{0, 1_000_000};
        in.lClosed = new long[2];
        in.lWithdrawn = new long[2];
        in.lBuyer = new int[2];
        in.lPop = new float[2];
        in.lCat = new int[2];
        in.lMaker = new int[2];
        int n = bids.length;
        in.nBids = n;
        in.bId = new long[n];
        in.bLot = new int[n];
        in.bClient = new int[n];
        in.bAt = new int[n];
        in.bMs = new short[n];
        in.bFlags = new short[n];
        for (int i = 0; i < n; i++) {
            in.bId[i] = i + 1;
            in.bLot[i] = 1;
            in.bClient[i] = (int) bids[i][0];
            in.bAt[i] = Math.toIntExact(bids[i][1] / 1000);
            in.bMs[i] = (short) (bids[i][1] % 1000);
            in.bFlags[i] = (short) ((bids[i][2] != 0 ? SiteInput.B_ACCEPTED : 0) | SiteInput.B_ONLINE);
        }
        in.pLot = new int[0];
        in.pClient = new int[0];
        in.pFirstAt = new long[0];
        in.pOnline = new boolean[0];
        in.rId = new int[0];
        in.rClient = new int[0];
        in.rSale = new int[0];
        in.rDecided = new long[0];
        in.rFlags = new byte[0];
        in.iId = new int[0];
        in.iClient = new int[0];
        in.iSale = new int[0];
        in.iIssued = new long[0];
        in.iDue = new long[0];
        in.iPaid = new long[0];
        in.iCancelled = new long[0];
        in.payAt = new long[0];
        in.payInvoice = new int[0];
        in.payClient = new int[0];
        in.payId = new int[0];
        in.refAt = new long[0];
        in.refClient = new int[0];
        in.refSale = new int[0];
        in.refId = new int[0];
        in.stId = new int[0];
        in.stClient = new int[0];
        in.stSale = new int[0];
        in.stCreated = new long[0];
        in.poAt = new long[0];
        in.poSettlement = new int[0];
        in.poClient = new int[0];
        in.poSale = new int[0];
        in.poId = new int[0];
        in.oId = new int[0];
        in.oAt = new long[0];
        in.oLot = new int[0];
        in.oClient = new int[0];
        in.oVia = new byte[0];
        return in.finish(x -> new StubSources(x).build());
    }

    /** «Перебита»: (адресат, ставка-причина) по порядку. */
    private static List<long[]> outbids(SiteInput in) {
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < in.nSrc; i++) if (in.srcKind[i] == Rules.N_OUTBID) out.add(new long[]{in.srcClient[i], in.srcKeyB[i]});
        return out;
    }

    @Test
    void outbidGoesToTheLeaderAfterTheOutbiddingBid() {
        // 1 ставит; 2 перебивает; автоставка 1 отвечает той же мс — лидер не сменился, перебит 2 (ответом, ставка 3);
        // отклонённая ставка 3 не в счёт; 3 перебивает 1 (ставка 5)
        SiteInput in = tiny(new long[][]{{1, 10_000_000, 1}, {2, 20_000_000, 1}, {1, 20_000_000, 1}, {3, 30_000_000, 0},
                {3, 40_000_000, 1}});
        List<long[]> ob = outbids(in);
        assertEquals(2, ob.size());
        assertArrayEquals(new long[]{2, 3}, ob.get(0));
        assertArrayEquals(new long[]{1, 5}, ob.get(1));
        for (int i = 0; i < in.nSrc; i++) {
            if (in.srcKind[i] != Rules.N_OUTBID) continue;
            int b = (int) in.srcKeyB[i] - 1;
            assertTrue(in.srcAt[i] >= in.bAt[b], "уведомление — не раньше перебившей ставки");
        }
    }

    @Test
    void autoAnswerLaterIsAChangeOfLeader() {
        // ответ прежнего лидера не в ту же мс — лидер сменялся: перебиты оба по очереди
        SiteInput in = tiny(new long[][]{{1, 10_000_000, 1}, {2, 20_000_000, 1}, {1, 20_000_001, 1}});
        List<long[]> ob = outbids(in);
        assertEquals(2, ob.size());
        assertArrayEquals(new long[]{1, 2}, ob.get(0));
        assertArrayEquals(new long[]{2, 3}, ob.get(1));
    }

    // ---------------------------------------------------------------- тихие часы и местное время

    @Test
    void quietHoursByCityZone() {
        SiteInput in = tiny(new long[][]{{1, 10_000_000, 1}});
        in.cityOffset = new int[]{0, 3 * 3600, 10 * 3600};
        in.cCity[1] = 1;
        in.cCity[2] = 2;
        long msk19 = LocalDate.of(2026, 11, 18).atTime(19, 0).toEpochSecond(ZoneOffset.ofHours(3));
        assertFalse(Rules.quiet(in, 1, msk19), "19:00 в Москве — не тихие часы");
        assertTrue(Rules.quiet(in, 2, msk19), "19:00 по Москве — 02:00 во Владивостоке");
        assertTrue(Rules.quiet(in, 1, msk19 + 4 * 3600), "23:00 по Москве");
        assertFalse(Rules.quiet(in, 1, msk19 + 13 * 3600), "08:00 по Москве");
    }

    /** Push в выпуске не отправлен в тихие часы — по выпуску и поясам городов упрощённого мира (независимо от самопроверки). */
    @Test
    void pushIsNeverSentInQuietHours() throws IOException {
        SiteInput in = stubOf(one);
        int[] sent = {0};
        rows(one, "notifications", f -> {
            if (!f[3].equals("push") || f[9].isEmpty()) return;
            int c = Integer.parseInt(f[1]);
            long t = Long.parseLong(f[9]);
            int s = (int) Math.floorMod(t + in.offsetOfCity(in.cCity[c]), 86_400L);
            assertFalse(s >= 23 * 3600 || s < 8 * 3600, "push " + f[0] + " в " + s / 3600 + " ч по местному");
            sent[0]++;
        });
        assertTrue(sent[0] > 100, "push отправлены: " + sent[0]);
        int[] suppressed = {0};
        rows(one, "notifications", f -> {
            if (f[3].equals("push") && f[12].equals("suppressed")) suppressed[0]++;
        });
        assertTrue(suppressed[0] > 10, "push в тихие часы подавлены: " + suppressed[0]);
    }

    private static SiteInput stubOf(Path dir) {
        long cutoff = Math.min(SiteOnlyDatasetPeriod.now(), SiteOnlyDatasetPeriod.end());
        return StubWorld.build(SiteOnlyDatasetPeriod.seed(), 0.01, cutoff);
    }

    /** Период и seed тестового набора — те же, что у {@code SiteOnlyDataset}. */
    static final class SiteOnlyDatasetPeriod {
        static long now() {
            return new SiteOnlyDataset(1).period().nowEpoch();
        }

        static long end() {
            return new SiteOnlyDataset(1).period().endEpochExclusive();
        }

        static long seed() {
            return new SiteOnlyDataset(1).defaultSeed();
        }
    }

    // ---------------------------------------------------------------- порядок, срез, продление

    @Test
    void idsFollowTimeAndEventsStayInsideVisits() throws IOException {
        long[] last = {Long.MIN_VALUE, Long.MIN_VALUE};
        Map<Long, long[]> span = new HashMap<>();
        rows(one, "sessions", f -> {
            long start = Long.parseLong(f[6]);
            assertTrue(start >= last[0], "визиты по началу");
            last[0] = start;
            span.put(Long.parseLong(f[0]), new long[]{start, Long.parseLong(f[7])});
        });
        rows(one, "session_events", f -> {
            long[] sp = span.get(Long.parseLong(f[0]));
            long at = Long.parseLong(f[2]);
            assertTrue(at >= sp[0] && at <= sp[1], "событие внутри визита");
        });
        rows(one, "notifications", f -> {
            long c = Long.parseLong(f[8]);
            assertTrue(c >= last[1], "уведомления по постановке в очередь");
            last[1] = c;
        });
    }

    @Test
    void visitsAreCutAtTheCutoff() throws IOException {
        long cutoff = LocalDate.parse(SHORT_TO).plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        rows(shortRun, "sessions", f -> assertTrue(Long.parseLong(f[7]) < cutoff, "визит кончился до среза"));
        rows(shortRun, "session_events", f -> assertTrue(Long.parseLong(f[2]) < cutoff, "событие до среза"));
        rows(shortRun, "notifications", f -> {
            for (int k : new int[]{8, 9, 10}) assertTrue(f[k].isEmpty() || Long.parseLong(f[k]) < cutoff, "уведомление до среза");
            if (f[12].equals("delivered")) assertFalse(f[10].isEmpty());
        });
        rows(shortRun, "watchlist", f -> assertTrue(Long.parseLong(f[2]) < cutoff));
    }

    /**
     * Продление: каждая строка короткого прогона есть в длинном по ключу, поля те же, кроме «в пути»
     * ({@code sessions.ended_at}, {@code watchlist.removed_at}, у уведомлений — отправка и статус); события визитов —
     * только дописываются.
     */
    @Test
    void extendingThePeriodKeepsHistory() throws IOException {
        check(new String[]{"watchlist"}, 2, Set.of(3));
        check(new String[]{"sessions"}, 1, Set.of(7));
        check(new String[]{"session_events"}, 2, Set.of());
        check(new String[]{"notifications"}, 1, Set.of(9, 10, 11, 12));
    }

    private static void check(String[] tables, int keyCols, Set<Integer> mayChange) throws IOException {
        String t = tables[0];
        Map<String, String[]> longRows = new HashMap<>();
        rows(one, t, f -> longRows.put(String.join("\t", Arrays.copyOf(f, keyCols)), f));
        List<String> diffs = new ArrayList<>();
        int[] n = {0};
        rows(shortRun, t, f -> {
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
        assertTrue(n[0] > 100, t + ": строк короткого " + n[0]);
        assertEquals(List.of(), diffs, t);
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
