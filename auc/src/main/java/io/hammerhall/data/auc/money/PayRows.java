package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.world.PayMethod;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Строки {@code payments} компактно (без объекта на платёж): копятся во время сборки, сортируются по моменту и
 * владельцу и получают {@code id} по моменту. Владелец — счёт, регистрация (депозит) или отчёт (выплата); внутри
 * владельца — порядок попытки (ключ платежа — владелец и попытка).
 */
final class PayRows {
    /** Виды платежей ({@code payments.kind}) — порядок кодов не важен, значения — {@link #KIND}. */
    static final byte DEPOSIT = 0, DEPOSIT_APPLIED = 1, DEPOSIT_REFUND = 2, DEPOSIT_FORFEIT = 3, INVOICE = 4, REFUND = 5,
            PAYOUT = 6;
    static final String[] KIND = {"deposit", "deposit_applied", "deposit_refund", "deposit_forfeit", "invoice", "refund",
            "payout"};
    static final String[] FAILURE = {"declined", "insufficient_funds", "timeout", "limit"};
    /** Владелец: счёт, регистрация, отчёт. */
    static final int OWNER_INVOICE = 1, OWNER_REGISTRATION = 2, OWNER_SETTLEMENT = 3;

    int size;
    long[] at = new long[1024], amount = new long[1024], ref = new long[1024], owner = new long[1024];
    int[] client = new int[1024], invoice = new int[1024], registration = new int[1024], settlement = new int[1024],
            seq = new int[1024];
    byte[] kind = new byte[1024], method = new byte[1024], failure = new byte[1024];
    boolean[] ok = new boolean[1024], duplicate = new boolean[1024];
    /** {@code payments.id} по индексу строки — после {@link #order()}. */
    int[] id;

    int add(byte kind, long at, long amount, int client, PayMethod method, int invoice, int registration, int settlement,
            boolean ok, String failure, long ref, int ownerType, long ownerId, int seq, boolean duplicate) {
        if (amount <= 0) throw new IllegalArgumentException("платёж без суммы: " + amount);
        if (size == at().length) grow();
        int i = size++;
        this.kind[i] = kind;
        this.at[i] = at;
        this.amount[i] = amount;
        this.client[i] = client;
        this.method[i] = (byte) method.ordinal();
        this.invoice[i] = invoice;
        this.registration[i] = registration;
        this.settlement[i] = settlement;
        this.ok[i] = ok;
        this.failure[i] = (byte) (failure == null ? -1 : Arrays.asList(FAILURE).indexOf(failure));
        if (failure != null && this.failure[i] < 0) throw new IllegalArgumentException("причина отказа: " + failure);
        this.ref[i] = ref;
        this.owner[i] = (long) ownerType << 40 | ownerId;
        this.seq[i] = seq;
        this.duplicate[i] = duplicate;
        return i;
    }

    private long[] at() {
        return at;
    }

    private void grow() {
        int n = at.length * 2;
        at = Arrays.copyOf(at, n);
        amount = Arrays.copyOf(amount, n);
        ref = Arrays.copyOf(ref, n);
        owner = Arrays.copyOf(owner, n);
        client = Arrays.copyOf(client, n);
        invoice = Arrays.copyOf(invoice, n);
        registration = Arrays.copyOf(registration, n);
        settlement = Arrays.copyOf(settlement, n);
        seq = Arrays.copyOf(seq, n);
        kind = Arrays.copyOf(kind, n);
        method = Arrays.copyOf(method, n);
        failure = Arrays.copyOf(failure, n);
        ok = Arrays.copyOf(ok, n);
        duplicate = Arrays.copyOf(duplicate, n);
    }

    /** Порядок строк по ({@code at}, владелец, попытка); выдаёт {@code id} с 1. */
    Integer[] order() {
        Integer[] idx = new Integer[size];
        for (int i = 0; i < size; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.<Integer>comparingLong(i -> at[i]).thenComparingLong(i -> owner[i])
                .thenComparingInt(i -> seq[i]));
        id = new int[size];
        for (int k = 0; k < size; k++) id[idx[k]] = k + 1;
        return idx;
    }

    PayMethod method(int i) {
        return PayMethod.values()[method[i]];
    }

    /** Номер операции провайдера: {@code card-…}, {@code sbp-…} (16 hex); {@code null} — нет. */
    String providerRef(int i) {
        if (ref[i] == 0) return null;
        return (method[i] == PayMethod.SBP.ordinal() ? "sbp-" : "card-") + String.format(java.util.Locale.ROOT, "%016x", ref[i]);
    }

    String failure(int i) {
        return failure[i] < 0 ? null : FAILURE[failure[i]];
    }
}
