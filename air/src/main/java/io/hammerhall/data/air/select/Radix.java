package io.hammerhall.data.air.select;

import java.util.Arrays;

/**
 * Поразрядная сортировка пар {@code (ключ, значение)} по ключу — массивы {@code long} на десятки миллионов элементов:
 * за линейное время и без объектов. Ключи — неотрицательные (коды {@link KeyCodec}, упакованные моменты), поэтому
 * порядок без знака совпадает с обычным. Сортировка устойчива: при равных ключах значения — в прежнем порядке.
 */
public final class Radix {
    private Radix() {
    }

    /** Отсортировать {@code key[0, n)} по возрастанию, переставляя {@code val} вместе с ним. */
    public static void sort(long[] key, long[] val, int n) {
        for (int i = 0; i < n; i++) if (key[i] < 0) throw new IllegalArgumentException("отрицательный ключ " + key[i]);
        long[] k2 = new long[n], v2 = new long[n];
        int[] count = new int[(1 << 16) + 1];
        long[] sk = key, sv = val, dk = k2, dv = v2;
        for (int shift = 0; shift < 64; shift += 16) {
            Arrays.fill(count, 0);
            for (int i = 0; i < n; i++) count[(int) ((sk[i] >>> shift) & 0xffff) + 1]++;
            boolean trivial = false;
            for (int b = 1; b < count.length && !trivial; b++) trivial = count[b] == n;
            if (trivial || n == 0) continue;
            for (int b = 1; b < count.length; b++) count[b] += count[b - 1];
            for (int i = 0; i < n; i++) {
                int p = count[(int) ((sk[i] >>> shift) & 0xffff)]++;
                dk[p] = sk[i];
                dv[p] = sv[i];
            }
            long[] t = sk;
            sk = dk;
            dk = t;
            t = sv;
            sv = dv;
            dv = t;
        }
        if (sk != key) {
            System.arraycopy(sk, 0, key, 0, n);
            System.arraycopy(sv, 0, val, 0, n);
        }
    }
}
