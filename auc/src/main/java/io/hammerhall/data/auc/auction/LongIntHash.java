package io.hammerhall.data.auc.auction;

import java.util.Arrays;

/**
 * Отображение {@code long → int} открытой адресацией, без объектов на запись (ключ лота → {@code lots.id} на ≈ 1,3 млн
 * лотов). Только поиск по ключу: порядок обхода нигде не используется (детерминизм не зависит от хеша).
 */
public final class LongIntHash {
    private static final long EMPTY = Long.MIN_VALUE;
    private long[] keys;
    private int[] vals;
    private int size, mask;

    public LongIntHash(int expected) {
        int cap = Integer.highestOneBit(Math.max(16, expected * 2 - 1)) << 1;
        keys = new long[cap];
        vals = new int[cap];
        Arrays.fill(keys, EMPTY);
        mask = cap - 1;
    }

    private static int mix(long k) {
        long h = k * 0x9e3779b97f4a7c15L;
        return (int) (h ^ (h >>> 32));
    }

    public void put(long k, int v) {
        if (k == EMPTY) throw new IllegalArgumentException("ключ " + k);
        if (2 * (size + 1) > keys.length) grow();
        int i = mix(k) & mask;
        while (keys[i] != EMPTY && keys[i] != k) i = (i + 1) & mask;
        if (keys[i] == EMPTY) size++;
        keys[i] = k;
        vals[i] = v;
    }

    /** Значение по ключу; {@code missing} — нет ключа. */
    public int get(long k, int missing) {
        int i = mix(k) & mask;
        while (keys[i] != EMPTY) {
            if (keys[i] == k) return vals[i];
            i = (i + 1) & mask;
        }
        return missing;
    }

    public int size() {
        return size;
    }

    private void grow() {
        long[] ok = keys;
        int[] ov = vals;
        keys = new long[ok.length * 2];
        vals = new int[ok.length * 2];
        Arrays.fill(keys, EMPTY);
        mask = keys.length - 1;
        size = 0;
        for (int i = 0; i < ok.length; i++) if (ok[i] != EMPTY) put(ok[i], ov[i]);
    }
}
