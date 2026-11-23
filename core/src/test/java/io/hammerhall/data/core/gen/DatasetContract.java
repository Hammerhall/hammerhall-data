package io.hammerhall.data.core.gen;

import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.schema.SchemaDot;
import io.hammerhall.data.core.schema.Table;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Общие тесты набора — одни для всех наборов репозитория. Домен наследует класс одной строкой и отдаёт свой
 * {@link Dataset}:
 * <pre>
 *   class StoContractTest extends DatasetContract {
 *       &#64;Override protected Dataset dataset() { return new StoDataset(); }
 *   }
 * </pre>
 * Проверяется: паспорт {@code <код>/dataset.properties} совпадает с генератором; {@code Main} домена — по контракту;
 * схема в git ({@code <код>/schema/}: DDL, ER-диаграмма, поля, картинки в DOT) — та, что пишет генератор; у каждой
 * таблицы есть короткое название; у каждого DOT — SVG, нарисованный из него (отметка sha256); {@code small}-выпуск со
 * всеми модулями проходит самопроверку и сверку с {@code manifest.json}; ни в выпуске, ни в файлах репозитория нет
 * внутренних имён платформы.
 * Без наследника тесты не идут: {@code TestMain} (прогон {@code build.sh}) считает это провалом.
 *
 * <p>{@link #small(Dataset)} — тот же {@code small}-выпуск для тестов домена: строится один раз на JVM.
 */
public abstract class DatasetContract {
    /** Ключи паспорта; {@code input.<имя>.*} — у каждого входа из {@code inputs}. */
    static final Set<String> PASSPORT_KEYS = Set.of("code", "title", "summary", "version", "build", "profiles", "license",
            "release.environment", "full.xmx", "full.budget_minutes", "inputs");
    static final Set<String> INPUT_KEYS = Set.of("file", "release", "url", "sha256_tsv", "sha256");
    /** Необязательное {@code profile.<профиль>=…} — что такое профиль у набора (для заметок к выпуску); профиль — из {@code profiles}. */
    static final String PROFILE_NOTE = "profile.";
    /** Строка паспорта — «ключ=значение» без экранирования (так его одинаково читают bash, Python и Java) или комментарий. */
    static final Pattern LINE = Pattern.compile("^(#.*|[a-z][a-z0-9_.]*=[^\\\\]*|\\s*)$");

    protected abstract Dataset dataset();

    // ---------------------------------------------------------------- общий small-выпуск

    /** Готовый выпуск {@code small} со всеми модулями и seed по умолчанию. */
    public record Small(Path dir, Release.Result result) {
    }

    private static final Map<String, Small> SMALL = new HashMap<>();

    /** Нет входа набора (например, {@code airports.tsv.gz}) — тест пропускается (в CI пропуск — провал). */
    public static void assumeInputs(Dataset d) {
        for (Map.Entry<String, Path> in : Cli.inputPaths(d).entrySet()) {
            assumeTrue(Files.isRegularFile(in.getValue()), "нет входа " + in.getKey() + " (" + in.getValue()
                    + ") — пропуск сборочных тестов");
        }
    }

    /** {@code small}-выпуск набора — один на JVM (папка удаляется при выходе). */
    public static synchronized Small small(Dataset d) throws IOException {
        assumeInputs(d);
        Small s = SMALL.get(d.code());
        if (s == null) {
            Path dir = Files.createTempDirectory("data-" + d.code() + "-small-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(dir)));
            Release.Result r = Cli.run(d, Map.of("profile", "small", "out", dir.toString()));
            s = new Small(dir, r);
            SMALL.put(d.code(), s);
        }
        return s;
    }

    static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            // временная папка; не удалилась — не ошибка теста
        }
    }

    // ---------------------------------------------------------------- паспорт

    static Properties passport(Path dir, List<String> problems) throws IOException {
        Path p = dir.resolve(Cli.PASSPORT);
        assertTrue(Files.isRegularFile(p), "нет паспорта набора " + p);
        int n = 0;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            n++;
            if (!LINE.matcher(line).matches()) problems.add(Cli.PASSPORT + ":" + n + ": не «ключ=значение»: " + line);
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            props.load(r);
        }
        return props;
    }

    @Test
    void passportMatchesGenerator() throws IOException {
        Dataset d = dataset();
        Path dir = Cli.dataDir(d).toAbsolutePath().normalize();
        List<String> problems = new ArrayList<>();
        Properties p = passport(dir, problems);
        Map<String, String> want = new HashMap<>();
        want.put("code", d.code());
        want.put("title", d.title());
        want.put("version", d.version());
        want.put("build", "java");
        want.put("release.environment", d.code() + "-data-release");
        for (Map.Entry<String, String> w : want.entrySet()) {
            if (!w.getValue().equals(p.getProperty(w.getKey()))) {
                problems.add(w.getKey() + "=" + p.getProperty(w.getKey()) + ", у генератора — " + w.getValue());
            }
        }
        if (!String.valueOf(dir.getFileName()).equals(d.code())) problems.add("папка набора " + dir + " ≠ код " + d.code());
        if (!d.version().matches(Pattern.quote(d.code()) + "-data-v[0-9]+")) problems.add("версия не <код>-data-vN: " + d.version());
        List<String> profiles = List.of(p.getProperty("profiles", "").trim().split("\\s+"));
        for (String pr : profiles) {
            try {
                Profile.parse(pr);
            } catch (IllegalArgumentException e) {
                problems.add("profiles: " + e.getMessage());
            }
        }
        if (!profiles.contains("small")) problems.add("profiles без small (≈ 1 %) — он есть у всех наборов");
        List<String> names = new ArrayList<>();
        for (Dataset.Input in : d.inputs()) names.add(in.name());
        String inputs = p.getProperty("inputs", "").trim();
        List<String> declared = inputs.isEmpty() ? List.of() : List.of(inputs.split("\\s+"));
        if (!declared.equals(names)) problems.add("inputs=" + declared + ", у генератора — " + names);
        for (Dataset.Input in : d.inputs()) {
            String k = "input." + in.name() + ".";
            check(problems, k + "file", in.file(), p.getProperty(k + "file"));
            check(problems, k + "sha256_tsv", in.sha256Tsv(), p.getProperty(k + "sha256_tsv"));
            check(problems, k + "sha256", in.sha256(), p.getProperty(k + "sha256"));
            if ((p.getProperty(k + "release") == null) == (p.getProperty(k + "url") == null)) {
                problems.add(k + "release или " + k + "url — ровно одно: откуда CI берёт вход");
            }
        }
        for (String key : p.stringPropertyNames()) {
            boolean known = PASSPORT_KEYS.contains(key);
            for (String n : names) known |= key.startsWith("input." + n + ".") && INPUT_KEYS.contains(key.substring(("input." + n + ".").length()));
            known |= key.startsWith(PROFILE_NOTE) && profiles.contains(key.substring(PROFILE_NOTE.length()))
                    && !p.getProperty(key).isBlank();
            if (!known) problems.add("неизвестный ключ паспорта: " + key);
        }
        assertEquals(List.of(), problems, "паспорт " + dir.resolve(Cli.PASSPORT));
    }

    private static void check(List<String> problems, String key, String want, String have) {
        if (want == null ? have != null : !want.equals(have)) problems.add(key + "=" + have + ", у генератора — " + want);
    }

    @Test
    void mainFollowsTheContract() throws ReflectiveOperationException {
        Dataset d = dataset();
        assertEquals("io.hammerhall.data." + d.code(), d.getClass().getPackageName(), "пакет набора — io.hammerhall.data.<код>");
        Method main = Class.forName("io.hammerhall.data." + d.code() + ".Main").getMethod("main", String[].class);
        assertTrue(Modifier.isStatic(main.getModifiers()), "Main.main — static");
    }

    // ---------------------------------------------------------------- схема и выпуск

    @Test
    void schemaInGitMatchesGenerator() throws IOException {
        Dataset d = dataset();
        Path dir = Cli.dataDir(d).resolve("schema");
        Map<String, String> texts = Cli.schemaTexts(d.schema());
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> t : texts.entrySet()) {
            Path f = dir.resolve(t.getKey());
            if (!Files.isRegularFile(f) || !Files.readString(f, StandardCharsets.UTF_8).equals(t.getValue())) stale.add(f.toString());
        }
        for (Path f : Cli.dotFiles(dir)) {
            if (!texts.containsKey(f.getFileName().toString())) stale.add(f + " — такой картинки генератор не пишет");
        }
        assertEquals(List.of(), stale, "схема в git не совпала с генератором: ./build.sh " + d.code() + " exec schema");
    }

    /** У каждой таблицы — короткое русское название: шапка таблицы на картинке схемы. */
    @Test
    void everyTableHasTitle() {
        List<String> missing = new ArrayList<>();
        for (Table t : dataset().schema().tables()) if (t.title() == null || t.title().isBlank()) missing.add(t.name());
        assertEquals(List.of(), missing, "таблицы без title(\"…\") — короткого названия для картинки схемы");
    }

    /**
     * У каждого DOT в {@code <код>/schema/} — SVG рядом, нарисованный из него: в SVG отметка sha256 этого DOT. Схема
     * изменилась, а картинку не перерисовали — провал. Graphviz тесту не нужен: сверяется только отметка.
     */
    @Test
    void schemaPicturesAreFresh() throws IOException {
        Dataset d = dataset();
        Path dir = Cli.dataDir(d).resolve("schema");
        List<Path> dots = Cli.dotFiles(dir);
        assertTrue(!dots.isEmpty(), "нет картинок схемы в " + dir + ": ./build.sh " + d.code() + " exec schema");
        List<String> stale = new ArrayList<>();
        for (Path dot : dots) {
            Path svg = dir.resolve(SchemaDot.svgName(dot.getFileName().toString()));
            if (!Files.isRegularFile(svg)) {
                stale.add(svg.getFileName() + " — нет файла");
                continue;
            }
            String want = SchemaDot.sha256(Files.readString(dot, StandardCharsets.UTF_8));
            String have = SchemaDot.markedSha256(Files.readString(svg, StandardCharsets.UTF_8));
            if (!want.equals(have)) {
                stale.add(svg.getFileName() + (have == null ? " — нет отметки sha256 DOT" : " — нарисована из другого DOT"));
            }
        }
        assertEquals(List.of(), stale, "картинки схемы устарели — перерисуй: tools/schema-svg.sh " + d.code());
    }

    @Test
    void smallReleaseChecksOut() throws IOException {
        Dataset d = dataset();
        Small s = small(d);
        assertTrue(s.result().ok(), s.result().report().render());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        PrintStream old = System.out;
        boolean ok;
        try {
            System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
            ok = Cli.verify(d, s.dir());
        } finally {
            System.setOut(old);
        }
        assertTrue(ok, bo.toString(StandardCharsets.UTF_8));
    }

    /**
     * Публичные имена не пересекаются с внутренними: во всём выпуске нет ни одного внутреннего имени платформы. Значения
     * полей, помеченных в схеме «как есть из внешнего источника» ({@code verbatimSource}), не проверяются — что пропущено,
     * печатается; заголовки, тексты и прочие поля — проверяются.
     */
    @Test
    void releaseHasNoInternalNames() throws IOException {
        Path dir = small(dataset()).dir();
        PublicNames.Scan scan = PublicNames.scan(dir, Cli.verbatimFor(dataset(), dir));
        if (!scan.skipped().isEmpty()) System.out.println(dataset().code() + ": " + scan.skippedSummary());
        assertEquals(Map.of(), scan.hits(), "внутренние имена в выпуске (файл → число слов)");
        // сканер читал таблицы разжатыми: слов в файле больше, чем в его имени (заголовок — уже слова)
        List<Path> tables;
        try (Stream<Path> s = Files.list(dir)) {
            tables = s.filter(p -> p.getFileName().toString().endsWith(".tsv.gz")).sorted().toList();
        }
        assertTrue(!tables.isEmpty() && scan.files() > tables.size(), "файлов прочитано " + scan.files());
        for (Path t : tables) {
            long inName = Pattern.compile("\\p{L}+").matcher(t.getFileName().toString()).results().count();
            assertTrue(PublicNames.scan(t).words() > inName, "в " + t.getFileName() + " не прочитано содержимое");
        }
    }

    /** То же для репозитория: код, комментарии, тесты, тексты, паспорта и ожидания всех наборов. */
    @Test
    void repositoryHasNoInternalNames() throws IOException {
        Path root = Cli.dataDir(dataset()).toAbsolutePath().normalize().getParent();
        assumeTrue(root != null && Files.isRegularFile(root.resolve("build.sh")), "нет корня репозитория");
        PublicNames.Scan scan = PublicNames.scan(root);
        assertEquals(Map.of(), scan.hits(), "внутренние имена в репозитории (файл → число слов)");
        assertTrue(scan.files() > 50, "файлов " + scan.files() + " — сканер не читал репозиторий");
    }
}
