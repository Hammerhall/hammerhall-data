package io.hammerhall.data.auc.money;

/**
 * Словарь {@code long → int} на открытой адресации — для естественных ключей (счёт, лот) без объекта на запись. Порядок
 * обхода не нужен и не даётся (детерминизм выпуска от него не зависит). Значение по умолчанию — {@code -1}.
 */
final class LongIntMap {
    private static final long EMPTY = Long.MIN_VALUE;
    private long[] keys;
    private int[] vals;
    private int size;

    LongIntMap(int expected) {
        int cap = 16;
        while (cap < expected * 2) cap <<= 1;
        keys = new long[cap];
        vals = new int[cap];
        java.util.Arrays.fill(keys, EMPTY);
    }

    private static int mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        return (int) k;
    }

    int get(long k) {
        if (k == EMPTY) throw new IllegalArgumentException("ключ");
        int m = keys.length - 1;
        for (int i = mix(k) & m; ; i = (i + 1) & m) {
            if (keys[i] == k) return vals[i];
            if (keys[i] == EMPTY) return -1;
        }
    }

    void put(long k, int v) {
        if (k == EMPTY) throw new IllegalArgumentException("ключ");
        if ((size + 1) * 2 > keys.length) grow();
        int m = keys.length - 1;
        for (int i = mix(k) & m; ; i = (i + 1) & m) {
            if (keys[i] == k) {
                vals[i] = v;
                return;
            }
            if (keys[i] == EMPTY) {
                keys[i] = k;
                vals[i] = v;
                size++;
                return;
            }
        }
    }

    int size() {
        return size;
    }

    private void grow() {
        long[] ok = keys;
        int[] ov = vals;
        keys = new long[ok.length * 2];
        vals = new int[ok.length * 2];
        java.util.Arrays.fill(keys, EMPTY);
        size = 0;
        for (int i = 0; i < ok.length; i++) if (ok[i] != EMPTY) put(ok[i], ov[i]);
    }
}
