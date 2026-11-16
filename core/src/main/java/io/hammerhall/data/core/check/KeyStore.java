package io.hammerhall.data.core.check;

import java.util.Arrays;

/**
 * Ключи таблицы, на которую ссылаются, — для поиска по ссылке. Одиночный целый ключ — {@link LongKeys} (плотные id —
 * битовой картой, бит на id). Составной и текстовый: пока ключи приходят по возрастанию (таблица пишется в порядке
 * ключа, составной из двух целых упакован без хеша) — отсортированный массив кусками по 8 байт на ключ и двоичный
 * поиск; первый ключ не по порядку — переход на {@link LongKeys}.
 */
final class KeyStore {
    private static final int CHUNK_BITS = 20;
    private static final int CHUNK = 1 << CHUNK_BITS;

    private long[][] chunks = new long[4][];
    private long n;
    private long last;
    private LongKeys hashed;

    /** @param sorted {@code true} — начать с отсортированного массива; {@code false} — сразу {@link LongKeys} */
    KeyStore(boolean sorted) {
        if (!sorted) hashed = new LongKeys();
    }

    /** Добавить; {@code false} — ключ уже был. */
    boolean add(long k) {
        if (hashed != null) return hashed.add(k);
        if (n > 0 && k <= last) {
            if (k == last) return false;
            toHashed();
            return hashed.add(k);
        }
        int c = (int) (n >>> CHUNK_BITS), i = (int) (n & (CHUNK - 1));
        if (c == chunks.length) chunks = Arrays.copyOf(chunks, c * 2);
        long[] ch = chunks[c];
        if (ch == null) chunks[c] = ch = new long[c == 0 ? 1024 : CHUNK];
        else if (i == ch.length) chunks[c] = ch = Arrays.copyOf(ch, Math.min(CHUNK, ch.length * 2));
        ch[i] = k;
        n++;
        last = k;
        return true;
    }

    private long at(long i) {
        return chunks[(int) (i >>> CHUNK_BITS)][(int) (i & (CHUNK - 1))];
    }

    private void toHashed() {
        LongKeys h = new LongKeys();
        for (long i = 0; i < n; i++) h.add(at(i));
        hashed = h;
        chunks = null;
        n = 0;
    }

    boolean contains(long k) {
        if (hashed != null) return hashed.contains(k);
        long lo = 0, hi = n - 1;
        while (lo <= hi) {
            long mid = (lo + hi) >>> 1;
            long v = at(mid);
            if (v < k) lo = mid + 1;
            else if (v > k) hi = mid - 1;
            else return true;
        }
        return false;
    }

    long size() {
        return hashed != null ? hashed.size() : n;
    }

    /** Оценка занятой памяти, байт. */
    long bytes() {
        if (hashed != null) return hashed.bytes();
        long b = 0;
        for (long[] c : chunks) if (c != null) b += 8L * c.length;
        return b;
    }

    boolean sortedMode() {
        return hashed == null;
    }
}
