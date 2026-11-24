package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.Config;
import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.mov.MovDataset;
import io.hammerhall.data.mov.MovSchema;
import io.hammerhall.data.mov.site.text.SiteTexts;
import io.hammerhall.data.mov.site.text.StubTexts;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Набор для тестов синтетики без снимка: схема — та же ({@link MovSchema}), модули — «кино» из выдуманных фильмов
 * ({@link StubInput}: пишет только {@code movies}, без бюджета и сборов) и {@code site} на них. Окно — как у набора.
 */
final class SiteOnlyDataset implements Dataset {
    final SiteInput input;
    final SiteTexts texts;
    SiteModule site;

    SiteOnlyDataset(SiteInput input) {
        this(input, new StubTexts());
    }

    SiteOnlyDataset(SiteInput input, SiteTexts texts) {
        this.input = input;
        this.texts = texts;
    }

    /** Выпуск профиля в папку; {@code to} — конец окна или {@code null}. */
    static Release.Result run(SiteOnlyDataset d, String profile, Path out, String to) throws IOException {
        Map<String, String> opt = new LinkedHashMap<>();
        opt.put("profile", profile);
        opt.put("out", out.toString());
        if (to != null) opt.put("to", to);
        return Cli.run(d, opt);
    }

    @Override
    public String code() {
        return "mov";
    }

    @Override
    public String title() {
        return "Кино (тест синтетики)";
    }

    @Override
    public String version() {
        return MovSchema.VERSION;
    }

    @Override
    public String generatorVersion() {
        return "mov-gen test";
    }

    @Override
    public long defaultSeed() {
        return MovDataset.DEFAULT_SEED;
    }

    @Override
    public Period period() {
        return MovDataset.PERIOD;
    }

    @Override
    public Schema schema() {
        return MovSchema.build();
    }

    @Override
    public List<Module> modules() {
        site = new SiteModule(List.of("cinema"), ctx -> input, texts);
        return List.of(new MoviesOnly(input), site);
    }

    @Override
    public Release.Docs docs() {
        return new Release.Docs() {
            @Override
            public String sourceMd(Schema schema, Map<String, Object> manifest) {
                return "# тест синтетики\n";
            }

            @Override
            public String license() {
                return "test\n";
            }
        };
    }

    @Override
    public List<Input> inputs() {
        return List.of();
    }

    @Override
    public List<Map<String, Object>> readInputs(Config cfg, boolean strict) {
        return List.of();
    }

    /** «Кино» из выдуманных фильмов: только таблица {@code movies}. */
    static final class MoviesOnly implements Module {
        private final SiteInput input;

        MoviesOnly(SiteInput input) {
            this.input = input;
        }

        @Override
        public String name() {
            return "cinema";
        }

        @Override
        public List<String> tables() {
            return List.of("movies");
        }

        @Override
        public void generate(GenContext ctx) throws IOException {
            TableSink s = ctx.sink("movies");
            for (SiteInput.Movie m : input.movies()) {
                s.add(m.id(), m.title(), m.titleRu(), (short) m.year(), m.releaseDate(), m.runtime(),
                        BigDecimal.valueOf(m.rank()).setScale(1, RoundingMode.HALF_EVEN), null, null);
            }
            ctx.close("movies");
        }
    }
}
