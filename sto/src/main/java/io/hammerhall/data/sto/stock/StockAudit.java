package io.hammerhall.data.sto.stock;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.Planted;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.geo.Geo;
import io.hammerhall.data.sto.stock.StockLedger.Kind;
import io.hammerhall.data.sto.stock.StockLedger.Ref;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Независимая проверка журнала движений — на лету, по каждой записанной строке и своими структурами (не состоянием
 * движка): остатки по (склад, ячейка, партия) и по (склад, партия) не ниже нуля, кроме заложенных; FIFO отбора и
 * переброски; приёмка — по своему заказу и не раньше его; партия в пути — без движений; переброски сходятся парами
 * (расход на родителе — приход на ребёнке, в тех же партиях и штуках); размещение — парами «−приёмка, +ячейки»;
 * загрузка ячеек — в пределах веса и объёма; в конце — сверка остатков журнала с движком.
 */
final class StockAudit {
    private final StockEngine e;
    private final Planted planted;
    private final int[] cellMaxG, cellMaxMl;
    private final boolean[] cellReturns;
    private final int[] cellWh;
    private final long[] usedG, usedMl;
    private final int[] vMl, wG;

    private final LongIntMap pos = new LongIntMap(1 << 16);
    private final LongIntMap agg = new LongIntMap(1 << 16);
    private final LongIntMap pick = new LongIntMap(1 << 16);
    private final LongIntMap fifoHead = new LongIntMap(1 << 14);
    private int[] fBatch = new int[1 << 14], fNext = new int[1 << 14];
    private int fTop, fFree = -1;
    private long[] age = new long[1 << 14];
    private final BitSet moved = new BitSet();
    private long lastId, lastAt = Long.MIN_VALUE;
    private long rows;

    private static final class Doc {
        int src = -1, dst = -1;
        long outAt = -1, inAt = -1;
        long sumOut, sumIn, hashOut, hashIn;
        int bad;
    }

    private final Map<Long, Doc> docs = new TreeMap<>();
    // текущая группа размещения
    private long gAt = Long.MIN_VALUE, gRef;
    private int gNode = -1, gBatch, gNeg, gRows;
    private long gSum;
    private Ref gRefType;

    private final Map<String, List<String>> bad = new LinkedHashMap<>();
    private final Map<String, Long> badCount = new LinkedHashMap<>();
    private long fifoChecks, pairsOk, receipts;
    private boolean finished;

    static final String C_NEG = "остаток (склад, ячейка, товар, партия) не ниже нуля, кроме заложенных";
    static final String C_NEG_TOTAL = "остаток (склад, товар, партия) не ниже нуля, кроме заложенных";
    static final String C_FIFO = "отбор и переброска — FIFO по партиям";
    static final String C_RECEIPT = "приёмка — по своему заказу, на свой склад, не раньше заказа";
    static final String C_TRANSIT = "партия в пути — без приёмки и движений";
    static final String C_PAIRS = "переброски сходятся парами: расход на родителе = приход на ребёнке";
    static final String C_PUTAWAY = "размещение — парами «−зона приёмки, +ячейки»";
    static final String C_CAPACITY = "загрузка ячеек — в пределах max_weight_kg и max_volume_l";
    static final String C_ORDER = "журнал по времени и по id; ячейка — своего склада; партия — своего товара";
    static final String C_STATE = "остатки журнала = остатки движка";

