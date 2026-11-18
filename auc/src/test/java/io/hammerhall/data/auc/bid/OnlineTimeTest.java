package io.hammerhall.data.auc.bid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static io.hammerhall.data.auc.bid.Action.bid;
import static io.hammerhall.data.auc.bid.Fx.CLOSE;
import static io.hammerhall.data.auc.bid.Fx.DAY;
import static io.hammerhall.data.auc.bid.Fx.HOUR;
import static io.hammerhall.data.auc.bid.Fx.MIN;
import static io.hammerhall.data.auc.bid.Fx.OPEN;
import static io.hammerhall.data.auc.bid.Fx.SEC;
import static io.hammerhall.data.auc.bid.Fx.rub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Онлайн-торги по времени: мягкое закрытие и цепочка продлений, ставка в момент закрытия, гонка в одну миллисекунду,
 * снайперы, повторы и ошибки ввода, волна закрытия.
 */
class OnlineTimeTest {
    static final EventType BID = EventType.BID, EXTEND = EventType.EXTEND, CLOSED = EventType.CLOSE;

    /** Новые времена закрытия из событий продления и закрытия. */
    static List<Long> closes(LotOutcome o) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < o.size(); i++) if (o.type(i) != BID) out.add(o.closeAt(i) - CLOSE);
        return out;
    }

    @Test
    void softCloseChainFromBid() {
        // окно 2 мин, продление 2 мин от поздней ставки
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2),
                bid(CLOSE - 3 * MIN, 0, rub(10_000)),     // вне окна — без продления
                bid(CLOSE - 60 * SEC, 1, rub(11_000)),    // → закрытие CLOSE + 60 с
                bid(CLOSE + 30 * SEC, 0, rub(12_000)),    // → CLOSE + 150 с
                bid(CLOSE + 140 * SEC, 1, rub(13_000)),   // → CLOSE + 260 с
                bid(CLOSE + 260 * SEC, 0, rub(14_000)));  // в сам момент закрытия — поздно
        assertEquals(List.of(BID, BID, EXTEND, BID, EXTEND, BID, EXTEND, CLOSED, BID), Fx.types(o));
        assertEquals(List.of(60 * SEC, 150 * SEC, 260 * SEC, 260 * SEC), closes(o));
        assertEquals(List.of("1 manual 10000 ok", "2 manual 11000 ok", "1 manual 12000 ok", "2 manual 13000 ok",
                "1 manual 14000 after_close"), Fx.bids(o));
        assertEquals(3, o.extensions());
        assertEquals(CLOSE + 260 * SEC, o.closedAt());
        assertEquals(2, o.winnerId());
        assertEquals(rub(13_000), o.hammerPrice());
        // продление — в момент поздней ставки
        assertEquals(o.time(1), o.time(2));
    }

    @Test
    void softCloseChainFromClose() {
        AuctionRules r = AuctionRules.standard().withSoftClose(2 * MIN, 2 * MIN, AuctionRules.Extension.FROM_CLOSE);
        LotOutcome o = Fx.run(r, Fx.online(10_000), Fx.scripted(2),
                bid(CLOSE - 60 * SEC, 0, rub(10_000)),    // → CLOSE + 2 мин
                bid(CLOSE + 30 * SEC, 1, rub(11_000)),    // → CLOSE + 4 мин
                bid(CLOSE + 140 * SEC, 0, rub(12_000)));  // → CLOSE + 6 мин
        assertEquals(List.of(2 * MIN, 4 * MIN, 6 * MIN, 6 * MIN), closes(o));
        assertEquals(3, o.extensions());
    }

    /** T10: потолок продлений — по времени, не дальше планового конца + потолок (здесь 5 мин). */
    @Test
    void extensionCapByTime() {
        AuctionRules r = AuctionRules.standard().withMaxExtendMs(5 * MIN);
        LotOutcome o = Fx.run(r, Fx.online(10_000), Fx.scripted(2),
                bid(CLOSE - 60 * SEC, 0, rub(10_000)),    // → CLOSE + 60 с
                bid(CLOSE + 50 * SEC, 1, rub(11_000)),    // → CLOSE + 170 с
                bid(CLOSE + 160 * SEC, 0, rub(12_000)),   // → CLOSE + 280 с
                bid(CLOSE + 270 * SEC, 1, rub(13_000)),   // по ставке — CLOSE + 390 с, по потолку — CLOSE + 300 с
                bid(CLOSE + 295 * SEC, 0, rub(14_000)),   // в окне, но потолок достигнут — без продления
                bid(CLOSE + 300 * SEC, 1, rub(15_000)));  // в сам конец — поздно
        assertEquals(List.of(BID, EXTEND, BID, EXTEND, BID, EXTEND, BID, EXTEND, BID, CLOSED, BID), Fx.types(o));
        assertEquals(List.of(60 * SEC, 170 * SEC, 280 * SEC, 300 * SEC, 300 * SEC), closes(o));
        assertEquals(4, o.extensions());
        assertEquals(CLOSE + 5 * MIN, o.closedAt());
        assertEquals(1, o.winnerId());
        assertEquals("2 manual 15000 after_close", Fx.bids(o).get(5));
        // у дома — 60 мин сверх плана
        assertEquals(60 * MIN, AuctionRules.standard().maxExtendMs());
        assertEquals(CLOSE + 60 * MIN, AuctionRules.standard().extendedClose(CLOSE, CLOSE + 59 * MIN, CLOSE + 59 * MIN - SEC));
        assertEquals(CLOSE + 60 * MIN, AuctionRules.standard().extendedClose(CLOSE, CLOSE + 60 * MIN, CLOSE + 60 * MIN - SEC));
    }

    @Test
    void noSoftClose() {
        AuctionRules r = AuctionRules.standard().withSoftClose(0, 0, AuctionRules.Extension.FROM_BID);
        LotOutcome o = Fx.run(r, Fx.online(10_000), Fx.scripted(2),
                bid(CLOSE - 1, 0, rub(10_000)), bid(CLOSE, 1, rub(11_000)));
        assertEquals(List.of(BID, CLOSED, BID), Fx.types(o));
        assertEquals(List.of("1 manual 10000 ok", "2 manual 11000 after_close"), Fx.bids(o));
        assertEquals(CLOSE, o.closedAt());
    }

    @Test
    void sameMillisecondRaceOrderedBySeq() {
        long t = OPEN + DAY;
        // 2 и 3 ставят 11 000 в одну миллисекунду: принимается первая по номеру приёма, вторая проиграла гонку
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(3),
                bid(OPEN + HOUR, 0, rub(10_000)), bid(t, 1, rub(11_000)), bid(t, 2, rub(11_000)));
        assertEquals(List.of("1 manual 10000 ok", "2 manual 11000 ok", "3 manual 11000 race_lost"), Fx.bids(o));
        assertEquals(o.time(1), o.time(2));
        assertTrue(o.seq(1) < o.seq(2));
        assertEquals(2, o.winnerId());
        // тот же миг, обратный порядок приёма — обратный исход
        LotOutcome r = Fx.run(Fx.online(10_000), Fx.scripted(3),
                bid(OPEN + HOUR, 0, rub(10_000)), bid(t, 2, rub(11_000)), bid(t, 1, rub(11_000)));
        assertEquals(List.of("1 manual 10000 ok", "3 manual 11000 ok", "2 manual 11000 race_lost"), Fx.bids(r));
        assertEquals(3, r.winnerId());
        // в ту же миллисекунду, но сумма выше — это не гонка: обе приняты
        LotOutcome b = Fx.run(Fx.online(10_000), Fx.scripted(3),
                bid(OPEN + HOUR, 0, rub(10_000)), bid(t, 1, rub(11_000)), bid(t, 2, rub(12_000)));
        assertEquals(List.of("1 manual 10000 ok", "2 manual 11000 ok", "3 manual 12000 ok"), Fx.bids(b));
        // в следующую миллисекунду — уже не гонка, а просто ниже минимальной (участник сценария не «видел» цену)
        LotOutcome n = Fx.run(Fx.online(10_000), Fx.scripted(3),
                bid(OPEN + HOUR, 0, rub(10_000)), bid(t, 1, rub(11_000)), bid(t + 1, 2, rub(11_000)));
        assertEquals("3 manual 11000 below_minimum", Fx.bids(n).get(2));
    }

    /** Гонки, задержки, повторы и ошибки ввода — из поведения участников: причины верны (их сверяют инварианты). */
    @Test
    void racesDuplicatesAndTyposFromBehavior() {
        Behavior noisy = Behavior.none().withRaceShare(0.5).withDuplicateShare(0.5).withTypoShare(0.2);
        BidEngine e = new BidEngine(AuctionRules.standard(), noisy);
        LotOutcome o = new LotOutcome();
        int[] seen = new int[RejectReason.values().length];
        for (int k = 0; k < 2_000; k++) {
            List<Bidder> b = List.of(Bidder.online(1, Strategy.WAR, rub(60_000)), Bidder.online(2, Strategy.WAR, rub(55_000)),
                    Bidder.online(3, Strategy.EARLY, rub(30_000)), Bidder.online(4, Strategy.CAUTIOUS, rub(40_000)));
            e.run(Fx.online(10_000), b, new SplittableRandom(k), o);
            for (int i = 0; i < o.size(); i++) if (o.type(i) == BID) seen[o.reason(i).ordinal()]++;
        }
        assertTrue(seen[RejectReason.RACE_LOST.ordinal()] > 0, "нет гонок");
        assertTrue(seen[RejectReason.ALREADY_LEADING.ordinal()] > 0, "нет повторов");
        assertTrue(seen[RejectReason.BELOW_MINIMUM.ordinal()] > 0, "нет ошибок ввода");
        assertTrue(seen[RejectReason.PRICE_CHANGED.ordinal()] > 0, "нет ставок, опоздавших за ценой");
    }

    /** Снайпер ставит в последние секунды (без мягкого закрытия); часть опаздывает — отклонена после закрытия. */
    @Test
    void snipersBidInTheLastSeconds() {
        AuctionRules r = AuctionRules.standard().withSoftClose(0, 0, AuctionRules.Extension.FROM_BID);
        BidEngine e = new BidEngine(r, Behavior.none());
        LotOutcome o = new LotOutcome();
        int sniperBids = 0, late = 0;
        for (int k = 0; k < 3_000; k++) {
            List<Bidder> b = List.of(Bidder.online(1, Strategy.EARLY, rub(25_000)), Bidder.online(2, Strategy.SNIPER, rub(30_000)));
            e.run(Fx.online(10_000), b, new SplittableRandom(k), o);
            for (int i = 0; i < o.size(); i++) {
                if (o.type(i) != BID || o.bidderId(i) != 2 || o.kind(i) == BidKind.AUTO) continue;
                sniperBids++;
                assertTrue(o.time(i) >= CLOSE - 30 * SEC, "ставка снайпера за " + (CLOSE - o.time(i)) + " мс до закрытия");
                assertTrue(o.time(i) < CLOSE + SEC);
                if (o.reason(i) == RejectReason.AFTER_CLOSE) late++;
            }
        }
        assertTrue(sniperBids > 2_500, "снайпер ставил " + sniperBids);
        assertTrue(late > 0 && late < sniperBids / 5, "опоздавших " + late + " из " + sniperBids);
    }

    /** С мягким закрытием снайпер не «крадёт» лот: его ставка продлевает торги, перебитый отвечает. */
    @Test
    void softCloseDefeatsSniping() {
        BidEngine e = new BidEngine(AuctionRules.standard(), Behavior.none());
        LotOutcome o = new LotOutcome();
        int extended = 0, answered = 0;
        for (int k = 0; k < 2_000; k++) {
            List<Bidder> b = List.of(Bidder.online(1, Strategy.WAR, rub(40_000)), Bidder.online(2, Strategy.SNIPER, rub(30_000)));
            e.run(Fx.online(10_000), b, new SplittableRandom(k), o);
            if (o.extensions() > 0) extended++;
            // перебитый снайпером воюющий ставит ещё раз — уже в продлении, после планового конца
            long snipe = -1;
            boolean after = false;
            for (int i = 0; i < o.size(); i++) {
                if (o.type(i) != BID || !o.accepted(i)) continue;
                if (o.bidderId(i) == 2 && snipe < 0) snipe = o.time(i);
                else if (o.bidderId(i) == 1 && snipe >= 0 && o.kind(i) != BidKind.AUTO && o.time(i) >= CLOSE) after = true;
            }
            if (after) answered++;
        }
        assertTrue(extended > 1_800, "продлений " + extended);
        // воюющий отвечает за 10 с + Exp(85 с): чаще успевает в окно, иногда — опаздывает
        assertTrue(answered > 1_000, "воюющий ответил в продлении: " + answered);
    }

    /**
     * Гонку и «цена изменилась» объясняет только принятая ставка <i>другого</i> участника, перед которой сумма ещё
     * проходила: цену, сдвинутую своей же ставкой (и автоставкой лидера в ответ), — ниже минимальной.
     */
    @Test
    void ownBidDoesNotExplainARace() {
        long t = OPEN + DAY;
        // 1 — автоставка до 20 000; 2 ставит 12 000 (принята, 1 отвечает 13 000) и в ту же мс ещё раз 12 000
        LotOutcome o = Fx.run(Fx.online(10_000), Fx.scripted(2),
                Action.maxBid(OPEN + HOUR, 0, rub(20_000)), bid(t, 1, rub(12_000)), bid(t, 1, rub(12_000)));
        assertEquals(List.of("1 max 10000 ok", "2 manual 12000 ok", "1 auto 13000 ok", "2 manual 12000 below_minimum"), Fx.bids(o));
        // чужая принятая в ту же мс, перед которой сумма проходила, — гонка
        LotOutcome r = Fx.run(Fx.online(10_000), Fx.scripted(3),
                Action.maxBid(OPEN + HOUR, 0, rub(20_000)), bid(t, 1, rub(12_000)), bid(t, 2, rub(12_000)));
        assertEquals("3 manual 12000 race_lost", Fx.bids(r).getLast());
    }

    /** Второй участник гонки — только тот, кто уже может ставить: не раньше своего {@code placedAt} (регистрации). */
    @Test
    void racerIsNeverBeforeRegistration() {
        Behavior racy = Behavior.none().withRaceShare(1).withLateRaceShare(1);
        BidEngine e = new BidEngine(AuctionRules.standard(), racy);
        LotOutcome o = new LotOutcome();
        long late = CLOSE - 10 * MIN;
        int races = 0, lateBids = 0;
        for (int k = 0; k < 500; k++) {
            List<Bidder> b = List.of(Bidder.online(1, Strategy.WAR, rub(60_000)), Bidder.online(2, Strategy.EARLY, rub(50_000)),
                    new Bidder(3, Channel.ONLINE, Strategy.CAUTIOUS, rub(70_000), late));
            e.run(Fx.online(10_000), b, new SplittableRandom(k), o);
            for (int i = 0; i < o.size(); i++) {
                if (o.type(i) != BID) continue;
                if (o.reason(i) == RejectReason.RACE_LOST) races++;
                if (o.bidderId(i) == 3) {
                    lateBids++;
                    assertTrue(o.time(i) >= late, "участник 3 поставил до регистрации");
                }
            }
        }
        assertTrue(races > 100 && lateBids > 100, "гонок " + races + ", ставок поздно зарегистрированного " + lateBids);
    }

    /** Гонки конца лота ({@link Behavior#lateRaceShare()}) — в последние 10 с до конца, действовавшего в момент ставки. */
    @Test
    void lateRacesAreInTheLastSeconds() {
        Behavior b = Behavior.none().withLateRaceShare(1);
        BidEngine e = new BidEngine(AuctionRules.standard(), b);
        LotOutcome o = new LotOutcome();
        int races = 0;
        for (int k = 0; k < 2_000; k++) {
            List<Bidder> bs = List.of(Bidder.online(1, Strategy.SNIPER, rub(40_000)), Bidder.online(2, Strategy.WAR, rub(45_000)),
                    Bidder.online(3, Strategy.EARLY, rub(50_000)), Bidder.online(4, Strategy.MAX, rub(30_000)));
            e.run(Fx.online(10_000), bs, new SplittableRandom(k), o);
            long close = CLOSE, closeAtMs = CLOSE, ms = Long.MIN_VALUE;
            for (int i = 0; i < o.size(); i++) {
                if (o.time(i) != ms) {
                    ms = o.time(i);
                    closeAtMs = close;
                }
                if (o.type(i) == EXTEND) close = o.closeAt(i);
                if (o.type(i) == BID && o.reason(i) == RejectReason.RACE_LOST) {
                    races++;
                    assertTrue(o.time(i) >= closeAtMs - 10 * SEC, "гонка за " + (closeAtMs - o.time(i)) + " мс до конца");
                }
            }
        }
        assertTrue(races > 100, "гонок " + races);
    }

    @Test
    void closingWave() {
        long first = CLOSE;
        assertEquals(first, Schedule.waveClose(first, 30 * SEC, 0));
        assertEquals(first + 30 * SEC * 99, Schedule.waveClose(first, 30 * SEC, 99));
        // продление одного лота других не сдвигает: закрытие следующего — из волны, не из итога предыдущего
        LotOutcome a = Fx.run(Lot.online(1, rub(10_000), 0, OPEN, Schedule.waveClose(first, MIN, 0)), Fx.scripted(1),
                bid(first - SEC, 0, rub(10_000)));
        assertEquals(first - SEC + 2 * MIN, a.closedAt());
        LotOutcome b = Fx.run(Lot.online(2, rub(10_000), 0, OPEN, Schedule.waveClose(first, MIN, 1)), Fx.scripted(1));
        assertEquals(first + MIN, b.closedAt());
    }
}
