package io.hammerhall.data.sto.stock;

import io.hammerhall.data.core.random.Rng;

import java.util.Arrays;

/**
 * Словарь {@code long → int} с открытой адресацией, без упаковки. Ключ {@link Long#MIN_VALUE} зарезервирован.
 * Удаления нет: обнулённые значения остаются (для остатков это и нужно — «был, стал нулём»). Порядок обхода не
 * используется нигде, где он влиял бы на вывод.
 */
final class LongIntMap {
    private static final long EMPTY = Long.MIN_VALUE;
    private long[] keys;
    private int[] vals;
    private int size;
    private int mask;

    LongIntMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(16, expected * 2 - 1)) << 1;
        keys = new long[cap];
        vals = new int[cap];
        Arrays.fill(keys, EMPTY);
        mask = cap - 1;
    }

    private int slot(long k) {
        int i = (int) (Rng.mix64(k) & mask);
        while (keys[i] != EMPTY && keys[i] != k) i = (i + 1) & mask;
        return i;
    }

    int get(long k, int def) {
        int i = slot(k);
        return keys[i] == EMPTY ? def : vals[i];
    }

    boolean containsKey(long k) {
        return keys[slot(k)] != EMPTY;
    }

    void put(long k, int v) {
        int i = slot(k);
        if (keys[i] == EMPTY) {
            keys[i] = k;
            if (++size * 2 > keys.length) {
                vals[i] = v;
                grow();
                return;
            }
        }
        vals[i] = v;
    }

    /** Прибавить и вернуть новое значение (отсутствующий ключ — ноль). */
    int add(long k, int delta) {
        int i = slot(k);
        if (keys[i] == EMPTY) {
            keys[i] = k;
            vals[i] = delta;
            if (++size * 2 > keys.length) grow();
            return delta;
        }
        vals[i] += delta;
        return vals[i];
    }

    int size() {
        return size;
    }

    /** Байт под таблицу — для журнала памяти. */
    long bytes() {
        return (long) keys.length * 12;
    }

    private void grow() {
        long[] ok = keys;
        int[] ov = vals;
        keys = new long[ok.length * 2];
        vals = new int[ok.length * 2];
        Arrays.fill(keys, EMPTY);
        mask = keys.length - 1;
        for (int j = 0; j < ok.length; j++) {
            if (ok[j] == EMPTY) continue;
            int i = slot(ok[j]);
            keys[i] = ok[j];
            vals[i] = ov[j];
        }
    }

    /** Обход непустых пар (порядок — по таблице; только для проверок, не для вывода). */
    void forEach(Visitor v) {
        for (int j = 0; j < keys.length; j++) if (keys[j] != EMPTY) v.visit(keys[j], vals[j]);
    }

    interface Visitor {
        void visit(long key, int value);
    }
}
