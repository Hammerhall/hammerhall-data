package io.hammerhall.data.sto.orders;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.money.Money;
import io.hammerhall.data.core.money.Tax;
import io.hammerhall.data.sto.StoDataset;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.Promotions;
import io.hammerhall.data.sto.catalog.Promotions.Promotion;
import io.hammerhall.data.sto.catalog.Promotions.Target;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила заказов без прогона всего мира: скидки по акциям ({@link PromoCalc}) на заданных акциях, сценарии
 * самопроверки ({@link OrderAudit}) — правильный заказ проходит, испорченный ловится своей проверкой; тексты (в них нет
 * внутренних имён платформы; исходники и выпуск целиком проверяют общие тесты набора — {@code StoContractTest}).
 */
class OrderRulesTest {
    @TempDir
    static Path tmp;
    static GenContext ctx;
    static World w;

    @BeforeAll
    static void world() throws IOException {
        DatasetContract.assumeInputs(new StoDataset());
        Release.Result r = Cli.run(new StoDataset(), Map.of("profile", "small", "out", tmp.resolve("w").toString(),
                "modules", "market,customers"));
        assertTrue(r.ok());
        ctx = r.context();
        w = new World(ctx);
    }

    static final long T = LocalDate.of(2026, 3, 10).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() + 12 * 3600;

    /** Товары магазина в продаже в момент {@link #T}, не комплекты, по id. */
    static List<Catalog.Product> shopProducts(int n) {
        List<Catalog.Product> out = new ArrayList<>();
        for (Catalog.Product p : w.cat.products()) {
            if (p.bundle || p.assortment == Catalog.Assortment.ONLY_3P || w.prices.priceAt(p.id, T) <= 0) continue;
            out.add(p);
            if (out.size() == n) break;
        }
        return out;
    }

    static Promotion promo(int id, String kind, String pct, Long amount, Integer buy, Integer free, String code, Long min,
                           boolean stackable, int... products) {
        List<Target> ts = new ArrayList<>();
        for (int p : products) ts.add(new Target(p, null));
        return new Promotion(id, kind, "акция " + id, "promo " + id, null, pct == null ? null : new BigDecimal(pct), amount,
                buy, free, code, min, T - 86_400, T + 86_400, stackable, ts, false);
    }

    static Order.Line line(int no, int product, int qty, long unit, int seller) {
        Order.Line l = new Order.Line();
        l.lineNo = no;
        l.product = product;
        l.qty = qty;
        l.unit = unit;
        l.seller = seller;
        return l;
    }

    // ---------------------------------------------------------------- акции

    @Test
    void promotionRules() {
        List<Catalog.Product> ps = shopProducts(8);
        int a = ps.get(0).id, b = ps.get(1).id, c = ps.get(2).id, d = ps.get(3).id, e = ps.get(4).id, f = ps.get(5).id;
        Promotions promos = new Promotions(List.of(
                promo(1, "percent", "10", null, null, null, null, null, false, a),
                promo(2, "n_plus_m", null, null, 3, 1, null, null, false, b, c),
                promo(3, "bundle", "15", null, null, null, null, null, false, d, e),
                promo(4, "percent", "20", null, null, null, null, null, false, f),
                promo(5, "percent", "10", null, null, null, null, null, true, f),
                promo(6, "amount", null, 50_00L, null, null, null, null, true, f),
                promo(7, "promocode", null, 300_00L, null, null, "TESTCODE", 2000_00L, true)));
        PromoCalc pc = new PromoCalc(w.cat, promos, w.maxProduct());
        pc.day(T - 3600, T + 3600);
        // процент на товар
        Order.Line[] o1 = {line(1, a, 1, 1000_00, 0)};
        pc.apply(o1, null, T);
        assertEquals(100_00, o1[0].discount);
        assertEquals(1, o1[0].promo);
        // n+m: из трёх единиц бесплатна самая дешёвая
        Order.Line[] o2 = {line(1, b, 2, 500_00, 0), line(2, c, 1, 300_00, 0)};
        pc.apply(o2, null, T);
        assertEquals(0, o2[0].discount);
        assertEquals(300_00, o2[1].discount);
        // «вместе дешевле»: один целевой товар — без скидки, два — по 15 %
        Order.Line[] o3 = {line(1, d, 1, 2000_00, 0)};
        pc.apply(o3, null, T);
        assertEquals(0, o3[0].discount);
        Order.Line[] o4 = {line(1, d, 1, 2000_00, 0), line(2, e, 1, 1000_00, 0)};
        pc.apply(o4, null, T);
        assertEquals(300_00, o4[0].discount);
        assertEquals(150_00, o4[1].discount);
        // несуммируемая 20 % против суммируемых 10 % + 50 ₽: 200 > 150 — берётся одна
        Order.Line[] o5 = {line(1, f, 1, 1000_00, 0)};
        pc.apply(o5, null, T);
        assertEquals(200_00, o5[0].discount);
        assertEquals(4, o5[0].promo);
        // промокод на заказ: от порога — делится по строкам, ниже порога — нет
        Order.Line[] o6 = {line(1, ps.get(6).id, 1, 1500_00, 0), line(2, ps.get(7).id, 1, 1000_00, 0)};
        assertTrue(pc.apply(o6, "TESTCODE", T));
        assertEquals(300_00, o6[0].discount + o6[1].discount);
        assertEquals(180_00, o6[0].discount);
        Order.Line[] o7 = {line(1, ps.get(6).id, 1, 1500_00, 0)};
        assertFalse(pc.apply(o7, "TESTCODE", T));
        assertEquals(0, o7[0].discount);
        // строка продавца — без акций магазина; акция вне срока — не действует
        Order.Line[] o8 = {line(1, a, 1, 1000_00, 7)};
        pc.apply(o8, null, T);
        assertEquals(0, o8[0].discount);
        Order.Line[] o9 = {line(1, a, 1, 1000_00, 0)};
        pc.apply(o9, null, T + 2 * 86_400);
        assertEquals(0, o9[0].discount);
    }

