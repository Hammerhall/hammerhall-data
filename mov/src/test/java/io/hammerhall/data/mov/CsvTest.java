package io.hammerhall.data.mov;

import io.hammerhall.data.mov.source.Csv;
import io.hammerhall.data.mov.source.SourceData;
import io.hammerhall.data.mov.source.Wikidata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Чтение снимка: CSV (RFC 4180, как пишет модуль csv Python), номера элементов, даты P577, годность текста. */
class CsvTest {
    @Test
    @DisplayName("CSV: поля в кавычках — с запятыми, кавычками и переводами строк; пустые поля; CRLF")
    void quotedFields() throws IOException {
        String text = "a,b,c\n1,\"x, y\",\"он сказал \"\"да\"\"\"\r\n2,,\"две\nстроки\"\n\n3,\"\",z";
        assertEquals(List.of(List.of("a", "b", "c"), List.of("1", "x, y", "он сказал \"да\""), List.of("2", "", "две\nстроки"),
                List.of("3", "", "z")), Csv.parse(text));
    }

    @Test
    @DisplayName("CSV: запись на стыке буфера чтения не рвётся; после закрывающей кавычки — только запятая или конец")
    void bufferBoundary() throws IOException {
        StringBuilder sb = new StringBuilder("k,v\n");
        for (int i = 0; i < 20_000; i++) sb.append(i).append(",\"x,").append(i).append("\"\r\n");
        Csv.Table t = new Csv.Table(new Csv(new StringReader(sb.toString()), "big.csv"), List.of("k", "v"));
        int n = 0;
        for (List<String> r; (r = t.next()) != null; n++) assertEquals("x," + n, t.get(r, "v"));
        assertEquals(20_000, n);
        IOException e = assertThrows(IOException.class, () -> Csv.parse("a,b\n\"x\"y,z\n"));
        assertEquals("csv:2: после закрывающей кавычки — «y»", e.getMessage());
        assertThrows(IOException.class, () -> Csv.parse("a,\"незакрытая\n"));
    }

    @Test
    @DisplayName("Номер элемента: Q42 и ссылка; не элемент и номер вне int — ошибка, qOrZero — 0")
    void items() {
        assertEquals(42, Wikidata.q("Q42"));
        assertEquals(42, Wikidata.q("http://www.wikidata.org/entity/Q42"));
        assertThrows(IllegalArgumentException.class, () -> Wikidata.q("P31"));
        assertThrows(IllegalArgumentException.class, () -> Wikidata.q("Q12x"));
        assertThrows(IllegalArgumentException.class, () -> Wikidata.q("Q3000000000"));
        assertEquals(0, Wikidata.qOrZero(""));
        assertEquals(0, Wikidata.qOrZero("_:t123"));
    }

    @Test
    @DisplayName("Дата P577: запись сервиса, до н. э. — с минусом; ранг preferred/normal; непонятная запись — null")
    void dates() {
        SourceData.DateStmt d = SourceData.date("1999-03-31T00:00:00Z", "11", "preferred");
        assertEquals(new SourceData.DateStmt(1999, 3, 31, 11, true), d);
        assertEquals(new SourceData.DateStmt(-500, 1, 1, 9, false), SourceData.date("-0500-01-01T00:00:00Z", "9", "normal"));
        assertEquals(12345, SourceData.date("+12345-01-01T00:00:00Z", "9", "normal").year());
        assertNull(SourceData.date("1999", "9", "normal"));
        assertNull(SourceData.date("1999-03-31T00:00:00Z", "x", "normal"));
        assertNull(SourceData.date("1999-03-31T00:00:00Z", "11", "deprecated"));
    }

    @Test
    @DisplayName("Текст для TSV: обратная косая, табуляция, перевод строки, управляющие — негодный, как отсутствующий")
    void usable() {
        assertTrue(Wikidata.usable("Amélie «Ö» 東京"));
        for (String s : new String[]{"", "a\\b", "a\tb", "a\nb", "a\rb", "a\u0007b", "a\u007fb"}) assertFalse(Wikidata.usable(s), s);
        assertFalse(Wikidata.usable(null));
    }
}
