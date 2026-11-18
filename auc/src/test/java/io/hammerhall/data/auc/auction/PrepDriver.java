package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.AucDataset;
import io.hammerhall.data.auc.auction.intake.AuctionPrep;
import io.hammerhall.data.auc.bid.*;
import io.hammerhall.data.auc.world.*;
import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.*;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.schema.Schema;

import java.io.IOException;
import java.util.*;

/**
 * Тестовый драйвер подготовки торгов: модуль {@code auction} только с шестью таблицами подготовки — движок по лотам
 * напрямую, все платят на 3-й день, без отказов. Настоящий оркестр — {@code auction.Orchestrator}.
 */
final class PrepDriver {
    private PrepDriver() {
    }

    /** Последний прогон: подготовка и нижние оценки лотов по {@code lots.id} драйвера. */
    static AuctionPrep last;
    static final Map<Integer, Long> LOT_LOW = new HashMap<>();
    static final Map<Integer, Integer> LOT_SALE = new HashMap<>();
    /** Момент регистрации каждого участника и попытки — сразу после {@code demand()} (для проверки, что он не меняется). */
    static final Map<Long, Long> REG_AT_DEMAND = new HashMap<>();
    /** Участник или попытка раньше своей регистрации (по {@code registrations()} сразу после {@code demand()}). */
    static final List<String> EARLY = new ArrayList<>();

    static final class PrepDataset implements Dataset {
        final AucDataset d = new AucDataset();
        public String code() { return d.code(); }
        public String title() { return d.title(); }
        public String version() { return d.version(); }
        public String generatorVersion() { return d.generatorVersion(); }
        public long defaultSeed() { return d.defaultSeed(); }
        public io.hammerhall.data.core.time.Period period() { return d.period(); }
        public Schema schema() { return d.schema(); }
        public List<Module> modules() {
            List<Module> m = new ArrayList<>();
            for (Module x : d.modules()) if (Set.of("house", "people", "catalog").contains(x.name())) m.add(x);
            m.add(new Driver());
            return m;
        }
        public Release.Docs docs() { return d.docs(); }
        public List<Input> inputs() { return d.inputs(); }
        public List<Map<String, Object>> readInputs(Config c, boolean s) throws IOException { return d.readInputs(c, s); }
        public Map<String, Object> format() { return d.format(); }
        public int gzipLevel() { return d.gzipLevel(); }
    }

    static final class Driver implements Module {
        AuctionPrep prep;
        public String name() { return "auction"; }
        public List<String> requires() { return List.of("house", "people", "catalog"); }
        public List<String> tables() { return AuctionPrep.TABLES; }

