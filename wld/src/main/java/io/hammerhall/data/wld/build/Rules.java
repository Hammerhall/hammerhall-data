package io.hammerhall.data.wld.build;

import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.wld.source.Cldr;
import io.hammerhall.data.wld.source.SourceData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Правила сборки набора — по одному на пункт описания (README набора, «Правила»): чистые функции от значений
 * источников, без случайности и без порядка {@code HashMap}. Их же проверяют модульные тесты.
 */
public final class Rules {
    private Rules() {
    }

    // ---------------------------------------------------------------- страны

    /** Часть света по коду GeoNames ({@code Continent} в {@code countryInfo.txt}). */
    public static final Map<String, String> CONTINENTS = continents();

    private static Map<String, String> continents() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("AF", "Africa");
        m.put("AN", "Antarctica");
        m.put("AS", "Asia");
        m.put("EU", "Europe");
        m.put("NA", "North America");
        m.put("OC", "Oceania");
        m.put("SA", "South America");
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Часть света; неизвестный код — ошибка (в GeoNames их семь). */
    public static String continent(String code) {
        String c = CONTINENTS.get(code);
        if (c == null) throw new IllegalArgumentException("неизвестный код части света GeoNames: «" + code + "»");
        return c;
    }

    /** Одно из названий строки GeoNames ({@code name}, {@code asciiname}, одно из {@code alternatenames}) равно {@code want}. */
    public static boolean namesMatch(String want, String name, String asciiName, String alternateNames) {
        if (want == null || want.isEmpty()) return false;
        if (want.equals(name) || want.equals(asciiName)) return true;
        if (alternateNames == null || alternateNames.isEmpty()) return false;
        for (String a : alternateNames.split(",", -1)) if (want.equals(a)) return true;
        return false;
    }

    /** Больше население, при равенстве — меньше geonameid. */
    static final Comparator<SourceData.Place> BIGGEST = Comparator.comparingLong(SourceData.Place::population).reversed()
            .thenComparingLong(SourceData.Place::id);

    /**
     * Столица страны: среди её городов без учёта порога населения (класс {@code P}, код годится, не в списках
     * исключений; по geonameid — одна строка — отбирает вызывающий):
     * <ol>
     *   <li>код {@code PPLC} и одно из названий равно {@code Capital} из {@code countryInfo.txt};</li>
     *   <li>иначе — единственная строка {@code PPLC} страны;</li>
     *   <li>иначе — строка с названием, равным {@code Capital}, наибольшего населения (при равенстве — меньший geonameid).</li>
     * </ol>
     * Пустой {@code Capital} или ничего не нашлось — {@code null}. В первом пункте при нескольких строках — так же, как в
     * третьем.
     */
    public static Long capital(String capital, List<SourceData.Place> rows) {
        if (capital == null || capital.isEmpty()) return null;
        List<SourceData.Place> pplc = new ArrayList<>(), named = new ArrayList<>(), both = new ArrayList<>();
        for (SourceData.Place p : rows) {
            boolean isC = p.featureCode().equals("PPLC");
            if (isC) pplc.add(p);
            if (p.namedCapital()) named.add(p);
            if (isC && p.namedCapital()) both.add(p);
        }
        if (!both.isEmpty()) return both.stream().min(BIGGEST).orElseThrow().id();
        if (pplc.size() == 1) return pplc.getFirst().id();
        if (!named.isEmpty()) return named.stream().min(BIGGEST).orElseThrow().id();
        return null;
    }

    /** Базовый язык тега CLDR: {@code zh-Hant} → {@code zh}. */
    public static String baseLanguage(String tag) {
        int dash = tag.indexOf('-');
        return dash < 0 ? tag : tag.substring(0, dash);
    }

    /** Официальные языки территории ({@code official}, {@code de_facto_official}): по убыванию доли, при равенстве — по коду. */
    public static List<Cldr.LanguagePopulation> officialByShare(List<Cldr.LanguagePopulation> langs) {
        List<Cldr.LanguagePopulation> out = new ArrayList<>();
        for (Cldr.LanguagePopulation l : langs) if (l.official()) out.add(l);
        out.sort((a, b) -> {
            BigDecimal x = a.percent() == null ? BigDecimal.ZERO : a.percent(), y = b.percent() == null ? BigDecimal.ZERO : b.percent();
            int c = y.compareTo(x);
            return c != 0 ? c : io.hammerhall.data.wld.source.Snapshot.compareBytes(a.tag(), b.tag());
        });
        return out;
    }

