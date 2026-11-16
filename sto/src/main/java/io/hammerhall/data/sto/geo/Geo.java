package io.hammerhall.data.sto.geo;

import io.hammerhall.data.core.geo.GeoMath;
import io.hammerhall.data.core.geo.Polygon;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Модель географии для следующих модулей ({@code ctx.get(StoKeys.GEO)}): города, дерево складов, ячейки,
 * перевозчики, зоны доставки — в том виде, в каком они записаны в таблицы. Неизменяема после модуля {@code geo}.
 */
public final class Geo {

    public record City(int id, String nameRu, String nameEn, String regionRu, String regionEn, String district,
                       double lat, double lon, ZoneId zone, int population, String airportCode, String code) {
    }

    public enum Kind {
        HUB("hub"), DC("dc"), WAREHOUSE("warehouse"), PICKUP_POINT("pickup_point");
        public final String code;

        Kind(String code) {
            this.code = code;
        }
    }

    public record Warehouse(int id, Integer parentId, Kind kind, String code, String nameRu, String nameEn, City city,
                            double lat, double lon, LocalDate openedOn, LocalDate closedOn) {
        /** Работает в этот день (открыт включительно, закрыт — исключая). */
        public boolean openOn(LocalDate d) {
            return !d.isBefore(openedOn) && (closedOn == null || d.isBefore(closedOn));
        }

        public boolean stores() {
            return kind != Kind.PICKUP_POINT;
        }
    }

    public record Cell(int id, int warehouseId, String zone, String code, int rack, int shelf, int cell,
                       int maxWeightKg, int maxVolumeL) {
    }

    public record Carrier(int id, String nameRu, String nameEn, String kind) {
    }

    public record Zone(int id, int warehouseId, int carrierId, int ring, Polygon polygon, int baseDays) {
    }

    private final List<City> cities;
    private final List<Warehouse> warehouses;
    private final List<Cell> cells;
    private final List<Carrier> carriers;
    private final List<Zone> zones;
    private final Map<Integer, List<Warehouse>> pickupByCity = new TreeMap<>();
    private final Map<Integer, List<Cell>> cellsByWarehouse = new TreeMap<>();
    private final Map<Long, List<Zone>> zonesByWhCarrier = new TreeMap<>();

    public Geo(List<City> cities, List<Warehouse> warehouses, List<Cell> cells, List<Carrier> carriers, List<Zone> zones) {
        this.cities = List.copyOf(cities);
        this.warehouses = List.copyOf(warehouses);
        this.cells = List.copyOf(cells);
        this.carriers = List.copyOf(carriers);
        this.zones = List.copyOf(zones);
        for (Warehouse w : warehouses) {
            if (w.kind == Kind.PICKUP_POINT) pickupByCity.computeIfAbsent(w.city.id(), k -> new ArrayList<>()).add(w);
        }
        for (Cell c : cells) cellsByWarehouse.computeIfAbsent(c.warehouseId(), k -> new ArrayList<>()).add(c);
        for (Zone z : zones) zonesByWhCarrier.computeIfAbsent(((long) z.warehouseId << 32) | z.carrierId, k -> new ArrayList<>()).add(z);
    }

    public List<City> cities() {
        return cities;
    }

    /** Город по id (id — с 1, по порядку name_en). */
    public City city(int id) {
        return cities.get(id - 1);
    }

    public List<Warehouse> warehouses() {
        return warehouses;
    }

    public Warehouse warehouse(int id) {
        return warehouses.get(id - 1);
    }

    public List<Warehouse> ofKind(Kind k) {
        List<Warehouse> out = new ArrayList<>();
        for (Warehouse w : warehouses) if (w.kind == k) out.add(w);
        return out;
    }

    /** Пункты выдачи города, работающие в день {@code d}, по id. */
    public List<Warehouse> pickupPointsOpen(int cityId, LocalDate d) {
        List<Warehouse> out = new ArrayList<>();
        for (Warehouse w : pickupByCity.getOrDefault(cityId, List.of())) if (w.openOn(d)) out.add(w);
        return out;
    }

    public List<Cell> cells() {
        return cells;
    }

    public List<Cell> cellsOf(int warehouseId) {
        return cellsByWarehouse.getOrDefault(warehouseId, List.of());
    }

    public List<Carrier> carriers() {
        return carriers;
    }

    public Carrier carrier(int id) {
        return carriers.get(id - 1);
    }

    public List<Zone> zones() {
        return zones;
    }

    public List<Zone> zonesOf(int warehouseId, int carrierId) {
        return Collections.unmodifiableList(zonesByWhCarrier.getOrDefault(((long) warehouseId << 32) | carrierId, List.of()));
    }

    /**
     * Зона склада и перевозчика, содержащая точку (луч по градусам — как посчитает ученик); {@code null} — точка вне
     * зон этого перевозчика (тогда — почта от ближайшего склада, срок {@link #OUT_OF_ZONE_DAYS}).
     */
    public Zone zoneFor(int warehouseId, int carrierId, double lat, double lon) {
        for (Zone z : zonesOf(warehouseId, carrierId)) if (z.polygon().contains(lon, lat)) return z;
        return null;
    }

    public static final int OUT_OF_ZONE_DAYS = 14;

    /** Ближайший к точке работающий городской склад (по дуге большого круга). */
    public Warehouse nearestWarehouse(double lat, double lon, LocalDate d) {
        Warehouse best = null;
        double bd = Double.MAX_VALUE;
        for (Warehouse w : warehouses) {
            if (w.kind != Kind.WAREHOUSE || !w.openOn(d)) continue;
            double dist = GeoMath.haversineKm(lat, lon, w.lat, w.lon);
            if (dist < bd) {
                bd = dist;
                best = w;
            }
        }
        return best;
    }

    public City cityByName(String nameEn) {
        for (City c : cities) if (c.nameEn().equals(nameEn)) return c;
        throw new IllegalArgumentException("нет города " + nameEn);
    }
}
