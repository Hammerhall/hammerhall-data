package io.hammerhall.data.sto.stock;

import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.geo.GeoMath;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.RuCalendar;
import io.hammerhall.data.sto.StoKeys;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.geo.Geo;
import io.hammerhall.data.sto.geo.GeoModule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Заглушка модуля заказов: синтетический спрос на товары магазина, чтобы движок остатков можно было прогнать и
 * проверить одного. Сутки: число строк — Пуассон от прогноза ({@link CatalogForecast}) × календаря; товар — по весу,
 * покупатель — город по населению и точка вокруг центра, момент — по местному суточному ходу; дальше — как у
 * настоящего заказа: выбор склада, резерв, сборка через 1–20 ч, редкие отмены, ожидание нехватки (чем дольше ждать,
 * тем чаще отказ), возврат части строк (годный — на полку, брак — в списание). Ссылки {@code order}/{@code return}
 * в журнале — номера синтетических заказов и возвратов (таблиц заказов в этом прогоне нет).
 */
public final class DemandDriver implements DemandSource {
    private static final double[] HOURS = {0.3, 0.2, 0.1, 0.1, 0.1, 0.2, 0.4, 0.7, 1.0, 1.2, 1.3, 1.4, 1.4, 1.4, 1.3, 1.3,
            1.4, 1.5, 1.7, 1.9, 2.0, 1.9, 1.4, 0.8};
    private static final int NEW = 1, PICK = 2, CANCEL = 3, GIVE_UP = 4, RETURN = 5;

    private final CatalogForecast fc;
    private final Catalog cat;
    private final RuCalendar cal;
    private final List<Geo.City> cities;
    private final double[] cityCum;
    private final double[] hourCum;
    private final RandomStreams.Stream stCount, stLine, stLife;
    private final List<Catalog.Product> byLaunch;
    private int launchPtr;
    private long orderSeq, returnSeq, evSeq;
    private StockEngine engine;

    // учёт
    long lines, fromCity, fromDc, fromHub, waited, lost, cancelled, gaveUp, picked, returned, backFilled;

    private static final class Line {
        long orderId;
        int product, qty, wh, batch;
        long res = -1;
        boolean done, resellable;
    }

    private record Ev(long t, long seq, int type, Line line, double lat, double lon) {
    }

    private final PriorityQueue<Ev> queue = new PriorityQueue<>((a, b) -> a.t != b.t ? Long.compare(a.t, b.t)
            : Long.compare(a.seq, b.seq));
    /** Резерв → строка (только поиск по ключу; порядок обхода нигде не нужен). */
    private final Map<Long, Line> byRes = new HashMap<>();

    public DemandDriver(GenContext ctx, CatalogForecast fc) {
        this.fc = fc;
        this.cat = ctx.get(StoKeys.CATALOG);
        this.cal = ctx.calendar();
        Geo geo = ctx.get(StoKeys.GEO);
        this.cities = geo.cities();
        cityCum = new double[cities.size()];
        double acc = 0;
        for (int i = 0; i < cities.size(); i++) cityCum[i] = acc += cities.get(i).population();
        hourCum = new double[24];
        acc = 0;
        for (int i = 0; i < 24; i++) hourCum[i] = acc += HOURS[i];
        stCount = ctx.stream("stock.demand.count");
        stLine = ctx.stream("stock.demand.line");
        stLife = ctx.stream("stock.demand.life");
        byLaunch = new ArrayList<>(cat.products());
        byLaunch.sort((x, y) -> x.launchedOn.compareTo(y.launchedOn) != 0 ? x.launchedOn.compareTo(y.launchedOn)
                : Integer.compare(x.id, y.id));
        int maxId = 0;
        for (Catalog.Product p : cat.products()) maxId = Math.max(maxId, p.id);
        rate = new double[maxId + 1];
        rateWeek = new long[maxId + 1];
        java.util.Arrays.fill(rateWeek, Long.MIN_VALUE);
    }

    private final double[] rate;
    private final long[] rateWeek;
    private long tableWeek = Long.MIN_VALUE;
    private final List<List<Catalog.Product>> seasonProducts = new ArrayList<>();
    private final List<double[]> seasonCum = new ArrayList<>();
    private final double[] seasonTotal = new double[RuCalendar.Season.values().length];
    private final List<Catalog.Product> fresh = new ArrayList<>();
    private final List<Double> freshRate = new ArrayList<>();

    /** Строк в сутки без календаря на неделю: в четверг недели, но не раньше запуска и не после снятия. */
    private double weekRate(Catalog.Product p, LocalDate d, long monday) {
        if (rateWeek[p.id] == monday) return rate[p.id];
        LocalDate at = LocalDate.ofEpochDay(monday + 3);
        if (at.isBefore(p.launchedOn)) at = p.launchedOn;
        if (p.discontinuedPlan != null && !at.isBefore(p.discontinuedPlan)) at = d;
        rate[p.id] = p.aliveOn(at) ? fc.lineRate(p, at) : 0;
        rateWeek[p.id] = monday;
        return rate[p.id];
    }

