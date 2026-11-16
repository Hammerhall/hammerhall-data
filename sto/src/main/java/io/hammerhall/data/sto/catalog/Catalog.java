package io.hammerhall.data.sto.catalog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Модель каталога для следующих модулей ({@code ctx.get(StoKeys.CATALOG)}): ровно то, что записано в таблицы
 * каталога, плюс знания генератора, которых в таблицах нет и которые нужны для правдоподобных продаж: популярность
 * (Парето), жизненный цикл и преемник поколения, ассортимент (1P / 3P), правила «покупают вместе».
 */
public final class Catalog {

    /** Кто продаёт товар: только магазин, магазин и продавцы, только продавцы маркетплейса. */
    public enum Assortment {ONLY_1P, BOTH, ONLY_3P}

    public record Brand(int id, String name, String country, String tier, boolean privateLabel, List<String> groups,
                        double weight) {
    }

    public record SpecValue(String key, String textRu, String textEn, Double num) {
    }

    public record BundleItem(Product component, int qty) {
    }

    /** Товар. Поля таблицы {@code products} — как в таблице; остальное — для модулей. */
    public static final class Product {
        public int id;
        public String sku, ean13;
        public Brand brand;
        public CategoryTree.Node category;
        public Kinds.Kind kind;
        public String nameRu, nameEn, modelLine;
        /** Модель без бренда и варианта, как в названии: «Aster Pro 7». */
        public String display;
        public int generation;
        public LocalDate launchedOn;
        /** Как в таблице: NULL, если снятие позже конца периода. */
        public LocalDate discontinuedOn;
        /** По плану генератора (может быть после конца периода; {@code null} — линейка не кончается в горизонте). */
        public LocalDate discontinuedPlan;
        public int weightG, lengthMm, widthMm, heightMm, warrantyMonths;
        public boolean bundle;
        /** Цена при запуске, копеек (до истории цен; у комплекта — из цен состава). */
        public long launchPriceKop;
        /** Относительная популярность (тяжёлый хвост: 20 % товаров дают ≈ 80 % спроса). */
        public double popularity;
        public Assortment assortment;
        /** Запуск следующего поколения той же линейки ({@code null} — нет): после него спрос падает. */
        public LocalDate successorLaunch;
        /** Стабильный ключ плана («вид/слот/линейка/поколение/вариант») — для потоков случайности модулей. */
        public String planKey;
        /** Для аксессуаров — к каким устройствам подходит. */
        public List<Product> compatibleWith = List.of();
        public List<SpecValue> specs = new ArrayList<>();
        public List<BundleItem> components = List.of();
        /** Заложено: неверная контрольная цифра EAN-13. */
        public boolean badEan;

        public boolean aliveOn(LocalDate d) {
            return !d.isBefore(launchedOn) && (discontinuedPlan == null || d.isBefore(discontinuedPlan));
        }

        public SpecValue spec(String key) {
            for (SpecValue v : specs) if (v.key().equals(key)) return v;
            return null;
        }

        @Override
        public String toString() {
            return id + " " + nameRu;
        }
    }

    private final CategoryTree tree;
    private final Kinds kinds;
    private final List<Brand> brands;
    private final List<Product> products;
    private final Map<String, List<Product>> byKind = new TreeMap<>();

    public Catalog(CategoryTree tree, Kinds kinds, List<Brand> brands, List<Product> products) {
        this.tree = tree;
        this.kinds = kinds;
        this.brands = List.copyOf(brands);
        this.products = List.copyOf(products);
        for (Product p : products) byKind.computeIfAbsent(p.kind.code, k -> new ArrayList<>()).add(p);
    }

    public CategoryTree tree() {
        return tree;
    }

    public Kinds kinds() {
        return kinds;
    }

    public List<Brand> brands() {
        return brands;
    }

    public Brand brand(int id) {
        return brands.get(id - 1);
    }

    /** Все товары по id (индекс = id − 1). */
    public List<Product> products() {
        return products;
    }

    public Product product(int id) {
        return products.get(id - 1);
    }

    public List<Product> ofKind(String kind) {
        return Collections.unmodifiableList(byKind.getOrDefault(kind, List.of()));
    }

