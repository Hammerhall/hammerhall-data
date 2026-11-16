package io.hammerhall.data.core.io;

import io.hammerhall.data.core.check.Integrity;
import io.hammerhall.data.core.schema.Column;
import io.hammerhall.data.core.schema.Table;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Приёмник строк одной таблицы. Модуль кладёт строку значениями в порядке полей описания ({@link #add}); приёмник
 * проверяет типы, NULL, перечисления, порядок, ключи и ссылки и пишет {@code <таблица>.tsv.gz}.
 *
 * <ul>
 *   <li>{@link Mode#SORT} — строки копятся в памяти и сортируются по {@link Table#orderBy()} при закрытии: для
 *       справочников и таблиц до нескольких миллионов строк;</li>
 *   <li>{@link Mode#STREAM} — модуль сам выдаёт строки в порядке {@code orderBy} (например, сутки за сутками по
 *       возрастанию id), приёмник пишет сразу и падает на первой строке не по порядку: для больших таблиц.</li>
 * </ul>
 * Уникальность ключа и UNIQUE, ссылки — проверяет {@link Integrity} (по порядку строк, экономно по памяти).
 * Таблицу, на которую ссылаются, выгодно закрыть ({@link #close()}) сразу, как она дописана, не дожидаясь конца
 * модуля: ссылки на неё дальше проверяются сразу, не копятся.
 */
public final class TableSink {
    public enum Mode {SORT, STREAM}

    private final Table table;
    private final Mode mode;
    private final GzipTsvFile file;
    private final Integrity integrity;
    private final Comparator<Object[]> order;
    private final List<Object[]> buffer = new ArrayList<>();
    private final StringBuilder sb = new StringBuilder(256);
    private Object[] last;
    private long rows;
    private GzipTsvFile.Info info;

    public TableSink(Table table, Mode mode, Path dir, int gzipLevel, Integrity integrity) throws IOException {
        this.table = table;
        this.mode = mode;
        this.integrity = integrity;
        this.order = RowFormat.comparator(table);
        this.file = new GzipTsvFile(dir.resolve(table.name() + ".tsv.gz"), gzipLevel);
        this.file.line(table.header());
        integrity.begin(table);
    }

    public Table table() {
        return table;
    }

    public long rows() {
        return rows + buffer.size();
    }

    /** Строка — значения в порядке полей таблицы. */
    public void add(Object... values) throws IOException {
        if (info != null) throw new IllegalStateException(table + ": приёмник уже закрыт");
        if (values.length != table.width()) {
            throw new IllegalArgumentException(table + ": полей " + values.length + ", а в описании " + table.width());
        }
        if (mode == Mode.SORT) {
            buffer.add(values);
        } else {
            write(values);
        }
    }

    private void write(Object[] values) throws IOException {
        if (last != null) {
            int c = order.compare(last, values);
            if (c > 0) {
                throw new IllegalStateException(table + ": строка не по порядку " + table.orderBy() + ": "
                        + java.util.Arrays.toString(values) + " после " + java.util.Arrays.toString(last));
            }
        }
        last = values;
        sb.setLength(0);
        List<Column> cols = table.columns();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append('\t');
            RowFormat.append(sb, table, cols.get(i), values[i]);
        }
        file.line(sb);
        integrity.row(table, values);
        rows++;
    }

    /**
     * Дописать (для SORT — отсортировать и записать) и закрыть файл; повторный вызов — тот же итог. Закрытие
     * запечатывает таблицу в проверках: отложенные ссылки на неё проверяются, новые — сразу.
     */
    public GzipTsvFile.Info close() throws IOException {
        if (info != null) return info;
        if (mode == Mode.SORT) {
            buffer.sort(order);
            for (Object[] r : buffer) write(r);
            buffer.clear();
        }
        info = file.finish();
        integrity.end(table);
        return info;
    }

    public boolean closed() {
        return info != null;
    }
}
