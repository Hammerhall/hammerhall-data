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

    /** P1476: сначала язык {@code en}, затем остальные — по языку, затем по тексту (по байтам). */
    static final Comparator<SourceData.Title> TITLE_ORDER = Comparator
            .<SourceData.Title>comparingInt(t -> t.lang().equals("en") ? 0 : 1)
            .thenComparing(SourceData.Title::lang, Snapshot::compareBytes)
            .thenComparing(SourceData.Title::title, Snapshot::compareBytes);

    /**
     * Название фильма: метка {@code en} → {@code mul} → P1476 ({@link #TITLE_ORDER}) → метка первого запасного языка
     * ({@link Wikidata#LABEL_LANGS} после {@code ru}). Нет — {@code null} (фильм не берётся).
     */
    public static String title(String[] labels, List<SourceData.Title> titles) {
        if (labels != null && labels[Wikidata.EN] != null) return labels[Wikidata.EN];
        if (labels != null && labels[Wikidata.MUL] != null) return labels[Wikidata.MUL];
        if (titles != null && !titles.isEmpty()) return titles.stream().min(TITLE_ORDER).orElseThrow().title();
        if (labels != null) {
            for (int i = Wikidata.FIRST_LATIN; i < labels.length; i++) if (labels[i] != null) return labels[i];
        }
        return null;
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
