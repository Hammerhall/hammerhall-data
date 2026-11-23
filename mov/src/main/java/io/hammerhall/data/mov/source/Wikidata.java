package io.hammerhall.data.mov.source;

import java.util.List;

/**
 * Значения ответов Wikidata в снимке: номер элемента, годность текста для TSV выпуска, языки меток.
 */
public final class Wikidata {
    private Wikidata() {
    }

    /** Языки меток снимка по порядку предпочтения; после {@code ru} — запасные латинские (для имён без en/mul). */
    public static final List<String> LABEL_LANGS = List.of("en", "mul", "ru", "de", "fr", "es", "it", "pt", "nl", "sv", "pl");
    public static final int EN = 0, MUL = 1, RU = 2;
    /** Первый запасной язык (после {@code ru}). */
    public static final int FIRST_LATIN = 3;

    /** Номер языка в {@link #LABEL_LANGS}; нет — {@code -1}. */
    public static int lang(String code) {
        return LABEL_LANGS.indexOf(code);
    }

    /**
     * Номер элемента: {@code Q42} → 42; ссылка {@code http://www.wikidata.org/entity/Q42} — тоже. Не элемент (пусто,
     * «неизвестное значение», лексема) — ошибка; номер больше {@code int} — ошибка (id набора — {@code int}).
     */
    public static int q(String item) {
        String s = item.strip();
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        if (s.length() < 2 || s.charAt(0) != 'Q') throw new IllegalArgumentException("не элемент Wikidata: «" + item + "»");
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') throw new IllegalArgumentException("не элемент Wikidata: «" + item + "»");
        }
        long v = Long.parseLong(s.substring(1));
        if (v <= 0 || v > Integer.MAX_VALUE) throw new IllegalArgumentException("номер элемента вне int: «" + item + "»");
        return (int) v;
    }

    /** Номер элемента или {@code 0}, если поле пустое или не элемент. */
    public static int qOrZero(String item) {
        if (item == null || item.isBlank()) return 0;
        try {
            return q(item);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /**
     * Годится ли текст для выпуска: не пустой и без знаков, которых нет в TSV выпуска (обратная косая, табуляция,
     * перевод строки, управляющие). Негодный текст — как отсутствующий: значение источника не правим.
     */
    public static boolean usable(String label) {
        if (label == null || label.isEmpty()) return false;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '\\' || c < 0x20 || c == 0x7f) return false;
        }
        return true;
    }
}
