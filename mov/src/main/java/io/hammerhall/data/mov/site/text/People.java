package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Логины и отображаемые имена пользователей из словарей {@code names.txt} и {@code logins.txt}.
 *
 * <p>Логин — по одному из узоров (доли — {@link #PATTERN_W}): имя и цифры ({@code dima1987}), имя и фамилия
 * ({@code kuznetsova_anna}, {@code a_petrov}), прилагательное и существительное ({@code silent_fox77}), два
 * существительных, русское слово латиницей ({@code kotik_88}), кино-слово ({@code kinoman_buff}), имя со словом
 * ({@code masha_owl}), приставка ({@code mr_fox}), город ({@code dima_spb}). Цифры — год рождения (4 или 2 знака) или
 * случайное число. Годен логин 4–20 знаков, {@code [a-z][a-z0-9_]*}, без «__» и «_» в конце, без внутренних имён.
 *
 * <p>Отображаемое имя — «Анна К.» (имя по полу и весу, буква — от фамилии по весу), иногда ({@link #NICK_SHARE}) — ник.
 */
final class People {
    /** Имя или фамилия с весом и написаниями латиницей. */
    record Name(String ru, double weight, List<String> latin) {
    }

    static final Pattern LOGIN = Pattern.compile("[a-z][a-z0-9_]{3,19}");
    static final double NICK_SHARE = 0.12;
    /** Доли узоров логина (порядок — как в {@link #build}). */
    static final double[] PATTERN_W = {0.14, 0.17, 0.17, 0.07, 0.12, 0.10, 0.13, 0.05, 0.05};

    final List<Name> male, female, surnames;
    private final double[] maleW, femaleW, surnameW;
    final List<String> nickAny, nickM, nickF;
    final List<String> adj, noun, ru, kino, fan, prefix, city;

    People() {
        Map<String, List<String>> n = Vocab.sections("names.txt");
        male = names(n.get("male"));
        female = names(n.get("female"));
        surnames = names(n.get("surnames"));
        maleW = weights(male);
        femaleW = weights(female);
        surnameW = weights(surnames);
        nickAny = Vocab.items(n.get("nick.any"));
        nickM = Vocab.items(n.get("nick.M"));
        nickF = Vocab.items(n.get("nick.F"));
        Map<String, List<String>> l = Vocab.sections("logins.txt");
        adj = Vocab.words(l.get("adj"));
        noun = Vocab.words(l.get("noun"));
        ru = Vocab.words(l.get("ru"));
        kino = Vocab.words(l.get("kino"));
        fan = Vocab.words(l.get("fan"));
        prefix = Vocab.words(l.get("prefix"));
        city = Vocab.words(l.get("city"));
    }

    private static List<Name> names(List<String> lines) {
        if (lines == null) throw new IllegalStateException("names.txt: нет секции");
        List<Name> out = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split("\\s+");
            if (f.length < 3) throw new IllegalStateException("names.txt: «имя вес латиница…»: " + line);
            out.add(new Name(f[0], Double.parseDouble(f[1]), List.copyOf(Arrays.asList(f).subList(2, f.length))));
        }
        return List.copyOf(out);
    }

    private static double[] weights(List<Name> names) {
        double[] w = new double[names.size()];
        for (int i = 0; i < w.length; i++) w[i] = names.get(i).weight();
        return w;
    }

    // ---------------------------------------------------------------- отображаемое имя

    String displayName(Rng r, char gender) {
        boolean f = switch (gender) {
            case 'F', 'f' -> true;
            case 'M', 'm' -> false;
            default -> throw new IllegalArgumentException("пол — 'M' или 'F', а не '" + gender + "'");
        };
        if (r.chance(NICK_SHARE)) return r.chance(0.7) ? r.pick(nickAny) : r.pick(f ? nickF : nickM);
        Name first = f ? female.get(r.weighted(femaleW)) : male.get(r.weighted(maleW));
        Name last = surnames.get(r.weighted(surnameW));
        return first.ru() + " " + last.ru().charAt(0) + ".";
    }

    // ---------------------------------------------------------------- логин

    String login(Rng r) {
        for (int i = 0; i < 64; i++) {
            String s = build(r);
            if (valid(s)) return s;
        }
        // запас (не бывает на словарях набора): слово и четыре цифры
        return r.pick(noun) + r.between(1000, 9999);
    }

    static boolean valid(String s) {
        return LOGIN.matcher(s).matches() && !s.contains("__") && !s.endsWith("_") && !Tx.internal(s) && !rude(s);
    }

    /**
     * Нежелательная латиница внутри логина (брань, имена политиков, внутренние имена соседних наборов — склейки слов
     * дают их нечаянно: «…le» + «nina»):
     * отпечатки — первые 16 знаков sha256 подстроки, как у {@code PublicNames}; сам список в открытом коде не пишется.
     */
    static final Set<String> RUDE = Set.of(
            "038c9ccdd226f572", "0631a609edb7c79f", "0951caa80b947130", "10fa8018c94a0cd9", "2a6937d721b14cdd",
            "32ded712566e352e", "566f532d486c9477", "59cc3cb8a4297301", "66a343906358730f", "6ac3c336e4094835",
            "85fc17f7069acd39", "8c5c04391361cbf4", "98d44e13f455d916", "9ae315a94e428a7e", "a60a52382d707771",
            "ab080737bac518b3", "ad505b0be8a49b89", "c2c3b68b48832afd", "d75a838dc758ba17", "e43b64ac515180de",
            "ebf1cce064da7d55", "fd70ad909b94deb2");
    static final int RUDE_MIN = 3, RUDE_MAX = 7;

    static boolean rude(String s) {
        for (int a = 0; a < s.length(); a++) {
            for (int b = a + RUDE_MIN; b <= Math.min(s.length(), a + RUDE_MAX); b++) {
                String sub = s.substring(a, b);
                if (sub.chars().allMatch(c -> c >= 'a' && c <= 'z') && RUDE.contains(fingerprint(sub))) return true;
            }
        }
        return false;
    }

    private static String fingerprint(String w) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(w.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String build(Rng r) {
        return switch (r.weighted(PATTERN_W)) {
            case 0 -> name(r) + (r.chance(0.5) ? sep(r) + initial(r) : "") + sep(r) + digits(r);
            case 1 -> fullName(r);
            case 2 -> r.pick(adj) + sep(r) + r.pick(noun) + (r.chance(0.75) ? sep(r) + digits(r) : "");
            case 3 -> r.pick(noun) + sep(r) + r.pick(noun) + (r.chance(0.6) ? digits(r) : "");
            case 4 -> ruWord(r);
            case 5 -> kinoWord(r);
            case 6 -> nameWord(r);
            case 7 -> r.pick(prefix) + "_" + anyWord(r) + (r.chance(0.9) ? sep(r) + digits(r) : "");
            default -> anyWord(r) + "_" + r.pick(city) + (r.chance(0.9) ? digits(r) : "");
        };
    }

    /** Имя со словом: {@code masha_owl}, {@code lazy_dima}, {@code kotik_lena}, {@code ivan_calm}. */
    private String nameWord(Rng r) {
        int k = r.nextInt(4);
        String base = k == 0 ? name(r) + sep(r) + r.pick(noun) : k == 1 ? r.pick(adj) + sep(r) + name(r)
                : k == 2 ? r.pick(ru) + sep(r) + name(r) : name(r) + "_" + r.pick(adj);
        return r.chance(0.65) ? base + sep(r) + digits(r) : base;
    }

    /** Буква фамилии латиницей (по весу фамилий): «k» у Кузнецова. */
    private String initial(Rng r) {
        return surnames.get(r.weighted(surnameW)).latin().get(0).substring(0, 1);
    }

    /** Имя, английское существительное или русское слово. */
    private String anyWord(Rng r) {
        int k = r.nextInt(3);
        return k == 0 ? name(r) : k == 1 ? r.pick(noun) : r.pick(ru);
    }

    private static String sep(Rng r) {
        return r.chance(0.5) ? "_" : "";
    }

    /** Имя латиницей: пол — поровну, имя — по весу, написание — любое. */
    private String name(Rng r) {
        Name n = r.chance(0.5) ? female.get(r.weighted(femaleW)) : male.get(r.weighted(maleW));
        return r.pick(n.latin());
    }

    /** Фамилия латиницей (женская форма — у -ov, -ev, -in, -yn с «a»). */
    private String surname(Rng r) {
        String s = surnames.get(r.weighted(surnameW)).latin().get(0);
        boolean fem = r.chance(0.5) && (s.endsWith("ov") || s.endsWith("ev") || s.endsWith("in") || s.endsWith("yn"));
        return fem ? s + "a" : s;
    }

    private String fullName(Rng r) {
        String n = name(r), s = surname(r);
        String base = switch (r.nextInt(6)) {
            case 0 -> n + "_" + s;
            case 1 -> s + "_" + n;
            case 2 -> n.charAt(0) + (r.chance(0.5) ? String.valueOf((char) ('a' + r.nextInt(26))) : "") + sep(r) + s;
            case 3 -> s + sep(r) + n.charAt(0) + (char) ('a' + r.nextInt(26));
            case 4 -> n + s;
            default -> s + sep(r) + digits(r);
        };
        return r.chance(0.5) && !Character.isDigit(base.charAt(base.length() - 1)) ? base + digits(r) : base;
    }

    private String ruWord(Rng r) {
        String w = r.pick(ru);
        return switch (r.weighted(new double[]{0.6, 0.2, 0.2})) {
            case 0 -> w + sep(r) + digits(r);
            case 1 -> w + sep(r) + r.pick(ru) + (r.chance(0.4) ? digits(r) : "");
            default -> r.pick(adj) + sep(r) + w + (r.chance(0.4) ? digits(r) : "");
        };
    }

    private String kinoWord(Rng r) {
        String k = r.pick(kino);
        int form = r.nextInt(4);
        String base = switch (form) {
            case 0 -> k + sep(r) + r.pick(fan);
            case 1 -> name(r) + "_" + k;
            case 2 -> k + sep(r) + name(r);
            default -> r.pick(adj) + sep(r) + k;
        };
        // кино-слов немного: «кино + фанат» и «имя + кино» без цифр почти всегда заняты
        return form < 3 || r.chance(0.8) ? base + sep(r) + digits(r) : base;
    }

    /** Цифры: год рождения (4 или 2 знака), число до 99, до 999, до 9999 или «красивое». */
    static String digits(Rng r) {
        return switch (r.weighted(new double[]{0.16, 0.16, 0.1, 0.22, 0.32, 0.04})) {
            case 0 -> Integer.toString(r.between(1955, 2010));
            case 1 -> {
                int y = r.between(1955, 2010) % 100;
                yield y < 10 ? "0" + y : Integer.toString(y);
            }
            case 2 -> Integer.toString(r.between(1, 99));
            case 3 -> Integer.toString(r.between(100, 999));
            case 4 -> Integer.toString(r.between(1000, 9999));
            default -> r.pick(new String[]{"777", "111", "123", "321", "2024", "2025", "2026", "13", "42", "007"});
        };
    }
}
