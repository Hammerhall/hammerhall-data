package io.hammerhall.data.wld;

import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.wld.build.World;
import io.hammerhall.data.wld.source.SourceData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Набор из крошечного снимка ({@link TinySnapshot}): правила на настоящем разборе файлов снимка. */
class WorldTest {
    static SourceData tiny() throws IOException {
        return SourceData.read(TinySnapshot.path());
    }

    static World world(Profile p) throws IOException {
        return World.build(tiny(), p, Exclusions.DEFAULT);
    }

    static Map<String, World.Country> countries(World w) {
        return w.countries().stream().collect(Collectors.toMap(World.Country::code, Function.identity()));
    }

    static List<Long> ids(World w) {
        return w.cities().stream().map(World.City::id).toList();
    }

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    @DisplayName("Страны: есть в CLDR и не в списке исключённых; коды, названия, часть света, регион, local_name")
    void countriesTaken() throws IOException {
        World w = world(Profile.SMALL);
        assertEquals(List.of("QMA", "QNA", "QPA", "QRA", "QTA"), w.countries().stream().map(World.Country::code).toList(),
                "QS нет в CLDR, EH — в списке исключённых");
        World.Country qm = countries(w).get("QMA");
        assertEquals("QM", qm.code2());
        assertEquals("Qumland", qm.name(), "ключ CLDR — ровно ISO2, не -alt-");
        assertEquals("Кумландия", qm.nameRu());
        assertEquals("Kumland", qm.localName());
        assertEquals("Europe", qm.continent());
        assertEquals("Western Europe", qm.region());
        assertEquals("Западная Европа", qm.regionRu());
        World.Country qn = countries(w).get("QNA"), qp = countries(w).get("QPA"), qr = countries(w).get("QRA"),
                qt = countries(w).get("QTA");
        assertEquals("Nordija", qn.localName(), "sr_Latn: локали sr-Latn нет — локаль sr");
        assertEquals("Eastern Asia", qn.region(), "запись 172 — устаревшая");
        assertEquals("Pelagia", qp.localName(), "xx — нет локали, zh-Hant — нет названия, затем en");
        assertEquals("Australasia", qp.region(), "419 — группировка");
        assertEquals("Rivie", qr.localName(), "de_facto_official — тоже официальный");
        assertEquals("Outlying Oceania", qt.region(), "числовой записи нет — буквенная QO");
        assertEquals("Внешняя Океания", qt.regionRu());
        assertNull(qt.localName(), "языков нет");
        assertEquals("Antarctica", qt.continent());
    }

    @Test
    @DisplayName("World Bank: последний год с данными у каждого показателя; ВНП в млн (половина — к чётному) и за год до него")
    void worldBankLastYear() throws IOException {
        Map<String, World.Country> c = countries(world(Profile.FULL));
        World.Country qm = c.get("QMA"), qn = c.get("QNA");
        assertEquals(5_000_000L, qm.population(), "2023 — пусто: последний год с данными — 2022");
        assertEquals(d("2345.68"), qm.gnp());
        assertEquals(d("1234.56"), qm.gnpOld(), "1234.565 → 1234.56");
        assertEquals(d("72.124"), qm.lifeExpectancy(), "72.1245 → 72.124");
        assertEquals(d("41285"), qm.surfaceArea(), "World Bank, последний год (2022), не GeoNames");
        assertEquals(3_100_000L, qn.population(), "3100000.0 — целое");
        assertEquals(d("500000.00"), qn.gnp(), "5e+11");
        assertNull(qn.gnpOld(), "за 2021 — пусто");
        assertEquals(d("65.500"), qn.lifeExpectancy());
        assertEquals(d("82880.25"), qn.surfaceArea());
    }

    @Test
    @DisplayName("Площадь и население: нет у страны данных World Bank — GeoNames (население — если больше нуля)")
    void geonamesFallback() throws IOException {
        Map<String, World.Country> c = countries(world(Profile.FULL));
        assertEquals(d("468"), c.get("QPA").surfaceArea());
        assertNull(c.get("QPA").population(), "в GeoNames 0 — пусто");
        assertEquals(d("14000000"), c.get("QTA").surfaceArea(), "1.4E7");
        assertNull(c.get("QTA").population());
        assertEquals(800L, c.get("QRA").population());
        assertEquals(d("0.44"), c.get("QRA").surfaceArea());
        assertNull(c.get("QRA").lifeExpectancy(), "без замены");
        assertNull(c.get("QRA").gnp());
    }

    @Test
    @DisplayName("Столицы: три правила среди городов страны; столица из capitals/*.zip; пустой Capital; PPLX — не кандидат")
    void capitals() throws IOException {
        World w = world(Profile.SMALL);
        Map<String, World.Country> c = countries(w);
        assertEquals(1001L, c.get("QMA").capital());
        assertEquals(2001L, c.get("QNA").capital(), "единственная PPLC, хотя есть PPL «Beta»");
        assertEquals(3001L, c.get("QPA").capital(), "из capitals/QP.zip: население как у 3002, geonameid меньше");
        assertNull(c.get("QRA").capital(), "Capital пуст");
        assertNull(c.get("QTA").capital(), "«Epsilon» — PPLX, не город: кандидатов нет");
        assertEquals(2L, w.stats().get("capitals_empty"));
    }

