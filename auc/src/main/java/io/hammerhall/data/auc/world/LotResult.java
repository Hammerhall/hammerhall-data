package io.hammerhall.data.auc.world;

/**
 * Итог лота (в цикле, торги → подготовка торгов; в мир — через {@link AuctionWorld}): продан или нет, продажа после торгов,
 * отказ покупателя и второй шанс. Решение «повтор или возврат» по нему принимает
 * подготовка торгов. Деньги — копейки, моменты — секунды Unix (0 — нет).
 *
 * <p>В цикле (подготовке торгов) — моделируемый итог без среза (горизонт 180 суток): продан, не продан, снят, отказ на 31-й день
 * и второй шанс — даже если это после среза (решение «повтор или возврат» от среза не зависит). В мире
 * ({@link AuctionWorld#lot}, {@link AuctionWorld#lotsOfSale}) — состояние на срез: {@code OPEN}, если лот не закрыт до
 * среза; поля событий после среза — 0.
 *
 * @param lotKey              {@link Keys#lot}
 * @param lotId               {@code lots.id}
 * @param saleId              {@code sales.id}
 * @param itemId              {@code items.id}
 * @param consignmentId       {@code consignments.id}
 * @param dept                отдел
 * @param format              формат торгов
 * @param offeringNo          выставление в договоре: 1–3
 * @param result              итог на «сейчас» ({@code OPEN} — лот не закрыт к срезу)
 * @param priceKop            молоток, цена продажи после торгов; 0 — не продан
 * @param lowKop              нижняя оценка
 * @param highKop             верхняя оценка
 * @param reserveKop          резерв; 0 — без резерва
 * @param closedAt            закрыт (молоток или конец онлайн-лота); 0 — не закрыт
 * @param buyerRegKey         регистрация покупателя ({@code sold}, {@code sold_after_sale}); 0 — нет
 * @param buyerClientId       покупатель; 0 — нет
 * @param underbidderRegKey   вторая ставка: регистрация с наибольшей принятой ставкой среди прочих; 0 — нет
 * @param underbidderAmountKop её последняя принятая ставка
 * @param buyerDefault        покупатель не оплатил: продажа отменена на 31-й день
 * @param defaultAt           момент отмены
 * @param secondChanceClientId второй шанс принят этим клиентом (новый счёт {@code second_chance}); 0 — нет
 * @param secondChancePriceKop цена второго шанса (его последняя ставка, не ниже резерва)
 * @param paidAt              покупатель (или второй шанс) оплатил полностью — вещь передана; 0 — нет
 * @param freeAt              с какого момента вещь свободна для решения «повтор или возврат»: не продан — закрытие;
 *                            отказ без второго шанса — отмена; снят — момент снятия; иначе 0
 * @param withdrawnAt         снят до торгов; 0 — нет
 * @param withdrawnBy         кто снял: {@code consignor} (сбор {@code WDR-2024} + каталог), {@code house} (без сборов);
 *                            {@code null} — не снят
 */
public record LotResult(long lotKey, int lotId, int saleId, int itemId, int consignmentId, Dept dept, Format format,
                        int offeringNo, LotResultCode result, long priceKop, long lowKop, long highKop, long reserveKop,
                        long closedAt, long buyerRegKey, int buyerClientId, long underbidderRegKey,
                        long underbidderAmountKop, boolean buyerDefault, long defaultAt, int secondChanceClientId,
                        long secondChancePriceKop, long paidAt, long freeAt, long withdrawnAt, String withdrawnBy) {
    public boolean sold() {
        return result == LotResultCode.SOLD || result == LotResultCode.SOLD_AFTER_SALE;
    }
}
