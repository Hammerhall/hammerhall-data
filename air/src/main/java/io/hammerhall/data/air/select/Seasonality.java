package io.hammerhall.data.air.select;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Модель сезонности {@code air-data-v1} — та же, что у прежней сборки на Python и DuckDB (таблицы перенесены один в
 * один): бронь остаётся целиком, если {@code u < доля}, где
 * <ul>
 *   <li>{@code u} — первые 4 байта MD5(номер брони) как целое без знака, little-endian, / 2<sup>32</sup> (в DuckDB —
 *       {@code md5_number(book_ref) % 2^32});</li>
 *   <li>доля — 1.0, если местная дата первого вылета брони попадает в праздничное окно её региона или страны, иначе
 *       {@code месяц[регион][месяц] × день_недели[ISO-день]}; страна — страна аэропорта первого вылета, регион — по
 *       {@link #REGION}; страны нет в {@link #REGION} — бронь не остаётся (в сборке это внутреннее соединение).</li>
 * </ul>
 * Числа модели — строками, как в манифесте выпуска ({@code 0.84}, {@code 1.0}); в счёте — те же {@code double}, что
 * у Python и DuckDB.
 */
public final class Seasonality {
    private Seasonality() {
    }

    /** Страна → регион (порядок — как в сборке и манифесте). */
    public static final Map<String, String> REGION;

    static {
        Map<String, String> r = new LinkedHashMap<>();
        String[][] pairs = {
                {"Germany", "EU"}, {"Italy", "EU"}, {"France", "EU"}, {"United Kingdom", "EU"}, {"Sweden", "EU"},
                {"Finland", "EU"}, {"Czech Republic", "EU"}, {"Austria", "EU"},
                {"United States", "NA"}, {"Canada", "NA"}, {"China", "CN"}, {"Japan", "JP"},
                {"India", "IN"}, {"Nepal", "IN"}, {"Russia", "RU"}, {"Chile", "SH"}, {"New Zealand", "SH"}};
        for (String[] p : pairs) r.put(p[0], p[1]);
        REGION = Collections.unmodifiableMap(r);
    }

    //                                  янв     фев     мар     апр     май     июн     июл     авг     сен     окт     ноя     дек
    private static final String[][] MONTH_TEXT = {
            {"EU", "0.84", "0.84", "0.88", "0.92", "0.94", "0.97", "1.0", "1.0", "0.95", "0.92", "0.85", "0.92"},
            {"NA", "0.84", "0.85", "0.95", "0.92", "0.95", "1.0", "1.0", "0.97", "0.88", "0.92", "0.9", "0.94"},
            {"CN", "0.92", "0.95", "0.86", "0.9", "0.93", "0.9", "1.0", "1.0", "0.88", "0.93", "0.86", "0.88"},
            {"JP", "0.9", "0.85", "0.95", "0.92", "0.92", "0.85", "0.95", "1.0", "0.9", "0.92", "0.9", "0.93"},
            {"IN", "0.93", "0.9", "0.9", "0.95", "1.0", "0.98", "0.85", "0.84", "0.86", "0.95", "0.97", "0.97"},
            {"RU", "0.9", "0.86", "0.85", "0.88", "0.94", "0.98", "1.0", "1.0", "0.93", "0.88", "0.84", "0.92"},
            {"SH", "1.0", "1.0", "0.92", "0.88", "0.84", "0.84", "0.95", "0.86", "0.9", "0.9", "0.93", "1.0"}};

    /** ISO-день недели местного вылета: 1 — понедельник … 7 — воскресенье. */
    private static final String[] WEEKDAY_TEXT = {"0.95", "0.86", "0.88", "0.94", "0.98", "0.9", "1.0"};

    /** Праздники и пики: кто (регион или страна), с, по (включительно, местная дата вылета), что. */
    public record Holiday(String who, LocalDate from, LocalDate to, String name) {
    }

    public static final List<Holiday> HOLIDAYS = List.of(
            h("CN", "2025-09-30", "2025-10-07", "Золотая неделя"),
            h("CN", "2026-02-02", "2026-03-13", "Чуньюнь (Новый год 17.02.2026)"),
            h("CN", "2026-04-30", "2026-05-05", "праздник труда"),
            h("CN", "2026-09-30", "2026-10-07", "Золотая неделя"),
            h("CN", "2027-01-22", "2027-03-02", "Чуньюнь (Новый год 06.02.2027)"),
            h("CN", "2027-04-30", "2027-05-05", "праздник труда"),
            h("CN", "2027-09-30", "2027-10-07", "Золотая неделя"),
            h("JP", "2025-12-27", "2026-01-04", "новогодние"), h("JP", "2026-04-29", "2026-05-06", "Золотая неделя"),
            h("JP", "2026-08-11", "2026-08-17", "Обон"), h("JP", "2026-12-27", "2027-01-04", "новогодние"),
            h("JP", "2027-04-29", "2027-05-05", "Золотая неделя"), h("JP", "2027-08-11", "2027-08-17", "Обон"),
            h("IN", "2025-10-17", "2025-10-26", "Дивали (20.10.2025)"), h("IN", "2025-12-20", "2026-01-03", "новогодние"),
            h("IN", "2026-11-05", "2026-11-14", "Дивали (08.11.2026)"), h("IN", "2026-12-20", "2027-01-03", "новогодние"),
            h("IN", "2027-10-26", "2027-11-04", "Дивали (29.10.2027)"),
            h("United States", "2025-11-22", "2025-12-01", "День благодарения (27.11.2025)"),
            h("United States", "2026-11-21", "2026-11-30", "День благодарения (26.11.2026)"),
            h("United States", "2026-07-01", "2026-07-06", "День независимости"),
            h("United States", "2027-07-01", "2027-07-06", "День независимости"),
            h("Canada", "2025-10-10", "2025-10-13", "День благодарения (13.10.2025)"),
            h("Canada", "2026-10-09", "2026-10-12", "День благодарения (12.10.2026)"),
            h("Canada", "2027-10-08", "2027-10-11", "День благодарения (11.10.2027)"),
            h("NA", "2025-12-18", "2026-01-04", "Рождество"), h("NA", "2026-03-07", "2026-03-29", "весенние каникулы"),
            h("NA", "2026-12-18", "2027-01-04", "Рождество"), h("NA", "2027-03-06", "2027-03-28", "весенние каникулы"),
            h("EU", "2025-12-19", "2026-01-04", "Рождество"), h("EU", "2026-03-28", "2026-04-12", "Пасха (05.04.2026)"),
            h("EU", "2026-12-19", "2027-01-04", "Рождество"), h("EU", "2027-03-20", "2027-04-04", "Пасха (28.03.2027)"),
            h("RU", "2025-12-28", "2026-01-11", "новогодние каникулы"), h("RU", "2026-05-01", "2026-05-11", "майские"),
            h("RU", "2026-12-28", "2027-01-10", "новогодние каникулы"), h("RU", "2027-05-01", "2027-05-10", "майские"),
            h("Chile", "2026-09-16", "2026-09-21", "Фьестас Патриас"), h("Chile", "2027-09-16", "2027-09-21", "Фьестас Патриас"),
            h("SH", "2025-12-15", "2026-01-31", "южное лето, праздники"), h("SH", "2026-12-15", "2027-01-31", "южное лето, праздники"));

    private static Holiday h(String who, String from, String to, String name) {
        return new Holiday(who, LocalDate.parse(from), LocalDate.parse(to), name);
    }

    private static final Map<String, double[]> MONTH = new LinkedHashMap<>();
    private static final double[] WEEKDAY = new double[8];

    static {
        for (String[] row : MONTH_TEXT) {
            double[] v = new double[13];
            for (int m = 1; m <= 12; m++) v[m] = Double.parseDouble(row[m]);
            MONTH.put(row[0], v);
        }
        for (int d = 1; d <= 7; d++) WEEKDAY[d] = Double.parseDouble(WEEKDAY_TEXT[d - 1]);
    }

    /** Регион страны или {@code null} — страны нет в модели (бронь не остаётся). */
    public static String region(String country) {
        return country == null ? null : REGION.get(country);
    }

    /** Праздник ли местная дата для региона или страны. */
    public static boolean holiday(String region, String country, LocalDate d) {
        for (Holiday h : HOLIDAYS) {
            if ((h.who().equals(region) || h.who().equals(country)) && !d.isBefore(h.from()) && !d.isAfter(h.to())) return true;
        }
        return false;
    }

    /** Доля для страны и местной даты первого вылета; страны нет в модели — {@code NaN}. */
    public static double share(String country, LocalDate d) {
        String region = region(country);
        if (region == null) return Double.NaN;
        if (holiday(region, country, d)) return 1.0;
        return MONTH.get(region)[d.getMonthValue()] * WEEKDAY[d.getDayOfWeek().getValue()];
    }

    /** {@code (md5_number(book_ref) % 2^32)::double / 2^32}: первые 4 байта MD5 без знака, little-endian. */
    public static double unit(String bookRef) {
        byte[] d;
        try {
            d = MessageDigest.getInstance("MD5").digest(bookRef.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long u = (d[0] & 0xffL) | (d[1] & 0xffL) << 8 | (d[2] & 0xffL) << 16 | (d[3] & 0xffL) << 24;
        return (double) u / 4294967296.0;
    }

    /** Остаётся ли бронь: {@code u < доля}; страны нет в модели — нет. */
    public static boolean keep(String bookRef, String country, LocalDate localDate) {
        double share = share(country, localDate);
        return !Double.isNaN(share) && unit(bookRef) < share;
    }

    /** Поле {@code selection} манифеста — описание модели, как у выпуска {@code air-data-v1}. */
    public static Map<String, Object> manifest() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unit", "booking");
        m.put("key", "md5_number(book_ref) % 2^32 / 2^32 < share");
        m.put("share", "holiday ? 1.0 : month[region][month] * weekday[isodow]");
        m.put("by", "country, local date and weekday of the first departure of the booking");
        m.put("region", new LinkedHashMap<>(REGION));
        Map<String, Object> month = new LinkedHashMap<>();
        for (String[] row : MONTH_TEXT) {
            List<Object> v = new ArrayList<>();
            for (int i = 1; i <= 12; i++) v.add(new BigDecimal(row[i]));
            month.put(row[0], v);
        }
        m.put("month", month);
        Map<String, Object> wd = new LinkedHashMap<>();
        for (int d = 1; d <= 7; d++) wd.put(Integer.toString(d), new BigDecimal(WEEKDAY_TEXT[d - 1]));
        m.put("weekday", wd);
        List<Object> hs = new ArrayList<>();
        for (Holiday h : HOLIDAYS) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("who", h.who());
            e.put("from", h.from().toString());
            e.put("to", h.to().toString());
            e.put("name", h.name());
            hs.add(e);
        }
        m.put("holidays", hs);
        return m;
    }
}
