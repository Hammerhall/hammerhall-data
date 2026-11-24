package io.hammerhall.data.mov.build;

import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Key;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.mov.Exclusions;
import io.hammerhall.data.mov.MovSchema;
import io.hammerhall.data.mov.source.SnapshotSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Единственный модуль набора: собирает {@link Cinema} из снимка и пишет все таблицы — сначала справочники, на
 * которые ссылаются связи (жанры, фильмы, люди, актёры, режиссёры, студии, серии, награды; их закрывает сразу), затем
 * связи.
 */
public final class CinemaModule implements Module {
    /** Собранный набор — для манифеста. */
    public static final Key<Cinema> CINEMA = Key.of("mov.cinema");

    private final SnapshotSource source;
    private final Exclusions exclusions;

    public CinemaModule(SnapshotSource source, Exclusions exclusions) {
        this.source = source;
        this.exclusions = exclusions;
    }

    @Override
    public String name() {
        return "cinema";
    }

    @Override
    public List<String> tables() {
        return MovSchema.TABLES;
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        Cinema c = Cinema.build(source.load(ctx.config().input("source")), ctx.profile(), ctx.streams(), exclusions);
        ctx.put(CINEMA, c);
        ctx.log("снимок " + source.loaded().snapshot() + ": " + c.stats());

        TableSink s = ctx.sink("genres");
        for (Map.Entry<Integer, String> g : Genres.NAMES.entrySet()) s.add(g.getKey(), g.getValue());
        ctx.close("genres");

        s = ctx.sink("movies");
        for (Cinema.Movie m : c.movies()) {
            s.add(m.id(), m.title(), m.titleRu(), (short) m.year(), m.releaseDate(), m.runtime(), m.rank(), m.budgetUsd(),
                    m.boxOfficeUsd());
        }
        ctx.close("movies");

        // люди — до актёров и режиссёров: на них ссылаются actors.id и directors.id
        s = ctx.sink("people");
        for (Cinema.Person p : c.people()) {
            s.add(p.id(), p.firstName(), p.lastName(), p.nameRu(), p.gender(), p.birthDate(), small(p.birthYear()),
                    p.deathDate(), small(p.deathYear()), p.birthCityId());
        }
        ctx.close("people");

        s = ctx.sink("actors");
        for (Cinema.Actor a : c.actors()) s.add(a.id(), a.firstName(), a.lastName(), a.gender(), a.nameRu());
        ctx.close("actors");

        s = ctx.sink("directors");
        for (Cinema.Director d : c.directors()) s.add(d.id(), d.firstName(), d.lastName(), d.nameRu());
        ctx.close("directors");

        s = ctx.sink("roles");
        for (Cinema.Role r : c.roles()) s.add(r.actorId(), r.movieId(), r.role());
        ctx.close("roles");

        s = ctx.sink("movies_directors");
        for (Cinema.Pair p : c.moviesDirectors()) s.add(p.a(), p.b());
        ctx.close("movies_directors");

        s = ctx.sink("movies_genres");
        for (Cinema.Pair p : c.moviesGenres()) s.add(p.a(), p.b());
        ctx.close("movies_genres");

        s = ctx.sink("directors_genres");
        for (Cinema.DirectorGenre d : c.directorsGenres()) s.add(d.directorId(), d.genreId(), d.prob());
        ctx.close("directors_genres");

        s = ctx.sink("movies_countries");
        for (Cinema.Code x : c.moviesCountries()) s.add(x.movieId(), x.code());
        ctx.close("movies_countries");

        s = ctx.sink("movies_languages");
        for (Cinema.Code x : c.moviesLanguages()) s.add(x.movieId(), x.code());
        ctx.close("movies_languages");

        s = ctx.sink("people_countries");
        for (Cinema.Code x : c.peopleCountries()) s.add(x.movieId(), x.code());
        ctx.close("people_countries");

        s = ctx.sink("movies_crew");
        for (Cinema.CrewMember x : c.crew()) s.add(x.movieId(), x.personId(), x.job());
        ctx.close("movies_crew");

        s = ctx.sink("companies");
        for (Cinema.Company x : c.companies()) s.add(x.id(), x.name(), x.nameRu(), x.countryCode());
        ctx.close("companies");

        s = ctx.sink("movies_companies");
        for (Cinema.MovieCompany x : c.moviesCompanies()) s.add(x.movieId(), x.companyId(), x.role());
        ctx.close("movies_companies");

        s = ctx.sink("series");
        for (Cinema.Item x : c.series()) s.add(x.id(), x.name(), x.nameRu());
        ctx.close("series");

        s = ctx.sink("movies_series");
        for (Cinema.MovieSeries x : c.moviesSeries()) s.add(x.movieId(), x.seriesId(), x.ordinal());
        ctx.close("movies_series");

        s = ctx.sink("movies_sequels");
        for (Cinema.Pair p : c.sequels()) s.add(p.a(), p.b());
        ctx.close("movies_sequels");

        s = ctx.sink("awards");
        for (Cinema.Item x : c.awards()) s.add(x.id(), x.name(), x.nameRu());
        ctx.close("awards");

        s = ctx.sink("movies_awards");
        for (Cinema.MovieAward x : c.moviesAwards()) s.add(x.movieId(), x.awardId(), x.won(), small(x.year()));
        ctx.close("movies_awards");

        s = ctx.sink("movies_releases");
        for (Cinema.Release x : c.releases()) s.add(x.movieId(), x.countryCode(), x.releaseDate());
        ctx.close("movies_releases");

        s = ctx.sink("movies_titles");
        for (Cinema.Title x : c.titles()) s.add(x.movieId(), x.lang(), x.title());
        ctx.close("movies_titles");

        s = ctx.sink("movies_locations");
        for (Cinema.Pair p : c.locations()) s.add(p.a(), p.b());
        ctx.close("movies_locations");
    }

