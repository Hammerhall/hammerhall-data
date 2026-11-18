package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Мелочи для текстов: словари из текстовых блоков, выбор, числа с запятой, склонение числительных, римские века.
 * Всё — без локали системы и без {@code String.format} с плавающей точкой: тексты одинаковы на любой ОС.
 */
final class W {
    private W() {
    }

    /** Список из текстового блока: элементы через «|», переводы строк — как пробел, края обрезаны, пустые — мимо. */
    static String[] list(String block) {
        String s = block.replace('\n', ' ');
        List<String> out = new ArrayList<>();
        for (String p : s.split("\\|")) {
            String t = p.strip().replaceAll("\\s+", " ");
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    /** Список слов через пробелы и переводы строк. */
    static String[] words(String block) {
        String t = block.strip();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }

    static String pick(Rng r, String[] a) {
        return a[r.nextInt(a.length)];
    }

    /** Предлог «с» или «со» перед словом: «со стогами», «с мельницей». */
    static String s(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.length() > 1) {
            char a = w.charAt(0), b = w.charAt(1);
            boolean cons = "бвгджзйклмнпрстфхцчшщ".indexOf(b) >= 0;
            if (a == 'щ' || ("сзшж".indexOf(a) >= 0 && cons) || w.startsWith("мной") || w.startsWith("льд")) return "со " + word;
        }
        return "с " + word;
    }

    /** Предложение с точкой и пробелом для продолжения: «… шт.» + «Россия» → «… шт. Россия». */
    static String dot(String s) {
        return s.endsWith(".") ? s + " " : s + ". ";
    }

    static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** Число с {@code decimals} знаками после запятой (запятая — десятичный разделитель). */
    static String dec(double v, int decimals) {
        long scale = 1;
        for (int i = 0; i < decimals; i++) scale *= 10;
        long x = StrictMath.round(v * scale);
        if (decimals == 0) return Long.toString(x);
        String sign = x < 0 ? "-" : "";
        x = Math.abs(x);
        StringBuilder frac = new StringBuilder(Long.toString(x % scale));
        while (frac.length() < decimals) frac.insert(0, '0');
        return sign + (x / scale) + "," + frac;
    }

    /** Сантиметры: целое — без дробной части, иначе — один знак (шаг 0,5). */
    static String cm(double v) {
        double h = StrictMath.round(v * 2) / 2.0;
        return h == StrictMath.floor(h) ? Long.toString((long) h) : dec(h, 1);
    }

    /** Случайная величина с шагом 0,5 в [lo, hi]. */
    static double half(Rng r, double lo, double hi) {
        int n = (int) StrictMath.round((hi - lo) * 2);
        return lo + r.nextInt(n + 1) / 2.0;
    }

    /** Форма существительного при числе: 1 чашка, 2 чашки, 5 чашек. */
    static String plural(long n, String one, String few, String many) {
        long a = Math.abs(n) % 100, b = a % 10;
        if (a >= 11 && a <= 14) return many;
        if (b == 1) return one;
        if (b >= 2 && b <= 4) return few;
        return many;
    }

    static String count(long n, String one, String few, String many) {
        return n + " " + plural(n, one, few, many);
    }

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII",
            "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX", "XXI"};

    static String roman(int n) {
        return ROMAN[n];
    }

    /** Век года (1801–1900 — XIX). */
    static int century(int year) {
        return (year - 1) / 100 + 1;
    }

    /** Предложение: с заглавной и с точкой на конце. */
    static String sentence(String s) {
        String t = s.strip();
        if (t.isEmpty()) return t;
        char last = t.charAt(t.length() - 1);
        if (last != '.' && last != '!' && last != '?' && last != '»' && last != ')') t = t + ".";
        else if (last == '»' || last == ')') t = t + ".";
        return cap(t);
    }

    /** Склеить предложения через пробел. */
    static String join(List<String> sentences) {
        StringBuilder sb = new StringBuilder();
        for (String s : sentences) {
            if (s == null || s.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(sentence(s));
        }
        return sb.toString();
    }

    /** Десятилетие: 1912 → «1910-е годы». */
    static String decade(int year) {
        return (year / 10 * 10) + "-е годы";
    }

    /** Десятилетие в родительном: «1910-х годов». */
    static String decadeGen(int year) {
        return (year / 10 * 10) + "-х годов";
    }

    /** Две последние цифры года — так художники часто датируют: «[19]12». */
    static String yy(int year) {
        int v = year % 100;
        return v < 10 ? "0" + v : Integer.toString(v);
    }

    /** Буква-инициал имени: «И.» */
    static String initial(String name) {
        if (name == null || name.isEmpty()) return "";
        int cp = name.codePointAt(0);
        return new String(Character.toChars(cp)) + ".";
    }

    // ---------------------------------------------------------------- транслитерация (для проверок)

    private static final String RU = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] LAT = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o",
            "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"};

    /** Кириллица → латиница, упрощённо (для сверки с латинскими списками и отпечатками). */
    static String latin(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < low.length(); i++) {
            char c = low.charAt(i);
            int k = RU.indexOf(c);
            sb.append(k >= 0 ? LAT[k] : String.valueOf(c));
        }
        return sb.toString();
    }

    /** Нормализация для сверки: нижний регистр, «ё» → «е», только буквы и цифры. */
    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
