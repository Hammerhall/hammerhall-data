package io.hammerhall.data.auc.bid;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Свойства на случайных лотах ({@link RandomLots}): инварианты ({@link Invariants}) держатся на 100 тыс. лотов с
 * правилами по умолчанию и на 20 тыс. — с другими правилами и шумным поведением. Движок проверяет себя сам
 * ({@code selfCheck}); здесь — ещё раз снаружи, и что встречается всё, что должно встречаться.
 */
class PropertyTest {

    record Stats(int lots, int sold, int unsoldWithBids, int extended, int vendor, int shill, Set<RejectReason> reasons,
                 Set<BidKind> kinds, long bids, long[] live) {
    }

    /** Живые ставки: [принятых выше 100 000 ₽, из них полшага, принятых ручных, из них прыжков, страховочных, повторов]. */
    static void liveCounts(LotOutcome o, IncrementTable inc, long[] c) {
        long prev = -1;
        for (int i = 0; i < o.size(); i++) {
            if (o.type(i) != EventType.BID) continue;
            if (o.hiddenRepeat(i)) c[5]++;
            if (o.channel(i) == Channel.PHONE && o.kind(i) == BidKind.ABSENTEE) c[4]++;
            if (!o.accepted(i)) continue;
            if (o.mode() == Mode.LIVE && prev > 0) {
                if (o.amount(i) > AuctionRules.HOUSE_HALF_STEP_FROM) {
                    c[0]++;
                    if (o.amount(i) == prev + inc.step(prev) / 2) c[1]++;
                }
                if (o.kind(i) == BidKind.MANUAL) {
                    c[2]++;
                    if (o.amount(i) > inc.minNext(prev) && o.amount(i) >= inc.minNext(inc.minNext(prev))) c[3]++;
                }
            }
            prev = o.amount(i);
        }
    }

    static Stats run(AuctionRules rules, Behavior beh, int from, int lots) {
        return run(rules, beh, from, lots, false);
    }

    /** {@code faults} — у каждого четвёртого онлайн-лота заложенный сбой (по кругу). */
    static Stats run(AuctionRules rules, Behavior beh, int from, int lots, boolean faults) {
        return run(rules, beh, from, lots, faults, false);
    }

    /** {@code raises} — у онлайн-лотов подъёмы максимума ({@link RandomLots#raises}). */
    static Stats run(AuctionRules rules, Behavior beh, int from, int lots, boolean faults, boolean raises) {
        Faults[] all = {Faults.ofMissedExtension(), Faults.ofEarlyClose(2_000), Faults.ofDoubleAccept()};
        BidEngine e = new BidEngine(rules, beh);
        LotOutcome o = new LotOutcome();
        int sold = 0, unsold = 0, extended = 0, vendor = 0, shill = 0;
        long bids = 0;
        Set<RejectReason> reasons = EnumSet.noneOf(RejectReason.class);
        Set<BidKind> kinds = EnumSet.noneOf(BidKind.class);
        long[] live = new long[6];
        for (int k = from; k < from + lots; k++) {
            RandomLots.Case c = RandomLots.make(k, rules.increments());
            Faults f = faults && c.lot().mode() == Mode.ONLINE && k % 4 == 0 ? all[(k / 4) % all.length] : Faults.NONE;
            e.run(c.lot(), c.bidders(), raises ? RandomLots.raises(c, k) : List.of(), f, new SplittableRandom(k), o);
            assertNull(Invariants.verify(c.lot(), c.bidders(), rules, o), () -> o.toString());
            if (o.sold()) sold++;
            else if (o.highBid() > 0) unsold++;
            if (o.extensions() > 0) extended++;
            if (o.vendorBids() > 0) vendor++;
            if (o.shillBids() > 0) shill++;
            bids += o.bids();
            liveCounts(o, rules.increments(), live);
            for (int i = 0; i < o.size(); i++) {
                if (o.type(i) != EventType.BID) continue;
                reasons.add(o.reason(i));
                kinds.add(o.kind(i));
            }
        }
        return new Stats(lots, sold, unsold, extended, vendor, shill, reasons, kinds, bids, live);
    }

    @Test
    void invariantsOn100kLots() {
        Stats s = run(AuctionRules.standard(), Behavior.standard(), 1, 100_000);
        System.out.printf("100 000 лотов: продано %d, не дошли до резерва %d, с продлением %d, со ставками продавца %d, "
                + "с подставным %d, ставок %d%n", s.sold(), s.unsoldWithBids(), s.extended(), s.vendor(), s.shill(), s.bids());
        assertEquals(EnumSet.allOf(RejectReason.class), s.reasons(), "встречаются все причины отказа");
        assertEquals(EnumSet.allOf(BidKind.class), s.kinds(), "встречаются все виды ставок");
        assertTrue(s.sold() > s.lots() / 2 && s.sold() < s.lots() * 95 / 100, "продано " + s.sold());
        assertTrue(s.unsoldWithBids() > 0 && s.extended() > 0 && s.vendor() > 0 && s.shill() > 0);
        long[] c = s.live();
        System.out.printf(java.util.Locale.ROOT, "живые: полшага %.1f %% принятых выше 100 000 ₽, прыжков %.1f %% ручных; "
                + "страховочных телефонных %d, повторов %d%n", 100.0 * c[1] / c[0], 100.0 * c[3] / c[2], c[4], c[5]);
        assertTrue(c[1] > 0 && c[3] > 0 && c[4] > 0 && c[5] > 0, "полшага, прыжки, страховочные, повторы — есть");
    }

    @Test
    void invariantsUnderOtherRules() {
        Behavior noisy = new Behavior(0.2, 0.2, 0.3, 0.3, 0.5, 1.0, 0.3, 0.3);
        AuctionRules base = AuctionRules.standard();
        run(base.withSoftClose(5 * 60_000, 3 * 60_000, AuctionRules.Extension.FROM_CLOSE).withMaxExtendMs(10 * 60_000), noisy, 200_000, 20_000);
        run(base, noisy, 250_000, 20_000, true);
        run(base, Behavior.standard(), 260_000, 20_000, true, true);
        run(base.withMaxExtendMs(0).withHalfStepFrom(0), Behavior.standard(), 270_000, 20_000, true);
        run(base.withSoftClose(0, 0, AuctionRules.Extension.FROM_BID), noisy, 300_000, 20_000);
        run(base.withSoftClose(60_000, 10 * 60_000, AuctionRules.Extension.FROM_BID), Behavior.none(), 350_000, 20_000);
        run(base.withVendorBids(false).withProxyToReserve(false), noisy, 400_000, 20_000);
        run(base.withIncrements(IncrementTable.rub(0, 500, 10_000, 1_000, 50_000, 5_000, 200_000, 10_000)), noisy, 500_000, 20_000);
    }
}
