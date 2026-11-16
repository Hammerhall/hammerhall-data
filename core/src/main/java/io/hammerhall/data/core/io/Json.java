package io.hammerhall.data.core.io;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Минимальный JSON — как {@code json.dumps(indent=1, ensure_ascii=False)} у сборщика авиа: порядок ключей — порядок
 * вставки ({@code LinkedHashMap}), отступ — один пробел на уровень. Значения: Map, List, String, Long/Integer,
 * Boolean, null. Генераторы дробных чисел не пишут нарочно (запись {@code double} зависит от реализации); дробное в
 * чужом JSON (манифест выпуска, собранного не генератором) читается как {@link BigDecimal} — с тем же текстом.
 */
public final class Json {
    private Json() {
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int n) {
        sb.append('\n');
        for (int i = 0; i < n; i++) sb.append(' ');
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, int level) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(sb, s);
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof BigDecimal d) {
            sb.append(d.toPlainString());
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                indent(sb, level + 1);
                string(sb, (String) e.getKey());
                sb.append(": ");
                write(sb, e.getValue(), level + 1);
            }
            indent(sb, level);
            sb.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                indent(sb, level + 1);
                write(sb, l.get(i), level + 1);
            }
            indent(sb, level);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("JSON: тип " + v.getClass().getSimpleName());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- чтение (для проверки выпуска)

    /** Разбор JSON того же подмножества (объекты, массивы, строки, целые, true/false/null); дробные — {@link BigDecimal}. */
    public static Object parse(String s) {
        int[] pos = {0};
        Object v = value(s, pos);
        skip(s, pos);
        if (pos[0] != s.length()) throw new IllegalArgumentException("JSON: лишнее в позиции " + pos[0]);
        return v;
    }

    private static void skip(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
    }

    private static Object value(String s, int[] p) {
        skip(s, p);
        char c = s.charAt(p[0]);
        if (c == '{') {
            p[0]++;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            skip(s, p);
            if (s.charAt(p[0]) == '}') {
                p[0]++;
                return m;
            }
            while (true) {
                skip(s, p);
                String k = (String) value(s, p);
                skip(s, p);
                expect(s, p, ':');
                m.put(k, value(s, p));
                skip(s, p);
                if (s.charAt(p[0]) == ',') {
                    p[0]++;
                    continue;
                }
                expect(s, p, '}');
                return m;
            }
        }
        if (c == '[') {
            p[0]++;
            List<Object> l = new java.util.ArrayList<>();
            skip(s, p);
            if (s.charAt(p[0]) == ']') {
                p[0]++;
                return l;
            }
            while (true) {
                l.add(value(s, p));
                skip(s, p);
                if (s.charAt(p[0]) == ',') {
                    p[0]++;
                    continue;
                }
                expect(s, p, ']');
                return l;
            }
        }
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            p[0]++;
            while (true) {
                char ch = s.charAt(p[0]++);
                if (ch == '"') return sb.toString();
                if (ch == '\\') {
                    char e = s.charAt(p[0]++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16));
                            p[0] += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(ch);
                }
            }
        }
        if (s.startsWith("true", p[0])) {
            p[0] += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", p[0])) {
            p[0] += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", p[0])) {
            p[0] += 4;
            return null;
        }
        int start = p[0];
        boolean fraction = false;
        while (p[0] < s.length()) {
            char ch = s.charAt(p[0]);
            if (ch == '.' || ch == 'e' || ch == 'E' || ch == '+') fraction = true;
            else if (!Character.isDigit(ch) && ch != '-') break;
            p[0]++;
        }
        if (p[0] == start) throw new IllegalArgumentException("JSON: нет значения в позиции " + start);
        String num = s.substring(start, p[0]);
        return fraction ? new BigDecimal(num) : (Object) Long.parseLong(num);
    }

    private static void expect(String s, int[] p, char c) {
        if (s.charAt(p[0]) != c) throw new IllegalArgumentException("JSON: ждали '" + c + "' в позиции " + p[0]);
        p[0]++;
    }
}
