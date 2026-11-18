package io.hammerhall.data.auc.site;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.check.LongRuns;
import io.hammerhall.data.core.gen.Profile;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Самопроверка модуля сайта — по записанным строкам, а не по ходу генерации: каждое
 * событие и уведомление сверяется со ставками, поручениями, наблюдением, лотами и счетами мира заново. Визиты пишет
 * один поток, уведомления — другой: у каждой половины своё состояние.
 *
 * <ul>
 *   <li>события визита — внутри визита, по порядку, раньше среза; действия — только у вошедших;</li>
 *   <li>воронка сходится: {@code watch_add}/{@code watch_remove} ↔ строки {@code watchlist} (тот же клиент, лот и
 *       момент, у приложения — {@code via = app}); {@code bid_submit} ↔ ручная онлайн-ставка формой того же клиента в
 *       тот же момент; {@code order_set} ↔ максимум с сайта или приложения; у клиентов с согласием — ровно по
 *       одному событию на строку, без согласия — ни одного;</li>
 *   <li>{@code outbid} — после перебившей ставки тому, чья принятая ставка была перед ней (пересчёт по ставкам лота);
 *       {@code closing_soon} — за час до плана наблюдающему на этот момент; {@code lot_won} — покупателю;</li>
 *   <li>push не отправлен в тихие часы по поясу города клиента; статусы согласованы с моментами; повтор
 *       {@code event_key} и канала — только у заложенных ({@code notification_duplicate});</li>
 *   <li>коридоры: просмотров на лот ≈ 30, наблюдающих — около числа участников; ночные по местному ставки у
 *       востока страны — чаще, чем у запада.</li>
 * </ul>
 */
final class SiteAudit {
    private static final int MAX_EXAMPLES = 20;
    private final SiteInput in;
    private final Watches w;

    // ---------------------------------------------------------------- визиты (поток записи визитов)
    private final List<String> sessionErrors = new ArrayList<>();
    private long sessionErrorCount;
    private long curSession, curStart, curEnd, lastAt;
    private int curClient, lastSeq;
    private final byte[] bidSeen, orderSeen, addSeen, remSeen;
    private final long[] viewsPerLot;
    private long views;

    // ---------------------------------------------------------------- уведомления (поток записи уведомлений)
    private final List<String> noticeErrors = new ArrayList<>();
    private long noticeErrorCount;
    private final LongRuns keys;
    private final long[] plantedHashes;
    private int nPlanted;
    private long pushSent;
    /** Принятые ставки по лоту (индексы ставок) — для пересчёта «кто вёл». */
    private final int[] accFrom, accIdx;

    SiteAudit(SiteInput in, Watches w) {
        this.in = in;
        this.w = w;
        bidSeen = new byte[in.nBids];
        orderSeen = new byte[in.nOrders];
        addSeen = new byte[w.n];
        remSeen = new byte[w.n];
        viewsPerLot = new long[in.nLots + 1];
        // отрезок 8 МБ: в full ключей ~20 млн — отрезками на диск (папка -Ddata.tmpdir, как у проверок ядра), не в кучу
        keys = new LongRuns(1 << 20, io.hammerhall.data.core.check.Integrity.Limits.fromSystem().tmpDir());
        plantedHashes = new long[1 << 16];
        accFrom = new int[in.nLots + 2];
        int na = 0;
        for (int i = 0; i < in.nBids; i++) {
            if ((in.bFlags[i] & SiteInput.B_ACCEPTED) == 0) continue;
            accFrom[in.bLot[i] + 1]++;
            na++;
        }
        for (int l = 1; l < accFrom.length; l++) accFrom[l] += accFrom[l - 1];
        accIdx = new int[na];
        int[] fill = Arrays.copyOf(accFrom, accFrom.length);
        for (int i = 0; i < in.nBids; i++) if ((in.bFlags[i] & SiteInput.B_ACCEPTED) != 0) accIdx[fill[in.bLot[i]]++] = i;
    }

    private void sessionError(String what) {
        sessionErrorCount++;
        if (sessionErrors.size() < MAX_EXAMPLES) sessionErrors.add(what);
    }

    private void noticeError(String what) {
        noticeErrorCount++;
        if (noticeErrors.size() < MAX_EXAMPLES) noticeErrors.add(what);
    }

    // ================================================================ визиты

