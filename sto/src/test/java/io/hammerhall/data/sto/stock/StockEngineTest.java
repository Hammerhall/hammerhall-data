package io.hammerhall.data.sto.stock;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.sto.StoDataset;
import io.hammerhall.data.sto.StoKeys;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.geo.Geo;
import io.hammerhall.data.sto.supply.Suppliers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Движок остатков через его API, без таблиц выпуска (журнал — в памяти): выбор склада, резерв, отбор FIFO, очередь
 * при нехватке и готовность по поступлению, отмена, возврат, FBO-партии продавца, порядок времени, независимая
 * проверка журнала.
 */
class StockEngineTest {
    @TempDir
    static Path tmp;
    static GenContext ctx;

    @BeforeAll
    static void world() throws IOException {
        DatasetContract.assumeInputs(new StoDataset());
        Release.Result r = Cli.run(new StoDataset(), Map.of("profile", "small", "out", tmp.resolve("w").toString(),
                "modules", "pricing,suppliers"));
        assertTrue(r.ok());
        ctx = r.context();
    }

    /** Журнал в памяти. */
    static final class Mem implements StockLedger {
        final List<long[]> mv = new ArrayList<>();
        final List<Kind> kinds = new ArrayList<>();
        final List<long[]> batches = new ArrayList<>();
        int pos;
        boolean closed;

        @Override
        public void movement(long id, long at, int wh, int cell, int pid, int batch, Kind kind, int qty, Ref ref, long refId) {
            mv.add(new long[]{id, at, wh, cell, pid, batch, qty, refId});
            kinds.add(kind);
        }

        @Override
        public void purchaseOrder(int id, int supplierId, int wh, long orderedAt, long expectedAt, long receivedAt,
                                  String status, String purpose) {
            pos++;
        }

        @Override
        public void batch(int id, int poId, int sellerId, int pid, int wh, int qty, long cost, long receivedAt, int ordered) {
            batches.add(new long[]{id, poId, sellerId, pid, wh, qty, cost, receivedAt, ordered});
        }

