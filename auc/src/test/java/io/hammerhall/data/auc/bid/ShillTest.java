package io.hammerhall.data.auc.bid;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static io.hammerhall.data.auc.bid.Fx.rub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Подставные ставки: видны по скрытой метке, в публичных полях событий — как обычные. */
class ShillTest {

    @Test
    void hiddenMarkOnlyOnShillBids() {
        BidEngine e = new BidEngine(AuctionRules.standard(), Behavior.standard());
        LotOutcome o = new LotOutcome();
        int lotsWithShill = 0, shillWon = 0;
        Set<Channel> shillChannels = EnumSet.noneOf(Channel.class), otherManualChannels = EnumSet.noneOf(Channel.class);
        Set<BidKind> shillKinds = EnumSet.noneOf(BidKind.class);
        for (int k = 0; k < 3_000; k++) {
            boolean live = k % 2 == 1;
            List<Bidder> b = new ArrayList<>();
            if (live) {
                b.add(Bidder.of(1, Channel.ROOM, Strategy.EARLY, rub(40_000)));
                b.add(Bidder.of(2, Channel.ROOM, Strategy.SHILL, rub(30_000)));
                b.add(Bidder.of(3, Channel.ROOM, Strategy.WAR, rub(35_000)));
            } else {
                b.add(Bidder.online(1, Strategy.EARLY, rub(40_000)));
                b.add(Bidder.online(2, Strategy.SHILL, rub(30_000)));
                b.add(Bidder.online(3, Strategy.WAR, rub(35_000)));
            }
            Lot lot = live ? LiveTest.lot(10_000, 0) : Fx.online(10_000);
            e.run(lot, b, new SplittableRandom(k), o);
            int marked = 0;
            for (int i = 0; i < o.size(); i++) {
                boolean shill = o.type(i) == EventType.BID && o.bidderId(i) == 2;
                assertEquals(shill, o.hiddenShill(i), "метка — ровно у ставок подставного");
                if (shill) {
                    marked++;
                    shillChannels.add(o.channel(i));
                    shillKinds.add(o.kind(i));
                } else if (o.type(i) == EventType.BID && o.kind(i) == BidKind.MANUAL) {
                    otherManualChannels.add(o.channel(i));
                }
            }
            assertEquals(marked, o.shillBids());
            if (marked > 0) lotsWithShill++;
            assertEquals(o.sold() && o.winnerId() == 2, o.shillWon());
            if (o.shillWon()) shillWon++;
        }
        assertTrue(lotsWithShill > 1_000, "лотов с подставными ставками " + lotsWithShill);
        assertTrue(shillWon < lotsWithShill / 10, "подставному достаётся редко: " + shillWon);
        // выглядят как обычные: ручные ставки в тех же каналах, что у прочих участников
        assertEquals(EnumSet.of(BidKind.MANUAL), shillKinds);
        assertTrue(otherManualChannels.containsAll(shillChannels));
    }

    @Test
    void publicEventHasNoHiddenField() {
        List<String> names = new ArrayList<>();
        for (RecordComponent c : BidEvent.class.getRecordComponents()) names.add(c.getName());
        assertEquals(List.of("seq", "type", "at", "bidderId", "channel", "kind", "amount", "max", "accepted", "reason", "closeAt"), names);
        // публичный вид лота с подставным не несёт метки ни в одном поле и в тексте
        BidEngine e = new BidEngine(AuctionRules.standard(), Behavior.none());
        LotOutcome o = new LotOutcome();
        for (int seed = 0; seed < 100 && o.shillBids() == 0; seed++) {
            e.run(Fx.online(10_000), List.of(Bidder.online(1, Strategy.EARLY, rub(40_000)),
                    Bidder.online(2, Strategy.SHILL, rub(30_000))), new SplittableRandom(seed), o);
        }
        assertTrue(o.shillBids() > 0);
        for (BidEvent ev : o.events()) {
            assertFalse(ev.toString().contains("shill"));
            assertFalse(ev.toString().contains("подстав"));
        }
    }
}
