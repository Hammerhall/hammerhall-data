package io.hammerhall.data.auc.catalog;

import io.hammerhall.data.core.check.CheckReport;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Самопроверка текстов вещей: поля не пусты, длины — в пределах; имя мастера есть в описании,
 * если мастер указан и атрибуция не {@code unknown}; годы в датировке — внутри {@code year_from…year_to}; проба и
 * материал из полей — в тексте описания. Считается на лету, пока вещи пишутся; нарушения — счётчиками с примером.
 */
final class TextAudit {
    private static final Pattern YEAR = Pattern.compile("(?<![0-9])(1[0-9]{3}|20[0-9]{2})(?![0-9])");
    private final Map<String, Long> fails = new TreeMap<>();
    private final Map<String, String> example = new TreeMap<>();
    private long items, provenance;

    void item(int id, Texts.ItemText t, Texts.Maker maker) {
        items++;
        len("title_ru 20–120", id, t.titleRu(), 20, 120);
        len("title_en 20–120", id, t.titleEn(), 20, 120);
        len("description_ru 200–1200", id, t.descriptionRu(), 200, 1200);
        len("condition_ru 30–400", id, t.conditionRu(), 30, 400);
        if (maker != null && !"unknown".equals(t.attribution())) {
            if (!mentions(t.descriptionRu(), maker)) fail("мастер есть в описании", id, maker.nameRu());
        }
        if (maker == null && !"unknown".equals(t.attribution())) fail("без мастера — атрибуция unknown", id, t.attribution());
        if (t.datingRu() != null) {
            Matcher m = YEAR.matcher(t.datingRu());
            while (m.find()) {
                int y = Integer.parseInt(m.group(1));
                boolean in = t.yearFrom() != null && t.yearTo() != null && y >= t.yearFrom() && y <= t.yearTo();
                if (!in) fail("годы датировки внутри year_from…year_to", id, t.datingRu() + " / " + t.yearFrom() + "–" + t.yearTo());
            }
        }
        if (t.yearFrom() != null && t.yearTo() != null && t.yearTo() < t.yearFrom()) fail("year_to ≥ year_from", id, t.yearFrom() + "–" + t.yearTo());
        if (t.metalFineness() != null && !t.descriptionRu().contains(String.valueOf(t.metalFineness()))) {
            fail("проба — в описании", id, String.valueOf(t.metalFineness()));
        }
        if (t.materialRu() != null) {
            String w = firstWord(t.materialRu());
            if (!w.isEmpty() && !t.descriptionRu().toLowerCase(Locale.ROOT).contains(w)) fail("материал — в описании", id, t.materialRu());
        }
        if (t.pieces() < 1) fail("pieces ≥ 1", id, String.valueOf(t.pieces()));
        for (Texts.Link l : t.provenance()) {
            provenance++;
            int max = "chain".equals(l.kind()) ? 1000 : 300;
            len("provenance.text_ru 30–" + (max == 1000 ? "1000 (chain)" : "300"), id, l.textRu(), 30, max);
        }
    }

    /**
     * Есть ли мастер в тексте: у людей — корень фамилии; у мастерских — корень любого собственного имени названия (слова с
     * заглавной буквы и слова в «ёлочках»); падежные окончания отбрасываются, регистр — как в названии.
     */
    static boolean mentions(String text, Texts.Maker m) {
        for (String c : candidates(m)) if (text.contains(stem(c))) return true;
        return false;
    }

    static java.util.List<String> candidates(Texts.Maker m) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (m.person() && m.lastName() != null) {
            out.add(m.lastName());
            return out;
        }
        String[] w = m.nameRu().split("[^\\p{L}«»-]+");
        boolean quoted = false;
        for (int i = 0; i < w.length; i++) {
            String x = w[i];
            boolean open = x.startsWith("«"), close = x.endsWith("»");
            String clean = x.replace("«", "").replace("»", "");
            if (clean.length() >= 3 && (quoted || open || Character.isUpperCase(clean.charAt(0)))) out.add(clean);
            if (open) quoted = true;
            if (close) quoted = false;
        }
        if (out.isEmpty()) out.add(m.nameRu());
        return out;
    }

    static String stem(String w) {
        if (w.length() > 5) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && "ьйаяоеиыу".indexOf(w.charAt(w.length() - 1)) >= 0) return w.substring(0, w.length() - 1);
        return w;
    }

    private static String firstWord(String s) {
        String[] w = s.toLowerCase(Locale.ROOT).split("[^\\p{L}]+");
        for (String x : w) if (x.length() >= 4) return x.length() > 6 ? x.substring(0, x.length() - 2) : x;
        return "";
    }

    private void len(String rule, int id, String s, int min, int max) {
        if (s == null || s.isBlank()) fail(rule + " — не пусто", id, "пусто");
        else if (s.length() < min || s.length() > max) fail(rule, id, s.length() + " знаков: " + s);
    }

    private void fail(String rule, int id, String what) {
        fails.merge(rule, 1L, Long::sum);
        example.putIfAbsent(rule, "вещь " + id + ": " + (what.length() > 160 ? what.substring(0, 160) + "…" : what));
    }

    void report(CheckReport r) {
        r.expect("тексты вещей: длины, мастер в описании, годы в датировке, проба и материал", fails.isEmpty(),
                fails.isEmpty() ? items + " вещей, " + provenance + " звеньев" : fails + " — например " + example);
    }

    Map<String, Long> fails() {
        return fails;
    }
}
