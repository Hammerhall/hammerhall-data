package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.Horizon;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.InvoiceKind;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.LotResult;
import io.hammerhall.data.auc.world.LotResultCode;
import io.hammerhall.data.auc.world.PaymentPlanner.InvoiceDraft;
import io.hammerhall.data.auc.world.PaymentPlanner.Line;
import io.hammerhall.data.auc.world.PaymentPlanner.PaymentPlan;
import io.hammerhall.data.auc.world.RegMode;
import io.hammerhall.data.auc.world.Registrations;
import io.hammerhall.data.auc.world.SaleTier;
import io.hammerhall.data.auc.world.StaffRole;
import io.hammerhall.data.auc.world.Via;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.Rng;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Упрощённый модуль {@code auction} для проверки денег целиком (только тесты денег): календарь (онлайн — вт и чт,
 * живые — раз в месяц сезона), лоты с итогами, договоры, регистрации с депозитами дорогих лотов, счета через настоящий
 * {@link PaymentPlanner} — отказы, второй шанс, продажа после торгов; пишет {@code sales}, {@code consignments},
 * {@code registrations}, {@code lots} (минимально, но по ограничениям схемы) и кладёт {@code CALENDAR}, {@code AUCTION}.
 */
final class StubAuction implements Module {
    @Override
    public String name() {
        return "auction";
    }

    @Override
    public List<String> requires() {
        return List.of("house", "people", "catalog");
    }

    @Override
    public List<String> tables() {
        return List.of("sales", "consignments", "registrations", "lots");
    }

    // ---------------------------------------------------------------- мир

    static final class Cal implements Calendar {
        final List<Sale> sales = new ArrayList<>();

        @Override
        public List<Sale> sales() {
            return sales;
        }

        @Override
        public Sale sale(int id) {
            return sales.get(id - 1);
        }

        @Override
        public List<Sale> openingBetween(long fromAt, long toAt) {
            List<Sale> out = new ArrayList<>();
            for (Sale s : sales) if (s.biddingOpensAt() >= fromAt && s.biddingOpensAt() < toAt) out.add(s);
            return out;
        }
    }

    static final class Regs implements Registrations {
        final Map<Long, Registration> byKey = new TreeMap<>();
        final Map<Integer, List<Registration>> bySale = new TreeMap<>();

        @Override
        public Registration get(long key) {
            return byKey.get(key);
        }

        @Override
        public int id(long key) {
            Registration r = byKey.get(key);
            return r == null ? 0 : r.id();
        }

        @Override
        public List<Registration> ofSale(int saleId) {
            return bySale.getOrDefault(saleId, List.of());
        }

        @Override
        public int orderId(long orderKey) {
            return 0;
        }
    }

    static final class World implements AuctionWorld {
        final List<LotResult> lots = new ArrayList<>();
        final Map<Integer, List<LotResult>> bySale = new TreeMap<>();
        final List<Consignment> consignments = new ArrayList<>();
        final Regs regs = new Regs();
        final List<InvoicePlan> invoices = new ArrayList<>();

        @Override
        public int lotCount() {
            return lots.size();
        }

        @Override
        public LotResult lot(int lotId) {
            return lots.get(lotId - 1);
        }

        @Override
        public List<LotResult> lotsOfSale(int saleId) {
            return bySale.getOrDefault(saleId, List.of());
        }

        @Override
        public Consignment consignment(int id) {
            return consignments.get(id - 1);
        }

        @Override
        public Registrations registrations() {
            return regs;
        }

        @Override
        public List<InvoicePlan> invoices() {
            return invoices;
        }

        @Override
        public BidLog bids() {
            return null;
        }

        @Override
        public List<Notice> notices() {
            return List.of();
        }

        @Override
        public ClientOutcome clientOutcome(int clientId) {
            return ClientOutcome.NONE;
        }
    }

