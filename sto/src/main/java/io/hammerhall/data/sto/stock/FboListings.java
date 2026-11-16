package io.hammerhall.data.sto.stock;

import java.time.LocalDate;

/**
 * Товары продавцов FBO, лежащие на наших складах: кто, что, на каком складе держит и сколько продаёт. Движок раз в
 * сутки спрашивает действующие на дату позиции и сам ведёт их пополнение — продавец привозит партию (в
 * {@code batches} — с {@code seller_id}, без заказа поставщику), когда остаток падает до точки заказа. Модель
 * продавцов и предложений — у модуля маркетплейса: он даёт эту реализацию; пока продавцов нет — {@link #NONE}.
 */
public interface FboListings {
    /** Позиция FBO на дату. */
    record Listing(int sellerId, int productId, int warehouseId, double unitsPerDay, long declaredCostKop,
                   int leadDays) {
    }

    interface Sink {
        void accept(Listing l);
    }

    /** Действующие на дату позиции — в стабильном порядке (по продавцу, товару). */
    void forEach(LocalDate d, Sink sink);

    FboListings NONE = (d, sink) -> {
    };
}