    void session(long id, int client, byte device, long start, long end) {
        curSession = id;
        curClient = client;
        curStart = start;
        curEnd = end;
        lastAt = start;
        lastSeq = 0;
        if (start >= in.cutoff || end >= in.cutoff) sessionError("визит " + id + " после среза");
        if (end < start) sessionError("визит " + id + ": конец раньше начала");
        if (client != 0 && (in.cFlags[client] & SiteInput.C_ANALYTICS) == 0) sessionError("визит " + id + ": клиент без согласия");
    }

    void event(long sid, int client, byte device, int seq, long at, byte kind, int sale, int lot, long bid, int order) {
        if (sid != curSession || seq != lastSeq + 1) sessionError("событие " + sid + "/" + seq + ": не по порядку");
        lastSeq = seq;
        if (at < lastAt || at < curStart || at > curEnd) sessionError("событие " + sid + "/" + seq + ": момент вне визита");
        lastAt = at;
        if (at >= in.cutoff) sessionError("событие " + sid + "/" + seq + " после среза");
        if (lot != 0) {
            if (in.lPub[lot] > at) sessionError("событие " + sid + "/" + seq + ": лот " + lot + " ещё не опубликован");
            if (sale != in.lSale[lot]) sessionError("событие " + sid + "/" + seq + ": торги не лота");
        }
        boolean action = kind == Rules.E_WATCH || kind == Rules.E_UNWATCH || kind == Rules.E_FORM || kind == Rules.E_BID
                || kind == Rules.E_ORDER;
        if (action && client == 0) sessionError("событие " + sid + "/" + seq + ": действие без входа");
        boolean app = device == SiteInput.DEV_APP;
        switch (kind) {
            case Rules.E_VIEW -> {
                if (lot == 0) sessionError("просмотр без лота: " + sid + "/" + seq);
                else viewsPerLot[lot]++;
                views++;
            }
            case Rules.E_PAGE -> {
                if (sale == 0) sessionError("страница каталога без торгов: " + sid + "/" + seq);
            }
            case Rules.E_BID -> {
                int i = in.bidIndex(bid);
                if (i < 0 || !in.formBid(i) || in.bClient[i] != client || in.bAt[i] != at || in.bLot[i] != lot) {
                    sessionError("bid_submit " + sid + "/" + seq + ": нет ручной онлайн-ставки " + bid + " клиента " + client
                            + " в этот момент");
                } else if (bidSeen[i] < 100) bidSeen[i]++;
            }
            case Rules.E_ORDER -> {
                int i = Arrays.binarySearch(in.oId, 0, in.nOrders, order);
                if (i < 0 || in.oClient[i] != client || in.oAt[i] != at || in.oLot[i] != lot
                        || (in.oVia[i] == SiteInput.VIA_APP) != app) {
                    sessionError("order_set " + sid + "/" + seq + ": нет поручения " + order + " клиента " + client);
                } else if (orderSeen[i] < 100) orderSeen[i]++;
            }
            case Rules.E_WATCH -> {
                int row = w.find(client, lot);
                if (row < 0 || w.added[row] != at || (w.via[row] == SiteInput.VIA_APP) != app) {
                    sessionError("watch_add " + sid + "/" + seq + ": нет строки наблюдения (" + client + ", " + lot + ") в " + at);
                } else if (addSeen[row] < 100) addSeen[row]++;
            }
            case Rules.E_UNWATCH -> {
                int row = w.find(client, lot);
                if (row < 0 || w.removed[row] != at) {
                    sessionError("watch_remove " + sid + "/" + seq + ": нет снятия наблюдения (" + client + ", " + lot + ")");
                } else if (remSeen[row] < 100) remSeen[row]++;
            }
            default -> {
            }
        }
    }

    // ================================================================ уведомления

