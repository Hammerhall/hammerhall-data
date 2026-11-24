package io.hammerhall.data.mov.build;

import io.hammerhall.data.mov.Exclusions;
import io.hammerhall.data.mov.source.SourceData;
import io.hammerhall.data.mov.source.WorldCities;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Места ({@code places}): значения P915 (место съёмок), P840 (место действия), P19 (место рождения) и P159
 * (штаб-квартира) — название, вид (страна или место), страна, координаты и город набора «Мир»: по GeoNames ID, иначе
 * ближайший город той же страны не дальше {@value #MAX_KM} км. Места без названия и места из исключений
 * ({@link Excluded}) в набор не берутся — как будто их нет. Чистые функции от снимка и городов; {@link Resolver} — кэш
 * по месту.
 */
public final class Places {
    private Places() {
    }

    /** Вид места: сама страна (у элемента есть ISO3) или место в стране. */
    public static final String COUNTRY = "country", PLACE = "place";
    /** Ближайший город — не дальше стольких километров. */
    public static final double MAX_KM = 30.0;
    /** Знаков после точки у координат места. */
    public static final int COORD_SCALE = 7;

    /** Откуда город места. */
    public enum CityPath {
        /** GeoNames ID места — город «Мира». */
        GEONAMES,
        /** Ближайший город той же страны не дальше {@link #MAX_KM} км. */
        NEAREST,
        /** Города нет: место — не город «Мира», рядом нет (или нет координат, страны). */
        NONE,
        /** Место — сама страна: города нет. */
        COUNTRY
    }

    /** Почему места нет в наборе (как будто у него нет названия): по пунктам списка исключений. */
    public enum Excluded {
        /** Место — страна с кодом из списка исключений ({@link Exclusions}). */
        EXCLUDED_COUNTRY,
        /** Место в стране (P17) с кодом из списка исключений. */
        IN_EXCLUDED_COUNTRY,
        /** Номер элемента — из списка мест ({@link Exclusions#places()}). */
        LISTED
    }

    /**
     * Разобранное место: взято — {@code place} и путь города ({@code excluded} — {@code null}); исключено — только
     * {@code excluded}.
     */
    public record Resolved(Cinema.Place place, CityPath path, Excluded excluded) {
        /** Место берётся в набор. */
        public boolean taken() {
            return place != null;
        }
    }

    /** Расстояние до города: половина — к чётному до 0,1 км по точной десятичной записи числа {@code double}. */
    public static BigDecimal km(double d) {
        return new BigDecimal(d).setScale(1, RoundingMode.HALF_EVEN);
    }

    /** Координата места для выпуска: половина — к чётному до {@value #COORD_SCALE} знаков. */
    public static BigDecimal coord(BigDecimal v) {
        return v == null ? null : v.setScale(COORD_SCALE, RoundingMode.HALF_EVEN);
    }

    /** У элемента есть ISO 3166-1 alpha-3 ({@code countries.csv}) — он сам страна. */
    public static boolean isCountry(SourceData.Codes codes) {
        if (codes == null) return false;
        for (String c : codes.second()) if (c.matches("[A-Z]{3}")) return true;
        return false;
    }

    /** Коды страны — из списка исключений (по ISO2 или ISO3, как {@link Rules#countryCode}). */
    public static boolean excludedCountry(SourceData.Codes codes, Exclusions ex) {
        if (codes == null) return false;
        for (String c : codes.first()) if (ex.country2(c)) return true;
        for (String c : codes.second()) if (ex.country3(c)) return true;
        return false;
    }

    /**
     * Место по правилам. Нет в {@code places} или нет названия (метка {@code en}, иначе {@code mul}) — {@code null}.
     * Исключено — (а) страна с кодом из списка, (б) место, чья страна P17 — из списка, (в) номер элемента — из списка
     * мест; проверяются по этому порядку — {@link Resolved#excluded()}. Иначе: вид — {@link #COUNTRY}, если у элемента
     * есть ISO3, иначе {@link #PLACE}; страна — свой код у страны, ISO3 страны P17 у места. Город (только у места):
     * GeoNames ID — город «Мира» → он, 0 км; иначе при координатах и стране — ближайший город той же страны не дальше
     * {@link #MAX_KM} км (при равенстве — меньший id), расстояние — {@link #km}; иначе пусто. Координаты для
     * расстояния — как в снимке (десятичная запись → ближайшее {@code double}), в выпуск — {@link #coord}.
     */
    public static Resolved resolve(int id, SourceData.Place p, Map<Integer, SourceData.Codes> countries, Exclusions ex,
                                   WorldCities cities) {
        if (p == null) return null;
        String name = p.labelEn() != null ? p.labelEn() : p.labelMul();
        if (name == null) return null;
        SourceData.Codes own = countries.get(id);
        boolean country = isCountry(own);
        SourceData.Codes of = country || p.country() == 0 ? null : countries.get(p.country());
        if (country && excludedCountry(own, ex)) return new Resolved(null, null, Excluded.EXCLUDED_COUNTRY);
        if (!country && excludedCountry(of, ex)) return new Resolved(null, null, Excluded.IN_EXCLUDED_COUNTRY);
        if (ex.place(id)) return new Resolved(null, null, Excluded.LISTED);
        String code = Rules.countryCode(country ? own : of, ex);
        Integer city = null;
        BigDecimal cityKm = null;
        CityPath path;
        if (country) {
            path = CityPath.COUNTRY;
        } else if (p.geonames() != 0 && cities.has(p.geonames())) {
            city = p.geonames();
            cityKm = BigDecimal.ZERO.setScale(1);
            path = CityPath.GEONAMES;
        } else {
            WorldCities.Nearest n = p.hasCoords() && code != null
                    ? cities.nearest(code, Double.parseDouble(p.lat().toString()), Double.parseDouble(p.lon().toString()), MAX_KM)
                    : null;
            if (n != null) {
                city = n.id();
                cityKm = km(n.km());
                path = CityPath.NEAREST;
            } else {
                path = CityPath.NONE;
            }
        }
        return new Resolved(new Cinema.Place(id, name, p.labelRu(), country ? COUNTRY : PLACE, coord(p.lat()), coord(p.lon()), code,
                city, cityKm), path, null);
    }

    /** Места снимка с кэшем: каждое место разбирается один раз. */
    public static final class Resolver {
        private final SourceData src;
        private final WorldCities cities;
        private final Exclusions ex;
        private final Map<Integer, Resolved> cache = new HashMap<>();

        public Resolver(SourceData src, WorldCities cities, Exclusions ex) {
            this.src = src;
            this.cities = cities;
            this.ex = ex;
        }

        /** Место по номеру элемента; нет в {@code places} или нет названия — {@code null}. */
        public Resolved get(int id) {
            if (cache.containsKey(id)) return cache.get(id);
            Resolved r = resolve(id, src.places().get(id), src.countries(), ex, cities);
            cache.put(id, r);
            return r;
        }

        /** Место, если оно берётся в набор (есть название, не исключено); иначе {@code null}. */
        public Cinema.Place taken(int id) {
            Resolved r = get(id);
            return r == null ? null : r.place();
        }
    }
}