    @Test
    @DisplayName("Пороги профилей: full ≥ 1 000, medium ≥ 15 000, small ≥ 100 000 — и все столицы в каждом")
    void thresholds() throws IOException {
        assertEquals(List.of(1001L, 1002L, 1003L, 1004L, 1008L, 1009L, 1010L, 2001L, 2002L, 3001L, 3002L, 3012L, 4001L),
                ids(world(Profile.FULL)));
        assertEquals(List.of(1001L, 1002L, 1003L, 1009L, 1010L, 2001L, 2002L, 3001L, 3002L, 4001L), ids(world(Profile.MEDIUM)));
        assertEquals(List.of(1001L, 1002L, 1010L, 2001L, 3001L, 4001L), ids(world(Profile.SMALL)),
                "2001 (900 жителей) и 3001 (20 000) — столицы");
    }

    @Test
    @DisplayName("Города: только класс P, без PPLX/PPLH/PPLCH; при повторе geonameid — строка cities500")
    void cityRows() throws IOException {
        World w = world(Profile.FULL);
        List<Long> ids = ids(w);
        for (long skipped : List.of(1005L, 1006L, 1007L, 1011L, 1012L, 2003L, 3010L, 3011L, 5001L, 6001L, 6002L, 7001L)) {
            assertFalse(ids.contains(skipped), "город " + skipped);
        }
        World.City c3002 = w.cities().stream().filter(c -> c.id() == 3002).findFirst().orElseThrow();
        assertEquals("Gammaport", c3002.name(), "строка из cities500, не из capitals/QP.zip");
        assertEquals(20_000, c3002.population());
        World.City c1001 = w.cities().getFirst();
        assertEquals("Alpha City", c1001.name());
        assertEquals("Альфа-Сити", c1001.nameRu(), "элемент с наименьшим Q: Q5, не Q12");
        assertEquals("QMA", c1001.countryCode());
        assertEquals("Upper Province", c1001.district());
        assertEquals("48.1", c1001.latitude().toPlainString());
        assertEquals("Europe/Berlin", c1001.timezone());
        World.City c1002 = w.cities().get(1);
        assertEquals("Альфабург, \"старый\"", c1002.nameRu());
        World.City c1008 = w.cities().stream().filter(c -> c.id() == 1008).findFirst().orElseThrow();
        assertNull(c1008.district(), "региона QM.00 нет");
        assertNull(c1008.timezone());
        assertNull(c1008.nameRu());
    }

    @Test
    @DisplayName("Исключения: регион CC.ADM1 и geonameid убирают города, страна — всё своё; на страну регионы не влияют")
    void exclusions(@TempDir Path tmp) throws IOException {
        SourceData src = tiny();
        World w = World.build(src, Profile.FULL, new Exclusions(Exclusions.COUNTRIES, List.of("QM.09"), List.of(1002L)));
        List<Long> ids = ids(w);
        assertFalse(ids.contains(1004L), "регион QM.09");
        assertFalse(ids.contains(1002L), "geonameid 1002");
        assertEquals(11, ids.size());
        assertEquals(5_000_000L, countries(w).get("QMA").population(), "число жителей страны — как в источнике");

        World noCapital = World.build(src, Profile.SMALL, new Exclusions(Exclusions.COUNTRIES, List.of(), List.of(1001L)));
        assertEquals(1011L, countries(noCapital).get("QMA").capital(),
                "1001 — в списке городов: кандидат не он, и единственная оставшаяся PPLC — 1011");
        assertFalse(ids(noCapital).contains(1001L));
        assertTrue(ids(noCapital).contains(1011L), "столица — в любом профиле, даже с 10 жителями");

        World noQm = World.build(src, Profile.FULL, new Exclusions(List.of("QM"), List.of(), List.of()));
        assertFalse(countries(noQm).containsKey("QMA"));
        assertTrue(countries(noQm).containsKey("ESH"), "список стран подменён — EH снова в наборе");
        assertTrue(noQm.cities().stream().noneMatch(c -> c.countryCode().equals("QMA")));
        assertTrue(noQm.borders().stream().noneMatch(b -> b.countryCode().equals("QMA") || b.neighbourCode().equals("QMA")));
        assertTrue(noQm.languages().stream().noneMatch(l -> l.countryCode().equals("QMA")));
        assertTrue(noQm.years().stream().noneMatch(y -> y.countryCode().equals("QMA")));
    }

