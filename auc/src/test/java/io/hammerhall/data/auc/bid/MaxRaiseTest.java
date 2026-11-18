package io.hammerhall.data.auc.bid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.hammerhall.data.auc.bid.Action.bid;
import static io.hammerhall.data.auc.bid.Action.maxBid;
import static io.hammerhall.data.auc.bid.Action.raiseMax;
import static io.hammerhall.data.auc.bid.Fx.CLOSE;
import static io.hammerhall.data.auc.bid.Fx.HOUR;
import static io.hammerhall.data.auc.bid.Fx.OPEN;
import static io.hammerhall.data.auc.bid.Fx.rub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Подъём своего максимума (T4): ведущий — событие {@link EventType#MAX_RAISE}, цена прежняя, соперник упирается в
 * новый максимум; перебитый — обычный поставленный максимум; не выше действующего — отказ; выше потолка участника —
 * ошибка входа.
 */
class MaxRaiseTest {
    static final long T1 = OPEN + HOUR, T2 = OPEN + 2 * HOUR, T3 = OPEN + 3 * HOUR, T4 = OPEN + 4 * HOUR;

    /** Все события строками: ставки — как {@link Fx#bids}, подъём — «raise участник максимум цена ok|причина». */
    static List<String> log(LotOutcome o) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < o.size(); i++) {
            if (o.type(i) == EventType.MAX_RAISE) {
                out.add("raise " + o.bidderId(i) + " " + o.maxAmount(i) / 100 + " " + o.amount(i) / 100 + " "
                        + (o.accepted(i) ? "ok" : o.reason(i).code()));
            } else if (o.type(i) == EventType.BID) {
                out.add(o.bidderId(i) + " " + o.kind(i).code() + " " + o.amount(i) / 100 + " " + (o.accepted(i) ? "ok" : o.reason(i).code()));
            }
        }
        return out;
    }

    @Test
    void leaderRaisesBeforeTheRivalArrives() {
        // без подъёма: 15 000 против 18 000 — выиграл бы второй за 16 000
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2),
                maxBid(T1, 0, rub(15_000)), raiseMax(T2, 0, rub(20_000)), maxBid(T3, 1, rub(18_000)));
        assertEquals(List.of("1 max 10000 ok", "raise 1 20000 10000 ok", "2 max 18000 ok", "1 auto 19000 ok"), log(o));
        assertEquals(EventType.MAX_RAISE, o.type(1));
        assertEquals(BidKind.MAX, o.kind(1));
        assertEquals(o.amount(0), o.amount(1), "цена при подъёме не меняется");
        assertEquals(1, o.winnerId());
        assertEquals(rub(19_000), o.hammerPrice());
        assertEquals(3, o.bids(), "подъём — не ставка");
        assertEquals(5, o.size(), "четыре события торгов и закрытие");
        assertEquals(rub(20_000), o.maxAmount(3), "автоставка — по новому максимуму");
        // соперник выше нового максимума: ведущий ставит его целиком, соперник — следующий шаг
        LotOutcome p = Fx.run(Fx.online(10_000), Fx.scripted(2),
                maxBid(T1, 0, rub(15_000)), raiseMax(T2, 0, rub(20_000)), maxBid(T3, 1, rub(25_000)));
        assertEquals(List.of("1 max 10000 ok", "raise 1 20000 10000 ok", "1 auto 20000 ok", "2 max 21000 ok"), log(p));
        assertEquals(2, p.winnerId());
    }

    @Test
    void outbidBidderRaisingIsAnOrdinaryMax() {
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2),
                maxBid(T1, 0, rub(15_000)), maxBid(T2, 1, rub(20_000)), raiseMax(T3, 0, rub(25_000)));
        assertEquals(List.of("1 max 10000 ok", "1 auto 15000 ok", "2 max 16000 ok", "2 auto 20000 ok", "1 max 21000 ok"), log(o));
        assertEquals(rub(25_000), o.maxAmount(4));
        assertEquals(1, o.winnerId());
        // перебитый поднимает недостаточно — обычный отказ ниже минимальной
        LotOutcome n = Fx.run(Fx.online(10_000), Fx.scripted(2),
                maxBid(T1, 0, rub(15_000)), maxBid(T2, 1, rub(20_000)), raiseMax(T3, 0, rub(16_000)));
        assertEquals("1 max 16000 below_minimum", log(n).getLast());
    }

    @Test
    void raiseNotAboveCurrentMaxIsRejected() {
        // снизить максимум нельзя; равный — тоже не подъём: отказ, максимум и цена прежние
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2),
                maxBid(T1, 0, rub(20_000)), raiseMax(T2, 0, rub(15_000)), raiseMax(T3, 0, rub(20_000)), maxBid(T4, 1, rub(18_000)));
        assertEquals(List.of("1 max 10000 ok", "raise 1 15000 10000 below_minimum", "raise 1 20000 10000 below_minimum",
                "2 max 18000 ok", "1 auto 19000 ok"), log(o));
        assertFalse(o.accepted(1));
        assertEquals(1, o.winnerId());
    }

    @Test
    void manualLeaderPlacesAMax() {
        // ведущий ручной ставкой ставит максимум — это подъём: цена прежняя, дальше система ведёт за него
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2),
                bid(T1, 0, rub(10_000)), raiseMax(T2, 0, rub(20_000)), bid(T3, 1, rub(12_000)));
        assertEquals(List.of("1 manual 10000 ok", "raise 1 20000 10000 ok", "2 manual 12000 ok", "1 auto 13000 ok"), log(o));
    }

    @Test
    void raiseToReserveLiftsThePrice() {
        // новый максимум дотянул до резерва — цена = резерв (T5), как у любого максимума
        Lot lot = Lot.online(1, rub(10_000), rub(18_000), OPEN, CLOSE);
        LotOutcome o = Fx.run(lot, Fx.scripted(2), maxBid(T1, 0, rub(15_000)), raiseMax(T2, 0, rub(20_000)));
        assertEquals(List.of("1 max 10000 ok", "raise 1 20000 10000 ok", "1 auto 18000 ok"), log(o));
        assertTrue(o.sold());
        assertEquals(rub(18_000), o.hammerPrice());
    }

    @Test
    void raiseAfterCloseAndAboveCeiling() {
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(1), maxBid(T1, 0, rub(15_000)), raiseMax(CLOSE, 0, rub(20_000)));
        assertEquals(List.of("1 max 10000 ok", "1 max 20000 after_close"), log(o));
        List<Bidder> capped = List.of(Bidder.online(1, Strategy.SCRIPTED, rub(18_000)));
        assertThrows(IllegalArgumentException.class, () -> Fx.run(Fx.online(10_000), capped,
                maxBid(T1, 0, rub(15_000)), raiseMax(T2, 0, rub(20_000))));
        assertThrows(IllegalArgumentException.class, () -> new Action(T1, 0, rub(1), false, false, true));
    }

    @Test
    void publicEventOfARaise() {
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(1), maxBid(T1, 0, rub(15_000)), raiseMax(T2, 0, rub(20_000)));
        BidEvent e = o.event(1);
        assertEquals(new BidEvent(2, EventType.MAX_RAISE, T2, 1, Channel.ONLINE, BidKind.MAX, rub(10_000), rub(20_000), true,
                RejectReason.NONE, 0), e);
        assertEquals("max_raise", EventType.MAX_RAISE.code());
    }
}
