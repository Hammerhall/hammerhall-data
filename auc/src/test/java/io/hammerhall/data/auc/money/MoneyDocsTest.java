package io.hammerhall.data.auc.money;

import io.hammerhall.data.core.check.PublicNames;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Раздел SOURCE.md о деньгах: без табуляций, без внутренних имён платформы (core PublicNames), без долей заложенного. */
class MoneyDocsTest {
    @Test
    void sectionIsClean() {
        String s = MoneyDocs.SECTION;
        String low = s.toLowerCase(Locale.ROOT);
        assertFalse(PublicNames.isInternal(s) || PublicNames.isInternal(MoneyDocs.TITLE), "внутренние имена платформы");
        assertFalse(s.contains("\t"), "табуляция");
        assertFalse(low.contains("0,05") || low.contains("0,0005") || low.contains("60 %"), "доли заложенного — только словами");
        for (String t : MoneyTables.TABLES) assertTrue(s.contains("`" + t + "`"), "таблица " + t + " описана");
        assertFalse(MoneyDocs.TITLE.isBlank());
    }
}
