package io.hammerhall.data.auc.bid;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static io.hammerhall.data.auc.bid.Action.bid;
import static io.hammerhall.data.auc.bid.Action.repeat;
import static io.hammerhall.data.auc.bid.Fx.CLOSE;
import static io.hammerhall.data.auc.bid.Fx.HOUR;
import static io.hammerhall.data.auc.bid.Fx.MIN;
import static io.hammerhall.data.auc.bid.Fx.OPEN;
import static io.hammerhall.data.auc.bid.Fx.SEC;
import static io.hammerhall.data.auc.bid.Fx.rub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Повтор принятой ставки — скрытая метка и ссылка на исходную (в данных — причина «повтор» и тот же ключ запроса);
 * заложенные сбои онлайн-лота ({@link Faults}): пропущенное продление, ранний конец, дважды принятый повтор — скрыто
 * помечены, сводка знает, заложены ли они, проверка инвариантов пропускает ровно их.
 */
class RepeatsAndFaultsTest {
    static final long T1 = OPEN + HOUR, T2 = OPEN + 2 * HOUR;

    static LotOutcome run(Faults f, Action... script) {
        return new BidEngine(AuctionRules.standard(), Behavior.none()).run(Fx.online(10_000), Fx.scripted(2), List.of(script), f,
                new SplittableRandom(1), new LotOutcome());
    }

    @Test
    void repeatIsMarkedAndPointsToTheOriginal() {
        LotOutcome o = run(Faults.NONE, bid(T1, 0, rub(10_000)), repeat(T1 + 300, 0), bid(T2, 1, rub(11_000)), repeat(T2 + 500, 0));
        assertEquals(List.of("1 manual 10000 ok", "1 manual 10000 already_leading", "2 manual 11000 ok", "1 manual 10000 below_minimum"),
                Fx.bids(o));
        assertFalse(o.hiddenRepeat(0));
        assertTrue(o.hiddenRepeat(1));
        assertEquals(0, o.repeatOf(1));
        assertTrue(o.hiddenRepeat(3));
        assertEquals(0, o.repeatOf(3));
        assertEquals(-1, o.repeatOf(2));
        assertEquals(2, o.winnerId());
    }

    /** Повторы из поведения: каждый помечен, ссылается на свою принятую ставку той же суммы и в данных не принят. */
    @Test
    void repeatsFromBehavior() {
        BidEngine e = new BidEngine(AuctionRules.standard(), Behavior.none().withDuplicateShare(1));
        LotOutcome o = new LotOutcome();
        int repeats = 0;
        for (int k = 0; k < 500; k++) {
            List<Bidder> b = List.of(Bidder.online(1, Strategy.WAR, rub(40_000)), Bidder.online(2, Strategy.EARLY, rub(30_000)));
            e.run(Fx.online(10_000), b, new SplittableRandom(k), o);
            for (int i = 0; i < o.size(); i++) {
                if (!o.hiddenRepeat(i)) continue;
                repeats++;
                int j = o.repeatOf(i);
                assertFalse(o.accepted(i));
                assertTrue(o.accepted(j) && o.bidderId(j) == o.bidderId(i) && o.amount(j) == o.amount(i));
                assertTrue(o.time(i) - o.time(j) <= BidEngine.DUP_MAX);
            }
        }
        assertTrue(repeats > 1_000, "повторов " + repeats);
    }

