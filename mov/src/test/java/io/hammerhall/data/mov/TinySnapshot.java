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
 * <p>Файлы снимка v2 — тоже все, с ветками правил: год по P1191 (лучший ранг), P571 (P1191 хуже года), P2754 (P571
 * после года снимка), по запасному свойству при P577 вне окна, без года и по запасным; название из меток на любом языке
 * (негодная метка, две метки языка); бюджет и сборы (лучший ранг среди сумм в долларах, другая валюта, ноль и меньше,
 * половина — к чётному, запись с порядком, нечитаемая сумма); даты жизни (лучший ранг, точность, до 1700 и после года
 * снимка, смерть раньше рождения по дате и по году, дата не по календарю); город рождения (первое место по номеру,
 * место без GeoNames ID и без строки); гражданство (исключённый код, без ISO3, два кода); съёмочная группа (все пять
 * должностей, не человек, без имени, иное P21, фильм не в наборе); студии и прокатчики (обе роли у одной, без метки
 * en/mul, негодная метка en, страна исключённая и пустая); места съёмок (два места на один город, без GeoNames ID);
 * продолжения (P156 и встречное P155, сам себе, фильм не в наборе); серии (номера 1…10 000 и мимо, без метки); награды
 * (выиграна и номинирована, год по самой ранней дате, точность хуже года, без даты, без метки); выход по странам
 * (самая ранняя на страну, исключённая страна, город, точность месяца, дата не по календарю); строки о фильме не из
 * {@code films.csv}. Название только из метки {@code ru} — Q109 (P1476 со знаком табуляции негоден); без названия
 * совсем — Q126 (меток нет ни в одном файле).
 *
 * <p>Снимок v3 и города «Мира» ({@link #CITIES}, вход {@code cities}): места съёмок (P915), действия (P840), рождения
 * (P19) и штаб-квартиры (P159). Место — страна (Q30, есть ISO3); город — по GeoNames ID из «Мира» (0 км), ближайший той
 * же страны (ближе — город другой страны, равные расстояния — меньший id, совпадение координат — 0 км), дальше 30 км,
 * без координат, без страны, координаты вне диапазона; место без названия (ни en, ни mul) — его нет и ссылок на него
 * нет; название из {@code mul}; координаты с половиной в восьмом знаке. Исключения ({@link #EXCLUSIONS}): страна с
 * исключённым кодом (Q9001), место в такой стране (Q6013), место из списка (Q6014); место рождения — первое по номеру
 * из тех, что берутся (у Q503: Q6003 нет в places, Q6014 в списке — Q6015).
 *
 * <p>{@link #entries()} — записи снимка (путь → байты): тесты правят копию и пишут свой снимок ({@link #with}).
 */
public final class TinySnapshot {
    private TinySnapshot() {
    }

    private static Path file;
    private static final java.util.concurrent.atomic.AtomicInteger COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    private static Path citiesFile;

    /** Списки исключений крошечного снимка: страны — как у выпуска, места — Q6014. */
    static final Exclusions EXCLUSIONS = new Exclusions(Exclusions.COUNTRIES3, Exclusions.COUNTRIES2, java.util.Set.of(6014));

    /**
     * Входы набора для тестов: настоящий снимок и настоящие города «Мира», если они есть по путям входов; иначе — эти
     * ({@code -Dmov.source}, {@code -Dmov.cities}), каждый вход — сам по себе.
     */
    static synchronized void useIfNoSnapshot() throws IOException {
        Path real = Cli.inputPath(new MovDataset(), MovDataset.SOURCE, Map.of());
        if (!Files.isRegularFile(real) || real.equals(file)) System.setProperty("mov.source", path().toString());
        Path cities = Cli.inputPath(new MovDataset(), MovDataset.CITIES, Map.of());
        if (!Files.isRegularFile(cities) || cities.equals(citiesFile)) System.setProperty("mov.cities", citiesPath().toString());
    }

    /** Путь к крошечным городам «Мира» ({@link #CITIES}, {@code cities.tsv.gz}; пишется один раз на JVM). */
    static synchronized Path citiesPath() throws IOException {
        if (citiesFile == null) {
            Path dir = Files.createTempDirectory("mov-tiny-cities-");
            Path f = dir.resolve("cities.tsv.gz");
            Files.write(f, gz(CITIES));
            f.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            citiesFile = f;
        }
        return citiesFile;
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
            "Q120|Q11424", "Q121|Q11424", "Q122|Q11424", "Q123|Q11424", "Q124|Q11424", "Q125|Q11424", "Q126|Q11424");

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
            "Q120|1979-01-01T00:00:00Z|9|normal",
            "Q124|2010-01-01T00:00:00Z|9|normal",
            "Q125|1870-01-01T00:00:00Z|9|normal",
            "Q126|2011-01-01T00:00:00Z|9|normal");

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
            "Q120|en|Delta Dawn",
            "Q121|en|First Showing", "Q122|en|Made Long Ago", "Q123|en|Production Days", "Q125|en|Second Chance");

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
            "Q513|1|Q6581072|4", "Q514|1|Q6581097|0", "Q515|1||0", "Q516|1|Q48270|0", "Q517|1||0", "Q518|1||0",
            "Q519|1||0", "Q520|1|Q6581097|0");

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
            "Q515|en|Ole Back\\slash", "Q515|fr|Ole Lund",
            "Q516|en|Robin Ash", "Q517|en|Maria Ortiz", "Q518|en|Sam Reed", "Q520|en|Carl Stone");

    static final String PEOPLE_NAMES = csv("person,kind,item,ordinal",
            "Q501|family|Q801|", "Q501|given|Q904|",
            "Q502|family|Q802|",
            "Q507|given|Q901|",
            "Q510|given|Q902|",
            "Q511|given|Q903|",
            "Q512|family|Q805|2", "Q512|family|Q804|1",
            "Q515|given|Q902|2", "Q515|given|Q901|1",
            "Q517|given|Q902|");

    static final String NAME_ITEMS = csv("item,label_en,label_mul,kind",
            "Q801|Halden||family", "Q802|Zhang||family", "Q804||Kim|family", "Q805|Lee||family",
            "Q901|Hans||male", "Q902|Maria||female", "Q903|Al||unisex", "Q904|Steven||male");

    static final String COUNTRIES = csv("item,iso2,iso3",
            "Q30|US|USA", "Q9001|EH|ESH", "Q9002|QZ|", "Q9003|QX|QXB", "Q9003|QX|QXA", "Q6000||");

    static final String LANGUAGES = csv("item,iso1,iso3,label_en",
            "Q1860|en|eng|English", "Q9010||qya|Made-up", "Q9011|||Nameless");

    static final String CHARACTERS = csv("item,label_en,label_mul",
            "Q701|Captain Rook|", "Q702||Ghost", "Q703|Back\\slash|Clean Name", "Q704|Unused|");


    // ---------------------------------------------------------------- снимок v2

    /** Запасные даты года: Q121 — P1191, Q122 — P571, Q123 — P2754, Q125 — P1191 при P577 1870; Q104 — года нет. */
    static final String FILM_DATES_EXTRA = csv("film,prop,date,precision,rank",
            "Q101|P1191|1990-01-01T00:00:00Z|9|normal",
            "Q104|P571|1850-01-01T00:00:00Z|9|normal",
            "Q121|P1191|1960-05-05T00:00:00Z|11|preferred", "Q121|P1191|1955-01-01T00:00:00Z|9|normal",
            "Q122|P1191|1950-01-01T00:00:00Z|8|normal", "Q122|P571|1948-01-01T00:00:00Z|9|normal",
            "Q123|P571|2030-01-01T00:00:00Z|9|normal", "Q123|P2754|1933-01-01T00:00:00Z|9|normal",
            "Q125|P1191|1990-01-01T00:00:00Z|9|normal",
            "Q999|P571|1990-01-01T00:00:00Z|9|normal");

    /** Метки на любых языках — у Q124 нет ни метки из LABEL_LANGS, ни P1476. */
    static final String FILM_LABELS_ALL = csv("film,lang,label",
            "Q124|ar|Bad\tTitle", "Q124|cs|Český název", "Q124|cs|Alternativní název", "Q124|ja|日本語の題名");

    /** Выход по странам: film,date,precision,place. */
    static final String FILM_RELEASES = csv("film,date,precision,place",
            "Q101|1999-03-31T00:00:00Z|11|Q30", "Q101|1999-05-01T00:00:00Z|11|Q30", "Q101|1999-06-01T00:00:00Z|11|Q9001",
            "Q101|1999-04-10T00:00:00Z|11|Q9003", "Q101|1999-02-01T00:00:00Z|10|Q30", "Q101|1999-02-30T00:00:00Z|11|Q30",
            "Q101|1999-01-15T00:00:00Z|11|Q6000",
            "Q112|2003-03-03T00:00:00Z|11|Q30");

    /** Съёмочная группа: Q509 — не человек, Q519 — без имени, Q516 — иное P21, Q102 — дубль, Q999 — не фильм снимка. */
    static final String FILM_CREW = csv("film,prop,person",
            "Q101|P58|Q516", "Q101|P58|Q502", "Q101|P86|Q517", "Q101|P344|Q518", "Q101|P162|Q501", "Q101|P162|Q509",
            "Q101|P1040|Q519",
            "Q102|P86|Q520",
            "Q112|P58|Q516", "Q112|P1040|Q518",
            "Q999|P58|Q516");

    static final String FILM_LINKS2 = csv("film,prop,value",
            "Q101|P272|Q7001", "Q101|P750|Q7002", "Q101|P750|Q7001",
            "Q101|P915|Q6000", "Q101|P915|Q6001", "Q101|P915|Q6005", "Q101|P915|Q6002",
            "Q101|P156|Q112",
            "Q112|P272|Q7003", "Q112|P750|Q7004", "Q112|P915|Q6000", "Q112|P155|Q101", "Q112|P156|Q117",
            "Q113|P156|Q113", "Q113|P915|Q6010", "Q113|P915|Q6012", "Q112|P915|Q6013",
            "Q101|P840|Q30", "Q101|P840|Q6007", "Q112|P840|Q6006", "Q117|P840|Q6009", "Q117|P840|Q9001",
            "Q119|P155|Q102",
            "Q120|P155|Q119");

    static final String FILM_SERIES = csv("film,series,ordinal",
            "Q101|Q7101|2", "Q101|Q7101|1", "Q112|Q7101|0", "Q112|Q7102|10001", "Q113|Q7102|10000", "Q117|Q7101|3a",
            "Q117|Q7103|1");

    /** Суммы: Q4917 — доллар США, Q4916 — другая валюта. */
    static final String FILM_MONEY = csv("film,prop,amount,unit,rank",
            "Q101|P2130|+1000000|Q4917|preferred", "Q101|P2130|+5000000|Q4917|normal", "Q101|P2130|+9000000|Q4916|preferred",
            "Q101|P2142|+2500000.5|Q4917|normal", "Q101|P2142|+2400000|Q4917|normal", "Q101|P2142|abc|Q4917|normal",
            "Q107|P2130|0|Q4917|preferred", "Q107|P2130|100|Q4917|normal",
            "Q110|P2130|0.4|Q4917|normal", "Q110|P2142|3.5|Q4917|normal",
            "Q111|P2142|2.5|Q4917|normal",
            "Q112|P2130|+3000000|Q4916|preferred", "Q112|P2130|+2000000|Q4917|normal", "Q112|P2142|+7000000|Q4916|normal",
            "Q113|P2130|1.5E+3|Q4917|normal",
            "Q117|P2130|-5|Q4917|normal");

    static final String FILM_AWARDS = csv("film,prop,award,date,precision",
            "Q101|P166|Q7201|2000-03-26T00:00:00Z|11", "Q101|P166|Q7201|1999-01-01T00:00:00Z|9", "Q101|P1411|Q7201||",
            "Q101|P1411|Q7202|2001-01-01T00:00:00Z|8",
            "Q112|P166|Q7203|2004-01-01T00:00:00Z|9", "Q112|P1411|Q7202|2002-01-01T00:00:00Z|9",
            "Q112|P1411|Q7202|2003-xx|9");

    static final String PEOPLE_DATES = csv("person,prop,date,precision,rank",
            "Q501|P569|1946-12-18T00:00:00Z|11|normal",
            "Q502|P569|1970-01-01T00:00:00Z|9|preferred", "Q502|P569|1969-05-05T00:00:00Z|11|normal",
            "Q503|P569|1650-01-01T00:00:00Z|11|normal", "Q503|P570|1720-01-01T00:00:00Z|9|normal",
            "Q504|P569|1950-01-01T00:00:00Z|11|normal", "Q504|P570|1940-01-01T00:00:00Z|11|normal",
            "Q505|P569|1980-06-01T00:00:00Z|10|normal", "Q505|P569|1979-01-01T00:00:00Z|8|normal",
            "Q505|P570|2020-02-30T00:00:00Z|11|normal",
            "Q506|P569|1900-03-04T00:00:00Z|11|normal", "Q506|P570|1999-12-31T00:00:00Z|11|normal",
            "Q507|P570|2000-01-01T00:00:00Z|9|normal",
            "Q510|P569|1975-07-07T00:00:00Z|11|normal", "Q510|P570|1975-07-06T00:00:00Z|11|normal",
            "Q513|P569|2030-01-01T00:00:00Z|9|normal",
            "Q516|P569|bad-date|11|normal");

    static final String PEOPLE_LINKS = csv("person,prop,value",
            "Q501|P19|Q6001", "Q501|P19|Q6000", "Q501|P27|Q30", "Q501|P27|Q9001",
            "Q502|P19|Q6002", "Q502|P27|Q9002",
            "Q503|P19|Q6003", "Q503|P19|Q6015", "Q503|P19|Q6014",
            "Q510|P19|Q6007", "Q510|P19|Q6013", "Q510|P27|Q30", "Q510|P27|Q9003");

    /**
     * Места: Q6000 — город «Мира» по GeoNames ID; Q6001 — GeoNames ID не из «Мира», ближайший город страны — Brookfield
     * (Otherland ближе, но в другой стране); Q6002 — без GeoNames ID, два города на равном расстоянии; Q6004 — GeoNames ID
     * не читается; Q6005 — ни города, ни координат; Q6006 — ближайший дальше 30 км; Q6007 — без en и mul; Q6009 — без
     * страны; Q6010 — широта вне диапазона; Q6012 — только mul, координаты как у города; Q30 — страна; Q9001 — страна
     * с исключённым кодом; Q6013 — место в ней; Q6014 — из списка мест ({@link #EXCLUSIONS}); Q6015 — город «Мира» по
     * GeoNames ID, без координат.
     */
    static final String PLACES = csv("item,geonames,country,label_en,label_mul,label_ru,lat,lon",
            "Q30||Q30|United States||Соединённые Штаты|39.8|-98.5",
            "Q6000|1000001|Q30|Rivermouth||Ривермут|40.00000005|-74.00000015",
            "Q6001|1000002|Q30|Hillford|||40.08|-74",
            "Q6002||Q30|Old Mill|||40|-74.25",
            "Q6004|abc|Q30|Bad Id||||",
            "Q6005|1000002|Q30|Hillford North||||",
            "Q6006||Q30|Far Away|||45|-74",
            "Q6007||Q30|||Безымянное|40|-74",
            "Q6009|||Open Sea|||40|-74.01",
            "Q6010||Q30|Broken Coords|||95|10",
            "Q6012||Q30||Mill Pond||40|-74",
            "Q6013||Q9001|Sand Camp|||1|1",
            "Q6014|1000001|Q30|Listed Spot|||40|-74",
            "Q6015|1000004|Q30|Lakeside Village||||",
            "Q9001||Q9001|Zone Nine||||");

    static final String ITEM_LABELS = csv("item,lang,label",
            "Q7001|en|Northwind Pictures", "Q7001|ru|Нордвинд", "Q7002|mul|Bluebird Films", "Q7003|ru|Только русское",
            "Q7004|en|Bad\\Name", "Q7004|mul|Harbor Media",
            "Q7101|en|Alpha Saga", "Q7101|ru|Сага об Альфе", "Q7102|mul|Night Saga",
            "Q7201|en|Golden Reel", "Q7201|ru|Золотая катушка", "Q7202|mul|Silver Frame", "Q7202|de|Silberrahmen");

    /** Студии: штаб-квартира Q7001 — Q6001 (город — ближайший), Q7002 — Q6007 (без названия), Q7004 — Q6013 (исключено). */
    static final String COMPANIES = csv("item,country,hq",
            "Q7001|Q30|Q6001", "Q7002|Q9001|Q6007", "Q7004||Q6013");

    /**
     * Города «Мира» (TSV выпуска {@code wld-data-v1}, колонки — как в нём): Rivermouth и Lakeside — на равном расстоянии
     * от Old Mill (Q6002); Otherland — в другой стране; Farvale — дальше 30 км от Far Away (Q6006).
     */
    static final String CITIES = String.join("\n",
            "id\tname\tname_ru\tcountry_code\tdistrict\tpopulation\tlatitude\tlongitude\ttimezone",
            "1000001\tRivermouth\t\tUSA\tShore\t50000\t40\t-74\tAmerica/New_York",
            "1000003\tBrookfield\t\tUSA\tShore\t12000\t40.1\t-74\tAmerica/New_York",
            "1000004\tLakeside\t\tUSA\tShore\t9000\t40\t-74.5\tAmerica/New_York",
            "1000006\tOtherland\t\tQXA\t\t3000\t40.08\t-74\t",
            "1000007\tFarvale\t\tUSA\tNorth\t2000\t45.4\t-74\tAmerica/New_York") + "\n";

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
        e.put("wikidata/film_dates_extra.csv.gz", gz(FILM_DATES_EXTRA));
        e.put("wikidata/film_labels_all.csv.gz", gz(FILM_LABELS_ALL));
        e.put("wikidata/film_releases.csv.gz", gz(FILM_RELEASES));
        e.put("wikidata/film_crew.csv.gz", gz(FILM_CREW));
        e.put("wikidata/film_links2.csv.gz", gz(FILM_LINKS2));
        e.put("wikidata/film_series.csv.gz", gz(FILM_SERIES));
        e.put("wikidata/film_money.csv.gz", gz(FILM_MONEY));
        e.put("wikidata/film_awards.csv.gz", gz(FILM_AWARDS));
        e.put("wikidata/people_dates.csv.gz", gz(PEOPLE_DATES));
        e.put("wikidata/people_links.csv.gz", gz(PEOPLE_LINKS));
        e.put("wikidata/places.csv.gz", gz(PLACES));
        e.put("wikidata/item_labels.csv.gz", gz(ITEM_LABELS));
        e.put("wikidata/companies.csv", utf8(COMPANIES));
        List<String> sums = new ArrayList<>();
        for (String k : new TreeMap<>(e).keySet()) sums.add("0".repeat(64) + "  " + k);
        e.put("SHA256SUMS", utf8(String.join("\n", sums) + "\n"));
        return e;
    }
}
