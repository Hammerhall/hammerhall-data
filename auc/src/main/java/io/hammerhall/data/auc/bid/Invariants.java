package io.hammerhall.data.auc.bid;

import java.util.List;

/**
 * Инварианты торгов лота — проверка итога заново, по событиям, без состояния движка: движок зовёт её после каждого
 * лота ({@link AuctionRules#selfCheck()}), тесты — на ручных примерах и на случайных лотах. Номера T1–T16 — сквозные
 * номера правил торгов дома.
 *
 * <ul>
 *   <li>время событий не убывает; онлайн — не раньше открытия, живые — в пределах слота; номера — по порядку;</li>
 *   <li>онлайн каждая сумма — на сетке шагов (T1); каждая принятая — не ниже {@code предыдущая + шаг} (T2), первая —
 *       не ниже стартовой; живые — так же или полшага выше порога {@link AuctionRules#halfStepFrom()} (T1а);</li>
 *   <li>ставка участника — не выше его максимума; ставка от имени продавца — только на живых торгах и ниже резерва
 *       (T9); из книги исполняются только поручения книги; ведущий не перебивает сам себя (кроме помеченного сбоя);</li>
 *   <li>отклонённая ставка — с причиной, и причина верна (T3, T6, T15): ниже минимальной — правда ниже; гонка — в ту
 *       же миллисекунду раньше принята другая; ничья — следом лидер ставит ту же сумму; «уже лидер» — правда лидер;
 *       после закрытия — правда после;</li>
 *   <li>автоставка (T5) — только за лидера и ровно {@code min(максимум, второй + шаг)}, а если максимум дотягивает до
 *       резерва, — не ниже резерва ({@link AuctionRules#proxyToReserve()}); поставленный максимум — на минимальную цену
 *       (или резерв), либо целиком;</li>
 *   <li>подъём максимума (T4) — только ведущим, онлайн, до закрытия; цена прежняя; применён ⇔ новый максимум выше
 *       действующего, не выше потолка участника; иначе — отказ {@code below_minimum};</li>
 *   <li>продления — только по правилу T10: каждая принятая ставка в окне, сдвигающая конец по
 *       {@link AuctionRules#extendedClose}, продлевает, других продлений нет; конец не дальше потолка; закрытие — одно
 *       и в срок; у живых — один молоток, последним;</li>
 *   <li>итог (T16): продан ⇔ последняя принятая ставка — участника и не ниже резерва; победитель — её участник; молоток —
 *       её сумма, не ниже резерва и не выше максимума победителя; каждая отклонённая (кроме опоздавших и повторов
 *       лидера) ниже минимальной и в конце, ни один чужой поставленный максимум не выше итоговой цены; счётчики
 *       сводки сходятся с событиями.</li>
 * </ul>
 * Заложенные сбои ({@link Faults}) проверка пропускает ровно там, где они скрыто помечены, и сверяет пометки со сводкой.
 */
public final class Invariants {
    /** Рабочие массивы на участника — переиспользуются (движок держит один экземпляр). */
    private boolean[] seen = new boolean[16];
    private long[] proxyMax = new long[16];
    /** Принятые ставки лота: момент, участник, минимальная перед ней. */
    private long[] accT = new long[64], accMin = new long[64];
    private int[] accB = new int[64];
    /** Окно, в котором чужая принятая ставка объясняет «цена изменилась» (как у независимой проверки набора). */
    static final long RECENT_MS = 10_000;

    /** Проверить; нарушение — {@link IllegalStateException} с описанием и событиями лота. */
    public static void check(Lot lot, List<Bidder> bidders, AuctionRules rules, LotOutcome o) {
        new Invariants().require(lot, bidders, rules, o);
    }

    /** Первое найденное нарушение или {@code null}. */
    public static String verify(Lot lot, List<Bidder> bidders, AuctionRules rules, LotOutcome o) {
        return new Invariants().find(lot, bidders, rules, o);
    }

    /** То же, что {@link #check}, на своих рабочих массивах. */
    public void require(Lot lot, List<Bidder> bidders, AuctionRules rules, LotOutcome o) {
        String p = find(lot, bidders, rules, o);
        if (p != null) throw new IllegalStateException("лот " + lot.id() + ": " + p + "\n" + o);
    }

