package io.hammerhall.data.mov.build;

import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.mov.Exclusions;
import io.hammerhall.data.mov.source.Snapshot;
import io.hammerhall.data.mov.source.SourceData;
import io.hammerhall.data.mov.source.Wikidata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Правила сборки набора — по одному на пункт описания (README набора, «Правила»): чистые функции от значений
 * снимка, без случайности и без порядка {@code HashMap}. Жанры — {@link Genres}, люди — {@link Names}, рейтинг —
 * {@link Rank}. Их же проверяют модульные тесты.
 */
public final class Rules {
    private Rules() {
    }

    // ---------------------------------------------------------------- отбор фильмов

    /** Классы P31, которые включает снимок (фильмы этих классов в нём есть). */
    public static final Set<Integer> FILM_CLASSES = Set.of(11424, 24869, 24862, 17517379, 202866, 29168811, 20650540,
            18089587, 93204, 506240, 226730);
    /** Классы P31, при которых фильм не берётся: клип (Q193977), трейлер (Q622550), проект (Q18011172), серия (Q24856). */
    public static final Set<Integer> EXCLUDED_CLASSES = Set.of(193977, 622550, 18011172, 24856);
    /** Первый год набора: раньше — не фильм в смысле набора (ошибка даты в источнике). */
    public static final int FIRST_YEAR = 1874;

    /**
     * Классы фильма годятся: среди них нет исключённых ({@link #EXCLUDED_CLASSES} и классы {@code classes.csv} с
     * {@code included = 0}) и есть хотя бы один включённый ({@link #FILM_CLASSES} или {@code included = 1}).
     */
    public static boolean classesOk(int[] classes, Map<Integer, Boolean> snapshotClasses) {
        boolean any = false;
        for (int c : classes) {
            Boolean inc = snapshotClasses.get(c);
            if (EXCLUDED_CLASSES.contains(c) || Boolean.FALSE.equals(inc)) return false;
            if (FILM_CLASSES.contains(c) || Boolean.TRUE.equals(inc)) any = true;
        }
        return any;
    }

    /** Год и дата выхода фильма. */
    public record Released(int year, LocalDate date) {
    }

    /**
     * Год и дата выхода по P577: утверждения лучшего ранга — {@code preferred}, если у фильма они есть, иначе
     * {@code normal} (как «истинные» значения Wikidata); из них — с точностью не ниже года (precision ≥ 9); год —
     * самой ранней из них; дата — самая ранняя с точностью до дня (precision 11) среди тех же утверждений, иначе
     * пусто. Год вне {@code FIRST_YEAR…lastYear} или подходящих утверждений нет — {@code null} (фильм не берётся).
     */
    public static Released released(List<SourceData.DateStmt> dates, int lastYear) {
        if (dates == null) return null;
        boolean anyPreferred = false;
        for (SourceData.DateStmt d : dates) anyPreferred |= d.preferred();
        SourceData.DateStmt earliest = null, day = null;
        for (SourceData.DateStmt d : dates) {
            if (d.preferred() != anyPreferred || d.precision() < 9) continue;
            if (earliest == null || d.compareDate(earliest) < 0) earliest = d;
            if (d.precision() == 11 && (day == null || d.compareDate(day) < 0)) day = d;
        }
        if (earliest == null) return null;
        int year = earliest.year();
        if (year < FIRST_YEAR || year > lastYear) return null;
        LocalDate date = null;
        if (day != null) {
            try {
                date = LocalDate.of(day.year(), day.month(), day.day());
            } catch (DateTimeException e) {
                date = null; // дата не по календарю (месяц или день 0) — как без даты
            }
        }
        return new Released(year, date);
    }

    /** Откуда год фильма: P577, иначе по очереди запасные свойства ({@link SourceData#YEAR_FALLBACK}). */
    public enum YearPath {
        P577, P1191, P571, P2754
    }

    /** Год и дата выхода фильма и откуда год. */
    public record Year(int year, LocalDate date, YearPath path) {
    }

