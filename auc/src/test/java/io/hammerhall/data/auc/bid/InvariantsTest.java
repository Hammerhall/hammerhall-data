package io.hammerhall.data.auc.bid;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.hammerhall.data.auc.bid.Fx.CLOSE;
import static io.hammerhall.data.auc.bid.Fx.HOUR;
import static io.hammerhall.data.auc.bid.Fx.OPEN;
import static io.hammerhall.data.auc.bid.Fx.rub;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверка инвариантов сама ловит нарушения: итоги собраны руками — верный проходит, испорченные — нет. */
class InvariantsTest {
    static final AuctionRules RULES = AuctionRules.standard();
    static final Lot LOT = Lot.online(5, rub(10_000), 0, OPEN, CLOSE);
    static final List<Bidder> BIDDERS = Fx.scripted(2);
    static final int CH = Channel.ONLINE.ordinal(), MANUAL = BidKind.MANUAL.ordinal();

    /** Итог: ставки участников 0/1 на суммы в рублях (по часу), закрытие, сводка — по последней ставке. */
    static LotOutcome outcome(long[] times, int[] who, long[] rubles, long closeAt) {
        LotOutcome o = new LotOutcome();
        o.reset(LOT.id(), Mode.ONLINE, rub(10_000));
        for (int i = 0; i < who.length; i++) {
            o.add(EventType.BID, times[i], who[i], who[i] + 1, rub(rubles[i]), 0, CH, MANUAL, RejectReason.NONE, 0, (byte) 0);
        }
        o.add(EventType.CLOSE, closeAt, LotOutcome.NONE, 0, 0, 0, -1, -1, RejectReason.NONE, closeAt, (byte) 0);
        int w = who[who.length - 1];
        long p = rub(rubles[rubles.length - 1]);
        o.summary(true, p, p, w, w + 1, closeAt, who.length, who.length, 0, 0, 0, 2, 0, 0, false);
        return o;
    }

    static final long[] T = {OPEN + HOUR, OPEN + 2 * HOUR};

    @Test
    void validPasses() {
        assertNull(Invariants.verify(LOT, BIDDERS, RULES, outcome(T, new int[]{0, 1}, new long[]{10_000, 11_000}, CLOSE)));
    }

    @Test
    void catchesViolations() {
        // ставка ниже предыдущей + шаг; сумма не на сетке
        assertNotNull(Invariants.verify(LOT, BIDDERS, RULES, outcome(T, new int[]{0, 1}, new long[]{10_000, 10_000}, CLOSE)));
        assertNotNull(Invariants.verify(LOT, BIDDERS, RULES, outcome(T, new int[]{0, 1}, new long[]{10_000, 11_500}, CLOSE)));
        // первая ниже стартовой
        assertNotNull(Invariants.verify(LOT, BIDDERS, RULES, outcome(T, new int[]{0, 1}, new long[]{9_000, 10_000}, CLOSE)));
        // время назад
        assertNotNull(Invariants.verify(LOT, BIDDERS, RULES, outcome(new long[]{T[1], T[0]}, new int[]{0, 1}, new long[]{10_000, 11_000}, CLOSE)));
        // поздняя ставка без продления
        assertNotNull(Invariants.verify(LOT, BIDDERS, RULES, outcome(new long[]{T[0], CLOSE - 1_000}, new int[]{0, 1},
                new long[]{10_000, 11_000}, CLOSE)));
        // закрытие не в срок
        assertNotNull(Invariants.verify(LOT, BIDDERS, RULES, outcome(T, new int[]{0, 1}, new long[]{10_000, 11_000}, CLOSE + 1)));
        // молоток выше максимума победителя
        List<Bidder> poor = List.of(Bidder.online(1, Strategy.SCRIPTED, rub(50_000)), Bidder.online(2, Strategy.SCRIPTED, rub(10_500)));
        assertNotNull(Invariants.verify(LOT, poor, RULES, outcome(T, new int[]{0, 1}, new long[]{10_000, 11_000}, CLOSE)));
        // продан ниже резерва
        Lot reserved = Lot.online(5, rub(10_000), rub(20_000), OPEN, CLOSE);
        assertNotNull(Invariants.verify(reserved, BIDDERS, RULES, outcome(T, new int[]{0, 1}, new long[]{10_000, 11_000}, CLOSE)));
    }

    @Test
    void extensionOnlyByRule() {
        long t = CLOSE - 60_000;
        LotOutcome o = new LotOutcome();
        o.reset(LOT.id(), Mode.ONLINE, rub(10_000));
        o.add(EventType.BID, t, 0, 1, rub(10_000), 0, CH, MANUAL, RejectReason.NONE, 0, (byte) 0);
        o.add(EventType.EXTEND, t, LotOutcome.NONE, 0, 0, 0, -1, -1, RejectReason.NONE, t + 3 * 60_000, (byte) 0);   // по правилу — t + 2 мин
        o.add(EventType.CLOSE, t + 3 * 60_000, LotOutcome.NONE, 0, 0, 0, -1, -1, RejectReason.NONE, t + 3 * 60_000, (byte) 0);
        o.summary(true, rub(10_000), rub(10_000), 0, 1, t + 3 * 60_000, 1, 1, 0, 0, 0, 1, 1, 0, false);
        String p = Invariants.verify(LOT, BIDDERS, RULES, o);
        assertNotNull(p);
        assertTrue(p.contains("по правилу"), p);
    }

    /** «Цена изменилась» без принятой чужой ставки, перед которой сумма проходила, — нарушение (T15). */
    @Test
    void priceChangedNeedsAnotherAcceptedBid() {
        LotOutcome o = new LotOutcome();
        o.reset(LOT.id(), Mode.ONLINE, rub(10_000));
        o.add(EventType.BID, T[0], 0, 1, rub(10_000), 0, CH, MANUAL, RejectReason.NONE, 0, (byte) 0);
        o.add(EventType.BID, T[1], 0, 1, rub(9_000), 0, CH, MANUAL, RejectReason.PRICE_CHANGED, 0, (byte) 0);
        o.add(EventType.CLOSE, CLOSE, LotOutcome.NONE, 0, 0, 0, -1, -1, RejectReason.NONE, CLOSE, (byte) 0);
        o.summary(true, rub(10_000), rub(10_000), 0, 1, CLOSE, 2, 1, 1, 0, 0, 1, 0, 0, false);
        String p = Invariants.verify(LOT, BIDDERS, RULES, o);
        assertNotNull(p);
        assertTrue(p.contains("T15"), p);
    }

    @Test
    void engineSelfCheckThrows() {
        LotOutcome bad = outcome(T, new int[]{0, 1}, new long[]{10_000, 10_500}, CLOSE);
        assertThrows(IllegalStateException.class, () -> Invariants.check(LOT, BIDDERS, RULES, bad));
    }
}