    private static String at(int i) {
        return "событие " + (i + 1) + ": ";
    }

    /** Цена автоставки за максимум {@code mx} с учётом резерва (T5). */
    private static long withReserve(AuctionRules rules, long reserveGrid, long amount, long mx) {
        if (!rules.proxyToReserve() || reserveGrid == 0 || mx < reserveGrid || amount >= reserveGrid) return amount;
        return reserveGrid;
    }

    /** То же, что {@link #verify}, на своих рабочих массивах: без объектов, пока нарушений нет. */
    public String find(Lot lot, List<Bidder> bidders, AuctionRules rules, LotOutcome o) {
        IncrementTable inc = rules.increments();
        boolean live = lot.mode() == Mode.LIVE;
        long opening = rules.opening(lot);
        long reserve = lot.reserve();
        long reserveGrid = rules.reserveOnGrid(lot);
        long planned = lot.endAt();
        int n = o.size();
        int nb = bidders.size();
        if (n == 0) return "нет событий";
        if (o.mode() != lot.mode() || o.lotId() != lot.id() || o.opening() != opening) return "сводка не этого лота";
        long prevT = Long.MIN_VALUE;
        long price = 0;
        boolean has = false;
        int leader = LotOutcome.NONE;
        // лидер до последней принятой ставки: ставка ниже автоставки лидера принимается, и он сразу отвечает
        int leaderBefore = LotOutcome.NONE;
        // действующий максимум лидера: у поручения — его максимум, у ручной ставки — она сама
        long leaderMax = 0;
        long close = planned;
        long closedAt = 0;
        int ext = 0, closes = 0, hammers = 0;
        long lastBidAmount = 0;
        boolean closed = false;
        boolean needExt = false;
        long needExtAt = 0;
        boolean missed = false, early = false, twice = false;
        int bids = 0, acc = 0, rej = 0, auto = 0, vendor = 0, shillBids = 0;
        if (seen.length < nb) {
            seen = new boolean[Math.max(nb, seen.length * 2)];
            proxyMax = new long[seen.length];
        }
        for (int b = 0; b < nb; b++) {
            seen[b] = false;
            proxyMax[b] = 0;
        }
        for (int i = 0; i < n; i++) {
            long t = o.time(i);
            EventType ty = o.type(i);
            if (o.seq(i) != i + 1) return at(i) + "номер " + o.seq(i);
            if (t < prevT) return at(i) + "время назад: " + t + " < " + prevT;
            if (needExt && (t > needExtAt || ty == EventType.CLOSE)) return "T10: поздняя ставка в " + needExtAt + " без продления";
            prevT = t;
            if (t < lot.startAt()) return at(i) + "раньше начала лота";
            if (live && t >= lot.endAt()) return at(i) + "вне слота лота";
            if (ty != EventType.BID && (o.hiddenRepeat(i) || o.hiddenMissedExtension(i) || o.hiddenShill(i))) {
                return at(i) + "метка ставки не у ставки";
            }
            if (ty != EventType.CLOSE && o.hiddenEarlyClose(i)) return at(i) + "метка раннего конца не у закрытия";
            switch (ty) {
                case BID -> {
                    if (hammers > 0) return at(i) + "ставка после молотка";
                    bids++;
                    int b = o.bidderIndex(i);
                    long amt = o.amount(i);
                    long mx = o.maxAmount(i);
                    BidKind k = o.kind(i);
                    if (k == null || o.channel(i) == null) return at(i) + "ставка без вида или канала";
                    long cap;
                    if (b == LotOutcome.VENDOR) {
                        if (!live) return at(i) + "T9: ставка от имени продавца на онлайн-торгах";
                        if (k != BidKind.VENDOR || o.channel(i) != Channel.AUCTIONEER || o.bidderId(i) != 0) {
                            return at(i) + "ставка от имени продавца не помечена";
                        }
                        if (o.hiddenShill(i)) return at(i) + "скрытая метка у ставки продавца";
                        vendor++;
                        cap = Long.MAX_VALUE;
                    } else {
                        if (b < 0 || b >= nb) return at(i) + "нет участника " + b;
                        Bidder bd = bidders.get(b);
                        if (o.bidderId(i) != bd.id() || o.channel(i) != bd.channel()) return at(i) + "не тот участник или канал";
                        if (k == BidKind.VENDOR) return at(i) + "участник со ставкой продавца";
                        if ((k == BidKind.ABSENTEE) != bd.book()) return at(i) + "T7: из книги — только поручения книги";
                        if (o.hiddenShill(i) != bd.shill()) return at(i) + "скрытая метка не совпала с участником";
                        if (bd.shill()) shillBids++;
                        seen[b] = true;
                        cap = inc.floor(bd.max());
                    }
                    if (amt <= 0) return at(i) + "сумма " + amt;
                    if (!live && !inc.onGrid(amt)) return at(i) + "T1: сумма " + amt + " не на сетке шагов";
                    if (mx > 0 && !inc.onGrid(mx)) return at(i) + "T4: максимум " + mx + " не на сетке шагов";
                    if (o.hiddenRepeat(i)) {
                        int j = o.repeatOf(i);
                        if (j < 0 || j >= i || o.type(j) != EventType.BID || !o.accepted(j) || o.bidderIndex(j) != b
                                || o.kind(j) != BidKind.MANUAL || o.kind(i) != BidKind.MANUAL || live) {
                            return at(i) + "T14: повтор без своей принятой ручной ставки";
                        }
                        if (!o.accepted(i) && amt != o.amount(j)) return at(i) + "T14: повтор не на ту же сумму";
                    }
                    long min = has ? inc.minNext(price) : opening;
                    if (o.accepted(i)) {
                        acc++;
                        if (k.auto()) auto++;
                        if (closed) return at(i) + "принята после закрытия";
                        if (!live && t >= close) return at(i) + "T10: принята в момент закрытия или позже";
                        boolean half = live && k == BidKind.MANUAL && has && rules.halfStepFrom() > 0
                                && price >= rules.halfStepFrom() && amt == price + inc.step(price) / 2;
                        if (amt < min && !half) return at(i) + "T2: принята " + amt + " < минимальной " + min;
                        if (b >= 0) {
                            if (amt > cap) return at(i) + "ставка " + amt + " выше максимума участника " + cap;
                            if (mx > cap) return at(i) + "максимум " + mx + " выше намерения " + cap;
                            if (mx > 0 && amt > mx) return at(i) + "ставка выше своего максимума";
                            boolean wasLeading = b == leader || b == leaderBefore;
                            if (k == BidKind.AUTO && !wasLeading) return at(i) + "T5: автоставка не за лидера";
                            if (!k.auto() && b == leader && has) {
                                if (!o.hiddenRepeat(i)) return at(i) + "T3: ведущий перебил сам себя";
                                twice = true;
                            }
                            boolean response = k == BidKind.AUTO || (k == BidKind.ABSENTEE && wasLeading);
                            if (response && amt != mx
                                    && amt != Math.min(mx, withReserve(rules, reserveGrid, inc.minNext(lastBidAmount), mx))) {
                                return at(i) + "T5: автоставка " + amt + " — не минимальный шаг от " + lastBidAmount
                                        + " (с резервом), не весь максимум " + mx;
                            }
                            boolean placed = k == BidKind.MAX || (k == BidKind.ABSENTEE && !wasLeading);
                            if (placed && amt != mx && amt != withReserve(rules, reserveGrid, min, mx)) {
                                return at(i) + "T5: максимум поставлен не на минимальную цену (с резервом) и не целиком";
                            }
                            if (placed) proxyMax[b] = Math.max(proxyMax[b], mx);
                        } else if (reserve == 0 || amt >= reserve) {
                            return at(i) + "T9: ставка от имени продавца " + amt + " не ниже резерва " + reserve;
                        }
                        if (acc > accT.length) {
                            accT = java.util.Arrays.copyOf(accT, accT.length * 2);
                            accMin = java.util.Arrays.copyOf(accMin, accMin.length * 2);
                            accB = java.util.Arrays.copyOf(accB, accB.length * 2);
                        }
                        accT[acc - 1] = t;
                        accMin[acc - 1] = min;
                        accB[acc - 1] = b;
                        price = amt;
                        has = true;
                        leaderBefore = leader;
                        leader = b;
                        leaderMax = mx > 0 ? mx : amt;
                        if (!live) {
                            long nc = rules.extendedClose(planned, close, t);
                            if (o.hiddenMissedExtension(i)) {
                                if (nc <= close) return at(i) + "метка «без продления» у ставки, которая не продлевала";
                                missed = true;
                                needExt = false;
                            } else if (nc > close) {
                                needExt = true;
                                needExtAt = t;
                            }
                        }
                    } else {
                        rej++;
                        if (o.hiddenMissedExtension(i)) return at(i) + "метка «без продления» у отклонённой";
                        RejectReason r = o.reason(i);
                        if (r == RejectReason.NONE) return at(i) + "отклонена без причины";
                        if (closed && r != RejectReason.AFTER_CLOSE) return at(i) + "после закрытия — причина не after_close";
                        switch (r) {
                            case AFTER_CLOSE -> {
                                if (live || !closed || t < closedAt) return at(i) + "T10: after_close не после закрытия";
                            }
                            case BELOW_MINIMUM -> {
                                if (amt >= min) return at(i) + "T3: below_minimum, но " + amt + " ≥ минимальной " + min;
                            }
                            case PRICE_CHANGED, RACE_LOST -> {
                                if (amt >= min) return at(i) + "T15: " + r.code() + ", но " + amt + " ≥ минимальной " + min;
                                // её сделала недопустимой принятая чужая ставка, перед которой сумма ещё проходила
                                boolean explained = false;
                                for (int j = acc - 1; j >= 0 && accT[j] >= t - RECENT_MS && !explained; j--) {
                                    explained = accB[j] != b && amt >= accMin[j] && (r == RejectReason.PRICE_CHANGED || accT[j] == t);
                                }
                                if (!explained) {
                                    return at(i) + "T15: " + r.code() + " без принятой чужой ставки, перед которой " + amt + " проходила"
                                            + (r == RejectReason.RACE_LOST ? " (в ту же миллисекунду)" : "");
                                }
                            }
                            case TIED -> {
                                if (amt < min) return at(i) + "T6: tied ниже минимальной";
                                if (i + 1 >= n || o.type(i + 1) != EventType.BID || !o.accepted(i + 1)
                                        || o.amount(i + 1) != amt || o.bidderIndex(i + 1) != leader) {
                                    return at(i) + "T6: tied, а лидер не поднялся до этой суммы следом";
                                }
                            }
                            case ALREADY_LEADING -> {
                                if (b != leader) return at(i) + "T3: already_leading, а лидер другой";
                            }
                            case NONE -> throw new IllegalStateException();
                        }
                    }
                    lastBidAmount = amt;
                }
                case MAX_RAISE -> {
                    int b = o.bidderIndex(i);
                    long mx = o.maxAmount(i);
                    if (live || closed) return at(i) + "T4: подъём максимума не на открытом онлайн-лоте";
                    if (!has || b < 0 || b != leader) return at(i) + "T4: максимум поднимает не ведущий";
                    Bidder bd = bidders.get(b);
                    if (o.bidderId(i) != bd.id() || o.channel(i) != bd.channel() || o.kind(i) != BidKind.MAX) {
                        return at(i) + "подъём максимума — не тот участник, канал или вид";
                    }
                    if (o.amount(i) != price) return at(i) + "T4: подъём максимума изменил цену";
                    if (!inc.onGrid(mx) || mx > inc.floor(bd.max())) return at(i) + "T4: новый максимум не на сетке или выше потолка";
                    if (o.accepted(i)) {
                        if (mx <= leaderMax) return at(i) + "T4: применён подъём не выше действующего максимума " + leaderMax;
                        leaderMax = mx;
                        proxyMax[b] = Math.max(proxyMax[b], mx);
                    } else if (o.reason(i) != RejectReason.BELOW_MINIMUM || mx > leaderMax) {
                        return at(i) + "T4: отказ в подъёме — не по правилу";
                    }
                }
                case EXTEND -> {
                    if (live) return at(i) + "продление на живых торгах";
                    if (closed) return at(i) + "продление после закрытия";
                    if (!needExt || needExtAt != t) return at(i) + "T10: продление не по правилу — нет поздней ставки в эту миллисекунду";
                    long nc = rules.extendedClose(planned, close, t);
                    if (o.closeAt(i) != nc) return at(i) + "T10: новый конец " + o.closeAt(i) + ", по правилу — " + nc;
                    close = nc;
                    ext++;
                    needExt = false;
                }
                case CLOSE -> {
                    if (live) return at(i) + "закрытие по часам на живых торгах";
                    if (closed) return at(i) + "закрытие дважды";
                    if (o.closeAt(i) != t) return at(i) + "время закрытия не совпало";
                    if (o.hiddenEarlyClose(i)) {
                        if (t >= close) return at(i) + "метка раннего конца, а закрыт не раньше";
                        early = true;
                    } else if (t != close) {
                        return at(i) + "T10: закрытие в " + t + ", по правилу — " + close;
                    }
                    closed = true;
                    closedAt = t;
                    closes++;
                }
                case FAIR_WARNING -> {
                    if (!live) return at(i) + "предупреждение на онлайн-торгах";
                }
                case HAMMER -> {
                    if (!live) return at(i) + "молоток на онлайн-торгах";
                    hammers++;
                    if (i != n - 1) return at(i) + "молоток не последним";
                }
            }
        }
        if (needExt) return "T10: поздняя ставка в " + needExtAt + " без продления";
        if (!live && closes != 1) return "закрытий " + closes;
        if (!live && rules.maxExtendMs() > 0 && close > planned + rules.maxExtendMs()) return "T10: конец за потолком продлений";
        if (live && hammers != 1) return "молотков " + hammers;

        boolean sold = has && leader >= 0 && price >= reserve;
        if (o.sold() != sold) return "T16: итог «продан» = " + o.sold() + ", по событиям — " + sold;
        if (o.highBid() != (has ? price : 0)) return "наибольшая ставка " + o.highBid() + ", по событиям — " + price;
        if (sold) {
            Bidder w = bidders.get(leader);
            if (o.hammerPrice() != price) return "T16: молоток " + o.hammerPrice() + ", последняя принятая — " + price;
            if (o.winnerIndex() != leader || o.winnerId() != w.id()) return "T16: победитель не тот, чья последняя принятая ставка";
            if (price < reserve) return "T16: молоток ниже резерва";
            if (price > inc.floor(w.max())) return "молоток выше максимума победителя";
        } else if (o.hammerPrice() != 0 || o.winnerIndex() != LotOutcome.NONE || o.winnerId() != 0) {
            return "T16: не продан, а молоток или победитель есть";
        }
        if (live) {
            int h = n - 1;
            if (o.amount(h) != o.hammerPrice() || o.bidderId(h) != o.winnerId()) return "молоток не совпал с итогом";
            if (o.closedAt() != o.time(h)) return "закрытие живого лота — не молоток";
        } else if (o.closedAt() != closedAt) {
            return "закрытие в итоге " + o.closedAt() + ", по событиям — " + closedAt;
        }
        // победитель — наибольшая допустимая ставка: каждая отклонённая (кроме опоздавших и повторов лидера) ниже
        // минимальной и в конце торгов (минимальная только растёт), ничья — не выше итоговой цены
        long finalMin = has ? inc.minNext(price) : opening;
        for (int i = 0; i < n; i++) {
            RejectReason r = o.reason(i);
            if (o.type(i) != EventType.BID || o.accepted(i) || r == RejectReason.AFTER_CLOSE || r == RejectReason.ALREADY_LEADING) continue;
            if (r == RejectReason.TIED ? o.amount(i) > price : o.amount(i) >= finalMin) {
                return "отклонена ставка " + o.amount(i) + " допустима против итоговой цены " + price;
            }
        }
        for (int b = 0; b < nb; b++) {
            if (b != leader && proxyMax[b] > price) return "T5: максимум участника " + bidders.get(b).id() + " выше итоговой цены, а он не лидер";
        }
        int participants = 0;
        for (int b = 0; b < nb; b++) if (seen[b]) participants++;
        if (o.bids() != bids || o.acceptedBids() != acc || o.rejectedBids() != rej || o.autoBids() != auto
                || o.vendorBids() != vendor || o.participants() != participants || o.extensions() != ext) {
            return "счётчики сводки не сходятся с событиями";
        }
        if (o.shillBids() != shillBids || o.shillWon() != (sold && bidders.get(leader).shill())) return "скрытые счётчики не сходятся";
        if (o.plantedMissedExtension() != missed || o.plantedEarlyClose() != early || o.plantedDoubleAccept() != twice) {
            return "заложенные сбои в сводке не сходятся с пометками";
        }
        return null;
    }
}
