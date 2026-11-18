package io.hammerhall.data.auc.site;

import io.hammerhall.data.core.gen.Planted;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Сборка модуля сайта: общее (наблюдение, веса лотов, вероятности визитов, анонимные устройства) — один раз, затем
 * сутки — параллельно ({@link DayGen} на поток), запись — по порядку суток двумя писателями: визиты с событиями и
 * уведомления; наблюдение — третьим, сразу. {@code id} визитов и уведомлений — сквозной счётчик писателя по порядку
 * суток (префиксные суммы суток), поэтому от числа потоков не зависит ничего.
 */
final class SiteGen {
    private static final HexFormat HEX = HexFormat.of();

    final SiteInput in;
    final Rules rules;
    final Watches watches;
    final RandomStreams.Stream stAct, stBrowse, stAnon, stNotify;

    // общее для суток
    final int[] pubDay, endDay, saleEndDay;
    final double[] lotW;
    final float[][] p0;
    final long[] anonKey;
    final byte[] anonKind;
    final int[] anonCity;

    /** Время процессора по частям (нс): сутки (все потоки), запись визитов, запись уведомлений, наблюдения. */
    final java.util.concurrent.atomic.AtomicLong cpuDays = new java.util.concurrent.atomic.AtomicLong();
    long cpuSessions, cpuNotices, cpuWatch;