    /**
     * Год фильма с запасными путями: по P577 ({@link #released}); нет — по очереди P1191 (первый показ), P571
     * (создание), P2754 (дата производства), каждое — тем же правилом (лучший ранг, точность не ниже года, самая
     * ранняя, год в {@code FIRST_YEAR…lastYear}). Дата выхода — только из P577: год по запасному свойству — без даты.
     * Нет ни по одному — {@code null} (фильм не берётся).
     *
     * @param extra свойство → утверждения фильма ({@code film_dates_extra}); нет свойства — нет утверждений
     */
    public static Year year(List<SourceData.DateStmt> p577, Map<String, List<SourceData.DateStmt>> extra, int lastYear) {
        Released r = released(p577, lastYear);
        if (r != null) return new Year(r.year(), r.date(), YearPath.P577);
        for (String p : SourceData.YEAR_FALLBACK) {
            r = released(extra == null ? null : extra.get(p), lastYear);
            if (r != null) return new Year(r.year(), null, YearPath.valueOf(p));
        }
        return null;
    }

    /** P1476: сначала язык {@code en}, затем остальные — по языку, затем по тексту (по байтам). */
    static final Comparator<SourceData.Title> TITLE_ORDER = Comparator
            .<SourceData.Title>comparingInt(t -> t.lang().equals("en") ? 0 : 1)
            .thenComparing(SourceData.Title::lang, Snapshot::compareBytes)
            .thenComparing(SourceData.Title::title, Snapshot::compareBytes);

    /**
     * Название фильма: метка {@code en} → {@code mul} → P1476 ({@link #TITLE_ORDER}) → метка первого запасного языка
     * ({@link Wikidata#LABEL_LANGS} после {@code ru}) → метка {@code ru}. Нет — {@code null} (фильм не берётся).
     */
    public static String title(String[] labels, List<SourceData.Title> titles) {
        Titled t = titled(labels, titles, null);
        return t == null ? null : t.title();
    }

    /** Откуда название фильма. */
    public enum TitlePath {
        /** Метка {@code en}. */
        LABEL_EN,
        /** Метка {@code mul}. */
        LABEL_MUL,
        /** P1476. */
        P1476,
        /** Метка запасного языка ({@link Wikidata#LABEL_LANGS} после {@code ru}). */
        LABEL_LATIN,
        /** Метка {@code ru} (название — то же, что {@code title_ru}). */
        LABEL_RU,
        /** Метка на любом другом языке ({@code film_labels_all}). */
        LABEL_ANY
    }

    /** Название фильма и откуда оно. */
    public record Titled(String title, TitlePath path) {
    }

    /** Метки {@code film_labels_all}: по коду языка, затем по тексту (по байтам). */
    static final Comparator<SourceData.Title> LABEL_ORDER = Comparator
            .<SourceData.Title, String>comparing(SourceData.Title::lang, Snapshot::compareBytes)
            .thenComparing(SourceData.Title::title, Snapshot::compareBytes);

    /**
     * Название фильма: цепочка {@link #title}; нет — метка из {@code film_labels_all}: языки по байтам кода, первая
     * годная (в снимке уже только годные). Нет — {@code null} (фильм не берётся).
     *
     * @param all метки фильма на любых языках ({@code film_labels_all}) или {@code null}
     */
    public static Titled titled(String[] labels, List<SourceData.Title> titles, List<SourceData.Title> all) {
        if (labels != null && labels[Wikidata.EN] != null) return new Titled(labels[Wikidata.EN], TitlePath.LABEL_EN);
        if (labels != null && labels[Wikidata.MUL] != null) return new Titled(labels[Wikidata.MUL], TitlePath.LABEL_MUL);
        if (titles != null && !titles.isEmpty()) return new Titled(titles.stream().min(TITLE_ORDER).orElseThrow().title(), TitlePath.P1476);
        if (labels != null) {
            for (int i = Wikidata.FIRST_LATIN; i < labels.length; i++) if (labels[i] != null) return new Titled(labels[i], TitlePath.LABEL_LATIN);
            if (labels[Wikidata.RU] != null) return new Titled(labels[Wikidata.RU], TitlePath.LABEL_RU);
        }
        if (all != null) {
            for (SourceData.Title t : all.stream().sorted(LABEL_ORDER).toList()) {
                if (Wikidata.usable(t.title())) return new Titled(t.title(), TitlePath.LABEL_ANY);
            }
        }
        return null;
    }

