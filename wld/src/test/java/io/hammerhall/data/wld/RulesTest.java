package io.hammerhall.data.wld;

import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.wld.build.Rules;
import io.hammerhall.data.wld.source.Cldr;
import io.hammerhall.data.wld.source.SourceData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Правила сборки по одному — на маленьких значениях, без снимка. */
class RulesTest {
    static SourceData.Place p(long id, String name, String alternates, String code, long population, String capital) {
        return new SourceData.Place(id, name, code, "QM", "01", population, "1", "2", "UTC",
                Rules.namesMatch(capital, name, name, alternates), true);
    }

    // ---------------------------------------------------------------- столица

    @Test
    @DisplayName("Столица, п. 1: PPLC с названием столицы — по name, asciiname или alternatenames")
    void capitalPplcByName() {
        String cap = "Alpha";
        List<SourceData.Place> rows = List.of(p(1, "Other", "", "PPLC", 900, cap), p(2, "Alpha-Stadt", "Alpha,A", "PPLC", 10, cap),
                p(3, "Alpha", "", "PPL", 5_000_000, cap));
        assertEquals(2L, Rules.capital(cap, rows), "PPLC с названием — раньше единственной PPLC и раньше простого PPL");
    }

    @Test
    @DisplayName("Столица, п. 1: несколько PPLC с названием — больше население, затем меньше geonameid")
    void capitalSeveralNamedPplc() {
        String cap = "Alpha";
        assertEquals(7L, Rules.capital(cap, List.of(p(9, "Alpha", "", "PPLC", 100, cap), p(7, "Alpha", "", "PPLC", 100, cap),
                p(8, "Alpha", "", "PPLC", 50, cap))));
    }

    @Test
    @DisplayName("Столица, п. 2: нет PPLC с названием — единственная PPLC страны")
    void capitalSinglePplc() {
        String cap = "Beta";
        assertEquals(1L, Rules.capital(cap, List.of(p(1, "Betaville", "", "PPLC", 900, cap), p(2, "Beta", "", "PPL", 50_000, cap))));
    }

    @Test
    @DisplayName("Столица, п. 3: PPLC нет или их несколько — населённый пункт с названием, больше население, затем меньше id")
    void capitalByNameAndPopulation() {
        String cap = "Gamma";
        List<SourceData.Place> rows = List.of(p(5, "Gammaport", "Gamma", "PPLA", 20_000, cap), p(4, "Gamma", "", "PPL", 20_000, cap),
                p(3, "Gamma Heights", "Gamma", "PPL", 10_000, cap), p(1, "Ville A", "", "PPLC", 1, cap), p(2, "Ville B", "", "PPLC", 1, cap));
        assertEquals(4L, Rules.capital(cap, rows));
        assertEquals(5L, Rules.capital(cap, List.of(p(5, "Gammaport", "Gamma", "PPLA", 20_001, cap), p(4, "Gamma", "", "PPL", 20_000, cap))));
    }

    @Test
    @DisplayName("Столица: пустой Capital или ничего не нашлось — пусто; название сравнивается точно")
    void capitalNone() {
        assertNull(Rules.capital("", List.of(p(1, "X", "", "PPLC", 1, ""))));
        assertNull(Rules.capital(null, List.of(p(1, "X", "", "PPLC", 1, null))));
        assertNull(Rules.capital("Delta", List.of(p(1, "delta", "", "PPL", 1, "Delta"), p(2, "Deltaport", "", "PPL", 1, "Delta"))));
        assertTrue(Rules.namesMatch("Alpha", "X", "Y", "a,Alpha,b"));
        assertFalse(Rules.namesMatch("Alpha", "X", "Y", "Alpha b,alpha"));
        assertFalse(Rules.namesMatch("", "", "", ""));
    }

    // ---------------------------------------------------------------- local_name, регион, язык

    static Cldr.LanguagePopulation lp(String code, String pct, String status) {
        return new Cldr.LanguagePopulation(code, new BigDecimal(pct), status);
    }

