package io.hammerhall.data.auc.auction.engine;

import java.util.Arrays;

/**
 * Строки торгов пачки лотов (обычно — одних торгов) компактно, без объекта на строку: лоты, ставки, события журнала
 * лота (кроме {@code published}, {@code opened}, {@code after_sale} — их ставит модуль), редакции поручений. Ставки,
 * события и редакции лота лежат подряд; у лота — их диапазоны. Ссылки — индексы внутри пачки; {@code id} выпуска
 * ставятся при записи. Деньги — копейки, время — миллисекунды Unix (UTC). Моделирование — без среза: срез отбирает
 * строки при записи.
 */
public final class LotRows {
    // ---------------------------------------------------------------- лоты
    public int lots;
    public long[] lotKey = new long[8];
    public boolean[] live = new boolean[8];
    public int[] bidFrom = new int[8], bidTo = new int[8], evFrom = new int[8], evTo = new int[8], revFrom = new int[8],
            revTo = new int[8];
    /** Итог торгов: {@link Codes#RES_SOLD}, {@link Codes#RES_BOUGHT_IN}, {@link Codes#RES_WITHDRAWN}. */
    public byte[] result = new byte[8];
    public long[] hammer = new long[8], highBid = new long[8], openedMs = new long[8], closedMs = new long[8],
            plannedMs = new long[8], reserve = new long[8], start = new long[8];
    /** Индекс ставки-победителя (в пачке) или -1. */
    public int[] winningBid = new int[8];
    /** Ставка, на которой достигнут резерв (в пачке) или -1. */
    public int[] reserveBid = new int[8];
    /**
     * Участник с наибольшей принятой ставкой (не в пользу продавца) — у проданного это победитель — и второй после него
     * (у проданного — {@code underbidder}, второй шанс): ключ регистрации (0 — нет), клиент, его наибольшая ставка.
     */
    public long[] topReg = new long[8], secondReg = new long[8], topAmount = new long[8], secondAmount = new long[8];
    public int[] topClient = new int[8], secondClient = new int[8];
    public short[] extensions = new short[8];
    /** Заложено движком: бит 1 — повтор принят дважды, 2 — продление пропущено, 4 — ранний конец. */
    public byte[] planted = new byte[8];
    /** Скрыто: ставок подставных участников, лот достался подставному. */
    public int[] shillBids = new int[8];
    public boolean[] shillWon = new boolean[8];
    public static final byte P_DOUBLE = 1, P_MISSED = 2, P_EARLY = 4;

    // ---------------------------------------------------------------- ставки (в порядке bids.seq внутри лота)
    public int bids;
    public int[] bLot = new int[64];
    /** Ключ регистрации; 0 — ставка в пользу продавца. */
    public long[] bReg = new long[64];
    public int[] bClient = new int[64];
    public byte[] bChannel = new byte[64], bKind = new byte[64], bReason = new byte[64];
    /** Редакция поручения участника на лоте (1, 2, …); 0 — без поручения. */
    public short[] bRev = new short[64];
    public long[] bAmount = new long[64], bTime = new long[64], bKeyHi = new long[64], bKeyLo = new long[64];
    public boolean[] bAccepted = new boolean[64], bExtended = new boolean[64], bHasKey = new boolean[64];
    /** Участник ставит через партнёрскую площадку (не форма сайта дома). */
    public boolean[] bPartner = new boolean[64];
    public int[] bClerk = new int[64];
    /** Скрыто: подставная ставка; повтор (индекс исходной в пачке, иначе -1). */
    public boolean[] bShill = new boolean[64];
    public int[] bRepeatOf = new int[64];

    // ---------------------------------------------------------------- события журнала лота (от движка и резерв)
    public int events;
    public int[] eLot = new int[32];
    public byte[] eKind = new byte[32];
    public long[] eTime = new long[32], eNewClose = new long[32], ePrice = new long[32];
    /** Ставка-причина (индекс в пачке) или -1. */
    public int[] eBid = new int[32];
    public int[] eStaff = new int[32];

