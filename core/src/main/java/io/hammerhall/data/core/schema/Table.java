package io.hammerhall.data.core.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Описание таблицы — единственное: из него пишутся TSV (порядок и типы полей), DDL, ER-диаграмма и поля в
 * SOURCE.md. Описывается цепочкой:
 *
 * <pre>{@code
 * Table.of("products", "каталог", "Товары")
 *     .col("id", INT, "Товар")
 *     .col("brand_id", INT, "Бренд").ref("brands")
 *     .col("discontinued_on", DATE, "Снят с продажи").nullable()
 *     .col("kind", TEXT, "Вид").values("a", "b")
 *     .pk("id").unique("sku");
 * }</pre>
 * Модификаторы ({@code nullable}, {@code ref}, {@code values}, {@code scale}, {@code source}, {@code verbatimSource})
 * относятся к последнему полю. Порядок строк в TSV — {@link #orderBy()}, по умолчанию — первичный ключ. NULL в поле порядка идёт первым
 * ({@code NULLS FIRST}), в полях из {@link #nullsLast(String...)} — последним ({@code NULLS LAST}).
 */
public final class Table {
    public record ForeignKey(List<String> columns, String refTable, List<String> refColumns) {
    }

    public record Check(String name, String sql) {
    }

    private final String name;
    private final String group;
    private final String descRu;
    private final List<Column> columns = new ArrayList<>();
    private List<String> primaryKey = List.of();
    private final List<List<String>> uniques = new ArrayList<>();
    private final List<List<String>> indexes = new ArrayList<>();
    private final List<ForeignKey> compositeFks = new ArrayList<>();
    private final List<Check> checks = new ArrayList<>();
    private List<String> orderBy = null;
    private List<String> nullsLast = List.of();
    private String rowsHint = "";

    private Table(String name, String group, String descRu) {
        this.name = name;
        this.group = group;
        this.descRu = descRu;
    }

    public static Table of(String name, String group, String descRu) {
        return new Table(name, group, descRu);
    }

    // ---------------------------------------------------------------- построение

    public Table col(String name, ColType type, String descRu) {
        int prec = type == ColType.DECIMAL ? 12 : 0, sc = type == ColType.DOUBLE ? 6 : (type == ColType.DECIMAL ? 2 : 0);
        columns.add(new Column(name, type, false, prec, sc, List.of(), null, descRu, null, false));
        return this;
    }

    private Table mod(java.util.function.UnaryOperator<Column> f) {
        int i = columns.size() - 1;
        columns.set(i, f.apply(columns.get(i)));
        return this;
    }

    public Table nullable() {
        return mod(c -> c.withNullable(true));
    }

    public Table ref(String table) {
        return mod(c -> c.withRef(table));
    }

    public Table values(String... v) {
        return mod(c -> c.withValues(Arrays.asList(v)));
    }

    /** Для DECIMAL — (всего цифр, после точки); для DOUBLE — только шкала. */
    public Table scale(int precision, int scale) {
        return mod(c -> c.withScale(precision, scale));
    }

    public Table source(String s) {
        return mod(c -> c.withSource(s));
    }

    /**
     * Значения поля — из внешнего источника ({@link #source}) как есть, без изменений по содержанию (названия мест,
     * номера, имена из выгрузки): это не наши слова, и проверка внутренних имён выпуска значения такого поля пропускает
     * (имя поля, тексты выпуска, прочие поля и репозиторий — проверяются). Задаётся явно; по умолчанию — нет.
     */
    public Table verbatimSource() {
        return mod(Column::withVerbatim);
    }

    public Table pk(String... cols) {
        primaryKey = List.of(cols);
        return this;
    }

    public Table unique(String... cols) {
        uniques.add(List.of(cols));
        return this;
    }

    public Table index(String... cols) {
        indexes.add(List.of(cols));
        return this;
    }

    /** Составной внешний ключ (на первичный ключ {@code refTable}). */
    public Table fk(List<String> cols, String refTable) {
        compositeFks.add(new ForeignKey(List.copyOf(cols), refTable, null));
        return this;
    }

    public Table check(String checkName, String sql) {
        checks.add(new Check(checkName, sql));
        return this;
    }

    public Table orderBy(String... cols) {
        orderBy = List.of(cols);
        return this;
    }

    /** Поля порядка строк, в которых NULL идёт последним ({@code ORDER BY … NULLS LAST}); по умолчанию — первым. */
    public Table nullsLast(String... cols) {
        nullsLast = List.of(cols);
        return this;
    }

    /** Ориентир числа строк в {@code full} (для SOURCE.md и сверки пропорций). */
    public Table rows(String hint) {
        rowsHint = hint;
        return this;
    }

    // ---------------------------------------------------------------- чтение

    public String name() {
        return name;
    }

    public String group() {
        return group;
    }

    public String descRu() {
        return descRu;
    }

    public String rowsHint() {
        return rowsHint;
    }

    public List<Column> columns() {
        return Collections.unmodifiableList(columns);
    }

    public int width() {
        return columns.size();
    }

    public int indexOf(String col) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).name().equals(col)) return i;
        throw new IllegalArgumentException(name + ": нет поля " + col);
    }

    public Column column(String col) {
        return columns.get(indexOf(col));
    }

    public List<String> primaryKey() {
        return primaryKey;
    }

    public List<List<String>> uniques() {
        return Collections.unmodifiableList(uniques);
    }

    public List<List<String>> indexes() {
        return Collections.unmodifiableList(indexes);
    }

    public List<Check> checks() {
        return Collections.unmodifiableList(checks);
    }

    public List<String> orderBy() {
        return orderBy != null ? orderBy : primaryKey;
    }

    /** Поля порядка, в которых NULL — последним (см. {@link #nullsLast(String...)}). */
    public List<String> nullsLast() {
        return nullsLast;
    }

    /** Все внешние ключи: одиночные (из {@code ref}) и составные, в порядке описания. */
    public List<ForeignKey> foreignKeys(Schema schema) {
        List<ForeignKey> out = new ArrayList<>();
        for (Column c : columns) {
            if (c.refTable() != null) {
                Table t = schema.table(c.refTable());
                out.add(new ForeignKey(List.of(c.name()), t.name(), t.primaryKey()));
            }
        }
        for (ForeignKey f : compositeFks) {
            out.add(new ForeignKey(f.columns(), f.refTable(), schema.table(f.refTable()).primaryKey()));
        }
        return out;
    }

    public String header() {
        StringBuilder sb = new StringBuilder();
        for (Column c : columns) {
            if (!sb.isEmpty()) sb.append('\t');
            sb.append(c.name());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return name;
    }
}
