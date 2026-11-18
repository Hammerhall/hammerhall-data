package io.hammerhall.data.auc.auction.intake;

import io.hammerhall.data.auc.auction.calendar.AuctionCalendar;
import io.hammerhall.data.auc.auction.calendar.CalendarView;
import io.hammerhall.data.auc.auction.calendar.Depts;
import io.hammerhall.data.auc.auction.calendar.SaleSpec;
import io.hammerhall.data.auc.auction.calendar.Sessions;
import io.hammerhall.data.auc.auction.calendar.Wave;
import io.hammerhall.data.auc.auction.demand.RegDraft;
import io.hammerhall.data.auc.bid.Bidder;
import io.hammerhall.data.auc.bid.Channel;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.Participants;
import io.hammerhall.data.auc.world.RegMode;
import io.hammerhall.data.auc.world.SalePlan;
import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.core.time.Period;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.hammerhall.data.core.check.Aggregate.countEq;
import static io.hammerhall.data.core.check.Aggregate.max;
import static io.hammerhall.data.core.check.Aggregate.min;
import static io.hammerhall.data.core.check.Aggregate.sum;

/**
 * Самопроверка подготовки торгов: календарь (60 живых и ≈ 420 онлайн в год, сезоны,
 * главные недели, паузы), волна (≤ 1 200 за вечер) и сессии (≤ 250 лотов), каталог (резерв ≤ нижней, кроме заложенных;
 * живые — от 5 000 ₽), регистрации (таблички, сроки, лимит T11, допуск T13), заявки до торгов (заочные — не позже чем за
 * 2 ч, телефон — от 30 000 ₽) и коридоры календаря, сезонности, участников и регистраций. Проверяет модель в
 * памяти, независимо от движка; правила пересчитывает сама.
 */
final class PrepAudit {
    private PrepAudit() {
    }

    /** Нарушения, найденные при спросе (лимит, допуск, сроки заявок) — копит {@link AuctionPrep#demand}. */
    static final class Found {
        final Map<String, Long> count = new TreeMap<>();
        final Map<String, List<String>> examples = new TreeMap<>();

        synchronized void add(String rule, String example) {
            count.merge(rule, 1L, Long::sum);
            List<String> l = examples.computeIfAbsent(rule, k -> new ArrayList<>());
            if (l.size() < 5) l.add(example);
        }
    }

    static final String[] RULES = {"T11 лимит: сумма максимумов", "T13 допуск к дорогим лотам",
            "заочные — не позже чем за 2 ч", "телефон — лоты от 30 000 ₽", "регистрация до первой ставки",
            "таблички: диапазон способа"};