    /** Лот в работе — до выдачи id и среза. */
    private static final class L {
        Calendar.Sale sale;
        int lotNo, consignmentId, itemId, buyer, under;
        long low, high, reserve, start, closedAt, price, underAmount, afterAt, withdrawnAt, published;
        LotResultCode result;
        String withdrawnBy;
        boolean platform;
        long defaultAt, scPrice, paidAt, scClient;
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        HouseRules house = ctx.get(AucKeys.HOUSE);
        ClientBase clients = ctx.get(AucKeys.CLIENTS);
        PaymentPlanner planner = new PaymentPlanner(ctx.streams(), house, clients);
        long cut = Horizon.cut(ctx.period());
        long end = Horizon.end(ctx.period());
        Cal cal = new Cal();
        // календарь: онлайн — вт и чт в 19:00 (отделы по кругу), живые — 2-я суббота месяца сезона
        List<Calendar.Sale> raw = new ArrayList<>();
        int[] no = new int[4000];
        for (LocalDate d = LocalDate.of(2025, 10, 7); d.isBefore(LocalDate.of(2027, 10, 30)); d = d.plusDays(1)) {
            boolean online = d.getDayOfWeek() == DayOfWeek.TUESDAY || d.getDayOfWeek() == DayOfWeek.THURSDAY
                    || d.getDayOfWeek() == DayOfWeek.WEDNESDAY && d.getMonthValue() == 9 && d.getYear() == 2026;
            boolean live = d.getDayOfWeek() == DayOfWeek.SATURDAY && d.getDayOfMonth() >= 8 && d.getDayOfMonth() <= 14
                    && d.getMonthValue() != 1 && d.getMonthValue() != 7 && d.getMonthValue() != 8;
            if (d.getMonthValue() == 1 && d.getDayOfMonth() <= 10) continue;
            for (int f = 0; f < 2; f++) {
                if (f == 0 && !online || f == 1 && !live) continue;
                Format fmt = f == 0 ? Format.TIMED : Format.LIVE;
                Dept dept = Dept.values()[(int) (d.toEpochDay() % 6)];
                long starts = MoneyTime.at(d.toEpochDay(), (f == 0 ? 19 : 12) * 3600L);
                int n = ++no[d.getYear() * 2 - 4000 + f];
                long key = Keys.sale(fmt, dept, d.getYear(), n);
                long published = starts - (f == 0 ? 10 : 21) * 86_400L;
                long opens = f == 0 ? published + 3 * 86_400L : published;
                if (published >= cut) {
                    published = 0;
                    opens = 0;
                }
                raw.add(new Calendar.Sale(0, key, (f == 0 ? "T-" : "L-") + d.getYear() + "-" + String.format("%04d", n), fmt,
                        SaleTier.REGULAR, dept, f == 0 ? 1 : 2, 0, "Торги " + n, "Sale " + n, published, opens,
                        f == 1 ? d.minusDays(6) : null, f == 1 ? d.minusDays(1) : null, starts, 1, 0,
                        house.premiumSchedule(dept, d).id(), 10));
            }
        }
        raw.sort(Comparator.comparingLong(Calendar.Sale::startsAt).thenComparingLong(Calendar.Sale::key));
        for (int i = 0; i < raw.size(); i++) {
            Calendar.Sale s = raw.get(i);
            cal.sales.add(new Calendar.Sale(i + 1, s.key(), s.code(), s.format(), s.tier(), s.dept(), s.officeId(), 0,
                    s.titleRu(), s.titleEn(), s.publishedAt(), s.biddingOpensAt(), s.previewFrom(), s.previewTo(),
                    s.startsAt(), 1, 0, s.premiumScheduleId(), 10));
        }
        // лоты
        World w = new World();
        List<L> ls = new ArrayList<>();
        TableSink consS = ctx.sink("consignments");
        int specialist = house.active(StaffRole.SPECIALIST, null, 0, LocalDate.of(2026, 1, 1)).get(0).id();
        for (Calendar.Sale s : cal.sales) {
            if (s.publishedAt() == 0) continue;
            Rng r = ctx.stream("stub.lots").at(s.key());
            int nClients = Math.max(10, clients.countRegisteredBefore(s.publishedAt()));
            int lots = s.live() ? r.between(10, 20) : r.between(8, 18);
            for (int k = 1; k <= lots; k++) {
                L l = new L();
                l.sale = s;
                l.lotNo = k;
                l.published = s.publishedAt();
                int consignor = 1 + r.nextInt(nClients);
                w.consignments.add(new AuctionWorld.Consignment(w.consignments.size() + 1, consignor,
                        clients.dealer(consignor) ? "dealer" : "private", clients.dealer(consignor),
                        r.chance(0.05) ? 7_500 : null, s.publishedAt() - 20 * 86_400L));
                l.consignmentId = w.consignments.size();
                l.itemId = 1 + r.nextInt(80);
                long lowRub = Math.max(s.live() ? 5_000 : 1_000, Math.round(r.logNormal(s.live() ? 60_000 : 12_000, 1.2) / 1000) * 1000);
                if (s.live() && k <= 2 && r.chance(0.5)) lowRub = 3_200_000;
                l.low = lowRub * 100;
                l.high = l.low * 3 / 2;
                l.reserve = r.chance(0.85) ? l.low * 4 / 5 / 10_000 * 10_000 : 0;
                l.start = l.low / 2;
                l.closedAt = s.startsAt() + k * 60L;
                boolean open = l.closedAt >= cut;
                if (r.chance(0.03)) {
                    l.result = LotResultCode.WITHDRAWN;
                    l.withdrawnAt = s.publishedAt() + 86_400;
                    l.withdrawnBy = r.chance(0.7) ? "consignor" : "house";
                    l.closedAt = 0;
                } else if (r.chance(0.76)) {
                    l.result = LotResultCode.SOLD;
                    l.price = Math.max(l.reserve, l.low * (80 + r.nextInt(120)) / 100) / 10_000 * 10_000;
                    if (l.price < l.reserve || l.price == 0) l.price = Math.max(l.reserve, 100_000);
                    l.buyer = 1 + r.nextInt(nClients);
                    if (l.buyer == consignor) l.buyer = l.buyer % nClients + 1;
                    l.platform = s.live() && r.chance(0.2);
                    if (r.chance(0.8)) {
                        l.under = l.buyer % nClients + 1;
                        if (l.under == consignor) l.under = l.under % nClients + 1;
                        l.underAmount = Math.max(l.reserve, l.price - 10_000);
                    }
                } else {
                    l.result = LotResultCode.BOUGHT_IN;
                    if (r.chance(0.08) && l.closedAt + 7 * 86_400L < end) {
                        l.result = LotResultCode.SOLD_AFTER_SALE;
                        l.price = Math.max(l.reserve, l.low * 7 / 10 / 10_000 * 10_000);
                        if (l.price == 0) l.price = 100_000;
                        l.buyer = 1 + r.nextInt(nClients);
                        if (l.buyer == consignor) l.buyer = l.buyer % nClients + 1;
                        l.afterAt = l.closedAt + r.between(1, 7) * 86_400L;
                    }
                }
                // не закрыт к срезу: участники (и регистрации) — те же, итога нет
                if (open && l.result != LotResultCode.WITHDRAWN) l.result = LotResultCode.OPEN;
                ls.add(l);
            }
        }
        for (AuctionWorld.Consignment c : w.consignments) {
            consS.add(c.id(), c.consignorId(), 1, specialist, c.kind(), c.signedAt(), house.fee(HouseRules.SC_2024).id(),
                    c.commissionMilliPct() == null ? null : BigDecimal.valueOf(c.commissionMilliPct(), 3), c.dealerDiscount(),
                    "return", LocalDate.of(2028, 1, 1));
        }
        consS.close();
        // регистрации: покупатели и вторые; депозит — у дорогих лотов
        record RegPlan(long key, int sale, int client, long at, long deposit) {
        }
        TreeMap<Long, RegPlan> rp = new TreeMap<>();
        for (L l : ls) {
            for (int c : new int[]{l.buyer, l.under}) {
                if (c == 0) continue;
                long key = Keys.registration(l.sale.id(), c);
                long dep = l.low >= HouseRules.PREMIUM_LOT_FROM ? l.low / 10 : 0;
                RegPlan old = rp.get(key);
                long at = l.sale.biddingOpensAt() + 3600 + (c * 997L) % 86_400;
                rp.put(key, new RegPlan(key, l.sale.id(), c, at, Math.max(dep, old == null ? 0 : old.deposit)));
            }
        }
        List<RegPlan> rl = new ArrayList<>(rp.values());
        rl.sort(Comparator.comparingLong(RegPlan::at).thenComparingLong(RegPlan::key));
        TableSink regS = ctx.sink("registrations");
        int rid = 0;
        Map<Integer, Integer> paddle = new TreeMap<>();
        for (RegPlan p : rl) {
            if (p.at >= cut) continue;
            rid++;
            int pad = paddle.merge(p.sale, 1, Integer::sum) + 10_000;
            long approved = p.at + 600;
            Registrations.Registration reg = new Registrations.Registration(p.key, rid, p.sale, p.client, pad, p.at, Via.SITE,
                    RegMode.ONLINE, "approved", approved, 0, null, 0, p.deposit > 0 ? approved + 3 * 86_400L : 0, p.deposit);
            w.regs.byKey.put(p.key, reg);
            w.regs.bySale.computeIfAbsent(p.sale, k -> new ArrayList<>()).add(reg);
            regS.add(rid, p.sale, p.client, pad, p.at, "site", "online", "approved", approved, null, null, null,
                    reg.premiumApprovedAt() == 0 ? null : reg.premiumApprovedAt(), p.deposit == 0 ? null : p.deposit);
        }
        regS.close();
        // счета: по торгам и покупателю; продажа после торгов — свой счёт; второй шанс — после отказа
        TreeMap<Long, List<L>> byInvoice = new TreeMap<>();
        for (L l : ls) {
            if (l.result == LotResultCode.SOLD) byInvoice.computeIfAbsent(Keys.invoice(l.sale.id(), l.buyer, InvoiceKind.SALE, 0),
                    k -> new ArrayList<>()).add(l);
        }
        List<InvoiceDraft> drafts = new ArrayList<>();
        Map<Long, List<L>> linesOf = new TreeMap<>();
        for (var e : byInvoice.entrySet()) {
            List<L> g = e.getValue();
            Calendar.Sale s = g.get(0).sale;
            long issued = g.get(g.size() - 1).closedAt + 3600;
            List<Line> lines = new ArrayList<>();
            long dep = 0;
            for (L l : g) lines.add(new Line(Keys.lot(s.id(), l.lotNo, 0), l.price, l.platform));
            RegPlan p = rp.get(Keys.registration(s.id(), g.get(0).buyer));
            if (p != null) dep = p.deposit;
            drafts.add(new InvoiceDraft(e.getKey(), InvoiceKind.SALE, s.id(), s.format(), s.dept(), s.saleDate(),
                    Keys.registration(s.id(), g.get(0).buyer), g.get(0).buyer, false, issued,
                    HouseRules.dueAt(issued, 10), lines, dep, 0));
            linesOf.put(e.getKey(), g);
        }
        for (L l : ls) {
            if (l.result != LotResultCode.SOLD_AFTER_SALE) continue;
            long key = Keys.invoice(l.sale.id(), l.buyer, InvoiceKind.AFTER_SALE, l.lotNo % 256);
            // регистрация покупателя после торгов — та же, если есть; иначе — берётся первая регистрация торгов
            long regKey = Keys.registration(l.sale.id(), l.buyer);
            if (w.regs.get(regKey) == null) {
                List<Registrations.Registration> any = w.regs.ofSale(l.sale.id());
                if (any.isEmpty()) {
                    l.result = LotResultCode.BOUGHT_IN;
                    l.price = 0;
                    continue;
                }
                l.buyer = any.get(0).clientId();
                regKey = any.get(0).key();
                key = Keys.invoice(l.sale.id(), l.buyer, InvoiceKind.AFTER_SALE, l.lotNo % 256);
            }
            drafts.add(new InvoiceDraft(key, InvoiceKind.AFTER_SALE, l.sale.id(), l.sale.format(), l.sale.dept(),
                    l.sale.saleDate(), regKey, l.buyer, false, l.afterAt + 3600, HouseRules.dueAt(l.afterAt + 3600, 10),
                    List.of(new Line(Keys.lot(l.sale.id(), l.lotNo, 0), l.price, false)), 0, 0));
            linesOf.put(key, List.of(l));
        }
        drafts.sort(Comparator.comparingLong(InvoiceDraft::issuedAt).thenComparingLong(InvoiceDraft::key));
        List<InvoiceDraft> all = new ArrayList<>();
        Map<Integer, Integer> paidCount = new TreeMap<>();
        java.util.PriorityQueue<InvoiceDraft> q = new java.util.PriorityQueue<>(
                Comparator.comparingLong(InvoiceDraft::issuedAt).thenComparingLong(InvoiceDraft::key));
        q.addAll(drafts);
        Map<Long, PaymentPlan> plans = new TreeMap<>();
        while (!q.isEmpty()) {
            InvoiceDraft d0 = q.poll();
            if (d0.issuedAt() >= end) continue;
            boolean fresh = paidCount.getOrDefault(d0.clientId(), 0) < 2;
            InvoiceDraft d = new InvoiceDraft(d0.key(), d0.kind(), d0.saleId(), d0.format(), d0.dept(), d0.saleDate(),
                    d0.regKey(), d0.clientId(), fresh, d0.issuedAt(), d0.dueAt(), d0.lines(), d0.depositKop(), d0.relatedKey());
            PaymentPlan p = planner.plan(d);
            plans.put(d.key(), p);
            all.add(d);
            if (p.paidAt() != 0) paidCount.merge(d.clientId(), 1, Integer::sum);
            for (L l : linesOf.get(d.key())) {
                if (d.kind() == InvoiceKind.SECOND_CHANCE) {
                    l.paidAt = p.paidAt();
                } else if (p.defaults()) {
                    l.defaultAt = p.cancelAt();
                } else {
                    l.paidAt = p.paidAt();
                }
            }
            if (p.defaults() && p.secondChanceAccepted() && d.kind() == InvoiceKind.SALE) {
                for (L l : linesOf.get(d.key())) {
                    if (l.under == 0 || l.underAmount < l.reserve) continue;
                    long regKey = Keys.registration(l.sale.id(), l.under);
                    if (w.regs.get(regKey) == null) continue;
                    long at = p.cancelAt() + 2 * 86_400L;
                    long key = Keys.invoice(l.sale.id(), l.under, InvoiceKind.SECOND_CHANCE, l.lotNo % 256);
                    InvoiceDraft sc = new InvoiceDraft(key, InvoiceKind.SECOND_CHANCE, l.sale.id(), l.sale.format(),
                            l.sale.dept(), l.sale.saleDate(), regKey, l.under, false, at, HouseRules.dueAt(at, 10),
                            List.of(new Line(Keys.lot(l.sale.id(), l.lotNo, 0), l.underAmount, false)), 0, d.key());
                    l.scPrice = l.underAmount;
                    l.scClient = l.under;
                    linesOf.put(key, List.of(l));
                    q.add(sc);
                }
            }
        }
        for (InvoiceDraft d : all) if (d.issuedAt() < cut) w.invoices.add(new AuctionWorld.InvoicePlan(d, plans.get(d.key())));
        // лоты: id по публикации, итог на срез
        ls.sort(Comparator.<L>comparingLong(l -> l.published).thenComparingInt(l -> l.sale.id()).thenComparingInt(l -> l.lotNo));
        TableSink lotS = ctx.sink("lots");
        int id = 0;
        for (L l : ls) {
            id++;
            Calendar.Sale s = l.sale;
            long lotKey = Keys.lot(s.id(), l.lotNo, 0);
            boolean def = l.defaultAt != 0 && l.defaultAt < cut;
            long scIssued = l.scClient != 0 && l.defaultAt + 2 * 86_400L < cut ? l.scClient : 0;
            LotResult lr = new LotResult(lotKey, id, s.id(), l.itemId, l.consignmentId, s.dept(), s.format(), 1, l.result,
                    l.price, l.low, l.high, l.reserve, l.closedAt, l.buyer == 0 ? 0 : Keys.registration(s.id(), l.buyer),
                    l.buyer, l.under == 0 ? 0 : Keys.registration(s.id(), l.under), l.underAmount, def, def ? l.defaultAt : 0,
                    (int) scIssued, scIssued != 0 ? l.scPrice : 0, l.paidAt < cut ? l.paidAt : 0, 0, l.withdrawnAt,
                    l.withdrawnBy);
            w.lots.add(lr);
            w.bySale.computeIfAbsent(s.id(), k -> new ArrayList<>()).add(lr);
            boolean sold = l.result == LotResultCode.SOLD;
            Integer buyerReg = l.buyer == 0 ? null : nz(w.regs.id(Keys.registration(s.id(), l.buyer)));
            Integer underReg = l.under == 0 ? null : nz(w.regs.id(Keys.registration(s.id(), l.under)));
            lotS.add(id, s.id(), l.lotNo, Keys.lotLabel(lotKey), l.itemId, l.consignmentId, 1, null, s.live() ? 1 : null,
                    l.published, l.low, l.high, l.start, l.reserve == 0 ? null : l.reserve,
                    s.live() ? null : s.startsAt() + l.lotNo * 60L, s.biddingOpensAt(),
                    l.closedAt == 0 ? null : l.closedAt, l.closedAt == 0 ? null : 0, 0, sold ? 3 : 0, l.result.code(),
                    sold ? l.price : null, null, sold || l.result == LotResultCode.SOLD_AFTER_SALE ? buyerReg : null,
                    l.result == LotResultCode.OPEN ? null : underReg,
                    l.result == LotResultCode.SOLD_AFTER_SALE ? l.price : null,
                    l.result == LotResultCode.SOLD_AFTER_SALE ? l.afterAt : null,
                    l.withdrawnAt == 0 ? null : l.withdrawnAt, l.withdrawnBy);
        }
        lotS.close();
        TableSink saleS = ctx.sink("sales");
        for (Calendar.Sale s : cal.sales) {
            long endAt = 0;
            boolean open = false;
            for (LotResult l : w.lotsOfSale(s.id())) {
                endAt = Math.max(endAt, l.closedAt());
                if (l.result() == LotResultCode.OPEN) open = true;
            }
            String status = s.publishedAt() == 0 ? "scheduled" : open || endAt == 0 ? (s.biddingOpensAt() < cut ? "open" : "published")
                    : "closed";
            boolean t = s.format() == Format.TIMED;
            saleS.add(s.id(), s.code(), s.format().code(), s.tier().code(), s.dept().id(), s.officeId(), s.titleRu(), s.titleEn(),
                    s.publishedAt() == 0 ? null : s.publishedAt(), s.biddingOpensAt() == 0 ? null : s.biddingOpensAt(),
                    s.previewFrom(), s.previewTo(), s.startsAt(), status.equals("closed") ? endAt : null,
                    t ? 5 : null, t ? 60 : null, t ? 120 : null, t ? 120 : null, t ? 3600 : null, s.premiumScheduleId(), 10,
                    status);
        }
        saleS.close();
        ctx.put(AucKeys.CALENDAR, cal);
        ctx.put(AucKeys.AUCTION, w);
        ctx.log("упрощённые торги: торгов " + cal.sales.size() + ", лотов " + ls.size() + ", регистраций " + rid
                + ", счетов " + w.invoices.size());
    }

    private static Integer nz(int v) {
        return v == 0 ? null : v;
    }
}
