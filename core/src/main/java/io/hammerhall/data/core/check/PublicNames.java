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
 *
 * <p><b>Значения внешнего источника как есть</b> (поля схемы с {@code Table.verbatimSource()}: названия мест, номера,
 * имена из чужой выгрузки) — не наши слова, совпадения в них случайны. Проверка выпуска ({@link #scan(Path, Map)} с
 * {@link #verbatimColumns}) пропускает <i>только значения</i> таких полей в {@code .tsv.gz}; заголовок файла, тексты
 * выпуска и прочие поля проверяются как обычно, а что пропущено — в {@link Scan#skipped()} (и строкой в отчёте).
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
    public record Scan(int files, int words, Map<String, Integer> hits, Map<String, List<String>> skipped) {
        public boolean clean() {
            return hits.isEmpty();
        }

        /** Строка для отчёта: значения каких полей не проверялись (пусто — пропусков нет). */
        public String skippedSummary() {
            if (skipped.isEmpty()) return "";
            int n = 0;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> e : skipped.entrySet()) {
                n += e.getValue().size();
                sb.append(sb.isEmpty() ? "" : "; ").append(e.getKey()).append(": ").append(String.join(", ", e.getValue()));
            }
            return "значения внешнего источника как есть не проверялись: " + n + " полей в " + skipped.size()
                    + " таблицах (" + sb + ")";
        }
    }

    /**
     * Поля «как есть» ({@code Column.verbatim()}) по файлам выпуска схемы: {@code <таблица>.tsv.gz} → имена полей.
     * Для {@link #scan(Path, Map)} по папке выпуска этой схемы.
     */
    public static Map<String, Set<String>> verbatimColumns(io.hammerhall.data.core.schema.Schema schema) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (io.hammerhall.data.core.schema.Table t : schema.tables()) {
            Set<String> cols = new java.util.LinkedHashSet<>();
            for (io.hammerhall.data.core.schema.Column c : t.columns()) if (c.verbatim()) cols.add(c.name());
            if (!cols.isEmpty()) out.put(t.name() + ".tsv.gz", cols);
        }
        return out;
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
        return scan(root, Map.of(), LATIN, CYRILLIC);
    }

    /**
     * То же, но в файлах {@code .tsv.gz} из {@code verbatim} (путь от папки → имена полей) значения этих полей не
     * проверяются — только для папки выпуска, чья схема их так пометила ({@link #verbatimColumns}).
     */
    public static Scan scan(Path root, Map<String, Set<String>> verbatim) throws IOException {
        return scan(root, verbatim, LATIN, CYRILLIC);
    }

    static Scan scan(Path root, Set<String> latin, Set<String> cyrillic) throws IOException {
        return scan(root, Map.of(), latin, cyrillic);
    }

    static Scan scan(Path root, Map<String, Set<String>> verbatim, Set<String> latin, Set<String> cyrillic) throws IOException {
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
        Map<String, List<String>> skipped = new TreeMap<>();
        for (Path f : files) {
            String name = String.valueOf(f.getFileName());
            String lower = name.toLowerCase(Locale.ROOT);
            String rel = f.equals(root) ? name : root.relativize(f).toString().replace('\\', '/');
            Set<String> words = new HashSet<>();
            words(name, words);
            if (lower.endsWith(".tsv.gz")) {
                Set<String> skipCols = verbatim.getOrDefault(rel, Set.of());
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(f), 1 << 16),
                        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                                .onUnmappableCharacter(CodingErrorAction.REPLACE)), 1 << 16)) {
                    // заголовок — всегда целиком; значения полей «как есть» — мимо
                    String header = r.readLine();
                    boolean[] skip = null;
                    if (header != null) {
                        words(header, words);
                        String[] h = header.split("\t", -1);
                        List<String> used = new ArrayList<>();
                        for (String c : h) if (skipCols.contains(c)) used.add(c);
                        if (!used.isEmpty()) {
                            skip = new boolean[h.length];
                            for (int i = 0; i < h.length; i++) skip[i] = skipCols.contains(h[i]);
                            skipped.put(rel, used);
                        }
                    }
                    for (String line; (line = r.readLine()) != null; ) {
                        if (skip == null) {
                            words(line, words);
                            continue;
                        }
                        String[] v = line.split("\t", -1);
                        for (int i = 0; i < v.length; i++) if (i >= skip.length || !skip[i]) words(v[i], words);
                    }
                }
            } else if (BINARY.stream().noneMatch(lower::endsWith)) {
                words(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), words);
            }
            int n = 0;
            for (String w : words) if (verdict.computeIfAbsent(w, x -> contains(x, latin, cyrillic))) n++;
            if (n > 0) hits.put(rel, n);
        }
        return new Scan(files.size(), verdict.size(), hits, skipped);
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
