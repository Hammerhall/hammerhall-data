package io.hammerhall.data.core.check;

import java.util.List;

/**
 * Контрольный агрегат таблицы выпуска — для файла ожиданий ({@code freeze}) и проверки выпуска у пользователя
 * ({@code check}, {@code tools/check_data.py}). Считается по <b>тексту</b> TSV, одинаково в генераторе и в скрипте
 * проверки, поэтому точно: целые и деньги — без плавающей точки, текст — побайтно.
 *
 * <ul>
 *   <li>{@code sum(поле)} — точная сумма числового поля (шкала — из описания поля);</li>
 *   <li>{@code min(поле)}, {@code max(поле)} — у числовых полей — как числа, у прочих (текст, дата) — побайтно UTF-8;</li>
 *   <li>{@code nulls(поле)} — число пустых значений;</li>
 *   <li>{@code distinct(поле)} — число разных непустых значений (держит значения в памяти — не для полей
 *       многомиллионных таблиц с почти уникальными значениями);</li>
 *   <li>{@code count_eq(поле=значение)} — сколько строк с этим значением (как оно записано в TSV: {@code t}, {@code dc}…).</li>
 * </ul>
 * Модуль объявляет свои в {@code Module.aggregates()}; ядро добавляет к каждой таблице свои по умолчанию
 * ({@link Aggregates#defaults}).
 */
public record Aggregate(String table, String op, String column, String arg) {
    public static final List<String> OPS = List.of("sum", "min", "max", "nulls", "distinct", "count_eq");

    public Aggregate {
        if (!OPS.contains(op)) throw new IllegalArgumentException("агрегат " + op + " — не из " + OPS);
        if (table == null || column == null) throw new IllegalArgumentException("агрегат без таблицы или поля");
        if (op.equals("count_eq") != (arg != null)) throw new IllegalArgumentException("значение — только у count_eq");
    }

    public static Aggregate sum(String table, String column) {
        return new Aggregate(table, "sum", column, null);
    }

    public static Aggregate min(String table, String column) {
        return new Aggregate(table, "min", column, null);
    }

    public static Aggregate max(String table, String column) {
        return new Aggregate(table, "max", column, null);
    }

    public static Aggregate nulls(String table, String column) {
        return new Aggregate(table, "nulls", column, null);
    }

    public static Aggregate distinct(String table, String column) {
        return new Aggregate(table, "distinct", column, null);
    }

    public static Aggregate countEq(String table, String column, String value) {
        return new Aggregate(table, "count_eq", column, value);
    }

    /** {@code sum(price)}, {@code count_eq(kind=dc)}. */
    public String label() {
        return op + "(" + column + (arg == null ? "" : "=" + arg) + ")";
    }
}
