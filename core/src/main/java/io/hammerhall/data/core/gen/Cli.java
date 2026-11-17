package io.hammerhall.data.core.gen;

import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.io.Json;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.SchemaExport;
import io.hammerhall.data.core.time.Period;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Командная строка генератора — одна для всех наборов: {@code Main} домена зовёт {@link #main(Dataset, String[])}.
 *
 * <pre>
 *   generate --profile small|medium|full --seed N --out DIR [--ВХОД ПУТЬ …]
 *            [--modules a,b] [--to YYYY-MM-DD] [--gzip 1..9, по умолчанию — у набора, обычно 6] [--inputs strict]
 *   schema   [--out DIR]               DDL (две части), ER-диаграмма Mermaid, поля — без данных (по умолчанию — &lt;код&gt;/schema)
 *   verify   DIR                       сверить файлы выпуска с manifest.json (строки, sha256 сжатого и разжатого)
 *   freeze   DIR [--out FILE]          файл ожиданий из готового выпуска (по умолчанию &lt;код&gt;/expected/ВЕРСИЯ-ПРОФИЛЬ.json)
 *   check    DIR [--expected FILE] [--aggregates no]   проверить выпуск против ожиданий (как tools/check_data.py)
 *   names    ПУТЬ                      нет ли в файлах (выпуск, исходники) внутренних имён платформы; в выпуске этого
 *                                      набора значения полей «как есть» из внешнего источника не проверяются
 * </pre>
 * {@code --inputs strict} — входы обязаны совпасть с эталоном ({@link Dataset#readInputs}); без него расхождение —
 * предупреждение. Путь ко входу — {@link #inputPath}; папка набора (паспорт, схема, ожидания) — {@link #dataDir}.
 */
public final class Cli {
    /** Паспорт набора — в папке набора. */
    public static final String PASSPORT = "dataset.properties";

    private Cli() {
    }

    public static void main(Dataset d, String[] args) throws Exception {
        System.exit(exec(d, args));
    }

    /** Выполнить команду; результат — код выхода (0 — успех, 1 — проверка не прошла, 2 — неверный вызов). */
    public static int exec(Dataset d, String[] args) throws IOException {
        if (args.length == 0) {
            usage(d);
            return 2;
        }
        Map<String, String> opt = new LinkedHashMap<>();
        List<String> pos = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("нет значения у " + args[i]);
                opt.put(args[i].substring(2), args[++i]);
            } else {
                pos.add(args[i]);
            }
        }
        Path dir = pos.isEmpty() ? (opt.containsKey("dir") ? Path.of(opt.get("dir")) : null) : Path.of(pos.getFirst());
        switch (args[0]) {
            case "generate" -> {
                return generate(d, opt) ? 0 : 1;
            }
            case "schema" -> {
                schema(d, opt.containsKey("out") ? Path.of(opt.get("out")) : dataDir(d).resolve("schema"));
                return 0;
            }
            case "verify" -> {
                return verify(need(dir)) ? 0 : 1;
            }
            case "freeze" -> {
                freeze(d, need(dir), opt.get("out"));
                return 0;
            }
            case "check" -> {
                return check(d, need(dir), opt.get("expected"), !"no".equals(opt.get("aggregates"))) ? 0 : 1;
            }
            case "names" -> {
                return names(d, need(dir)) ? 0 : 1;
            }
            default -> {
                usage(d);
                return 2;
            }
        }
    }

    private static Path need(Path dir) {
        if (dir == null) throw new IllegalArgumentException("нужна папка: КОМАНДА ПАПКА");
        return dir;
    }

    private static void usage(Dataset d) {
        StringBuilder in = new StringBuilder();
        for (Dataset.Input i : d.inputs()) in.append(" --").append(i.name()).append(" ПУТЬ");
        System.err.println(d.generatorVersion() + " — генератор набора " + d.version() + " (" + d.title() + ")\n"
                + "  generate --profile small|medium|full --seed N --out DIR" + in
                + " [--modules a,b] [--to YYYY-MM-DD] [--gzip 1..9, по умолчанию " + d.gzipLevel() + "] [--inputs strict]\n"
                + "  schema   [--out DIR]\n"
                + "  verify   DIR\n"
                + "  freeze   DIR [--out FILE]\n"
                + "  check    DIR [--expected FILE] [--aggregates no]\n"
                + "  names    ПУТЬ");
    }

    // ---------------------------------------------------------------- папка набора и входы

    /**
     * Папка набора в репозитории ({@code <код>/}: паспорт, схема, ожидания, рабочие входы): {@code -Ddata.dir} (её
     * ставят {@code build.sh} и Maven), иначе {@code ./<код>}, если там паспорт, иначе текущая папка.
     */
    public static Path dataDir(Dataset d) {
        String p = System.getProperty("data.dir");
        if (p != null && !p.isBlank()) return Path.of(p);
        if (Files.isRegularFile(Path.of(d.code(), PASSPORT))) return Path.of(d.code());
        return Path.of("");
    }

    /** Переменная окружения входа: {@code <КОД>_<ИМЯ>}, например {@code STO_AIRPORTS}. */
    public static String envName(Dataset d, Dataset.Input in) {
        return (d.code() + "_" + in.name()).toUpperCase(Locale.ROOT);
    }

    /**
     * Путь ко входу: {@code --имя ПУТЬ} → {@code -D<код>.<имя>=ПУТЬ} → переменная {@link #envName} →
     * {@code <папка набора>/work/<файл>}.
     */
    public static Path inputPath(Dataset d, Dataset.Input in, Map<String, String> opt) {
        String v = opt.get(in.name());
        if (v == null || v.isBlank()) v = System.getProperty(d.code() + "." + in.name());
        if (v == null || v.isBlank()) v = System.getenv(envName(d, in));
        return v != null && !v.isBlank() ? Path.of(v) : dataDir(d).resolve("work").resolve(in.file());
    }

    /** Пути ко всем входам набора (как у {@code generate}, без опций командной строки). */
    public static Map<String, Path> inputPaths(Dataset d) {
        Map<String, Path> out = new LinkedHashMap<>();
        for (Dataset.Input in : d.inputs()) out.put(in.name(), inputPath(d, in, Map.of()));
        return out;
    }

    // ---------------------------------------------------------------- прогон

    /** Конфигурация прогона из опций командной строки (те же ключи, что у {@code generate}, без «--»). */
    public static Config config(Dataset d, Map<String, String> opt) {
        Period period = d.period();
        if (opt.containsKey("to")) period = period.withTo(LocalDate.parse(opt.get("to")));
        Map<String, Path> inputs = new LinkedHashMap<>();
        for (Dataset.Input in : d.inputs()) inputs.put(in.name(), inputPath(d, in, opt));
        List<String> modules = opt.containsKey("modules") ? List.of(opt.get("modules").split(",")) : List.of();
        return new Config(Long.parseLong(opt.getOrDefault("seed", Long.toString(d.defaultSeed()))),
                Profile.parse(opt.getOrDefault("profile", "small")), period,
                Path.of(opt.getOrDefault("out", d.version())), inputs,
                Integer.parseInt(opt.getOrDefault("gzip", Integer.toString(d.gzipLevel()))), modules, d.version(),
                d.generatorVersion());
    }

    /** Прогон с проверкой входов «предупреждением» (тесты, локальные прогоны). */
    public static Release.Result run(Dataset d, Map<String, String> opt) throws IOException {
        return run(d, config(d, opt), false);
    }

    /** @param strictInputs входы обязаны совпасть с эталоном, иначе — ошибка (для CI) */
    public static Release.Result run(Dataset d, Config cfg, boolean strictInputs) throws IOException {
        return Release.run(d, cfg, d.readInputs(cfg, strictInputs));
    }

    static boolean generate(Dataset d, Map<String, String> opt) throws IOException {
        long t0 = System.nanoTime();
        String inputs = opt.getOrDefault("inputs", "warn");
        if (!inputs.equals("warn") && !inputs.equals("strict")) throw new IllegalArgumentException("--inputs warn | strict");
        Release.Result r = run(d, config(d, opt), inputs.equals("strict"));
        Runtime rt = Runtime.getRuntime();
        long peak = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) peak += p.getPeakUsage().getUsed();
        }
        System.out.printf(Locale.ROOT, "итого: %.1f с, куча занята %d МБ из %d МБ, пик по пулам кучи ≤ %d МБ%n",
                (System.nanoTime() - t0) / 1e9, (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20, peak >> 20);
        if (!r.ok()) System.out.println("САМОПРОВЕРКА НЕ ПРОЙДЕНА — выпуск не годен");
        return r.ok();
    }

    // ---------------------------------------------------------------- ожидания, схема, проверки

    static void freeze(Dataset d, Path dir, String outFile) throws IOException {
        Path out = outFile != null ? Path.of(outFile) : Expected.defaultPath(dataDir(d), dir);
        System.out.println("заморозка " + dir + " → " + out);
        Expected.write(out, Expected.freeze(dir, d.schema(), d.aggregates(), System.out));
        System.out.println("ожидания записаны: " + out);
    }

    static boolean check(Dataset d, Path dir, String expectedFile, boolean aggregates) throws IOException {
        Path exp = expectedFile != null ? Path.of(expectedFile) : Expected.defaultPath(dataDir(d), dir);
        System.out.println("проверка " + dir + " против " + exp);
        return Expected.check(dir, Expected.read(exp), System.out, aggregates);
    }

    /** Файлы схемы, которые пишет {@link #schema} (и которые лежат в git в {@code <код>/schema/}). */
    public static final List<String> SCHEMA_FILES = List.of("schema-tables.sql", "schema-constraints.sql", "schema.mmd",
            "schema-fields.md");

    /** Тексты схемы по {@link #SCHEMA_FILES}. */
    public static List<String> schemaTexts(Schema s) {
        List<String> errors = s.validate();
        if (!errors.isEmpty()) throw new IllegalStateException("описание схемы: " + errors);
        return List.of(SchemaExport.tablesSql(s), SchemaExport.constraintsSql(s), SchemaExport.mermaid(s),
                "# " + s.version() + " — поля\n" + SchemaExport.fieldsMarkdown(s));
    }

    static void schema(Dataset d, Path out) throws IOException {
        Schema s = d.schema();
        List<String> texts = schemaTexts(s);
        Files.createDirectories(out);
        for (int i = 0; i < SCHEMA_FILES.size(); i++) {
            Files.writeString(out.resolve(SCHEMA_FILES.get(i)), texts.get(i), StandardCharsets.UTF_8);
        }
        System.out.println("схема: " + s.tables().size() + " таблиц → " + out);
    }

    /**
     * Поля «как есть» для проверки имён: папка — выпуск этого набора ({@code manifest.json} с его версией) — по схеме
     * набора ({@link PublicNames#verbatimColumns}); иначе (исходники, чужой выпуск, отдельный файл) — нет.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, java.util.Set<String>> verbatimFor(Dataset d, Path path) {
        Path m = path.resolve("manifest.json");
        if (!Files.isDirectory(path) || !Files.isRegularFile(m)) return Map.of();
        try {
            Object v = ((Map<String, Object>) Json.parse(Files.readString(m, StandardCharsets.UTF_8))).get("version");
            return d.version().equals(v) ? PublicNames.verbatimColumns(d.schema()) : Map.of();
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    static boolean names(Dataset d, Path path) throws IOException {
        PublicNames.Scan s = PublicNames.scan(path, verbatimFor(d, path));
        for (Map.Entry<String, Integer> h : s.hits().entrySet()) {
            System.out.println("НЕТ  " + h.getKey() + " — внутренних имён платформы: " + h.getValue());
        }
        if (!s.skipped().isEmpty()) System.out.println(s.skippedSummary());
        System.out.println(s.clean()
                ? "внутренних имён нет: " + s.files() + " файлов, " + s.words() + " разных слов"
                : "ЕСТЬ ВНУТРЕННИЕ ИМЕНА: " + s.hits().size() + " файлов из " + s.files());
        return s.clean() && s.files() > 0;
    }

    @SuppressWarnings("unchecked")
    static boolean verify(Path dir) throws IOException {
        Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));
        boolean ok = true;
        for (Map<String, Object> f : (List<Map<String, Object>>) m.get("files")) {
            Path p = dir.resolve((String) f.get("name"));
            MessageDigest plain, gz;
            try {
                plain = MessageDigest.getInstance("SHA-256");
                gz = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            long lines = 0;
            try (InputStream in = new DigestInputStream(new GZIPInputStream(new DigestInputStream(Files.newInputStream(p), gz), 1 << 16), plain)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) for (int i = 0; i < n; i++) if (buf[i] == '\n') lines++;
            }
            boolean good = lines - 1 == (Long) f.get("rows") && HexFormat.of().formatHex(plain.digest()).equals(f.get("sha256_tsv"))
                    && HexFormat.of().formatHex(gz.digest()).equals(f.get("sha256"));
            ok &= good;
            System.out.println((good ? "OK   " : "НЕТ  ") + f.get("name") + " " + (lines - 1) + " строк");
        }
        for (String extra : List.of("LICENSE", "SOURCE.md", "schema-tables.sql", "schema-constraints.sql", "schema.mmd",
                "load-postgres.psql")) {
            boolean present = Files.exists(dir.resolve(extra));
            ok &= present;
            System.out.println((present ? "OK   " : "НЕТ  ") + extra);
        }
        System.out.println(ok ? "ВЫПУСК В ПОРЯДКЕ" : "ВЫПУСК НЕ СОВПАЛ С МАНИФЕСТОМ");
        return ok;
    }
}
