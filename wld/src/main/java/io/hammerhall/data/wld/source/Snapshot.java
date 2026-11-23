package io.hammerhall.data.wld.source;

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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Снимок источников {@code wld-source-YYYYMMDD.zip}: zip без общей верхней папки (раскладка — в {@code CONTRACT}
 * снимка и в README набора). Записи читаются по пути от корня архива; вложенные zip GeoNames
 * ({@code geonames/cities500.zip}, {@code geonames/capitals/<CC>.zip}) — потоком, без распаковки на диск. Текст — UTF-8;
 * испорченный байт — ошибка, а не замена.
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

    public BufferedReader reader(String name) throws IOException {
        return reader(stream(name));
    }

    /** Имена записей (не папок) с этим началом пути, по имени (побайтно). */
    public List<String> names(String prefix) {
        List<String> out = new ArrayList<>();
        for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            if (!e.isDirectory() && e.getName().startsWith(prefix)) out.add(e.getName());
        }
        out.sort(Snapshot::compareBytes);
        return out;
    }

    /** Сравнение строк по байтам UTF-8 — как сортировка архива и {@code LC_ALL=C sort}. */
    public static int compareBytes(String a, String b) {
        return java.util.Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Записи вложенного zip, чьё имя проходит {@code want}, — по порядку архива: читателю — поток строк записи (UTF-8).
     * Сколько записей прочитано — результат.
     */
    public int inner(String name, java.util.function.Predicate<String> want, InnerReader reader) throws IOException {
        int n = 0;
        try (ZipInputStream z = new ZipInputStream(stream(name), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.isDirectory() || !want.test(e.getName())) continue;
                n++;
                BufferedReader r = reader(new NonClosing(z));
                reader.read(e.getName(), r);
            }
        }
        return n;
    }

    /** Читатель записи вложенного zip. */
    @FunctionalInterface
    public interface InnerReader {
        void read(String entry, BufferedReader lines) throws IOException;
    }

    /** Строки текста без перевода строки в конце; {@code \r} перед {@code \n} — отбрасывается. */
    public static void lines(BufferedReader r, Consumer<String> line) throws IOException {
        for (String s; (s = r.readLine()) != null; ) line.accept(s);
    }

    /** Поток записи вложенного zip, который читатель не закрывает (следующая запись — в том же потоке). */
    private static final class NonClosing extends java.io.FilterInputStream {
        NonClosing(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // поток архива закрывает тот, кто его открыл
        }
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
