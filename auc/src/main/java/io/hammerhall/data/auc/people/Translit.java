package io.hammerhall.data.auc.people;

/** Латиница для адресов почты: упрощённая транслитерация (как в загранпаспорте, без диакритики и апострофов). */
public final class Translit {
    private Translit() {
    }

    private static final String RU = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] LAT = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "i", "k", "l", "m", "n", "o",
            "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "iu", "ia"};

    /** Латиница с заглавными буквами слов: «Иван Сухотин» → «Ivan Sukhotin»; знаки препинания и пробелы — как есть. */
    public static String en(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        boolean start = true;
        for (char c : s.toCharArray()) {
            char lc = Character.toLowerCase(c);
            int i = RU.indexOf(lc);
            if (i >= 0) {
                String l = LAT[i];
                if (start && !l.isEmpty()) l = Character.toUpperCase(l.charAt(0)) + l.substring(1);
                sb.append(l);
                start = l.isEmpty() && start;
            } else {
                sb.append(c);
                start = !Character.isLetterOrDigit(c);
            }
        }
        return sb.toString();
    }

    public static String latin(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (char c : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            int i = RU.indexOf(c);
            if (i >= 0) sb.append(LAT[i]);
            else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }
}
