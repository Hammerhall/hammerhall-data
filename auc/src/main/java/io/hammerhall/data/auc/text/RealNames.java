package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.check.PublicNames;

import java.util.HashSet;
import java.util.Set;

/**
 * Настоящие имена, которых в данных быть не должно (словарь {@code vocab/real-names.txt}): фамилии известных художников, скульпторов, медальеров, ювелиров,
 * часовщиков, фабрикантов фарфора, серебра, мебели и игрушек, издателей; марки и фирмы (часы, ювелирные дома,
 * мануфактуры, производители игрушек), аукционные дома и галереи; люди, чьё имя узнаётся само по себе. Словари
 * {@link Nation} составлены без них — список страхует: имя мастера или человека, где хоть одно слово совпало
 * (с учётом женской формы), отбрасывается, а придуманное из слогов название — ещё и при расстоянии Левенштейна ≤ 1
 * до марки. Список не исчерпывает мир: совпадение с малоизвестным мастером где-то ещё не исключено.
 */
final class RealNames {
    private RealNames() {
    }




    /** Частицы фамилий («ван», «де», «фон») — не имена. */
    private static final Set<String> PARTICLES = Set.of("ван", "де", "фон", "ди", "да", "ла", "ле", "дер");

    private static final Set<String> NAMES = new HashSet<>();
    /**
     * Часть «людей, чьё имя узнаётся само по себе» — отпечатками, как внутренние имена в {@code PublicNames}: сам список
     * в открытом коде не пишется. Отпечаток — первые 16 знаков sha256 от {@link W#norm} фамилии.
     */
    private static final Set<String> HIDDEN = Set.of(
            "10fd0217e032d1da", "346b1f894918b8c1", "34b7f1b02353f002", "373d49e9c4fa949f", "38c600eb474c0839",
            "63d5f7c041347ff2", "7b0fee65e23d532f", "7ec86ca73b09bfc2", "8c72ac0dab2e659c", "9c7106a5ee097c96",
            "ace88c6ff74484ec", "be96cf5b2107cea6");
    private static final String[] BRAND_LIST;

    static {
        for (String w : V.words("real-names", "artists")) addName(w);
        for (String w : V.words("real-names", "famous")) addName(w);
        // «~» — обычное слово русского языка (марка часов, издательство): только для сверки придуманных
        // названий, в именах людей и фирм слово само по себе не запрещено
        String[] b = V.words("real-names", "brands");
        BRAND_LIST = new String[b.length];
        for (int i = 0; i < b.length; i++) {
            boolean common = b[i].startsWith("~");
            String w = common ? b[i].substring(1) : b[i];
            if (!common) addName(w);
            BRAND_LIST[i] = W.norm(w);
        }
    }

    private static void addName(String w) {
        NAMES.add(W.norm(w));
        for (String part : w.split("-")) if (part.length() >= 3 && !PARTICLES.contains(part)) NAMES.add(W.norm(part));
    }

    /** Настоящее имя: в списке открытым текстом или среди отпечатков. */
    private static boolean known(String n) {
        return NAMES.contains(n) || HIDDEN.contains(fingerprint(n));
    }

    static String fingerprint(String word) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(word.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Мужская основа женской формы: Серебрякова → серебряков, Залесская → залесский. */
    private static String maleStem(String n) {
        if (n.endsWith("ская") || n.endsWith("цкая")) return n.substring(0, n.length() - 2) + "ий";
        if (n.endsWith("ова") || n.endsWith("ева") || n.endsWith("ина") || n.endsWith("ына")) return n.substring(0, n.length() - 1);
        return n;
    }

    /** Совпадает ли какое-то слово имени с настоящим (или это внутреннее имя платформы). */
    static boolean blocked(String name) {
        if (PublicNames.isInternal(name) || PublicNames.isInternal(W.latin(name))) return true;
        for (String w : name.split("[\\s«»\"'.,()]+")) {
            if (w.isEmpty()) continue;
            String n = W.norm(w);
            if (n.length() < 2) continue;
            if (PARTICLES.contains(n)) continue;
            if (known(n) || known(maleStem(n))) return true;
            for (String part : w.split("-")) {
                String p = W.norm(part);
                if (p.length() >= 3 && (known(p) || known(maleStem(p)))) return true;
            }
        }
        return false;
    }

    /** Придуманное из слогов название: ещё и не ближе одной правки к марке (от 5 букв). */
    static boolean resemblesBrand(String coined) {
        if (blocked(coined)) return true;
        String n = W.norm(coined);
        for (String b : BRAND_LIST) {
            if (b.length() >= 5 && Math.abs(b.length() - n.length()) <= 1 && W.levenshtein(b, n) <= 1) return true;
            if (b.length() >= 5 && n.contains(b)) return true;
        }
        return false;
    }

    private static final String[] TRADE = V.words("real-names", "trade");
    private static final String[] TRADE_LATIN = V.words("real-names", "trade.latin");

    private static boolean near(String w, String t) {
        if (w.length() < 4 || t.length() < 4) return w.equals(t);
        int max = t.length() >= 7 ? 2 : 1;
        if (Math.abs(w.length() - t.length()) > max) return false;
        return W.levenshtein(w, t) <= max;
    }

    /**
     * Созвучно ли какое-то слово названия известному аукционному дому, антиквару, галерее или торговцу искусством
     * (секции {@code trade}, {@code trade.latin}): ≤ 1 правки до 6 букв, ≤ 2 — от 7 букв; кириллица и латиница.
     */
    static boolean resemblesTrade(String name) {
        for (String raw : name.split("[^\\p{L}-]+")) {
            if (raw.isEmpty()) continue;
            String w = raw.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
            boolean latin = w.chars().allMatch(ch -> ch < 0x400);
            for (String t : latin ? TRADE_LATIN : TRADE) {
                if (near(w, t.replace('ё', 'е'))) return true;
            }
            if (!latin) {
                String lw = W.latin(w).replace("-", "");
                for (String t : TRADE_LATIN) if (near(lw, t)) return true;
            }
        }
        return false;
    }

    /** Сколько слов в списке (для отчёта). */
    static int size() {
        return NAMES.size() + HIDDEN.size();
    }
}
