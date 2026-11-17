package io.hammerhall.data.air.dump;

import io.hammerhall.data.air.select.KeyCodec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Строка блока COPY в формате text: поля через табуляцию, {@code \N} — NULL, спецсимволы — через обратную косую
 * ({@code \\}, {@code \t}, {@code \n}, {@code \r}, {@code \b}, {@code \f}, {@code \v}, {@code \ooo}, {@code \xhh}).
 * Объект переиспользуется: действителен только внутри вызова {@code Block.row}.
 */
public final class CopyRow {
    private byte[] buf;
    private int[] start = new int[16], end = new int[16];
    private int n;
    private long line;
    private boolean backslash;

    void set(byte[] buf, int from, int to, long line) {
        this.buf = buf;
        this.line = line;
        n = 0;
        backslash = false;
        int s = from;
        for (int i = from; i <= to; i++) {
            if (i < to && buf[i] == '\\') backslash = true;
            if (i == to || buf[i] == '\t') {
                if (n == start.length) {
                    start = java.util.Arrays.copyOf(start, n * 2);
                    end = java.util.Arrays.copyOf(end, n * 2);
                }
                start[n] = s;
                end[n] = i;
                n++;
                s = i + 1;
            }
        }
    }

    /** Число полей строки. */
    public int size() {
        return n;
    }

    /** Номер строки в выгрузке (с 1) — для сообщений об ошибках. */
    public long line() {
        return line;
    }

    /** В строке есть обратная косая (NULL или экранирование). */
    public boolean anyBackslash() {
        return backslash;
    }

    public boolean isNull(int i) {
        return end[i] - start[i] == 2 && buf[start[i]] == '\\' && buf[start[i] + 1] == 'N';
    }

    /** В поле есть экранирование обратной косой (не считая NULL). */
    public boolean escaped(int i) {
        if (isNull(i)) return false;
        for (int p = start[i]; p < end[i]; p++) if (buf[p] == '\\') return true;
        return false;
    }

    /** Значение поля — текст (экранирование снято); NULL — {@code null}. */
    public String text(int i) {
        if (isNull(i)) return null;
        int s = start[i], e = end[i];
        boolean esc = false;
        for (int p = s; p < e && !esc; p++) esc = buf[p] == '\\';
        if (!esc) return new String(buf, s, e - s, StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(e - s);
        for (int p = s; p < e; p++) {
            byte c = buf[p];
            if (c != '\\' || p + 1 == e) {
                out.write(c);
                continue;
            }
            byte x = buf[++p];
            switch (x) {
                case 'b' -> out.write(8);
                case 'f' -> out.write(12);
                case 'n' -> out.write(10);
                case 'r' -> out.write(13);
                case 't' -> out.write(9);
                case 'v' -> out.write(11);
                case 'x' -> {
                    int v = 0, k = 0;
                    while (k < 2 && p + 1 < e && hex(buf[p + 1]) >= 0) {
                        v = v * 16 + hex(buf[++p]);
                        k++;
                    }
                    if (k == 0) out.write('x');
                    else out.write(v);
                }
                default -> {
                    if (x >= '0' && x <= '7') {
                        int v = x - '0', k = 1;
                        while (k < 3 && p + 1 < e && buf[p + 1] >= '0' && buf[p + 1] <= '7') {
                            v = v * 8 + (buf[++p] - '0');
                            k++;
                        }
                        out.write(v & 0xff);
                    } else {
                        out.write(x);
                    }
                }
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(byte b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }

    /** Целое поле (десятичная запись, как её пишет Postgres); NULL и прочее — ошибка. */
    public long longValue(int i) {
        int s = start[i], e = end[i];
        boolean neg = s < e && buf[s] == '-';
        if (neg) s++;
        if (s == e || e - s > 18) throw bad(i, "не целое");
        long v = 0;
        for (int p = s; p < e; p++) {
            int d = buf[p] - '0';
            if (d < 0 || d > 9) throw bad(i, "не целое");
            v = v * 10 + d;
        }
        return neg ? -v : v;
    }

    /** Код ключа поля ({@link KeyCodec}) — без строки. */
    public long key(int i) {
        if (isNull(i) || escaped(i)) throw bad(i, "ключ — NULL или с экранированием");
        return KeyCodec.encode(buf, start[i], end[i] - start[i]);
    }

    /** Поле как есть (байты выгрузки, без снятия экранирования). */
    public byte[] raw(int i) {
        return java.util.Arrays.copyOfRange(buf, start[i], end[i]);
    }

    /** Сравнить поле как есть с байтами побайтно без знака (как строки в DuckDB): &lt;0, 0, &gt;0. */
    public int compareRaw(int i, byte[] other) {
        int s = start[i], len = end[i] - s, m = Math.min(len, other.length);
        for (int k = 0; k < m; k++) {
            int c = Integer.compare(buf[s + k] & 0xff, other[k] & 0xff);
            if (c != 0) return c;
        }
        return Integer.compare(len, other.length);
    }

    IllegalArgumentException bad(int i, String what) {
        String v = new String(buf, start[i], Math.min(end[i] - start[i], 80), StandardCharsets.UTF_8);
        return new IllegalArgumentException("строка " + line + " выгрузки, поле " + (i + 1) + " «" + v + "»: " + what);
    }
}
