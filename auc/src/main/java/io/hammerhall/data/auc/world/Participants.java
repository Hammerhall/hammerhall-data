package io.hammerhall.data.auc.world;

import io.hammerhall.data.auc.bid.BidKind;
import io.hammerhall.data.auc.bid.Bidder;
import io.hammerhall.data.auc.bid.Channel;

import java.util.List;

/**
 * Участники лота (в цикле, подготовка торгов → торги): кто придёт, с каким каналом, стратегией и максимумом — для движка — и
 * попытки, отклонённые до движка.
 *
 * @param lotKey   {@link Keys#lot}
 * @param bidders  участники для движка; {@code Bidder.id} — ключ регистрации ({@link Keys#registration})
 * @param rejected попытки, отклонённые до движка, — по моменту; вливаются в журнал ставок по времени
 */
public record Participants(long lotKey, List<Participant> bidders, List<Rejected> rejected) {
    public Participants {
        bidders = List.copyOf(bidders);
        rejected = List.copyOf(rejected);
    }

    /**
     * Участник лота.
     *
     * @param regKey       {@link Keys#registration}
     * @param clientId     {@code clients.id}
     * @param bidder       для движка (ключ, канал, стратегия, максимум с долей лимита, момент заочной)
     * @param via          откуда ставит онлайн-участник: {@code SITE}, {@code APP}, {@code PARTNER_PLATFORM}
     * @param orderKey     поручение, поданное до торгов (заочная, телефон, максимум заранее) — {@link Keys#order};
     *                     0 — нет
     * @param phoneClerkId {@code staff.id} на линии (телефон); 0 — нет
     * @param phoneNoAnswer телефон: не дозвонились — страховочная «из книги» ({@code Bidder.phoneStandIn})
     */
    public record Participant(long regKey, int clientId, Bidder bidder, Via via, long orderKey, int phoneClerkId,
                              boolean phoneNoAnswer) {
    }

    /**
     * Попытка, отклонённая до движка.
     *
     * @param atMs      момент, миллисекунды Unix
     * @param regKey    регистрация — всегда есть ({@code bids.registration_id} пусто только у ставки за продавца): у
     *                  {@code not_registered} — неодобренная ({@code pending}, {@code rejected}, {@code revoked})
     * @param clientId  {@code clients.id}
     * @param channel   канал
     * @param kind      вид ({@code MANUAL} у ручной; {@code MAX} — поручение отклонено)
     * @param amountKop сумма
     * @param reason    {@code off_increment}, {@code over_limit}, {@code own_lot}, {@code not_registered},
     *                  {@code blocked}, {@code premium_not_approved}, {@code not_open}, {@code lot_withdrawn}
     */
    public record Rejected(long atMs, long regKey, int clientId, Channel channel, BidKind kind, long amountKop,
                           String reason) {
    }
}
