package io.hammerhall.data.mov;

import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.mov.build.Rules;
import io.hammerhall.data.mov.source.SourceData;
import io.hammerhall.data.mov.source.SourceData.DateStmt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Правила фильма, ролей, кодов, долей и профилей — по одному, на маленьких значениях, без снимка. */
class RulesTest {
    static DateStmt d(int y, int m, int day, int prec, boolean pref) {
        return new DateStmt(y, m, day, prec, pref);
    }

    static String[] labels(String... langValue) {
        String[] a = new String[11];
        for (int i = 0; i < langValue.length; i += 2) a[io.hammerhall.data.mov.source.Wikidata.lang(langValue[i])] = langValue[i + 1];
        return a;
    }

    // ---------------------------------------------------------------- отбор

    @Test
    @DisplayName("Классы: исключённый класс (список или included=0) — не берём; нужен хоть один включённый")
    void classes() {
        Map<Integer, Boolean> snap = Map.of(11424, true, 777, false, 888, true);
        assertTrue(Rules.classesOk(new int[]{11424}, snap));
        assertTrue(Rules.classesOk(new int[]{888}, Map.of(888, true)), "включённый — по classes.csv");
        assertFalse(Rules.classesOk(new int[]{11424, 24856}, snap), "серия фильмов — из списка исключённых");
        assertFalse(Rules.classesOk(new int[]{11424, 193977}, Map.of()), "клип");
        assertFalse(Rules.classesOk(new int[]{11424, 777}, snap), "included = 0 в снимке");
        assertFalse(Rules.classesOk(new int[]{5}, Map.of()), "ни одного класса фильма");
        assertFalse(Rules.classesOk(new int[0], Map.of()));
    }

    @Test
    @DisplayName("Год: лучший ранг (preferred, если есть), из него — самая ранняя с точностью ≥ года; дата — до дня")
    void releasedPreferred() {
        List<DateStmt> ds = List.of(d(1998, 1, 1, 9, false), d(2001, 5, 5, 11, true), d(1999, 3, 31, 11, true));
        assertEquals(new Rules.Released(1999, LocalDate.of(1999, 3, 31)), Rules.released(ds, 2026));
        List<DateStmt> worse = List.of(d(1960, 1, 1, 8, false), d(1962, 1, 1, 9, false), d(1961, 6, 15, 11, false));
        assertEquals(new Rules.Released(1961, LocalDate.of(1961, 6, 15)), Rules.released(worse, 2026),
                "точность десятилетия — не в счёт");
        assertNull(Rules.released(List.of(d(1960, 1, 1, 8, true), d(1961, 6, 15, 11, false)), 2026),
                "preferred есть, но только с точностью десятилетия: normal не смотрим — года нет");
    }

    @Test
    @DisplayName("Год без дня: точность месяц или год — даты выхода нет; год — от самой ранней, а дата — своя")
    void releasedYearOnly() {
        assertEquals(new Rules.Released(1950, null), Rules.released(List.of(d(1950, 5, 1, 10, false)), 2026));
        assertEquals(new Rules.Released(1950, LocalDate.of(1951, 2, 3)),
                Rules.released(List.of(d(1950, 1, 1, 9, false), d(1951, 2, 3, 11, false)), 2026));
        assertEquals(new Rules.Released(1950, null), Rules.released(List.of(d(1950, 0, 0, 11, false)), 2026),
                "день 0 — не дата по календарю");
    }

    @Test
    @DisplayName("Год вне 1874…год снимка, только точность хуже года, нет дат — фильм не берётся")
    void releasedOutOfRange() {
        assertNull(Rules.released(List.of(d(1873, 1, 1, 9, false)), 2026));
        assertEquals(1874, Rules.released(List.of(d(1874, 12, 9, 11, false)), 2026).year());
        assertNull(Rules.released(List.of(d(2027, 1, 1, 9, false)), 2026));
        assertEquals(2026, Rules.released(List.of(d(2026, 1, 1, 9, false)), 2026).year());
        assertNull(Rules.released(List.of(d(-1, 1, 1, 9, false)), 2026));
        assertNull(Rules.released(List.of(d(1990, 1, 1, 8, false)), 2026));
        assertNull(Rules.released(List.of(), 2026));
        assertNull(Rules.released(null, 2026));
        assertNull(Rules.released(List.of(d(1870, 1, 1, 9, false), d(1950, 1, 1, 9, false)), 2026),
                "год — самой ранней даты: 1870 вне окна, другие не спасают");
    }

