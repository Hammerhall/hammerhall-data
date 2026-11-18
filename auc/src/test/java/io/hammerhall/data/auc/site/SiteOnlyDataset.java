package io.hammerhall.data.auc.site;

import io.hammerhall.data.auc.AucDataset;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.Config;
import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.time.Period;

import java.util.List;
import java.util.Map;

/**
 * Набор из одного модуля сайта на упрощённом мире ({@link StubWorld}) — для тестов и замеров скорости модуля (схема —
 * четыре таблицы модуля и упрощённые таблицы-ссылки, {@link SiteTables.schema()}). Замер: {@code main(профиль, потоков,
 * папка[, --to дата])}.
 */
final class SiteOnlyDataset implements Dataset {
    private final int threads;

    SiteOnlyDataset(int threads) {
        this.threads = threads;
    }

    @Override
    public String code() {
        return "auc";
    }

    @Override
    public String title() {
        return AucDataset.HOUSE_NAME;
    }

    @Override
    public String version() {
        return AucDataset.VERSION;
    }

    @Override
    public String generatorVersion() {
        return AucDataset.GENERATOR_VERSION;
    }

    @Override
    public long defaultSeed() {
        return AucDataset.DEFAULT_SEED;
    }

    @Override
    public Period period() {
        return AucDataset.PERIOD;
    }

    @Override
    public Schema schema() {
        return SiteTables.schema();
    }

    @Override
    public List<Module> modules() {
        return List.of(new SiteModule(List.of(),
                ctx -> StubWorld.build(ctx.config().seed(), ctx.profile().scale(), SiteModule.cutoff(ctx)), threads));
    }

    @Override
    public Release.Docs docs() {
        return new Release.Docs() {
            @Override
            public String sourceMd(Schema schema, Map<String, Object> manifest) {
                return SiteDocs.SOURCE_MD;
            }

            @Override
            public String license() {
                return "MIT";
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

    /** Замер: {@code профиль потоков папка [дата --to]}. */
    public static void main(String[] a) throws Exception {
        String profile = a.length > 0 ? a[0] : "small";
        int threads = a.length > 1 ? Integer.parseInt(a[1]) : 0;
        String out = a.length > 2 ? a[2] : "site-" + profile;
        java.util.List<String> args = new java.util.ArrayList<>(List.of("generate", "--profile", profile, "--out", out));
        if (a.length > 3) {
            args.add("--to");
            args.add(a[3]);
        }
        System.exit(Cli.exec(new SiteOnlyDataset(threads), args.toArray(new String[0])));
    }
}
