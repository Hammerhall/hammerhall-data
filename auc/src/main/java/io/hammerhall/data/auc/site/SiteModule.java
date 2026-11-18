package io.hammerhall.data.auc.site;

import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static io.hammerhall.data.core.check.Aggregate.countEq;
import static io.hammerhall.data.core.check.Aggregate.max;
import static io.hammerhall.data.core.check.Aggregate.min;
import static io.hammerhall.data.core.check.Aggregate.nulls;
import static io.hammerhall.data.core.check.Aggregate.sum;

/**
 * Модуль {@code site}: {@code watchlist}, {@code sessions}, {@code session_events},
 * {@code notifications} — ≈ 85 млн строк из ≈ 123 млн набора. Визиты строятся вокруг ставок и наблюдения клиентов с
 * согласием на аналитику, плюс визиты «посмотреть» (клиенты и анонимные посетители) — до ≈ 30 просмотров на лот;
 * уведомления — из событий мира (регистрации, ставки, закрытия, счета, платежи, выплаты).
 *
 * <p><b>Скорость</b> — цель {@code full} ≤ 3 мин на 2 ядрах: сутки — параллельно ({@code -Dsite.threads}, по
 * умолчанию — по числу ядер), события — в примитивных массивах суток ({@link DayOut}), запись — по порядку суток,
 * {@code id} визитов и уведомлений — сквозным счётчиком по суткам. Один и четыре потока дают один манифест.
 *
 * <p>Вход — {@link SiteInput}: переходник от записей мира ({@link WorldInput}).
 */
public final class SiteModule implements Module {
    public static final String NAME = "site";

    private final List<String> requires;
    private final Function<GenContext, SiteInput> input;
    private final int threads;
    private SiteAudit audit;
    private SiteGen gen;

    /**
     * @param requires модули-поставщики мира
     * @param input    вход модуля из мира
     * @param threads  потоков суток; 0 — {@code -Dsite.threads} или по числу ядер
     */
    public SiteModule(List<String> requires, Function<GenContext, SiteInput> input, int threads) {
        this.requires = List.copyOf(requires);
        this.input = input;
        this.threads = threads;
    }

    /**
     * В наборе: вход — из мира ({@link WorldInput}); {@code requires} — модули-поставщики, что есть в сборке
     * ({@code house}, {@code people}, {@code catalog}, {@code clients}, {@code auction}, {@code money}).
     */
    public static SiteModule ofWorld(List<String> requires) {
        return new SiteModule(requires, WorldInput::build, 0);
    }

    /** Срез фактов: min(«сейчас», конец периода + 1 сутки). */
    public static long cutoff(GenContext ctx) {
        return Math.min(ctx.period().nowEpoch(), ctx.period().endEpochExclusive());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return requires;
    }

    @Override
    public List<String> tables() {
        return SiteTables.TABLES;
    }

