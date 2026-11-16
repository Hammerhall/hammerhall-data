package io.hammerhall.data.sto.orders;

import io.hammerhall.data.core.gen.Ids;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.money.Money;
import io.hammerhall.data.core.money.Tax;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.RuCalendar;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.Promotions;
import io.hammerhall.data.sto.customers.Customers;
import io.hammerhall.data.sto.geo.Geo;
import io.hammerhall.data.sto.market.Market;
import io.hammerhall.data.sto.stock.StockEngine;
import io.hammerhall.data.sto.stock.StockLedger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import static io.hammerhall.data.sto.orders.World.DAY;
import static io.hammerhall.data.sto.orders.World.HOUR;
import static io.hammerhall.data.sto.orders.World.MIN;

/**
 * Сутки за сутками: кто заказывает, что в корзине, по какой цене и со скидками, откуда и как везём; заказ живёт
 * событиями во времени на движке остатков (резерв → ожидание поступления → сборка → отгрузка → доставка), платежи,
 * отмены, возвраты и отзывы. Подробно — в javadoc {@link OrderModule}.
 */
final class OrderSim implements StockEngine.Listener {
    /**
     * Множитель к повторным заказам модели покупателей (её {@code activity} — ожидаемых заказов за 30 дней): нормировка
     * к ≈ 5 млн заказов за период в {@code full}. Константа — от настроенного периода не зависит.
     */
    static final double REPEAT_RATE = 1.07;
    /** Сессий без заказа на одну сессию с заказом (в журнале — только посетители с согласием на аналитику). */
    static final double BROWSE_PER_ORDER_SESSION = 5.6;
    /** Доля заказов с сайта и из приложения, чья сессия попала в журнал (согласие на аналитику). */
    static final double TRACKED_APP = 0.26, TRACKED_WEB = 0.13;

    static final String[] PAY_METHODS = {"card", "sbp", "installment", "cash_on_delivery", "gift_card", "points"};
    static final byte CARD = 0, SBP = 1, INSTALLMENT = 2, COD = 3, GIFT = 4, POINTS = 5;
    static final String[] DEVICES = {"desktop", "mobile_web", "tablet", "app"};
    static final String[] SOURCES = {"direct", "search", "ads", "social", "email", "push", "referral"};

    /** Суточный ход заказов по местному часу. */
    private static final double[] HOURS = {0.35, 0.2, 0.1, 0.07, 0.06, 0.1, 0.25, 0.55, 0.9, 1.15, 1.3, 1.4, 1.45, 1.45,
            1.4, 1.4, 1.45, 1.55, 1.75, 2.0, 2.2, 2.15, 1.7, 1.0};
    /** Телефонные заказы — часы работы операторов. */
    private static final double[] PHONE_HOURS = {0, 0, 0, 0, 0, 0, 0, 0, 0, 1.0, 1.4, 1.5, 1.4, 1.3, 1.3, 1.3, 1.3, 1.2,
            1.1, 1.0, 0.8, 0.5, 0, 0};

    private final World w;
    private final StockEngine e;
    private final Picker picker;
    private final PromoCalc promo;
    private final Sessions sessions;
    final OrderAudit audit;
    private final long cutoff;
    private final double baseMean;

    private final RandomStreams.Stream stFirst, stRepeat, stRepeatN, stBasket, stCreate, stPay, stFate, stShip, stAfter,
            stReturn, stReview, stSnow, stPvz, stBrowseN, stPlant;
    private final Ids.Sequence orderIds, paymentIds, shipmentIds, returnIds, reviewIds;
    final TableSink sOrders, sItems, sLog, sPayments, sShipments, sReturns, sReviews;

    // ---------------------------------------------------------------- события

    private static final int EV_CREATE = 1, EV_PAY = 2, EV_UNPAID = 3, EV_CONFIRM = 4, EV_READY = 5, EV_CUST_CANCEL = 6,
            EV_GIVE_UP = 7, EV_SELLER_CANCEL = 8, EV_NET_START = 9, EV_PICK = 10, EV_NET_DONE = 11, EV_FBS_START = 12,
            EV_FBS_DONE = 13, EV_COD_PAY = 14, EV_REFUND = 15, EV_STOCK_BACK = 16, EV_RET_REQUEST = 17,
            EV_RET_RECEIVE = 18, EV_RET_REFUND = 19, EV_REVIEW = 20, EV_DUP_PAY = 21, EV_DUP_REFUND = 22;

    private static final class Ev {
        final long t, seq;
        final int type;
        final Order o;
        final int a;
        final Object x;

        Ev(long t, long seq, int type, Order o, int a, Object x) {
            this.t = t;
            this.seq = seq;
            this.type = type;
            this.o = o;
            this.a = a;
            this.x = x;
        }
    }

    private final PriorityQueue<Ev> queue = new PriorityQueue<>((x, y) -> x.t != y.t ? Long.compare(x.t, y.t)
            : Long.compare(x.seq, y.seq));
    private long evSeq;
    private long now;

    /** Попытка платежа (заранее разыграна при оформлении). */
    record Attempt(long at, byte method, int installments, long amount, String status) {
    }

    /** Возврат строки (разыгран при доставке). */
    private record Ret(Order.Line line, int qty, String reason, String condition, String decision, long requested,
                       long received, long decided, long refund, boolean resellable) {
    }

    /** Отзыв (разыгран при доставке). */
    private record Review(int product, int score, long created, long answered, String text) {
    }

    // ---------------------------------------------------------------- намерения суток

    /** Намерение заказа: покупатель, момент, корзина, куда везти; итог — заказ или брошенная корзина. */
    static final class Intent {
        final long key;
        int customer;
        long t;
        byte channel, device, source;
        int geoCity;
        boolean b2b, first, cardTest, tracked;
        int n;
        int[] pid = new int[4], qty = new int[4], seller = new int[4], offer = new int[4];
        long[] unit = new long[4];
        boolean[] fbo = new boolean[4], companion = new boolean[4];
        byte method;
        int address, pickup;
        double lat, lon;
        int lmWh, lmCity, destCity, carrier, zone, baseDays;
        int session;
        long sessionStart;
        // итог
        Order order;
        boolean[] removed;
        long[] payAt = new long[0];

        Intent(long key) {
            this.key = key;
        }

        void add(int p, int q, int s, int off, long u, boolean f, boolean comp) {
            if (n == pid.length) {
                int m = n * 2;
                pid = Arrays.copyOf(pid, m);
                qty = Arrays.copyOf(qty, m);
                seller = Arrays.copyOf(seller, m);
                offer = Arrays.copyOf(offer, m);
                unit = Arrays.copyOf(unit, m);
                fbo = Arrays.copyOf(fbo, m);
                companion = Arrays.copyOf(companion, m);
            }
            pid[n] = p;
            qty[n] = q;
            seller[n] = s;
            offer[n] = off;
            unit[n] = u;
            fbo[n] = f;
            companion[n] = comp;
            n++;
        }

        int indexOf(int p) {
            for (int i = 0; i < n; i++) if (pid[i] == p) return i;
            return -1;
        }
    }

    // ---------------------------------------------------------------- окно записи заказов (по id)

    private Order[] win = new Order[1 << 16];
    private int winBase = 1, flushNext = 1, nextId = 1;
    private Order.Line[] resLine = new Order.Line[1 << 12];
    private Order[] resOrder = new Order[1 << 12];

    // учёт
    long stIntents, stFailed, stOrders, stLines, stLines1p, stLinesFbo, stLinesFbs, stRemoved, stWaited, stCancelled,
            stUnpaid, stGaveUp, stCustCancel, stSellerCancel, stDelivered, stReturns, stReviews, stShipments, stPayments,
            stFromCity, stFromDc, stFromHub, stCrossDock, stPlantedBad, stPlantedDouble, stPlantedCard;
    double expectedRepeat;

