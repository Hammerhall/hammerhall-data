package io.hammerhall.data.core.schema;

/**
 * Тип поля: что модуль кладёт в строку (Java) → как поле пишется в TSV → тип в DDL Postgres.
 *
 * <table>
 *   <tr><th>тип</th><th>значение в строке</th><th>TSV</th><th>DDL</th></tr>
 *   <tr><td>SMALLINT, INT, BIGINT</td><td>{@code Integer/Long/Short}</td><td>{@code 42}</td><td>smallint, integer, bigint</td></tr>
 *   <tr><td>MONEY</td><td>{@code Long} копеек или {@code BigDecimal}</td><td>{@code 1290.00}</td><td>numeric(14,2)</td></tr>
 *   <tr><td>DECIMAL</td><td>{@code BigDecimal} или {@code Double} (округляется до шкалы)</td><td>{@code 0.975}</td><td>numeric(p,s)</td></tr>
 *   <tr><td>DOUBLE</td><td>{@code Double} — пишется с фиксированной шкалой поля</td><td>{@code 55.751244}</td><td>double precision</td></tr>
 *   <tr><td>TEXT</td><td>{@code String} без табуляции, перевода строки и обратной косой; пустая — нельзя</td><td>как есть</td><td>text</td></tr>
 *   <tr><td>BOOL</td><td>{@code Boolean}</td><td>{@code t} / {@code f}</td><td>boolean</td></tr>
 *   <tr><td>DATE</td><td>{@code LocalDate}</td><td>{@code 2026-12-01}</td><td>date</td></tr>
 *   <tr><td>MOMENT</td><td>{@code Long} — секунды Unix, UTC</td><td>{@code 1764536400}</td><td>timestamptz (загрузчик: to_timestamp)</td></tr>
 * </table>
 * NULL — {@code null}, в TSV — пустое поле.
 */
public enum ColType {
    SMALLINT, INT, BIGINT, MONEY, DECIMAL, DOUBLE, TEXT, BOOL, DATE, MOMENT;

    public boolean integral() {
        return this == SMALLINT || this == INT || this == BIGINT;
    }

    /** Тип в DDL Postgres. */
    public String sql(Column c) {
        return switch (this) {
            case SMALLINT -> "smallint";
            case INT -> "integer";
            case BIGINT -> "bigint";
            case MONEY -> "numeric(14,2)";
            case DECIMAL -> "numeric(" + c.precision() + "," + c.scale() + ")";
            case DOUBLE -> "double precision";
            case TEXT -> "text";
            case BOOL -> "boolean";
            case DATE -> "date";
            case MOMENT -> "timestamptz";
        };
    }

    /** Короткое имя типа для Mermaid и описания полей. */
    public String shortName(Column c) {
        return switch (this) {
            case SMALLINT -> "smallint";
            case INT -> "int";
            case BIGINT -> "bigint";
            case MONEY -> "money";
            case DECIMAL -> "decimal";
            case DOUBLE -> "double";
            case TEXT -> "text";
            case BOOL -> "bool";
            case DATE -> "date";
            case MOMENT -> "moment";
        };
    }
}
