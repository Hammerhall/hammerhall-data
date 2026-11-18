package io.hammerhall.data.auc.world;

/**
 * Состояние клиентов на начало недели (в цикле, торги → подготовка торгов): снимок на понедельник 00:00 по Москве — по
 * нему решаются регистрации, поданные в эту неделю. Блокировка — с 31-го дня после
 * торгов неоплаченного счёта, «оплаченные счета» — с дня оплаты.
 */
public interface ClientState {
    /** Момент снимка (секунды Unix): понедельник 00:00 по Москве. */
    long asOf();

    /** Заблокирован на момент снимка (неуплата, подставные ставки, по просьбе). */
    boolean blocked(int clientId);

    /** Оплаченных счетов к моменту снимка. */
    int paidInvoices(int clientId);

    /** Есть просроченный неоплаченный счёт. */
    boolean overdue(int clientId);

    /** Лимит на торги (T11), копейки: до двух оплаченных счетов — 300 000 ₽; потом 0 — без лимита. */
    default long bidLimitKop(int clientId) {
        return paidInvoices(clientId) >= HouseRules.PAID_INVOICES_FOR_NO_LIMIT ? 0 : HouseRules.NEW_CLIENT_LIMIT;
    }
}
