package io.hammerhall.data.auc.bid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static io.hammerhall.data.auc.bid.Fx.DAY;
import static io.hammerhall.data.auc.bid.Fx.HOUR;
import static io.hammerhall.data.auc.bid.Fx.SEC;
import static io.hammerhall.data.auc.bid.Fx.rub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Живые торги: заочные ставки как автоставки, раньше оставленная побеждает при равных; резерв и ставки от имени
 * продавца; открытие с резерва по книге; лот без ставок; события в пределах слота; темп.
 */
class LiveTest {
    static final long SALE = RandomLots.BASE + 10 * HOUR;
    /** Слот лота при темпе 60 лотов в час — минута. */
    static final long SLOT_END = Schedule.liveSlotEnd(SALE, 60, 0);

    static Lot lot(long startRub, long reserveRub) {
        return Lot.live(7, rub(startRub), rub(reserveRub), SALE, SLOT_END);
    }

    static LotOutcome run(AuctionRules rules, Behavior beh, Lot lot, List<Bidder> b) {
        return Fx.run(rules, beh, lot, b, 42);
    }

    static LotOutcome run(Lot lot, List<Bidder> b) {
        return run(AuctionRules.standard(), Behavior.none(), lot, b);
    }

    @Test
    void absenteeIsExecutedAsProxy() {
        // заочная до 20 000 против зала до 15 000: зал доходит до своего максимума, книга — следующий шаг
        LotOutcome o = run(lot(10_000, 0), List.of(Bidder.absentee(1, rub(20_000), SALE - DAY),
                Bidder.of(2, Channel.ROOM, Strategy.MAX, rub(15_000))));
        assertTrue(o.sold());
        assertEquals(1, o.winnerId());
        assertEquals(rub(16_000), o.hammerPrice());
        for (int i = 0; i < o.size(); i++) {
            if (o.type(i) != EventType.BID || o.bidderId(i) != 1) continue;
            assertEquals(Channel.ABSENTEE, o.channel(i));
            assertEquals(BidKind.ABSENTEE, o.kind(i));
            assertTrue(o.kind(i).auto());
            assertEquals(rub(20_000), o.maxAmount(i));
        }
        assertEquals(List.of("1 absentee 10000 ok", "2 manual 11000 ok", "1 absentee 12000 ok", "2 manual 13000 ok",
                "1 absentee 14000 ok", "2 manual 15000 ok", "1 absentee 16000 ok"), Fx.bids(o));
        assertEquals(EventType.HAMMER, o.type(o.size() - 1));
        assertEquals(rub(16_000), o.amount(o.size() - 1));
    }

    @Test
    void earlierAbsenteeWinsTie() {
        // в списке поздняя — первой: книга исполняется в порядке, в каком ставки оставлены
        LotOutcome o = run(lot(10_000, 0), List.of(Bidder.absentee(2, rub(15_000), SALE - DAY),
                Bidder.absentee(1, rub(15_000), SALE - 2 * DAY)));
        assertEquals(List.of("1 absentee 10000 ok", "2 absentee 15000 tied", "1 absentee 15000 ok"), Fx.bids(o));
        assertEquals(1, o.winnerId());
        assertEquals(rub(15_000), o.hammerPrice());
    }

    @Test
    void roomBidEqualToBookLosesTie() {
        // заочная до 13 000 оставлена раньше: ставка зала на 13 000 — ничья в пользу книги
        LotOutcome o = run(lot(10_000, 0), List.of(Bidder.absentee(1, rub(13_000), SALE - DAY),
                Bidder.of(2, Channel.ROOM, Strategy.MAX, rub(13_000))));
        assertEquals(List.of("1 absentee 10000 ok", "2 manual 11000 ok", "1 absentee 12000 ok", "2 manual 13000 tied",
                "1 absentee 13000 ok"), Fx.bids(o));
        assertEquals(1, o.winnerId());
    }