    @Test
    @DisplayName("Название: en → mul → P1476 (сначала en, затем по языку) → первый запасной язык → ru; ru — и отдельно")
    void title() {
        List<SourceData.Title> t = List.of(new SourceData.Title("fr", "Le Récit"), new SourceData.Title("en", "The Story"),
                new SourceData.Title("de", "Die Geschichte"));
        assertEquals("E", Rules.title(labels("en", "E", "mul", "M"), t));
        assertEquals("M", Rules.title(labels("mul", "M", "de", "D"), t));
        assertEquals("The Story", Rules.title(labels("ru", "Р", "de", "D"), t));
        assertEquals("Die Geschichte", Rules.title(null, List.of(t.get(0), t.get(2))), "без en — по языку: de раньше fr");
        assertEquals("D", Rules.title(labels("ru", "Р", "fr", "F", "de", "D"), List.of()), "de раньше fr в LABEL_LANGS");
        assertEquals("Р", Rules.title(labels("ru", "Р"), null), "только ru — название из ru");
        assertNull(Rules.title(labels(), null), "меток нет — названия нет");
        assertEquals("Р", Rules.titleRu(labels("ru", "Р")));
        assertNull(Rules.titleRu(null));
    }

    @Test
    @DisplayName("Хронометраж: P2047 / 60, половина — к чётному по точной записи; из значений в 1…1 500 — наименьшее")
    void runtime() {
        assertEquals(90, Rules.minutes(new BigDecimal("5400")));
        assertEquals(40, Rules.minutes(new BigDecimal("2430")), "40,5 → 40");
        assertEquals(42, Rules.minutes(new BigDecimal("2490")), "41,5 → 42");
        assertEquals(88, Rules.minutes(new BigDecimal("5310")), "88,5 → 88");
        assertEquals(1, Rules.minutes(new BigDecimal("30.0001")));
        assertNull(Rules.minutes(new BigDecimal("30")), "0,5 → 0 — вне");
        assertEquals(1500, Rules.minutes(new BigDecimal("90000.0")));
        assertNull(Rules.minutes(new BigDecimal("90060")), "1 501 — вне");
        assertEquals(109, Rules.runtime(List.of(new BigDecimal("30"), new BigDecimal("6540"))), "30 с — вне, берём 109");
        assertEquals(90, Rules.runtime(List.of(new BigDecimal("5400"), new BigDecimal("7140"))), "наименьшее");
        assertNull(Rules.runtime(List.of(new BigDecimal("20"), new BigDecimal("90060"))), "годных нет");
        assertNull(Rules.runtime(List.of()));
        assertNull(Rules.runtime(null));
    }

    @Test
    @DisplayName("Дубли IMDb: остаётся больший sitelinks, при равенстве — меньший id; фильм с двумя ID — по обоим")
    void imdb() {
        Map<Integer, SourceData.Signals> s = Map.of(
                1, new SourceData.Signals(10, 0, 0, List.of("tt1")),
                2, new SourceData.Signals(50, 0, 0, List.of("tt1")),
                3, new SourceData.Signals(7, 0, 0, List.of("tt2")),
                4, new SourceData.Signals(7, 0, 0, List.of("tt2", "tt3")),
                5, new SourceData.Signals(9, 0, 0, List.of("tt3")),
                6, new SourceData.Signals(1, 0, 0, List.of()));
        assertEquals(Set.of(1, 4), Rules.imdbDuplicates(Set.of(1, 2, 3, 4, 5, 6), s),
                "tt1: 2 больше; tt2: 3 и 4 равны — 3; tt3: 5 больше 4");
        assertEquals(Set.of(), Rules.imdbDuplicates(Set.of(1, 3, 6), s), "дубль считается только среди кандидатов");
    }

    // ---------------------------------------------------------------- роли

    @Test
    @DisplayName("Роль: P4633, иначе метка en → mul персонажа, иначе пусто; знаки не для TSV — пусто, без запасного")
    void role() {
        String[] ch = {"Captain", "Капитан-mul"};
        assertEquals("Narrator", Rules.role("Narrator", ch));
        assertEquals("Captain", Rules.role(null, ch));
        assertEquals("Капитан-mul", Rules.role("", new String[]{null, "Капитан-mul"}));
        assertNull(Rules.role(null, null));
        assertNull(Rules.role("Bad\tName", ch), "своё имя роли негодно — роль пустая, метка персонажа не подставляется");
        assertNull(Rules.role("a\\b", null));
    }

