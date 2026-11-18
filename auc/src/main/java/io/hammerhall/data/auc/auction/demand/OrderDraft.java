package io.hammerhall.data.auc.auction.demand;

import io.hammerhall.data.auc.world.Via;

/**
 * Заявка до торгов (живые): заочная или телефонная, редакция 1 ({@code Keys.order(лот, клиент, 1)}). Деньги — копейки,
 * момент подачи — миллисекунды.
 *
 * @param key          {@code Keys.order}
 * @param lotKey       лот
 * @param regKey       регистрация
 * @param clientId     клиент
 * @param kind         {@code absentee} или {@code phone}
 * @param maxKop       максимум (у телефона — страховочная); 0 — телефон без страховочной
 * @param placedAtMs   подана
 * @param via          откуда
 * @param phoneOutcome телефон: {@code connected}, {@code no_answer}; {@code null} — у заочной
 * @param clerkId      сотрудник на линии; 0 — нет
 * @param cancelledAt  отозвана до торгов или лот снят, секунды; 0 — нет
 */
public record OrderDraft(long key, long lotKey, long regKey, int clientId, String kind, long maxKop, long placedAtMs, Via via,
                         String phoneOutcome, int clerkId, long cancelledAt) {
}
