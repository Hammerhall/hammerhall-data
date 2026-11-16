package io.hammerhall.data.core.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Схема домена: таблицы в порядке описания (он же порядок в DDL, манифесте и SOURCE.md). */
public final class Schema {
    private final String domain;
    private final String version;
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final Map<String, String> groupTitles = new LinkedHashMap<>();

    public Schema(String domain, String version) {
        this.domain = domain;
        this.version = version;
    }

    public String domain() {
        return domain;
    }

    public String version() {
        return version;
    }

    public Schema group(String code, String titleRu) {
        groupTitles.put(code, titleRu);
        return this;
    }

    public Map<String, String> groups() {
        return Collections.unmodifiableMap(groupTitles);
    }

    public Schema add(Table t) {
        if (tables.putIfAbsent(t.name(), t) != null) throw new IllegalArgumentException("таблица дважды: " + t.name());
        return this;
    }

    public Table table(String name) {
        Table t = tables.get(name);
        if (t == null) throw new IllegalArgumentException("нет таблицы " + name);
        return t;
    }

    public boolean has(String name) {
        return tables.containsKey(name);
    }

    public List<Table> tables() {
        return new ArrayList<>(tables.values());
    }

    /** Проверка самого описания: ключи, ссылки, перечисления, порядок строк. Пустой список — описание цельное. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        for (Table t : tables.values()) {
            if (!groupTitles.containsKey(t.group())) errors.add(t.name() + ": неизвестная группа " + t.group());
            if (t.primaryKey().isEmpty()) errors.add(t.name() + ": нет первичного ключа");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Column c : t.columns()) {
                if (!seen.add(c.name())) errors.add(t.name() + "." + c.name() + ": поле дважды");
                if (!c.name().matches("[a-z][a-z0-9_]*")) errors.add(t.name() + "." + c.name() + ": имя не snake_case");
                if (c.isEnum() && c.type() != ColType.TEXT) errors.add(t.name() + "." + c.name() + ": перечисление не TEXT");
                if (c.descRu() == null || c.descRu().isBlank()) errors.add(t.name() + "." + c.name() + ": нет описания");
                if (c.refTable() != null) {
                    if (!tables.containsKey(c.refTable())) {
                        errors.add(t.name() + "." + c.name() + ": ссылка на неизвестную таблицу " + c.refTable());
                    } else if (tables.get(c.refTable()).primaryKey().size() != 1) {
                        errors.add(t.name() + "." + c.name() + ": ссылка на составной ключ — через fk(...)");
                    } else {
                        Table rt = tables.get(c.refTable());
                        Column target = rt.column(rt.primaryKey().getFirst());
                        if (target.type() != c.type()) {
                            errors.add(t.name() + "." + c.name() + ": тип " + c.type() + " ≠ " + rt.name() + "."
                                    + target.name() + " " + target.type());
                        }
                    }
                }
            }
            for (String k : t.primaryKey()) {
                if (!seen.contains(k)) errors.add(t.name() + ": поле ключа " + k + " не описано");
                else if (t.column(k).nullable()) errors.add(t.name() + ": поле ключа " + k + " допускает NULL");
            }
            for (String k : t.orderBy()) if (!seen.contains(k)) errors.add(t.name() + ": поле порядка " + k + " не описано");
            for (List<String> u : t.uniques()) for (String k : u) if (!seen.contains(k)) errors.add(t.name() + ": уникальность по " + k);
            for (List<String> u : t.indexes()) for (String k : u) if (!seen.contains(k)) errors.add(t.name() + ": индекс по " + k);
            try {
                for (Table.ForeignKey f : t.foreignKeys(this)) {
                    if (f.refColumns().size() != f.columns().size()) {
                        errors.add(t.name() + ": внешний ключ " + f.columns() + " → " + f.refTable() + f.refColumns()
                                + " — разное число полей");
                    }
                }
            } catch (IllegalArgumentException e) {
                errors.add(t.name() + ": " + e.getMessage());
            }
        }
        return errors;
    }
}
