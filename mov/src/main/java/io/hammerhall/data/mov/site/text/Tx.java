package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.check.PublicNames;

import java.util.List;
import java.util.Locale;

/**
 * Мелочи для текстов: чистка пробелов и знаков, предложение с заглавной и точкой, подсчёт слов, форма слова при числе,
 * транслитерация (для проверки внутренних имён) и чистка значений со стороны (названия, имена). Без локали системы.
 */
final class Tx {
    private Tx() {
    }

    /** Пробелы — по одному, без пробела перед «,.:;!?»)» и после «« („(», края обрезаны. */
    static String tidy(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == ' ') {
                space = true;
                continue;
            }
            if (space && !sb.isEmpty() && ",.:;!?»)“".indexOf(c) < 0) {
                char p = sb.charAt(sb.length() - 1);
                if (p != '«' && p != '(' && p != '„') sb.append(' ');
            }
            space = false;
            // «,,» и «, ,» из пустых вариантов
            if (c == ',' && !sb.isEmpty() && sb.charAt(sb.length() - 1) == ',') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /** С заглавной буквы (если начинается со строчной). */
    static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        char c = s.charAt(0);
        return Character.isLowerCase(c) ? Character.toUpperCase(c) + s.substring(1) : s;
    }

    /** Со строчной буквы (если начинается с заглавной, а вторая — строчная: «Сюжет» → «сюжет», «ТВ» — как есть). */
    static String uncap(String s) {
        if (s == null || s.length() < 2) return s;
        char c = s.charAt(0);
        return Character.isUpperCase(c) && Character.isLowerCase(s.charAt(1)) ? Character.toLowerCase(c) + s.substring(1) : s;
    }

    /** Предложение: чистка пробелов, заглавная буква, точка в конце (если нет «.!?…»). */
    static String sentence(String s) {
        String t = cap(tidy(s));
        if (t.isEmpty()) return t;
        char last = t.charAt(t.length() - 1);
        if (last == ',' || last == ':' || last == ';') t = t.substring(0, t.length() - 1);
        last = t.charAt(t.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '…' ? t : t + ".";
    }

    /** Предложения через пробел. */
    static String join(List<String> sentences) {
        StringBuilder sb = new StringBuilder(512);
        for (String s : sentences) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
    }

    /** Число слов: подряд идущие буквы, цифры, «-» и «+» внутри (тире «—» и знаки — не слова). */
    static int words(String s) {
        int n = 0;
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean w = Character.isLetterOrDigit(c) || (in && (c == '-' || c == '+' || c == '\''));
            if (w && !in) n++;
            in = w;
        }
        return n;
    }

    /** Форма существительного при числе: 1 балл, 3 балла, 8 баллов. */
    static String plural(long n, String one, String few, String many) {
        long a = Math.abs(n) % 100, b = a % 10;
        if (a >= 11 && a <= 14) return many;
        if (b == 1) return one;
        if (b >= 2 && b <= 4) return few;
        return many;
    }

    private static final String RU = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] LAT = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o",
            "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"};

    /** Кириллица → латиница, упрощённо (для сверки с латинскими отпечатками). */
    static String latin(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(low.length() + 8);
        for (int i = 0; i < low.length(); i++) {
            char c = low.charAt(i);
            int k = RU.indexOf(c);
            sb.append(k >= 0 ? LAT[k] : String.valueOf(c));
        }
        return sb.toString();
    }

    /** Внутреннее имя платформы — как есть или латиницей. */
    static boolean internal(String s) {
        return PublicNames.isInternal(s) || PublicNames.isInternal(latin(s));
    }

    /**
     * Значение со стороны (название, имя): управляющие знаки и переводы строк — в пробел, пробелы — по одному;
     * пустое, длиннее {@code max} или с внутренним именем платформы — {@code null} (текст о нём молчит).
     */
    static String clean(String s, int max) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == ' ') {
                space = true;
                continue;
            }
            if (space && !sb.isEmpty()) sb.append(' ');
            space = false;
            sb.append(c);
        }
        String t = sb.toString();
        if (t.isEmpty() || t.length() > max || internal(t)) return null;
        return t;
    }

    /** Название в «ёлочках»; свои «ёлочки» внутри — в „лапки“. */
    static String quote(String title) {
        return "«" + title.replace('«', '„').replace('»', '“') + "»";
    }
}
