package io.hammerhall.data.auc.bid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static io.hammerhall.data.auc.bid.Fx.CLOSE;
import static io.hammerhall.data.auc.bid.Fx.OPEN;
import static io.hammerhall.data.auc.bid.Fx.rub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Узоры поведения онлайн — по многим лотам: ранний ставит в первой половине срока, воюющие дают цепочки продлений,
 * осторожный не доходит до максимума, «по максимуму» действует один раз, подставной уходит до конца торгов.
 */
class StrategiesTest {
    static final int LOTS = 2_000;
    static final long W = CLOSE - OPEN;

    interface Check {
        void lot(LotOutcome o);
    }

    static void each(List<Bidder> bidders, Check c) {
        BidEngine e = new BidEngine(AuctionRules.standard(), Behavior.none());
        LotOutcome o = new LotOutcome();
        for (int k = 0; k < LOTS; k++) {
            e.run(Fx.online(10_000), bidders, new SplittableRandom(k), o);
            c.lot(o);
        }
    }

    /** Свои действия участника (не автоставки за него). */
    static boolean own(LotOutcome o, int i, long id) {
        return o.type(i) == EventType.BID && o.bidderId(i) == id && !o.kind(i).auto();
    }

    @Test
    void earlyBidsInTheFirstHalf() {
        int[] first = {0, 0};
        each(List.of(Bidder.online(1, Strategy.EARLY, rub(50_000)), Bidder.online(2, Strategy.MAX, rub(30_000))), o -> {
            for (int i = 0; i < o.size(); i++) {
                if (own(o, i, 1)) {
                    first[0]++;
                    if (o.time(i) - OPEN < W / 2 + 2_000) first[1]++;
                    break;
                }
            }
        });
        assertEquals(LOTS, first[0], "ранний ставил в каждом лоте");
        assertEquals(first[0], first[1], "первая ставка раннего — в первой половине срока");
    }

    /**
     * Двое воюющих: шагами, по очереди. Прежний тест держал один ответ для всех ({@code ≥ 10 с}, медиана больше 30 с) —
     * теперь ответов два, и проверяются оба:
     * <ul>
     *   <li>бой (цена ниже половины максимума отвечающего): ответ за {@code 3 с + Exp(15 с)}, почти всегда до конца окна;
     *       уставшие ставят максимумы — выигрывает больший;</li>
     *   <li>у своего предела — ответ позднего {@code 10 с + Exp(105 с)}: медленнее окна (2 мин) опаздывает, и лот может
     *       уйти меньшему максимуму.</li>
     * </ul>
     * В обоих случаях война в окне тянет продления: закрытие — через 2 мин после последней ставки (не дальше потолка).
     */
    @Test
    void priceWarChainsExtensions() {
        // бой: максимумы далеко от стартовой 10 000 ₽
        War duel = war(rub(80_000), rub(70_000));
        assertTrue(duel.extended3 > LOTS / 2, duel.toString());
        assertEquals(0, duel.selfOutbid, "ни один не перебивает сам себя (кроме своего максимума целиком перед большим)");
        assertTrue(duel.smallerWins < LOTS / 50, "в бою опаздывают редко — выигрывает больший максимум: " + duel);
        assertTrue(duel.gap(10) >= BidEngine.DUEL_REACT_MIN, "ответ в бою — не раньше чем через 3 с: " + duel);
        assertTrue(duel.gap(50) > 8 * Fx.SEC && duel.gap(50) < 20 * Fx.SEC, "медиана ответа в бою ≈ 3 с + 15 с · ln 2: " + duel);
        assertTrue(duel.gap(99) < 2 * Fx.MIN, "ответ в бою успевает до конца окна: " + duel);
        // у предела: стартовая 10 000 ₽ — больше половины обоих максимумов
        War edge = war(rub(18_000), rub(16_000));
        assertTrue(edge.extended3 > LOTS / 20, edge.toString());
        assertEquals(0, edge.selfOutbid, "ни один не перебивает сам себя");
        assertTrue(edge.smallerWins > 0 && edge.smallerWins < LOTS / 2, "меньший максимум выигрывает, только когда больший опоздал: " + edge);
        // быстрее 10 с — только повтор после отказа
        assertTrue(edge.gap(10) >= BidEngine.WAR_REACT_MIN, "ответ у предела — не раньше чем через 10 с: " + edge);
        assertTrue(edge.gap(50) > 30 * Fx.SEC && edge.gap(100) > 2 * Fx.MIN - 10 * Fx.SEC, "медиана и хвост ответа у предела: " + edge);
    }

