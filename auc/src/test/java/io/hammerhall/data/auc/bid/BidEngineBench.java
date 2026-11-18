package io.hammerhall.data.auc.bid;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Замер движка на одном ядре (не тест: запуск — {@code java … io.hammerhall.data.auc.bid.BidEngineBench [участников]
 * [лотов]}). Лоты готовятся заранее ({@link RandomLots}, ровно N участников), меряется только {@code run}: лотов в
 * секунду, событий и ставок в секунду, байт на лот — с проверкой инвариантов и без.
 */
public final class BidEngineBench {
    private BidEngineBench() {
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int lots = args.length > 1 ? Integer.parseInt(args[1]) : 50_000;
        IncrementTable inc = IncrementTable.standard();
        List<RandomLots.Case> cases = new ArrayList<>(lots);
        for (int k = 1; k <= lots; k++) cases.add(RandomLots.make(k, inc, n));
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        for (boolean check : new boolean[]{false, true}) {
            BidEngine e = new BidEngine(AuctionRules.standard().withSelfCheck(check), Behavior.standard());
            LotOutcome o = new LotOutcome();
            // прогрев
            for (int round = 0; round < 3; round++) pass(e, o, cases);
            long best = Long.MAX_VALUE, events = 0, bids = 0, online = 0, bytes = 0;
            for (int round = 0; round < 5; round++) {
                long a0 = mx.getThreadAllocatedBytes(tid);
                long t0 = System.nanoTime();
                long[] r = pass(e, o, cases);
                long dt = System.nanoTime() - t0;
                bytes = mx.getThreadAllocatedBytes(tid) - a0;
                best = Math.min(best, dt);
                events = r[0];
                bids = r[1];
                online = r[2];
            }
            double sec = best / 1e9;
            System.out.printf(Locale.ROOT, "участников %d, лотов %d (онлайн %d), проверка %s: ставок на лот %.1f, событий на лот %.1f; "
                            + "%.0f лотов/с, %.2f млн ставок/с, %.2f млн событий/с; %.0f байт на лот%n",
                    n, lots, online, check ? "да" : "нет", bids / (double) lots, events / (double) lots, lots / sec,
                    bids / sec / 1e6, events / sec / 1e6, bytes / (double) lots);
        }
    }

    /** Один проход: события, ставки, онлайн-лоты. Генератор случайности — новый на лот, как у генератора набора. */
    static long[] pass(BidEngine e, LotOutcome o, List<RandomLots.Case> cases) {
        long events = 0, bids = 0, online = 0;
        for (int i = 0; i < cases.size(); i++) {
            RandomLots.Case c = cases.get(i);
            e.run(c.lot(), c.bidders(), new SplittableRandom(c.lot().id()), o);
            events += o.size();
            bids += o.bids();
            if (c.lot().mode() == Mode.ONLINE) online++;
        }
        return new long[]{events, bids, online};
    }
}