    @Test
    void reserveNotMetWithoutVendorBids() {
        AuctionRules r = AuctionRules.standard().withVendorBids(false);
        LotOutcome o = run(r, Behavior.none(), lot(10_000, 30_000), List.of(Bidder.of(1, Channel.ROOM, Strategy.MAX, rub(15_000)),
                Bidder.of(2, Channel.PHONE, Strategy.MAX, rub(18_000))));
        // в зале двое чередуются: последняя ставка — максимум младшего (15 000) или следующий шаг (16 000)
        assertFalse(o.sold());
        assertTrue(o.highBid() == rub(15_000) || o.highBid() == rub(16_000), "наибольшая " + o.highBid());
        assertEquals(0, o.hammerPrice());
        assertEquals(0, o.vendorBids());
        assertEquals(0, o.amount(o.size() - 1), "молоток: снят");
    }

    @Test
    void vendorBidsUpToReserveHelpReachIt() {
        // один участник до 20 000, резерв 15 000: аукционист открывает и ведёт ставки от имени продавца ниже резерва
        Behavior b = Behavior.none().withVendorOpenShare(1);
        LotOutcome o = run(AuctionRules.standard(), b, lot(10_000, 15_000), List.of(Bidder.of(1, Channel.ROOM, Strategy.MAX, rub(20_000))));
        assertEquals(List.of("- vendor 10000 ok", "1 manual 11000 ok", "- vendor 12000 ok", "1 manual 13000 ok",
                "- vendor 14000 ok", "1 manual 15000 ok"), Fx.bids(o));
        assertTrue(o.sold());
        assertEquals(rub(15_000), o.hammerPrice());
        assertEquals(3, o.vendorBids());
        for (int i = 0; i < o.size(); i++) {
            if (o.type(i) == EventType.BID && o.kind(i) == BidKind.VENDOR) {
                assertEquals(Channel.AUCTIONEER, o.channel(i));
                assertEquals(0, o.bidderId(i));
                assertEquals(LotOutcome.VENDOR, o.bidderIndex(i));
                assertTrue(o.amount(i) < rub(15_000));
            }
        }
        // без ставок продавца тот же лот не продан: одна ставка на старте ниже резерва
        LotOutcome n = run(AuctionRules.standard().withVendorBids(false), b, lot(10_000, 15_000),
                List.of(Bidder.of(1, Channel.ROOM, Strategy.MAX, rub(20_000))));
        assertEquals(List.of("1 manual 10000 ok"), Fx.bids(n));
        assertFalse(n.sold());
    }

    @Test
    void vendorBidsCannotSell() {
        // участник до 13 000 при резерве 20 000: последней остаётся ставка продавца — снят
        Behavior b = Behavior.none().withVendorOpenShare(1);
        LotOutcome o = run(AuctionRules.standard(), b, lot(10_000, 20_000), List.of(Bidder.of(1, Channel.ROOM, Strategy.MAX, rub(13_000))));
        assertEquals(List.of("- vendor 10000 ok", "1 manual 11000 ok", "- vendor 12000 ok", "1 manual 13000 ok",
                "- vendor 14000 ok"), Fx.bids(o));
        assertFalse(o.sold());
        assertEquals(rub(14_000), o.highBid());
        assertEquals(0, o.winnerId());
    }

    @Test
    void singleBookBidOpensAtReserve() {
        LotOutcome o = run(lot(10_000, 20_000), List.of(Bidder.absentee(1, rub(30_000), SALE - DAY)));
        assertEquals(List.of("1 absentee 20000 ok"), Fx.bids(o));
        assertTrue(o.sold());
        assertEquals(rub(20_000), o.hammerPrice());
        // правило выключено — с открытия, и лот не дошёл до резерва
        LotOutcome n = run(AuctionRules.standard().withProxyToReserve(false).withVendorBids(false), Behavior.none(),
                lot(10_000, 20_000), List.of(Bidder.absentee(1, rub(30_000), SALE - DAY)));
        assertEquals(List.of("1 absentee 10000 ok"), Fx.bids(n));
        assertFalse(n.sold());
    }

    /** T5 и T7: резерв между двумя заочными максимумами — цена = резерв. */
    @Test
    void reserveBetweenBookMaxima() {
        LotOutcome o = run(lot(10_000, 20_000), List.of(Bidder.absentee(2, rub(25_000), SALE - DAY),
                Bidder.absentee(1, rub(15_000), SALE - 2 * DAY)));
        assertEquals(List.of("1 absentee 10000 ok", "1 absentee 15000 ok", "2 absentee 20000 ok"), Fx.bids(o));
        assertTrue(o.sold());
        assertEquals(2, o.winnerId());
        assertEquals(rub(20_000), o.hammerPrice());
    }

