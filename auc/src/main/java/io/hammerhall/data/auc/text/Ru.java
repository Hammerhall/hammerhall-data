package io.hammerhall.data.auc.text;

/**
 * Склонение имён в текстах: родительный падеж («работа Ивана Сухотина», «из собрания А. П. Сухотиной»), множественное
 * число фамилии («братья Сухотины», «семьи Сухотиных»). Правила — под словари этого пакета: русские фамилии на
 * -ов/-ев/-ин/-ын, -ский/-цкий, -ых/-их; иностранные — склоняется последнее слово, если оно на согласную (мужские),
 * женские — только польские на -ская и чешские на -ова. Словари подобраны так, чтобы правил хватало (тест).
 */
final class Ru {
    private Ru() {
    }

    private static final String HUSH = "гкхжшщч";

    private static boolean consonant(char c) {
        return "бвгджзклмнпрстфхцчшщ".indexOf(c) >= 0;
    }

    /** Родительный мужского имени: Иван → Ивана, Пётр → Петра, Илья → Ильи, Андрей → Андрея, Луи → Луи. */
    static String genMaleFirst(String n) {
        switch (n) {
            case "Пётр" -> {
                return "Петра";
            }
            case "Павел" -> {
                return "Павла";
            }
            case "Лев" -> {
                return "Льва";
            }
            default -> {
            }
        }
        char last = n.charAt(n.length() - 1);
        if (n.endsWith("уа")) return n;
        if (last == 'й' || last == 'ь') return n.substring(0, n.length() - 1) + "я";
        if (last == 'а') return n.substring(0, n.length() - 1) + (HUSH.indexOf(n.charAt(n.length() - 2)) >= 0 ? "и" : "ы");
        if (last == 'я') return n.substring(0, n.length() - 1) + "и";
        if (consonant(last)) return n + "а";
        return n;
    }

    /** Родительный женского имени: Анна → Анны, Мария → Марии, Ольга → Ольги, Любовь → Любови, Мари → Мари. */
    static String genFemaleFirst(String n) {
        char last = n.charAt(n.length() - 1);
        if (last == 'я') return n.substring(0, n.length() - 1) + "и";
        if (last == 'а') return n.substring(0, n.length() - 1) + (HUSH.indexOf(n.charAt(n.length() - 2)) >= 0 ? "и" : "ы");
        if (last == 'ь') return n.substring(0, n.length() - 1) + "и";
        return n;
    }

    static String genFirst(String n, boolean female) {
        return female ? genFemaleFirst(n) : genMaleFirst(n);
    }

    /** Родительный отчества: Петрович → Петровича, Петровна → Петровны. */
    static String genPatronymic(String p) {
        if (p.endsWith("на")) return p.substring(0, p.length() - 1) + "ы";
        return p + "а";
    }

    /** Женская форма русской или польской фамилии от мужской: Сухотин → Сухотина, Залесский → Залесская. */
    static String feminineSlavic(String m) {
        if (m.endsWith("ский") || m.endsWith("цкий")) return m.substring(0, m.length() - 2) + "ая";
        if (m.endsWith("ых") || m.endsWith("их")) return m;
        if (m.endsWith("ов") || m.endsWith("ев") || m.endsWith("ёв") || m.endsWith("ин") || m.endsWith("ын")) return m + "а";
        return m;
    }

    /** Женская форма чешской фамилии: Новак → Новакова. */
    static String feminineCzech(String m) {
        return m + "ова";
    }

    /** Родительный фамилии (последнего слова; частицы «ван», «де» и т. п. — как есть). */
    static String genSurname(String s, boolean female) {
        int sp = s.lastIndexOf(' ');
        String head = sp < 0 ? "" : s.substring(0, sp + 1), w = sp < 0 ? s : s.substring(sp + 1);
        return head + genSurnameWord(w, female);
    }

    private static String genSurnameWord(String w, boolean female) {
        if (female) {
            if (w.endsWith("ская") || w.endsWith("цкая")) return w.substring(0, w.length() - 2) + "ой";
            if (w.endsWith("ова") || w.endsWith("ева") || w.endsWith("ёва") || w.endsWith("ина") || w.endsWith("ына")) {
                return w.substring(0, w.length() - 1) + "ой";
            }
            return w;
        }
        if (w.endsWith("ский") || w.endsWith("цкий")) return w.substring(0, w.length() - 2) + "ого";
        if (w.endsWith("ых") || w.endsWith("их")) return w;
        char last = w.charAt(w.length() - 1);
        if (last == 'й' || last == 'ь') return w.substring(0, w.length() - 1) + "я";
        if (consonant(last)) return w + "а";
        return w;
    }

    /** Фамилия семьи во множественном: Сухотин → Сухотины, Залесский → Залесские; иностранные — как есть. */
    static String pluralSurname(String m) {
        if (m.endsWith("ский") || m.endsWith("цкий")) return m.substring(0, m.length() - 2) + "ие";
        if (m.endsWith("ых") || m.endsWith("их")) return m;
        if (m.endsWith("ов") || m.endsWith("ев") || m.endsWith("ёв") || m.endsWith("ин") || m.endsWith("ын")) return m + "ы";
        return m;
    }

    /** Родительный множественного: Сухотиных, Залесских; иностранные — как есть («семьи Обер»). */
    static String pluralGenSurname(String m) {
        if (m.endsWith("ский") || m.endsWith("цкий")) return m.substring(0, m.length() - 2) + "их";
        if (m.endsWith("ых") || m.endsWith("их")) return m;
        if (m.endsWith("ов") || m.endsWith("ев") || m.endsWith("ёв") || m.endsWith("ин") || m.endsWith("ын")) return m + "ых";
        return m;
    }
}
