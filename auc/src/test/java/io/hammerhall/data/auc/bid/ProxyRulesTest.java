package io.hammerhall.data.auc.bid;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.hammerhall.data.auc.bid.Action.bid;
import static io.hammerhall.data.auc.bid.Action.maxBid;
import static io.hammerhall.data.auc.bid.Fx.HOUR;
import static io.hammerhall.data.auc.bid.Fx.OPEN;
import static io.hammerhall.data.auc.bid.Fx.rub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правило шага и автоставки на ручных примерах: онлайн-лот, стартовая 10 000 ₽ (шаг 1 000 ₽ до 30 000, дальше —
 * 2 500), ставки — по сценарию, далеко от закрытия (без продлений). Участники 1, 2, 3 — в сценарии индексы 0, 1, 2.
 */
class ProxyRulesTest {
    static final long T1 = OPEN + HOUR, T2 = OPEN + 2 * HOUR, T3 = OPEN + 3 * HOUR, T4 = OPEN + 4 * HOUR;

    @Test
    void bidMustBeCurrentPlusStep() {
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2),
                bid(T1, 0, rub(10_000)), bid(T2, 1, rub(10_000)), bid(T3, 1, rub(11_000)), bid(T4, 0, rub(12_000)));
        assertEquals(List.of("1 manual 10000 ok", "2 manual 10000 below_minimum", "2 manual 11000 ok", "1 manual 12000 ok"),
                Fx.bids(o));
        assertTrue(o.sold());
        assertEquals(rub(12_000), o.hammerPrice());
        assertEquals(1, o.winnerId());
        assertEquals(4, o.bids());
        assertEquals(3, o.acceptedBids());
        assertEquals(2, o.participants());
    }

    @Test
    void firstBidNotBelowStartAndRoundedDown() {
        // старт 10 250 ₽ — к сетке вверх: 11 000; ставка 10 999,99 ₽ — к сетке вниз: 10 000 — ниже старта
        Lot lot = Lot.online(1, rub(10_250), 0, OPEN, Fx.CLOSE);
        LotOutcome o = Fx.run(lot, Fx.scripted(1), bid(T1, 0, rub(10_999) + 99), bid(T2, 0, rub(11_500)));
        assertEquals(rub(11_000), o.opening());
        assertEquals(List.of("1 manual 10000 below_minimum", "1 manual 11000 ok"), Fx.bids(o));
    }

    @Test
    void maxPlacedOpensAtStartPrice() {
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(1), maxBid(T1, 0, rub(30_000)));
        assertEquals(List.of("1 max 10000 ok"), Fx.bids(o));
        assertEquals(rub(30_000), o.maxAmount(0));
        assertEquals(rub(10_000), o.hammerPrice());
    }

    @Test
    void proxyAnswersManualBidsByMinimalStep() {
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2),
                maxBid(T1, 0, rub(20_000)), bid(T2, 1, rub(12_000)), bid(T3, 1, rub(14_000)));
        assertEquals(List.of("1 max 10000 ok", "2 manual 12000 ok", "1 auto 13000 ok", "2 manual 14000 ok", "1 auto 15000 ok"),
                Fx.bids(o));
        assertTrue(o.kind(2).auto());
        assertEquals(o.time(1), o.time(2), "автоставка — в ту же миллисекунду");
        assertEquals(1, o.winnerId());
        assertEquals(rub(15_000), o.hammerPrice());
        assertEquals(2, o.autoBids());
    }

    @Test
    void twoMaximaBiggerWinsAtSecondPlusStep() {
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2), maxBid(T1, 0, rub(20_000)), maxBid(T2, 1, rub(15_000)));
        assertEquals(List.of("1 max 10000 ok", "2 max 15000 ok", "1 auto 16000 ok"), Fx.bids(o));
        assertEquals(1, o.winnerId());
        assertEquals(rub(16_000), o.hammerPrice());
    }

    @Test
    void laterBiggerMaximumTakesTheLead() {
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2), maxBid(T1, 0, rub(15_000)), maxBid(T2, 1, rub(20_000)));
        // меньший максимум — целиком, больший — второй максимум + шаг
        assertEquals(List.of("1 max 10000 ok", "1 auto 15000 ok", "2 max 16000 ok"), Fx.bids(o));
        assertEquals(2, o.winnerId());
        assertEquals(rub(16_000), o.hammerPrice());
    }

    @Test
    void equalMaximaEarlierWins() {
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2), maxBid(T1, 0, rub(15_000)), maxBid(T2, 1, rub(15_000)));
        assertEquals(List.of("1 max 10000 ok", "2 max 15000 tied", "1 auto 15000 ok"), Fx.bids(o));
        assertEquals(1, o.winnerId());
        assertEquals(rub(15_000), o.hammerPrice());
        // ручная ставка, равная максимуму лидера, — тоже ничья в пользу раннего
        LotOutcome m = Fx.run(Fx.online(10_000), Fx.scripted(2), maxBid(T1, 0, rub(15_000)), bid(T2, 1, rub(15_000)));
        assertEquals(List.of("1 max 10000 ok", "2 manual 15000 tied", "1 auto 15000 ok"), Fx.bids(m));
        assertEquals(1, m.winnerId());
    }

    @Test
    void priceNeverAboveFirstMaximum() {
        // 2-й максимум 29 000 + шаг 1 000 = 30 000 = 1-й максимум; максимум 31 000 приводится к сетке (шаг 2 500) — 30 000
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2), maxBid(T1, 0, rub(31_000)), maxBid(T2, 1, rub(29_000)));
        assertEquals(List.of("1 max 10000 ok", "2 max 29000 ok", "1 auto 30000 ok"), Fx.bids(o));
        assertEquals(rub(30_000), o.maxAmount(0));
        // лидер исчерпал максимум — ручная ставка выше забирает лот
        LotOutcome p = Fx.run(Fx.online(10_000), Fx.scripted(2),
                maxBid(T1, 0, rub(15_000)), maxBid(T2, 1, rub(14_000)), bid(T3, 1, rub(16_000)));
        assertEquals(List.of("1 max 10000 ok", "2 max 14000 ok", "1 auto 15000 ok", "2 manual 16000 ok"), Fx.bids(p));
        assertEquals(2, p.winnerId());
    }

    /** T5: резерв между вторым и первым максимумом — цена = резерв. */
    @Test
    void reserveBetweenMaximaSetsThePrice() {
        Lot lot = Lot.online(1, rub(10_000), rub(18_000), OPEN, Fx.CLOSE);
        LotOutcome o = Fx.run(lot, Fx.scripted(2), maxBid(T1, 1, rub(15_000)), maxBid(T2, 0, rub(20_000)));
        // без резерва было бы 16 000 (второй + шаг) и «не продан»; с резервом между — 18 000 и продан
        assertEquals(List.of("2 max 10000 ok", "2 auto 15000 ok", "1 max 18000 ok"), Fx.bids(o));
        assertTrue(o.sold());
        assertEquals(rub(18_000), o.hammerPrice());
        assertEquals(1, o.winnerId());
        // правило выключено — цена по максимумам, до резерва не дошли
        LotOutcome off = Fx.run(AuctionRules.standard().withProxyToReserve(false), lot, Fx.scripted(2),
                maxBid(T1, 1, rub(15_000)), maxBid(T2, 0, rub(20_000)));
        assertEquals(List.of("2 max 10000 ok", "2 auto 15000 ok", "1 max 16000 ok"), Fx.bids(off));
        assertFalse(off.sold());
    }

    @Test
    void singleMaximumAboveReserveOpensAtReserve() {
        Lot lot = Lot.online(1, rub(10_000), rub(18_000), OPEN, Fx.CLOSE);
        // один максимум не ниже резерва — сразу резерв; ручная ставка против него — ответ не ниже резерва
        LotOutcome o = Fx.run(lot, Fx.scripted(2), maxBid(T1, 0, rub(20_000)), bid(T2, 1, rub(19_000)));
        assertEquals(List.of("1 max 18000 ok", "2 manual 19000 ok", "1 auto 20000 ok"), Fx.bids(o));
        assertTrue(o.sold());
        // резерв выше обоих максимумов — не продан, цена по максимумам
        Lot high = Lot.online(1, rub(10_000), rub(25_000), OPEN, Fx.CLOSE);
        LotOutcome h = Fx.run(high, Fx.scripted(2), maxBid(T1, 0, rub(20_000)), maxBid(T2, 1, rub(15_000)));
        assertEquals(List.of("1 max 10000 ok", "2 max 15000 ok", "1 auto 16000 ok"), Fx.bids(h));
        assertFalse(h.sold());
        // резерв ниже «второй + шаг» — не мешает: 12 000 на открытии, дальше по максимумам
        Lot low = Lot.online(1, rub(10_000), rub(12_000), OPEN, Fx.CLOSE);
        LotOutcome l = Fx.run(low, Fx.scripted(2), maxBid(T1, 1, rub(15_000)), maxBid(T2, 0, rub(20_000)));
        assertEquals(List.of("2 max 12000 ok", "2 auto 15000 ok", "1 max 16000 ok"), Fx.bids(l));
    }

    @Test
    void leaderCannotOutbidHimself() {
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(1), bid(T1, 0, rub(10_000)), bid(T2, 0, rub(11_000)));
        assertEquals(List.of("1 manual 10000 ok", "1 manual 11000 already_leading"), Fx.bids(o));
        assertEquals(rub(10_000), o.hammerPrice());
    }

    @Test
    void noBidsNotSold() {
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2));
        assertFalse(o.sold());
        assertEquals(List.of(EventType.CLOSE), Fx.types(o));
        assertEquals(0, o.bids());
        assertEquals(0, o.participants());
        assertEquals(0, o.highBid());
    }

    @Test
    void reserveNotReachedNotSold() {
        Lot lot = Lot.online(1, rub(10_000), rub(15_000), OPEN, Fx.CLOSE);
        LotOutcome o = Fx.run(lot, Fx.scripted(2), maxBid(T1, 0, rub(14_000)), maxBid(T2, 1, rub(13_000)));
        assertEquals(List.of("1 max 10000 ok", "2 max 13000 ok", "1 auto 14000 ok"), Fx.bids(o));
        assertFalse(o.sold());
        assertEquals(0, o.hammerPrice());
        assertEquals(rub(14_000), o.highBid());
        assertEquals(0, o.winnerId());
        // ставка на сам резерв — продан
        LotOutcome s = Fx.run(lot, Fx.scripted(2), maxBid(T1, 0, rub(14_000)), bid(T2, 1, rub(15_000)));
        assertTrue(s.sold());
        assertEquals(rub(15_000), s.hammerPrice());
        assertEquals(2, s.winnerId());
    }
}
