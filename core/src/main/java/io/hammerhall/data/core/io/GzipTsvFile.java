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
import java.util.zip.GZIPOutputStream;

/**
 * Файл {@code <таблица>.tsv.gz}: UTF-8, строки через {@code \n}, gzip без отметки времени и имени файла (заголовок
 * gzip у JDK — нулевой MTIME). Сжатие и подсчёт sha256 — в своём потоке: производитель строк не ждёт deflate, на двух
 * ядрах это почти вдвое быстрее. Порядок байт файла от этого не зависит.
 */
public final class GzipTsvFile implements Closeable {
    /** Итог файла для манифеста. */
    public record Info(String name, long rows, long bytes, String sha256, String sha256Tsv) {
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
        this.path = path;
        try {
            plainSha = MessageDigest.getInstance("SHA-256");
            gzSha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        OutputStream file = new BufferedOutputStream(Files.newOutputStream(path), 1 << 16);
        OutputStream digested = new DigestOutputStream(file, gzSha);
        GZIPOutputStream gz = new GZIPOutputStream(digested, 1 << 16) {
            {
                def.setLevel(level);
            }
        };
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
}