    @Test
    @DisplayName("Языки: CLDR, иначе база с письменностью, иначе Wikidata; без английского названия — не берутся; official и de_facto")
    void languages() throws IOException {
        World w = world(Profile.SMALL);
        assertEquals(12, w.languages().size());
        assertEquals(1L, w.stats().get("languages_without_english_name"));
        Map<String, World.Language> l = new java.util.HashMap<>();
        for (World.Language x : w.languages()) l.put(x.countryCode() + " " + x.languageCode(), x);
        assertEquals("Serbian (Latin)", l.get("QNA sr-Latn").language());
        assertEquals("сербский (латиница)", l.get("QNA sr-Latn").languageRu());
        assertFalse(l.get("QNA ru").official(), "official_regional — не официальный");
        assertTrue(l.get("QMA it").official());
        assertFalse(l.get("QMA en").official());
        assertEquals("Xxan", l.get("QPA xxa").language(), "Wikidata: Q9 без английской метки — Q20");
        assertEquals("старый", l.get("QPA xxa").languageRu(), "русская — у Q9, наименьший Q с меткой");
        assertFalse(l.containsKey("QPA qzz"), "английского названия нет нигде");
        assertEquals("Qabic (Latin)", l.get("QNA qab-Latn").language());
        assertEquals("кабский (латиница)", l.get("QNA qab-Latn").languageRu());
        assertEquals("Qrran", l.get("QRA qrr").language());
        assertNull(l.get("QRA qrr").languageRu());
        assertEquals("китайский, традиционное письмо", l.get("QPA zh-Hant").languageRu());
        assertEquals(d("0.0062"), l.get("QRA fr").percentage());
    }

    @Test
    @DisplayName("Соседство — объединение сторон, без невзятых стран; ряды по годам — с 1960, строка — где есть хоть одно значение")
    void bordersAndYears() throws IOException {
        World w = world(Profile.MEDIUM);
        List<String> pairs = new ArrayList<>();
        for (World.Border b : w.borders()) pairs.add(b.countryCode() + "-" + b.neighbourCode());
        assertEquals(List.of("QMA-QNA", "QMA-QPA", "QMA-QRA", "QNA-QMA", "QPA-QMA", "QRA-QMA"), pairs);
        List<String> years = new ArrayList<>();
        for (World.Year y : w.years()) years.add(y.countryCode() + " " + y.year());
        assertEquals(List.of("QMA 2020", "QMA 2021", "QMA 2022", "QNA 2010", "QNA 2022", "QRA 2020"), years,
                "1959 — до начала рядов; агрегат WLD и ESH — не страны набора");
        World.Year qm2021 = w.years().get(1);
        assertEquals(4_950_000L, qm2021.population());
        assertEquals(d("1234.56"), qm2021.gnp());
        assertEquals(d("1300.00"), qm2021.gdp());
        assertEquals(d("72.124"), qm2021.lifeExpectancy());
        assertNull(w.years().get(2).gdp(), "за 2022 у ВВП — null");
        assertEquals(2022, w.lastYear(), "WLD (агрегат) с 2023 — не в счёт");
    }

    @Test
    @DisplayName("Страны, языки, соседство и ряды — одни во всех профилях")
    void sameCountriesInAllProfiles() throws IOException {
        World s = world(Profile.SMALL), m = world(Profile.MEDIUM), f = world(Profile.FULL);
        assertEquals(f.countries(), s.countries());
        assertEquals(f.countries(), m.countries());
        assertEquals(f.languages(), s.languages());
        assertEquals(f.borders(), m.borders());
        assertEquals(f.years(), s.years());
    }

    @Test
    @DisplayName("Координаты: значение источника точно (хвостовые нули — не знаки); больше 7 знаков — ошибка, а не округление")
    void coordinatesAsInSource(@TempDir Path tmp) throws IOException {
        List<String> rows = new ArrayList<>(TinySnapshot.cities500());
        rows.set(0, rows.getFirst().replace("\t48.1\t11.5\t", "\t48.1000000\t11.50\t"));
        Path f = TinySnapshot.with(tmp, Map.of("geonames/cities500.zip",
                TinySnapshot.zip(Map.of("cities500.txt", TinySnapshot.utf8(TinySnapshot.lines(rows))))));
        World.City c = World.build(SourceData.read(f), Profile.FULL, Exclusions.DEFAULT).cities().getFirst();
        assertEquals(0, d("48.1").compareTo(c.latitude()));
        assertEquals(0, d("11.5").compareTo(c.longitude()));

        rows.set(0, rows.getFirst().replace("\t48.1000000\t", "\t48.12345678\t"));
        Path g = TinySnapshot.with(tmp, Map.of("geonames/cities500.zip",
                TinySnapshot.zip(Map.of("cities500.txt", TinySnapshot.utf8(TinySnapshot.lines(rows))))));
        SourceData src = SourceData.read(g);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> World.build(src, Profile.FULL, Exclusions.DEFAULT));
        assertTrue(e.getMessage().contains("1001"), e.getMessage());
    }

    @Test
    @DisplayName("Снимок без нужного файла (wikidata/languages.csv) — ошибка с именем файла, а не тихо пустые названия")
    void missingFileIsAnError(@TempDir Path tmp) throws IOException {
        java.util.Map<String, byte[]> drop = new java.util.HashMap<>();
        drop.put("wikidata/languages.csv", null);
        IOException e = assertThrows(IOException.class, () -> SourceData.read(TinySnapshot.with(tmp, drop)));
        assertTrue(e.getMessage().contains("wikidata/languages.csv"), e.getMessage());
    }
}