    void notice(long id, int client, byte kind, byte channel, String eventKey, int sale, int lot, int invoice, long keyA,
                long keyB, long created, long sent, long delivered, short attempts, byte status, boolean dup) {
        long cutoff = in.cutoff;
        if (created >= cutoff || sent >= cutoff || delivered >= cutoff) noticeError("уведомление " + id + " после среза");
        if (sent != 0 && sent < created) noticeError("уведомление " + id + ": отправлено раньше постановки");
        if (delivered != 0 && (sent == 0 || delivered < sent)) noticeError("уведомление " + id + ": доставлено раньше отправки");
        switch (status) {
            case Rules.ST_DELIVERED -> {
                if (delivered == 0 || attempts < 1) noticeError("уведомление " + id + ": доставлено без момента");
            }
            case Rules.ST_FAILED -> {
                if (delivered != 0 || attempts != Rules.MAX_ATTEMPTS || sent == 0) noticeError("уведомление " + id + ": отказ не по попыткам");
            }
            default -> {
                if (delivered != 0) noticeError("уведомление " + id + ": доставлено, а статус " + SiteTables.NOTIFY_STATUS[status]);
            }
        }
        if (channel == Rules.CH_PUSH) {
            if ((in.cFlags[client] & SiteInput.C_PUSH) == 0) noticeError("push " + id + " клиенту без приложения");
            if (sent != 0) {
                pushSent++;
                int s = (int) Math.floorMod(sent + in.offsetOfCity(in.cCity[client]), 86_400L);
                if (s >= Rules.QUIET_FROM || s < Rules.QUIET_TO) noticeError("push " + id + " отправлен в тихие часы");
            }
        }
        if (kind == Rules.N_SUMMARY && status != Rules.ST_SUPPRESSED && (in.cFlags[client] & SiteInput.C_MARKETING) == 0) {
            noticeError("итоги торгов " + id + " отписавшемуся клиенту");
        }
        switch (kind) {
            case Rules.N_OUTBID -> checkOutbid(id, client, lot, keyB, created);
            case Rules.N_CLOSING -> {
                long t = in.lPlan[lot] - 3600;
                int row = w.find(client, lot);
                if (in.sFormat[in.lSale[lot]] != SiteInput.F_TIMED || created < t || created > t + 60 || row < 0
                        || w.added[row] > t || (w.removed[row] != 0 && w.removed[row] <= t)) {
                    noticeError("closing_soon " + id + ": не за час до плана наблюдающему");
                }
            }
            case Rules.N_WON -> {
                if (in.lBuyer[lot] != client || created < in.lClosed[lot]) noticeError("lot_won " + id + ": не покупателю");
            }
            default -> {
            }
        }
        long h = Rng.mix64(RandomStreams.fnv64(eventKey) ^ (channel + 1L) * 0x9E3779B97F4A7C15L);
        keys.add(h);
        if (dup) {
            if (nPlanted < plantedHashes.length) plantedHashes[nPlanted] = h;
            nPlanted++;
        }
    }

    private void checkOutbid(long id, int client, int lot, long bid, long created) {
        int i = in.bidIndex(bid);
        if (i < 0 || (in.bFlags[i] & SiteInput.B_ACCEPTED) == 0 || in.bLot[i] != lot || created < in.bAt[i]) {
            noticeError("outbid " + id + ": нет принятой ставки " + bid + " лота " + lot + " до уведомления");
            return;
        }
        int from = accFrom[lot], to = accFrom[lot + 1];
        int pos = Arrays.binarySearch(accIdx, from, to, i);
        if (pos <= from) {
            noticeError("outbid " + id + ": перед ставкой " + bid + " никто не вёл");
            return;
        }
        int prev = accIdx[pos - 1];
        if (in.bClient[prev] != client || in.bClient[i] == client) {
            noticeError("outbid " + id + ": адресат " + client + " не вёл перед ставкой " + bid);
        }
    }

    // ================================================================ итог

    void report(CheckReport r, Profile profile) {
        // воронка: каждая строка — ровно одно событие у клиентов с согласием
        long missBid = 0, extraBid = 0;
        List<String> ex = new ArrayList<>();
        for (int i = 0; i < in.nBids; i++) {
            boolean want = in.formBid(i) && (in.cFlags[in.bClient[i]] & SiteInput.C_ANALYTICS) != 0;
            int got = bidSeen[i];
            if (want && got != 1) {
                missBid++;
                if (ex.size() < 5) ex.add("ставка " + in.bidId(i) + ": bid_submit ×" + got);
            } else if (!want && got != 0) extraBid++;
        }
        r.expectNone("сайт: bid_submit ↔ ручные онлайн-ставки формой", missBid + extraBid, ex,
                "ставок формой у клиентов с согласием — по одному bid_submit");
        long missOrder = 0;
        ex = new ArrayList<>();
        for (int i = 0; i < in.nOrders; i++) {
            boolean want = (in.cFlags[in.oClient[i]] & SiteInput.C_ANALYTICS) != 0;
            if ((want ? 1 : 0) != orderSeen[i]) {
                missOrder++;
                if (ex.size() < 5) ex.add("поручение " + in.oId[i] + ": order_set ×" + orderSeen[i]);
            }
        }
        r.expectNone("сайт: order_set ↔ максимумы с сайта и приложения", missOrder, ex, "по одному на поручение клиента с согласием");
        long missWatch = 0;
        ex = new ArrayList<>();
        for (int i = 0; i < w.n; i++) {
            boolean want = (in.cFlags[w.client[i]] & SiteInput.C_ANALYTICS) != 0;
            int wa = want ? 1 : 0, wr = want && w.removed[i] != 0 ? 1 : 0;
            if (addSeen[i] != wa || remSeen[i] != wr) {
                missWatch++;
                if (ex.size() < 5) ex.add("наблюдение (" + w.client[i] + ", " + w.lot[i] + "): add ×" + addSeen[i] + ", remove ×" + remSeen[i]);
            }
        }
        r.expectNone("сайт: watch_add/watch_remove ↔ watchlist", missWatch, ex, "строк наблюдения " + w.n);
        r.expectNone("сайт: события визитов", sessionErrorCount, sessionErrors, "по порядку, внутри визита, раньше среза");
        r.expectNone("уведомления: моменты, статусы, тихие часы, адресаты", noticeErrorCount, noticeErrors,
                "push отправлено " + pushSent);
        // повторы event_key и канала — только заложенные
        long[] planted = Arrays.copyOf(plantedHashes, Math.min(nPlanted, plantedHashes.length));
        Arrays.sort(planted);
        long[] dups = {0, 0};
        keys.duplicates(h -> {
            dups[0]++;
            if (Arrays.binarySearch(planted, h) < 0) dups[1]++;
        });
        keys.close();
        r.expect("уведомления: повтор event_key и канала — только заложенные", dups[1] == 0 && dups[0] == nPlanted,
                "повторов " + dups[0] + ", заложено " + nPlanted + ", чужих " + dups[1]);
        corridors(r, profile);
    }

