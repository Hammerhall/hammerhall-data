package io.hammerhall.data.core.io;

import io.hammerhall.data.core.money.Money;
import io.hammerhall.data.core.schema.ColType;
import io.hammerhall.data.core.schema.Column;
import io.hammerhall.data.core.schema.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Значение поля → текст TSV по типу из описания схемы; проверка значения; сравнение для сортировки строк. */
public final class RowFormat {
    private RowFormat() {
    }

    private static final long[] POW10 = {1, 10, 100, 1000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

    /** Число с фиксированной шкалой: округление к ближайшему (половина — вверх), без локали. */
    public static String fixed(double v, int scale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("не число: " + v);
        long u = Math.round(v * POW10[scale]);
        boolean neg = u < 0;
        long a = Math.abs(u);
        String digits = Long.toString(a);
        if (scale == 0) return (neg ? "-" : "") + digits;
        while (digits.length() <= scale) digits = "0" + digits;
        String s = digits.substring(0, digits.length() - scale) + "." + digits.substring(digits.length() - scale);
        return neg ? "-" + s : s;
    }

    /** «6.100» → «6.1», «128.000» → «128», «-0.000» → «0» (DECIMAL пишется без хвостовых нулей). */
    public static String trimZeros(String s) {
        if (s.indexOf('.') < 0) return s;
        int e = s.length();
        while (s.charAt(e - 1) == '0') e--;
        if (s.charAt(e - 1) == '.') e--;
        String r = s.substring(0, e);
        return r.equals("-0") ? "0" : r;
    }

    public static void checkText(String s, Table t, Column c) {
        if (s.isEmpty()) throw new IllegalArgumentException(t + "." + c.name() + ": пустая строка (пустое поле — NULL)");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\t' || ch == '\n' || ch == '\r' || ch == '\\') {
                throw new IllegalArgumentException(t + "." + c.name() + ": недопустимый символ в «" + s + "»");
            }
        }
        if (c.isEnum() && !c.values().contains(s)) {
            throw new IllegalArgumentException(t + "." + c.name() + ": «" + s + "» не из " + c.values());
        }
    }

    /** Дописать значение поля в строку; {@code null} — пустое поле. */
    public static void append(StringBuilder sb, Table t, Column c, Object v) {
        if (v == null) {
            if (!c.nullable()) throw new IllegalArgumentException(t + "." + c.name() + ": NULL в NOT NULL");
            return;
        }
        switch (c.type()) {
            case SMALLINT, INT, BIGINT -> {
                if (!(v instanceof Integer || v instanceof Long || v instanceof Short)) throw bad(t, c, v);
                long x = ((Number) v).longValue();
                if (c.type() == ColType.SMALLINT && (x < Short.MIN_VALUE || x > Short.MAX_VALUE)) throw bad(t, c, v);
                if (c.type() == ColType.INT && (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE)) throw bad(t, c, v);
                sb.append(x);
            }
            case MONEY -> {
                if (v instanceof Long l) sb.append(Money.format(l));
                else if (v instanceof BigDecimal b) sb.append(b.setScale(2, RoundingMode.HALF_UP).toPlainString());
                else throw bad(t, c, v);
            }
            case DECIMAL -> {
                if (v instanceof BigDecimal b) sb.append(trimZeros(b.setScale(c.scale(), RoundingMode.HALF_UP).toPlainString()));
                else if (v instanceof Double d) sb.append(trimZeros(fixed(d, c.scale())));
                else throw bad(t, c, v);
            }
            case DOUBLE -> {
                if (!(v instanceof Double d)) throw bad(t, c, v);
                sb.append(fixed(d, c.scale()));
            }
            case TEXT -> {
                if (!(v instanceof String s)) throw bad(t, c, v);
                checkText(s, t, c);
                sb.append(s);
            }
            case BOOL -> {
                if (!(v instanceof Boolean b)) throw bad(t, c, v);
                sb.append(b ? 't' : 'f');
            }
            case DATE -> {
                if (!(v instanceof LocalDate d)) throw bad(t, c, v);
                sb.append(d);
            }
            case MOMENT -> {
                if (v instanceof Long l) sb.append(l);
                else if (v instanceof Instant i) sb.append(i.getEpochSecond());
                else throw bad(t, c, v);
            }
        }
    }

    private static IllegalArgumentException bad(Table t, Column c, Object v) {
        return new IllegalArgumentException(t + "." + c.name() + ": значение " + v + " (" + v.getClass().getSimpleName()
                + ") не подходит к типу " + c.type());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compareValues(Object a, Object b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number x && b instanceof Number y) {
            // два BigDecimal — точно (через double два разных значения могли сравняться)
            if (a instanceof BigDecimal p && b instanceof BigDecimal q) return p.compareTo(q);
            if (a instanceof Double || b instanceof Double || a instanceof BigDecimal || b instanceof BigDecimal) {
                return Double.compare(x.doubleValue(), y.doubleValue());
            }
            return Long.compare(x.longValue(), y.longValue());
        }
        if (a instanceof Instant x && b instanceof Instant y) return x.compareTo(y);
        return ((Comparable) a).compareTo(b);
    }

    /** Сравнение строк по полям порядка таблицы. */
    public static Comparator<Object[]> comparator(Table t) {
        List<String> order = t.orderBy();
        int[] idx = new int[order.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = t.indexOf(order.get(i));
        return (r1, r2) -> {
            for (int i : idx) {
                int c = compareValues(r1[i], r2[i]);
                if (c != 0) return c;
            }
            return 0;
        };
    }
}
