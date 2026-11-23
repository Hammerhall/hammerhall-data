package io.hammerhall.data.wld;

import io.hammerhall.data.wld.source.Csv;
import io.hammerhall.data.wld.source.Wikidata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Чтение CSV Wikidata (RFC 4180, как пишет модуль csv Python). */
class CsvTest {
    @Test
    @DisplayName("CSV: поля в кавычках — с запятыми, кавычками и переводами строк; пустые поля; CRLF")
    void quotedFields() throws IOException {
        String text = "a,b,c\n1,\"x, y\",\"он сказал \"\"да\"\"\"\r\n2,,\"две\nстроки\"\n\n3,\"\",z";
        assertEquals(List.of(List.of("a", "b", "c"), List.of("1", "x, y", "он сказал \"да\""), List.of("2", "", "две\nстроки"),
                List.of("3", "", "z")), Csv.parse(text));
    }

    @Test
    @DisplayName("CSV: кавычка внутри поля без кавычек — как есть; пустые строки — не записи")
    void bareQuoteAndBlankLines() throws IOException {
        assertEquals(List.of(List.of("a\"b", "c"), List.of("d", "")), Csv.parse("\n\na\"b,c\n\nd,\n"));
    }

    @Test
    @DisplayName("Wikidata: метка с обратной косой или управляющим знаком — как отсутствующая, берётся следующий элемент")
    void unusableLabels() throws IOException {
        String text = "geonameid,item,label_ru\n1,Q5,\"Альфа\\\\\"\n1,Q9,Альфа\n2,Q3,\"Бета\tБета\"\n3,Q4,Гамма\n";
        Map<Long, Wikidata.Label> m = Wikidata.citiesRu(new StringReader(text), "cities_ru.csv", new long[1]);
        assertEquals("Альфа", m.get(1L).label());
        assertEquals(9L, m.get(1L).q());
        assertEquals(null, m.get(2L));
        assertEquals("Гамма", m.get(3L).label());
    }

    @Test
    @DisplayName("CSV: после закрывающей кавычки — только запятая или конец записи")
    void junkAfterQuote() {
        IOException e = assertThrows(IOException.class, () -> Csv.parse("a,b\n\"x\"y,z\n"));
        assertEquals("csv:2: после закрывающей кавычки — «y»", e.getMessage());
        assertThrows(IOException.class, () -> Csv.parse("a,\"незакрытая\n"));
    }
}
