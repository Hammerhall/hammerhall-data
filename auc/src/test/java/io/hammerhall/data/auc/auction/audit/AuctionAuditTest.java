package io.hammerhall.data.auc.auction.audit;

import io.hammerhall.data.auc.auction.engine.Codes;
import io.hammerhall.data.auc.auction.engine.LotInput;
import io.hammerhall.data.auc.auction.engine.LotRows;
import io.hammerhall.data.auc.auction.engine.LotRunner;
import io.hammerhall.data.auc.auction.engine.Participant;
import io.hammerhall.data.auc.bid.AuctionRules;
import io.hammerhall.data.auc.bid.Behavior;
import io.hammerhall.data.auc.bid.Channel;
import io.hammerhall.data.auc.bid.Faults;
import io.hammerhall.data.auc.bid.Strategy;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.Planted;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Самопроверка T1–T16 — независимо от движка: строки случайных лотов движка проходят чисто; порча строк ловится своей
 * проверкой; заложенные сбои движка пропускаются ровно там, где помечены.
 */
class AuctionAuditTest {
    static final long OPEN = 1_772_553_600_000L, DAY = 86_400_000L, CLOSE = OPEN + 7 * DAY, CUT = Long.MAX_VALUE / 4;
    static final java.util.Map<Long, Long> LOW = new java.util.concurrent.ConcurrentHashMap<>();
    static final Strategy[] ONLINE = {Strategy.EARLY, Strategy.SNIPER, Strategy.WAR, Strategy.CAUTIOUS, Strategy.MAX};

    static LotRunner runner() {
        Behavior b = new Behavior(0.05, 0.05, 0.05, 0.05, 0.15, 0.5, 0.04, 0.065);
        return new LotRunner(AuctionRules.house(HouseRules.standardIncrements()), b, new RandomStreams(3).stream("auction.bids"), null);
    }

    /** Случайный лот: онлайн (участники со стратегиями) или живой (зал, онлайн, заочные, телефон). */
    static LotInput randomLot(long key, Rng r) {
        boolean live = r.chance(0.3);
        long low = HouseRules.standardIncrements().floor((long) (r.logNormal(40_000, 1.2) * 100) + 5_000_00L);
        if (live) low = Math.max(low, 30_000_00L);
        LOW.put(key, low);
        long start = HouseRules.standardIncrements().floor(low * 6 / 10);
        long reserve = r.chance(0.85) ? HouseRules.standardIncrements().floor(low * 8 / 10) : 0;
        if (reserve > 0 && reserve < start) reserve = start;
        List<Participant> ps = new ArrayList<>();
        int n = r.nextInt(8);
        for (int k = 0; k < n; k++) {
            long reg = key * 100 + k + 1;
            long max = HouseRules.standardIncrements().floor((long) (low * (0.5 + 1.5 * r.nextDouble())));
            if (max <= 0) continue;
            if (!live) {
                ps.add(Participant.online(reg, 1000 + k, ONLINE[r.nextInt(ONLINE.length)], max, Codes.VIA_SITE));
            } else {
                switch (r.nextInt(4)) {
                    case 0 -> ps.add(Participant.absentee(reg, 1000 + k, max, OPEN - DAY + k, Codes.VIA_SITE));
                    case 1 -> ps.add(Participant.phone(reg, 1000 + k, Strategy.WAR, max, max / 2 > 0 ? HouseRules.standardIncrements().floor(max / 2) : 0,
                            OPEN - DAY, 7, r.chance(0.8)));
                    case 2 -> ps.add(Participant.live(reg, 1000 + k, Channel.ROOM, Strategy.EARLY, max, Codes.VIA_OFFICE));
                    default -> ps.add(Participant.live(reg, 1000 + k, Channel.ONLINE, Strategy.CAUTIOUS, max, Codes.VIA_APP));
                }
            }
        }
        return live ? LotInput.live(key, start, reserve, OPEN, OPEN + 48_000, 5, ps)
                : LotInput.online(key, start, reserve, OPEN, CLOSE, ps);
    }

