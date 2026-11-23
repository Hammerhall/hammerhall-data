package io.hammerhall.data.wld.build;

import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.wld.Exclusions;
import io.hammerhall.data.wld.source.Cldr;
import io.hammerhall.data.wld.source.GeoNames;
import io.hammerhall.data.wld.source.SourceData;
import io.hammerhall.data.wld.source.Wikidata;
import io.hammerhall.data.wld.source.WorldBank;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Набор целиком — функция снимка источников, профиля и списков исключений ({@link #build}): строки пяти таблиц в
 * порядке их ключей. Профиль меняет только города (порог населения); страны, языки, соседство и ряды по годам — одни
 * во всех профилях.
 */
public final class World {
    /** Знаков после точки у координат в выпуске: значение источника сохраняется точно (хвостовые нули — не знаки). */
    public static final int COORD_SCALE = 7;
    /** Знаков после точки у площади (км²) и у доли языка (%, в CLDR — до 4). */
    public static final int AREA_SCALE = 4;
    public static final int PERCENT_SCALE = 4;
    /** Первый год рядов World Bank. */
    public static final int FIRST_YEAR = 1960;

    public record Country(String code, String code2, String name, String nameRu, String localName, String continent,
                          String region, String regionRu, BigDecimal surfaceArea, Long population,
                          BigDecimal lifeExpectancy, BigDecimal gnp, BigDecimal gnpOld, Long capital) {
    }

    public record City(long id, String name, String nameRu, String countryCode, String district, long population,
                       BigDecimal latitude, BigDecimal longitude, String timezone) {
    }

    public record Language(String countryCode, String languageCode, String language, String languageRu,
                           boolean official, BigDecimal percentage) {
    }

    public record Border(String countryCode, String neighbourCode) {
    }

    public record Year(String countryCode, int year, Long population, BigDecimal gnp, BigDecimal gdp,
                       BigDecimal lifeExpectancy) {
    }

    private final Profile profile;
    private final List<Country> countries;
    private final List<City> cities;
    private final List<Language> languages;
    private final List<Border> borders;
    private final List<Year> years;
    private final Map<String, Long> stats;
    private final Integer lastYear;

    private World(Profile profile, List<Country> countries, List<City> cities, List<Language> languages,
                  List<Border> borders, List<Year> years, Map<String, Long> stats, Integer lastYear) {
        this.profile = profile;
        this.countries = List.copyOf(countries);
        this.cities = List.copyOf(cities);
        this.languages = List.copyOf(languages);
        this.borders = List.copyOf(borders);
        this.years = List.copyOf(years);
        this.stats = java.util.Collections.unmodifiableMap(stats);
        this.lastYear = lastYear;
    }

    public Profile profile() {
        return profile;
    }

    public List<Country> countries() {
        return countries;
    }

    public List<City> cities() {
        return cities;
    }

    public List<Language> languages() {
        return languages;
    }

    public List<Border> borders() {
        return borders;
    }

    public List<Year> years() {
        return years;
    }

    /** Счётчики сборки (для журнала и самопроверки). */
    public Map<String, Long> stats() {
        return stats;
    }

    /** Последний год рядов World Bank (любой из четырёх показателей); нет данных — {@code null}. */
    public Integer lastYear() {
        return lastYear;
    }

    /** Порог населения городов этого профиля. */
    public long minPopulation() {
        return Rules.minPopulation(profile);
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * Координата — десятичным числом из записи источника: значение сохраняется точно (запись выпуска — без хвостовых
     * нулей: {@code -34.6131500} → {@code -34.61315}); знаков больше {@link #COORD_SCALE} — ошибка, а не округление.
     */
    static BigDecimal coordinate(String text, long id, String what) {
        BigDecimal b;
        try {
            b = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("город " + id + ": " + what + " — не число: «" + text + "»", e);
        }
        return Rules.exact(b, COORD_SCALE, "город " + id + ": " + what);
    }

    /** Ряд показателя World Bank страны; агрегаты World Bank — не страны: для них рядов нет. */
    private static final class Wb {
        final Map<String, WorldBank.Series> series;
        final Set<String> aggregates;

        Wb(SourceData src) {
            this.series = src.indicators();
            this.aggregates = src.wbAggregates();
        }

        Integer last(String id, String iso3) {
            return aggregates.contains(iso3) ? null : series.get(id).lastYear(iso3);
        }

        BigDecimal value(String id, String iso3, int year) {
            return aggregates.contains(iso3) ? null : series.get(id).value(iso3, year);
        }

        BigDecimal lastValue(String id, String iso3) {
            Integer y = last(id, iso3);
            return y == null ? null : value(id, iso3, y);
        }

        TreeMap<Integer, BigDecimal> all(String id, String iso3) {
            TreeMap<Integer, BigDecimal> m = aggregates.contains(iso3) ? null : series.get(id).values().get(iso3);
            return m == null ? new TreeMap<>() : m;
        }
    }

    /** Собрать набор: правила — {@link Rules}, списки исключений — {@code ex}. */
    public static World build(SourceData src, Profile profile, Exclusions ex) {
        Map<String, Long> stats = new LinkedHashMap<>();
        Map<String, String> en = src.territories().get("en"), ru = src.territories().get("ru");
        Wb wb = new Wb(src);

        // страны: есть в CLDR (ключ — ровно ISO2) и не в списке исключённых
        List<GeoNames.Country> taken = new ArrayList<>();
        Map<String, String> iso3Of = new HashMap<>();
        long notInCldr = 0, excluded = 0;
        for (GeoNames.Country c : src.countries()) {
            if (!en.containsKey(c.iso2())) {
                notInCldr++;
            } else if (ex.country(c.iso2())) {
                excluded++;
            } else {
                taken.add(c);
                if (iso3Of.put(c.iso2(), c.iso3()) != null) throw new IllegalStateException("countryInfo.txt: ISO2 дважды: " + c.iso2());
            }
        }
        stats.put("countryinfo_rows", (long) src.countries().size());
        stats.put("countries_not_in_cldr", notInCldr);
        stats.put("countries_excluded", excluded);

        // кандидаты в столицы — города страны без учёта порога населения: столица всегда среди городов
        Map<String, List<SourceData.Place>> placesOf = new HashMap<>();
        for (SourceData.Place p : src.places()) {
            if (iso3Of.containsKey(p.country()) && isCity(p, ex)) {
                placesOf.computeIfAbsent(p.country(), k -> new ArrayList<>()).add(p);
            }
        }

        List<Country> countries = new ArrayList<>();
        Set<Long> capitals = new HashSet<>();
        long capitalNone = 0;
        for (GeoNames.Country c : taken) {
            String iso2 = c.iso2(), iso3 = c.iso3();
            String regionCode = Rules.region(iso2, src.containment());
            String localName = Rules.localName(iso2, src.territoryInfo().getOrDefault(iso2, List.of()), src.localeNames());

            BigDecimal area = wb.lastValue(WorldBank.AREA, iso3);
            if (area == null && !c.area().isBlank()) {
                BigDecimal ga = parseDecimal(c.area(), "countryInfo.txt " + iso2 + ": Area");
                if (ga.signum() > 0) area = ga; // как у населения: 0 в справочнике — «нет данных»
            }
            area = Rules.exact(area, AREA_SCALE, iso2 + ": площадь");

            Long population = Rules.integral(wb.lastValue(WorldBank.POPULATION, iso3), iso3 + ": " + WorldBank.POPULATION);
            if (population == null && !c.population().isBlank()) {
                long gp = Rules.integral(parseDecimal(c.population(), "countryInfo.txt " + iso2 + ": Population"), iso2 + ": Population");
                if (gp > 0) population = gp;
            }

            BigDecimal life = Rules.lifeExpectancy(wb.lastValue(WorldBank.LIFE, iso3));
            Integer gy = wb.last(WorldBank.GNP, iso3);
            BigDecimal gnp = gy == null ? null : Rules.millions(wb.value(WorldBank.GNP, iso3, gy));
            BigDecimal gnpOld = gy == null ? null : Rules.millions(wb.value(WorldBank.GNP, iso3, gy - 1));

            Long capital = Rules.capital(c.capital(), placesOf.getOrDefault(iso2, List.of()));
            if (capital == null) capitalNone++;
            else capitals.add(capital);

            countries.add(new Country(iso3, iso2, en.get(iso2), nz(ru.get(iso2)), nz(localName), Rules.continent(c.continent()),
                    regionCode == null ? null : nz(en.get(regionCode)), regionCode == null ? null : nz(ru.get(regionCode)),
                    area, population, life, gnp, gnpOld, capital));
        }
        countries.sort((a, b) -> a.code().compareTo(b.code()));
        stats.put("countries", (long) countries.size());
        stats.put("capitals_empty", capitalNone);

        // города
        long min = Rules.minPopulation(profile), skippedCode = 0, skippedRegion = 0, skippedId = 0, below = 0, capitalsBelow = 0;
        List<City> cities = new ArrayList<>();
        for (SourceData.Place p : src.places()) {
            String iso3 = iso3Of.get(p.country());
            if (iso3 == null) continue;
            if (!Rules.cityCode(p.featureCode())) {
                skippedCode++;
                continue;
            }
            if (ex.region(p.country(), p.admin1())) {
                skippedRegion++;
                continue;
            }
            if (ex.city(p.id())) {
                skippedId++;
                continue;
            }
            boolean capital = capitals.contains(p.id());
            if (p.population() < min && !capital) {
                below++;
                continue;
            }
            if (p.population() < min) capitalsBelow++;
            Wikidata.Label ruLabel = src.citiesRu().get(p.id());
            String district = p.admin1().isEmpty() ? null : src.admin1().get(p.country() + "." + p.admin1());
            cities.add(new City(p.id(), p.name(), ruLabel == null ? null : nz(ruLabel.label()), iso3, nz(district),
                    p.population(), coordinate(p.latitude(), p.id(), "широта"), coordinate(p.longitude(), p.id(), "долгота"),
                    nz(p.timezone())));
        }
        stats.put("places", (long) src.places().size());
        stats.put("places_skipped_by_code", skippedCode);
        stats.put("places_skipped_by_region", skippedRegion);
        stats.put("places_skipped_by_id", skippedId);
        stats.put("places_below_threshold", below);
        stats.put("cities", (long) cities.size());
        stats.put("capitals_below_threshold", capitalsBelow);

        // языки: нет английского названия ни одним способом — строку языка не берём
        Map<String, String> langEn = src.languages().get("en"), langRu = src.languages().get("ru"),
                scrEn = src.scripts().get("en"), scrRu = src.scripts().get("ru");
        Map<String, String> wdEn = src.languageLabels().en(), wdRu = src.languageLabels().ru();
        List<Language> languages = new ArrayList<>();
        long unnamed = 0;
        for (GeoNames.Country c : taken) {
            Set<String> seen = new HashSet<>();
            for (Cldr.LanguagePopulation l : src.territoryInfo().getOrDefault(c.iso2(), List.of())) {
                String tag = l.tag();
                if (!seen.add(tag)) throw new IllegalStateException("territoryInfo.json: " + c.iso2() + " — язык " + tag + " дважды");
                if (l.percent() == null) throw new IllegalStateException("territoryInfo.json: " + c.iso2() + "." + l.code() + " — нет _populationPercent");
                String nameEn = nz(Rules.languageName(tag, langEn, scrEn, wdEn));
                if (nameEn == null) {
                    unnamed++;
                    continue;
                }
                languages.add(new Language(c.iso3(), tag, nameEn, nz(Rules.languageName(tag, langRu, scrRu, wdRu)), l.official(),
                        Rules.exact(l.percent(), PERCENT_SCALE, c.iso2() + "." + l.code() + ": доля")));
            }
        }
        stats.put("languages", (long) languages.size());
        stats.put("languages_without_english_name", unnamed);

        // соседство
        Map<String, List<String>> neighbours = new LinkedHashMap<>();
        for (GeoNames.Country c : src.countries()) neighbours.putIfAbsent(c.iso2(), c.neighbours());
        List<Border> borders = new ArrayList<>();
        for (List<String> pair : Rules.borders(neighbours, iso3Of.keySet())) {
            borders.add(new Border(iso3Of.get(pair.get(0)), iso3Of.get(pair.get(1))));
        }

        // ряды по годам: годы, где у страны есть хоть одно из четырёх значений; последний год — по всем странам
        // World Bank (агрегаты — не страны)
        Integer lastYear = null;
        for (String id : WorldBank.YEARLY) {
            for (Map.Entry<String, TreeMap<Integer, BigDecimal>> e : src.indicators().get(id).values().entrySet()) {
                if (src.wbAggregates().contains(e.getKey()) || e.getValue().isEmpty()) continue;
                int y = e.getValue().lastKey();
                if (lastYear == null || y > lastYear) lastYear = y;
            }
        }
        List<Year> years = new ArrayList<>();
        for (GeoNames.Country c : taken) {
            String iso3 = c.iso3();
            TreeSet<Integer> ys = new TreeSet<>();
            for (String id : WorldBank.YEARLY) ys.addAll(wb.all(id, iso3).keySet());
            for (int y : ys) {
                if (y < FIRST_YEAR || lastYear == null || y > lastYear) continue;
                years.add(new Year(iso3, y, Rules.integral(wb.value(WorldBank.POPULATION, iso3, y), iso3 + " " + y + ": население"),
                        Rules.millions(wb.value(WorldBank.GNP, iso3, y)), Rules.millions(wb.value(WorldBank.GDP, iso3, y)),
                        Rules.lifeExpectancy(wb.value(WorldBank.LIFE, iso3, y))));
            }
        }
        return new World(profile, countries, cities, languages, borders, years, stats, lastYear);
    }

    /** Строка годится в города (код и списки исключений; порог — отдельно). */
    static boolean isCity(SourceData.Place p, Exclusions ex) {
        return Rules.cityCode(p.featureCode()) && !ex.region(p.country(), p.admin1()) && !ex.city(p.id());
    }

    private static BigDecimal parseDecimal(String s, String what) {
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(what + " — не число: «" + s + "»", e);
        }
    }
}
