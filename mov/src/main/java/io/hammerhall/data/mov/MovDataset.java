package io.hammerhall.data.mov;

import io.hammerhall.data.core.gen.Config;
import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.mov.build.Cinema;
import io.hammerhall.data.mov.build.CinemaModule;
import io.hammerhall.data.mov.build.Names;
import io.hammerhall.data.mov.build.Rank;
import io.hammerhall.data.mov.build.Rules;
import io.hammerhall.data.mov.source.SnapshotSource;
import io.hammerhall.data.mov.source.SourceData;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Набор {@code mov-data-v1} — «Кино»: контракт домена с ядром ({@link Dataset}). Генератор — импорт: вход — снимок
 * Wikidata {@code mov-source-YYYYMMDD.zip} из выпуска этого репозитория, выход — десять таблиц ({@link MovSchema}).
 * Случайность — только в рейтинге фильма ({@link Rank}) и в поле «пол» там, где источник молчит ({@link Names#seeded}):
 * от seed набора и id. Профиль — доля самых известных фильмов ({@link Rules#profileSize}). Паспорт —
 * {@code mov/dataset.properties}.
 */
public final class MovDataset implements Dataset {
    public static final String CODE = "mov";
    public static final String GENERATOR_VERSION = "mov-gen 0.1.0";
    /** seed набора: рейтинг и пол там, где источник молчит, — от него и id. */
    public static final long DEFAULT_SEED = 20261123L;
    /** Снимок источников: выпуск этого репозитория; файл и sha256 — те же, что в паспорте. */
    public static final String SNAPSHOT = "mov-source-20261123";
    public static final Input SOURCE = new Input("source", SNAPSHOT + ".zip", null,
            "4bf07971ce78c22a9bd67338e6405acfd5cf0898a35dcf4d05add1c1890c1c4d");
    /** Дата снимка (из его имени) — конец окна и «сейчас» выпуска; фильмы — с {@link Rules#FIRST_YEAR} года. */
    public static final LocalDate SNAPSHOT_DATE = LocalDate.parse(SNAPSHOT.substring("mov-source-".length()),
            DateTimeFormatter.BASIC_ISO_DATE);
    public static final Period PERIOD = new Period(LocalDate.of(Rules.FIRST_YEAR, 1, 1), SNAPSHOT_DATE,
            SNAPSHOT_DATE.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));
    /** Источник и лицензия — для манифеста. */
    public static final String SOURCES = "Wikidata (CC0 1.0)";

    private final SnapshotSource source = new SnapshotSource();
    private final Exclusions exclusions;

    public MovDataset() {
        this(Exclusions.DEFAULT);
    }

    /** Набор с другими списками исключений — для тестов. */
    public MovDataset(Exclusions exclusions) {
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
        return "Кино";
    }

    @Override
    public String version() {
        return MovSchema.VERSION;
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
        return MovSchema.build();
    }

    @Override
    public List<Module> modules() {
        return List.of(new CinemaModule(source, exclusions));
    }

    @Override
    public Release.Docs docs() {
        return new MovDocs(source);
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
            throw new IllegalArgumentException("mov: окно задаёт снимок источников, --to не поддерживается");
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

    /** Поля выпуска сверх полей ядра: источник, отбор профиля, рейтинг, пол актёров по путям. */
    @Override
    public Map<String, Object> manifestFields(GenContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", SOURCES);
        Cinema c = ctx.has(CinemaModule.CINEMA) ? ctx.get(CinemaModule.CINEMA) : null;
        Map<String, Object> sel = new LinkedHashMap<>();
        sel.put("order", "popularity desc, then id");
        sel.put("share", switch (ctx.profile()) {
            case SMALL -> "1 %, rounded up";
            case MEDIUM -> "10 %, rounded up";
            case FULL -> "all";
        });
        sel.put("movies_full", c == null ? null : (long) c.moviesFull());
        sel.put("movies", c == null ? null : (long) c.movies().size());
        m.put("selection", sel);
        Map<String, Object> rank = new LinkedHashMap<>();
        rank.put("kind", "synthetic: popularity signals of Wikidata and seeded noise, not a rating of the film");
        rank.put("popularity", "ln(1+sitelinks) + 0.5 ln(1+awards) + 0.25 ln(1+nominations) + 0.1 ln(1+cast in full)");
        rank.put("score", "0.6 z(popularity) + 0.4 N(0,1) noise of stream " + Rank.NOISE_STREAM + " by movie id");
        rank.put("scale", "quantile (i+0.5)/N of score over full, piecewise-linear inverse CDF, half-even to 0.1");
        rank.put("same_in_all_profiles", true);
        m.put("rank", rank);
        Map<String, Object> g = new LinkedHashMap<>();
        if (c != null) {
            g.put("p21", c.genderPaths().get(Names.GenderPath.P21));
            g.put("given_name", c.genderPaths().get(Names.GenderPath.GIVEN_NAME));
            g.put("seeded", c.genderPaths().get(Names.GenderPath.SEEDED));
            g.put("seeded_female_share", c.femaleShare() == null ? null : c.femaleShare().toPlainString());
            g.put("not_taken_other_p21", c.genderPaths().get(Names.GenderPath.NOT_TAKEN));
        }
        m.put("actors_gender", g);
        return m;
    }

    /** Формат ядра; моментов в наборе нет, даты — ISO 8601. */
    @Override
    public Map<String, Object> format() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("encoding", "UTF-8");
        f.put("delimiter", "TAB");
        f.put("header", true);
        f.put("null", "empty field");
        f.put("date", "ISO 8601, YYYY-MM-DD");
        f.put("decimal", "point, no trailing zeros");
        f.put("compression", "gzip, mtime=0");
        return f;
    }
}
