package io.hammerhall.data.auc.text;

import java.util.Locale;

/**
 * Русские имена и слова — латиницей, как в англоязычных каталогах (упрощённая система BGN): «Сухотин» → «Sukhotin»,
 * «Ельчанинов» → «Yelchaninov», «Залесская» → «Zalesskaya», «Дмитриевич» → «Dmitriyevich», «-ский» → «-sky».
 */
final class Translit {
    private Translit() {
    }

    private static final String VOWELS = "аеёиоуыэюя";

    static String ru(String s) {
        StringBuilder out = new StringBuilder();
        String low = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < s.length(); i++) {
            char c = low.charAt(i);
            boolean upper = Character.isUpperCase(s.charAt(i));
            char prev = i > 0 ? low.charAt(i - 1) : ' ';
            boolean start = i == 0 || !Character.isLetter(prev);
            boolean afterSoft = prev == 'ь' || prev == 'ъ' || VOWELS.indexOf(prev) >= 0;
            String t;
            if ((c == 'и' || c == 'ы') && i + 1 < s.length() && low.charAt(i + 1) == 'й'
                    && (i + 2 >= s.length() || !Character.isLetter(low.charAt(i + 2)))) {
                t = "y";
                i++;
            } else {
                t = switch (c) {
                    case 'а' -> "a";
                    case 'б' -> "b";
                    case 'в' -> "v";
                    case 'г' -> "g";
                    case 'д' -> "d";
                    case 'е' -> start || afterSoft ? "ye" : "e";
                    case 'ё' -> start || afterSoft || "жчшщ".indexOf(prev) < 0 ? "yo" : "o";
                    case 'ж' -> "zh";
                    case 'з' -> "z";
                    case 'и' -> prev == 'ь' ? "yi" : "i";
                    case 'й' -> "y";
                    case 'к' -> "k";
                    case 'л' -> "l";
                    case 'м' -> "m";
                    case 'н' -> "n";
                    case 'о' -> "o";
                    case 'п' -> "p";
                    case 'р' -> "r";
                    case 'с' -> "s";
                    case 'т' -> "t";
                    case 'у' -> "u";
                    case 'ф' -> "f";
                    case 'х' -> "kh";
                    case 'ц' -> "ts";
                    case 'ч' -> "ch";
                    case 'ш' -> "sh";
                    case 'щ' -> "shch";
                    case 'ъ', 'ь' -> "";
                    case 'ы' -> "y";
                    case 'э' -> "e";
                    case 'ю' -> "yu";
                    case 'я' -> "ya";
                    default -> String.valueOf(s.charAt(i));
                };
            }
            if (upper && !t.isEmpty()) t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
            out.append(t);
        }
        return out.toString();
    }
}
