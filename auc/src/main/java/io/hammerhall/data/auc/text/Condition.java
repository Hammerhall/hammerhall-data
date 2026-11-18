package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Состояние вещи: код шкалы раздела — общая ({@code excellent, very_good, good, fair, poor}), у
 * монет, медалей и бон — нумизматическая ({@code unc, au, xf, vf, f, vg}), у марок — {@code mnh, mh, used}; и общий
 * уровень 1–5 (1 — лучший) для всех шкал — для модели цены. Текст —
 * 30–400 знаков: начало по шкале, замечания по виду и тяжести, иногда заключительное замечание. Словари — секции
 * {@code cond.*} в {@code vocab/common.txt}.
 */
final class Condition {
    private Condition() {
    }

    static final String[] GRADES = {"excellent", "very_good", "good", "fair", "poor"};
    static final double[] GRADE_W = {0.12, 0.33, 0.33, 0.17, 0.05};
    static final String[] COIN_GRADES = {"unc", "au", "xf", "vf", "f", "vg"};
    static final double[] COIN_W = {0.10, 0.13, 0.29, 0.28, 0.14, 0.06};
    /** Общий уровень по нумизматической степени (0 — лучший). */
    private static final int[] COIN_TO_GRADE = {0, 1, 1, 2, 3, 4};
    private static final String[] COIN_WORDS = {"без следов обращения", "почти без следов обращения",
            "отличная", "очень хорошая", "хорошая", "удовлетворительная"};
    static final String[] STAMP_GRADES = {"mnh", "mh", "used"};
    /** Все коды шкал — для проверок. */
    static final java.util.Set<String> ALL_CODES = java.util.Set.of("excellent", "very_good", "good", "fair", "poor", "unc",
            "au", "xf", "vf", "f", "vg", "mnh", "mh", "used");

    private record Defects(String[] minor, String[] moderate, String[] major) {
    }

    private static final Map<String, Defects> DEFECTS = new HashMap<>();
    private static final String[][] HEAD = new String[5][];
    private static final String[] CLOSE = V.list("common", "cond.close");
    private static final String[] EXCELLENT_EXTRA = V.list("common", "cond.excellent.extra");

    static {
        for (String sec : V.sectionNames("common")) {
            if (!sec.startsWith("cond.") || !sec.endsWith(".minor")) continue;
            String k = sec.substring("cond.".length(), sec.length() - ".minor".length());
            DEFECTS.put(k, new Defects(V.list("common", "cond." + k + ".minor"), V.list("common", "cond." + k + ".moderate"),
                    V.list("common", "cond." + k + ".major")));
        }
        for (int i = 0; i < GRADES.length; i++) HEAD[i] = V.list("common", "cond.head." + GRADES[i]);
    }

    private static Defects defects(Ctx c) {
        Defects d = c.defects != null ? DEFECTS.get(c.defects) : null;
        if (d == null) d = DEFECTS.get(c.kind.name());
        if (d == null) throw new IllegalStateException("нет замечаний о состоянии для " + c.kind);
        return d;
    }

    /** Заполнить {@code grade}, {@code coinGrade} и {@code condition}. */
    static void fill(Ctx c) {
        Rng r = c.r;
        Defects d = defects(c);
        String text;
        if (c.kind == Kind.COIN || c.kind == Kind.MEDAL || c.kind == Kind.BANKNOTE) {
            int g = r.weighted(COIN_W);
            c.grade = COIN_GRADES[g];
            c.level = COIN_TO_GRADE[g] + 1;
            List<String> notes = notes(c, d, g <= 1 ? 0 : g <= 3 ? 1 : 2, g == 0 ? (r.chance(0.5) ? 1 : 0) : 1 + r.nextInt(2));
            String head = "Сохранность " + COIN_GRADES[g].toUpperCase(java.util.Locale.ROOT) + " (" + COIN_WORDS[g] + ")";
            text = notes.isEmpty() ? head : head + ": " + String.join(", ", notes);
        } else if (c.kind == Kind.STAMP) {
            int g = r.weighted(new double[]{0.35, 0.25, 0.40});
            String head = switch (g) {
                case 0 -> W.pick(r, new String[]{"Негашёная, с оригинальным клеем, без наклейки (**)",
                        "Чистая, полный оригинальный клей, без следов наклейки"});
                case 1 -> W.pick(r, new String[]{"Негашёная, со следом наклейки (*)", "Чистая, с наклейкой, клей сохранён"});
                default -> W.pick(r, new String[]{"Гашёная", "Гашёная, чёткий календарный штемпель", "Гашёная, штемпель частично"});
            };
            c.grade = STAMP_GRADES[g];
            c.level = g == 0 ? (r.chance(0.6) ? 1 : 2) : g == 1 ? (r.chance(0.6) ? 2 : 3) : (r.chance(0.7) ? 3 : 4);
            List<String> notes = notes(c, d, r.chance(0.7) ? 0 : 1, r.chance(0.5) ? 0 : 1);
            text = notes.isEmpty() ? head : head + "; " + String.join(", ", notes);
        } else {
            int g = r.weighted(GRADE_W);
            c.grade = GRADES[g];
            c.level = g + 1;
            String head = W.pick(r, HEAD[g]);
            if (g == 0) {
                text = r.chance(0.6) ? head + ", " + W.pick(r, EXCELLENT_EXTRA) : head;
            } else {
                int sev = g == 1 ? 0 : g == 2 ? (r.chance(0.6) ? 0 : 1) : g == 3 ? 1 : 2;
                int n = g == 1 ? 1 + r.nextInt(2) : 1 + r.nextInt(3);
                text = head + (r.chance(0.5) ? ": " : "; ") + String.join(", ", notes(c, d, sev, n));
            }
        }
        text = W.sentence(text);
        if (text.length() < 30 || r.chance(0.25)) text = text + " " + W.sentence(W.pick(r, CLOSE));
        c.condition = text;
    }

    private static List<String> notes(Ctx c, Defects d, int severity, int n) {
        Rng r = c.r;
        List<String> out = new ArrayList<>();
        String[][] pools = {d.minor, d.moderate, d.major};
        for (int i = 0; i < n * 4 && out.size() < n; i++) {
            int s = i == 0 ? severity : Math.max(0, severity - r.nextInt(2));
            String p = c.pick(pools[s]);
            if (p != null && !out.contains(p)) out.add(p);
        }
        if (out.isEmpty()) out.add(severity == 0 ? "следы времени" : "заметные следы времени и бытования");
        return out;
    }
}
