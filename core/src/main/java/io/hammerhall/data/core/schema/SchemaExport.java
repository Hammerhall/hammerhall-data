package io.hammerhall.data.core.schema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Выгрузки из описания схемы ({@code ADR-024}, п. 11): DDL двумя файлами (таблицы; ключи, связи, проверки, индексы),
 * ER-диаграмма Mermaid, поля для SOURCE.md. Простой SQL без команд psql — годится базовой миграцией {@code V1__}.
 */
public final class SchemaExport {
    private SchemaExport() {
    }

    private static String q(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String ident(String s) {
        return s.length() <= 63 ? s : s.substring(0, 63);
    }

    // ---------------------------------------------------------------- DDL: таблицы

    public static String tablesSql(Schema s) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ").append(s.version()).append(" — таблицы. Сгенерировано из описания схемы; руками не править.\n");
        sb.append("-- Порядок развёртывания: schema-tables.sql → загрузка TSV (COPY) → schema-constraints.sql.\n");
        sb.append("-- Моменты — timestamptz; в TSV они — секунды Unix (UTC): загрузчик переводит to_timestamp(...).\n");
        sb.append("-- Деньги — numeric(14,2), рубли; пустое поле TSV — NULL.\n");
        String group = null;
        for (Table t : s.tables()) {
            if (!t.group().equals(group)) {
                group = t.group();
                sb.append("\n-- ").append("=".repeat(70)).append("\n-- ").append(s.groups().get(group)).append('\n');
            }
            sb.append("\nCREATE TABLE ").append(t.name()).append(" (\n");
            int w = 0;
            for (Column c : t.columns()) w = Math.max(w, c.name().length());
            List<Column> cols = t.columns();
            for (int i = 0; i < cols.size(); i++) {
                Column c = cols.get(i);
                sb.append("    ").append(c.name()).append(" ".repeat(w - c.name().length() + 1)).append(c.type().sql(c));
                if (!c.nullable()) sb.append(" NOT NULL");
                if (i < cols.size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append(");\n");
            sb.append("COMMENT ON TABLE ").append(t.name()).append(" IS ").append(q(t.descRu())).append(";\n");
            for (Column c : t.columns()) {
                sb.append("COMMENT ON COLUMN ").append(t.name()).append('.').append(c.name()).append(" IS ")
                        .append(q(fieldNote(c))).append(";\n");
            }
        }
        return sb.toString();
    }

    private static String fieldNote(Column c) {
        String d = c.descRu();
        if (c.type() == ColType.MOMENT) d += " (в TSV — секунды Unix, UTC)";
        if (c.isEnum()) d += ": " + String.join(", ", c.values());
        return d;
    }

    // ---------------------------------------------------------------- DDL: ограничения и индексы

    public static String constraintsSql(Schema s) {
        StringBuilder pk = new StringBuilder(), uq = new StringBuilder(), ck = new StringBuilder(),
                fk = new StringBuilder(), ix = new StringBuilder();
        for (Table t : s.tables()) {
            pk.append("ALTER TABLE ").append(t.name()).append(" ADD CONSTRAINT ").append(ident(t.name() + "_pkey"))
                    .append(" PRIMARY KEY (").append(String.join(", ", t.primaryKey())).append(");\n");
            for (List<String> u : t.uniques()) {
                uq.append("ALTER TABLE ").append(t.name()).append(" ADD CONSTRAINT ")
                        .append(ident(t.name() + "_" + String.join("_", u) + "_key")).append(" UNIQUE (")
                        .append(String.join(", ", u)).append(");\n");
            }
            for (Column c : t.columns()) {
                if (!c.isEnum()) continue;
                List<String> vs = new ArrayList<>();
                for (String v : c.values()) vs.add(q(v));
                ck.append("ALTER TABLE ").append(t.name()).append(" ADD CONSTRAINT ")
                        .append(ident(t.name() + "_" + c.name() + "_check")).append(" CHECK (").append(c.name())
                        .append(" IN (").append(String.join(", ", vs)).append("));\n");
            }
            for (Table.Check c : t.checks()) {
                ck.append("ALTER TABLE ").append(t.name()).append(" ADD CONSTRAINT ")
                        .append(ident(t.name() + "_" + c.name() + "_check")).append(" CHECK (").append(c.sql())
                        .append(");\n");
            }
            Set<List<String>> idx = new LinkedHashSet<>();
            for (Table.ForeignKey f : t.foreignKeys(s)) {
                fk.append("ALTER TABLE ").append(t.name()).append(" ADD CONSTRAINT ")
                        .append(ident(t.name() + "_" + String.join("_", f.columns()) + "_fkey"))
                        .append(" FOREIGN KEY (").append(String.join(", ", f.columns())).append(") REFERENCES ")
                        .append(f.refTable()).append(" (").append(String.join(", ", f.refColumns())).append(");\n");
                if (!isPrefix(f.columns(), t.primaryKey())) idx.add(f.columns());
            }
            idx.addAll(t.indexes());
            for (List<String> i : idx) {
                boolean coveredByUnique = false;
                for (List<String> u : t.uniques()) if (isPrefix(i, u)) coveredByUnique = true;
                if (coveredByUnique || isPrefix(i, t.primaryKey())) continue;
                ix.append("CREATE INDEX ").append(ident(t.name() + "_" + String.join("_", i) + "_idx")).append(" ON ")
                        .append(t.name()).append(" (").append(String.join(", ", i)).append(");\n");
            }
        }
        return "-- " + s.version() + " — ключи, проверки, внешние ключи, индексы. Выполнять после загрузки данных.\n"
                + "-- Сгенерировано из описания схемы; руками не править.\n\n"
                + "-- первичные ключи\n" + pk + "\n-- уникальность\n" + uq + "\n-- допустимые значения и проверки\n" + ck
                + "\n-- внешние ключи\n" + fk + "\n-- индексы (внешние ключи и поиск по времени)\n" + ix;
    }

    private static boolean isPrefix(List<String> a, List<String> b) {
        if (a.size() > b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equals(b.get(i))) return false;
        return true;
    }

    // ---------------------------------------------------------------- загрузчик

    /**
     * Загрузчик для psql: {@code psql -d БАЗА -f load-postgres.psql} из папки выпуска. По таблице — временная таблица
     * (моменты — bigint), {@code \\copy … FROM PROGRAM 'gzip -dc …'} на стороне клиента (суперпользователь не нужен),
     * перенос с {@code to_timestamp}. Сначала — schema-tables.sql, после — schema-constraints.sql.
     */
    public static String loaderPsql(Schema s, java.util.Collection<String> present) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ").append(s.version()).append(" — загрузка в Postgres (psql, из папки выпуска; нужен gzip в PATH)\n");
        sb.append("--   psql -v ON_ERROR_STOP=1 -d БАЗА -f schema-tables.sql -f load-postgres.psql -f schema-constraints.sql\n");
        sb.append("\\set ON_ERROR_STOP 1\nSET client_encoding = 'UTF8';\n");
        for (Table t : s.tables()) {
            if (!present.contains(t.name())) continue;
            String stage = "_load_" + t.name();
            sb.append("\n-- ").append(t.name()).append('\n');
            sb.append("CREATE TEMP TABLE ").append(stage).append(" (");
            List<String> sel = new ArrayList<>();
            List<Column> cols = t.columns();
            for (int i = 0; i < cols.size(); i++) {
                Column c = cols.get(i);
                if (i > 0) sb.append(", ");
                sb.append(c.name()).append(' ').append(c.type() == ColType.MOMENT ? "bigint" : c.type().sql(c));
                sel.add(c.type() == ColType.MOMENT ? "to_timestamp(" + c.name() + ")" : c.name());
            }
            sb.append(");\n");
            sb.append("\\copy ").append(stage).append(" FROM PROGRAM 'gzip -dc ").append(t.name())
                    .append(".tsv.gz' WITH (FORMAT text, HEADER true, NULL '')\n");
            sb.append("INSERT INTO ").append(t.name()).append(" SELECT ").append(String.join(", ", sel)).append(" FROM ")
                    .append(stage).append(";\nDROP TABLE ").append(stage).append(";\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- Mermaid

    public static String mermaid(Schema s) {
        StringBuilder sb = new StringBuilder();
        sb.append("%% ").append(s.version()).append(" — ER-диаграмма; сгенерировано из описания схемы\n");
        sb.append("erDiagram\n");
        for (Table t : s.tables()) {
            sb.append("    ").append(t.name()).append(" {\n");
            Set<String> fkCols = new LinkedHashSet<>();
            for (Table.ForeignKey f : t.foreignKeys(s)) fkCols.addAll(f.columns());
            for (Column c : t.columns()) {
                boolean isPk = t.primaryKey().contains(c.name()), isFk = fkCols.contains(c.name());
                String key = isPk && isFk ? " PK, FK" : isPk ? " PK" : isFk ? " FK" : "";
                sb.append("        ").append(c.type().shortName(c)).append(' ').append(c.name()).append(key)
                        .append(" \"").append(c.descRu().replace("\"", "'")).append("\"\n");
            }
            sb.append("    }\n");
        }
        for (Table t : s.tables()) {
            for (Table.ForeignKey f : t.foreignKeys(s)) {
                boolean optional = false;
                for (String c : f.columns()) optional |= t.column(c).nullable();
                sb.append("    ").append(f.refTable()).append(optional ? " |o--o{ " : " ||--o{ ").append(t.name())
                        .append(" : \"").append(String.join(", ", f.columns())).append("\"\n");
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- поля для SOURCE.md

    public static String fieldsMarkdown(Schema s) {
        StringBuilder sb = new StringBuilder();
        String group = null;
        for (Table t : s.tables()) {
            if (!t.group().equals(group)) {
                group = t.group();
                sb.append("\n### ").append(s.groups().get(group)).append("\n");
            }
            sb.append("\n#### `").append(t.name()).append("` — ").append(t.descRu()).append("\n\n");
            sb.append("Ключ — `").append(String.join(", ", t.primaryKey())).append("`; строки упорядочены по `")
                    .append(String.join(", ", t.orderBy())).append("`.\n\n");
            sb.append("| поле | тип | NULL | что | откуда |\n|---|---|---|---|---|\n");
            for (Column c : t.columns()) {
                String type = c.type().shortName(c);
                if (c.refTable() != null) type += " → `" + c.refTable() + "`";
                String what = c.descRu();
                if (c.isEnum()) what += ": " + String.join(", ", c.values().stream().map(v -> "`" + v + "`").toList());
                sb.append("| `").append(c.name()).append("` | ").append(type).append(" | ")
                        .append(c.nullable() ? "да" : "").append(" | ").append(what.replace("|", "\\|")).append(" | ")
                        .append(c.source() == null ? "генератор" : c.source()).append(" |\n");
            }
            for (Table.ForeignKey f : t.foreignKeys(s)) {
                if (f.columns().size() > 1) {
                    sb.append("\nСоставная ссылка: `(").append(String.join(", ", f.columns())).append(")` → `")
                            .append(f.refTable()).append("`.\n");
                }
            }
        }
        return sb.toString();
    }
}
