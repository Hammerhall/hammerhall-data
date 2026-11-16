package io.hammerhall.data.core.check;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongConsumer;

/**
 * Список 64-битных значений на диске: дописывается кусками, читается один раз целиком. Для отложенных ссылок,
 * которых больше, чем разумно держать в памяти (целевая таблица пишется позже ссылок на неё).
 */
public final class LongSpill implements AutoCloseable {
    private final Path tmpDir;
    private Path file;
    private DataOutputStream out;
    private long size;

    /** @param tmpDir папка временных файлов; {@code null} — системная */
    public LongSpill(Path tmpDir) {
        this.tmpDir = tmpDir;
    }

    public void append(long[] a, int from, int to) {
        try {
            if (out == null) {
                file = tmpDir == null ? Files.createTempFile("data-refs-", ".bin") : Files.createTempFile(tmpDir, "data-refs-", ".bin");
                out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
            }
            for (int i = from; i < to; i++) out.writeLong(a[i]);
        } catch (IOException e) {
            throw new UncheckedIOException("сброс ссылок на диск", e);
        }
        size += to - from;
    }

    public long size() {
        return size;
    }

    /** Прочитать всё по порядку записи (после этого список пуст и файл удалён). */
    public void drain(LongConsumer c) {
        if (file == null) return;
        try {
            out.close();
            out = null;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
                for (long i = 0; i < size; i++) c.accept(in.readLong());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("чтение ссылок с диска", e);
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
            // закрываем, что можем
        }
        out = null;
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // временный файл — не критично
            }
        }
        file = null;
        size = 0;
    }
}
