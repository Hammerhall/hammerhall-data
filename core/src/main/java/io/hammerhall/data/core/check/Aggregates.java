package io.hammerhall.data.core.check;

import io.hammerhall.data.core.schema.ColType;
import io.hammerhall.data.core.schema.Column;
import io.hammerhall.data.core.schema.Table;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Счёт контрольных агрегатов по файлу таблицы ({@code <таблица>.tsv.gz}) — по тексту, за один проход вместе с
 * числом строк и sha256 (сжатого и разжатого). Тот же счёт — в {@code tools/check_data.py}: правила здесь и там
 * должны совпадать побуквенно.
 *
 * <p>Числа — целые со шкалой: «1290.5» при шкале 2 — 129050; сумма — точная (переполнение {@code long} — ошибка), вывод
 * — с шкалой знаков после точки («-5.07», «0.000»). Текст — побайтно UTF-8. Пустое поле — NULL: в суммах, минимумах и
 * {@code distinct} не участвует.
 */
public final class Aggregates {
    private Aggregates() {
    }

    /**
     * Агрегат, готовый к счёту: {@code scale} — шкала числа для {@code sum/min/max} числового поля; {@code null} —
     * сравнение текстом (или шкала не нужна).
     */
    public record Spec(String table, String op, String column, String arg, Integer scale) {
        public String label() {
            return op + "(" + column + (arg == null ? "" : "=" + arg) + ")";
        }

        /** Из объявления и описания таблицы. */
        public static Spec of(Aggregate a, Table t) {
            Column c = t.column(a.column());
            Integer scale = scaleOf(c);
            if (a.op().equals("sum") && scale == null) {
                throw new IllegalArgumentException(t + "." + c.name() + ": sum по нечисловому полю " + c.type());
            }
            boolean numericOp = a.op().equals("sum") || a.op().equals("min") || a.op().equals("max");
            return new Spec(t.name(), a.op(), a.column(), a.arg(), numericOp ? scale : null);
        }

        public Map<String, Object> toJson(Object value) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", table);
            m.put("op", op);
            m.put("column", column);
            if (arg != null) m.put("arg", arg);
            if (scale != null) m.put("scale", (long) scale);
            m.put("value", value);
            return m;
        }