    /** Год — {@code smallint} поля; нет — NULL. */
    private static Short small(Integer year) {
        return year == null ? null : (short) (int) year;
    }

    /**
     * Свои инварианты: размер профиля, рейтинг у всех фильмов в [1.0, 9.9], у каждого актёра — роль, у каждого
     * режиссёра — фильм, доли жанров режиссёра в (0, 1], пол актёра — {@code M} или {@code F}; люди — ровно актёры,
     * режиссёры и группа профиля, пол человека — как у актёра; в справочниках (студии, серии, награды) — только те, на
     * кого ссылаются; продолжение — не сам фильм; смерть — не раньше рождения; бюджет и сборы — больше нуля. Ключи и
     * ссылки всех таблиц проверяет ядро по схеме.
     */
    @Override
    public void check(GenContext ctx, CheckReport report) {
        Cinema c = ctx.get(CINEMA);
        int want = Rules.profileSize(c.moviesFull(), c.profile());
        report.expectNone("фильмов в профиле — " + Rules.profileShare(c.profile()) + " (округление вверх)",
                c.movies().size() == want ? List.of() : List.of(c.movies().size() + " ≠ " + want),
                c.movies().size() + " из " + c.moviesFull());

        List<String> badRank = new ArrayList<>();
        for (Cinema.Movie m : c.movies()) {
            if (m.rank() == null || m.rank().scale() != 1 || m.rank().compareTo(Rank.MIN) < 0 || m.rank().compareTo(Rank.MAX) > 0) {
                badRank.add(m.id() + " (" + m.rank() + ")");
            }
        }
        report.expectNone("рейтинг у каждого фильма, 1.0…9.9, один знак", badRank, "фильмов " + c.movies().size());

        Set<Integer> withRole = new HashSet<>();
        for (Cinema.Role r : c.roles()) withRole.add(r.actorId());
        List<String> idle = new ArrayList<>();
        for (Cinema.Actor a : c.actors()) if (!withRole.contains(a.id())) idle.add(String.valueOf(a.id()));
        report.expectNone("у каждого актёра — роль в фильме профиля", idle, "актёров " + c.actors().size());

        Set<Integer> withFilm = new HashSet<>();
        for (Cinema.Pair p : c.moviesDirectors()) withFilm.add(p.a());
        List<String> idleD = new ArrayList<>();
        for (Cinema.Director d : c.directors()) if (!withFilm.contains(d.id())) idleD.add(String.valueOf(d.id()));
        report.expectNone("у каждого режиссёра — фильм профиля", idleD, "режиссёров " + c.directors().size());

        List<String> badProb = new ArrayList<>();
        for (Cinema.DirectorGenre d : c.directorsGenres()) {
            if (d.prob().signum() <= 0 || d.prob().compareTo(java.math.BigDecimal.ONE) > 0) badProb.add(d.directorId() + "/" + d.genreId());
        }
        report.expectNone("доли жанров режиссёра — в (0, 1]", badProb, "строк " + c.directorsGenres().size());

        List<String> badGender = new ArrayList<>();
        for (Cinema.Actor a : c.actors()) if (!a.gender().equals("M") && !a.gender().equals("F")) badGender.add(String.valueOf(a.id()));
        report.expectNone("пол актёра — M или F, всегда", badGender, "пути: " + c.genderPaths() + ", доля F " + c.femaleShare());

        Set<Integer> wantPeople = new java.util.TreeSet<>();
        for (Cinema.Actor a : c.actors()) wantPeople.add(a.id());
        for (Cinema.Director d : c.directors()) wantPeople.add(d.id());
        for (Cinema.CrewMember x : c.crew()) wantPeople.add(x.personId());
        Set<Integer> havePeople = new java.util.TreeSet<>();
        for (Cinema.Person p : c.people()) havePeople.add(p.id());
        report.expectNone("люди — ровно актёры, режиссёры и съёмочная группа профиля", diff(wantPeople, havePeople),
                "людей " + havePeople.size());

        Map<Integer, String> actorGender = new java.util.HashMap<>();
        for (Cinema.Actor a : c.actors()) actorGender.put(a.id(), a.gender());
        List<String> badPeople = new ArrayList<>();
        for (Cinema.Person p : c.people()) {
            String g = actorGender.get(p.id());
            if (g != null && !g.equals(p.gender())) badPeople.add(p.id() + " (пол " + p.gender() + ", в actors " + g + ")");
            if (p.deathYear() != null && (p.birthYear() == null || p.deathYear() < p.birthYear())) badPeople.add(p.id() + " (годы)");
            if (p.deathDate() != null && (p.birthDate() == null || p.deathDate().isBefore(p.birthDate()))) badPeople.add(p.id() + " (даты)");
        }
        report.expectNone("пол человека — как у актёра; смерть — не раньше рождения", badPeople, "пути пола: " + c.peopleGenderPaths());

        Set<Integer> usedCompanies = new HashSet<>(), usedSeries = new HashSet<>(), usedAwards = new HashSet<>();
        for (Cinema.MovieCompany x : c.moviesCompanies()) usedCompanies.add(x.companyId());
        for (Cinema.MovieSeries x : c.moviesSeries()) usedSeries.add(x.seriesId());
        for (Cinema.MovieAward x : c.moviesAwards()) usedAwards.add(x.awardId());
        List<String> idleItems = new ArrayList<>();
        for (Cinema.Company x : c.companies()) if (!usedCompanies.contains(x.id())) idleItems.add("companies " + x.id());
        for (Cinema.Item x : c.series()) if (!usedSeries.contains(x.id())) idleItems.add("series " + x.id());
        for (Cinema.Item x : c.awards()) if (!usedAwards.contains(x.id())) idleItems.add("awards " + x.id());
        report.expectNone("в справочниках — только те, на кого ссылаются фильмы профиля", idleItems,
                "студий " + c.companies().size() + ", серий " + c.series().size() + ", наград " + c.awards().size());

        List<String> badLinks = new ArrayList<>();
        for (Cinema.Pair p : c.sequels()) if (p.a() == p.b()) badLinks.add("movies_sequels " + p.a());
        for (Cinema.Movie m : c.movies()) {
            if (m.budgetUsd() != null && m.budgetUsd() <= 0) badLinks.add("budget_usd " + m.id());
            if (m.boxOfficeUsd() != null && m.boxOfficeUsd() <= 0) badLinks.add("box_office_usd " + m.id());
        }
        report.expectNone("продолжение — не сам фильм; бюджет и сборы — больше нуля", badLinks,
                "продолжений " + c.sequels().size());
        report.ok("год и название фильмов", "год по путям " + c.yearPaths() + ", название по путям " + c.titlePaths());
        report.ok("жанры", "у фильмов full с P136 жанр из словаря — " + c.stats().get("movies_full_with_p136_genre") + " из "
                + c.stats().get("movies_full_with_p136"));
    }

