package io.hammerhall.data.core.check;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Множество 64-битных ключей без упаковки в {@code Long}: плотные неотрицательные идентификаторы — битовой картой,
 * остальные (хеши составных и текстовых ключей) — открытой адресацией. Для проверки ключей и ссылок на десятках
 * миллионов строк.
 */
public final class LongKeys {
    private static final long EMPTY = Long.MIN_VALUE;
    private final BitSet dense = new BitSet();
    private long[] table = new long[16];
    private int sparse;
    private boolean hasEmptyMarker;
    private long size;

    public LongKeys() {
        Arrays.fill(table, EMPTY);
    }

    /** Добавить; {@code false} — ключ уже был. */
    public boolean add(long k) {
        if (k >= 0 && k < Integer.MAX_VALUE) {
            int i = (int) k;
            if (dense.get(i)) return false;
            dense.set(i);
            size++;
            return true;
        }
        if (k == EMPTY) {
            if (hasEmptyMarker) return false;
            hasEmptyMarker = true;
            size++;
            return true;
        }
        if ((sparse + 1) * 2 > table.length) grow();
        if (!insert(table, k)) return false;
        sparse++;
        size++;
        return true;
    }

    private static boolean insert(long[] t, long k) {
        int mask = t.length - 1;
        int i = (int) (io.hammerhall.data.core.random.Rng.mix64(k) & mask);
        while (t[i] != EMPTY) {
            if (t[i] == k) return false;
            i = (i + 1) & mask;
        }
        t[i] = k;
        return true;
    }

    private void grow() {
        long[] n = new long[table.length * 2];
        Arrays.fill(n, EMPTY);
        for (long k : table) if (k != EMPTY) insert(n, k);
        table = n;
    }

    public boolean contains(long k) {
        if (k >= 0 && k < Integer.MAX_VALUE) return dense.get((int) k);
        if (k == EMPTY) return hasEmptyMarker;
        int mask = table.length - 1;
        int i = (int) (io.hammerhall.data.core.random.Rng.mix64(k) & mask);
        while (table[i] != EMPTY) {
            if (table[i] == k) return true;
            i = (i + 1) & mask;
        }
        return false;
    }

    public long size() {
        return size;
    }

    /** Оценка занятой памяти, байт (битовая карта плотных ключей + таблица остальных). */
    public long bytes() {
        return dense.size() / 8 + 8L * table.length;
    }
}
