package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.check.PublicNames;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Справочник мастеров: сколько, без повторов и без настоящих имён, годы правдоподобны, детерминизм; словари имён
 * чисты; склонение имён — по правилам.
 */
class MakersTest {
    static final long SEED = 20251001L;
    static final Makers MAKERS = Makers.build(SEED);

    @Test
    void countsIdsAndUniqueNames() {
        int want = 0;
        for (MakerKind k : MakerKind.values()) {
            want += k.count;
            assertEquals(k.count, MAKERS.of(k).size(), "мастеров вида " + k);
        }
        assertEquals(want, MAKERS.all().size());
        Set<String> names = new HashSet<>();
        for (int i = 0; i < MAKERS.all().size(); i++) {
            Maker m = MAKERS.all().get(i);
            assertEquals(i + 1, m.id(), "id по порядку");
            assertTrue(names.add(m.name()), "повтор имени мастера: " + m.name());
        }
        System.out.println("мастеров: " + want + " (" + MAKERS.of(MakerKind.ARTIST).size() + " художников)");
    }

    @Test
    void sameSeedSameTable() {
        Makers again = Makers.build(SEED);
        List<Object[]> a = MAKERS.table(), b = again.table();
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) assertTrue(Arrays.equals(a.get(i), b.get(i)), "строка " + (i + 1));
        Makers other = Makers.build(SEED + 1);
        int same = 0;
        for (int i = 0; i < a.size(); i++) if (a.get(i)[4].equals(other.table().get(i)[4])) same++;
        assertTrue(same < a.size() / 10, "другой seed — другие мастера: совпало " + same);
        assertNotEquals(MAKERS.byId(1).name(), other.byId(1).name());
    }

    @Test
    void noRealOrInternalNames() {
        List<String> bad = new ArrayList<>();
        for (Maker m : MAKERS.all()) {
            if (PublicNames.isInternal(m.name()) || PublicNames.isInternal(W.latin(m.name()))) bad.add("внутреннее: " + m.id());
            if (m.isPerson() && RealNames.blocked(m.person().last())) bad.add(m.name());
            if (!m.isPerson() && RealNames.blocked(m.name())) bad.add(m.name());
            if (m.mark() == null || m.mark().isBlank()) bad.add("без клейма: " + m.name());
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void dictionariesAreClean() {
        List<String> bad = new ArrayList<>();
        for (Nation n : Nation.values()) {
            Set<String> seen = new HashSet<>();
            for (Nation.Surname sur : n.surnames) {
                String s = sur.ru();
                if (sur.en() == null || sur.en().isBlank() || !sur.en().matches("[\\p{IsLatin}' -]+")) bad.add(n + ": нет латиницы у " + s);
                if (!seen.add(s)) bad.add(n + ": повтор " + s);
                if (RealNames.blocked(s)) bad.add(n + ": настоящая фамилия " + s);
                if (n != Nation.RU && n != Nation.PL && s.endsWith("а")) bad.add(n + ": на -а не склоняется правилами " + s);
                if (PublicNames.isInternal(s) || PublicNames.isInternal(W.latin(s))) bad.add(n + ": внутреннее имя");
            }
            for (Nation.Male m : n.male) if (PublicNames.isInternal(m.name())) bad.add(n + ": внутреннее имя");
            for (Nation.Female f : n.female) if (PublicNames.isInternal(f.name())) bad.add(n + ": внутреннее имя");
            assertTrue(n.cities.length > 0, n + ": нет городов");
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void englishNamesRowsTiersBios() {
        Set<String> en = new HashSet<>();
        List<String> bad = new ArrayList<>();
        int[] tiers = new int[4];
        for (Maker m : MAKERS.all()) {
            if (m.nameEn() == null || m.nameEn().isBlank() || m.nameEn().matches(".*\\p{IsCyrillic}.*")) bad.add("английское имя: " + m.name() + " — " + m.nameEn());
            if (!en.add(m.nameEn())) bad.add("повтор английского имени: " + m.nameEn());
            if (m.name().length() > 80 || m.nameEn().length() > 80) bad.add("длиннее 80: " + m.name());
            Object[] row = Makers.row(m);
            if (row.length != Makers.COLUMNS.size()) bad.add("ширина строки " + row.length);
            for (int i : new int[]{0, 1, 2, 3, 4, 5, 9, 11, 16}) if (row[i] == null) bad.add("пусто " + Makers.COLUMNS.get(i) + ": " + m.name());
            if (m.isPerson() && (row[6] == null || row[8] == null)) bad.add("имя и фамилия у человека: " + m.name());
            if (!m.isPerson() && row[6] != null) bad.add("имя у мастерской: " + m.name());
            tiers[m.tier().charAt(0) - 'A']++;
            boolean needBio = m.tier().equals("A") || m.tier().equals("B");
            if (needBio != (m.bioRu() != null)) bad.add("справка и уровень: " + m.tier() + " " + m.name());
            if (m.bioRu() != null && (m.bioRu().length() < 120 || m.bioRu().length() > 400)) bad.add("справка " + m.bioRu().length() + ": " + m.bioRu());
            if (bad.size() > 20) break;
        }
        assertEquals(List.of(), bad);
        int n = MAKERS.all().size();
        System.out.printf(java.util.Locale.ROOT, "уровни: A %d, B %d, C %d, D %d%n", tiers[0], tiers[1], tiers[2], tiers[3]);
        assertTrue(tiers[0] > 0.02 * n && tiers[0] < 0.04 * n, "уровень A — около 3 %: " + tiers[0]);
    }

    /** Направление художника согласно специализации и годам: абстракционист — абстрактного направления, с 1910-х. */
    @Test
    void schoolsAgreeWithSpecialtyAndYears() {
        List<String> bad = new ArrayList<>();
        for (Maker m : MAKERS.all()) {
            if (m.school() == null) continue;
            Makers.Movement mv = Makers.movement(m.school());
            if (mv == null) {
                bad.add("нет направления " + m.school());
                continue;
            }
            int start = m.yearFrom() + 30;
            if (start < mv.from() || start > mv.to()) bad.add(m.school() + " " + mv.from() + "–" + mv.to() + " у родившегося в " + m.yearFrom());
            boolean abs = Makers.abstractArtist(m);
            if (abs && mv.style().equals("fig")) bad.add("абстракционист — " + m.school());
            if (!abs && mv.style().equals("abs")) bad.add(m.specialty() + " — " + m.school());
            if (abs && m.activeFrom() < 1910) bad.add("абстракционист с " + m.activeFrom());
            if (m.bioRu() != null && m.bioRu().contains("абстрактн") != abs) bad.add("справка: " + m.bioRu());
        }
        assertEquals(List.of(), bad);
    }

    /** Торговые дома, аукционы, галереи и лавки — не созвучны известным настоящим. */
    @Test
    void venuesAndFirmsDoNotResembleRealTrade() {
        assertTrue(RealNames.resemblesTrade("«Розенбах и сыновья»"));
        assertTrue(RealNames.resemblesTrade("Rosenbach & Sons"));
        assertTrue(RealNames.resemblesTrade("Галерея «Вильденштайн»"));
        assertTrue(RealNames.resemblesTrade("Аукционный дом «Лемпертц»"));
        assertTrue(!RealNames.resemblesTrade("«Сухотин и сыновья»"));
        List<String> bad = new ArrayList<>();
        Common.Venues v = new LotTexts(MAKERS, new io.hammerhall.data.core.random.RandomStreams(SEED)).venues;
        for (Common.Venue[] vs : List.of(v.houses, v.galleries, v.shops)) {
            for (Common.Venue x : vs) if (RealNames.resemblesTrade(x.nom())) bad.add(x.nom());
        }
        for (Maker m : MAKERS.all()) {
            if (!m.isPerson() && (RealNames.resemblesTrade(m.name()) || RealNames.resemblesTrade(m.nameEn()))) bad.add(m.name());
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void yearsArePlausible() {
        List<String> bad = new ArrayList<>();
        for (Maker m : MAKERS.all()) {
            if (m.yearFrom() < 1690 || m.yearFrom() > Makers.NOW_YEAR) bad.add(m.name() + " " + m.yearFrom());
            if (m.yearTo() != null && m.yearTo() < m.yearFrom()) bad.add(m.name() + " " + m.years());
            if (m.isPerson() && m.yearTo() != null && (m.yearTo() - m.yearFrom() < 28 || m.yearTo() - m.yearFrom() > 98)) {
                bad.add(m.name() + " " + m.years());
            }
            if (m.isPerson() && m.yearTo() == null && Makers.NOW_YEAR - m.yearFrom() > 98) bad.add(m.name() + " жив в " + m.years());
            if (m.kind() == MakerKind.ARTIST && m.school() == null) bad.add(m.name() + ": нет направления");
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void namesDecline() {
        assertEquals("Сухотина", Ru.genSurname("Сухотин", false));
        assertEquals("Сухотиной", Ru.genSurname("Сухотина", true));
        assertEquals("Залесского", Ru.genSurname("Залесский", false));
        assertEquals("Залесской", Ru.genSurname("Залесская", true));
        assertEquals("Седых", Ru.genSurname("Седых", false));
        assertEquals("ван Беека", Ru.genSurname("ван Беек", false));
        assertEquals("Бельфонтен", Ru.genSurname("Бельфонтен", true));
        assertEquals("Новаковой", Ru.genSurname(Ru.feminineCzech("Новак"), true));
        assertEquals("Петра", Ru.genMaleFirst("Пётр"));
        assertEquals("Павла", Ru.genMaleFirst("Павел"));
        assertEquals("Льва", Ru.genMaleFirst("Лев"));
        assertEquals("Ильи", Ru.genMaleFirst("Илья"));
        assertEquals("Саввы", Ru.genMaleFirst("Савва"));
        assertEquals("Андрея", Ru.genMaleFirst("Андрей"));
        assertEquals("Игоря", Ru.genMaleFirst("Игорь"));
        assertEquals("Франсуа", Ru.genMaleFirst("Франсуа"));
        assertEquals("Луи", Ru.genMaleFirst("Луи"));
        assertEquals("Жана", Ru.genMaleFirst("Жан"));
        assertEquals("Анны", Ru.genFemaleFirst("Анна"));
        assertEquals("Ольги", Ru.genFemaleFirst("Ольга"));
        assertEquals("Марии", Ru.genFemaleFirst("Мария"));
        assertEquals("Любови", Ru.genFemaleFirst("Любовь"));
        assertEquals("Мари", Ru.genFemaleFirst("Мари"));
        assertEquals("Петровича", Ru.genPatronymic("Петрович"));
        assertEquals("Петровны", Ru.genPatronymic("Петровна"));
        assertEquals("Сухотиных", Ru.pluralGenSurname("Сухотин"));
        assertEquals("Залесских", Ru.pluralGenSurname("Залесский"));
        assertEquals("Сухотины", Ru.pluralSurname("Сухотин"));
        Person p = new Person("Анна", "Петровна", "Сухотина", "Сухотин", true, Country.RU, "Anna", "Sukhotina", "Sukhotin");
        assertEquals("Анны Петровны Сухотиной", p.fullGen());
        assertEquals("Сухотина А. П.", p.biblio());
        assertEquals("Sukhotina, A. P.", p.biblioEn());
        assertEquals("Anna Petrovna Sukhotina", p.fullEn());
        assertEquals("А. П. Сухотиной", p.initialsGen());
        assertEquals("Sukhotin", Translit.ru("Сухотин"));
        assertEquals("Yerokhina", Translit.ru("Ерохина"));
        assertEquals("Zalesskaya", Translit.ru("Залесская"));
        assertEquals("Vasily", Translit.ru("Василий"));
        assertEquals("Shchukin", Translit.ru("Щукин"));
        assertEquals("со стогами", W.s("стогами"));
        assertEquals("с сапфиром", W.s("сапфиром"));
    }

    @Test
    void brandsAreRecognised() {
        // придуманные из слогов названия сверяются с марками: совпадение и одна правка — отказ
        assertTrue(RealNames.resemblesBrand("Ролекс"));
        assertTrue(RealNames.resemblesBrand("Ролекc"));
        assertTrue(RealNames.resemblesBrand("Мейсен"));
        assertTrue(RealNames.blocked("Айвазовский"));
        assertTrue(RealNames.blocked("Серебрякова"));
        assertTrue(!RealNames.blocked("Сухотин"));
    }
}
