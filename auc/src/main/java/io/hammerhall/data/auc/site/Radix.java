package io.hammerhall.data.auc.site;

import java.util.Arrays;

/**
 * Устойчивая поразрядная сортировка пар «ключ — значение» по беззнаковому ключу: примитивные массивы, без объектов
 * и без сравнений; равные ключи сохраняют исходный порядок (поэтому порядок генерации — последний ключ сортировки).
 */
final class Radix {
    private Radix() {
    }

    /** Отсортировать первые {@code n} пар по {@code keys}; {@code bits} — сколько младших бит ключа значимы. */
    static void sort(long[] keys, int[] vals, int n, int bits) {
        if (n < 2) return;
        if (n < 64) {
            insertion(keys, vals, n);
            return;
        }
        long[] k2 = new long[n];
        int[] v2 = new int[n];
        long[] ka = keys, kb = k2;
        int[] va = vals, vb = v2;
        int[] count = new int[1 << 11];
        for (int shift = 0; shift < bits; shift += 11) {
            Arrays.fill(count, 0);
            for (int i = 0; i < n; i++) count[(int) ((ka[i] >>> shift) & 0x7ff)]++;
            if (count[(int) ((ka[0] >>> shift) & 0x7ff)] == n) continue;
            int sum = 0;
            for (int d = 0; d < count.length; d++) {
                int c = count[d];
                count[d] = sum;
                sum += c;
            }
            for (int i = 0; i < n; i++) {
                int d = (int) ((ka[i] >>> shift) & 0x7ff);
                int p = count[d]++;
                kb[p] = ka[i];
                vb[p] = va[i];
            }
            long[] tk = ka;
            ka = kb;
            kb = tk;
            int[] tv = va;
            va = vb;
            vb = tv;
        }
        if (ka != keys) {
            System.arraycopy(ka, 0, keys, 0, n);
            System.arraycopy(va, 0, vals, 0, n);
        }
    }

    private static void insertion(long[] k, int[] v, int n) {
        for (int i = 1; i < n; i++) {
            long kk = k[i];
            int vv = v[i];
            int j = i - 1;
            while (j >= 0 && Long.compareUnsigned(k[j], kk) > 0) {
                k[j + 1] = k[j];
                v[j + 1] = v[j];
                j--;
            }
            k[j + 1] = kk;
            v[j + 1] = vv;
        }
    }

    /** Перестановка индексов {@code 0..n-1} по ключам (ключи не меняются). */
    static int[] order(long[] keys, int n, int bits) {
        long[] k = Arrays.copyOf(keys, n);
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        sort(k, idx, n, bits);
        return idx;
    }

    /** Первый индекс в возрастающем {@code a[from..to)} со значением ≥ {@code x}. */
    static int lowerBound(int[] a, int from, int to, long x) {
        int lo = from, hi = to;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (a[m] < x) lo = m + 1;
            else hi = m;
        }
        return lo;
    }

    /** Первый индекс в возрастающем {@code a[from..to)} со значением ≥ {@code x}. */
    static int lowerBound(long[] a, int from, int to, long x) {
        int lo = from, hi = to;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (a[m] < x) lo = m + 1;
            else hi = m;
        }
        return lo;
    }
}
