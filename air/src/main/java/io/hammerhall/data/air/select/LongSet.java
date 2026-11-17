package io.hammerhall.data.air.select;

import io.hammerhall.data.core.random.Rng;

/**
 * Множество кодов ключей ({@link KeyCodec}) открытой адресацией: 8 байт на ячейку, заполнение не больше 3/4.
 * Ноль — пустая ячейка (кодов, равных нулю, не бывает). Размер известен заранее — без перестроек.
 */
public final class LongSet {
    private final long[] table;
    private final int mask;
    private long size;

    /** @param expected сколько ключей ляжет (больше — ошибка) */
    public LongSet(long expected) {
        long cap = 16;
        while (cap * 3 < expected * 4 + 4) cap <<= 1;
        if (cap > 1 << 30) throw new IllegalArgumentException("слишком много ключей: " + expected);
        table = new long[(int) cap];
        mask = (int) cap - 1;
    }

    public boolean add(long k) {
        if (k == 0) throw new IllegalArgumentException("ключ 0");
        int i = (int) (Rng.mix64(k) & mask);
        while (table[i] != 0) {
            if (table[i] == k) return false;
            i = (i + 1) & mask;
        }
        if ((size + 1) * 4 > (long) table.length * 3) throw new IllegalStateException("множество переполнено: " + size);
        table[i] = k;
        size++;
        return true;
    }

    public boolean contains(long k) {
        int i = (int) (Rng.mix64(k) & mask);
        while (table[i] != 0) {
            if (table[i] == k) return true;
            i = (i + 1) & mask;
        }
        return false;
    }

    public long size() {
        return size;
    }

    public long bytes() {
        return 8L * table.length;
    }
}
