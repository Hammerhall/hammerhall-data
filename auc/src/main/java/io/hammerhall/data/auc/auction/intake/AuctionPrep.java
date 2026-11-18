package io.hammerhall.data.auc.auction.intake;

import io.hammerhall.data.auc.auction.Prep;
import io.hammerhall.data.auc.auction.calendar.AuctionCalendar;
import io.hammerhall.data.auc.auction.calendar.CalendarView;
import io.hammerhall.data.auc.auction.calendar.SaleSpec;
import io.hammerhall.data.auc.auction.calendar.Wave;
import io.hammerhall.data.auc.auction.demand.DemandModel;
import io.hammerhall.data.auc.auction.demand.OrderBook;
import io.hammerhall.data.auc.auction.demand.RegDraft;
import io.hammerhall.data.auc.auction.demand.RegistrationBook;
import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Horizon;
import io.hammerhall.data.auc.world.Intake;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.LotResult;
import io.hammerhall.data.auc.world.LotResultCode;
import io.hammerhall.data.auc.world.OrderEvents;
import io.hammerhall.data.auc.world.Participants;
import io.hammerhall.data.auc.world.Registrations;
import io.hammerhall.data.auc.world.SalePlan;
import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Подготовка торгов — реализация {@link Prep} для оркестра модуля {@code auction}: календарь
 * ({@code auction.calendar}), каталог и договоры ({@code auction.intake}), участники и регистрации
 * ({@code auction.demand}). Пишет 6 таблиц: {@code sales}, {@code sale_sessions}, {@code consignments},
 * {@code consignment_items}, {@code registrations}, {@code bid_orders}.
 *
 * <h2>Повтор или возврат</h2>
 * Не продан — по договору: «выставить снова» — повтор, «вернуть» — возврат, «спросить» — пополам; третий раз не
 * выставляется, договор кончается — возврат. Повтор готов через 1–4 месяца с эстимейтом ниже на 15–35 % (вещь живых
 * торгов в 30 % случаев уходит в онлайн). Покупатель не заплатил и второго шанса нет — повтор на ближайших торгах (через
 * 3–20 дней, эстимейт тот же). Снят — возврат. Продан и оплачен — договор закрыт; покупатель-торговец сдаёт купленное
 * обратно в 10 % случаев, прочие — в 2 % (через 3–15 месяцев, {@link ConsignmentBook#resale}).
 */
public final class AuctionPrep implements Prep {
    public static final List<String> TABLES = List.of("sales", "sale_sessions", "consignments", "consignment_items",
            "registrations", "bid_orders");
    static final long DAY = 86_400;
    static final double RESALE_DEALER = 0.10, RESALE_OTHER = 0.02, REOFFER_TO_ONLINE = 0.30;

    private final HouseRules house;
    private final ClientBase clients;
    private final Intake intake;
    private final Period period;
    private final long cut;
    private final AuctionCalendar calendar;
    private final CalendarView view;
    private ConsignmentBook book;
    private final CatalogBuilder builder;
    private final DemandModel demand;
    private RegistrationBook regs = new RegistrationBook();
    private OrderBook orders = new OrderBook();
    /** Итоги регистраций для коридоров — посчитаны, когда генераторы закончили (книга отпущена). */
    private PrepAudit.RegStats regStats;
    private final RandomStreams.Stream outcome;
    private final Map<Integer, CatalogBuilder.Built> built = new HashMap<>();
    private final Map<Integer, long[]> closed = new HashMap<>();
    private final Map<Integer, Long> saleEnds = new HashMap<>();
    private GenContext ctx;
    private LotIds lotIds;
    private TableSink consSink, regSink, orderSink;
    private final Stats stats = new Stats();
    private final PrepAudit.Found found = new PrepAudit.Found();
    /**
     * Торги, записанные целиком ({@link #saleWritten}): каталог отпущен — для {@code sale_sessions} остаются сессии, для
     * проверки каталога — её итог по торгам ({@link PrepAudit#catalogSale}).
     */
    private final Map<Integer, Written> written = new HashMap<>();

    /** Итог отпущенного каталога торгов. */
    record Written(boolean empty, List<SalePlan.Session> sessions, long lots, long planted, List<String> bad) {
    }

    /** Счётчики для журнала и проверок. */
    static final class Stats {
        long reoffers, returns, resales, toOnline, regs, orderDrafts, participants, rejected;
        final long[] rejectByReason = new long[16];
    }

    public AuctionPrep(GenContext ctx) {
        this.ctx = ctx;
        this.house = ctx.get(AucKeys.HOUSE);
        this.clients = ctx.get(AucKeys.CLIENTS);
        this.intake = ctx.get(AucKeys.CATALOG);
        this.period = ctx.period();
        this.cut = Horizon.cut(period);
        this.calendar = new AuctionCalendar(ctx.streams());
        this.view = new CalendarView(calendar, period, cut, house);
        this.book = new ConsignmentBook(intake, clients, house, ctx.streams());
        this.builder = new CatalogBuilder(intake, house, book, view, calendar.modeled(period, cut), ctx.profile().scale(),
                ctx.streams(), ctx.planted());
        this.demand = new DemandModel(house, clients, intake, view, ctx.streams(), ctx.planted());
        this.outcome = ctx.streams().stream("auction.outcome");
        regs.orders(orders);
        ctx.log("подготовка торгов: торгов в выпуске " + view.sales().size() + ", с каталогом до среза "
                + view.sales().stream().filter(s -> view.published(s.id())).count());
    }

    public CalendarView view() {
        return view;
    }

    @Override
    public void open(GenContext ctx, LotIds ids) throws IOException {
        this.ctx = ctx;
        this.lotIds = ids;
        consSink = ctx.streamSink("consignments");
        regSink = ctx.streamSink("registrations");
        orderSink = ctx.streamSink("bid_orders");
    }

    @Override
    public Calendar calendar() {
        return view;
    }

    // ---------------------------------------------------------------- каталог и спрос

    @Override
    public SalePlan catalog(Calendar.Sale sale) {
        if (!view.published(sale.id())) return new SalePlan(sale, List.of(), List.of());
        CatalogBuilder.Built b = builder.build(sale);
        synchronized (built) {
            built.put(sale.id(), b);
        }
        return b.plan();
    }

    @Override
    public List<Participants> demand(SalePlan plan, ClientStates states) {
        if (plan.lots().isEmpty()) return List.of();
        DemandModel.SaleDemand d = demand.demand(plan, states::at);
        regs.add(plan.sale().id(), d.registrations(), d.lots());
        orders.add(d.orders());
        PrepAudit.demand(this, plan, d.lots(), d.registrations(), found);
        synchronized (stats) {
            stats.regs += d.registrations().size();
            stats.orderDrafts += d.orders().size();
            for (Participants p : d.lots()) {
                stats.participants += p.bidders().size();
                stats.rejected += p.rejected().size();
            }
        }
        return d.lots();
    }

    @Override
    public Registrations registrations() {
        if (regs == null) throw new IllegalStateException("регистрации отпущены: генераторы закончили");
        return regs;
    }

    @Override
    public void generatorsDone() {
        if (regs == null) return;
        regStats = PrepAudit.regStats(this);
        regs = null;
        orders = null;
        book = null;
    }

    PrepAudit.RegStats regStats() {
        return regStats != null ? regStats : PrepAudit.regStats(this);
    }

    @Override
    public void orders(OrderEvents events) {
        orders.apply(events);
    }

    // ---------------------------------------------------------------- итог лота

    private CatalogBuilder.Entry entry(long lotKey) {
        CatalogBuilder.Built b;
        synchronized (built) {
            b = built.get(Keys.lotSale(lotKey));
        }
        if (b == null) throw new IllegalStateException("лот " + lotKey + ": торги без каталога");
        int i = Keys.lotNo(lotKey) - 1;
        return b.entries()[i];
    }

    @Override
    public Disposition result(LotResult r) {
        CatalogBuilder.Entry e = entry(r.lotKey());
        SaleSpec spec = view.spec(r.saleId());
        orders.settleLot(r.lotKey(), r.closedAt(), r.buyerRegKey(), r.withdrawnAt());
        Rng rng = outcome.at(r.lotKey());
        int cons = e.consignmentId;
        switch (r.result()) {
            case OPEN -> {
                return Disposition.NONE;
            }
            case WITHDRAWN -> {
                long at = r.withdrawnAt() > 0 ? r.withdrawnAt() : r.freeAt();
                book.end(cons, e.itemId, ConsignmentBook.OUT_WITHDRAWN, at);
                stats.returns++;
                return Disposition.giveBack(at);
            }
            case SOLD, SOLD_AFTER_SALE -> {
                if (!r.buyerDefault() || r.secondChanceClientId() != 0) {
                    if (r.paidAt() > 0) {
                        book.end(cons, e.itemId, ConsignmentBook.OUT_SOLD, r.paidAt());
                        int buyer = r.secondChanceClientId() != 0 ? r.secondChanceClientId() : r.buyerClientId();
                        double p = clients.dealer(buyer) ? RESALE_DEALER : RESALE_OTHER;
                        if (buyer > 0 && rng.chance(p)) {
                            book.resale(e.itemId, buyer, r.lotKey(), r.paidAt(), rng);
                            stats.resales++;
                        }
                    }
                    return Disposition.NONE;
                }
                // отказ покупателя без второго шанса — повтор на ближайших торгах с пометкой
                long free = r.freeAt() > 0 ? r.freeAt() : r.defaultAt();
                if (e.offeringNo >= 3) return giveBack(cons, e, free + (3 + rng.nextInt(12)) * DAY);
                long ready = free + (3 + rng.nextInt(18)) * DAY;
                if (expired(cons, ready)) return giveBack(cons, e, free + (3 + rng.nextInt(12)) * DAY);
                builder.enqueue(builder.entry(e.itemId, cons, e.offeringNo + 1, r.lotKey(), e.low, e.high, e.reserve, ready,
                        e.forceOnline));
                stats.reoffers++;
                return Disposition.reoffer(free + DAY);
            }
            default -> {
                // не продан
                long free = r.freeAt() > 0 ? r.freeAt() : r.closedAt();
                String pol = book.policy(cons);
                boolean again = pol.equals("reoffer") || pol.equals("ask") && rng.chance(0.5);
                long ready = free + (30 + rng.nextInt(91)) * DAY;
                if (!again || e.offeringNo >= 3 || expired(cons, ready)) {
                    return giveBack(cons, e, free + (3 + rng.nextInt(19)) * DAY);
                }
                double c = Pricing.reofferCut(rng);
                Pricing.Estimate est = Pricing.reduce(house.increments(), new Pricing.Estimate(e.low, e.high), c);
                long res = Pricing.reduceReserve(house.increments(), e.reserve, est.low(), c);
                boolean online = e.forceOnline || spec.live() && rng.chance(REOFFER_TO_ONLINE) || est.low() < Pricing.MIN_LOW_LIVE;
                if (online && spec.live()) stats.toOnline++;
                builder.enqueue(builder.entry(e.itemId, cons, e.offeringNo + 1, r.lotKey(), est.low(), est.high(), res, ready,
                        online && spec.live() || e.forceOnline));
                stats.reoffers++;
                return Disposition.reoffer(free + (1 + rng.nextInt(5)) * DAY);
            }
        }
    }

    private boolean expired(int cons, long at) {
        return !Period.localDate(at, HouseRules.ZONE).isBefore(book.expiresOn(cons).minusDays(14));
    }

    private Disposition giveBack(int cons, CatalogBuilder.Entry e, long at) {
        book.end(cons, e.itemId, ConsignmentBook.OUT_RETURNED, at);
        stats.returns++;
        return Disposition.giveBack(at);
    }

    @Override
    public void saleClosed(int saleId, long endsAt, long[] sessionEndsAt) {
        synchronized (closed) {
            saleEnds.put(saleId, endsAt);
            closed.put(saleId, sessionEndsAt == null ? new long[0] : sessionEndsAt.clone());
        }
    }

    // ---------------------------------------------------------------- запись

    @Override
    public void lotWritten(long lotKey) {
        orders.lotWritten(lotKey);
    }

    /**
     * Торги записаны целиком: итоги лотов получены ({@link #result}), {@code lots.id} выданы и заложенные нарушения
     * отмечены — каталог больше не нужен: проверка каталога торгов — сейчас, для {@code sale_sessions} — сессии.
     */
    @Override
    public void saleWritten(int saleId) {
        CatalogBuilder.Built b;
        synchronized (built) {
            b = built.remove(saleId);
        }
        if (b == null) return;
        List<String> bad = new ArrayList<>();
        long[] n = PrepAudit.catalogSale(this, saleId, b.plan(), bad);
        written.put(saleId, new Written(b.plan().lots().isEmpty(), b.plan().sessions(), n[0], n[1], bad.isEmpty() ? List.of() : bad));
    }

    @Override
    public void flush(long watershed) throws IOException {
        long w = Math.min(watershed, cut);
        for (java.util.Iterator<Long> it = builder.plantedReserve.iterator(); it.hasNext(); ) {
            int id = lotIds.lotId(it.next());
            if (id > 0) {
                ctx.planted().add("reserve_above_low", id);
                it.remove();
            }
        }
        book.advance(w);
        book.rows(house.fee(HouseRules.SC_2024).id(), w, consSink::add);
        regs.flush(w, cut, regSink::add);
        orders.flush(w, cut, lotIds::lotId, regs::id, orderSink::add);
    }

    @Override
    public void finish() throws IOException {
        flush(cut);
        ctx.close("consignments");
        ctx.close("registrations");
        ctx.close("bid_orders");
        TableSink lines = ctx.streamSink("consignment_items");
        book.lineRows(cut, lines::add);
        lines.close();
        TableSink sales = ctx.streamSink("sales"), sessions = ctx.streamSink("sale_sessions");
        for (Calendar.Sale s : view.sales()) {
            SaleSpec p = view.spec(s.id());
            boolean pub = view.published(s.id());
            CatalogBuilder.Built b = built.get(s.id());
            Written wr = written.get(s.id());
            boolean has = b != null || wr != null;
            boolean empty = b != null ? b.plan().lots().isEmpty() : wr != null && wr.empty();
            long ends = saleEnds.getOrDefault(s.id(), 0L);
            if (pub && ends == 0 && has && empty && p.startsAt() < cut) ends = p.startsAt();
            boolean ended = ends > 0 && ends < cut;
            String status = !pub ? "scheduled" : p.biddingOpensAt() >= cut ? "published" : ended ? "closed" : "open";
            boolean timed = !p.live();
            sales.add(s.id(), s.code(), s.format().code(), s.tier().code(), s.dept().id(), s.officeId(), s.titleRu(), s.titleEn(),
                    pub ? p.publishedAt() : null, pub ? p.biddingOpensAt() : null, p.previewFrom(), p.previewTo(), p.startsAt(),
                    ended ? ends : null, timed ? Wave.LOTS_PER_GROUP : null, timed ? Wave.GROUP_INTERVAL_S : null,
                    timed ? Wave.EXTENSION_WINDOW_S : null, timed ? Wave.EXTENSION_S : null, timed ? Wave.EXTENSION_CAP_S : null,
                    s.premiumScheduleId(), s.paymentDueDays(), status);
            if (!has) continue;
            long[] se = closed.getOrDefault(s.id(), new long[0]);
            for (SalePlan.Session x : b != null ? b.plan().sessions() : wr.sessions()) {
                long e = x.no() - 1 < se.length ? se[x.no() - 1] : 0;
                sessions.add(s.id(), x.no(), x.startsAt(), e > 0 && e < cut ? e : null, x.firstLotNo(), x.lastLotNo(), x.auctioneerId());
            }
        }
        sales.close();
        sessions.close();
        // ключи лотов нужны проверке каталога только для торгов, не записанных целиком (таких к концу нет)
        if (built.isEmpty()) lotIds = null;
        ctx.log("подготовка торгов: каталог — " + builder.stats() + "; договоров " + book.written() + "; регистраций "
                + regs.issued() + "; " + orders.stats() + "; повторов " + stats.reoffers + " (в онлайн " + stats.toOnline
                + "), возвратов " + stats.returns + ", сдач обратно " + stats.resales + "; участников " + stats.participants
                + ", отказов до движка " + stats.rejected);
    }

    // ---------------------------------------------------------------- договоры

    @Override
    public AuctionWorld.Consignment consignment(int id) {
        if (book == null) throw new IllegalStateException("договоры отпущены: генераторы закончили");
        return book.consignment(id);
    }

    @Override
    public int intakeConsignment(int itemId) {
        if (book == null) throw new IllegalStateException("договоры отпущены: генераторы закончили");
        return book.intakeConsignment(itemId);
    }

    // ---------------------------------------------------------------- проверки

    @Override
    public void check(GenContext ctx, CheckReport report) {
        PrepAudit.check(this, ctx, report);
    }

    @Override
    public List<Aggregate> aggregates() {
        return PrepAudit.aggregates();
    }

    // ---- для проверок
    PrepAudit.Found found() {
        return found;
    }

    io.hammerhall.data.core.gen.Planted planted() {
        return ctx.planted();
    }

    Period period() {
        return period;
    }

    int lotId(long lotKey) {
        return lotIds == null ? 0 : lotIds.lotId(lotKey);
    }

    boolean hasLots(long regKey) {
        return regs.hasLots(regKey);
    }

    RegistrationBook regs() {
        return regs;
    }

    ConsignmentBook book() {
        return book;
    }

    AuctionCalendar auctionCalendar() {
        return calendar;
    }

    Map<Integer, CatalogBuilder.Built> built() {
        return built;
    }

    Map<Integer, Written> written() {
        return written;
    }

    long cut() {
        return cut;
    }

    double scale() {
        return ctx.profile().scale();
    }
}
