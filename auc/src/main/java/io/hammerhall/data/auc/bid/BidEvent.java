package io.hammerhall.data.auc.bid;

import io.hammerhall.data.core.money.Money;

/**
 * Событие торгов лота — публичные поля, как они пойдут в данные. Скрытой метки подставной ставки здесь нет: она —
 * только в {@link LotOutcome#hiddenShill(int)}. Удобный вид для тестов и отладки; генератор читает
 * {@link LotOutcome} по индексам, без этих объектов.
 *
 * @param seq      порядковый номер события в лоте (1, 2, …) — порядок приёма и обработки; события одной
 *                 миллисекунды упорядочены по нему
 * @param type     вид события
 * @param at       момент, миллисекунды Unix (UTC)
 * @param bidderId участник ({@link Bidder#id()}); 0 — нет участника (ставка от имени продавца, продление, закрытие,
 *                 предупреждение, молоток)
 * @param channel  канал ставки (и подъёма максимума); у прочих событий — {@code null}
 * @param kind     вид ставки ({@code MAX} у подъёма максимума); у прочих событий — {@code null}
 * @param amount   сумма ставки, копейки; у молотка — цена молотка (0 — не продан); у подъёма максимума — текущая
 *                 цена; у прочих — 0
 * @param max      максимум участника при автоставке, поставленном максимуме и заочной ставке; у подъёма — новый
 *                 максимум; иначе 0
 * @param accepted ставка принята; у {@link EventType#MAX_RAISE} — редакция максимума применена
 * @param reason   почему отклонена ({@link RejectReason#NONE} у принятой и у прочих событий)
 * @param closeAt  продление и закрытие — время закрытия лота; молоток — 0; ставка — 0
 */
public record BidEvent(int seq, EventType type, long at, long bidderId, Channel channel, BidKind kind, long amount,
                       long max, boolean accepted, RejectReason reason, long closeAt) {

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(seq).append(' ').append(at).append(' ').append(type.code());
        if (type == EventType.BID) {
            sb.append(' ').append(bidderId == 0 ? "-" : String.valueOf(bidderId)).append(' ').append(channel.code())
                    .append(' ').append(kind.code()).append(' ').append(Money.format(amount));
            if (max > 0) sb.append(" max ").append(Money.format(max));
            sb.append(accepted ? " ok" : " REJECTED " + reason.code());
        } else if (type == EventType.MAX_RAISE) {
            sb.append(' ').append(bidderId).append(" max ").append(Money.format(max)).append(" at ").append(Money.format(amount))
                    .append(accepted ? " ok" : " REJECTED " + reason.code());
        } else if (type == EventType.HAMMER) {
            sb.append(amount > 0 ? " sold " + Money.format(amount) + " to " + bidderId : " passed");
        } else if (closeAt > 0) {
            sb.append(" close ").append(closeAt);
        }
        return sb.toString();
    }
}
