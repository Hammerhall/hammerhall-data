package io.hammerhall.data.mov;

import io.hammerhall.data.mov.build.Names;
import io.hammerhall.data.mov.build.Names.GenderPath;
import io.hammerhall.data.mov.build.Names.Split;
import io.hammerhall.data.mov.source.SourceData.NameRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Люди: полное имя, имя и фамилия, пол актёра. */
class NamesTest {
    @Test
    @DisplayName("Полное имя: en → mul → первый запасной язык после ru; ru не берётся; уточнение в скобках — прочь")
    void fullName() {
        assertEquals("Ann Lee", Names.fullName(RulesTest.labels("en", "Ann Lee", "mul", "M")));
        assertEquals("M", Names.fullName(RulesTest.labels("mul", "M", "de", "D")));
        assertEquals("Hans Müller", Names.fullName(RulesTest.labels("ru", "Ганс", "fr", "Jean", "de", "Hans Müller")));
        assertNull(Names.fullName(RulesTest.labels("ru", "Только по-русски")));
        assertNull(Names.fullName(null));
        assertEquals("John Smith", Names.fullName(RulesTest.labels("en", "John Smith (actor)")));
        assertEquals("Somebody", Names.fullName(RulesTest.labels("en", "(unnamed)", "mul", "Somebody")),
                "от метки ничего не осталось — следующая");
    }

    @Test
    @DisplayName("Уточнение в скобках: только в конце, со вложенными скобками; середина — как есть")
    void qualifier() {
        assertEquals("John Smith", Names.stripQualifier("John Smith (actor)"));
        assertEquals("A (b (c))", Names.stripQualifier("A (b (c))"), "вложенные скобки — не уточнение");
        assertEquals("A (b) c", Names.stripQualifier("A (b) c"));
        assertEquals("A", Names.stripQualifier("A (x) "));
        assertEquals("A (x)", Names.stripQualifier("A (x) (y)"), "убирается одно уточнение");
        assertEquals("A)", Names.stripQualifier("A)"), "нет открывающей — как есть");
        assertEquals("", Names.stripQualifier("(unnamed)"));
        assertEquals("Ann", Names.fullName(RulesTest.labels("en", "Ann\u00a0(actress)\u00a0")), "неразрывный пробел — тоже пробел");
    }

    @Test
    @DisplayName("Фамилия P734: имя равно ей, оканчивается на «␣F» или начинается с «F␣» — фамилия она; иначе эвристика")
    void splitByFamily() {
        assertEquals(new Split("Steven", "Halden"), Names.split("Steven Halden", "Halden"));
        assertEquals(new Split("Samuel L.", "Jackson"), Names.split("Samuel L. Jackson", "Jackson"));
        assertEquals(new Split("Wei", "Zhang"), Names.split("Zhang Wei", "Zhang"), "порядок «фамилия имя»");
        assertEquals(new Split(null, "Zelda"), Names.split("Zelda", "Zelda"));
        assertEquals(new Split("Leeann", "Smith"), Names.split("Leeann Smith", "Lee"), "Lee — не слово в имени: эвристика");
        assertEquals(new Split("Jean-Luc", "Godard"), Names.split("Jean-Luc Godard", "Godard"));
        assertEquals(new Split("Anna", "Maria-Smith"), Names.split("Anna Maria-Smith", "Smith"), "дефис — не граница слова");
        assertEquals(new Split("Robert", "Halden Jr."), Names.split("Robert Halden Jr.", "Halden"),
                "не оканчивается на фамилию (суффикс) — эвристика");
    }

