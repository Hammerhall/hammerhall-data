package io.hammerhall.data.sto.stock;

import java.time.LocalDate;

/**
 * Источник расхода для движка остатков — то, что сутки за сутками резервирует, отбирает и возвращает товар. Сейчас
 * это {@link DemandDriver} (синтетический спрос); потом — модуль заказов: он реализует этот интерфейс или, что
 * проще, сам ведёт цикл суток вокруг {@link StockEngine} (см. javadoc движка) — движок от этого не меняется.
 */
public interface DemandSource extends StockEngine.Listener {
    /** Прогноз, по которому движок пополняет склады. */
    Forecast forecast();

    /**
     * Сутки {@code d}: все вызовы движка — с моментами из {@code [dayStart, dayEnd)} по неубыванию. Движок уже в
     * {@link StockEngine#beginDay}, после возврата вызывающий закроет сутки {@link StockEngine#endDay}.
     */
    void day(LocalDate d, long dayStart, long dayEnd, StockEngine engine);
}
