package io.hammerhall.data.mov;

import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.mov.build.Cinema;
import io.hammerhall.data.mov.build.Genres;
import io.hammerhall.data.mov.build.Places;
import io.hammerhall.data.mov.build.Rules;
import io.hammerhall.data.mov.build.Names.GenderPath;
import io.hammerhall.data.mov.source.SourceData;
import io.hammerhall.data.mov.source.WorldCities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Набор из крошечного снимка ({@link TinySnapshot}): правила на настоящем разборе файлов снимка. */
class CinemaTest {
    static SourceData tiny() throws IOException {
        return SourceData.read(TinySnapshot.path());
    }

    /** Крошечные города «Мира» ({@link TinySnapshot#CITIES}). */
    static WorldCities cities() throws IOException {
        return WorldCities.read(TinySnapshot.citiesPath());
    }

    static Cinema cinema(Profile p) throws IOException {
        return Cinema.build(tiny(), cities(), p, new RandomStreams(MovDataset.DEFAULT_SEED), TinySnapshot.EXCLUSIONS);
    }

    static Map<Integer, Cinema.Movie> movies(Cinema c) {
        return c.movies().stream().collect(Collectors.toMap(Cinema.Movie::id, Function.identity()));
    }

    static Map<Integer, Cinema.Actor> actors(Cinema c) {
        return c.actors().stream().collect(Collectors.toMap(Cinema.Actor::id, Function.identity()));
    }

    static List<String> pairs(List<Cinema.Pair> l) {
        return l.stream().map(p -> p.a() + "-" + p.b()).toList();
    }

    @Test
    @DisplayName("Фильмы: исключённые классы, без года, вне окна, без названия, дубль IMDb — не берутся")
    void selection() throws IOException {
        Cinema c = cinema(Profile.FULL);
        assertEquals(List.of(101, 107, 108, 109, 110, 111, 112, 113, 114, 115, 117, 119, 120, 121, 122, 123, 124, 125),
                c.movies().stream().map(Cinema.Movie::id).toList());
        Map<String, Long> st = c.stats();
        assertEquals(26L, st.get("films_in_snapshot"));
        assertEquals(2L, st.get("films_excluded_class"), "серия Q103, клип Q116");
        assertEquals(4L, st.get("films_without_year"), "Q104 без даты (P571 — 1850), Q105 — 1870, Q106 — 2027, Q118 — до н. э.");
        assertEquals(1L, st.get("films_without_title"), "Q126: меток нет ни в одном файле");
        assertEquals(1L, st.get("films_imdb_duplicates"), "Q102 — дубль Q101");
        assertEquals(18, c.moviesFull());
    }

    @Test
    @DisplayName("Фильм: название, ru, год и дата, хронометраж — по правилам; NULL — где источник молчит")
    void movieFields() throws IOException {
        Map<Integer, Cinema.Movie> m = movies(cinema(Profile.FULL));
        Cinema.Movie a = m.get(101);
        assertEquals("Alpha Story", a.title());
        assertEquals("Альфа", a.titleRu());
        assertEquals(1999, a.year(), "preferred 1999 раньше normal 1998");
        assertEquals(LocalDate.of(1999, 3, 31), a.releaseDate());
        assertEquals(90, a.runtime());
        assertEquals("The Story", m.get(107).title(), "P1476: en раньше fr");
        assertEquals(1950, m.get(107).year());
        assertNull(m.get(107).releaseDate(), "точность — месяц");
        assertEquals("Deutsches", m.get(108).title(), "запасной язык: de раньше fr");
        assertEquals(LocalDate.of(1961, 6, 15), m.get(108).releaseDate());
        assertEquals("Long Haul", m.get(114).title(), "mul");
        assertNull(m.get(114).runtime(), "1 501 минута");
        assertEquals(1500, m.get(115).runtime());
        assertEquals(40, m.get(111).runtime());
        assertEquals(42, m.get(112).runtime());
        assertEquals(90, m.get(113).runtime(), "20 секунд — 0 минут, вне: из годных — 90");
        assertEquals(1874, m.get(113).year());
        assertNull(m.get(120).titleRu());
    }

    @Test
    @DisplayName("Жанры фильмов: по классу, хронометражу и P136 (составные, глубина, два якоря, глубже 6 — нет)")
    void genres() throws IOException {
        Map<Integer, List<Integer>> g = new HashMap<>();
        for (Cinema.Pair p : cinema(Profile.FULL).moviesGenres()) g.computeIfAbsent(p.a(), k -> new java.util.ArrayList<>()).add(p.b());
        assertEquals(List.of(6, 9, 18), g.get(101), "drama film + romantic comedy");
        assertEquals(List.of(4, 20), g.get(110), "анимационный короткометражный");
        assertEquals(List.of(8, 20), g.get(111), "документальный, 40 минут");
        assertEquals(List.of(1, 7, 22), g.get(112), "neo-noir на уровне 2; action на 1, adventure на 2");
        assertNull(g.get(113), "якорь на уровне 7 и петля — жанров нет");
        assertEquals(List.of(6, 14), g.get(117));
        assertEquals(List.of(20), g.get(119), "P136 short film, хотя 41 минута");
        assertEquals(List.of(6), g.get(120), "mockumentary — только Comedy (не Documentary + Comedy от предков)");
        assertEquals(7, g.size());
    }

