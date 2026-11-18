package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Horizon;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.hammerhall.data.core.check.Aggregate.countEq;
import static io.hammerhall.data.core.check.Aggregate.max;
import static io.hammerhall.data.core.check.Aggregate.min;

/**
 * Модуль {@code money}: счета, строки, платежи, отчёты комитентам — по итогам торгов ({@code AUCTION}) и
 * тем же планам оплаты, что и в цикле торгов ({@link PaymentPlanner}); кладёт {@code MONEY} — события для уведомлений.
 * Правила — M1–M7, устройство — {@link MoneyBook}, самопроверка — {@link MoneyAudit}.
 */
public final class MoneyModule implements Module {
    public static final String NAME = "money";

    private MoneyAudit audit;
    /** Итоги для самопроверки; сама книга (строки денег) после прогона не держится — память {@code full}. */
    private MoneyBook.Stats stats;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return List.of("house", "people", "auction");
    }

    @Override
    public List<String> tables() {
        return MoneyTables.TABLES;
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        HouseRules house = ctx.get(AucKeys.HOUSE);
        ClientBase clients = ctx.has(AucKeys.CLIENTS) ? ctx.get(AucKeys.CLIENTS) : null;
        Calendar calendar = ctx.get(AucKeys.CALENDAR);
        AuctionWorld aw = ctx.get(AucKeys.AUCTION);
        PaymentPlanner planner = new PaymentPlanner(ctx.streams(), house, clients);
        long cut = Horizon.cut(ctx.period());
        Map<String, TableSink> sinks = new LinkedHashMap<>();
        for (String t : MoneyTables.TABLES) sinks.put(t, ctx.streamSink(t));
        audit = new MoneyAudit(house, ctx.calendar(), cut, ctx.profile().scale());
        MoneyBook book = new MoneyBook(house, calendar, aw, planner, ctx.streams(), ctx.calendar(), cut, ctx.profile().scale(),
                ctx.planted(), new MoneyTables.SinkOut(sinks), audit);
        book.run();
        ctx.put(AucKeys.MONEY, book.events);
        MoneyBook.Stats s = book.stats;
        stats = s;
        ctx.log(String.format(Locale.ROOT, "money: счетов %d (строк %d; переиздано %d, отменено за неуплату %d), платежей %d, "
                        + "депозитов %d (в зачёт %d, возврат %d, удержано %d), отчётов %d (строк %d), выплат %d, событий %d",
                s.invoices, s.lines, s.reissued, s.cancelledNonPayment, book.pays.size, s.deposits, s.depositApplied,
                s.depositRefund, s.depositForfeit, s.settlements, s.settlementLines, s.payouts, book.events.size()));
        ctx.log("money: заложено — старая шкала " + s.oldSchedule + ", выплат раньше оплаты " + s.payoutEarly
                + ", дублей платежа " + s.duplicates + (s.planMismatch > 0 ? "; планов не как в цикле торгов: " + s.planMismatch : ""));
    }

    @Override
    public void check(GenContext ctx, CheckReport r) {
        if (audit == null) {
            r.fail("money: самопроверка", "модуль не прогнан");
            return;
        }
        audit.report(r, stats);
    }

    @Override
    public List<Aggregate> aggregates() {
        List<Aggregate> a = new ArrayList<>();
        for (String k : List.of("sale", "after_sale", "second_chance", "shortfall", "reissue")) a.add(countEq("invoices", "kind", k));
        for (String k : List.of("issued", "part_paid", "paid", "overdue", "cancelled")) a.add(countEq("invoices", "status", k));
        a.add(countEq("invoices", "cancel_reason", "non_payment"));
        a.add(countEq("invoices", "cancel_reason", "house_error"));
        a.add(min("invoices", "issued_at"));
        a.add(max("invoices", "issued_at"));
        a.add(max("invoices", "number"));
        a.add(countEq("invoice_lines", "vat_rate", "20"));
        a.add(countEq("invoice_lines", "vat_rate", "22"));
        for (String k : List.of("1", "2", "3")) a.add(countEq("invoice_lines", "premium_schedule_id", k));
        a.add(max("invoice_lines", "line_no"));
        for (String k : PayRows.KIND) a.add(countEq("payments", "kind", k));
        for (String k : List.of("card", "sbp", "bank_transfer", "cash", "deposit")) a.add(countEq("payments", "method", k));
        a.add(countEq("payments", "status", "failed"));
        for (String k : PayRows.FAILURE) a.add(countEq("payments", "failure_reason", k));
        a.add(min("payments", "at"));
        a.add(max("payments", "at"));
        for (String k : List.of("awaiting_buyers", "part_paid", "paid", "owed_by_consignor")) {
            a.add(countEq("settlements", "status", k));
        }
        a.add(max("settlements", "created_at"));
        for (String k : MoneyBook.OUTCOME) a.add(countEq("settlement_lines", "outcome", k));
        a.add(max("settlement_lines", "line_no"));
        return a;
    }
}
