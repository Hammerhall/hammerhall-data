package io.hammerhall.data.sto.orders;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.gen.Planted;
import io.hammerhall.data.core.money.Money;
import io.hammerhall.data.core.money.Tax;
import io.hammerhall.data.sto.geo.Geo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Самопроверка заказов по тому, что записано в таблицы (значения строк), своими структурами: суммы заказа и строк,
 * НДС на дату, цены строк (магазин — по истории цен, продавец — по предложению на момент), журнал статусов (по времени
 * и по машине состояний; поля заказа — последние моменты своих статусов), платежи сходятся с суммой, возврат не
 * больше оплаченного, доставка не раньше отгрузки, отзыв после получения, сессии и события по времени, моменты —
 * внутри периода и до конца фактов. Заложенные нарушения ({@code ctx.planted()}) — пропускаются ровно они.
 */
final class OrderAudit {
    /** Допустимые переходы: из статуса → в статусы (индексы {@code StoSchema.ORDER_STATUSES}). */
    static final boolean[][] ALLOWED = new boolean[9][9];

    static {
        allow(Order.CREATED, Order.PAID, Order.ASSEMBLING, Order.CANCELLED);
        allow(Order.PAID, Order.ASSEMBLING, Order.CANCELLED);
        allow(Order.ASSEMBLING, Order.ASSEMBLED);
        allow(Order.ASSEMBLED, Order.SHIPPED, Order.ASSEMBLING);
        allow(Order.SHIPPED, Order.READY, Order.DELIVERED, Order.ASSEMBLED, Order.CANCELLED);
        allow(Order.READY, Order.DELIVERED, Order.CANCELLED, Order.SHIPPED);
        allow(Order.DELIVERED, Order.RETURNED);
    }

    private static void allow(byte from, byte... to) {
        for (byte t : to) ALLOWED[from][t] = true;
    }

    private final World w;
    private final Planted planted;
    private final long start, cutoff, end;

    // заказы (индекс = id)
    private int[] oCreated = new int[1 << 16], oDelivered = new int[1 << 16], oCustomer = new int[1 << 16],
            oLine0 = new int[1 << 16];
    private byte[] oStatus = new byte[1 << 16], oLines = new byte[1 << 16];
    private long[] oTotal = new long[1 << 16], oPaid = new long[1 << 16], oRefund = new long[1 << 16];
    private boolean[] oCod = new boolean[1 << 16];
    private int[] shShipped = new int[1 << 16], shDelivered = new int[1 << 16];
    private int maxOrder;
    // строки
    private long[] lNet = new long[1 << 16];
    private int[] lProduct = new int[1 << 16];
    private short[] lQty = new short[1 << 16];
    private int lineTop;
    // сессии (индекс = id)
    private int[] sStart = new int[1 << 16], sEnd = new int[1 << 16], sCustomer = new int[1 << 16];
    private int curSession, curSeq;
    private long curAt;
    // отложенные: возвраты, отзывы
    private final List<long[]> rets = new ArrayList<>();
    private final List<int[]> revs = new ArrayList<>();

    private final Map<String, long[]> counts = new LinkedHashMap<>();
    private final Map<String, List<String>> examples = new LinkedHashMap<>();
    long orders, lines, payments, shipments, returns, reviews, sessions, events;

    static final String C_SUMS = "заказы: сумма = Σ строк − скидки + доставка, НДС строк и заказа — по ставке на дату";
    static final String C_PRICE = "строки: цена — магазина по истории цен или предложения продавца на момент заказа";
    static final String C_LOG = "журнал статусов: по времени, по машине состояний, поля заказа — моменты своих статусов";
    static final String C_TIME = "моменты — внутри периода и до конца фактов (кроме обещанных и плановых)";
    static final String C_DELIVERY = "доставка не раньше отгрузки; способ получения — адрес или пункт выдачи";
    static final String C_PAY = "платежи сходятся с суммой заказа за вычетом возвратов (кроме неоплаченных и отменённых)";
    static final String C_RET = "возврат не больше оплаченного за строку и не раньше получения";
    static final String C_REV = "отзыв — после получения, своего клиента, о товаре заказа";
    static final String C_SHIP = "отгрузки: зона — склада и перевозчика отгрузки, статус — по моментам";
    static final String C_SESS = "сессии: события по порядку и по времени внутри сессии, заказ — внутри своей сессии";
    static final String C_QUERY = "поисковые запросы — без внутренних имён платформы";