    @Test
    @DisplayName("Люди: имя из меток (без уточнения, запасной язык), фамилия по P734 и эвристике; не люди и без имени — нет")
    void people() throws IOException {
        Cinema c = cinema(Profile.FULL);
        Map<Integer, Cinema.Actor> a = actors(c);
        assertEquals(List.of(502, 503, 504, 505, 507, 510, 511, 512, 513, 514, 515), List.copyOf(new java.util.TreeSet<>(a.keySet())));
        assertEquals("Wei", a.get(502).firstName());
        assertEquals("Zhang", a.get(502).lastName());
        assertEquals("van Dalen", a.get(503).lastName());
        assertEquals("Halden Jr.", a.get(504).lastName());
        assertNull(a.get(505).firstName());
        assertEquals("Zelda", a.get(505).lastName());
        assertEquals("Hans", a.get(507).firstName());
        assertEquals("Müller", a.get(507).lastName());
        assertEquals("Ганс Мюллер", a.get(507).nameRu());
        assertEquals("de la Vega", a.get(510).lastName());
        assertEquals("Al", a.get(511).firstName());
        assertEquals("Lee", a.get(512).firstName(), "фамилия — первая по P1545: Kim (mul)");
        assertEquals("Kim", a.get(512).lastName());
        assertEquals("Torres", a.get(513).lastName(), "метка mul");
        assertEquals("Else", a.get(514).lastName(), "en «(unnamed)» — пусто без уточнения: mul");
        assertEquals("Lund", a.get(515).lastName(), "en с обратной косой — негодна: fr");
        List<Cinema.Director> d = c.directors();
        assertEquals(List.of(501, 506, 522), d.stream().map(Cinema.Director::id).toList(),
                "Q508 без имени, Q509 — не человек; Q522 — режиссёр документального фильма");
        assertEquals(new Cinema.Director(501, "Steven", "Halden", "Стивен Халден"), d.get(0));
        assertEquals(new Cinema.Director(506, "John", "Smith", null), d.get(1),
                "иное P21 — не в актёрах, но в режиссёрах; уточнение в скобках убрано");
    }

    @Test
    @DisplayName("Пол: P21, имя, от seed — с долей F актёров full (одна на все профили); иное P21 — не в актёрах")
    void gender() throws IOException {
        Cinema s = cinema(Profile.SMALL);
        Map<Integer, Cinema.Actor> a = actors(s);
        assertEquals("M", a.get(502).gender());
        assertEquals("F", a.get(503).gender());
        assertEquals("M", a.get(504).gender());
        assertEquals("F", a.get(505).gender());
        assertEquals("M", a.get(507).gender(), "имя Hans — male");
        assertEquals("F", a.get(510).gender(), "имя Maria — female");
        assertEquals(Map.of(GenderPath.P21, 6L, GenderPath.GIVEN_NAME, 2L, GenderPath.SEEDED, 2L, GenderPath.NOT_TAKEN, 1L),
                s.genderPaths());
        assertEquals(new BigDecimal("0.4444"), s.femaleShare(), "по full: 4 F из 9 (Q515 — M по первому имени, P1545 = 1)");
        assertTrue(s.roles().stream().noneMatch(r -> r.actorId() == 506));
        Cinema m = cinema(Profile.MEDIUM), f = cinema(Profile.FULL);
        assertEquals(s.femaleShare(), f.femaleShare());
        assertEquals("M", actors(m).get(515).gender());
        Map<Integer, Cinema.Actor> fa = actors(f);
        for (Cinema.Actor x : s.actors()) assertEquals(fa.get(x.id()), x, "актёр — как в full, с тем же полом");
        for (Cinema.Actor x : f.actors()) assertTrue(x.gender().equals("M") || x.gender().equals("F"));
    }

    @Test
    @DisplayName("Роли: строка на пару; P4633 раньше персонажа; несколько ролей — через « / »; повтор — один; табуляция — пусто")
    void roles() throws IOException {
        Map<String, String> r = new HashMap<>();
        for (Cinema.Role x : cinema(Profile.FULL).roles()) r.put(x.actorId() + "-" + x.movieId(), x.role());
        assertEquals(15, r.size());
        assertTrue(r.containsKey("514-120"), "состав mockumentary берётся — фильм не документальный");
        assertEquals("Captain Rook / Narrator", r.get("502-101"));
        assertEquals("Ghost", r.get("503-101"), "метка mul персонажа; повтор строки — одна роль");
        assertNull(r.get("504-101"));
        assertTrue(r.containsKey("504-101"), "P725 без роли — строка есть, роль пустая");
        assertNull(r.get("505-101"), "P4633 с табуляцией — роль пустая, метку персонажа не берём");
        assertEquals("Clean Name", r.get("507-101"), "en персонажа негодна — mul");
        assertEquals("Herself", r.get("510-101"));
        assertEquals("Self", r.get("502-107"));
        assertTrue(!r.containsKey("502-102"), "Q102 — дубль, его роли не берутся");
    }