    StockAudit(StockEngine e, Geo geo, Catalog cat, Planted planted) {
        this.e = e;
        this.planted = planted;
        int max = 0;
        for (Geo.Cell c : geo.cells()) max = Math.max(max, c.id());
        cellMaxG = new int[max + 1];
        cellMaxMl = new int[max + 1];
        cellReturns = new boolean[max + 1];
        cellWh = new int[max + 1];
        usedG = new long[max + 1];
        usedMl = new long[max + 1];
        for (Geo.Cell c : geo.cells()) {
            cellMaxG[c.id()] = c.maxWeightKg() * 1000;
            cellMaxMl[c.id()] = c.maxVolumeL() * 1000;
            cellReturns[c.id()] = c.zone().equals("returns");
            cellWh[c.id()] = c.warehouseId();
        }
        int maxP = 0;
        for (Catalog.Product p : cat.products()) maxP = Math.max(maxP, p.id);
        vMl = new int[maxP + 1];
        wG = new int[maxP + 1];
        for (Catalog.Product p : cat.products()) {
            vMl[p.id] = (int) Math.max(1, (long) p.lengthMm * p.widthMm * p.heightMm / 1000);
            wG[p.id] = Math.max(1, p.weightG);
        }
        for (String c : List.of(C_NEG, C_NEG_TOTAL, C_FIFO, C_RECEIPT, C_TRANSIT, C_PAIRS, C_PUTAWAY, C_CAPACITY, C_ORDER,
                C_STATE)) {
            bad.put(c, new ArrayList<>());
            badCount.put(c, 0L);
        }
    }

    static long posKey(int node, int cell, int batch) {
        return ((long) node << 50) | ((long) cell << 26) | batch;
    }

    static long aggKey(int node, int batch) {
        return ((long) node << 50) | (1L << 49) | batch;
    }

    private static long fifoKey(int node, int pid, int seller) {
        return ((long) node << 50) | ((long) seller << 24) | pid;
    }

    private void fail(String check, String what) {
        badCount.merge(check, 1L, Long::sum);
        List<String> l = bad.get(check);
        if (l.size() < 5) l.add(what);
    }

    private boolean older(int b1, int b2) {
        return age[b1] != age[b2] ? age[b1] < age[b2] : b1 < b2;
    }

