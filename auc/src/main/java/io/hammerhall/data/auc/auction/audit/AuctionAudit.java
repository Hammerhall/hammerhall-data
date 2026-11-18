package io.hammerhall.data.auc.auction.audit;

import io.hammerhall.data.auc.auction.engine.Codes;
import io.hammerhall.data.auc.bid.IncrementTable;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.Planted;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Самопроверка таблиц торгов ({@code lots}, {@code bids}, {@code lot_events}, {@code item_status_log}) —
 * <b>независимо от движка</b>: правила T1–T16 пересчитываются по записанным значениям строк и таблице шагов
 * ({@code increment_bands}, своя арифметика — без {@code BidEngine} и {@code AuctionRules}); заложенные отклонения
 * ({@code Planted}: {@code double_bid}, {@code missed_extension}, {@code early_close}, {@code reserve_above_low}) —
 * пропускаются ровно они. Лот проверяется, когда записан ({@link #lot}); журнал вещи — потоком в конце.
 */
public final class AuctionAudit {
    static final String C_GRID = "T1: принятые суммы, стартовая, резерв и максимумы — на сетке шагов (живые — до полшага)";
    static final String C_RISE = "T2: принятые ставки строго растут на шаг (живые — и полшага от 100 000 ₽), первая ≥ стартовой";
    static final String C_REASON = "T3–T15: причина каждой отклонённой ставки подтверждается пересчётом";
    static final String C_ACCEPT = "T3, T12, T13: принятая ставка — в открытом лоте, не лидера, не комитента";
    static final String C_T5 = "T5–T8: автоставки и заочные — по правилу лидера, не выше максимума поручения";
    static final String C_T9 = "T9: ставки за продавца — только живые, при резерве, строго ниже резерва";
    static final String C_T10 = "T10: конец онлайн-лота по журналу ставок; продления, потолок, ставок после конца нет";
    static final String C_T14 = "T14: повтор запроса — тот же ключ, той же регистрации, duplicate в 10 мин после принятой";
    static final String C_T16 = "T16: итог лота, молоток, победитель, вторая ставка, число принятых";
    static final String C_EVENTS = "журнал лота: порядок, публикация, открытие, резерв достигнут, итог, после торгов";
    static final String C_TIME = "время: публикация ≤ открытие ≤ ставки < закрытие; всё — раньше среза";
    static final String C_PHONE = "телефонные ставки — только у лотов с нижней оценкой от 30 000 ₽";
    static final String C_RESERVE = "резерв не выше нижней оценки (кроме заложенного reserve_above_low)";
    static final String C_LIVE = "живые: лоты сессии по номерам, открытие следующего — не раньше молотка, слот ≥ 20 с, ≤ 250 лотов";
    static final String C_ITEM = "журнал вещи: машина состояний, seq подряд, моменты не убывают";
    static final List<String> CHECKS = List.of(C_GRID, C_RISE, C_REASON, C_ACCEPT, C_T5, C_T9, C_T10, C_T14, C_T16,
            C_EVENTS, C_TIME, C_PHONE, C_RESERVE, C_LIVE, C_ITEM);

    static final long HALF_STEP_FROM = 100_000_00L, PHONE_MIN_LOW = 30_000_00L, PREMIUM_LOW = 3_000_000_00L;
    static final long RECENT_MS = 10_000, DUPLICATE_MS = 600_000, AFTER_SALE_MAX_S = 7 * 86_400L;

    private final long[] from, step;
    private final long cut;
    private final Planted planted;
    private final Map<String, long[]> counts = new LinkedHashMap<>();
    private final Map<String, List<String>> examples = new LinkedHashMap<>();
    long lots, bids, events, items, itemRows;
    long stMissedSkipped, stUnchecked;
    /** Живые торги: лоты по торгам — (сессия, номер, открытие, закрытие). */
    private final Map<Integer, List<long[]>> liveLots = new HashMap<>();

    public AuctionAudit(IncrementTable inc, long cut, Planted planted) {
        int n = inc.bands();
        from = new long[n];
        step = new long[n];
        for (int i = 0; i < n; i++) {
            from[i] = inc.bandFrom(i);
            step[i] = inc.bandStep(i);
        }
        this.cut = cut;
        this.planted = planted;
        for (String c : CHECKS) {
            counts.put(c, new long[1]);
            examples.put(c, new ArrayList<>());
        }
    }

    // ---------------------------------------------------------------- сетка — своя арифметика по increment_bands

    long stepOf(long price) {
        int lo = 0, hi = from.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (from[mid] <= price) lo = mid;
            else hi = mid - 1;
        }
        return step[lo];
    }

    boolean onGrid(long a) {
        return a >= 0 && a % stepOf(a) == 0;
    }

    long minNext(long price) {
        return price + stepOf(price);
    }

    long ceil(long a) {
        if (a <= 0) return 0;
        long f = a - a % stepOf(a);
        return f == a ? f : minNext(f);
    }

    private void bad(String check, String what) {
        counts.get(check)[0]++;
        List<String> ex = examples.get(check);
        if (ex.size() < 5) ex.add(what);
    }

    // ================================================================ лот

    public void lot(AuditLot a) {
        lots++;
        bids += a.bids;
        events += a.events;
        String L = "лот " + a.lotId;
        boolean online = !a.live;
        if (!onGrid(a.start)) bad(C_GRID, L + ": стартовая " + a.start);
        if (a.reserve > 0 && !onGrid(a.reserve)) bad(C_GRID, L + ": резерв " + a.reserve);
        if (a.reserve > a.low && !a.plantedReserveAboveLow) bad(C_RESERVE, L + ": резерв " + a.reserve + " > " + a.low);
        for (int k = 0; k < a.revs; k++) if (a.rMax[k] > 0 && !onGrid(a.rMax[k])) bad(C_GRID, L + ": максимум " + a.rMax[k]);
        boolean closed = a.closedMs >= 0;
        // ---- проход по ставкам в порядке seq
        boolean hasBid = false, halfSeen = false;
        long price = 0, leader = -1;
        long c = a.plannedMs, cap = a.plannedMs + a.capMs;
        boolean missedUsed = false;
        long missedAt = Long.MIN_VALUE;
        int accepted = 0, extFlags = 0, last = -1;
        long reserveMetBid = 0;
        // ожидаемые продления: (момент, новый конец)
        long[] extAt = new long[4], extTo = new long[4];
        int ne = 0;
        // принятые: момент, минимальная до неё, регистрация
        long[] accT = new long[a.bids], accMin = new long[a.bids], accReg = new long[a.bids];
        int na = 0;
        Map<String, Integer> keys = new HashMap<>();
        long actualEnd = closed ? a.closedMs : Long.MAX_VALUE;
        for (int i = 0; i < a.bids; i++) {
            long t = a.bTime[i];
            long amt = a.bAmount[i];
            String B = L + " ставка " + a.bId[i];
            if (a.bSeq[i] != i + 1) bad(C_EVENTS, B + ": seq " + a.bSeq[i]);
            if (t >= a.cutMs) bad(C_TIME, B + ": после среза");
            if (t < a.publishedSec * 1000) bad(C_TIME, B + ": раньше публикации");
            if (a.bChannel[i] == Codes.CH_PHONE && a.low < PHONE_MIN_LOW) bad(C_PHONE, B + ": телефон, нижняя " + a.low);
            boolean vendor = a.bKind[i] == Codes.K_VENDOR;
            if (vendor != (a.bReg[i] == 0) || vendor != (a.bChannel[i] == Codes.CH_AUCTIONEER)) bad(C_T9, B + ": продавец ↔ регистрация, канал");
            long min = hasBid ? minNext(price) : a.start;
            // ключ запроса (T14)
            String key = a.bHasKey[i] ? Long.toHexString(a.bKeyHi[i]) + ":" + Long.toHexString(a.bKeyLo[i]) : null;
            Integer orig = key == null ? null : keys.get(key);
            if (key != null && orig == null) keys.put(key, i);
            if (a.bAccepted[i]) {
                accepted++;
                last = i;
                if (t < a.openedMs || a.openedMs == 0) bad(C_TIME, B + ": принята до открытия");
                if (closed && t >= actualEnd) bad(C_TIME, B + ": принята после закрытия");
                if (a.bClient[i] == a.consignorClient && !vendor) bad(C_ACCEPT, B + ": комитент на свой лот");
                if (a.withdrawnSec > 0 && t >= a.withdrawnSec * 1000) bad(C_ACCEPT, B + ": снятый лот");
                boolean half = a.live && hasBid && price >= HALF_STEP_FROM && amt == price + stepOf(price) / 2;
                if (!hasBid && amt < a.start) bad(C_RISE, B + ": первая " + amt + " < стартовой " + a.start);
                if (hasBid && amt < min && !half) bad(C_RISE, B + ": " + amt + " < " + min);
                if (half) halfSeen = true;
                if (!halfSeen && !onGrid(amt)) bad(C_GRID, B + ": не на сетке " + amt);
                // лидер не перебивает сам себя (кроме заложенного повтора и автоставки до резерва)
                if (a.bReg[i] == leader && hasBid) {
                    boolean auto = a.bKind[i] == Codes.K_AUTO || a.bKind[i] == Codes.K_ABSENTEE;
                    // лидер ставит сам: до резерва (T5), до равного максимума отклонённой (tied), свой максимум целиком
                    // перед большим чужим максимумом (T5, «меньший — целиком»); иначе — только заложенный повтор
                    boolean ok = auto && amt == ceil(a.reserve)
                            || auto && i > 0 && !a.bAccepted[i - 1] && a.bReason[i - 1] == Codes.R_TIED && a.bAmount[i - 1] == amt
                            || auto && amt == leaderMax(a, leader, t) && i + 1 < a.bids && a.bAccepted[i + 1]
                            && a.bReg[i + 1] != leader && (a.bKind[i + 1] == Codes.K_MAX || a.bKind[i + 1] == Codes.K_ABSENTEE)
                            || a.plantedDouble && orig != null;
                    if (!ok) bad(C_ACCEPT, B + ": лидер перебил сам себя");
                }
                if (key != null && orig != null && !a.plantedDouble) bad(C_T14, B + ": повтор ключа принят");
                // T5: автоставка, поставленный максимум, заочная
                byte k = a.bKind[i];
                if (k == Codes.K_MAX || k == Codes.K_AUTO || k == Codes.K_ABSENTEE) {
                    long m = a.bOrderMax[i];
                    if (a.bOrder[i] <= 0) bad(C_T5, B + ": без поручения");
                    if (m > 0 && amt > m) bad(C_T5, B + ": " + amt + " выше максимума " + m);
                    long r = ceil(a.reserve);
                    boolean rule = amt == min || (r > 0 && amt == r) || amt == m || (!hasBid && amt == a.start)
                            || (hasBid && amt == minNext(price));
                    if (!rule) bad(C_T5, B + ": сумма " + amt + " не по правилу (мин. " + min + ", максимум " + m + ")");
                }
                if (vendor) {
                    if (online) bad(C_T9, B + ": за продавца онлайн");
                    if (a.reserve == 0) bad(C_T9, B + ": за продавца без резерва");
                    else if (amt >= a.reserve && !planted.has("vendor_bid_at_reserve", a.lotId)) bad(C_T9, B + ": за продавца не ниже резерва");
                    if (leader == 0) bad(C_T9, B + ": продавец перебил сам себя");
                }
                // T10
                if (online) {
                    if (t >= c) bad(C_T10, B + ": принята после конца " + c);
                    long nc = t >= c - a.windowMs && t < c ? Math.min(Math.max(c, t + a.extendMs), cap) : c;
                    nc = Math.max(nc, c);
                    if (nc != c) {
                        if (a.bExtended[i]) {
                            c = nc;
                            if (ne == extAt.length) {
                                extAt = Arrays.copyOf(extAt, ne * 2);
                                extTo = Arrays.copyOf(extTo, ne * 2);
                            }
                            extAt[ne] = t;
                            extTo[ne] = nc;
                            ne++;
                        } else if (a.plantedMissed && (!missedUsed || t == missedAt)) {
                            // заложенный сбой часов: продления нет у всей группы ставок этой миллисекунды
                            if (!missedUsed) stMissedSkipped++;
                            missedUsed = true;
                            missedAt = t;
                        } else {
                            bad(C_T10, B + ": должна продлить до " + nc);
                        }
                    } else if (a.bExtended[i]) {
                        bad(C_T10, B + ": продлила, хотя не должна");
                    }
                } else if (a.bExtended[i]) {
                    bad(C_T10, B + ": продление у живого лота");
                }
                if (a.bExtended[i]) extFlags++;
                if (a.reserve > 0 && reserveMetBid == 0 && !vendor && amt >= a.reserve) reserveMetBid = a.bId[i];
                accT[na] = t;
                accMin[na] = min;
                accReg[na] = a.bReg[i];
                na++;
                hasBid = true;
                price = amt;
                leader = a.bReg[i];
            } else {
                if (a.bExtended[i]) bad(C_T10, B + ": отклонённая продлила");
                byte r = a.bReason[i];
                boolean ok;
                switch (r) {
                    case Codes.R_BELOW_MINIMUM -> ok = amt < min;
                    case Codes.R_OFF_INCREMENT -> ok = !onGrid(amt);
                    case Codes.R_PRICE_CHANGED, Codes.R_RACE_LOST -> {
                        // цену подняли, пока ставка шла: в окне есть принятая ставка, до которой сумма была допустима, и
                        // среди поднявших — чужая (свой максимум в пути вместе с ручной ставкой — тоже «цена изменилась»)
                        ok = false;
                        boolean other = false;
                        if (amt < min) {
                            for (int q = na - 1; q >= 0 && accT[q] >= t - RECENT_MS; q--) {
                                if (accReg[q] != a.bReg[i]) other = true;
                                if (amt >= accMin[q] && other && (r == Codes.R_PRICE_CHANGED || a.live || accT[q] == t)) {
                                    ok = true;
                                    break;
                                }
                            }
                        }
                    }
                    case Codes.R_TIED -> ok = leader > 0 && leader != a.bReg[i] && amt == leaderMax(a, leader, t);
                    case Codes.R_ALREADY_LEADING -> ok = leader == a.bReg[i];
                    case Codes.R_AFTER_CLOSE -> ok = online ? t >= Math.min(c, actualEnd) : closed && t >= actualEnd;
                    case Codes.R_DUPLICATE -> ok = orig != null && a.bReg[orig] == a.bReg[i] && a.bAccepted[orig]
                            && t - a.bTime[orig] <= DUPLICATE_MS && t >= a.bTime[orig];
                    case Codes.R_NOT_OPEN -> ok = a.openedMs == 0 || t < a.openedMs;
                    case Codes.R_LOT_WITHDRAWN -> ok = a.withdrawnSec > 0 && t >= a.withdrawnSec * 1000;
                    case Codes.R_OWN_LOT -> ok = a.bClient[i] == a.consignorClient;
                    case Codes.R_PREMIUM_NOT_APPROVED -> ok = a.low >= PREMIUM_LOW;
                    case Codes.R_OVER_LIMIT, Codes.R_NOT_REGISTERED, Codes.R_BLOCKED -> {
                        // регистрации ↔ ставки проверяет подготовка торгов
                        ok = true;
                        stUnchecked++;
                    }
                    default -> ok = false;
                }
                if (!ok) bad(C_REASON, B + ": " + Codes.REASONS.get(r) + " (сумма " + amt + ", мин. " + min + ")");
                if (r != Codes.R_DUPLICATE && orig != null) bad(C_T14, B + ": повтор ключа без duplicate");
                if ((a.bKind[i] == Codes.K_MAX || a.bKind[i] == Codes.K_ABSENTEE) && a.bOrder[i] <= 0) bad(C_T5, B + ": без поручения");
            }
        }
        // ---- конец онлайн-лота (T10)
        if (online && closed) {
            if (a.plantedEarly) {
                if (!(a.closedMs < c && c - a.closedMs <= 3_000)) bad(C_T10, L + ": ранний конец " + a.closedMs + " при " + c);
            } else if (a.closedMs != c) {
                bad(C_T10, L + ": закрыт " + a.closedMs + ", по журналу " + c);
            }
        }
        if (online && c > cap) bad(C_T10, L + ": конец позже потолка");
        if (a.extensions != extFlags) bad(C_T10, L + ": продлений " + a.extensions + ", ставок-причин " + extFlags);
        // ---- итог (T16)
        if (a.bidCount != accepted) bad(C_T16, L + ": bid_count " + a.bidCount + " ≠ " + accepted);
        boolean shouldSell = last >= 0 && a.bReg[last] != 0 && (a.reserve == 0 || a.bAmount[last] >= a.reserve);
        byte res = a.result;
        if (a.withdrawnSec > 0 && a.withdrawnSec * 1000 < a.cutMs) {
            if (res != Codes.RES_WITHDRAWN) bad(C_T16, L + ": снят, итог " + Codes.RESULTS.get(res));
            if (accepted > 0) bad(C_T16, L + ": у снятого есть принятые ставки");
        } else if (!closed) {
            if (res != Codes.RES_OPEN) bad(C_T16, L + ": не закрыт, итог " + Codes.RESULTS.get(res));
        } else if (shouldSell) {
            if (res != Codes.RES_SOLD) bad(C_T16, L + ": должен быть продан");
            if (a.hammer != a.bAmount[last]) bad(C_T16, L + ": молоток " + a.hammer + " ≠ " + a.bAmount[last]);
            if (a.winningBid != a.bId[last]) bad(C_T16, L + ": победитель — не последняя принятая");
            if (a.buyerReg != a.bReg[last]) bad(C_T16, L + ": покупатель — не её регистрация");
            long under = 0, best = -1;
            for (int i = 0; i < a.bids; i++) {
                if (a.bAccepted[i] && a.bReg[i] != 0 && a.bReg[i] != a.bReg[last] && a.bAmount[i] > best) {
                    best = a.bAmount[i];
                    under = a.bReg[i];
                }
            }
            if (a.underbidderReg != under) bad(C_T16, L + ": вторая ставка " + a.underbidderReg + " ≠ " + under);
        } else {
            if (res != Codes.RES_BOUGHT_IN && res != Codes.RES_SOLD_AFTER_SALE) bad(C_T16, L + ": не продан, итог " + Codes.RESULTS.get(res));
            if (a.hammer != 0 || a.winningBid != 0) bad(C_T16, L + ": молоток у непроданного");
            if (res == Codes.RES_SOLD_AFTER_SALE) {
                long cs = a.closedMs / 1000;
                if (a.afterSalePrice < a.reserve || a.afterSalePrice < a.start) bad(C_T16, L + ": после торгов ниже резерва");
                if (a.afterSaleSec <= cs || a.afterSaleSec > cs + AFTER_SALE_MAX_S) bad(C_T16, L + ": после торгов не в 7 дней");
                if (a.buyerReg == 0) bad(C_T16, L + ": после торгов без покупателя");
            }
        }
        // ---- журнал лота
        events(a, L, ne, extAt, extTo, reserveMetBid, cap);
        if (a.publishedSec * 1000 > a.openedMs && a.openedMs > 0) bad(C_TIME, L + ": открыт раньше публикации");
        if (a.live && a.withdrawnSec == 0) {
            liveLots.computeIfAbsent(a.saleId, s -> new ArrayList<>())
                    .add(new long[]{a.sessionNo, a.lotNo, a.openedMs, a.closedMs});
        }
    }

    /** Максимум лидера на момент: последняя не отклонённая его редакция, поданная не позже. */
    private static long leaderMax(AuditLot a, long reg, long t) {
        long m = 0, at = Long.MIN_VALUE;
        for (int k = 0; k < a.revs; k++) {
            if (a.rReg[k] == reg && !a.rRejected[k] && a.rPlaced[k] <= t && a.rPlaced[k] >= at) {
                at = a.rPlaced[k];
                m = a.rMax[k];
            }
        }
        return m;
    }

    private void events(AuditLot a, String L, int ne, long[] extAt, long[] extTo, long reserveMetBid, long cap) {
        long prev = Long.MIN_VALUE;
        int ext = 0;
        boolean resMet = false, end = false;
        for (int e = 0; e < a.events; e++) {
            byte k = a.eKind[e];
            long t = a.eTime[e];
            String E = L + " событие " + a.eSeq[e] + " " + Codes.EVENTS.get(k);
            if (a.eSeq[e] != e + 1) bad(C_EVENTS, E + ": seq");
            if (t < prev) bad(C_EVENTS, E + ": раньше предыдущего");
            if (t >= a.cutMs) bad(C_TIME, E + ": после среза");
            prev = t;
            if (e == 0 && (k != Codes.E_PUBLISHED || t != a.publishedSec * 1000)) bad(C_EVENTS, E + ": первое — не публикация");
            if (e > 0 && k == Codes.E_PUBLISHED) bad(C_EVENTS, E + ": вторая публикация");
            switch (k) {
                case Codes.E_OPENED -> {
                    if (t != a.openedMs) bad(C_EVENTS, E + ": не момент открытия");
                }
                case Codes.E_RESERVE_MET -> {
                    resMet = true;
                    if (a.eBid[e] != reserveMetBid) bad(C_EVENTS, E + ": не первая ставка не ниже резерва");
                }
                case Codes.E_EXTENDED, Codes.E_EXTENSION_CAPPED -> {
                    if (ext >= ne || extAt[ext] != t || extTo[ext] != a.eNewClose[e]) {
                        bad(C_T10, E + ": продление не по журналу ставок");
                    } else if ((k == Codes.E_EXTENSION_CAPPED) != (extTo[ext] == cap)) {
                        bad(C_T10, E + ": потолок — extension_capped");
                    }
                    ext++;
                }
                case Codes.E_FAIR_WARNING -> {
                    if (!a.live) bad(C_EVENTS, E + ": онлайн");
                }
                case Codes.E_SOLD, Codes.E_BOUGHT_IN -> {
                    end = true;
                    if (t != a.closedMs) bad(C_EVENTS, E + ": не момент закрытия");
                    if (k == Codes.E_SOLD && (a.eBid[e] != a.winningBid || a.ePrice[e] != a.hammer)) bad(C_EVENTS, E + ": не победитель");
                    if ((k == Codes.E_SOLD) != (a.result == Codes.RES_SOLD)) bad(C_EVENTS, E + ": не итог лота");
                }
                case Codes.E_AFTER_SALE -> {
                    if (a.result != Codes.RES_SOLD_AFTER_SALE || t != a.afterSaleSec * 1000 || a.ePrice[e] != a.afterSalePrice) {
                        bad(C_EVENTS, E + ": не продажа после торгов");
                    }
                }
                case Codes.E_WITHDRAWN -> {
                    if (t != a.withdrawnSec * 1000) bad(C_EVENTS, E + ": не момент снятия");
                }
                default -> {
                }
            }
        }
        if (ext != ne) bad(C_T10, L + ": продлений в журнале " + ext + ", по ставкам " + ne);
        if (resMet != (reserveMetBid != 0)) bad(C_EVENTS, L + ": reserve_met " + resMet);
        if (end != (a.closedMs >= 0)) bad(C_EVENTS, L + ": итог в журнале " + end);
        if (a.openedMs > 0 && a.openedMs < a.cutMs && (a.events < 2 || a.eKind[1] != Codes.E_OPENED)) bad(C_EVENTS, L + ": нет открытия");
    }

    // ================================================================ журнал вещи

    private static final boolean[][] ALLOWED = new boolean[14][14];
    private static final boolean[] FIRST = new boolean[14];

    static {
        FIRST[Codes.IS_RECEIVED] = FIRST[Codes.IS_RESALE_RECEIVED] = true;
        allow(Codes.IS_RECEIVED, Codes.IS_ACCEPTED, Codes.IS_DECLINED);
        allow(Codes.IS_ACCEPTED, Codes.IS_CATALOGUED);
        allow(Codes.IS_REOFFER_PLANNED, Codes.IS_CATALOGUED);
        allow(Codes.IS_RESALE_RECEIVED, Codes.IS_CATALOGUED);
        allow(Codes.IS_CATALOGUED, Codes.IS_SOLD, Codes.IS_BOUGHT_IN, Codes.IS_WITHDRAWN);
        allow(Codes.IS_SOLD, Codes.IS_BUYER_PAID, Codes.IS_BUYER_DEFAULT);
        allow(Codes.IS_AFTER_SALE, Codes.IS_BUYER_PAID, Codes.IS_BUYER_DEFAULT);
        allow(Codes.IS_BUYER_PAID, Codes.IS_RELEASED);
        allow(Codes.IS_BUYER_DEFAULT, Codes.IS_AFTER_SALE, Codes.IS_REOFFER_PLANNED, Codes.IS_RETURNED);
        allow(Codes.IS_BOUGHT_IN, Codes.IS_AFTER_SALE, Codes.IS_REOFFER_PLANNED, Codes.IS_RETURNED);
        allow(Codes.IS_WITHDRAWN, Codes.IS_RETURNED, Codes.IS_REOFFER_PLANNED);
        allow(Codes.IS_RELEASED, Codes.IS_RESALE_RECEIVED);
    }

    private static void allow(byte f, byte... to) {
        for (byte t : to) ALLOWED[f][t] = true;
    }

    private int curItem = -1, curSeq;
    private long curAt, releasedAt;
    private byte curStatus;

    public void beginItemLog() {
        curItem = -1;
    }

    public void itemRow(int item, int seq, long at, byte status) {
        itemRows++;
        String I = "вещь " + item + " #" + seq + " " + Codes.ITEM_STATUSES.get(status);
        if (at >= cut) bad(C_ITEM, I + ": после среза");
        if (item != curItem) {
            items++;
            curItem = item;
            if (seq != 1) bad(C_ITEM, I + ": seq не с 1");
            if (!FIRST[status]) bad(C_ITEM, I + ": первое состояние");
        } else {
            if (seq != curSeq + 1) bad(C_ITEM, I + ": seq не подряд");
            if (at < curAt) bad(C_ITEM, I + ": момент раньше предыдущего");
            if (!ALLOWED[curStatus][status]) bad(C_ITEM, I + ": переход из " + Codes.ITEM_STATUSES.get(curStatus));
            if (status == Codes.IS_RESALE_RECEIVED && curStatus == Codes.IS_RELEASED && at - releasedAt < 80L * 86_400) {
                bad(C_ITEM, I + ": сдача купленного раньше 3 месяцев");
            }
        }
        if (status == Codes.IS_RELEASED) releasedAt = at;
        curSeq = seq;
        curAt = at;
        curStatus = status;
    }

    public void endItemLog() {
        curItem = -1;
    }

    // ================================================================ итог

    public void report(CheckReport r) {
        // живые торги: сессии
        List<Integer> sales = new ArrayList<>(liveLots.keySet());
        sales.sort(null);
        for (int s : sales) {
            List<long[]> ls = liveLots.get(s);
            ls.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
            Map<Long, Integer> perSession = new HashMap<>();
            for (int i = 0; i < ls.size(); i++) {
                long[] x = ls.get(i);
                perSession.merge(x[0], 1, Integer::sum);
                if (i == 0 || ls.get(i - 1)[0] != x[0]) continue;
                long[] p = ls.get(i - 1);
                if (x[2] > 0 && p[2] > 0) {
                    if (x[2] < p[2] + 20_000) bad(C_LIVE, "торги " + s + " лот " + x[1] + ": слот меньше 20 с");
                    if (p[3] >= 0 && x[2] < p[3]) bad(C_LIVE, "торги " + s + " лот " + x[1] + ": открыт до молотка предыдущего");
                }
            }
            for (Map.Entry<Long, Integer> e : perSession.entrySet()) {
                if (e.getValue() > 250) bad(C_LIVE, "торги " + s + " сессия " + e.getKey() + ": лотов " + e.getValue());
            }
        }
        String scope = "лотов " + lots + ", ставок " + bids + ", событий " + events;
        for (String c : CHECKS) {
            String ok = c.equals(C_ITEM) ? "вещей " + items + ", строк " + itemRows
                    : c.equals(C_REASON) ? scope + "; регистрационных причин (проверяет подготовка торгов) " + stUnchecked
                    : c.equals(C_T10) ? scope + "; пропущено заложенных продлений " + stMissedSkipped : scope;
            r.expectNone("auction: " + c, counts.get(c)[0], examples.get(c), ok);
        }
    }

    /** Нарушений по проверке (для тестов). */
    public long violations(String check) {
        return counts.get(check)[0];
    }

    public long violations() {
        long n = 0;
        for (long[] c : counts.values()) n += c[0];
        return n;
    }

    public Map<String, List<String>> examples() {
        return examples;
    }
}
