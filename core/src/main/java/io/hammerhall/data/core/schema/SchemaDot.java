package io.hammerhall.data.core.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Схема картинкой: файлы Graphviz (DOT) из описания схемы — в духе схемы демобазы «Авиаперевозки». Картинки (SVG)
 * рисует {@code tools/schema-svg.sh <код>} и кладёт рядом; в SVG — отметка sha256 исходного DOT
 * ({@link #svgMarker}), по ней тест набора видит, что картинка устарела.
 *
 * <ul>
 *   <li>Таблиц не больше {@value #MAX_TABLES} — одна картинка {@code schema.dot}: все таблицы со всеми полями, группы —
 *       серыми скруглёнными рамками.</li>
 *   <li>Больше — карта {@code schema.dot} (все таблицы без полей, группы, связи от таблицы к таблице — одна стрелка на
 *       пару, связи в справочные группы не рисуются) и по картинке на группу {@code schema-<код группы>.dot}: все поля
 *       её таблиц и таблицы других групп, на которые они ссылаются (серой рамкой, только шапка); связи — только от
 *       таблиц группы. Кто ссылается на группу — на картинке своей группы: каждая связь — ровно на одной картинке
 *       группы. В группе — не больше {@value #MAX_TABLES} таблиц.</li>
 * </ul>
 * Шапка таблицы — серая: имя жирным, под ним короткое русское название ({@link Table#title()}). Строка поля — значок и
 * имя: {@code #} — поле первичного ключа, {@code ∗} — обязательное, {@code ○} — может быть пустым. Связь — от поля к
 * полю: «вилка» у таблицы «многих», у таблицы «одного» — без значка; пунктир — ссылка может быть пустой или связь
 * смысловая ({@link Table#link}). Порядок таблиц, групп и связей — как в описании схемы: тот же вход — тот же файл.
 */
public final class SchemaDot {
    /** Предел таблиц на одной картинке со всеми полями: всей схемы — или одной группы. */
    public static final int MAX_TABLES = 12;
    /** Отметка в SVG: {@code <!-- schema-dot sha256: … -->} — sha256 DOT, из которого она нарисована. */
    public static final String MARKER = "schema-dot sha256:";

    static final String FONT = "DejaVu Sans";
    /**
     * Значки полей — моноширинным: имена полей встают в столбец. Пробел после значка — внутри его шрифта: Graphviz ставит
     * имя поля отдельным текстом с координатой после пробела, и отступ не теряется там, где SVG схлопывает пробелы в
     * начале текста.
     */
    static final String MARK_FONT = "DejaVu Sans Mono";
    static final String HEADER_BG = "#cfcfcf";
    static final String GROUP_FILL = "#ececec";
    static final String GROUP_LINE = "#bbbbbb";
    static final String NEIGHBOR_LINE = "#999999";
    static final String NEIGHBOR_TEXT = "#666666";

    private SchemaDot() {
    }

    /**
     * Связь для картинки: {@code child.columns} → {@code parent.refColumns}. {@code optional} — какое-то поле ссылки
     * может быть пустым; {@code semantic} — смысловая связь без внешнего ключа.
     */
    record Rel(Table child, List<String> columns, Table parent, List<String> refColumns, boolean optional,
               boolean semantic) {
        boolean dashed() {
            return optional || semantic;
        }

        boolean self() {
            return child == parent;
        }
    }

    // ---------------------------------------------------------------- файлы

    /** Имя файла DOT картинки группы. */
    public static String groupFile(String group) {
        return "schema-" + group + ".dot";
    }

    /** Файлы DOT схемы: имя → текст, в порядке картинок ({@code schema.dot}, затем группы по порядку описания). */
    public static Map<String, String> files(Schema s) {
        List<String> errors = s.validate();
        if (!errors.isEmpty()) throw new IllegalStateException("описание схемы: " + errors);
        Map<String, String> out = new LinkedHashMap<>();
        List<Table> all = s.tables();
        if (all.size() <= MAX_TABLES) {
            out.put("schema.dot", whole(s));
            return out;
        }
        out.put("schema.dot", overview(s));
        for (String g : s.groups().keySet()) {
            List<Table> own = inGroup(s, g);
            if (own.isEmpty()) continue;
            if (own.size() > MAX_TABLES) {
                throw new IllegalStateException("картинка группы «" + s.groupLabel(g) + "» (" + g + "): " + own.size()
                        + " таблиц, предел — " + MAX_TABLES + "; разделите группу в описании схемы");
            }
            out.put(groupFile(g), group(s, g));
        }
        return out;
    }

    /** Картинки схемы (SVG) по порядку: те же имена, что у {@link #files}, с {@code .svg}. */
    public static List<String> pictures(Schema s) {
        List<String> out = new ArrayList<>();
        for (String f : files(s).keySet()) out.add(svgName(f));
        return out;
    }

    /** {@code schema-catalog.dot} → {@code schema-catalog.svg}. */
    public static String svgName(String dotName) {
        if (!dotName.endsWith(".dot")) throw new IllegalArgumentException("не DOT: " + dotName);
        return dotName.substring(0, dotName.length() - ".dot".length()) + ".svg";
    }

    /** sha256 текста DOT (UTF-8) — то, что {@code tools/schema-svg.sh} пишет в отметку SVG. */
    public static String sha256(String dot) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dot.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Отметка SVG для этого DOT: {@code <!-- schema-dot sha256: … -->}. */
    public static String svgMarker(String dot) {
        return "<!-- " + MARKER + " " + sha256(dot) + " -->";
    }

    /** sha256 DOT из отметки SVG (в начале файла, до {@code <svg}); нет отметки — {@code null}. */
    public static String markedSha256(String svg) {
        int end = svg.indexOf("<svg");
        String head = end < 0 ? svg : svg.substring(0, end);
        int i = head.indexOf("<!-- " + MARKER + " ");
        if (i < 0) return null;
        int from = i + ("<!-- " + MARKER + " ").length(), to = head.indexOf(" -->", from);
        return to < 0 ? null : head.substring(from, to).strip();
    }

    // ---------------------------------------------------------------- связи

    static List<Rel> relations(Schema s) {
        List<Rel> out = new ArrayList<>();
        for (Table t : s.tables()) {
            for (Table.ForeignKey f : t.foreignKeys(s)) {
                out.add(new Rel(t, f.columns(), s.table(f.refTable()), f.refColumns(), anyNullable(t, f.columns()), false));
            }
            for (Table.Link l : t.links(s)) {
                out.add(new Rel(t, l.columns(), s.table(l.refTable()), l.refColumns(), anyNullable(t, l.columns()), true));
            }
        }
        return out;
    }

    private static boolean anyNullable(Table t, List<String> cols) {
        for (String c : cols) if (t.column(c).nullable()) return true;
        return false;
    }

    private static List<Table> inGroup(Schema s, String g) {
        List<Table> out = new ArrayList<>();
        for (Table t : s.tables()) if (t.group().equals(g)) out.add(t);
        return out;
    }

    // ---------------------------------------------------------------- три вида картинок

    /** Вся схема со всеми полями (таблиц не больше {@link #MAX_TABLES}). */
    static String whole(Schema s) {
        StringBuilder sb = head(s, "схема: все таблицы со всеми полями, группы — рамками");
        for (String g : s.groups().keySet()) {
            List<Table> own = inGroup(s, g);
            if (own.isEmpty()) continue;
            openCluster(sb, s, g, false);
            for (Table t : own) sb.append("    ").append(id(t)).append(" [label=").append(full(t)).append("];\n");
            sb.append("  }\n");
        }
        for (Rel r : relations(s)) edge(sb, r, true, true);
        return sb.append("}\n").toString();
    }

    /** Карта: все таблицы без полей, группы, одна стрелка на пару таблиц, без связей в справочные группы. */
    static String overview(Schema s) {
        StringBuilder sb = head(s, "карта схемы: таблицы по группам без полей, связи между таблицами (одна на пару; "
                + "в справочные группы — не рисуются)");
        for (String g : s.groups().keySet()) {
            List<Table> own = inGroup(s, g);
            if (own.isEmpty()) continue;
            openCluster(sb, s, g, true);
            for (Table t : own) sb.append("    ").append(id(t)).append(" [label=").append(headerOnly(t)).append("];\n");
            sb.append("  }\n");
        }
        // одна стрелка на пару «многие → один»: сплошная, если хоть одна связь пары — обязательный внешний ключ
        Map<List<String>, Boolean> pairs = new LinkedHashMap<>();
        for (Rel r : relations(s)) {
            if (r.self() || s.isReference(r.parent().group())) continue;
            List<String> key = List.of(r.child().name(), r.parent().name());
            pairs.merge(key, r.dashed(), (a, b) -> a && b);
        }
        for (Map.Entry<List<String>, Boolean> p : pairs.entrySet()) {
            sb.append("  ").append(id(p.getKey().get(1))).append(" -> ").append(id(p.getKey().get(0)));
            sb.append(p.getValue() ? " [style=dashed];\n" : ";\n");
        }
        return sb.append("}\n").toString();
    }

    /**
     * Группа: все поля её таблиц и таблицы других групп, на которые они ссылаются, — серой рамкой; связи — только от
     * таблиц группы (кто ссылается на группу — на картинке своей группы, иначе у таблицы-«хаба» — пучок чужих линий).
     */
    static String group(Schema s, String g) {
        StringBuilder sb = head(s, "группа «" + s.groupLabel(g) + "»: все поля её таблиц; таблицы других групп, на которые "
                + "они ссылаются, — серой рамкой");
        List<Rel> rels = new ArrayList<>();
        Set<String> near = new LinkedHashSet<>();
        for (Rel r : relations(s)) {
            if (!r.child().group().equals(g)) continue;
            rels.add(r);
            if (!r.parent().group().equals(g)) near.add(r.parent().name());
        }
        openCluster(sb, s, g, false);
        for (Table t : inGroup(s, g)) sb.append("    ").append(id(t)).append(" [label=").append(full(t)).append("];\n");
        sb.append("  }\n");
        for (Table t : s.tables()) {
            if (near.contains(t.name())) sb.append("  ").append(id(t)).append(" [label=").append(neighbor(t)).append("];\n");
        }
        for (Rel r : rels) edge(sb, r, true, r.parent().group().equals(g));
        return sb.append("}\n").toString();
    }

    // ---------------------------------------------------------------- DOT

    private static StringBuilder head(Schema s, String what) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(s.version()).append(" — ").append(what).append(".\n");
        sb.append("// Сгенерировано из описания схемы; руками не править. Картинка (SVG): tools/schema-svg.sh ")
                .append(s.domain()).append(".\n");
        sb.append("digraph schema {\n");
        sb.append("  graph [rankdir=LR, fontname=\"").append(FONT).append("\", fontsize=13, nodesep=0.35, ranksep=0.9, ")
                .append("pad=0.3, splines=true, newrank=true];\n");
        sb.append("  node [shape=plain, fontname=\"").append(FONT).append("\", fontsize=11];\n");
        sb.append("  edge [dir=both, arrowtail=none, arrowhead=crow, arrowsize=0.9, color=\"#333333\"];\n");
        return sb;
    }

    private static void openCluster(StringBuilder sb, Schema s, String g, boolean overview) {
        sb.append("  subgraph ").append(id("cluster_" + g)).append(" {\n");
        sb.append("    label=<<B>").append(html(s.groupLabel(g))).append("</B>");
        if (overview && s.isReference(g)) {
            sb.append("<BR/><FONT POINT-SIZE=\"10\">справочник: связи с ним — на картинках групп</FONT>");
        }
        sb.append(">;\n");
        sb.append("    style=\"rounded,filled\"; fillcolor=\"").append(GROUP_FILL).append("\"; color=\"").append(GROUP_LINE)
                .append("\"; margin=14;\n");
    }

    /**
     * Связь: от поля «одного» к полю «многих» (порты — на своих картинках, у соседей и на карте — к шапке); вилка — у
     * «многих». Ссылка таблицы на себя — петля справа.
     */
    private static void edge(StringBuilder sb, Rel r, boolean childPorts, boolean parentPorts) {
        String from = id(r.parent()) + (parentPorts ? ":" + id(r.refColumns().getFirst()) : "");
        String to = id(r.child()) + (childPorts ? ":" + id(r.columns().getFirst()) : "");
        if (r.self()) {
            from += ":e";
            to += ":e";
        }
        sb.append("  ").append(from).append(" -> ").append(to).append(r.dashed() ? " [style=dashed];\n" : ";\n");
    }

    /** Шапка таблицы: имя жирным, под ним короткое русское название. */
    private static String headerCell(Table t) {
        return "<TR><TD BGCOLOR=\"" + HEADER_BG + "\" CELLPADDING=\"6\"><B>" + html(t.name()) + "</B><BR/>"
                + html(t.title()) + "</TD></TR>";
    }

    private static String full(Table t) {
        StringBuilder sb = new StringBuilder("<<TABLE BORDER=\"1\" CELLBORDER=\"0\" CELLSPACING=\"0\" CELLPADDING=\"3\" "
                + "BGCOLOR=\"white\">");
        sb.append(headerCell(t)).append("<TR><TD CELLPADDING=\"1\"></TD></TR>");
        for (Column c : t.columns()) {
            String mark = t.primaryKey().contains(c.name()) ? "#" : c.nullable() ? "○" : "∗";
            sb.append("<TR><TD ALIGN=\"LEFT\" PORT=\"").append(html(c.name())).append("\"><FONT FACE=\"").append(MARK_FONT)
                    .append("\">").append(mark).append(" </FONT>").append(html(c.name())).append("</TD></TR>");
        }
        return sb.append("<TR><TD CELLPADDING=\"1\"></TD></TR></TABLE>>").toString();
    }

    private static String headerOnly(Table t) {
        return "<<TABLE BORDER=\"1\" CELLBORDER=\"0\" CELLSPACING=\"0\" CELLPADDING=\"3\" BGCOLOR=\"white\">"
                + headerCell(t) + "</TABLE>>";
    }

    private static String neighbor(Table t) {
        return "<<TABLE BORDER=\"1\" CELLBORDER=\"0\" CELLSPACING=\"0\" CELLPADDING=\"5\" COLOR=\"" + NEIGHBOR_LINE
                + "\" BGCOLOR=\"white\"><TR><TD><FONT COLOR=\"" + NEIGHBOR_TEXT + "\">" + html(t.name()) + "<BR/>"
                + html(t.title()) + "</FONT></TD></TR></TABLE>>";
    }

    private static String id(Table t) {
        return id(t.name());
    }

    /** Имя в DOT — всегда в кавычках: имя таблицы может совпасть со словом языка ({@code node}, {@code graph}). */
    private static String id(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------------------------------------------------------------- текст для SOURCE.md

    /**
     * Абзац «Схема» для SOURCE.md выпуска: какие картинки лежат в выпуске и как их читать. Без заголовка — набор ставит
     * его сам. Строки — не длиннее 118 знаков, как в остальном SOURCE.md.
     */
    public static String sourceMd(Schema s) {
        StringBuilder sb = new StringBuilder();
        if (pictures(s).size() == 1) {
            sb.append("Картинка схемы — `schema.svg`: все таблицы со всеми полями, группы — серыми рамками. ");
        } else {
            List<String> refs = new ArrayList<>();
            for (String g : s.groups().keySet()) {
                if (s.isReference(g) && !inGroup(s, g).isEmpty()) refs.add("«" + s.groupLabel(g) + "»");
            }
            sb.append("Картинки схемы (SVG): `schema.svg` — карта: все таблицы по группам, без полей, связи между таблицами ")
                    .append("(одна стрелка на пару");
            if (!refs.isEmpty()) {
                sb.append("; связи в справочник").append(refs.size() > 1 ? "и " : " ").append(String.join(", ", refs))
                        .append(" на карте не рисуются");
            }
            sb.append("); по картинке на группу — все поля её таблиц и таблицы других групп, на которые они ссылаются ")
                    .append("(серой рамкой; кто ссылается на группу — на картинке своей группы): ");
            List<String> items = new ArrayList<>();
            for (String g : s.groups().keySet()) {
                if (!inGroup(s, g).isEmpty()) items.add("`" + svgName(groupFile(g)) + "` — " + s.groupLabel(g));
            }
            sb.append(String.join(", ", items)).append(". ");
        }
        sb.append("Шапка таблицы — имя и короткое название. Значки у полей: `#` — поле первичного ключа, `∗` — обязательное ")
                .append("(NOT NULL), `○` — может быть пустым (NULL). Линия связи идёт от поля к полю: «вилка» — у таблицы, ")
                .append("где строк много, у таблицы «одного» значка нет; пунктир — ссылка может быть пустой или связь только ")
                .append("смысловая (по смыслу ссылка есть, внешнего ключа в `schema-constraints.sql` нет).");
        return wrap(sb.toString(), 118);
    }

    /** Перенос по словам: строки не длиннее {@code width} знаков (слово длиннее — на своей строке), в конце — перевод строки. */
    static String wrap(String text, int width) {
        StringBuilder out = new StringBuilder(), line = new StringBuilder();
        for (String w : text.split(" ")) {
            if (!line.isEmpty() && line.length() + 1 + w.length() > width) {
                out.append(line).append('\n');
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(w);
        }
        return out.append(line).append('\n').toString();
    }
}