    /** T8: не дозвонились — страховочная телефонная ставка исполняется из книги, в канале телефона. */
    @Test
    void phoneStandInIsExecutedFromTheBook() {
        LotOutcome o = run(lot(30_000, 0), List.of(Bidder.phoneStandIn(1, rub(50_000), SALE - DAY),
                Bidder.of(2, Channel.ROOM, Strategy.MAX, rub(40_000))));
        // книга открывает с 30 000; зал до 40 000 — шаг 2 500; книга отвечает следующим шагом
        assertEquals(List.of("1 absentee 30000 ok", "2 manual 32500 ok", "1 absentee 35000 ok", "2 manual 37500 ok",
                "1 absentee 40000 ok"), Fx.bids(o));
        for (int i = 0; i < o.size(); i++) {
            if (o.type(i) == EventType.BID && o.bidderId(i) == 1) {
                assertEquals(Channel.PHONE, o.channel(i));
                assertEquals(BidKind.ABSENTEE, o.kind(i));
            }
        }
        assertEquals(1, o.winnerId());
        assertTrue(Bidder.phoneStandIn(1, 1, 0).book());
        assertFalse(Bidder.of(1, Channel.PHONE, Strategy.WAR, 1).book());
    }

    /** T1а: полшага в зале выше 100 000 ₽ — сетка дальше идёт от принятой суммы; ниже порога полшага нет. */
    @Test
    void halfStepAbove100k() {
        Behavior half = Behavior.none().withLiveHalfStepShare(1);
        List<Bidder> b = List.of(Bidder.of(1, Channel.ROOM, Strategy.WAR, rub(160_000)), Bidder.of(2, Channel.ROOM, Strategy.WAR, rub(150_000)));
        LotOutcome o = run(AuctionRules.standard(), half, Lot.live(9, rub(80_000), 0, SALE, SLOT_END), b);
        List<Long> amounts = new ArrayList<>();
        for (int i = 0; i < o.size(); i++) if (o.type(i) == EventType.BID && o.accepted(i)) amounts.add(o.amount(i) / 100);
        // 80 000 … 100 000 — целым шагом 5 000; от 100 000 — полшага (шаг 10 000 / 2) от каждой принятой суммы;
        // двое чередуются — последняя 150 000 или 155 000 (смотря кто начал)
        List<Long> want = new ArrayList<>();
        for (long a = 80_000; a <= 155_000; a += 5_000) want.add(a);
        if (amounts.size() == want.size() - 1) want.removeLast();
        assertEquals(want, amounts);
        assertEquals(1, o.winnerId());
        // без полшага в правилах — только целый шаг, все суммы на сетке
        LotOutcome n = run(AuctionRules.standard().withHalfStepFrom(0), half, Lot.live(9, rub(80_000), 0, SALE, SLOT_END), b);
        for (int i = 0; i < n.size(); i++) {
            if (n.type(i) == EventType.BID) assertTrue(IncrementTable.standard().onGrid(n.amount(i)), Fx.bids(n).toString());
        }
        assertTrue(Fx.bids(n).contains("1 manual 110000 ok") || Fx.bids(n).contains("2 manual 110000 ok"));
    }

    /** Прыжок ведущего на 2–5 шагов: ставка — точка сетки выше запроса. */
    @Test
    void auctioneerJumps() {
        Behavior jump = Behavior.none().withLiveJumpShare(1);
        IncrementTable inc = IncrementTable.standard();
        int jumps = 0;
        for (int k = 0; k < 200; k++) {
            List<Bidder> b = List.of(Bidder.of(1, Channel.ROOM, Strategy.WAR, rub(60_000)), Bidder.of(2, Channel.PHONE, Strategy.WAR, rub(55_000)));
            LotOutcome o = Fx.run(AuctionRules.standard(), jump, lot(10_000, 0), b, k);
            long prev = -1;
            for (int i = 0; i < o.size(); i++) {
                if (o.type(i) != EventType.BID || !o.accepted(i)) continue;
                if (prev > 0) {
                    long steps = 0;
                    for (long p = prev; p < o.amount(i); p = inc.minNext(p)) steps++;
                    assertTrue(inc.onGrid(o.amount(i)));
                    assertTrue(steps >= 1 && steps <= 5, "шагов " + steps);
                    if (steps >= 2) jumps++;
                }
                prev = o.amount(i);
            }
        }
        assertTrue(jumps > 500, "прыжков " + jumps);
    }

