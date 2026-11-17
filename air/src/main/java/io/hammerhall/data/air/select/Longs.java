package io.hammerhall.data.air.select;

import java.util.Arrays;

/** Растущий массив {@code long} (без упаковки в {@code Long}): ключи больших таблиц выгрузки. */
public final class Longs {
    private long[] a = new long[1 << 10];
    private int n;

    public void add(long v) {
        if (n == a.length) {
            if (a.length >= Integer.MAX_VALUE - 16) throw new IllegalStateException("слишком много значений");
            a = Arrays.copyOf(a, (int) Math.min(Integer.MAX_VALUE - 16L, a.length + (a.length >> 1) + 16L));
        }
        a[n++] = v;
    }

    public int size() {
        return n;
    }

    /** Массив ровно по размеру; растущий массив после этого пуст. */
    public long[] take() {
        long[] out = n == a.length ? a : Arrays.copyOf(a, n);
        a = new long[16];
        n = 0;
        return out;
    }
}