    int threads() {
        if (threads > 0) return threads;
        String p = System.getProperty("site.threads");
        if (p != null && !p.isBlank()) return Math.max(1, Integer.parseInt(p.trim()));
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        java.lang.management.OperatingSystemMXBean os0 = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long proc0 = os0 instanceof com.sun.management.OperatingSystemMXBean x ? x.getProcessCpuTime() : 0;
        long gc0 = 0;
        for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            gc0 += Math.max(0, b.getCollectionTime());
        }
        long t0 = System.nanoTime();
        SiteInput in = input.apply(ctx);
        long t1 = System.nanoTime();
        SiteGen g = new SiteGen(in, ctx.streams());
        audit = new SiteAudit(in, g.watches);
        long t2 = System.nanoTime();
        TableSink watch = ctx.streamSink("watchlist"), sessions = ctx.streamSink("sessions"),
                events = ctx.streamSink("session_events"), notes = ctx.streamSink("notifications");
        int n = threads();
        g.run(watch, sessions, events, notes, n, audit, ctx.planted());
        long t3 = System.nanoTime();
        gen = g;
        long rows = g.stWatch + g.stSessions + g.stEvents + g.stNotices;
        ctx.log(String.format(Locale.ROOT, "site: вход %.1f с, подготовка %.1f с, сутки и запись %.1f с (%d потоков) — "
                        + "%,d строк, %.0f тыс. строк/с", (t1 - t0) / 1e9, (t2 - t1) / 1e9, (t3 - t2) / 1e9, n, rows,
                rows / ((t3 - t2) / 1e9) / 1e3));
        ctx.log(String.format(Locale.ROOT, "site: лотов %,d, ставок %,d (формой %,d), поручений %,d, клиентов %,d; "
                        + "наблюдений %,d, визитов %,d (без входа %,d), событий %,d (%.1f на визит), уведомлений %,d "
                        + "(повторов %d)", in.nLots, in.nBids, in.manual.length, in.nOrders, in.nClients, g.stWatch,
                g.stSessions, g.stAnonSessions, g.stEvents, g.stSessions == 0 ? 0 : (double) g.stEvents / g.stSessions,
                g.stNotices, g.stDup));
        java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long proc = os instanceof com.sun.management.OperatingSystemMXBean x ? x.getProcessCpuTime() : 0;
        ctx.log(String.format(Locale.ROOT, "site: процессор — сутки %.1f с, запись визитов и событий %.1f с, уведомлений %.1f с, "
                        + "наблюдений %.1f с; процесс от начала модуля %.1f с (с сжатием gzip и проверками ядра)",
                g.cpuDays.get() / 1e9, g.cpuSessions / 1e9, g.cpuNotices / 1e9, g.cpuWatch / 1e9, (proc - proc0) / 1e9));
        // сжатие своих таблиц (потоки ядра gz-<таблица>) и сборка мусора — для оценки full
        java.lang.management.ThreadMXBean tm = java.lang.management.ManagementFactory.getThreadMXBean();
        long gz = 0;
        for (Thread th : Thread.getAllStackTraces().keySet()) {
            String nm = th.getName();
            for (String t : SiteTables.TABLES) if (nm.equals("gz-" + t + ".tsv.gz")) gz += Math.max(0, tm.getThreadCpuTime(th.threadId()));
        }
        long gc = 0;
        for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            gc += Math.max(0, b.getCollectionTime());
        }
        ctx.log(String.format(Locale.ROOT, "site: процессор сжатия своих таблиц (пока открыты) %.1f с; сборка мусора за прогон %.1f с",
                gz / 1e9, (gc - gc0) / 1e3));
        long[] m = g.stMix;
        ctx.log(String.format(Locale.ROOT, "site: визиты вокруг действий %,d (%.1f событий), «посмотреть» клиентов %,d (%.1f), "
                        + "анонимные %,d (%.1f)", m[0], m[1] / Math.max(1.0, m[0]), m[2], m[3] / Math.max(1.0, m[2]), m[4],
                m[5] / Math.max(1.0, m[4])));
        StringBuilder sb = new StringBuilder("site: события —");
        for (int k = 0; k < g.stKinds.length; k++) sb.append(' ').append(SiteTables.EVENT_KINDS[k]).append('=').append(g.stKinds[k]);
        sb.append(String.format(Locale.ROOT, "; просмотров на лот %.1f", in.nLots == 0 ? 0 : (double) g.stKinds[Rules.E_VIEW] / in.nLots));
        ctx.log(sb.toString());
        sb = new StringBuilder("site: уведомления —");
        for (int k = 0; k < g.stNoticeKinds.length; k++) sb.append(' ').append(SiteTables.NOTIFY_KINDS[k]).append('=').append(g.stNoticeKinds[k]);
        ctx.log(sb.toString());
    }

    @Override
    public void check(GenContext ctx, CheckReport r) {
        if (audit == null) {
            r.fail("site: самопроверка", "модуль не прогнан");
            return;
        }
        audit.report(r, ctx.profile());
    }

    @Override
    public List<Aggregate> aggregates() {
        return List.of(
                countEq("watchlist", "via", "app"), nulls("watchlist", "removed_at"), min("watchlist", "added_at"),
                max("watchlist", "added_at"),
                countEq("sessions", "device", "app"), countEq("sessions", "device", "mobile_web"),
                countEq("sessions", "source", "push"), countEq("sessions", "source", "search"), nulls("sessions", "client_id"),
                nulls("sessions", "city_id"), min("sessions", "started_at"), max("sessions", "ended_at"),
                countEq("session_events", "kind", "lot_view"), countEq("session_events", "kind", "search"),
                countEq("session_events", "kind", "catalogue_page"), countEq("session_events", "kind", "watch_add"),
                countEq("session_events", "kind", "watch_remove"), countEq("session_events", "kind", "bid_form"),
                countEq("session_events", "kind", "bid_submit"), countEq("session_events", "kind", "order_set"),
                sum("session_events", "results"), sum("session_events", "page"), max("session_events", "seq"),
                countEq("notifications", "kind", "outbid"), countEq("notifications", "kind", "closing_soon"),
                countEq("notifications", "kind", "lot_won"), countEq("notifications", "kind", "payment_reminder"),
                countEq("notifications", "channel", "push"), countEq("notifications", "status", "suppressed"),
                countEq("notifications", "status", "failed"), countEq("notifications", "status", "pending"),
                sum("notifications", "attempts"), nulls("notifications", "delivered_at"));
    }

    /** Итоги прогона (для тестов). */
    SiteGen gen() {
        return gen;
    }
}
