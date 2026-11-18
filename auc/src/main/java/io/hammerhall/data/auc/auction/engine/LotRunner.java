package io.hammerhall.data.auc.auction.engine;

import io.hammerhall.data.auc.bid.Action;
import io.hammerhall.data.auc.bid.AuctionRules;
import io.hammerhall.data.auc.bid.Behavior;
import io.hammerhall.data.auc.bid.BidEngine;
import io.hammerhall.data.auc.bid.BidKind;
import io.hammerhall.data.auc.bid.Bidder;
import io.hammerhall.data.auc.bid.Channel;
import io.hammerhall.data.auc.bid.EventType;
import io.hammerhall.data.auc.bid.Faults;
import io.hammerhall.data.auc.bid.IncrementTable;
import io.hammerhall.data.auc.bid.Lot;
import io.hammerhall.data.auc.bid.LotOutcome;
import io.hammerhall.data.auc.bid.Mode;
import io.hammerhall.data.auc.bid.RejectReason;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Обёртка движка торгов: лот и участники ({@link LotInput}) → движок
 * ({@code BidEngine.run(lot, bidders, script, faults, random, out)}) → строки ставок, журнала лота, сводка лота и
 * редакции поручений ({@link LotRows}). Генератор решает, кто придёт, с каким максимумом, каналом и узором; движок —
 * когда ставят и что принято. Один экземпляр на поток (движок и его итог переиспользуются).
 *
 * <p>Из движка в строки:
 * <ul>
 *   <li>событие {@code BID} — строка ставки: момент (мс), участник → ключ регистрации, канал и вид — коды движка как
 *       есть, сумма, принята, причина; отказы до движка ({@link Attempt}) вливаются по времени (при равном моменте —
 *       после событий движка), порядок движка сохраняется — это {@code bids.seq};</li>
 *   <li>повтор принятой ставки ({@code hiddenRepeat}) — причина {@code duplicate} вместо публичной причины движка и ключ
 *       запроса исходной ({@code repeatOf}); принятый повтор — заложенный {@code double_bid};</li>
 *   <li>ключ запроса ({@code request_key}, 32 hex) — у ручных онлайн-ставок, от (лот, регистрация, номер попытки);</li>
 *   <li>{@code MAX} — новая редакция поручения ({@code bid_orders}); отклонённый максимум — редакция {@code rejected};
 *       {@code MAX_RAISE} — строки ставки нет, новая редакция {@code raise_leading}, прежняя — {@code superseded};
 *       {@code AUTO}, {@code ABSENTEE} (и ручные ставки по телефону) — ссылка на действующую редакцию; заочная и
 *       телефонная заявка — редакция 1 в момент, когда оставлена;</li>
 *   <li>{@code EXTEND} — событие {@code extended} (новый конец), а при конце = план + потолок — {@code extension_capped};
 *       ставка-причина — первая принятая в ту же миллисекунду после прежнего продления (по T10 конец двигает она), у неё
 *       {@code extended_close};</li>
 *   <li>{@code CLOSE} / {@code HAMMER} — {@code sold} или {@code bought_in}, {@code FAIR_WARNING} — {@code fair_warning};
 *       первая принятая ставка не ниже резерва (не в пользу продавца) — {@code reserve_met};</li>
 *   <li>сотрудник ставки ({@code clerk_id}): телефон — на линии; заочная, в пользу продавца, полшага и прыжок —
 *       аукционист.</li>
 * </ul>
 * Заложенные сбои ({@link Faults}) — только онлайн-лотам, по {@link FaultPlan}; что вышло — {@code planted…()} движка.
 */
public final class LotRunner {
    /** Потолок продлений сверх плана, мс (T10) — как в правилах дома движка. */
    public static final long CAP_MS = AuctionRules.HOUSE_CAP_MS;
    /** Номера попыток отказов до движка — отдельно от попыток в движке (ключи запросов не совпадают). */
    static final int PRE_ENGINE_ATTEMPTS = 1_000_000;

