package io.hammerhall.data.auc.auction.outcome;

import io.hammerhall.data.auc.auction.Prep;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.ClientState;

import java.util.Arrays;

/**
 * Состояние клиентов по итогам торгов: оплаченные счета (с дня оплаты), просрочка (от срока до
 * оплаты или отмены), блокировка за неуплату (с 31-го дня), расширенная проверка (счёт от 1 млн ₽). Пишется в одном
 * потоке после торгов недели; снимок на понедельник ({@link #at}) читают параллельно — массивы в это время не меняются.
 * Списки событий клиента — в общих массивах (связные списки по индексам), без объекта на событие.
 */
public final class ClientLedger implements Prep.ClientStates {
    public static final byte BR_NONE = 0, BR_NON_PAYMENT = 1;

    private final ClientBase base;
    private long[] blockedAt;
    private byte[] blockReason;
    private long[] enhancedAt;
    private int[] paidHead, dueHead;
    private int[] paidNext = new int[1024], dueNext = new int[1024];
    private long[] paidAt = new long[1024], dueFrom = new long[1024], dueTo = new long[1024];
    private int paidN, dueN;

    /** {@code base} — блокировки не за неуплату ({@code otherBlockAt}: по просьбе, подозрение в подставных); может быть {@code null}. */
    public ClientLedger(int clients, ClientBase base) {
        this.base = base;
        int n = clients + 1;
        blockedAt = new long[n];
        blockReason = new byte[n];
        enhancedAt = new long[n];
        paidHead = new int[n];
        dueHead = new int[n];
        Arrays.fill(paidHead, -1);
        Arrays.fill(dueHead, -1);
    }

    private void ensure(int c) {
        if (c < blockedAt.length) return;
        int n = Math.max(c + 1, blockedAt.length + (blockedAt.length >> 1));
        int old = blockedAt.length;
        blockedAt = Arrays.copyOf(blockedAt, n);
        blockReason = Arrays.copyOf(blockReason, n);
        enhancedAt = Arrays.copyOf(enhancedAt, n);
        paidHead = Arrays.copyOf(paidHead, n);
        dueHead = Arrays.copyOf(dueHead, n);
        Arrays.fill(paidHead, old, n, -1);
        Arrays.fill(dueHead, old, n, -1);
    }

    /** Счёт клиента оплачен полностью в {@code at}. */
    public void paid(int client, long at) {
        ensure(client);
        if (paidN == paidAt.length) {
            paidAt = Arrays.copyOf(paidAt, paidN * 2);
            paidNext = Arrays.copyOf(paidNext, paidN * 2);
        }
        paidAt[paidN] = at;
        paidNext[paidN] = paidHead[client];
        paidHead[client] = paidN++;
    }

    /** Счёт просрочен с {@code dueAt} до {@code resolvedAt} (оплата или отмена; 0 — не разрешился в модели). */
    public void overdue(int client, long dueAt, long resolvedAt) {
        ensure(client);
        if (dueN == dueFrom.length) {
            dueFrom = Arrays.copyOf(dueFrom, dueN * 2);
            dueTo = Arrays.copyOf(dueTo, dueN * 2);
            dueNext = Arrays.copyOf(dueNext, dueN * 2);
        }
        dueFrom[dueN] = dueAt;
        dueTo[dueN] = resolvedAt;
        dueNext[dueN] = dueHead[client];
        dueHead[client] = dueN++;
    }

    /** Блокировка с {@code at}; прежняя (раньше) остаётся. */
    public void block(int client, long at, byte reason) {
        ensure(client);
        if (blockedAt[client] == 0 || at < blockedAt[client]) {
            blockedAt[client] = at;
            blockReason[client] = reason;
        }
    }

    /** Расширенная проверка в {@code at} (первая остаётся). */
    public void enhanced(int client, long at) {
        ensure(client);
        if (enhancedAt[client] == 0 || at < enhancedAt[client]) enhancedAt[client] = at;
    }

    public long blockedAt(int client) {
        return client < blockedAt.length ? blockedAt[client] : 0;
    }

    public byte blockReason(int client) {
        return client < blockReason.length ? blockReason[client] : BR_NONE;
    }

    public long enhancedAt(int client) {
        return client < enhancedAt.length ? enhancedAt[client] : 0;
    }

    /** Оплаченных счетов раньше момента. */
    public int paidBefore(int client, long at) {
        if (client >= paidHead.length) return 0;
        int n = 0;
        for (int e = paidHead[client]; e >= 0; e = paidNext[e]) if (paidAt[e] < at) n++;
        return n;
    }

    /** Есть ли просроченный неоплаченный счёт в момент {@code at}. */
    public boolean overdueAt(int client, long at) {
        if (client >= dueHead.length) return false;
        for (int e = dueHead[client]; e >= 0; e = dueNext[e]) {
            if (dueFrom[e] < at && (dueTo[e] == 0 || dueTo[e] > at)) return true;
        }
        return false;
    }

    @Override
    public ClientState at(long mondayAt) {
        return new Snapshot(mondayAt);
    }

    private final class Snapshot implements ClientState {
        private final long asOf;

        Snapshot(long asOf) {
            this.asOf = asOf;
        }

        @Override
        public long asOf() {
            return asOf;
        }

        @Override
        public boolean blocked(int clientId) {
            long b = blockedAt(clientId);
            if (b != 0 && b < asOf) return true;
            if (base == null || clientId < 1 || clientId > base.size()) return false;
            long o = base.otherBlockAt(clientId);
            return o != 0 && o < asOf;
        }

        @Override
        public int paidInvoices(int clientId) {
            return paidBefore(clientId, asOf);
        }

        @Override
        public boolean overdue(int clientId) {
            return overdueAt(clientId, asOf);
        }
    }
}
