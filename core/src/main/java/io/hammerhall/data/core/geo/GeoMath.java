package io.hammerhall.data.core.geo;

import io.hammerhall.data.core.random.Rng;

/**
 * Геометрия на сфере и вокруг точки. Смещения — в локальной равнопромежуточной проекции вокруг центра
 * (1° широты = {@value #KM_PER_DEG} км, 1° долготы = {@value #KM_PER_DEG}·cos φ₀): многоугольник, построенный так,
 * в градусах — аффинный образ многоугольника в километрах, поэтому «точка в многоугольнике» лучом по градусам даёт
 * тот же ответ, что и в километрах. Тригонометрия — {@link StrictMath} (побайтная повторяемость).
 */
public final class GeoMath {
    private GeoMath() {
    }

    public static final double EARTH_RADIUS_KM = 6371.0088;
    public static final double KM_PER_DEG = 111.32;

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = StrictMath.toRadians(lat1), p2 = StrictMath.toRadians(lat2);
        double dp = p2 - p1, dl = StrictMath.toRadians(lon2 - lon1);
        double a = StrictMath.sin(dp / 2) * StrictMath.sin(dp / 2)
                + StrictMath.cos(p1) * StrictMath.cos(p2) * StrictMath.sin(dl / 2) * StrictMath.sin(dl / 2);
        return 2 * EARTH_RADIUS_KM * StrictMath.asin(StrictMath.min(1.0, StrictMath.sqrt(a)));
    }

    /** Точка в {@code northKm} к северу и {@code eastKm} к востоку от центра: {@code [lat, lon]}. */
    public static double[] offset(double lat, double lon, double northKm, double eastKm) {
        double la = lat + northKm / KM_PER_DEG;
        double lo = lon + eastKm / (KM_PER_DEG * StrictMath.cos(StrictMath.toRadians(lat)));
        return new double[]{la, lo};
    }

    /** Точка на расстоянии {@code km} по азимуту {@code bearingDeg} (0 — север, 90 — восток). */
    public static double[] polar(double lat, double lon, double km, double bearingDeg) {
        double b = StrictMath.toRadians(bearingDeg);
        return offset(lat, lon, km * StrictMath.cos(b), km * StrictMath.sin(b));
    }

    /**
     * Случайная точка вокруг центра: расстояние — {@code radiusKm · u^shape} ({@code shape = 0.5} — равномерно по
     * кругу; больше — гуще к центру), азимут — равномерный.
     */
    public static double[] around(Rng r, double lat, double lon, double radiusKm, double shape) {
        double d = radiusKm * StrictMath.pow(r.nextDouble(), shape);
        return polar(lat, lon, d, 360.0 * r.nextDouble());
    }

    /** Округление координаты до 6 знаков (≈ 0,1 м) — так она и пишется в TSV. */
    public static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
