package io.hammerhall.data.sto.orders;

import io.hammerhall.data.core.gen.Ids;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.customers.Customers;
import io.hammerhall.data.sto.geo.Geo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static io.hammerhall.data.sto.orders.World.DAY;
import static io.hammerhall.data.sto.orders.World.HOUR;
import static io.hammerhall.data.sto.orders.World.MIN;

/**
 * Сессии сайта и приложения и их события — в журнале только посетители, согласившиеся на аналитику (в приложении —
 * чаще). У заказа такого посетителя — своя сессия: поиск, просмотры,
 * добавления в корзину, убранные при оформлении строки (товара нет и ждать не стали), оформление в момент создания
 * заказа, попытки оплаты. Остальные сессии — без заказа: заход на одну страницу, просмотр каталога, брошенная корзина
 * (часть доходит до оформления); больше половины — без входа (без клиента). Сессии суток нумеруются по началу и пишутся
 * в конце суток, когда известно, чем кончилось оформление.
 */
final class Sessions {
    private static final byte ORDER = 0, BOUNCE = 1, BROWSE = 2, CART = 3;
    private static final double[] HOURS = {0.4, 0.25, 0.12, 0.08, 0.07, 0.12, 0.3, 0.6, 0.95, 1.15, 1.3, 1.35, 1.4, 1.4,
            1.35, 1.35, 1.4, 1.5, 1.7, 1.95, 2.15, 2.1, 1.7, 1.05};

    private final World w;
    private final TableSink sSessions, sEvents;
    private final OrderAudit audit;
    private final Ids.Sequence ids;
    private final RandomStreams.Stream stOrder, stBrowse;
    private final double[] cityCum;
    private Picker picker;
    private final List<Plan> today = new ArrayList<>();
    long stSessions, stEvents, stAnon, stQueries;

    private static final class Plan {
        long key, start;
        int id, customer, city;
        byte kind, device, source;
        OrderSim.Intent it;
    }

    Sessions(World w, TableSink sessions, TableSink events, OrderAudit audit) {
        this.w = w;
        this.sSessions = sessions;
        this.sEvents = events;
        this.audit = audit;
        this.ids = w.ctx.ids().of("sessions");
        this.stOrder = w.ctx.stream("orders.session");
        this.stBrowse = w.ctx.stream("orders.browse");
        List<Geo.City> cities = w.geo.cities();
        cityCum = new double[cities.size()];
        double acc = 0;
        for (int i = 0; i < cities.size(); i++) cityCum[i] = acc += cities.get(i).population();
    }

    void bind(Picker p) {
        this.picker = p;
    }

    /** Сессии суток: у намерений заказа с сайта и из приложения, плюс {@code browse} сессий без заказа. */
    void plan(LocalDate d, long ds, List<OrderSim.Intent> intents, int browse, Customers.Sampler smp) {
        today.clear();
        for (OrderSim.Intent it : intents) {
            if (!it.tracked) continue;
            Rng r = stOrder.at(it.key);
            double minutes = r.logNormal(9, 0.6) + 2.0 * it.n;
            Plan p = new Plan();
            p.key = it.key;
            p.kind = ORDER;
            p.start = Math.max(ds, it.t - (long) (minutes * MIN));
            p.customer = it.customer;
            p.city = it.geoCity;
            p.device = it.device;
            p.source = it.source;
            p.it = it;
            today.add(p);
        }
        long ed = d.toEpochDay();
        Customers cs = w.cs;
        for (int i = 0; i < browse; i++) {
            Rng r = stBrowse.at(ed, i);
            Plan p = new Plan();
            p.key = (ed << 21) | (1L << 19) | i;
            double u = r.nextDouble();
            p.kind = u < 0.76 ? BOUNCE : u < 0.92 ? BROWSE : CART;
            int city;
            if (r.chance(0.42) && smp.size() > 0) {
                p.customer = smp.pick(r);
                city = cs.cityAt(p.customer, ds + 12 * HOUR);
                String dev = cs.device(p.customer);
                p.device = (byte) java.util.Arrays.asList(OrderSim.DEVICES).indexOf(dev);
            } else {
                stAnon++;
                city = w.geo.cities().get(Picker.search(cityCum, r.nextDouble() * cityCum[cityCum.length - 1])).id();
                p.device = (byte) r.weighted(new double[]{0.3, 0.45, 0.05, 0.2});
            }
            p.city = r.chance(0.06) ? 0 : city;
            p.source = (byte) r.weighted(p.device == 3 ? new double[]{0.42, 0.05, 0.12, 0.08, 0.08, 0.2, 0.05}
                    : new double[]{0.25, 0.36, 0.18, 0.09, 0.07, 0, 0.05});
            int off = w.offset(city, ds + 12 * HOUR);
            int h = r.weighted(HOURS);
            p.start = ds + Math.floorMod(h * HOUR + r.nextInt((int) HOUR) - off, DAY);
            today.add(p);
        }
        today.sort((a, b) -> a.start != b.start ? Long.compare(a.start, b.start) : Long.compare(a.key, b.key));
        for (Plan p : today) {
            p.id = ids.nextInt();
            if (p.it != null) {
                p.it.session = p.id;
                p.it.sessionStart = p.start;
            }
        }
    }