    OrderAudit(World w, long cutoff) {
        this.w = w;
        // ёмкость — по ожидаемому объёму профиля (≈ 5,3 млн заказов в full), чтобы не копировать массивы при росте
        int no = (int) Math.min(Integer.MAX_VALUE - 16, 5_400_000L * w.scale * (w.period.to().toEpochDay()
                - w.period.from().toEpochDay() + 1) / 760 + 4096);
        ensureOrder(no);
        int nl = (int) Math.min(Integer.MAX_VALUE - 16, no * 2.4);
        lNet = new long[nl];
        lProduct = new int[nl];
        lQty = new short[nl];
        int ns = (int) Math.min(Integer.MAX_VALUE - 16, no * 1.3);
        sStart = new int[ns];
        sEnd = new int[ns];
        sCustomer = new int[ns];
        this.planted = w.ctx.planted();
        this.start = w.period.startEpoch();
        this.cutoff = cutoff;
        this.end = w.period.endEpochExclusive();
        for (String c : List.of(C_SUMS, C_PRICE, C_LOG, C_TIME, C_DELIVERY, C_PAY, C_RET, C_REV, C_SHIP, C_SESS, C_QUERY)) {
            counts.put(c, new long[1]);
            examples.put(c, new ArrayList<>());
        }
    }

    private void bad(String check, String what) {
        counts.get(check)[0]++;
        List<String> ex = examples.get(check);
        if (ex.size() < 5) ex.add(what);
    }

    private boolean fact(long t) {
        return t >= start && t < cutoff;
    }

    private void ensureOrder(int id) {
        if (id < oCreated.length) return;
        int n = Math.max(id + 1, oCreated.length + (oCreated.length >> 2));
        oCreated = Arrays.copyOf(oCreated, n);
        oDelivered = Arrays.copyOf(oDelivered, n);
        oCustomer = Arrays.copyOf(oCustomer, n);
        oLine0 = Arrays.copyOf(oLine0, n);
        oStatus = Arrays.copyOf(oStatus, n);
        oLines = Arrays.copyOf(oLines, n);
        oTotal = Arrays.copyOf(oTotal, n);
        oPaid = Arrays.copyOf(oPaid, n);
        oRefund = Arrays.copyOf(oRefund, n);
        oCod = Arrays.copyOf(oCod, n);
        shShipped = Arrays.copyOf(shShipped, n);
        shDelivered = Arrays.copyOf(shDelivered, n);
    }

    // ================================================================ заказ, строки, журнал