    // ---------------------------------------------------------------- редакции поручений (bid_orders)
    public int revs;
    public int[] rLot = new int[16];
    public long[] rReg = new long[16];
    public int[] rClient = new int[16];
    public short[] rRev = new short[16];
    public byte[] rKind = new byte[16], rReason = new byte[16], rStatus = new byte[16], rVia = new byte[16],
            rPhone = new byte[16];
    /** Максимум (0 — пусто: телефон без страховочной), момент подачи, момент окончательного статуса. */
    public long[] rMax = new long[16], rPlaced = new long[16], rEnded = new long[16];
    public int[] rClerk = new int[16];

    public void clear() {
        lots = bids = events = revs = 0;
    }

    // ---------------------------------------------------------------- добавление

    int addLot(long key, boolean isLive) {
        if (lots == lotKey.length) growLots();
        int i = lots++;
        lotKey[i] = key;
        live[i] = isLive;
        bidFrom[i] = bidTo[i] = bids;
        evFrom[i] = evTo[i] = events;
        revFrom[i] = revTo[i] = revs;
        result[i] = Codes.RES_OPEN;
        hammer[i] = highBid[i] = openedMs[i] = closedMs[i] = plannedMs[i] = reserve[i] = start[i] = 0;
        winningBid[i] = reserveBid[i] = -1;
        topReg[i] = secondReg[i] = topAmount[i] = secondAmount[i] = 0;
        topClient[i] = secondClient[i] = 0;
        extensions[i] = 0;
        planted[i] = 0;
        shillBids[i] = 0;
        shillWon[i] = false;
        return i;
    }

    int addBid(int lot, long reg, int client, byte channel, byte kind, short rev, long amount, long t, boolean accepted,
               byte reason, int clerk) {
        if (bids == bLot.length) growBids();
        int i = bids++;
        bLot[i] = lot;
        bReg[i] = reg;
        bClient[i] = client;
        bChannel[i] = channel;
        bKind[i] = kind;
        bRev[i] = rev;
        bAmount[i] = amount;
        bTime[i] = t;
        bAccepted[i] = accepted;
        bReason[i] = reason;
        bExtended[i] = false;
        bHasKey[i] = false;
        bPartner[i] = false;
        bKeyHi[i] = bKeyLo[i] = 0;
        bClerk[i] = clerk;
        bShill[i] = false;
        bRepeatOf[i] = -1;
        bidTo[lot] = bids;
        return i;
    }

    int addEvent(int lot, byte kind, long t, int bid, long newClose, long price, int staff) {
        if (events == eLot.length) growEvents();
        int i = events++;
        eLot[i] = lot;
        eKind[i] = kind;
        eTime[i] = t;
        eBid[i] = bid;
        eNewClose[i] = newClose;
        ePrice[i] = price;
        eStaff[i] = staff;
        evTo[lot] = events;
        return i;
    }

    int addRev(int lot, long reg, int client, short rev, byte kind, long max, long placed, byte reason, byte via,
               int clerk, byte phone) {
        if (revs == rLot.length) growRevs();
        int i = revs++;
        rLot[i] = lot;
        rReg[i] = reg;
        rClient[i] = client;
        rRev[i] = rev;
        rKind[i] = kind;
        rMax[i] = max;
        rPlaced[i] = placed;
        rReason[i] = reason;
        rStatus[i] = Codes.OS_ACTIVE;
        rEnded[i] = 0;
        rVia[i] = via;
        rClerk[i] = clerk;
        rPhone[i] = phone;
        revTo[lot] = revs;
        return i;
    }

    // ---------------------------------------------------------------- рост массивов