    /** Строки лота → лот самопроверки (как пишет оркестр: id ставок — по порядку, регистрация — её ключ). */
    static AuditLot audit(LotRows w, int i, LotInput in, Planted planted) {
        AuditLot a = new AuditLot();
        a.clear();
        a.lotId = (int) in.lotKey();
        a.saleId = (int) in.lotKey();
        a.lotNo = (int) in.lotKey();
        a.live = in.live();
        a.start = in.startKop();
        a.reserve = in.reserveKop();
        a.low = LOW.get(in.lotKey());
        a.high = a.low * 2;
        a.plannedMs = in.live() ? 0 : in.closeMs();
        a.openedMs = w.openedMs[i];
        a.closedMs = w.closedMs[i];
        a.extensions = w.extensions[i];
        a.publishedSec = (OPEN - 3 * DAY) / 1000;
        a.consignorClient = 99;
        a.cutMs = CUT;
        a.capMs = 3_600_000;
        a.windowMs = 120_000;
        a.extendMs = 120_000;
        int acc = 0;
        for (int b = w.bidFrom[i]; b < w.bidTo[i]; b++) {
            long omax = 0;
            if (w.bRev[b] > 0) {
                for (int k = w.revFrom[i]; k < w.revTo[i]; k++) if (w.rReg[k] == w.bReg[b] && w.rRev[k] == w.bRev[b]) omax = w.rMax[k];
            }
            a.bid(b + 1, b - w.bidFrom[i] + 1, w.bReg[b], w.bClient[b], w.bChannel[b], w.bKind[b], w.bRev[b] > 0 ? w.bRev[b] : 0,
                    omax, w.bAmount[b], w.bTime[b], w.bAccepted[b], w.bReason[b], w.bExtended[b], w.bHasKey[b], w.bKeyHi[b],
                    w.bKeyLo[b], w.bClerk[b]);
            if (w.bAccepted[b]) acc++;
        }
        for (int k = w.revFrom[i]; k < w.revTo[i]; k++) a.revision(w.rReg[k], w.rPlaced[k], w.rMax[k], w.rStatus[k] == Codes.OS_REJECTED);
        a.bidCount = acc;
        boolean sold = w.result[i] == Codes.RES_SOLD;
        a.result = w.result[i];
        a.hammer = sold ? w.hammer[i] : 0;
        a.winningBid = sold ? w.winningBid[i] + 1 : 0;
        a.buyerReg = sold ? w.topReg[i] : 0;
        a.underbidderReg = sold ? w.secondReg[i] : 0;
        int seq = 0;
        a.event(++seq, Codes.E_PUBLISHED, a.publishedSec * 1000, 0, 0, 0, 0);
        a.event(++seq, Codes.E_OPENED, a.openedMs, 0, 0, 0, 0);
        for (int e = w.evFrom[i]; e < w.evTo[i]; e++) {
            a.event(++seq, w.eKind[e], w.eTime[e], w.eBid[e] >= 0 ? w.eBid[e] + 1 : 0, w.eNewClose[e], w.ePrice[e], w.eStaff[e]);
        }
        a.plantedDouble = (w.planted[i] & LotRows.P_DOUBLE) != 0 && planted.has("double_bid", a.lotId);
        a.plantedMissed = (w.planted[i] & LotRows.P_MISSED) != 0 && planted.has("missed_extension", a.lotId);
        a.plantedEarly = (w.planted[i] & LotRows.P_EARLY) != 0 && planted.has("early_close", a.lotId);
        return a;
    }

    static AuctionAudit newAudit(Planted p) {
        return new AuctionAudit(HouseRules.standardIncrements(), CUT / 1000, p);
    }

    @Test
    void engineRowsPassTheIndependentCheck() {
        LotRunner run = runner();
        Rng r = new RandomStreams(11).stream("t").at(1);
        Planted p = new Planted();
        AuctionAudit audit = newAudit(p);
        long bids = 0;
        for (int k = 1; k <= 3_000; k++) {
            LotRows w = new LotRows();
            LotInput in = randomLot(k, r);
            run.run(in, w);
            bids += w.bids;
            audit.lot(audit(w, 0, in, p));
        }
        CheckReport rep = new CheckReport();
        audit.report(rep);
        assertEquals(0, audit.violations(), rep.render());
        assertTrue(bids > 10_000, "ставок " + bids);
    }

    /** Порча одного поля в строках лота — ловится своей проверкой. */
    private void tamper(String check, Consumer<AuditLot> spoil) {
        LotRunner run = runner();
        Rng r = new RandomStreams(12).stream("t").at(2);
        Planted p = new Planted();
        AuctionAudit audit = newAudit(p);
        int spoiled = 0;
        for (int k = 1; k <= 400; k++) {
            LotRows w = new LotRows();
            LotInput in = randomLot(k, r);
            run.run(in, w);
            AuditLot a = audit(w, 0, in, p);
            long before = audit.violations(check);
            spoil.accept(a);
            audit.lot(a);
            if (audit.violations(check) > before) spoiled++;
        }
        assertTrue(spoiled > 0, "порча не поймана: " + check);
    }

