package io.hammerhall.data.air;

import io.hammerhall.data.air.dump.DumpScan;
import io.hammerhall.data.air.dump.DumpSource;
import io.hammerhall.data.air.dump.PgValues;
import io.hammerhall.data.air.load.NetworkModule;
import io.hammerhall.data.air.load.SalesModule;
import io.hammerhall.data.air.select.Seasonality;
import io.hammerhall.data.air.select.Selection;
import io.hammerhall.data.core.gen.Config;
import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.io.GzipTsvFile;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.time.Period;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Набор {@code air-data-v1} — «Авиаперевозки»: контракт домена с ядром ({@link Dataset}). Генератор здесь — импорт:
 * вход — выгрузка демобазы Postgres Pro «Авиаперевозки» 2.0 ({@code demo-20250901-2y.sql.gz}, pg_dump текстом),
 * выход — те же таблицы, что у выпуска, собранного прежней сборкой на Python и DuckDB. Паспорт —
 * {@code air/dataset.properties}.
 *
 * <p>Два прохода по выгрузке: первый ({@link #readInputs}) — sha256, справочники, ключи билетов и перелётов; второй
 * (модуль продаж) — строки броней, билетов, перелётов и талонов отбора. Профиль {@code small} — подмножество броней
 * {@code full} ({@link Selection}); справочники и рейсы — целиком в любом профиле.
 */
public final class AirDataset implements Dataset {
    public static final String CODE = "air";
    public static final String GENERATOR_VERSION = "air-gen 0.1.0";
    /** Seed влияет только на выборку броней профиля меньше {@code full}. */
    public static final long DEFAULT_SEED = 20250901L;
    /**
     * Окно данных выгрузки: продажи — с 01.09.2025, рейсы — 01.10.2025 … 30.10.2027; «сейчас» — 01.09.2027 00:00 UTC
     * (рейсы до него прилетели или отменены, после — по расписанию). Окно задаёт выгрузка: {@code --to} не меняет данные
     * и не принимается.
     */
    public static final Period PERIOD = new Period(LocalDate.of(2025, 9, 1), LocalDate.of(2027, 10, 30),
            Instant.parse("2027-09-01T00:00:00Z"), ZoneId.of("UTC"));
    /** Выгрузка демобазы — снимок в выпуске {@link #DUMP_RELEASE}; sha256 — та же, что в паспорте. */
    public static final Input DUMP = new Input("dump", "demo-20250901-2y.sql.gz", null,
            "6aa9ca6313768b6b9ee8a6f1e13e902249c9d40fbad59938c7b6cc3dd90af471");
    public static final String DUMP_RELEASE = "air-source-20250901";
    public static final String SOURCE = "Postgres Pro demo database «Airlines» 2.0, demo-20250901-2y (730 days), MIT © 2025 Postgres Professional";

    private final DumpSource source = new DumpSource();

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String title() {
        return "Авиаперевозки";
    }

    @Override
    public String version() {
        return AirSchema.VERSION;
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
        return AirSchema.build();
    }

    @Override
    public List<Module> modules() {
        return List.of(new NetworkModule(source), new SalesModule(source));
    }

    @Override
    public Release.Docs docs() {
        return new AirDocs();
    }

    @Override
    public List<Input> inputs() {
        return List.of(DUMP);
    }

    /**
     * Первый проход по выгрузке ({@link DumpScan}) и сверка её sha256 с паспортом — в том же проходе. В паспорте суммы
     * нет (заглушка до выкладки снимка) или она другая: {@code strict} — ошибка, иначе — предупреждение.
     */
    @Override
    public List<Map<String, Object>> readInputs(Config cfg, boolean strict) throws IOException {
        if (!cfg.period().equals(PERIOD)) {
            throw new IllegalArgumentException("air: окно задаёт выгрузка, --to не поддерживается");
        }
        DumpScan s = source.scan(cfg.input(DUMP.name()));
        String have = s.result.sha256();
        String msg = DUMP.sha256() == null || DUMP.sha256().isEmpty()
                ? "в паспорте нет sha256 выгрузки (input.dump.sha256 — заглушка): сверить не с чем; у этой — " + have
                : !DUMP.sha256().equals(have) ? DUMP.file() + ": sha256 " + have + ", в паспорте " + DUMP.sha256() : null;
        if (msg != null) {
            if (strict) throw new IllegalStateException(msg);
            System.out.println("ВНИМАНИЕ: " + msg);
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("name", DUMP.file());
        in.put("release", DUMP_RELEASE);
        in.put("license", "MIT, Copyright (c) 2025 Postgres Professional");
        in.put("sha256", have);
        return List.of(in);
    }

    /**
     * Поля выпуска {@code air-data-v1} сверх полей ядра: источник, «сейчас», последняя продажа выгрузки, брони выгрузки
     * и оставленные моделью, модель отбора; у профиля меньше {@code full} — ещё его выборка.
     */
    @Override
    public Map<String, Object> manifestFields(GenContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", SOURCE);
        m.put("now", PERIOD.now().toString());
        DumpScan s = source.scanned();
        m.put("last_booking", s == null ? null : s.lastBooking);
        Selection.Stats st = ctx.has(NetworkModule.SELECTION) ? ctx.get(NetworkModule.SELECTION).stats() : null;
        m.put("bookings_total", st == null ? null : st.bookings());
        m.put("bookings_kept", st == null ? null : st.keptByModel());
        m.put("selection", Seasonality.manifest());
        if (ctx.profile() != Profile.FULL) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("unit", "booking");
            p.put("key", "unit(seed, 'air.small', fnv64(book_ref)) < scale, among bookings kept by selection");
            p.put("scale", String.valueOf(ctx.profile().scale()));
            p.put("bookings", st == null ? null : st.inProfile());
            m.put("profile_sample", p);
        }
        return m;
    }

    /** Формат выпуска {@code air-data-v1}; время — по {@link PgValues#ROUNDING}. */
    @Override
    public Map<String, Object> format() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("encoding", "UTF-8");
        f.put("delimiter", "TAB");
        f.put("header", true);
        f.put("null", "empty field");
        f.put("time", PgValues.timeFormatNote());
        f.put("boolean", "t / f");
        f.put("money", "decimal string, 2 digits, as in the source");
        f.put("compression", "gzip, mtime=0");
        return f;
    }

    /** Как у выпуска {@code air-data-v1} (CPython, gzip 9): при том же содержимом совпадают и байты файлов. */
    @Override
    public int gzipLevel() {
        return 9;
    }

    @Override
    public GzipTsvFile.Header gzipHeader() {
        return GzipTsvFile.Header.CPYTHON;
    }
}