    /** Итог пары воюющих по {@link #LOTS} лотам: продления, ставки сами за собой, победы меньшего, ответы ручной ставкой. */
    record War(int extended3, int selfOutbid, int smallerWins, List<Long> gaps) {
        long gap(int pct) {
            return gaps.get(Math.min(gaps.size() - 1, gaps.size() * pct / 100));
        }

        @Override
        public String toString() {
            return "лотов с ≥ 3 продлениями " + extended3 + ", сам за собой " + selfOutbid + ", у меньшего " + smallerWins
                    + ", ответов " + gaps.size() + (gaps.isEmpty() ? "" : ", медиана " + gap(50));
        }
    }

    static War war(long max1, long max2) {
        int[] s = {0, 0, 0};
        List<Long> gaps = new ArrayList<>();
        long[] max = {0, max1, max2};
        each(List.of(Bidder.online(1, Strategy.WAR, max1), Bidder.online(2, Strategy.WAR, max2)), o -> {
            if (o.extensions() >= 3) s[0]++;
            long prev = 0, prevAt = 0, lastAt = 0;
            BidKind prevKind = null;
            boolean entered = false;
            for (int i = 0; i < o.size(); i++) {
                if (o.type(i) != EventType.BID || !o.accepted(i)) continue;
                long who = o.bidderId(i);
                // сам за собой — только автоставка по правилам: «меньший максимум целиком» (T5) — лидер на весь свой
                // максимум перед большим максимумом соперника (уставшие в бою ставят максимумы оба) — и ответ на равную
                // ставку соперника, отклонённую {@code tied}
                boolean tied = i > 0 && o.type(i - 1) == EventType.BID && o.reason(i - 1) == RejectReason.TIED && o.amount(i - 1) == o.amount(i);
                if (who == prev && !(o.kind(i) == BidKind.AUTO && (tied || o.amount(i) == max[(int) who] && rivalMaxNext(o, i, who)))) s[1]++;
                // ответ на перебитую ставку; первая смена участника — вступление второго, не ответ
                if (o.kind(i) == BidKind.MANUAL && prevKind == BidKind.MANUAL && who != prev && prev != 0) {
                    if (entered) gaps.add(o.time(i) - prevAt);
                    entered = true;
                }
                prev = who;
                prevKind = o.kind(i);
                prevAt = lastAt = o.time(i);
            }
            if (o.winnerId() == 2) s[2]++;
            if (o.extensions() > 0) assertEquals(Math.min(lastAt + 2 * Fx.MIN, CLOSE + 60 * Fx.MIN), o.closedAt());
        });
        gaps.sort(null);
        assertTrue(gaps.size() > LOTS, "ответов ручной ставкой " + gaps.size());
        return new War(s[0], s[1], s[2], gaps);
    }

    /**
     * Узор ценовой войны: пара воюющих с максимумами ×3,2–6,5 верхней оценки (так их ставит спрос) —
     * двое чередуются не меньше 12 раз, молоток не меньше 3× верхней оценки, — почти всегда; продлений у такого лота
     * в среднем 9–14 (война — в окне мягкого закрытия), почти все закрыты в 12 мин после плана. Воюющий против не
     * воюющего отвечает как поздний — не быстрее 10 с.
     */
    @Test
    void priceWarReachesTheWarPattern() {
        long high = rub(15_000);
        BidEngine e = new BidEngine(AuctionRules.standard(), Behavior.standard());
        LotOutcome o = new LotOutcome();
        IncrementTable inc = IncrementTable.standard();
        int wars = 0, sold = 0, extended = 0, within12 = 0;
        long extSum = 0;
        for (int k = 0; k < LOTS; k++) {
            SplittableRandom r = new SplittableRandom(1_000 + k);
            List<Bidder> b = List.of(Bidder.online(1, Strategy.WAR, inc.floor((long) (high * (3.2 + 3.3 * r.nextDouble())))),
                    Bidder.online(2, Strategy.WAR, inc.floor((long) (high * (3.2 + 3.3 * r.nextDouble())))));
            e.run(Lot.online(k + 1, rub(5_000), 0, OPEN, CLOSE), b, r, o);
            if (o.extensions() > 0) {
                extended++;
                extSum += o.extensions();
                if (o.closedAt() - CLOSE <= 12 * Fx.MIN) within12++;
            }
            if (!o.sold()) continue;
            sold++;
            long prev = 0;
            int alt = 0;
            for (int i = 0; i < o.size(); i++) {
                if (o.type(i) != EventType.BID || !o.accepted(i)) continue;
                if (prev != 0 && o.bidderId(i) != prev) alt++;
                prev = o.bidderId(i);
            }
            if (alt >= 12 && o.hammerPrice() >= 3 * high) wars++;
        }
        double ext = (double) extSum / extended;
        String st = "войн " + wars + " из проданных " + sold + ", продлено " + extended + ", продлений на продлённый " + ext
                + ", в 12 мин " + within12;
        assertTrue(wars >= 0.95 * LOTS, st);
        assertTrue(ext > 8 && ext < 15, st);
        assertTrue(within12 >= 0.95 * extended, st);
        // против не воюющего (ранний с максимумом) — ответ позднего: ни одного ответа ручной ставкой быстрее 10 с
        List<Long> late = new ArrayList<>();
        each(List.of(Bidder.online(1, Strategy.WAR, rub(80_000)), Bidder.online(2, Strategy.EARLY, rub(60_000))), x -> {
            long at = -1;
            for (int i = 0; i < x.size(); i++) {
                if (x.type(i) != EventType.BID || !x.accepted(i)) continue;
                if (x.bidderId(i) == 2) at = x.time(i);
                else if (at >= 0 && x.kind(i) == BidKind.MANUAL) {
                    late.add(x.time(i) - at);
                    at = -1;
                }
            }
        });
        late.sort(null);
        assertTrue(!late.isEmpty() && late.get(late.size() / 10) >= BidEngine.WAR_REACT_MIN, "ответ против не воюющего");
    }