    @Test
    void spoiledRowsAreCaught() {
        tamper(AuctionAudit.C_GRID, a -> {
            for (int i = 0; i < a.bids; i++) if (a.bAccepted[i] && !a.live) {
                a.bAmount[i] += 1;
                return;
            }
        });
        tamper(AuctionAudit.C_RISE, a -> {
            int last = -1;
            for (int i = 0; i < a.bids; i++) if (a.bAccepted[i]) last = i;
            if (last > 0) a.bAmount[last] = a.start - 100;
        });
        tamper(AuctionAudit.C_T10, a -> {
            for (int i = 0; i < a.bids; i++) if (a.bExtended[i]) {
                a.bExtended[i] = false;
                return;
            }
            if (!a.live && a.closedMs > 0) a.closedMs += 5_000;
        });
        tamper(AuctionAudit.C_REASON, a -> {
            for (int i = 0; i < a.bids; i++) if (!a.bAccepted[i] && a.bReason[i] == Codes.R_BELOW_MINIMUM) {
                a.bReason[i] = Codes.R_AFTER_CLOSE;
                return;
            }
            for (int i = 0; i < a.bids; i++) if (!a.bAccepted[i]) {
                a.bReason[i] = Codes.R_OWN_LOT;
                return;
            }
        });
        tamper(AuctionAudit.C_T16, a -> {
            if (a.result == Codes.RES_SOLD) a.hammer += 100_00;
            else a.bidCount++;
        });
        tamper(AuctionAudit.C_EVENTS, a -> {
            if (a.events > 2) a.eTime[a.events - 1] = a.eTime[0] - 1;
        });
        tamper(AuctionAudit.C_ACCEPT, a -> {
            for (int i = 0; i < a.bids; i++) if (a.bAccepted[i] && a.bReg[i] != 0) {
                a.bClient[i] = a.consignorClient;
                return;
            }
        });
    }

    @Test
    void plantedFaultsAreSkippedExactlyWhereMarked() {
        LotRunner run = runner();
        Rng r = new RandomStreams(13).stream("t").at(3);
        Faults[] fs = {Faults.ofDoubleAccept(), Faults.ofMissedExtension(), Faults.ofEarlyClose(2_000)};
        String[] codes = {"double_bid", "missed_extension", "early_close"};
        byte[] bits = {LotRows.P_DOUBLE, LotRows.P_MISSED, LotRows.P_EARLY};
        for (int f = 0; f < fs.length; f++) {
            Planted marked = new Planted(), none = new Planted();
            AuctionAudit withMarks = newAudit(marked), without = newAudit(none);
            int planted = 0;
            for (int k = 1; k <= 600 && planted < 20; k++) {
                LotInput in = randomLot(k, r);
                if (in.live() || in.participants().size() < 3) continue;
                in = in.withFaults(fs[f]);
                LotRows w = new LotRows();
                run.run(in, w);
                if ((w.planted[0] & bits[f]) == 0) continue;
                planted++;
                marked.add(codes[f], (int) in.lotKey());
                withMarks.lot(audit(w, 0, in, marked));
                without.lot(audit(w, 0, in, none));
            }
            assertTrue(planted > 0, "не заложено ни одного " + codes[f]);
            CheckReport rep = new CheckReport();
            withMarks.report(rep);
            assertEquals(0, withMarks.violations(), codes[f] + ": заложенное не пропущено\n" + rep.render());
            assertTrue(without.violations() >= planted, codes[f] + ": без пометки сбой не пойман");
        }
    }

    @Test
    void itemStateMachine() {
        AuctionAudit ok = newAudit(new Planted());
        byte[][] good = {
                {Codes.IS_RECEIVED, Codes.IS_DECLINED},
                {Codes.IS_RECEIVED, Codes.IS_ACCEPTED, Codes.IS_CATALOGUED, Codes.IS_SOLD, Codes.IS_BUYER_PAID, Codes.IS_RELEASED},
                {Codes.IS_RECEIVED, Codes.IS_ACCEPTED, Codes.IS_CATALOGUED, Codes.IS_BOUGHT_IN, Codes.IS_REOFFER_PLANNED,
                        Codes.IS_CATALOGUED, Codes.IS_SOLD, Codes.IS_BUYER_DEFAULT, Codes.IS_AFTER_SALE, Codes.IS_BUYER_PAID},
                {Codes.IS_RECEIVED, Codes.IS_ACCEPTED, Codes.IS_CATALOGUED, Codes.IS_WITHDRAWN, Codes.IS_RETURNED}};
        int item = 1;
        for (byte[] g : good) feed(ok, item++, g, 1_000);
        assertEquals(0, ok.violations());
        byte[][] bad = {
                {Codes.IS_ACCEPTED},
                {Codes.IS_RECEIVED, Codes.IS_CATALOGUED},
                {Codes.IS_RECEIVED, Codes.IS_ACCEPTED, Codes.IS_CATALOGUED, Codes.IS_SOLD, Codes.IS_RELEASED},
                {Codes.IS_RECEIVED, Codes.IS_DECLINED, Codes.IS_ACCEPTED}};
        for (byte[] g : bad) {
            AuctionAudit a = newAudit(new Planted());
            feed(a, 1, g, 1_000);
            assertEquals(1, a.violations(AuctionAudit.C_ITEM), java.util.Arrays.toString(g));
        }
        AuctionAudit back = newAudit(new Planted());
        feed(back, 1, new byte[]{Codes.IS_RECEIVED, Codes.IS_ACCEPTED}, -10);
        assertEquals(1, back.violations(AuctionAudit.C_ITEM));
    }

    private static void feed(AuctionAudit a, int item, byte[] statuses, long step) {
        a.beginItemLog();
        long t = 1_000_000;
        for (int s = 0; s < statuses.length; s++) {
            a.itemRow(item, s + 1, t, statuses[s]);
            t += step;
        }
        a.endItemLog();
    }
}