    /**
     * Название страны на главном языке ({@code local_name}): официальные языки по убыванию доли
     * ({@link #officialByShare}); берётся первый, у чьей локали ({@code <код с «-»>}, а нет такой локали — базового
     * языка) в {@code territories.json} есть название этой страны. Ничего — {@code null}.
     *
     * @param localeNames локаль → названия территорий; нет локали — нет ключа
     */
    public static String localName(String iso2, List<Cldr.LanguagePopulation> langs, Map<String, Map<String, String>> localeNames) {
        for (Cldr.LanguagePopulation l : officialByShare(langs)) {
            String tag = l.tag();
            Map<String, String> names = localeNames.get(tag);
            if (names == null && tag.indexOf('-') >= 0) names = localeNames.get(baseLanguage(tag));
            if (names == null) continue;
            String v = names.get(iso2);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /**
     * Подрегион ООН страны: запись {@code territoryContainment} с числовым кодом, не группировка и не устаревшая, чья
     * {@code _contains} содержит ISO2 страны (такая должна быть одна; не одна — {@code null}); нет числовой — такая же
     * запись с буквенным кодом ({@code QO}). Нет — {@code null}.
     */
    public static String region(String iso2, List<Cldr.Container> containment) {
        List<String> numeric = new ArrayList<>(), alpha = new ArrayList<>();
        for (Cldr.Container c : containment) {
            if (c.grouping() || c.deprecated() || !c.contains().contains(iso2)) continue;
            if (c.code().matches("[0-9]+")) numeric.add(c.code());
            else if (c.code().matches("[A-Z]+")) alpha.add(c.code());
        }
        if (!numeric.isEmpty()) return numeric.size() == 1 ? numeric.getFirst() : null;
        return alpha.size() == 1 ? alpha.getFirst() : null;
    }

    /**
     * Название языка по тегу CLDR на языке локали ({@code en} или {@code ru}) — первое найденное:
     * <ol>
     *   <li>{@code languages.json} по тегу целиком;</li>
     *   <li>тег {@code xx-Yyyy} — название базового {@code xx} + « (» + письменность {@code Yyyy} из {@code scripts.json} + «)»;</li>
     *   <li>метка Wikidata базового кода (ISO 639-3; элемент с наименьшим Q среди элементов с меткой) — с письменностью
     *       CLDR в скобках, если в теге она есть.</li>
     * </ol>
     * Ничего — {@code null}.
     */
    public static String languageName(String tag, Map<String, String> languages, Map<String, String> scripts,
                                      Map<String, String> wikidata) {
        String v = languages.get(tag);
        if (v != null && !v.isEmpty()) return v;
        int dash = tag.indexOf('-');
        String baseCode = dash < 0 ? tag : tag.substring(0, dash);
        String script = null;
        if (dash >= 0) {
            script = scripts.get(tag.substring(dash + 1));
            String base = languages.get(baseCode);
            if (base != null && !base.isEmpty() && script != null && !script.isEmpty()) return base + " (" + script + ")";
        }
        String w = wikidata.get(baseCode);
        if (w == null || w.isEmpty()) return null;
        if (dash < 0) return w;
        return script == null || script.isEmpty() ? null : w + " (" + script + ")";
    }

    /**
     * Соседство: пара (A, B) и (B, A) — если хотя бы одна сторона перечисляет другую в {@code neighbours} и обе
     * страны взяты; сама с собой — нет. Итог — пары ISO2 по порядку.
     */
    public static Set<List<String>> borders(Map<String, List<String>> neighbours, Set<String> taken) {
        Set<List<String>> out = new TreeSet<>(Comparator.<List<String>, String>comparing(List::getFirst)
                .thenComparing(l -> l.get(1)));
        for (Map.Entry<String, List<String>> e : neighbours.entrySet()) {
            String a = e.getKey();
            if (!taken.contains(a)) continue;
            for (String b : e.getValue()) {
                if (b.equals(a) || !taken.contains(b)) continue;
                out.add(List.of(a, b));
                out.add(List.of(b, a));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- числа World Bank

    /** Доллары → миллионы долларов: значение / 1 000 000, половина — к чётному, 2 знака. */
    public static BigDecimal millions(BigDecimal usd) {
        return usd == null ? null : usd.movePointLeft(6).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Продолжительность жизни: половина — к чётному, 3 знака. */
    public static BigDecimal lifeExpectancy(BigDecimal v) {
        return v == null ? null : v.setScale(3, RoundingMode.HALF_EVEN);
    }

    /** Значение как есть, если его запись в выпуске (шкала {@code scale}) не потеряет знаков; иначе — ошибка. */
    public static BigDecimal exact(BigDecimal v, int scale, String what) {
        if (v == null) return null;
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() > scale) throw new IllegalStateException(what + ": " + v.toPlainString() + " — знаков после точки больше " + scale);
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    /** Целое значение; дробное — ошибка. */
    public static Long integral(BigDecimal v, String what) {
        if (v == null) return null;
        try {
            return v.stripTrailingZeros().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException(what + ": " + v.toPlainString() + " — не целое", e);
        }
    }

    // ---------------------------------------------------------------- города

    /** Коды GeoNames класса {@code P}, которые в города не идут (части городов, исторические, заброшенные, разрушенные). */
    public static final Set<String> SKIPPED_CODES = Set.copyOf(new LinkedHashSet<>(List.of("PPLX", "PPLH", "PPLQ", "PPLW", "PPLCH")));

    /** Код населённого пункта годится для города. */
    public static boolean cityCode(String featureCode) {
        return !SKIPPED_CODES.contains(featureCode);
    }

    /** Порог населения городов профиля: {@code full} — 1 000, {@code medium} — 15 000, {@code small} — 100 000. */
    public static long minPopulation(Profile p) {
        return switch (p) {
            case FULL -> 1_000;
            case MEDIUM -> 15_000;
            case SMALL -> 100_000;
        };
    }
}