    @Test
    @DisplayName("Страны и языки: ISO3 без исключённых и без пустых; ISO 639-1, иначе 639-3")
    void codes() throws IOException {
        Cinema c = cinema(Profile.FULL);
        assertEquals(List.of("101 QXA", "101 USA", "112 USA"), c.moviesCountries().stream().map(x -> x.movieId() + " " + x.code()).toList());
        assertEquals(List.of("101 en", "101 qya"), c.moviesLanguages().stream().map(x -> x.movieId() + " " + x.code()).toList());
    }

    @Test
    @DisplayName("Жанры режиссёров: доля его фильмов с жанром среди фильмов с жанрами, по фильмам профиля")
    void directorsGenres() throws IOException {
        Map<String, BigDecimal> f = new HashMap<>();
        for (Cinema.DirectorGenre d : cinema(Profile.FULL).directorsGenres()) f.put(d.directorId() + "-" + d.genreId(), d.prob());
        assertEquals(new BigDecimal("0.6667"), f.get("501-6"), "Comedy — в 2 из 3 (Q113 без жанров не в счёт)");
        assertEquals(new BigDecimal("0.3333"), f.get("501-9"));
        assertEquals(new BigDecimal("0.5000"), f.get("506-20"));
        assertEquals(12, f.size(), "у Q522 — Documentary и Short документального Q111");
        Map<String, BigDecimal> s = new HashMap<>();
        for (Cinema.DirectorGenre d : cinema(Profile.SMALL).directorsGenres()) s.put(d.directorId() + "-" + d.genreId(), d.prob());
        assertEquals(Map.of("501-6", BigDecimal.ONE.setScale(4), "501-9", BigDecimal.ONE.setScale(4), "501-18",
                BigDecimal.ONE.setScale(4)), s, "в small у режиссёра один фильм");
    }

    @Test
    @DisplayName("Профили: по популярности (1 % и 10 %, вверх); люди и связи — только их; рейтинг — один во всех профилях")
    void profiles() throws IOException {
        Cinema f = cinema(Profile.FULL), m = cinema(Profile.MEDIUM), s = cinema(Profile.SMALL);
        assertEquals(List.of(101), s.movies().stream().map(Cinema.Movie::id).toList());
        assertEquals(List.of(101, 112), m.movies().stream().map(Cinema.Movie::id).toList());
        assertEquals(List.of(501), s.directors().stream().map(Cinema.Director::id).toList());
        assertEquals(List.of("501-101"), pairs(s.moviesDirectors()));
        assertEquals(10, s.roles().size());
        assertEquals(List.of(502, 503, 504, 505, 507, 510, 511, 512, 513, 514, 515),
                m.actors().stream().map(Cinema.Actor::id).toList());
        Map<Integer, Cinema.Movie> fm = movies(f);
        for (Cinema.Movie x : m.movies()) assertEquals(fm.get(x.id()), x, "фильм профиля — как в full, с тем же рейтингом");
        for (Cinema.Movie x : f.movies()) {
            assertTrue(x.rank().compareTo(new BigDecimal("1.0")) >= 0 && x.rank().compareTo(new BigDecimal("9.9")) <= 0);
            assertEquals(1, x.rank().scale());
        }
    }

    @Test
    @DisplayName("Повторяемость: тот же снимок и seed — тот же набор; другой seed меняет только рейтинг и пол «от seed»")
    void seeds() throws IOException {
        SourceData src = tiny();
        Cinema a = Cinema.build(src, cities(), Profile.FULL, new RandomStreams(1), TinySnapshot.EXCLUSIONS);
        Cinema b = Cinema.build(src, cities(), Profile.FULL, new RandomStreams(1), TinySnapshot.EXCLUSIONS);
        Cinema c = Cinema.build(src, cities(), Profile.FULL, new RandomStreams(2), TinySnapshot.EXCLUSIONS);
        assertEquals(a.movies(), b.movies());
        assertEquals(a.actors(), b.actors());
        assertEquals(a.roles(), c.roles());
        assertEquals(a.movies().stream().map(Cinema.Movie::title).toList(), c.movies().stream().map(Cinema.Movie::title).toList());
        assertTrue(!a.movies().equals(c.movies()), "рейтинг зависит от seed");
    }

    @Test
    @DisplayName("Год снимка — из имени в fetch.json: фильм его года берётся, следующего — нет; снимок без файла — ошибка")
    void snapshotYear(@TempDir Path tmp) throws IOException {
        Path f = TinySnapshot.with(tmp, Map.of("fetch.json",
                TinySnapshot.utf8("{\"snapshot\":\"mov-source-20270101\",\"fetched_at\":\"2027-01-01T00:00:00Z\"}")));
        Cinema c = Cinema.build(SourceData.read(f), cities(), Profile.FULL, new RandomStreams(1), TinySnapshot.EXCLUSIONS);
        assertTrue(movies(c).containsKey(106), "2027 — год снимка");
        Map<String, byte[]> drop = new HashMap<>();
        drop.put("wikidata/characters.csv.gz", null);
        IOException e = assertThrows(IOException.class, () -> SourceData.read(TinySnapshot.with(tmp, drop)));
        assertTrue(e.getMessage().contains("wikidata/characters"), e.getMessage());
    }

