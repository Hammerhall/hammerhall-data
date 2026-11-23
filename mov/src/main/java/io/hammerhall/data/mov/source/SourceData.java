package io.hammerhall.data.mov.source;

import io.hammerhall.data.core.io.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Всё, что сборка берёт из снимка источников, — прочитано один раз ({@link #read}); дальше снимок не нужен. Значения —
 * как в снимке; правила набора ({@code Rules}, {@code Genres}, {@code Names}, {@code Rank}, {@code Cinema})
 * применяются потом. Строки файлов {@code film_*} о фильмах, которых нет в {@code films.csv}, не читаются (их число —
 * в {@link #stats}); тексты с негодными для TSV знаками ({@link Wikidata#usable}) — как отсутствующие, кроме
 * {@code character_name}: для него правило роли своё (роль пустая), поэтому оно хранится как есть.
 *
 * @param snapshot      имя снимка из {@code fetch.json} ({@code mov-source-YYYYMMDD})
 * @param fetchedAt     время снимка из {@code fetch.json} (UTC)
 * @param classes       {@code classes.csv}: класс → включён ли ({@code included})
 * @param filmClasses   {@code films.csv}: фильм → его классы P31 (по возрастанию номера)
 * @param dates         {@code film_dates.csv}: утверждения P577 фильма
 * @param filmLabels    {@code film_labels.csv}: метки по {@link Wikidata#LABEL_LANGS} (нет — {@code null})
 * @param titles        {@code film_titles.csv}: P1476 фильма
 * @param links         {@code film_links.csv}: P136, P57, P495, P364 фильма
 * @param seconds       {@code film_durations.csv}: значения P2047 фильма, секунды (по возрастанию, без повторов)
 * @param signals       {@code film_signals.csv}: признаки известности и ID IMDb
 * @param cast          {@code film_cast.csv}: строки состава (P161, P725) фильмов из {@code films.csv}
 * @param genreParents  {@code genre_items.csv}: элемент → его P279 (по возрастанию номера; у корня — пусто)
 * @param people        {@code people.csv}: человек → P31 = Q5, P21, sitelinks
 * @param peopleLabels  {@code people_labels.csv}: метки по {@link Wikidata#LABEL_LANGS}
 * @param peopleNames   {@code people_names.csv}: имена (P735) и фамилии (P734) человека
 * @param nameItems     {@code name_items.csv}: элементы имён и фамилий — метки и вид
 * @param countries     {@code countries.csv}: элемент страны → коды ISO
 * @param languages     {@code languages.csv}: элемент языка → коды ISO
 * @param characters    {@code characters.csv}: персонаж → метки {@code en}, {@code mul}
 * @param stats         счётчики чтения (для журнала и манифеста)
 */
public record SourceData(String snapshot, String fetchedAt, Map<Integer, Boolean> classes, Map<Integer, int[]> filmClasses,
                         Map<Integer, List<DateStmt>> dates, Map<Integer, String[]> filmLabels, Map<Integer, List<Title>> titles,
                         Map<Integer, Links> links, Map<Integer, List<BigDecimal>> seconds, Map<Integer, Signals> signals, Cast cast,
                         Map<Integer, int[]> genreParents, Map<Integer, Person> people, Map<Integer, String[]> peopleLabels,
                         Map<Integer, PersonNames> peopleNames, Map<Integer, NameItem> nameItems,
                         Map<Integer, Codes> countries, Map<Integer, Codes> languages, Map<Integer, String[]> characters,
                         Map<String, Long> stats) {

    public static final String FETCH = "fetch.json";
    public static final String WD = "wikidata/";
    /** Таблицы снимка — имя без расширения: сжатая ({@code .csv.gz}) или нет ({@code .csv}) — {@link Snapshot#csvName}. */
    public static final String CLASSES = WD + "classes", FILMS = WD + "films", FILM_DATES = WD + "film_dates",
            FILM_LABELS = WD + "film_labels", FILM_TITLES = WD + "film_titles", FILM_LINKS = WD + "film_links",
            FILM_DURATIONS = WD + "film_durations", FILM_SIGNALS = WD + "film_signals", FILM_CAST = WD + "film_cast",
            GENRE_ITEMS = WD + "genre_items", PEOPLE = WD + "people", PEOPLE_LABELS = WD + "people_labels",
            PEOPLE_NAMES = WD + "people_names", NAME_ITEMS = WD + "name_items", COUNTRIES = WD + "countries",
            LANGUAGES = WD + "languages", CHARACTERS = WD + "characters";

    /** Свойства {@code film_links}: жанр, режиссёр, страна, язык. */
    public static final String P136 = "P136", P57 = "P57", P495 = "P495", P364 = "P364";
    /** Свойства {@code film_cast}: актёр, актёр озвучивания. */
    public static final String P161 = "P161", P725 = "P725";

    /**
     * Утверждение P577: дата ({@code year} со знаком; {@code month}, {@code day} — как в записи сервиса), точность,
     * ранг {@code preferred} (иначе {@code normal}).
     */
    public record DateStmt(int year, int month, int day, int precision, boolean preferred) {
        /** Раньше — меньше: год, месяц, день. */
        public int compareDate(DateStmt o) {
            int c = Integer.compare(year, o.year);
            if (c == 0) c = Integer.compare(month, o.month);
            return c != 0 ? c : Integer.compare(day, o.day);
        }
    }

    /** Название P1476: язык монолингвального текста и текст. */
    public record Title(String lang, String title) {
    }

    /** Связи фильма — номера элементов по возрастанию, без повторов. */
    public record Links(int[] genres, int[] directors, int[] countries, int[] languages) {
        public static final Links NONE = new Links(new int[0], new int[0], new int[0], new int[0]);
    }

    /** Признаки известности фильма: sitelinks, число P166 (награды), P1411 (номинации); ID IMDb (P345) — все значения. */
    public record Signals(int sitelinks, int awards, int nominations, List<String> imdb) {
        public static final Signals NONE = new Signals(0, 0, 0, List.of());
    }

    /** Человек: P31 = Q5; P21 (номер элемента, {@code 0} — нет); sitelinks. */
    public record Person(boolean human, int gender, int sitelinks) {
    }

    /** Имя или фамилия человека: элемент и P1545 (строкой, часто пусто). */
    public record NameRef(int item, String ordinal) {
    }

    /** Имена (P735) и фамилии (P734) человека. */
    public record PersonNames(List<NameRef> given, List<NameRef> family) {
    }

    /** Элемент имени или фамилии: метки {@code en}, {@code mul} (нет — {@code null}), вид по P31. */
    public record NameItem(String labelEn, String labelMul, String kind) {
    }

    /** Коды ISO элемента страны или языка — все значения (по байтам): страна — ISO2, ISO3; язык — ISO 639-1, 639-3. */
    public record Codes(List<String> first, List<String> second) {
    }

    /**
     * Строки состава: по строке на утверждение P161/P725 и квалификатор роли. {@code character} — номер элемента P453
     * ({@code 0} — нет), {@code characterName} — P4633 как есть ({@code null} — нет).
     */
    public static final class Cast {
        private int n;
        private int[] film = new int[1024], person = new int[1024], character = new int[1024];
        private boolean[] voice = new boolean[1024];
        private String[] characterName = new String[1024];

        void add(int f, boolean v, int p, int ch, String name) {
            if (n == film.length) {
                int m = n * 2;
                film = Arrays.copyOf(film, m);
                person = Arrays.copyOf(person, m);
                character = Arrays.copyOf(character, m);
                voice = Arrays.copyOf(voice, m);
                characterName = Arrays.copyOf(characterName, m);
            }
            film[n] = f;
            voice[n] = v;
            person[n] = p;
            character[n] = ch;
            characterName[n] = name;
            n++;
        }

        public int size() {
            return n;
        }

        public int film(int i) {
            return film[i];
        }

        /** Строка — P725 (озвучивание), иначе P161. */
        public boolean voice(int i) {
            return voice[i];
        }

        public int person(int i) {
            return person[i];
        }

        public int character(int i) {
            return character[i];
        }

        public String characterName(int i) {
            return characterName[i];
        }
    }

    // ---------------------------------------------------------------- чтение

    /** Прочитать снимок. */
    public static SourceData read(Path zip) throws IOException {
        Map<String, Long> stats = new LinkedHashMap<>();
        try (Snapshot s = Snapshot.open(zip)) {
            Object fj = Json.parse(s.text(FETCH));
            if (!(fj instanceof Map<?, ?> fetch)) throw new IOException(FETCH + ": не объект JSON");
            String snapshot = String.valueOf(fetch.get("snapshot"));
            String fetchedAt = fetch.get("fetched_at") == null ? null : String.valueOf(fetch.get("fetched_at"));

            Map<Integer, Boolean> classes = new LinkedHashMap<>();
            each(s, CLASSES, List.of("class", "included"), stats, (t, r) -> {
                String inc = t.get(r, "included").strip();
                if (!inc.equals("1") && !inc.equals("0")) throw t.bad("included — 1 или 0, а не «" + inc + "»");
                classes.put(Wikidata.q(t.get(r, "class")), inc.equals("1"));
            });

            Map<Integer, IntList> fc = new HashMap<>();
            each(s, FILMS, List.of("film", "class"), stats, (t, r) -> {
                int c = Wikidata.qOrZero(t.get(r, "class"));
                IntList l = fc.computeIfAbsent(Wikidata.q(t.get(r, "film")), k -> new IntList());
                if (c != 0) l.add(c);
            });
            Map<Integer, int[]> filmClasses = new HashMap<>();
            for (Map.Entry<Integer, IntList> e : fc.entrySet()) filmClasses.put(e.getKey(), e.getValue().sortedUnique());
            fc.clear();
            long[] skipped = new long[1];

            Map<Integer, List<DateStmt>> dates = new HashMap<>();
            each(s, FILM_DATES, List.of("film", "date", "precision", "rank"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                DateStmt d = date(t.get(r, "date"), t.get(r, "precision"), t.get(r, "rank"));
                if (d == null) {
                    stats.merge("film_dates_unreadable", 1L, Long::sum);
                    return;
                }
                dates.computeIfAbsent(f, k -> new ArrayList<>(1)).add(d);
            });

            Map<Integer, String[]> filmLabels = new HashMap<>();
            each(s, FILM_LABELS, List.of("film", "lang", "label"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                putLabel(filmLabels, f, t.get(r, "lang"), t.get(r, "label"));
            });

            Map<Integer, List<Title>> titles = new HashMap<>();
            each(s, FILM_TITLES, List.of("film", "lang", "title"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                String title = t.get(r, "title");
                if (Wikidata.usable(title)) titles.computeIfAbsent(f, k -> new ArrayList<>(1)).add(new Title(t.get(r, "lang"), title));
            });

            Map<Integer, IntList[]> lk = new HashMap<>();
            each(s, FILM_LINKS, List.of("film", "prop", "value"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                int slot = switch (t.get(r, "prop")) {
                    case P136 -> 0;
                    case P57 -> 1;
                    case P495 -> 2;
                    case P364 -> 3;
                    default -> throw t.bad("film_links: свойство «" + t.get(r, "prop") + "» — не P136, P57, P495, P364");
                };
                int v = Wikidata.qOrZero(t.get(r, "value"));
                if (v == 0) {
                    stats.merge("film_links_not_items", 1L, Long::sum);
                    return;
                }
                IntList[] a = lk.computeIfAbsent(f, k -> new IntList[4]);
                if (a[slot] == null) a[slot] = new IntList();
                a[slot].add(v);
            });
            Map<Integer, Links> links = new HashMap<>();
            for (Map.Entry<Integer, IntList[]> e : lk.entrySet()) {
                IntList[] a = e.getValue();
                links.put(e.getKey(), new Links(IntList.sorted(a[0]), IntList.sorted(a[1]), IntList.sorted(a[2]), IntList.sorted(a[3])));
            }
            lk.clear();

            Map<Integer, List<BigDecimal>> durations = new HashMap<>();
            each(s, FILM_DURATIONS, List.of("film", "seconds"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                BigDecimal v;
                try {
                    v = new BigDecimal(t.get(r, "seconds").strip());
                } catch (NumberFormatException e) {
                    stats.merge("film_durations_unreadable", 1L, Long::sum);
                    return;
                }
                durations.computeIfAbsent(f, k -> new ArrayList<>(1)).add(v);
            });
            Map<Integer, List<BigDecimal>> seconds = new HashMap<>();
            for (Map.Entry<Integer, List<BigDecimal>> e : durations.entrySet()) {
                TreeSet<BigDecimal> u = new TreeSet<>(e.getValue());
                seconds.put(e.getKey(), List.copyOf(u));
            }
            durations.clear();

            Map<Integer, Signals> signals = new HashMap<>();
            each(s, FILM_SIGNALS, List.of("film", "sitelinks", "awards", "nominations", "imdb"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                String imdb = t.get(r, "imdb").strip();
                Signals x = new Signals(count(t, r, "sitelinks"), count(t, r, "awards"), count(t, r, "nominations"),
                        imdb.isEmpty() ? List.of() : List.of(imdb));
                signals.merge(f, x, SourceData::mergeSignals);
            });

            Cast cast = new Cast();
            each(s, FILM_CAST, List.of("film", "prop", "person", "character", "character_name"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                String prop = t.get(r, "prop");
                if (!prop.equals(P161) && !prop.equals(P725)) throw t.bad("film_cast: свойство «" + prop + "» — не P161, P725");
                int p = Wikidata.qOrZero(t.get(r, "person"));
                if (p == 0) {
                    stats.merge("film_cast_not_items", 1L, Long::sum);
                    return;
                }
                String name = t.get(r, "character_name");
                cast.add(f, prop.equals(P725), p, Wikidata.qOrZero(t.get(r, "character")), name.isEmpty() ? null : name);
            });
            stats.put("film_rows_not_in_films", skipped[0]);

            Map<Integer, IntList> gp = new HashMap<>();
            each(s, GENRE_ITEMS, List.of("item", "parent"), stats, (t, r) -> {
                IntList l = gp.computeIfAbsent(Wikidata.q(t.get(r, "item")), k -> new IntList());
                int p = Wikidata.qOrZero(t.get(r, "parent"));
                if (p != 0) l.add(p);
            });
            Map<Integer, int[]> genreParents = new HashMap<>();
            for (Map.Entry<Integer, IntList> e : gp.entrySet()) genreParents.put(e.getKey(), e.getValue().sortedUnique());

            Map<Integer, Person> people = new HashMap<>();
            each(s, PEOPLE, List.of("person", "human", "gender", "sitelinks"), stats, (t, r) -> {
                String h = t.get(r, "human").strip();
                if (!h.equals("1") && !h.equals("0")) throw t.bad("human — 1 или 0, а не «" + h + "»");
                int p = Wikidata.q(t.get(r, "person"));
                Person x = new Person(h.equals("1"), Wikidata.qOrZero(t.get(r, "gender")), count(t, r, "sitelinks"));
                if (people.putIfAbsent(p, x) != null) throw t.bad("people: человек дважды: Q" + p);
            });

            Map<Integer, String[]> peopleLabels = new HashMap<>();
            each(s, PEOPLE_LABELS, List.of("person", "lang", "label"), stats,
                    (t, r) -> putLabel(peopleLabels, Wikidata.q(t.get(r, "person")), t.get(r, "lang"), t.get(r, "label")));

            Map<Integer, PersonNames> peopleNames = new HashMap<>();
            each(s, PEOPLE_NAMES, List.of("person", "kind", "item", "ordinal"), stats, (t, r) -> {
                int item = Wikidata.qOrZero(t.get(r, "item"));
                if (item == 0) return;
                PersonNames pn = peopleNames.computeIfAbsent(Wikidata.q(t.get(r, "person")),
                        k -> new PersonNames(new ArrayList<>(1), new ArrayList<>(1)));
                NameRef ref = new NameRef(item, t.get(r, "ordinal"));
                switch (t.get(r, "kind")) {
                    case "given" -> pn.given().add(ref);
                    case "family" -> pn.family().add(ref);
                    default -> throw t.bad("people_names: kind — given или family, а не «" + t.get(r, "kind") + "»");
                }
            });

            Map<Integer, NameItem> nameItems = new HashMap<>();
            each(s, NAME_ITEMS, List.of("item", "label_en", "label_mul", "kind"), stats, (t, r) -> {
                String en = t.get(r, "label_en"), mul = t.get(r, "label_mul");
                nameItems.put(Wikidata.q(t.get(r, "item")), new NameItem(Wikidata.usable(en) ? en : null,
                        Wikidata.usable(mul) ? mul : null, t.get(r, "kind").strip()));
            });

            Map<Integer, Codes> countries = codes(s, COUNTRIES, "iso2", "iso3", stats);
            Map<Integer, Codes> languages = codes(s, LANGUAGES, "iso1", "iso3", stats);

            Map<Integer, String[]> characters = new HashMap<>();
            each(s, CHARACTERS, List.of("item", "label_en", "label_mul"), stats, (t, r) -> {
                String en = t.get(r, "label_en"), mul = t.get(r, "label_mul");
                characters.put(Wikidata.q(t.get(r, "item")), new String[]{Wikidata.usable(en) ? en : null,
                        Wikidata.usable(mul) ? mul : null});
            });

            return new SourceData(snapshot, fetchedAt, Collections.unmodifiableMap(classes), filmClasses, dates, filmLabels,
                    titles, links, seconds, signals, cast, genreParents, people, peopleLabels, peopleNames, nameItems,
                    countries, languages, characters, stats);
        }
    }

    /** Строка файла снимка. */
    @FunctionalInterface
    interface Row {
        void accept(Csv.Table t, List<String> r) throws IOException;
    }

    private static void each(Snapshot s, String base, List<String> columns, Map<String, Long> stats, Row row) throws IOException {
        String name = s.csvName(base);
        long n = 0;
        try (BufferedReader in = s.reader(name)) {
            Csv.Table t = new Csv.Table(new Csv(in, name), columns);
            for (List<String> r; (r = t.next()) != null; ) {
                n++;
                try {
                    row.accept(t, r);
                } catch (IllegalArgumentException e) {
                    throw t.bad(e.getMessage());
                }
            }
        }
        stats.put(base.substring(WD.length()) + "_rows", n);
    }

    private static int count(Csv.Table t, List<String> r, String column) throws IOException {
        String v = t.get(r, column).strip();
        if (v.isEmpty()) return 0;
        try {
            int x = Integer.parseInt(v);
            if (x < 0) throw t.bad(column + " — отрицательное: " + v);
            return x;
        } catch (NumberFormatException e) {
            throw t.bad(column + " — не целое: «" + v + "»");
        }
    }

    private static Signals mergeSignals(Signals a, Signals b) {
        TreeSet<String> imdb = new TreeSet<>(Snapshot::compareBytes);
        imdb.addAll(a.imdb());
        imdb.addAll(b.imdb());
        return new Signals(Math.max(a.sitelinks(), b.sitelinks()), Math.max(a.awards(), b.awards()),
                Math.max(a.nominations(), b.nominations()), List.copyOf(imdb));
    }

    /** Метка в ячейку своего языка; языки не из {@link Wikidata#LABEL_LANGS} и негодные тексты — мимо; две — меньшая по байтам. */
    private static void putLabel(Map<Integer, String[]> m, int item, String lang, String label) {
        int i = Wikidata.lang(lang);
        if (i < 0 || !Wikidata.usable(label)) return;
        String[] a = m.computeIfAbsent(item, k -> new String[Wikidata.LABEL_LANGS.size()]);
        if (a[i] == null || Snapshot.compareBytes(label, a[i]) < 0) a[i] = label;
    }

    private static Map<Integer, Codes> codes(Snapshot s, String base, String first, String second, Map<String, Long> stats)
            throws IOException {
        Map<Integer, List<TreeSet<String>>> acc = new HashMap<>();
        each(s, base, List.of("item", first, second), stats, (t, r) -> {
            List<TreeSet<String>> a = acc.computeIfAbsent(Wikidata.q(t.get(r, "item")),
                    k -> List.of(new TreeSet<>(Snapshot::compareBytes), new TreeSet<>(Snapshot::compareBytes)));
            String x = t.get(r, first).strip(), y = t.get(r, second).strip();
            if (!x.isEmpty()) a.get(0).add(x);
            if (!y.isEmpty()) a.get(1).add(y);
        });
        Map<Integer, Codes> out = new HashMap<>();
        for (Map.Entry<Integer, List<TreeSet<String>>> e : acc.entrySet()) {
            out.put(e.getKey(), new Codes(List.copyOf(e.getValue().get(0)), List.copyOf(e.getValue().get(1))));
        }
        return out;
    }

    /**
     * Утверждение P577 из записи сервиса ({@code 1999-03-31T00:00:00Z}, до н. э. — с минусом, иногда с плюсом);
     * запись не разобрать — {@code null}. Ранг — {@code preferred} или {@code normal} (deprecated снимок не пишет).
     */
    public static DateStmt date(String text, String precision, String rank) {
        String s = text.strip();
        int i = 0, sign = 1;
        if (s.startsWith("-")) {
            sign = -1;
            i = 1;
        } else if (s.startsWith("+")) {
            i = 1;
        }
        int dash = s.indexOf('-', i);
        if (dash <= i || dash + 6 > s.length() || s.charAt(dash + 3) != '-') return null;
        int year, month, day, prec;
        try {
            year = sign * Integer.parseInt(s.substring(i, dash));
            month = Integer.parseInt(s.substring(dash + 1, dash + 3));
            day = Integer.parseInt(s.substring(dash + 4, dash + 6));
            prec = Integer.parseInt(precision.strip());
        } catch (NumberFormatException e) {
            return null;
        }
        String rk = rank.strip();
        if (!rk.equals("preferred") && !rk.equals("normal")) return null;
        return new DateStmt(year, month, day, prec, rk.equals("preferred"));
    }

    /** Растущий массив {@code int}. */
    static final class IntList {
        private int[] a = new int[2];
        private int n;

        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        int[] sortedUnique() {
            int[] b = Arrays.copyOf(a, n);
            Arrays.sort(b);
            int m = 0;
            for (int i = 0; i < b.length; i++) if (m == 0 || b[i] != b[m - 1]) b[m++] = b[i];
            return Arrays.copyOf(b, m);
        }

        static int[] sorted(IntList l) {
            return l == null ? new int[0] : l.sortedUnique();
        }
    }
}