    @Override
    public List<Aggregate> aggregates() {
        return List.of(
                Aggregate.min("movies", "year"),
                Aggregate.max("movies", "year"),
                Aggregate.sum("movies", "rank"),
                Aggregate.min("movies", "rank"),
                Aggregate.max("movies", "rank"),
                Aggregate.sum("movies", "runtime"),
                Aggregate.countEq("actors", "gender", "F"),
                Aggregate.distinct("movies_genres", "genre_id"),
                Aggregate.sum("directors_genres", "prob"),
                Aggregate.distinct("movies_countries", "country_code"),
                Aggregate.distinct("movies_languages", "language_code"),
                Aggregate.sum("movies", "budget_usd"),
                Aggregate.sum("movies", "box_office_usd"),
                Aggregate.countEq("people", "gender", "F"),
                Aggregate.nulls("people", "gender"),
                Aggregate.min("people", "birth_year"),
                Aggregate.nulls("people", "birth_city_id"),
                Aggregate.distinct("people_countries", "country_code"),
                Aggregate.countEq("movies_crew", "job", "writer"),
                Aggregate.countEq("movies_companies", "role", Rules.PRODUCTION),
                Aggregate.nulls("movies_series", "ordinal"),
                Aggregate.countEq("movies_awards", "won", "t"),
                Aggregate.distinct("movies_releases", "country_code"),
                Aggregate.distinct("movies_titles", "lang"),
                Aggregate.distinct("movies_locations", "city_id"));
    }

    /** Чего нет в одном множестве из другого — в обе стороны, для отчёта. */
    private static List<String> diff(Set<Integer> want, Set<Integer> have) {
        List<String> out = new ArrayList<>();
        for (int x : want) if (!have.contains(x)) out.add("нет " + x);
        for (int x : have) if (!want.contains(x)) out.add("лишний " + x);
        return out;
    }
}
