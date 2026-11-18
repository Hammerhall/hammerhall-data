package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.auction.engine.Codes;
import io.hammerhall.data.auc.auction.engine.LotRows;
import io.hammerhall.data.auc.auction.outcome.ClientLedger;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.LotResult;
import io.hammerhall.data.auc.world.LotResultCode;
import io.hammerhall.data.auc.world.Registrations;
import io.hammerhall.data.auc.world.SalePlan;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Итоги модуля {@code auction} для мира ({@code AUCTION}): лоты на срез, ставки компактно, счета с планами
 * оплаты, события-источники уведомлений, исход клиентов. Всё — в столбцах примитивов кусками ({@link Cols}: моменты в
 * секундах — {@code int}, в мс — {@code int} + {@code short}); записи ({@link LotResult}, {@link AuctionWorld.Notice})
 * создаются при чтении. Только факты до среза и состояние на срез.
 */
final class WorldData implements AuctionWorld {
    static final byte N_OUTBID = 0, N_LOT_WON = 1, N_REGISTRATION = 2, N_SUMMARY = 3;
    static final String[] NOTICE_KINDS = {"outbid", "lot_won", "registration_decided", "sale_summary"};
    private static final Dept[] DEPTS = Dept.values();
    private static final Format[] FORMATS = Format.values();

    private final long cut;
    private final io.hammerhall.data.auc.world.ClientBase clients;
    private Prep prep;
    private ClientLedger ledger;
    private List<InvoicePlan> invoices = List.of();

    // ---------------------------------------------------------------- лоты по id (с 1; строка 0 — пустая)
    private int lots;
    private final Cols.Longs lKey = new Cols.Longs(), lBuyerReg = new Cols.Longs(), lUnderReg = new Cols.Longs();
    private final Cols.Kops lPrice = new Cols.Kops(), lLow = new Cols.Kops(), lHigh = new Cols.Kops(),
            lReserve = new Cols.Kops(), lUnderAmt = new Cols.Kops(), lScPrice = new Cols.Kops();
    private final Cols.Secs lClosed = new Cols.Secs(), lDefaultAt = new Cols.Secs(), lPaidAt = new Cols.Secs(),
            lFreeAt = new Cols.Secs(), lWithdrawnAt = new Cols.Secs();
    private final Cols.Ints lSale = new Cols.Ints(), lItem = new Cols.Ints(), lCons = new Cols.Ints(), lBuyer = new Cols.Ints(),
            lScClient = new Cols.Ints();
    /** План лота — только для генераторов (отпускается, {@link #generatorsDone}). */
    private Cols.Secs lPublished = new Cols.Secs(), lPlanned = new Cols.Secs();
    private Cols.Ints lLotNo = new Cols.Ints();
    /** Части мира отпущены ({@link #generatorsDone}). */
    private boolean released;
    private final Cols.Bytes lDept = new Cols.Bytes(), lFormat = new Cols.Bytes(), lOffering = new Cols.Bytes(),
            lResult = new Cols.Bytes(), lWithdrawnBy = new Cols.Bytes(), lDefault = new Cols.Bytes();
    /** Лоты торгов: {@code sales.id} → {@code lots.id} подряд. */
    private int[][] saleLots = new int[64][];
    private int[] saleLotN = new int[64];

    // ---------------------------------------------------------------- ставки по id (с 1; индекс — id − 1)
    private int bids;
    private final Cols.Ints bLot = new Cols.Ints(), bClient = new Cols.Ints();
    private final Cols.Millis bAt = new Cols.Millis();
    private final Cols.Kops bAmount = new Cols.Kops();
    private final Cols.Bytes bFlags = new Cols.Bytes();

    // ---------------------------------------------------------------- уведомления
    /**
     * Вид; адресат; {@code ref} — лот у «перебита» и «выиграл» (торги — лота), торги у остальных; ставка — у «перебита» и
     * «выиграл». После {@link #finish} — по порядку выдачи (момент, ключ события).
     */
    private int notices;
    private Cols.Bytes nKind = new Cols.Bytes();
    private Cols.Ints nClient = new Cols.Ints(), nRef = new Cols.Ints(), nBid = new Cols.Ints();
    private Cols.Millis nAt = new Cols.Millis();

