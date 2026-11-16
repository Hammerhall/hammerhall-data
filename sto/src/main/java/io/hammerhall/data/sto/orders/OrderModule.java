package io.hammerhall.data.sto.orders;

import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.sto.catalog.CatalogModule;
import io.hammerhall.data.sto.catalog.PricingModule;
import io.hammerhall.data.sto.customers.CustomerModule;
import io.hammerhall.data.sto.geo.GeoModule;
import io.hammerhall.data.sto.market.MarketModule;
import io.hammerhall.data.sto.stock.CatalogForecast;
import io.hammerhall.data.sto.stock.StockEngine;
import io.hammerhall.data.sto.stock.StockModule;
import io.hammerhall.data.sto.supply.SupplierModule;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.hammerhall.data.core.check.Aggregate.countEq;
import static io.hammerhall.data.core.check.Aggregate.max;
import static io.hammerhall.data.core.check.Aggregate.min;
import static io.hammerhall.data.core.check.Aggregate.sum;

/**
 * Сердце мира — сутки за сутками: сессии и события сайта, заказы, строки, журнал статусов, платежи, отгрузки, возвраты,
 * отзывы — на движке остатков ({@link StockEngine}): модуль ведёт его вместо заглушки склада и пишет его три таблицы
 * ({@link StockModule#TABLES}).
 *
 * <h2>Сутки</h2>
 * <ol>
 *   <li>Кто заказывает: первые заказы суток из модели покупателей и повторные — Пуассон от их активности × календарь
 *       (месяц, день недели, события) × {@link OrderSim#REPEAT_RATE} (нормировка к ≈ 5 млн заказов в {@code full});
 *       момент — по местному времени города покупателя.</li>
 *   <li>Корзина ({@link Picker}): товары любимых отделов или общего спроса по популярности, жизненному циклу и сезону;
 *       «покупают вместе» по правилам с известным лифтом; у кого покупать — {@code Market.choose}; b2b — крупно.</li>
 *   <li>Куда: пункт выдачи (доля покупателя), курьер (зона склада последней мили), иначе почта (в зоне или вне зон).</li>
 *   <li>Оформление в момент заказа: склад по строке — {@code route1P} / {@code routeFbo}; товара нет — ждать (тем реже,
 *       чем дольше) или убрать строку; ни одной строки — брошенная корзина. Цены — на момент, акции — {@link PromoCalc},
 *       НДС — на дату заказа. Резерв — сразу или в очередь склада.</li>
 *   <li>Оплата (карта, СБП, рассрочка у дорогих, сертификат, баллы — с повторами при отказе) или при получении; отмены
 *       до сборки — не оплатил, передумал, не дождался, отказал продавец.</li>
 *   <li>Сборка: все строки готовы — отбор ({@code pick}) на складах, посылки продавцов FBS — их сроком; отгрузка — со
 *       склада последней мили, доставка, получение; возвраты и отзывы — разыграны при получении.</li>
 * </ol>
 *
 * <h2>Последняя миля</h2>
 * Хаб и РЦ зон доставки не имеют. Отгрузка (строка {@code shipments}) — всегда со склада последней мили: для пункта
 * выдачи — склад, к которому он привязан, для курьера и почты — ближайший к адресу городской склад. Товар с хаба, РЦ и
 * другого городского склада приходит туда транзитом (срок — как у переброски сети), посылка продавца FBS — из его
 * города; зона и перевозчик — этого склада. {@code order_items.warehouse_id} — склад отбора (где движение {@code pick}).
 *
 * <h2>«Сейчас»</h2>
 * Факты — до конца последних суток движка ({@code StockEngine.lastDay}); всё после — не записывается: заказы
 * последних дней — со статусом на «сейчас», моменты после него — пусто; обещанная дата и план отгрузки — планы.
 */
public final class OrderModule implements Module {
    public static final String NAME = "orders";
    public static final List<String> TABLES = List.of("sessions", "session_events", "orders", "order_items",
            "order_status_log", "payments", "shipments", "returns", "reviews");

