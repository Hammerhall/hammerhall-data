package io.hammerhall.data.air.select;

import java.nio.charset.StandardCharsets;

/**
 * Номер брони и номер билета — в 64 бита без потерь, чтобы десятки миллионов ключей держать массивами {@code long},
 * а не строками. Код обратим ({@link #decode}) и не равен нулю (ноль — «пусто» у {@link LongSet}).
 *
 * <ul>
 *   <li>только цифры, 1–17 знаков (номер билета {@code 0005432000000}): бит 62, длина (5 бит), число;</li>
 *   <li>цифры и заглавные латинские буквы, 1–11 знаков (номер брони {@code 6TAS39}): биективная запись по основанию
 *       37 (цифры знаков — 1…36), меньше 2<sup>58</sup>.</li>
 * </ul>
 * Ключ другого вида — ошибка с самим ключом: выгрузка не та, что ждали, молча сводить разные ключи к одному нельзя.
 */
public final class KeyCodec {
    private static final long DIGITS = 1L << 62;
    private static final int MAX_DIGITS = 17;
    private static final int MAX_ALNUM = 11;

    private KeyCodec() {
    }

    /** Код ключа из байтов UTF-8 {@code b[off, off+len)}. */
    public static long encode(byte[] b, int off, int len) {
        if (len < 1) throw bad(b, off, len);
        boolean digits = len <= MAX_DIGITS;
        for (int i = 0; i < len && digits; i++) digits = b[off + i] >= '0' && b[off + i] <= '9';
        if (digits) {
            long v = 0;
            for (int i = 0; i < len; i++) v = v * 10 + (b[off + i] - '0');
            return DIGITS | ((long) len << 57) | v;
        }
        if (len > MAX_ALNUM) throw bad(b, off, len);
        long v = 0;
        for (int i = 0; i < len; i++) {
            int c = b[off + i];
            int d;
            if (c >= '0' && c <= '9') d = c - '0' + 1;
            else if (c >= 'A' && c <= 'Z') d = c - 'A' + 11;
            else throw bad(b, off, len);
            v = v * 37 + d;
        }
        return v;
    }

    public static long encode(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return encode(b, 0, b.length);
    }

    /** Ключ по коду (обратно к {@link #encode}). */
    public static String decode(long code) {
        if (code <= 0) throw new IllegalArgumentException("не код ключа: " + code);
        if ((code & DIGITS) != 0) {
            int len = (int) ((code >>> 57) & 31);
            long v = code & ((1L << 57) - 1);
            char[] c = new char[len];
            for (int i = len - 1; i >= 0; i--) {
                c[i] = (char) ('0' + v % 10);
                v /= 10;
            }
            return new String(c);
        }
        StringBuilder sb = new StringBuilder();
        long v = code;
        while (v > 0) {
            long d = (v - 1) % 37 + 1;
            sb.append(d <= 10 ? (char) ('0' + d - 1) : (char) ('A' + d - 11));
            v = (v - d) / 37;
        }
        return sb.reverse().toString();
    }

    private static IllegalArgumentException bad(byte[] b, int off, int len) {
        return new IllegalArgumentException("ключ «" + new String(b, off, Math.max(0, len), StandardCharsets.UTF_8)
                + "» — не 1–17 цифр и не 1–11 знаков [0-9A-Z]: выгрузка не того вида, что ждали");
    }
}