    @Test
    @DisplayName("local_name: официальные по убыванию доли, при равенстве — по коду; первая локаль с названием страны")
    void localName() {
        Map<String, Map<String, String>> loc = Map.of("de", Map.of("QM", "Kumland"), "fr", Map.of("QM", "Qumlande"),
                "it", Map.of("QM", "Qumlandia"), "en", Map.of("QM", "Qumland"));
        List<Cldr.LanguagePopulation> langs = List.of(lp("en", "95", null), lp("it", "40", "de_facto_official"),
                lp("fr", "40", "official"), lp("xx", "41", "official_regional"));
        assertEquals("Qumlande", Rules.localName("QM", langs, loc), "fr и it — по 40 %: fr раньше по коду; en — не официальный");
        assertEquals("Kumland", Rules.localName("QM", List.of(lp("fr", "40", "official"), lp("de", "60", "official")), loc));
        assertEquals(List.of("de", "fr"), Rules.officialByShare(List.of(lp("fr", "40", "official"), lp("de", "60", "official"),
                lp("en", "99", "official_minority"))).stream().map(Cldr.LanguagePopulation::code).toList());
    }

    @Test
    @DisplayName("local_name: нет локали xx-Yyyy — локаль xx; локаль есть, а названия нет — следующий язык; ничего — пусто")
    void localNameFallback() {
        Map<String, Map<String, String>> loc = Map.of("sr", Map.of("QN", "Nordija"), "zh-Hant", Map.of("QM", "x"),
                "zh", Map.of("QN", "不用"), "en", Map.of("QN", "Nordia"));
        assertEquals("Nordija", Rules.localName("QN", List.of(lp("sr_Latn", "80", "official")), loc));
        assertEquals("Nordia", Rules.localName("QN", List.of(lp("zh_Hant", "70", "official"), lp("en", "30", "official")), loc),
                "локаль zh-Hant есть, но без QN — к zh не идём, берём следующий язык");
        assertNull(Rules.localName("QN", List.of(lp("xx", "70", "official")), loc));
        assertNull(Rules.localName("QN", List.of(), loc));
    }

    @Test
    @DisplayName("Регион ООН: числовая запись, не группировка и не устаревшая; иначе — буквенная; не одна — пусто")
    void region() {
        List<Cldr.Container> c = List.of(new Cldr.Container("001", List.of("150"), false, false),
                new Cldr.Container("155", List.of("QM", "QX"), false, false),
                new Cldr.Container("155-status-deprecated", List.of("QS"), false, false),
                new Cldr.Container("172", List.of("QN"), false, true),
                new Cldr.Container("030", List.of("QN"), false, false),
                new Cldr.Container("419", List.of("QP"), true, false),
                new Cldr.Container("EU", List.of("QM"), true, false),
                new Cldr.Container("QO", List.of("QT", "QM"), false, false),
                new Cldr.Container("011", List.of("QX"), false, false));
        assertEquals("155", Rules.region("QM", c));
        assertEquals("030", Rules.region("QN", c));
        assertEquals("QO", Rules.region("QT", c));
        assertNull(Rules.region("QP", c), "только группировка");
        assertNull(Rules.region("QS", c), "только устаревшая запись");
        assertNull(Rules.region("QX", c), "две числовые — пусто");
    }

    @Test
    @DisplayName("Название языка: по коду; иначе «базовый (письменность)»; иначе метка Wikidata (с письменностью); нет — пусто")
    void languageName() {
        Map<String, String> lang = Map.of("sr", "Serbian", "zh-Hant", "Traditional Chinese", "zh", "Chinese");
        Map<String, String> scr = Map.of("Latn", "Latin", "Hant", "Traditional");
        Map<String, String> wd = Map.of("abq", "Abaza", "sr", "Serbo");
        assertEquals("Traditional Chinese", Rules.languageName("zh-Hant", lang, scr, wd));
        assertEquals("Serbian (Latin)", Rules.languageName("sr-Latn", lang, scr, wd));
        assertEquals("Abaza", Rules.languageName("abq", lang, scr, wd));
        assertEquals("Abaza (Latin)", Rules.languageName("abq-Latn", lang, scr, wd));
        assertEquals("Serbo", Rules.languageName("sr", Map.of(), scr, wd), "в CLDR нет — Wikidata");
        assertNull(Rules.languageName("abq-Cyrl", lang, scr, wd), "письменности нет в CLDR — пусто");
        assertNull(Rules.languageName("xx", lang, scr, wd));
        assertEquals("zh-Hant", new Cldr.LanguagePopulation("zh_Hant", BigDecimal.ONE, "official").tag());
    }

