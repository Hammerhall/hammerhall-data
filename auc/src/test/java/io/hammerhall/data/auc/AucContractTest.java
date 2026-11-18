package io.hammerhall.data.auc;

import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.DatasetContract;

/** Общие тесты набора (паспорт, схема, small-выпуск, внутренние имена) — для набора «Дом Одина». */
class AucContractTest extends DatasetContract {
    @Override
    protected Dataset dataset() {
        return new AucDataset();
    }
}
