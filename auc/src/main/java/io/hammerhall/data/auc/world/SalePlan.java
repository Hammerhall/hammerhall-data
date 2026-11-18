package io.hammerhall.data.auc.world;

import java.util.List;

/**
 * Торги до молотка (в цикле, подготовка торгов → торги, сайт): каталог — лоты с эстимейтом, резервом, стартовой и местом в волне
 * или сессии, и сессии живых торгов. {@code lots.id} здесь нет — его выдаёт оркестр торгов по
 * моменту публикации; лот узнаётся по ключу ({@link Keys#lot}).
 *
 * @param sale     торги
 * @param lots     лоты по номеру, затем букве
 * @param sessions сессии живых торгов по номеру; у онлайн — пусто
 */
public record SalePlan(Calendar.Sale sale, List<Lot> lots, List<Session> sessions) {
    public SalePlan {
        lots = List.copyOf(lots);
        sessions = List.copyOf(sessions);
    }

    /**
     * Лот каталога. Деньги — копейки, моменты — секунды Unix (0 — нет).
     *
     * @param key           {@link Keys#lot}
     * @param lotNo         номер в каталоге
     * @param label         «123», «123A» (добавлен после публикации)
     * @param itemId        {@code items.id}
     * @param consignmentId {@code consignments.id}
     * @param consignorId   {@code clients.id} комитента (правило T12 «свой лот»)
     * @param offeringNo    1 — впервые в договоре, 2–3 — повторно
     * @param previousLotKey прежнее выставление этой вещи (ключ); 0 — нет
     * @param sessionNo     живые: сессия; онлайн — 0
     * @param publishedAt   в каталоге
     * @param lowKop        нижняя оценка
     * @param highKop       верхняя оценка
     * @param startKop      стартовая цена (на сетке)
     * @param reserveKop    резерв; 0 — без резерва (заложенное {@code reserve_above_low} — выше нижней оценки)
     * @param opensAt       онлайн — открытие ставок; живые — 0 (слот — у оркестра по темпу сессии)
     * @param closesAtPlanned онлайн — место в волне (план конца); живые — 0
     * @param withdrawnAt   снят до торгов; 0 — нет
     * @param withdrawnBy   {@code consignor}, {@code house}; {@code null} — не снят
     */
    public record Lot(long key, int lotNo, String label, int itemId, int consignmentId, int consignorId,
                      int offeringNo, long previousLotKey, int sessionNo, long publishedAt, long lowKop, long highKop,
                      long startKop, long reserveKop, long opensAt, long closesAtPlanned, long withdrawnAt,
                      String withdrawnBy) {
    }

    /**
     * Сессия живых торгов.
     *
     * @param no          номер с 1
     * @param startsAt    начало
     * @param firstLotNo  первый лот
     * @param lastLotNo   последний лот (не больше 250 лотов в сессии)
     * @param auctioneerId {@code staff.id}
     * @param lotsPerHour темп: 75 общие, 40 дорогие (нижняя оценка от 1 млн ₽), 110 недорогие
     */
    public record Session(int no, long startsAt, int firstLotNo, int lastLotNo, int auctioneerId, int lotsPerHour) {
    }
}