    @Test
    @DisplayName("Пробный снимок (fetch.py --sample: поле sample в fetch.json) — не вход сборки")
    void sampleSnapshotRefused(@TempDir Path tmp) throws IOException {
        Path f = TinySnapshot.with(tmp, Map.of("fetch.json", TinySnapshot.utf8("{\"snapshot\":\"mov-source-20261125-sample200\","
                + "\"fetched_at\":\"2026-11-25T03:00:00Z\",\"sample\":{\"films\":200,\"from\":\"mov-source-20261124\","
                + "\"from_sha256\":\"00\"},\"files\":{},\"skipped\":[]}")));
        IOException e = assertThrows(IOException.class, () -> SourceData.read(f));
        assertTrue(e.getMessage().contains("пробный снимок: 200 фильмов из mov-source-20261124"), e.getMessage());
    }

    @Test
    @DisplayName("Таблица снимка без сжатия (.csv вместо .csv.gz) читается так же")
    void plainCsv(@TempDir Path tmp) throws IOException {
        Map<String, byte[]> ch = new HashMap<>();
        ch.put("wikidata/film_cast.csv.gz", null);
        ch.put("wikidata/film_cast.csv", TinySnapshot.utf8(TinySnapshot.FILM_CAST));
        Cinema c = Cinema.build(SourceData.read(TinySnapshot.with(tmp, ch)), cities(), Profile.FULL, new RandomStreams(MovDataset.DEFAULT_SEED),
                TinySnapshot.EXCLUSIONS);
        assertEquals(cinema(Profile.FULL).roles(), c.roles());
    }

    // ---------------------------------------------------------------- снимок v2

    static Map<Integer, Cinema.Person> people(Cinema c) {
        return c.people().stream().collect(Collectors.toMap(Cinema.Person::id, Function.identity()));
    }

    @Test
    @DisplayName("Год по запасным путям (P1191, P571, P2754 — тем же правилом), дата — только из P577; название — из меток на любом языке")
    void yearAndTitleFallback() throws IOException {
        Cinema c = cinema(Profile.FULL);
        Map<Integer, Cinema.Movie> m = movies(c);
        assertEquals(1999, m.get(101).year(), "P577 есть — P1191 не смотрим");
        assertEquals(LocalDate.of(1999, 3, 31), m.get(101).releaseDate());
        assertEquals(1960, m.get(121).year(), "P1191: preferred 1960 раньше normal 1955");
        assertNull(m.get(121).releaseDate(), "дата выхода — только из P577");
        assertEquals(1948, m.get(122).year(), "P1191 с точностью десятилетия — не в счёт: P571");
        assertEquals(1933, m.get(123).year(), "P571 — 2030, после года снимка: P2754");
        assertEquals(1990, m.get(125).year(), "P577 — 1870, вне окна: P1191");
        assertNull(m.get(125).releaseDate());
        assertEquals("Alternativní název", m.get(124).title(), "ar — негодная, cs — первый язык; из двух меток cs — меньшая");
        assertNull(m.get(124).titleRu());
        assertEquals("Только по-русски", m.get(109).title(), "только метка ru (P1476 негоден) — название из ru");
        assertEquals(m.get(109).title(), m.get(109).titleRu());
        assertEquals(1970, m.get(109).year());
        assertEquals(Map.of(Rules.YearPath.P577, 14L, Rules.YearPath.P1191, 2L, Rules.YearPath.P571, 1L, Rules.YearPath.P2754, 1L),
                c.yearPaths());
        assertEquals(Map.of(Rules.TitlePath.LABEL_EN, 13L, Rules.TitlePath.LABEL_MUL, 1L, Rules.TitlePath.P1476, 1L,
                Rules.TitlePath.LABEL_LATIN, 1L, Rules.TitlePath.LABEL_RU, 1L, Rules.TitlePath.LABEL_ANY, 1L), c.titlePaths());
        assertEquals(1L, c.stats().get("movies_title_label_ru"));
        assertEquals(1L, c.stats().get("movies_year_p571"));
        Cinema s = cinema(Profile.SMALL);
        assertEquals(1L, s.yearPaths().get(Rules.YearPath.P577), "пути — по фильмам профиля");
        assertEquals(0L, s.yearPaths().get(Rules.YearPath.P1191));
    }

    @Test
    @DisplayName("Бюджет и сборы: в долларах США, лучший ранг, наибольшая, half-even; ≤ 0 и другие валюты — пусто")
    void money() throws IOException {
        Map<Integer, Cinema.Movie> m = movies(cinema(Profile.FULL));
        assertEquals(1_000_000L, m.get(101).budgetUsd(), "preferred 1 000 000 USD; 5 000 000 normal и 9 000 000 в другой валюте — мимо");
        assertEquals(2_500_000L, m.get(101).boxOfficeUsd(), "2 500 000,5 → 2 500 000; нечитаемая сумма — мимо");
        assertNull(m.get(107).budgetUsd(), "preferred — 0: пусто, normal не смотрим");
        assertNull(m.get(110).budgetUsd(), "0,4 → 0 — пусто");
        assertEquals(4L, m.get(110).boxOfficeUsd(), "3,5 → 4");
        assertEquals(2L, m.get(111).boxOfficeUsd(), "2,5 → 2");
        assertEquals(2_000_000L, m.get(112).budgetUsd(), "preferred — в другой валюте: из сумм в долларах — normal");
        assertNull(m.get(112).boxOfficeUsd(), "только другая валюта");
        assertEquals(1_500L, m.get(113).budgetUsd(), "1.5E+3");
        assertNull(m.get(117).budgetUsd(), "−5");
        assertNull(m.get(120).budgetUsd());
    }

