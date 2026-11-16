package io.hammerhall.data.core.check;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Публичные имена не пересекаются с внутренними именами платформы: в данных выпусков,
 * названиях и текстах не должно быть имён её внутренних частей. Сами имена здесь не записаны — только отпечатки
 * (первые 16 знаков sha256 от слова в нижнем регистре), чтобы открытый код генераторов их не раскрывал.
 *
 * <p>Латиница — ищется и внутри слова (выдуманное «Xyzname» тоже отсекается); кириллица — только слово целиком, чтобы
 * не задеть обычные слова, которые начинаются так же.
 *
 * <p>{@link #scan} — проверка папки целиком (выпуск любого набора, исходники, репозиторий): команда {@code names} у
 * {@code Cli} и общие тесты набора ({@code DatasetContract}).
 */
public final class PublicNames {
    private PublicNames() {
    }

    static final Set<String> LATIN = Set.of("982945308d3682d1", "50bc66d8aae5ae90", "fd136cd6601aef10",
            "092cda390b474161", "8cfde6efdfc4ed5a", "0dbe5eed7bf15a42", "77f1d1c04d32a84a", "a6821122b64a72ba");
    static final int MIN_LATIN = 4;
    static final int MAX_LATIN = 8;
    static final Set<String> CYRILLIC = Set.of("c037f15add3d1757", "0bad1136a07245ad", "c12fdda346bf0a11",
            "4aa04a3894d5c154", "74f67b3a9663985a", "ae514376b4b4e65a", "33ec610cf8e982ea", "f1bba88bef55f16f",
            "45bc527702c7eb85", "b2d6128df55b4571", "80ef7bcba2d74f24");

    /** Есть ли в тексте внутреннее имя платформы. */
    public static boolean isInternal(String text) {
        return contains(text, LATIN, CYRILLIC);
    }

    static boolean contains(String text, Set<String> latin, Set<String> cyrillic) {
        if (text == null || text.isEmpty()) return false;
        String s = text.toLowerCase(Locale.ROOT);
        int n = s.length();
        int i = 0;
        while (i < n) {
            if (!Character.isLetter(s.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && Character.isLetter(s.charAt(j))) j++;
            if (tokenMatches(s.substring(i, j), latin, cyrillic)) return true;
            i = j;
        }
        return false;
    }

    private static boolean tokenMatches(String token, Set<String> latin, Set<String> cyrillic) {
        if (cyrillic.contains(fingerprint(token))) return true;
        int n = token.length();
        for (int a = 0; a < n; a++) {
            int b = a;
            while (b < n && b - a < MAX_LATIN && token.charAt(b) >= 'a' && token.charAt(b) <= 'z') {
                b++;
                if (b - a >= MIN_LATIN && latin.contains(fingerprint(token.substring(a, b)))) return true;
            }
        }
        return false;
    }

    /**
     * Итог {@link #scan}: сколько файлов прочитано, сколько в них разных слов и в каких файлах (путь от папки) нашлись
     * внутренние имена — сколько разных слов. Сами слова не возвращаются: их нельзя печатать (журналы CI открыты).
     */
    public record Scan(int files, int words, Map<String, Integer> hits) {
        public boolean clean() {
            return hits.isEmpty();
        }
    }

    /** Папки, в которые {@link #scan} не заходит: сборка, рабочие прогоны, служебные папки git, IDE и Python. */
    public static final Set<String> SKIP_DIRS = Set.of("target", "work", ".git", ".idea", ".vscode", "__pycache__", ".venv");

    /** Двоичные и сжатые файлы (кроме {@code .tsv.gz}) — без содержимого, проверяется только имя. */
    static final List<String> BINARY = List.of(".gz", ".tar", ".zip", ".jar", ".class", ".png", ".jpg", ".pdf", ".duckdb");

    /**
     * Найти внутренние имена в файле или во всех файлах папки (с вложенными, кроме {@link #SKIP_DIRS}): {@code .tsv.gz}
     * читается разжатым, прочее — как текст UTF-8 (испорченные байты — заменой), у двоичных ({@link #BINARY}) —
     * только имя файла. Слово — подряд идущие буквы, как в {@link #isInternal}.
     */
    public static Scan scan(Path root) throws IOException {
        return scan(root, LATIN, CYRILLIC);
    }

    static Scan scan(Path root, Set<String> latin, Set<String> cyrillic) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(root)) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    boolean skip = !dir.equals(root) && SKIP_DIRS.contains(String.valueOf(dir.getFileName()));
                    return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            files.add(root);
        }
        files.sort(null);
        Map<String, Boolean> verdict = new HashMap<>();
        Map<String, Integer> hits = new TreeMap<>();
        for (Path f : files) {
            String name = String.valueOf(f.getFileName());
            String lower = name.toLowerCase(Locale.ROOT);
            Set<String> words = new HashSet<>();
            words(name, words);
            if (lower.endsWith(".tsv.gz")) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(f), 1 << 16),
                        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                                .onUnmappableCharacter(CodingErrorAction.REPLACE)), 1 << 16)) {
                    for (String line; (line = r.readLine()) != null; ) words(line, words);
                }
            } else if (BINARY.stream().noneMatch(lower::endsWith)) {
                words(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), words);
            }
            int n = 0;
            for (String w : words) if (verdict.computeIfAbsent(w, x -> contains(x, latin, cyrillic))) n++;
            if (n > 0) hits.put(f.equals(root) ? name : root.relativize(f).toString().replace('\\', '/'), n);
        }
        return new Scan(files.size(), verdict.size(), hits);
    }

    /** Слова текста — подряд идущие буквы. */
    static void words(String text, Set<String> out) {
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (!Character.isLetter(text.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && Character.isLetter(text.charAt(j))) j++;
            out.add(text.substring(i, j));
            i = j;
        }
    }

    static String fingerprint(String word) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(word.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
