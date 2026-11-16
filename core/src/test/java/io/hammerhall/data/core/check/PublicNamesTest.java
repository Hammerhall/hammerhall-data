package io.hammerhall.data.core.check;

import io.hammerhall.data.core.io.GzipTsvFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