    @Test
    @DisplayName("Роли пары: без повторов, по байтам, через « / »; ни одной — пусто")
    void roles() {
        assertEquals("Captain Rook / Narrator", Rules.roles(Arrays.asList("Narrator", null, "Captain Rook", "Narrator")));
        assertEquals("Ghost", Rules.roles(List.of("Ghost", "Ghost")));
        assertNull(Rules.roles(Arrays.asList(null, null)));
        assertEquals("Zed / Ärger", Rules.roles(List.of("Ärger", "Zed")), "по байтам UTF-8");
    }

    // ---------------------------------------------------------------- коды

    @Test
    @DisplayName("Страна: ISO3 (несколько — меньший); исключённый код по ISO3 или ISO2 — строки нет; без ISO3 — нет")
    void countries() {
        Exclusions ex = Exclusions.DEFAULT;
        assertEquals("USA", Rules.countryCode(new SourceData.Codes(List.of("US"), List.of("USA")), ex));
        assertEquals("QXA", Rules.countryCode(new SourceData.Codes(List.of("QX"), List.of("QXA", "QXB")), ex));
        assertNull(Rules.countryCode(new SourceData.Codes(List.of("EH"), List.of("ESH")), ex));
        assertNull(Rules.countryCode(new SourceData.Codes(List.of(), List.of("GIB")), ex), "по ISO3");
        assertNull(Rules.countryCode(new SourceData.Codes(List.of("TW"), List.of("QQQ")), ex), "по ISO2");
        assertNull(Rules.countryCode(new SourceData.Codes(List.of("QZ"), List.of()), ex));
        assertNull(Rules.countryCode(new SourceData.Codes(List.of(), List.of("usa")), ex), "не три заглавные — не код");
        assertNull(Rules.countryCode(null, ex));
        Exclusions none = new Exclusions(List.of(), List.of());
        assertEquals("ESH", Rules.countryCode(new SourceData.Codes(List.of("EH"), List.of("ESH")), none), "список подменён");
    }

    @Test
    @DisplayName("Язык: ISO 639-1, иначе ISO 639-3; нет — строки нет")
    void languages() {
        assertEquals("en", Rules.languageCode(new SourceData.Codes(List.of("en"), List.of("eng"))));
        assertEquals("qya", Rules.languageCode(new SourceData.Codes(List.of(), List.of("qya"))));
        assertNull(Rules.languageCode(new SourceData.Codes(List.of(), List.of())));
        assertNull(Rules.languageCode(null));
    }

    @Test
    @DisplayName("Списки исключений: ISO3 и ISO2 парами, по шаблону; списки выпуска — как у набора «Мир»")
    void exclusionLists() {
        assertEquals(List.of("ESH", "PSE", "XKX", "TWN", "FLK", "GIB"), Exclusions.DEFAULT.countries3());
        assertEquals(List.of("EH", "PS", "XK", "TW", "FK", "GI"), Exclusions.DEFAULT.countries2());
        assertThrows(IllegalArgumentException.class, () -> new Exclusions(List.of("ES"), List.of("ES")));
        assertThrows(IllegalArgumentException.class, () -> new Exclusions(List.of("ESH"), List.of()));
    }

    // ---------------------------------------------------------------- доли и профили

    @Test
    @DisplayName("Доля жанра режиссёра: 4 знака, половина — к чётному")
    void prob() {
        assertEquals(new BigDecimal("0.6667"), Rules.prob(2, 3));
        assertEquals(new BigDecimal("0.3333"), Rules.prob(1, 3));
        assertEquals(new BigDecimal("1.0000"), Rules.prob(4, 4));
        assertEquals(new BigDecimal("0.0002"), Rules.prob(1, 4000), "0,00025 → 0,0002");
        assertEquals(new BigDecimal("0.0004"), Rules.prob(7, 20000), "0,00035 → 0,0004");
        assertThrows(IllegalArgumentException.class, () -> Rules.prob(1, 0));
        assertThrows(IllegalArgumentException.class, () -> Rules.prob(3, 2));
    }

    @Test
    @DisplayName("Профили: full — все, medium — 10 %, small — 1 %, округление вверх")
    void profileSize() {
        assertEquals(12, Rules.profileSize(12, Profile.FULL));
        assertEquals(2, Rules.profileSize(12, Profile.MEDIUM));
        assertEquals(1, Rules.profileSize(12, Profile.SMALL));
        assertEquals(4_001, Rules.profileSize(400_001, Profile.SMALL));
        assertEquals(40_000, Rules.profileSize(400_000, Profile.MEDIUM));
        assertEquals(0, Rules.profileSize(0, Profile.SMALL));
    }

