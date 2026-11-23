package io.hammerhall.data.wld;

import io.hammerhall.data.core.gen.Config;
import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.wld.build.Rules;
import io.hammerhall.data.wld.build.World;
import io.hammerhall.data.wld.build.WorldModule;
import io.hammerhall.data.wld.source.SnapshotSource;
import io.hammerhall.data.wld.source.SourceData;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Набор {@code wld-data-v1} — «Мир»: контракт домена с ядром ({@link Dataset}). Генератор — импорт: вход — снимок
 * открытых источников {@code wld-source-YYYYMMDD.zip} (GeoNames, World Bank, Unicode CLDR, Wikidata) из выпуска этого
 * репозитория, выход — пять таблиц ({@link WldSchema}). Случайности нет: выпуск — функция снимка и профиля; профиль
 * меняет только порог населения городов ({@link Rules#minPopulation}). Паспорт — {@code wld/dataset.properties}.
 */
public final class WldDataset implements Dataset {
    public static final String CODE = "wld";
    public static final String GENERATOR_VERSION = "wld-gen 0.1.0";
    /** Случайности в наборе нет: seed в манифесте есть (как у всех наборов), на содержимое не влияет. */
    public static final long DEFAULT_SEED = 20261123L;
    /** Снимок источников: выпуск этого репозитория; файл и sha256 — те же, что в паспорте. */
    public static final String SNAPSHOT = "wld-source-20261123";
    public static final Input SOURCE = new Input("source", SNAPSHOT + ".zip", null,
            "c1dbea75bb4d06b11724ddb7e27135d3cf4667867a74e78fc99e635342babac9");
    /** Дата снимка (из его имени) — конец окна и «сейчас» выпуска; данные — с 1960 года (ряды World Bank). */
    public static final LocalDate SNAPSHOT_DATE = LocalDate.parse(SNAPSHOT.substring("wld-source-".length()),
            DateTimeFormatter.BASIC_ISO_DATE);
    public static final Period PERIOD = new Period(LocalDate.of(World.FIRST_YEAR, 1, 1), SNAPSHOT_DATE,
            SNAPSHOT_DATE.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));
    /** Источники и лицензии — для манифеста. */
    public static final String SOURCES = "GeoNames (CC BY 4.0), World Bank Open Data (CC BY 4.0), Unicode CLDR 49 "
            + "(Unicode License v3), Wikidata (CC0)";

    private final SnapshotSource source = new SnapshotSource();
    private final Exclusions exclusions;

    public WldDataset() {
        this(Exclusions.DEFAULT);
    }

    /** Набор с другими списками исключений — для тестов. */
    public WldDataset(Exclusions exclusions) {
        this.exclusions = exclusions;
    }

    public Exclusions exclusions() {
        return exclusions;
    }

    /** Снимок этого набора (после {@link #readInputs} — прочитан). */
    public SnapshotSource source() {
        return source;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String title() {
        return "Мир";
    }

    @Override
    public String version() {
        return WldSchema.VERSION;
    }

    @Override
    public String generatorVersion() {
        return GENERATOR_VERSION;
    }

    @Override
    public long defaultSeed() {
        return DEFAULT_SEED;
    }

    @Override
    public Period period() {
        return PERIOD;
    }

    @Override
    public Schema schema() {
        return WldSchema.build();
    }

    @Override
    public List<Module> modules() {
        return List.of(new WorldModule(source, exclusions));
    }

    @Override
    public Release.Docs docs() {
        return new WldDocs(source);
    }

    @Override
    public List<Input> inputs() {
        return List.of(SOURCE);
    }

    /**
     * Снимок: sha256 файла — против паспорта (в паспорте заглушка или другая сумма: {@code strict} — ошибка, иначе —
     * предупреждение), затем разбор ({@link SourceData#read}). Имя снимка в манифесте — из его {@code fetch.json}, не
     * из имени файла: тождество выпуска зависит только от содержимого.
     */
    @Override
    public List<Map<String, Object>> readInputs(Config cfg, boolean strict) throws IOException {
        if (!cfg.period().equals(PERIOD)) {
            throw new IllegalArgumentException("wld: окно задаёт снимок источников, --to не поддерживается");
        }
        SourceData d = source.load(cfg.input(SOURCE.name()));
        String have = source.sha256();
        String msg = SOURCE.sha256().matches("0+")
                ? "в паспорте нет sha256 снимка (input.source.sha256 — заглушка): сверить не с чем; у этого — " + have
                : !SOURCE.sha256().equals(have) ? SOURCE.file() + ": sha256 " + have + ", в паспорте " + SOURCE.sha256() : null;
        if (msg != null) {
            if (strict) throw new IllegalStateException(msg);
            System.out.println("ВНИМАНИЕ: " + msg);
        }
        if (!SNAPSHOT.equals(d.snapshot())) {
            String m = "снимок " + d.snapshot() + " (fetch.json), а набор собирается из " + SNAPSHOT;
            if (strict) throw new IllegalStateException(m);
            System.out.println("ВНИМАНИЕ: " + m);
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("name", d.snapshot() + ".zip");
        in.put("release", d.snapshot());
        in.put("fetched_at", d.fetchedAt());
        in.put("license", SOURCES);
        in.put("sha256", have);
        return List.of(in);
    }

    /** Поля выпуска сверх полей ядра: источники, порог городов профиля, последний год рядов World Bank. */
    @Override
    public Map<String, Object> manifestFields(GenContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", SOURCES);
        World w = ctx.has(WorldModule.WORLD) ? ctx.get(WorldModule.WORLD) : null;
        Map<String, Object> cities = new LinkedHashMap<>();
        cities.put("min_population", Rules.minPopulation(ctx.profile()));
        cities.put("capitals", "all capitals, whatever their population");
        m.put("cities", cities);
        m.put("world_bank_years", w == null || w.lastYear() == null ? null
                : World.FIRST_YEAR + "-" + w.lastYear());
        return m;
    }

    /** Формат ядра; дат и моментов в наборе нет, деньги — миллионы долларов США. */
    @Override
    public Map<String, Object> format() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("encoding", "UTF-8");
        f.put("delimiter", "TAB");
        f.put("header", true);
        f.put("null", "empty field");
        f.put("decimal", "point, no trailing zeros");
        f.put("money", "millions of current US dollars, 2 digits, half-even");
        f.put("boolean", "t / f");
        f.put("compression", "gzip, mtime=0");
        return f;
    }
}
