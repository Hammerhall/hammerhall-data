package io.hammerhall.data.wld;

import io.hammerhall.data.core.gen.Cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Крошечный снимок источников в формате {@code wld-source-*.zip} — вместо настоящего: тесты набора идут и без него.
 * Всё выдумано (страны {@code QM}, {@code QN}, {@code QP}, {@code QR}, {@code QT} из кодов для частного употребления,
 * города «Alpha City», «Betaville» …), кроме кодов из списков исключений — у них выдуманные названия. В снимке — все
 * случаи правил: три способа найти столицу (и столица из {@code capitals/QP.zip}, и строка, которая есть и там, и в
 * cities500), пустая столица, столица не среди городов, порог каждого профиля ровно на границе, коды {@code PPLX},
 * {@code PPLH}, {@code PPLCH}, строка не класса {@code P}, страна не из CLDR, исключённая страна, ВНП с половиной на
 * границе округления, площадь и население из GeoNames, агрегат World Bank, языки с письменностью, с названием только из
 * Wikidata и без английского названия, регион по буквенному коду, соседство с одной стороны. Из {@code wikidata/} — только
 * то, что сборка читает ({@code cities_ru.csv}, {@code languages.csv}): прочие файлы настоящего снимка ей не нужны.
 *
 * <p>{@link #entries()} — записи снимка (путь → байты): тесты правят копию и пишут свой снимок ({@link #write}).
 */
public final class TinySnapshot {
    private TinySnapshot() {
    }

    private static Path file;
    private static final java.util.concurrent.atomic.AtomicInteger COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    /** Вход набора для тестов: настоящий снимок, если он есть по пути входа; иначе — этот ({@code -Dwld.source}). */
    static synchronized void useIfNoSnapshot() throws IOException {
        Path real = Cli.inputPath(new WldDataset(), WldDataset.SOURCE, Map.of());
        if (Files.isRegularFile(real) && !real.equals(file)) return;
        System.setProperty("wld.source", path().toString());
    }

    /** Путь к крошечному снимку (пишется один раз на JVM, во временную папку). */
    static synchronized Path path() throws IOException {
        if (file == null) {
            Path dir = Files.createTempDirectory("wld-tiny-");
            Path f = dir.resolve(WldDataset.SNAPSHOT + ".zip");
            write(f, entries());
            f.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            file = f;
        }
        return file;
    }

    /** Снимок с правками: записи по умолчанию, поверх — {@code changes} ({@code null} — убрать запись). */
    static Path with(Path dir, Map<String, byte[]> changes) throws IOException {
        Map<String, byte[]> e = entries();
        for (Map.Entry<String, byte[]> c : changes.entrySet()) {
            if (c.getValue() == null) e.remove(c.getKey());
            else e.put(c.getKey(), c.getValue());
        }
        Path f = dir.resolve("snapshot-" + COUNTER.incrementAndGet() + ".zip");
        write(f, e);
        return f;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** zip как в контракте снимка: записи по имени, время — 1980-01-01, без общей папки. */
    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        write(bo, entries);
        return bo.toByteArray();
    }

    static void write(Path f, Map<String, byte[]> entries) throws IOException {
        try (OutputStream o = Files.newOutputStream(f)) {
            write(o, entries);
        }
    }

    private static void write(OutputStream o, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(o, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : new TreeMap<>(entries).entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setLastModifiedTime(FileTime.fromMillis(315_532_800_000L));
                z.putNextEntry(ze);
                z.write(e.getValue());
                z.closeEntry();
            }
        }
    }

    // ---------------------------------------------------------------- GeoNames

    static final String COUNTRY_HEADER = "#ISO\tISO3\tISO-Numeric\tfips\tCountry\tCapital\tArea(in sq km)\tPopulation\t"
            + "Continent\ttld\tCurrencyCode\tCurrencyName\tPhone\tPostal Code Format\tPostal Code Regex\tLanguages\t"
            + "geonameid\tneighbours\tEquivalentFipsCode";

    static String country(String iso2, String iso3, String name, String capital, String area, String population,
                          String continent, String neighbours) {
        return String.join("\t", iso2, iso3, "900", iso2, name, capital, area, population, continent, "." + iso2.toLowerCase(),
                "XTS", "Test", "1", "", "", "en", "1", neighbours, "");
    }

    /** Строка таблицы geoname (19 полей). */
    static String place(long id, String name, String alternates, String lat, String lon, String cls, String code,
                        String cc, String admin1, long population, String tz) {
        return String.join("\t", Long.toString(id), name, name, alternates, lat, lon, cls, code, cc, "", admin1, "", "",
                "", Long.toString(population), "", "10", tz, "2026-01-01");
    }

    static List<String> countryInfo() {
        return List.of(
                "# GeoNames countryInfo (крошечный, для тестов)",
                COUNTRY_HEADER,
                country("QM", "QMA", "Qumland", "Alpha City", "41000", "4000000", "EU", "QN,QP,EH"),
                country("QN", "QNA", "Nordia", "Beta", "82880", "3000000", "AS", "QS"),
                country("QP", "QPA", "Pelagia", "Gamma", "468", "0", "OC", ""),
                country("QR", "QRA", "Rivia", "", "0.44", "800", "AF", "QM"),
                country("QS", "QSA", "Oldland", "Delta", "100", "100", "EU", "QN"),
                country("QT", "QTA", "Tundra", "Epsilon", "1.4E7", "0", "AN", ""),
                country("EH", "ESH", "Zetaland", "Zeta", "266000", "500000", "AF", "QM"));
    }

    static List<String> cities500() {
        String tz = "Europe/Berlin";
        return List.of(
                place(1001, "Alpha City", "Alpha,Alpha City,Альфа", "48.1", "11.5", "P", "PPLC", "QM", "01", 2_500_000, tz),
                place(1002, "Alphaburg", "", "47.25", "-0.5", "P", "PPLA", "QM", "02", 250_000, tz),
                place(1003, "Midtown", "", "46.12345", "10", "P", "PPL", "QM", "02", 50_000, tz),
                place(1004, "Smallville", "", "45.5", "9.25", "P", "PPL", "QM", "09", 5_000, tz),
                place(1005, "Tinyham", "", "45.4", "9.2", "P", "PPL", "QM", "02", 999, tz),
                place(1006, "Oldquarter", "", "48.11", "11.51", "P", "PPLX", "QM", "01", 300_000, tz),
                place(1007, "Ruinton", "", "48.2", "11.6", "P", "PPLH", "QM", "01", 200_000, tz),
                place(1008, "Exactly", "", "44.1", "8.1", "P", "PPL", "QM", "00", 1_000, ""),
                place(1009, "Fifteen", "", "44.2", "8.2", "P", "PPL", "QM", "02", 15_000, tz),
                place(1010, "Hundred", "", "44.3", "8.3", "P", "PPL", "QM", "02", 100_000, tz),
                place(1011, "Alpha Town", "", "44.4", "8.4", "P", "PPLC", "QM", "01", 10, tz),
                place(1012, "Alpha Lake", "", "44.5", "8.5", "L", "LK", "QM", "01", 0, tz),
                place(2001, "Betaville", "", "-33.86785", "151.20732", "P", "PPLC", "QN", "01", 900, "Asia/Tokyo"),
                place(2002, "Beta", "", "-33.9", "151.3", "P", "PPL", "QN", "01", 50_000, "Asia/Tokyo"),
                place(2003, "Old Beta", "Beta", "-33.8", "151.1", "P", "PPLCH", "QN", "01", 0, "Asia/Tokyo"),
                place(3002, "Gammaport", "Gamma,Gama", "-41.28664", "174.77557", "P", "PPLA", "QP", "01", 20_000, "Pacific/Auckland"),
                place(4001, "Rivertown", "", "6.5", "3.4", "P", "PPLC", "QR", "01", 150_000, "Africa/Lagos"),
                place(5001, "Delta", "", "50.1", "10.1", "P", "PPLC", "QS", "01", 300_000, tz),
                place(6001, "Epsilon", "", "-77.8", "166.7", "P", "PPLX", "QT", "", 50, ""),
                place(6002, "Base Two", "", "-77.9", "166.8", "P", "PPL", "QT", "", 30, ""),
                place(7001, "Zeta", "", "27.15", "-13.2", "P", "PPLC", "EH", "01", 200_000, "Africa/Lagos"));
    }

    /** {@code capitals/QP.zip}: столицы в cities500 нет; строка 3002 — и там, и тут (берётся из cities500). */
    static List<String> capitalsQp() {
        String tz = "Pacific/Auckland";
        return List.of(
                place(3001, "Gamma", "", "-41.3", "174.8", "P", "PPL", "QP", "01", 20_000, tz),
                place(3002, "Gammaport Old", "Gamma", "-41.0", "174.0", "P", "PPLA", "QP", "01", 99_999, tz),
                place(3010, "Gamma Hotel", "Gamma", "-41.31", "174.81", "S", "HTL", "QP", "01", 0, tz),
                place(3011, "Gamma Village", "", "-41.4", "174.9", "P", "PPL", "QP", "01", 600, tz),
                place(3012, "Gamma Farms", "", "-41.5", "175", "P", "PPL", "QP", "01", 5_000, tz));
    }

    static List<String> admin1() {
        return List.of("QM.01\tUpper Province\tUpper Province\t9001", "QM.02\tLower Province\tLower Province\t9002",
                "QM.09\tNinth Province\tNinth Province\t9009", "QN.01\tCoast\tCoast\t9101", "QP.01\tIsles\tIsles\t9201");
    }

    // ---------------------------------------------------------------- World Bank

    /** Строка показателя ответа API; {@code value} — как в JSON ({@code null}, {@code 12}, {@code 1.5}, {@code 5e+11}). */
    static String wbRow(String id, String iso3, int year, String value) {
        return "{\"indicator\":{\"id\":\"" + id + "\",\"value\":\"x\"},\"country\":{\"id\":\"" + (iso3.isEmpty() ? "" : iso3.substring(0, 2))
                + "\",\"value\":\"x\"},\"countryiso3code\":\"" + iso3 + "\",\"date\":\"" + year + "\",\"value\":" + value
                + ",\"unit\":\"\",\"obs_status\":\"\",\"decimal\":0}";
    }

    /** Ответ API постранично: по странице на группу строк. */
    static String wbPages(List<List<String>> pages) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (List<String> p : pages) total += p.size();
        for (int i = 0; i < pages.size(); i++) {
            sb.append("[{\"page\":").append(i + 1).append(",\"pages\":").append(pages.size()).append(",\"per_page\":20000,\"total\":")
                    .append(total).append(",\"sourceid\":\"2\",\"lastupdated\":\"2026-11-01\"},")
                    .append(pages.get(i).isEmpty() ? "null" : "[" + String.join(",", pages.get(i)) + "]").append("]\n");
        }
        return sb.toString();
    }

    static Map<String, String> worldBank() {
        Map<String, String> m = new LinkedHashMap<>();
        String pop = "SP.POP.TOTL", gnp = "NY.GNP.MKTP.CD", gdp = "NY.GDP.MKTP.CD", life = "SP.DYN.LE00.IN", area = "AG.SRF.TOTL.K2";
        m.put(pop, wbPages(List.of(
                List.of(wbRow(pop, "QMA", 2023, "null"), wbRow(pop, "QMA", 2022, "5000000"), wbRow(pop, "QMA", 2021, "4950000"),
                        wbRow(pop, "QMA", 2020, "4900000"), wbRow(pop, "QMA", 1959, "4000000")),
                List.of(wbRow(pop, "QNA", 2022, "3100000.0"), wbRow(pop, "WLD", 2023, "8000000000"), wbRow(pop, "", 2023, "7"),
                        wbRow(pop, "ESH", 2022, "600000")))));
        m.put(gnp, wbPages(List.of(List.of(wbRow(gnp, "QMA", 2022, "2345675000.5"), wbRow(gnp, "QMA", 2021, "1234565000"),
                wbRow(gnp, "QNA", 2022, "5e+11"), wbRow(gnp, "QNA", 2021, "null")))));
        m.put(gdp, wbPages(List.of(List.of(wbRow(gdp, "QMA", 2021, "1300000000"), wbRow(gdp, "QMA", 2022, "null"),
                wbRow(gdp, "QRA", 2020, "1000000")))));
        m.put(life, wbPages(List.of(List.of(wbRow(life, "QMA", 2022, "72.1245"), wbRow(life, "QMA", 2021, "72.1235"),
                wbRow(life, "QNA", 2010, "65.5")), List.of())));
        m.put(area, wbPages(List.of(List.of(wbRow(area, "QMA", 2022, "41285.0"), wbRow(area, "QNA", 2021, "82880.25"),
                wbRow(area, "QRA", 2022, "0.44"), wbRow(area, "QMA", 2010, "41000")))));
        return m;
    }

    static String wbCountries() {
        return "[{\"page\":1,\"pages\":1,\"per_page\":1000,\"total\":3},[{\"id\":\"WLD\",\"iso2Code\":\"1W\",\"name\":\"World\","
                + "\"region\":{\"id\":\"NA\",\"iso2code\":\"NA\",\"value\":\"Aggregates\"}},{\"id\":\"QMA\",\"iso2Code\":\"QM\","
                + "\"name\":\"Qumland\",\"region\":{\"id\":\"ECS\",\"iso2code\":\"Z7\",\"value\":\"Europe & Central Asia \"}},"
                + "{\"id\":\"QNA\",\"iso2Code\":\"QN\",\"name\":\"Nordia\",\"region\":{\"id\":\"EAS\",\"iso2code\":\"Z4\","
                + "\"value\":\"East Asia & Pacific\"}}]]\n";
    }

    // ---------------------------------------------------------------- CLDR

    static String names(String locale, String kind, Map<String, String> v) {
        StringBuilder sb = new StringBuilder("{\n  \"main\": {\n    \"" + locale + "\": {\n      \"identity\": {\"language\": \""
                + locale + "\"},\n      \"localeDisplayNames\": {\n        \"" + kind + "\": {");
        boolean first = true;
        for (Map.Entry<String, String> e : v.entrySet()) {
            sb.append(first ? "\n" : ",\n").append("          \"").append(e.getKey()).append("\": \"").append(e.getValue()).append('"');
            first = false;
        }
        return sb.append("\n        }\n      }\n    }\n  }\n}\n").toString();
    }

    static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static final String TERRITORY_INFO = """
            {
              "supplemental": {
                "version": {"_unicodeVersion": "17.0.0", "_cldrVersion": "49"},
                "territoryInfo": {
                  "QM": {"_gdp": "1", "_population": "5000000", "languagePopulation": {
                    "de": {"_populationPercent": "60", "_officialStatus": "official"},
                    "en": {"_populationPercent": "95"},
                    "fr": {"_populationPercent": "40", "_officialStatus": "official"},
                    "it": {"_populationPercent": "40", "_officialStatus": "de_facto_official"}}},
                  "QN": {"_population": "3000000", "languagePopulation": {
                    "qab_Latn": {"_populationPercent": "5"},
                    "ru": {"_populationPercent": "10", "_officialStatus": "official_regional"},
                    "sr_Latn": {"_populationPercent": "80", "_officialStatus": "official"}}},
                  "QP": {"_population": "1", "languagePopulation": {
                    "en": {"_populationPercent": "30", "_officialStatus": "official"},
                    "qzz": {"_populationPercent": "1"},
                    "xxa": {"_populationPercent": "70", "_officialStatus": "official"},
                    "zh_Hant": {"_populationPercent": "70", "_officialStatus": "official"}}},
                  "QR": {"_population": "800", "languagePopulation": {
                    "fr": {"_populationPercent": "0.0062", "_officialStatus": "de_facto_official"},
                    "qrr": {"_populationPercent": "2.5"}}},
                  "QT": {"_population": "0"},
                  "EH": {"_population": "1", "languagePopulation": {"ar": {"_populationPercent": "90", "_officialStatus": "official"}}}
                }
              }
            }
            """;

    static final String CONTAINMENT = """
            {
              "supplemental": {
                "version": {"_cldrVersion": "49"},
                "territoryContainment": {
                  "001": {"_contains": ["150", "142", "009", "002"]},
                  "001-status-grouping": {"_contains": ["EU", "UN"]},
                  "150": {"_contains": ["155"]},
                  "155": {"_contains": ["QM"]},
                  "155-status-deprecated": {"_contains": ["QS"]},
                  "142": {"_contains": ["030"]},
                  "030": {"_contains": ["QN"]},
                  "172": {"_contains": ["QN"], "_status": "deprecated"},
                  "009": {"_contains": ["053", "QO"]},
                  "053": {"_contains": ["QP"]},
                  "419": {"_contains": ["QP"], "_grouping": "true"},
                  "002": {"_contains": ["011"]},
                  "011": {"_contains": ["QR", "EH"]},
                  "EU": {"_contains": ["QM"], "_grouping": "true"},
                  "QO": {"_contains": ["QT"]},
                  "UN": {"_contains": ["QM", "QN"], "_grouping": "true"}
                }
              }
            }
            """;

    static final String CLDR_LICENSE = """
            COPYRIGHT AND PERMISSION NOTICE (TEST)

            Unicode License v3 — test text of the tiny snapshot.

            SPDX-License-Identifier: Unicode-3.0
            """;

    static void cldr(Map<String, byte[]> e) {
        String n = "cldr/cldr-localenames-full/main/";
        e.put("cldr/cldr-core/LICENSE", utf8(CLDR_LICENSE));
        e.put("cldr/cldr-core/package.json", utf8("{\"name\": \"cldr-core\", \"version\": \"49.0.0\"}\n"));
        e.put("cldr/cldr-core/supplemental/territoryInfo.json", utf8(TERRITORY_INFO));
        e.put("cldr/cldr-core/supplemental/territoryContainment.json", utf8(CONTAINMENT));
        e.put("cldr/cldr-core/supplemental/languageData.json", utf8("{\"supplemental\": {\"languageData\": {}}}\n"));
        e.put("cldr/cldr-core/supplemental/likelySubtags.json", utf8("{\"supplemental\": {\"likelySubtags\": {}}}\n"));
        e.put("cldr/cldr-localenames-full/LICENSE", utf8(CLDR_LICENSE));
        e.put("cldr/cldr-localenames-full/package.json", utf8("{\"name\": \"cldr-localenames-full\", \"version\": \"49.0.0\"}\n"));
        e.put(n + "en/territories.json", utf8(names("en", "territories", map("001", "world", "002", "Africa",
                "009", "Oceania", "011", "Western Africa", "030", "Eastern Asia", "053", "Australasia", "142", "Asia",
                "150", "Europe", "155", "Western Europe", "QO", "Outlying Oceania", "EH", "Zetaland",
                "QM", "Qumland", "QM-alt-short", "Qum", "QN", "Nordia", "QP", "Pelagia", "QR", "Rivia", "QT", "Tundra"))));
        e.put(n + "ru/territories.json", utf8(names("ru", "territories", map("011", "Западная Африка",
                "030", "Восточная Азия", "053", "Австралазия", "155", "Западная Европа", "QO", "Внешняя Океания",
                "QM", "Кумландия", "QN", "Нордия", "QP", "Пелагия", "QR", "Ривия", "QT", "Тундра", "EH", "Зеталенд"))));
        e.put(n + "de/territories.json", utf8(names("de", "territories", map("QM", "Kumland", "QN", "Nordien"))));
        e.put(n + "fr/territories.json", utf8(names("fr", "territories", map("QM", "Qumlande", "QR", "Rivie"))));
        e.put(n + "it/territories.json", utf8(names("it", "territories", map("QM", "Qumlandia"))));
        e.put(n + "sr/territories.json", utf8(names("sr", "territories", map("QN", "Nordija"))));
        e.put(n + "zh-Hant/territories.json", utf8(names("zh-Hant", "territories", map("QM", "庫姆蘭"))));
        e.put(n + "en/languages.json", utf8(names("en", "languages", map("de", "German", "en", "English", "fr", "French",
                "it", "Italian", "ru", "Russian", "sr", "Serbian", "zh", "Chinese", "zh-Hant", "Traditional Chinese", "ar", "Arabic"))));
        e.put(n + "ru/languages.json", utf8(names("ru", "languages", map("de", "немецкий", "en", "английский",
                "fr", "французский", "it", "итальянский", "ru", "русский", "sr", "сербский", "zh", "китайский",
                "zh-Hant", "китайский, традиционное письмо"))));
        e.put(n + "en/scripts.json", utf8(names("en", "scripts", map("Latn", "Latin", "Hant", "Traditional"))));
        e.put(n + "ru/scripts.json", utf8(names("ru", "scripts", map("Latn", "латиница", "Hant", "традиционная китайская"))));
        for (String loc : List.of("de", "fr", "it", "sr", "zh-Hant")) {
            e.put(n + loc + "/languages.json", utf8(names(loc, "languages", map("en", "x"))));
            e.put(n + loc + "/scripts.json", utf8(names(loc, "scripts", map("Latn", "x"))));
        }
        e.put("cldr/npm/cldr-core-49.0.0.tgz", new byte[]{31, -117, 8, 0});
        e.put("cldr/npm/cldr-localenames-full-49.0.0.tgz", new byte[]{31, -117, 8, 0});
    }

    // ---------------------------------------------------------------- Wikidata

    static final String CITIES_RU = """
            geonameid,item,label_ru
            1001,Q5,Альфа-Сити
            1001,Q12,Альфа второй
            1002,Q7,"Альфабург, ""старый""\"
            2001,Q900,Бетавиль
            not-a-number,Q1,мимо
            3001,Q31,Гамма
            """;

    /**
     * Метки языков Wikidata: у {@code xxa} английская — у Q20 (у Q9 её нет), русская — у Q9; у {@code qzz} английской нет
     * (язык не берётся); у {@code qrr} нет русской.
     */
    static final String LANGUAGES = """
            code,item,label_en,label_ru
            qab,Q30,Qabic,кабский
            qrr,Q50,Qrran,
            qzz,Q40,,кзз
            xxa,Q9,,старый
            xxa,Q20,Xxan,ксанский
            """;

    // ---------------------------------------------------------------- снимок

    static String lines(List<String> l) {
        return String.join("\n", l) + "\n";
    }

    /** Записи крошечного снимка: путь → байты (изменяемая копия). */
    static Map<String, byte[]> entries() throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("fetch.json", utf8("{\"snapshot\":\"" + WldDataset.SNAPSHOT + "\",\"fetched_at\":\"2026-11-23T03:00:00Z\","
                + "\"files\":{},\"skipped\":[]}\n"));
        e.put("SOURCE.md", utf8("# Снимок источников (крошечный, для тестов)\n"));
        e.put("geonames/readme.txt", utf8("readme\n"));
        e.put("geonames/countryInfo.txt", utf8(lines(countryInfo())));
        e.put("geonames/admin1CodesASCII.txt", utf8(lines(admin1())));
        e.put("geonames/cities500.zip", zip(Map.of("cities500.txt", utf8(lines(cities500())))));
        e.put("geonames/capitals/QP.zip", zip(Map.of("QP.txt", utf8(lines(capitalsQp())), "readme.txt", utf8("readme\n"))));
        for (Map.Entry<String, String> w : worldBank().entrySet()) {
            e.put("worldbank/" + w.getKey() + ".jsonl", utf8(w.getValue()));
            e.put("worldbank/indicators/" + w.getKey() + ".json", utf8("[{}]\n"));
            e.put("worldbank/metadata/" + w.getKey() + ".json", utf8("{}\n"));
        }
        e.put("worldbank/countries.jsonl", utf8(wbCountries()));
        cldr(e);
        e.put("wikidata/queries/cities_ru.rq", utf8("SELECT ?gid ?item ?ru WHERE { }\n"));
        e.put("wikidata/cities_ru.csv", utf8(CITIES_RU));
        e.put("wikidata/languages.csv", utf8(LANGUAGES));
        List<String> sums = new ArrayList<>();
        for (String k : new TreeMap<>(e).keySet()) sums.add("0".repeat(64) + "  " + k);
        e.put("SHA256SUMS", utf8(lines(sums)));
        return e;
    }
}