    // ---------------------------------------------------------------- снимок v2: год и название

    @Test
    @DisplayName("Год с запасными путями: P577, иначе P1191, P571, P2754 — тем же правилом; дата — только из P577")
    void yearFallback() {
        Map<String, List<DateStmt>> extra = Map.of(
                "P1191", List.of(d(1960, 5, 5, 11, true), d(1955, 1, 1, 9, false)),
                "P571", List.of(d(1948, 1, 1, 9, false)),
                "P2754", List.of(d(1933, 1, 1, 9, false)));
        assertEquals(new Rules.Year(1999, LocalDate.of(1999, 3, 31), Rules.YearPath.P577),
                Rules.year(List.of(d(1999, 3, 31, 11, false)), extra, 2026));
        assertEquals(new Rules.Year(1960, null, Rules.YearPath.P1191), Rules.year(null, extra, 2026),
                "P1191: лучший ранг — preferred; дата выхода — не из P1191");
        assertEquals(new Rules.Year(1960, null, Rules.YearPath.P1191), Rules.year(List.of(d(1870, 1, 1, 9, false)), extra, 2026),
                "P577 вне окна — года по P577 нет: дальше по очереди");
        assertEquals(new Rules.Year(1948, null, Rules.YearPath.P571),
                Rules.year(List.of(), Map.of("P1191", List.of(d(1950, 1, 1, 8, false)), "P571", extra.get("P571")), 2026),
                "P1191 только с точностью десятилетия");
        assertEquals(new Rules.Year(1933, null, Rules.YearPath.P2754),
                Rules.year(null, Map.of("P571", List.of(d(2030, 1, 1, 9, false)), "P2754", extra.get("P2754")), 2026));
        assertNull(Rules.year(null, Map.of("P571", List.of(d(1850, 1, 1, 9, false))), 2026));
        assertNull(Rules.year(null, null, 2026));
        assertNull(Rules.year(null, Map.of("P999", List.of(d(1990, 1, 1, 9, false))), 2026), "другие свойства — не в счёт");
    }

    @Test
    @DisplayName("Название с путём: en → mul → P1476 → запасной язык → метка на любом языке (по коду языка, первая годная)")
    void titled() {
        List<SourceData.Title> all = List.of(new SourceData.Title("ja", "J"), new SourceData.Title("cs", "Č"),
                new SourceData.Title("cs", "A"), new SourceData.Title("ar", "Bad\tTab"));
        assertEquals(new Rules.Titled("E", Rules.TitlePath.LABEL_EN), Rules.titled(labels("en", "E"), null, all));
        assertEquals(new Rules.Titled("M", Rules.TitlePath.LABEL_MUL), Rules.titled(labels("mul", "M"), null, all));
        assertEquals(new Rules.Titled("T", Rules.TitlePath.P1476), Rules.titled(null, List.of(new SourceData.Title("fr", "T")), all));
        assertEquals(new Rules.Titled("D", Rules.TitlePath.LABEL_LATIN), Rules.titled(labels("de", "D"), null, all));
        assertEquals(new Rules.Titled("A", Rules.TitlePath.LABEL_ANY), Rules.titled(null, null, all),
                "ar — негодная; cs — первый по коду; из двух меток cs — меньшая по байтам");
        assertEquals(new Rules.Titled("Р", Rules.TitlePath.LABEL_RU), Rules.titled(labels("ru", "Р"), List.of(),
                List.of(new SourceData.Title("ja", "J"))), "ru — раньше меток на любом языке");
        assertEquals(new Rules.Titled("D", Rules.TitlePath.LABEL_LATIN), Rules.titled(labels("ru", "Р", "de", "D"), null, null),
                "запасной латинский — раньше ru");
        assertEquals(new Rules.Titled("T", Rules.TitlePath.P1476), Rules.titled(labels("ru", "Р"),
                List.of(new SourceData.Title("fr", "T")), null), "P1476 — раньше ru");
        assertNull(Rules.titled(labels(), null, List.of()));
        assertEquals("E", Rules.title(labels("en", "E"), null), "прежняя цепочка — та же");
    }