    private StockEngine engine;
    private OrderAudit audit;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return List.of(GeoModule.NAME, CatalogModule.NAME, PricingModule.NAME, SupplierModule.NAME, MarketModule.NAME,
                CustomerModule.NAME);
    }

    @Override
    public List<String> tables() {
        List<String> t = new ArrayList<>(StockModule.TABLES);
        t.addAll(TABLES);
        return t;
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        World w = new World(ctx);
        CatalogForecast fc = new CatalogForecast(w.cat, ctx.period(), w.scale);
        FboAdapter fbo = new FboAdapter(w);
        StockEngine e = StockEngine.open(ctx, fc, fbo);
        TableSink sessions = ctx.streamSink("sessions"), events = ctx.streamSink("session_events");
        TableSink[] sinks = {ctx.streamSink("orders"), ctx.streamSink("order_items"), ctx.streamSink("order_status_log"),
                ctx.streamSink("payments"), ctx.streamSink("shipments"), ctx.streamSink("returns"), ctx.streamSink("reviews")};
        long cutoff = World.dayStart(e.lastDay().plusDays(1));
        OrderAudit a = new OrderAudit(w, cutoff);
        Sessions ss = new Sessions(w, sessions, events, a);
        OrderSim sim = new OrderSim(w, e, sinks, ss, a);
        e.setListener(sim);
        ctx.log("orders: позиций FBO в листингах " + fbo.listings() + ", конец фактов " + e.lastDay());
        long t0 = System.nanoTime();
        for (LocalDate d = e.firstDay(); !d.isAfter(e.lastDay()); d = d.plusDays(1)) {
            e.beginDay(d);
            sim.day(d);
            e.endDay(d);
            if (d.getDayOfMonth() == 1 && d.getMonthValue() % 3 == 1) {
                Runtime rt = Runtime.getRuntime();
                ctx.log(String.format(Locale.ROOT, "orders: %s, %.0f с, заказов %d, куча %d МБ", d, (System.nanoTime() - t0) / 1e9,
                        sim.stOrders, (rt.totalMemory() - rt.freeMemory()) >> 20));
            }
        }
        sim.finish();
        e.finish();
        ctx.log("orders: " + sim.stats());
        ctx.log("orders: сессий " + ss.stSessions + " (без входа " + ss.stAnon + "), событий " + ss.stEvents + ", запросов "
                + ss.stQueries);
        ctx.log("stock: " + e.stats());
        engine = e;
        audit = a;
    }

    @Override
    public void check(GenContext ctx, CheckReport r) {
        StockModule.checkEngine(ctx, engine, r);
        if (audit == null) {
            r.fail("orders: самопроверка", "модуль не прогнан");
            return;
        }
        audit.report(r);
    }

    @Override
    public List<Aggregate> aggregates() {
        return List.of(
                countEq("orders", "status", "delivered"), countEq("orders", "status", "cancelled"),
                countEq("orders", "status", "returned"), countEq("orders", "channel", "phone"),
                countEq("orders", "channel", "app"), countEq("orders", "delivery_method", "pickup"),
                countEq("orders", "delivery_method", "post"), min("orders", "created_at"), max("orders", "created_at"),
                sum("order_items", "qty"), countEq("order_items", "vat_rate", "20"), countEq("order_items", "vat_rate", "22"),
                countEq("order_items", "vat_rate", "0"), max("order_items", "line_no"),
                countEq("order_status_log", "status", "assembling"), countEq("order_status_log", "status", "ready_for_pickup"),
                max("order_status_log", "seq"),
                countEq("payments", "method", "installment"), countEq("payments", "method", "cash_on_delivery"),
                countEq("payments", "status", "failed"), countEq("payments", "status", "refunded"), sum("payments", "installments"),
                countEq("shipments", "status", "delivered"), countEq("shipments", "carrier_id", "6"), sum("shipments", "weight_g"),
                sum("shipments", "boxes"),
                countEq("returns", "reason", "defect"), countEq("returns", "decision", "refund"), sum("returns", "qty"),
                sum("reviews", "score"), countEq("reviews", "score", "1"), countEq("reviews", "score", "5"),
                countEq("sessions", "device", "app"), countEq("sessions", "source", "search"), min("sessions", "started_at"),
                max("sessions", "ended_at"),
                countEq("session_events", "kind", "view"), countEq("session_events", "kind", "add_to_cart"),
                countEq("session_events", "kind", "payment"), max("session_events", "seq"));
    }
}
