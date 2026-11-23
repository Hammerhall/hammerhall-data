package io.hammerhall.data.wld.source;

import io.hammerhall.data.wld.build.Rules;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Всё, что сборка берёт из снимка источников, — прочитано один раз ({@link #read}); дальше снимок не нужен. Значения —
 * как в источниках; правила набора ({@code Rules}, {@code World}) применяются потом.
 *
 * @param snapshot       имя снимка из {@code fetch.json} ({@code wld-source-YYYYMMDD})
 * @param fetchedAt      время снимка из {@code fetch.json} (UTC)
 * @param countries      строки {@code countryInfo.txt} по порядку файла
 * @param admin1         {@code CC.ADM1} → название региона первого уровня
 * @param places         населённые пункты (класс {@code P}) из {@code cities500.txt} и {@code capitals/*.zip}: по
 *                       geonameid — одна строка (из cities500, если она там есть), по возрастанию geonameid
 * @param indicators     ряды показателей World Bank по коду показателя
 * @param wbAggregates   коды агрегатов World Bank ({@code countries.jsonl}: {@code region.value == "Aggregates"})
 * @param territories    названия территорий: {@code en}, {@code ru}
 * @param languages      названия языков: {@code en}, {@code ru}
 * @param scripts        названия письменностей: {@code en}, {@code ru}
 * @param localeNames    названия территорий в локалях, нужных для {@code local_name} (нет локали — нет ключа)
 * @param territoryInfo  языки территорий
 * @param containment    вложенность регионов
 * @param citiesRu       geonameid → русская метка Wikidata (элемент с наименьшим Q)
 * @param languageLabels метки языков Wikidata по коду ISO 639-3
 * @param cldrVersion    версия пакета {@code cldr-core} ({@code package.json})
 * @param cldrLicense    текст {@code cldr/cldr-core/LICENSE}
 * @param cldrLicenseNames текст {@code cldr/cldr-localenames-full/LICENSE}
 * @param stats          счётчики чтения (для журнала и манифеста)
 */
public record SourceData(String snapshot, String fetchedAt, List<GeoNames.Country> countries, Map<String, String> admin1,
                         List<Place> places, Map<String, WorldBank.Series> indicators, Set<String> wbAggregates,
                         Map<String, Map<String, String>> territories, Map<String, Map<String, String>> languages,
                         Map<String, Map<String, String>> scripts, Map<String, Map<String, String>> localeNames,
                         Map<String, List<Cldr.LanguagePopulation>> territoryInfo, List<Cldr.Container> containment,
                         Map<Long, Wikidata.Label> citiesRu, Wikidata.LanguageLabels languageLabels,
                         String cldrVersion, String cldrLicense, String cldrLicenseNames, Map<String, Long> stats) {

    /**
     * Населённый пункт (класс {@code P}) — без альтернативных названий: вместо них {@code namedCapital} — совпало ли
     * одно из названий ({@code name}, {@code asciiname}, {@code alternatenames}) со столицей страны в
     * {@code countryInfo.txt} ({@link Rules#namesMatch}).
     */
    public record Place(long id, String name, String featureCode, String country, String admin1, long population,
                        String latitude, String longitude, String timezone, boolean namedCapital, boolean fromCities500) {
    }

    public static final String COUNTRY_INFO = "geonames/countryInfo.txt";
    public static final String ADMIN1 = "geonames/admin1CodesASCII.txt";
    public static final String CITIES500 = "geonames/cities500.zip";
    public static final String CAPITALS = "geonames/capitals/";
    public static final String WB = "worldbank/";
    public static final String CITIES_RU = "wikidata/cities_ru.csv";
    public static final String LANGUAGES = "wikidata/languages.csv";

    /** Прочитать снимок. */
    public static SourceData read(Path zip) throws IOException {
        Map<String, Long> stats = new LinkedHashMap<>();
        try (Snapshot s = Snapshot.open(zip)) {
            Map<String, Object> fetch = Cldr.object(Cldr.parse(s.text("fetch.json"), "fetch.json"), "fetch.json");
            String snapshot = String.valueOf(fetch.get("snapshot"));
            String fetchedAt = fetch.get("fetched_at") == null ? null : String.valueOf(fetch.get("fetched_at"));

            List<GeoNames.Country> countries;
            try (BufferedReader r = s.reader(COUNTRY_INFO)) {
                countries = GeoNames.countries(r, COUNTRY_INFO);
            }
            Map<String, String> admin1;
            try (BufferedReader r = s.reader(ADMIN1)) {
                admin1 = GeoNames.admin1(r, ADMIN1);
            }
            Map<String, String> capitalOf = new HashMap<>();
            for (GeoNames.Country c : countries) capitalOf.put(c.iso2(), c.capital());

            // cities500 — первым: при повторе geonameid берётся его строка
            Map<Long, Place> places = new HashMap<>();
            long[] read = new long[3];
            int inner = s.inner(CITIES500, n -> n.equals("cities500.txt"), (name, r) -> GeoNames.places(r, CITIES500 + "!" + name, p -> {
                read[0]++;
                if (p.featureClass().equals("P")) places.putIfAbsent(p.id(), place(p, capitalOf, true));
            }));
            if (inner != 1) throw new IOException(CITIES500 + ": нет cities500.txt");
            for (String cap : s.names(CAPITALS)) {
                if (!cap.endsWith(".zip")) continue;
                s.inner(cap, n -> n.matches("[A-Z]{2}\\.txt"), (name, r) -> GeoNames.places(r, cap + "!" + name, p -> {
                    read[1]++;
                    if (!p.featureClass().equals("P")) return;
                    if (places.putIfAbsent(p.id(), place(p, capitalOf, false)) != null) read[2]++;
                }));
            }
            List<Place> sorted = new ArrayList<>(places.values());
            sorted.sort((a, b) -> Long.compare(a.id(), b.id()));
            stats.put("cities500_rows", read[0]);
            stats.put("capitals_rows", read[1]);
            stats.put("capitals_rows_already_in_cities500", read[2]);

            Map<String, WorldBank.Series> indicators = new LinkedHashMap<>();
            for (String id : WorldBank.INDICATORS) {
                String f = WB + id + ".jsonl";
                try (BufferedReader r = s.reader(f)) {
                    indicators.put(id, WorldBank.series(id, r, f));
                }
            }
            Set<String> aggregates;
            try (BufferedReader r = s.reader(WB + "countries.jsonl")) {
                aggregates = WorldBank.aggregates(r, WB + "countries.jsonl");
            }

            Map<String, Map<String, String>> territories = new LinkedHashMap<>(), languages = new LinkedHashMap<>(),
                    scripts = new LinkedHashMap<>();
            for (String loc : List.of("en", "ru")) {
                territories.put(loc, names(s, loc, "territories"));
                languages.put(loc, names(s, loc, "languages"));
                scripts.put(loc, names(s, loc, "scripts"));
            }
            String ti = Cldr.CORE + "supplemental/territoryInfo.json", tc = Cldr.CORE + "supplemental/territoryContainment.json";
            Map<String, List<Cldr.LanguagePopulation>> territoryInfo = Cldr.territoryInfo(s.text(ti), ti);
            List<Cldr.Container> containment = Cldr.containment(s.text(tc), tc);
            // локали для local_name: официальные языки территорий — код с «-» и его базовый язык
            Set<String> wanted = new LinkedHashSet<>();
            for (List<Cldr.LanguagePopulation> langs : territoryInfo.values()) {
                for (Cldr.LanguagePopulation l : langs) {
                    if (!l.official()) continue;
                    wanted.add(l.tag());
                    wanted.add(Rules.baseLanguage(l.tag()));
                }
            }
            Map<String, Map<String, String>> localeNames = new LinkedHashMap<>();
            for (String loc : wanted) {
                String f = Cldr.namesFile(loc, "territories");
                if (s.has(f)) localeNames.put(loc, Cldr.names(s.text(f), f, "territories"));
            }

            long[] skipped = new long[1];
            Map<Long, Wikidata.Label> citiesRu;
            try (BufferedReader r = s.reader(CITIES_RU)) {
                citiesRu = Wikidata.citiesRu(r, CITIES_RU, skipped);
            }
            stats.put("cities_ru_rows_skipped", skipped[0]);
            Wikidata.LanguageLabels languageLabels;
            try (BufferedReader r = s.reader(LANGUAGES)) {
                languageLabels = Wikidata.languages(r, LANGUAGES);
            }
            Object version = Cldr.object(Cldr.parse(s.text(Cldr.CORE + "package.json"), Cldr.CORE + "package.json"),
                    Cldr.CORE + "package.json").get("version");
            String license = s.text(Cldr.CORE + "LICENSE");
            String licenseNames = s.has(Cldr.NAMES + "LICENSE") ? s.text(Cldr.NAMES + "LICENSE") : license;
            return new SourceData(snapshot, fetchedAt, List.copyOf(countries), Collections.unmodifiableMap(admin1),
                    List.copyOf(sorted), Collections.unmodifiableMap(indicators), Set.copyOf(aggregates), territories,
                    languages, scripts, localeNames, territoryInfo, containment, citiesRu, languageLabels, version == null ? null : String.valueOf(version), license, licenseNames, stats);
        }
    }

    private static Map<String, String> names(Snapshot s, String locale, String kind) throws IOException {
        String f = Cldr.namesFile(locale, kind);
        return Cldr.names(s.text(f), f, kind);
    }

    private static Place place(GeoNames.Place p, Map<String, String> capitalOf, boolean fromCities500) {
        String capital = capitalOf.get(p.country());
        boolean named = capital != null && Rules.namesMatch(capital, p.name(), p.asciiName(), p.alternateNames());
        return new Place(p.id(), p.name(), p.featureCode(), p.country(), p.admin1(), p.population(), p.latitude(),
                p.longitude(), p.timezone(), named, fromCities500);
    }

    /** Для манифеста: время снимка и его имя — из {@code fetch.json}. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("snapshot", snapshot);
        m.put("fetched_at", fetchedAt);
        return m;
    }
}