    // ---------------------------------------------------------------- события

    private final List<long[]> ev = new ArrayList<>();
    private final List<String> evQuery = new ArrayList<>();
    private static final byte VIEW = 0, SEARCH = 1, ADD = 2, REMOVE = 3, CHECKOUT = 4, PAYMENT = 5;
    private static final String[] KINDS = {"view", "search", "add_to_cart", "remove_from_cart", "checkout", "payment"};

    private void add(byte kind, int product, String query) {
        ev.add(new long[]{kind, product, 0});
        evQuery.add(query);
    }

    /** Записать сессии суток (по id) с событиями; события после конца фактов не пишутся. */
    void write(long cutoff) {
        for (Plan p : today) {
            ev.clear();
            evQuery.clear();
            Rng r = (p.kind == ORDER ? stOrder.at(p.key, 1) : stBrowse.at(p.key, 1));
            long end;
            if (p.kind == ORDER) end = orderEvents(p, r);
            else end = browseEvents(p, r);
            if (ev.isEmpty()) continue;
            int n = 0;
            long last = p.start;
            for (long[] x : ev) {
                if (x[2] >= cutoff) break;
                last = x[2];
                n++;
            }
            if (n == 0) continue;
            end = Math.min(Math.max(end, last), cutoff - 1);
            try {
                sSessions.add(p.id, p.customer == 0 ? null : p.customer, p.start, end, OrderSim.DEVICES[p.device],
                        OrderSim.SOURCES[p.source], p.city == 0 ? null : p.city);
                for (int i = 0; i < n; i++) {
                    long[] x = ev.get(i);
                    String q = evQuery.get(i);
                    sEvents.add(p.id, i + 1, x[2], KINDS[(int) x[0]], x[1] == 0 ? null : (int) x[1], q);
                    audit.event(p.id, i + 1, x[2], (int) x[0], q);
                    if (q != null) stQueries++;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            audit.session(p.id, p.customer, p.start, end);
            stSessions++;
            stEvents += n;
        }
        today.clear();
    }

    /** Разложить события по времени: {@code [from, to]} пропорционально случайным промежуткам. */
    private void spread(int fromIdx, long from, long to, Rng r) {
        int m = ev.size() - fromIdx;
        if (m <= 0) return;
        double[] gap = new double[m];
        double tot = 0;
        for (int i = 0; i < m; i++) tot += gap[i] = 0.3 + r.exponential(1.0);
        double span = Math.max(0, to - from);
        double acc = 0;
        for (int i = 0; i < m; i++) {
            acc += gap[i];
            ev.get(fromIdx + i)[2] = from + (long) (span * (acc - gap[i]) / tot);
        }
    }

    private long orderEvents(Plan p, Rng r) {
        OrderSim.Intent it = p.it;
        long checkout = it.t;
        Catalog cat = w.cat;
        boolean search = r.chance(0.32);
        if (search) add(SEARCH, 0, query(r, cat.product(it.pid[0])));
        for (int i = 0; i < it.n; i++) {
            if (r.chance(0.1)) {
                int rel = picker.related(it.pid[i], r);
                if (rel > 0) add(VIEW, rel, null);
            }
            if ((i == 0 && search) || (!it.companion[i] && r.chance(0.35))) add(VIEW, it.pid[i], null);
            add(ADD, it.pid[i], null);
        }
        int decoy = 0;
        if (r.chance(0.07)) {
            decoy = picker.related(it.pid[0], r);
            if (decoy > 0 && it.indexOf(decoy) < 0) add(ADD, decoy, null);
            else decoy = 0;
        }
        spread(0, p.start, checkout - 20, r);
        // при оформлении: убранные (товара нет — ждать не стали), передумал о лишнем
        int removed = 0;
        for (int i = 0; i < it.n; i++) if (it.removed != null && it.removed[i]) removed++;
        long lastT = ev.isEmpty() ? p.start : ev.getLast()[2];
        long at = Math.max(lastT, checkout - 2L * (removed + (decoy > 0 ? 1 : 0)) - 1);
        if (decoy > 0) {
            ev.add(new long[]{REMOVE, decoy, Math.min(at, checkout)});
            evQuery.add(null);
            at += 2;
        }
        for (int i = 0; i < it.n; i++) {
            if (it.removed == null || !it.removed[i]) continue;
            ev.add(new long[]{REMOVE, it.pid[i], Math.min(at, checkout)});
            evQuery.add(null);
            at += 2;
        }
        if (it.order == null) return checkout + 10 + r.nextInt(90);
        ev.add(new long[]{CHECKOUT, 0, checkout});
        evQuery.add(null);
        long last = checkout;
        for (long t : it.payAt) {
            ev.add(new long[]{PAYMENT, 0, t});
            evQuery.add(null);
            last = Math.max(last, t);
        }
        return last + 5 + r.nextInt(115);
    }

    private long browseEvents(Plan p, Rng r) {
        int dept = -1;
        if (p.customer > 0) {
            int[] fav = w.cs.favoriteDepartments(p.customer);
            if (fav.length > 0 && r.chance(0.6)) dept = picker.deptIndexOfCategory(fav[0]);
        }
        if (dept < 0) dept = picker.pickDept(r);
        int first = picker.pickInDept(dept, r);
        for (int tries = 0; first <= 0 && tries < 20; tries++) first = picker.pickInDept(picker.pickDept(r), r);
        if (first <= 0) first = 1;
        long dur;
        switch (p.kind) {
            case BOUNCE -> {
                if (r.chance(0.2)) add(SEARCH, 0, query(r, w.cat.product(first)));
                else add(VIEW, first, null);
                dur = 5 + r.nextInt(60);
            }
            case BROWSE -> {
                if (r.chance(0.4)) add(SEARCH, 0, query(r, w.cat.product(first)));
                add(VIEW, first, null);
                double v = r.nextDouble();
                int k = v < 0.4 ? 0 : v < 0.75 ? 1 : 2;
                for (int i = 0; i < k; i++) {
                    int x = r.chance(0.7) ? picker.related(first, r) : picker.pickInDept(picker.pickDept(r), r);
                    if (x > 0) add(VIEW, x, null);
                }
                dur = (long) (r.logNormal(5, 0.7) * MIN);
            }
            default -> {
                if (r.chance(0.35)) add(SEARCH, 0, query(r, w.cat.product(first)));
                add(VIEW, first, null);
                add(ADD, first, null);
                if (r.chance(0.4)) {
                    int x = picker.related(first, r);
                    if (x > 0 && x != first) {
                        add(VIEW, x, null);
                        add(ADD, x, null);
                        if (r.chance(0.3)) add(REMOVE, x, null);
                    }
                }
                if (r.chance(0.25)) add(CHECKOUT, 0, null);
                dur = (long) (r.logNormal(7, 0.6) * MIN);
            }
        }
        spread(0, p.start, p.start + dur, r);
        return p.start + dur + 3 + r.nextInt(40);
    }

    private static String query(Rng r, Catalog.Product p) {
        return Texts.query(r, p);
    }
}