    /**
     * Названия фильма по языкам ({@code movies_titles}): метки {@link Wikidata#LABEL_LANGS} и метки
     * {@code film_labels_all} — годные, одна на язык: у языка несколько — меньшая по байтам. Язык → название, языки —
     * по байтам кода.
     */
    public static Map<String, String> titles(String[] labels, List<SourceData.Title> all) {
        Map<String, String> out = new java.util.TreeMap<>(Snapshot::compareBytes);
        if (labels != null) {
            for (int i = 0; i < labels.length; i++) if (labels[i] != null) out.put(Wikidata.LABEL_LANGS.get(i), labels[i]);
        }
        if (all != null) {
            for (SourceData.Title t : all) {
                if (!Wikidata.usable(t.lang()) || !Wikidata.usable(t.title())) continue;
                out.merge(t.lang(), t.title(), (a, b) -> Snapshot.compareBytes(a, b) <= 0 ? a : b);
            }
        }
        return out;
    }

    /** Название по-русски: метка {@code ru} или пусто. */
    public static String titleRu(String[] labels) {
        return labels == null ? null : labels[Wikidata.RU];
    }

    /** Хронометраж в минутах (вне этого — пусто). */
    public static final int MIN_RUNTIME = 1, MAX_RUNTIME = 1_500;

