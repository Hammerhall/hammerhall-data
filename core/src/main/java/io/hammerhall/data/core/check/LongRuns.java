package io.hammerhall.data.core.check;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Поиск повторов среди 64-битных ключей за 8 байт на ключ — внешней сортировкой: ключи копятся в массиве; полный
 * массив ({@code runSize} ключей) сортируется и сбрасывается на диск отрезком; в конце — слияние отрезков, равные
 * соседи — повтор. Для уникальности там, где повторы не соседние (порядок строк не совпадает с ключом) и множество
 * ключей для ссылок не нужно. Временные файлы удаляет {@link #close()}.
 */
public final class LongRuns implements AutoCloseable {
    private final int runSize;
    private final Path tmpDir;
    private long[] buf;
    private int n;
    private final List<Path> runs = new ArrayList<>();
    private long size;

    /** @param tmpDir папка временных файлов; {@code null} — системная */
    public LongRuns(int runSize, Path tmpDir) {
        if (runSize < 2) throw new IllegalArgumentException("runSize " + runSize);
        this.runSize = runSize;
        this.tmpDir = tmpDir;
        this.buf = new long[Math.min(1024, runSize)];
    }

    public void add(long k) {
        if (n == buf.length) {
            if (buf.length < runSize) buf = Arrays.copyOf(buf, (int) Math.min(runSize, 2L * buf.length));
            else spill();
        }
        buf[n++] = k;
        size++;
    }

    public long size() {
        return size;
    }

    /** Сколько отрезков сброшено на диск. */
    public int spilledRuns() {
        return runs.size();
    }

    private void spill() {
        Arrays.sort(buf, 0, n);
        try {
            Path p = tmpDir == null ? Files.createTempFile("data-keys-", ".bin") : Files.createTempFile(tmpDir, "data-keys-", ".bin");
            runs.add(p);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p), 1 << 16))) {
                for (int i = 0; i < n; i++) out.writeLong(buf[i]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("сброс ключей на диск", e);
        }
        n = 0;
    }

    /**
     * Повторы: каждое второе и следующее вхождение ключа передаётся в {@code onDuplicate}. Вызывается один раз, в
     * конце; возвращает число повторов.
     */
    public long duplicates(LongConsumer onDuplicate) {
        if (runs.isEmpty()) {
            Arrays.sort(buf, 0, n);
            long dups = 0;
            for (int i = 1; i < n; i++) {
                if (buf[i] == buf[i - 1]) {
                    dups++;
                    onDuplicate.accept(buf[i]);
                }
            }
            return dups;
        }
        if (n > 0) spill();
        buf = new long[0];
        int k = runs.size();
        DataInputStream[] in = new DataInputStream[k];
        long[] head = new long[k];
        int[] heap = new int[k];
        int hn = 0;
        long dups = 0;
        try {
            for (int i = 0; i < k; i++) {
                in[i] = new DataInputStream(new BufferedInputStream(Files.newInputStream(runs.get(i)), 1 << 16));
                if (next(in[i], head, i)) heap[hn++] = i;
            }
            for (int i = hn / 2 - 1; i >= 0; i--) down(heap, hn, head, i);
            boolean any = false;
            long prev = 0;
            while (hn > 0) {
                int r = heap[0];
                long v = head[r];
                if (any && v == prev) {
                    dups++;
                    onDuplicate.accept(v);
                }
                any = true;
                prev = v;
                if (!next(in[r], head, r)) heap[0] = heap[--hn];
                down(heap, hn, head, 0);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("слияние ключей", e);
        } finally {
            for (DataInputStream s : in) {
                if (s == null) continue;
                try {
                    s.close();
                } catch (IOException ignored) {
                    // закрываем, что можем
                }
            }
        }
        return dups;
    }

    private static boolean next(DataInputStream in, long[] head, int i) throws IOException {
        try {
            head[i] = in.readLong();
            return true;
        } catch (EOFException e) {
            return false;
        }
    }

    private static void down(int[] heap, int hn, long[] head, int i) {
        while (true) {
            int l = 2 * i + 1, r = l + 1, m = i;
            if (l < hn && head[heap[l]] < head[heap[m]]) m = l;
            if (r < hn && head[heap[r]] < head[heap[m]]) m = r;
            if (m == i) return;
            int t = heap[i];
            heap[i] = heap[m];
            heap[m] = t;
            i = m;
        }
    }

    @Override
    public void close() {
        for (Path p : runs) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // временный файл — не критично
            }
        }
        runs.clear();
        buf = new long[0];
        n = 0;
    }
}