    @Test
    void noBidsPassed() {
        LotOutcome o = run(lot(10_000, 0), List.of(Bidder.of(1, Channel.ROOM, Strategy.MAX, rub(5_000))));
        assertEquals(List.of(EventType.HAMMER), Fx.types(o));
        assertFalse(o.sold());
        assertEquals(0, o.bids());
        assertEquals(o.time(0), o.closedAt());
    }

    @Test
    void eventsStayWithinTheSlot() {
        // слот 10 с, восемь воюющих с большими максимумами — времена сжимаются, порядок сохраняется
        Lot tight = Lot.live(8, rub(1_000), 0, SALE, SALE + 10 * SEC);
        List<Bidder> b = new ArrayList<>();
        for (int i = 1; i <= 8; i++) b.add(Bidder.of(i, i % 2 == 0 ? Channel.ROOM : Channel.PHONE, Strategy.WAR, rub(100_000 + i * 10_000)));
        LotOutcome o = run(AuctionRules.standard(), Behavior.standard(), tight, b);
        assertTrue(o.bids() > 40, "ставок " + o.bids());
        long prev = SALE;
        for (int i = 0; i < o.size(); i++) {
            assertTrue(o.time(i) >= prev && o.time(i) < SALE + 10 * SEC, "событие " + i + " в " + (o.time(i) - SALE));
            prev = o.time(i);
        }
        assertEquals(EventType.HAMMER, o.type(o.size() - 1));
        assertEquals(8, o.winnerId(), "наибольший максимум");
    }

    @Test
    void tempoSlots() {
        assertEquals(SALE + 60 * SEC, Schedule.liveSlotStart(SALE, 60, 1));
        assertEquals(SALE + HOUR, Schedule.liveSlotStart(SALE, 70, 70), "без накопления ошибки");
        assertEquals(Schedule.liveSlotStart(SALE, 70, 3), Schedule.liveSlotEnd(SALE, 70, 2));
    }

    /** Все каналы зала в событиях; поздние вступают после предупреждения; гонки и опоздания онлайн — с причиной. */
    @Test
    void roomPhoneOnlineAndAbsentee() {
        BidEngine e = new BidEngine(AuctionRules.standard(), Behavior.standard().withLiveRaceShare(0.3).withLiveLateOnlineShare(0.5));
        LotOutcome o = new LotOutcome();
        Set<Channel> channels = EnumSet.noneOf(Channel.class);
        Set<RejectReason> reasons = EnumSet.noneOf(RejectReason.class);
        int lateAfterWarning = 0;
        for (int k = 0; k < 2_000; k++) {
            List<Bidder> b = List.of(Bidder.absentee(1, rub(14_000), SALE - DAY), Bidder.of(2, Channel.ROOM, Strategy.EARLY, rub(16_000)),
                    Bidder.of(3, Channel.PHONE, Strategy.WAR, rub(18_000)), Bidder.of(4, Channel.ONLINE, Strategy.WAR, rub(19_000)),
                    Bidder.of(5, Channel.ROOM, Strategy.SNIPER, rub(25_000)), Bidder.of(6, Channel.ROOM, Strategy.CAUTIOUS, rub(15_000)));
            e.run(lot(10_000, 12_000), b, new SplittableRandom(k), o);
            boolean warned = false;
            for (int i = 0; i < o.size(); i++) {
                if (o.type(i) == EventType.FAIR_WARNING) warned = true;
                if (o.type(i) != EventType.BID) continue;
                channels.add(o.channel(i));
                reasons.add(o.reason(i));
                if (o.bidderId(i) == 5) {
                    assertTrue(warned, "поздний вступил до предупреждения");
                    lateAfterWarning++;
                }
            }
        }
        assertEquals(EnumSet.of(Channel.ROOM, Channel.PHONE, Channel.ONLINE, Channel.ABSENTEE), channels);
        assertTrue(reasons.containsAll(EnumSet.of(RejectReason.RACE_LOST, RejectReason.PRICE_CHANGED, RejectReason.TIED)), reasons.toString());
        assertTrue(lateAfterWarning > 1_000);
    }
}
