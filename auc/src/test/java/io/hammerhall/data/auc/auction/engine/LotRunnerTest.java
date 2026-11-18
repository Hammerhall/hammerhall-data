package io.hammerhall.data.auc.auction.engine;

import io.hammerhall.data.auc.bid.Action;
import io.hammerhall.data.auc.bid.AuctionRules;
import io.hammerhall.data.auc.bid.Behavior;
import io.hammerhall.data.auc.bid.Channel;
import io.hammerhall.data.auc.bid.Faults;
import io.hammerhall.data.auc.bid.IncrementTable;
import io.hammerhall.data.auc.bid.Strategy;
import io.hammerhall.data.core.random.RandomStreams;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Обёртка движка на сценарных лотах ({@link Action}) → точные строки ставок, журнала лота, сводки и поручений. */
class LotRunnerTest {
    static final long SEC = 1_000, MIN = 60_000, HOUR = 3_600_000, DAY = 24 * HOUR;
    /** Онлайн-лот: ставки открыты 03.03.2026 16:00 UTC, план — через 7 суток. */
    static final long OPEN = 1_772_553_600_000L, CLOSE = OPEN + 7 * DAY;
    static final IncrementTable INC = IncrementTable.rub(0, 50, 1_000, 100, 3_000, 250, 6_000, 500, 10_000, 1_000,
            30_000, 2_500, 60_000, 5_000, 100_000, 10_000, 300_000, 25_000, 600_000, 50_000, 1_000_000, 100_000,
            3_000_000, 250_000, 6_000_000, 500_000, 10_000_000, 1_000_000);

    static long rub(long r) {
        return r * 100;
    }

    static LotRunner runner() {
        return runner(Behavior.none());
    }

    static LotRunner runner(Behavior b) {
        return new LotRunner(AuctionRules.house(INC), b, new RandomStreams(7).stream("auction.bids"), null);
    }

    /** Участники сценария: ключи регистраций 101, 102, …, клиенты 11, 12, … */
    static List<Participant> scripted(int n) {
        List<Participant> ps = new ArrayList<>();
        for (int i = 0; i < n; i++) ps.add(Participant.online(101 + i, 11 + i, Strategy.SCRIPTED, rub(10_000_000), Codes.VIA_SITE));
        return ps;
    }

    /** Ставки строками: «рег канал вид сумма ok|причина [ext] [rN]». */
    static List<String> bids(LotRows r, int lot) {
        List<String> out = new ArrayList<>();
        for (int j = r.bidFrom[lot]; j < r.bidTo[lot]; j++) {
            StringBuilder sb = new StringBuilder();
            sb.append(r.bReg[j] == 0 ? "-" : String.valueOf(r.bReg[j])).append(' ').append(Codes.CHANNELS.get(r.bChannel[j]))
                    .append(' ').append(Codes.KINDS.get(r.bKind[j])).append(' ').append(r.bAmount[j] / 100).append(' ')
                    .append(r.bAccepted[j] ? "ok" : Codes.REASONS.get(r.bReason[j]));
            if (r.bExtended[j]) sb.append(" ext");
            if (r.bRev[j] > 0) sb.append(" r").append(r.bRev[j]);
            out.add(sb.toString());
        }
        return out;
    }

    static List<String> events(LotRows r, int lot) {
        List<String> out = new ArrayList<>();
        for (int e = r.evFrom[lot]; e < r.evTo[lot]; e++) {
            StringBuilder sb = new StringBuilder(Codes.EVENTS.get(r.eKind[e]));
            if (r.eBid[e] >= 0) sb.append(" #").append(r.eBid[e] - r.bidFrom[lot] + 1);
            if (r.eNewClose[e] > 0) sb.append(" до +").append((r.eNewClose[e] - CLOSE) / SEC).append("с");
            if (r.ePrice[e] > 0) sb.append(' ').append(r.ePrice[e] / 100);
            out.add(sb.toString());
        }
        return out;
    }

