/**
 * Движок торгов аукционного дома: лот ({@link io.hammerhall.data.auc.bid.Lot}) и участники с намерениями
 * ({@link io.hammerhall.data.auc.bid.Bidder}) → детерминированная последовательность событий и итог лота
 * ({@link io.hammerhall.data.auc.bid.LotOutcome}). Без хранения и без схемы таблиц — генератор набора оборачивает его.
 *
 * <ul>
 *   <li>{@link io.hammerhall.data.auc.bid.IncrementTable} — таблица шага ставки и сетка сумм;</li>
 *   <li>{@link io.hammerhall.data.auc.bid.AuctionRules} — правила дома (шаги, мягкое закрытие, ставки от имени
 *       продавца); {@link io.hammerhall.data.auc.bid.Behavior} — доли ошибок, повторов и гонок;</li>
 *   <li>{@link io.hammerhall.data.auc.bid.BidEngine} — онлайн-торги по времени и живые торги в зале;</li>
 *   <li>{@link io.hammerhall.data.auc.bid.Invariants} — проверка итога по событиям;</li>
 *   <li>{@link io.hammerhall.data.auc.bid.Schedule} — волна закрытия и слоты живых торгов.</li>
 * </ul>
 * Деньги — {@code long} копеек, время — миллисекунды Unix (UTC).
 */
package io.hammerhall.data.auc.bid;