    OrderSim(World w, StockEngine e, TableSink[] sinks, Sessions sessions, OrderAudit audit) {
        this.w = w;
        this.e = e;
        this.sessions = sessions;
        this.audit = audit;
        this.picker = new Picker(w.cat, w.cal, w.maxProduct());
        this.promo = new PromoCalc(w.cat, w.promos, w.maxProduct());
        sessions.bind(picker);
        LocalDate lastFact = e.lastDay();
        this.cutoff = World.dayStart(lastFact.plusDays(1));
        // средний календарный множитель за опорный год — константа, от настроенного периода не зависит
        double acc = 0;
        int n = 0;
        for (LocalDate d = LocalDate.of(2026, 1, 1); d.getYear() == 2026; d = d.plusDays(1), n++) {
            acc += w.cal.demand(d, RuCalendar.Season.BASE);
        }
        this.baseMean = acc / n;
        var ctx = w.ctx;
        stFirst = ctx.stream("orders.first");
        stRepeat = ctx.stream("orders.repeat");
        stRepeatN = ctx.stream("orders.repeat_count");
        stBasket = ctx.stream("orders.basket");
        stCreate = ctx.stream("orders.create");
        stPay = ctx.stream("orders.pay");
        stFate = ctx.stream("orders.fate");
        stShip = ctx.stream("orders.ship");
        stAfter = ctx.stream("orders.after");
        stReturn = ctx.stream("orders.returns");
        stReview = ctx.stream("orders.reviews");
        stSnow = ctx.stream("orders.snow");
        stPvz = ctx.stream("orders.pickup_point");
        stBrowseN = ctx.stream("orders.browse_count");
        stPlant = ctx.stream("orders.planted");
        orderIds = ctx.ids().of("orders");
        paymentIds = ctx.ids().of("payments");
        shipmentIds = ctx.ids().of("shipments");
        returnIds = ctx.ids().of("returns");
        reviewIds = ctx.ids().of("reviews");
        sOrders = sinks[0];
        sItems = sinks[1];
        sLog = sinks[2];
        sPayments = sinks[3];
        sShipments = sinks[4];
        sReturns = sinks[5];
        sReviews = sinks[6];
    }

    long cutoff() {
        return cutoff;
    }

    private void schedule(long t, int type, Order o, int a, Object x) {
        queue.add(new Ev(t, evSeq++, type, o, a, x));
    }

    // ================================================================ сутки

    void day(LocalDate d) {
        long ds = World.dayStart(d), de = ds + DAY;
        long ed = d.toEpochDay();
        now = Math.max(now, ds);
        picker.day(d);
        promo.day(ds, de);
        Customers cs = w.cs;
        List<Intent> intents = new ArrayList<>();
        int[] first = cs.firstOrdersOn(d);
        for (int i = 0; i < first.length; i++) {
            Intent it = new Intent((ed << 21) | i);
            it.customer = first[i];
            it.first = true;
            it.t = cs.firstOrderAt(first[i]);
            if (fill(it, stFirst.at(ed, i), d)) intents.add(it);
        }
        Customers.Sampler smp = cs.sampler(d);
        double lam = REPEAT_RATE * smp.totalActivity() / 30.0 * w.cal.demand(d, RuCalendar.Season.BASE) / baseMean;
        expectedRepeat += lam;
        int nr = stRepeatN.at(ed).poisson(lam);
        for (int i = 0; i < nr; i++) {
            Rng r = stRepeat.at(ed, i);
            int c = smp.pick(r);
            if (c <= 0) continue;
            Intent it = new Intent((ed << 21) | (1 << 20) | i);
            it.customer = c;
            it.t = -1;
            if (fill(it, r, d)) intents.add(it);
        }
        intents.sort((a, b) -> a.t != b.t ? Long.compare(a.t, b.t) : Long.compare(a.key, b.key));
        stIntents += intents.size();
        // сессии суток: с заказом и без
        int orderSessions = 0;
        for (Intent it : intents) if (it.tracked) orderSessions++;
        int nb = stBrowseN.at(ed).poisson(orderSessions * BROWSE_PER_ORDER_SESSION);
        sessions.plan(d, ds, intents, nb, smp);
        for (Intent it : intents) schedule(it.t, EV_CREATE, null, 0, it);
        run(de);
        sessions.write(cutoff);
        flush();
    }

    /** Прожить события суток до {@code end} (исключая). */
    private void run(long end) {
        while (!queue.isEmpty() && queue.peek().t < end) {
            Ev ev = queue.poll();
            now = Math.max(now, ev.t);
            handle(ev, now);
        }
    }

    @Override
    public void ready(long reservationId, long at) {
        int r = (int) reservationId;
        Order.Line l = resLine[r];
        Order o = resOrder[r];
        if (l == null) return;
        schedule(Math.max(at, now), EV_READY, o, l.lineNo, null);
    }

    private void handle(Ev ev, long t) {
        Order o = ev.o;
        switch (ev.type) {
            case EV_CREATE -> create((Intent) ev.x, t);
            case EV_PAY -> pay(o, (Attempt) ev.x, t);
            case EV_DUP_PAY -> dupPay(o, (Attempt) ev.x, t);
            case EV_DUP_REFUND -> dupRefund(o, (Attempt) ev.x, t);
            case EV_UNPAID -> {
                if (!o.paid && !o.cancelled) {
                    stUnpaid++;
                    cancel(o, t);
                }
            }
            case EV_CONFIRM -> {
                o.confirmed = true;
                tryStart(o, t);
            }
            case EV_READY -> {
                Order.Line l = o.lines[ev.a - 1];
                if (o.cancelled || l.ready) break;
                l.ready = true;
                o.waiting--;
                tryStart(o, t);
            }
            case EV_CUST_CANCEL -> {
                if (!o.started && !o.cancelled) {
                    stCustCancel++;
                    cancel(o, t);
                }
            }
            case EV_GIVE_UP -> {
                if (!o.started && !o.cancelled && o.waiting > 0) {
                    stGaveUp++;
                    cancel(o, t);
                }
            }
            case EV_SELLER_CANCEL -> {
                if (!o.started && !o.cancelled) {
                    stSellerCancel++;
                    cancel(o, t);
                }
            }
            case EV_NET_START -> netStart(o, t);
            case EV_PICK -> pick(o, o.lines[ev.a - 1], t);
            case EV_NET_DONE -> partDone(o, o.parts[0], t);
            case EV_FBS_START -> fbsStart(o, o.parts[ev.a], t);
            case EV_FBS_DONE -> partDone(o, o.parts[ev.a], t);
            case EV_COD_PAY -> {
                o.addPayment(COD, o.total);
                payment(o, COD, null, o.total, "succeeded", t);
            }
            case EV_REFUND -> refundAll(o, t);
            case EV_STOCK_BACK -> {
                Order.Line l = o.lines[ev.a - 1];
                e.customerReturn(o.id, StockLedger.Ref.ORDER, o.lmWh, l.product, l.seller, l.batch, l.qty, true, t);
            }
            case EV_RET_REQUEST -> retRequest(o, (Ret) ev.x, t);
            case EV_RET_RECEIVE -> {
                Ret r = (Ret) ev.x;
                Order.Line l = r.line;
                e.customerReturn(ev.a, StockLedger.Ref.RETURN, o.lmWh, l.product, l.seller, l.batch, r.qty, r.resellable, t);
            }
            case EV_RET_REFUND -> {
                Ret r = (Ret) ev.x;
                byte m = o.mainMethod();
                payment(o, m < 0 ? CARD : m, null, -r.refund, "refunded", t);
            }
            case EV_REVIEW -> review(o, (Review) ev.x, t);
            default -> throw new IllegalStateException("событие " + ev.type);
        }
    }

    // ================================================================ намерение: кто, когда, что, куда

    private static int pickHour(Rng r, double[] w) {
        return r.weighted(w);
    }

    /** Заполнить намерение; {@code false} — корзина не собралась. */
    private boolean fill(Intent it, Rng r, LocalDate d) {
        Customers cs = w.cs;
        int c = it.customer;
        it.b2b = cs.b2b(c);
        int city = cs.cityAt(c, it.t < 0 ? World.dayStart(d) + 12 * HOUR : it.t);
        int age = cs.age(c, d);
        double pPhone = it.b2b ? 0.10 : age >= 60 ? 0.08 : 0.025;
        boolean phone = r.chance(pPhone);
        if (it.t < 0) {
            long ds = World.dayStart(d);
            int off = w.offset(city, ds + 12 * HOUR);
            int h = pickHour(r, phone ? PHONE_HOURS : HOURS);
            long local = h * HOUR + r.nextInt((int) HOUR);
            it.t = ds + Math.floorMod(local - off, DAY);
        }
        String dev = cs.device(c);
        if (phone) {
            it.channel = Order.PHONE;
        } else if (dev.equals("app")) {
            it.channel = r.chance(0.9) ? Order.APP : Order.WEB;
        } else {
            it.channel = Order.WEB;
        }
        it.device = (byte) (it.channel == Order.APP ? 3 : dev.equals("app") ? 1 : Arrays.asList(DEVICES).indexOf(dev));
        it.source = source(r, it.channel, cs.acquiredVia(c));
        it.tracked = it.channel != Order.PHONE && r.chance(it.channel == Order.APP ? TRACKED_APP : TRACKED_WEB);
        it.geoCity = r.chance(0.04) ? 0 : cs.cityAt(c, it.t);
        it.cardTest = it.first && !it.b2b && stPlant.at(it.key).chance(0.0009);
        if (!basket(it, r, d)) return false;
        return deliver(it, r, d, city);
    }

