package io.hammerhall.data.mov;

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
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Крошечный снимок Wikidata в формате {@code mov-source-*.zip} — вместо настоящего: тесты набора идут и без него.
 * Фильмы, люди и персонажи выдуманы (номера элементов — Q101…, Q501…), настоящие — только номера классов и якорных
 * жанров Wikidata и коды ISO. Большие таблицы — {@code .csv.gz} внутри zip, как в снимке; тексты — CSV, как пишет
 * модуль {@code csv} Python.
 *
 * <p>В снимке — все случаи правил: исключённые классы (клип, серия), фильм без даты, с годом до 1874 и после года
 * снимка, с датой до н. э.; предпочтительная дата позже обычной и точность хуже года; названия из меток, P1476 и
 * запасного языка, фильм без названия; хронометраж на границах (40,5 и 41,5 минуты, 1 500 и 1 501, 20 секунд);
 * дубль по IMDb; жанры по классу, по хронометражу и по P136 на разной глубине, два якоря одного уровня, якорь глубже 6,
 * петля P279, составной жанр; люди без имени, не люди, имя из запасного языка и из метки без уточнения в скобках;
 * фамилия в конце и в начале имени, частицы, суффикс, мононим; пол по P21 (четыре значения и иное), по имени и от
 * seed; роли из P4633 и персонажа, несколько ролей, повтор, роль со знаком табуляции; страны (исключённый код, без
 * ISO3, два ISO3) и языки (ISO 639-1, только 639-3, без кода).
 *
 * <p>{@link #entries()} — записи снимка (путь → байты): тесты правят копию и пишут свой снимок ({@link #with}).
 */
public final class TinySnapshot {
    private TinySnapshot() {
    }

    private static Path file;
    private static final java.util.concurrent.atomic.AtomicInteger COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    /** Вход набора для тестов: настоящий снимок, если он есть по пути входа; иначе — этот ({@code -Dmov.source}). */
    static synchronized void useIfNoSnapshot() throws IOException {
        Path real = Cli.inputPath(new MovDataset(), MovDataset.SOURCE, Map.of());
        if (Files.isRegularFile(real) && !real.equals(file)) return;
        System.setProperty("mov.source", path().toString());
    }

    /** Путь к крошечному снимку (пишется один раз на JVM, во временную папку). */
    static synchronized Path path() throws IOException {
        if (file == null) {
            Path dir = Files.createTempDirectory("mov-tiny-");
            Path f = dir.resolve(MovDataset.SNAPSHOT + ".zip");
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

    /** gzip без отметки времени (как {@code .csv.gz} снимка). */
    static byte[] gz(String text) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bo)) {
            g.write(utf8(text));
        }
        return bo.toByteArray();
    }

    static void write(Path f, Map<String, byte[]> entries) throws IOException {
        try (OutputStream o = Files.newOutputStream(f); ZipOutputStream z = new ZipOutputStream(o, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : new TreeMap<>(entries).entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setLastModifiedTime(FileTime.fromMillis(315_532_800_000L));
                z.putNextEntry(ze);
                z.write(e.getValue());
                z.closeEntry();
            }
        }
    }

    // ---------------------------------------------------------------- CSV

    /** Поле CSV как у {@code csv.writer} Python ({@code QUOTE_MINIMAL}). */
    static String field(String v) {
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    /** Текст CSV: заголовок, затем строки ({@code |} — разделитель полей в строке-описании). */
    static String csv(String header, String... rows) {
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (String r : rows) {
            List<String> f = new ArrayList<>();
            for (String x : r.split("\\|", -1)) f.add(field(x));
            sb.append(String.join(",", f)).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- данные

    static final String CLASSES = csv("class,label_en,included",
            "Q11424|film|1", "Q24862|short film|1", "Q17517379|animated short film|1", "Q93204|documentary film|1",
            "Q202866|animated film|1", "Q193977|music video|0", "Q24856|film series|0");

    /** Фильмы и их классы: Q103 — серия, Q116 — клип; остальные — фильмы. */
    static final String FILMS = csv("film,class",
            "Q101|Q11424", "Q102|Q11424", "Q103|Q11424", "Q103|Q24856", "Q104|Q11424", "Q105|Q11424", "Q106|Q11424",
            "Q107|Q11424", "Q108|Q11424", "Q109|Q11424", "Q110|Q17517379", "Q111|Q93204", "Q112|Q11424", "Q113|Q11424",
            "Q114|Q11424", "Q115|Q11424", "Q116|Q11424", "Q116|Q193977", "Q117|Q11424", "Q118|Q11424", "Q119|Q11424",
            "Q120|Q11424");

    static final String FILM_DATES = csv("film,date,precision,rank",
            "Q101|1998-01-01T00:00:00Z|9|normal", "Q101|1999-03-31T00:00:00Z|11|preferred",
            "Q101|2001-05-05T00:00:00Z|11|preferred",
            "Q102|1999-01-01T00:00:00Z|9|normal",
            "Q103|1999-01-01T00:00:00Z|9|normal",
            "Q105|1870-01-01T00:00:00Z|9|normal",
            "Q106|2027-01-01T00:00:00Z|9|normal",
            "Q107|1950-05-01T00:00:00Z|10|normal",
            "Q108|1960-01-01T00:00:00Z|8|normal", "Q108|1961-06-15T00:00:00Z|11|normal",
            "Q108|1962-01-01T00:00:00Z|9|normal",
            "Q109|1970-01-01T00:00:00Z|9|normal",
            "Q110|1940-11-13T00:00:00Z|11|normal",
            "Q111|1985-01-01T00:00:00Z|9|normal",
            "Q112|1974-01-01T00:00:00Z|9|normal",
            "Q113|1874-12-09T00:00:00Z|11|normal",
            "Q114|2003-01-01T00:00:00Z|9|normal",
            "Q115|2004-01-01T00:00:00Z|9|normal",
            "Q116|1983-12-02T00:00:00Z|11|normal",
            "Q117|1991-01-01T00:00:00Z|9|normal",
            "Q118|-0001-01-01T00:00:00Z|9|normal",
            "Q119|2005-01-01T00:00:00Z|9|normal",
            "Q120|1979-01-01T00:00:00Z|9|normal");

    static final String FILM_LABELS = csv("film,lang,label",
            "Q101|en|Alpha Story", "Q101|ru|Альфа", "Q101|de|Alpha-Geschichte",
            "Q102|en|Alpha Story Copy",
            "Q103|en|Series One",
            "Q104|en|No Date",
            "Q105|en|Too Early",
            "Q106|en|Too Late",
            "Q107|ru|История",
            "Q108|ru|Русское", "Q108|fr|Français", "Q108|de|Deutsches", "Q108|ja|日本",
            "Q109|ru|Только по-русски",
            "Q110|en|Paper Moon Short",
            "Q111|en|Short Document",
            "Q112|en|Night Chase",
            "Q113|en|Deep Roots",
            "Q114|mul|Long Haul",
            "Q115|en|Very Long", "Q115|mul|Very Long Mul",
            "Q116|en|Clip",
            "Q117|en|Scary Laughs",
            "Q118|en|Ancient",
            "Q119|en|Gamma Night", "Q119|ru|Ночь гаммы",
            "Q120|en|Delta Dawn");

    static final String FILM_TITLES = csv("film,lang,title",
            "Q107|fr|Le Récit", "Q107|en|The Story", "Q107|de|Die Geschichte",
            "Q109|xx|Tab\there");

    static final String FILM_LINKS = csv("film,prop,value",
            "Q101|P136|Q130232", "Q101|P136|Q860626", "Q101|P57|Q501", "Q101|P495|Q30", "Q101|P495|Q9001",
            "Q101|P495|Q9002", "Q101|P495|Q9003", "Q101|P364|Q1860", "Q101|P364|Q9010", "Q101|P364|Q9011",
            "Q102|P57|Q501",
            "Q107|P57|Q506",
            "Q112|P136|Q8001", "Q112|P136|Q8003", "Q112|P57|Q501", "Q112|P57|Q509", "Q112|P495|Q30",
            "Q113|P136|Q8010", "Q113|P136|Q8020", "Q113|P57|Q501", "Q113|P57|Q508",
            "Q117|P136|Q8030", "Q117|P57|Q501", "Q117|P57|Q506",
            "Q119|P57|Q506", "Q119|P136|Q24862");

    static final String FILM_DURATIONS = csv("film,seconds",
            "Q101|5400", "Q110|600", "Q111|2430", "Q112|2490", "Q113|5400", "Q113|20", "Q114|90060", "Q115|90000.0",
            "Q119|2460");

    /** Признаки: Q101 — самый известный; Q102 — дубль Q101 по IMDb с меньшим sitelinks; Q112 — второй. */
    static final String FILM_SIGNALS = csv("film,sitelinks,awards,nominations,imdb",
            "Q101|50|3|5|tt0000101", "Q102|10|0|0|tt0000101", "Q107|2|0|0|", "Q108|1|0|0|tt0000108",
            "Q110|5|0|0|", "Q111|0|0|0|", "Q112|30|1|0|tt0000112", "Q113|0|0|0|", "Q114|0|0|0|", "Q115|0|0|0|",
            "Q117|20|0|2|", "Q119|3|0|0|", "Q120|0|0|0|");

    /** Состав: film,prop,person,ordinal,character,character_name. */
    static final String FILM_CAST = csv("film,prop,person,ordinal,character,character_name",
            "Q101|P161|Q502|1|Q701|",
            "Q101|P161|Q502|1||Narrator",
            "Q101|P161|Q503|2|Q702|",
            "Q101|P161|Q503|2|Q702|",
            "Q101|P725|Q504|||",
            "Q101|P161|Q505||Q701|Bad\tName",
            "Q101|P161|Q506|||",
            "Q101|P161|Q507||Q703|",
            "Q101|P161|Q508|||",
            "Q101|P161|Q509|||",
            "Q101|P161|Q510||Q704|Herself",
            "Q101|P161|Q511|||",
            "Q101|P161|Q512|||",
            "Q101|P161|Q513|||",
            "Q101|P161|Q514|||",
            "Q102|P161|Q502|||",
            "Q107|P161|Q502|||Self",
            "Q112|P161|Q510|||",
            "Q112|P161|Q515|||",
            "Q119|P161|Q513|||");

    static final String GENRE_ITEMS = csv("item,label_en,parent",
            "Q130232|drama film|",
            "Q860626|romantic comedy|Q157443",
            "Q8001|made-up genre one|Q8002",
            "Q8002|made-up genre two|Q2421031",
            "Q2421031|neo-noir|Q959790",
            "Q8003|made-up genre three|Q188473", "Q8003|made-up genre three|Q8004",
            "Q8004|made-up genre four|Q319221",
            "Q8010|deep 1|Q8011", "Q8011|deep 2|Q8012", "Q8012|deep 3|Q8013", "Q8013|deep 4|Q8014",
            "Q8014|deep 5|Q8015", "Q8015|deep 6|Q8016", "Q8016|deep 7|Q130232",
            "Q8020|loop a|Q8021", "Q8021|loop b|Q8020",
            "Q8030|two anchors|Q157443", "Q8030|two anchors|Q200092");

    /** Люди: person,human,gender,sitelinks. */
    static final String PEOPLE = csv("person,human,gender,sitelinks",
            "Q501|1|Q6581097|40", "Q502|1|Q6581097|30", "Q503|1|Q6581072|20", "Q504|1|Q2449503|5", "Q505|1|Q1052281|5",
            "Q506|1|Q48270|3", "Q507|1||1", "Q508|1|Q6581072|1", "Q509|0||0", "Q510|1||2", "Q511|1||0", "Q512|1||0",
            "Q513|1|Q6581072|4", "Q514|1|Q6581097|0", "Q515|1||0");

    static final String PEOPLE_LABELS = csv("person,lang,label",
            "Q501|en|Steven Halden", "Q501|ru|Стивен Халден",
            "Q502|en|Zhang Wei",
            "Q503|en|Pieter van Dalen",
            "Q504|en|Robert Halden Jr.",
            "Q505|en|Zelda",
            "Q506|en|John Smith (actor)",
            "Q507|de|Hans Müller", "Q507|ru|Ганс Мюллер",
            "Q508|ru|Только русское имя",
            "Q509|en|A Horse",
            "Q510|en|Maria de la Vega",
            "Q511|en|Al Corvo",
            "Q512|en|Kim Lee",
            "Q513|mul|Ana Torres",
            "Q514|en|(unnamed)", "Q514|mul|Somebody Else",
            "Q515|en|Ole Back\\slash", "Q515|fr|Ole Lund");

    static final String PEOPLE_NAMES = csv("person,kind,item,ordinal",
            "Q501|family|Q801|", "Q501|given|Q904|",
            "Q502|family|Q802|",
            "Q507|given|Q901|",
            "Q510|given|Q902|",
            "Q511|given|Q903|",
            "Q512|family|Q805|2", "Q512|family|Q804|1",
            "Q515|given|Q902|2", "Q515|given|Q901|1");

    static final String NAME_ITEMS = csv("item,label_en,label_mul,kind",
            "Q801|Halden||family", "Q802|Zhang||family", "Q804||Kim|family", "Q805|Lee||family",
            "Q901|Hans||male", "Q902|Maria||female", "Q903|Al||unisex", "Q904|Steven||male");

    static final String COUNTRIES = csv("item,iso2,iso3",
            "Q30|US|USA", "Q9001|EH|ESH", "Q9002|QZ|", "Q9003|QX|QXB", "Q9003|QX|QXA");

    static final String LANGUAGES = csv("item,iso1,iso3,label_en",
            "Q1860|en|eng|English", "Q9010||qya|Made-up", "Q9011|||Nameless");

    static final String CHARACTERS = csv("item,label_en,label_mul",
            "Q701|Captain Rook|", "Q702||Ghost", "Q703|Back\\slash|Clean Name", "Q704|Unused|");

    // ---------------------------------------------------------------- снимок

    /** Записи крошечного снимка: путь → байты (изменяемая копия). */
    static Map<String, byte[]> entries() throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("fetch.json", utf8("{\"snapshot\":\"" + MovDataset.SNAPSHOT + "\",\"fetched_at\":\"2026-11-23T03:00:00Z\","
                + "\"files\":{},\"skipped\":[]}\n"));
        e.put("SOURCE.md", utf8("# Снимок Wikidata (крошечный, для тестов)\n"));
        e.put("wikidata/queries/films.rq", utf8("SELECT ?film WHERE { ?film wdt:P31 wd:%CLASS% }\n"));
        e.put("wikidata/classes.csv", utf8(CLASSES));
        e.put("wikidata/films.csv.gz", gz(FILMS));
        e.put("wikidata/film_dates.csv.gz", gz(FILM_DATES));
        e.put("wikidata/film_labels.csv.gz", gz(FILM_LABELS));
        e.put("wikidata/film_titles.csv.gz", gz(FILM_TITLES));
        e.put("wikidata/film_links.csv.gz", gz(FILM_LINKS));
        e.put("wikidata/film_durations.csv.gz", gz(FILM_DURATIONS));
        e.put("wikidata/film_signals.csv.gz", gz(FILM_SIGNALS));
        e.put("wikidata/film_cast.csv.gz", gz(FILM_CAST));
        e.put("wikidata/genre_items.csv", utf8(GENRE_ITEMS));
        e.put("wikidata/people.csv.gz", gz(PEOPLE));
        e.put("wikidata/people_labels.csv.gz", gz(PEOPLE_LABELS));
        e.put("wikidata/people_names.csv.gz", gz(PEOPLE_NAMES));
        e.put("wikidata/name_items.csv.gz", gz(NAME_ITEMS));
        e.put("wikidata/countries.csv", utf8(COUNTRIES));
        e.put("wikidata/languages.csv", utf8(LANGUAGES));
        e.put("wikidata/characters.csv.gz", gz(CHARACTERS));
        List<String> sums = new ArrayList<>();
        for (String k : new TreeMap<>(e).keySet()) sums.add("0".repeat(64) + "  " + k);
        e.put("SHA256SUMS", utf8(String.join("\n", sums) + "\n"));
        return e;
    }
}
