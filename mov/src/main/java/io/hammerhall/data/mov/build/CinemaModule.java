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
 * Единственный модуль набора: собирает {@link Cinema} из снимка и пишет десять таблиц — сначала справочники, на
 * которые ссылаются связи (жанры, фильмы, актёры, режиссёры; их закрывает сразу), затем связи.
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
            s.add(m.id(), m.title(), m.titleRu(), (short) m.year(), m.releaseDate(), m.runtime(), m.rank());
        }
        ctx.close("movies");

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
    }

    /**
     * Свои инварианты: размер профиля, рейтинг у всех фильмов в [1.0, 9.9], у каждого актёра — роль, у каждого
     * режиссёра — фильм, доли жанров режиссёра в (0, 1], пол актёра — {@code M} или {@code F}.
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
                Aggregate.distinct("movies_languages", "language_code"));
    }
}