    @Test
    @DisplayName("Люди: актёры, режиссёры и группа — один раз; пол (иное P21 — пусто), даты жизни, город рождения, гражданство")
    void peopleV2() throws IOException {
        Cinema c = cinema(Profile.FULL);
        Map<Integer, Cinema.Person> p = people(c);
        assertEquals(List.of(501, 502, 503, 504, 505, 506, 507, 510, 511, 512, 513, 514, 515, 516, 517, 518, 522),
                c.people().stream().map(Cinema.Person::id).toList(),
                "Q509 — не человек, Q519 — без имени, Q520 — только в дубле, Q521 — только в составе документального фильма");
        assertEquals(new Cinema.Person(501, "Steven", "Halden", "Стивен Халден", "M", LocalDate.of(1946, 12, 18), 1946, null,
                null, 1_000_001, 6000), p.get(501), "место — первое по номеру P19 (Q6000), город — его");
        assertEquals(new Cinema.Person(506, "John", "Smith", null, null, LocalDate.of(1900, 3, 4), 1900, LocalDate.of(1999, 12, 31),
                1999, null, null), p.get(506), "иное P21 — в режиссёрах, пол пустой");
        assertNull(p.get(502).birthDate(), "preferred — с точностью года");
        assertEquals(1970, p.get(502).birthYear());
        assertEquals(6002, p.get(502).birthPlaceId());
        assertEquals(1_000_001, p.get(502).birthCityId(), "у места нет GeoNames ID — ближайший город (равные — меньший id)");
        assertNull(p.get(503).birthYear(), "1650 — раньше 1700");
        assertNull(p.get(503).deathYear(), "без рождения нет смерти");
        assertEquals(6015, p.get(503).birthPlaceId(), "первое по номеру P19 из тех, что берутся: Q6003 нет, Q6014 — в списке");
        assertEquals(1_000_004, p.get(503).birthCityId());
        assertNull(p.get(510).birthPlaceId(), "Q6007 без названия, Q6013 — в исключённой стране");
        assertNull(p.get(510).birthCityId());
        assertEquals(1950, p.get(504).birthYear());
        assertNull(p.get(504).deathDate(), "смерть раньше рождения");
        assertNull(p.get(504).deathYear());
        assertNull(p.get(505).birthDate(), "точность месяца");
        assertEquals(1980, p.get(505).birthYear(), "1979 — точность десятилетия");
        assertNull(p.get(505).deathDate(), "30 февраля — не дата");
        assertEquals(2020, p.get(505).deathYear());
        assertNull(p.get(507).deathYear(), "смерть без рождения — пусто");
        assertEquals(LocalDate.of(1975, 7, 7), p.get(510).birthDate());
        assertNull(p.get(510).deathDate(), "дата смерти раньше даты рождения — пусто");
        assertEquals(1975, p.get(510).deathYear(), "а год — не меньше года рождения");
        assertNull(p.get(513).birthYear(), "2030 — после года снимка");
        assertNull(p.get(516).gender(), "иное P21");
        assertNull(p.get(516).birthYear(), "дата не разобрана");
        assertEquals("F", p.get(517).gender(), "по имени");
        assertTrue(Set.of("M", "F").contains(p.get(518).gender()), "от seed");
        for (Cinema.Actor a : c.actors()) assertEquals(a.gender(), p.get(a.id()).gender(), "пол актёра — как в actors");
        assertEquals(Map.of(GenderPath.P21, 8L, GenderPath.GIVEN_NAME, 4L, GenderPath.SEEDED, 3L, GenderPath.NOT_TAKEN, 2L),
                c.peopleGenderPaths());
        assertEquals(List.of("501 USA", "510 QXA", "510 USA"),
                c.peopleCountries().stream().map(x -> x.movieId() + " " + x.code()).toList(), "ESH — исключён, у Q9002 нет ISO3");
        Map<Integer, Cinema.Person> sp = people(cinema(Profile.SMALL));
        assertEquals(List.of(501, 502, 503, 504, 505, 507, 510, 511, 512, 513, 514, 516, 517, 518), List.copyOf(new java.util.TreeSet<>(sp.keySet())));
        for (Cinema.Person x : sp.values()) assertEquals(p.get(x.id()), x, "человек — как в full");
    }

    @Test
    @DisplayName("Съёмочная группа: пять должностей; не человек и без имени — нет; фильм не в наборе — нет")
    void crew() throws IOException {
        Cinema c = cinema(Profile.FULL);
        assertEquals(new java.util.TreeSet<>(List.of("101 501 producer", "101 502 writer", "101 516 writer", "101 517 composer",
                        "101 518 cinematographer", "112 516 writer", "112 518 editor")),
                new java.util.TreeSet<>(c.crew().stream().map(x -> x.movieId() + " " + x.personId() + " " + x.job()).toList()));
        assertTrue(actors(c).keySet().stream().noneMatch(a -> a == 516 || a == 517 || a == 518), "группа без ролей — не актёры");
        assertEquals(5, cinema(Profile.SMALL).crew().size());
    }