    private final BidEngine engine;
    private final IncrementTable inc;
    private final long capMs;
    private final RandomStreams.Stream bidStream;
    private final FaultPlan faultPlan;
    private final LotOutcome out = new LotOutcome();
    private final List<Bidder> bidders = new ArrayList<>();
    private final List<Action> script = new ArrayList<>();

    // рабочие массивы по участникам лота
    private int[] partToEng = new int[16];
    private int[] engToPart = new int[16];
    /** Строка текущей (последней не отклонённой) редакции участника; -1 — нет. */
    private int[] curRevRow = new int[16];
    /** Номер последней выданной редакции участника. */
    private short[] lastRev = new short[16];
    private int[] attemptNo = new int[16], preAttemptNo = new int[16];
    private boolean[] used = new boolean[64];
    private int[] engRow = new int[64];
    private Integer[] attemptOrder = new Integer[16];

    /**
     * @param rules     правила дома ({@code AuctionRules.house(increment_bands)})
     * @param behavior  доли поведения под коридоры
     * @param bidStream поток {@code auction.bids}: случайность движка — {@code at(ключ лота)}, ключи запросов — от него же
     * @param faultPlan какие онлайн-лоты получают заложенный сбой; {@code null} — без сбоев
     */
    public LotRunner(AuctionRules rules, Behavior behavior, RandomStreams.Stream bidStream, FaultPlan faultPlan) {
        this.engine = new BidEngine(rules, behavior);
        this.inc = rules.increments();
        this.capMs = rules.maxExtendMs();
        this.bidStream = bidStream;
        this.faultPlan = faultPlan;
    }

    public IncrementTable increments() {
        return inc;
    }

    /** Итог движка по последнему лоту (для тестов и отладки; живёт до следующего {@link #run}). */
    public LotOutcome lastOutcome() {
        return out;
    }

    /** Прогнать лот и дописать его строки в {@code dst}; возвращает индекс лота в пачке. */
    public int run(LotInput in, LotRows dst) {
        List<Participant> ps = in.participants();
        int np = ps.size();
        ensureParticipants(np);
        int lot = dst.addLot(in.lotKey(), in.live());
        dst.start[lot] = in.startKop();
        dst.reserve[lot] = in.reserveKop();
        dst.plannedMs[lot] = in.live() ? 0 : in.closeMs();
        dst.openedMs[lot] = in.openMs();
        for (int p = 0; p < np; p++) {
            curRevRow[p] = -1;
            lastRev[p] = 0;
            attemptNo[p] = 0;
            preAttemptNo[p] = 0;
        }
        // заявки до торгов — редакция 1 в момент, когда оставлены (телефон без ответа и без страховочной — тоже)
        for (int p = 0; p < np; p++) {
            Participant q = ps.get(p);
            if (!q.presetOrder() || !(q.inEngine() || q.channel() == Channel.PHONE)) continue;
            int r = dst.addRev(lot, q.registrationKey(), q.clientId(), (short) 1, q.presetKind(), q.orderMaxKop(),
                    q.placedAtMs(), Codes.OR_NONE, q.via(), q.clerkId(), q.phone());
            lastRev[p] = 1;
            curRevRow[p] = r;
        }
        ensureUsed(dst.revs - dst.revFrom[lot]);
        Arrays.fill(used, 0, dst.revs - dst.revFrom[lot], false);
        int[] att = sortedAttempts(in.attempts());
        if (in.withdrawnAtMs() > 0) {
            withdrawn(in, dst, lot, att);
            return lot;
        }
        // участники движка
        bidders.clear();
        int ne = 0;
        for (int p = 0; p < np; p++) {
            Participant q = ps.get(p);
            partToEng[p] = -1;
            if (!q.inEngine()) continue;
            partToEng[p] = ne;
            engToPart[ne] = p;
            bidders.add(new Bidder(ne + 1L, q.channel(), q.strategy(), q.maxKop(), q.placedAtMs()));
            ne++;
        }
        script.clear();
        for (Action a : in.script()) {
            int e = partToEng[a.bidder()];
            if (e < 0) throw new IllegalArgumentException("лот " + in.lotKey() + ": в сценарии участник вне движка " + a.bidder());
            script.add(new Action(a.at(), e, a.amount(), a.max(), a.repeat(), a.raise()));
        }
        Faults f = in.live() ? Faults.NONE : (in.faults() != null ? in.faults()
                : faultPlan == null ? Faults.NONE : faultPlan.forLot(in.lotKey()));
        Lot l = new Lot(in.lotKey(), in.live() ? Mode.LIVE : Mode.ONLINE,
                in.startKop(), in.reserveKop(), in.openMs(), in.closeMs());
        Rng rnd = bidStream.at(in.lotKey());
        engine.run(l, bidders, script, f, rnd::nextLong, out);
        merge(in, dst, lot, att);
        return lot;
    }