    private void buildWeek(LocalDate d, long monday) {
        tableWeek = monday;
        while (launchPtr < byLaunch.size() && !byLaunch.get(launchPtr).launchedOn.isAfter(d)) launchPtr++;
        int ns = RuCalendar.Season.values().length;
        seasonProducts.clear();
        List<List<Double>> w = new ArrayList<>();
        for (int s = 0; s < ns; s++) {
            seasonProducts.add(new ArrayList<>());
            w.add(new ArrayList<>());
        }
        long weekEnd = monday + 7;
        for (int i = 0; i < launchPtr; i++) {
            Catalog.Product p = byLaunch.get(i);
            if (p.assortment == Catalog.Assortment.ONLY_3P) continue;
            if (p.discontinuedPlan != null && p.discontinuedPlan.toEpochDay() <= d.toEpochDay()) continue;
            if (p.launchedOn.toEpochDay() >= weekEnd) continue;
            double x = weekRate(p, d, monday);
            if (x <= 0) continue;
            int s = fc.season(p).ordinal();
            seasonProducts.get(s).add(p);
            w.get(s).add(x);
        }
        seasonCum.clear();
        for (int s = 0; s < ns; s++) {
            double[] c = new double[w.get(s).size()];
            double acc = 0;
            for (int i = 0; i < c.length; i++) c[i] = acc += w.get(s).get(i);
            seasonCum.add(c);
            seasonTotal[s] = acc;
        }
        fresh.clear();
        freshRate.clear();
    }

    /** Товар по доле {@code x} от суммы весов суток: сезон, затем внутри сезона; новые недели — в хвосте. */
    private Catalog.Product sampleProduct(double x, double[] calDay, double freshTotal) {
        for (int s = 0; s < seasonTotal.length; s++) {
            double part = calDay[s] * seasonTotal[s];
            if (x < part && seasonCum.get(s).length > 0) {
                double[] c = seasonCum.get(s);
                return seasonProducts.get(s).get(search(c, x / calDay[s]));
            }
            x -= part;
        }
        for (int i = 0; i < fresh.size(); i++) {
            x -= freshRate.get(i) * calDay[fc.season(fresh.get(i)).ordinal()];
            if (x < 0) return fresh.get(i);
        }
        return fresh.isEmpty() ? null : fresh.getLast();
    }

    @Override
    public Forecast forecast() {
        return fc;
    }

