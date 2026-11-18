package io.hammerhall.data.auc.auction.audit;

import java.util.Arrays;

/**
 * Лот для самопроверки — значения, как они записаны в таблицы ({@code lots}, {@code bids}, {@code lot_events}; у
 * поручений — максимум и момент редакции): {@code id} выпуска, суммы — копейки, моменты — мс (секунды × 1000 + мс поля).
 * Только строки раньше среза. Один объект переиспользуется от лота к лоту.
 */
public final class AuditLot {
    // ---------------------------------------------------------------- лот
    public int lotId, saleId, lotNo, sessionNo;
    public boolean live;
    public long start, reserve, low, high;
    /** Онлайн: план конца (мс); живые — 0. */
    public long plannedMs;
    /** Открытие (мс); 0 — нет. */
    public long openedMs;
    /** Фактическое закрытие (мс); -1 — не закрыт к срезу. */
    public long closedMs;
    public int extensions, bidCount;
    /** {@code Codes.RES_*} на срез. */
    public byte result;
    public long hammer, winningBid, buyerReg, underbidderReg;
    public long publishedSec, withdrawnSec, afterSaleSec, afterSalePrice;
    public int consignorClient;
    public long cutMs, capMs, windowMs, extendMs;
    public boolean plantedDouble, plantedMissed, plantedEarly, plantedReserveAboveLow;

    // ---------------------------------------------------------------- ставки (по seq)
    public int bids;
    public long[] bId = new long[64], bReg = new long[64], bOrder = new long[64], bOrderMax = new long[64],
            bAmount = new long[64], bTime = new long[64], bKeyHi = new long[64], bKeyLo = new long[64];
    public int[] bSeq = new int[64], bClient = new int[64], bClerk = new int[64];
    public byte[] bChannel = new byte[64], bKind = new byte[64], bReason = new byte[64];
    public boolean[] bAccepted = new boolean[64], bExtended = new boolean[64], bHasKey = new boolean[64];

    // ---------------------------------------------------------------- журнал лота
    public int events;
    public int[] eSeq = new int[16], eStaff = new int[16];
    public byte[] eKind = new byte[16];
    public long[] eTime = new long[16], eBid = new long[16], eNewClose = new long[16], ePrice = new long[16];

    // ---------------------------------------------------------------- редакции поручений
    public int revs;
    public long[] rReg = new long[16], rPlaced = new long[16], rMax = new long[16];
    public boolean[] rRejected = new boolean[16];

    public void clear() {
        bids = events = revs = 0;
        plantedDouble = plantedMissed = plantedEarly = plantedReserveAboveLow = false;
    }

    public void bid(long id, int seq, long reg, int client, byte channel, byte kind, long order, long orderMax, long amount,
                    long t, boolean accepted, byte reason, boolean extended, boolean hasKey, long keyHi, long keyLo,
                    int clerk) {
        if (bids == bId.length) {
            int n = bids * 2;
            bId = Arrays.copyOf(bId, n);
            bReg = Arrays.copyOf(bReg, n);
            bOrder = Arrays.copyOf(bOrder, n);
            bOrderMax = Arrays.copyOf(bOrderMax, n);
            bAmount = Arrays.copyOf(bAmount, n);
            bTime = Arrays.copyOf(bTime, n);
            bKeyHi = Arrays.copyOf(bKeyHi, n);
            bKeyLo = Arrays.copyOf(bKeyLo, n);
            bSeq = Arrays.copyOf(bSeq, n);
            bClient = Arrays.copyOf(bClient, n);
            bClerk = Arrays.copyOf(bClerk, n);
            bChannel = Arrays.copyOf(bChannel, n);
            bKind = Arrays.copyOf(bKind, n);
            bReason = Arrays.copyOf(bReason, n);
            bAccepted = Arrays.copyOf(bAccepted, n);
            bExtended = Arrays.copyOf(bExtended, n);
            bHasKey = Arrays.copyOf(bHasKey, n);
        }
        int i = bids++;
        bId[i] = id;
        bSeq[i] = seq;
        bReg[i] = reg;
        bClient[i] = client;
        bChannel[i] = channel;
        bKind[i] = kind;
        bOrder[i] = order;
        bOrderMax[i] = orderMax;
        bAmount[i] = amount;
        bTime[i] = t;
        bAccepted[i] = accepted;
        bReason[i] = reason;
        bExtended[i] = extended;
        bHasKey[i] = hasKey;
        bKeyHi[i] = keyHi;
        bKeyLo[i] = keyLo;
        bClerk[i] = clerk;
    }

    public void event(int seq, byte kind, long t, long bid, long newClose, long price, int staff) {
        if (events == eSeq.length) {
            int n = events * 2;
            eSeq = Arrays.copyOf(eSeq, n);
            eStaff = Arrays.copyOf(eStaff, n);
            eKind = Arrays.copyOf(eKind, n);
            eTime = Arrays.copyOf(eTime, n);
            eBid = Arrays.copyOf(eBid, n);
            eNewClose = Arrays.copyOf(eNewClose, n);
            ePrice = Arrays.copyOf(ePrice, n);
        }
        int i = events++;
        eSeq[i] = seq;
        eKind[i] = kind;
        eTime[i] = t;
        eBid[i] = bid;
        eNewClose[i] = newClose;
        ePrice[i] = price;
        eStaff[i] = staff;
    }

    public void revision(long reg, long placedMs, long max, boolean rejected) {
        if (revs == rReg.length) {
            int n = revs * 2;
            rReg = Arrays.copyOf(rReg, n);
            rPlaced = Arrays.copyOf(rPlaced, n);
            rMax = Arrays.copyOf(rMax, n);
            rRejected = Arrays.copyOf(rRejected, n);
        }
        int i = revs++;
        rReg[i] = reg;
        rPlaced[i] = placedMs;
        rMax[i] = max;
        rRejected[i] = rejected;
    }
}
