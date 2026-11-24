package io.hammerhall.data.mov;

import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.mov.build.Cinema;
import io.hammerhall.data.mov.site.SiteInput;
import io.hammerhall.data.mov.source.SourceData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Переходник синтетики {@link SiteInput#of} на крошечном снимке: фильмы профиля, жанры, люди для текстов, популярность. */
class SiteInputTest {
    @Test
    @DisplayName("SiteInput из собранного кино: фильмы — те же, жанры и режиссёры — по связям, актёров не больше двух, место по популярности — перестановка")
    void fromCinema() throws IOException {
        SourceData src = CinemaTest.tiny();
        Cinema c = CinemaTest.cinema(Profile.FULL);
        SiteInput in = SiteInput.of(c, src);
        assertEquals(c.movies().size(), in.movies().size());
        assertEquals(c.moviesFull(), in.moviesFull());
        Set<Integer> places = new HashSet<>();
        Map<Integer, Integer> genres = new HashMap<>();
        for (Cinema.Pair p : c.moviesGenres()) genres.merge(p.a(), 1, Integer::sum);
        for (int i = 0; i < in.movies().size(); i++) {
            SiteInput.Movie m = in.movies().get(i);
            Cinema.Movie cm = c.movies().get(i);
            assertEquals(cm.id(), m.id());
            assertEquals(cm.title(), m.title());
            assertEquals(cm.rank().doubleValue(), m.rank());
            assertEquals(genres.getOrDefault(m.id(), 0), m.genres().length);
            assertTrue(m.actors().size() <= 2);
            assertEquals(m.directorIds().length, m.directors().size());
            if (m.seriesNo() != null) assertTrue(m.seriesId() != null);
            places.add(m.popularity());
        }
        assertEquals(in.movies().size(), places.size());
        assertTrue(places.contains(0) && places.contains(in.movies().size() - 1));
        // у фильма 101 режиссёр 501 — «Стивен Халден» (имя по-русски), актёры — с именами
        SiteInput.Movie m101 = in.movies().stream().filter(m -> m.id() == 101).findFirst().orElseThrow();
        assertEquals(List.of("Стивен Халден"), m101.directors());
        assertTrue(!m101.actors().isEmpty());
    }
}
