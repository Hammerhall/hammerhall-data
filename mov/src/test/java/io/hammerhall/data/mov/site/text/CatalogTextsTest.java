package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Подборки по жанрам и справочник тегов. */
class CatalogTextsTest {
    static final long SEED = 20251001L;
    static final SiteTextsRu TEXTS = new SiteTextsRu();

    @Test
    @DisplayName("Подборки: по каждому жанру 1–24 — разные названия, с заглавной, до 80 знаков; жанр вне словаря — без жанра")
    void collections() {
        RandomStreams.Stream st = new RandomStreams(SEED).stream("collections");
        Set<String> all = new HashSet<>();
        for (int g = 1; g <= 24; g++) {
            Set<String> t = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                String s = TEXTS.collectionTitle(st.at(g, i), g);
                assertTrue(!s.isBlank() && s.length() <= 80 && Character.isUpperCase(s.charAt(0)), g + ": " + s);
                assertFalse(s.contains("{") || s.contains("[") || s.contains("  ") || s.endsWith("."), s);
                assertTrue(Forbidden.check(s) == null, g + ": " + s);
                t.add(s);
            }
            assertTrue(t.size() >= (g == GenreRu.ADULT ? 4 : 25), "жанр " + g + ": разных подборок " + t.size());
            all.addAll(t);
        }
        for (int g : new int[]{0, -1, 25, 1000}) {
            String s = TEXTS.collectionTitle(st.at(g), g);
            assertTrue(!s.isBlank() && Character.isUpperCase(s.charAt(0)), s);
        }
        System.out.println("разных названий подборок: " + all.size());
        assertTrue(all.size() > 800, "всего разных " + all.size());
        // свои названия жанра и общие шаблоны — оба вида
        Set<String> sci = new HashSet<>();
        for (int i = 0; i < 2000; i++) sci.add(TEXTS.collectionTitle(st.at(19, 5000 + i), 19));
        assertTrue(sci.contains("Лучшее про космос"), "своё название жанра");
        assertTrue(sci.stream().anyMatch(s -> s.contains("фантастические фильмы") || s.contains("фантастических фильмов")), "общий шаблон");
        // Adult — только свои
        for (int i = 0; i < 200; i++) assertFalse(TEXTS.collectionTitle(st.at(2, i), 2).contains("для взрослых"));
    }

    @Test
    @DisplayName("Теги: постоянный список ≈ 200 коротких тегов без повторов, строчными; id = индекс + 1")
    void tags() {
        List<String> t = TEXTS.tags();
        assertTrue(t.size() >= 190 && t.size() <= 210, "тегов " + t.size());
        Set<String> norm = new HashSet<>();
        for (String s : t) {
            assertTrue(norm.add(s.toLowerCase(Locale.ROOT).replace('ё', 'е')), "дважды: " + s);
            assertTrue(s.length() >= 3 && s.length() <= 30 && s.equals(s.strip()) && !s.contains("  "), s);
            assertEquals(s.toLowerCase(Locale.ROOT), s, "строчными: " + s);
            assertTrue(Forbidden.check(s) == null, s);
        }
        assertSame(t, new SiteTextsRu().tags(), "один и тот же список");
        assertThrows(UnsupportedOperationException.class, () -> t.add("x"));
        assertEquals("атмосферно", t.get(0), "id 1");
        assertEquals("по книге", t.get(1), "id 2");
        assertTrue(t.contains("неожиданный финал") && t.contains("на один раз"));
    }

    @Test
    @DisplayName("Детерминизм подборок: тот же Rng — то же название")
    void deterministic() {
        for (int g = 1; g <= 24; g++) {
            assertEquals(TEXTS.collectionTitle(new Rng(g * 31L), g), new SiteTextsRu().collectionTitle(new Rng(g * 31L), g));
        }
    }
}
