package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.bid.Behavior;
import io.hammerhall.data.auc.catalog.CatalogModule;
import io.hammerhall.data.auc.house.HouseModule;
import io.hammerhall.data.auc.people.PeopleModule;
import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.PaymentPlanner;
import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.hammerhall.data.core.check.Aggregate.countEq;
import static io.hammerhall.data.core.check.Aggregate.max;
import static io.hammerhall.data.core.check.Aggregate.min;
import static io.hammerhall.data.core.check.Aggregate.sum;

/**
 * Модуль {@code auction}: один модуль ядра на десять таблиц торгов — петли обратной связи (повтор непроданного, отказ
 * и блокировка, второй шанс, снятие лимита после оплаты, сдача купленного обратно) проходит один цикл неделя за неделей
 * ({@link Orchestrator}). Внутри — подготовка торгов ({@link Prep}: {@code sales}, {@code sale_sessions},
 * {@code consignments}, {@code consignment_items}, {@code registrations}, {@code bid_orders}) и сами торги (движок,
 * исход, {@code lots}, {@code bids}, {@code lot_events}, {@code item_status_log}). План оплаты счетов — от модуля денег
 * ({@link PaymentPlanner}). Кладёт в мир {@code AUCTION}
 * ({@code AuctionWorld}) и {@code CALENDAR} (если подготовка не положила сама).
 */
public final class AuctionModule implements Module {
    public static final String NAME = "auction";
    public static final List<String> PREP_TABLES = List.of("sales", "sale_sessions", "consignments", "consignment_items",
            "registrations", "bid_orders");
    public static final List<String> TABLES = List.of("lots", "bids", "lot_events", "item_status_log");
    /**
     * Доли поведения движка под коридоры: опечатки, повторы ≈ 0,5 % ручных онлайн-ставок, гонки (пара на
     * ≈ 0,2 % ставок), решения аукциониста; полшага ≈ 0,065 (3 % ставок выше 100 000 ₽), прыжки 0,04.
     */
    public static final Behavior BEHAVIOR = Behavior.standard().withDuplicateShare(0.01).withRaceShare(0.0007)
            .withLateRaceShare(0.20);

    private final Function<GenContext, Prep> prepFactory;
    private final Function<GenContext, PaymentPlanner> plannerFactory;
    /** Самопроверка торгов — после прогона; сам оркестр (строки торгов, журнал вещи, ключи лотов) не держится. */
    private io.hammerhall.data.auc.auction.audit.AuctionAudit audit;
    private io.hammerhall.data.auc.auction.audit.Corridors corridors;
    private Prep prep;

    /** Планировщик оплаты модуля денег: {@code money.PaymentPlanner(streams, house, clients)}. */
    public static PaymentPlanner moneyPlanner(GenContext ctx) {
        return new io.hammerhall.data.auc.money.PaymentPlanner(ctx.streams(), ctx.get(AucKeys.HOUSE), ctx.get(AucKeys.CLIENTS));
    }

    public AuctionModule(Function<GenContext, Prep> prep, Function<GenContext, PaymentPlanner> planner) {
        this.prepFactory = prep;
        this.plannerFactory = planner;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return List.of(HouseModule.NAME, PeopleModule.NAME, CatalogModule.NAME);
    }

    @Override
    public List<String> tables() {
        List<String> t = new ArrayList<>(PREP_TABLES);
        t.addAll(TABLES);
        return t;
    }

    /** Потоков на торги недели: {@code -Dauc.threads=N} (по умолчанию — ядра, не больше 4); результат от числа не зависит. */
    static int threads() {
        String p = System.getProperty("auc.threads");
        if (p != null && !p.isBlank()) return Math.max(1, Integer.parseInt(p.strip()));
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        prep = prepFactory.apply(ctx);
        PaymentPlanner planner = plannerFactory.apply(ctx);
        Orchestrator orchestrator = new Orchestrator(ctx, ctx.get(AucKeys.HOUSE), ctx.get(AucKeys.CATALOG),
                ctx.get(AucKeys.CLIENTS), prep, planner, BEHAVIOR, threads());
        orchestrator.run();
        if (!ctx.has(AucKeys.CALENDAR)) ctx.put(AucKeys.CALENDAR, prep.calendar());
        ctx.put(AucKeys.AUCTION, orchestrator.world);
        audit = orchestrator.audit;
        corridors = orchestrator.corridors;
    }

    @Override
    public void check(GenContext ctx, CheckReport report) {
        if (audit == null) {
            report.fail("auction: самопроверка", "модуль не прогнан");
            return;
        }
        prep.check(ctx, report);
        audit.report(report);
        corridors.report(report);
    }

    @Override
    public List<Aggregate> aggregates() {
        List<Aggregate> a = new ArrayList<>(List.of(
                countEq("lots", "result", "sold"), countEq("lots", "result", "bought_in"), countEq("lots", "result", "withdrawn"),
                countEq("lots", "result", "sold_after_sale"), countEq("lots", "result", "open"), sum("lots", "extensions"),
                sum("lots", "bid_count"), max("lots", "bid_count"), min("lots", "published_at"), max("lots", "closed_at"),
                countEq("bids", "accepted", "t"), countEq("bids", "kind", "manual"), countEq("bids", "kind", "max"),
                countEq("bids", "kind", "auto"), countEq("bids", "kind", "absentee"), countEq("bids", "kind", "vendor"),
                countEq("bids", "channel", "online"), countEq("bids", "channel", "room"), countEq("bids", "channel", "phone"),
                countEq("bids", "reject_reason", "duplicate"), countEq("bids", "reject_reason", "race_lost"),
                countEq("bids", "reject_reason", "after_close"), countEq("bids", "reject_reason", "tied"),
                countEq("bids", "extended_close", "t"), min("bids", "placed_at"), max("bids", "placed_at"),
                countEq("lot_events", "kind", "extended"), countEq("lot_events", "kind", "extension_capped"),
                countEq("lot_events", "kind", "reserve_met"), countEq("lot_events", "kind", "fair_warning"),
                max("lot_events", "seq"),
                countEq("item_status_log", "status", "buyer_default"), countEq("item_status_log", "status", "reoffer_planned"),
                countEq("item_status_log", "status", "returned"), countEq("item_status_log", "status", "released"),
                max("item_status_log", "seq")));
        if (prep != null) a.addAll(prep.aggregates());
        return a;
    }
}