    // ---------------------------------------------------------------- самопроверка: сценарии

    /** Правильный доставленный заказ магазина по телефону: курьер, карта. */
    static Order good() {
        Catalog.Product p = shopProducts(1).getFirst();
        int customer = 0, address = 0;
        for (int c = 1; c <= w.cs.size() && address == 0; c++) {
            int[] as = w.cs.addressesOf(c);
            if (as.length > 0 && w.cs.addressCreatedAt(as[0]) < T) {
                customer = c;
                address = as[0];
            }
        }
        Order o = new Order(1);
        o.id = 1;
        o.customer = customer;
        o.address = address;
        o.channel = Order.PHONE;
        o.method = Order.COURIER;
        o.created = T;
        o.estimated = T + 3 * 86_400;
        Order.Line l = line(1, p.id, 2, w.prices.priceAt(p.id, T), 0);
        l.network = true;
        l.vatRate = Tax.vatRate(w.businessDate(T));
        l.vat = Money.vatIncluded(l.net(), l.vatRate);
        o.lines = new Order.Line[]{l};
        o.items = l.gross();
        o.delivery = 299_00;
        o.total = o.items + o.delivery;
        o.vat = l.vat + Money.vatIncluded(o.delivery, l.vatRate);
        o.log(Order.CREATED, T);
        o.log(Order.PAID, T + 60);
        o.log(Order.ASSEMBLING, T + 3600);
        o.log(Order.ASSEMBLED, T + 7200);
        o.log(Order.SHIPPED, T + 10_800);
        o.log(Order.DELIVERED, T + 86_400);
        return o;
    }

    /** Прогнать сценарий: заказ (возможно испорченный) и действия с проверкой; итог проверки по имени. */
    static boolean passes(String check, Consumer<Order> spoil, Consumer<OrderAudit> more) {
        OrderAudit a = new OrderAudit(w, T + 30 * 86_400L);
        Order o = good();
        spoil.accept(o);
        a.payment(1, OrderSim.CARD, o.total, "succeeded", T + 60, false);
        a.shipment(1, firstCityWarehouse(), 6, 0, T + 9000, T + 10_800, T + 80_000, "delivered");
        long paid = -1, asm = -1, shp = -1, dlv = -1, cnc = -1;
        for (int i = 0; i < o.logN; i++) {
            switch (o.logSt[i]) {
                case Order.PAID -> paid = o.logAt[i];
                case Order.ASSEMBLED -> asm = o.logAt[i];
                case Order.SHIPPED -> shp = o.logAt[i];
                case Order.DELIVERED -> dlv = o.logAt[i];
                case Order.CANCELLED -> cnc = o.logAt[i];
                default -> {
                }
            }
        }
        a.order(o, o.logN, paid, asm, shp, dlv, cnc, o.lastStatus());
        more.accept(a);
        CheckReport r = new CheckReport();
        a.report(r);
        for (CheckReport.Result x : r.results()) if (x.name().equals("orders: " + check)) return x.ok();
        throw new AssertionError("нет проверки " + check);
    }

    static int firstCityWarehouse() {
        for (var x : w.geo.warehouses()) if (x.kind() == io.hammerhall.data.sto.geo.Geo.Kind.WAREHOUSE) return x.id();
        throw new AssertionError();
    }

