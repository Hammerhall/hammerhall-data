package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Категория — узел дерева внутри отдела (до трёх уровней), словарь {@code vocab/categories.txt}. Лист указывает вид
 * генератора ({@link Kind}), вариант («landscape», «ring», «silver») и долю лотов внутри отдела.
 *
 * @param id         ключ, с 1, в порядке словаря
 * @param code       код латиницей
 * @param department отдел
 * @param parent     родитель; {@code null} — верхний уровень отдела
 * @param level      1–3
 * @param nameRu     по-русски
 * @param nameEn     по-английски
 * @param kind       вид генератора; {@code null} — не лист
 * @param variant    вариант вида; {@code null} — любой
 * @param weight     доля листа внутри отдела (относительный вес)
 * @param makerKind  кто мастер у вещей листа; {@code null} — без мастера
 */
public record Category(int id, String code, Department department, Category parent, int level, String nameRu,
                       String nameEn, Kind kind, String variant, double weight, MakerKind makerKind) {

    public boolean isLeaf() {
        return kind != null;
    }

    private static final List<Category> ALL;
    private static final Map<String, Category> BY_CODE = new HashMap<>();
    private static final Map<Department, double[]> CUM = new EnumMap<>(Department.class);
    private static final Map<Department, List<Category>> LEAVES = new EnumMap<>(Department.class);

    static {
        List<Category> all = new ArrayList<>();
        for (String line : V.lines("categories", "tree")) {
            String[] f = line.split("\\s*;\\s*");
            if (f.length != 9) throw new IllegalStateException("categories: не 9 полей: " + line);
            Department d = null;
            for (Department x : Department.values()) if (x.code.equals(f[0])) d = x;
            if (d == null) throw new IllegalStateException("categories: нет отдела " + f[0]);
            Category parent = f[2].equals("-") ? null : BY_CODE.get(f[2]);
            if (!f[2].equals("-") && parent == null) throw new IllegalStateException("categories: родитель после ребёнка: " + line);
            Kind kind = f[5].equals("-") ? null : Kind.valueOf(f[5]);
            MakerKind mk = kind == null ? null : f[8].equals("-") ? kind.makerKind : f[8].equals("none") ? null : MakerKind.valueOf(f[8]);
            Category c = new Category(all.size() + 1, f[1], d, parent, parent == null ? 1 : parent.level + 1, f[3], f[4], kind,
                    f[6].equals("-") ? null : f[6], Double.parseDouble(f[7]), mk);
            if (BY_CODE.put(c.code, c) != null) throw new IllegalStateException("categories: код дважды: " + c.code);
            all.add(c);
        }
        ALL = Collections.unmodifiableList(all);
        for (Department d : Department.values()) {
            List<Category> leaves = new ArrayList<>();
            for (Category c : all) if (c.department == d && c.isLeaf()) leaves.add(c);
            double[] cum = new double[leaves.size()];
            double s = 0;
            for (int i = 0; i < cum.length; i++) {
                s += leaves.get(i).weight;
                cum[i] = s;
            }
            LEAVES.put(d, leaves);
            CUM.put(d, cum);
        }
    }

    /** Все узлы, по {@code id}. */
    public static List<Category> all() {
        return ALL;
    }

    public static Category byCode(String code) {
        Category c = BY_CODE.get(code);
        if (c == null) throw new IllegalArgumentException("нет категории " + code);
        return c;
    }

    public static Category byId(int id) {
        return ALL.get(id - 1);
    }

    /** Лист отдела по долям. */
    public static Category pick(Department d, Rng r) {
        double[] cum = CUM.get(d);
        double x = r.nextDouble() * cum[cum.length - 1];
        int i = java.util.Arrays.binarySearch(cum, x);
        if (i < 0) i = -i - 1;
        return LEAVES.get(d).get(Math.min(i, cum.length - 1));
    }

    /** Верхний узел ветки (уровень 1). */
    public Category top() {
        Category c = this;
        while (c.parent != null) c = c.parent;
        return c;
    }

    @Override
    public String toString() {
        return code;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Category c && c.id == id;
    }

    @Override
    public int hashCode() {
        return id;
    }
}
