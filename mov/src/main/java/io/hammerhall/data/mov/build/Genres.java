package io.hammerhall.data.mov.build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Жанры набора: словарь из 24 жанров (id и названия — как в прежнем наборе фильмов) и правило жанров фильма — по
 * классам P31, по хронометражу и по P136 через таблицу якорей ({@link #ANCHORS}) и предков по P279.
 */
public final class Genres {
    private Genres() {
    }

    public static final int ACTION = 1, ADULT = 2, ADVENTURE = 3, ANIMATION = 4, BIOGRAPHY = 5, COMEDY = 6, CRIME = 7,
            DOCUMENTARY = 8, DRAMA = 9, FAMILY = 10, FANTASY = 11, FILM_NOIR = 12, HISTORY = 13, HORROR = 14, MUSIC = 15,
            MUSICAL = 16, MYSTERY = 17, ROMANCE = 18, SCI_FI = 19, SHORT = 20, SPORT = 21, THRILLER = 22, WAR = 23,
            WESTERN = 24;

    /** Словарь: id → название, ровно как в прежнем наборе (написание — как есть: {@code Sci-Fi}, {@code Film noir}). */
    public static final Map<Integer, String> NAMES = names();

    private static Map<Integer, String> names() {
        Map<Integer, String> m = new LinkedHashMap<>();
        String[] n = {"Action", "Adult", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama",
                "Family", "Fantasy", "Film noir", "History", "Horror", "Music", "Musical", "Mystery", "Romance", "Sci-Fi",
                "Short", "Sport", "Thriller", "War", "Western"};
        for (int i = 0; i < n.length; i++) m.put(i + 1, n[i]);
        return Collections.unmodifiableMap(m);
    }

    // ---------------------------------------------------------------- классы фильма (P31) и хронометраж

    /** Классы анимации: фильм такого класса — {@code Animation}. */
    public static final Set<Integer> ANIMATION_CLASSES = Set.of(202866, 29168811, 20650540, 17517379, 18089587);
    /** Документальный фильм (Q93204) — {@code Documentary}. */
    public static final int DOCUMENTARY_CLASS = 93204;
    /** Короткометражный (Q24862) и анимационный короткометражный (Q17517379) — {@code Short}. */
    public static final Set<Integer> SHORT_CLASSES = Set.of(24862, 17517379);
    /** Хронометраж не больше стольких минут — {@code Short}. */
    public static final int SHORT_MAX_MINUTES = 40;
    /** Глубина обхода предков P279 от значения P136 (само значение — уровень 0). */
    public static final int MAX_DEPTH = 6;

    // ---------------------------------------------------------------- якоря

    /**
     * Якоря: элемент жанра Wikidata (номер Q) → жанры словаря. Составной жанр — два жанра. Таблица нейтральная, по
     * меткам элементов (сверена с метками снимка {@code mov-source-20261123}: якорь — элемент, чья метка называет жанр
     * словаря); {@code Adult} в ней нет — таких фильмов в снимке нет.
     */
    public static final Map<Integer, List<Integer>> ANCHORS = anchors();

    private static void a(Map<Integer, List<Integer>> m, int q, Integer... genres) {
        if (m.put(q, List.of(genres)) != null) throw new IllegalStateException("якорь дважды: Q" + q);
    }

    private static Map<Integer, List<Integer>> anchors() {
        Map<Integer, List<Integer>> m = new LinkedHashMap<>();
        a(m, 188473, ACTION);                 // action film
        a(m, 1033891, ACTION);                // martial arts film
        a(m, 1535153, ACTION);                // superhero film
        a(m, 846544, ACTION);                 // disaster film
        a(m, 754803, ACTION);                 // wuxia
        a(m, 15637293, ACTION);               // action anime and manga
        a(m, 319221, ADVENTURE);              // adventure film
        a(m, 15712918, ADVENTURE);            // adventure anime and manga
        a(m, 202866, ANIMATION);              // animated film
        a(m, 29168811, ANIMATION);            // animated feature film
        a(m, 20650540, ANIMATION);            // anime film
        a(m, 17517379, ANIMATION, SHORT);     // animated short film
        a(m, 18089587, ANIMATION);            // stop-motion animated film
        a(m, 11425, ANIMATION);               // animation
        a(m, 645928, BIOGRAPHY);              // biographical film
        a(m, 36279, BIOGRAPHY);               // biography
        a(m, 157443, COMEDY);                 // comedy film
        a(m, 40831, COMEDY);                  // comedy
        a(m, 5778924, COMEDY);                // black comedy film
        a(m, 622548, COMEDY);                 // parody film
        a(m, 459435, COMEDY);                 // mockumentary — пародия с актёрами, не документальный (§11)
        a(m, 128758, COMEDY);                 // satire
        a(m, 859369, COMEDY, DRAMA);          // comedy drama
        a(m, 860626, COMEDY, ROMANCE);        // romantic comedy
        a(m, 224700, COMEDY, HORROR);         // comedy horror
        a(m, 959790, CRIME);                  // crime film
        a(m, 7444356, CRIME);                 // gangster film
        a(m, 496523, CRIME);                  // heist film
        a(m, 2421031, CRIME, THRILLER);       // neo-noir
        a(m, 3541116, CRIME);                 // true crime
        a(m, 186424, CRIME, MYSTERY);         // detective fiction
        a(m, 93204, DOCUMENTARY);             // documentary film
        a(m, 110593123, DOCUMENTARY, MUSIC);  // music documentary film
        a(m, 1800833, DOCUMENTARY, MUSIC);    // rockumentary
        a(m, 130232, DRAMA);                  // drama film
        a(m, 21010853, DRAMA);                // drama fiction
        a(m, 80930, DRAMA);                   // tragedy
        a(m, 3038946, DRAMA, ROMANCE);        // romantic drama
        a(m, 1361932, FAMILY);                // family film
        a(m, 2143665, FAMILY);                // children's film
        a(m, 157394, FANTASY);                // fantasy film
        a(m, 132311, FANTASY);                // fantasy
        a(m, 699, FANTASY);                   // fairy tale
        a(m, 185867, FILM_NOIR);              // film noir
        a(m, 17013749, HISTORY);              // historical film
        a(m, 7168625, HISTORY);               // historical drama
        a(m, 1196408, HISTORY);               // historical fiction
        a(m, 200092, HORROR);                 // horror film
        a(m, 853630, HORROR);                 // slasher film
        a(m, 3072049, HORROR);                // zombie film
        a(m, 1342372, HORROR);                // monster film
        a(m, 16575965, HORROR);               // horror fiction
        a(m, 430525, MUSIC);                  // concert film
        a(m, 109733358, MUSIC);               // music-themed film
        a(m, 842256, MUSICAL);                // musical film
        a(m, 2743, MUSICAL);                  // musical play
        a(m, 1200678, MYSTERY);               // mystery film
        a(m, 6585139, MYSTERY);               // mystery fiction
        a(m, 1054574, ROMANCE);               // romance film
        a(m, 471839, SCI_FI);                 // science fiction film
        a(m, 24925, SCI_FI);                  // science fiction
        a(m, 24862, SHORT);                   // short film
        a(m, 1339864, SPORT);                 // sport film
        a(m, 2484376, THRILLER);              // thriller film
        a(m, 182015, THRILLER);               // thriller
        a(m, 590103, THRILLER);               // psychological thriller
        a(m, 2297927, THRILLER);              // spy film
        a(m, 11304653, THRILLER);             // suspense film
        a(m, 369747, WAR);                    // war film
        a(m, 172980, WESTERN);                // Western film
        a(m, 21590660, WESTERN);              // Western
        for (List<Integer> g : m.values()) {
            for (int x : g) {
                if (!NAMES.containsKey(x) || x == ADULT) throw new IllegalStateException("якорь на жанр вне словаря: " + x);
            }
        }
        return Collections.unmodifiableMap(m);
    }

    /**
     * Жанры одного значения P136: само значение и его предки по P279 обходом вширь (уровень 0 — значение, глубина —
     * не больше {@link #MAX_DEPTH}); первый уровень, на котором встретились якоря, даёт жанры всех якорей этого уровня.
     * Якорей нет до глубины — пусто. Петли P279 не мешают: элемент обходится один раз.
     *
     * @param parents элемент → его P279 ({@code genre_items}); нет элемента — нет предков
     */
    public static Set<Integer> ofValue(int value, Map<Integer, int[]> parents) {
        Set<Integer> seen = new HashSet<>();
        List<Integer> level = List.of(value);
        seen.add(value);
        for (int depth = 0; depth <= MAX_DEPTH && !level.isEmpty(); depth++) {
            TreeSet<Integer> found = new TreeSet<>();
            for (int q : level) {
                List<Integer> g = ANCHORS.get(q);
                if (g != null) found.addAll(g);
            }
            if (!found.isEmpty()) return found;
            List<Integer> next = new ArrayList<>();
            for (int q : level) {
                int[] ps = parents.get(q);
                if (ps == null) continue;
                for (int p : ps) if (seen.add(p)) next.add(p);
            }
            level = next;
        }
        return Set.of();
    }

    /** Кэш обхода по значению P136 — значений намного меньше, чем фильмов. */
    public static final class Resolver {
        private final Map<Integer, int[]> parents;
        private final Map<Integer, Set<Integer>> cache = new java.util.HashMap<>();

        public Resolver(Map<Integer, int[]> parents) {
            this.parents = parents;
        }

        public Set<Integer> ofValue(int value) {
            return cache.computeIfAbsent(value, v -> Genres.ofValue(v, parents));
        }
    }

    /**
     * Жанры фильма — объединение: (а) по классам (анимация, документальный, короткометражный); (б) хронометраж не
     * больше {@link #SHORT_MAX_MINUTES} минут — {@code Short}; (в) по каждому значению P136 ({@link #ofValue}). По
     * возрастанию id.
     */
    public static TreeSet<Integer> ofFilm(int[] classes, Integer runtime, int[] p136, Resolver r) {
        TreeSet<Integer> out = new TreeSet<>();
        for (int c : classes) {
            if (ANIMATION_CLASSES.contains(c)) out.add(ANIMATION);
            if (c == DOCUMENTARY_CLASS) out.add(DOCUMENTARY);
            if (SHORT_CLASSES.contains(c)) out.add(SHORT);
        }
        if (runtime != null && runtime <= SHORT_MAX_MINUTES) out.add(SHORT);
        for (int v : p136) out.addAll(r.ofValue(v));
        return out;
    }
}