    /** Следующая принятая ставка — максимум соперника. */
    static boolean rivalMaxNext(LotOutcome o, int i, long who) {
        for (int j = i + 1; j < o.size(); j++) {
            if (o.type(j) != EventType.BID || !o.accepted(j)) continue;
            return o.bidderId(j) != who && o.kind(j) == BidKind.MAX;
        }
        return false;
    }

    @Test
    void cautiousStopsBelowMaximum() {
        long max = rub(40_000);
        int[] s = {0, 0};
        each(List.of(Bidder.online(1, Strategy.CAUTIOUS, max), Bidder.online(2, Strategy.WAR, rub(100_000))), o -> {
            int mine = 0;
            for (int i = 0; i < o.size(); i++) {
                if (!own(o, i, 1)) continue;
                mine++;
                assertTrue(o.amount(i) <= max * 95 / 100, "осторожный поставил " + o.amount(i));
            }
            assertTrue(mine <= 3 + 1, "ставок осторожного " + mine);   // три попытки и повтор после отказа
            if (mine > 0) s[0]++;
            if (o.winnerId() == 2) s[1]++;
        });
        assertTrue(s[0] > LOTS / 2);
        // воюющий выигрывает, если не опоздал с ответом в окно мягкого закрытия
        assertTrue(s[1] > LOTS * 95 / 100, "выиграл воюющий " + s[1]);
    }

    @Test
    void maxBidderActsOnce() {
        each(List.of(Bidder.online(1, Strategy.MAX, rub(30_000)), Bidder.online(2, Strategy.EARLY, rub(25_000)),
                Bidder.online(3, Strategy.WAR, rub(35_000))), o -> {
            int mine = 0;
            for (int i = 0; i < o.size(); i++) {
                if (!own(o, i, 1)) continue;
                mine++;
                assertEquals(BidKind.MAX, o.kind(i));
                assertEquals(rub(30_000), o.maxAmount(i));
            }
            assertTrue(mine <= 1, "«по максимуму» действовал " + mine + " раз");
        });
    }

    @Test
    void shillLeavesBeforeTheEnd() {
        long stop = CLOSE - (long) (W * BidEngine.SHILL_STOP);
        int[] s = {0};
        each(List.of(Bidder.online(1, Strategy.EARLY, rub(40_000)), Bidder.online(2, Strategy.SHILL, rub(30_000)),
                Bidder.online(3, Strategy.WAR, rub(45_000))), o -> {
            for (int i = 0; i < o.size(); i++) {
                if (!own(o, i, 2)) continue;
                s[0]++;
                assertTrue(o.time(i) < stop + 2_000, "подставной ставил за " + (CLOSE - o.time(i)) + " мс до закрытия");
                assertTrue(o.amount(i) <= rub(30_000));
            }
        });
        assertTrue(s[0] > LOTS, "подставных ставок " + s[0]);
    }
}
