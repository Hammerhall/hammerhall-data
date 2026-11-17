package io.hammerhall.data.air;

import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.DatasetContract;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Общие тесты набора (паспорт, схема, small-выпуск, внутренние имена) — для «Авиаперевозок». Вход — настоящая
 * выгрузка, если она есть ({@code AIR_DUMP}, {@code -Dair.dump}, {@code air/work/}), иначе — крошечная из
 * {@link TinyDump}: так тесты идут и без 1,1 ГБ.
 */
class AirContractTest extends DatasetContract {
    static {
        try {
            TinyDump.useIfNoDump();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected Dataset dataset() {
        return new AirDataset();
    }
}
