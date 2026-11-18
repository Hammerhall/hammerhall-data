package io.hammerhall.data.core.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Схема домена: таблицы в порядке описания (он же порядок в DDL, манифесте, SOURCE.md и на картинках схемы) и группы
 * таблиц. Группа-справочник ({@link #group(String, String, boolean)}) — то, на что ссылаются отовсюду (города, правила
 * дома): на обзорной карте связи в неё не рисуются, иначе карта превращается в паутину.
 */
public final class Schema {
    private final String domain;
    private final String version;
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final Map<String, String> groupTitles = new LinkedHashMap<>();
    private final java.util.Set<String> referenceGroups = new java.util.LinkedHashSet<>();

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

    /**
     * Группа таблиц: код (латиница — часть имени файла картинки группы) и название по-русски (заголовок раздела DDL и
     * SOURCE.md). На рамке картинки — название до двоеточия ({@link #groupLabel}): «Сеть: аэропорты, …» → «Сеть».
     */
    public Schema group(String code, String titleRu) {
        return group(code, titleRu, false);
    }

    /** Группа таблиц; {@code reference} — справочник: на обзорной карте связи в него не рисуются. */
    public Schema group(String code, String titleRu, boolean reference) {
        if (groupTitles.putIfAbsent(code, titleRu) != null) throw new IllegalArgumentException("группа дважды: " + code);
        if (reference) referenceGroups.add(code);
        return this;
    }

    public Map<String, String> groups() {
        return Collections.unmodifiableMap(groupTitles);
    }

    /** Группа — справочник (см. {@link #group(String, String, boolean)}). */
    public boolean isReference(String code) {
        return referenceGroups.contains(code);
    }

    /** Подпись группы на рамке картинки: название до двоеточия. */
    public String groupLabel(String code) {
        String t = groupTitles.get(code);
        if (t == null) throw new IllegalArgumentException("нет группы " + code);
        int colon = t.indexOf(':');
        return (colon < 0 ? t : t.substring(0, colon)).strip();
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
            if (t.title() == null || t.title().isBlank()) {
                errors.add(t.name() + ": нет короткого названия — title(\"…\"), шапка таблицы на картинке схемы");
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Column c : t.columns()) {
                if (!seen.add(c.name())) errors.add(t.name() + "." + c.name() + ": поле дважды");
                if (!c.name().matches("[a-z][a-z0-9_]*")) errors.add(t.name() + "." + c.name() + ": имя не snake_case");
                if (c.isEnum() && c.type() != ColType.TEXT) errors.add(t.name() + "." + c.name() + ": перечисление не TEXT");
                if (c.descRu() == null || c.descRu().isBlank()) errors.add(t.name() + "." + c.name() + ": нет описания");
                if (c.verbatim() && (c.source() == null || c.source().isBlank())) {
                    errors.add(t.name() + "." + c.name() + ": verbatimSource без source — откуда значения как есть?");
                }
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
            for (String k : t.nullsLast()) if (!t.orderBy().contains(k)) errors.add(t.name() + ": NULLS LAST по " + k + " — не поле порядка");
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
            errors.addAll(linkErrors(t, seen));
        }
        return errors;
    }

    /** Смысловые связи таблицы: поля есть, цель есть, число и типы полей совпадают, внешним ключом связь не дублируется. */
    private List<String> linkErrors(Table t, java.util.Set<String> seen) {
        List<String> errors = new ArrayList<>();
        List<Table.Link> links;
        try {
            links = t.links(this);
        } catch (IllegalArgumentException e) {
            return List.of(t.name() + ": смысловая связь — " + e.getMessage());
        }
        java.util.Set<List<String>> fkCols = new java.util.HashSet<>();
        try {
            for (Table.ForeignKey f : t.foreignKeys(this)) fkCols.add(f.columns());
        } catch (IllegalArgumentException e) {
            // ошибку внешнего ключа уже записали выше
        }
        for (Table.Link l : links) {
            String where = t.name() + " " + l.columns() + " ⇢ " + l.refTable() + l.refColumns();
            Table rt = tables.get(l.refTable());
            if (l.columns().isEmpty() || l.columns().size() != l.refColumns().size()) {
                errors.add(where + ": смысловая связь — разное число полей");
                continue;
            }
            for (int i = 0; i < l.columns().size(); i++) {
                String c = l.columns().get(i), r = l.refColumns().get(i);
                if (!seen.contains(c)) {
                    errors.add(where + ": нет поля " + c);
                } else if (!rt.columns().stream().anyMatch(x -> x.name().equals(r))) {
                    errors.add(where + ": у " + rt.name() + " нет поля " + r);
                } else if (t.column(c).type() != rt.column(r).type()) {
                    errors.add(where + ": тип " + c + " " + t.column(c).type() + " ≠ " + rt.column(r).type());
                }
            }
            if (fkCols.contains(l.columns())) errors.add(where + ": у этих полей уже есть внешний ключ");
        }
        return errors;
    }
}