    void order(Order o, int logN, long paidAt, long asmAt, long shpAt, long dlvAt, long cncAt, byte status) {
        orders++;
        int id = o.id;
        ensureOrder(id);
        maxOrder = Math.max(maxOrder, id);
        oCreated[id] = (int) o.created;
        oDelivered[id] = dlvAt < 0 ? -1 : (int) dlvAt;
        oCustomer[id] = o.customer;
        oStatus[id] = status;
        oTotal[id] = o.total;
        oCod[id] = o.cod;
        oLines[id] = (byte) Math.min(127, o.lines.length);
        oLine0[id] = lineTop;
        // суммы и НДС
        LocalDate bd = w.businessDate(o.created);
        int shop = Tax.vatRate(bd);
        long items = 0, disc = 0, vat = 0;
        for (Order.Line l : o.lines) {
            lines++;
            items += l.unit * l.qty;
            disc += l.discount;
            int rate = l.seller == 0 ? shop : w.market.vatRate(l.seller, bd);
            if (l.vatRate != rate) bad(C_SUMS, "заказ " + id + " строка " + l.lineNo + ": ставка " + l.vatRate + ", на дату " + rate);
            long net = l.unit * l.qty - l.discount;
            if (net < 0 || l.discount < 0) bad(C_SUMS, "заказ " + id + " строка " + l.lineNo + ": скидка больше суммы");
            if (l.vat != Money.vatIncluded(net, rate)) bad(C_SUMS, "заказ " + id + " строка " + l.lineNo + ": НДС " + l.vat);
            vat += l.vat;
            if (l.seller == 0) {
                long p = w.prices.priceAt(l.product, o.created);
                if (p != l.unit || l.offer != 0) bad(C_PRICE, "заказ " + id + " строка " + l.lineNo + ": цена " + l.unit + ", в истории " + p);
            } else {
                int off = l.offer;
                boolean ok = off > 0 && w.market.offerSeller(off) == l.seller && w.market.offerProduct(off) == l.product
                        && w.market.offerPriceKop(off) == l.unit && w.market.offerFrom(off) <= o.created
                        && o.created < w.market.offerTo(off);
                if (!ok) bad(C_PRICE, "заказ " + id + " строка " + l.lineNo + ": предложение " + off);
                boolean fbs = !w.market.seller(l.seller).fbo();
                if (fbs == l.network) bad(C_PRICE, "заказ " + id + " строка " + l.lineNo + ": склад отбора у продавца " + l.seller);
            }
            if (lineTop == lNet.length) {
                int n = lNet.length + (lNet.length >> 1);
                lNet = Arrays.copyOf(lNet, n);
                lProduct = Arrays.copyOf(lProduct, n);
                lQty = Arrays.copyOf(lQty, n);
            }
            lNet[lineTop] = net;
            lProduct[lineTop] = l.product;
            lQty[lineTop++] = (short) l.qty;
        }
        long del = o.delivery;
        if (items != o.items || disc != o.discount || o.total != items - disc + del || del < 0
                || o.vat != vat + Money.vatIncluded(del, shop)) {
            bad(C_SUMS, "заказ " + id + ": " + o.items + " − " + o.discount + " + " + del + " ≠ " + o.total);
        }
        // журнал
        boolean plantedBad = planted.has("invalid_status_transition", id);
        long pa = -1, as = -1, sh = -1, dl = -1, cn = -1;
        for (int i = 0; i < logN; i++) {
            long at = o.logAt[i];
            byte st = o.logSt[i];
            if (!fact(at)) bad(C_TIME, "заказ " + id + ": статус в " + at);
            if (i == 0) {
                if (st != Order.CREATED || at != o.created) bad(C_LOG, "заказ " + id + ": первый статус не created");
            } else {
                if (at < o.logAt[i - 1]) bad(C_LOG, "заказ " + id + ": статус " + (i + 1) + " раньше предыдущего");
                if (!ALLOWED[o.logSt[i - 1]][st] && !plantedBad) {
                    bad(C_LOG, "заказ " + id + ": переход " + o.logSt[i - 1] + " → " + st);
                }
            }
            switch (st) {
                case Order.PAID -> pa = at;
                case Order.ASSEMBLED -> as = at;
                case Order.SHIPPED -> sh = at;
                case Order.DELIVERED -> dl = at;
                case Order.CANCELLED -> cn = at;
                default -> {
                }
            }
        }
        if (o.cod && dl >= 0) pa = dl;
        if (status != o.logSt[logN - 1] || pa != paidAt || as != asmAt || sh != shpAt || dl != dlvAt || cn != cncAt) {
            bad(C_LOG, "заказ " + id + ": поля моментов не совпали с журналом");
        }
        if ((status == Order.CANCELLED) != (cncAt >= 0) && !plantedBad) bad(C_LOG, "заказ " + id + ": отмена без статуса");
        if (o.estimated < o.created || o.estimated >= end) bad(C_TIME, "заказ " + id + ": обещано " + o.estimated);
        // доставка
        if (dlvAt >= 0 && shpAt >= 0 && dlvAt < shpAt) bad(C_DELIVERY, "заказ " + id + ": получен раньше отгрузки");
        if (dlvAt >= 0 && shDelivered[id] > 0 && dlvAt < shDelivered[id]) bad(C_DELIVERY, "заказ " + id + ": получен раньше доставки отгрузки");
        if (shpAt >= 0 && shShipped[id] > 0 && shpAt < shShipped[id] && !plantedBad) bad(C_DELIVERY, "заказ " + id + ": отгружен раньше отгрузки");
        boolean pickup = o.method == Order.PICKUP;
        if (pickup != (o.pickup != 0) || pickup == (o.address != 0)) bad(C_DELIVERY, "заказ " + id + ": адрес и пункт выдачи");
        if (o.pickup != 0 && w.geo.warehouse(o.pickup).kind() != Geo.Kind.PICKUP_POINT) bad(C_DELIVERY, "заказ " + id + ": не пункт выдачи");
        if (o.address != 0 && w.cs.addressCustomer(o.address) != o.customer && w.cs.sharedGroup(o.address) < 0) {
            bad(C_DELIVERY, "заказ " + id + ": чужой адрес");
        }
        // сессия
        if (o.session != 0 && o.channel == Order.PHONE) bad(C_SESS, "заказ " + id + ": сессия у заказа по телефону");
        if (o.session != 0) {
            int s = o.session;
            if (s >= sStart.length || sStart[s] == 0) bad(C_SESS, "заказ " + id + ": сессии " + s + " нет");
            else if (o.created < sStart[s] || o.created > sEnd[s] || sCustomer[s] != o.customer) {
                bad(C_SESS, "заказ " + id + ": вне своей сессии " + s);
            }
        }
    }