    /** Проверки спроса одних торгов (в момент спроса). */
    static void demand(AuctionPrep prep, SalePlan plan, List<Participants> lots, List<RegDraft> regs, Found f) {
        SaleSpec spec = prep.view().spec(plan.sale().id());
        Map<Long, RegDraft> byKey = new HashMap<>();
        Set<Integer> paddles = new HashSet<>();
        for (RegDraft g : regs) {
            byKey.put(g.key(), g);
            int p = g.paddle();
            boolean ok = !spec.live() ? p >= 10_000 && p <= 99_999
                    : switch (g.mode()) {
                case ROOM -> p >= 1 && p <= 1_999;
                case ONLINE -> p >= 10_000 && p <= 99_999;
                default -> p >= 2_000 && p <= 2_999;
            };
            if (!ok || !paddles.add(p)) f.add(RULES[5], plan.sale().code() + " " + g.key() + " " + g.mode() + " " + p);
            if (g.registeredAt() < spec.biddingOpensAt()) f.add(RULES[4], plan.sale().code() + " " + g.key() + " до открытия");
        }
        Map<Long, Long> sum = new HashMap<>();
        long[] sessionStart = new long[plan.sessions().size() + 1];
        for (SalePlan.Session s : plan.sessions()) sessionStart[s.no()] = s.startsAt();
        for (int i = 0; i < lots.size(); i++) {
            SalePlan.Lot lot = plan.lots().get(i);
            for (Participants.Participant p : lots.get(i).bidders()) {
                Bidder b = p.bidder();
                RegDraft g = byKey.get(p.regKey());
                if (g == null || !g.approved()) {
                    f.add(RULES[4], plan.sale().code() + " лот " + lot.lotNo() + ": участник без одобренной регистрации");
                    continue;
                }
                sum.merge(p.regKey(), b.max(), Long::sum);
                long lotStart = spec.live() ? sessionStart[lot.sessionNo()] : lot.opensAt();
                if (lot.lowKop() >= HouseRules.PREMIUM_LOT_FROM) {
                    boolean ok = g.premiumAt() > 0 && (spec.live() ? g.premiumAt() < lotStart : b.placedAt() >= g.premiumAt() * 1000);
                    if (!ok) f.add(RULES[1], plan.sale().code() + " лот " + lot.lotNo() + " рег. " + g.key());
                }
                if (b.channel() == Channel.ABSENTEE || b.book()) {
                    if (b.placedAt() > (spec.startsAt() - HouseRules.ABSENTEE_CUTOFF_S) * 1000) {
                        f.add(RULES[2], plan.sale().code() + " лот " + lot.lotNo() + " " + b.placedAt());
                    }
                }
                if (b.channel() == Channel.PHONE && lot.lowKop() < HouseRules.PHONE_MIN_LOW) {
                    f.add(RULES[3], plan.sale().code() + " лот " + lot.lotNo());
                }
                if (!spec.live() && b.placedAt() < Math.max(g.decidedAt(), g.registeredAt()) * 1000) {
                    f.add(RULES[4], plan.sale().code() + " лот " + lot.lotNo() + ": ставит до регистрации или решения");
                }
                if (spec.live() && g.decidedAt() >= lotStart) {
                    f.add(RULES[4], plan.sale().code() + " лот " + lot.lotNo() + ": регистрация решена после начала сессии");
                }
                if (b.book() && b.placedAt() < g.registeredAt() * 1000) {
                    f.add(RULES[4], plan.sale().code() + " лот " + lot.lotNo() + ": заявка до регистрации");
                }
            }
        }
        for (int i = 0; i < lots.size(); i++) {
            for (Participants.Rejected x : lots.get(i).rejected()) {
                RegDraft g = byKey.get(x.regKey());
                if (g == null || x.atMs() < g.registeredAt() * 1000) {
                    f.add(RULES[4], plan.sale().code() + " лот " + (i + 1) + ": попытка " + x.reason() + " до регистрации");
                }
            }
        }
        for (Map.Entry<Long, Long> e : sum.entrySet()) {
            RegDraft g = byKey.get(e.getKey());
            if (g.limit() > 0 && e.getValue() > g.limit() && !prep.planted().has("bid_over_limit", g.key())) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lots.size(); i++) {
                    for (Participants.Participant p : lots.get(i).bidders()) {
                        if (p.regKey() == g.key()) sb.append(" лот ").append(i + 1).append(':').append(p.bidder().channel().code())
                                .append('/').append(p.bidder().strategy().code()).append('=').append(p.bidder().max());
                    }
                }
                f.add(RULES[0], plan.sale().code() + " рег. " + g.key() + ": " + e.getValue() + " > " + g.limit() + sb);
            }
        }
    }

    static void check(AuctionPrep prep, GenContext ctx, CheckReport r) {
        boolean small = ctx.profile() != Profile.FULL;
        double tol = small ? 2 : 1;
        Found f = prep.found();
        for (String rule : RULES) {
            long n = f.count.getOrDefault(rule, 0L);
            r.expectNone("подготовка торгов: " + rule, n, f.examples.getOrDefault(rule, List.of()), "нарушений нет");
        }
        calendar(prep, r);
        catalog(prep, ctx, r);
        registrations(prep, ctx, r, tol);
    }

    /** Календарь: полные годы периода — 60 живых, 395–425 онлайн, сезоны 27 / 27 / 6, главные недели, паузы. */
    private static void calendar(AuctionPrep prep, CheckReport r) {
        AuctionCalendar cal = prep.auctionCalendar();
        Period p = prep.period();
        List<String> bad = new ArrayList<>();
        for (int y = p.from().getYear(); y <= p.to().getYear(); y++) {
            int live = 0, online = 0;
            int[] season = new int[3];
            Set<String> majors = new HashSet<>();
            for (SaleSpec s : cal.year(y)) {
                LocalDate d = Period.localDate(s.startsAt(), HouseRules.ZONE);
                if (!s.live()) {
                    online++;
                    if (d.getMonthValue() == 1 && d.getDayOfMonth() <= 10) bad.add(s.code() + ": закрытие 1–10 января");
                    continue;
                }
                live++;
                int md = d.getMonthValue() * 100 + d.getDayOfMonth();
                season[md >= 215 && md <= 605 ? 0 : md >= 915 && md <= 1215 ? 1 : 2]++;
                for (LocalDate x : s.saleDays()) {
                    int m = x.getMonthValue(), dd = x.getDayOfMonth();
                    if (m == 1 && dd <= 10 || m == 5 && dd <= 10 || m == 7 && dd >= 15 || m == 8) bad.add(s.code() + ": пауза " + x);
                    if (x.getDayOfWeek().getValue() == 7 || x.getDayOfWeek().getValue() == 1) bad.add(s.code() + ": " + x.getDayOfWeek());
                }
                if (!s.tier().equals("regular")) majors.add(s.tier() + " " + Depts.CODE[s.dept()] + " " + d.getMonthValue());
            }
            if (live != 60) bad.add(y + ": живых торгов " + live);
            if (online < 395 || online > 425) bad.add(y + ": онлайн-торгов " + online);
            if (season[0] != 27 || season[1] != 27 || season[2] != 6) bad.add(y + ": сезоны " + season[0] + "/" + season[1] + "/" + season[2]);
            if (majors.size() != 8) bad.add(y + ": главных и вечерних " + majors.size());
        }
        r.expectNone("календарь: 60 живых и ≈ 420 онлайн в год, сезоны, главные недели, паузы", bad.size(),
                bad.subList(0, Math.min(5, bad.size())), "годы " + p.from().getYear() + "–" + p.to().getYear());
    }

    /**
     * Каталоги: волна, сессии, резерв, нижняя оценка живых, стартовая. Торги, записанные целиком, проверены при записи
     * ({@link AuctionPrep#saleWritten}); остальные — здесь; итог — по порядку {@code sales.id}.
     */
    private static void catalog(AuctionPrep prep, GenContext ctx, CheckReport r) {
        List<String> bad = new ArrayList<>();
        long lots = 0, planted = 0, liveLots = 0;
        Map<Integer, CatalogBuilder.Built> built = prep.built();
        Map<Integer, AuctionPrep.Written> written = prep.written();
        List<Integer> ids = new ArrayList<>(built.keySet());
        ids.addAll(written.keySet());
        ids.sort(null);
        for (int id : ids) {
            AuctionPrep.Written w = written.get(id);
            long[] n;
            if (w != null) {
                n = new long[]{w.lots(), w.planted()};
                bad.addAll(w.bad());
            } else {
                n = catalogSale(prep, id, built.get(id).plan(), bad);
            }
            lots += n[0];
            planted += n[1];
            if (prep.view().spec(id).live()) liveLots += n[0];
        }
        r.expectNone("каталог: волна (5 лотов в 60 с, ≤ 1 200 за вечер), сессии (≤ 250), резерв ≤ нижней, живые от 5 000 ₽, "
                        + "лотов не больше плана (живые; онлайн — 2 × план с залежавшимися), у обычных живых от 1 млн ₽ — ≤ 10 %",
                bad.size(), bad.subList(0, Math.min(5, bad.size())), "лотов " + lots + ", заложенный резерв выше нижней — " + planted);
        // доля живых лотов (≈ 2,3 % — 31 тыс. из 1,34 млн); проверяется в full (в малых профилях — пол каталога 10)
        double share = lots == 0 ? 0 : 100.0 * liveLots / lots;
        r.expect("каталог: доля живых лотов ≈ 2,3 %", ctx.profile() != Profile.FULL || Math.abs(share - LIVE_SHARE) <= LIVE_SHARE / 2,
                String.format(Locale.ROOT, "%.2f %% (%d из %d; цель %.1f ± %.2f %% в full)", share, liveLots, lots, LIVE_SHARE,
                        LIVE_SHARE / 2));
    }

    /** Доля живых лотов среди всех, %. */
    static final double LIVE_SHARE = 2.3;

    /** Проверка каталога одних торгов: нарушения — в {@code bad}; итог — {лотов, заложенный резерв выше нижней}. */
    static long[] catalogSale(AuctionPrep prep, int id, SalePlan plan, List<String> bad) {
        long lots = 0, planted = 0;
        {
            SaleSpec spec = prep.view().spec(id);
            int target = spec.targetLots(prep.scale()), size = plan.lots().size();
            if (spec.live() ? size > target : size > 2 * target) {
                bad.add(plan.sale().code() + ": лотов " + size + " при плане " + target + (spec.live() ? "" : " (онлайн — до 2 × плана)"));
            }
            if (spec.live() && spec.tier().equals("regular")) {
                int top = 0;
                for (SalePlan.Lot l : plan.lots()) if (l.lowKop() >= CatalogBuilder.MAJOR_LOW) top++;
                if (top > Math.floor(CatalogBuilder.REGULAR_TOP_SHARE * size)) {
                    bad.add(plan.sale().code() + ": обычные живые — лотов от 1 млн ₽ " + top + " из " + size);
                }
            }
            Map<Long, Integer> perEvening = new TreeMap<>();
            Map<Long, Integer> perGroup = new TreeMap<>();
            for (SalePlan.Lot l : plan.lots()) {
                lots++;
                if (l.reserveKop() > l.lowKop()) {
                    if (prep.planted().has("reserve_above_low", prep.lotId(l.key()))) planted++;
                    else bad.add(plan.sale().code() + " лот " + l.label() + ": резерв выше нижней");
                }
                if (l.startKop() > l.lowKop() || l.reserveKop() > 0 && l.startKop() > l.reserveKop()) {
                    bad.add(plan.sale().code() + " лот " + l.label() + ": стартовая выше нижней или резерва");
                }
                if (l.lowKop() >= l.highKop()) bad.add(plan.sale().code() + " лот " + l.label() + ": эстимейт");
                if (spec.live()) {
                    if (l.lowKop() < HouseRules.LIVE_MIN_LOW) bad.add(plan.sale().code() + " лот " + l.label() + ": живые от 5 000 ₽");
                } else {
                    long c = l.closesAtPlanned();
                    perEvening.merge(Math.floorDiv(c + 3 * 3600, 86_400), 1, Integer::sum);
                    perGroup.merge(c, 1, Integer::sum);
                    if (c < spec.startsAt() || Math.floorMod(c - spec.startsAt(), 60) != 0) bad.add(plan.sale().code() + ": волна " + c);
                }
            }
            for (int n : perEvening.values()) if (n > Wave.MAX_PER_EVENING) bad.add(plan.sale().code() + ": за вечер " + n);
            for (int n : perGroup.values()) if (n > Wave.LOTS_PER_GROUP) bad.add(plan.sale().code() + ": в группе " + n);
            int next = 1;
            for (SalePlan.Session s : plan.sessions()) {
                int n = s.lastLotNo() - s.firstLotNo() + 1;
                if (n > Sessions.MAX_LOTS || s.firstLotNo() != next) bad.add(plan.sale().code() + " сессия " + s.no() + ": " + n);
                next = s.lastLotNo() + 1;
            }
            if (spec.live() && !plan.lots().isEmpty() && next != plan.lots().size() + 1) bad.add(plan.sale().code() + ": лоты вне сессий");
        }
        return new long[]{lots, planted};
    }

    /**
     * Итоги регистраций для коридоров: всего (поданных до среза), в последние 72 ч, в день торгов, без лотов,
     * отклонённых; торгов на участника в год — по возрастанию.
     */
    record RegStats(long n, long last72, long sameDay, long withoutLots, long rejected, List<Integer> perClientYear) {
    }

    /** Итоги регистраций по книге регистраций (пока она есть). */
    static RegStats regStats(AuctionPrep prep) {
        CalendarView view = prep.view();
        long cut = prep.cut();
        Map<Long, Integer> perClientYear = new HashMap<>();
        long[] c = new long[5];
        for (Calendar.Sale s : view.sales()) {
            if (!view.published(s.id())) continue;
            SaleSpec spec = view.spec(s.id());
            long start = spec.startsAt();
            prep.regs().visit(s.id(), (key, clientId, registeredAt, approved, withLots) -> {
                if (registeredAt >= cut) return;
                c[0]++;
                if (start - registeredAt <= 72 * 3600) c[1]++;
                if (Math.floorDiv(start + 3 * 3600, 86_400) == Math.floorDiv(registeredAt + 3 * 3600, 86_400)) c[2]++;
                if (!approved) c[4]++;
                if (!withLots) c[3]++;
                else {
                    int y = Period.localDate(start, HouseRules.ZONE).getYear();
                    perClientYear.merge((long) clientId << 16 | y, 1, Integer::sum);
                }
            });
        }
        List<Integer> per = new ArrayList<>(perClientYear.values());
        per.sort(null);
        return new RegStats(c[0], c[1], c[2], c[3], c[4], per);
    }

    /** Коридоры регистраций и участников: 70 % — в последние 72 ч, 10 % — в день торгов; торгов на участника в год. */
    private static void registrations(AuctionPrep prep, GenContext ctx, CheckReport r, double tol) {
        RegStats st = prep.regStats();
        long n = st.n(), last72 = st.last72(), sameDay = st.sameDay(), withoutLots = st.withoutLots(), rejected = st.rejected();
        if (n == 0) {
            r.fail("регистрации: коридоры", "регистраций нет");
            return;
        }
        double p72 = (double) last72 / n, pDay = (double) sameDay / n, pRej = (double) rejected / n, pNo = (double) withoutLots / n;
        corridor(r, "регистрации: в последние 72 ч до начала", p72, 0.70, 0.02 * tol + 0.02);
        corridor(r, "регистрации: в день торгов", pDay, 0.10, 0.02 * tol + 0.02);
        corridor(r, "регистрации: отклонены", pRej, 0.02, 0.01 * tol + 0.01);
        corridor(r, "регистрации без ставок", pNo, 0.25, 0.04 * tol + 0.04);
        List<Integer> per = st.perClientYear();
        double mean = per.stream().mapToInt(Integer::intValue).average().orElse(0);
        int median = per.get(per.size() / 2), p90 = per.get((int) (per.size() * 0.9)), p99 = per.get((int) (per.size() * 0.99));
        ctx.log(String.format(Locale.ROOT, "регистрации: %d, в последние 72 ч %.1f %%, в день торгов %.1f %%, отклонены %.1f %%, "
                        + "без лотов %.1f %%; торгов на участника в год: медиана %d, среднее %.2f, p90 %d, p99 %d",
                n, 100 * p72, 100 * pDay, 100 * pRej, 100 * pNo, median, mean, p90, p99));
        r.expect("участники: торгов на участника в год — медиана 2, среднее ≈ 5 (коридор ±40 %)",
                mean >= 5 * (1 - 0.2 * tol) && mean <= 5 * (1 + 0.2 * tol) && median >= 1 && median <= 3,
                String.format(Locale.ROOT, "медиана %d, среднее %.2f, p90 %d, p99 %d", median, mean, p90, p99));
    }

    static void corridor(CheckReport r, String name, double v, double target, double tol) {
        r.expect(name, Math.abs(v - target) <= tol, String.format(Locale.ROOT, "%.1f %% (цель %.0f ± %.0f %%)", 100 * v,
                100 * target, 100 * tol));
    }

    static List<Aggregate> aggregates() {
        return List.of(countEq("sales", "format", "live"), countEq("sales", "status", "closed"),
                countEq("sales", "tier", "major"), countEq("sales", "tier", "evening"), min("sales", "starts_at"),
                max("sales", "starts_at"), sum("sale_sessions", "last_lot_no"),
                countEq("consignments", "kind", "resale"), countEq("consignments", "unsold_policy", "reoffer"),
                countEq("consignment_items", "outcome", "sold"), countEq("consignment_items", "outcome", "returned"),
                countEq("registrations", "status", "rejected"), countEq("registrations", "mode", "absentee"),
                countEq("registrations", "via", "partner_platform"), sum("registrations", "paddle_no"),
                countEq("bid_orders", "kind", "absentee"), countEq("bid_orders", "kind", "phone"),
                countEq("bid_orders", "status", "won"), countEq("bid_orders", "revision_reason", "raise_leading"));
    }
}