    @Test
    @DisplayName("Соседство: пара в обе стороны, если перечисляет хотя бы одна сторона и обе страны взяты")
    void borders() {
        Map<String, List<String>> nb = Map.of("QM", List.of("QN", "EH", "QM"), "QR", List.of("QM"), "EH", List.of("QM"),
                "QN", List.of("QS"), "QS", List.of("QN"));
        assertEquals(Set.of(List.of("QM", "QN"), List.of("QN", "QM"), List.of("QM", "QR"), List.of("QR", "QM")),
                Rules.borders(nb, Set.of("QM", "QN", "QR")));
        assertEquals(List.of(List.of("QM", "QN"), List.of("QM", "QR"), List.of("QN", "QM"), List.of("QR", "QM")),
                List.copyOf(Rules.borders(nb, Set.of("QM", "QN", "QR"))), "по порядку");
    }

    // ---------------------------------------------------------------- числа

    @Test
    @DisplayName("ВНП: доллары → миллионы, 2 знака, половина — к чётному; продолжительность жизни — 3 знака так же")
    void rounding() {
        assertEquals("1234.56", Rules.millions(new BigDecimal("1234565000")).toPlainString());
        assertEquals("1234.58", Rules.millions(new BigDecimal("1234575000")).toPlainString());
        assertEquals("2345.68", Rules.millions(new BigDecimal("2345675000.5")).toPlainString());
        assertEquals("500000.00", Rules.millions(new BigDecimal("5e+11")).toPlainString());
        assertEquals("72.124", Rules.lifeExpectancy(new BigDecimal("72.1235")).toPlainString());
        assertEquals("72.124", Rules.lifeExpectancy(new BigDecimal("72.1245")).toPlainString());
        assertEquals("65.500", Rules.lifeExpectancy(new BigDecimal("65.5")).toPlainString());
        assertNull(Rules.millions(null));
    }

    @Test
    @DisplayName("Числа как есть: запись не теряет знаков, иначе — ошибка; целое — только целое")
    void exactNumbers() {
        assertEquals("17100000", Rules.exact(new BigDecimal("1.71E7"), 4, "x").toPlainString());
        assertEquals("41285", Rules.exact(new BigDecimal("41285.0"), 4, "x").toPlainString());
        assertEquals("0.0062", Rules.exact(new BigDecimal("0.0062"), 4, "x").toPlainString());
        assertThrows(IllegalStateException.class, () -> Rules.exact(new BigDecimal("0.00001"), 4, "x"));
        assertEquals(3_100_000L, Rules.integral(new BigDecimal("3100000.0"), "x"));
        assertThrows(IllegalStateException.class, () -> Rules.integral(new BigDecimal("3.5"), "x"));
    }

    // ---------------------------------------------------------------- города

    @Test
    @DisplayName("Пороги профилей: full — 1 000, medium — 15 000, small — 100 000")
    void thresholds() {
        assertEquals(1_000, Rules.minPopulation(Profile.FULL));
        assertEquals(15_000, Rules.minPopulation(Profile.MEDIUM));
        assertEquals(100_000, Rules.minPopulation(Profile.SMALL));
    }

    @Test
    @DisplayName("Коды класса P: PPLX, PPLH, PPLQ, PPLW, PPLCH — не города, остальные — города")
    void featureCodes() {
        for (String c : List.of("PPLX", "PPLH", "PPLQ", "PPLW", "PPLCH")) assertFalse(Rules.cityCode(c), c);
        for (String c : List.of("PPL", "PPLA", "PPLA2", "PPLC", "PPLG", "PPLL", "PPLS")) assertTrue(Rules.cityCode(c), c);
    }

    @Test
    @DisplayName("Часть света — по коду GeoNames, неизвестный код — ошибка")
    void continents() {
        assertEquals("North America", Rules.continent("NA"));
        assertEquals("Antarctica", Rules.continent("AN"));
        assertEquals(7, Rules.CONTINENTS.size());
        assertThrows(IllegalArgumentException.class, () -> Rules.continent("XX"));
    }

    @Test
    @DisplayName("Списки исключений: страны — ISO2, регионы — CC.ADM1, города — geonameid")
    void exclusionLists() {
        Exclusions ex = new Exclusions(List.of("QM"), List.of("QM.09"), List.of(1002L));
        assertTrue(ex.country("QM"));
        assertTrue(ex.region("QM", "09"));
        assertFalse(ex.region("QM", ""));
        assertFalse(ex.region("QN", "09"));
        assertTrue(ex.city(1002));
        assertThrows(IllegalArgumentException.class, () -> new Exclusions(List.of("QMA"), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Exclusions(List.of(), List.of("QM-09"), List.of()));
        assertEquals(List.of("EH", "PS", "XK", "TW", "FK", "GI"), Exclusions.DEFAULT.countries());
    }
}