    // ================================================================ платежи, отгрузки, возвраты, отзывы

    void payment(int order, byte method, long amount, String status, long at, boolean installments) {
        payments++;
        ensureOrder(order);
        if (!fact(at)) bad(C_TIME, "платёж заказа " + order + " в " + at);
        boolean refund = status.equals("refunded");
        if (refund != (amount < 0) || amount == 0) bad(C_PAY, "заказ " + order + ": сумма " + amount + " при статусе " + status);
        if (installments != (method == OrderSim.INSTALLMENT && !refund)) bad(C_PAY, "заказ " + order + ": срок рассрочки");
        if (status.equals("succeeded") || refund) oPaid[order] += amount;
    }

    void shipment(int order, int wh, int carrier, int zone, long planned, long shipped, long delivered, String status) {
        shipments++;
        ensureOrder(order);
        if (w.geo.warehouse(wh).kind() != Geo.Kind.WAREHOUSE) bad(C_SHIP, "заказ " + order + ": склад " + wh);
        if (zone != 0) {
            Geo.Zone z = w.geo.zones().get(zone - 1);
            if (z.warehouseId() != wh || z.carrierId() != carrier) bad(C_SHIP, "заказ " + order + ": зона " + zone);
        } else if (carrier != 6) {
            bad(C_SHIP, "заказ " + order + ": вне зон — не почта");
        }
        // план — внутри окна, если окно покрывает 60 дней после «сейчас» (как в выпуске); в укороченном прогоне план
        // вправе выходить за его конец
        if (planned < start || (end >= cutoff + 60 * World.DAY && planned >= end)) {
            bad(C_TIME, "отгрузка заказа " + order + ": план " + planned);
        }
        if (shipped >= 0 && (!fact(shipped) || shipped < planned)) bad(C_SHIP, "заказ " + order + ": отгружено " + shipped);
        if (delivered >= 0 && (!fact(delivered) || shipped < 0 || delivered < shipped)) {
            bad(C_DELIVERY, "отгрузка заказа " + order + ": доставлено раньше отгрузки");
        }
        boolean ok = switch (status) {
            case "planned" -> shipped < 0 && delivered < 0;
            case "shipped", "in_transit", "lost" -> shipped >= 0 && delivered < 0;
            case "delivered", "returned" -> shipped >= 0 && delivered >= 0;
            default -> false;
        };
        if (!ok) bad(C_SHIP, "заказ " + order + ": статус " + status + " при моментах");
        if (shipped >= 0) shShipped[order] = Math.max(shShipped[order], (int) shipped);
        if (delivered >= 0) shDelivered[order] = Math.max(shDelivered[order], (int) delivered);
    }

    void ret(int order, int line, int qty, long requested, long received, long refund) {
        returns++;
        if (!fact(requested) || (received >= 0 && (!fact(received) || received < requested))) {
            bad(C_TIME, "возврат заказа " + order + ": моменты");
        }
        rets.add(new long[]{order, line, qty, requested, refund});
        if (refund > 0) {
            ensureOrder(order);
            oRefund[order] += refund;
        }
    }

    void review(int order, int product, int customer, long created) {
        reviews++;
        if (!fact(created)) bad(C_TIME, "отзыв о заказе " + order + " в " + created);
        revs.add(new int[]{order, product, customer, (int) created});
    }

    // ================================================================ сессии

    void session(int id, int customer, long s, long e) {
        sessions++;
        if (id >= sStart.length) {
            int n = Math.max(id + 1, sStart.length + (sStart.length >> 1));
            sStart = Arrays.copyOf(sStart, n);
            sEnd = Arrays.copyOf(sEnd, n);
            sCustomer = Arrays.copyOf(sCustomer, n);
        }
        sStart[id] = (int) s;
        sEnd[id] = (int) e;
        sCustomer[id] = customer;
        if (!fact(s) || e < s || !fact(e) || curSession != id || curAt > e) bad(C_SESS, "сессия " + id + ": начало и конец");
    }

