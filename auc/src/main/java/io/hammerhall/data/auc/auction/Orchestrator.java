package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.auction.audit.AuctionAudit;
import io.hammerhall.data.auc.auction.audit.AuditLot;
import io.hammerhall.data.auc.auction.engine.Attempt;
import io.hammerhall.data.auc.auction.engine.Codes;
import io.hammerhall.data.auc.auction.engine.FaultPlan;
import io.hammerhall.data.auc.auction.engine.LotInput;
import io.hammerhall.data.auc.auction.engine.LotRows;
import io.hammerhall.data.auc.auction.engine.LotRunner;
import io.hammerhall.data.auc.auction.engine.Participant;
import io.hammerhall.data.auc.auction.outcome.ClientLedger;
import io.hammerhall.data.auc.auction.outcome.ItemLog;
import io.hammerhall.data.auc.bid.Behavior;
import io.hammerhall.data.auc.bid.Bidder;
import io.hammerhall.data.auc.bid.Channel;
import io.hammerhall.data.auc.bid.Schedule;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Intake;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.OrderEvents;
import io.hammerhall.data.auc.world.Participants;
import io.hammerhall.data.auc.world.PaymentPlanner;
import io.hammerhall.data.auc.world.Registrations;
import io.hammerhall.data.auc.world.SalePlan;
import io.hammerhall.data.auc.world.Via;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Planted;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.RandomStreams;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Оркестр модуля {@code auction}: неделя за неделей (пн 00:00 по Москве) торги по порядку открытия ставок —
 * каталог (подготовка торгов, по порядку), участники и движок (торги недели — параллельно), исход и состояние клиентов (по
 * порядку), затем водораздел записи.
 *
 * <h2>Водораздел и {@code id} по времени</h2>
 * Все строки торгов — не раньше открытия их ставок, торги идут по открытию, поэтому после недели строки с моментом
 * раньше открытия следующих торгов окончательны. {@code lots.id} — по (публикация, торги, номер, буква): выдаётся лотам,
 * опубликованным раньше {@code W_pub} = min(пн следующей недели, публикация необработанных торгов); ставки ({@code id}
 * — по (момент, мс, лот, seq)) пишутся до {@code W_pub} — у их лотов {@code id} к этому моменту есть (ставка не раньше
 * публикации). Лоты и журнал лота пишутся по {@code id}, когда записаны все ставки лота до среза.
 *
 * <h2>Срез</h2>
 * Моделирование — всегда до среза + 180 суток ({@code Horizon}); срез только отбирает строки (момент раньше среза) и
 * ставит состояние на срез ({@code lots.result}, итоговые поля, {@code bid_count}, {@code extensions}). Поэтому та же
 * история до прежнего среза при продлении периода.
 */
final class Orchestrator {
    static final long WEEK = 7 * 86_400L;
    private static final long INF = Long.MAX_VALUE / 4;

    private final GenContext ctx;
    private final HouseRules house;
    private final Prep prep;
    private final long cut, end, cutMs;
    private final int threads;
    private final Behavior behavior;
    private final FaultPlan faults;
    private final RandomStreams.Stream bidStream;
    final ClientLedger ledger;
    final ItemLog itemLog;
    final Outcomes outcomes;
    final AuctionAudit audit;
    final io.hammerhall.data.auc.auction.audit.Corridors corridors;
    final WorldData world;
    private final Planted planted;
    private TableSink bidsSink, lotsSink, eventsSink;

    private final List<SaleRun> pending = new ArrayList<>();
    private final Map<Integer, SaleRun> bySerial = new HashMap<>();
    private int serial;
    private int nextLotId = 1;
    private long nextBidId = 1;
    /** Лоты с {@code id}, ещё не записанные: {@code id} → торги, индекс. */
    private SaleRun[] lotRun = new SaleRun[1 << 12];
    private int[] lotIdx = new int[1 << 12];
    private int nextToWrite = 1;
    private long bidsFlushedMs = Long.MIN_VALUE;
    /** Ключ лота → {@code lots.id} (прежнее выставление, журнал вещи, мир). */
    final LongIntHash lotIds = new LongIntHash(1 << 16);
    private final ThreadLocal<LotRunner> runners;
    private final AuditLot auditLot = new AuditLot();
    long stSales, stLots, stBids, stEvents;

    Orchestrator(GenContext ctx, HouseRules house, Intake intake, io.hammerhall.data.auc.world.ClientBase clients, Prep prep,
                 PaymentPlanner planner, Behavior behavior, int threads) {
        this.ctx = ctx;
        this.house = house;
        this.prep = prep;
        this.cut = io.hammerhall.data.auc.world.Horizon.cut(ctx.period());
        this.end = io.hammerhall.data.auc.world.Horizon.end(ctx.period());
        this.cutMs = cut * 1000;
        this.threads = threads;
        this.behavior = behavior;
        this.bidStream = ctx.stream("auction.bids");
        this.faults = new FaultPlan(ctx.stream("auction.reject"), FaultPlan.rateFor(ctx.profile().scale()));
        this.ledger = new ClientLedger(clients.size(), clients);
        this.itemLog = new ItemLog(cut);
        this.outcomes = new Outcomes(house, prep, planner, ledger, itemLog, ctx.stream("auction.outcome"), cut);
        this.planted = ctx.planted();
        this.audit = new AuctionAudit(house.increments(), cut, planted);
        this.world = new WorldData(cut, clients);
        this.corridors = new io.hammerhall.data.auc.auction.audit.Corridors(ctx.profile().scale());
        this.outcomes.corridors = corridors;
        this.runners = ThreadLocal.withInitial(() -> new LotRunner(house.auctionRules(), behavior, bidStream, faults));
        if (intake == null) throw new IllegalArgumentException("нет потока приёма");
        this.intake = intake;
    }

