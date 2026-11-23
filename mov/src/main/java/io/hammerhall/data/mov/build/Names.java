package io.hammerhall.data.mov.build;

import io.hammerhall.data.mov.source.SourceData;
import io.hammerhall.data.mov.source.Wikidata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Люди: полное имя, разбиение на имя и фамилию, пол актёра — чистые функции от значений снимка.
 */
public final class Names {
    private Names() {
    }

    // ---------------------------------------------------------------- полное имя

    /**
     * Полное имя: метка {@code en} → {@code mul} → первая метка запасного латинского языка по порядку
     * {@link Wikidata#LABEL_LANGS} после {@code ru}; уточнение в скобках в конце метки убирается
     * ({@link #stripQualifier}). Метка, от которой после этого ничего не осталось, — как отсутствующая. Нет — {@code null}
     * (человек не берётся).
     *
     * @param labels метки по {@link Wikidata#LABEL_LANGS} (годные для TSV; нет — {@code null})
     */
    public static String fullName(String[] labels) {
        if (labels == null) return null;
        for (int i = 0; i < labels.length; i++) {
            if (i == Wikidata.RU) continue;
            if (labels[i] == null) continue;
            String s = strip(stripQualifier(labels[i]));
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    /**
     * Пробел в смысле разбиения имён — как {@code str.isspace()} Python и {@code \s} его регулярных выражений:
     * пробельные знаки Java и разделители Юникода (в том числе неразрывный пробел).
     */
    public static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Текст без пробелов ({@link #isSpace}) по краям. */
    public static String strip(String s) {
        int a = 0, b = s.length();
        while (a < b && isSpace(s.charAt(a))) a++;
        while (b > a && isSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /**
     * Одно уточнение в скобках в конце метки: {@code John Smith (actor)} → {@code John Smith}. Уточнение — «(», текст
     * без скобок, «)», дальше — только пробелы; вложенные скобки — не уточнение ({@code A (b (c))} — как есть).
     * Пробелы перед уточнением убираются.
     */
    public static String stripQualifier(String label) {
        int end = label.length();
        while (end > 0 && isSpace(label.charAt(end - 1))) end--;
        if (end == 0 || label.charAt(end - 1) != ')') return label;
        int open = label.lastIndexOf('(', end - 2);
        if (open < 0 || label.substring(open + 1, end - 1).indexOf(')') >= 0) return label;
        int cut = open;
        while (cut > 0 && isSpace(label.charAt(cut - 1))) cut--;
        return label.substring(0, cut);
    }

    // ---------------------------------------------------------------- имя и фамилия

    /** Частицы, которые присоединяются к фамилии (с учётом регистра: {@code Van} — не частица). */
    public static final Set<String> PARTICLES = Set.of("van", "von", "de", "da", "di", "del", "della", "der", "den", "du",
            "la", "le", "ter", "ten", "bin", "ibn", "al");
    /** Суффиксы после фамилии — остаются в фамилии. */
    public static final Set<String> SUFFIXES = Set.of("Jr.", "Sr.", "II", "III", "IV");

    /** Имя ({@code null} — нет, мононим) и фамилия. */
    public record Split(String first, String last) {
    }

    /**
     * Порядок ссылок на имя или фамилию: по P1545 — сначала числа (по значению), затем прочие записи (по тексту), пустое —
     * последним; затем по номеру элемента.
     */
    static final Comparator<SourceData.NameRef> BY_ORDINAL = (x, y) -> {
        int c = compareOrdinal(x.ordinal(), y.ordinal());
        return c != 0 ? c : Integer.compare(x.item(), y.item());
    };

    private static int kind(String s) {
        if (s == null || s.isEmpty()) return 2;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return 1;
        return 0;
    }

    static int compareOrdinal(String a, String b) {
        int ka = kind(a), kb = kind(b);
        if (ka != kb) return Integer.compare(ka, kb);
        if (ka == 0) return new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
        return ka == 1 ? a.compareTo(b) : 0;
    }

    /** Первая ссылка по {@link #BY_ORDINAL}; нет — {@code null}. */
    public static SourceData.NameRef first(List<SourceData.NameRef> refs) {
        return refs == null || refs.isEmpty() ? null : refs.stream().min(BY_ORDINAL).orElseThrow();
    }

    /**
     * Имя и фамилия из полного имени. Есть фамилия F (метка {@code en}, иначе {@code mul} первой P734) и полное имя
     * равно F или оканчивается на «␣F» — фамилия F, имя — остаток; начинается с «F␣» (порядок «фамилия имя») — так же.
     * Иначе — {@link #heuristic}.
     *
     * @param family метка фамилии или {@code null}
     */
    public static Split split(String full, String family) {
        if (family != null && !family.isEmpty()) {
            if (full.equals(family) || full.endsWith(" " + family)) {
                return new Split(nz(strip(full.substring(0, full.length() - family.length()))), family);
            }
            if (full.startsWith(family + " ")) return new Split(nz(strip(full.substring(family.length()))), family);
        }
        return heuristic(full);
    }

    /**
     * Разбиение без фамилии: слова — через пробелы ({@link #isSpace}); фамилия — последнее слово с частицами перед ним
     * ({@link #PARTICLES}, все подряд) и суффиксом после ({@link #SUFFIXES}, остаётся в фамилии); имя — слова до неё.
     * Одно слово — имени нет, фамилия — оно. Слова имени и фамилии — через один пробел.
     */
    public static Split heuristic(String full) {
        List<String> w = new ArrayList<>();
        for (int i = 0; i < full.length(); ) {
            while (i < full.length() && isSpace(full.charAt(i))) i++;
            int j = i;
            while (j < full.length() && !isSpace(full.charAt(j))) j++;
            if (j > i) w.add(full.substring(i, j));
            i = j;
        }
        if (w.size() <= 1) return new Split(null, w.isEmpty() ? strip(full) : w.getFirst());
        int end = w.size();
        if (SUFFIXES.contains(w.get(end - 1))) end--;
        int start = end - 1;
        while (start > 0 && PARTICLES.contains(w.get(start - 1))) start--;
        return new Split(nz(String.join(" ", w.subList(0, start))), String.join(" ", w.subList(start, w.size())));
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Метка элемента фамилии или имени: {@code en}, иначе {@code mul}; нет — {@code null}. */
    public static String label(SourceData.NameItem item) {
        if (item == null) return null;
        return item.labelEn() != null ? item.labelEn() : item.labelMul();
    }

    // ---------------------------------------------------------------- пол

    /** P21, которые дают {@code M}: мужской пол (Q6581097) и Q2449503. */
    public static final Set<Integer> P21_M = Set.of(6581097, 2449503);
    /** P21, которые дают {@code F}: женский пол (Q6581072) и Q1052281. */
    public static final Set<Integer> P21_F = Set.of(6581072, 1052281);

    /** Откуда пол актёра. */
    public enum GenderPath {
        /** P21 ({@link #P21_M}, {@link #P21_F}). */
        P21,
        /** Вид первого имени P735 ({@code name_items.kind}: {@code male}, {@code female}). */
        GIVEN_NAME,
        /** Источник молчит — детерминированно от seed и id. */
        SEEDED,
        /** P21 — иное значение: в актёры человек не берётся. */
        NOT_TAKEN
    }

    /** Пол актёра и откуда он ({@code gender} — {@code null}, если путь {@link GenderPath#SEEDED} или {@link GenderPath#NOT_TAKEN}). */
    public record Gender(String gender, GenderPath path) {
    }

    /**
     * Пол актёра по источнику: P21 из {@link #P21_M} — {@code M}, из {@link #P21_F} — {@code F}, иное значение — не
     * берётся; P21 нет — вид первого имени P735 ({@code male} — {@code M}, {@code female} — {@code F}); иначе —
     * {@link GenderPath#SEEDED} (значение даёт {@link #seeded}).
     *
     * @param p21       номер элемента P21 ({@code 0} — нет)
     * @param givenKind вид первого имени P735 или {@code null}
     */
    public static Gender gender(int p21, String givenKind) {
        if (P21_M.contains(p21)) return new Gender("M", GenderPath.P21);
        if (P21_F.contains(p21)) return new Gender("F", GenderPath.P21);
        if (p21 != 0) return new Gender(null, GenderPath.NOT_TAKEN);
        if ("male".equals(givenKind)) return new Gender("M", GenderPath.GIVEN_NAME);
        if ("female".equals(givenKind)) return new Gender("F", GenderPath.GIVEN_NAME);
        return new Gender(null, GenderPath.SEEDED);
    }

    /**
     * Пол, когда источник молчит: {@code F}, если доля потока под id ({@code unit}, равномерно в [0, 1)) меньше доли
     * {@code F} среди актёров {@code full} с полом из источника ({@code femaleShare}), иначе {@code M}.
     */
    public static String seeded(double unit, double femaleShare) {
        return unit < femaleShare ? "F" : "M";
    }
}
