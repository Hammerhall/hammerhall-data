package io.hammerhall.data.mov.source;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Снимок источников {@code mov-source-YYYYMMDD.zip}: zip без общей верхней папки (раскладка — в README набора).
 * Записи читаются по пути от корня архива; большие CSV лежат внутри архива сжатыми ({@code .csv.gz}) — они читаются
 * потоком через {@link GZIPInputStream}, без распаковки на диск. Текст — UTF-8; испорченный байт — ошибка, а не замена.
 */
public final class Snapshot implements Closeable {
    private final Path path;
    private final ZipFile zip;

    private Snapshot(Path path) throws IOException {
        this.path = path;
        this.zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8);
    }

    public static Snapshot open(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("нет снимка источников: " + path);
        return new Snapshot(path);
    }

    public Path path() {
        return path;
    }

    /** sha256 файла снимка как есть. */
    public static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public boolean has(String name) {
        ZipEntry e = zip.getEntry(name);
        return e != null && !e.isDirectory();
    }

    private ZipEntry entry(String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null || e.isDirectory()) throw new IOException(path.getFileName() + ": в снимке нет " + name);
        return e;
    }

    public InputStream stream(String name) throws IOException {
        return zip.getInputStream(entry(name));
    }

    public byte[] bytes(String name) throws IOException {
        try (InputStream in = stream(name)) {
            return in.readAllBytes();
        }
    }

    public String text(String name) throws IOException {
        return decode(bytes(name), name);
    }

    /** Строгое UTF-8: испорченный байт — ошибка с именем записи. */
    static String decode(byte[] b, String name) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IOException(name + ": не UTF-8", e);
        }
    }

    public static BufferedReader reader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)), 1 << 16);
    }

    /** Текст записи потоком; запись {@code .gz} — разжатой. */
    public BufferedReader reader(String name) throws IOException {
        InputStream in = stream(name);
        return reader(name.endsWith(".gz") ? new GZIPInputStream(in, 1 << 16) : in);
    }

    /**
     * Имя записи CSV в снимке: {@code base.csv.gz}, а нет такой — {@code base.csv} (большие таблицы снимок кладёт
     * сжатыми, маленькие — как есть; сборке всё равно). Нет ни той, ни другой — ошибка с обоими именами.
     */
    public String csvName(String base) throws IOException {
        if (has(base + ".csv.gz")) return base + ".csv.gz";
        if (has(base + ".csv")) return base + ".csv";
        throw new IOException(path.getFileName() + ": в снимке нет " + base + ".csv.gz и " + base + ".csv");
    }

    /** Сравнение строк по байтам UTF-8 — как сортировка снимка и {@code LC_ALL=C sort}. */
    public static int compareBytes(String a, String b) {
        return java.util.Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
