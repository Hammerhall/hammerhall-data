package io.hammerhall.data.core.check;

import io.hammerhall.data.core.io.GzipTsvFile;
import io.hammerhall.data.core.schema.ColType;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Отпечатки внутренних имён: проверка механизма на подставных словах — сами имена в тестах тоже не пишем. */
class PublicNamesTest {
    static final Set<String> LAT = Set.of(PublicNames.fingerprint("qwex"), PublicNames.fingerprint("zorbix"));
    static final Set<String> CYR = Set.of(PublicNames.fingerprint("пробник"));

    @Test
    void fingerprintIsShortSha256() {
        assertEquals("2cf24dba5fb0a30e", PublicNames.fingerprint("hello"));
        assertEquals(16, PublicNames.fingerprint("x").length());
    }

    @Test
    void latinIsFoundInsideWords() {
        assertTrue(PublicNames.contains("Phone Qwex 8", LAT, CYR));
        assertTrue(PublicNames.contains("ООО «Zorbixa Рус»", LAT, CYR));
        assertTrue(PublicNames.contains("aqwexb", LAT, CYR));
        assertFalse(PublicNames.contains("Qwe x", LAT, CYR));
    }

    @Test
    void cyrillicOnlyAsWholeWord() {
        assertTrue(PublicNames.contains("новый Пробник!", LAT, CYR));
        assertFalse(PublicNames.contains("пробники", LAT, CYR));
    }

    @Test
    void realFingerprintsAreWellFormed() {
        assertEquals(8, PublicNames.LATIN.size());
        assertEquals(11, PublicNames.CYRILLIC.size());
        assertFalse(PublicNames.isInternal("Воронеж, воронка, Forge, Flint, Kremen"));
    }

    /** Поиск по папке: разжатый .tsv.gz, тексты, имена файлов; target/ и work/ — мимо; число слов, а не сами слова. */
    @Test
    void scanReadsReleaseAndSourcesButSkipsBuildDirs(@TempDir Path dir) throws IOException {
        try (GzipTsvFile f = new GzipTsvFile(dir.resolve("brands.tsv.gz"), 6)) {
            f.line("id\tname");
            f.line("1\tVelmara");
            f.line("2\tZorbix Рус");
        }
        Files.writeString(dir.resolve("SOURCE.md"), "# Набор\nновый Пробник и обычные слова\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("clean.txt"), "Forge, Flint, Kremen", StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("target/qwex.txt"), "qwex", StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("src/work"));
        Files.writeString(dir.resolve("src/work/a.txt"), "qwex", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("src/qwex-notes.txt"), "имя файла тоже проверяется", StandardCharsets.UTF_8);
        PublicNames.Scan s = PublicNames.scan(dir, LAT, CYR);
        assertEquals(Map.of("SOURCE.md", 1, "brands.tsv.gz", 1, "src/qwex-notes.txt", 1), s.hits());
        assertEquals(4, s.files(), "target/ и work/ не читаются");
        assertFalse(s.clean());
        assertTrue(PublicNames.scan(dir.resolve("clean.txt"), LAT, CYR).clean());
    }

    /**
     * Поля «как есть» из внешнего источника: подставное слово в их значениях пропускается, а то же слово в
     * непомеченном поле, в заголовке файла (имя помеченного поля) и в SOURCE.md — находится. Без пометок — как раньше.
     */
    @Test
    void verbatimColumnsSkipOnlyTheirValues(@TempDir Path dir) throws IOException {
        Schema schema = new Schema("t", "t-data-v1").group("g", "Группа")
                .add(Table.of("places", "g", "Места").title("Места")
                        .col("code", ColType.TEXT, "Код").source("ext").verbatimSource()
                        .col("name", ColType.TEXT, "Название").source("ext").verbatimSource()
                        .col("note", ColType.TEXT, "Своё поле").pk("code"))
                .add(Table.of("labels", "g", "Подписи").title("Подписи")
                        .col("code", ColType.TEXT, "Код").source("ext").verbatimSource()
                        .col("qwex_name", ColType.TEXT, "Поле с подставным словом в имени").source("ext").verbatimSource()
                        .pk("code"));
        assertEquals(List.of(), schema.validate());
        Map<String, Set<String>> verbatim = PublicNames.verbatimColumns(schema);
        assertEquals(Map.of("places.tsv.gz", Set.of("code", "name"), "labels.tsv.gz", Set.of("code", "qwex_name")), verbatim);

        try (GzipTsvFile f = new GzipTsvFile(dir.resolve("places.tsv.gz"), 6)) {
            f.line("code\tname\tnote");
            f.line("A1\tQwex Field\tобычная заметка");
            f.line("ZORBIX\tНовый Пробник\tобычная заметка");
        }
        Files.writeString(dir.resolve("SOURCE.md"), "# Набор\nобычные слова\n", StandardCharsets.UTF_8);
        PublicNames.Scan s = PublicNames.scan(dir, verbatim, LAT, CYR);
        assertEquals(Map.of(), s.hits(), "слова в значениях помеченных полей — мимо");
        assertEquals(Map.of("places.tsv.gz", List.of("code", "name")), s.skipped());
        assertTrue(s.skippedSummary().contains("2 полей в 1 таблицах") && s.skippedSummary().contains("places.tsv.gz: code, name"),
                s.skippedSummary());
        assertEquals(Map.of("places.tsv.gz", 3), PublicNames.scan(dir, LAT, CYR).hits(), "без пометок — находятся");

        // то же слово в непомеченном поле
        try (GzipTsvFile f = new GzipTsvFile(dir.resolve("places.tsv.gz"), 6)) {
            f.line("code\tname\tnote");
            f.line("A1\tQwex Field\tзаметка про qwex");
        }
        assertEquals(Map.of("places.tsv.gz", 1), PublicNames.scan(dir, verbatim, LAT, CYR).hits(), "непомеченное поле");
        Files.delete(dir.resolve("places.tsv.gz"));

        // в заголовке (имя помеченного поля) и в SOURCE.md
        try (GzipTsvFile f = new GzipTsvFile(dir.resolve("labels.tsv.gz"), 6)) {
            f.line("code\tqwex_name");
            f.line("A1\tобычно");
        }
        Files.writeString(dir.resolve("SOURCE.md"), "# Набор\nQwex — и здесь\n", StandardCharsets.UTF_8);
        assertEquals(Map.of("labels.tsv.gz", 1, "SOURCE.md", 1), PublicNames.scan(dir, verbatim, LAT, CYR).hits(),
                "заголовок и тексты выпуска проверяются всегда");

        Schema bad = new Schema("t", "t-data-v1").group("g", "Группа")
                .add(Table.of("x", "g", "X").title("X").col("code", ColType.TEXT, "Код").verbatimSource().pk("code"));
        assertTrue(bad.validate().getFirst().contains("verbatimSource без source"), bad.validate().toString());
    }
}