    private static byte source(Rng r, byte channel, String acquired) {
        // direct, search, ads, social, email, push, referral
        double[] wgt = channel == Order.APP ? new double[]{0.42, 0.05, 0.10, 0.06, 0.10, 0.22, 0.05}
                : new double[]{0.30, 0.33, 0.15, 0.07, 0.09, 0, 0.06};
        switch (acquired) {
            case "search" -> wgt[1] *= 1.5;
            case "ads" -> wgt[2] *= 1.8;
            case "social" -> wgt[3] *= 2.0;
            case "referral" -> wgt[6] *= 2.0;
            default -> wgt[0] *= 1.2;
        }
        return (byte) r.weighted(wgt);
    }

    /** Корзина: якорные товары (любимые отделы или общий спрос), спутники по правилам, штуки, продавец. */
    private boolean basket(Intent it, Rng r, LocalDate d) {
        Customers cs = w.cs;
        int c = it.customer;
        Rng rb = stBasket.at(it.key);
        int anchors;
        if (it.b2b) {
            anchors = 1 + Math.min(25, rb.poisson(cs.basketScale(c) * 0.5));
        } else {
            double u = rb.nextDouble();
            anchors = u < 0.44 ? 1 : u < 0.71 ? 2 : u < 0.85 ? 3 : u < 0.93 ? 4 : 5 + rb.nextInt(4);
        }
        int[] fav = cs.favoriteDepartments(c);
        int[] favIdx = new int[fav.length];
        for (int i = 0; i < fav.length; i++) favIdx[i] = picker.deptIndexOfCategory(fav[i]);
        double[] favW = {0.5, 0.3, 0.2};
        int nAnchor = 0;
        for (int a = 0; a < anchors; a++) {
            for (int tries = 0; tries < 6; tries++) {
                int dept;
                if (favIdx.length > 0 && rb.chance(0.65)) {
                    dept = favIdx[rb.weighted(Arrays.copyOf(favW, favIdx.length))];
                    if (dept < 0) dept = picker.pickDept(rb);
                } else {
                    dept = picker.pickDept(rb);
                }
                int pid = picker.pickInDept(dept, rb);
                if (pid <= 0) continue;
                if (it.indexOf(pid) >= 0) break;
                if (addLine(it, pid, rb, d, false)) {
                    nAnchor++;
                    break;
                }
            }
        }
        if (nAnchor == 0) return false;
        // «покупают вместе»
        int base = it.n;
        for (int i = 0; i < base; i++) {
            Catalog.Product p = w.cat.product(it.pid[i]);
            for (Catalog.Companion rule : picker.rules(p)) {
                if (!rb.chance(rule.probability())) continue;
                int cp = picker.companion(p, rule, rb);
                if (cp <= 0 || it.indexOf(cp) >= 0) continue;
                addLine(it, cp, rb, d, true);
            }
        }
        // штуки
        double bs = cs.basketScale(c);
        for (int i = 0; i < it.n; i++) {
            long rub = it.unit[i] / 100;
            int q;
            if (rub < 1000) q = 1 + rb.poisson(0.35);
            else if (rub < 5000) q = rb.chance(0.06) ? 2 : 1;
            else q = rb.chance(0.015) ? 2 : 1;
            if (it.b2b && rub < 30_000) q *= 1 + rb.poisson(bs * 0.4);
            it.qty[i] = Math.min(99, q);
        }
        return true;
    }

    /** Строка корзины: товар в продаже и у кого покупаем (магазин или продавец). */
    private boolean addLine(Intent it, int pid, Rng r, LocalDate d, boolean companion) {
        Catalog.Product p = w.cat.product(pid);
        if (!p.aliveOn(d)) return false;
        long p1 = w.prices.priceAt(pid, it.t);
        Market.OfferAt o = w.market.choose(pid, it.t, p1, p.assortment, r);
        if (o == null) {
            if (p1 <= 0 || p.assortment == Catalog.Assortment.ONLY_3P) return false;
            it.add(pid, 1, 0, 0, p1, false, companion);
        } else {
            if (!w.market.seller(o.sellerId()).activeAt(it.t)) return false;
            it.add(pid, 1, o.sellerId(), o.offerId(), o.priceKop(), o.fbo(), companion);
        }
        return true;
    }

    /** Куда везти: пункт выдачи (доля покупателя; без адреса — всегда), курьер в зоне доставки, иначе почта. */
    private boolean deliver(Intent it, Rng r, LocalDate d, int city) {
        Customers cs = w.cs;
        int c = it.customer;
        Rng rd = stFate.at(it.key, 1);
        int addr = cs.addressAt(c, it.t, rd);
        boolean pickup = addr < 0 || rd.chance(cs.pickupShare(c));
        Geo geo = w.geo;
        if (pickup) {
            List<Geo.Warehouse> pts = geo.pickupPointsOpen(city, d);
            if (pts.isEmpty()) {
                if (addr < 0) return false;
                pickup = false;
            } else {
                Geo.Warehouse pv = pts.get(stPvz.at(c, city).nextInt(pts.size()));
                it.method = Order.PICKUP;
                it.pickup = pv.id();
                it.address = 0;
                it.lat = pv.lat();
                it.lon = pv.lon();
                it.destCity = pv.city().id();
                Geo.Warehouse par = pv.parentId() == null ? null : geo.warehouse(pv.parentId());
                if (par != null && par.kind() == Geo.Kind.WAREHOUSE && par.openOn(d)) it.lmWh = par.id();
                else it.lmWh = e.network().warehouseId(e.network().nearestCity(it.lat, it.lon, d));
                int[] order = {1, 5, 6};
                pickCarrier(it, order, rd);
            }
        }
        if (!pickup) {
            it.address = addr;
            it.pickup = 0;
            it.lat = cs.addressLat(addr);
            it.lon = cs.addressLon(addr);
            it.destCity = cs.addressCity(addr);
            it.lmWh = e.network().warehouseId(e.network().nearestCity(it.lat, it.lon, d));
            it.method = Order.COURIER;
            boolean express = rd.chance(it.b2b ? 0.05 : 0.12);
            int[] order = express ? new int[]{4, 1, 2, 3} : new int[]{1, 2, 3};
            if (!pickCarrier(it, order, rd)) {
                it.method = Order.POST;
                pickCarrier(it, new int[]{6}, rd);
            }
        }
        it.lmCity = geo.warehouse(it.lmWh).city().id();
        return true;
    }

    /** Первый перевозчик из списка, чья зона склада последней мили содержит точку; иначе — почта вне зон. */
    private boolean pickCarrier(Intent it, int[] carriers, Rng r) {
        for (int c : carriers) {
            Geo.Zone z = w.geo.zoneFor(it.lmWh, c, it.lat, it.lon);
            if (z != null) {
                it.carrier = c;
                it.zone = z.id();
                it.baseDays = z.baseDays();
                return true;
            }
        }
        if (carriers.length == 1 || it.method == Order.PICKUP) {
            Geo.Zone z = w.geo.zoneFor(it.lmWh, 6, it.lat, it.lon);
            it.carrier = 6;
            it.zone = z == null ? 0 : z.id();
            it.baseDays = z == null ? Geo.OUT_OF_ZONE_DAYS : z.baseDays();
            return true;
        }
        return false;
    }

    // ================================================================ оформление