    // ---------------------------------------------------------------- лот снят до торгов

    private void withdrawn(LotInput in, LotRows dst, int lot, int[] att) {
        List<Participant> ps = in.participants();
        for (int k : att) addAttempt(in, dst, lot, in.attempts().get(k), ps);
        for (int r = dst.revFrom[lot]; r < dst.revTo[lot]; r++) {
            if (dst.rStatus[r] != Codes.OS_ACTIVE) continue;
            dst.rStatus[r] = Codes.OS_CANCELLED;
            dst.rEnded[r] = Math.max(dst.rPlaced[r], in.withdrawnAtMs());
        }
        dst.result[lot] = Codes.RES_WITHDRAWN;
        dst.closedMs[lot] = 0;
        dst.openedMs[lot] = 0;
        dst.addEvent(lot, Codes.E_WITHDRAWN, in.withdrawnAtMs(), -1, 0, 0, 0);
    }

    // ---------------------------------------------------------------- слияние событий движка и отказов до движка

    private void merge(LotInput in, LotRows dst, int lot, int[] att) {
        List<Participant> ps = in.participants();
        List<Attempt> attempts = in.attempts();
        int n = out.size();
        if (engRow.length < n) engRow = new int[Math.max(n, engRow.length * 2)];
        int ai = 0;
        boolean live = in.live();
        boolean hasBid = false;
        long price = 0;
        int leader = -1; // участник-лидер (индекс в ps), -2 — продавец
        int extendFloor = dst.bids;
        boolean reserveMet = false;
        long reserve = in.reserveKop();
        for (int i = 0; i < n; i++) {
            long t = out.time(i);
            while (ai < att.length && attempts.get(att[ai]).atMs() < t) addAttempt(in, dst, lot, attempts.get(att[ai++]), ps);
            EventType type = out.type(i);
            engRow[i] = -1;
            switch (type) {
                case BID -> {
                    int eb = out.bidderIndex(i);
                    int p = eb >= 0 ? engToPart[eb] : -1;
                    Participant q = p >= 0 ? ps.get(p) : null;
                    BidKind kind = out.kind(i);
                    Channel ch = out.channel(i);
                    boolean acc = out.accepted(i);
                    long amt = out.amount(i);
                    byte reason = (byte) out.reason(i).ordinal();
                    short rev = 0;
                    if (kind == BidKind.MAX) {
                        // поставлен максимум — новая редакция поручения (отклонённый — отклонённая редакция)
                        rev = newRevision(dst, lot, p, q, out.maxAmount(i), t, leader == p);
                        if (!acc) {
                            int r = dst.revs - 1;
                            dst.rStatus[r] = Codes.OS_REJECTED;
                            dst.rEnded[r] = t;
                        } else {
                            curRevRow[p] = dst.revs - 1;
                        }
                    } else if (p >= 0 && (kind == BidKind.AUTO || kind == BidKind.ABSENTEE
                            || (kind == BidKind.MANUAL && ch == Channel.PHONE))) {
                        if (curRevRow[p] >= 0) rev = dst.rRev[curRevRow[p]];
                        else if (kind != BidKind.MANUAL) {
                            throw new IllegalStateException("лот " + in.lotKey() + ": автоставка без поручения у участника " + p);
                        }
                    }
                    int clerk = 0;
                    if (ch == Channel.PHONE) clerk = q.clerkId();
                    else if (kind == BidKind.VENDOR || kind == BidKind.ABSENTEE) clerk = in.auctioneerId();
                    else if (live && acc && kind == BidKind.MANUAL && hasBid && amt != inc.minNext(price)) clerk = in.auctioneerId();
                    int row = dst.addBid(lot, q == null ? 0 : q.registrationKey(), q == null ? 0 : q.clientId(),
                            (byte) ch.ordinal(), (byte) kind.ordinal(), rev, amt, t, acc, reason, clerk);
                    engRow[i] = row;
                    dst.bShill[row] = out.hiddenShill(i);
                    dst.bPartner[row] = q != null && q.via() == Codes.VIA_PARTNER;
                    if (ch == Channel.ONLINE && kind == BidKind.MANUAL) {
                        if (out.hiddenRepeat(i)) {
                            int orig = engRow[out.repeatOf(i)];
                            dst.bHasKey[row] = true;
                            dst.bKeyHi[row] = dst.bKeyHi[orig];
                            dst.bKeyLo[row] = dst.bKeyLo[orig];
                            dst.bRepeatOf[row] = orig;
                            if (!acc) dst.bReason[row] = Codes.R_DUPLICATE;
                        } else {
                            setKey(dst, row, in.lotKey(), q.registrationKey(), ++attemptNo[p]);
                        }
                    }
                    if (acc) {
                        if (p >= 0 && curRevRow[p] >= 0 && rev > 0) used[curRevRow[p] - dst.revFrom[lot]] = true;
                        hasBid = true;
                        price = amt;
                        leader = p >= 0 ? p : -2;
                        if (reserve > 0 && !reserveMet && p >= 0 && amt >= reserve) {
                            reserveMet = true;
                            dst.reserveBid[lot] = row;
                            dst.addEvent(lot, Codes.E_RESERVE_MET, t, row, 0, amt, live ? in.auctioneerId() : 0);
                        }
                    }
                }
                case MAX_RAISE -> {
                    if (out.accepted(i)) {
                        int p = engToPart[out.bidderIndex(i)];
                        Participant q = ps.get(p);
                        int prev = curRevRow[p];
                        newRevision(dst, lot, p, q, out.maxAmount(i), t, true);
                        int nr = dst.revs - 1;
                        dst.rReason[nr] = dst.rRev[nr] == 1 ? Codes.OR_NONE : Codes.OR_RAISE_LEADING;
                        used[nr - dst.revFrom[lot]] = true;
                        if (prev >= 0) {
                            dst.rStatus[prev] = Codes.OS_SUPERSEDED;
                            dst.rEnded[prev] = t;
                        }
                        curRevRow[p] = dst.revs - 1;
                    }
                }
                case EXTEND -> {
                    // ставка-причина: первая принятая в эту миллисекунду после прежнего продления
                    int cause = -1;
                    for (int j = dst.bids - 1; j >= extendFloor && dst.bTime[j] == t; j--) if (dst.bAccepted[j]) cause = j;
                    if (cause < 0) throw new IllegalStateException("лот " + in.lotKey() + ": продление без принятой ставки в " + t);
                    dst.bExtended[cause] = true;
                    extendFloor = dst.bids;
                    long nc = out.closeAt(i);
                    byte k = nc == in.closeMs() + capMs ? Codes.E_EXTENSION_CAPPED : Codes.E_EXTENDED;
                    dst.addEvent(lot, k, t, cause, nc, 0, 0);
                }
                case CLOSE, HAMMER -> {
                    boolean sold = out.sold();
                    int win = sold ? lastAccepted(dst, lot) : -1;
                    dst.addEvent(lot, sold ? Codes.E_SOLD : Codes.E_BOUGHT_IN, t, win, 0, sold ? out.hammerPrice() : 0,
                            live ? in.auctioneerId() : 0);
                }
                case FAIR_WARNING -> dst.addEvent(lot, Codes.E_FAIR_WARNING, t, -1, 0, 0, in.auctioneerId());
            }
        }
        while (ai < att.length) addAttempt(in, dst, lot, attempts.get(att[ai++]), ps);
        summary(in, dst, lot);
    }