    private static int search(double[] cum, double x) {
        int lo = 0, hi = cum.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] > x) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    @Override
    public void day(LocalDate d, long dayStart, long dayEnd, StockEngine e) {
        this.engine = e;
        long ed = d.toEpochDay();
        long monday = ed - (d.getDayOfWeek().getValue() - 1);
        // веса товаров: гладкая часть — одна на неделю (четверг, как выборка прогноза движка), по сезонам; календарь —
        // на сутки. Неделя строится в первый её день из уже запущенных товаров; запущенные позже — отдельным списком
        // (на дату видны только товары, запущенные не позже неё: так продление периода не меняет прошлое)
        if (monday != tableWeek) buildWeek(d, monday);
        while (launchPtr < byLaunch.size() && !byLaunch.get(launchPtr).launchedOn.isAfter(d)) {
            Catalog.Product p = byLaunch.get(launchPtr++);
            if (p.assortment == Catalog.Assortment.ONLY_3P) continue;
            double x = weekRate(p, d, monday);
            if (x > 0) {
                fresh.add(p);
                freshRate.add(x);
            }
        }
        RuCalendar.Season[] ss = RuCalendar.Season.values();
        double[] calDay = new double[ss.length];
        double total = 0;
        for (RuCalendar.Season s : ss) {
            calDay[s.ordinal()] = cal.demand(d, s);
            total += calDay[s.ordinal()] * seasonTotal[s.ordinal()];
        }
        double freshTotal = 0;
        for (int i = 0; i < fresh.size(); i++) freshTotal += freshRate.get(i) * calDay[fc.season(fresh.get(i)).ordinal()];
        total += freshTotal;
        int n = stCount.at(ed).poisson(total);
        for (int i = 0; i < n; i++) {
            Rng r = stLine.at(ed, i);
            Catalog.Product p = sampleProduct(r.nextDouble() * total, calDay, freshTotal);
            // прореживание: товар снят с продажи посреди недели — строки нет
            if (p == null || !p.aliveOn(d)) continue;
            Geo.City c = cities.get(search(cityCum, r.nextDouble() * cityCum[cityCum.length - 1]));
            double[] pt = GeoMath.around(r, c.lat(), c.lon(), GeoModule.cityRadiusKm(c), 0.62);
            int hour = search(hourCum, r.nextDouble() * hourCum[23]);
            int offset = c.zone().getRules().getOffset(Instant.ofEpochSecond(dayStart)).getTotalSeconds();
            long local = hour * 3600L + r.nextInt(3600);
            long t = dayStart + Math.floorMod(local - offset, 86_400L);
            int qty;
            if (r.chance(0.004) && p.launchPriceKop < 30_000_00) qty = r.between(5, 20);
            else if (p.launchPriceKop < 1000_00) qty = 1 + r.poisson(0.4);
            else qty = r.chance(0.05) ? 2 : 1;
            Line l = new Line();
            l.orderId = ++orderSeq;
            l.product = p.id;
            l.qty = qty;
            queue.add(new Ev(t, evSeq++, NEW, l, pt[0], pt[1]));
        }
        while (!queue.isEmpty() && queue.peek().t < dayEnd) {
            Ev ev = queue.poll();
            long t = Math.max(ev.t, e.clock());
            Line l = ev.line;
            switch (ev.type) {
                case NEW -> newLine(l, t, ev.lat, ev.lon, dayEnd);
                case PICK -> {
                    if (l.done || l.res < 0 || !e.isReady(l.res)) break;
                    StockEngine.Picked pk = e.pick(l.res, t);
                    byRes.remove(l.res);
                    l.res = -1;
                    l.done = true;
                    l.batch = pk.batchId();
                    picked++;
                    Rng r = stLife.at(l.orderId, 1);
                    double pr = cat.product(l.product).kind.group.equals("accessories") ? 0.03 : 0.05;
                    if (r.chance(pr)) {
                        long rt = t + r.between(4, 30) * 86_400L + r.between(0, 86_399);
                        l.resellable = r.chance(0.65);
                        queue.add(new Ev(rt, evSeq++, RETURN, l, 0, 0));
                    }
                }
                case CANCEL, GIVE_UP -> {
                    if (l.done || l.res < 0) break;
                    if (ev.type == GIVE_UP && e.isReady(l.res)) break;
                    e.cancel(l.res, t);
                    byRes.remove(l.res);
                    l.res = -1;
                    l.done = true;
                    if (ev.type == CANCEL) cancelled++;
                    else gaveUp++;
                }
                case RETURN -> {
                    returned++;
                    e.customerReturn(++returnSeq, StockLedger.Ref.RETURN, l.wh, l.product, 0, l.batch, l.qty,
                            l.resellable, t);
                }
                default -> throw new IllegalStateException("событие " + ev.type);
            }
        }
    }

    private void newLine(Line l, long t, double lat, double lon, long dayEnd) {
        lines++;
        StockEngine e = engine;
        StockEngine.Offer o = e.route1P(l.product, l.qty, lat, lon, t);
        Rng r = stLife.at(l.orderId, 0);
        if (o.inStock()) {
            l.wh = o.warehouseId();
            int node = e.network().node(o.warehouseId());
            switch (e.network().kind[node]) {
                case WAREHOUSE -> fromCity++;
                case DC -> fromDc++;
                default -> fromHub++;
            }
            l.res = e.reserve(l.orderId, 1, l.wh, l.product, 0, l.qty, t);
            byRes.put(l.res, l);
            long pickAt = t + r.between(3600, 20 * 3600);
            if (r.chance(0.03)) queue.add(new Ev(t + r.between(600, (int) (pickAt - t)), evSeq++, CANCEL, l, 0, 0));
            queue.add(new Ev(pickAt, evSeq++, PICK, l, 0, 0));
            return;
        }
        if (o.readyAt() < 0) {
            lost++;
            return;
        }
        double days = (o.readyAt() - t) / 86_400.0;
        double pw = days <= 2 ? 0.85 : days <= 7 ? 0.6 : days <= 21 ? 0.35 : 0.1;
        if (!r.chance(pw)) {
            lost++;
            return;
        }
        waited++;
        l.wh = o.warehouseId();
        l.res = e.reserve(l.orderId, 1, l.wh, l.product, 0, l.qty, t);
        byRes.put(l.res, l);
        if (e.isReady(l.res)) {
            queue.add(new Ev(t + r.between(1800, 6 * 3600), evSeq++, PICK, l, 0, 0));
        } else {
            long patience = (long) (Math.max(3.0, days * 1.6) * 86_400);
            queue.add(new Ev(t + patience, evSeq++, GIVE_UP, l, 0, 0));
        }
    }

    @Override
    public void ready(long reservationId, long at) {
        Line l = byRes.get(reservationId);
        if (l == null) return;
        backFilled++;
        Rng r = stLife.at(l.orderId, 2);
        queue.add(new Ev(at + r.between(1800, 3 * 3600), evSeq++, PICK, l, 0, 0));
    }

    public String stats() {
        return String.format(java.util.Locale.ROOT,
                "строк %d: со склада %d, из РЦ %d, с хаба %d; ждали %d (дождались %d, передумали %d), без товара %d, "
                        + "отменено %d, отобрано %d, возвратов %d",
                lines, fromCity, fromDc, fromHub, waited, backFilled, gaveUp, lost, cancelled, picked, returned);
    }
}
