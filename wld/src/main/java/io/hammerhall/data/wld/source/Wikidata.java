package io.hammerhall.data.wld.source;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ответы Wikidata (SPARQL) в снимке — CSV с заголовком ({@link Csv}): сборка читает {@code wikidata/cities_ru.csv} и
 * {@code wikidata/languages.csv}; прочие файлы {@code wikidata/} снимка ей не нужны. Тексты запросов — в
 * {@code wikidata/queries/}.
 */
public final class Wikidata {
    private Wikidata() {
    }

    /** Номер элемента: {@code Q42} → 42; ссылка {@code http://www.wikidata.org/entity/Q42} — тоже. */
    public static long q(String item) {
        String s = item.strip();
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        if (s.length() < 2 || s.charAt(0) != 'Q') throw new IllegalArgumentException("не элемент Wikidata: " + item);
        return Long.parseLong(s.substring(1));
    }

    /** Русская метка элемента. */
    public record Label(long q, String label) {
    }

    /**
     * Годится ли метка: не пустая и без знаков, которых нет в TSV выпуска (обратная косая, табуляция, перевод строки,
     * управляющие). Негодная метка — как отсутствующая: значение источника не правим.
     */
    static boolean usable(String label) {
        if (label == null || label.isEmpty()) return false;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '\\' || c < 0x20 || c == 0x7f) return false;
        }
        return true;
    }

    /**
     * {@code cities_ru.csv} ({@code geonameid,item,label_ru}): geonameid → метка элемента с наименьшим номером Q среди
     * элементов с годной меткой ({@link #usable}). Строка с geonameid не числом — пропускается (их число —
     * {@code skipped[0]}).
     */
    public static Map<Long, Label> citiesRu(Reader in, String file, long[] skipped) throws IOException {
        Csv.Table t = new Csv.Table(new Csv(in, file), List.of("geonameid", "item", "label_ru"));
        Map<Long, Label> out = new HashMap<>();
        for (List<String> r; (r = t.next()) != null; ) {
            long gid, q;
            try {
                gid = Long.parseLong(t.get(r, "geonameid").strip());
                q = q(t.get(r, "item"));
            } catch (IllegalArgumentException e) {
                skipped[0]++;
                continue;
            }
            String label = t.get(r, "label_ru");
            if (!usable(label)) continue;
            Label prev = out.get(gid);
            if (prev == null || q < prev.q()) out.put(gid, new Label(q, label));
        }
        return out;
    }

    /**
     * Названия языков по коду ISO 639-3 ({@code languages.csv}: {@code code,item,label_en,label_ru}): у каждого кода —
     * метка элемента с наименьшим номером Q среди элементов с непустой меткой этого языка.
     *
     * @param en код → английская метка
     * @param ru код → русская метка
     */
    public record LanguageLabels(Map<String, String> en, Map<String, String> ru) {
        public static final LanguageLabels NONE = new LanguageLabels(Map.of(), Map.of());

        /** Метки языка: {@code en} или {@code ru}. */
        public Map<String, String> of(String locale) {
            return switch (locale) {
                case "en" -> en;
                case "ru" -> ru;
                default -> throw new IllegalArgumentException("метки Wikidata — en и ru, а не " + locale);
            };
        }
    }

    /** {@code languages.csv}: метки языков (см. {@link LanguageLabels}). */
    public static LanguageLabels languages(Reader in, String file) throws IOException {
        Csv.Table t = new Csv.Table(new Csv(in, file), List.of("code", "item", "label_en", "label_ru"));
        Map<String, Label> en = new HashMap<>(), ru = new HashMap<>();
        for (List<String> r; (r = t.next()) != null; ) {
            String code = t.get(r, "code");
            long q = q(t.get(r, "item"));
            pick(en, code, q, t.get(r, "label_en"));
            pick(ru, code, q, t.get(r, "label_ru"));
        }
        return new LanguageLabels(labels(en), labels(ru));
    }

    private static void pick(Map<String, Label> m, String code, long q, String label) {
        if (!usable(label)) return;
        Label prev = m.get(code);
        if (prev == null || q < prev.q()) m.put(code, new Label(q, label));
    }

    private static Map<String, String> labels(Map<String, Label> m) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Label> e : m.entrySet()) out.put(e.getKey(), e.getValue().label());
        return java.util.Collections.unmodifiableMap(out);
    }
}
