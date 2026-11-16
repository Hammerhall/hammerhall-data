package io.hammerhall.data.sto;

import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.gen.Expected;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.io.GzipTsvFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Файл ожиданий и проверка выпуска: {@code freeze} → {@code check} проходит; другие байты gzip того же содержимого —
 * проходит (справочная пометка); подменённое содержимое — нет; то же — у {@code tools/check_data.py}, если есть
 * Python. Плюс переносимость: чужие локаль и пояс JVM не меняют выпуск.
 */
class ExpectedTest {
    @TempDir
    static Path tmp;
    static Path base, expectedFile;
    static Map<String, Object> expected;

    static Release.Result run(Path out, String modules, String gzip) throws IOException {
        return Cli.run(StoReleaseTest.STO, Map.of("profile", "small", "out", out.toString(), "modules", modules, "gzip", gzip));
    }

    @BeforeAll
    static void build() throws IOException {
        DatasetContract.assumeInputs(StoReleaseTest.STO);
        base = tmp.resolve("base");
        assertTrue(run(base, "geo", "6").ok());
        expected = Expected.freeze(base, StoSchema.build(), StoReleaseTest.STO.aggregates(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        expectedFile = tmp.resolve("expected").resolve("sto-data-v1-small.json");
        Expected.write(expectedFile, expected);
    }

    static String check(Path dir, boolean[] ok) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ok[0] = Expected.check(dir, Expected.read(expectedFile), new PrintStream(bo, true, StandardCharsets.UTF_8), true);
        return bo.toString(StandardCharsets.UTF_8);
    }

    static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> s = Files.list(from)) {
            for (Path p : s.toList()) Files.copy(p, to.resolve(p.getFileName()));
        }
    }

    @Test
    void frozenReleasePasses() throws IOException {
        boolean[] ok = {false};
        String out = check(base, ok);
        assertTrue(ok[0], out);
        assertTrue(out.contains("ВЫПУСК В ПОРЯДКЕ"));
        assertFalse(out.contains("справочно: у"), "те же байты gzip — без пометки");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> aggs = (List<Map<String, Object>>) expected.get("aggregates");
        assertTrue(aggs.size() >= 10, "агрегаты ядра и волны 1: " + aggs.size());
        assertEquals(Expected.read(expectedFile), expected, "файл ожиданий читается обратно");
    }

    @Test
    void otherGzipBytesSameContentPass() throws IOException {
        Path other = tmp.resolve("gzip1");
        assertTrue(run(other, "geo", "1").ok());
        assertNotEquals(Files.readString(base.resolve("manifest.json")), Files.readString(other.resolve("manifest.json")),
                "другой уровень gzip — другие байты и манифест");
        boolean[] ok = {false};
        String out = check(other, ok);
        assertTrue(ok[0], out);
        assertTrue(out.contains("справочно: у"), out);
    }

    @Test
    void changedContentFails() throws IOException {
        Path bad = tmp.resolve("tampered");
        copy(base, bad);
        // одна буква в названии города: строки те же, sha256_tsv другой; манифест — прежний
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(base.resolve("cities.tsv.gz"))), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
        }
        lines.set(3, lines.get(3).replaceFirst("\t", "\tX"));
        Files.delete(bad.resolve("cities.tsv.gz"));
        try (GzipTsvFile f = new GzipTsvFile(bad.resolve("cities.tsv.gz"), 6)) {
            for (String l : lines) f.line(l);
        }
        boolean[] ok = {true};
        String out = check(bad, ok);
        assertFalse(ok[0], out);
        assertTrue(out.contains("НЕТ  cities.tsv.gz") && out.contains("sha256_tsv"), out);
        assertTrue(out.contains("ВЫПУСК НЕ СОВПАЛ"), out);
        python(bad, 1);
    }

    @Test
    void pythonCheckAgrees() throws IOException {
        python(base, 0);
    }

    /** {@code tools/check_data.py} (в корне репозитория) — тот же вердикт; нет Python — пропуск. */
    static void python(Path dir, int wantExit) throws IOException {
        Path root = Cli.dataDir(StoReleaseTest.STO).toAbsolutePath().normalize().getParent();
        Path script = root == null ? Path.of("tools", "check_data.py") : root.resolve("tools").resolve("check_data.py");
        assumeTrue(Files.exists(script), "нет " + script);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        List<List<String>> pythons = windows ? List.of(List.of("py", "-3"), List.of("python"), List.of("python3"))
                : List.of(List.of("python3"), List.of("python"));
        for (List<String> py : pythons) {
            List<String> cmd = new ArrayList<>(py);
            cmd.addAll(List.of("-X", "utf8", "-I", script.toString(), dir.toString(), "--expected", expectedFile.toString()));
            Process p;
            try {
                p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            } catch (IOException e) {
                continue;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code;
            try {
                code = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (!out.contains("проверка ")) continue;      // не тот интерпретатор (например, заглушка Microsoft Store)
            assertEquals(wantExit, code, out);
            assertTrue(out.contains(wantExit == 0 ? "ВЫПУСК В ПОРЯДКЕ" : "ВЫПУСК НЕ СОВПАЛ"), out);
            return;
        }
        assumeTrue(false, "нет Python — проверка скриптом пропущена");
    }

    @Test
    void foreignLocaleAndTimeZoneDoNotChangeTheRelease() throws IOException {
        Path a = tmp.resolve("loc-a"), b = tmp.resolve("loc-b");
        assertTrue(run(a, "catalog", "6").ok());
        Locale loc = Locale.getDefault();
        TimeZone tz = TimeZone.getDefault();
        try {
            // турецкая локаль ломает toLowerCase/format без Locale.ROOT; UTC+14 — всё, что зависит от пояса системы
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            assertTrue(run(b, "catalog", "6").ok());
        } finally {
            Locale.setDefault(loc);
            TimeZone.setDefault(tz);
        }
        assertArrayEquals(Files.readAllBytes(a.resolve("manifest.json")), Files.readAllBytes(b.resolve("manifest.json")));
        assertArrayEquals(Files.readAllBytes(a.resolve("SOURCE.md")), Files.readAllBytes(b.resolve("SOURCE.md")));
    }
}
