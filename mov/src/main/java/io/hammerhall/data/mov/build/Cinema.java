package io.hammerhall.data.mov.build;

import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.mov.Exclusions;
import io.hammerhall.data.mov.source.SourceData;
import io.hammerhall.data.mov.source.Wikidata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Набор целиком — функция снимка источников, профиля, seed и списков исключений ({@link #build}): строки таблиц
 * ({@code MovSchema.TABLES}). Сначала собирается {@code full} — все фильмы, люди и роли (по нему считаются
 * популярность и рейтинг); профиль — первые фильмы по популярности, люди, связи и справочники (студии, серии,
 * награды) — только по ним.
 */
public final class Cinema {
    public record Movie(int id, String title, String titleRu, int year, LocalDate releaseDate, Integer runtime, BigDecimal rank,
                        Long budgetUsd, Long boxOfficeUsd) {
    }

    public record Actor(int id, String firstName, String lastName, String gender, String nameRu) {
    }

    public record Director(int id, String firstName, String lastName, String nameRu) {
    }

    public record Role(int actorId, int movieId, String role) {
    }

    /**
     * Пара id: {@code movies_directors} (режиссёр, фильм), {@code movies_genres} (фильм, жанр),
     * {@code movies_sequels} (фильм, следующий фильм), {@code movies_locations} (фильм, город).
     */
    public record Pair(int a, int b) {
    }

    public record DirectorGenre(int directorId, int genreId, BigDecimal prob) {
    }

    /** Код: {@code movies_countries}, {@code movies_languages} (фильм, код), {@code people_countries} (человек, код). */
    public record Code(int movieId, String code) {
    }

    /** Человек {@code people}: актёр, режиссёр или член съёмочной группы фильма профиля. */
    public record Person(int id, String firstName, String lastName, String nameRu, String gender, LocalDate birthDate,
                         Integer birthYear, LocalDate deathDate, Integer deathYear, Integer birthCityId) {
    }

    /** Строка {@code movies_crew}: фильм, человек, должность ({@link Rules#CREW_JOBS}). */
    public record CrewMember(int movieId, int personId, String job) {
    }

    /** Студия или прокатчик {@code companies}. */
    public record Company(int id, String name, String nameRu, String countryCode) {
    }

    /** Строка {@code movies_companies}: роль — {@link Rules#PRODUCTION} или {@link Rules#DISTRIBUTION}. */
    public record MovieCompany(int movieId, int companyId, String role) {
    }

    /** Элемент справочника с названием: {@code series}, {@code awards}. */
    public record Item(int id, String name, String nameRu) {
    }

    public record MovieSeries(int movieId, int seriesId, Integer ordinal) {
    }

    public record MovieAward(int movieId, int awardId, boolean won, Integer year) {
    }

    /** Строка {@code movies_releases}: фильм, страна (ISO 3166-1 alpha-3), дата выхода в ней. */
    public record Release(int movieId, String countryCode, LocalDate releaseDate) {
    }

    /** Строка {@code movies_titles}: фильм, язык метки Wikidata, название. */
    public record Title(int movieId, String lang, String title) {
    }

    /** Строки всех таблиц профиля — копятся при сборке. */
    private static final class Rows {
        final List<Movie> movies = new ArrayList<>();
        final List<Actor> actors = new ArrayList<>();
        final List<Director> directors = new ArrayList<>();
        final List<Role> roles = new ArrayList<>();
        final List<Pair> moviesDirectors = new ArrayList<>(), moviesGenres = new ArrayList<>();
        final List<DirectorGenre> directorsGenres = new ArrayList<>();
        final List<Code> moviesCountries = new ArrayList<>(), moviesLanguages = new ArrayList<>();
        final List<Person> people = new ArrayList<>();
        final List<Code> peopleCountries = new ArrayList<>();
        final List<CrewMember> crew = new ArrayList<>();
        final List<Company> companies = new ArrayList<>();
        final List<MovieCompany> moviesCompanies = new ArrayList<>();
        final List<Item> series = new ArrayList<>();
        final List<MovieSeries> moviesSeries = new ArrayList<>();
        final List<Pair> sequels = new ArrayList<>();
        final List<Item> awards = new ArrayList<>();
        final List<MovieAward> moviesAwards = new ArrayList<>();
        final List<Release> releases = new ArrayList<>();
        final List<Title> titles = new ArrayList<>();
        final List<Pair> locations = new ArrayList<>();
    }

    private final Profile profile;
    private final Rows rows;
    private final Map<Names.GenderPath, Long> genderPaths, peopleGenderPaths;
    private final Map<Rules.YearPath, Long> yearPaths;
    private final Map<Rules.TitlePath, Long> titlePaths;
    private final BigDecimal femaleShare;
    private final int moviesFull;
    private final Map<String, Long> stats;

    private Cinema(Profile profile, Rows rows, Map<Names.GenderPath, Long> genderPaths,
                   Map<Names.GenderPath, Long> peopleGenderPaths, Map<Rules.YearPath, Long> yearPaths,
                   Map<Rules.TitlePath, Long> titlePaths, BigDecimal femaleShare, int moviesFull, Map<String, Long> stats) {
        this.profile = profile;
        this.rows = rows;
        this.genderPaths = Collections.unmodifiableMap(new EnumMap<>(genderPaths));
        this.peopleGenderPaths = Collections.unmodifiableMap(new EnumMap<>(peopleGenderPaths));
        this.yearPaths = Collections.unmodifiableMap(new EnumMap<>(yearPaths));
        this.titlePaths = Collections.unmodifiableMap(new EnumMap<>(titlePaths));
        this.femaleShare = femaleShare;
        this.moviesFull = moviesFull;
        this.stats = Collections.unmodifiableMap(stats);
    }

    public Profile profile() {
        return profile;
    }

    public List<Movie> movies() {
        return Collections.unmodifiableList(rows.movies);
    }

    public List<Actor> actors() {
        return Collections.unmodifiableList(rows.actors);
    }

    public List<Director> directors() {
        return Collections.unmodifiableList(rows.directors);
    }

    public List<Role> roles() {
        return Collections.unmodifiableList(rows.roles);
    }

    public List<Pair> moviesDirectors() {
        return Collections.unmodifiableList(rows.moviesDirectors);
    }

    public List<Pair> moviesGenres() {
        return Collections.unmodifiableList(rows.moviesGenres);
    }

    public List<DirectorGenre> directorsGenres() {
        return Collections.unmodifiableList(rows.directorsGenres);
    }

    public List<Code> moviesCountries() {
        return Collections.unmodifiableList(rows.moviesCountries);
    }

    public List<Code> moviesLanguages() {
        return Collections.unmodifiableList(rows.moviesLanguages);
    }

    /** {@code people}: актёры, режиссёры и съёмочная группа профиля — по возрастанию id. */
    public List<Person> people() {
        return Collections.unmodifiableList(rows.people);
    }

    /** {@code people_countries}: (человек, код страны). */
    public List<Code> peopleCountries() {
        return Collections.unmodifiableList(rows.peopleCountries);
    }

    public List<CrewMember> crew() {
        return Collections.unmodifiableList(rows.crew);
    }

    public List<Company> companies() {
        return Collections.unmodifiableList(rows.companies);
    }

    public List<MovieCompany> moviesCompanies() {
        return Collections.unmodifiableList(rows.moviesCompanies);
    }

    public List<Item> series() {
        return Collections.unmodifiableList(rows.series);
    }

    public List<MovieSeries> moviesSeries() {
        return Collections.unmodifiableList(rows.moviesSeries);
    }

    /** {@code movies_sequels}: (фильм, следующий фильм). */
    public List<Pair> sequels() {
        return Collections.unmodifiableList(rows.sequels);
    }

    public List<Item> awards() {
        return Collections.unmodifiableList(rows.awards);
    }

    public List<MovieAward> moviesAwards() {
        return Collections.unmodifiableList(rows.moviesAwards);
    }

    public List<Release> releases() {
        return Collections.unmodifiableList(rows.releases);
    }

    public List<Title> titles() {
        return Collections.unmodifiableList(rows.titles);
    }

    /** {@code movies_locations}: (фильм, город). */
    public List<Pair> locations() {
        return Collections.unmodifiableList(rows.locations);
    }

    /** Актёров профиля по пути пола; {@link Names.GenderPath#NOT_TAKEN} — люди состава профиля, которых нет в актёрах. */
    public Map<Names.GenderPath, Long> genderPaths() {
        return genderPaths;
    }

    /** Людей профиля ({@code people}) по пути пола; {@link Names.GenderPath#NOT_TAKEN} — иное P21, пол пустой. */
    public Map<Names.GenderPath, Long> peopleGenderPaths() {
        return peopleGenderPaths;
    }

    /** Фильмов профиля по пути года. */
    public Map<Rules.YearPath, Long> yearPaths() {
        return yearPaths;
    }

    /** Фильмов профиля по пути названия. */
    public Map<Rules.TitlePath, Long> titlePaths() {
        return titlePaths;
    }

    /** Доля {@code F} среди актёров {@code full} с полом из источника — вероятность {@code F} там, где источник молчит. */
    public BigDecimal femaleShare() {
        return femaleShare;
    }

    /** Фильмов в {@code full} (от этого числа — доля профиля). */
    public int moviesFull() {
        return moviesFull;
    }

    /** Счётчики сборки (для журнала и самопроверки). */
    public Map<String, Long> stats() {
        return stats;
    }

    // ---------------------------------------------------------------- сборка

    /** Год снимка — последний год фильмов: из имени снимка ({@code mov-source-YYYYMMDD}), иначе из времени снимка. */
    public static int snapshotYear(SourceData src) {
        Matcher m = Pattern.compile("-source-(\\d{4})\\d{4}$").matcher(String.valueOf(src.snapshot()));
        if (m.find()) return Integer.parseInt(m.group(1));
        if (src.fetchedAt() != null && src.fetchedAt().matches("\\d{4}-.*")) return Integer.parseInt(src.fetchedAt().substring(0, 4));
        throw new IllegalStateException("год снимка не понять: snapshot «" + src.snapshot() + "», fetched_at «" + src.fetchedAt() + "»");
    }

    /** Фильм {@code full} до рейтинга. */
    private record Film(int id, String title, Rules.TitlePath titlePath, String titleRu, Rules.Year year, Integer runtime,
                        TreeSet<Integer> genres, SourceData.Links links) {
    }

    /** Человек, годный в набор: имя, фамилия, имя по-русски, пол по источнику (с путём). */
    private record Human(String first, String last, String nameRu, Names.Gender gender) {
    }

    /** Утверждения свойств {@code film_dates_extra} одного фильма: свойство → утверждения. */
    private static Map<String, List<SourceData.DateStmt>> extraDates(SourceData src, int film) {
        Map<String, List<SourceData.DateStmt>> m = new HashMap<>();
        for (Map.Entry<String, Map<Integer, List<SourceData.DateStmt>>> e : src.datesExtra().entrySet()) {
            List<SourceData.DateStmt> d = e.getValue().get(film);
            if (d != null) m.put(e.getKey(), d);
        }
        return m;
    }

    /** Собрать набор: правила — {@link Rules}, {@link Genres}, {@link Names}, {@link Rank}; случайность — {@code streams}. */
    public static Cinema build(SourceData src, Profile profile, RandomStreams streams, Exclusions ex) {
        Map<String, Long> stats = new LinkedHashMap<>();
        int lastYear = snapshotYear(src);

        // ---- фильмы full
        int[] candidates = src.filmClasses().keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        long badClass = 0, noYear = 0, noTitle = 0;
        Map<Integer, Film> pre = new HashMap<>();
        Genres.Resolver genres = new Genres.Resolver(src.genreParents());
        for (int id : candidates) {
            if (!Rules.classesOk(src.filmClasses().get(id), src.classes())) {
                badClass++;
                continue;
            }
            Rules.Year year = Rules.year(src.dates().get(id), extraDates(src, id), lastYear);
            if (year == null) {
                noYear++;
                continue;
            }
            String[] labels = src.filmLabels().get(id);
            Rules.Titled title = Rules.titled(labels, src.titles().get(id), src.labelsAll().get(id));
            if (title == null) {
                noTitle++;
                continue;
            }
            Integer runtime = Rules.runtime(src.seconds().get(id));
            SourceData.Links links = src.links().getOrDefault(id, SourceData.Links.NONE);
            pre.put(id, new Film(id, title.title(), title.path(), Rules.titleRu(labels), year, runtime,
                    Genres.ofFilm(src.filmClasses().get(id), runtime, links.genres(), genres), links));
        }
        Set<Integer> dups = Rules.imdbDuplicates(pre.keySet(), src.signals());
        for (int d : dups) pre.remove(d);
        List<Film> films = new ArrayList<>(pre.values());
        films.sort((a, b) -> Integer.compare(a.id(), b.id()));
        stats.put("films_in_snapshot", (long) candidates.length);
        stats.put("films_excluded_class", badClass);
        stats.put("films_without_year", noYear);
        stats.put("films_without_title", noTitle);
        stats.put("films_imdb_duplicates", (long) dups.size());
        stats.put("movies_full", (long) films.size());
        long withP136 = 0, withP136Genre = 0;
        for (Film f : films) {
            if (f.links().genres().length == 0) continue;
            withP136++;
            boolean any = false;
            for (int v : f.links().genres()) any |= !genres.ofValue(v).isEmpty();
            if (any) withP136Genre++;
        }
        stats.put("movies_full_with_p136", withP136);
        stats.put("movies_full_with_p136_genre", withP136Genre);

        // ---- люди: годен ли человек (человек, есть имя); пол по источнику
        Map<Integer, Human> humans = new HashMap<>();
        Set<Integer> notHumans = new HashSet<>();
        java.util.function.IntFunction<Human> human = id -> {
            Human h = humans.get(id);
            if (h != null || notHumans.contains(id)) return h;
            SourceData.Person p = src.people().get(id);
            String[] labels = src.peopleLabels().get(id);
            String full = p == null || !p.human() ? null : Names.fullName(labels);
            if (full == null) {
                notHumans.add(id);
                return null;
            }
            SourceData.PersonNames pn = src.peopleNames().get(id);
            SourceData.NameRef fam = pn == null ? null : Names.first(pn.family());
            SourceData.NameRef giv = pn == null ? null : Names.first(pn.given());
            Names.Split sp = Names.split(full, fam == null ? null : Names.label(src.nameItems().get(fam.item())));
            SourceData.NameItem gi = giv == null ? null : src.nameItems().get(giv.item());
            h = new Human(sp.first(), sp.last(), labels[Wikidata.RU],
                    Names.gender(p.gender(), gi == null ? null : gi.kind()));
            humans.put(id, h);
            return h;
        };

        // ---- роли full: строка на пару «актёр, фильм» (роли пары — одной строкой)
        Set<Integer> taken = pre.keySet();
        SourceData.Cast cast = src.cast();
        Map<Long, List<String>> rolesOf = new HashMap<>();
        long castRows = 0, castNotTaken = 0;
        for (int i = 0; i < cast.size(); i++) {
            int f = cast.film(i);
            if (!taken.contains(f)) continue;
            castRows++;
            Human h = human.apply(cast.person(i));
            if (h == null || h.gender().path() == Names.GenderPath.NOT_TAKEN) {
                castNotTaken++;
                continue;
            }
            long key = ((long) cast.person(i) << 32) | f;
            String r = Rules.role(cast.characterName(i), src.characters().get(cast.character(i)));
            rolesOf.computeIfAbsent(key, k -> new ArrayList<>(1)).add(r);
        }
        stats.put("cast_rows_of_movies", castRows);
        stats.put("cast_rows_not_taken", castNotTaken);
        long[] pairs = rolesOf.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        // ролей фильма — различных пар «актёр, роль» (пустая роль — тоже роль), как строк ролей по правилу состава,
        // до слияния ролей пары в одну строку
        Map<Integer, Integer> castOf = new HashMap<>();
        for (long k : pairs) castOf.merge((int) k, new HashSet<>(rolesOf.get(k)).size(), Integer::sum);

        // ---- рейтинг и порядок популярности — по full
        int n = films.size();
        int[] ids = new int[n];
        double[] pop = new double[n];
        for (int i = 0; i < n; i++) {
            Film f = films.get(i);
            ids[i] = f.id();
            SourceData.Signals s = src.signals().getOrDefault(f.id(), SourceData.Signals.NONE);
            pop[i] = Rank.popularity(s.sitelinks(), s.awards(), s.nominations(), castOf.getOrDefault(f.id(), 0));
        }
        BigDecimal[] rank = Rank.ranks(ids, pop, streams);
        Integer[] byPop = new Integer[n];
        for (int i = 0; i < n; i++) byPop[i] = i;
        Arrays.sort(byPop, (a, b) -> {
            int c = Double.compare(pop[b], pop[a]);
            return c != 0 ? c : Integer.compare(ids[a], ids[b]);
        });
        int size = Rules.profileSize(n, profile);
        boolean[] in = new boolean[n];
        for (int i = 0; i < size; i++) in[byPop[i]] = true;

        // ---- таблицы профиля: фильмы и их связи
        Rows out = new Rows();
        Set<Integer> profileFilms = new HashSet<>();
        for (int i = 0; i < n; i++) if (in[i]) profileFilms.add(films.get(i).id());
        TreeMap<Integer, List<Integer>> filmsOfDirector = new TreeMap<>();
        Map<Integer, TreeSet<Integer>> genresOf = new HashMap<>();
        Map<Rules.YearPath, Long> yearPaths = new EnumMap<>(Rules.YearPath.class);
        for (Rules.YearPath p : Rules.YearPath.values()) yearPaths.put(p, 0L);
        Map<Rules.TitlePath, Long> titlePaths = new EnumMap<>(Rules.TitlePath.class);
        for (Rules.TitlePath p : Rules.TitlePath.values()) titlePaths.put(p, 0L);
        TreeSet<Integer> crewPeople = new TreeSet<>(), companyIds = new TreeSet<>(), seriesIds = new TreeSet<>(),
                awardIds = new TreeSet<>();
        TreeSet<Long> sequels = new TreeSet<>();
        for (int i = 0; i < n; i++) {
            if (!in[i]) continue;
            Film f = films.get(i);
            int id = f.id();
            List<SourceData.MoneyStmt> money = src.money().get(id);
            out.movies.add(new Movie(id, f.title(), f.titleRu(), f.year().year(), f.year().date(), f.runtime(), rank[i],
                    Rules.usd(money, false), Rules.usd(money, true)));
            yearPaths.merge(f.year().path(), 1L, Long::sum);
            titlePaths.merge(f.titlePath(), 1L, Long::sum);
            genresOf.put(id, f.genres());
            for (int g : f.genres()) out.moviesGenres.add(new Pair(id, g));
            for (int d : f.links().directors()) {
                if (human.apply(d) == null) continue;
                out.moviesDirectors.add(new Pair(d, id));
                filmsOfDirector.computeIfAbsent(d, k -> new ArrayList<>()).add(id);
            }
            TreeSet<String> cc = new TreeSet<>(), lc = new TreeSet<>();
            for (int c : f.links().countries()) {
                String code = Rules.countryCode(src.countries().get(c), ex);
                if (code != null) cc.add(code);
            }
            for (int l : f.links().languages()) {
                String code = Rules.languageCode(src.languages().get(l));
                if (code != null) lc.add(code);
            }
            for (String c : cc) out.moviesCountries.add(new Code(id, c));
            for (String c : lc) out.moviesLanguages.add(new Code(id, c));

            // съёмочная группа: люди (человек, есть имя)
            SourceData.Crew crew = src.crew().getOrDefault(id, SourceData.Crew.NONE);
            for (int j = 0; j < Rules.CREW_JOBS.size(); j++) {
                for (int p : crew.of(j)) {
                    if (human.apply(p) == null) continue;
                    out.crew.add(new CrewMember(id, p, Rules.CREW_JOBS.get(j)));
                    crewPeople.add(p);
                }
            }

            // студии и прокатчики, места съёмок, продолжения
            SourceData.Links2 l2 = src.links2().getOrDefault(id, SourceData.Links2.NONE);
            for (int c : l2.studios()) {
                if (Rules.itemName(src.itemLabels().get(c)) == null) continue;
                out.moviesCompanies.add(new MovieCompany(id, c, Rules.PRODUCTION));
                companyIds.add(c);
            }
            for (int c : l2.distributors()) {
                if (Rules.itemName(src.itemLabels().get(c)) == null) continue;
                out.moviesCompanies.add(new MovieCompany(id, c, Rules.DISTRIBUTION));
                companyIds.add(c);
            }
            TreeSet<Integer> cities = new TreeSet<>();
            for (int p : l2.locations()) {
                Integer city = src.places().get(p);
                if (city != null) cities.add(city);
            }
            for (int c : cities) out.locations.add(new Pair(id, c));
            for (int x : l2.next()) if (x != id && profileFilms.contains(x)) sequels.add(((long) id << 32) | x);
            for (int x : l2.previous()) if (x != id && profileFilms.contains(x)) sequels.add(((long) x << 32) | id);

            // серии: пара «фильм, серия» — одна строка, номер — из всех P1545 пары
            TreeMap<Integer, List<String>> ser = new TreeMap<>();
            for (SourceData.SeriesRef s : src.series().getOrDefault(id, List.of())) {
                ser.computeIfAbsent(s.series(), k -> new ArrayList<>(1)).add(s.ordinal());
            }
            for (Map.Entry<Integer, List<String>> s : ser.entrySet()) {
                if (Rules.itemName(src.itemLabels().get(s.getKey())) == null) continue;
                out.moviesSeries.add(new MovieSeries(id, s.getKey(), Rules.ordinal(s.getValue())));
                seriesIds.add(s.getKey());
            }

            // награды и номинации: тройка «фильм, награда, выиграна ли» — одна строка, год — из всех её дат
            TreeMap<Long, List<SourceData.DateStmt>> aw = new TreeMap<>();
            for (SourceData.AwardStmt a : src.awards().getOrDefault(id, List.of())) {
                aw.computeIfAbsent(((long) a.award() << 1) | (a.won() ? 1 : 0), k -> new ArrayList<>(1)).add(a.date());
            }
            for (Map.Entry<Long, List<SourceData.DateStmt>> a : aw.entrySet()) {
                int award = (int) (a.getKey() >>> 1);
                if (Rules.itemName(src.itemLabels().get(award)) == null) continue;
                out.moviesAwards.add(new MovieAward(id, award, (a.getKey() & 1) == 1, Rules.awardYear(a.getValue())));
                awardIds.add(award);
            }

            // выход по странам: точность до дня, страна с ISO3; на страну — самая ранняя дата
            TreeMap<String, LocalDate> rel = new TreeMap<>();
            for (SourceData.PlaceDate pd : src.releases().getOrDefault(id, List.of())) {
                LocalDate day = Rules.releaseDay(pd.date());
                if (day == null) continue;
                String code = Rules.countryCode(src.countries().get(pd.place()), ex);
                if (code != null) rel.merge(code, day, (a, b) -> a.isAfter(b) ? b : a);
            }
            for (Map.Entry<String, LocalDate> r : rel.entrySet()) out.releases.add(new Release(id, r.getKey(), r.getValue()));

            // названия по языкам
            for (Map.Entry<String, String> t : Rules.titles(src.filmLabels().get(id), src.labelsAll().get(id)).entrySet()) {
                out.titles.add(new Title(id, t.getKey(), t.getValue()));
            }
        }
        for (long s : sequels) out.sequels.add(new Pair((int) (s >>> 32), (int) s));

        for (Map.Entry<Integer, List<Integer>> e : filmsOfDirector.entrySet()) {
            Human h = humans.get(e.getKey());
            out.directors.add(new Director(e.getKey(), h.first(), h.last(), h.nameRu()));
            int withGenre = 0;
            TreeMap<Integer, Integer> perGenre = new TreeMap<>();
            for (int f : new TreeSet<>(e.getValue())) {
                TreeSet<Integer> g = genresOf.get(f);
                if (g.isEmpty()) continue;
                withGenre++;
                for (int x : g) perGenre.merge(x, 1, Integer::sum);
            }
            for (Map.Entry<Integer, Integer> g : perGenre.entrySet()) {
                out.directorsGenres.add(new DirectorGenre(e.getKey(), g.getKey(), Rules.prob(g.getValue(), withGenre)));
            }
        }

        // роли и актёры профиля; пол, где источник молчит, — от доли F среди актёров full с полом из источника (одна на
        // все профили: пол актёра — один во всех профилях)
        TreeSet<Integer> actorIds = new TreeSet<>(), fullActors = new TreeSet<>();
        for (long k : pairs) {
            int a = (int) (k >>> 32), f = (int) k;
            fullActors.add(a);
            if (!profileFilms.contains(f)) continue;
            out.roles.add(new Role(a, f, Rules.roles(rolesOf.get(k))));
            actorIds.add(a);
        }
        long known = 0, female = 0;
        for (int a : fullActors) {
            Names.Gender g = humans.get(a).gender();
            if (g.gender() != null) {
                known++;
                if (g.gender().equals("F")) female++;
            }
        }
        Map<Names.GenderPath, Long> paths = new EnumMap<>(Names.GenderPath.class);
        for (Names.GenderPath gp : Names.GenderPath.values()) paths.put(gp, 0L);
        for (int a : actorIds) paths.merge(humans.get(a).gender().path(), 1L, Long::sum);
        Set<Integer> notTakenInProfile = new TreeSet<>();
        for (int i = 0; i < cast.size(); i++) {
            if (!profileFilms.contains(cast.film(i))) continue;
            Human h = humans.get(cast.person(i));
            if (h != null && h.gender().path() == Names.GenderPath.NOT_TAKEN) notTakenInProfile.add(cast.person(i));
        }
        paths.put(Names.GenderPath.NOT_TAKEN, (long) notTakenInProfile.size());
        double share = known == 0 ? 0.5 : (double) female / known;
        RandomStreams.Stream genderStream = streams.stream(GENDER_STREAM);
        for (int a : actorIds) {
            Human h = humans.get(a);
            out.actors.add(new Actor(a, h.first(), h.last(), gender(a, h, genderStream, share), h.nameRu()));
        }

        // люди профиля: актёры, режиссёры и группа — один раз; пол — как у актёров, иное P21 — пусто
        TreeSet<Integer> peopleIds = new TreeSet<>(actorIds);
        peopleIds.addAll(filmsOfDirector.keySet());
        peopleIds.addAll(crewPeople);
        Map<Names.GenderPath, Long> peoplePaths = new EnumMap<>(Names.GenderPath.class);
        for (Names.GenderPath gp : Names.GenderPath.values()) peoplePaths.put(gp, 0L);
        for (int p : peopleIds) {
            Human h = humans.get(p);
            peoplePaths.merge(h.gender().path(), 1L, Long::sum);
            Rules.Life life = Rules.life(src.peopleDates().get(p), lastYear);
            SourceData.PersonLinks pl = src.peopleLinks().getOrDefault(p, SourceData.PersonLinks.NONE);
            out.people.add(new Person(p, h.first(), h.last(), h.nameRu(), gender(p, h, genderStream, share), life.birthDate(),
                    life.birthYear(), life.deathDate(), life.deathYear(), Rules.city(pl.birthPlaces(), src.places())));
            TreeSet<String> cc = new TreeSet<>();
            for (int c : pl.citizenships()) {
                String code = Rules.countryCode(src.countries().get(c), ex);
                if (code != null) cc.add(code);
            }
            for (String c : cc) out.peopleCountries.add(new Code(p, c));
        }

        // справочники — только те, на кого есть ссылка в профиле
        for (int c : companyIds) {
            String[] l = src.itemLabels().get(c);
            Integer country = src.companies().get(c);
            out.companies.add(new Company(c, Rules.itemName(l), Rules.itemNameRu(l),
                    country == null ? null : Rules.countryCode(src.countries().get(country), ex)));
        }
        for (int s : seriesIds) {
            String[] l = src.itemLabels().get(s);
            out.series.add(new Item(s, Rules.itemName(l), Rules.itemNameRu(l)));
        }
        for (int a : awardIds) {
            String[] l = src.itemLabels().get(a);
            out.awards.add(new Item(a, Rules.itemName(l), Rules.itemNameRu(l)));
        }

        stats.put("movies", (long) out.movies.size());
        for (Rules.YearPath p : Rules.YearPath.values()) stats.put("movies_year_" + p.name().toLowerCase(java.util.Locale.ROOT), yearPaths.get(p));
        for (Rules.TitlePath p : Rules.TitlePath.values()) stats.put("movies_title_" + p.name().toLowerCase(java.util.Locale.ROOT), titlePaths.get(p));
        stats.put("actors", (long) out.actors.size());
        stats.put("directors", (long) out.directors.size());
        stats.put("roles", (long) out.roles.size());
        stats.put("roles_full", (long) pairs.length);
        stats.put("people", (long) out.people.size());
        stats.put("movies_crew", (long) out.crew.size());
        stats.put("companies", (long) out.companies.size());
        stats.put("series", (long) out.series.size());
        stats.put("awards", (long) out.awards.size());
        stats.put("people_not_taken", (long) notHumans.size());
        BigDecimal shareOut = known == 0 ? null : Rules.prob((int) female, (int) known);
        return new Cinema(profile, out, paths, peoplePaths, yearPaths, titlePaths, shareOut, n, stats);
    }

    /**
     * Пол человека: из источника (P21, иначе первое имя P735); источник молчит — от seed и id ({@link Names#seeded},
     * поток {@link #GENDER_STREAM}, доля {@code F} — {@code share}); иное P21 — {@code null}. У актёра — то же, что в
     * {@code actors}.
     */
    private static String gender(int id, Human h, RandomStreams.Stream stream, double share) {
        if (h.gender().gender() != null) return h.gender().gender();
        if (h.gender().path() == Names.GenderPath.NOT_TAKEN) return null;
        return Names.seeded(stream.unit(id), share);
    }

    /** Имя потока пола там, где источник молчит (актёры и люди). */
    public static final String GENDER_STREAM = "actors.gender";
}
