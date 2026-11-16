package io.hammerhall.data.sto.orders;

import java.util.Arrays;

/** Заказ в работе: строки, части исполнения (отгрузки), журнал статусов, платежи. Живёт до записи строки заказа. */
final class Order {
    // статусы — индексы StoSchema.ORDER_STATUSES
    static final byte CREATED = 0, PAID = 1, ASSEMBLING = 2, ASSEMBLED = 3, SHIPPED = 4, READY = 5, DELIVERED = 6,
            CANCELLED = 7, RETURNED = 8;
    static final byte WEB = 0, APP = 1, PHONE = 2;
    static final String[] CHANNELS = {"web", "app", "phone"};
    static final byte COURIER = 0, PICKUP = 1, POST = 2;
    static final String[] METHODS = {"courier", "pickup", "post"};

    /** Строка заказа. */
    static final class Line {
        int lineNo, product, seller, offer, qty;
        long unit, discount;
        int promo, vatRate;
        long vat;
        /** Товар продавца на складе магазина (FBO) или магазина (1P) — идёт через склады; FBS — продавец сам. */
        boolean network;
        /** Склад отбора (1P, FBO); 0 — FBS. */
        int warehouse;
        int part;
        long res = -1;
        boolean ready, picked;
        long pickedAt;
        int batch;
        /** Ожидаемая готовность к отбору (для обещанной даты). */
        long readyEta;

        long gross() {
            return unit * qty;
        }

        long net() {
            return unit * qty - discount;
        }
    }

    /** Часть исполнения — одна отгрузка: товар через склады магазина или посылка продавца FBS. */
    static final class Part {
        boolean fbs;
        int seller;
        long startAt = -1, assembledAt = -1, reassembledAt = -1;
        long planned, firstShipped = -1, shipped, arrival;
        int carrier, zone, baseDays;
        int weight, boxes;
        boolean lost;
        long lostAt = -1;
    }

    final long key;
    int id;
    int customer;
    int session;
    byte channel, method;
    int address, pickup;
    double lat, lon;
    /** Склад последней мили (городской склад; для пункта выдачи — его склад) и его город. */
    int lmWh, lmCity;
    int destCity;
    String promoCode;
    long created, estimated;
    boolean cod, b2b;
    long items, discount, delivery, vat, total;
    Line[] lines;
    Part[] parts;
    int carrier, zone, baseDays;
    // ход заказа
    int waiting;
    boolean paid, confirmed, started, cancelled, assembledAll, finalized;
    int partsAssembled, netPicksLeft;
    long netPickMax = Long.MIN_VALUE;
    long paidAmount;
    int paySeq;
    // успешные платежи (для возвратов денег): способ, сумма
    byte[] payMethod = new byte[2];
    long[] payAmount = new long[2];
    int payN;
    // журнал статусов (с будущими записями, заранее разыгранными; в выпуск — до конца фактов)
    long[] logAt = new long[8];
    byte[] logSt = new byte[8];
    int logN;
    byte planted;
    static final byte P_BAD_TRANSITION = 1, P_DOUBLE_PAY = 2, P_CARD_TEST = 4;

    Order(long key) {
        this.key = key;
    }

    void log(byte st, long at) {
        if (logN == logAt.length) {
            logAt = Arrays.copyOf(logAt, logN * 2);
            logSt = Arrays.copyOf(logSt, logN * 2);
        }
        logAt[logN] = at;
        logSt[logN++] = st;
    }

    byte lastStatus() {
        return logSt[logN - 1];
    }

    void addPayment(byte method, long amount) {
        if (payN == payMethod.length) {
            payMethod = Arrays.copyOf(payMethod, payN * 2);
            payAmount = Arrays.copyOf(payAmount, payN * 2);
        }
        payMethod[payN] = method;
        payAmount[payN++] = amount;
    }

    /** Способ самого крупного успешного платежа (им возвращают деньги); −1 — платежей нет. */
    byte mainMethod() {
        int best = -1;
        for (int i = 0; i < payN; i++) if (payAmount[i] > 0 && (best < 0 || payAmount[i] > payAmount[best])) best = i;
        return best < 0 ? -1 : payMethod[best];
    }
}
