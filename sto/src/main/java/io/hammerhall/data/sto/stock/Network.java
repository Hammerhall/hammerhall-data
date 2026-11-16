package io.hammerhall.data.sto.stock;

import io.hammerhall.data.core.geo.GeoMath;
import io.hammerhall.data.sto.geo.Geo;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Складская сеть для движка остатков: узлы — хаб, РЦ и городские склады (пункты выдачи остатков не держат), дерево
 * снабжения (хаб → РЦ → склады), сроки перевозки между узлами, ближайший склад к точке и доля спроса страны,
 * которую обслуживает поддерево узла (по населению городов, привязанных к ближайшему открытому складу).
 */
public final class Network {
    final int n;
    final int[] whId;
    final int[] nodeOfWh;
    final int[] parent;
    final Geo.Kind[] kind;
    final ZoneId[] zone;
    final String[] district;
    final double[] lat;
    final double[] lon;
    final LocalDate[] opened;
    final LocalDate[] closed;
    final int[][] children;
    final int hub;
    final int[] cityNodes;
    private final double[] ux, uy, uz;
    private final Geo geo;
    private final Map<Long, double[]> sharesByMask = new TreeMap<>();

    Network(Geo geo) {
        this.geo = geo;
        List<Geo.Warehouse> st = new ArrayList<>();
        for (Geo.Warehouse w : geo.warehouses()) if (w.stores()) st.add(w);
        n = st.size();
        if (n > 63) throw new IllegalStateException("узлов больше 63: " + n);
        whId = new int[n];
        parent = new int[n];
        kind = new Geo.Kind[n];
        zone = new ZoneId[n];
        district = new String[n];
        lat = new double[n];
        lon = new double[n];
        opened = new LocalDate[n];
        closed = new LocalDate[n];
        ux = new double[n];
        uy = new double[n];
        uz = new double[n];
        int maxWh = 0;
        for (Geo.Warehouse w : geo.warehouses()) maxWh = Math.max(maxWh, w.id());
        nodeOfWh = new int[maxWh + 1];
        java.util.Arrays.fill(nodeOfWh, -1);
        int h = -1;
        List<Integer> cities = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Geo.Warehouse w = st.get(i);
            whId[i] = w.id();
            nodeOfWh[w.id()] = i;
            kind[i] = w.kind();
            zone[i] = w.city().zone();
            district[i] = w.city().district();
            lat[i] = w.lat();
            lon[i] = w.lon();
            opened[i] = w.openedOn();
            closed[i] = w.closedOn();
            double[] u = unit(w.lat(), w.lon());
            ux[i] = u[0];
            uy[i] = u[1];
            uz[i] = u[2];
            if (w.kind() == Geo.Kind.HUB) h = i;
            if (w.kind() == Geo.Kind.WAREHOUSE) cities.add(i);
        }
        hub = h;
        cityNodes = cities.stream().mapToInt(Integer::intValue).toArray();
        List<List<Integer>> ch = new ArrayList<>();
        for (int i = 0; i < n; i++) ch.add(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            Integer p = st.get(i).parentId();
            parent[i] = p == null ? -1 : nodeOfWh[p];
            if (p != null && parent[i] < 0) throw new IllegalStateException("родитель склада " + whId[i] + " не склад");
            if (parent[i] >= 0) ch.get(parent[i]).add(i);
        }
        children = new int[n][];
        for (int i = 0; i < n; i++) children[i] = ch.get(i).stream().mapToInt(Integer::intValue).toArray();
        List<Integer> bu = new ArrayList<>(cities);
        for (int i = 0; i < n; i++) if (kind[i] == Geo.Kind.DC) bu.add(i);
        bottomUp = bu.stream().mapToInt(Integer::intValue).toArray();
        List<Integer> dcs = new ArrayList<>();
        for (int i = 0; i < n; i++) if (kind[i] == Geo.Kind.DC) dcs.add(i);
        dcNodes = dcs.stream().mapToInt(Integer::intValue).toArray();
    }

    /** РЦ по номеру узла. */
    final int[] dcNodes;

    /** Склады, затем РЦ (без хаба) — порядок расчёта целей снизу вверх. */
    final int[] bottomUp;

    static double[] unit(double latDeg, double lonDeg) {
        double la = StrictMath.toRadians(latDeg), lo = StrictMath.toRadians(lonDeg);
        double c = StrictMath.cos(la);
        return new double[]{c * StrictMath.cos(lo), c * StrictMath.sin(lo), StrictMath.sin(la)};
    }

    public int size() {
        return n;
    }

    public int warehouseId(int node) {
        return whId[node];
    }

    /** Узел склада; −1 — не склад хранения (пункт выдачи) или нет такого. */
    public int node(int warehouseId) {
        return warehouseId > 0 && warehouseId < nodeOfWh.length ? nodeOfWh[warehouseId] : -1;
    }

    public boolean open(int node, LocalDate d) {
        return !d.isBefore(opened[node]) && (closed[node] == null || d.isBefore(closed[node]));
    }

    /**
     * Ближайший к точке открытый городской склад (узел): то же, что {@link Geo#nearestWarehouse}, но по скалярному
     * произведению единичных векторов (монотонно по дуге большого круга) и только среди складов сети.
     */
    public int nearestCity(double latDeg, double lonDeg, LocalDate d) {
        double[] u = unit(latDeg, lonDeg);
        int best = -1;
        double bd = -2;
        for (int i : cityNodes) {
            if (!open(i, d)) continue;
            double dot = u[0] * ux[i] + u[1] * uy[i] + u[2] * uz[i];
            if (dot > bd) {
                bd = dot;
                best = i;
            }
        }
        return best;
    }

    /** Ближайший к точке узел из набора (по маске узлов); −1 — набор пуст. */
    int nearestOf(long mask, double latDeg, double lonDeg) {
        double[] u = unit(latDeg, lonDeg);
        int best = -1;
        double bd = -2;
        for (int i = 0; i < n; i++) {
            if ((mask & (1L << i)) == 0) continue;
            double dot = u[0] * ux[i] + u[1] * uy[i] + u[2] * uz[i];
            if (dot > bd) {
                bd = dot;
                best = i;
            }
        }
        return best;
    }

    double km(int a, int b) {
        return GeoMath.haversineKm(lat[a], lon[a], lat[b], lon[b]);
    }

    /** Перевозка между узлами, секунд: в пределах агломерации — 5 ч, дальше — сутки на каждые 650 км. */
    long transitSeconds(int src, int dst) {
        long t = transit[src * n + dst];
        if (t == 0) {
            double d = km(src, dst);
            t = d < 80 ? 5 * 3600L : 5 * 3600L + 86_400L * (long) StrictMath.ceil(d / 650.0);
            transit[src * n + dst] = t;
        }
        return t;
    }

    private final long[] transit = new long[64 * 64];

    double kmFromCity(int node, Geo.City c) {
        return GeoMath.haversineKm(lat[node], lon[node], c.lat(), c.lon());
    }

    /** Маска открытых городских складов на дату. */
    long openCityMask(LocalDate d) {
        long m = 0;
        for (int i : cityNodes) if (open(i, d)) m |= 1L << i;
        return m;
    }

    /**
     * Доля национального спроса, которую обслуживает поддерево узла: город — свой склад (если в городе их несколько —
     * поровну), иначе ближайший открытый; РЦ — сумма своих складов, хаб — 1.
     */
    double[] shares(LocalDate d) {
        long mask = openCityMask(d);
        double[] s = sharesByMask.get(mask);
        if (s != null) return s;
        s = new double[n];
        double total = 0;
        for (Geo.City c : geo.cities()) {
            total += c.population();
            List<Integer> own = new ArrayList<>();
            for (int i : cityNodes) {
                if ((mask & (1L << i)) == 0) continue;
                if (geo.warehouse(whId[i]).city().id() == c.id()) own.add(i);
            }
            if (!own.isEmpty()) {
                for (int i : own) s[i] += c.population() / (double) own.size();
            } else {
                int best = nearestOf(mask, c.lat(), c.lon());
                s[best] += c.population();
            }
        }
        for (int i : cityNodes) s[i] /= total;
        for (int i : cityNodes) {
            int p = parent[i];
            while (p >= 0 && p != hub) {
                s[p] += s[i];
                p = parent[p];
            }
        }
        s[hub] = 1.0;
        sharesByMask.put(mask, s);
        return s;
    }
}
