package io.hammerhall.data.mov;

import io.hammerhall.data.mov.build.Genres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.hammerhall.data.mov.build.Genres.ACTION;
import static io.hammerhall.data.mov.build.Genres.ADVENTURE;
import static io.hammerhall.data.mov.build.Genres.ANIMATION;
import static io.hammerhall.data.mov.build.Genres.COMEDY;
import static io.hammerhall.data.mov.build.Genres.CRIME;
import static io.hammerhall.data.mov.build.Genres.DOCUMENTARY;
import static io.hammerhall.data.mov.build.Genres.DRAMA;
import static io.hammerhall.data.mov.build.Genres.HISTORY;
import static io.hammerhall.data.mov.build.Genres.HORROR;
import static io.hammerhall.data.mov.build.Genres.ROMANCE;
import static io.hammerhall.data.mov.build.Genres.SHORT;
import static io.hammerhall.data.mov.build.Genres.THRILLER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Жанры: словарь, якоря на разной глубине P279, составные, по классу и хронометражу. */
class GenresTest {
    /** Граф P279: элемент → предки. */
    static Map<Integer, int[]> graph(int[]... edges) {
        Map<Integer, List<Integer>> m = new HashMap<>();
        for (int[] e : edges) m.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
        Map<Integer, int[]> out = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : m.entrySet()) out.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        return out;
    }

    @Test
    @DisplayName("Словарь: ровно 24 жанра прежнего набора, id 1–24, написание Sci-Fi и Film noir")
    void dictionary() {
        assertEquals(List.of("Action", "Adult", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama",
                "Family", "Fantasy", "Film noir", "History", "Horror", "Music", "Musical", "Mystery", "Romance", "Sci-Fi",
                "Short", "Sport", "Thriller", "War", "Western"), List.copyOf(Genres.NAMES.values()));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24),
                List.copyOf(Genres.NAMES.keySet()));
        for (List<Integer> g : Genres.ANCHORS.values()) assertFalse(g.contains(Genres.ADULT), "Adult — никогда");
        assertEquals(List.of(5), Genres.ANCHORS.get(645928), "biographical film → Biography");
        assertEquals(List.of(21), Genres.ANCHORS.get(1339864), "sport film → Sport");
        assertEquals(List.of(HISTORY), Genres.ANCHORS.get(17013749));
    }

    @Test
    @DisplayName("Якорь — само значение (уровень 0): его жанры; составной — два жанра; предки уже не смотрятся")
    void anchorAtZero() {
        Map<Integer, int[]> g = graph(new int[]{860626, 157443}, new int[]{2421031, 959790});
        assertEquals(Set.of(COMEDY, ROMANCE), Genres.ofValue(860626, g), "romantic comedy");
        assertEquals(Set.of(CRIME, THRILLER), Genres.ofValue(2421031, g), "neo-noir — Crime + Thriller, не только Crime предка");
        assertEquals(Set.of(HISTORY), Genres.ofValue(7168625, Map.of()), "historical drama — History, как в контракте");
        assertEquals(Set.of(ACTION), Genres.ofValue(846544, Map.of()), "disaster film — Action");
    }

    @Test
    @DisplayName("Якорь глубже: первый уровень с якорями; на нём — все якоря; глубже не идём")
    void anchorDeeper() {
        Map<Integer, int[]> g = graph(new int[]{1, 2}, new int[]{2, 2421031},
                new int[]{3, 188473}, new int[]{3, 4}, new int[]{4, 319221},
                new int[]{5, 157443}, new int[]{5, 200092});
        assertEquals(Set.of(CRIME, THRILLER), Genres.ofValue(1, g), "уровень 2");
        assertEquals(Set.of(ACTION), Genres.ofValue(3, g), "action на уровне 1, adventure — на 2: только Action");
        assertEquals(Set.of(ADVENTURE), Genres.ofValue(4, g));
        assertEquals(Set.of(COMEDY, HORROR), Genres.ofValue(5, g), "два якоря одного уровня");
    }

    @Test
    @DisplayName("Mockumentary (Q459435) — Comedy: якорь уровня 0 перекрывает предков (документальный фильм и пародия)")
    void mockumentary() {
        Map<Integer, int[]> g = graph(new int[]{459435, 93204}, new int[]{459435, 622548});
        assertEquals(Set.of(COMEDY), Genres.ofValue(459435, g));
        assertEquals(List.of(COMEDY), Genres.ANCHORS.get(459435));
        assertEquals(Set.of(DOCUMENTARY), Genres.ofValue(472637, graph(new int[]{472637, 93204})), "docufiction — как есть");
    }

    @Test
    @DisplayName("Глубина: якорь на уровне 6 — да, на уровне 7 — нет; петля P279 и элемент без предков — пусто")
    void depthAndLoops() {
        Map<Integer, int[]> six = graph(new int[]{10, 11}, new int[]{11, 12}, new int[]{12, 13}, new int[]{13, 14},
                new int[]{14, 15}, new int[]{15, 130232});
        assertEquals(Set.of(DRAMA), Genres.ofValue(10, six), "уровень 6");
        Map<Integer, int[]> seven = graph(new int[]{9, 10}, new int[]{10, 11}, new int[]{11, 12}, new int[]{12, 13},
                new int[]{13, 14}, new int[]{14, 15}, new int[]{15, 130232});
        assertEquals(Set.of(), Genres.ofValue(9, seven), "уровень 7");
        assertEquals(Set.of(), Genres.ofValue(20, graph(new int[]{20, 21}, new int[]{21, 20})));
        assertEquals(Set.of(), Genres.ofValue(99, Map.of()));
        Map<Integer, int[]> diamond = graph(new int[]{30, 31}, new int[]{30, 32}, new int[]{31, 33}, new int[]{32, 33},
                new int[]{33, 471839});
        assertEquals(Set.of(19), Genres.ofValue(30, diamond), "ромб: общий предок — один раз");
    }

    @Test
    @DisplayName("Фильм: класс (анимация, документальный, короткометражный) + хронометраж ≤ 40 + P136 — объединение")
    void film() {
        Genres.Resolver r = new Genres.Resolver(graph(new int[]{5, 157443}, new int[]{5, 200092}));
        assertEquals(Set.of(ANIMATION, SHORT), Genres.ofFilm(new int[]{17517379}, 10, new int[0], r));
        assertEquals(Set.of(ANIMATION), Genres.ofFilm(new int[]{20650540}, 120, new int[0], r), "аниме");
        assertEquals(Set.of(DOCUMENTARY, SHORT), Genres.ofFilm(new int[]{93204}, 40, new int[0], r), "40 минут — Short");
        assertEquals(Set.of(DOCUMENTARY), Genres.ofFilm(new int[]{93204}, 41, new int[0], r));
        assertEquals(Set.of(SHORT), Genres.ofFilm(new int[]{24862}, null, new int[0], r));
        assertEquals(Set.of(COMEDY, HORROR, DRAMA), Genres.ofFilm(new int[]{11424}, 95, new int[]{5, 130232}, r));
        assertTrue(Genres.ofFilm(new int[]{11424, 506240, 226730}, null, new int[]{999}, r).isEmpty(),
                "телефильм и немой — без жанра, P136 без якоря — тоже");
    }
}
