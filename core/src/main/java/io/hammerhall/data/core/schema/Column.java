package io.hammerhall.data.core.schema;

import java.util.List;

/**
 * Поле таблицы.
 *
 * @param name       имя (snake_case, латиница)
 * @param type       тип
 * @param nullable   может ли быть NULL (пустое поле в TSV)
 * @param precision  для DECIMAL — всего цифр
 * @param scale      для DECIMAL и DOUBLE — знаков после точки
 * @param values     допустимые значения (перечисление) или пусто
 * @param refTable   таблица, на первичный ключ которой ссылается поле, или {@code null}
 * @param descRu     описание по-русски — в DDL (COMMENT), SOURCE.md и ER-диаграмму
 * @param source     откуда значение: {@code null} — сгенерировано, иначе — источник (например, {@code air-data-v1})
 */
public record Column(String name, ColType type, boolean nullable, int precision, int scale, List<String> values,
                     String refTable, String descRu, String source) {

    public Column {
        values = values == null ? List.of() : List.copyOf(values);
    }

    Column withNullable(boolean n) {
        return new Column(name, type, n, precision, scale, values, refTable, descRu, source);
    }

    Column withValues(List<String> v) {
        return new Column(name, type, nullable, precision, scale, v, refTable, descRu, source);
    }

    Column withRef(String t) {
        return new Column(name, type, nullable, precision, scale, values, t, descRu, source);
    }

    Column withScale(int p, int s) {
        return new Column(name, type, nullable, p, s, values, refTable, descRu, source);
    }

    Column withSource(String s) {
        return new Column(name, type, nullable, precision, scale, values, refTable, descRu, s);
    }

    public boolean isEnum() {
        return !values.isEmpty();
    }
}