    @Test
    @DisplayName("Названия по языкам: метки LABEL_LANGS и любые годные, одна на язык (меньшая), языки по байтам")
    void titlesByLang() {
        Map<String, String> t = Rules.titles(labels("ru", "Р", "en", "E", "de", "D"), List.of(new SourceData.Title("zh-hant", "Z"),
                new SourceData.Title("cs", "Č"), new SourceData.Title("cs", "A"), new SourceData.Title("ar", "Bad\tTab"),
                new SourceData.Title("", "Пусто")));
        assertEquals(List.of("cs", "de", "en", "ru", "zh-hant"), List.copyOf(t.keySet()));
        assertEquals("A", t.get("cs"));
        assertEquals(Map.of(), Rules.titles(null, null));
    }

    // ---------------------------------------------------------------- снимок v2: деньги

    static SourceData.MoneyStmt money(boolean box, String amount, int unit, boolean pref) {
        return new SourceData.MoneyStmt(box, new BigDecimal(amount), unit, pref);
    }

    @Test
    @DisplayName("Бюджет и сборы: только доллары США; лучший ранг среди них; наибольшая; half-even; ≤ 0 — пусто")
    void usd() {
        List<SourceData.MoneyStmt> m = List.of(money(false, "1000000", Rules.USD, true), money(false, "5000000", Rules.USD, false),
                money(false, "9000000", 4916, true), money(true, "2500000.5", Rules.USD, false), money(true, "2400000", Rules.USD, false));
        assertEquals(1_000_000L, Rules.usd(m, false));
        assertEquals(2_500_000L, Rules.usd(m, true), "2 500 000,5 → 2 500 000");
        assertEquals(2_000_000L, Rules.usd(List.of(money(false, "3000000", 4916, true), money(false, "2000000", Rules.USD, false)), false),
                "preferred — только в другой валюте: из долларов — normal");
        assertNull(Rules.usd(List.of(money(false, "0", Rules.USD, true), money(false, "100", Rules.USD, false)), false),
                "лучший ранг — 0: пусто");
        assertNull(Rules.usd(List.of(money(false, "0.4", Rules.USD, false)), false));
        assertEquals(4L, Rules.usd(List.of(money(true, "3.5", Rules.USD, false)), true));
        assertEquals(2L, Rules.usd(List.of(money(true, "2.5", Rules.USD, false)), true));
        assertEquals(1500L, Rules.usd(List.of(money(false, "1.5E+3", Rules.USD, false)), false));
        assertNull(Rules.usd(List.of(money(false, "-5", Rules.USD, false)), false));
        assertNull(Rules.usd(List.of(money(false, "1E+30", Rules.USD, false)), false), "больше bigint");
        assertNull(Rules.usd(List.of(money(true, "7000000", 4916, false)), true));
        assertNull(Rules.usd(List.of(money(true, "7000000", Rules.USD, false)), false), "сборы — не бюджет");
        assertNull(Rules.usd(null, false));
    }

    // ---------------------------------------------------------------- снимок v2: люди

    @Test
    @DisplayName("Даты жизни: лучший ранг; дата — точность дня; год — точность ≥ года; 1700…год снимка; смерть — при рождении не позже")
    void life() {
        assertEquals(new Rules.Life(LocalDate.of(1946, 12, 18), 1946, null, null),
                Rules.life(new SourceData.LifeDates(List.of(d(1946, 12, 18, 11, false)), List.of()), 2026));
        assertEquals(new Rules.Life(null, 1970, null, null),
                Rules.life(new SourceData.LifeDates(List.of(d(1970, 1, 1, 9, true), d(1969, 5, 5, 11, false)), List.of()), 2026),
                "preferred — только с точностью года");
        assertEquals(Rules.Life.NONE, Rules.life(new SourceData.LifeDates(List.of(d(1650, 1, 1, 11, false)),
                List.of(d(1720, 1, 1, 9, false))), 2026), "до 1700 — пусто, и смерти без рождения нет");
        assertEquals(new Rules.Life(LocalDate.of(1950, 1, 1), 1950, null, null), Rules.life(new SourceData.LifeDates(
                List.of(d(1950, 1, 1, 11, false)), List.of(d(1940, 1, 1, 11, false))), 2026), "смерть раньше рождения");
        assertEquals(new Rules.Life(null, 1980, null, 2020), Rules.life(new SourceData.LifeDates(
                List.of(d(1980, 6, 1, 10, false), d(1979, 1, 1, 8, false)), List.of(d(2020, 2, 30, 11, false))), 2026),
                "30 февраля — не дата, год — есть");
        assertEquals(new Rules.Life(LocalDate.of(1975, 7, 7), 1975, null, 1975), Rules.life(new SourceData.LifeDates(
                List.of(d(1975, 7, 7, 11, false)), List.of(d(1975, 7, 6, 11, false))), 2026), "дата и год — каждое своим сравнением");
        assertEquals(Rules.Life.NONE, Rules.life(new SourceData.LifeDates(List.of(d(2030, 1, 1, 9, false)), List.of()), 2026));
        assertEquals(new Rules.Life(LocalDate.of(1700, 1, 1), 1700, LocalDate.of(2026, 12, 31), 2026), Rules.life(
                new SourceData.LifeDates(List.of(d(1700, 1, 1, 11, false)), List.of(d(2026, 12, 31, 11, false))), 2026), "границы");
        assertEquals(new Rules.Life(LocalDate.of(1901, 2, 3), 1900, null, null), Rules.life(new SourceData.LifeDates(
                List.of(d(1900, 1, 1, 9, false), d(1901, 2, 3, 11, false)), List.of()), 2026), "год — самой ранней, дата — своя");
        assertEquals(Rules.Life.NONE, Rules.life(null, 2026));
        assertEquals(new Rules.Event(null, null), Rules.event(List.of(d(-50, 1, 1, 11, false)), 2026), "до н. э.");
    }