    static final Consumer<Order> NONE = o -> {
    };
    static final Consumer<OrderAudit> NOTHING = a -> {
    };

    @Test
    void auditPassesGoodOrder() {
        for (String c : List.of(OrderAudit.C_SUMS, OrderAudit.C_PRICE, OrderAudit.C_LOG, OrderAudit.C_TIME, OrderAudit.C_DELIVERY,
                OrderAudit.C_PAY, OrderAudit.C_RET, OrderAudit.C_REV, OrderAudit.C_SHIP)) {
            assertTrue(passes(c, NONE, NOTHING), c);
        }
    }

    @Test
    void auditCatchesBrokenOrders() {
        assertFalse(passes(OrderAudit.C_SUMS, o -> o.total += 1, NOTHING), "итог не сходится");
        assertFalse(passes(OrderAudit.C_SUMS, o -> o.lines[0].vatRate = 18, NOTHING), "ставка НДС не на дату");
        assertFalse(passes(OrderAudit.C_PRICE, o -> o.lines[0].unit += 100, NOTHING), "цена не по истории");
        assertFalse(passes(OrderAudit.C_LOG, o -> o.log(Order.ASSEMBLING, T + 90_000), NOTHING), "переход delivered → assembling");
        assertFalse(passes(OrderAudit.C_LOG, o -> o.logAt[3] = T + 100, NOTHING), "статус раньше предыдущего");
        assertFalse(passes(OrderAudit.C_TIME, o -> o.logAt[5] = T + 40 * 86_400L, NOTHING), "получен после конца фактов");
        assertFalse(passes(OrderAudit.C_DELIVERY, o -> o.logAt[5] = T + 10_000, NOTHING), "получен раньше отгрузки");
        assertFalse(passes(OrderAudit.C_PAY, NONE, a -> a.payment(1, OrderSim.CARD, 100, "succeeded", T + 61, false)), "лишний платёж");
        assertFalse(passes(OrderAudit.C_RET, NONE, a -> a.ret(1, 1, 2, T + 90_000, -1, 10_000_000_00L)), "вернули больше оплаченного");
        assertFalse(passes(OrderAudit.C_RET, NONE, a -> a.ret(1, 1, 1, T + 3600, -1, -1)), "возврат до получения");
        assertFalse(passes(OrderAudit.C_REV, NONE, a -> a.review(1, 1, 1, T + 7200)), "отзыв до получения");
        assertFalse(passes(OrderAudit.C_SHIP, NONE, a -> a.shipment(1, firstCityWarehouse(), 6, 0, T, -1, -1, "delivered")),
                "статус отгрузки без моментов");
        // заложенный переход пропускается — ровно он
        ctx.planted().add("invalid_status_transition", 1);
        assertTrue(passes(OrderAudit.C_LOG, o -> o.log(Order.ASSEMBLING, T + 90_000), NOTHING), "заложенный переход");
    }

    @Test
    void allowedTransitions() {
        assertTrue(OrderAudit.ALLOWED[Order.CREATED][Order.PAID]);
        assertTrue(OrderAudit.ALLOWED[Order.ASSEMBLED][Order.ASSEMBLING]);
        assertTrue(OrderAudit.ALLOWED[Order.READY][Order.SHIPPED]);
        assertFalse(OrderAudit.ALLOWED[Order.CANCELLED][Order.SHIPPED]);
        assertFalse(OrderAudit.ALLOWED[Order.DELIVERED][Order.PAID]);
        assertFalse(OrderAudit.ALLOWED[Order.CREATED][Order.DELIVERED]);
    }

    // ---------------------------------------------------------------- тексты

    @Test
    void queriesAndReviewsArePublic() {
        var st = ctx.stream("test.texts");
        int typos = 0, latin = 0;
        for (int i = 0; i < 3000; i++) {
            Catalog.Product p = w.cat.product(1 + i * 7 % w.cat.products().size());
            String q = Texts.query(st.at(i), p);
            assertFalse(q.isEmpty() || q.contains("\t") || q.contains("\\"), q);
            assertFalse(PublicNames.isInternal(q), "запрос");
            if (!q.equals(p.kind.nameRu.toLowerCase(java.util.Locale.ROOT))) typos++;
            if (q.chars().allMatch(ch -> ch < 128)) latin++;
            String rv = Texts.review(st.at(i, 1), 1 + i % 5, p, i % 3 == 0);
            assertFalse(PublicNames.isInternal(rv) || rv.contains(p.brand.name()), rv);
        }
        assertTrue(typos > 1000 && latin > 300, "запросов не по виду " + typos + ", латиницей " + latin);
        assertEquals("smartfon", Texts.translit("смартфон"));
        assertEquals("cvfhnajy", Texts.layout("смартфон"));
    }
}
