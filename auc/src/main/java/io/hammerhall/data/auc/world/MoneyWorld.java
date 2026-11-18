package io.hammerhall.data.auc.world;

import java.util.List;

/**
 * Деньги ({@code MONEY}, модуль {@code money} → сайт): события счетов, платежей и выплат — источники
 * уведомлений. Только факты до среза.
 */
public interface MoneyWorld {
    /**
     * Событие денег.
     *
     * @param kind      {@code invoice_issued}, {@code payment_reminder}, {@code payment_received},
     *                  {@code invoice_cancelled}, {@code deposit_refunded}, {@code settlement_ready},
     *                  {@code settlement_paid}
     * @param clientId  адресат
     * @param saleId    {@code sales.id}; 0 — нет
     * @param invoiceId {@code invoices.id}; 0 — нет
     * @param at        момент (секунды Unix)
     * @param eventKey  ключ события ({@code invoice_issued:<invoice>}) — для идемпотентного потребителя
     */
    record Event(String kind, int clientId, int saleId, int invoiceId, long at, String eventKey) {
    }

    /** События по моменту, затем ключу. */
    List<Event> events();

    /**
     * Генераторы взяли своё (вызывает сайт — последний генератор, — собрав источники уведомлений): события можно
     * отпустить (память {@code full}); после вызова {@link #events} бросает {@link IllegalStateException}. По умолчанию —
     * ничего.
     */
    default void generatorsDone() {
    }
}
