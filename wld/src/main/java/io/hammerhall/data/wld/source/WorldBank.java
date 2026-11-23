package io.hammerhall.data.wld.source;

import io.hammerhall.data.core.io.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Ответы API World Bank ({@code https://api.worldbank.org/v2/…}) в снимке — {@code .jsonl}: одна строка — одна
 * страница ответа {@code [{"page":…},[строки…]]}. Строка показателя — {@code countryiso3code}, {@code date} (год),
 * {@code value} (число или {@code null}). Значение берётся числом как в ответе ({@link BigDecimal} — без потерь).
 */
public final class WorldBank {
    private WorldBank() {
    }

    /** Показатели набора: население, ВНП (GNI), ВВП, продолжительность жизни, площадь. */
    public static final String POPULATION = "SP.POP.TOTL";
    public static final String GNP = "NY.GNP.MKTP.CD";
    public static final String GDP = "NY.GDP.MKTP.CD";
    public static final String LIFE = "SP.DYN.LE00.IN";
    public static final String AREA = "AG.SRF.TOTL.K2";
    public static final List<String> INDICATORS = List.of(POPULATION, GNP, GDP, LIFE, AREA);
    /** Показатели рядов по годам ({@code country_years}). */
    public static final List<String> YEARLY = List.of(POPULATION, GNP, GDP, LIFE);

    /**
     * Ряд показателя: ISO3 → год → значение; только непустые значения.
     *
     * @param conflicts сколько раз одна пара (страна, год) встретилась с другим непустым значением (берётся первое)
     */
    public record Series(String id, Map<String, TreeMap<Integer, BigDecimal>> values, int conflicts) {
        public Series {
            values = Collections.unmodifiableMap(values);
        }

        /** Последний год с данными у страны; нет данных — {@code null}. */
        public Integer lastYear(String iso3) {
            TreeMap<Integer, BigDecimal> m = values.get(iso3);
            return m == null || m.isEmpty() ? null : m.lastKey();
        }

        public BigDecimal value(String iso3, int year) {
            TreeMap<Integer, BigDecimal> m = values.get(iso3);
            return m == null ? null : m.get(year);
        }

        /** Последний год с данными по всем странам; нет данных — {@code null}. */
        public Integer maxYear() {
            Integer max = null;
            for (TreeMap<Integer, BigDecimal> m : values.values()) {
                if (!m.isEmpty() && (max == null || m.lastKey() > max)) max = m.lastKey();
            }
            return max;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> page(String line, String file, int n) throws IOException {
        Object v;
        try {
            v = Json.parse(line);
        } catch (RuntimeException e) {
            throw new IOException(file + ":" + n + ": не JSON — " + e.getMessage(), e);
        }
        if (!(v instanceof List<?> l) || l.isEmpty() || !(l.getFirst() instanceof Map)) {
            throw new IOException(file + ":" + n + ": страница ответа — не [{…}, […]]");
        }
        return (List<Object>) l;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(List<Object> page, String file, int n) throws IOException {
        if (page.size() < 2 || page.get(1) == null) return List.of();
        if (!(page.get(1) instanceof List<?> l)) throw new IOException(file + ":" + n + ": строки ответа — не массив");
        return (List<Map<String, Object>>) l;
    }

    /** Число из ответа: целое, дробное или строка с числом; {@code null} и пустая строка — нет значения. */
    static BigDecimal number(Object v, String where) throws IOException {
        if (v == null) return null;
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        if (v instanceof BigDecimal b) return b;
        if (v instanceof String s) {
            if (s.isBlank()) return null;
            try {
                return new BigDecimal(s.strip());
            } catch (NumberFormatException e) {
                throw new IOException(where + ": значение — не число: " + s, e);
            }
        }
        throw new IOException(where + ": значение — не число: " + v);
    }

    /** Ряд показателя из {@code worldbank/<ID>.jsonl}. */
    public static Series series(String id, BufferedReader r, String file) throws IOException {
        Map<String, TreeMap<Integer, BigDecimal>> values = new HashMap<>();
        int n = 0, conflicts = 0;
        for (String line; (line = r.readLine()) != null; ) {
            n++;
            if (line.isBlank()) continue;
            for (Map<String, Object> row : rows(page(line, file, n), file, n)) {
                Object iso = row.get("countryiso3code"), date = row.get("date");
                if (!(iso instanceof String iso3) || iso3.isEmpty()) continue;
                BigDecimal v = number(row.get("value"), file + ":" + n);
                if (v == null) continue;
                int year;
                try {
                    year = Integer.parseInt(String.valueOf(date));
                } catch (NumberFormatException e) {
                    throw new IOException(file + ":" + n + ": год — не число: " + date, e);
                }
                BigDecimal prev = values.computeIfAbsent(iso3, k -> new TreeMap<>()).putIfAbsent(year, v);
                if (prev != null && prev.compareTo(v) != 0) conflicts++;
            }
        }
        return new Series(id, values, conflicts);
    }

    /** {@code worldbank/countries.jsonl}: коды ({@code id}) записей, у которых {@code region.value == "Aggregates"}. */
    @SuppressWarnings("unchecked")
    public static Set<String> aggregates(BufferedReader r, String file) throws IOException {
        Set<String> out = new HashSet<>();
        int n = 0;
        for (String line; (line = r.readLine()) != null; ) {
            n++;
            if (line.isBlank()) continue;
            for (Map<String, Object> row : rows(page(line, file, n), file, n)) {
                Object region = row.get("region");
                if (region instanceof Map<?, ?> m && "Aggregates".equals(String.valueOf(((Map<String, Object>) m).get("value")).strip())
                        && row.get("id") instanceof String id) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
