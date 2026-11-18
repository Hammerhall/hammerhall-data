/**
 * Общий мир набора «Дом Одина»: записи и ключи, через которые модули говорят друг с другом. Запись меняет поставщик
 * вместе с потребителями.
 *
 * <table>
 *   <tr><th>ключ / запись</th><th>поставщик</th><th>потребители</th></tr>
 *   <tr><td>{@link io.hammerhall.data.auc.world.AucKeys#HOUSE} — {@link io.hammerhall.data.auc.world.HouseRules}</td><td>{@code house}</td><td>подготовка торгов, торги, {@code money}</td></tr>
 *   <tr><td>{@link io.hammerhall.data.auc.world.AucKeys#CLIENTS} — {@link io.hammerhall.data.auc.world.ClientBase}</td><td>{@code people}</td><td>подготовка торгов, торги, {@code site}</td></tr>
 *   <tr><td>{@link io.hammerhall.data.auc.world.AucKeys#CATALOG} — {@link io.hammerhall.data.auc.world.Intake}</td><td>{@code catalog}</td><td>подготовка торгов</td></tr>
 *   <tr><td>{@link io.hammerhall.data.auc.world.AucKeys#CALENDAR} — {@link io.hammerhall.data.auc.world.Calendar}</td><td>подготовка торгов</td><td>торги, {@code site}</td></tr>
 *   <tr><td>в цикле: {@link io.hammerhall.data.auc.world.SalePlan}, {@link io.hammerhall.data.auc.world.Participants},
 *       {@link io.hammerhall.data.auc.world.Registrations}</td><td>подготовка торгов</td><td>торги, {@code site}</td></tr>
 *   <tr><td>в цикле: {@link io.hammerhall.data.auc.world.ClientState}, {@link io.hammerhall.data.auc.world.LotResult},
 *       {@link io.hammerhall.data.auc.world.OrderEvents}</td><td>торги</td><td>подготовка торгов</td></tr>
 *   <tr><td>{@link io.hammerhall.data.auc.world.PaymentPlanner} (функция, не модуль)</td><td>{@code money}</td><td>торги</td></tr>
 *   <tr><td>{@link io.hammerhall.data.auc.world.AucKeys#AUCTION} — {@link io.hammerhall.data.auc.world.AuctionWorld}</td><td>торги</td><td>{@code money}, {@code site}, {@code clients}</td></tr>
 *   <tr><td>{@link io.hammerhall.data.auc.world.AucKeys#MONEY} — {@link io.hammerhall.data.auc.world.MoneyWorld}</td><td>{@code money}</td><td>{@code site}</td></tr>
 * </table>
 *
 * <p><b>Соглашения.</b> Деньги — {@code long} копеек (как в движке); моменты — секунды Unix UTC ({@code ...At}), точные
 * моменты ставок и событий лота — миллисекунды Unix ({@code ...AtMs}); даты бизнеса — {@code LocalDate} по Москве.
 * {@code id} выпуска — {@code int} с 1 (у ставок, визитов, уведомлений — {@code long}); 0 — «нет» (там, где поле
 * необязательно). Случайность — только от естественных ключей ({@link io.hammerhall.data.auc.world.Keys}), не от
 * {@code id} выпуска.
 */
package io.hammerhall.data.auc.world;
