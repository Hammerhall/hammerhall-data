package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Логины и отображаемые имена: формат, разброс (200 тыс. логинов), пол имени, ники, запреты, детерминизм. */
class PeopleTextsTest {
    static final long SEED = 20251001L;
    static final SiteTextsRu TEXTS = new SiteTextsRu();
    static final Pattern NAME = Pattern.compile("[А-ЯЁ][а-яё]+ [А-ЯЁ]\\.");

    @Test
    @DisplayName("200 тыс. логинов: латиница, цифры и «_», 4–20 знаков; дубликатов мало, узоры разные; без запретов")
    void logins() {
        RandomStreams.Stream st = new RandomStreams(SEED).stream("users");
        int n = 200_000;
        Set<String> seen = new HashSet<>(n * 2);
        Map<String, String> bad = new HashMap<>();
        int dup = 0, digits = 0, under = 0;
        long len = 0;
        for (int i = 0; i < n; i++) {
            String s = TEXTS.login(st.at(i));
            if (!People.valid(s) || s.length() < 4 || s.length() > 20) bad.putIfAbsent("формат", s);
            String why = Forbidden.check(s);
            if (why != null) bad.putIfAbsent(why, "логин из " + s.length() + " знаков");
            if (!seen.add(s)) dup++;
            if (s.chars().anyMatch(Character::isDigit)) digits++;
            if (s.indexOf('_') >= 0) under++;
            len += s.length();
        }
        assertEquals(Map.of(), bad);
        System.out.printf(Locale.ROOT, "логинов: %d, дубликатов %d (%.2f %%), с цифрами %.0f %%, с «_» %.0f %%, длина в среднем %.1f%n",
                n, dup, 100.0 * dup / n, 100.0 * digits / n, 100.0 * under / n, (double) len / n);
        assertTrue(dup < n * 0.03, "дубликатов " + dup);
        assertTrue(digits > n * 0.3 && digits < n * 0.8, "с цифрами " + digits);
        assertTrue(under > n * 0.25 && under < n * 0.85, "с «_» " + under);
    }

    @Test
    @DisplayName("Отображаемое имя: «Имя Ф.» по полу, иногда ник; пол — 'M'/'F' (и строчные), иначе — ошибка")
    void displayNames() {
        RandomStreams.Stream st = new RandomStreams(SEED).stream("names");
        People p = TEXTS.people();
        Set<String> male = new HashSet<>(), female = new HashSet<>();
        for (People.Name x : p.male) male.add(x.ru());
        for (People.Name x : p.female) female.add(x.ru());
        Set<String> nicksM = new HashSet<>(p.nickAny), nicksF = new HashSet<>(p.nickAny);
        nicksM.addAll(p.nickM);
        nicksF.addAll(p.nickF);
        int n = 100_000, nick = 0;
        Set<String> distinct = new HashSet<>();
        Map<Character, Integer> initials = new HashMap<>();
        for (int i = 0; i < n; i++) {
            char g = i % 2 == 0 ? 'M' : 'F';
            String s = TEXTS.displayName(st.at(i), g);
            distinct.add(s);
            if (NAME.matcher(s).matches()) {
                String first = s.substring(0, s.indexOf(' '));
                assertTrue(g == 'M' ? male.contains(first) : female.contains(first), g + ": " + s);
                initials.merge(s.charAt(s.length() - 2), 1, Integer::sum);
            } else {
                nick++;
                assertTrue(g == 'M' ? nicksM.contains(s) : nicksF.contains(s), "ник не из списка (" + g + "): " + s);
            }
            assertTrue(Forbidden.check(s) == null, s);
        }
        System.out.printf(Locale.ROOT, "отображаемых имён: %d разных из %d, ников %.1f %%, первых букв фамилии %d%n",
                distinct.size(), n, 100.0 * nick / n, initials.size());
        assertTrue(nick > n * 0.08 && nick < n * 0.16, "ников " + nick);
        assertTrue(initials.size() >= 20, "букв фамилий " + initials.size());
        assertTrue(distinct.size() > 1500, "разных имён " + distinct.size());
        assertTrue(NAME.matcher(TEXTS.displayName(new Rng(3), 'f')).matches() || nicksF.contains(TEXTS.displayName(new Rng(3), 'f')));
        assertThrows(IllegalArgumentException.class, () -> TEXTS.displayName(new Rng(1), 'X'));
        assertThrows(IllegalArgumentException.class, () -> TEXTS.displayName(new Rng(1), ' '));
    }

    @Test
    @DisplayName("Словари имён: без повторов; латиница имён — строчная, без запретов")
    void nameVocab() {
        People p = TEXTS.people();
        List<String> all = new ArrayList<>();
        for (List<People.Name> list : List.of(p.male, p.female, p.surnames)) {
            Set<String> ru = new HashSet<>();
            for (People.Name x : list) {
                assertTrue(ru.add(x.ru()), "дважды: " + x.ru());
                assertTrue(x.weight() > 0, x.ru());
                for (String l : x.latin()) assertTrue(l.matches("[a-z]+"), x.ru() + ": " + l);
                all.add(x.ru());
                all.addAll(x.latin());
            }
        }
        for (List<String> list : List.of(p.adj, p.noun, p.ru, p.kino, p.fan, p.prefix, p.city)) {
            for (String w : list) {
                assertTrue(w.matches("[a-z]+"), w);
                all.add(w);
            }
        }
        all.addAll(p.nickAny);
        all.addAll(p.nickM);
        all.addAll(p.nickF);
        for (String s : all) assertTrue(Forbidden.check(s) == null, "запрет: слово из " + s.length() + " знаков");
        assertTrue(p.male.size() >= 50 && p.female.size() >= 50 && p.surnames.size() >= 150, "словари имён");
    }

    @Test
    @DisplayName("Детерминизм: тот же Rng — тот же логин и имя")
    void deterministic() {
        RandomStreams.Stream a = new RandomStreams(SEED).stream("users"), b = new RandomStreams(SEED).stream("users");
        SiteTextsRu again = new SiteTextsRu();
        for (int i = 999; i >= 0; i--) {
            Rng x = a.at(i), y = b.at(i);
            assertEquals(TEXTS.login(x), again.login(y));
            assertEquals(TEXTS.displayName(x, 'F'), again.displayName(y, 'F'));
        }
    }
}
