package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.world.MoneyWorld;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * {@code MONEY} — события денег для уведомлений сайта: компактно (массивы примитивов), запись {@link Event} —
 * при чтении. Только факты до среза. Ключ события — {@code вид:номер} (номер — счёт, отчёт или платёж; у напоминаний —
 * ещё номер напоминания). Порядок — по моменту, затем виду и номеру.
 */
final class MoneyEvents implements MoneyWorld {
    static final byte INVOICE_ISSUED = 0, PAYMENT_REMINDER = 1, PAYMENT_RECEIVED = 2, INVOICE_CANCELLED = 3,
            DEPOSIT_REFUNDED = 4, SETTLEMENT_READY = 5, SETTLEMENT_PAID = 6;
    static final String[] KIND = {"invoice_issued", "payment_reminder", "payment_received", "invoice_cancelled",
            "deposit_refunded", "settlement_ready", "settlement_paid"};
    private static final boolean[] BY_PAYMENT = {false, false, true, false, true, false, true};

    private int size;
    private byte[] kind = new byte[1024], n = new byte[1024];
    private int[] client = new int[1024], sale = new int[1024], invoice = new int[1024], ref = new int[1024];
    private long[] at = new long[1024];
    private int[] order;

    void add(byte k, int clientId, int saleId, int invoiceId, long when, int refId, int no) {
        if (order != null) throw new IllegalStateException("события уже запечатаны");
        if (size == at.length) {
            int c = size * 2;
            kind = Arrays.copyOf(kind, c);
            n = Arrays.copyOf(n, c);
            client = Arrays.copyOf(client, c);
            sale = Arrays.copyOf(sale, c);
            invoice = Arrays.copyOf(invoice, c);
            ref = Arrays.copyOf(ref, c);
            at = Arrays.copyOf(at, c);
        }
        int i = size++;
        kind[i] = k;
        client[i] = clientId;
        sale[i] = saleId;
        invoice[i] = invoiceId;
        at[i] = when;
        ref[i] = refId;
        n[i] = (byte) no;
    }

    /** Событие платежа: номер — строка {@link PayRows}, до выдачи {@code id} ({@link #paymentIds}). */
    void payment(int row, byte k, int clientId, int saleId, int invoiceId, long when) {
        add(k, clientId, saleId, invoiceId, when, row, 0);
    }

    /** Строки платежей получили {@code id}: номер события — {@code payments.id}. */
    void paymentIds(int[] idByRow) {
        for (int i = 0; i < size; i++) if (BY_PAYMENT[kind[i]]) ref[i] = idByRow[ref[i]];
    }

    void seal() {
        Integer[] idx = new Integer[size];
        for (int i = 0; i < size; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.<Integer>comparingLong(i -> at[i]).thenComparingInt(i -> kind[i])
                .thenComparingInt(i -> ref[i]).thenComparingInt(i -> n[i]));
        order = new int[size];
        for (int k = 0; k < size; k++) order[k] = idx[k];
    }

    int size() {
        return size;
    }

    /** Событий вида — для журнала и проверок. */
    long count(byte k) {
        long c = 0;
        for (int i = 0; i < size; i++) if (kind[i] == k) c++;
        return c;
    }

    /** Сайт собрал источники уведомлений — события больше не нужны (память full). */
    @Override
    public void generatorsDone() {
        released = true;
        kind = null;
        n = null;
        client = null;
        sale = null;
        invoice = null;
        ref = null;
        at = null;
        order = null;
    }

    private boolean released;

    @Override
    public List<Event> events() {
        if (released) throw new IllegalStateException("события денег отпущены: генераторы закончили");
        if (order == null) throw new IllegalStateException("события ещё не запечатаны");
        return new AbstractList<>() {
            @Override
            public Event get(int k) {
                int i = order[k];
                String key = KIND[kind[i]] + ":" + ref[i] + (kind[i] == PAYMENT_REMINDER ? ":" + n[i] : "");
                return new Event(KIND[kind[i]], client[i], sale[i], invoice[i], at[i], key);
            }

            @Override
            public int size() {
                return size;
            }
        };
    }
}
