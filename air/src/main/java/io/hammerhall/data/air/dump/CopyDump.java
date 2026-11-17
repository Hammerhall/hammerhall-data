package io.hammerhall.data.air.dump;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Выгрузка pg_dump в виде текстового SQL ({@code .sql} или {@code .sql.gz}) — потоком, за один проход: блоки
 * {@code COPY bookings.<таблица> (<поля>) FROM stdin;} … {@code \.} отдаются посетителю строка за строкой, sha256
 * файла (байтов на диске, до распаковки) считается в том же проходе. Распакованное на диск не пишется, в памяти — одна
 * строка. Порядок блоков — любой; блоки других схем пропускаются; остальной SQL (SET, CREATE, ALTER) не разбирается.
 */
public final class CopyDump {
    /** Заголовок блока — как у сборки {@code air-data-v1}: схема {@code bookings}, поля — в скобках через запятую. */
    static final Pattern COPY = Pattern.compile("^COPY bookings\\.(\\w+) \\((.*)\\) FROM stdin;$");
    /** Любой другой блок COPY — его строки не SQL, их надо пропустить до {@code \.}. */
    static final Pattern ANY_COPY = Pattern.compile("^COPY \\S+ .*FROM stdin;$");

    private CopyDump() {
    }

    /** Строки одного блока. */
    public interface Block {
        void row(CopyRow r) throws IOException;

        default void end() throws IOException {
        }
    }

    /** Что делать с блоком таблицы; {@code null} — пропустить. Поля — из заголовка COPY, в его порядке. */
    public interface Visitor {
        Block begin(String table, List<String> columns) throws IOException;
    }

    /**
     * Итог прохода: sha256 и размер файла, строк в блоках по таблицам (в порядке блоков в выгрузке), полей с
     * экранированием по таблицам (их значения разобраны так же, как остальные, но в выпуске их быть не должно).
     */
    public record Result(String sha256, long bytes, Map<String, Long> rows, Map<String, Long> escaped) {
    }

    public static Result read(Path file, Visitor visitor) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Map<String, Long> rows = new LinkedHashMap<>();
        Map<String, Long> escaped = new LinkedHashMap<>();
        long size;
        try (InputStream disk = Files.newInputStream(file);
             BufferedInputStream raw = new BufferedInputStream(new DigestInputStream(disk, sha), 1 << 16)) {
            raw.mark(2);
            int b0 = raw.read(), b1 = raw.read();
            raw.reset();
            boolean gz = b0 == 0x1f && b1 == 0x8b;
            InputStream text = gz ? new GZIPInputStream(raw, 1 << 16) : raw;
            new Lines(text, visitor, rows, escaped, file).run();
            // хвост за последним членом gzip (если есть) — тоже в сумму: sha256 — всего файла
            byte[] tmp = new byte[1 << 16];
            while (raw.read(tmp) >= 0) {
                // только досчитать сумму
            }
            size = Files.size(file);
        }
        return new Result(HexFormat.of().formatHex(sha.digest()), size, rows, escaped);
    }

    /** Разбор строк: вне блока — поиск заголовка COPY, в блоке — строки до {@code \.}. */
    private static final class Lines {
        private final InputStream in;
        private final Visitor visitor;
        private final Map<String, Long> rows, escaped;
        private final Path file;
        private byte[] buf = new byte[1 << 20];
        private int len, pos;
        private long line;
        private final CopyRow row = new CopyRow();

        Lines(InputStream in, Visitor visitor, Map<String, Long> rows, Map<String, Long> escaped, Path file) {
            this.in = in;
            this.visitor = visitor;
            this.rows = rows;
            this.escaped = escaped;
            this.file = file;
        }

        /** Следующая строка — {@code buf[pos, end)} без {@code \n}; {@code -1} — конец файла. */
        private int next() throws IOException {
            while (true) {
                for (int i = pos; i < len; i++) {
                    if (buf[i] == '\n') {
                        line++;
                        return i;
                    }
                }
                if (pos > 0) {
                    System.arraycopy(buf, pos, buf, 0, len - pos);
                    len -= pos;
                    pos = 0;
                }
                if (len == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                int n = in.read(buf, len, buf.length - len);
                if (n < 0) {
                    if (len == 0) return -1;
                    // последняя строка без \n
                    if (len == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length + 1);
                    buf[len] = '\n';
                    len++;
                    continue;
                }
                len += n;
            }
        }

        void run() throws IOException {
            String table = null;
            Block block = null;
            boolean inCopy = false;
            int width = 0;
            long count = 0, esc = 0;
            List<String> seen = new ArrayList<>();
            int e;
            while ((e = next()) >= 0) {
                int s = pos;
                pos = e + 1;
                if (!inCopy) {
                    if (e - s < 5 || buf[s] != 'C' || buf[s + 1] != 'O' || buf[s + 2] != 'P' || buf[s + 3] != 'Y') continue;
                    String l = new String(buf, s, e - s, StandardCharsets.UTF_8);
                    Matcher m = COPY.matcher(l);
                    if (m.matches()) {
                        table = m.group(1);
                        if (seen.contains(table)) throw new IOException(file + ": блок COPY таблицы " + table + " — второй раз");
                        seen.add(table);
                        List<String> cols = new ArrayList<>();
                        for (String c : m.group(2).split(",")) {
                            String t = c.strip();
                            if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
                            cols.add(t);
                        }
                        width = cols.size();
                        block = visitor.begin(table, List.copyOf(cols));
                        inCopy = true;
                        count = 0;
                        esc = 0;
                    } else if (ANY_COPY.matcher(l).matches()) {
                        table = null;
                        block = null;
                        inCopy = true;
                    }
                    continue;
                }
                if (e - s == 2 && buf[s] == '\\' && buf[s + 1] == '.') {
                    if (table != null) {
                        if (block != null) block.end();
                        rows.put(table, count);
                        escaped.put(table, esc);
                    }
                    inCopy = false;
                    table = null;
                    block = null;
                    continue;
                }
                if (table == null) continue;
                count++;
                if (block == null) continue;
                row.set(buf, s, e, line);
                if (row.size() != width) {
                    throw new IOException(file + ", строка " + line + ": полей " + row.size() + ", в заголовке COPY "
                            + table + " — " + width);
                }
                if (row.anyBackslash()) for (int i = 0; i < width; i++) if (row.escaped(i)) esc++;
                block.row(row);
            }
            if (inCopy) throw new IOException(file + ": выгрузка оборвана внутри блока COPY " + table);
        }
    }
}