    private static long cpu() {
        return java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    // итоги
    long stSessions, stEvents, stNotices, stWatch, stAnonSessions, stDup;
    final long[] stKinds = new long[SiteTables.EVENT_KINDS.length];
    final long[] stNoticeKinds = new long[SiteTables.NOTIFY_KINDS.length];
    /** Визиты и события по видам (до среза не обрезано): вокруг действий, «посмотреть» клиента, анонимные. */
    final long[] stMix = new long[6];

    SiteGen(SiteInput in, RandomStreams streams) {
        this.in = in;
        this.rules = new Rules(streams.stream("site.via"));
        this.stAct = streams.stream("site.sessions");
        this.stBrowse = streams.stream("site.browse");
        this.stAnon = streams.stream("site.anon");
        this.stNotify = streams.stream("site.notify");
        this.watches = Watches.build(in, streams.stream("site.watch"), rules);

        int nl = in.nLots;
        pubDay = new int[nl + 1];
        endDay = new int[nl + 1];
        lotW = new double[nl + 1];
        saleEndDay = new int[in.nSales + 1];
        // интерес лота — относительно среднего по торгам (лоты с публикацией каталога): у каждых торгов в среднем 30
        // просмотров на лот, у интересных лотов — больше; добавленные позже среднего не меняют (стабильность)
        double[] popSum = new double[in.nSales + 1];
        int[] popN = new int[in.nSales + 1];
        for (int l = 1; l <= nl; l++) {
            int s = in.lSale[l];
            if (in.lPub[l] == 0 || in.lPub[l] > in.sPub[s]) continue;
            popSum[s] += 1 + in.lPop[l];
            popN[s]++;
        }
        for (int l = 1; l <= nl; l++) {
            if (in.lPub[l] == 0) {
                pubDay[l] = Integer.MAX_VALUE;
                endDay[l] = Integer.MIN_VALUE;
                continue;
            }
            pubDay[l] = (int) SiteInput.day(in.lPub[l]);
            endDay[l] = (int) Math.max(pubDay[l], SiteInput.day(in.lPlan[l]));
            int s = in.lSale[l];
            double norm = popN[s] > 0 ? popSum[s] / popN[s] : Rules.POP_NORM;
            lotW[l] = Rules.VIEWS_PER_LOT * (1 + in.lPop[l]) / norm / Rules.daySum(endDay[l] - pubDay[l]);
            saleEndDay[s] = Math.max(saleEndDay[s], endDay[l]);
        }
        p0 = new float[Rules.DAY_FACTORS.length][in.consenting.length];
        for (int f = 0; f < p0.length; f++) {
            for (int i = 0; i < in.consenting.length; i++) {
                p0[f][i] = (float) StrictMath.exp(-in.cVisits[in.consenting[i]] * Rules.DAY_FACTORS[f]);
            }
        }
        // анонимные устройства: постоянный пул на профиль
        int pool = (int) Math.max(100, Math.round(600_000 * in.scale));
        anonKey = new long[pool];
        anonKind = new byte[pool];
        anonCity = new int[pool];
        double[] cum = new double[in.cityIds.length];
        double acc = 0;
        for (int i = 0; i < cum.length; i++) cum[i] = acc += in.cityWeight[i];
        RandomStreams.Stream sd = streams.stream("site.devices");
        for (int a = 0; a < pool; a++) {
            Rng r = sd.at(a);
            anonKey[a] = r.nextLong();
            double u = r.nextDouble();
            anonKind[a] = u < 0.45 ? SiteInput.DEV_DESKTOP : u < 0.95 ? SiteInput.DEV_MOBILE : SiteInput.DEV_APP;
            if (cum.length > 0) {
                double x = r.nextDouble() * acc;
                int k = 0;
                while (k < cum.length - 1 && cum[k] <= x) k++;
                anonCity[a] = in.cityIds[k];
            }
        }
        sorted(in.oAt, in.nOrders, "поручения");
    }

    private static void sorted(long[] a, int n, String what) {
        for (int i = 1; i < n; i++) if (a[i] < a[i - 1]) throw new IllegalStateException(what + " не по времени");
    }

    // ================================================================ прогон

    /** Сгенерировать и записать четыре таблицы; {@code threads} — потоков суток. */
    void run(TableSink watch, TableSink sessions, TableSink events, TableSink notes, int threads, SiteAudit audit,
             Planted planted) throws IOException {
        long firstDay = in.firstDay, lastDay = SiteInput.day(in.cutoff - 1);
        int nd = (int) Math.max(0, lastDay - firstDay + 1);
        AtomicReference<Throwable> fail = new AtomicReference<>();
        Thread wt = Thread.ofPlatform().name("site-watch").daemon(true).start(() -> {
            try {
                long c0 = cpu();
                writeWatch(watch, audit);
                cpuWatch = cpu() - c0;
            } catch (Throwable t) {
                fail.compareAndSet(null, t);
            }
        });
        @SuppressWarnings({"unchecked", "rawtypes"})
        CompletableFuture<DayOut>[] fut = new CompletableFuture[nd];
        for (int d = 0; d < nd; d++) fut[d] = new CompletableFuture<>();
        int window = Math.max(4, threads * 3);
        Semaphore permits = new Semaphore(window);
        AtomicIntegerArray done = new AtomicIntegerArray(nd);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads),
                r -> Thread.ofPlatform().name("site-day").daemon(true).unstarted(r));
        ThreadLocal<DayGen> gens = ThreadLocal.withInitial(() -> new DayGen(this));
        Thread submitter = Thread.ofPlatform().name("site-submit").daemon(true).start(() -> {
            for (int d = 0; d < nd; d++) {
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    return;
                }
                final int dd = d;
                pool.execute(() -> {
                    try {
                        long c0 = cpu();
                        DayOut o = gens.get().run(firstDay + dd);
                        cpuDays.addAndGet(cpu() - c0);
                        fut[dd].complete(o);
                    } catch (Throwable t) {
                        fut[dd].completeExceptionally(t);
                    }
                });
            }
        });
        Thread nt = Thread.ofPlatform().name("site-notify").daemon(true).start(() -> {
            try {
                long id = 1;
                for (int d = 0; d < nd; d++) {
                    DayOut o = fut[d].join();
                    long c0 = cpu();
                    id = writeNotices(o, notes, id, audit, planted);
                    cpuNotices += cpu() - c0;
                    if (done.incrementAndGet(d) == 2) {
                        fut[d] = null;
                        permits.release();
                    }
                }
            } catch (Throwable t) {
                fail.compareAndSet(null, t);
            }
        });
        try {
            long id = 1;
            for (int d = 0; d < nd; d++) {
                if (fail.get() != null) break;
                DayOut o = fut[d].join();
                long c0 = cpu();
                id = writeSessions(o, sessions, events, id, audit);
                cpuSessions += cpu() - c0;
                if (done.incrementAndGet(d) == 2) {
                    fut[d] = null;
                    permits.release();
                }
            }
            nt.join();
            wt.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано", e);
        } catch (java.util.concurrent.CompletionException e) {
            fail.compareAndSet(null, e.getCause());
        } finally {
            submitter.interrupt();
            pool.shutdownNow();
        }
        Throwable t = fail.get();
        if (t instanceof IOException io) throw io;
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error er) throw er;
        if (t != null) throw new IllegalStateException(t);
    }

    // ================================================================ запись

    private void writeWatch(TableSink sink, SiteAudit audit) throws IOException {
        Watches w = watches;
        for (int j = 0; j < w.n; j++) {
            int i = w.order[j];
            sink.add(w.client[i], w.lot[i], w.added[i], w.removed[i] == 0 ? null : w.removed[i], SiteTables.VIA[w.via[i]]);
        }
        stWatch = w.n;
        sink.close();
    }

    private long writeSessions(DayOut o, TableSink sessions, TableSink events, long id, SiteAudit audit) throws IOException {
        long cutoff = in.cutoff;
        for (int k = 0; k < stMix.length; k++) stMix[k] += o.mix[k];
        for (int k = 0; k < o.ns; k++) {
            int s = o.sOrder[k];
            long start = o.sStart[s];
            if (start >= cutoff || o.sEvN[s] == 0) continue;
            int from = o.sEvFrom[s], n = o.sEvN[s];
            int kept = 0;
            while (kept < n && o.eAt[from + kept] < cutoff) kept++;
            if (kept == 0) continue;
            long end = o.sEnd[s];
            if (kept < n || end >= cutoff) end = o.eAt[from + kept - 1];
            int client = o.sClient[s], city = o.sCity[s];
            sessions.add(id, client == 0 ? null : client, HEX.toHexDigits(o.sDevKey[s]), SiteTables.DEVICES[o.sDevice[s]],
                    SiteTables.SOURCES[o.sSource[s]], city == 0 ? null : city, start, end);
            audit.session(id, client, o.sDevice[s], start, end);
            for (int e = 0; e < kept; e++) {
                int i = from + e;
                byte kind = o.eKind[i];
                int sale = o.eSale[i], lot = o.eLot[i], res = o.eResults[i], page = o.ePage[i], ord = o.eOrder[i];
                long bid = o.eBid[i];
                events.add(id, e + 1, o.eAt[i], SiteTables.EVENT_KINDS[kind], sale == 0 ? null : sale, lot == 0 ? null : lot,
                        o.eQuery[i], res < 0 ? null : res, page == 0 ? null : page, bid == 0 ? null : bid,
                        ord == 0 ? null : ord);
                audit.event(id, client, o.sDevice[s], e + 1, o.eAt[i], kind, sale, lot, bid, ord);
                stKinds[kind]++;
            }
            stSessions++;
            if (client == 0) stAnonSessions++;
            stEvents += kept;
            id++;
        }
        return id;
    }

    private long writeNotices(DayOut o, TableSink sink, long id, SiteAudit audit, Planted planted) throws IOException {
        StringBuilder key = new StringBuilder(48);
        for (int k = 0; k < o.nn; k++) {
            int i = o.nOrder[k];
            byte kind = o.nKind[i];
            key.setLength(0);
            key.append(SiteTables.NOTIFY_KINDS[kind]).append(':').append(o.nKeyA[i]);
            if (o.nKeyB[i] >= 0) key.append(':').append(o.nKeyB[i]);
            String ek = key.toString();
            int sale = o.nSale[i], lot = o.nLot[i], inv = o.nInvoice[i];
            long sent = o.nSent[i], dl = o.nDelivered[i];
            sink.add(id, o.nClient[i], SiteTables.NOTIFY_KINDS[kind], SiteTables.CHANNELS[o.nChannel[i]], ek,
                    sale == 0 ? null : sale, lot == 0 ? null : lot, inv == 0 ? null : inv, o.nCreated[i],
                    sent == 0 ? null : sent, dl == 0 ? null : dl, (int) o.nAttempts[i], SiteTables.NOTIFY_STATUS[o.nStatus[i]]);
            if (o.nDup[i]) {
                planted.add("notification_duplicate", id);
                stDup++;
            }
            audit.notice(id, o.nClient[i], kind, o.nChannel[i], ek, sale, lot, inv, o.nKeyA[i], o.nKeyB[i], o.nCreated[i],
                    sent, dl, o.nAttempts[i], o.nStatus[i], o.nDup[i]);
            stNoticeKinds[kind]++;
            stNotices++;
            id++;
        }
        return id;
    }
}
