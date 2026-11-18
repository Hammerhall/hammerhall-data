package io.hammerhall.data.auc.site;

import io.hammerhall.data.auc.AucDataset;
import io.hammerhall.data.auc.catalog.CatalogModule;
import io.hammerhall.data.auc.house.HouseModule;
import io.hammerhall.data.auc.people.PeopleModule;
import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.LotResult;
import io.hammerhall.data.auc.world.LotResultCode;
import io.hammerhall.data.auc.world.MoneyWorld;
import io.hammerhall.data.auc.world.Registrations;
import io.hammerhall.data.auc.world.SaleTier;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.gen.Release;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Переходник «мир → сайт» на настоящих записях мира: поддельные {@code CALENDAR}, {@code AUCTION} и {@code MONEY}
 * собраны из упрощённых торгов ({@link StubWorld}) поверх настоящих клиентов и отданы модулю сайта через мир, как их
 * отдают модули торгов и денег.
 * Сайт идёт путём мира ({@code AuctionWorld.bids}, {@code notices}, {@code MoneyWorld.events}) и проходит самопроверку.
 */
class WorldInputTest {
    @Test
    void parsesEventKeys() {
        assertArrayEquals(new long[]{8, 6}, WorldInput.parseKey("outbid:8:6", "outbid"));
        assertArrayEquals(new long[]{123, -1}, WorldInput.parseKey("invoice_issued:123", "invoice_issued"));
        assertArrayEquals(new long[]{5, 2}, WorldInput.parseKey("payment_reminder:5:2", "payment_reminder"));
        assertThrows(IllegalArgumentException.class, () -> WorldInput.parseKey("outbid:x", "outbid"));
        assertThrows(IllegalArgumentException.class, () -> WorldInput.parseKey("lot_won:1", "outbid"));
        assertEquals(Rules.N_OUTBID, WorldInput.kindCode("outbid"));
        assertEquals(Rules.N_PAYOUT, WorldInput.kindCode("settlement_paid"));
    }

    @Test
    void deviceKindIsAPropertyOfTheDevice() {
        int[] n = new int[3];
        for (long d = 1; d <= 30_000; d++) {
            byte k = WorldInput.deviceKind(d * 0x9E3779B97F4A7C15L);
            assertEquals(k, WorldInput.deviceKind(d * 0x9E3779B97F4A7C15L));
            n[k]++;
        }
        assertTrue(n[0] > 12_000 && n[1] > 8_000 && n[2] > 5_000, "доли устройств: " + n[0] + " / " + n[1] + " / " + n[2]);
    }

