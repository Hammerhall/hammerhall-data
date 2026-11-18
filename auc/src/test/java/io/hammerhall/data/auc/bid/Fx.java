package io.hammerhall.data.auc.bid;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Общее для ручных примеров: время, рубли, прогон лота, ставки строками. */
final class Fx {
    static final long SEC = 1_000, MIN = 60_000, HOUR = 3_600_000, DAY = 24 * HOUR;
    /** Онлайн-лот: открыт 01.03.2026, закрывается через 7 дней. */
    static final long OPEN = RandomLots.BASE, CLOSE = OPEN + 7 * DAY;

    private Fx() {
    }

    static long rub(long r) {
        return r * 100;
    }

    /** Онлайн-лот без резерва со стартовой ценой в рублях. */
    static Lot online(long startRub) {
        return Lot.online(1, rub(startRub), 0, OPEN, CLOSE);
    }

    /** {@code n} участников сценария (ключи 1…n) с большим максимумом. */
    static List<Bidder> scripted(int n) {
        List<Bidder> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(Bidder.online(i, Strategy.SCRIPTED, rub(10_000_000)));
        return out;
    }

    static LotOutcome run(AuctionRules rules, Lot lot, List<Bidder> bidders, Action... script) {
        return new BidEngine(rules, Behavior.none()).run(lot, bidders, List.of(script), new SplittableRandom(1), new LotOutcome());
    }

    static LotOutcome run(Lot lot, List<Bidder> bidders, Action... script) {
        return run(AuctionRules.standard(), lot, bidders, script);
    }

    static LotOutcome run(AuctionRules rules, Behavior beh, Lot lot, List<Bidder> bidders, long seed) {
        return new BidEngine(rules, beh).run(lot, bidders, new SplittableRandom(seed), new LotOutcome());
    }

    /** Ставки строками «участник вид рубли ok|причина» (участник продавца — «-»). */
    static List<String> bids(LotOutcome o) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < o.size(); i++) {
            if (o.type(i) != EventType.BID) continue;
            String who = o.bidderId(i) == 0 ? "-" : String.valueOf(o.bidderId(i));
            out.add(who + " " + o.kind(i).code() + " " + o.amount(i) / 100 + " " + (o.accepted(i) ? "ok" : o.reason(i).code()));
        }
        return out;
    }

    /** Виды событий по порядку. */
    static List<EventType> types(LotOutcome o) {
        List<EventType> out = new ArrayList<>();
        for (int i = 0; i < o.size(); i++) out.add(o.type(i));
        return out;
    }
}
