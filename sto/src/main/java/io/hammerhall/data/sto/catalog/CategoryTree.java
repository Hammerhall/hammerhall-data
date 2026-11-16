package io.hammerhall.data.sto.catalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Дерево категорий из {@code categories.txt}: отступ — уровень, у листа — вид товара, у части листьев — деление по
 * характеристике (facet): товар попадает в дочернюю категорию по значению. id — обходом в глубину в порядке файла,
 * дочерние по характеристике — сразу за родителем. Дерево не зависит от периода и seed.
 */
public final class CategoryTree {

    public static final class Node {
        public int id;
        public Node parent;
        public int level;
        public String nameRu, nameEn;
        /** Вид товара — у узла, к которому привязан вид (лист или родитель деления). */
        public String kind;
        public final List<Node> children = new ArrayList<>();
        /** Деление по характеристике: ключ и полосы. */
        public String facetKey;
        public final List<Band> bands = new ArrayList<>();
        public int departmentId;

        public boolean leaf() {
            return children.isEmpty();
        }

        public Integer parentId() {
            return parent == null ? null : parent.id;
        }
    }

    /** Полоса деления: число — «не больше max», текст — равенство, {@code other} — всё остальное. */
    public record Band(Double max, String text, boolean other, Node node) {
    }

    private final Node root;
    private final List<Node> all = new ArrayList<>();

    private CategoryTree(Node root) {
        this.root = root;
    }

    public Node root() {
        return root;
    }

    /** Все узлы по id (индекс = id − 1). */
    public List<Node> nodes() {
        return all;
    }

    public Node node(int id) {
        return all.get(id - 1);
    }

    /** Узлы, к которым привязан вид товара (в порядке id). */
    public List<Node> kindNodes() {
        List<Node> out = new ArrayList<>();
        for (Node n : all) if (n.kind != null) out.add(n);
        return out;
    }

    /** Лист для товара: по полосе деления или сам узел вида. */
    public Node leafFor(Node kindNode, Double num, String text) {
        if (kindNode.facetKey == null) return kindNode;
        for (Band b : kindNode.bands) {
            if (b.other) return b.node;
            if (b.max != null && num != null && num <= b.max + 1e-9) return b.node;
            if (b.text != null && b.text.equals(text)) return b.node;
        }
        throw new IllegalStateException(kindNode.nameRu + ": нет полосы для " + (num != null ? num : text));
    }

    public static CategoryTree load(Kinds kinds) throws IOException {
        List<Node> stack = new ArrayList<>();
        Node root = null;
        for (String raw : Kinds.lines("categories.txt")) {
            if (raw.isBlank() || raw.startsWith("#")) continue;
            int indent = 0;
            while (raw.charAt(indent) == ' ') indent++;
            if (indent % 2 != 0) throw new IOException("categories.txt: нечётный отступ: " + raw);
            int level = indent / 2 + 1;
            String[] f = raw.trim().split(" \\| ");
            Node n = new Node();
            n.level = level;
            n.nameRu = f[0].trim();
            n.nameEn = f[1].trim();
            if (f.length >= 3) {
                n.kind = f[2].trim();
                if (!kinds.kinds().containsKey(n.kind)) throw new IOException("categories.txt: нет вида " + n.kind);
            }
            if (level == 1) {
                if (root != null) throw new IOException("categories.txt: второй корень " + n.nameRu);
                root = n;
                stack.clear();
                stack.add(n);
            } else {
                while (stack.size() >= level) stack.removeLast();
                if (stack.size() != level - 1) throw new IOException("categories.txt: пропущен уровень у " + n.nameRu);
                n.parent = stack.getLast();
                n.parent.children.add(n);
                stack.add(n);
            }
            if (f.length >= 4) {
                String facet = f[3].trim();
                if (!facet.startsWith("facet=")) throw new IOException("categories.txt: ждали facet= у " + n.nameRu);
                String body = facet.substring("facet=".length());
                int colon = body.indexOf(':');
                n.facetKey = body.substring(0, colon);
                Kinds.SpecKey sk = kinds.keys().get(n.facetKey);
                if (sk == null) throw new IOException("categories.txt: нет ключа " + n.facetKey);
                for (String band : body.substring(colon + 1).split(";")) {
                    int eq = band.indexOf('=');
                    String thr = band.substring(0, eq), label = band.substring(eq + 1);
                    int slash = label.indexOf('/');
                    Node c = new Node();
                    c.level = level + 1;
                    c.nameRu = label.substring(0, slash);
                    c.nameEn = label.substring(slash + 1);
                    c.parent = n;
                    n.children.add(c);
                    if (thr.equals("*")) n.bands.add(new Band(null, null, true, c));
                    else if (sk.numeric()) n.bands.add(new Band(Double.parseDouble(thr), null, false, c));
                    else n.bands.add(new Band(null, thr, false, c));
                }
            }
        }
        if (root == null) throw new IOException("categories.txt: нет корня");
        CategoryTree t = new CategoryTree(root);
        t.number(root, 0);
        for (Node n : t.all) {
            if (n.leaf() && n.kind == null && (n.parent == null || n.parent.kind == null)) {
                throw new IOException("categories.txt: лист без вида: " + n.nameRu);
            }
            if (n.level > 6) throw new IOException("categories.txt: глубже 6 уровней: " + n.nameRu);
        }
        return t;
    }

    private void number(Node n, int dept) {
        n.id = all.size() + 1;
        all.add(n);
        if (n.level == 2) dept = n.id;
        n.departmentId = n.level == 1 ? 0 : dept;
        for (Node c : n.children) number(c, dept);
    }
}