    /** Новая редакция поручения участника {@code p} (поставлен максимум). */
    private short newRevision(LotRows dst, int lot, int p, Participant q, long max, long t, boolean leading) {
        short rev = ++lastRev[p];
        byte reason = rev == 1 ? Codes.OR_NONE : (leading ? Codes.OR_RAISE_LEADING : Codes.OR_REBID_AFTER_OUTBID);
        if (rev == Short.MAX_VALUE) throw new IllegalStateException("слишком много редакций поручения");
        dst.addRev(lot, q.registrationKey(), q.clientId(), rev, q.presetOrder() ? q.presetKind() : Codes.OK_AUTO, max, t,
                reason, q.via(), q.clerkId(), q.phone());
        ensureUsed(dst.revs - dst.revFrom[lot]);
        used[dst.revs - 1 - dst.revFrom[lot]] = false;
        return rev;
    }

    private void addAttempt(LotInput in, LotRows dst, int lot, Attempt a, List<Participant> ps) {
        int p = a.participant();
        Participant q = ps.get(p);
        byte kind = q.channel() == Channel.ABSENTEE ? Codes.K_ABSENTEE : a.max() ? Codes.K_MAX : Codes.K_MANUAL;
        short rev = 0;
        if (a.max() || q.channel() == Channel.ABSENTEE) {
            rev = newRevision(dst, lot, p, q, a.amountKop(), a.atMs(), false);
            int r = dst.revs - 1;
            dst.rStatus[r] = Codes.OS_REJECTED;
            dst.rEnded[r] = a.atMs();
        }
        byte ch = (byte) q.channel().ordinal();
        int clerk = q.channel() == Channel.PHONE ? q.clerkId() : 0;
        int row = dst.addBid(lot, q.registrationKey(), q.clientId(), ch, kind, rev, a.amountKop(), a.atMs(), false,
                a.reason(), clerk);
        dst.bPartner[row] = q.via() == Codes.VIA_PARTNER;
        if (ch == Codes.CH_ONLINE && !a.max()) setKey(dst, row, in.lotKey(), q.registrationKey(), PRE_ENGINE_ATTEMPTS + ++preAttemptNo[p]);
    }