    static List<String> revs(LotRows r, int lot) {
        List<String> out = new ArrayList<>();
        for (int k = r.revFrom[lot]; k < r.revTo[lot]; k++) {
            out.add(r.rReg[k] + " r" + r.rRev[k] + " " + Codes.ORDER_KINDS.get(r.rKind[k]) + " " + r.rMax[k] / 100 + " "
                    + Codes.ORDER_STATUSES.get(r.rStatus[k])
                    + (r.rReason[k] > 0 ? " " + Codes.ORDER_REASONS.get(r.rReason[k]) : ""));
        }
        return out;
    }

    @Test
    void manualBidsAndLateExtension() {
        LotRows r = new LotRows();
        LotInput in = LotInput.online(1, rub(1_000), 0, OPEN, CLOSE, scripted(2)).withScript(List.of(
                Action.bid(OPEN + HOUR, 0, rub(1_000)),
                Action.bid(OPEN + 2 * HOUR, 1, rub(1_100)),
                Action.bid(CLOSE - 30 * SEC, 0, rub(1_200)),
                Action.bid(CLOSE + 60 * SEC, 1, rub(1_300))));
        int lot = runner().run(in, r);
        assertEquals(List.of("101 online manual 1000 ok", "102 online manual 1100 ok", "101 online manual 1200 ok ext",
                "102 online manual 1300 ok ext"), bids(r, lot));
        assertEquals(List.of("extended #3 до +90с", "extended #4 до +180с", "sold #4 1300"), events(r, lot));
        assertEquals(Codes.RES_SOLD, r.result[lot]);
        assertEquals(rub(1_300), r.hammer[lot]);
        assertEquals(102, r.topReg[lot]);
        assertEquals(101, r.secondReg[lot]);
        assertEquals(rub(1_200), r.secondAmount[lot]);
        assertEquals(CLOSE + 180 * SEC, r.closedMs[lot]);
        assertEquals(2, r.extensions[lot]);
        assertEquals(r.bidFrom[lot] + 3, r.winningBid[lot]);
        // ключи запросов: 32 hex, у всех разные
        List<String> keys = new ArrayList<>();
        for (int j = r.bidFrom[lot]; j < r.bidTo[lot]; j++) {
            String k = r.requestKey(j);
            assertEquals(32, k.length());
            assertTrue(k.matches("[0-9a-f]{32}"));
            assertFalse(keys.contains(k));
            keys.add(k);
        }
        assertEquals(List.of(), revs(r, lot));
    }