    @Test
    void siteRunsOnTheWorldPath() throws IOException {
        Path tmp = Files.createTempDirectory("auc-site-world-");
        try {
            Release.Result r = Cli.run(new WorldDataset(), Map.of("profile", "small", "out", tmp.toString()));
            assertTrue(r.ok(), r.report().render());
            long events = 0, notices = 0;
            for (var f : r.files()) {
                if (f.name().equals("session_events.tsv.gz")) events = f.rows();
                if (f.name().equals("notifications.tsv.gz")) notices = f.rows();
            }
            assertTrue(events > 100_000 && notices > 50_000, "событий " + events + ", уведомлений " + notices);
        } finally {
            try (Stream<Path> s = Files.walk(tmp)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    /** Набор: дом, люди, вещи, поддельные торги и деньги, сайт — путём мира. */
    static final class WorldDataset extends AucDatasetView {
        @Override
        public List<Module> modules() {
            List<Module> m = new ArrayList<>();
            m.add(new HouseModule());
            m.add(new PeopleModule());
            m.add(new CatalogModule());
            m.add(new FakeTrade());
            // модуль clients здесь не нужен: его проверки ждут настоящих регистраций мира
            m.add(SiteModule.ofWorld(List.of(HouseModule.NAME, PeopleModule.NAME, CatalogModule.NAME, FakeTrade.NAME)));
            return m;
        }
    }

    /** {@code AucDataset} с другим списком модулей (паспорт, схема, входы — те же). */
    abstract static class AucDatasetView extends DatasetDelegate {
        AucDatasetView() {
            super(new AucDataset());
        }
    }

    /** Кладёт в мир календарь, торги и деньги упрощённого мира поверх настоящих клиентов. Таблиц не пишет. */
    static final class FakeTrade implements Module {
        static final String NAME = "fake_trade";

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public List<String> requires() {
            return List.of(HouseModule.NAME, PeopleModule.NAME, CatalogModule.NAME);
        }

        @Override
        public List<String> tables() {
            return List.of();
        }

        @Override
        public void generate(GenContext ctx) {
            SiteInput x = StubWorld.trade(ctx);
            ctx.put(AucKeys.CALENDAR, calendar(x));
            ctx.put(AucKeys.AUCTION, auction(x));
            List<MoneyWorld.Event> money = new ArrayList<>();
            for (int i = 0; i < x.nSrc; i++) {
                byte k = x.srcKind[i];
                if (k < Rules.N_INVOICE) continue;
                money.add(new MoneyWorld.Event(SiteTables.NOTIFY_KINDS[k], x.srcClient[i], x.srcSale[i], x.srcInvoice[i],
                        x.srcAt[i], key(x, i)));
            }
            ctx.put(AucKeys.MONEY, () -> money);
        }

        private static String key(SiteInput x, int i) {
            return SiteTables.NOTIFY_KINDS[x.srcKind[i]] + ":" + x.srcKeyA[i] + (x.srcKeyB[i] >= 0 ? ":" + x.srcKeyB[i] : "");
        }

        private static Calendar calendar(SiteInput x) {
            List<Calendar.Sale> sales = new ArrayList<>();
            for (int s = 1; s <= x.nSales; s++) {
                Format f = x.sFormat[s] == SiteInput.F_TIMED ? Format.TIMED : Format.LIVE;
                sales.add(new Calendar.Sale(s, s, "S-" + s, f, SaleTier.REGULAR, Dept.byId(x.sDept[s] + 1), 1, 0, "Торги",
                        "Sale", x.sPub[s], x.sOpens[s], null, null, x.sStart[s], 1, 10, 1, 10));
            }
            return new Calendar() {
                @Override
                public List<Sale> sales() {
                    return sales;
                }

                @Override
                public Sale sale(int id) {
                    return sales.get(id - 1);
                }

                @Override
                public List<Sale> openingBetween(long fromAt, long toAt) {
                    return List.of();
                }
            };
        }

        private static AuctionWorld auction(SiteInput x) {
            List<AuctionWorld.Notice> notices = new ArrayList<>();
            for (int i = 0; i < x.nSrc; i++) {
                byte k = x.srcKind[i];
                if (k >= Rules.N_INVOICE) continue;
                notices.add(new AuctionWorld.Notice(SiteTables.NOTIFY_KINDS[k], x.srcClient[i], x.srcSale[i], x.srcLot[i],
                        k == Rules.N_OUTBID ? x.srcKeyB[i] : 0, x.srcAt[i] * 1000L, key(x, i)));
            }
            return new AuctionWorld() {
                @Override
                public int lotCount() {
                    return x.nLots;
                }

                @Override
                public LotResult lot(int l) {
                    int s = x.lSale[l];
                    boolean sold = x.lBuyer[l] != 0;
                    LotResultCode rc = x.lClosed[l] == 0 ? LotResultCode.OPEN : sold ? LotResultCode.SOLD : LotResultCode.BOUGHT_IN;
                    return new LotResult(Keys.lot(s, x.lNo[l], 0), l, s, 0, 0, Dept.byId(x.sDept[s] + 1),
                            x.sFormat[s] == SiteInput.F_TIMED ? Format.TIMED : Format.LIVE, 1, rc, sold ? 100_00 : 0, 0, 0,
                            0, x.lClosed[l], 0, x.lBuyer[l], 0, 0, false, 0, 0, 0, 0, 0, x.lWithdrawn[l], null);
                }

                @Override
                public List<LotResult> lotsOfSale(int saleId) {
                    return List.of();
                }

                @Override
                public Consignment consignment(int id) {
                    return null;
                }

                @Override
                public Registrations registrations() {
                    return null;
                }

                @Override
                public List<InvoicePlan> invoices() {
                    return List.of();
                }

                @Override
                public BidLog bids() {
                    return new BidLog() {
                        @Override
                        public int size() {
                            return x.nBids;
                        }

                        @Override
                        public long id(int i) {
                            return x.bidId(i);
                        }

                        @Override
                        public int lotId(int i) {
                            return x.bLot[i];
                        }

                        @Override
                        public int clientId(int i) {
                            return x.bClient[i];
                        }

                        @Override
                        public long atMs(int i) {
                            return x.bAt[i] * 1000L + x.bMs[i];
                        }

                        @Override
                        public long amountKop(int i) {
                            return 100_00;
                        }

                        @Override
                        public boolean accepted(int i) {
                            return (x.bFlags[i] & SiteInput.B_ACCEPTED) != 0;
                        }

                        @Override
                        public boolean manualOnline(int i) {
                            return x.formBid(i);
                        }
                    };
                }

                @Override
                public List<Notice> notices() {
                    return notices;
                }

                @Override
                public ClientOutcome clientOutcome(int clientId) {
                    return ClientOutcome.NONE;
                }
            };
        }
    }
}
