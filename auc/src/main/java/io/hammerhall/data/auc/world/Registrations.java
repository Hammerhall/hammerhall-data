package io.hammerhall.data.auc.world;

import java.util.List;

/**
 * Регистрации на торги (в цикле, подготовка торгов → торги, сайт): ключ ({@link Keys#registration}) → {@code id},
 * клиент, табличка, лимит, допуск. {@code id} — по {@code registered_at} (выдаёт подготовка торгов за водоразделом
 * записи); пока не выдан — 0. Здесь же — {@code id} редакций поручений ({@code bid_orders}, тоже от подготовки торгов)
 * по ключу {@link Keys#order}.
 */
public interface Registrations {
    /**
     * Регистрация. Деньги — копейки, моменты — секунды Unix (0 — нет).
     *
     * @param key                {@link Keys#registration}
     * @param id                 {@code registrations.id}; 0 — ещё не выдан
     * @param saleId             {@code sales.id}
     * @param clientId           {@code clients.id}
     * @param paddleNo           номер таблички
     * @param registeredAt       подана
     * @param via                откуда
     * @param mode               заявленный способ
     * @param status             на «сейчас»: {@code approved}, {@code pending}, {@code rejected}, {@code revoked}
     * @param decidedAt          решение
     * @param decidedById        {@code staff.id}; 0 — автоматически
     * @param rejectReason       {@code no_verification}, {@code unpaid_invoice}, {@code blocked}; {@code null} — нет
     * @param bidLimitKop        лимит (T11); 0 — без лимита
     * @param premiumApprovedAt  допуск к дорогим лотам (T13)
     * @param depositKop         депозит за дорогие лоты; 0 — нет
     */
    record Registration(long key, int id, int saleId, int clientId, int paddleNo, long registeredAt, Via via,
                        RegMode mode, String status, long decidedAt, int decidedById, String rejectReason,
                        long bidLimitKop, long premiumApprovedAt, long depositKop) {
        public boolean approved() {
            return "approved".equals(status);
        }
    }

    /** Регистрация по ключу; {@code null} — нет. */
    Registration get(long key);

    /** {@code registrations.id} по ключу; 0 — нет или ещё не выдан. */
    int id(long key);

    /** Регистрации торгов — по ключу. */
    List<Registration> ofSale(int saleId);

    /** {@code bid_orders.id} редакции поручения по ключу {@link Keys#order}; 0 — нет или ещё не выдан. */
    int orderId(long orderKey);
}