        public void generate(GenContext ctx) throws IOException {
            prep = new AuctionPrep(ctx);
            last = prep;
            REG_AT_DEMAND.clear();
            EARLY.clear();
            LOT_LOW.clear();
            LOT_SALE.clear();
            Map<Long, Integer> lotIds = new HashMap<>();
            int[] nextLot = {1};
            prep.open(ctx, k -> lotIds.getOrDefault(k, 0));
            HouseRules house = ctx.get(AucKeys.HOUSE);
            BidEngine eng = new BidEngine(house.auctionRules(), Behavior.standard());
            LotOutcome out = new LotOutcome();
            Map<Integer, List<Long>> paid = new HashMap<>();
            Prep.ClientStates states = monday -> new ClientState() {
                public long asOf() { return monday; }
                public boolean blocked(int c) { return false; }
                public int paidInvoices(int c) {
                    List<Long> l = paid.get(c);
                    if (l == null) return 0;
                    int n = 0;
                    for (long t : l) if (t < monday) n++;
                    return n;
                }
                public boolean overdue(int c) { return false; }
            };
            io.hammerhall.data.auc.world.Calendar cal = prep.calendar();
            long end = Horizon.end(ctx.period());
            long first = Long.MAX_VALUE;
            for (var s : cal.sales()) if (s.biddingOpensAt() > 0) first = Math.min(first, s.biddingOpensAt());
            long w = io.hammerhall.data.auc.auction.demand.DemandModel.monday(first);
            RandomStreamsHolder rs = new RandomStreamsHolder(ctx);
            long lots = 0, sold = 0, bids = 0;
            for (; w < end; w += 7 * 86_400L) {
                List<io.hammerhall.data.auc.world.Calendar.Sale> week = cal.openingBetween(w, w + 7 * 86_400L);
                List<SalePlan> plans = new ArrayList<>();
                for (var s : week) {
                    SalePlan p = prep.catalog(s);
                    for (SalePlan.Lot l : p.lots()) {
                        LOT_LOW.put(nextLot[0], l.lowKop());
                        LOT_SALE.put(nextLot[0], s.id());
                        lotIds.put(l.key(), nextLot[0]++);
                    }
                    plans.add(p);
                }
                for (SalePlan p : plans) {
                    List<Participants> parts = prep.demand(p, states);
                    for (int i = 0; i < parts.size(); i++) {
                        SalePlan.Lot lot = p.lots().get(i);
                        long slot0 = p.sale().live() ? p.sessions().get(lot.sessionNo() - 1).startsAt() * 1000 : 0;
                        for (Participants.Participant x : parts.get(i).bidders()) {
                            Registrations.Registration g = prep.registrations().get(x.regKey());
                            REG_AT_DEMAND.put(x.regKey(), g.registeredAt());
                            long firstBid = p.sale().live() && !x.bidder().book() ? slot0 : x.bidder().placedAt();
                            if (firstBid < Math.max(g.registeredAt(), x.bidder().book() ? 0 : g.decidedAt()) * 1000) {
                                EARLY.add(p.sale().code() + " " + lot.label() + " " + x.regKey());
                            }
                        }
                        for (Participants.Rejected x : parts.get(i).rejected()) {
                            Registrations.Registration g = prep.registrations().get(x.regKey());
                            REG_AT_DEMAND.put(x.regKey(), g.registeredAt());
                            if (x.atMs() < g.registeredAt() * 1000) EARLY.add(p.sale().code() + " " + lot.label() + " попытка " + x.reason());
                        }
                    }
                    long saleEnd = 0;
                    long[] sessEnd = new long[p.sessions().size()];
                    Map<Integer, Integer> idxInSession = new HashMap<>();
                    for (int i = 0; i < p.lots().size(); i++) {
                        SalePlan.Lot l = p.lots().get(i);
                        lots++;
                        if (l.withdrawnAt() > 0) {
                            prep.result(result(l, p, LotResultCode.WITHDRAWN, 0, 0, l.withdrawnAt(), 0));
                            continue;
                        }
                        Lot lot;
                        if (p.sale().live()) {
                            SalePlan.Session ss = p.sessions().get(l.sessionNo() - 1);
                            int k = idxInSession.merge(ss.no(), 1, Integer::sum) - 1;
                            long s0 = io.hammerhall.data.auc.bid.Schedule.liveSlotStart(ss.startsAt() * 1000, ss.lotsPerHour(), k);
                            long s1 = io.hammerhall.data.auc.bid.Schedule.liveSlotEnd(ss.startsAt() * 1000, ss.lotsPerHour(), k);
                            lot = Lot.live(l.key(), l.startKop(), l.reserveKop(), s0, s1);
                        } else {
                            lot = Lot.online(l.key(), l.startKop(), l.reserveKop(), l.opensAt() * 1000, l.closesAtPlanned() * 1000);
                        }
                        List<Bidder> bs = new ArrayList<>();
                        for (Participants.Participant x : parts.get(i).bidders()) bs.add(x.bidder());
                        eng.run(lot, bs, rs.at(l.key())::nextLong, out);
                        bids += out.acceptedBids();
                        long closed = Math.floorDiv(out.closedAt(), 1000);
                        saleEnd = Math.max(saleEnd, closed);
                        if (p.sale().live()) sessEnd[l.sessionNo() - 1] = Math.max(sessEnd[l.sessionNo() - 1], closed);
                        prep.orders(events(l.key(), out, closed));
                        if (out.sold()) {
                            sold++;
                            long buyer = out.winnerId();
                            long pay = closed + 3 * 86_400L;
                            paid.computeIfAbsent(Keys.registrationClient(buyer), c -> new ArrayList<>()).add(pay);
                            prep.result(result(l, p, LotResultCode.SOLD, out.hammerPrice(), buyer, closed, pay));
                        } else {
                            prep.result(result(l, p, LotResultCode.BOUGHT_IN, 0, 0, closed, 0));
                        }
                    }
                    prep.saleClosed(p.sale().id(), saleEnd, sessEnd);
                }
                prep.flush(w + 7 * 86_400L);
            }
            prep.finish();
            ctx.log("драйвер: лотов " + lots + ", продано " + sold + ", принятых ставок " + bids);
        }

