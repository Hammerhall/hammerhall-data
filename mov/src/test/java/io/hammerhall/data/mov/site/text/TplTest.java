package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.mov.site.text.Tpl.Slot;
import io.hammerhall.data.mov.site.text.Tpl.Slots;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Шаблоны: варианты, род жанра, поля и их маска, ошибки разметки; чистка текста. */
class TplTest {
    @Test
    @DisplayName("Варианты [а|б], вложенные и пустые; поля {X}; число раскрытий")
    void alternatives() {
        Tpl t = Tpl.parse("[Сюжет|История [простая|ясная]] {T}[|, увы].");
        assertEquals(Slot.T.bit(), t.need);
        assertEquals(6, t.variants());
        Slots s = new Slots();
        s.set(Slot.T, "«Икс»");
        Set<String> out = new HashSet<>();
        Rng r = new Rng(1);
        for (int i = 0; i < 500; i++) out.add(Tx.tidy(t.expand(r, s)));
        assertEquals(Set.of("Сюжет «Икс».", "Сюжет «Икс», увы.", "История простая «Икс».", "История простая «Икс», увы.",
                "История ясная «Икс».", "История ясная «Икс», увы."), out);
    }

    @Test
    @DisplayName("Род жанра <м|ж|с>: «хороший боевик», «хорошая комедия», «хорошее фэнтези»; шаблону нужен жанр")
    void gender() {
        Tpl t = Tpl.parse("<Хороший|Хорошая|Хорошее> {G}");
        assertEquals(Slot.G.bit(), t.need);
        Slots s = new Slots();
        assertFalse(s.fits(t.need));
        String[][] cases = {{"боевик", "0", "Хороший боевик"}, {"комедия", "1", "Хорошая комедия"}, {"фэнтези", "2", "Хорошее фэнтези"}};
        for (String[] c : cases) {
            s.set(Slot.G, c[0]);
            s.gender = Integer.parseInt(c[1]);
            assertTrue(s.fits(t.need));
            assertEquals(c[2], t.expand(new Rng(1), s));
        }
    }

    @Test
    @DisplayName("Ошибки разметки — с текстом шаблона: незакрытые скобки, неизвестное поле, не три формы рода")
    void errors() {
        for (String bad : new String[]{"[а|б", "а]", "{Z}", "{T", "<а|б>", "а|б", "<а|б|в"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Tpl.parse(bad), bad);
            assertTrue(e.getMessage().contains(bad), e.getMessage());
        }
    }

    @Test
    @DisplayName("Чистка: пробелы, знаки, заглавная, точка; слова; «баллы»; значения со стороны")
    void tidy() {
        assertEquals("Сюжет, увы, «X» — так.", Tx.sentence("  сюжет ,  увы ,, « X »  — так "));
        assertEquals("Финал.", Tx.sentence("финал:"));
        assertEquals("Это?", Tx.sentence("это?"));
        assertEquals(4, Tx.words("«Тихий берег» — 9 из"));
        assertEquals(3, Tx.words("Экшен-сцены, 18+ и"));
        assertEquals("1 балл 3 балла 8 баллов 11 баллов 21 балл", "1 " + Tx.plural(1, "балл", "балла", "баллов") + " 3 "
                + Tx.plural(3, "балл", "балла", "баллов") + " 8 " + Tx.plural(8, "балл", "балла", "баллов") + " 11 "
                + Tx.plural(11, "балл", "балла", "баллов") + " 21 " + Tx.plural(21, "балл", "балла", "баллов"));
        assertEquals("Анна Ланская", Tx.clean(" Анна\t Ланская\n", 60));
        assertEquals(null, Tx.clean("   ", 60));
        assertEquals(null, Tx.clean("x".repeat(61), 60));
        assertEquals("«Операция „Ы“»", Tx.quote("Операция «Ы»"));
        assertEquals("сюжет", Tx.uncap("Сюжет"));
        assertEquals("ТВ", Tx.uncap("ТВ"));
    }
}
