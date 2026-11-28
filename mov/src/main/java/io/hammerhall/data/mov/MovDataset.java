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
import io.hammerhall.data.mov.build.Places;
import io.hammerhall.data.mov.build.Rank;
import io.hammerhall.data.mov.build.Rules;
import io.hammerhall.data.mov.site.SiteModule;
import io.hammerhall.data.mov.source.CitiesSource;
import io.hammerhall.data.mov.source.SnapshotSource;
import io.hammerhall.data.mov.source.SourceData;
import io.hammerhall.data.mov.source.WorldCities;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Набор {@code mov-data-v1} — «Кино»: контракт домена с ядром ({@link Dataset}). Генератор — импорт: вход — снимок
 * Wikidata {@code mov-source-YYYYMMDD.zip} (v2: с файлами съёмочной группы, студий, серий, наград, выхода по странам,
 * дат жизни) из выпуска этого репозитория, выход — таблицы {@link MovSchema}.
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
    public static final String SNAPSHOT = "mov-source-20261125";
    public static final Input SOURCE = new Input("source", SNAPSHOT + ".zip", null,
            "ec6626eeb3725a98375195306ee36f935e7df915e77aaf46d92cba74252ab762");
    /** Дата снимка (из его имени); фильмы — с {@link Rules#FIRST_YEAR} года до года снимка. */
    public static final LocalDate SNAPSHOT_DATE = LocalDate.parse(SNAPSHOT.substring("mov-source-".length()),
            DateTimeFormatter.BASIC_ISO_DATE);
    /**
     * Окно выпуска — синтетики «Свартальвхейм» (общее окно связанных наборов): кино из снимка от окна не зависит; {@code --to} меняет
     * только конец окна и срез синтетики.
     */
    public static final Period PERIOD = Period.common(Period.MOSCOW);
    /**
     * Второй вход — города набора «Мир» ({@code cities.tsv.gz} выпуска {@code wld-data-v1}): город места (P19, P915,
     * P840, P159) — по GeoNames ID или ближайший той же страны. Сумма содержимого — та же, что в паспорте
     * ({@code input.cities.sha256_tsv}); нули — заглушка, сверить не с чем.
     */
    public static final Input CITIES = new Input("cities", WorldCities.FILE,
            "1536ea827a88fca5692e974d3314bbe296b55a03d6ae692528e22e012e9e792f", null);
    /** Выпуск, из которого города. */
    public static final String CITIES_RELEASE = "wld-data-v1";
    /** Источник и лицензия — для манифеста. */
    public static final String SOURCES = "Wikidata (CC0 1.0)";
    /** Лицензия городов «Мира» (названия и координаты — GeoNames). */
    public static final String CITIES_LICENSE = "GeoNames (CC BY 4.0), via wld-data-v1";

    private final SnapshotSource source = new SnapshotSource();
    private final CitiesSource cities = new CitiesSource();
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

    /** Города «Мира» этого набора (после {@link #readInputs} — прочитаны). */
    public CitiesSource cities() {
        return cities;
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
        return List.of(new CinemaModule(source, cities, exclusions), SiteModule.ofCinema(source));
    }

    @Override
    public Release.Docs docs() {
        return new MovDocs(source);
    }

    @Override
    public List<Input> inputs() {
        return List.of(SOURCE, CITIES);
    }

    /**
     * Снимок: sha256 файла — против паспорта (в паспорте заглушка или другая сумма: {@code strict} — ошибка, иначе —
     * предупреждение), затем разбор ({@link SourceData#read}). Имя снимка в манифесте — из его {@code fetch.json}, не
     * из имени файла: тождество выпуска зависит только от содержимого. Города «Мира» — sha256 содержимого против
     * паспорта ({@link #CITIES}; так же: заглушка или другая сумма — со {@code strict} ошибка, иначе предупреждение).
     */
    @Override
    public List<Map<String, Object>> readInputs(Config cfg, boolean strict) throws IOException {
        if (!cfg.period().from().equals(PERIOD.from()) || !cfg.period().now().equals(PERIOD.now())
                || !cfg.period().businessZone().equals(PERIOD.businessZone())) {
            throw new IllegalArgumentException("mov: начало окна, «сейчас» и пояс — постоянные, меняется только конец (--to)");
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

        WorldCities w = cities.load(cfg.input(CITIES.name()));
        String cmsg = CITIES.sha256Tsv().matches("0+")
                ? "в паспорте нет sha256_tsv городов (input.cities.sha256_tsv — заглушка): сверить не с чем; у этих — " + w.sha256Tsv()
                : !CITIES.sha256Tsv().equals(w.sha256Tsv())
                ? CITIES.file() + ": sha256_tsv " + w.sha256Tsv() + ", в паспорте " + CITIES.sha256Tsv() + " — не " + CITIES_RELEASE : null;
        if (cmsg != null) {
            if (strict) throw new IllegalStateException(cmsg);
            System.out.println("ВНИМАНИЕ: " + cmsg);
        }
        Map<String, Object> ci = new LinkedHashMap<>();
        ci.put("name", CITIES.file());
        ci.put("release", CITIES_RELEASE);
        ci.put("license", CITIES_LICENSE);
        ci.put("rows", (long) w.size());
        ci.put("sha256_tsv", w.sha256Tsv());
        return List.of(in, ci);
    }

    /**
     * Поля выпуска сверх полей ядра: источник, отбор профиля, рейтинг, пол актёров и людей по путям, год и название
     * фильмов по путям.
     */
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
        Map<String, Object> pg = new LinkedHashMap<>();
        if (c != null) {
            pg.put("p21", c.peopleGenderPaths().get(Names.GenderPath.P21));
            pg.put("given_name", c.peopleGenderPaths().get(Names.GenderPath.GIVEN_NAME));
            pg.put("seeded", c.peopleGenderPaths().get(Names.GenderPath.SEEDED));
            pg.put("null_other_p21", c.peopleGenderPaths().get(Names.GenderPath.NOT_TAKEN));
        }
        m.put("people_gender", pg);
        Map<String, Object> y = new LinkedHashMap<>();
        if (c != null) for (Rules.YearPath p : Rules.YearPath.values()) y.put(p.name().toLowerCase(Locale.ROOT), c.yearPaths().get(p));
        m.put("movies_year", y);
        Map<String, Object> t = new LinkedHashMap<>();
        if (c != null) for (Rules.TitlePath p : Rules.TitlePath.values()) t.put(p.name().toLowerCase(Locale.ROOT), c.titlePaths().get(p));
        m.put("movies_title", t);
        Map<String, Object> pc = new LinkedHashMap<>();
        if (c != null) for (Places.CityPath p : Places.CityPath.values()) pc.put(p.name().toLowerCase(Locale.ROOT), c.placePaths().get(p));
        m.put("places_city", pc);
        Map<String, Object> pe = new LinkedHashMap<>();
        if (c != null) for (Places.Excluded e : Places.Excluded.values()) pe.put(e.name().toLowerCase(Locale.ROOT), c.placesExcluded().get(e));
        m.put("places_excluded", pe);
        Map<String, Object> dm = new LinkedHashMap<>();
        if (c != null) {
            dm.put("movies", c.documentaries().movies());
            dm.put("roles_not_taken", c.documentaries().rolesNotTaken());
            dm.put("actors_dropped", c.documentaries().actorsDropped());
            dm.put("people_dropped", c.documentaries().peopleDropped());
        }
        m.put("documentaries", dm);
        Map<String, Object> site = SiteModule.manifest(ctx);
        if (site != null) m.put("synthetic", site);
        return m;
    }

    /** Формат ядра; моменты синтетики — секунды Unix, деньги — рубли, даты — ISO 8601. */
    @Override
    public Map<String, Object> format() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("encoding", "UTF-8");
        f.put("delimiter", "TAB");
        f.put("header", true);
        f.put("null", "empty field");
        f.put("time", "Unix seconds, UTC");
        f.put("date", "ISO 8601, YYYY-MM-DD");
        f.put("decimal", "point, no trailing zeros");
        f.put("money", "decimal string, 2 digits, rubles");
        f.put("boolean", "t / f");
        f.put("compression", "gzip, mtime=0");
        return f;
    }
}
