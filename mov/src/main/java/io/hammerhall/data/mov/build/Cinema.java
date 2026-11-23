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
 * Набор целиком — функция снимка источников, профиля, seed и списков исключений ({@link #build}): строки десяти таблиц в
 * порядке их ключей. Сначала собирается {@code full} — все фильмы, люди и роли (по нему считаются популярность и
 * рейтинг); профиль — первые фильмы по популярности, люди и связи — только по ним.
 */
public final class Cinema {
    public record Movie(int id, String title, String titleRu, int year, LocalDate releaseDate, Integer runtime, BigDecimal rank) {
    }

    public record Actor(int id, String firstName, String lastName, String gender, String nameRu) {
    }

    public record Director(int id, String firstName, String lastName, String nameRu) {
    }

    public record Role(int actorId, int movieId, String role) {
    }

    /** Пара id: {@code movies_directors} (режиссёр, фильм), {@code movies_genres} (фильм, жанр). */
    public record Pair(int a, int b) {
    }

    public record DirectorGenre(int directorId, int genreId, BigDecimal prob) {
    }

    /** Код фильма: {@code movies_countries}, {@code movies_languages}. */
    public record Code(int movieId, String code) {
    }

    private final Profile profile;
    private final List<Movie> movies;
    private final List<Actor> actors;
    private final List<Director> directors;
    private final List<Role> roles;
    private final List<Pair> moviesDirectors, moviesGenres;
    private final List<DirectorGenre> directorsGenres;
    private final List<Code> moviesCountries, moviesLanguages;
    private final Map<Names.GenderPath, Long> genderPaths;
    private final BigDecimal femaleShare;
    private final int moviesFull;
    private final Map<String, Long> stats;

    private Cinema(Profile profile, List<Movie> movies, List<Actor> actors, List<Director> directors, List<Role> roles,
                   List<Pair> moviesDirectors, List<Pair> moviesGenres, List<DirectorGenre> directorsGenres,
                   List<Code> moviesCountries, List<Code> moviesLanguages, Map<Names.GenderPath, Long> genderPaths,
                   BigDecimal femaleShare, int moviesFull, Map<String, Long> stats) {
        this.profile = profile;
        this.movies = List.copyOf(movies);
        this.actors = List.copyOf(actors);
        this.directors = List.copyOf(directors);
        this.roles = List.copyOf(roles);
        this.moviesDirectors = List.copyOf(moviesDirectors);
        this.moviesGenres = List.copyOf(moviesGenres);
        this.directorsGenres = List.copyOf(directorsGenres);
        this.moviesCountries = List.copyOf(moviesCountries);
        this.moviesLanguages = List.copyOf(moviesLanguages);
        this.genderPaths = Collections.unmodifiableMap(new EnumMap<>(genderPaths));
        this.femaleShare = femaleShare;
        this.moviesFull = moviesFull;
        this.stats = Collections.unmodifiableMap(stats);
    }

    public Profile profile() {
        return profile;
    }

    public List<Movie> movies() {
        return movies;
    }

    public List<Actor> actors() {
        return actors;
    }

    public List<Director> directors() {
        return directors;
    }

    public List<Role> roles() {
        return roles;
    }

    public List<Pair> moviesDirectors() {
        return moviesDirectors;
    }

    public List<Pair> moviesGenres() {
        return moviesGenres;
    }

    public List<DirectorGenre> directorsGenres() {
        return directorsGenres;
    }

    public List<Code> moviesCountries() {
        return moviesCountries;
    }

    public List<Code> moviesLanguages() {
        return moviesLanguages;
    }

    /** Актёров профиля по пути пола; {@link Names.GenderPath#NOT_TAKEN} — люди состава профиля, которых нет в актёрах. */
    public Map<Names.GenderPath, Long> genderPaths() {
        return genderPaths;
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
    private record Film(int id, String title, String titleRu, Rules.Released released, Integer runtime, TreeSet<Integer> genres,
                        SourceData.Links links) {
    }

    /** Человек, годный в набор: имя, фамилия, имя по-русски, пол актёра (с путём). */
    private record Human(String first, String last, String nameRu, Names.Gender gender) {
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
            Rules.Released rel = Rules.released(src.dates().get(id), lastYear);
            if (rel == null) {
                noYear++;
                continue;
            }
            String[] labels = src.filmLabels().get(id);
            String title = Rules.title(labels, src.titles().get(id));
            if (title == null) {
                noTitle++;
                continue;
            }
            Integer runtime = Rules.runtime(src.seconds().get(id));
            SourceData.Links links = src.links().getOrDefault(id, SourceData.Links.NONE);
            pre.put(id, new Film(id, title, Rules.titleRu(labels), rel, runtime,
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

        // ---- люди: годен ли человек (человек, есть имя); пол актёра
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

        // ---- таблицы профиля
        List<Movie> movies = new ArrayList<>();
        List<Pair> moviesGenres = new ArrayList<>(), moviesDirectors = new ArrayList<>();
        List<Code> countries = new ArrayList<>(), languages = new ArrayList<>();
        Set<Integer> profileFilms = new HashSet<>();
        TreeMap<Integer, List<Integer>> filmsOfDirector = new TreeMap<>();
        Map<Integer, TreeSet<Integer>> genresOf = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (!in[i]) continue;
            Film f = films.get(i);
            profileFilms.add(f.id());
            movies.add(new Movie(f.id(), f.title(), f.titleRu(), f.released().year(), f.released().date(), f.runtime(), rank[i]));
            genresOf.put(f.id(), f.genres());
            for (int g : f.genres()) moviesGenres.add(new Pair(f.id(), g));
            for (int d : f.links().directors()) {
                if (human.apply(d) == null) continue;
                moviesDirectors.add(new Pair(d, f.id()));
                filmsOfDirector.computeIfAbsent(d, k -> new ArrayList<>()).add(f.id());
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
            for (String c : cc) countries.add(new Code(f.id(), c));
            for (String c : lc) languages.add(new Code(f.id(), c));
        }

        List<Director> directors = new ArrayList<>();
        List<DirectorGenre> directorsGenres = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> e : filmsOfDirector.entrySet()) {
            Human h = humans.get(e.getKey());
            directors.add(new Director(e.getKey(), h.first(), h.last(), h.nameRu()));
            int withGenre = 0;
            TreeMap<Integer, Integer> perGenre = new TreeMap<>();
            for (int f : new TreeSet<>(e.getValue())) {
                TreeSet<Integer> g = genresOf.get(f);
                if (g.isEmpty()) continue;
                withGenre++;
                for (int x : g) perGenre.merge(x, 1, Integer::sum);
            }
            for (Map.Entry<Integer, Integer> g : perGenre.entrySet()) {
                directorsGenres.add(new DirectorGenre(e.getKey(), g.getKey(), Rules.prob(g.getValue(), withGenre)));
            }
        }

        // роли и актёры профиля; пол, где источник молчит, — от доли F среди актёров full с полом из источника (одна на
        // все профили: пол актёра — один во всех профилях)
        List<Role> roles = new ArrayList<>();
        TreeSet<Integer> actorIds = new TreeSet<>(), fullActors = new TreeSet<>();
        for (long k : pairs) {
            int a = (int) (k >>> 32), f = (int) k;
            fullActors.add(a);
            if (!profileFilms.contains(f)) continue;
            roles.add(new Role(a, f, Rules.roles(rolesOf.get(k))));
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
        List<Actor> actors = new ArrayList<>();
        for (int a : actorIds) {
            Human h = humans.get(a);
            String g = h.gender().gender() != null ? h.gender().gender() : Names.seeded(genderStream.unit(a), share);
            actors.add(new Actor(a, h.first(), h.last(), g, h.nameRu()));
        }

        stats.put("movies", (long) movies.size());
        stats.put("actors", (long) actors.size());
        stats.put("directors", (long) directors.size());
        stats.put("roles", (long) roles.size());
        stats.put("roles_full", (long) pairs.length);
        stats.put("people_not_taken", (long) notHumans.size());
        BigDecimal shareOut = known == 0 ? null : Rules.prob((int) female, (int) known);
        return new Cinema(profile, movies, actors, directors, roles, moviesDirectors, moviesGenres, directorsGenres, countries,
                languages, paths, shareOut, n, stats);
    }

    /** Имя потока пола актёра там, где источник молчит. */
    public static final String GENDER_STREAM = "actors.gender";
}