    private void growLots() {
        int n = lotKey.length * 2;
        lotKey = Arrays.copyOf(lotKey, n);
        live = Arrays.copyOf(live, n);
        bidFrom = Arrays.copyOf(bidFrom, n);
        bidTo = Arrays.copyOf(bidTo, n);
        evFrom = Arrays.copyOf(evFrom, n);
        evTo = Arrays.copyOf(evTo, n);
        revFrom = Arrays.copyOf(revFrom, n);
        revTo = Arrays.copyOf(revTo, n);
        result = Arrays.copyOf(result, n);
        hammer = Arrays.copyOf(hammer, n);
        highBid = Arrays.copyOf(highBid, n);
        openedMs = Arrays.copyOf(openedMs, n);
        closedMs = Arrays.copyOf(closedMs, n);
        plannedMs = Arrays.copyOf(plannedMs, n);
        reserve = Arrays.copyOf(reserve, n);
        start = Arrays.copyOf(start, n);
        winningBid = Arrays.copyOf(winningBid, n);
        reserveBid = Arrays.copyOf(reserveBid, n);
        topReg = Arrays.copyOf(topReg, n);
        secondReg = Arrays.copyOf(secondReg, n);
        topAmount = Arrays.copyOf(topAmount, n);
        secondAmount = Arrays.copyOf(secondAmount, n);
        topClient = Arrays.copyOf(topClient, n);
        secondClient = Arrays.copyOf(secondClient, n);
        extensions = Arrays.copyOf(extensions, n);
        planted = Arrays.copyOf(planted, n);
        shillBids = Arrays.copyOf(shillBids, n);
        shillWon = Arrays.copyOf(shillWon, n);
    }

    private void growBids() {
        int n = bLot.length * 2;
        bLot = Arrays.copyOf(bLot, n);
        bReg = Arrays.copyOf(bReg, n);
        bClient = Arrays.copyOf(bClient, n);
        bChannel = Arrays.copyOf(bChannel, n);
        bKind = Arrays.copyOf(bKind, n);
        bReason = Arrays.copyOf(bReason, n);
        bRev = Arrays.copyOf(bRev, n);
        bAmount = Arrays.copyOf(bAmount, n);
        bTime = Arrays.copyOf(bTime, n);
        bKeyHi = Arrays.copyOf(bKeyHi, n);
        bKeyLo = Arrays.copyOf(bKeyLo, n);
        bAccepted = Arrays.copyOf(bAccepted, n);
        bExtended = Arrays.copyOf(bExtended, n);
        bHasKey = Arrays.copyOf(bHasKey, n);
        bPartner = Arrays.copyOf(bPartner, n);
        bClerk = Arrays.copyOf(bClerk, n);
        bShill = Arrays.copyOf(bShill, n);
        bRepeatOf = Arrays.copyOf(bRepeatOf, n);
    }

    private void growEvents() {
        int n = eLot.length * 2;
        eLot = Arrays.copyOf(eLot, n);
        eKind = Arrays.copyOf(eKind, n);
        eTime = Arrays.copyOf(eTime, n);
        eNewClose = Arrays.copyOf(eNewClose, n);
        ePrice = Arrays.copyOf(ePrice, n);
        eBid = Arrays.copyOf(eBid, n);
        eStaff = Arrays.copyOf(eStaff, n);
    }

    private void growRevs() {
        int n = rLot.length * 2;
        rLot = Arrays.copyOf(rLot, n);
        rReg = Arrays.copyOf(rReg, n);
        rClient = Arrays.copyOf(rClient, n);
        rRev = Arrays.copyOf(rRev, n);
        rKind = Arrays.copyOf(rKind, n);
        rReason = Arrays.copyOf(rReason, n);
        rStatus = Arrays.copyOf(rStatus, n);
        rVia = Arrays.copyOf(rVia, n);
        rPhone = Arrays.copyOf(rPhone, n);
        rMax = Arrays.copyOf(rMax, n);
        rPlaced = Arrays.copyOf(rPlaced, n);
        rEnded = Arrays.copyOf(rEnded, n);
        rClerk = Arrays.copyOf(rClerk, n);
    }

    /** Ключ запроса ставки — 32 hex ({@code bids.request_key}); {@code null} — ключа нет. */
    public String requestKey(int bid) {
        if (!bHasKey[bid]) return null;
        return hex(bKeyHi[bid]) + hex(bKeyLo[bid]);
    }

    private static String hex(long v) {
        String s = Long.toHexString(v);
        return "0".repeat(16 - s.length()) + s;
    }
}