    /**
     * Двойник регистрации (T14 в full, лот 213726): один клиент двумя участниками лота с одной регистрацией. В движке это
     * два независимых участника, а ключ запроса — от (лот, регистрация, номер попытки участника): ручные 17 000 ₽ обоих
     * (вторая через 10 мс — гонка, {@code price_changed}) получали один ключ — «повтор ключа без duplicate», а в живых
     * лотах 148896 и 227705 — две принятые ставки с одним ключом. Такой вход — ошибка: отказ с ключом регистрации.
     */
    @Test
    void twinRegistrationIsRejected() {
        List<Participant> ps = new ArrayList<>(scripted(2));
        ps.add(Participant.online(102, 12, Strategy.SCRIPTED, rub(10_000_000), Codes.VIA_APP));
        List<Action> script = List.of(
                Action.maxBid(OPEN + HOUR, 0, rub(52_500)),
                Action.bid(OPEN + 2 * HOUR, 1, rub(17_000)),
                Action.bid(OPEN + 2 * HOUR + 10, 2, rub(17_000)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LotInput.online(213726, rub(10_000), rub(13_000), OPEN, CLOSE, ps).withScript(script));
        assertTrue(e.getMessage().contains("регистрация 102"), e.getMessage());
        // живые торги и попытки до движка — тот же вход
        assertThrows(IllegalArgumentException.class, () -> LotInput.live(148896, rub(24_000), rub(26_000), OPEN, OPEN + MIN, 7,
                List.of(Participant.live(537095, 13385, Channel.ONLINE, Strategy.WAR, rub(300_000), Codes.VIA_APP),
                        Participant.live(537095, 13385, Channel.ONLINE, Strategy.WAR, rub(300_000), Codes.VIA_APP))));
        // разные регистрации — как прежде: у каждой ручной ставки свой ключ
        LotRows r = new LotRows();
        int lot = runner().run(LotInput.online(213726, rub(10_000), rub(13_000), OPEN, CLOSE, scripted(3)).withScript(script), r);
        List<String> keys = new ArrayList<>();
        for (int j = r.bidFrom[lot]; j < r.bidTo[lot]; j++) {
            String k = r.requestKey(j);
            if (k == null) continue;
            assertFalse(keys.contains(k), "ставка " + (j - r.bidFrom[lot] + 1));
            keys.add(k);
        }
        assertEquals(2, keys.size());
    }

    @Test
    void maxAutoAndTiedMaximum() {
        LotRows r = new LotRows();
        LotInput in = LotInput.online(2, rub(1_000), 0, OPEN, CLOSE, scripted(3)).withScript(List.of(
                Action.maxBid(OPEN + HOUR, 0, rub(5_000)),
                Action.bid(OPEN + 2 * HOUR, 1, rub(1_100)),
                Action.maxBid(OPEN + 3 * HOUR, 2, rub(5_000))));
        int lot = runner().run(in, r);
        assertEquals(List.of("101 online max 1000 ok r1", "102 online manual 1100 ok", "101 online auto 1200 ok r1",
                "103 online max 5000 tied r1", "101 online auto 5000 ok r1"), bids(r, lot));
        assertEquals(List.of("101 r1 auto 5000 won", "103 r1 auto 5000 rejected"), revs(r, lot));
        assertEquals(List.of("sold #5 5000"), events(r, lot));
        // у автоставок ключа запроса нет, у ручной — есть
        assertNull(r.requestKey(r.bidFrom[lot]));
        assertTrue(r.requestKey(r.bidFrom[lot] + 1) != null);
        // второй — с наибольшей принятой ставкой среди прочих (отклонённый равный максимум не в счёт)
        assertEquals(102, r.secondReg[lot]);
    }

    @Test
    void repeatIsDuplicateWithTheSameKey() {
        LotRows r = new LotRows();
        LotInput in = LotInput.online(3, rub(1_000), 0, OPEN, CLOSE, scripted(2)).withScript(List.of(
                Action.bid(OPEN + HOUR, 0, rub(1_000)),
                Action.repeat(OPEN + HOUR + 700, 0),
                Action.bid(OPEN + 2 * HOUR, 1, rub(1_100)),
                Action.repeat(OPEN + 2 * HOUR + 400, 1),
                Action.bid(OPEN + 3 * HOUR, 0, rub(1_200)),
                Action.repeat(OPEN + 3 * HOUR + 300, 1)));
        int lot = runner().run(in, r);
        assertEquals(List.of("101 online manual 1000 ok", "101 online manual 1000 duplicate", "102 online manual 1100 ok",
                "102 online manual 1100 duplicate", "101 online manual 1200 ok", "102 online manual 1100 duplicate"),
                bids(r, lot));
        int b = r.bidFrom[lot];
        assertEquals(r.requestKey(b), r.requestKey(b + 1));
        assertEquals(r.requestKey(b + 2), r.requestKey(b + 3));
        assertEquals(r.requestKey(b + 2), r.requestKey(b + 5));
        assertNotEquals(r.requestKey(b), r.requestKey(b + 4));
        assertEquals(b, r.bRepeatOf[b + 1]);
        assertEquals(0, r.planted[lot]);
    }

    @Test
    void plantedDoubleAcceptKeepsTheKey() {
        LotRows r = new LotRows();
        LotInput in = LotInput.online(4, rub(1_000), 0, OPEN, CLOSE, scripted(2)).withScript(List.of(
                Action.bid(OPEN + HOUR, 0, rub(1_000)),
                Action.repeat(OPEN + HOUR + 700, 0))).withFaults(Faults.ofDoubleAccept());
        int lot = runner().run(in, r);
        assertEquals(List.of("101 online manual 1000 ok", "101 online manual 1100 ok"), bids(r, lot));
        int b = r.bidFrom[lot];
        assertEquals(r.requestKey(b), r.requestKey(b + 1));
        assertEquals(LotRows.P_DOUBLE, r.planted[lot]);
    }

    @Test
    void raiseOfOwnMaximumIsANewRevision() {
        LotRows r = new LotRows();
        LotInput in = LotInput.online(5, rub(1_000), 0, OPEN, CLOSE, scripted(2)).withScript(List.of(
                Action.maxBid(OPEN + HOUR, 0, rub(2_000)),
                Action.bid(OPEN + 2 * HOUR, 1, rub(1_100)),
                Action.raiseMax(OPEN + 3 * HOUR, 0, rub(5_000)),
                Action.bid(OPEN + 4 * HOUR, 1, rub(3_000))));
        int lot = runner().run(in, r);
        assertEquals(List.of("101 online max 1000 ok r1", "102 online manual 1100 ok", "101 online auto 1200 ok r1",
                "102 online manual 3000 ok", "101 online auto 3250 ok r2"), bids(r, lot));
        assertEquals(List.of("101 r1 auto 2000 superseded", "101 r2 auto 5000 won raise_leading"), revs(r, lot));
        assertEquals(OPEN + 3 * HOUR, r.rEnded[r.revFrom[lot]]);
    }

    @Test
    void extensionCapIsWrittenAsCapped() {
        LotRows r = new LotRows();
        List<Action> s = new ArrayList<>();
        long t = CLOSE - 60 * SEC;
        int who = 0;
        long amount = rub(1_000);
        // ставки через каждые 100 с: каждая продлевает лот до «ставка + 2 мин», пока не упрётся в план + 60 мин
        while (t < CLOSE + HOUR - 30 * SEC) {
            s.add(Action.bid(t, who, amount));
            who = 1 - who;
            amount = INC.minNext(amount);
            t += 100 * SEC;
        }
        s.add(Action.bid(CLOSE + HOUR - 10 * SEC, who, amount));
        int lot = runner().run(LotInput.online(6, rub(1_000), 0, OPEN, CLOSE, scripted(2)).withScript(s), r);
        List<String> ev = events(r, lot);
        assertEquals("extension_capped #37 до +3600с", ev.get(ev.size() - 2));
        assertEquals(36, ev.stream().filter(e -> e.startsWith("extended ")).count());
        assertEquals(37, r.extensions[lot]);
        assertEquals(CLOSE + HOUR, r.closedMs[lot]);
        // ставка после потолка — принята (t < конца), но конец не двигает
        List<String> b = bids(r, lot);
        assertEquals(s.size(), b.size());
        assertFalse(b.get(b.size() - 1).contains("ext"));
        assertTrue(b.get(b.size() - 2).endsWith("ext"));
    }

    @Test
    void attemptsBeforeTheEngineMergeByTime() {
        LotRows r = new LotRows();
        List<Participant> ps = new ArrayList<>(scripted(2));
        ps.add(Participant.rejectedOnly(103, 13, Channel.ONLINE, Codes.VIA_APP));
        LotInput in = LotInput.online(7, rub(1_000), 0, OPEN, CLOSE, ps).withScript(List.of(
                Action.bid(OPEN + HOUR, 0, rub(1_000)),
                Action.bid(OPEN + 3 * HOUR, 1, rub(1_100)))).withAttempts(List.of(
                new Attempt(OPEN + 2 * HOUR, 2, rub(1_150), false, Codes.R_NOT_REGISTERED),
                new Attempt(OPEN - MIN, 1, rub(1_000), false, Codes.R_NOT_OPEN),
                new Attempt(OPEN + 3 * HOUR, 2, rub(1_234), true, Codes.R_OFF_INCREMENT)));
        int lot = runner().run(in, r);
        assertEquals(List.of("102 online manual 1000 not_open", "101 online manual 1000 ok",
                "103 online manual 1150 not_registered", "102 online manual 1100 ok", "103 online max 1234 off_increment r1"),
                bids(r, lot));
        assertEquals(List.of("103 r1 auto 1234 rejected"), revs(r, lot));
        int b = r.bidFrom[lot];
        assertTrue(r.requestKey(b) != null);
        assertNull(r.requestKey(b + 4));
    }

    @Test
    void withdrawnLotCancelsOrders() {
        LotRows r = new LotRows();
        List<Participant> ps = List.of(Participant.absentee(201, 21, rub(50_000), OPEN - DAY, Codes.VIA_SITE),
                Participant.phone(202, 22, Strategy.EARLY, rub(80_000), rub(40_000), OPEN - 2 * DAY, 5, true));
        LotInput in = LotInput.live(8, rub(30_000), rub(40_000), OPEN, OPEN + 48 * SEC, 3, ps).withdrawnAt(OPEN - HOUR);
        int lot = runner().run(in, r);
        assertEquals(Codes.RES_WITHDRAWN, r.result[lot]);
        assertEquals(List.of("withdrawn"), events(r, lot));
        assertEquals(List.of("201 r1 absentee 50000 cancelled", "202 r1 phone 40000 cancelled"), revs(r, lot));
        assertEquals(List.of(), bids(r, lot));
    }

    @Test
    void liveLotRowsFollowTheRules() {
        LotRunner run = runner(new Behavior(0.01, 0.0, 0.0, 0.05, 0.15, 0.6, 0.04, 0.065));
        LotRows r = new LotRows();
        int vendor = 0, absentee = 0, phone = 0, sold = 0;
        for (int k = 0; k < 400; k++) {
            List<Participant> ps = new ArrayList<>();
            if (k % 2 == 0) {
                ps.add(Participant.absentee(1000L * k + 1, 1, rub(35_000 + 1_000L * (k % 50)), OPEN - DAY + k, Codes.VIA_SITE));
            }
            ps.add(Participant.live(1000L * k + 2, 2, Channel.ROOM, Strategy.EARLY, rub(k % 5 == 0 ? 45_000 : 90_000), Codes.VIA_OFFICE));
            ps.add(Participant.live(1000L * k + 3, 3, Channel.ONLINE, Strategy.WAR, rub(k % 7 == 0 ? 40_000 : 70_000 + 500L * (k % 40)), Codes.VIA_APP));
            ps.add(Participant.phone(1000L * k + 4, 4, Strategy.MAX, rub(85_000), rub(50_000), OPEN - DAY, 9, k % 3 != 0));
            long slot = OPEN + k * 48 * SEC;
            int lot = run.run(LotInput.live(100 + k, rub(30_000), rub(55_000), slot, slot + 48 * SEC, 3, ps), r);
            long prev = -1;
            for (int j = r.bidFrom[lot]; j < r.bidTo[lot]; j++) {
                assertTrue(r.bTime[j] >= slot && r.bTime[j] < slot + 48 * SEC);
                assertTrue(r.bTime[j] >= prev);
                prev = r.bTime[j];
                if (r.bKind[j] == Codes.K_VENDOR) {
                    vendor++;
                    assertEquals(0, r.bReg[j]);
                    assertEquals(3, r.bClerk[j]);
                    assertTrue(r.bAmount[j] < rub(55_000));
                }
                if (r.bKind[j] == Codes.K_ABSENTEE) {
                    assertTrue(r.bRev[j] > 0);
                    if (r.bChannel[j] == Codes.CH_ABSENTEE) absentee++;
                    else phone++;
                }
                if (r.bChannel[j] == Codes.CH_PHONE) assertEquals(9, r.bClerk[j]);
                assertFalse(r.bExtended[j]);
            }
            if (r.result[lot] == Codes.RES_SOLD) {
                sold++;
                assertTrue(r.hammer[lot] >= rub(55_000));
                assertTrue(r.bReg[r.winningBid[lot]] != 0);
            }
            // журнал: резерв (если дошли) раньше молотка, предупреждение — аукционистом
            List<String> ev = events(r, lot);
            String last = ev.get(ev.size() - 1);
            assertTrue(last.startsWith("sold") || last.startsWith("bought_in"), last);
            for (int e = r.evFrom[lot]; e < r.evTo[lot]; e++) assertEquals(3, r.eStaff[e]);
        }
        assertTrue(vendor > 0 && absentee > 0 && phone > 0 && sold > 100, vendor + " " + absentee + " " + phone + " " + sold);
    }
}
