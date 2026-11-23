package io.hammerhall.data.wld;

import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.DatasetContract;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Общие тесты набора (паспорт, схема, small-выпуск, внутренние имена) — для «Мира». Вход — настоящий снимок
 * источников, если он есть ({@code WLD_SOURCE}, {@code -Dwld.source}, {@code wld/work/}), иначе — крошечный из
 * {@link TinySnapshot}: так тесты идут и без снимка.
 */
class WldContractTest extends DatasetContract {
    static {
        try {
            TinySnapshot.useIfNoSnapshot();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected Dataset dataset() {
        return new WldDataset();
    }
}