    void event(int session, int seq, long at, int kind, String query) {
        events++;
        if (session != curSession) {
            curSession = session;
            curSeq = 0;
            curAt = Long.MIN_VALUE;
        }
        if (seq != curSeq + 1 || at < curAt || !fact(at)) bad(C_SESS, "сессия " + session + ": событие " + seq);
        curSeq = seq;
        curAt = at;
        if ((kind == 1) != (query != null)) bad(C_SESS, "сессия " + session + ": запрос у события " + seq);
        if (query != null && PublicNames.isInternal(query)) bad(C_QUERY, "сессия " + session + ": запрос");
    }

    // ================================================================ итог

    void report(CheckReport r) {
        // возвраты: не больше оплаченного за строку, после получения заказа
        for (long[] x : rets) {
            int o = (int) x[0], line = (int) x[1];
            if (o > maxOrder || oCreated[o] == 0 || line > oLines[o]) {
                bad(C_RET, "заказ " + o + ": нет строки " + line);
                continue;
            }
            int li = oLine0[o] + line - 1;
            if (x[2] > lQty[li] || x[2] <= 0) bad(C_RET, "заказ " + o + " строка " + line + ": штук " + x[2]);
            if (x[4] > Money.mulDiv(lNet[li], x[2], lQty[li])) bad(C_RET, "заказ " + o + " строка " + line + ": вернули " + x[4]);
            if (!planted.has("invalid_status_transition", o) && (oDelivered[o] < 0 || x[3] < oDelivered[o])) {
                bad(C_RET, "заказ " + o + ": возврат до получения");
            }
        }
        for (int[] x : revs) {
            int o = x[0];
            if (o > maxOrder || oCreated[o] == 0) {
                bad(C_REV, "отзыв о заказе " + o + ": заказа нет");
                continue;
            }
            boolean pb = planted.has("invalid_status_transition", o);
            if (!pb && (oDelivered[o] < 0 || x[3] < oDelivered[o])) bad(C_REV, "заказ " + o + ": отзыв до получения");
            if (oCustomer[o] != x[2]) bad(C_REV, "заказ " + o + ": отзыв чужого клиента");
            boolean has = false;
            for (int i = 0; i < oLines[o]; i++) if (lProduct[oLine0[o] + i] == x[1]) has = true;
            if (!has) bad(C_REV, "заказ " + o + ": товара " + x[1] + " нет в заказе");
        }
        // платежи сходятся
        for (int o = 1; o <= maxOrder; o++) {
            if (oCreated[o] == 0) continue;
            if (planted.has("double_payment", o) || planted.has("card_testing", o)) continue;
            byte st = oStatus[o];
            long paid = oPaid[o];
            if (paid < 0) {
                bad(C_PAY, "заказ " + o + ": вернули больше оплаченного");
                continue;
            }
            if (st == Order.CANCELLED) {
                if (paid != 0) bad(C_PAY, "заказ " + o + ": отменён, не возвращено " + paid);
                continue;
            }
            boolean settled = oCod[o] ? oDelivered[o] >= 0 : st != Order.CREATED;
            if (!settled) {
                if (oCod[o] && paid != 0) bad(C_PAY, "заказ " + o + ": оплата при получении до получения");
                continue;
            }
            if (paid != oTotal[o] - oRefund[o]) {
                bad(C_PAY, "заказ " + o + ": оплачено " + paid + ", сумма " + oTotal[o] + ", возвращено по возвратам " + oRefund[o]);
            }
        }
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            String what = switch (e.getKey()) {
                case C_SUMS, C_LOG, C_DELIVERY, C_PAY -> "заказов " + orders + ", строк " + lines;
                case C_PRICE -> "строк " + lines;
                case C_TIME -> "заказов " + orders + ", платежей " + payments + ", отгрузок " + shipments;
                case C_RET -> "возвратов " + returns;
                case C_REV -> "отзывов " + reviews;
                case C_SHIP -> "отгрузок " + shipments;
                default -> "сессий " + sessions + ", событий " + events;
            };
            r.expectNone("orders: " + e.getKey(), e.getValue()[0], examples.get(e.getKey()), what);
        }
    }
}
