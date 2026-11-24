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
 * @param datesExtra    {@code film_dates_extra.csv}: свойство ({@link #YEAR_FALLBACK}) → фильм → его утверждения
 * @param labelsAll     {@code film_labels_all.csv}: все метки фильма без метки из {@link Wikidata#LABEL_LANGS} и без
 *                      P1476 — язык и текст (только годные для TSV)
 * @param releases      {@code film_releases.csv}: P577 фильма с местом публикации P291
 * @param crew          {@code film_crew.csv}: съёмочная группа фильма по {@link #CREW_PROPS}
 * @param links2        {@code film_links2.csv}: студии, прокатчики, места съёмок, предыдущий и следующий фильм
 * @param series        {@code film_series.csv}: серии P179 фильма с P1545
 * @param money         {@code film_money.csv}: бюджет P2130 и сборы P2142 фильма
 * @param awards        {@code film_awards.csv}: награды P166 и номинации P1411 фильма с датой P585
 * @param peopleDates   {@code people_dates.csv}: рождение P569 и смерть P570 человека
 * @param peopleLinks   {@code people_links.csv}: место рождения P19 и гражданство P27 человека
 * @param places        {@code places.csv}: место (значение P19, P915) → GeoNames ID (только у мест, где он есть)
 * @param itemLabels    {@code item_labels.csv}: студия, прокатчик, серия, награда → метки {@code en}, {@code mul},
 *                      {@code ru} (нет — {@code null})
 * @param companies     {@code companies.csv}: студия или прокатчик → страна P17 (только у тех, где она есть)
 * @param stats         счётчики чтения (для журнала и манифеста)
 */
public record SourceData(String snapshot, String fetchedAt, Map<Integer, Boolean> classes, Map<Integer, int[]> filmClasses,
                         Map<Integer, List<DateStmt>> dates, Map<Integer, String[]> filmLabels, Map<Integer, List<Title>> titles,
                         Map<Integer, Links> links, Map<Integer, List<BigDecimal>> seconds, Map<Integer, Signals> signals, Cast cast,
                         Map<Integer, int[]> genreParents, Map<Integer, Person> people, Map<Integer, String[]> peopleLabels,
                         Map<Integer, PersonNames> peopleNames, Map<Integer, NameItem> nameItems,
                         Map<Integer, Codes> countries, Map<Integer, Codes> languages, Map<Integer, String[]> characters,
                         Map<String, Map<Integer, List<DateStmt>>> datesExtra, Map<Integer, List<Title>> labelsAll,
                         Map<Integer, List<PlaceDate>> releases, Map<Integer, Crew> crew, Map<Integer, Links2> links2,
                         Map<Integer, List<SeriesRef>> series, Map<Integer, List<MoneyStmt>> money,
                         Map<Integer, List<AwardStmt>> awards, Map<Integer, LifeDates> peopleDates,
                         Map<Integer, PersonLinks> peopleLinks, Map<Integer, Integer> places, Map<Integer, String[]> itemLabels,
                         Map<Integer, Integer> companies, Map<String, Long> stats) {

    public static final String FETCH = "fetch.json";
    public static final String WD = "wikidata/";
    /** Таблицы снимка — имя без расширения: сжатая ({@code .csv.gz}) или нет ({@code .csv}) — {@link Snapshot#csvName}. */
    public static final String CLASSES = WD + "classes", FILMS = WD + "films", FILM_DATES = WD + "film_dates",
            FILM_LABELS = WD + "film_labels", FILM_TITLES = WD + "film_titles", FILM_LINKS = WD + "film_links",
            FILM_DURATIONS = WD + "film_durations", FILM_SIGNALS = WD + "film_signals", FILM_CAST = WD + "film_cast",
            GENRE_ITEMS = WD + "genre_items", PEOPLE = WD + "people", PEOPLE_LABELS = WD + "people_labels",
            PEOPLE_NAMES = WD + "people_names", NAME_ITEMS = WD + "name_items", COUNTRIES = WD + "countries",
            LANGUAGES = WD + "languages", CHARACTERS = WD + "characters";
    /** Таблицы снимка v2 ({@code mov-source-20261124} и новее): без любой из них снимок не читается. */
    public static final String FILM_DATES_EXTRA = WD + "film_dates_extra", FILM_LABELS_ALL = WD + "film_labels_all",
            FILM_RELEASES = WD + "film_releases", FILM_CREW = WD + "film_crew", FILM_LINKS2 = WD + "film_links2",
            FILM_SERIES = WD + "film_series", FILM_MONEY = WD + "film_money", FILM_AWARDS = WD + "film_awards",
            PEOPLE_DATES = WD + "people_dates", PEOPLE_LINKS = WD + "people_links", PLACES = WD + "places",
            ITEM_LABELS = WD + "item_labels", COMPANIES = WD + "companies";
    /** Таблицы, которые есть только в снимке v2. */
    public static final List<String> TABLES_V2 = List.of(FILM_DATES_EXTRA, FILM_LABELS_ALL, FILM_RELEASES, FILM_CREW,
            FILM_LINKS2, FILM_SERIES, FILM_MONEY, FILM_AWARDS, PEOPLE_DATES, PEOPLE_LINKS, PLACES, ITEM_LABELS, COMPANIES);
    /** Все таблицы снимка — по порядку чтения. */
    public static final List<String> TABLES = java.util.stream.Stream.concat(java.util.stream.Stream.of(CLASSES, FILMS, FILM_DATES,
            FILM_LABELS, FILM_TITLES, FILM_LINKS, FILM_DURATIONS, FILM_SIGNALS, FILM_CAST, GENRE_ITEMS, PEOPLE, PEOPLE_LABELS,
            PEOPLE_NAMES, NAME_ITEMS, COUNTRIES, LANGUAGES, CHARACTERS), TABLES_V2.stream()).toList();

    /** Свойства {@code film_links}: жанр, режиссёр, страна, язык. */
    public static final String P136 = "P136", P57 = "P57", P495 = "P495", P364 = "P364";
    /** Свойства {@code film_cast}: актёр, актёр озвучивания. */
    public static final String P161 = "P161", P725 = "P725";
    /** Свойства {@code film_dates_extra}: первый показ, создание, дата производства. */
    public static final String P1191 = "P1191", P571 = "P571", P2754 = "P2754";
    /** Запасные свойства года фильма — по очереди, если по P577 года нет. */
    public static final List<String> YEAR_FALLBACK = List.of(P1191, P571, P2754);
    /** Свойства {@code film_crew}: сценарист, композитор, оператор, продюсер, монтажёр — по этому порядку. */
    public static final List<String> CREW_PROPS = List.of("P58", "P86", "P344", "P162", "P1040");
    /** Свойства {@code film_links2}: студия, прокатчик, место съёмок, предыдущий фильм, следующий фильм. */
    public static final String P272 = "P272", P750 = "P750", P915 = "P915", P155 = "P155", P156 = "P156";
    /** Свойства {@code film_money}: бюджет, сборы. */
    public static final String P2130 = "P2130", P2142 = "P2142";
    /** Свойства {@code film_awards}: награда, номинация. */
    public static final String P166 = "P166", P1411 = "P1411";
    /** Свойства {@code people_dates}: рождение, смерть. */
    public static final String P569 = "P569", P570 = "P570";
    /** Свойства {@code people_links}: место рождения, гражданство. */
    public static final String P19 = "P19", P27 = "P27";

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

    /** Утверждение P577 с местом публикации P291 ({@code film_releases}): место — номер элемента. */
    public record PlaceDate(int place, DateStmt date) {
    }

    /** Съёмочная группа фильма: люди по свойствам {@link #CREW_PROPS} (по тому же порядку), номера по возрастанию. */
    public record Crew(int[][] byProp) {
        public static final Crew NONE = new Crew(new int[CREW_PROPS.size()][0]);

        /** Люди свойства {@code CREW_PROPS.get(i)}. */
        public int[] of(int i) {
            return byProp[i];
        }
    }

    /** Связи фильма из {@code film_links2} — номера элементов по возрастанию, без повторов. */
    public record Links2(int[] studios, int[] distributors, int[] locations, int[] previous, int[] next) {
        public static final Links2 NONE = new Links2(new int[0], new int[0], new int[0], new int[0], new int[0]);
    }

    /** Серия фильма P179 и номер в ней P1545 (строкой как есть; нет — {@code null}). */
    public record SeriesRef(int series, String ordinal) {
    }

    /**
     * Сумма P2130 (бюджет) или P2142 (сборы): {@code boxOffice} — сборы; сумма как есть; валюта — номер элемента
     * ({@code 0} — нет); ранг {@code preferred} (иначе {@code normal}).
     */
    public record MoneyStmt(boolean boxOffice, BigDecimal amount, int unit, boolean preferred) {
    }

    /** Награда P166 ({@code won}) или номинация P1411; дата P585 ({@code null} — нет или не разобрать). */
    public record AwardStmt(int award, boolean won, DateStmt date) {
    }

    /** Утверждения P569 (рождение) и P570 (смерть) человека. */
    public record LifeDates(List<DateStmt> birth, List<DateStmt> death) {
        public static final LifeDates NONE = new LifeDates(List.of(), List.of());
    }

    /** Места рождения P19 и гражданство P27 человека — номера элементов по возрастанию, без повторов. */
    public record PersonLinks(int[] birthPlaces, int[] citizenships) {
        public static final PersonLinks NONE = new PersonLinks(new int[0], new int[0]);
    }

    /** Метки элемента {@code item_labels}: номер языка в массиве. */
    public static final int ITEM_EN = 0, ITEM_MUL = 1, ITEM_RU = 2;
    static final List<String> ITEM_LANGS = List.of("en", "mul", "ru");

    // ---------------------------------------------------------------- чтение

    /**
     * Прочитать снимок. Сначала — все ли таблицы на месте ({@link #TABLES}): нет хоть одной (например, снимок v1 без
     * файлов v2) — ошибка с её именем, до чтения больших файлов.
     */
    public static SourceData read(Path zip) throws IOException {
        Map<String, Long> stats = new LinkedHashMap<>();
        try (Snapshot s = Snapshot.open(zip)) {
            for (String t : TABLES) {
                try {
                    s.csvName(t);
                } catch (IOException e) {
                    if (!TABLES_V2.contains(t)) throw e;
                    throw new IOException(e.getMessage() + " — таблица снимка v2: снимок старше v2, его надо снять заново", e);
                }
            }
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

            // ---- снимок v2: фильмы
            Map<String, Map<Integer, List<DateStmt>>> datesExtra = new LinkedHashMap<>();
            for (String p : YEAR_FALLBACK) datesExtra.put(p, new HashMap<>());
            each(s, FILM_DATES_EXTRA, List.of("film", "prop", "date", "precision", "rank"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                Map<Integer, List<DateStmt>> m = datesExtra.get(t.get(r, "prop"));
                if (m == null) throw t.bad("film_dates_extra: свойство «" + t.get(r, "prop") + "» — не " + String.join(", ", YEAR_FALLBACK));
                DateStmt d = date(t.get(r, "date"), t.get(r, "precision"), t.get(r, "rank"));
                if (d == null) {
                    stats.merge("film_dates_extra_unreadable", 1L, Long::sum);
                    return;
                }
                m.computeIfAbsent(f, k -> new ArrayList<>(1)).add(d);
            });

            Map<Integer, List<Title>> labelsAll = new HashMap<>();
            each(s, FILM_LABELS_ALL, List.of("film", "lang", "label"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                String lang = t.get(r, "lang").strip(), label = t.get(r, "label");
                if (Wikidata.usable(lang) && Wikidata.usable(label)) {
                    labelsAll.computeIfAbsent(f, k -> new ArrayList<>(1)).add(new Title(lang, label));
                }
            });

            Map<Integer, List<PlaceDate>> releases = new HashMap<>();
            each(s, FILM_RELEASES, List.of("film", "date", "precision", "place"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                int place = Wikidata.qOrZero(t.get(r, "place"));
                DateStmt d = date(t.get(r, "date"), t.get(r, "precision"), "normal");
                if (place == 0 || d == null) {
                    stats.merge("film_releases_unreadable", 1L, Long::sum);
                    return;
                }
                releases.computeIfAbsent(f, k -> new ArrayList<>(1)).add(new PlaceDate(place, d));
            });

            Map<Integer, IntList[]> cr = new HashMap<>();
            each(s, FILM_CREW, List.of("film", "prop", "person"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                int slot = CREW_PROPS.indexOf(t.get(r, "prop"));
                if (slot < 0) throw t.bad("film_crew: свойство «" + t.get(r, "prop") + "» — не " + String.join(", ", CREW_PROPS));
                add(cr, f, slot, CREW_PROPS.size(), Wikidata.qOrZero(t.get(r, "person")), stats, "film_crew_not_items");
            });
            Map<Integer, Crew> crew = new HashMap<>();
            for (Map.Entry<Integer, IntList[]> e : cr.entrySet()) {
                int[][] a = new int[CREW_PROPS.size()][];
                for (int i = 0; i < a.length; i++) a[i] = IntList.sorted(e.getValue()[i]);
                crew.put(e.getKey(), new Crew(a));
            }
            cr.clear();

            Map<Integer, IntList[]> l2 = new HashMap<>();
            each(s, FILM_LINKS2, List.of("film", "prop", "value"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                int slot = switch (t.get(r, "prop")) {
                    case P272 -> 0;
                    case P750 -> 1;
                    case P915 -> 2;
                    case P155 -> 3;
                    case P156 -> 4;
                    default -> throw t.bad("film_links2: свойство «" + t.get(r, "prop") + "» — не P272, P750, P915, P155, P156");
                };
                add(l2, f, slot, 5, Wikidata.qOrZero(t.get(r, "value")), stats, "film_links2_not_items");
            });
            Map<Integer, Links2> links2 = new HashMap<>();
            for (Map.Entry<Integer, IntList[]> e : l2.entrySet()) {
                IntList[] a = e.getValue();
                links2.put(e.getKey(), new Links2(IntList.sorted(a[0]), IntList.sorted(a[1]), IntList.sorted(a[2]),
                        IntList.sorted(a[3]), IntList.sorted(a[4])));
            }
            l2.clear();

            Map<Integer, List<SeriesRef>> series = new HashMap<>();
            each(s, FILM_SERIES, List.of("film", "series", "ordinal"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                int x = Wikidata.qOrZero(t.get(r, "series"));
                if (x == 0) {
                    stats.merge("film_series_not_items", 1L, Long::sum);
                    return;
                }
                String o = t.get(r, "ordinal");
                series.computeIfAbsent(f, k -> new ArrayList<>(1)).add(new SeriesRef(x, o.isEmpty() ? null : o));
            });

            Map<Integer, List<MoneyStmt>> money = new HashMap<>();
            each(s, FILM_MONEY, List.of("film", "prop", "amount", "unit", "rank"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                String prop = t.get(r, "prop");
                if (!prop.equals(P2130) && !prop.equals(P2142)) throw t.bad("film_money: свойство «" + prop + "» — не P2130, P2142");
                String rk = t.get(r, "rank").strip();
                BigDecimal amount;
                try {
                    amount = new BigDecimal(t.get(r, "amount").strip());
                } catch (NumberFormatException e) {
                    amount = null;
                }
                if (amount == null || (!rk.equals("preferred") && !rk.equals("normal"))) {
                    stats.merge("film_money_unreadable", 1L, Long::sum);
                    return;
                }
                money.computeIfAbsent(f, k -> new ArrayList<>(1)).add(new MoneyStmt(prop.equals(P2142), amount,
                        Wikidata.qOrZero(t.get(r, "unit")), rk.equals("preferred")));
            });

            Map<Integer, List<AwardStmt>> awards = new HashMap<>();
            each(s, FILM_AWARDS, List.of("film", "prop", "award", "date", "precision"), stats, (t, r) -> {
                int f = Wikidata.q(t.get(r, "film"));
                if (!filmClasses.containsKey(f)) {
                    skipped[0]++;
                    return;
                }
                String prop = t.get(r, "prop");
                if (!prop.equals(P166) && !prop.equals(P1411)) throw t.bad("film_awards: свойство «" + prop + "» — не P166, P1411");
                int a = Wikidata.qOrZero(t.get(r, "award"));
                if (a == 0) {
                    stats.merge("film_awards_not_items", 1L, Long::sum);
                    return;
                }
                String text = t.get(r, "date");
                DateStmt d = text.isBlank() ? null : date(text, t.get(r, "precision"), "normal");
                if (!text.isBlank() && d == null) stats.merge("film_awards_dates_unreadable", 1L, Long::sum);
                awards.computeIfAbsent(f, k -> new ArrayList<>(1)).add(new AwardStmt(a, prop.equals(P166), d));
            });
            stats.put("film_rows_not_in_films", skipped[0]);

            // ---- снимок v2: люди, места, студии, серии, награды
            Map<Integer, List<List<DateStmt>>> pd = new HashMap<>();
            each(s, PEOPLE_DATES, List.of("person", "prop", "date", "precision", "rank"), stats, (t, r) -> {
                String prop = t.get(r, "prop");
                if (!prop.equals(P569) && !prop.equals(P570)) throw t.bad("people_dates: свойство «" + prop + "» — не P569, P570");
                DateStmt d = date(t.get(r, "date"), t.get(r, "precision"), t.get(r, "rank"));
                if (d == null) {
                    stats.merge("people_dates_unreadable", 1L, Long::sum);
                    return;
                }
                pd.computeIfAbsent(Wikidata.q(t.get(r, "person")), k -> List.of(new ArrayList<>(1), new ArrayList<>(1)))
                        .get(prop.equals(P569) ? 0 : 1).add(d);
            });
            Map<Integer, LifeDates> peopleDates = new HashMap<>();
            for (Map.Entry<Integer, List<List<DateStmt>>> e : pd.entrySet()) {
                peopleDates.put(e.getKey(), new LifeDates(List.copyOf(e.getValue().get(0)), List.copyOf(e.getValue().get(1))));
            }
            pd.clear();

            Map<Integer, IntList[]> pl = new HashMap<>();
            each(s, PEOPLE_LINKS, List.of("person", "prop", "value"), stats, (t, r) -> {
                int slot = switch (t.get(r, "prop")) {
                    case P19 -> 0;
                    case P27 -> 1;
                    default -> throw t.bad("people_links: свойство «" + t.get(r, "prop") + "» — не P19, P27");
                };
                add(pl, Wikidata.q(t.get(r, "person")), slot, 2, Wikidata.qOrZero(t.get(r, "value")), stats, "people_links_not_items");
            });
            Map<Integer, PersonLinks> peopleLinks = new HashMap<>();
            for (Map.Entry<Integer, IntList[]> e : pl.entrySet()) {
                peopleLinks.put(e.getKey(), new PersonLinks(IntList.sorted(e.getValue()[0]), IntList.sorted(e.getValue()[1])));
            }
            pl.clear();

            Map<Integer, Integer> places = new HashMap<>();
            each(s, PLACES, List.of("item", "geonames", "country", "label_en"), stats, (t, r) -> {
                int item = Wikidata.q(t.get(r, "item"));
                String g = t.get(r, "geonames").strip();
                if (g.isEmpty()) return;
                int id = geonames(g);
                if (id == 0) {
                    stats.merge("places_geonames_unreadable", 1L, Long::sum);
                    return;
                }
                places.merge(item, id, Math::min);
            });

            Map<Integer, String[]> itemLabels = new HashMap<>();
            each(s, ITEM_LABELS, List.of("item", "lang", "label"), stats, (t, r) -> {
                int i = ITEM_LANGS.indexOf(t.get(r, "lang").strip());
                String label = t.get(r, "label");
                if (i < 0 || !Wikidata.usable(label)) return;
                String[] a = itemLabels.computeIfAbsent(Wikidata.q(t.get(r, "item")), k -> new String[ITEM_LANGS.size()]);
                if (a[i] == null || Snapshot.compareBytes(label, a[i]) < 0) a[i] = label;
            });

            Map<Integer, Integer> companies = new HashMap<>();
            each(s, COMPANIES, List.of("item", "country"), stats, (t, r) -> {
                int c = Wikidata.qOrZero(t.get(r, "country"));
                if (c != 0) companies.merge(Wikidata.q(t.get(r, "item")), c, Math::min);
            });

            return new SourceData(snapshot, fetchedAt, Collections.unmodifiableMap(classes), filmClasses, dates, filmLabels,
                    titles, links, seconds, signals, cast, genreParents, people, peopleLabels, peopleNames, nameItems,
                    countries, languages, characters, datesExtra, labelsAll, releases, crew, links2, series, money, awards,
                    peopleDates, peopleLinks, places, itemLabels, companies, stats);
        }
    }

    /** Значение-элемент в ячейку {@code slot} из {@code slots}; не элемент ({@code 0}) — мимо, со счётчиком {@code stat}. */
    private static void add(Map<Integer, IntList[]> m, int key, int slot, int slots, int value, Map<String, Long> stats,
                            String stat) {
        if (value == 0) {
            stats.merge(stat, 1L, Long::sum);
            return;
        }
        IntList[] a = m.computeIfAbsent(key, k -> new IntList[slots]);
        if (a[slot] == null) a[slot] = new IntList();
        a[slot].add(value);
    }

    /** GeoNames ID (P1566): целое без знака, 1…{@code Integer.MAX_VALUE}; иначе {@code 0}. */
    static int geonames(String s) {
        if (s.isEmpty() || s.length() > 10) return 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return 0;
        long v = Long.parseLong(s);
        return v >= 1 && v <= Integer.MAX_VALUE ? (int) v : 0;
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