    @Test
    @DisplayName("Студии и прокатчики: обе роли; без метки en/mul — нет; негодная en — mul; страна — ISO3 или пусто")
    void companies() throws IOException {
        Cinema c = cinema(Profile.FULL);
        assertEquals(List.of(new Cinema.Company(7001, "Northwind Pictures", "Нордвинд", "USA", 6001, 1_000_003),
                new Cinema.Company(7002, "Bluebird Films", null, null, null, null),
                new Cinema.Company(7004, "Harbor Media", null, null, null, null)),
                c.companies(), "Q7002: страна исключена; Q7004: en с обратной косой, страны нет; Q7003 — только ru");
        assertEquals(new java.util.TreeSet<>(List.of("101 7001 distribution", "101 7001 production", "101 7002 distribution",
                "112 7004 distribution")), new java.util.TreeSet<>(c.moviesCompanies().stream()
                .map(x -> x.movieId() + " " + x.companyId() + " " + x.role()).toList()));
        assertEquals(List.of(7001, 7002), cinema(Profile.SMALL).companies().stream().map(Cinema.Company::id).toList(),
                "справочник — только те, на кого ссылается профиль");
    }

    @Test
    @DisplayName("Серии: номер — наименьшее целое 1…10 000, иначе пусто; продолжения — P156 и встречное P155, оба в профиле, не сам себе")
    void seriesAndSequels() throws IOException {
        Cinema c = cinema(Profile.FULL);
        assertEquals(List.of(new Cinema.Item(7101, "Alpha Saga", "Сага об Альфе"), new Cinema.Item(7102, "Night Saga", null)), c.series());
        assertEquals(List.of(new Cinema.MovieSeries(101, 7101, 1), new Cinema.MovieSeries(112, 7101, null),
                new Cinema.MovieSeries(112, 7102, null), new Cinema.MovieSeries(113, 7102, 10_000),
                new Cinema.MovieSeries(117, 7101, null)), c.moviesSeries(), "0, 10 001, «3a» — пусто; Q7103 без метки — нет");
        assertEquals(List.of("101-112", "112-117", "119-120"), pairs(c.sequels()), "Q113 → Q113 — нет; Q102 не в наборе — нет");
        assertEquals(List.of("101-112"), pairs(cinema(Profile.MEDIUM).sequels()));
        assertEquals(List.of(), pairs(cinema(Profile.SMALL).sequels()), "Q112 не в small");
    }

    @Test
    @DisplayName("Награды: выиграна и номинирована — две строки; год — самый ранний с точностью ≥ года; без метки — нет")
    void awards() throws IOException {
        Cinema c = cinema(Profile.FULL);
        assertEquals(List.of(new Cinema.Item(7201, "Golden Reel", "Золотая катушка"), new Cinema.Item(7202, "Silver Frame", null)),
                c.awards(), "метка de — не нужна");
        assertEquals(List.of(new Cinema.MovieAward(101, 7201, false, null), new Cinema.MovieAward(101, 7201, true, 1999),
                new Cinema.MovieAward(101, 7202, false, null), new Cinema.MovieAward(112, 7202, false, 2002)), c.moviesAwards(),
                "Q7202 у Q101 — точность десятилетия; Q7203 без метки");
        assertEquals(1L, tiny().stats().get("film_awards_dates_unreadable"));
    }

    @Test
    @DisplayName("Выход по странам: точность до дня, страна с ISO3 без исключённых, город — нет; на страну — самая ранняя")
    void releases() throws IOException {
        assertEquals(List.of(new Cinema.Release(101, "QXA", LocalDate.of(1999, 4, 10)), new Cinema.Release(101, "USA",
                LocalDate.of(1999, 3, 31)), new Cinema.Release(112, "USA", LocalDate.of(2003, 3, 3))), cinema(Profile.FULL).releases());
    }

    @Test
    @DisplayName("Названия по языкам: метки LABEL_LANGS и метки на любом языке — годные, одна на язык")
    void titles() throws IOException {
        Cinema c = cinema(Profile.FULL);
        Map<Integer, List<String>> t = new HashMap<>();
        for (Cinema.Title x : c.titles()) t.computeIfAbsent(x.movieId(), k -> new java.util.ArrayList<>()).add(x.lang() + ":" + x.title());
        assertEquals(List.of("de:Alpha-Geschichte", "en:Alpha Story", "ru:Альфа"), t.get(101));
        assertEquals(List.of("de:Deutsches", "fr:Français", "ru:Русское"), t.get(108), "метка ja не из LABEL_LANGS — не в film_labels");
        assertEquals(List.of("cs:Alternativní název", "ja:日本語の題名"), t.get(124), "ar — негодная");
        assertEquals(List.of("en:Very Long", "mul:Very Long Mul"), t.get(115));
        assertEquals(List.of("ru:Только по-русски"), t.get(109));
        assertEquals(25, c.titles().size());
        assertNull(t.get(126), "фильм не в наборе");
    }

