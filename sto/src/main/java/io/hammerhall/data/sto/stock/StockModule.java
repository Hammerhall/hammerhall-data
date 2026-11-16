package io.hammerhall.data.sto.stock;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.sto.StoKeys;
import io.hammerhall.data.sto.catalog.CatalogModule;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.PriceBook;
import io.hammerhall.data.sto.catalog.PricingModule;
import io.hammerhall.data.sto.geo.GeoModule;
import io.hammerhall.data.sto.supply.SupplierModule;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Склад и поставки: {@code purchase_orders}, {@code batches}, {@code stock_movements}. Ведёт {@link StockEngine} сутки
 * за сутками от источника расхода — пока модуля заказов нет, это {@link DemandDriver}.
 *
 * <p>Когда появится модуль заказов: он объявляет у себя эти три таблицы ({@link #TABLES}) вместе со своими, в своём
 * {@code generate} создаёт {@code StockEngine.open(ctx, прогноз, FBO)} и ведёт его в своём цикле суток (javadoc
 * движка), а проверки зовёт {@link #checkEngine}; этот модуль в {@code StoDataset.modules()} заменяется модулем заказов
 * (две таблицы у двух модулей ядро не пустит).
 */
public final class StockModule implements Module {
    public static final String NAME = "stock";
    public static final List<String> TABLES = List.of("purchase_orders", "batches", "stock_movements");
    private StockEngine engine;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return List.of(GeoModule.NAME, CatalogModule.NAME, PricingModule.NAME, SupplierModule.NAME);
    }

    @Override
    public List<String> tables() {
        return TABLES;
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        Catalog cat = ctx.get(StoKeys.CATALOG);
        CatalogForecast fc = new CatalogForecast(cat, ctx.period(), ctx.profile().scale());
        DemandDriver drv = new DemandDriver(ctx, fc);
        engine = run(ctx, drv, FboListings.NONE);
        ctx.log("stock: " + drv.stats());
    }

    /** Прогнать движок от источника расхода по всем суткам фактов. */
    public static StockEngine run(GenContext ctx, DemandSource src, FboListings fbo) {
        StockEngine e = StockEngine.open(ctx, src.forecast(), fbo);
        e.setListener(src);
        long t0 = System.nanoTime();
        for (LocalDate d = e.firstDay(); !d.isAfter(e.lastDay()); d = d.plusDays(1)) {
            e.beginDay(d);
            long ds = d.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
            src.day(d, ds, ds + 86_400, e);
            e.endDay(d);
            if (d.getDayOfMonth() == 1 && d.getMonthValue() % 3 == 1) {
                Runtime rt = Runtime.getRuntime();
                ctx.log(String.format(java.util.Locale.ROOT, "stock: %s, %.0f с, куча %d МБ", d,
                        (System.nanoTime() - t0) / 1e9, (rt.totalMemory() - rt.freeMemory()) >> 20));
            }
        }
        e.finish();
        ctx.log("stock: " + e.stats() + String.format(java.util.Locale.ROOT, "; проверка журнала ≈ %d МБ",
                e.audit.bytes() >> 20));
        return e;
    }

    @Override
    public void check(GenContext ctx, CheckReport r) {
        checkEngine(ctx, engine, r);
    }

    /** Проверки склада и поставок по прогнанному движку (для модуля, который сам ведёт движок). */
    public static void checkEngine(GenContext ctx, StockEngine e, CheckReport r) {
        if (e == null) {
            r.fail("stock: движок", "не прогнан");
            return;
        }
        e.audit.report(r);
        Catalog cat = ctx.get(StoKeys.CATALOG);
        PriceBook prices = ctx.get(StoKeys.PRICES);
        Period period = ctx.period();
        List<String> cost = new ArrayList<>(), po = new ArrayList<>();
        CostModel cm = new CostModel(cat, prices, ctx.get(StoKeys.SUPPLIERS), ctx.stream("stock.cost"), period);
        for (int b = 1; b <= e.batchTop; b++) {
            if (e.bPo[b] == 0) continue;
            Catalog.Product p = cat.product(e.bProduct[b]);
            long at = e.poOrdered[e.bPo[b]];
            long net = cm.netRetail(p, at, Period.localDate(at, java.time.ZoneOffset.UTC));
            if (e.bCost[b] <= 0 || e.bCost[b] >= net) cost.add("партия " + b + ": " + e.bCost[b] + " при рознице без НДС " + net);
            if (e.bOrdered[b] < e.bQty[b] || e.bQty[b] <= 0) po.add("партия " + b + ": принято " + e.bQty[b] + " из " + e.bOrdered[b]);
        }
        r.expectNone("stock: закупочная цена партии — выше нуля и ниже розницы без НДС на дату заказа", cost,
                "партий " + e.batchTop);
        for (int i = 1; i <= e.poTop; i++) {
            if (e.poExpected[i] < e.poOrdered[i]) po.add("заказ " + i + ": ожидается раньше размещения");
            if (!e.poCancelled(i) && e.poArrival[i] < e.poOrdered[i]) po.add("заказ " + i + ": приёмка раньше размещения");
            if (!period.contains(e.poOrdered[i])) po.add("заказ " + i + ": размещён вне периода");
            // ожидаемая дата — план: не дальше 61 дня от размещения; если окно покрывает 60 дней после «сейчас» (как
            // в выпуске), то и внутри окна (в укороченном прогоне план вправе выходить за его конец)
            if (e.poExpected[i] - e.poOrdered[i] > 61 * 86_400L) po.add("заказ " + i + ": ожидается позже 61 дня");
            if (period.endEpochExclusive() >= period.nowEpoch() + 60 * 86_400L && !period.contains(e.poExpected[i])) {
                po.add("заказ " + i + ": ожидается вне периода");
            }
            if (!e.poCancelled(i) && e.poArrival[i] < e.simEnd() && !period.contains(e.poArrival[i])) {
                po.add("заказ " + i + ": принят вне периода");
            }
        }
        r.expectNone("stock: заказы поставщикам — даты по порядку, недопоставка не больше заказа", po, "заказов " + e.poTop);
    }
}
