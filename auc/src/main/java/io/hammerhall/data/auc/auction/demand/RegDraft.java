package io.hammerhall.data.auc.auction.demand;

import io.hammerhall.data.auc.world.RegMode;
import io.hammerhall.data.auc.world.Via;

/**
 * Регистрация, как её решил спрос (модель без среза): одна на клиента и торги. Деньги — копейки, моменты — секунды.
 * Состояние на срез ({@code pending}, пустое решение) — при записи.
 */
public final class RegDraft {
    final long key;
    final int saleId, clientId;
    final long registeredAt;
    final Via via;
    final RegMode mode;
    /** {@code approved} или {@code rejected}. */
    final String status;
    final long decidedAt;
    final int decidedBy;
    final String rejectReason;
    final long limit, premiumAt, deposit;
    int paddle;
    /** {@code registrations.id}; 0 — не выдан (у книги регистраций). */
    int id;
    /** Участник хотя бы одного лота торгов (ставка или попытка) — для коридора «регистрации без ставок». */
    boolean withLots;

    RegDraft(long key, int saleId, int clientId, long registeredAt, Via via, RegMode mode, String status, long decidedAt,
             int decidedBy, String rejectReason, long limit, long premiumAt, long deposit) {
        this.key = key;
        this.saleId = saleId;
        this.clientId = clientId;
        this.registeredAt = registeredAt;
        this.via = via;
        this.mode = mode;
        this.status = status;
        this.decidedAt = decidedAt;
        this.decidedBy = decidedBy;
        this.rejectReason = rejectReason;
        this.limit = limit;
        this.premiumAt = premiumAt;
        this.deposit = deposit;
    }

    public long key() {
        return key;
    }

    public long registeredAt() {
        return registeredAt;
    }

    public boolean approved() {
        return "approved".equals(status);
    }

    public int paddle() {
        return paddle;
    }

    public RegMode mode() {
        return mode;
    }

    public long limit() {
        return limit;
    }

    public long decidedAt() {
        return decidedAt;
    }

    public int clientId() {
        return clientId;
    }

    public int saleId() {
        return saleId;
    }

    public long premiumAt() {
        return premiumAt;
    }
}