    void movement(long id, long at, int node, int cell, int pid, int batch, Kind kind, int qty, Ref ref, long refId) {
        rows++;
        if (id <= lastId || at < lastAt) fail(C_ORDER, "движение " + id + " не по порядку");
        lastId = id;
        lastAt = at;
        if (cell > 0 && cellWh[cell] != e.net.whId[node]) fail(C_ORDER, "движение " + id + ": ячейка " + cell + " чужого склада");
        if (batch > 0 && e.bProduct[batch] != pid) fail(C_ORDER, "движение " + id + ": партия " + batch + " другого товара");
        if (batch > 0) moved.set(batch);
        if (kind != Kind.MOVE) closeGroup();
        if (batch >= age.length) age = Arrays.copyOf(age, Math.max(batch + 1, age.length * 2));

        switch (kind) {
            case RECEIPT -> {
                receipts++;
                if (age[batch] == 0) age[batch] = at;
                else if (age[batch] != at) fail(C_RECEIPT, "партия " + batch + " принята дважды");
                if (e.bWh[batch] != e.net.whId[node]) fail(C_RECEIPT, "партия " + batch + " принята не на свой склад");
                if (ref == Ref.PURCHASE_ORDER) {
                    if (refId < 1 || refId > e.poTop || e.bPo[batch] != refId) fail(C_RECEIPT, "партия " + batch + " — не по заказу " + refId);
                    else if (at < e.poOrdered[(int) refId]) fail(C_RECEIPT, "заказ " + refId + ": приёмка раньше размещения");
                } else if (ref != Ref.NONE || e.bSeller[batch] == 0) {
                    fail(C_RECEIPT, "партия " + batch + ": приёмка без заказа");
                }
                if (qty <= 0) fail(C_RECEIPT, "приёмка " + id + " без штук");
            }
            case MOVE -> {
                long same = at;
                if (same != gAt || node != gNode || batch != gBatch || ref != gRefType || refId != gRef) {
                    closeGroup();
                    gAt = at;
                    gNode = node;
                    gBatch = batch;
                    gRefType = ref;
                    gRef = refId;
                }
                gRows++;
                gSum += qty;
                if (cell == 0) {
                    if (qty < 0) gNeg++;
                    else fail(C_PUTAWAY, "движение " + id + ": приход в зону приёмки");
                } else if (qty <= 0) {
                    fail(C_PUTAWAY, "движение " + id + ": расход из ячейки при размещении");
                }
            }
            case TRANSFER_OUT, TRANSFER_IN -> {
                if (ref != Ref.TRANSFER) {
                    fail(C_PAIRS, "движение " + id + " без ссылки на переброску");
                    break;
                }
                Doc d = docs.computeIfAbsent(refId, k -> new Doc());
                long h = (long) qty * Rng.mix64(batch * 0x9e3779b97f4a7c15L + 17);
                if (kind == Kind.TRANSFER_OUT) {
                    if (d.src < 0) d.src = node;
                    if (d.outAt < 0) d.outAt = at;
                    if (d.src != node || d.outAt != at || qty >= 0) d.bad++;
                    d.sumOut += qty;
                    d.hashOut += h;
                } else {
                    if (d.dst < 0) d.dst = node;
                    if (d.inAt < 0) d.inAt = at;
                    if (d.dst != node || d.inAt != at || qty <= 0) d.bad++;
                    d.sumIn += qty;
                    d.hashIn += h;
                }
            }
            default -> {
            }
        }

        // FIFO: до расхода не должно быть старшей партии с остатком (того же товара и владельца, вне зоны возвратов)
        if (kind == Kind.PICK || kind == Kind.SHIP || kind == Kind.TRANSFER_OUT) {
            fifoChecks++;
            long fk = fifoKey(node, pid, e.bSeller[batch]);
            int h = fifoHead.get(fk, -1);
            if (h >= 0 && fBatch[h] != batch && older(fBatch[h], batch)) {
                fail(C_FIFO, "движение " + id + ": взята партия " + batch + ", а старшая " + fBatch[h] + " лежит");
            }
            if (kind == Kind.PICK && cell == 0) fail(C_FIFO, "движение " + id + ": отбор (pick) без ячейки");
            if (kind == Kind.SHIP && cell != 0) fail(C_FIFO, "движение " + id + ": ship из ячейки");
        }

        // остатки
        long pk = posKey(node, cell, batch);
        int pv = pos.add(pk, qty);
        if (pv < 0 && !planted.has(StockEngine.PLANT_CELL, pk)) {
            fail(C_NEG, "движение " + id + ": склад " + e.net.whId[node] + ", ячейка " + cell + ", партия " + batch + " → " + pv);
        }
        long ak = aggKey(node, batch);
        int av = agg.add(ak, qty);
        if (av < 0 && !planted.has(StockEngine.PLANT_TOTAL, ak)) {
            fail(C_NEG_TOTAL, "движение " + id + ": склад " + e.net.whId[node] + ", партия " + batch + " → " + av);
        }
        if (cell == 0 || !cellReturns[cell]) {
            int before = pick.get(ak, 0);
            int after = pick.add(ak, qty);
            long fk = fifoKey(node, pid, e.bSeller[batch]);
            if (before <= 0 && after > 0) fifoInsert(fk, batch);
            else if (before > 0 && after <= 0) fifoRemove(fk, batch);
        }
        if (cell > 0) {
            usedG[cell] += (long) qty * wG[pid];
            usedMl[cell] += (long) qty * vMl[pid];
            if (qty > 0 && (usedG[cell] > cellMaxG[cell] || usedMl[cell] > cellMaxMl[cell])) {
                fail(C_CAPACITY, "движение " + id + ": ячейка " + cell + " — " + usedG[cell] / 1000 + " кг / "
                        + usedMl[cell] / 1000 + " л");
            }
        }
    }

    private void fifoInsert(long fk, int batch) {
        int n;
        if (fFree >= 0) {
            n = fFree;
            fFree = fNext[n];
        } else {
            n = fTop++;
            if (n == fBatch.length) {
                fBatch = Arrays.copyOf(fBatch, n * 2);
                fNext = Arrays.copyOf(fNext, n * 2);
            }
        }
        fBatch[n] = batch;
        int prev = -1, cur = fifoHead.get(fk, -1);
        while (cur >= 0 && older(fBatch[cur], batch)) {
            prev = cur;
            cur = fNext[cur];
        }
        fNext[n] = cur;
        if (prev < 0) fifoHead.put(fk, n);
        else fNext[prev] = n;
    }