    private void setKey(LotRows dst, int row, long lotKey, long regKey, int attempt) {
        Rng k = bidStream.at(lotKey, regKey, attempt);
        dst.bHasKey[row] = true;
        dst.bKeyHi[row] = k.nextLong();
        dst.bKeyLo[row] = k.nextLong();
    }

    private static int lastAccepted(LotRows dst, int lot) {
        for (int j = dst.bids - 1; j >= dst.bidFrom[lot]; j--) if (dst.bAccepted[j]) return j;
        return -1;
    }

    // ---------------------------------------------------------------- сводка и статусы поручений

    private void summary(LotInput in, LotRows dst, int lot) {
        boolean sold = out.sold();
        dst.result[lot] = sold ? Codes.RES_SOLD : Codes.RES_BOUGHT_IN;
        dst.hammer[lot] = sold ? out.hammerPrice() : 0;
        dst.highBid[lot] = out.highBid();
        dst.closedMs[lot] = out.closedAt();
        dst.extensions[lot] = (short) out.extensions();
        dst.planted[lot] = (byte) ((out.plantedDoubleAccept() ? LotRows.P_DOUBLE : 0)
                | (out.plantedMissedExtension() ? LotRows.P_MISSED : 0) | (out.plantedEarlyClose() ? LotRows.P_EARLY : 0));
        dst.shillBids[lot] = out.shillBids();
        dst.shillWon[lot] = out.shillWon();
        // наибольшая и вторая принятые ставки участников (не продавца): суммы растут — с конца первая своя и есть наибольшая
        long topReg = 0, secReg = 0, topAmt = 0, secAmt = 0;
        int topCl = 0, secCl = 0;
        for (int j = dst.bidTo[lot] - 1; j >= dst.bidFrom[lot]; j--) {
            if (!dst.bAccepted[j] || dst.bReg[j] == 0) continue;
            if (topReg == 0) {
                topReg = dst.bReg[j];
                topAmt = dst.bAmount[j];
                topCl = dst.bClient[j];
            } else if (dst.bReg[j] != topReg) {
                secReg = dst.bReg[j];
                secAmt = dst.bAmount[j];
                secCl = dst.bClient[j];
                break;
            }
        }
        dst.topReg[lot] = topReg;
        dst.topAmount[lot] = topAmt;
        dst.topClient[lot] = topCl;
        dst.secondReg[lot] = secReg;
        dst.secondAmount[lot] = secAmt;
        dst.secondClient[lot] = secCl;
        int win = sold ? lastAccepted(dst, lot) : -1;
        dst.winningBid[lot] = win;
        if (sold && dst.bReg[win] != topReg) {
            throw new IllegalStateException("лот " + in.lotKey() + ": победитель — не наибольшая ставка");
        }
        long close = dst.closedMs[lot];
        // статусы редакций (окончательные; состояние на срез ставит запись)
        for (int r = dst.revFrom[lot]; r < dst.revTo[lot]; r++) {
            byte st = dst.rStatus[r];
            if (st == Codes.OS_REJECTED || st == Codes.OS_SUPERSEDED) continue;
            long reg = dst.rReg[r];
            // есть ли у участника более поздняя не отклонённая редакция
            int next = -1;
            for (int s = r + 1; s < dst.revTo[lot]; s++) {
                if (dst.rReg[s] == reg && dst.rStatus[s] != Codes.OS_REJECTED) {
                    next = s;
                    break;
                }
            }
            if (next >= 0) {
                dst.rStatus[r] = Codes.OS_OUTBID;
                dst.rEnded[r] = Math.min(exceeded(dst, lot, r, close), dst.rPlaced[next]);
                continue;
            }
            if (sold && reg == topReg) {
                dst.rStatus[r] = Codes.OS_WON;
                dst.rEnded[r] = close;
            } else if (used[r - dst.revFrom[lot]]) {
                dst.rStatus[r] = Codes.OS_OUTBID;
                dst.rEnded[r] = exceeded(dst, lot, r, close);
            } else {
                dst.rStatus[r] = Codes.OS_UNUSED;
                dst.rEnded[r] = Math.max(close, dst.rPlaced[r]);
            }
        }
    }

