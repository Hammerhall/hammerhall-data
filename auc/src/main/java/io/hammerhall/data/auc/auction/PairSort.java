package io.hammerhall.data.auc.auction;

/**
 * Сортировка пар {@code (ключ, значение)} примитивов на месте — по ключу, при равных — по значению (оба по
 * возрастанию). Без объектов: порядок строк для выдачи {@code id} по времени (ставки, лоты) на миллионах строк.
 */
public final class PairSort {
    private PairSort() {
    }

    public static void sort(long[] key, long[] val, int n) {
        if (n > 1) quick(key, val, 0, n - 1);
    }

    private static boolean less(long[] k, long[] v, int i, long pk, long pv) {
        return k[i] < pk || (k[i] == pk && v[i] < pv);
    }

    private static boolean greater(long[] k, long[] v, int i, long pk, long pv) {
        return k[i] > pk || (k[i] == pk && v[i] > pv);
    }

    private static void quick(long[] k, long[] v, int lo, int hi) {
        while (hi - lo > 16) {
            int mid = (lo + hi) >>> 1;
            // медиана трёх — опорный элемент
            if (less(k, v, mid, k[lo], v[lo])) swap(k, v, mid, lo);
            if (less(k, v, hi, k[lo], v[lo])) swap(k, v, hi, lo);
            if (less(k, v, hi, k[mid], v[mid])) swap(k, v, hi, mid);
            long pk = k[mid], pv = v[mid];
            int i = lo, j = hi;
            while (i <= j) {
                while (less(k, v, i, pk, pv)) i++;
                while (greater(k, v, j, pk, pv)) j--;
                if (i <= j) {
                    swap(k, v, i, j);
                    i++;
                    j--;
                }
            }
            if (j - lo < hi - i) {
                quick(k, v, lo, j);
                lo = i;
            } else {
                quick(k, v, i, hi);
                hi = j;
            }
        }
        for (int i = lo + 1; i <= hi; i++) {
            long ck = k[i], cv = v[i];
            int j = i - 1;
            while (j >= lo && greater(k, v, j, ck, cv)) {
                k[j + 1] = k[j];
                v[j + 1] = v[j];
                j--;
            }
            k[j + 1] = ck;
            v[j + 1] = cv;
        }
    }

    private static void swap(long[] k, long[] v, int i, int j) {
        long t = k[i];
        k[i] = k[j];
        k[j] = t;
        t = v[i];
        v[i] = v[j];
        v[j] = t;
    }
}
