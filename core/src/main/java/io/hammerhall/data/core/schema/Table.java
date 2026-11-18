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
 * Table.of("products", "каталог", "Товары: модели, их поколения и варианты").title("Товары")
 *     .col("id", INT, "Товар")
 *     .col("brand_id", INT, "Бренд").ref("brands")
 *     .col("discontinued_on", DATE, "Снят с продажи").nullable()
 *     .col("kind", TEXT, "Вид").values("a", "b")
 *     .pk("id").unique("sku");
 * }</pre>
 * Модификаторы ({@code nullable}, {@code ref}, {@code values}, {@code scale}, {@code source}, {@code verbatimSource})
 * относятся к последнему полю. Порядок строк в TSV — {@link #orderBy()}, по умолчанию — первичный ключ. NULL в поле порядка идёт первым
 * ({@code NULLS FIRST}), в полях из {@link #nullsLast(String...)} — последним ({@code NULLS LAST}).
 *
 * <p>{@link #title(String)} — короткое русское название во множественном числе («Товары», «Бронирования»): шапка
 * таблицы на картинке схемы; обязательно. {@link #link(List, String)} — смысловая связь без внешнего ключа (ссылка есть
 * по смыслу, но ключа в DDL нет: например, на версионный ключ): только для картинки, пунктиром.
 */
public final class Table {
    public record ForeignKey(List<String> columns, String refTable, List<String> refColumns) {
    }

    public record Check(String name, String sql) {
    }

    /**
     * Смысловая связь без внешнего ключа: поля {@code columns} указывают на {@code refColumns} таблицы
     * {@code refTable}, но в DDL ключа нет. Рисуется на картинке схемы пунктиром; в DDL, проверки ссылок и Mermaid не
     * идёт.
     */
    public record Link(List<String> columns, String refTable, List<String> refColumns) {
    }

    private final String name;
    private final String group;
    private final String descRu;
    private String title;
    private final List<Column> columns = new ArrayList<>();
    private List<String> primaryKey = List.of();
    private final List<List<String>> uniques = new ArrayList<>();
    private final List<List<String>> indexes = new ArrayList<>();
    private final List<ForeignKey> compositeFks = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
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

    /** Короткое русское название во множественном числе — шапка таблицы на картинке схемы («Товары»). */
    public Table title(String titleRu) {
        title = titleRu;
        return this;
    }

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

    /**
     * Смысловая связь без внешнего ключа (см. {@link Link}) — на первые {@code cols.size()} полей первичного ключа
     * {@code refTable}: например, номер маршрута рейса → маршрут с версионным ключом (номер, начало действия).
     */
    public Table link(List<String> cols, String refTable) {
        links.add(new Link(List.copyOf(cols), refTable, null));
        return this;
    }

    /** Смысловая связь без внешнего ключа на поля {@code refCols} таблицы {@code refTable} (например, на UNIQUE). */
    public Table link(List<String> cols, String refTable, List<String> refCols) {
        links.add(new Link(List.copyOf(cols), refTable, List.copyOf(refCols)));
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

    /** Короткое русское название ({@link #title(String)}); {@code null} — не задано. */
    public String title() {
        return title;
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

    /**
     * Смысловые связи без внешнего ключа ({@link #link}), в порядке описания; поля цели — заданные или начало первичного
     * ключа цели.
     */
    public List<Link> links(Schema schema) {
        List<Link> out = new ArrayList<>();
        for (Link l : links) {
            List<String> ref = l.refColumns();
            if (ref == null) {
                List<String> pk = schema.table(l.refTable()).primaryKey();
                ref = pk.subList(0, Math.min(pk.size(), l.columns().size()));
            }
            out.add(new Link(l.columns(), l.refTable(), List.copyOf(ref)));
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
