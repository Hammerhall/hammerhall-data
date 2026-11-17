package io.hammerhall.data.core.io;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Файл {@code <таблица>.tsv.gz}: UTF-8, строки через {@code \n}, gzip без отметки времени и имени файла (заголовок
 * gzip у JDK — нулевой MTIME). Сжатие и подсчёт sha256 — в своём потоке: производитель строк не ждёт deflate, на двух
 * ядрах это почти вдвое быстрее. Порядок байт файла от этого не зависит.
 *
 * <p>Заголовок gzip — {@link Header}: по умолчанию тот, что пишет {@code GZIPOutputStream}; набор, выпуск которого
 * когда-то собран на CPython, может писать заголовок CPython — тогда при том же содержимом и уровне сжатия совпадают и
 * сами байты файла (deflate тот же — zlib).
 */
public final class GzipTsvFile implements Closeable {
    /** Итог файла для манифеста. */
    public record Info(String name, long rows, long bytes, String sha256, String sha256Tsv) {
    }

    /** Заголовок gzip: 10 байт перед потоком deflate; MTIME — ноль в обоих. */
    public enum Header {
        /** Как у {@code java.util.zip.GZIPOutputStream}: XFL = 0, OS = 255. */
        JDK,
        /**
         * Как у CPython {@code gzip.GzipFile(filename='', mtime=0)}: XFL = 2 при уровне 9, 4 при уровне 1, иначе 0;
         * OS = 255. Сам поток — {@code Deflater(level, nowrap)}, хвост — CRC32 и длина, как у {@code GZIPOutputStream}.
         */
        CPYTHON
    }

    private record Chunk(byte[] data, int len) {
    }

    private static final int CHUNK = 1 << 20;
    private static final Chunk END = new Chunk(null, -1);

    private final Path path;
    private final BlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(6);
    private final Thread worker;
    private final MessageDigest plainSha;
    private final MessageDigest gzSha;
    private volatile Throwable failure;
    private byte[] buf = new byte[CHUNK];
    private int pos;
    private long lines;
    private boolean closed;

    public GzipTsvFile(Path path, int level) throws IOException {
        this(path, level, Header.JDK);
    }

    public GzipTsvFile(Path path, int level, Header header) throws IOException {
        this.path = path;
        try {
            plainSha = MessageDigest.getInstance("SHA-256");
            gzSha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        OutputStream file = new BufferedOutputStream(Files.newOutputStream(path), 1 << 16);
        OutputStream digested = new DigestOutputStream(file, gzSha);
        OutputStream gz = header == Header.JDK
                ? new GZIPOutputStream(digested, 1 << 16) {
                    {
                        def.setLevel(level);
                    }
                }
                : new CPythonGzip(digested, level);
        worker = Thread.ofPlatform().name("gz-" + path.getFileName()).daemon(true).unstarted(() -> {
            try (gz) {
                while (true) {
                    Chunk c = queue.take();
                    if (c == END) break;
                    plainSha.update(c.data, 0, c.len);
                    gz.write(c.data, 0, c.len);
                }
            } catch (Throwable t) {
                failure = t;
                queue.clear();
            }
        });
        worker.start();
    }

    public Path path() {
        return path;
    }

    /** Дописать строку (без {@code \n} — он добавляется). */
    public void line(CharSequence s) throws IOException {
        byte[] b = s.toString().getBytes(StandardCharsets.UTF_8);
        write(b, 0, b.length);
        write1((byte) '\n');
        lines++;
    }

    private void write1(byte b) throws IOException {
        if (pos == buf.length) flushChunk();
        buf[pos++] = b;
    }

    private void write(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            if (pos == buf.length) flushChunk();
            int n = Math.min(len, buf.length - pos);
            System.arraycopy(b, off, buf, pos, n);
            pos += n;
            off += n;
            len -= n;
        }
    }

    private void flushChunk() throws IOException {
        if (failure != null) throw new IOException("сжатие " + path + " упало", failure);
        if (pos == 0) return;
        put(new Chunk(buf, pos));
        buf = new byte[CHUNK];
        pos = 0;
    }

    private void put(Chunk c) throws IOException {
        try {
            while (!queue.offer(c, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (failure != null) throw new IOException("сжатие " + path + " упало", failure);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано", e);
        }
    }

    /** Строк всего, с заголовком. */
    public long lines() {
        return lines;
    }

    public Info finish() throws IOException {
        close();
        return new Info(path.getFileName().toString(), lines - 1, Files.size(path),
                HexFormat.of().formatHex(gzSha.digest()), HexFormat.of().formatHex(plainSha.digest()));
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        flushChunk();
        put(END);
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано", e);
        }
        if (failure != null) throw new IOException("сжатие " + path + " упало", failure);
    }

    /** gzip с заголовком CPython ({@link Header#CPYTHON}). */
    private static final class CPythonGzip extends DeflaterOutputStream {
        private final CRC32 crc = new CRC32();
        private long size;

        CPythonGzip(OutputStream out, int level) throws IOException {
            super(out, new Deflater(level, true), 1 << 16);
            int xfl = level == Deflater.BEST_COMPRESSION ? 2 : level == Deflater.BEST_SPEED ? 4 : 0;
            out.write(new byte[]{0x1f, (byte) 0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, (byte) xfl, (byte) 0xff});
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            crc.update(b, off, len);
            size += len;
        }

        @Override
        public void finish() throws IOException {
            if (def.finished()) return;
            super.finish();
            long c = crc.getValue(), n = size & 0xffffffffL;
            byte[] tail = new byte[8];
            for (int i = 0; i < 4; i++) {
                tail[i] = (byte) (c >>> (8 * i));
                tail[4 + i] = (byte) (n >>> (8 * i));
            }
            out.write(tail);
        }

        @Override
        public void close() throws IOException {
            try {
                finish();
                out.close();
            } finally {
                def.end();
            }
        }
    }
}
