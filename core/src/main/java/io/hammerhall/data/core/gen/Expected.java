package io.hammerhall.data.core.gen;

import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.Aggregates;
import io.hammerhall.data.core.io.Json;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Файл ожиданий выпуска ({@code freeze}) и проверка выпуска по нему ({@code check}): пользователь скачивает выпуск и
 * сверяет его с числами, зафиксированными в репозитории ({@code <код>/expected/<версия>-<профиль>.json}). Тот же
 * формат и тот же счёт — у {@code tools/check_data.py}.
 *
 * <p><b>Тождество выпуска</b> — содержимое, от сборки zlib не зависит:
 * <ul>
 *   <li>у каждой таблицы — число строк и {@code sha256_tsv} (sha256 разжатого TSV);</li>
 *   <li>sha256 текстов выпуска, которые пишутся без сжатия ({@link #TEXTS});</li>
 *   <li>поля {@code manifest.json}, кроме списка файлов: версия, генератор, seed, профиль, период, модули, входы, формат;</li>
 *   <li>контрольные агрегаты таблиц ({@link Aggregate}) — не добавляют строгости к sha256, но показывают, <i>что</i>
 *       разошлось.</li>
 * </ul>
 * <b>Справочно</b> ({@link #REFERENCE_ONLY}): байты и sha256 файлов {@code .tsv.gz} (другая сборка zlib — другие
 * байты того же содержимого), поэтому и {@code manifest.json} целиком, и {@code SOURCE.md} (размеры файлов в нём —
 * по сжатым байтам). sha256 сжатого файла проверяется против {@code manifest.json} того же выпуска — это проверка
 * скачивания, а не тождества.
 */
public final class Expected {
    /** Тексты выпуска без сжатия — входят в тождество. */
    public static final List<String> TEXTS = List.of("LICENSE", "schema-tables.sql", "schema-constraints.sql", "schema.mmd",
            "load-postgres.psql");
    /** Файлы выпуска, которые зависят от байтов gzip, — справочно. */
    public static final List<String> REFERENCE_ONLY = List.of("manifest.json", "SOURCE.md");

    private Expected() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> manifest(Path dir) throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));
    }

    /** Поля манифеста, входящие в тождество: все, кроме списка файлов. */
    static Map<String, Object> identity(Map<String, Object> manifest) {
        Map<String, Object> m = new LinkedHashMap<>(manifest);
        m.remove("files");
        return m;
    }

    /**
     * {@code <папка набора>/expected/<версия>-<профиль>.json}; версия и профиль — из манифеста выпуска (профиля нет —
     * {@code full}: выпуск одним профилем).
     */
    public static Path defaultPath(Path datasetDir, Path releaseDir) throws IOException {
        Map<String, Object> m = manifest(releaseDir);
        return datasetDir.resolve("expected").resolve(m.get("version") + "-" + m.getOrDefault("profile", "full") + ".json");
    }

    static String sha256(Path p) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Агрегаты таблицы: ядра по умолчанию, затем объявленные (повторы по {@code label} — один раз). */
    static List<Aggregates.Spec> specs(Table t, List<Aggregate> declared) {
        Map<String, Aggregates.Spec> out = new LinkedHashMap<>();
        for (Aggregate a : Aggregates.defaults(t)) out.putIfAbsent(a.label(), Aggregates.Spec.of(a, t));
        for (Aggregate a : declared) if (a.table().equals(t.name())) out.putIfAbsent(a.label(), Aggregates.Spec.of(a, t));
        return new ArrayList<>(out.values());
    }

    /**
     * Ожидания из готового выпуска: файлы сверяются с его {@code manifest.json} (не совпал — выпуск испорчен,
     * замораживать нельзя), агрегаты считаются по тексту TSV.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> freeze(Path dir, Schema schema, List<Aggregate> declared, PrintStream log) throws IOException {
        for (Aggregate a : declared) Aggregates.Spec.of(a, schema.table(a.table()));
        Map<String, Object> m = manifest(dir);
        List<Object> files = new ArrayList<>(), aggs = new ArrayList<>(), texts = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) m.get("files")) {
            String name = (String) f.get("name");
            Table t = schema.table((String) f.get("table"));
            List<Aggregates.Spec> specs = specs(t, declared);
            Aggregates.FileStats st = Aggregates.compute(dir.resolve(name), specs);
            if (st.rows() != (Long) f.get("rows") || !st.sha256Tsv().equals(f.get("sha256_tsv")) || !st.sha256().equals(f.get("sha256"))) {
                throw new IOException(name + " не совпал с manifest.json — выпуск испорчен, замораживать нельзя");
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", name);
            e.put("table", t.name());
            e.put("rows", st.rows());
            e.put("sha256_tsv", st.sha256Tsv());
            e.put("sha256_gz_reference", st.sha256());
            files.add(e);
            for (int i = 0; i < specs.size(); i++) aggs.add(specs.get(i).toJson(st.values().get(i)));
            log.println("  " + name + ": " + st.rows() + " строк, агрегатов " + specs.size());
        }
        for (String n : TEXTS) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", n);
            e.put("sha256", sha256(dir.resolve(n)));
            texts.add(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", m.get("version"));
        out.put("profile", m.get("profile"));
        out.put("identity", "manifest (без files) + rows и sha256_tsv каждой таблицы + sha256 texts + aggregates; "
                + "sha256 и bytes файлов .tsv.gz, manifest.json и SOURCE.md — справочно (зависят от сборки zlib)");
        out.put("manifest", identity(m));
        out.put("files", files);
        out.put("texts", texts);
        out.put("reference_only", REFERENCE_ONLY);
        out.put("aggregates", aggs);
        return out;
    }

    /** Записать ожидания (JSON, UTF-8, {@code \n}). */
    public static void write(Path file, Map<String, Object> expected) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, Json.write(expected), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(Path file) throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Проверить выпуск против ожиданий: печатает «OK / НЕТ» по каждому пункту и итог. {@code aggregates = false} —
     * без агрегатов (только строки и sha256: быстрее на {@code full}).
     */
    @SuppressWarnings("unchecked")
    public static boolean check(Path dir, Map<String, Object> expected, PrintStream out, boolean aggregates) throws IOException {
        boolean ok = true;
        Map<String, Object> m = manifest(dir);
        Map<String, Object> want = (Map<String, Object>) expected.get("manifest"), have = identity(m);
        Set<String> keys = new LinkedHashSet<>(want.keySet());
        keys.addAll(have.keySet());
        for (String k : keys) {
            if (!Objects.equals(want.get(k), have.get(k))) {
                ok = false;
                out.println("НЕТ  manifest.json: «" + k + "» — ждали " + Json.write(want.get(k)).strip() + ", в выпуске "
                        + Json.write(have.get(k)).strip());
            }
        }
        if (ok) {
            out.println("OK   manifest.json: " + m.get("version") + ", профиль " + m.getOrDefault("profile", "full")
                    + (m.containsKey("seed") ? ", seed " + m.get("seed") : ""));
        }

        Map<String, Map<String, Object>> inManifest = new LinkedHashMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) m.get("files")) inManifest.put((String) f.get("name"), f);
        // агрегатов и текстов может не быть (ожидания, собранные из манифеста выпуска без генератора)
        List<Map<String, Object>> allAggs = (List<Map<String, Object>>) expected.getOrDefault("aggregates", List.of());
        int gzDiffer = 0;
        for (Map<String, Object> ef : (List<Map<String, Object>>) expected.get("files")) {
            String name = (String) ef.get("name");
            Map<String, Object> mf = inManifest.remove(name);
            Path p = dir.resolve(name);
            if (mf == null || !Files.exists(p)) {
                ok = false;
                out.println("НЕТ  " + name + " — " + (mf == null ? "нет в manifest.json" : "нет файла"));
                continue;
            }
            List<Aggregates.Spec> specs = new ArrayList<>();
            List<Object> wantValues = new ArrayList<>();
            if (aggregates) {
                for (Map<String, Object> a : allAggs) {
                    if (!ef.get("table").equals(a.get("table"))) continue;
                    specs.add(Aggregates.Spec.fromJson(a));
                    wantValues.add(a.get("value"));
                }
            }
            Aggregates.FileStats st = Aggregates.compute(p, specs);
            List<String> why = new ArrayList<>();
            if (st.rows() != (Long) ef.get("rows")) why.add("строк " + st.rows() + ", ждали " + ef.get("rows"));
            if (!st.sha256Tsv().equals(ef.get("sha256_tsv"))) why.add("содержимое другое (sha256_tsv)");
            if (!st.sha256().equals(mf.get("sha256"))) why.add("файл не совпал с manifest.json (sha256) — скачан с ошибкой?");
            if (!ef.get("rows").equals(mf.get("rows")) || !ef.get("sha256_tsv").equals(mf.get("sha256_tsv"))) {
                why.add("manifest.json не тот (rows / sha256_tsv)");
            }
            for (int i = 0; i < specs.size(); i++) {
                if (!Objects.equals(wantValues.get(i), st.values().get(i))) {
                    why.add(specs.get(i).label() + " = " + st.values().get(i) + ", ждали " + wantValues.get(i));
                }
            }
            if (!st.sha256().equals(ef.get("sha256_gz_reference"))) gzDiffer++;
            ok &= why.isEmpty();
            out.println((why.isEmpty() ? "OK   " : "НЕТ  ") + name + " — " + st.rows() + " строк"
                    + (specs.isEmpty() ? "" : ", агрегатов " + specs.size()) + (why.isEmpty() ? "" : ": " + String.join("; ", why)));
        }
        for (String extra : inManifest.keySet()) {
            ok = false;
            out.println("НЕТ  " + extra + " — лишний файл в manifest.json (в ожиданиях его нет)");
        }
        for (Map<String, Object> t : (List<Map<String, Object>>) expected.getOrDefault("texts", List.of())) {
            Path p = dir.resolve((String) t.get("name"));
            boolean good = Files.exists(p) && sha256(p).equals(t.get("sha256"));
            ok &= good;
            out.println((good ? "OK   " : "НЕТ  ") + t.get("name") + (good ? "" : " — " + (Files.exists(p) ? "текст другой" : "нет файла")));
        }
        for (String r : REFERENCE_ONLY) {
            boolean present = Files.exists(dir.resolve(r));
            ok &= present;
            out.println((present ? "OK   " : "НЕТ  ") + r + (present ? " (есть; справочно — зависит от байтов gzip)" : " — нет файла"));
        }
        if (gzDiffer > 0) {
            out.println("справочно: у " + gzDiffer + " файлов .tsv.gz сжатые байты не те, что при заморозке (другая сборка zlib);"
                    + " содержимое — то же, это не ошибка");
        }
        out.println(ok ? "ВЫПУСК В ПОРЯДКЕ" : "ВЫПУСК НЕ СОВПАЛ С ОЖИДАНИЯМИ");
        return ok;
    }
}