    @Test
    void missedExtension() {
        LotOutcome o = run(Faults.ofMissedExtension(),
                bid(T1, 0, rub(10_000)),
                bid(CLOSE - 60 * SEC, 1, rub(11_000)),    // по правилу — продление до CLOSE + 60 с; сбой — нет
                bid(CLOSE - 30 * SEC, 0, rub(12_000)),    // следующая поздняя — продлевает как обычно
                bid(CLOSE + 100 * SEC, 1, rub(13_000)));  // CLOSE + 90 с — уже закрыт
        assertEquals(List.of(EventType.BID, EventType.BID, EventType.BID, EventType.EXTEND, EventType.CLOSE, EventType.BID), Fx.types(o));
        assertTrue(o.hiddenMissedExtension(1));
        assertFalse(o.hiddenMissedExtension(2));
        assertTrue(o.plantedMissedExtension());
        assertEquals(CLOSE + 90 * SEC, o.closedAt());
        assertEquals(1, o.extensions());
        // без поздних ставок сбою негде случиться — не заложен
        assertFalse(run(Faults.ofMissedExtension(), bid(T1, 0, rub(10_000))).plantedMissedExtension());
    }

    @Test
    void earlyClose() {
        LotOutcome o = run(Faults.ofEarlyClose(2_000), bid(T1, 0, rub(10_000)), bid(CLOSE - 1_500, 1, rub(11_000)));
        assertEquals(List.of("1 manual 10000 ok", "2 manual 11000 after_close"), Fx.bids(o));
        int c = Fx.types(o).indexOf(EventType.CLOSE);
        assertEquals(CLOSE - 2_000, o.time(c));
        assertTrue(o.hiddenEarlyClose(c));
        assertTrue(o.plantedEarlyClose());
        assertEquals(CLOSE - 2_000, o.closedAt());
        assertEquals(1, o.winnerId());
        // с продлением — раньше продлённого конца
        LotOutcome x = run(Faults.ofEarlyClose(3_000), bid(CLOSE - MIN, 0, rub(10_000)));
        assertEquals(CLOSE + MIN - 3_000, x.closedAt());
    }

    @Test
    void doubleAccept() {
        // повтор принят второй раз: ведущий перебил сам себя на минимальный шаг
        LotOutcome o = run(Faults.ofDoubleAccept(), bid(T1, 0, rub(10_000)), repeat(T1 + 400, 0), repeat(T1 + 900, 0));
        assertEquals(List.of("1 manual 10000 ok", "1 manual 11000 ok", "1 manual 10000 already_leading"), Fx.bids(o));
        assertTrue(o.hiddenRepeat(1));
        assertEquals(0, o.repeatOf(1));
        assertTrue(o.plantedDoubleAccept());
        assertEquals(rub(11_000), o.hammerPrice());
        assertEquals(1, o.winnerId());
    }

    /** Сбои на лотах со стратегиями: заложены там, где нашёлся случай; инварианты держатся с пропуском ровно помеченного. */
    @Test
    void faultsOnStrategyLots() {
        Faults[] all = {Faults.ofMissedExtension(), Faults.ofEarlyClose(1_500), Faults.ofDoubleAccept(), new Faults(true, 1_000, true)};
        BidEngine e = new BidEngine(AuctionRules.standard(), Behavior.standard());
        LotOutcome o = new LotOutcome();
        int[] planted = new int[3];
        for (int k = 0; k < 4_000; k++) {
            List<Bidder> b = List.of(Bidder.online(1, Strategy.WAR, rub(40_000)), Bidder.online(2, Strategy.WAR, rub(35_000)),
                    Bidder.online(3, Strategy.EARLY, rub(25_000)), Bidder.online(4, Strategy.SNIPER, rub(30_000)));
            Lot lot = Fx.online(10_000);
            Faults f = all[k % all.length];
            e.run(lot, b, List.of(), f, new SplittableRandom(k), o);
            assertNull(Invariants.verify(lot, b, e.rules(), o));
            if (o.plantedMissedExtension()) planted[0]++;
            if (o.plantedEarlyClose()) planted[1]++;
            if (o.plantedDoubleAccept()) planted[2]++;
        }
        assertTrue(planted[0] > 1_500 && planted[1] == 2_000 && planted[2] > 1_000,
                "заложено: без продления " + planted[0] + ", ранний конец " + planted[1] + ", дважды " + planted[2]);
    }
}
