package io.hammerhall.data.mov;

import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.mov.build.Cinema;
import io.hammerhall.data.mov.build.Names.GenderPath;
import io.hammerhall.data.mov.source.SourceData;
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

    static Cinema cinema(Profile p) throws IOException {
        return Cinema.build(tiny(), p, new RandomStreams(MovDataset.DEFAULT_SEED), Exclusions.DEFAULT);
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
        assertEquals(List.of(101, 107, 108, 110, 111, 112, 113, 114, 115, 117, 119, 120),
                c.movies().stream().map(Cinema.Movie::id).toList());
        Map<String, Long> st = c.stats();
        assertEquals(20L, st.get("films_in_snapshot"));
        assertEquals(2L, st.get("films_excluded_class"), "серия Q103, клип Q116");
        assertEquals(4L, st.get("films_without_year"), "Q104 без даты, Q105 — 1870, Q106 — 2027, Q118 — до н. э.");
        assertEquals(1L, st.get("films_without_title"), "Q109: только ru, P1476 со знаком табуляции");
        assertEquals(1L, st.get("films_imdb_duplicates"), "Q102 — дубль Q101");
        assertEquals(12, c.moviesFull());
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
        assertEquals(6, g.size());
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
        assertEquals(List.of(501, 506), d.stream().map(Cinema.Director::id).toList(), "Q508 без имени, Q509 — не человек");
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
        assertEquals(14, r.size());
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
        assertEquals(10, f.size());
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
        Cinema a = Cinema.build(src, Profile.FULL, new RandomStreams(1), Exclusions.DEFAULT);
        Cinema b = Cinema.build(src, Profile.FULL, new RandomStreams(1), Exclusions.DEFAULT);
        Cinema c = Cinema.build(src, Profile.FULL, new RandomStreams(2), Exclusions.DEFAULT);
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
        Cinema c = Cinema.build(SourceData.read(f), Profile.FULL, new RandomStreams(1), Exclusions.DEFAULT);
        assertTrue(movies(c).containsKey(106), "2027 — год снимка");
        Map<String, byte[]> drop = new HashMap<>();
        drop.put("wikidata/characters.csv.gz", null);
        IOException e = assertThrows(IOException.class, () -> SourceData.read(TinySnapshot.with(tmp, drop)));
        assertTrue(e.getMessage().contains("wikidata/characters"), e.getMessage());
    }

    @Test
    @DisplayName("Таблица снимка без сжатия (.csv вместо .csv.gz) читается так же")
    void plainCsv(@TempDir Path tmp) throws IOException {
        Map<String, byte[]> ch = new HashMap<>();
        ch.put("wikidata/film_cast.csv.gz", null);
        ch.put("wikidata/film_cast.csv", TinySnapshot.utf8(TinySnapshot.FILM_CAST));
        Cinema c = Cinema.build(SourceData.read(TinySnapshot.with(tmp, ch)), Profile.FULL, new RandomStreams(MovDataset.DEFAULT_SEED),
                Exclusions.DEFAULT);
        assertEquals(cinema(Profile.FULL).roles(), c.roles());
    }
}