    static List<String> links(List<Cinema.PlaceLink> l) {
        return l.stream().map(x -> x.movieId() + "-" + x.placeId() + "-" + x.cityId()).toList();
    }

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    @DisplayName("Места съёмок и действия: строка на (фильм, место), город — как у места; место без названия — строки нет")
    void locationsAndSettings() throws IOException {
        Cinema c = cinema(Profile.FULL);
        assertEquals(List.of("101-6000-1000001", "101-6001-1000003", "101-6002-1000001", "101-6005-null", "112-6000-1000001",
                "113-6010-null", "113-6012-1000001"), links(c.locations()), "Q6001 и Q6005 — один GeoNames ID, но разные места");
        assertEquals(List.of("101-30-null", "112-6006-null", "117-6009-null"), links(c.settings()), "Q6007 без названия — нет");
        assertEquals(List.of("101-30-null"), links(cinema(Profile.SMALL).settings()));
        SourceData d = tiny();
        assertEquals(1L, d.stats().get("places_geonames_unreadable"));
        assertEquals(1L, d.stats().get("places_coords_unreadable"), "Q6010: широта 95");
    }

    @Test
    @DisplayName("Места: только те, на кого ссылается профиль; вид, страна, координаты; город — по GeoNames ID, ближайший той же страны до 30 км")
    void places() throws IOException {
        Cinema c = cinema(Profile.FULL);
        Map<Integer, Cinema.Place> p = c.places().stream().collect(Collectors.toMap(Cinema.Place::id, Function.identity()));
        assertEquals(List.of(30, 6000, 6001, 6002, 6005, 6006, 6009, 6010, 6012, 6015),
                c.places().stream().map(Cinema.Place::id).toList(),
                "съёмки, действие, рождение (Q6000, Q6002, Q6015), штаб-квартира (Q6001); Q6007 без названия; Q9001, Q6013, Q6014 — исключены");
        assertEquals(new Cinema.Place(30, "United States", "Соединённые Штаты", "country", bd("39.8000000"), bd("-98.5000000"),
                "USA", null, null), p.get(30), "страна: свой код, города нет");
        assertEquals(new Cinema.Place(6000, "Rivermouth", "Ривермут", "place", bd("40.0000000"), bd("-74.0000002"), "USA",
                1_000_001, bd("0.0")), p.get(6000), "город по GeoNames ID — 0 км; координаты — половина к чётному");
        assertEquals(1_000_003, p.get(6001).cityId(), "GeoNames ID не из «Мира»: ближайший той же страны (Otherland ближе, но QXA)");
        assertEquals(bd("2.2"), p.get(6001).cityKm());
        assertEquals(1_000_001, p.get(6002).cityId(), "Rivermouth и Lakeside — на равном расстоянии: меньший id");
        assertEquals(bd("21.3"), p.get(6002).cityKm());
        assertNull(p.get(6005).cityId(), "без координат");
        assertNull(p.get(6005).latitude());
        assertNull(p.get(6006).cityId(), "ближайший — дальше 30 км");
        assertNull(p.get(6006).cityKm());
        assertNull(p.get(6009).countryCode());
        assertNull(p.get(6009).cityId(), "без страны — ближайшего не ищем");
        assertNull(p.get(6010).latitude(), "широта вне диапазона — координат нет");
        assertEquals("Mill Pond", p.get(6012).name(), "en нет — mul");
        assertEquals(new Cinema.Place(6015, "Lakeside Village", null, "place", null, null, "USA", 1_000_004, bd("0.0")), p.get(6015));
        assertEquals(1_000_001, p.get(6012).cityId());
        assertEquals(bd("0.0"), p.get(6012).cityKm(), "совпали координаты — 0 км (ближайший)");
        assertEquals(Map.of(Places.CityPath.GEONAMES, 2L, Places.CityPath.NEAREST, 3L, Places.CityPath.NONE, 4L,
                Places.CityPath.COUNTRY, 1L), c.placePaths());
        assertEquals(Map.of(Places.Excluded.EXCLUDED_COUNTRY, 1L, Places.Excluded.IN_EXCLUDED_COUNTRY, 1L, Places.Excluded.LISTED, 1L),
                c.placesExcluded(), "Q9001 (действие Q117), Q6013 (съёмки Q112, рождение Q510, штаб-квартира Q7004), Q6014");
        Cinema s = cinema(Profile.SMALL);
        assertEquals(List.of(30, 6000, 6001, 6002, 6005, 6015), s.places().stream().map(Cinema.Place::id).toList(),
                "small: места фильма 101, рождения его людей и штаб-квартиры его студий");
        assertEquals(Map.of(Places.Excluded.EXCLUDED_COUNTRY, 0L, Places.Excluded.IN_EXCLUDED_COUNTRY, 1L, Places.Excluded.LISTED, 1L),
                s.placesExcluded());
    }

    @Test
    @DisplayName("Исключённые места — как без названия: строк мест фильмов нет, у людей и студий — пусто; список мест подменяется")
    void excludedPlaces() throws IOException {
        Cinema c = cinema(Profile.FULL);
        assertTrue(c.locations().stream().noneMatch(x -> x.placeId() == 6013), "съёмки Q112 в Q6013 — строки нет");
        assertTrue(c.settings().stream().noneMatch(x -> x.placeId() == 9001), "действие Q117 в стране Q9001 — строки нет");
        assertEquals(new Cinema.Company(7004, "Harbor Media", null, null, null, null),
                c.companies().stream().filter(x -> x.id() == 7004).findFirst().orElseThrow(), "штаб-квартира исключена — пусто");
        Cinema d = Cinema.build(tiny(), cities(), Profile.FULL, new RandomStreams(MovDataset.DEFAULT_SEED), Exclusions.DEFAULT);
        assertEquals(6014, people(d).get(503).birthPlaceId(), "без Q6014 в списке — оно первое из берущихся");
        assertEquals(1_000_001, people(d).get(503).birthCityId());
        assertEquals(0L, d.placesExcluded().get(Places.Excluded.LISTED));
    }