    WorldData(long cut, io.hammerhall.data.auc.world.ClientBase clients) {
        this.cut = cut;
        this.clients = clients;
        appendLot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    // ---------------------------------------------------------------- участники, максимумы с сайта
    private int parts;
    private Cols.Ints pLot = new Cols.Ints(), pClient = new Cols.Ints();
    private Cols.Secs pFirst = new Cols.Secs();
    private Cols.Bytes pOnline = new Cols.Bytes();
    private int orders;
    private Cols.Ints oId = new Cols.Ints(), oLot = new Cols.Ints(), oClient = new Cols.Ints();
    private Cols.Millis oAt = new Cols.Millis();
    private Cols.Bytes oApp = new Cols.Bytes();
    private int[] oOrder = new int[0];

    /** План лота (публикация, плановый конец, номер) — сразу после {@link #lot}. */
    void plan(int lotId, long publishedAt, long plannedAt, int lotNo) {
        lPublished.set(lotId, publishedAt);
        lPlanned.set(lotId, plannedAt);
        lLotNo.set(lotId, lotNo);
    }

    /** Смоделированные участники лота (без среза): первая ставка — по строкам модели; клиенты — зарегистрированные до среза. */
    void participants(int lotId, SaleRun r, int i) {
        LotRows w = r.rows;
        long plan = r.sale.live() ? Math.floorDiv(r.slotStart[i], 1000L) : r.lot(i).closesAtPlanned();
        for (int p = r.partFrom[i]; p < r.partFrom[i + 1]; p++) {
            int c = r.pClient[p];
            if (c <= 0 || (clients != null && clients.registeredAt(c) >= cut)) continue;
            long first = plan;
            for (int b = w.bidFrom[i]; b < w.bidTo[i]; b++) {
                if (w.bReg[b] == r.pReg[p]) {
                    first = Math.floorDiv(w.bTime[b], 1000L);
                    break;
                }
            }
            pLot.add(lotId);
            pClient.add(c);
            pFirst.add(first);
            pOnline.add(r.pOnline[p] ? 1 : 0);
            parts++;
        }
    }

    /** Редакция поручения-максимума с сайта или приложения (факт до среза). */
    void order(int id, int lotId, int clientId, long atMs, boolean app) {
        oId.add(id);
        oLot.add(lotId);
        oClient.add(clientId);
        oAt.add(atMs);
        oApp.add(app ? 1 : 0);
        orders++;
    }

    @Override
    public OrderLog orders() {
        alive("поручения");
        int[] o = oOrder;
        Cols.Ints oId = this.oId, oLot = this.oLot, oClient = this.oClient;
        Cols.Millis oAt = this.oAt;
        Cols.Bytes oApp = this.oApp;
        return new OrderLog() {
            @Override
            public int size() {
                return o.length;
            }

            @Override
            public int id(int i) {
                return oId.get(o[i]);
            }

            @Override
            public int lotId(int i) {
                return oLot.get(o[i]);
            }

            @Override
            public int clientId(int i) {
                return oClient.get(o[i]);
            }

            @Override
            public long atMs(int i) {
                return oAt.get(o[i]);
            }

            @Override
            public boolean app(int i) {
                return oApp.get(o[i]) != 0;
            }
        };
    }

    @Override
    public ParticipantLog participants() {
        alive("участники");
        int n = parts;
        Cols.Ints pLot = this.pLot, pClient = this.pClient;
        Cols.Secs pFirst = this.pFirst;
        Cols.Bytes pOnline = this.pOnline;
        return new ParticipantLog() {
            @Override
            public int size() {
                return n;
            }

            @Override
            public int lotId(int i) {
                return pLot.get(i);
            }

            @Override
            public int clientId(int i) {
                return pClient.get(i);
            }

            @Override
            public long firstBidAt(int i) {
                return pFirst.get(i);
            }

            @Override
            public boolean online(int i) {
                return pOnline.get(i) != 0;
            }
        };
    }

    @Override
    public LotPlan lotPlan(int lotId) {
        alive("планы лотов");
        if (lotId < 1 || lotId > lots) return null;
        return new LotPlan(lPublished.get(lotId), lPlanned.get(lotId), lLotNo.get(lotId));
    }

    // ================================================================ наполнение (оркестр)

    void bid(long id, int lotId, int clientId, long atMs, long amountKop, boolean accepted, boolean manualOnline) {
        if (id != bids + 1L) throw new IllegalStateException("ставки в мир — по id подряд: " + id);
        bLot.add(lotId);
        bClient.add(clientId);
        bAt.add(atMs);
        bAmount.add(amountKop);
        bFlags.add((accepted ? 1 : 0) | (manualOnline ? 2 : 0));
        bids++;
    }

    /** Регистрации торгов (решение) и сводка торгов участникам — когда торги отыграны в модели. */
    void saleProcessed(SaleRun r, Registrations regs, long cutSec) {
        for (Registrations.Registration g : regs.ofSale(r.sale.id())) {
            if (g.decidedAt() > 0 && g.decidedAt() < cutSec && !"pending".equals(g.status())) {
                notice(N_REGISTRATION, g.clientId(), r.sale.id(), 0, 0, g.decidedAt() * 1000);
            }
        }
        long end = Outcomes.saleEndSec(r) + 1_800;
        if (end >= cutSec) return;
        // участники со ставками — по клиенту
        int[] cl = new int[r.rows.bids];
        int n = 0;
        for (int b = 0; b < r.rows.bids; b++) if (r.rows.bReg[b] != 0) cl[n++] = r.rows.bClient[b];
        Arrays.sort(cl, 0, n);
        for (int k = 0; k < n; k++) {
            if (k > 0 && cl[k] == cl[k - 1]) continue;
            notice(N_SUMMARY, cl[k], r.sale.id(), 0, 0, end * 1000);
        }
    }

    /** Лот записан: итог на срез; «перебита» и «выиграл» — по записанным ставкам. */
    void lot(int id, SaleRun r, int i, byte res, long closedSec, int buyerRegId) {
        if (id != lots + 1) throw new IllegalStateException("лоты в мир — по id подряд: " + id);
        SalePlan.Lot l = r.lot(i);
        LotRows w = r.rows;
        lots = id;
        boolean sold = res == Codes.RES_SOLD, after = res == Codes.RES_SOLD_AFTER_SALE;
        boolean def = (sold || after) && r.buyerDefault[i] && r.defaultAt[i] < cut;
        boolean wd = res == Codes.RES_WITHDRAWN;
        appendLot(l.key(), r.sale.id(), l.itemId(), l.consignmentId(), r.sale.dept().ordinal(), r.sale.format().ordinal(),
                (byte) l.offeringNo(), res, l.lowKop(), l.highKop(), l.reserveKop(), closedSec,
                sold ? w.hammer[i] : after ? r.afterSalePrice[i] : 0,
                sold ? w.topReg[i] : after ? r.afterSaleReg[i] : 0,
                sold ? w.topClient[i] : after ? r.afterSaleClient[i] : 0,
                sold ? w.secondReg[i] : 0,
                sold ? w.secondAmount[i] : 0,
                def ? 1 : 0,
                def ? r.defaultAt[i] : 0,
                def ? r.scClient[i] : 0,
                def ? r.scPrice[i] : 0,
                (sold || after) && r.paidAt[i] > 0 && r.paidAt[i] < cut ? r.paidAt[i] : 0,
                r.freeAt[i] > 0 && r.freeAt[i] < cut ? r.freeAt[i] : 0,
                wd ? l.withdrawnAt() : 0);
        lWithdrawnBy.add(!wd ? 0 : "house".equals(l.withdrawnBy()) ? 2 : 1);
        int s = r.sale.id();
        if (s >= saleLots.length) {
            saleLots = Arrays.copyOf(saleLots, Math.max(s + 1, saleLots.length * 2));
            saleLotN = Arrays.copyOf(saleLotN, saleLots.length);
        }
        if (saleLots[s] == null) saleLots[s] = new int[8];
        if (saleLotN[s] == saleLots[s].length) saleLots[s] = Arrays.copyOf(saleLots[s], saleLotN[s] * 2);
        saleLots[s][saleLotN[s]++] = id;
        outbid(r, i, id);
        if (sold) notice(N_LOT_WON, w.topClient[i], s, id, r.bidId[w.winningBid[i]], w.closedMs[i]);
    }

    /**
     * «Ваша ставка перебита»: по группам ставок одной миллисекунды — кто вёл до группы или в ней и не ведёт после;
     * адресат — онлайн-участник (вёл ставкой из сайта или приложения); ставка-причина — первая, которая его сместила.
     */
    private void outbid(SaleRun r, int i, int lotId) {
        LotRows w = r.rows;
        long cutMs = cut * 1000;
        long leader = -1;
        int leaderClient = 0;
        boolean leaderOnline = false;
        long[] dispReg = new long[8];
        int[] dispClient = new int[8];
        long[] dispBid = new long[8];
        long[] dispAt = new long[8];
        int nd = 0;
        int b = w.bidFrom[i];
        while (b < w.bidTo[i] && w.bTime[b] < cutMs) {
            long t = w.bTime[b];
            nd = 0;
            for (; b < w.bidTo[i] && w.bTime[b] == t; b++) {
                if (!w.bAccepted[b]) continue;
                long who = w.bReg[b];
                if (who != leader) {
                    if (leader > 0 && leaderOnline) {
                        if (nd == dispReg.length) {
                            dispReg = Arrays.copyOf(dispReg, nd * 2);
                            dispClient = Arrays.copyOf(dispClient, nd * 2);
                            dispBid = Arrays.copyOf(dispBid, nd * 2);
                            dispAt = Arrays.copyOf(dispAt, nd * 2);
                        }
                        dispReg[nd] = leader;
                        dispClient[nd] = leaderClient;
                        dispBid[nd] = r.bidId[b];
                        dispAt[nd] = t;
                        nd++;
                    }
                    // вернул себе лидерство в той же группе — не перебит
                    for (int d = 0; d < nd; d++) if (dispReg[d] == who) dispReg[d] = 0;
                    leader = who;
                    leaderClient = w.bClient[b];
                }
                leaderOnline = w.bChannel[b] == Codes.CH_ONLINE;
            }
            for (int d = 0; d < nd; d++) {
                if (dispReg[d] == 0 || dispReg[d] == leader) continue;
                notice(N_OUTBID, dispClient[d], r.sale.id(), lotId, dispBid[d], dispAt[d]);
            }
        }
    }

    /**
     * Строка лота (кроме {@code withdrawnBy} — её добавляет {@link #lot}; план — {@link #plan}): деньги — копейки,
     * моменты — секунды.
     */
    private void appendLot(long key, int sale, int item, int cons, int dept, int format, int offering, int result, long low,
                           long high, long reserve, long closed, long price, long buyerReg, int buyer, long underReg,
                           long underAmt, int dflt, long defaultAt, int scClient, long scPrice, long paidAt, long freeAt,
                           long withdrawnAt) {
        lKey.add(key);
        lSale.add(sale);
        lItem.add(item);
        lCons.add(cons);
        lDept.add(dept);
        lFormat.add(format);
        lOffering.add(offering);
        lResult.add(result);
        lLow.add(low);
        lHigh.add(high);
        lReserve.add(reserve);
        lClosed.add(closed);
        lPrice.add(price);
        lBuyerReg.add(buyerReg);
        lBuyer.add(buyer);
        lUnderReg.add(underReg);
        lUnderAmt.add(underAmt);
        lDefault.add(dflt);
        lDefaultAt.add(defaultAt);
        lScClient.add(scClient);
        lScPrice.add(scPrice);
        lPaidAt.add(paidAt);
        lFreeAt.add(freeAt);
        lWithdrawnAt.add(withdrawnAt);
        lPublished.add(0);
        lPlanned.add(0);
        lLotNo.add(0);
        if (lKey.size() == 1) lWithdrawnBy.add(0);
    }

    private static boolean lotKind(byte kind) {
        return kind == N_OUTBID || kind == N_LOT_WON;
    }

    private void notice(byte kind, int client, int sale, int lot, long bid, long atMs) {
        if (atMs >= cut * 1000 || client <= 0) return;
        boolean byLot = lotKind(kind);
        if (byLot ? lot <= 0 || lSale.get(lot) != sale : lot != 0 || bid != 0) {
            throw new IllegalStateException("уведомление " + NOTICE_KINDS[kind] + ": торги " + sale + ", лот " + lot + ", ставка " + bid);
        }
        nKind.add(kind);
        nClient.add(client);
        nRef.add(byLot ? lot : sale);
        nBid.add(Math.toIntExact(bid));
        nAt.add(atMs);
        notices++;
    }

    void finish(List<InvoicePlan> inv, ClientLedger l, Prep p) {
        this.prep = p;
        this.ledger = l;
        List<InvoicePlan> s = new ArrayList<>(inv);
        s.sort((a, b) -> a.draft().issuedAt() != b.draft().issuedAt() ? Long.compare(a.draft().issuedAt(), b.draft().issuedAt())
                : Long.compare(a.draft().key(), b.draft().key()));
        this.invoices = List.copyOf(s);
        // уведомления — по моменту, затем ключу события (вид, лот или торги, ставка или клиент); при равных — порядок
        // появления; ключ (вид, ставка) уникален у «перебита» и «выиграл»
        int[] o = Cols.sortedIndex(notices, (a, b) -> {
            long ka = nAt.get(a), kb = nAt.get(b);
            if (ka != kb) return Long.compare(ka, kb);
            return Long.compare(noticeTie(a), noticeTie(b));
        });
        // столбцы — в этом порядке (по одному, чтобы прежний освобождался)
        Cols.Bytes kind = new Cols.Bytes();
        for (int j = 0; j < notices; j++) kind.add(nKind.get(o[j]));
        Cols.Ints client = new Cols.Ints(), ref = new Cols.Ints(), bid = new Cols.Ints();
        for (int j = 0; j < notices; j++) client.add(nClient.get(o[j]));
        nClient = client;
        for (int j = 0; j < notices; j++) ref.add(nRef.get(o[j]));
        nRef = ref;
        for (int j = 0; j < notices; j++) bid.add(nBid.get(o[j]));
        nBid = bid;
        Cols.Millis at = new Cols.Millis();
        for (int j = 0; j < notices; j++) at.add(nAt.get(o[j]));
        nAt = at;
        nKind = kind;
        // максимумы с сайта — по bid_orders.id
        long[] ok = new long[orders], ov = new long[orders];
        for (int i = 0; i < orders; i++) {
            ok[i] = oId.get(i);
            ov[i] = i;
        }
        PairSort.sort(ok, ov, orders);
        oOrder = new int[orders];
        for (int i = 0; i < orders; i++) {
            oOrder[i] = (int) ov[i];
            if (i > 0 && ok[i] == ok[i - 1]) throw new IllegalStateException("редакция поручения " + ok[i] + " дважды");
        }
        for (int i = 0; i < bids; i++) {
            if (bLot.get(i) < 1 || bLot.get(i) > lots) throw new IllegalStateException("ставка " + (i + 1) + ": лот " + bLot.get(i));
        }
        // лоты торгов — без запаса
        for (int q = 0; q < saleLots.length; q++) {
            if (saleLots[q] != null && saleLots[q].length != saleLotN[q]) saleLots[q] = Arrays.copyOf(saleLots[q], saleLotN[q]);
        }
    }

    /** Ключ порядка уведомления после момента: вид (старшие биты), затем ставка или (торги, клиент). */
    private long noticeTie(int i) {
        byte kind = nKind.get(i);
        long tie = lotKind(kind) ? nBid.get(i) : (long) nRef.get(i) << 32 | nClient.get(i);
        return (long) kind << 60 | (tie & 0x0FFF_FFFF_FFFF_FFFFL);
    }

    // ================================================================ AuctionWorld

    @Override
    public int lotCount() {
        return lots;
    }

    @Override
    public LotResult lot(int id) {
        if (id < 1 || id > lots) throw new IllegalArgumentException("нет лота " + id);
        LotResultCode code = switch (lResult.get(id)) {
            case Codes.RES_SOLD -> LotResultCode.SOLD;
            case Codes.RES_BOUGHT_IN -> LotResultCode.BOUGHT_IN;
            case Codes.RES_WITHDRAWN -> LotResultCode.WITHDRAWN;
            case Codes.RES_SOLD_AFTER_SALE -> LotResultCode.SOLD_AFTER_SALE;
            default -> LotResultCode.OPEN;
        };
        byte by = lWithdrawnBy.get(id);
        return new LotResult(lKey.get(id), id, lSale.get(id), lItem.get(id), lCons.get(id), DEPTS[lDept.get(id)],
                FORMATS[lFormat.get(id)], lOffering.get(id), code, lPrice.get(id), lLow.get(id), lHigh.get(id),
                lReserve.get(id), lClosed.get(id), lBuyerReg.get(id), lBuyer.get(id), lUnderReg.get(id), lUnderAmt.get(id),
                lDefault.get(id) != 0, lDefaultAt.get(id), lScClient.get(id), lScPrice.get(id), lPaidAt.get(id),
                lFreeAt.get(id), lWithdrawnAt.get(id), by == 0 ? null : by == 2 ? "house" : "consignor");
    }

    @Override
    public List<LotResult> lotsOfSale(int saleId) {
        if (saleId < 0 || saleId >= saleLots.length || saleLots[saleId] == null) return List.of();
        int[] ids = Arrays.copyOf(saleLots[saleId], saleLotN[saleId]);
        return new AbstractList<>() {
            @Override
            public LotResult get(int index) {
                return lot(ids[index]);
            }

            @Override
            public int size() {
                return ids.length;
            }
        };
    }

    @Override
    public Consignment consignment(int id) {
        alive("договоры");
        return prep.consignment(id);
    }

    @Override
    public Registrations registrations() {
        alive("регистрации");
        return prep.registrations();
    }

    @Override
    public List<InvoicePlan> invoices() {
        alive("счета");
        return invoices;
    }

    @Override
    public BidLog bids() {
        return new BidLog() {
            @Override
            public int size() {
                return bids;
            }

            @Override
            public long id(int i) {
                return i + 1L;
            }

            @Override
            public int lotId(int i) {
                return bLot.get(i);
            }

            @Override
            public int clientId(int i) {
                return bClient.get(i);
            }

            @Override
            public long atMs(int i) {
                return bAt.get(i);
            }

            @Override
            public long amountKop(int i) {
                return bAmount.get(i);
            }

            @Override
            public boolean accepted(int i) {
                return (bFlags.get(i) & 1) != 0;
            }

            @Override
            public boolean manualOnline(int i) {
                return (bFlags.get(i) & 2) != 0;
            }
        };
    }

    @Override
    public List<Notice> notices() {
        alive("уведомления");
        int n = notices;
        Cols.Bytes kinds = nKind;
        Cols.Ints clients = nClient, refs = nRef, bidIds = nBid;
        Cols.Millis ats = nAt;
        return new AbstractList<>() {
            @Override
            public Notice get(int i) {
                if (i < 0 || i >= n) throw new IndexOutOfBoundsException(i);
                byte kind = kinds.get(i);
                int client = clients.get(i), ref = refs.get(i);
                boolean byLot = lotKind(kind);
                int lot = byLot ? ref : 0, sale = byLot ? lSale.get(ref) : ref;
                long bid = byLot ? bidIds.get(i) : 0;
                String key = switch (kind) {
                    case N_OUTBID -> "outbid:" + lot + ":" + bid;
                    case N_LOT_WON -> "lot_won:" + lot;
                    case N_REGISTRATION -> "registration_decided:" + sale + ":" + client;
                    default -> "sale_summary:" + sale + ":" + client;
                };
                return new Notice(NOTICE_KINDS[kind], client, sale, lot, bid, ats.get(i), key);
            }

            @Override
            public int size() {
                return n;
            }
        };
    }

    /** Сайт собрал вход: уведомления, поручения, участники, планы лотов и счета больше не нужны — отпустить. */
    @Override
    public void generatorsDone() {
        if (released) return;
        released = true;
        if (prep != null) prep.generatorsDone();
        ledger = null;
        invoices = null;
        nKind = null;
        nClient = null;
        nRef = null;
        nBid = null;
        nAt = null;
        pLot = null;
        pClient = null;
        pFirst = null;
        pOnline = null;
        oId = null;
        oLot = null;
        oClient = null;
        oAt = null;
        oApp = null;
        oOrder = null;
        lPublished = null;
        lPlanned = null;
        lLotNo = null;
    }

    private void alive(String what) {
        if (released) throw new IllegalStateException("мир торгов: " + what + " отпущены после генераторов (generatorsDone)");
    }

    @Override
    public ClientOutcome clientOutcome(int clientId) {
        alive("исходы клиентов");
        long b = ledger.blockedAt(clientId);
        long e = ledger.enhancedAt(clientId);
        boolean blocked = b != 0 && b < cut;
        boolean enh = e != 0 && e < cut;
        if (!blocked && !enh) return ClientOutcome.NONE;
        return new ClientOutcome(blocked ? b : 0, blocked ? "non_payment" : null, enh ? e : 0);
    }
}
