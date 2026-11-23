package io.hammerhall.data.wld.source;

import io.hammerhall.data.core.io.Json;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unicode CLDR из снимка ({@code cldr/cldr-core/…}, {@code cldr/cldr-localenames-full/…} — извлечено из пакетов npm
 * {@code cldr-core}, {@code cldr-localenames-full}): названия территорий, языков и письменностей по локалям, языки
 * территорий ({@code territoryInfo.json}) и вложенность регионов ООН ({@code territoryContainment.json}).
 */
public final class Cldr {
    private Cldr() {
    }

    public static final String CORE = "cldr/cldr-core/";
    public static final String NAMES = "cldr/cldr-localenames-full/";

    /** Путь к файлу названий локали в снимке: {@code territories}, {@code languages}, {@code scripts}. */
    public static String namesFile(String locale, String kind) {
        return NAMES + "main/" + locale + "/" + kind + ".json";
    }

    /**
     * Язык территории из {@code territoryInfo.json → languagePopulation}.
     *
     * @param code    код CLDR как в файле ({@code zh_Hant})
     * @param percent {@code _populationPercent} числом
     * @param status  {@code _officialStatus} или {@code null}
     */
    public record LanguagePopulation(String code, BigDecimal percent, String status) {
        /** Статус {@code official} или {@code de_facto_official}. */
        public boolean official() {
            return "official".equals(status) || "de_facto_official".equals(status);
        }

        /** Код с «_» → «-» ({@code zh-Hant}). */
        public String tag() {
            return code.replace('_', '-');
        }
    }

    /**
     * Запись {@code territoryContainment.json}: код (ключ как в файле — {@code 015}, {@code QO}, {@code 001-status-grouping}),
     * что содержит, группировка ли ({@code _grouping}), устарела ли ({@code _status: deprecated}).
     */
    public record Container(String code, List<String> contains, boolean grouping, boolean deprecated) {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object v, String where) throws IOException {
        if (!(v instanceof Map<?, ?> m)) throw new IOException(where + ": ждали объект JSON");
        return (Map<String, Object>) m;
    }

    static Object parse(String text, String file) throws IOException {
        try {
            return Json.parse(text);
        } catch (RuntimeException e) {
            throw new IOException(file + ": не JSON — " + e.getMessage(), e);
        }
    }

    /**
     * Названия из файла локали ({@code main/<локаль>/<kind>.json}): {@code main → <локаль> → localeDisplayNames →
     * <kind>}; ключи — как в файле (с {@code -alt-…} тоже).
     */
    public static Map<String, String> names(String text, String file, String kind) throws IOException {
        Map<String, Object> main = object(object(parse(text, file), file).get("main"), file + ": main");
        if (main.size() != 1) throw new IOException(file + ": в main не одна локаль: " + main.keySet());
        Map<String, Object> loc = object(main.values().iterator().next(), file);
        Map<String, Object> names = object(object(loc.get("localeDisplayNames"), file + ": localeDisplayNames").get(kind),
                file + ": " + kind);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : names.entrySet()) {
            if (e.getValue() instanceof String s) out.put(e.getKey(), s);
        }
        return out;
    }

    /** {@code supplemental/territoryInfo.json}: ISO2 → языки в порядке файла (без {@code languagePopulation} — пусто). */
    public static Map<String, List<LanguagePopulation>> territoryInfo(String text, String file) throws IOException {
        Map<String, Object> info = object(object(object(parse(text, file), file).get("supplemental"), file)
                .get("territoryInfo"), file + ": territoryInfo");
        Map<String, List<LanguagePopulation>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> t : info.entrySet()) {
            Map<String, Object> v = object(t.getValue(), file + ": " + t.getKey());
            List<LanguagePopulation> langs = new ArrayList<>();
            Object lp = v.get("languagePopulation");
            if (lp != null) {
                for (Map.Entry<String, Object> l : object(lp, file + ": " + t.getKey()).entrySet()) {
                    Map<String, Object> x = object(l.getValue(), file + ": " + t.getKey() + "." + l.getKey());
                    Object pct = x.get("_populationPercent");
                    BigDecimal p;
                    try {
                        p = pct == null ? null : new BigDecimal(String.valueOf(pct).strip());
                    } catch (NumberFormatException e) {
                        throw new IOException(file + ": " + t.getKey() + "." + l.getKey() + ": _populationPercent — не число: " + pct, e);
                    }
                    Object st = x.get("_officialStatus");
                    langs.add(new LanguagePopulation(l.getKey(), p, st == null ? null : String.valueOf(st)));
                }
            }
            out.put(t.getKey(), List.copyOf(langs));
        }
        return out;
    }

    /** {@code supplemental/territoryContainment.json}: записи в порядке файла. */
    @SuppressWarnings("unchecked")
    public static List<Container> containment(String text, String file) throws IOException {
        Map<String, Object> tc = object(object(object(parse(text, file), file).get("supplemental"), file)
                .get("territoryContainment"), file + ": territoryContainment");
        List<Container> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : tc.entrySet()) {
            Map<String, Object> v = object(e.getValue(), file + ": " + e.getKey());
            List<String> contains = new ArrayList<>();
            if (v.get("_contains") instanceof List<?> l) for (Object o : l) contains.add(String.valueOf(o));
            boolean grouping = truth(v.get("_grouping"));
            boolean deprecated = "deprecated".equals(v.get("_status") == null ? null : String.valueOf(v.get("_status")));
            out.add(new Container(e.getKey(), List.copyOf(contains), grouping, deprecated));
        }
        return out;
    }

    private static boolean truth(Object v) {
        return v instanceof Boolean b ? b : v != null && "true".equals(String.valueOf(v));
    }
}
