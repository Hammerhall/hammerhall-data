package io.hammerhall.data.core.gen;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.io.GzipTsvFile;
import io.hammerhall.data.core.io.Json;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.SchemaExport;
import io.hammerhall.data.core.schema.Table;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Сборка выпуска: модули по зависимостям → самопроверка → {@code manifest.json}, DDL, ER-диаграмма, SOURCE.md,
 * LICENSE. Провал проверки — манифест не пишется, выпуск не годен.
 */
public final class Release {

    /** Что домен дописывает в выпуск помимо таблиц. */
    public interface Docs {
        String sourceMd(Schema schema, Map<String, Object> manifest);

        String license();
    }

    public record Result(boolean ok, List<GzipTsvFile.Info> files, CheckReport report, Map<String, Long> planted,
                         GenContext context) {
    }

    private Release() {
    }

    /** Модули в порядке зависимостей; выбранные в конфигурации — с их зависимостями. */
    static List<Module> plan(List<Module> all, Config cfg) {
        Map<String, Module> byName = new LinkedHashMap<>();
        for (Module m : all) byName.put(m.name(), m);
        for (String w : cfg.modules()) if (!byName.containsKey(w)) throw new IllegalArgumentException("нет модуля " + w);
        Set<String> want = new LinkedHashSet<>();
        for (Module m : all) if (cfg.wants(m.name())) addWithDeps(m.name(), byName, want, new LinkedHashSet<>());
        List<Module> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        while (out.size() < want.size()) {
            boolean progress = false;
            for (Module m : all) {
                if (!want.contains(m.name()) || done.contains(m.name())) continue;
                if (done.containsAll(m.requires())) {
                    out.add(m);
                    done.add(m.name());
                    progress = true;
                }
            }
            if (!progress) throw new IllegalStateException("цикл зависимостей модулей: " + want);
        }
        return out;
    }

    private static void addWithDeps(String n, Map<String, Module> byName, Set<String> want, Set<String> path) {
        if (want.contains(n)) return;
        if (!path.add(n)) throw new IllegalStateException("цикл зависимостей: " + path);
        Module m = byName.get(n);
        if (m == null) throw new IllegalArgumentException("нет модуля " + n);
        for (String r : m.requires()) addWithDeps(r, byName, want, path);
        want.add(n);
    }

    /**
     * Собрать выпуск набора по конфигурации; {@code inputs} — описание входов для манифеста
     * ({@link Dataset#readInputs}).
     */
    public static Result run(Dataset dataset, Config cfg, List<Map<String, Object>> inputs) throws IOException {
        Schema schema = dataset.schema();
        List<String> schemaErrors = schema.validate();
        if (!schemaErrors.isEmpty()) throw new IllegalStateException("описание схемы с ошибками: " + schemaErrors);
        Files.createDirectories(cfg.out());
        for (Table t : schema.tables()) Files.deleteIfExists(cfg.out().resolve(t.name() + ".tsv.gz"));
        Files.deleteIfExists(cfg.out().resolve("manifest.json"));
        // прежние тексты выпуска — тоже: проваленная самопроверка не должна оставить в папке старые SOURCE.md и DDL
        for (String f : Expected.TEXTS) Files.deleteIfExists(cfg.out().resolve(f));
        Files.deleteIfExists(cfg.out().resolve("SOURCE.md"));

        GenContext ctx = new GenContext(cfg, schema, dataset.gzipHeader());
        try {
            return run(dataset, cfg, schema, inputs, ctx, plan(dataset.modules(), cfg));
        } finally {
            ctx.integrity().close();
        }
    }

