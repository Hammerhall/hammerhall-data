package io.hammerhall.data.sto.orders;

import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.geo.GeoMath;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.core.time.RuCalendar;
import io.hammerhall.data.sto.StoKeys;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.PriceBook;
import io.hammerhall.data.sto.catalog.Promotions;
import io.hammerhall.data.sto.customers.Customers;
import io.hammerhall.data.sto.geo.Geo;
import io.hammerhall.data.sto.market.Market;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;

/**
 * Мир для модуля заказов: модели предыдущих модулей и общие вычисления — местное время города, дата бизнеса (Москва),
 * перевозка между точками, конец фактов. Неизменяем; на генерацию влияет только через свои функции.
 */
final class World {
    static final long DAY = 86_400L, HOUR = 3_600L, MIN = 60L;

    final GenContext ctx;
    final Period period;
    final RuCalendar cal;
    final double scale;
    final Catalog cat;
    final PriceBook prices;
    final Promotions promos;
    final Geo geo;
    final Market market;
    final Customers cs;
    /** Смещение пояса города, секунд (на период поясов без переходов — постоянное; иначе — по моменту). */
    private final int[] cityOffset;
    private final ZoneId[] cityZone;
    private final boolean[] cityFixed;
    /** Индекс федерального округа города (для задержек доставки). */
    final int[] cityDistrict;
    static final List<String> DISTRICTS = List.of("central", "northwest", "south", "north_caucasus", "volga", "ural",
            "siberia", "far_east");
    private final int maxProduct;

    World(GenContext ctx) {
        this.ctx = ctx;
        this.period = ctx.period();
        this.cal = ctx.calendar();
        this.scale = ctx.profile().scale();
        this.cat = ctx.get(StoKeys.CATALOG);
        this.prices = ctx.get(StoKeys.PRICES);
        this.promos = ctx.get(StoKeys.PROMOTIONS);
        this.geo = ctx.get(StoKeys.GEO);
        this.market = ctx.get(StoKeys.MARKET);
        this.cs = ctx.get(StoKeys.CUSTOMERS);
        int nc = geo.cities().size();
        cityOffset = new int[nc + 1];
        cityZone = new ZoneId[nc + 1];
        cityFixed = new boolean[nc + 1];
        cityDistrict = new int[nc + 1];
        long a = period.startEpoch() - 400 * DAY, b = period.endEpochExclusive() + 400 * DAY;
        for (Geo.City c : geo.cities()) {
            cityZone[c.id()] = c.zone();
            cityOffset[c.id()] = c.zone().getRules().getOffset(Instant.ofEpochSecond(a)).getTotalSeconds();
            ZoneOffsetTransition tr = c.zone().getRules().nextTransition(Instant.ofEpochSecond(a));
            cityFixed[c.id()] = tr == null || tr.getInstant().getEpochSecond() >= b;
            cityDistrict[c.id()] = DISTRICTS.indexOf(c.district());
        }
        int m = 0;
        for (Catalog.Product p : cat.products()) m = Math.max(m, p.id);
        maxProduct = m;
    }

    int maxProduct() {
        return maxProduct;
    }

    /** Смещение местного времени города от UTC в момент {@code t}, секунд. */
    int offset(int cityId, long t) {
        if (cityFixed[cityId]) return cityOffset[cityId];
        return cityZone[cityId].getRules().getOffset(Instant.ofEpochSecond(t)).getTotalSeconds();
    }

    ZoneId zone(int cityId) {
        return cityZone[cityId];
    }

    /** Местный час (0–23) в городе. */
    int localHour(int cityId, long t) {
        return (int) Math.floorMod(t + offset(cityId, t), DAY) / 3600;
    }

    /** Дата бизнеса (по Москве) — для ставки НДС «на дату заказа». */
    LocalDate businessDate(long t) {
        return LocalDate.ofEpochDay(Math.floorDiv(t + 3 * HOUR, DAY));
    }

    static LocalDate utcDate(long t) {
        return LocalDate.ofEpochDay(Math.floorDiv(t, DAY));
    }

    static long dayStart(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    /**
     * Момент местного времени {@code [fromHour, toHour)} не раньше {@code t}: внутри окна — как есть, раньше окна — его
     * начало плюс случайные минуты, позже — следующее утро.
     */
    long alignLocal(int cityId, long t, int fromHour, int toHour, Rng r) {
        int off = offset(cityId, t);
        long local = t + off;
        long sod = Math.floorMod(local, DAY);
        long base = local - sod;
        long lo = fromHour * HOUR, hi = toHour * HOUR;
        if (sod >= lo && sod < hi) return t;
        long next = sod < lo ? base + lo : base + DAY + lo;
        return next - off + r.nextInt((int) Math.max(1, Math.min(2 * HOUR, hi - lo)));
    }

    /** Перевозка между точками, секунд: в пределах агломерации — 5 ч, дальше — сутки на каждые 650 км (как у складской сети). */
    static long transit(double lat1, double lon1, double lat2, double lon2) {
        double km = GeoMath.haversineKm(lat1, lon1, lat2, lon2);
        return km < 80 ? 5 * HOUR : 5 * HOUR + DAY * (long) StrictMath.ceil(km / 650.0);
    }

    static double km(double lat1, double lon1, double lat2, double lon2) {
        return GeoMath.haversineKm(lat1, lon1, lat2, lon2);
    }
}