    private void create(Intent it, long t) {
        Rng r = stCreate.at(it.key);
        int n = it.n;
        boolean[] keep = new boolean[n];
        long[] eta = new long[n];
        int[] wh = new int[n];
        boolean any = false;
        it.removed = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean net = it.seller[i] == 0 || it.fbo[i];
            if (!net) {
                keep[i] = true;
                eta[i] = t;
                any = true;
                continue;
            }
            StockEngine.Offer o = it.seller[i] == 0 ? e.route1P(it.pid[i], it.qty[i], it.lat, it.lon, t)
                    : e.routeFbo(it.seller[i], it.pid[i], it.qty[i], it.lat, it.lon, t);
            if (o.inStock()) {
                keep[i] = true;
                eta[i] = t;
            } else if (o.readyAt() >= 0) {
                double days = (o.readyAt() - t) / (double) DAY;
                double pw = days <= 2 ? 0.85 : days <= 7 ? 0.6 : days <= 21 ? 0.35 : 0.1;
                if (it.b2b) pw = Math.min(0.95, pw * 1.2);
                keep[i] = r.chance(pw);
                eta[i] = o.readyAt();
                if (keep[i]) stWaited++;
            }
            if (keep[i]) {
                wh[i] = o.warehouseId();
                any = true;
            } else {
                it.removed[i] = true;
                stRemoved++;
            }
        }
        if (!any) {
            stFailed++;
            return;
        }
        Order o = new Order(it.key);
        o.id = orderIds.nextInt();
        o.customer = it.customer;
        o.session = it.session;
        o.channel = it.channel;
        o.method = it.method;
        o.address = it.address;
        o.pickup = it.pickup;
        o.lat = it.lat;
        o.lon = it.lon;
        o.lmWh = it.lmWh;
        o.lmCity = it.lmCity;
        o.destCity = it.destCity;
        o.carrier = it.carrier;
        o.zone = it.zone;
        o.baseDays = it.baseDays;
        o.created = t;
        o.b2b = it.b2b;
        int m = 0;
        for (int i = 0; i < n; i++) if (keep[i]) m++;
        o.lines = new Order.Line[m];
        int k = 0;
        List<Integer> fbsSellers = new ArrayList<>();
        boolean hasNet = false;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            Order.Line l = new Order.Line();
            l.lineNo = k + 1;
            l.product = it.pid[i];
            l.seller = it.seller[i];
            l.offer = it.offer[i];
            l.qty = it.qty[i];
            l.unit = it.unit[i];
            l.network = it.seller[i] == 0 || it.fbo[i];
            l.warehouse = wh[i];
            l.readyEta = eta[i];
            if (l.network) hasNet = true;
            else if (!fbsSellers.contains(l.seller)) fbsSellers.add(l.seller);
            o.lines[k++] = l;
            stLines++;
            if (l.seller == 0) stLines1p++;
            else if (l.network) stLinesFbo++;
            else stLinesFbs++;
        }
        // части исполнения: через склады магазина (часть 0, если есть), посылки продавцов FBS
        o.parts = new Order.Part[(hasNet ? 1 : 0) + fbsSellers.size()];
        int pi = 0;
        if (hasNet) o.parts[pi++] = new Order.Part();
        for (int s : fbsSellers) {
            Order.Part p = new Order.Part();
            p.fbs = true;
            p.seller = s;
            o.parts[pi++] = p;
        }
        for (Order.Line l : o.lines) {
            if (l.network) l.part = 0;
            else for (int j = 0; j < o.parts.length; j++) if (o.parts[j].fbs && o.parts[j].seller == l.seller) l.part = j;
        }
        // цены и скидки
        Customers cs = w.cs;
        String code = null;
        List<Promotions.Promotion> codes = promo.codesAt(t);
        if (!codes.isEmpty() && r.chance(cs.promoSensitivity(it.customer) * 0.22)) {
            List<Promotions.Promotion> ev = new ArrayList<>();
            for (Promotions.Promotion p : codes) if (p.event() != null) ev.add(p);
            List<Promotions.Promotion> from = !ev.isEmpty() && r.chance(0.6) ? ev : codes;
            code = from.get(r.nextInt(from.size())).promoCode();
        }
        promo.apply(o.lines, code, t);
        o.promoCode = code;
        LocalDate bd = w.businessDate(t);
        int shopVat = Tax.vatRate(bd);
        long items = 0, disc = 0, vat = 0;
        for (Order.Line l : o.lines) {
            l.vatRate = l.seller == 0 ? shopVat : w.market.vatRate(l.seller, bd);
            l.vat = Money.vatIncluded(l.net(), l.vatRate);
            items += l.gross();
            disc += l.discount;
            vat += l.vat;
        }
        long net = items - disc;
        o.delivery = switch (o.method) {
            case Order.PICKUP -> net >= 1000_00 ? 0 : 99_00;
            case Order.COURIER -> net >= 3500_00 ? 0 : o.carrier == 4 ? 499_00 : 299_00;
            default -> net >= 5000_00 ? 0 : o.zone == 0 ? 499_00 : 349_00;
        };
        if (o.b2b && net >= 30_000_00) o.delivery = 0;
        o.items = items;
        o.discount = disc;
        o.vat = vat + Money.vatIncluded(o.delivery, shopVat);
        o.total = net + o.delivery;
        o.estimated = estimate(o, it, t);
        // резервы
        for (Order.Line l : o.lines) {
            if (!l.network) {
                l.ready = true;
                continue;
            }
            l.res = e.reserve(o.id, l.lineNo, l.warehouse, l.product, l.seller, l.qty, t);
            bindRes(l.res, o, l);
            if (e.isReady(l.res)) {
                l.ready = true;
            } else {
                o.waiting++;
            }
            Geo.Kind kind = w.geo.warehouse(l.warehouse).kind();
            if (kind == Geo.Kind.WAREHOUSE) stFromCity++;
            else if (kind == Geo.Kind.DC) stFromDc++;
            else stFromHub++;
            if (l.warehouse != o.lmWh) stCrossDock++;
        }
        o.log(Order.CREATED, t);
        register(o);
        it.order = o;
        stOrders++;
        // оплата
        payPlan(o, it, t, r);
        // судьба до сборки: передумал, не дождался, продавец отказал
        Rng f = stFate.at(it.key, 2);
        if (f.chance(o.waiting > 0 ? 0.07 : 0.045)) {
            schedule(t + 5 * MIN + (long) f.exponential(1.6 * HOUR), EV_CUST_CANCEL, o, 0, null);
        }
        if (o.waiting > 0) {
            long maxEta = t;
            for (Order.Line l : o.lines) maxEta = Math.max(maxEta, l.readyEta);
            double days = (maxEta - t) / (double) DAY;
            long patience = (long) (Math.max(3.0, days * f.uniform(1.2, 2.2)) * DAY);
            schedule(t + Math.min(patience, 40 * DAY), EV_GIVE_UP, o, 0, null);
        }
        for (int j = 0; j < o.parts.length; j++) {
            Order.Part p = o.parts[j];
            if (!p.fbs) continue;
            Market.Seller s = w.market.seller(p.seller);
            if (f.chance(Math.min(0.3, 0.012 * s.cancelFactor()))) {
                schedule(t + 40 * MIN + (long) f.exponential(5 * HOUR), EV_SELLER_CANCEL, o, j, null);
            }
        }
    }

    private void bindRes(long res, Order o, Order.Line l) {
        int r = (int) res;
        if (r >= resLine.length) {
            int n = Math.max(r + 1, resLine.length * 2);
            resLine = Arrays.copyOf(resLine, n);
            resOrder = Arrays.copyOf(resOrder, n);
        }
        resLine[r] = l;
        resOrder[r] = o;
    }

    private void unbindRes(long res) {
        resLine[(int) res] = null;
        resOrder[(int) res] = null;
    }

    /** Обещанная дата: готовность × сборка × перевозка до склада последней мили × срок зоны; 22:00 местного дня. */
    private long estimate(Order o, Intent it, long t) {
        Geo.Warehouse lm = w.geo.warehouse(o.lmWh);
        long est = t;
        for (Order.Part p : o.parts) {
            long x;
            if (!p.fbs) {
                long ready = t, tr = 0;
                for (Order.Line l : o.lines) {
                    if (!l.network) continue;
                    ready = Math.max(ready, l.readyEta);
                    Geo.Warehouse src = w.geo.warehouse(l.warehouse);
                    if (src.id() != lm.id()) tr = Math.max(tr, World.transit(src.lat(), src.lon(), lm.lat(), lm.lon()));
                }
                x = ready + 6 * HOUR + tr + 3 * HOUR;
            } else {
                Market.Seller s = w.market.seller(p.seller);
                Geo.City sc = w.geo.city(s.cityId());
                x = t + s.handlingDays() * DAY + 6 * HOUR + World.transit(sc.lat(), sc.lon(), lm.lat(), lm.lon());
            }
            // ожидаемый срок в пути: зона × перевозчик + округ, запас — полдня и пятая часть срока
            double transitDays = o.baseDays * carrierFactor(o.carrier);
            x += (long) ((transitDays * 1.2 + regionDays(it.destCity) + 0.5) * DAY) + (o.baseDays == 0 ? 8 * HOUR : 0);
            est = Math.max(est, x);
        }
        int city = it.destCity;
        int off = w.offset(city, est);
        long local = est + off;
        long dayLocal = local - Math.floorMod(local, DAY);
        // обещание — план: не позже конца окна данных
        return Math.min(dayLocal + 22 * HOUR - off, w.period.endEpochExclusive() - 1);
    }

    private void register(Order o) {
        int idx = o.id - winBase;
        if (idx >= win.length) {
            // сдвинуть окно: записанные (null) — в начале
            int live = nextId - flushNext;
            int shift = flushNext - winBase;
            if (shift > 0) {
                System.arraycopy(win, shift, win, 0, live);
                Arrays.fill(win, live, win.length, null);
                winBase = flushNext;
            }
            idx = o.id - winBase;
            if (idx >= win.length) win = Arrays.copyOf(win, Math.max(win.length * 2, idx + 1));
        }
        win[idx] = o;
        nextId = o.id + 1;
    }

    // ================================================================ платежи

    private void payPlan(Order o, Intent it, long t, Rng r) {
        Rng p = stPay.at(it.key);
        long total = o.total;
        List<Attempt> plan = new ArrayList<>();
        if (it.cardTest) {
            // заложено: «проверка карт» — серия мелких авторизаций разными картами, заказ не оплачен
            o.planted |= Order.P_CARD_TEST;
            stPlantedCard++;
            w.ctx.planted().add("card_testing", o.id);
            int k = p.between(4, 12);
            long at = t + p.between(20, 90);
            for (int i = 0; i < k; i++) {
                plan.add(new Attempt(at, CARD, 0, p.between(1, 15) * 100L + p.nextInt(100), p.chance(0.55) ? "authorized" : "failed"));
                at += p.between(5, 70);
            }
            schedulePlan(o, it, plan);
            schedule(at + p.between(15, 60) * MIN, EV_UNPAID, o, 0, null);
            return;
        }
        boolean codOk = !o.b2b && o.method != Order.POST && total <= 150_000_00;
        double inst = total >= 15_000_00 ? Math.min(0.38, 0.08 + 0.11 * StrictMath.log(total / 15_000_00.0) / StrictMath.log(2)) : 0;
        if (o.b2b) inst = 0;
        // card, sbp, installment, cod, gift, points
        double[] wgt = o.b2b ? new double[]{0.6, 0.4, 0, 0, 0, 0}
                : new double[]{0.55, 0.24, inst, codOk ? 0.12 : 0, 0.02, 0.05};
        byte method = (byte) p.weighted(wgt);
        if (method == COD) {
            o.cod = true;
            schedule(t + p.between(2, 20) * MIN, EV_CONFIRM, o, 0, null);
            return;
        }
        long at = t + p.between(15, 120);
        long rest = total;
        if (method == POINTS || method == GIFT) {
            long part = method == POINTS ? Math.max(100, Money.percent(total, p.between(3, 20) * 100L))
                    : new long[]{1000_00, 2000_00, 3000_00, 5000_00, 10_000_00}[p.nextInt(5)];
            part = Math.min(part, total);
            plan.add(new Attempt(at, method, 0, part, "succeeded"));
            rest -= part;
            at += p.between(5, 40);
            method = p.chance(0.7) ? CARD : SBP;
        }
        if (rest > 0) {
            int months = 0;
            if (method == INSTALLMENT) {
                months = total >= 80_000_00 ? new int[]{6, 10, 12, 24}[p.nextInt(4)] : new int[]{3, 4, 6, 10}[p.nextInt(4)];
            }
            for (int attempt = 0; attempt < 3; attempt++) {
                double fail = switch (method) {
                    case INSTALLMENT -> 0.15;
                    case SBP -> 0.03;
                    default -> 0.065;
                };
                boolean ok = !p.chance(fail);
                String st = ok ? "succeeded" : (method == SBP && p.chance(0.5) ? "cancelled" : "failed");
                plan.add(new Attempt(at, method, months, rest, st));
                if (ok) break;
                if (!p.chance(0.75)) break;
                at += p.between(40, 400);
                method = p.chance(0.7) ? CARD : SBP;
                months = 0;
            }
        }
        schedulePlan(o, it, plan);
        Attempt last = plan.getLast();
        if (!"succeeded".equals(last.status())) schedule(last.at() + p.between(15, 60) * MIN, EV_UNPAID, o, 0, null);
        // заложено: двойное списание (вторая успешная операция той же суммы), часть — возвращена через несколько дней
        if ("succeeded".equals(last.status()) && last.method() == CARD && stPlant.at(it.key, 1).chance(0.0005)) {
            Rng q = stPlant.at(it.key, 2);
            o.planted |= Order.P_DOUBLE_PAY;
            stPlantedDouble++;
            w.ctx.planted().add("double_payment", o.id);
            Attempt dup = new Attempt(last.at() + q.between(2, 40), CARD, 0, last.amount(), "succeeded");
            schedule(dup.at(), EV_DUP_PAY, o, 0, dup);
            if (q.chance(0.5)) schedule(dup.at() + q.between(2, 9) * DAY + q.nextInt((int) DAY), EV_DUP_REFUND, o, 0, dup);
        }
    }

    private void schedulePlan(Order o, Intent it, List<Attempt> plan) {
        it.payAt = new long[plan.size()];
        for (int i = 0; i < plan.size(); i++) {
            it.payAt[i] = plan.get(i).at();
            schedule(plan.get(i).at(), EV_PAY, o, i, plan.get(i));
        }
    }

    private void pay(Order o, Attempt a, long t) {
        payment(o, a.method(), a.method() == INSTALLMENT && a.installments() > 0 ? a.installments() : null, a.amount(),
                a.status(), t);
        if (!"succeeded".equals(a.status())) return;
        o.addPayment(a.method(), a.amount());
        o.paidAmount += a.amount();
        if (o.cancelled) {
            // заказ отменили раньше, чем прошёл платёж (редкость) — сразу вернуть
            payment(o, a.method(), null, -a.amount(), "refunded", t);
            o.payAmount[o.payN - 1] = 0;
            return;
        }
        if (o.paidAmount >= o.total && !o.paid) {
            o.paid = true;
            o.log(Order.PAID, t);
            tryStart(o, t);
        }
    }

    private void dupPay(Order o, Attempt a, long t) {
        payment(o, a.method(), null, a.amount(), "succeeded", t);
        o.addPayment(a.method(), a.amount());
        if (o.cancelled) {
            payment(o, a.method(), null, -a.amount(), "refunded", t);
            o.payAmount[o.payN - 1] = 0;
        }
    }

    private void dupRefund(Order o, Attempt a, long t) {
        // вернуть лишнее списание, если его ещё не вернули с отменой заказа
        for (int i = 0; i < o.payN; i++) {
            if (o.payMethod[i] == a.method() && o.payAmount[i] == a.amount()) {
                payment(o, a.method(), null, -a.amount(), "refunded", t);
                o.payAmount[i] = 0;
                return;
            }
        }
    }

    /** Вернуть все успешные платежи заказа (отмена). */
    private void refundAll(Order o, long t) {
        for (int i = 0; i < o.payN; i++) {
            if (o.payAmount[i] <= 0) continue;
            payment(o, o.payMethod[i], null, -o.payAmount[i], "refunded", t);
            o.payAmount[i] = 0;
        }
    }

    private void payment(Order o, byte method, Integer installments, long amount, String status, long t) {
        int id = paymentIds.nextInt();
        int seq = ++o.paySeq;
        try {
            sPayments.add(id, o.id, (short) seq, PAY_METHODS[method], installments == null ? null : installments.shortValue(),
                    amount, status, t);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.payment(o.id, method, amount, status, t, installments != null);
        stPayments++;
    }

    // ================================================================ сборка

    private void tryStart(Order o, long t) {
        if (o.cancelled) return;
        if (!(o.paid || (o.cod && o.confirmed))) return;
        Rng r = stShip.at(o.key, 0);
        for (int j = 0; j < o.parts.length; j++) {
            Order.Part p = o.parts[j];
            if (!p.fbs || p.startAt >= 0) continue;
            p.startAt = Long.MAX_VALUE;
            schedule(t + 30 * MIN + (long) r.uniform(0, 7.5 * HOUR), EV_FBS_START, o, j, null);
        }
        if (o.parts.length > 0 && !o.parts[0].fbs && o.parts[0].startAt < 0 && o.waiting == 0) {
            o.parts[0].startAt = Long.MAX_VALUE;
            schedule(t + 10 * MIN + (long) r.exponential(1.2 * HOUR), EV_NET_START, o, 0, null);
        }
    }

    private void markAssembling(Order o, long t) {
        if (!o.started) {
            o.started = true;
            o.log(Order.ASSEMBLING, t);
        }
    }

    private void netStart(Order o, long t) {
        if (o.cancelled) return;
        Order.Part p = o.parts[0];
        p.startAt = t;
        markAssembling(o, t);
        Rng r = stShip.at(o.key, 1);
        for (Order.Line l : o.lines) {
            if (!l.network) continue;
            o.netPicksLeft++;
            schedule(t + 5 * MIN + r.nextInt((int) (2 * HOUR)), EV_PICK, o, l.lineNo, null);
        }
    }

    private void pick(Order o, Order.Line l, long t) {
        StockEngine.Picked pk = e.pick(l.res, t);
        unbindRes(l.res);
        l.res = -1;
        l.picked = true;
        l.pickedAt = t;
        l.batch = pk.batchId();
        o.netPickMax = Math.max(o.netPickMax, t);
        if (--o.netPicksLeft == 0) {
            Rng r = stShip.at(o.key, 2);
            schedule(t + 15 * MIN + r.nextInt((int) HOUR), EV_NET_DONE, o, 0, null);
        }
    }

    private void fbsStart(Order o, Order.Part p, long t) {
        if (o.cancelled) return;
        p.startAt = t;
        markAssembling(o, t);
        Market.Seller s = w.market.seller(p.seller);
        Rng r = stShip.at(o.key, 10 + p.seller);
        double days = Math.max(0.3, s.handlingDays() * r.uniform(0.6, 1.3));
        if (r.chance(Math.min(0.6, 0.07 * s.lateFactor()))) days += r.uniform(1, 3) * s.lateFactor();
        int idx = Arrays.asList(o.parts).indexOf(p);
        schedule(t + (long) (days * DAY), EV_FBS_DONE, o, idx, null);
    }

    /** Часть собрана: план и факт отгрузки на складе последней мили, доставка до точки — разыграны сразу. */
    private void partDone(Order o, Order.Part p, long t) {
        p.assembledAt = t;
        Rng r = stShip.at(o.key, 100 + Arrays.asList(o.parts).indexOf(p));
        boolean single = o.parts.length == 1;
        long base = t;
        if (single && !p.fbs && r.chance(0.006)) {
            p.reassembledAt = t + HOUR + r.nextInt((int) (4 * HOUR));
            base = p.reassembledAt;
        }
        Geo.Warehouse lm = w.geo.warehouse(o.lmWh);
        long tr = 0;
        int weight = 0, bulky = 0, smallW = 0;
        for (Order.Line l : o.lines) {
            if (l.part != Arrays.asList(o.parts).indexOf(p)) continue;
            Catalog.Product pr = w.cat.product(l.product);
            weight += pr.weightG * l.qty;
            long vol = (long) pr.lengthMm * pr.widthMm * pr.heightMm / 1_000_000L;
            if (vol > 40 || pr.weightG > 15_000) bulky += l.qty;
            else smallW += pr.weightG * l.qty;
            if (l.network) {
                Geo.Warehouse src = w.geo.warehouse(l.warehouse);
                if (src.id() != lm.id()) tr = Math.max(tr, World.transit(src.lat(), src.lon(), lm.lat(), lm.lon()));
            }
        }
        if (p.fbs) {
            Geo.City sc = w.geo.city(w.market.seller(p.seller).cityId());
            tr = World.transit(sc.lat(), sc.lon(), lm.lat(), lm.lon());
        }
        p.weight = Math.max(1, weight);
        p.boxes = Math.min(Short.MAX_VALUE, bulky + (smallW > 0 ? 1 + smallW / 20_000 : 0));
        p.planned = base + tr + 30 * MIN + r.nextInt((int) (2.5 * HOUR));
        long delay = (long) r.exponential(1.5 * HOUR);
        LocalDate pd = World.utcDate(p.planned);
        boolean peak = peak(pd);
        if (peak && r.chance(0.6)) delay += (long) (r.uniform(0.3, 1.8) * DAY);
        p.shipped = p.planned + delay;
        if (single && r.chance(0.004)) {
            p.firstShipped = p.shipped;
            p.shipped = p.firstShipped + 18 * HOUR + r.nextInt((int) (12 * HOUR));
        }
        // в пути: срок зоны × перевозчик × шум + регион + снег + распродажи
        double days = o.baseDays * carrierFactor(o.carrier) * r.logNormal(1.0, 0.22);
        int dc = w.cityDistrict[o.destCity];
        days += regionDays(o.destCity) * r.uniform(0.6, 1.4);
        days += snow(dc, World.utcDate(p.shipped), r);
        if (peak(World.utcDate(p.shipped))) days += r.uniform(0, 1.0);
        long arrival = o.baseDays == 0 && days < 0.5 ? p.shipped + 2 * HOUR + r.nextInt((int) (5 * HOUR))
                : p.shipped + (long) (days * DAY);
        arrival = switch (o.method) {
            case Order.COURIER -> w.alignLocal(o.destCity, arrival, 10, 21, r);
            case Order.PICKUP -> w.alignLocal(o.destCity, arrival, 9, 20, r);
            default -> w.alignLocal(o.destCity, arrival, 9, 18, r);
        };
        p.arrival = Math.max(arrival, p.shipped + HOUR);
        if (single && r.chance(0.0008)) {
            p.lost = true;
            p.lostAt = p.shipped + (long) (r.uniform(5, 12) * DAY);
        }
        if (++o.partsAssembled == o.parts.length) allAssembled(o, t);
    }

    private boolean peak(LocalDate d) {
        int m = d.getMonthValue(), dom = d.getDayOfMonth();
        if (m == 11 && dom >= 9 && dom <= 14) return true;
        LocalDate bf = RuCalendar.blackFriday(d.getYear());
        if (!d.isBefore(bf.minusDays(1)) && !d.isAfter(bf.plusDays(6))) return true;
        return m == 12 && dom >= 15;
    }

    /** Поправка срока на округ и север (средняя, дней): Дальний Восток и Сибирь — дольше. */
    private double regionDays(int city) {
        double d = switch (w.cityDistrict[city]) {
            case 7 -> 1.2;
            case 6 -> 0.6;
            case 5 -> 0.3;
            case 3 -> 0.4;
            case 1 -> 0.2;
            default -> 0.0;
        };
        return w.geo.city(city).lat() > 62 ? d + 0.8 : d;
    }

    private static double carrierFactor(int carrier) {
        return switch (carrier) {
            case 1 -> 1.0;
            case 2, 3 -> 1.1;
            case 4 -> 0.5;
            case 5 -> 1.15;
            case 6 -> 1.4;
            default -> 1.2;
        };
    }

    /** Снегопады: недели зимы, у северных и восточных округов — чаще; задержка 1–3 дня в округе всю неделю. */
    private double snow(int district, LocalDate d, Rng r) {
        int m = d.getMonthValue();
        if (m >= 4 && m <= 11) return 0;
        double p = switch (district) {
            case 1 -> 0.18;
            case 5, 6, 7 -> 0.22;
            case 2 -> 0.05;
            case 3 -> 0.04;
            default -> 0.12;
        };
        if (m == 3) p *= 0.5;
        long week = Math.floorDiv(d.toEpochDay() + 3, 7);
        Rng s = stSnow.at(district, week);
        if (!s.chance(p)) return 0;
        return s.uniform(1, 3) * r.uniform(0.7, 1.0);
    }

    // ================================================================ после сборки: всё разыграно сразу

    private void allAssembled(Order o, long t) {
        Rng r = stAfter.at(o.key);
        boolean single = o.parts.length == 1;
        long asm = Long.MIN_VALUE, shipped = Long.MIN_VALUE, arrival = Long.MIN_VALUE;
        for (Order.Part p : o.parts) {
            asm = Math.max(asm, p.assembledAt);
            shipped = Math.max(shipped, p.shipped);
            arrival = Math.max(arrival, p.arrival);
        }
        o.log(Order.ASSEMBLED, asm);
        Order.Part p0 = o.parts[0];
        if (single && p0.reassembledAt >= 0) {
            o.log(Order.ASSEMBLING, asm + (p0.reassembledAt - asm) / 3);
            o.log(Order.ASSEMBLED, p0.reassembledAt);
        }
        if (single && p0.firstShipped >= 0) {
            o.log(Order.SHIPPED, p0.firstShipped);
            o.log(Order.ASSEMBLED, p0.firstShipped + 3 * HOUR + r.nextInt((int) (7 * HOUR)));
        }
        o.log(Order.SHIPPED, shipped);
        long cancelAt = -1;
        boolean stockBack = false;
        long delivered = -1;
        int shipStatus; // 0 — доставлено, 1 — вернулось отправителю, 2 — утеряно
        if (single && p0.lost) {
            cancelAt = p0.lostAt;
            shipStatus = 2;
        } else if (o.method == Order.PICKUP) {
            o.log(Order.READY, arrival);
            if (single && r.chance(0.003)) {
                long fw = arrival + 2 * HOUR + r.nextInt((int) (6 * HOUR));
                o.log(Order.SHIPPED, fw);
                arrival = w.alignLocal(o.destCity, fw + DAY + r.nextInt((int) DAY), 9, 20, r);
                for (Order.Part p : o.parts) p.arrival = arrival;
                o.log(Order.READY, arrival);
            }
            if (r.chance(o.cod ? 0.06 : 0.03)) {
                cancelAt = arrival + 7 * DAY + r.nextInt((int) (6 * HOUR));
                stockBack = true;
                shipStatus = 1;
            } else {
                delivered = w.alignLocal(o.destCity, arrival + 30 * MIN + (long) r.exponential(1.1 * DAY), 10, 21, r);
                shipStatus = 0;
            }
        } else if (o.method == Order.COURIER) {
            if (single && r.chance(o.cod ? 0.025 : 0.004)) {
                cancelAt = arrival;
                stockBack = true;
                shipStatus = 1;
            } else {
                delivered = arrival;
                shipStatus = 0;
            }
        } else {
            if (r.chance(0.03)) {
                cancelAt = arrival + 15 * DAY;
                stockBack = true;
                shipStatus = 1;
            } else {
                delivered = w.alignLocal(o.destCity, arrival + 30 * MIN + (long) r.exponential(1.3 * DAY), 9, 19, r);
                shipStatus = 0;
            }
        }
        writeShipments(o, shipStatus, cancelAt);
        if (cancelAt >= 0) {
            o.log(Order.CANCELLED, cancelAt);
            o.cancelled = true;
            if (o.paidAmount > 0 || o.payN > 0) schedule(cancelAt, EV_REFUND, o, 0, null);
            if (stockBack) {
                long back = cancelAt + (long) (r.uniform(0.3, 2.5) * DAY);
                for (Order.Line l : o.lines) if (l.network) schedule(back, EV_STOCK_BACK, o, l.lineNo, null);
            }
        } else {
            o.log(Order.DELIVERED, delivered);
            stDelivered++;
            if (o.cod) schedule(delivered, EV_COD_PAY, o, 0, null);
            afterDelivery(o, delivered, o.method == Order.PICKUP ? arrival : delivered);
        }
        plantTransition(o);
        o.assembledAll = true;
        o.finalized = true;
    }

    private void writeShipments(Order o, int fate, long endAt) {
        for (Order.Part p : o.parts) {
            int id = shipmentIds.nextInt();
            Long shippedAt = p.shipped < cutoff ? p.shipped : null;
            boolean arrived = !p.lost && p.arrival < cutoff;
            Long deliveredAt = arrived ? p.arrival : null;
            String status;
            if (shippedAt == null) status = "planned";
            else if (p.lost) status = p.lostAt < cutoff ? "lost" : "in_transit";
            else if (fate == 1 && endAt < cutoff) status = "returned";
            else if (arrived) status = "delivered";
            else status = cutoff - p.shipped < 12 * HOUR ? "shipped" : "in_transit";
            try {
                sShipments.add(id, o.id, o.lmWh, o.carrier, o.zone == 0 ? null : o.zone, p.planned, shippedAt, deliveredAt,
                        status, p.weight, (short) p.boxes);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            audit.shipment(o.id, o.lmWh, o.carrier, o.zone, p.planned, shippedAt == null ? -1 : shippedAt,
                    deliveredAt == null ? -1 : deliveredAt, status);
            stShipments++;
        }
    }

    /** Возвраты и отзывы строк доставленного заказа. */
    private void afterDelivery(Order o, long delivered, long arrived) {
        // опоздание — против обещанного: для пункта выдачи — когда заказ туда пришёл
        boolean late = arrived > o.estimated + 12 * HOUR;
        long lastRefund = -1;
        int fullReturns = 0;
        for (Order.Line l : o.lines) {
            Rng r = stReturn.at(o.key, l.lineNo);
            Catalog.Product p = w.cat.product(l.product);
            Market.Seller s = l.seller == 0 ? null : w.market.seller(l.seller);
            double base = switch (p.kind.group) {
                case "accessories" -> 0.026;
                case "phones" -> 0.04;
                case "computers" -> 0.036;
                case "audio" -> 0.048;
                case "beauty", "appliances_small" -> 0.042;
                case "appliances_large", "furniture" -> 0.024;
                case "tv" -> 0.032;
                default -> 0.036;
            };
            double pr = 1.25 * base * (s == null ? 1 : s.returnFactor()) * (late ? 1.5 : 1) * (o.b2b ? 0.6 : 1);
            boolean defectSeller = s != null && s.quality() != Market.Quality.NORMAL;
            if (r.chance(Math.min(0.6, pr))) {
                String reason = reason(r, late, defectSeller);
                double lag = switch (reason) {
                    case "defect" -> Math.min(80, 1 + r.exponential(16));
                    case "damaged_in_delivery", "wrong_item" -> 0.1 + r.exponential(1.2);
                    default -> Math.min(13.5, 0.2 + r.exponential(4));
                };
                long req = delivered + HOUR + (long) (lag * DAY);
                int q = l.qty == 1 || r.chance(0.75) ? l.qty : r.between(1, l.qty - 1);
                long recv = w.alignLocal(o.destCity, req + (long) (r.uniform(0.5, 6) * DAY), 10, 20, r);
                String cond = switch (reason) {
                    case "changed_mind", "late_delivery" -> r.chance(0.55) ? "new" : r.chance(0.8) ? "opened" : "used";
                    case "defect" -> r.chance(0.6) ? "used" : r.chance(0.6) ? "opened" : "damaged";
                    case "damaged_in_delivery" -> "damaged";
                    case "wrong_item" -> r.chance(0.8) ? "new" : "opened";
                    default -> r.chance(0.5) ? "opened" : "used";
                };
                String dec;
                double u = r.nextDouble();
                if (reason.equals("defect")) dec = u < 0.6 ? "refund" : u < 0.78 ? "replace" : u < 0.92 ? "repair" : "rejected";
                else if (reason.equals("changed_mind") && cond.equals("used")) dec = u < 0.5 ? "rejected" : "refund";
                else if (reason.equals("not_as_described")) dec = u < (defectSeller ? 0.7 : 0.85) ? "refund" : "rejected";
                else if (reason.equals("wrong_item")) dec = u < 0.7 ? "refund" : "replace";
                else dec = u < 0.93 ? "refund" : "rejected";
                long decided = recv + 2 * HOUR + (long) (r.uniform(0, 3) * DAY);
                long refund = dec.equals("refund") ? Money.mulDiv(l.net(), q, l.qty) : 0;
                boolean resell = (cond.equals("new") || cond.equals("opened"))
                        && !reason.equals("defect") && !reason.equals("damaged_in_delivery");
                Ret ret = new Ret(l, q, reason, cond, dec, req, recv, decided, refund, resell);
                schedule(req, EV_RET_REQUEST, o, 0, ret);
                if (dec.equals("refund") && q == l.qty) {
                    fullReturns++;
                    lastRefund = Math.max(lastRefund, decided);
                }
            }
            // отзыв
            Rng v = stReview.at(o.key, l.lineNo);
            double pv = 0.145 * (l.unit >= 10_000_00 ? 1.3 : 1) * (o.b2b ? 0.35 : 1) * (late ? 1.2 : 1);
            if (v.chance(pv)) {
                double mean = 4.6 + (s == null ? 0.05 : s.scoreShift()) - (late ? 0.8 : 0)
                        + (p.brand.tier().equals("premium") ? 0.1 : p.brand.tier().equals("budget") ? -0.15 : 0);
                int score = (int) Math.max(1, Math.min(5, StrictMath.round(mean + v.gaussian() * 1.0)));
                if (v.chance(0.05)) score = 1;
                long created = delivered + 2 * HOUR + (long) (v.exponential(5.5) * DAY);
                double ansRate = s == null ? (score <= 3 ? 0.75 : 0.35) : s.answerRate() * (score <= 2 ? 1.4 : 1);
                long answered = v.chance(Math.min(0.95, ansRate)) ? created + 20 * MIN + (long) (v.exponential(1.4) * DAY) : -1;
                String text = v.chance(0.72) ? Texts.review(v, score, p, late) : null;
                schedule(created, EV_REVIEW, o, 0, new Review(l.product, score, created, answered, text));
            }
        }
        if (fullReturns == o.lines.length && lastRefund > 0) o.log(Order.RETURNED, lastRefund);
    }

    private static String reason(Rng r, boolean late, boolean badSeller) {
        // defect, not_as_described, changed_mind, wrong_item, damaged_in_delivery, late_delivery
        double[] wgt = {0.30, 0.15, 0.35, 0.07, 0.08, late ? 0.35 : 0.01};
        if (badSeller) {
            wgt[0] *= 1.6;
            wgt[1] *= 2.5;
            wgt[3] *= 2.0;
        }
        return new String[]{"defect", "not_as_described", "changed_mind", "wrong_item", "damaged_in_delivery",
                "late_delivery"}[r.weighted(wgt)];
    }

    private void retRequest(Order o, Ret r, long t) {
        int id = returnIds.nextInt();
        boolean recv = r.received < cutoff, dec = r.decided < cutoff;
        try {
            sReturns.add(id, o.id, (short) r.line.lineNo, (short) r.qty, t, recv ? r.received : null, r.reason,
                    recv ? r.condition : null, dec ? r.decision : null, dec ? r.refund : null);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.ret(o.id, r.line.lineNo, r.qty, t, recv ? r.received : -1, dec ? r.refund : -1);
        stReturns++;
        boolean stock = r.line.network && (r.decision.equals("refund") || r.decision.equals("replace"));
        if (stock) schedule(r.received, EV_RET_RECEIVE, o, id, r);
        if (r.refund > 0) schedule(r.decided, EV_RET_REFUND, o, id, r);
    }

    private void review(Order o, Review v, long t) {
        int id = reviewIds.nextInt();
        Long ans = v.answered >= 0 && v.answered < cutoff ? v.answered : null;
        try {
            sReviews.add(id, o.id, v.product, o.customer, (short) v.score, t, ans, v.text);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.review(o.id, v.product, o.customer, t);
        stReviews++;
    }

    /** Заложено: недопустимый переход статуса — пропущенная отгрузка или «оплачен» после получения. */
    private void plantTransition(Order o) {
        Rng r = stPlant.at(o.key, 3);
        if (!r.chance(0.0007)) return;
        int di = -1;
        for (int i = 0; i < o.logN; i++) if (o.logSt[i] == Order.DELIVERED) di = i;
        if (di < 0) return;
        if (r.chance(0.5)) {
            // «собран → получен»: в журнале нет отгрузки
            int k = 0;
            for (int i = 0; i < o.logN; i++) {
                if (o.logSt[i] == Order.SHIPPED || o.logSt[i] == Order.READY) continue;
                o.logAt[k] = o.logAt[i];
                o.logSt[k++] = o.logSt[i];
            }
            o.logN = k;
        } else {
            // «получен → оплачен → получен»: повторная запись оплаты после получения
            long at = o.logAt[di];
            List<long[]> rest = new ArrayList<>();
            for (int i = di + 1; i < o.logN; i++) rest.add(new long[]{o.logAt[i], o.logSt[i]});
            o.logN = di + 1;
            long p = at + 20 * MIN + r.nextInt((int) (3 * HOUR));
            o.log(Order.PAID, p);
            o.log(Order.DELIVERED, p + 5 * MIN + r.nextInt((int) HOUR));
            for (long[] x : rest) if (x[0] >= p) o.log((byte) x[1], x[0]);
        }
        o.planted |= Order.P_BAD_TRANSITION;
        stPlantedBad++;
        w.ctx.planted().add("invalid_status_transition", o.id);
    }

    // ================================================================ отмена

    private void cancel(Order o, long t) {
        if (o.cancelled || o.started) return;
        o.cancelled = true;
        for (Order.Line l : o.lines) {
            if (l.res >= 0) {
                e.cancel(l.res, t);
                unbindRes(l.res);
                l.res = -1;
            }
        }
        o.log(Order.CANCELLED, t);
        refundAll(o, t);
        o.finalized = true;
        stCancelled++;
    }

    // ================================================================ запись заказов по порядку id

    private void flush() {
        while (flushNext < nextId) {
            Order o = win[flushNext - winBase];
            if (o == null || !o.finalized) break;
            write(o);
            win[flushNext - winBase] = null;
            flushNext++;
        }
    }

    /** Конец фактов: дописать все заказы (не дошедшие до конца — со статусом на «сейчас»). */
    void finish() {
        while (flushNext < nextId) {
            Order o = win[flushNext - winBase];
            write(o);
            win[flushNext - winBase] = null;
            flushNext++;
        }
        queue.clear();
    }

    private void write(Order o) {
        // журнал — до конца фактов
        int n = 0;
        while (n < o.logN && o.logAt[n] < cutoff) n++;
        long paid = -1, asm = -1, shp = -1, dlv = -1, cnc = -1;
        for (int i = 0; i < n; i++) {
            switch (o.logSt[i]) {
                case Order.PAID -> paid = o.logAt[i];
                case Order.ASSEMBLED -> asm = o.logAt[i];
                case Order.SHIPPED -> shp = o.logAt[i];
                case Order.DELIVERED -> dlv = o.logAt[i];
                case Order.CANCELLED -> cnc = o.logAt[i];
                default -> {
                }
            }
        }
        if (o.cod && dlv >= 0) paid = dlv;
        byte status = o.logSt[n - 1];
        try {
            sOrders.add(o.id, o.customer, o.address == 0 ? null : o.address, o.pickup == 0 ? null : o.pickup,
                    o.session == 0 ? null : o.session, Order.CHANNELS[o.channel], Order.METHODS[o.method], o.created,
                    paid < 0 ? null : paid, asm < 0 ? null : asm, shp < 0 ? null : shp, dlv < 0 ? null : dlv,
                    cnc < 0 ? null : cnc, o.estimated, io.hammerhall.data.sto.StoSchema.ORDER_STATUSES[status],
                    o.promoCode, o.items, o.discount, o.delivery, o.vat, o.total);
            for (Order.Line l : o.lines) {
                sItems.add(o.id, (short) l.lineNo, l.product, l.seller == 0 ? null : l.seller, l.offer == 0 ? null : l.offer,
                        (short) l.qty, l.unit, l.discount, (short) l.vatRate, l.vat, l.network ? l.warehouse : null,
                        l.promo == 0 ? null : l.promo);
            }
            for (int i = 0; i < n; i++) {
                sLog.add(o.id, (short) (i + 1), o.logAt[i], io.hammerhall.data.sto.StoSchema.ORDER_STATUSES[o.logSt[i]]);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.order(o, n, paid, asm, shp, dlv, cnc, status);
    }

    String stats() {
        return String.format(java.util.Locale.ROOT,
                "намерений %d (корзина не собралась %d), заказов %d, строк %d (магазин %d, FBO %d, FBS %d); строк убрано при "
                        + "оформлении %d, ждали поступления %d; со склада %d, из РЦ %d, с хаба %d, через склад последней мили %d; "
                        + "отмен до сборки %d (не оплачено %d, передумал %d, не дождался %d, продавец %d); доставлено %d; "
                        + "отгрузок %d, платежей %d, возвратов %d, отзывов %d; ожидалось повторных %.0f; заложено: переходы %d, "
                        + "двойные %d, проверка карт %d",
                stIntents, stFailed, stOrders, stLines, stLines1p, stLinesFbo, stLinesFbs, stRemoved, stWaited, stFromCity,
                stFromDc, stFromHub, stCrossDock, stCancelled, stUnpaid, stCustCancel, stGaveUp, stSellerCancel, stDelivered,
                stShipments, stPayments, stReturns, stReviews, expectedRepeat, stPlantedBad, stPlantedDouble, stPlantedCard);
    }
}