    private static Result run(Dataset dataset, Config cfg, Schema schema, List<Map<String, Object>> inputs,
                              GenContext ctx, List<Module> plan) throws IOException {
        Docs docs = dataset.docs();
        ctx.log("выпуск " + cfg.datasetVersion() + ", профиль " + cfg.profile().code() + ", seed " + cfg.seed()
                + ", модули " + plan.stream().map(Module::name).toList());
        List<String> planned = new ArrayList<>();
        for (Module m : plan) planned.addAll(m.tables());
        ctx.integrity().plan(planned);
        for (Module m : plan) {
            for (String t : m.tables()) schema.table(t);
            ctx.enter(m.name(), m.tables());
            long t0 = System.nanoTime();
            m.generate(ctx);
            for (String t : m.tables()) {
                TableSink s = ctx.sinks().get(t);
                if (s != null && !s.closed()) s.close();
            }
            StringBuilder sb = new StringBuilder();
            for (String t : m.tables()) {
                TableSink s = ctx.sinks().get(t);
                sb.append(' ').append(t).append('=').append(s == null ? "—" : s.rows());
            }
            ctx.log("модуль " + m.name() + ": " + (System.nanoTime() - t0) / 1_000_000 + " мс;" + sb);
        }

        CheckReport report = new CheckReport();
        for (Module m : plan) {
            for (String t : m.tables()) {
                report.expect("таблица " + t + " записана", ctx.sinks().containsKey(t), "модуль " + m.name());
            }
            long t0 = System.nanoTime();
            m.check(ctx, report);
            ctx.log("проверки " + m.name() + ": " + (System.nanoTime() - t0) / 1_000_000 + " мс");
        }
        ctx.integrity().report(report);
        ctx.log("проверки ключей и ссылок: " + ctx.integrity().stats());

        List<GzipTsvFile.Info> files = new ArrayList<>();
        for (Table t : schema.tables()) {
            TableSink s = ctx.sinks().get(t.name());
            if (s != null) files.add(s.close());
        }
        System.out.print(report.render());
        Map<String, Long> planted = ctx.planted().counts();
        if (!planted.isEmpty()) ctx.log("заложенные нарушения (в выпуск не пишутся): " + planted);
        if (!report.allOk()) return new Result(false, files, report, planted, ctx);

        Map<String, Object> manifest = manifest(cfg, plan, inputs, dataset.manifestFields(ctx), dataset.format(), files);
        write(cfg.out().resolve("manifest.json"), Json.write(manifest));
        write(cfg.out().resolve("schema-tables.sql"), SchemaExport.tablesSql(schema));
        write(cfg.out().resolve("schema-constraints.sql"), SchemaExport.constraintsSql(schema));
        write(cfg.out().resolve("schema.mmd"), SchemaExport.mermaid(schema));
        List<String> present = new ArrayList<>();
        for (GzipTsvFile.Info f : files) present.add(f.name().substring(0, f.name().length() - ".tsv.gz".length()));
        write(cfg.out().resolve("load-postgres.psql"), SchemaExport.loaderPsql(schema, present));
        write(cfg.out().resolve("SOURCE.md"), docs.sourceMd(schema, manifest));
        write(cfg.out().resolve("LICENSE"), docs.license());
        ctx.log("готово: " + cfg.out());
        return new Result(true, files, report, planted, ctx);
    }

    /**
     * Манифест: поля ядра (версия … входы), затем поля домена ({@link Dataset#manifestFields}), формат и файлы.
     * Поле домена не может заменить поле ядра.
     */
    static Map<String, Object> manifest(Config cfg, List<Module> plan, List<Map<String, Object>> inputs,
                                        Map<String, Object> domain, Map<String, Object> format,
                                        List<GzipTsvFile.Info> files) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", cfg.datasetVersion());
        m.put("generator", cfg.generatorVersion());
        m.put("seed", cfg.seed());
        m.put("profile", cfg.profile().code());
        m.put("scale", String.valueOf(cfg.profile().scale()));
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("from", cfg.period().from().toString());
        period.put("to", cfg.period().to().toString());
        period.put("now", cfg.period().now().toString());
        period.put("business_timezone", cfg.period().businessZone().getId());
        m.put("period", period);
        m.put("modules", plan.stream().map(Module::name).toList());
        m.put("inputs", inputs);
        for (Map.Entry<String, Object> e : domain.entrySet()) {
            if (m.containsKey(e.getKey()) || e.getKey().equals("format") || e.getKey().equals("files")) {
                throw new IllegalStateException("поле манифеста «" + e.getKey() + "» — поле ядра, домен его не задаёт");
            }
            m.put(e.getKey(), e.getValue());
        }
        m.put("format", new LinkedHashMap<>(format));
        List<Object> fl = new ArrayList<>();
        for (GzipTsvFile.Info f : files) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", f.name());
            e.put("table", f.name().substring(0, f.name().length() - ".tsv.gz".length()));
            e.put("rows", f.rows());
            e.put("bytes", f.bytes());
            e.put("sha256", f.sha256());
            e.put("sha256_tsv", f.sha256Tsv());
            fl.add(e);
        }
        m.put("files", fl);
        return m;
    }

    private static void write(Path p, String text) throws IOException {
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }
}