    @Test
    @DisplayName("Эвристика: последнее слово с частицами (с учётом регистра) перед ним и суффиксом после; одно слово — имени нет")
    void heuristic() {
        assertEquals(new Split("Pieter", "van Dalen"), Names.heuristic("Pieter van Dalen"));
        assertEquals(new Split("Maria", "de la Vega"), Names.heuristic("Maria de la Vega"));
        assertEquals(new Split("Lee Van", "Cleef"), Names.heuristic("Lee Van Cleef"), "Van — с заглавной: не частица");
        assertEquals(new Split("Robert", "Halden Jr."), Names.heuristic("Robert Halden Jr."));
        assertEquals(new Split("John Paul", "Grey III"), Names.heuristic("John Paul Grey III"));
        assertEquals(new Split("Al", "Corvo"), Names.heuristic("Al Corvo"));
        assertEquals(new Split(null, "van Gogh"), Names.heuristic("van Gogh"), "частицы — все подряд, имени может не остаться");
        assertEquals(new Split(null, "Zelda"), Names.heuristic("Zelda"));
        assertEquals(new Split(null, "Prince II"), Names.heuristic("Prince II"), "слово и суффикс — фамилия целиком");
        assertEquals(new Split("Ana María", "Torres"), Names.heuristic(" Ana  María Torres "), "слова — через один пробел");
        assertEquals(new Split("Martin Luther", "King, Jr."), Names.heuristic("Martin Luther King, Jr."));
    }

    @Test
    @DisplayName("Первая P734/P735: по P1545 — числа, затем прочее, затем пусто; затем по номеру элемента")
    void firstByOrdinal() {
        assertEquals(804, Names.first(List.of(new NameRef(805, "2"), new NameRef(804, "1"))).item());
        assertEquals(805, Names.first(List.of(new NameRef(804, ""), new NameRef(805, "10"))).item());
        assertEquals(7, Names.first(List.of(new NameRef(9, ""), new NameRef(3, ""), new NameRef(7, "x"))).item(),
                "не число раньше пустого");
        assertEquals(3, Names.first(List.of(new NameRef(9, ""), new NameRef(3, ""))).item());
        assertEquals(5, Names.first(List.of(new NameRef(5, "b"), new NameRef(4, "c"))).item());
        assertEquals(6, Names.first(List.of(new NameRef(5, "a"), new NameRef(6, "99999999999999999999"))).item(), "длинное число");
        assertEquals(2, Names.first(List.of(new NameRef(9, "10"), new NameRef(2, "2"))).item(), "10 — число, не строка");
        assertNull(Names.first(List.of()));
    }

    @Test
    @DisplayName("Пол: P21 (четыре значения) → M/F; иное P21 — не берётся; нет P21 — по имени; иначе — от seed")
    void gender() {
        assertEquals(new Names.Gender("M", GenderPath.P21), Names.gender(6581097, "female"), "P21 раньше имени");
        assertEquals(new Names.Gender("M", GenderPath.P21), Names.gender(2449503, null));
        assertEquals(new Names.Gender("F", GenderPath.P21), Names.gender(6581072, null));
        assertEquals(new Names.Gender("F", GenderPath.P21), Names.gender(1052281, "male"));
        assertEquals(new Names.Gender(null, GenderPath.NOT_TAKEN), Names.gender(48270, "male"), "иное значение — имя не спасает");
        assertEquals(new Names.Gender("M", GenderPath.GIVEN_NAME), Names.gender(0, "male"));
        assertEquals(new Names.Gender("F", GenderPath.GIVEN_NAME), Names.gender(0, "female"));
        for (String k : new String[]{"unisex", "family", "other", null}) {
            assertEquals(new Names.Gender(null, GenderPath.SEEDED), Names.gender(0, k), String.valueOf(k));
        }
    }

    @Test
    @DisplayName("Пол от seed: F, если доля потока под id меньше доли F среди актёров full с полом из источника")
    void seeded() {
        assertEquals("F", Names.seeded(0.10, 0.37));
        assertEquals("M", Names.seeded(0.37, 0.37));
        assertEquals("M", Names.seeded(0.99, 0.37));
        assertEquals("M", Names.seeded(0.0, 0.0));
        assertEquals("F", Names.seeded(0.999, 1.0));
    }
}