    private final Intake intake;

    long cut() {
        return cut;
    }

    // ================================================================ цикл

    void run() throws IOException {
        bidsSink = ctx.streamSink("bids");
        lotsSink = ctx.streamSink("lots");
        eventsSink = ctx.streamSink("lot_events");
        LongIntHash ids = lotIds; // подготовке — только ключи лотов, не весь оркестр
        prep.open(ctx, key -> ids.get(key, 0));
        Calendar cal = prep.calendar();
        // открытие ставок → наименьшая публикация торгов, открывающихся не раньше (водораздел публикации)
        List<Calendar.Sale> todo = new ArrayList<>();
        for (Calendar.Sale s : cal.sales()) if (s.publishedAt() > 0 && s.biddingOpensAt() > 0 && s.biddingOpensAt() < end) todo.add(s);
        todo.sort((a, b) -> a.biddingOpensAt() != b.biddingOpensAt() ? Long.compare(a.biddingOpensAt(), b.biddingOpensAt())
                : Integer.compare(a.id(), b.id()));
        long[] opens = new long[todo.size()], pubMin = new long[todo.size() + 1];
        pubMin[todo.size()] = INF;
        for (int i = todo.size() - 1; i >= 0; i--) {
            opens[i] = todo.get(i).biddingOpensAt();
            pubMin[i] = Math.min(pubMin[i + 1], todo.get(i).publishedAt());
        }
        ExecutorService pool = threads > 1 ? Executors.newFixedThreadPool(threads) : null;
        try {
            if (!todo.isEmpty()) {
                long t0 = System.nanoTime();
                for (long mon = monday(opens[0]); mon < end; mon += WEEK) {
                    List<Calendar.Sale> week = new ArrayList<>();
                    for (Calendar.Sale s : cal.openingBetween(mon, mon + WEEK)) {
                        if (s.publishedAt() > 0 && s.biddingOpensAt() > 0 && s.biddingOpensAt() < end) week.add(s);
                    }
                    week(week, pool);
                    long next = mon + WEEK;
                    int k = Arrays.binarySearch(opens, next);
                    if (k < 0) k = -k - 1;
                    while (k > 0 && opens[k - 1] >= next) k--;
                    flush(Math.min(next, pubMin[k]));
                    if (day(mon).getDayOfMonth() <= 7 && day(mon).getMonthValue() % 3 == 1) {
                        Runtime rt = Runtime.getRuntime();
                        ctx.log(String.format(java.util.Locale.ROOT, "auction: %s, %.0f с, торгов %d, лотов %d, ставок %d, куча %d МБ",
                                day(mon), (System.nanoTime() - t0) / 1e9, stSales, stLots, stBids,
                                (rt.totalMemory() - rt.freeMemory()) >> 20));
                    }
                }
            }
        } finally {
            if (pool != null) pool.shutdownNow();
            runners.remove();
        }
        assignLotIds(INF);
        prep.flush(INF);
        flushBids(INF);
        flushLots();
        if (nextToWrite != nextLotId) throw new IllegalStateException("не записаны лоты с " + nextToWrite + " по " + (nextLotId - 1));
        for (SaleRun r : pending) {
            if (!r.done()) {
                throw new IllegalStateException("торги " + r.sale.id() + ": не всё записано — лотов " + r.lotsWritten + " из "
                        + r.lotsInRelease + ", ставок " + r.bidsWritten + " из " + r.bidsInRelease + ", курсор публикации "
                        + r.pubCursor + " из " + r.lots + ", следующий лот " + nextToWrite + " из " + nextLotId);
            }
        }
        for (SaleRun r : pending) prep.saleWritten(r.sale.id());
        pending.clear();
        bySerial.clear();
        prep.finish();
        writeItemLog();
        world.finish(outcomes.invoices, ledger, prep);
        ctx.log("auction: торгов " + stSales + ", лотов " + stLots + " (в выпуске " + (nextLotId - 1) + "), ставок в выпуске "
                + (nextBidId - 1) + ", событий лота " + stEvents + "; продаж после торгов " + outcomes.stAfterSales
                + ", счетов " + outcomes.stInvoices + ", отказов " + outcomes.stDefaults + ", второй шанс "
                + outcomes.stSecondChance + ", недостач " + outcomes.stShortfalls);
    }

    private static LocalDate day(long mon) {
        return Instant.ofEpochSecond(mon).atZone(HouseRules.ZONE).toLocalDate();
    }

    /** Понедельник 00:00 по Москве недели момента. */
    static long monday(long at) {
        LocalDate d = Instant.ofEpochSecond(at).atZone(HouseRules.ZONE).toLocalDate();
        while (d.getDayOfWeek() != DayOfWeek.MONDAY) d = d.minusDays(1);
        return d.atStartOfDay(HouseRules.ZONE).toEpochSecond();
    }

    // ================================================================ неделя

