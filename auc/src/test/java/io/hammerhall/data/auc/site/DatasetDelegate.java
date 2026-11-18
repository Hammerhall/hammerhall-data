package io.hammerhall.data.auc.site;

import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.gen.Config;
import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.time.Period;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Набор-обёртка: всё — как у {@code inner}, кроме списка модулей (его задаёт наследник). Для тестов модуля сайта. */
abstract class DatasetDelegate implements Dataset {
    final Dataset inner;

    DatasetDelegate(Dataset inner) {
        this.inner = inner;
    }

    @Override
    public String code() {
        return inner.code();
    }

    @Override
    public String title() {
        return inner.title();
    }

    @Override
    public String version() {
        return inner.version();
    }

    @Override
    public String generatorVersion() {
        return inner.generatorVersion();
    }

    @Override
    public long defaultSeed() {
        return inner.defaultSeed();
    }

    @Override
    public Period period() {
        return inner.period();
    }

    @Override
    public Schema schema() {
        return inner.schema();
    }

    @Override
    public List<Aggregate> aggregates() {
        return List.of();
    }

    @Override
    public Release.Docs docs() {
        return inner.docs();
    }

    @Override
    public List<Input> inputs() {
        return inner.inputs();
    }

    @Override
    public List<Map<String, Object>> readInputs(Config cfg, boolean strict) throws IOException {
        return inner.readInputs(cfg, strict);
    }

    @Override
    public Map<String, Object> format() {
        return inner.format();
    }

    @Override
    public Map<String, Object> manifestFields(GenContext ctx) {
        return inner.manifestFields(ctx);
    }
}
