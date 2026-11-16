package io.hammerhall.data.sto.catalog;

import java.util.Arrays;

/**
 * Цены на любой момент — ровно то, что записано в {@code prices}: у товара — периоды {@code [from_i, from_{i+1})},
 * последний — до снятия или без конца. {@link #priceAt} — двоичный поиск.
 */
public final class PriceBook {
    private final long[][] from;
    private final long[][] price;
    private final long[] end;

    /** @param maxId наибольший id товара */
    public PriceBook(int maxId) {
        from = new long[maxId + 1][];
        price = new long[maxId + 1][];
        end = new long[maxId + 1];
        Arrays.fill(end, Long.MAX_VALUE);
    }

    void set(int productId, long[] from, long[] price, long end) {
        this.from[productId] = from;
        this.price[productId] = price;
        this.end[productId] = end;
    }

    /** Цена с НДС, копеек, на момент (секунды Unix); −1 — товар в этот момент не продаётся. */
    public long priceAt(int productId, long epochSecond) {
        long[] f = from[productId];
        if (f == null || epochSecond < f[0] || epochSecond >= end[productId]) return -1;
        int i = Arrays.binarySearch(f, epochSecond);
        if (i < 0) i = -i - 2;
        return price[productId][i];
    }

    /** Число периодов цены товара. */
    public int periods(int productId) {
        long[] f = from[productId];
        return f == null ? 0 : f.length;
    }

    public long periodFrom(int productId, int i) {
        return from[productId][i];
    }

    public long periodPrice(int productId, int i) {
        return price[productId][i];
    }

    /** Конец последнего периода; {@link Long#MAX_VALUE} — без конца в периоде данных. */
    public long end(int productId) {
        return end[productId];
    }
}