    private void week(List<Calendar.Sale> sales, ExecutorService pool) throws IOException {
        int n = sales.size();
        if (n == 0) return;
        SalePlan[] plans = new SalePlan[n];
        for (int i = 0; i < n; i++) {
            plans[i] = prep.catalog(sales.get(i));
            if (plans[i].sale().id() != sales.get(i).id()) throw new IllegalStateException("каталог не тех торгов");
        }
        SaleRun[] runs = new SaleRun[n];
        int base = serial;
        serial += n;
        if (pool == null || n == 1) {
            for (int i = 0; i < n; i++) runs[i] = simulate(base + i, sales.get(i), plans[i]);
        } else {
            List<Future<SaleRun>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int k = i;
                fs.add(pool.submit(() -> simulate(base + k, sales.get(k), plans[k])));
            }
            for (int i = 0; i < n; i++) {
                try {
                    runs[i] = fs.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("прервано", e);
                } catch (ExecutionException e) {
                    Throwable c = e.getCause();
                    if (c instanceof RuntimeException re) throw re;
                    if (c instanceof Error er) throw er;
                    throw new IOException(c);
                }
            }
        }
        for (SaleRun r : runs) merge(r);
    }

    /** Участники и движок по всем лотам торгов — без общего состояния (параллельно для торгов недели). */
    private SaleRun simulate(int serialNo, Calendar.Sale sale, SalePlan plan) {
        List<Participants> parts = prep.demand(plan, ledger);
        List<SalePlan.Lot> lots = plan.lots();
        if (parts.size() != lots.size()) {
            throw new IllegalStateException("торги " + sale.id() + ": лотов " + lots.size() + ", участников на " + parts.size());
        }
        LotRunner runner = runners.get();
        LotRows rows = new LotRows();
        long[] slot = new long[lots.size()];
        boolean[] platform = new boolean[lots.size()];
        Map<Integer, SalePlan.Session> sessions = new HashMap<>();
        for (SalePlan.Session s : plan.sessions()) sessions.put(s.no(), s);
        Map<Integer, Integer> ordinal = new HashMap<>();
        int[] partFrom = new int[lots.size() + 1];
        int np = 0;
        for (Participants ps : parts) np += ps.bidders().size();
        long[] pReg = new long[np];
        int[] pClient = new int[np];
        boolean[] pOnline = new boolean[np];
        int pk = 0;
        for (int i = 0; i < lots.size(); i++) {
            SalePlan.Lot l = lots.get(i);
            Participants ps = parts.get(i);
            if (ps.lotKey() != l.key()) throw new IllegalStateException("участники не того лота: " + ps.lotKey() + " ≠ " + l.key());
            long open, close;
            int auctioneer = 0;
            boolean withdrawn = l.withdrawnAt() > 0;
            if (sale.live()) {
                SalePlan.Session s = sessions.get(l.sessionNo());
                if (s == null) throw new IllegalStateException("лот " + l.label() + ": нет сессии " + l.sessionNo());
                auctioneer = s.auctioneerId();
                if (withdrawn) {
                    open = s.startsAt() * 1000;
                    close = open + 1;
                } else {
                    int k = ordinal.merge(s.no(), 1, Integer::sum) - 1;
                    open = Schedule.liveSlotStart(s.startsAt() * 1000, s.lotsPerHour(), k);
                    close = Schedule.liveSlotEnd(s.startsAt() * 1000, s.lotsPerHour(), k);
                }
            } else {
                open = (l.opensAt() > 0 ? l.opensAt() : sale.biddingOpensAt()) * 1000;
                close = l.closesAtPlanned() * 1000;
                if (withdrawn && close <= open) close = open + 1;
            }
            slot[i] = open;
            partFrom[i] = pk;
            for (Participants.Participant p : ps.bidders()) {
                pReg[pk] = p.regKey();
                pClient[pk] = p.clientId();
                pOnline[pk] = p.bidder().channel() == Channel.ONLINE;
                pk++;
            }
            LotInput in = input(sale, l, ps, open, close, auctioneer);
            int li = runner.run(in, rows);
            if (rows.topReg[li] != 0) {
                for (Participants.Participant p : ps.bidders()) {
                    if (p.regKey() == rows.topReg[li]) platform[li] = p.via() == Via.PARTNER_PLATFORM;
                }
            }
        }
        partFrom[lots.size()] = pk;
        SaleRun run = new SaleRun(serialNo, sale, plan, rows, slot, platform, cutMs);
        run.partFrom = partFrom;
        run.pReg = pReg;
        run.pClient = pClient;
        run.pOnline = pOnline;
        return run;
    }

    /** Участники мира → вход обёртки движка. */
    private static LotInput input(Calendar.Sale sale, SalePlan.Lot l, Participants ps, long open, long close, int auctioneer) {
        List<Participant> out = new ArrayList<>();
        Map<Long, Integer> byReg = new HashMap<>();
        for (Participants.Participant p : ps.bidders()) {
            Bidder b = p.bidder();
            if (b.id() != p.regKey()) throw new IllegalArgumentException("лот " + l.label() + ": Bidder.id ≠ ключ регистрации");
            Channel ch = b.channel();
            if (ch == Channel.ONLINE && p.orderKey() != 0) {
                throw new IllegalArgumentException("лот " + l.label() + ": онлайн-максимум заранее — поручение рождается в торгах");
            }
            byte via = (byte) p.via().ordinal();
            byte phone = ch == Channel.PHONE ? (p.phoneNoAnswer() ? Codes.PO_NO_ANSWER : Codes.PO_CONNECTED) : Codes.PO_NONE;
            long orderMax = ch == Channel.ABSENTEE || (ch == Channel.PHONE && p.phoneNoAnswer()) ? b.max() : 0;
            byReg.put(p.regKey(), out.size());
            out.add(new Participant(p.regKey(), p.clientId(), ch, b.strategy(), b.max(), orderMax, b.placedAt(), via,
                    p.phoneClerkId(), phone, true));
        }
        List<Attempt> att = new ArrayList<>();
        for (Participants.Rejected r : ps.rejected()) {
            if (r.regKey() == 0) {
                throw new IllegalArgumentException("лот " + l.label() + ": попытка без регистрации — у ставки нужна регистрация");
            }
            Integer k = byReg.get(r.regKey());
            if (k == null) {
                k = out.size();
                byReg.put(r.regKey(), k);
                out.add(Participant.rejectedOnly(r.regKey(), r.clientId(), r.channel(),
                        r.channel() == Channel.ONLINE ? Codes.VIA_SITE : Codes.VIA_OFFICE));
            }
            boolean max = r.kind() == io.hammerhall.data.auc.bid.BidKind.MAX;
            att.add(new Attempt(r.atMs(), k, r.amountKop(), max, Codes.reason(r.reason())));
        }
        LotInput in = new LotInput(l.key(), sale.live(), l.startKop(), l.reserveKop(), open, close, auctioneer, out, att,
                List.of(), l.withdrawnAt() > 0 ? l.withdrawnAt() * 1000 : 0, null);
        return in;
    }

    // ================================================================ исход (по порядку)

    private void merge(SaleRun r) {
        stSales++;
        stLots += r.lots;
        outcomes.process(r);
        // события поручений — подготовке торгов
        for (int i = 0; i < r.lots; i++) prep.orders(orderEvents(r, i));
        prep.saleClosed(r.sale.id(), Outcomes.saleEndSec(r), Outcomes.sessionEnds(r));
        int inRelease = 0;
        for (int i = 0; i < r.lots; i++) if (r.lot(i).publishedAt() < cut) inRelease++;
        r.lotsInRelease = inRelease;
        pending.add(r);
        bySerial.put(r.serial, r);
        world.saleProcessed(r, prep.registrations(), cut);
    }

    /** Редакции поручений лота → события для подготовки торгов (новые редакции и окончательные статусы). */
    private static OrderEvents orderEvents(SaleRun r, int i) {
        LotRows rows = r.rows;
        List<long[]> ev = new ArrayList<>();
        List<OrderEvents.Event> out = new ArrayList<>();
        for (int k = rows.revFrom[i]; k < rows.revTo[i]; k++) {
            Via via = Via.values()[rows.rVia[k]];
            boolean preset = rows.rRev[k] == 1 && (rows.rKind[k] == Codes.OK_ABSENTEE || rows.rKind[k] == Codes.OK_PHONE);
            if (!preset) {
                OrderEvents.Type t = rows.rStatus[k] == Codes.OS_REJECTED ? OrderTypes.rejected()
                        : rows.rReason[k] == Codes.OR_RAISE_LEADING ? OrderEvents.Type.RAISED
                        : rows.rRev[k] == 1 ? OrderEvents.Type.PLACED : OrderEvents.Type.REBID;
                out.add(new OrderEvents.Event(t, rows.rReg[k], rows.rClient[k], rows.rRev[k], rows.rMax[k], rows.rPlaced[k], via));
                ev.add(new long[]{rows.rPlaced[k], out.size() - 1});
            }
            OrderEvents.Type fin = switch (rows.rStatus[k]) {
                case Codes.OS_WON -> OrderEvents.Type.WON;
                case Codes.OS_OUTBID -> OrderEvents.Type.OUTBID;
                case Codes.OS_UNUSED -> OrderEvents.Type.UNUSED;
                default -> null;
            };
            if (fin != null) {
                out.add(new OrderEvents.Event(fin, rows.rReg[k], rows.rClient[k], rows.rRev[k], rows.rMax[k], rows.rEnded[k], null));
                ev.add(new long[]{rows.rEnded[k], out.size() - 1});
            }
        }
        ev.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        List<OrderEvents.Event> sorted = new ArrayList<>(out.size());
        for (long[] e : ev) sorted.add(out.get((int) e[1]));
        return new OrderEvents(r.lot(i).key(), sorted);
    }

    // ================================================================ водораздел

    private void flush(long w) throws IOException {
        long lim = Math.min(w, cut);
        assignLotIds(lim);
        prep.flush(w);
        flushBids(lim);
        flushLots();
        pending.removeIf(r -> {
            if (r.done()) {
                bySerial.remove(r.serial);
                prep.saleWritten(r.sale.id());
                return true;
            }
            return false;
        });
    }

    /** {@code lots.id} — лотам, опубликованным раньше {@code limit} (секунды), по (публикация, торги, номер, буква). */
    private void assignLotIds(long limit) {
        limit = Math.min(limit, cut);
        int n = 0;
        for (SaleRun r : pending) {
            for (int c = r.pubCursor; c < r.lots && r.lot(r.pubOrder[c]).publishedAt() < limit; c++) n++;
        }
        if (n == 0) {
            skipAfterCut(limit);
            return;
        }
        long[] k = new long[n], v = new long[n];
        Map<Long, SaleRun> owner = new HashMap<>();
        int m = 0;
        for (SaleRun r : pending) {
            while (r.pubCursor < r.lots && r.lot(r.pubOrder[r.pubCursor]).publishedAt() < limit) {
                SalePlan.Lot l = r.lot(r.pubOrder[r.pubCursor]);
                k[m] = l.publishedAt();
                v[m] = l.key();
                owner.put(l.key(), r);
                m++;
                r.pubCursor++;
            }
        }
        PairSort.sort(k, v, n);
        for (int j = 0; j < n; j++) {
            SaleRun r = owner.get(v[j]);
            int idx = indexOfLot(r, v[j]);
            int id = nextLotId++;
            r.lotId[idx] = id;
            lotIds.put(v[j], id);
            if (id >= lotRun.length) {
                lotRun = Arrays.copyOf(lotRun, lotRun.length * 2);
                lotIdx = Arrays.copyOf(lotIdx, lotIdx.length * 2);
            }
            lotRun[id] = r;
            lotIdx[id] = idx;
        }
        skipAfterCut(limit);
    }

    /** Лоты, опубликованные не раньше среза, в выпуск не входят: курсор публикации — до конца. */
    private void skipAfterCut(long limit) {
        if (limit < cut) return;
        for (SaleRun r : pending) {
            while (r.pubCursor < r.lots && r.lot(r.pubOrder[r.pubCursor]).publishedAt() >= cut) r.pubCursor++;
        }
    }

    private static int indexOfLot(SaleRun r, long key) {
        List<SalePlan.Lot> ls = r.plan.lots();
        int lo = 0, hi = ls.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long k = ls.get(mid).key();
            if (k == key) return mid;
            if (k < key) lo = mid + 1;
            else hi = mid - 1;
        }
        for (int i = 0; i < ls.size(); i++) if (ls.get(i).key() == key) return i;
        throw new IllegalStateException("нет лота " + key);
    }

    /** Ставки с моментом раньше {@code limit} (секунды): по (момент, мс, лот, seq) — {@code id} подряд. */
    private void flushBids(long limit) throws IOException {
        long limMs = Math.min(limit, cut) * 1000;
        int n = 0;
        for (SaleRun r : pending) {
            for (int c = r.bidCursor; c < r.bidOrder.length && (r.bidOrder[c] >>> 21) < limMs; c++) n++;
        }
        bidsFlushedMs = Math.max(bidsFlushedMs, limMs);
        if (n == 0) return;
        long[] k = new long[n], v = new long[n];
        int m = 0;
        for (SaleRun r : pending) {
            while (r.bidCursor < r.bidOrder.length && (r.bidOrder[r.bidCursor] >>> 21) < limMs) {
                long o = r.bidOrder[r.bidCursor++];
                int b = (int) (o & ((1 << 21) - 1));
                int lot = r.rows.bLot[b];
                int id = r.lotId[lot];
                if (id == 0) throw new IllegalStateException("ставка лота без id: торги " + r.sale.id() + ", лот " + r.lot(lot).label());
                if (id >= 1 << 21) throw new IllegalStateException("лотов больше 2 млн — расширить ключ порядка ставок");
                k[m] = (o >>> 21) << 21 | id;
                v[m] = (long) r.serial << 32 | b;
                m++;
            }
        }
        PairSort.sort(k, v, n);
        Registrations regs = prep.registrations();
        for (int j = 0; j < n; j++) {
            SaleRun r = bySerial.get((int) (v[j] >>> 32));
            int b = (int) v[j];
            writeBid(r, b, regs);
        }
    }

    private void writeBid(SaleRun r, int b, Registrations regs) throws IOException {
        LotRows w = r.rows;
        int lot = w.bLot[b];
        long id = nextBidId++;
        r.bidId[b] = id;
        r.bidsWritten++;
        stBids++;
        long t = w.bTime[b];
        Integer reg = null;
        if (w.bReg[b] != 0) {
            int rid = regs.id(w.bReg[b]);
            if (rid <= 0) {
                Registrations.Registration g = regs.get(w.bReg[b]);
                throw new IllegalStateException("ставка (торги " + r.sale.id() + ", лот " + r.lot(lot).label() + ", "
                        + Codes.KINDS.get(w.bKind[b]) + ", " + Codes.REASONS.get(w.bReason[b]) + ") в " + t + ": у регистрации "
                        + w.bReg[b] + " нет id; регистрация — " + (g == null ? "нет в Registrations"
                        : "подана " + g.registeredAt() + ", " + g.status()) + "; водораздел ставок " + bidsFlushedMs);
            }
            reg = rid;
        }
        Integer order = null;
        if (w.bRev[b] > 0) {
            long ok = Keys.order(r.lot(lot).key(), w.bClient[b], w.bRev[b]);
            int oid = regs.orderId(ok);
            if (oid <= 0) throw new IllegalStateException("ставка: у поручения " + ok + " нет id к моменту " + t);
            order = oid;
        }
        byte reason = w.bReason[b];
        bidsSink.add(id, r.lotId[lot], b - w.bidFrom[lot] + 1, reg, Codes.CHANNELS.get(w.bChannel[b]),
                Codes.KINDS.get(w.bKind[b]), order, w.bAmount[b], Math.floorDiv(t, 1000L), (short) Math.floorMod(t, 1000L),
                w.bAccepted[b], reason == 0 ? null : Codes.REASONS.get(reason), w.bExtended[b], w.requestKey(b),
                w.bClerk[b] == 0 ? null : w.bClerk[b]);
        // форма сайта или приложения: ручная онлайн, не партнёрская площадка, не запрос мимо формы (off_increment)
        world.bid(id, r.lotId[lot], w.bClient[b], t, w.bAmount[b], w.bAccepted[b],
                w.bChannel[b] == Codes.CH_ONLINE && w.bKind[b] == Codes.K_MANUAL && !w.bPartner[b]
                        && w.bReason[b] != Codes.R_OFF_INCREMENT);
    }

    /** Лоты по {@code id}: пишется, когда записаны все его ставки до среза. */
    private void flushLots() throws IOException {
        Registrations regs = prep.registrations();
        while (nextToWrite < nextLotId) {
            SaleRun r = lotRun[nextToWrite];
            int i = lotIdx[nextToWrite];
            if (readyMs(r, i) >= bidsFlushedMs) break;
            writeLot(r, i, regs);
            prep.lotWritten(r.lot(i).key());
            r.written[i] = true;
            r.lotsWritten++;
            lotRun[nextToWrite] = null;
            nextToWrite++;
        }
    }

    /**
     * Последний момент лота до среза (мс): ставки, закрытие, продажа после торгов, снятие. Лот пишется, когда водораздел
     * его прошёл: все ставки записаны, регистрации покупателей выданы.
     */
    private long readyMs(SaleRun r, int i) {
        long t = r.lastBidMs[i];
        LotRows w = r.rows;
        if (w.result[i] != Codes.RES_WITHDRAWN && w.closedMs[i] < cutMs) t = Math.max(t, w.closedMs[i]);
        if (r.result[i] == Codes.RES_SOLD_AFTER_SALE && r.afterSaleAt[i] < cut) t = Math.max(t, r.afterSaleAt[i] * 1000);
        SalePlan.Lot l = r.lot(i);
        if (l.withdrawnAt() > 0 && l.withdrawnAt() < cut) t = Math.max(t, l.withdrawnAt() * 1000);
        // редакции поручений до среза (подъём максимума — без строки ставки): их id — у подготовки торгов к водоразделу
        for (int k = w.revFrom[i]; k < w.revTo[i]; k++) if (w.rPlaced[k] < cutMs) t = Math.max(t, w.rPlaced[k]);
        return t;
    }

    // ================================================================ лот и журнал лота

    private void writeLot(SaleRun r, int i, Registrations regs) throws IOException {
        SalePlan.Lot l = r.lot(i);
        LotRows w = r.rows;
        int id = r.lotId[i];
        boolean live = r.sale.live();
        auditLot.clear();
        long closedMs = w.result[i] == Codes.RES_WITHDRAWN ? -1 : w.closedMs[i];
        boolean closed = closedMs >= 0 && closedMs < cutMs;
        boolean withdrawn = l.withdrawnAt() > 0 && l.withdrawnAt() < cut;
        boolean afterSale = r.result[i] == Codes.RES_SOLD_AFTER_SALE && r.afterSaleAt[i] < cut;
        byte res;
        if (withdrawn) res = Codes.RES_WITHDRAWN;
        else if (!closed) res = Codes.RES_OPEN;
        else if (w.result[i] == Codes.RES_SOLD) res = Codes.RES_SOLD;
        else res = afterSale ? Codes.RES_SOLD_AFTER_SALE : Codes.RES_BOUGHT_IN;
        int bidCount = 0, ext = 0;
        for (int b = w.bidFrom[i]; b < w.bidTo[i]; b++) {
            if (w.bTime[b] >= cutMs) continue;
            if (w.bAccepted[b]) bidCount++;
            if (w.bExtended[b]) ext++;
        }
        // снятый лот: открыт, только если снят после открытия ставок (онлайн)
        long opened = w.result[i] != Codes.RES_WITHDRAWN ? w.openedMs[i]
                : !live && r.slotStart[i] < l.withdrawnAt() * 1000 ? r.slotStart[i] : 0;
        Long openedAt = opened > 0 && opened < cutMs ? Math.floorDiv(opened, 1000L) : null;
        boolean sold = res == Codes.RES_SOLD;
        long winBid = sold ? r.bidId[w.winningBid[i]] : 0;
        if (sold && winBid == 0) throw new IllegalStateException("лот " + id + ": ставка-победитель не записана");
        Integer buyer = null, under = null;
        if (sold) buyer = regId(regs, w.topReg[i]);
        if (res == Codes.RES_SOLD_AFTER_SALE) buyer = regId(regs, r.afterSaleReg[i]);
        if (sold && w.secondReg[i] != 0) under = regId(regs, w.secondReg[i]);
        int prev = l.previousLotKey() == 0 ? 0 : lotIds.get(l.previousLotKey(), 0);
        if (l.previousLotKey() != 0 && prev == 0) throw new IllegalStateException("лот " + id + ": прежнее выставление без id");
        lotsSink.add(id, r.sale.id(), l.lotNo(), l.label(), l.itemId(), l.consignmentId(), (short) l.offeringNo(),
                prev == 0 ? null : prev, live ? (Short) (short) l.sessionNo() : null, l.publishedAt(), l.lowKop(), l.highKop(),
                l.startKop(), l.reserveKop() > 0 ? l.reserveKop() : null, live ? null : l.closesAtPlanned(), openedAt,
                closed ? Math.floorDiv(closedMs, 1000L) : null, closed ? (Short) (short) Math.floorMod(closedMs, 1000L) : null,
                (short) ext, bidCount, Codes.RESULTS.get(res), sold ? w.hammer[i] : null, sold ? winBid : null, buyer, under,
                res == Codes.RES_SOLD_AFTER_SALE ? r.afterSalePrice[i] : null,
                res == Codes.RES_SOLD_AFTER_SALE ? r.afterSaleAt[i] : null, withdrawn ? l.withdrawnAt() : null,
                withdrawn ? l.withdrawnBy() : null);
        // журнал лота: публикация, открытие, события торгов, продажа после торгов — до среза
        int seq = 0;
        seq = event(id, seq, l.publishedAt() * 1000, Codes.E_PUBLISHED, 0, 0, 0, 0);
        if (openedAt != null) seq = event(id, seq, opened, Codes.E_OPENED, 0, 0, 0, live ? auctioneer(r, i) : 0);
        for (int e = w.evFrom[i]; e < w.evTo[i]; e++) {
            long t = w.eTime[e];
            if (t >= cutMs) continue;
            long bid = w.eBid[e] >= 0 ? r.bidId[w.eBid[e]] : 0;
            if (w.eBid[e] >= 0 && bid == 0) throw new IllegalStateException("лот " + id + ": ставка события не записана");
            seq = event(id, seq, t, w.eKind[e], bid, w.eNewClose[e], w.ePrice[e], w.eStaff[e]);
        }
        if (res == Codes.RES_SOLD_AFTER_SALE) {
            seq = event(id, seq, r.afterSaleAt[i] * 1000, Codes.E_AFTER_SALE, 0, 0, r.afterSalePrice[i], 0);
        }
        stEvents += seq;
        // заложенные сбои движка — ровно там, где движок сообщил, и только если след в выпуске
        if ((w.planted[i] & LotRows.P_DOUBLE) != 0 && plantedVisible(r, i, true)) planted.add("double_bid", id);
        if ((w.planted[i] & LotRows.P_MISSED) != 0 && plantedVisible(r, i, false)) planted.add("missed_extension", id);
        if ((w.planted[i] & LotRows.P_EARLY) != 0 && closed) planted.add("early_close", id);
        if (w.shillBids[i] > 0) planted.add("shill_bidding", id);
        // мир и самопроверка — по записанным значениям
        world.lot(id, r, i, res, closed ? closedMs / 1000 : 0, buyer == null ? 0 : buyer);
        world.plan(id, l.publishedAt(), live ? Math.floorDiv(r.slotStart[i], 1000L) : l.closesAtPlanned(), l.lotNo());
        world.participants(id, r, i);
        for (int k = w.revFrom[i]; k < w.revTo[i]; k++) {
            if (w.rPlaced[k] >= cutMs || w.rKind[k] != Codes.OK_AUTO) continue;
            if (w.rVia[k] != Codes.VIA_SITE && w.rVia[k] != Codes.VIA_APP) continue;
            int oid = regs.orderId(Keys.order(l.key(), w.rClient[k], w.rRev[k]));
            if (oid <= 0) throw new IllegalStateException("лот " + id + ": у редакции поручения нет id");
            world.order(oid, id, w.rClient[k], w.rPlaced[k], w.rVia[k] == Codes.VIA_APP);
        }
        fillAudit(r, i, id, res, closed ? closedMs : -1, ext, bidCount, sold ? w.hammer[i] : 0, winBid, buyer, under, regs);
        audit.lot(auditLot);
        corridors.lot(auditLot, r.sale.dept().ordinal());
    }

    private static int auctioneer(SaleRun r, int i) {
        for (SalePlan.Session s : r.plan.sessions()) if (s.no() == r.lot(i).sessionNo()) return s.auctioneerId();
        return 0;
    }

    /** След заложенного сбоя — до среза: дважды принятый повтор или пропущенное продление. */
    private boolean plantedVisible(SaleRun r, int i, boolean dbl) {
        LotRows w = r.rows;
        for (int b = w.bidFrom[i]; b < w.bidTo[i]; b++) {
            if (w.bTime[b] >= cutMs) continue;
            if (dbl && w.bAccepted[b] && w.bRepeatOf[b] >= 0) return true;
        }
        if (dbl) return false;
        // пропущенное продление: принятая ставка в окне без продления — смотрит самопроверка; здесь — лот закрыт до среза
        return w.closedMs[i] < cutMs;
    }

    private Integer regId(Registrations regs, long key) {
        int id = regs.id(key);
        if (id <= 0) throw new IllegalStateException("лот: у регистрации " + key + " нет id");
        return id;
    }

    private int event(int lotId, int seq, long t, byte kind, long bid, long newClose, long price, int staff) throws IOException {
        if (t >= cutMs) return seq;
        seq++;
        eventsSink.add(lotId, (short) seq, Math.floorDiv(t, 1000L), (short) Math.floorMod(t, 1000L), Codes.EVENTS.get(kind),
                bid == 0 ? null : bid, newClose > 0 ? Math.floorDiv(newClose, 1000L) : null,
                newClose > 0 ? (Short) (short) Math.floorMod(newClose, 1000L) : null, price > 0 ? price : null,
                staff == 0 ? null : staff);
        auditLot.event(seq, kind, t, bid, newClose, price, staff);
        return seq;
    }

    private void fillAudit(SaleRun r, int i, int id, byte res, long closedMs, int ext, int bidCount, long hammer,
                           long winBid, Integer buyer, Integer under, Registrations regs) {
        SalePlan.Lot l = r.lot(i);
        LotRows w = r.rows;
        AuditLot a = auditLot;
        a.lotId = id;
        a.saleId = r.sale.id();
        a.lotNo = l.lotNo();
        a.sessionNo = l.sessionNo();
        a.live = r.sale.live();
        a.start = l.startKop();
        a.reserve = l.reserveKop();
        a.low = l.lowKop();
        a.high = l.highKop();
        a.plannedMs = r.sale.live() ? 0 : l.closesAtPlanned() * 1000;
        a.openedMs = w.result[i] != Codes.RES_WITHDRAWN ? w.openedMs[i]
                : !r.sale.live() && r.slotStart[i] < l.withdrawnAt() * 1000 ? r.slotStart[i] : 0;
        a.closedMs = closedMs;
        a.extensions = ext;
        a.bidCount = bidCount;
        a.result = res;
        a.hammer = hammer;
        a.winningBid = winBid;
        a.buyerReg = buyer == null ? 0 : buyer;
        a.underbidderReg = under == null ? 0 : under;
        a.publishedSec = l.publishedAt();
        a.withdrawnSec = l.withdrawnAt();
        a.afterSaleSec = res == Codes.RES_SOLD_AFTER_SALE ? r.afterSaleAt[i] : 0;
        a.afterSalePrice = res == Codes.RES_SOLD_AFTER_SALE ? r.afterSalePrice[i] : 0;
        a.consignorClient = l.consignorId();
        a.cutMs = cutMs;
        a.capMs = HouseRules.EXTENSION_CAP_S * 1000L;
        a.windowMs = HouseRules.EXTENSION_WINDOW_S * 1000L;
        a.extendMs = HouseRules.EXTENSION_S * 1000L;
        for (int b = w.bidFrom[i]; b < w.bidTo[i]; b++) {
            if (w.bTime[b] >= cutMs) continue;
            long rid = w.bReg[b] == 0 ? 0 : regs.id(w.bReg[b]);
            long oid = 0, omax = 0;
            if (w.bRev[b] > 0) {
                oid = regs.orderId(Keys.order(l.key(), w.bClient[b], w.bRev[b]));
                for (int k = w.revFrom[i]; k < w.revTo[i]; k++) {
                    if (w.rReg[k] == w.bReg[b] && w.rRev[k] == w.bRev[b]) omax = w.rMax[k];
                }
            }
            a.bid(r.bidId[b], b - w.bidFrom[i] + 1, rid, w.bClient[b], w.bChannel[b], w.bKind[b], oid, omax, w.bAmount[b],
                    w.bTime[b], w.bAccepted[b], w.bReason[b], w.bExtended[b], w.bHasKey[b], w.bKeyHi[b], w.bKeyLo[b], w.bClerk[b]);
        }
        for (int k = w.revFrom[i]; k < w.revTo[i]; k++) {
            if (w.rPlaced[k] >= cutMs) continue;
            a.revision(regs.id(w.rReg[k]), w.rPlaced[k], w.rMax[k], w.rStatus[k] == Codes.OS_REJECTED);
        }
        a.plantedDouble = planted.has("double_bid", id);
        a.plantedMissed = planted.has("missed_extension", id);
        a.plantedEarly = planted.has("early_close", id);
        a.plantedReserveAboveLow = planted.has("reserve_above_low", id);
    }

    // ================================================================ журнал вещи

    private void writeItemLog() throws IOException {
        // приём: received → accepted / declined — по потоку приёма, раньше среза
        int n = intake.countReceivedBefore(cut);
        for (int id = 1; id <= n; id++) {
            Intake.Item it = intake.item(id);
            itemLog.add(id, it.receivedAt(), Codes.IS_RECEIVED, 0, 0);
            if (it.accepted()) {
                int c = prep.intakeConsignment(id);
                itemLog.add(id, it.decidedAt(), Codes.IS_ACCEPTED, 0, c);
            } else {
                itemLog.add(id, it.decidedAt(), Codes.IS_DECLINED, 0, 0);
            }
        }
        int[] order = itemLog.order();
        TableSink s = ctx.streamSink("item_status_log");
        int prevItem = -1, seq = 0;
        audit.beginItemLog();
        for (int o : order) {
            int item = itemLog.item(o);
            if (item != prevItem) {
                prevItem = item;
                seq = 0;
            }
            seq++;
            long lk = itemLog.lotKey(o);
            int lot = lk == 0 ? 0 : lotIds.get(lk, 0);
            if (lk != 0 && lot == 0) throw new IllegalStateException("журнал вещи " + item + ": лот без id");
            int c = itemLog.consignment(o);
            s.add(item, (short) seq, itemLog.at(o), Codes.ITEM_STATUSES.get(itemLog.status(o)), lot == 0 ? null : lot,
                    c == 0 ? null : c);
            audit.itemRow(item, seq, itemLog.at(o), itemLog.status(o));
        }
        audit.endItemLog();
        s.close();
    }
}