        static LotResult result(SalePlan.Lot l, SalePlan p, LotResultCode code, long price, long buyer, long closed, long paidAt) {
            boolean w = code == LotResultCode.WITHDRAWN;
            return new LotResult(l.key(), 0, p.sale().id(), l.itemId(), l.consignmentId(), p.sale().dept(), p.sale().format(),
                    l.offeringNo(), code, price, l.lowKop(), l.highKop(), l.reserveKop(), w ? 0 : closed, buyer,
                    buyer == 0 ? 0 : Keys.registrationClient(buyer), 0, 0, false, 0, 0, 0, paidAt,
                    code == LotResultCode.BOUGHT_IN || w ? closed : 0, w ? l.withdrawnAt() : 0, w ? l.withdrawnBy() : null);
        }

        static OrderEvents events(long lotKey, LotOutcome o, long closed) {
            List<OrderEvents.Event> ev = new ArrayList<>();
            Map<Long, Integer> rev = new TreeMap<>();
            Map<Long, Boolean> live = new TreeMap<>();
            for (int i = 0; i < o.size(); i++) {
                EventType t = o.type(i);
                boolean maxBid = t == EventType.BID && o.kind(i) == BidKind.MAX;
                if (!maxBid && t != EventType.MAX_RAISE) continue;
                long reg = o.bidderId(i);
                int c = Keys.registrationClient(reg);
                int r = rev.merge(reg, 1, Integer::sum);
                OrderEvents.Type ty;
                if (!o.accepted(i)) ty = OrderEvents.Type.REJECTED;
                else if (t == EventType.MAX_RAISE) ty = OrderEvents.Type.RAISED;
                else ty = r == 1 ? OrderEvents.Type.PLACED : OrderEvents.Type.REBID;
                if (ty != OrderEvents.Type.REJECTED && ty != OrderEvents.Type.RAISED && Boolean.TRUE.equals(live.get(reg))) {
                    ev.add(new OrderEvents.Event(OrderEvents.Type.OUTBID, reg, c, r - 1, 0, o.time(i), null));
                }
                ev.add(new OrderEvents.Event(ty, reg, c, r, o.maxAmount(i), o.time(i), Via.SITE));
                if (ty != OrderEvents.Type.REJECTED) live.put(reg, true);
            }
            long winner = o.sold() ? o.winnerId() : 0;
            for (Map.Entry<Long, Boolean> e : live.entrySet()) {
                long reg = e.getKey();
                int r = rev.get(reg);
                // последняя не отклонённая редакция
                ev.add(new OrderEvents.Event(reg == winner ? OrderEvents.Type.WON : OrderEvents.Type.OUTBID, reg,
                        Keys.registrationClient(reg), lastAccepted(ev, reg, r), 0, closed * 1000, null));
            }
            return new OrderEvents(lotKey, ev);
        }

        static int lastAccepted(List<OrderEvents.Event> ev, long reg, int r) {
            int best = 0;
            for (OrderEvents.Event e : ev) {
                if (e.regKey() == reg && (e.type() == OrderEvents.Type.PLACED || e.type() == OrderEvents.Type.REBID
                        || e.type() == OrderEvents.Type.RAISED)) best = Math.max(best, e.revision());
            }
            return best;
        }

        public void check(GenContext ctx, CheckReport r) { prep.check(ctx, r); }
        public List<Aggregate> aggregates() { return prep.aggregates(); }
    }

    static final class RandomStreamsHolder {
        final io.hammerhall.data.core.random.RandomStreams.Stream st;
        RandomStreamsHolder(GenContext ctx) { st = ctx.stream("auction.bids"); }
        io.hammerhall.data.core.random.Rng at(long k) { return st.at(k); }
    }
}
