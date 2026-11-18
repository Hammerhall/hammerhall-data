package io.hammerhall.data.auc.world;

import java.util.List;

/**
 * События поручений лота (в цикле, торги → подготовка торгов): что движок сделал с максимумами — для редакций
 * {@code bid_orders} (T4: {@code MAX}, {@code MAX_RAISE}, отказ, исчерпание, победа). Заявки до торгов —
 * заочные и телефонные (редакция 1, ключ {@code Keys.order(lot, client, 1)}) — подготовка торгов пишет сама из {@link Participants};
 * онлайн-поручения (максимумы) — только отсюда: все редакции, родившиеся в торгах лота, нумерует оркестр (по времени,
 * с отклонёнными до движка максимумами). {@code PLACED}, {@code REBID}, {@code RAISED}, {@code REJECTED} — новая
 * редакция; {@code OUTBID}, {@code EXCEEDED_LIMIT}, {@code WON}, {@code UNUSED} — окончательный статус (момент — когда
 * стал окончательным; состояние на срез — у подготовки торгов по моменту). {@code superseded} следует из
 * {@code RAISED}; {@code cancelled} у снятого лота подготовка торгов ставит сама.
 *
 * @param lotKey {@link Keys#lot}
 * @param events по моменту, затем порядку движка
 */
public record OrderEvents(long lotKey, List<Event> events) {
    public OrderEvents {
        events = List.copyOf(events);
    }

    public enum Type {
        /** Участник впервые поставил максимум в торгах (событие движка {@code MAX}) — редакция 1. */
        PLACED,
        /** Прежний максимум исчерпан, участник поставил новый ({@code MAX}) — редакция + 1, {@code rebid_after_outbid}. */
        REBID,
        /** Лидер поднял свой максимум ({@code MAX_RAISE}) — редакция + 1, {@code raise_leading}; строки {@code bids} нет. */
        RAISED,
        /**
         * Максимум отклонён (движком: {@code tied}, {@code price_changed}, {@code race_lost}, {@code below_minimum},
         * {@code already_leading}, {@code after_close}; или до движка — {@code Participants.Rejected} вида {@code MAX}) —
         * новая редакция со статусом {@code rejected}, {@code ended_at} = момент: строка {@code bids} вида {@code max}
         * ссылается на неё.
         */
        REJECTED,
        /** Поручение исчерпано — перебито ({@code outbid}). */
        OUTBID,
        /** Поручение не дало ставки сверх лимита ({@code exceeded_limit}). */
        EXCEEDED_LIMIT,
        /** Лот продан по этому поручению ({@code won}). */
        WON,
        /** Поручение так и не исполнено (заочная выше цены лота не понадобилась; телефон без страховочной) — {@code unused}. */
        UNUSED
    }

    /**
     * Событие поручения.
     *
     * @param type     что случилось
     * @param regKey   {@link Keys#registration}
     * @param clientId {@code clients.id}
     * @param revision редакция, к которой относится событие (у {@code PLACED}, {@code REBID}, {@code RAISED} — новая)
     * @param maxKop   максимум редакции (на сетке)
     * @param atMs     момент, миллисекунды Unix
     * @param via      откуда (у {@code PLACED}, {@code REBID}, {@code RAISED}); {@code null} — у прочих
     */
    public record Event(Type type, long regKey, int clientId, int revision, long maxKop, long atMs, Via via) {
        /** Ключ редакции: {@link Keys#order}. */
        public long orderKey(long lotKey) {
            return Keys.order(lotKey, clientId, revision);
        }
    }
}