    private void fifoRemove(long fk, int batch) {
        int prev = -1, cur = fifoHead.get(fk, -1);
        while (cur >= 0 && fBatch[cur] != batch) {
            prev = cur;
            cur = fNext[cur];
        }
        if (cur < 0) return;
        if (prev < 0) fifoHead.put(fk, fNext[cur]);
        else fNext[prev] = fNext[cur];
        fNext[cur] = fFree;
        fFree = cur;
    }

    private void closeGroup() {
        if (gRows == 0) return;
        if (gSum != 0 || gNeg != 1) {
            fail(C_PUTAWAY, "размещение партии " + gBatch + " в " + gAt + ": сумма " + gSum + ", расходов из зоны приёмки " + gNeg);
        } else {
            pairsOk++;
        }
        gRows = 0;
        gSum = 0;
        gNeg = 0;
        gAt = Long.MIN_VALUE;
    }

    /** После записи: переброски, партии в пути, сверка с движком. */
    void finish() {
        closeGroup();
        long end = e.simEnd();
        for (Map.Entry<Long, Doc> x : docs.entrySet()) {
            Doc d = x.getValue();
            if (d.bad > 0) {
                fail(C_PAIRS, "переброска " + x.getKey() + ": строки с разным складом, моментом или знаком");
            } else if (d.inAt < 0) {
                if (d.outAt + 16 * 86_400L < end) fail(C_PAIRS, "переброска " + x.getKey() + " ушла и не пришла");
            } else if (d.sumOut + d.sumIn != 0 || d.hashOut + d.hashIn != 0) {
                fail(C_PAIRS, "переброска " + x.getKey() + ": ушло " + -d.sumOut + ", пришло " + d.sumIn);
            } else if (d.inAt < d.outAt || e.net.parent[d.dst] != d.src) {
                fail(C_PAIRS, "переброска " + x.getKey() + ": не к ребёнку или приход раньше расхода");
            } else {
                pairsOk++;
            }
        }
        for (int b = 1; b <= e.batchTop; b++) {
            if (e.bRecv[b] < 0) {
                if (moved.get(b)) fail(C_TRANSIT, "партия " + b + " в пути, но с движениями");
            } else if (b < age.length && age[b] != e.bRecv[b]) {
                fail(C_TRANSIT, "партия " + b + ": момент приёмки в журнале и в партии разные");
            }
        }
        for (int po = 1; po <= e.poTop; po++) {
            if (e.poCancelled(po) && e.poArrival[po] != Long.MAX_VALUE) fail(C_RECEIPT, "отменённый заказ " + po + " с приёмкой");
        }
        // сверка: остатки движка (вне зоны возвратов) = журнал
        LongIntMap engine = new LongIntMap(1 << 16);
        e.forEachPosition((node, cell, product, batch, qty) -> {
            if (cell == 0 || !cellReturns[cell]) engine.add(aggKey(node, batch), qty);
        });
        long[] diff = new long[2];
        engine.forEach((k, v) -> {
            if (pick.get(k, 0) != v) diff[0]++;
        });
        pick.forEach((k, v) -> {
            if (v != 0 && engine.get(k, 0) != v) diff[1]++;
        });
        if (diff[0] + diff[1] > 0) fail(C_STATE, "расходится " + diff[0] + " + " + diff[1] + " пар (склад, партия)");
        finished = true;
    }

    void report(CheckReport r) {
        if (!finished) {
            r.fail("stock: проверка журнала", "движок не завершён");
            return;
        }
        for (Map.Entry<String, List<String>> x : bad.entrySet()) {
            long n = badCount.get(x.getKey());
            String detail = switch (x.getKey()) {
                case C_FIFO -> "проверено " + fifoChecks + " расходов";
                case C_PAIRS -> "переброски и размещения сходятся: " + pairsOk;
                case C_RECEIPT -> "приёмок " + receipts;
                default -> "движений " + rows;
            };
            if (n == 0) r.ok("stock: " + x.getKey(), detail);
            else r.fail("stock: " + x.getKey(), n + " нарушений; например: " + String.join("; ", x.getValue()));
        }
    }

    long bytes() {
        return pos.bytes() + agg.bytes() + pick.bytes() + fifoHead.bytes() + (long) fBatch.length * 8 + age.length * 8L
                + usedG.length * 16L;
    }
}