    /** Когда максимум редакции превзошла принятая ставка другого участника (или продавца); нет — конец лота. */
    private static long exceeded(LotRows dst, int lot, int r, long close) {
        long max = dst.rMax[r];
        if (max <= 0) return Math.max(close, dst.rPlaced[r]);
        for (int j = dst.bidFrom[lot]; j < dst.bidTo[lot]; j++) {
            if (dst.bAccepted[j] && dst.bReg[j] != dst.rReg[r] && dst.bAmount[j] > max && dst.bTime[j] >= dst.rPlaced[r]) {
                return dst.bTime[j];
            }
        }
        return Math.max(close, dst.rPlaced[r]);
    }

    // ---------------------------------------------------------------- рабочие массивы

    private int[] sortedAttempts(List<Attempt> a) {
        int n = a.size();
        if (n == 0) return new int[0];
        if (attemptOrder.length < n) attemptOrder = new Integer[Math.max(n, attemptOrder.length * 2)];
        for (int i = 0; i < n; i++) attemptOrder[i] = i;
        Arrays.sort(attemptOrder, 0, n, (x, y) -> {
            int c = Long.compare(a.get(x).atMs(), a.get(y).atMs());
            return c != 0 ? c : Integer.compare(x, y);
        });
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = attemptOrder[i];
        return r;
    }

    private void ensureParticipants(int n) {
        if (partToEng.length >= n) return;
        int m = Math.max(n, partToEng.length * 2);
        partToEng = new int[m];
        engToPart = new int[m];
        curRevRow = new int[m];
        lastRev = new short[m];
        attemptNo = new int[m];
        preAttemptNo = new int[m];
    }

    private void ensureUsed(int n) {
        if (used.length < n) used = Arrays.copyOf(used, Math.max(n, used.length * 2));
    }

    /** Причина отказа движка → код таблицы (порядок перечисления движка = {@link Codes#REASONS}). */
    static byte reasonOf(RejectReason r) {
        return (byte) r.ordinal();
    }
}
