package io.hammerhall.data.sto.stock;

import io.hammerhall.data.sto.geo.Geo;

import java.util.ArrayList;
import java.util.List;

/**
 * Ячейки складов и их загрузка (граммы и миллилитры), размещение товара с учётом {@code max_weight_kg} и
 * {@code max_volume_l}. В ячейке может лежать несколько товаров и партий — пока хватает веса и объёма. Зона по
 * габаритам: мелочь — мезонин, среднее — стеллажи, крупная партия — паллеты, крупногабарит — своя зона; зона
 * заполнена — следующая подходящая; места нет нигде — товар остаётся в зоне приёмки (ячейка NULL).
 */
final class Cells {
    static final byte PALLET = 0, SHELF = 1, MEZZ = 2, OVERSIZE = 3, RETURNS = 4;
    private static final byte[][] FALLBACK = {
            {PALLET, OVERSIZE, SHELF},
            {SHELF, PALLET, OVERSIZE, MEZZ},
            {MEZZ, SHELF, PALLET, OVERSIZE},
            {OVERSIZE, PALLET}};

    final int[] maxG, maxMl, usedG, usedMl, cellNode;
    final byte[] zone;
    private final int[][][] byZone;
    private final int[][] cursor;

    /** Результат размещения: ячейки и сколько штук в каждую. */
    static final class Placement {
        int n;
        int[] cell = new int[8];
        int[] qty = new int[8];

        void clear() {
            n = 0;
        }

        void add(int c, int q) {
            if (n == cell.length) {
                cell = java.util.Arrays.copyOf(cell, n * 2);
                qty = java.util.Arrays.copyOf(qty, n * 2);
            }
            cell[n] = c;
            qty[n] = q;
            n++;
        }
    }

    Cells(Geo geo, Network net) {
        int max = 0;
        for (Geo.Cell c : geo.cells()) max = Math.max(max, c.id());
        maxG = new int[max + 1];
        maxMl = new int[max + 1];
        usedG = new int[max + 1];
        usedMl = new int[max + 1];
        cellNode = new int[max + 1];
        zone = new byte[max + 1];
        java.util.Arrays.fill(cellNode, -1);
        byZone = new int[net.n][5][];
        cursor = new int[net.n][5];
        for (int node = 0; node < net.n; node++) {
            List<List<Integer>> z = new ArrayList<>();
            for (int i = 0; i < 5; i++) z.add(new ArrayList<>());
            for (Geo.Cell c : geo.cellsOf(net.whId[node])) {
                byte zz = code(c.zone());
                maxG[c.id()] = c.maxWeightKg() * 1000;
                maxMl[c.id()] = c.maxVolumeL() * 1000;
                cellNode[c.id()] = node;
                zone[c.id()] = zz;
                z.get(zz).add(c.id());
            }
            for (int i = 0; i < 5; i++) byZone[node][i] = z.get(i).stream().mapToInt(Integer::intValue).toArray();
        }
    }

    static byte code(String zone) {
        return switch (zone) {
            case "pallet" -> PALLET;
            case "shelf" -> SHELF;
            case "mezzanine" -> MEZZ;
            case "oversize" -> OVERSIZE;
            case "returns" -> RETURNS;
            default -> throw new IllegalArgumentException("зона " + zone);
        };
    }

    /** Зона по единице товара и размеру партии. */
    static byte zoneFor(int vMl, int wG, int qty) {
        if (vMl > 400_000 || wG > 60_000) return OVERSIZE;
        long tv = (long) qty * vMl;
        if (vMl <= 3_000 && wG <= 2_000 && tv <= 20_000) return MEZZ;
        if (vMl <= 100_000 && tv <= 600_000) return SHELF;
        return PALLET;
    }

    int fits(int cell, int vMl, int wG) {
        long byW = (maxG[cell] - (long) usedG[cell]) / wG;
        long byV = (maxMl[cell] - (long) usedMl[cell]) / vMl;
        long f = Math.min(byW, byV);
        return f <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, f);
    }

    void load(int cell, int vMl, int wG, int qty) {
        usedG[cell] += wG * qty;
        usedMl[cell] += vMl * qty;
    }

    /**
     * Разместить {@code qty} штук на складе: сначала в {@code prefer} (где товар уже лежит), потом по зонам с курсора.
     * Загрузку ячеек не меняет — это делает вызывающий через {@link #load}. Возвращает, сколько не поместилось.
     */
    int place(int node, int vMl, int wG, int qty, int prefer, Placement out) {
        out.clear();
        int rem = qty;
        if (prefer > 0 && cellNode[prefer] == node && zone[prefer] != RETURNS) {
            int f = fits(prefer, vMl, wG);
            int q = Math.min(rem, f);
            if (q > 0) {
                out.add(prefer, q);
                usedG[prefer] += wG * q;
                usedMl[prefer] += vMl * q;
                rem -= q;
            }
        }
        if (rem > 0) {
            for (byte z : FALLBACK[zoneFor(vMl, wG, rem)]) {
                int[] cs = byZone[node][z];
                if (cs.length == 0) continue;
                int start = cursor[node][z] % cs.length;
                for (int k = 0; k < cs.length && rem > 0; k++) {
                    int idx = (start + k) % cs.length;
                    int c = cs[idx];
                    if (c == prefer) continue;
                    int f = fits(c, vMl, wG);
                    if (f <= 0) continue;
                    int q = Math.min(rem, f);
                    out.add(c, q);
                    usedG[c] += wG * q;
                    usedMl[c] += vMl * q;
                    rem -= q;
                    cursor[node][z] = rem == 0 ? idx : idx + 1;
                }
                if (rem == 0) break;
            }
        }
        // загрузку вернём: её проставит вызывающий вместе с остатком
        for (int i = 0; i < out.n; i++) {
            usedG[out.cell[i]] -= wG * out.qty[i];
            usedMl[out.cell[i]] -= vMl * out.qty[i];
        }
        return rem;
    }

    /** Ячейка зоны возвратов для брака (по кругу); 0 — зоны нет. */
    int returnsCell(int node, int vMl, int wG, int qty) {
        int[] cs = byZone[node][RETURNS];
        for (int k = 0; k < cs.length; k++) {
            int idx = (cursor[node][RETURNS] + k) % cs.length;
            if (fits(cs[idx], vMl, wG) >= qty) {
                cursor[node][RETURNS] = idx + 1;
                return cs[idx];
            }
        }
        return 0;
    }

    boolean storage(int cell) {
        return cell > 0 && zone[cell] != RETURNS;
    }
}
