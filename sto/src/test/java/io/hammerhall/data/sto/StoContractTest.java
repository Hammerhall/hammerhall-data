package io.hammerhall.data.sto;

import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.DatasetContract;

/** Общие тесты набора (паспорт, схема, small-выпуск, внутренние имена) — для набора «Кременъ». */
class StoContractTest extends DatasetContract {
    @Override
    protected Dataset dataset() {
        return new StoDataset();
    }
}
