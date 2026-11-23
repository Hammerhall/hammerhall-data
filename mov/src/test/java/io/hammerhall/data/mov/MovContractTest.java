package io.hammerhall.data.mov;

import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.DatasetContract;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Общие тесты набора (паспорт, схема, small-выпуск, внутренние имена) — для «Кино». Вход — настоящий снимок, если он
 * есть ({@code MOV_SOURCE}, {@code -Dmov.source}, {@code mov/work/}), иначе — крошечный из {@link TinySnapshot}: так
 * тесты идут и без снимка.
 */
class MovContractTest extends DatasetContract {
    static {
        try {
            TinySnapshot.useIfNoSnapshot();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected Dataset dataset() {
        return new MovDataset();
    }
}