        public static Spec fromJson(Map<String, Object> m) {
            Object sc = m.get("scale");
            Spec s = new Spec((String) m.get("table"), (String) m.get("op"), (String) m.get("column"), (String) m.get("arg"),
                    sc == null ? null : ((Number) sc).intValue());
            if (!Aggregate.OPS.contains(s.op)) throw new IllegalArgumentException("агрегат " + s.op);
            return s;
        }
    }

    /** Шкала числового поля; {@code null} — поле не числовое (текст, дата, логическое). */
    public static Integer scaleOf(Column c) {
        return switch (c.type()) {
            case SMALLINT, INT, BIGINT, MOMENT -> 0;
            case MONEY -> 2;
            case DECIMAL, DOUBLE -> c.scale();
            case TEXT, BOOL, DATE -> null;
        };
    }

    /** Агрегаты ядра для любой таблицы: сумма каждого денежного поля, число NULL каждого необязательного. */
    public static List<Aggregate> defaults(Table t) {
        List<Aggregate> out = new ArrayList<>();
        for (Column c : t.columns()) {
            if (c.type() == ColType.MONEY) out.add(Aggregate.sum(t.name(), c.name()));
            if (c.nullable()) out.add(Aggregate.nulls(t.name(), c.name()));
        }
        return out;
    }

    /** Итог по файлу: строк (без заголовка), sha256 разжатого и сжатого, значения агрегатов в порядке спецификаций. */
    public record FileStats(long rows, String sha256Tsv, String sha256, List<Object> values) {
    }

    // ---------------------------------------------------------------- числа со шкалой

    /** «-12.5» при шкале 2 → -1250. Дробных знаков больше шкалы, пусто, мусор — ошибка. */
    public static long scaled(byte[] b, int from, int to, int scale) {
        int p = from;
        boolean neg = p < to && b[p] == '-';
        if (neg) p++;
        if (p >= to) throw bad(b, from, to);
        long v = 0;
        int frac = -1, digits = 0;
        for (; p < to; p++) {
            byte c = b[p];
            if (c == '.') {
                if (frac >= 0) throw bad(b, from, to);
                frac = 0;
                continue;
            }
            if (c < '0' || c > '9') throw bad(b, from, to);
            if (frac >= 0 && ++frac > scale) throw bad(b, from, to);
            digits++;
            v = Math.addExact(Math.multiplyExact(v, 10), c - '0');
        }
        if (digits == 0) throw bad(b, from, to);
        for (int i = Math.max(frac, 0); i < scale; i++) v = Math.multiplyExact(v, 10);
        return neg ? -v : v;
    }

    private static NumberFormatException bad(byte[] b, int from, int to) {
        return new NumberFormatException("не число со шкалой: «" + new String(b, from, to - from, StandardCharsets.UTF_8) + "»");
    }

    /** 129050 при шкале 2 → «1290.50»; -7 → «-0.07». */
    public static String formatScaled(long v, int scale) {
        if (scale == 0) return Long.toString(v);
        boolean neg = v < 0;
        String digits = neg ? Long.toString(v).substring(1) : Long.toString(v);
        StringBuilder sb = new StringBuilder();
        if (digits.length() <= scale) sb.append("0".repeat(scale - digits.length() + 1));
        sb.append(digits);
        sb.insert(sb.length() - scale, '.');
        return (neg ? "-" : "") + sb;
    }

    // ---------------------------------------------------------------- счёт

    private static final class Acc {
        final Spec spec;
        final boolean min;
        final byte[] eq;
        long count, sum, num;
        boolean has;
        byte[] text;
        Set<String> seen;

        Acc(Spec spec) {
            this.spec = spec;
            this.min = spec.op.equals("min");
            this.eq = spec.arg == null ? null : spec.arg.getBytes(StandardCharsets.UTF_8);
            if (spec.op.equals("distinct")) seen = new HashSet<>();
            if (spec.op.equals("sum") && spec.scale == null) throw new IllegalArgumentException(spec.label() + ": нет шкалы");
        }

        void accept(byte[] b, int from, int to) {
            switch (spec.op) {
                case "nulls" -> {
                    if (from == to) count++;
                }
                case "count_eq" -> {
                    if (Arrays.equals(b, from, to, eq, 0, eq.length)) count++;
                }
                case "distinct" -> {
                    if (from < to) seen.add(new String(b, from, to - from, StandardCharsets.UTF_8));
                }
                case "sum" -> {
                    if (from < to) sum = Math.addExact(sum, scaled(b, from, to, spec.scale));
                }
                default -> {
                    if (from == to) return;
                    if (spec.scale != null) {
                        long v = scaled(b, from, to, spec.scale);
                        if (!has || (min ? v < num : v > num)) num = v;
                    } else {
                        int c = has ? Arrays.compareUnsigned(b, from, to, text, 0, text.length) : 0;
                        if (!has || (min ? c < 0 : c > 0)) text = Arrays.copyOfRange(b, from, to);
                    }
                    has = true;
                }
            }
        }

        Object value() {
            return switch (spec.op) {
                case "nulls", "count_eq" -> count;
                case "distinct" -> (long) seen.size();
                case "sum" -> formatScaled(sum, spec.scale);
                default -> !has ? null : spec.scale != null ? formatScaled(num, spec.scale) : new String(text, StandardCharsets.UTF_8);
            };
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Один проход по файлу: строки, sha256 и агрегаты (все — по этой таблице). */
    public static FileStats compute(Path gz, List<Spec> specs) throws IOException {
        MessageDigest gzSha = sha256(), plainSha = sha256();
        Parser parser = new Parser(specs);
        try (InputStream raw = new DigestInputStream(Files.newInputStream(gz), gzSha);
             InputStream in = new GZIPInputStream(raw, 1 << 16)) {
            byte[] chunk = new byte[1 << 16];
            int r;
            while ((r = in.read(chunk)) > 0) {
                plainSha.update(chunk, 0, r);
                parser.feed(chunk, r);
            }
            raw.transferTo(OutputStream.nullOutputStream());
        }
        parser.finish(gz);
        List<Object> values = new ArrayList<>();
        for (Acc a : parser.accs) values.add(a.value());
        return new FileStats(parser.lines - 1, HexFormat.of().formatHex(plainSha.digest()),
                HexFormat.of().formatHex(gzSha.digest()), values);
    }

    /** Разбор строк TSV по кускам: поля только до последнего нужного. */
    private static final class Parser {
        final List<Spec> specs;
        final Acc[] accs;
        Acc[][] byCol;
        int maxCol = -1;
        long lines;
        boolean header = true;
        byte[] carry = new byte[1 << 12];
        int carryN;

        Parser(List<Spec> specs) {
            this.specs = specs;
            this.accs = new Acc[specs.size()];
            for (int i = 0; i < accs.length; i++) accs[i] = new Acc(specs.get(i));
        }

        void feed(byte[] b, int n) {
            int start = 0;
            for (int i = 0; i < n; i++) {
                if (b[i] != '\n') continue;
                lines++;
                if (header || accs.length > 0) {
                    if (carryN > 0) {
                        append(b, start, i);
                        line(carry, 0, carryN);
                        carryN = 0;
                    } else {
                        line(b, start, i);
                    }
                }
                start = i + 1;
            }
            if ((header || accs.length > 0) && start < n) append(b, start, n);
        }

        private void append(byte[] b, int from, int to) {
            int len = to - from;
            if (carryN + len > carry.length) carry = Arrays.copyOf(carry, Math.max(carry.length * 2, carryN + len));
            System.arraycopy(b, from, carry, carryN, len);
            carryN += len;
        }

        private void line(byte[] b, int from, int to) {
            if (header) {
                header = false;
                String[] names = new String(b, from, to - from, StandardCharsets.UTF_8).split("\t", -1);
                List<String> cols = List.of(names);
                List<List<Acc>> lists = new ArrayList<>();
                for (int i = 0; i < names.length; i++) lists.add(new ArrayList<>());
                for (Acc a : accs) {
                    int c = cols.indexOf(a.spec.column);
                    if (c < 0) throw new IllegalArgumentException(a.spec.table + ": нет поля " + a.spec.column + " в заголовке");
                    lists.get(c).add(a);
                    maxCol = Math.max(maxCol, c);
                }
                byCol = new Acc[names.length][];
                for (int i = 0; i < names.length; i++) byCol[i] = lists.get(i).toArray(new Acc[0]);
                return;
            }
            int field = 0, fs = from;
            for (int p = from; p <= to && field <= maxCol; p++) {
                if (p < to && b[p] != '\t') continue;
                for (Acc a : byCol[field]) a.accept(b, fs, p);
                field++;
                fs = p + 1;
            }
            if (field <= maxCol) throw new IllegalArgumentException("строка " + lines + ": полей " + field + ", нужно > " + maxCol);
        }

        void finish(Path gz) throws IOException {
            if (carryN > 0) throw new IOException(gz + ": последняя строка без перевода строки");
            if (header) throw new IOException(gz + ": нет заголовка");
        }
    }
}