    /**
     * Хронометраж одного значения: секунды P2047 / 60, округление половины к чётному до целого по точной десятичной
     * записи (5 310 с = 88,5 → 88); вне {@link #MIN_RUNTIME}…{@link #MAX_RUNTIME} — {@code null}.
     */
    public static Integer minutes(BigDecimal seconds) {
        if (seconds == null) return null;
        BigDecimal m = seconds.divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_EVEN);
        if (m.compareTo(BigDecimal.valueOf(MIN_RUNTIME)) < 0 || m.compareTo(BigDecimal.valueOf(MAX_RUNTIME)) > 0) return null;
        return m.intValueExact();
    }

    /** Хронометраж фильма: из значений P2047, что дают минуты в допустимых пределах ({@link #minutes}), — наименьшее; нет — {@code null}. */
    public static Integer runtime(List<BigDecimal> seconds) {
        if (seconds == null) return null;
        Integer best = null;
        for (BigDecimal s : seconds) {
            Integer m = minutes(s);
            if (m != null && (best == null || m < best)) best = m;
        }
        return best;
    }

    /**
     * Дубли по IMDb: у нескольких фильмов один ID IMDb — остаётся фильм с большим sitelinks, при равенстве — с меньшим
     * id; остальные — в итоге (их не берём). Фильм с несколькими ID проигрывает, если не первый хоть в одной группе.
     *
     * @param films кандидаты (прошли прочие правила отбора)
     */
    public static Set<Integer> imdbDuplicates(Set<Integer> films, Map<Integer, SourceData.Signals> signals) {
        Map<String, List<Integer>> byImdb = new HashMap<>();
        for (int f : films) {
            SourceData.Signals s = signals.get(f);
            if (s == null) continue;
            for (String id : s.imdb()) byImdb.computeIfAbsent(id, k -> new ArrayList<>(2)).add(f);
        }
        Set<Integer> out = new HashSet<>();
        for (List<Integer> g : byImdb.values()) {
            if (g.size() < 2) continue;
            int best = g.getFirst();
            for (int f : g) {
                int c = Integer.compare(sitelinks(signals, f), sitelinks(signals, best));
                if (c > 0 || (c == 0 && f < best)) best = f;
            }
            for (int f : g) if (f != best) out.add(f);
        }
        return out;
    }

    private static int sitelinks(Map<Integer, SourceData.Signals> signals, int film) {
        SourceData.Signals s = signals.get(film);
        return s == null ? 0 : s.sitelinks();
    }

    // ---------------------------------------------------------------- роли

    /**
     * Роль строки состава: {@code character_name} (P4633), иначе метка {@code en} → {@code mul} персонажа (P453),
     * иначе пусто. Роль со знаками, которых нет в TSV ({@link Wikidata#usable}), — пусто ({@code null}).
     *
     * @param characterLabels метки персонажа {@code {en, mul}} или {@code null}
     */
    public static String role(String characterName, String[] characterLabels) {
        String r = characterName;
        if (r == null || r.isEmpty()) {
            r = null;
            if (characterLabels != null) r = characterLabels[0] != null ? characterLabels[0] : characterLabels[1];
        }
        return Wikidata.usable(r) ? r : null;
    }

    /** Между ролями одного актёра в одном фильме. */
    public static final String ROLE_SEPARATOR = " / ";

    /**
     * Роли актёра в фильме — одной строкой: непустые роли без повторов по порядку байтов через
     * {@link #ROLE_SEPARATOR}; нет ни одной — {@code null}. Строка {@code roles} — одна на пару «актёр, фильм» (ключ
     * таблицы — пара: пустая роль не может быть полем ключа).
     */
    public static String roles(java.util.Collection<String> roles) {
        TreeSet<String> s = new TreeSet<>(Snapshot::compareBytes);
        for (String r : roles) if (r != null) s.add(r);
        return s.isEmpty() ? null : String.join(ROLE_SEPARATOR, s);
    }

    // ---------------------------------------------------------------- страны и языки

    /**
     * Код страны фильма (P495): ISO 3166-1 alpha-3 элемента страны (несколько — меньший по байтам); нет кода или код
     * из списка {@link Exclusions} (по ISO3 или ISO2 элемента) — {@code null}.
     */
    public static String countryCode(SourceData.Codes codes, Exclusions ex) {
        if (codes == null) return null;
        for (String iso2 : codes.first()) if (ex.country2(iso2)) return null;
        for (String iso3 : codes.second()) {
            if (!iso3.matches("[A-Z]{3}")) continue;
            return ex.country3(iso3) ? null : iso3;
        }
        return null;
    }

    /** Код языка фильма (P364): ISO 639-1 элемента, иначе ISO 639-3 (несколько — меньший по байтам); нет — {@code null}. */
    public static String languageCode(SourceData.Codes codes) {
        if (codes == null) return null;
        for (String c : codes.first()) if (c.matches("[a-z]{2}")) return c;
        for (String c : codes.second()) if (c.matches("[a-z]{3}")) return c;
        return null;
    }

    // ---------------------------------------------------------------- деньги

    /** Доллар США (Q4917) — единственная валюта бюджета и сборов набора. */
    public static final int USD = 4917;

    /**
     * Бюджет или сборы фильма в долларах США: из сумм ({@code film_money} одного свойства) — только в долларах США
     * ({@link #USD}); из них — лучшего ранга ({@code preferred}, если есть, иначе {@code normal}); наибольшая; целое —
     * половина к чётному по точной записи. Не больше нуля, больше {@code bigint} или сумм нет — {@code null}. Суммы в
     * других валютах не пересчитываются и не берутся.
     *
     * @param boxOffice {@code true} — сборы (P2142), {@code false} — бюджет (P2130)
     */
    public static Long usd(List<SourceData.MoneyStmt> money, boolean boxOffice) {
        if (money == null) return null;
        boolean anyPreferred = false;
        for (SourceData.MoneyStmt m : money) anyPreferred |= m.boxOffice() == boxOffice && m.unit() == USD && m.preferred();
        BigDecimal best = null;
        for (SourceData.MoneyStmt m : money) {
            if (m.boxOffice() != boxOffice || m.unit() != USD || m.preferred() != anyPreferred) continue;
            if (best == null || m.amount().compareTo(best) > 0) best = m.amount();
        }
        if (best == null) return null;
        BigDecimal v = best.setScale(0, RoundingMode.HALF_EVEN);
        if (v.signum() <= 0 || v.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) return null;
        return v.longValueExact();
    }

    // ---------------------------------------------------------------- люди: даты, город, страны

    /** Первый год дат жизни человека: раньше — ошибка даты в источнике (или не человек кино). */
    public static final int FIRST_LIFE_YEAR = 1700;

    /** Даты жизни человека: рождение и смерть — дата (точность до дня) и год; нет — {@code null}. */
    public record Life(LocalDate birthDate, Integer birthYear, LocalDate deathDate, Integer deathYear) {
        public static final Life NONE = new Life(null, null, null, null);
    }

    /** Утверждения лучшего ранга: {@code preferred}, если они есть, иначе {@code normal}. */
    static List<SourceData.DateStmt> best(List<SourceData.DateStmt> dates) {
        if (dates == null || dates.isEmpty()) return List.of();
        boolean anyPreferred = false;
        for (SourceData.DateStmt d : dates) anyPreferred |= d.preferred();
        List<SourceData.DateStmt> out = new ArrayList<>(dates.size());
        for (SourceData.DateStmt d : dates) if (d.preferred() == anyPreferred) out.add(d);
        return out;
    }

    /** Дата (точность до дня) и год одного события жизни; нет — {@code null}. */
    public record Event(LocalDate date, Integer year) {
    }

    /**
     * Дата и год одного события жизни (P569 или P570): утверждения лучшего ранга; дата — самая ранняя с точностью до
     * дня (не дата по календарю — пусто); год — самой ранней с точностью не ниже года. Год (и год даты) вне
     * {@link #FIRST_LIFE_YEAR}…{@code lastYear} — пусто.
     */
    public static Event event(List<SourceData.DateStmt> dates, int lastYear) {
        SourceData.DateStmt earliest = null, day = null;
        for (SourceData.DateStmt d : best(dates)) {
            if (d.precision() < 9) continue;
            if (earliest == null || d.compareDate(earliest) < 0) earliest = d;
            if (d.precision() == 11 && (day == null || d.compareDate(day) < 0)) day = d;
        }
        Integer year = earliest != null && earliest.year() >= FIRST_LIFE_YEAR && earliest.year() <= lastYear ? earliest.year() : null;
        LocalDate date = day != null && day.year() >= FIRST_LIFE_YEAR && day.year() <= lastYear ? calendar(day) : null;
        return new Event(date, year);
    }

    /**
     * Даты жизни: рождение и смерть — по {@link #event}; смерть — только если есть рождение не позже неё: дата смерти —
     * при дате рождения не позже, год смерти — при годе рождения не больше (иначе пусто).
     */
    public static Life life(SourceData.LifeDates dates, int lastYear) {
        if (dates == null) return Life.NONE;
        Event b = event(dates.birth(), lastYear), d = event(dates.death(), lastYear);
        LocalDate dd = d.date();
        Integer dy = d.year();
        if (dd != null && (b.date() == null || b.date().isAfter(dd))) dd = null;
        if (dy != null && (b.year() == null || b.year() > dy)) dy = null;
        return new Life(b.date(), b.year(), dd, dy);
    }

    /** Дата по календарю из утверждения (год 1…9999, как пишет {@code YYYY-MM-DD}); не дата — {@code null}. */
    public static LocalDate calendar(SourceData.DateStmt d) {
        if (d == null || d.year() < 1 || d.year() > 9_999) return null;
        try {
            return LocalDate.of(d.year(), d.month(), d.day());
        } catch (DateTimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- съёмочная группа, студии, серии, награды

    /** Должность в группе ({@code movies_crew.job}) по свойству {@link SourceData#CREW_PROPS} (тот же порядок). */
    public static final List<String> CREW_JOBS = List.of("writer", "composer", "cinematographer", "producer", "editor");
    /** Роль компании в фильме ({@code movies_companies.role}): студия (P272), прокатчик (P750). */
    public static final String PRODUCTION = "production", DISTRIBUTION = "distribution";

    /** Название элемента-справочника (студия, серия, награда): метка {@code en}, иначе {@code mul}; нет — {@code null}. */
    public static String itemName(String[] labels) {
        if (labels == null) return null;
        return labels[SourceData.ITEM_EN] != null ? labels[SourceData.ITEM_EN] : labels[SourceData.ITEM_MUL];
    }

    /** Название элемента-справочника по-русски: метка {@code ru}; нет — {@code null}. */
    public static String itemNameRu(String[] labels) {
        return labels == null ? null : labels[SourceData.ITEM_RU];
    }

    /** Номер в серии: P1545 — целое от 1 до стольких. */
    public static final int MAX_ORDINAL = 10_000;

    /**
     * Номер фильма в серии: из значений P1545 пары «фильм, серия» — целые (только цифры) от 1 до {@link #MAX_ORDINAL};
     * из них — наименьшее; нет — {@code null}.
     */
    public static Integer ordinal(java.util.Collection<String> ordinals) {
        Integer best = null;
        for (String o : ordinals) {
            if (o == null) continue;
            String s = o.strip();
            if (s.isEmpty() || s.length() > 9) continue;
            boolean digits = true;
            for (int i = 0; i < s.length(); i++) digits &= s.charAt(i) >= '0' && s.charAt(i) <= '9';
            if (!digits) continue;
            int v = Integer.parseInt(s);
            if (v >= 1 && v <= MAX_ORDINAL && (best == null || v < best)) best = v;
        }
        return best;
    }

    /**
     * Год награды или номинации: из дат P585 строк тройки «фильм, награда, выиграна ли» — самый ранний год среди дат с
     * точностью не ниже года; нет — {@code null} (год вне {@code smallint} — как без даты).
     */
    public static Integer awardYear(java.util.Collection<SourceData.DateStmt> dates) {
        Integer best = null;
        for (SourceData.DateStmt d : dates) {
            if (d == null || d.precision() < 9 || d.year() < Short.MIN_VALUE || d.year() > Short.MAX_VALUE) continue;
            if (best == null || d.year() < best) best = d.year();
        }
        return best;
    }

    /**
     * Дата выхода в стране ({@code movies_releases}): утверждение P577 с местом P291 и точностью до дня, дата по
     * календарю ({@link #calendar}); иначе — {@code null} (строки нет).
     */
    public static LocalDate releaseDay(SourceData.DateStmt d) {
        return d == null || d.precision() != 11 ? null : calendar(d);
    }

    // ---------------------------------------------------------------- жанры режиссёров

    /** Доля: {@code part / total}, половина — к чётному, 4 знака. */
    public static BigDecimal prob(int part, int total) {
        if (total <= 0 || part < 0 || part > total) throw new IllegalArgumentException("доля " + part + " / " + total);
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_EVEN);
    }

    // ---------------------------------------------------------------- профили

    /** Фильмов в профиле из {@code n}: {@code full} — все, {@code medium} — 10 %, {@code small} — 1 % (округление вверх). */
    public static int profileSize(int n, Profile p) {
        return switch (p) {
            case FULL -> n;
            case MEDIUM -> (int) ((n + 9L) / 10);
            case SMALL -> (int) ((n + 99L) / 100);
        };
    }

    /** Доля профиля словами — для паспорта и SOURCE.md. */
    public static String profileShare(Profile p) {
        return switch (p) {
            case FULL -> "все фильмы";
            case MEDIUM -> "10 % самых известных фильмов";
            case SMALL -> "1 % самых известных фильмов";
        };
    }
}