    // ---------------------------------------------------------------- снимок v2: справочники и связи

    @Test
    @DisplayName("Название студии, серии, награды: en → mul; ru — отдельно; нет — не берём")
    void itemName() {
        assertEquals("E", Rules.itemName(new String[]{"E", "M", "Р"}));
        assertEquals("M", Rules.itemName(new String[]{null, "M", "Р"}));
        assertNull(Rules.itemName(new String[]{null, null, "Р"}));
        assertNull(Rules.itemName(null));
        assertEquals("Р", Rules.itemNameRu(new String[]{"E", null, "Р"}));
        assertNull(Rules.itemNameRu(null));
    }

    @Test
    @DisplayName("Номер в серии: наименьшее целое (только цифры) от 1 до 10 000; иначе пусто")
    void ordinal() {
        assertEquals(1, Rules.ordinal(Arrays.asList("2", "1", null)));
        assertEquals(10_000, Rules.ordinal(List.of("10000", "10001")));
        assertEquals(5, Rules.ordinal(List.of("abc", "5", "0")));
        assertEquals(7, Rules.ordinal(List.of("007")));
        assertNull(Rules.ordinal(List.of("0", "-1", "3a", "1.5", "+2", "99999999999")));
        assertNull(Rules.ordinal(Arrays.asList((String) null)));
        assertNull(Rules.ordinal(List.of()));
    }

    @Test
    @DisplayName("Год награды: самый ранний год среди дат с точностью ≥ года; дат нет — пусто")
    void awardYear() {
        assertEquals(1999, Rules.awardYear(List.of(d(2000, 3, 26, 11, false), d(1999, 1, 1, 9, false))));
        assertNull(Rules.awardYear(Arrays.asList(null, d(2001, 1, 1, 8, false))));
        assertEquals(2002, Rules.awardYear(Arrays.asList(d(2002, 1, 1, 9, false), null)));
        assertNull(Rules.awardYear(List.of()));
    }

    @Test
    @DisplayName("Дата выхода в стране: точность ровно день, дата по календарю, год 1…9999")
    void releaseDay() {
        assertEquals(LocalDate.of(1999, 4, 10), Rules.releaseDay(d(1999, 4, 10, 11, false)));
        assertNull(Rules.releaseDay(d(1999, 2, 1, 10, false)));
        assertNull(Rules.releaseDay(d(1999, 2, 30, 11, false)));
        assertNull(Rules.releaseDay(d(0, 1, 1, 11, false)));
        assertNull(Rules.releaseDay(d(10_000, 1, 1, 11, false)));
        assertNull(Rules.releaseDay(null));
    }

    @Test
    @DisplayName("Должности группы и роли компаний — по порядку свойств снимка")
    void jobs() {
        assertEquals(List.of("P58", "P86", "P344", "P162", "P1040"), SourceData.CREW_PROPS);
        assertEquals(List.of("writer", "composer", "cinematographer", "producer", "editor"), Rules.CREW_JOBS);
        assertEquals("production", Rules.PRODUCTION);
        assertEquals("distribution", Rules.DISTRIBUTION);
    }
}
