package io.hammerhall.data.core.geo;

import io.hammerhall.data.core.io.RowFormat;

/**
 * Простой многоугольник в градусах (долгота, широта), кольцо замкнуто (последняя точка = первой), обход — против
 * часовой стрелки. В TSV — WKT {@code POLYGON((lon lat, lon lat, …))}, координаты — 6 знаков.
 */
public final class Polygon {
    private final double[] lon;
    private final double[] lat;

    /** Вершины без повтора первой; замыкание добавляется само. Координаты округляются до 6 знаков. */
    public Polygon(double[] lon, double[] lat) {
        if (lon.length != lat.length || lon.length < 3) throw new IllegalArgumentException("вершин " + lon.length);
        int n = lon.length;
        this.lon = new double[n + 1];
        this.lat = new double[n + 1];
        for (int i = 0; i < n; i++) {
            this.lon[i] = GeoMath.round6(lon[i]);
            this.lat[i] = GeoMath.round6(lat[i]);
        }
        this.lon[n] = this.lon[0];
        this.lat[n] = this.lat[0];
    }

    public int vertices() {
        return lon.length;
    }

    public double lon(int i) {
        return lon[i];
    }

    public double lat(int i) {
        return lat[i];
    }

    /** Удвоенная знаковая площадь в градусах²: > 0 — обход против часовой стрелки. */
    public double signedArea2() {
        double s = 0;
        for (int i = 0; i + 1 < lon.length; i++) s += lon[i] * lat[i + 1] - lon[i + 1] * lat[i];
        return s;
    }

    /** Точка внутри (луч вдоль долготы; точки ровно на границе — как получится, но одинаково везде). */
    public boolean contains(double x, double y) {
        boolean in = false;
        for (int i = 0, j = lon.length - 2; i < lon.length - 1; j = i++) {
            double xi = lon[i], yi = lat[i], xj = lon[j], yj = lat[j];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    public String wkt() {
        StringBuilder sb = new StringBuilder(lon.length * 24 + 12).append("POLYGON((");
        for (int i = 0; i < lon.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(RowFormat.fixed(lon[i], 6)).append(' ').append(RowFormat.fixed(lat[i], 6));
        }
        return sb.append("))").toString();
    }

    /** Разбор WKT того же вида (для проверок выпуска и потребителей). */
    public static Polygon parseWkt(String wkt) {
        String body = wkt.substring(wkt.indexOf("((") + 2, wkt.lastIndexOf("))"));
        String[] pts = body.split(", ");
        int n = pts.length - 1;
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            String[] p = pts[i].split(" ");
            x[i] = Double.parseDouble(p[0]);
            y[i] = Double.parseDouble(p[1]);
        }
        return new Polygon(x, y);
    }
}
