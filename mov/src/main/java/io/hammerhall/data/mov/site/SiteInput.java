package io.hammerhall.data.mov.site;

import io.hammerhall.data.mov.build.Cinema;
import io.hammerhall.data.mov.build.Rank;
import io.hammerhall.data.mov.source.SourceData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Вход модуля {@code site} — фильмы профиля такими, как их видит онлайн-кинотеатр: настоящие данные набора (название,
 * год, хронометраж, рейтинг, жанры, языки, страны, режиссёры и двое самых известных актёров — для текстов) и порядок
 * популярности, серии ({@code movies_series}) и продолжения ({@code movies_sequels}) — для узора «смотрят по
 * порядку». Переходник от собранного кино — {@link #of}: единственное место, которое знает устройство {@link Cinema}.
 *
 * @param movies     фильмы профиля по возрастанию id
 * @param moviesFull фильмов в {@code full} (от него — масштаб аудитории на маленьком снимке)
 */
public record SiteInput(List<Movie> movies, int moviesFull) {
    /**
     * Фильм профиля.
     *
     * @param popularity место по популярности в профиле: 0 — самый известный
     * @param genres     жанры словаря (1–24) по возрастанию
     * @param languages  коды языков (ISO 639-1, иначе 639-3)
     * @param countries  коды стран (ISO 3166-1 alpha-3)
     * @param directors  имена режиссёров (по-русски, иначе латиницей), в порядке {@code directorIds}
     * @param actors     до двух актёров (так же), самые известные — первыми
     * @param seriesId   серия фильма (с номером — первая по id) или {@code null}
     * @param seriesNo   номер в серии или {@code null}
     * @param next       продолжения фильма (id фильмов профиля), может быть пусто
     */
    public record Movie(int id, String title, String titleRu, int year, LocalDate releaseDate, Integer runtime, double rank,
                        int popularity, int[] genres, List<String> languages, List<String> countries, int[] directorIds,
                        List<String> directors, List<String> actors, Integer seriesId, Integer seriesNo, int[] next) {
        public boolean hasGenre(int g) {
            for (int x : genres) if (x == g) return true;
            return false;
        }
    }

    /** Индекс фильма по id или {@code -1} (фильмы — по возрастанию id). */
    public int indexOf(int movieId) {
        int lo = 0, hi = movies.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = Integer.compare(movies.get(mid).id(), movieId);
            if (c == 0) return mid;
            if (c < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    /**
     * Пары «предыдущий → следующий» (индексы фильмов профиля): продолжения ({@link Movie#next}) и соседние номера одной
     * серии; без повторов, по (предыдущий, следующий).
     */
    public List<int[]> successions() {
        java.util.TreeSet<Long> pairs = new java.util.TreeSet<>();
        Map<Integer, List<int[]>> bySeries = new java.util.TreeMap<>();
        for (int i = 0; i < movies.size(); i++) {
            Movie m = movies.get(i);
            for (int n : m.next()) {
                int j = indexOf(n);
                if (j >= 0 && j != i) pairs.add(((long) i << 32) | j);
            }
            if (m.seriesId() != null && m.seriesNo() != null) bySeries.computeIfAbsent(m.seriesId(), k -> new ArrayList<>()).add(new int[]{m.seriesNo(), i});
        }
        for (List<int[]> l : bySeries.values()) {
            l.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
            for (int k = 0; k + 1 < l.size(); k++) if (l.get(k)[0] < l.get(k + 1)[0]) pairs.add(((long) l.get(k)[1] << 32) | l.get(k + 1)[1]);
        }
        List<int[]> out = new ArrayList<>(pairs.size());
        for (long p : pairs) out.add(new int[]{(int) (p >>> 32), (int) p});
        return out;
    }

    /** Из собранного кино ({@link Cinema}) и снимка (известность фильмов и людей — для порядка и выбора актёров). */
    public static SiteInput of(Cinema c, SourceData src) {
        Map<Integer, List<Integer>> genres = new HashMap<>(), directorsOf = new HashMap<>(), actorsOf = new HashMap<>();
        Map<Integer, List<String>> langs = new HashMap<>(), countries = new HashMap<>();
        Map<Integer, Integer> cast = new HashMap<>();
        for (Cinema.Pair p : c.moviesGenres()) genres.computeIfAbsent(p.a(), k -> new ArrayList<>()).add(p.b());
        for (Cinema.Pair p : c.moviesDirectors()) directorsOf.computeIfAbsent(p.b(), k -> new ArrayList<>()).add(p.a());
        for (Cinema.Code x : c.moviesLanguages()) langs.computeIfAbsent(x.movieId(), k -> new ArrayList<>()).add(x.code());
        for (Cinema.Code x : c.moviesCountries()) countries.computeIfAbsent(x.movieId(), k -> new ArrayList<>()).add(x.code());
        for (Cinema.Role r : c.roles()) {
            actorsOf.computeIfAbsent(r.movieId(), k -> new ArrayList<>()).add(r.actorId());
            // ролей фильма — как в популярности сборки: роли пары через « / », пустая — одна
            cast.merge(r.movieId(), r.role() == null ? 1 : r.role().split(" / ", -1).length, Integer::sum);
        }
        // серия с номером — первая по id серии; без номера — первая по id; продолжения — пары movies_sequels
        Map<Integer, int[]> seriesOf = new HashMap<>();
        for (Cinema.MovieSeries x : c.moviesSeries()) {
            int[] cur = seriesOf.get(x.movieId());
            int[] cand = {x.seriesId(), x.ordinal() == null ? -1 : x.ordinal()};
            boolean better = cur == null || (cur[1] < 0 && cand[1] >= 0) || ((cur[1] < 0) == (cand[1] < 0) && cand[0] < cur[0]);
            if (better) seriesOf.put(x.movieId(), cand);
        }
        Map<Integer, List<Integer>> nextOf = new HashMap<>();
        for (Cinema.Pair p : c.sequels()) nextOf.computeIfAbsent(p.a(), k -> new ArrayList<>()).add(p.b());
        Map<Integer, String> actorName = new HashMap<>(), directorName = new HashMap<>();
        for (Cinema.Actor a : c.actors()) actorName.put(a.id(), name(a.nameRu(), a.firstName(), a.lastName()));
        for (Cinema.Director d : c.directors()) directorName.put(d.id(), name(d.nameRu(), d.firstName(), d.lastName()));

        List<Cinema.Movie> ms = c.movies();
        int n = ms.size();
        double[] pop = new double[n];
        for (int i = 0; i < n; i++) {
            Cinema.Movie m = ms.get(i);
            SourceData.Signals s = src.signals().getOrDefault(m.id(), SourceData.Signals.NONE);
            pop[i] = Rank.popularity(s.sitelinks(), s.awards(), s.nominations(), cast.getOrDefault(m.id(), 0));
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int x = Double.compare(pop[b], pop[a]);
            return x != 0 ? x : Integer.compare(ms.get(a).id(), ms.get(b).id());
        });
        int[] place = new int[n];
        for (int i = 0; i < n; i++) place[order[i]] = i;

        List<Movie> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Cinema.Movie m = ms.get(i);
            List<Integer> g = genres.getOrDefault(m.id(), List.of());
            int[] gs = g.stream().mapToInt(Integer::intValue).sorted().toArray();
            int[] ds = directorsOf.getOrDefault(m.id(), List.of()).stream().mapToInt(Integer::intValue).sorted().toArray();
            List<String> dn = new ArrayList<>();
            for (int d : ds) dn.add(directorName.get(d));
            List<Integer> as = new ArrayList<>(actorsOf.getOrDefault(m.id(), List.of()));
            as.sort((a, b) -> {
                int x = Integer.compare(sitelinks(src, b), sitelinks(src, a));
                return x != 0 ? x : Integer.compare(a, b);
            });
            List<String> an = new ArrayList<>();
            for (int k = 0; k < Math.min(2, as.size()); k++) an.add(actorName.get(as.get(k)));
            out.add(new Movie(m.id(), m.title(), m.titleRu(), m.year(), m.releaseDate(), m.runtime(), m.rank().doubleValue(),
                    place[i], gs, List.copyOf(langs.getOrDefault(m.id(), List.of())),
                    List.copyOf(countries.getOrDefault(m.id(), List.of())), ds, List.copyOf(dn), List.copyOf(an),
                    seriesOf.containsKey(m.id()) ? Integer.valueOf(seriesOf.get(m.id())[0]) : null,
                    seriesOf.containsKey(m.id()) && seriesOf.get(m.id())[1] >= 0 ? Integer.valueOf(seriesOf.get(m.id())[1]) : null,
                    nextOf.getOrDefault(m.id(), List.of()).stream().mapToInt(Integer::intValue).sorted().toArray()));
        }
        return new SiteInput(List.copyOf(out), c.moviesFull());
    }

    private static int sitelinks(SourceData src, int person) {
        SourceData.Person p = src.people().get(person);
        return p == null ? 0 : p.sitelinks();
    }

    /** Имя для текстов: по-русски, иначе «имя фамилия» латиницей. */
    static String name(String nameRu, String first, String last) {
        if (nameRu != null) return nameRu;
        return first == null ? last : first + " " + last;
    }
}