    @Test
    @DisplayName("places.csv читается по заголовку (столбцы в другом порядке): место без en — по mul, ссылки на него появляются")
    void placesMulColumn(@TempDir Path tmp) throws IOException {
        Map<String, byte[]> ch = new HashMap<>();
        ch.put("wikidata/places.csv.gz", TinySnapshot.gz(TinySnapshot.csv("item,label_ru,lat,lon,geonames,country,label_mul,label_en",
                "Q6007|Безымянное|40|-74||Q30|Nameless Hill|")));
        Cinema c = Cinema.build(SourceData.read(TinySnapshot.with(tmp, ch)), cities(), Profile.FULL,
                new RandomStreams(MovDataset.DEFAULT_SEED), TinySnapshot.EXCLUSIONS);
        assertEquals(List.of("101-6007-1000001"), links(c.settings()));
        assertEquals(List.of(new Cinema.Place(6007, "Nameless Hill", "Безымянное", "place", bd("40.0000000"), bd("-74.0000000"), "USA",
                1_000_001, bd("0.0"))), c.places().stream().filter(x -> x.id() == 6007).toList());
        assertEquals(6007, people(c).get(510).birthPlaceId());
        assertEquals(List.of(), links(c.locations()), "других мест в этом снимке нет");
    }

    @Test
    @DisplayName("Снимок v2: каждый новый файл обязателен — без него ошибка с его именем; строки о фильмах не из films.csv — мимо")
    void v2FilesRequired(@TempDir Path tmp) throws IOException {
        for (String base : List.of("film_dates_extra", "film_labels_all", "film_releases", "film_crew", "film_links2",
                "film_series", "film_money", "film_awards", "people_dates", "people_links", "places", "item_labels")) {
            Map<String, byte[]> drop = new HashMap<>();
            drop.put("wikidata/" + base + ".csv.gz", null);
            IOException e = assertThrows(IOException.class, () -> SourceData.read(TinySnapshot.with(tmp, drop)), base);
            assertTrue(e.getMessage().contains("wikidata/" + base + ".csv"), e.getMessage());
        }
        Map<String, byte[]> drop = new HashMap<>();
        drop.put("wikidata/companies.csv", null);
        IOException e = assertThrows(IOException.class, () -> SourceData.read(TinySnapshot.with(tmp, drop)));
        assertTrue(e.getMessage().contains("wikidata/companies.csv"), e.getMessage());
        SourceData d = tiny();
        assertTrue(d.stats().get("film_rows_not_in_films") >= 2, "Q999 в film_dates_extra и film_crew");
        assertTrue(d.crew().get(999) == null && d.datesExtra().get("P571").get(999) == null);
        assertEquals(1L, d.stats().get("film_money_unreadable"));
        assertEquals(1L, d.stats().get("people_dates_unreadable"));
    }

    // ---------------------------------------------------------------- §11: документальные фильмы

    @Test
    @DisplayName("Документальный фильм: актёрский состав не берётся; актёр без других ролей выпадает, с другими — остаётся; режиссёр — остаётся")
    void documentaries() throws IOException {
        Cinema c = cinema(Profile.FULL);
        assertTrue(c.moviesGenres().contains(new Cinema.Pair(111, Genres.DOCUMENTARY)), "Q111 — документальный (класс Q93204)");
        assertTrue(c.roles().stream().noneMatch(r -> r.movieId() == 111), "строк roles у документального фильма нет");
        Map<Integer, Cinema.Actor> a = actors(c);
        assertTrue(!a.containsKey(521), "Q521 снимается только в документальном — не актёр");
        assertTrue(!a.containsKey(518), "Q518 — тоже, но он в съёмочной группе");
        assertTrue(a.containsKey(502), "Q502 играет и в обычных фильмах");
        assertEquals(List.of("502-101", "502-107"), c.roles().stream().filter(r -> r.actorId() == 502)
                .map(r -> r.actorId() + "-" + r.movieId()).toList(), "без роли в Q111");
        Map<Integer, Cinema.Person> p = people(c);
        assertTrue(!p.containsKey(521), "на Q521 больше ничто не ссылается — его нет и в people");
        assertTrue(p.containsKey(518), "Q518 — в people (оператор Q101)");
        assertTrue(p.containsKey(522) && c.moviesDirectors().contains(new Cinema.Pair(522, 111)), "режиссёр — как раньше");
        assertEquals(new Cinema.Documentaries(1, 3, 2, 1), c.documentaries(), "фильм, роли (3 пары), актёры (Q518, Q521), люди (Q521)");
        assertEquals(1L, c.stats().get("documentary_people_dropped"));
        assertEquals(new Cinema.Documentaries(0, 0, 0, 0), cinema(Profile.SMALL).documentaries(), "Q111 не в small");
        assertEquals(new BigDecimal("0.4444"), c.femaleShare(), "доля F — по актёрам full до отбора (у Q521 и Q518 пол — от seed)");
    }
}