    /** Коридоры модуля: воронка и местное время. Ширина — ±10 % у средних, у {@code small} — вдвое шире. */
    private void corridors(CheckReport r, Profile profile) {
        double wide = profile == Profile.FULL ? 1 : 2;
        // просмотров на лот — у лотов, закрытых до среза (их жизнь в каталоге целиком в выпуске)
        long lots = 0, v = 0, part = 0, watch = 0;
        for (int l = 1; l <= in.nLots; l++) {
            if (in.lClosed[l] == 0 || in.lPub[l] == 0 || in.lPub[l] < in.firstDay * 86_400L + 7 * 86_400L) continue;
            lots++;
            v += viewsPerLot[l];
            part += in.partFrom[l + 1] - in.partFrom[l];
            watch += w.lotFrom[l + 1] - w.lotFrom[l];
        }
        double perLot = lots == 0 ? 0 : (double) v / lots;
        double lo = Rules.VIEWS_PER_LOT * (1 - 0.1 * wide), hi = Rules.VIEWS_PER_LOT * (1 + 0.1 * wide);
        r.expect("коридор «воронка»: просмотров на лот", lots == 0 || (perLot >= lo && perLot <= hi),
                String.format(Locale.ROOT, "%.1f на закрытый лот (%d лотов), коридор %.0f–%.0f", perLot, lots, lo, hi));
        double ratio = part == 0 ? 1 : (double) watch / part;
        double rlo = 1 - 0.2 * wide, rhi = 1 + 0.2 * wide;
        r.expect("коридор «воронка»: наблюдающих — около числа участников", part == 0 || (ratio >= rlo && ratio <= rhi),
                String.format(Locale.ROOT, "наблюдений / участников = %.2f, коридор %.2f–%.2f", ratio, rlo, rhi));
        // местное время: доля онлайн-ставок с 00:00 до 06:00 по местному — восток (UTC+7 и дальше) против запада (до UTC+4)
        long[] east = {0, 0}, west = {0, 0};
        for (int i = 0; i < in.nBids; i++) {
            if ((in.bFlags[i] & SiteInput.B_ONLINE) == 0) continue;
            int c = in.bClient[i];
            if (c == 0) continue;
            int off = in.offsetOfCity(in.cCity[c]);
            long[] g = off >= 7 * 3600 ? east : off <= 4 * 3600 ? west : null;
            if (g == null) continue;
            g[0]++;
            int s = (int) Math.floorMod(in.bAt[i] + off, 86_400L);
            if (s < 6 * 3600) g[1]++;
        }
        double se = east[0] == 0 ? 0 : (double) east[1] / east[0], sw = west[0] == 0 ? 0 : (double) west[1] / west[0];
        r.expect("коридор «местное время»: ночные ставки востока чаще запада", east[0] < 100 || se > sw,
                String.format(Locale.ROOT, "с 00:00 до 06:00 по местному: восток %.1f %% (%d), запад %.1f %% (%d)",
                        100 * se, east[0], 100 * sw, west[0]));
    }

    long views() {
        return views;
    }
}