    /** Товары в продаже в этот день (по плану), по id. */
    public List<Product> aliveOn(LocalDate d) {
        List<Product> out = new ArrayList<>();
        for (Product p : products) if (p.aliveOn(d)) out.add(p);
        return out;
    }

    /** Категория и всё её поддерево — для целей акций по категории. */
    public boolean inSubtree(Product p, int categoryId) {
        for (CategoryTree.Node n = p.category; n != null; n = n.parent) if (n.id == categoryId) return true;
        return false;
    }

    /**
     * Жизненный цикл: 0 вне продаж; разгон за первые 30 дней; затем спад с полупериодом ≈ смене поколения;
     * после выхода преемника — ×0,35 (каннибализация); за 30 дней до снятия — распродажа ×1,3. Множитель к
     * популярности — отправная модель для модуля заказов.
     */
    public double lifecycle(Product p, LocalDate d) {
        if (!p.aliveOn(d)) return 0;
        long age = d.toEpochDay() - p.launchedOn.toEpochDay();
        double f = age < 30 ? 0.3 + 0.7 * age / 30.0 : 1.0;
        double halfLife = Math.max(180, p.kind.cadenceMonths * 30.4);
        if (age > 60) f *= StrictMath.pow(0.5, (age - 60) / halfLife);
        if (p.successorLaunch != null && !d.isBefore(p.successorLaunch)) f *= 0.35;
        if (p.discontinuedPlan != null && p.discontinuedPlan.toEpochDay() - d.toEpochDay() <= 30) f *= 1.3;
        return f;
    }

    /** Правило «покупают вместе»: к товару вида {@code anchor} докладывают вид {@code companion} с вероятностью. */
    public record Companion(String anchor, String companion, double probability, boolean compatibleOnly) {
    }

    /**
     * Заложенные правила с известным лифтом (спецификация, §5). Для аксессуаров с совместимостью берётся
     * подходящий к устройству товар ({@link Product#compatibleWith}).
     */
    public static final List<Companion> COMPANIONS = List.of(
            new Companion("smartphone", "case", 0.30, true),
            new Companion("smartphone", "glass", 0.25, true),
            new Companion("smartphone", "charger", 0.10, false),
            new Companion("smartphone", "powerbank", 0.05, false),
            new Companion("smartphone", "tws", 0.07, false),
            new Companion("smartphone", "memory_card", 0.04, false),
            new Companion("tablet", "case_tablet", 0.35, true),
            new Companion("tablet", "stylus", 0.10, false),
            new Companion("laptop", "mouse", 0.22, false),
            new Companion("laptop", "laptop_bag", 0.18, false),
            new Companion("laptop_gaming", "mouse_gaming", 0.30, false),
            new Companion("laptop_gaming", "gaming_headset", 0.15, false),
            new Companion("ultrabook", "dock", 0.10, false),
            new Companion("desktop", "monitor", 0.30, false),
            new Companion("desktop", "kb_mouse_set", 0.25, false),
            new Companion("tv", "tv_mount", 0.20, false),
            new Companion("tv", "cable_hdmi", 0.15, false),
            new Companion("tv", "soundbar", 0.06, false),
            new Companion("console", "gamepad", 0.35, false),
            new Companion("printer_laser", "cartridge", 0.45, true),
            new Companion("mfp_laser", "cartridge", 0.45, true),
            new Companion("printer_inkjet", "cartridge", 0.50, true),
            new Companion("mfp_inkjet", "cartridge", 0.50, true),
            new Companion("mfp_inkjet", "paper", 0.30, false),
            new Companion("camera_mirrorless", "lens", 0.25, false),
            new Companion("camera_mirrorless", "memory_card", 0.35, false),
            new Companion("camera_mirrorless", "camera_bag", 0.20, false),
            new Companion("drill_cordless", "tool_battery", 0.20, true),
            new Companion("drill_cordless", "tool_set", 0.10, false),
            new Companion("vacuum", "vacuum_acc", 0.25, true),
            new Companion("robot_vacuum", "vacuum_acc", 0.30, true),
            new Companion("toothbrush", "brush_heads", 0.30, true),
            new Companion("coffee_auto", "coffee_grinder", 0.05, false),
            new Companion("garland", "extension", 0.15, false));
}
