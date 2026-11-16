package io.hammerhall.data.sto.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Акции — как в {@code promotions} и {@code promotion_targets}. Правила применения (для модуля заказов):
 * <ul>
 *   <li>{@code percent} — {@code discount_pct} от цены строки с целевым товаром;</li>
 *   <li>{@code amount} — {@code discount_amount} с каждой единицы целевого товара (не ниже 1 ₽ за единицу);</li>
 *   <li>{@code n_plus_m} — из каждых {@code buy_qty} единиц целевых товаров {@code free_qty} самых дешёвых — бесплатно;</li>
 *   <li>{@code bundle} — если в заказе ≥ 2 разных целевых товара: {@code discount_pct} на эти строки;</li>
 *   <li>{@code promocode} — только по коду {@code promo_code}; без целей — на весь заказ (сумма или процент), с целями —
 *       на целевые строки; {@code min_order_amount} — порог по сумме товаров;</li>
 *   <li>цель-категория действует на всё её поддерево; акция без целей (кроме промокода) не бывает;</li>
 *   <li>{@code stackable = f} — с другими акциями на ту же строку не суммируется (берётся лучшая); суммируемые
 *       складываются последовательно.</li>
 * </ul>
 */
public final class Promotions {

    public record Target(Integer productId, Integer categoryId) {
    }

    public record Promotion(int id, String kind, String nameRu, String nameEn, String event, BigDecimal discountPct,
                            Long discountAmountKop, Integer buyQty, Integer freeQty, String promoCode,
                            Long minOrderKop, long validFrom, long validTo, boolean stackable, List<Target> targets,
                            boolean fakeDiscount) {
        public boolean activeAt(long t) {
            return t >= validFrom && t < validTo;
        }
    }

    private final List<Promotion> all;

    public Promotions(List<Promotion> all) {
        this.all = List.copyOf(all);
    }

    /** Все акции по id (индекс = id − 1). */
    public List<Promotion> all() {
        return all;
    }

    public Promotion promotion(int id) {
        return all.get(id - 1);
    }

    public List<Promotion> activeAt(long t) {
        List<Promotion> out = new ArrayList<>();
        for (Promotion p : all) if (p.activeAt(t)) out.add(p);
        return out;
    }

    /** Действует ли акция на товар (по целям; категория — с поддеревом). */
    public static boolean appliesTo(Promotion promo, Catalog.Product p, Catalog catalog) {
        for (Target t : promo.targets()) {
            if (t.productId() != null && t.productId() == p.id) return true;
            if (t.categoryId() != null && catalog.inSubtree(p, t.categoryId())) return true;
        }
        return false;
    }
}
