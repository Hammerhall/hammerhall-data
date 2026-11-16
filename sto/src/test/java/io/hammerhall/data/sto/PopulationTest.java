package io.hammerhall.data.sto;

import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.Names;
import io.hammerhall.data.sto.catalog.PriceBook;
import io.hammerhall.data.sto.customers.Customers;
import io.hammerhall.data.sto.customers.PersonNames;
import io.hammerhall.data.sto.market.Market;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Население магазина: продавцы и предложения (модуль {@code market}), клиенты и адреса ({@code customers}) —
 * самопроверка, стабильность при продлении (прогон до {@value #SHORT_TO} — префикс полного), инварианты по TSV,
 * API моделей для следующих модулей; правила имён и порога НДС на УСН.
 */
class PopulationTest {
    static final String SHORT_TO = "2027-03-31";

    @TempDir
    static Path tmp;
    static Path full, shortRun;
    static boolean fullOk, shortOk;
    static String fullReport, shortReport;
    static Market market;
    static Customers customers;
    static Catalog catalog;
    static PriceBook prices;
    static long sharedPlanted;

    @BeforeAll
    static void build() throws IOException {
        DatasetContract.assumeInputs(StoReleaseTest.STO);
        full = tmp.resolve("full");
        shortRun = tmp.resolve("short");
        Release.Result rf = Cli.run(StoReleaseTest.STO, Map.of("profile", "small", "out", full.toString(),
                "modules", "market,customers"));
        fullOk = rf.ok();
        fullReport = rf.report().render();
        market = rf.context().get(StoKeys.MARKET);
        customers = rf.context().get(StoKeys.CUSTOMERS);
        catalog = rf.context().get(StoKeys.CATALOG);
        prices = rf.context().get(StoKeys.PRICES);
        sharedPlanted = rf.planted().getOrDefault("shared_address", 0L);
        Release.Result rs = Cli.run(StoReleaseTest.STO, Map.of("profile", "small", "out", shortRun.toString(),
                "modules", "market,customers", "to", SHORT_TO));
        shortOk = rs.ok();
        shortReport = rs.report().render();
    }

    @Test
    void selfCheckPasses() {
        assertTrue(fullOk, fullReport);
        assertTrue(shortOk, shortReport);
    }

    // ---------------------------------------------------------------- продление

    @Test
    void extendingThePeriodKeepsSellersAndOffers() throws IOException {
        long shortEnd = LocalDate.parse(SHORT_TO).plusDays(1).toEpochDay() * 86400L;
        List<String[]> ls = StoReleaseTest.rows(full, "sellers"), ss = StoReleaseTest.rows(shortRun, "sellers");
        assertTrue(ss.size() > 2000 && ss.size() < ls.size(), "продавцов: коротких " + ss.size() + ", длинных " + ls.size());
        for (int i = 0; i < ss.size(); i++) StoReleaseTest.sameExcept(ss.get(i), ls.get(i), 5, "продавец " + ss.get(i)[0]);
        List<String[]> lo = StoReleaseTest.rows(full, "offers"), so = StoReleaseTest.rows(shortRun, "offers");
        assertTrue(so.size() > 50_000 && so.size() < lo.size(), "предложений: коротких " + so.size() + ", длинных " + lo.size());
        for (int i = 0; i < so.size(); i++) {
            StoReleaseTest.sameExcept(so.get(i), lo.get(i), 4, "предложение " + so.get(i)[0]);
            if (!so.get(i)[4].isEmpty()) assertTrue(Long.parseLong(so.get(i)[4]) <= shortEnd);
        }
        // после прежнего среза — только новые строки (начало не раньше среза)
        for (int i = so.size(); i < lo.size(); i++) assertTrue(Long.parseLong(lo.get(i)[3]) >= shortEnd, "строка " + lo.get(i)[0]);
    }

    @Test
    void extendingThePeriodKeepsCustomersAndAddresses() throws IOException {
        List<String[]> lc = StoReleaseTest.rows(full, "customers"), sc = StoReleaseTest.rows(shortRun, "customers");
        assertTrue(sc.size() > 10_000 && sc.size() < lc.size(), "клиентов: коротких " + sc.size() + ", длинных " + lc.size());
        for (int i = 0; i < sc.size(); i++) assertEquals(String.join("\t", sc.get(i)), String.join("\t", lc.get(i)), "клиент");
        List<String[]> la = StoReleaseTest.rows(full, "addresses"), sa = StoReleaseTest.rows(shortRun, "addresses");
        assertTrue(sa.size() > 10_000 && sa.size() < la.size());
        for (int i = 0; i < sa.size(); i++) assertEquals(String.join("\t", sa.get(i)), String.join("\t", la.get(i)), "адрес");
    }

    // ---------------------------------------------------------------- инварианты по TSV

    @Test
    void offerPeriodsAreContiguousPerListing() throws IOException {
        Map<String, List<long[]>> byListing = new HashMap<>();
        for (String[] r : StoReleaseTest.rows(full, "offers")) {
            long to = r[4].isEmpty() ? Long.MAX_VALUE : Long.parseLong(r[4]);
            byListing.computeIfAbsent(r[1] + "|" + r[2], k -> new ArrayList<>()).add(new long[]{Long.parseLong(r[3]), to});
        }
        for (Map.Entry<String, List<long[]>> e : byListing.entrySet()) {
            List<long[]> p = e.getValue();
            p.sort((a, b) -> Long.compare(a[0], b[0]));
            for (int i = 0; i + 1 < p.size(); i++) assertEquals(p.get(i)[1], p.get(i + 1)[0], "листинг " + e.getKey());
        }
        assertTrue(byListing.size() > 20_000, "листингов " + byListing.size());
    }

    @Test
    void addressesAfterRegistrationAndSharedPointsPlanted() throws IOException {
        Map<String, Long> reg = new HashMap<>();
        for (String[] r : StoReleaseTest.rows(full, "customers")) reg.put(r[0], Long.parseLong(r[5]));
        Map<String, Set<String>> byPoint = new HashMap<>();
        for (String[] a : StoReleaseTest.rows(full, "addresses")) {
            assertTrue(Long.parseLong(a[6]) >= reg.get(a[1]), "адрес " + a[0] + " раньше регистрации");
            byPoint.computeIfAbsent(a[3] + "," + a[4], k -> new HashSet<>()).add(a[1]);
        }
        int groups = 0;
        for (Set<String> s : byPoint.values()) if (s.size() >= 2) groups++;
        assertTrue(groups >= 3, "групп «одна точка — разные клиенты» " + groups);
        assertTrue(sharedPlanted >= 6, "заложено адресов " + sharedPlanted);
    }

    // ---------------------------------------------------------------- API моделей

    @Test
    void marketModelAnswersOffersAtMoment() {
        long t = LocalDate.of(2026, 6, 15).toEpochDay() * 86400L;
        int checked = 0;
        Rng r = new Rng(1);
        for (Catalog.Product p : catalog.products()) {
            List<Market.OfferAt> offs = market.offersAt(p.id, t);
            if (p.assortment == Catalog.Assortment.ONLY_1P) assertTrue(offs.isEmpty());
            for (Market.OfferAt o : offs) {
                assertEquals(p.id, market.offerProduct(o.offerId()));
                assertTrue(market.offerFrom(o.offerId()) <= t && t < market.offerTo(o.offerId()));
                assertTrue(market.seller(o.sellerId()).activeAt(t));
                checked++;
            }
            if (p.assortment == Catalog.Assortment.ONLY_3P && p.aliveOn(LocalDate.of(2026, 6, 15)) && !offs.isEmpty()) {
                assertNotNull(market.choose(p.id, t, prices.priceAt(p.id, t), p.assortment, r));
            }
        }
        assertTrue(checked > 5_000, "предложений на момент " + checked);
        assertEquals(1, market.ofQuality(Market.Quality.BAD).size());
    }

    @Test
    void customersModelAnswersWhoOrders() {
        LocalDate d = LocalDate.of(2026, 11, 27);
        Customers.Sampler s = customers.sampler(d);
        assertTrue(s.size() > 1000, "активных " + s.size());
        Rng r = new Rng(2);
        long t = d.toEpochDay() * 86400L + 12 * 3600;
        int withAddr = 0;
        for (int i = 0; i < 500; i++) {
            int id = s.pick(r);
            assertTrue(customers.activeOn(id, d));
            int a = customers.addressAt(id, t, r);
            if (a > 0) {
                withAddr++;
                assertEquals(id, customers.addressCustomer(a));
                assertTrue(customers.addressCreatedAt(a) <= t);
            }
        }
        assertTrue(withAddr > 200, "с адресом " + withAddr);
        for (int id : customers.firstOrdersOn(d)) {
            long f = customers.firstOrderAt(id);
            assertTrue(f >= t - 12 * 3600 && f < t + 12 * 3600 && f >= customers.registeredAt(id));
        }
        assertTrue(customers.favoriteDepartments(1).length >= 1);
    }

    // ---------------------------------------------------------------- правила

    @Test
    void surnamesAgreeWithGender() {
        assertEquals("Иванова", PersonNames.feminine("Иванов"));
        assertEquals("Ильина", PersonNames.feminine("Ильин"));
        assertEquals("Соловьёва", PersonNames.feminine("Соловьёв"));
        assertEquals("Высоцкая", PersonNames.feminine("Высоцкий"));
        assertEquals("Островская", PersonNames.feminine("Островский"));
        assertEquals("Толстая", PersonNames.feminine("Толстой"));
        assertEquals("Шевченко", PersonNames.feminine("Шевченко"));
        assertEquals("Черных", PersonNames.feminine("Черных"));
        assertEquals("Мельник", PersonNames.feminine("Мельник"));
        Rng r = new Rng(3);
        for (int i = 0; i < 1000; i++) {
            String root = PersonNames.orgRoot(r);
            if (root != null) assertFalse(Names.resemblesReal(Names.toLatin(root)), root);
        }
    }

    private static Market oneSeller(Market.TaxRegime regime, long revenue2025, double growth) {
        Market.Seller s = new Market.Seller(1, "ООО «Проба»", "ООО", 1, Market.Model.FBS, 0, Long.MAX_VALUE, regime,
                revenue2025, growth, 1, List.of(), 1, Market.Quality.NORMAL, 1, 0, 1, 1, 0.5);
        return new Market(List.of(s), 1, new int[0], new int[0], new long[0], new long[0], new long[0]);
    }

    @Test
    void usnExemptionThresholdFallsByYear() {
        Market osn = oneSeller(Market.TaxRegime.OSN, 500_000_000L, 1.0);
        assertEquals(20, osn.vatRate(1, LocalDate.of(2025, 12, 31)));
        assertEquals(22, osn.vatRate(1, LocalDate.of(2026, 1, 1)));
        // 30 млн: в 2025 — до порога 60 млн, с 2026 — выше порога 20 млн → 5 %
        Market m30 = oneSeller(Market.TaxRegime.USN, 30_000_000L, 1.0);
        assertEquals(0, m30.vatRate(1, LocalDate.of(2025, 6, 1)));
        assertEquals(5, m30.vatRate(1, LocalDate.of(2026, 1, 1)));
        // 12 млн с ростом ×1,5: 2026 — 18 млн (до порога 20), 2027 — прошлый год 18 > 15 → 5 %
        Market m12 = oneSeller(Market.TaxRegime.USN, 12_000_000L, 1.5);
        assertEquals(0, m12.vatRate(1, LocalDate.of(2026, 12, 31)));
        assertEquals(5, m12.vatRate(1, LocalDate.of(2027, 1, 1)));
        // 15 млн с ростом ×1,6: в 2026 выручка 24 млн переходит порог 20 млн в ноябре → с 1 декабря 5 %
        Market m15 = oneSeller(Market.TaxRegime.USN, 15_000_000L, 1.6);
        assertEquals(0, m15.vatRate(1, LocalDate.of(2026, 11, 30)));
        assertEquals(5, m15.vatRate(1, LocalDate.of(2026, 12, 1)));
        // свыше 250 млн — 7 %
        Market big = oneSeller(Market.TaxRegime.USN, 300_000_000L, 1.0);
        assertEquals(7, big.vatRate(1, LocalDate.of(2026, 3, 1)));
        assertEquals(15_000_000L, Market.usnExemptionLimitRub(2027));
    }
}