        @Override
        public void purchaseOrdersDone() {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void reserveFifoBackorderReturnFbo() {
        Catalog cat = ctx.get(StoKeys.CATALOG);
        Geo geo = ctx.get(StoKeys.GEO);
        Suppliers sup = ctx.get(StoKeys.SUPPLIERS);
        LocalDate d0 = ctx.period().from();
        // ходовой товар магазина, у всех поставщиков бренда срок не больше 20 дней
        Catalog.Product p = null;
        for (Catalog.Product x : cat.products()) {
            if (!x.aliveOn(d0) || x.assortment != Catalog.Assortment.ONLY_1P || x.discontinuedPlan == null
                    || x.discontinuedPlan.isBefore(d0.plusDays(200))) continue;
            boolean fast = !sup.ofBrand(x.brand.id()).isEmpty();
            for (Suppliers.Supplier s : sup.ofBrand(x.brand.id())) fast &= s.leadTimeDays() <= 20;
            if (fast && (p == null || x.popularity * cat.lifecycle(x, d0) > p.popularity * cat.lifecycle(p, d0))) p = x;
        }
        assertTrue(p != null);
        final Catalog.Product prod = p;
        int hubWh = geo.ofKind(Geo.Kind.HUB).getFirst().id();
        FboListings fbo = (d, sink) -> sink.accept(new FboListings.Listing(77, prod.id, hubWh, 3.0, 1000_00, 3));
        Mem led = new Mem();
        StockEngine e = new StockEngine(ctx, led, new CatalogForecast(cat, ctx.period(), 0.01), fbo);
        List<long[]> ready = new ArrayList<>();
        e.setListener((r, t) -> ready.add(new long[]{r, t}));
        Geo.City msk = geo.cityByName("Moscow");

        LocalDate d = e.firstDay();
        e.beginDay(d);
        long t = d.atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 10 * 3600;
        StockEngine.Offer o = e.route1P(prod.id, 1, msk.lat(), msk.lon(), t);
        assertTrue(o.inStock(), "ходовой товар есть на старте: " + o);
        int free0 = e.available(o.warehouseId(), prod.id, 0, t);
        long r1 = e.reserve(1, 1, o.warehouseId(), prod.id, 0, 1, t);
        assertTrue(e.isReady(r1));
        assertEquals(free0 - 1, e.available(o.warehouseId(), prod.id, 0, t));
        StockEngine.Picked pk = e.pick(r1, t + 3600);
        assertEquals(1, pk.qty());
        assertTrue(pk.batchId() > 0 && pk.costKop() > 0);
        assertEquals(StockLedger.Kind.PICK, led.kinds.getLast(), "отбор из ячейки");
        assertEquals(-1, led.mv.getLast()[6]);
        long tt = t + 3600;
        assertThrows(IllegalStateException.class, () -> e.route1P(prod.id, 1, msk.lat(), msk.lon(), tt - 60),
                "назад во времени нельзя");
        // нехватка: больше свободного — в очередь; отмена очереди свободное не трогает
        int free1 = e.available(hubWh, prod.id, 0, t + 7200);
        long big = e.reserve(2, 1, hubWh, prod.id, 0, free1 + 1000, t + 7200);
        assertFalse(e.isReady(big));
        e.cancel(big, t + 7300);
        assertEquals(free1, e.available(hubWh, prod.id, 0, t + 7300));
        // очередь, которая дождётся поступления
        long wait = e.reserve(3, 1, hubWh, prod.id, 0, free1 + 3, t + 7400);
        assertFalse(e.isReady(wait));
        // возврат годного — снова в продаже
        int before = e.available(pk.warehouseId(), prod.id, 0, t + 7500);
        e.customerReturn(1, StockLedger.Ref.RETURN, pk.warehouseId(), prod.id, 0, pk.batchId(), 1, true, t + 7500);
        assertEquals(before + 1, e.available(pk.warehouseId(), prod.id, 0, t + 7500));
        e.endDay(d);

        boolean waitPicked = false, fboPicked = false;
        for (int i = 1; i < 90; i++) {
            d = d.plusDays(1);
            e.beginDay(d);
            long noon = d.atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 12 * 3600;
            for (long[] x : ready) {
                if (x[0] == wait && !waitPicked) {
                    StockEngine.Picked w = e.pick(wait, Math.max(noon, e.clock()));
                    assertEquals(free1 + 3, w.qty());
                    waitPicked = true;
                }
            }
            if (!fboPicked) {
                StockEngine.Offer f = e.routeFbo(77, prod.id, 1, msk.lat(), msk.lon(), Math.max(noon + 60, e.clock()));
                if (f.inStock()) {
                    long rf = e.reserve(10, 1, f.warehouseId(), prod.id, 77, 1, Math.max(noon + 120, e.clock()));
                    assertTrue(e.isReady(rf));
                    e.pick(rf, Math.max(noon + 180, e.clock()));
                    long b = led.mv.getLast()[5];
                    assertEquals(77, e.bSeller[(int) b], "партия продавца");
                    fboPicked = true;
                }
            }
            e.endDay(d);
        }
        assertTrue(waitPicked, "очередь дождалась поступления");
        assertTrue(fboPicked, "продавец FBO привёз товар");
        e.finish();
        assertTrue(led.closed && led.pos > 0 && !led.batches.isEmpty());
        boolean sellerBatch = false;
        for (long[] b : led.batches) if (b[2] == 77) sellerBatch = b[1] == 0;
        assertTrue(sellerBatch, "партия FBO — без заказа поставщику");
        CheckReport rep = new CheckReport();
        e.audit.report(rep);
        assertTrue(rep.allOk(), rep.render());
        assertThrows(IllegalStateException.class, e::finish);
    }
}
