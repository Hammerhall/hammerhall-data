package io.hammerhall.data.auc.auction.intake;

import io.hammerhall.data.auc.auction.calendar.CalendarView;
import io.hammerhall.data.auc.auction.calendar.Depts;
import io.hammerhall.data.auc.auction.calendar.SaleSpec;
import io.hammerhall.data.auc.auction.calendar.Sessions;
import io.hammerhall.data.auc.auction.calendar.Wave;
import io.hammerhall.data.auc.bid.IncrementTable;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Intake;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.SalePlan;
import io.hammerhall.data.auc.world.StaffRole;
import io.hammerhall.data.core.gen.Planted;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Очередь готовых вещей → каталог торгов. Очередь — по отделу, по порядку готовности; в неё приходят
 * вещи приёма ({@link Intake#ready}), повторные выставления и сданные обратно. Каталог собирается в момент публикации:
 *
 * <ul>
 *   <li><b>живые</b> — лучшие готовые вещи отдела по нижней оценке (от 5 000 ₽) до размера каталога; дорогие
 *       (от 1 млн ₽) берегутся для главных торгов, если они в ближайшие 120 дней;</li>
 *   <li><b>тематические</b> — готовые вещи раздела темы по порядку готовности;</li>
 *   <li><b>еженедельные</b> — остальные по порядку готовности; вещи, достойные живых торгов (нижняя оценка от порога
 *       отдела), ждут ближайших живых торгов отдела (до 70 дней), вещи раздела темы — ближайших тематических (до 21 дня);</li>
 *   <li>залежавшиеся вещи (готовы больше 42 дней) идут в онлайн-торги сверх размера — до двух размеров каталога.</li>
 * </ul>
 * Лоты — по разделу и нижней оценке; стартовая — {@link Pricing#start}; онлайн — место в волне ({@link Wave}), живые —
 * сессии ({@link Sessions}) с аукционистом зала. Снятие до торгов — 0,7 % лотов комитентом, 0,3 % домом.
 */
public final class CatalogBuilder {
    static final long DAY = 86_400;
    static final long LIVE_HOLD_S = 70 * DAY, MAJOR_HOLD_S = 120 * DAY, THEME_HOLD_S = 21 * DAY, THEME_WAIT_S = 42 * DAY;
    static final long STALE_S = 42 * DAY;
    static final long MAJOR_LOW = 1_000_000_00L;
    /**
     * Обычные живые торги — несколько топ-лотов и середина отдела: лотов от 1 млн ₽ — не больше этой доли каталога,
     * остальное — лучшие вещи дешевле (у главных и вечерних предела нет: дорогие вещи берегутся для них).
     */
    static final double REGULAR_TOP_SHARE = 0.10;
    /** Нижняя оценка, с которой вещь ждёт живых торгов отдела. */
    static final long[] LIVE_WORTHY = {50_000_00L, 50_000_00L, 30_000_00L, 30_000_00L, 50_000_00L, 20_000_00L};
    static final double WITHDRAW_CONSIGNOR = 0.007, WITHDRAW_HOUSE = 0.003;

    /** Вещь в очереди отдела: одно выставление. */
    public static final class Entry {
        final int itemId, consignmentId, offeringNo, dept;
        final long prevLotKey, low, high, reserve, readyAt;
        final String topCode;
        final boolean forceOnline;

        Entry(int itemId, int consignmentId, int offeringNo, long prevLotKey, int dept, String topCode, long low, long high,
              long reserve, long readyAt, boolean forceOnline) {
            this.itemId = itemId;
            this.consignmentId = consignmentId;
            this.offeringNo = offeringNo;
            this.prevLotKey = prevLotKey;
            this.dept = dept;
            this.topCode = topCode;
            this.low = low;
            this.high = high;
            this.reserve = reserve;
            this.readyAt = readyAt;
            this.forceOnline = forceOnline;
        }
    }

    static final Comparator<Entry> FIFO = Comparator.comparingLong((Entry e) -> e.readyAt).thenComparingInt(e -> e.itemId);
    static final Comparator<Entry> BEST = Comparator.comparingLong((Entry e) -> -e.low).thenComparingLong(e -> e.readyAt)
            .thenComparingInt(e -> e.itemId);

    private final Intake intake;
    private final HouseRules house;
    private final IncrementTable inc;
    private final ConsignmentBook book;
    private final CalendarView cal;
    private final double scale;
    private final RandomStreams.Stream lotStream;
    private final Planted planted;
    private final List<List<Entry>> queue = new ArrayList<>();
    private final List<List<Entry>> waiting = new ArrayList<>();
    private final long[] pulled = new long[Depts.COUNT];
    private final Map<Integer, String> topCode = new HashMap<>();
    /** Публикации живых, главных и тематических торгов — для «ждать ближайших». */
    private final long[][] livePubs = new long[Depts.COUNT][], majorPubs = new long[Depts.COUNT][];
    private final Map<String, long[]> themePubs = new HashMap<>();
    /** Лоты с заложенным {@code reserve_above_low} — ключи; в {@code Planted} — по {@code lots.id} (как проверяет оркестр). */
    final List<Long> plantedReserve = new ArrayList<>();
    // статистика
    long stLots, stStale, stShort;

    public CatalogBuilder(Intake intake, HouseRules house, ConsignmentBook book, CalendarView cal, List<SaleSpec> modeled,
                          double scale, RandomStreams streams, Planted planted) {
        this.intake = intake;
        this.house = house;
        this.inc = house.increments();
        this.book = book;
        this.cal = cal;
        this.scale = scale;
        this.lotStream = streams.stream("auction.lots");
        this.planted = planted;
        for (int d = 0; d < Depts.COUNT; d++) {
            queue.add(new ArrayList<>());
            waiting.add(new ArrayList<>());
            pulled[d] = Long.MIN_VALUE / 4;
        }
        List<List<Long>> live = new ArrayList<>(), major = new ArrayList<>();
        Map<String, List<Long>> theme = new HashMap<>();
        for (int d = 0; d < Depts.COUNT; d++) {
            live.add(new ArrayList<>());
            major.add(new ArrayList<>());
        }
        for (SaleSpec s : modeled) {
            if (s.live()) {
                live.get(s.dept()).add(s.publishedAt());
                if (!s.tier().equals("regular")) major.get(s.dept()).add(s.publishedAt());
            } else if (s.thematic()) {
                theme.computeIfAbsent(Depts.THEMES[s.theme()].category(), k -> new ArrayList<>()).add(s.publishedAt());
            }
        }
        for (int d = 0; d < Depts.COUNT; d++) {
            livePubs[d] = sorted(live.get(d));
            majorPubs[d] = sorted(major.get(d));
        }
        for (Map.Entry<String, List<Long>> e : theme.entrySet()) themePubs.put(e.getKey(), sorted(e.getValue()));
    }

    private static long[] sorted(List<Long> l) {
        long[] a = new long[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        Arrays.sort(a);
        return a;
    }

    /** Ближайшая публикация после {@code at} (строго); {@link Long#MAX_VALUE} — нет. */
    private static long next(long[] pubs, long at) {
        if (pubs == null) return Long.MAX_VALUE;
        int i = Arrays.binarySearch(pubs, at + 1);
        if (i < 0) i = -i - 1;
        return i < pubs.length ? pubs[i] : Long.MAX_VALUE;
    }

    /** Код раздела верхнего уровня категории-листа. */
    String top(int categoryId) {
        return topCode.computeIfAbsent(categoryId, id -> {
            HouseRules.Category c = house.category(id);
            while (c.parentId() != 0) c = house.category(c.parentId());
            return c.code();
        });
    }

    // ---------------------------------------------------------------- очередь

    /** Вещь снова в очередь (повтор, сдача обратно) — готова с {@code readyAt}. */
    void enqueue(Entry e) {
        waiting.get(e.dept).add(e);
    }

    /** Новое выставление вещи по договору. */
    Entry entry(int itemId, int consignmentId, int offeringNo, long prevLotKey, long low, long high, long reserve, long readyAt,
                boolean forceOnline) {
        int d = intake.dept(itemId).ordinal();
        return new Entry(itemId, consignmentId, offeringNo, prevLotKey, d, top(intake.categoryId(itemId)), low, high, reserve,
                readyAt, forceOnline);
    }

    private void pull(int d, long at) {
        List<Entry> q = queue.get(d);
        boolean added = false;
        long from = pulled[d] == Long.MIN_VALUE / 4 ? 0 : pulled[d];
        if (at > from) {
            for (int id : intake.ready(Dept.byId(d + 1), from, at)) {
                int c = book.intakeConsignment(id);
                if (c == 0) throw new IllegalStateException("вещь " + id + " готова, а договора нет");
                int l = book.line(c, id);
                q.add(new Entry(id, c, 1, 0, d, top(intake.categoryId(id)), book.lineLow(l), book.lineHigh(l),
                        book.lineReserve(l), intake.readyAt(id), false));
                added = true;
            }
            pulled[d] = at;
        }
        List<Entry> w = waiting.get(d);
        for (int i = w.size() - 1; i >= 0; i--) {
            if (w.get(i).readyAt < at) {
                q.add(w.remove(i));
                added = true;
            }
        }
        if (added) q.sort(FIFO);
    }

    /** Вещей ждёт в очередях (готовы к {@code at}). */
    public int queued() {
        int n = 0;
        for (List<Entry> q : queue) n += q.size();
        return n;
    }

    // ---------------------------------------------------------------- каталог

    /** Выбранные вещи последнего каталога (по номерам лотов) — для спроса и итогов. */
    public record Built(SalePlan plan, Entry[] entries) {
    }

    public Built build(Calendar.Sale sale) {
        SaleSpec s = cal.spec(sale.id());
        long pub = s.publishedAt();
        book.advance(pub);
        for (int c : book.takeNewResales()) {
            int l = book.firstLine(c);
            int item = book.lineItem(l);
            Rng rr = lotStream.at(item, book.signedAt(c));
            long ready = book.signedAt(c) + (5 + rr.nextInt(16)) * DAY + rr.nextInt(8 * 3600);
            enqueue(entry(item, c, 1, book.linePrevLot(l), book.lineLow(l), book.lineHigh(l), book.lineReserve(l), ready, false));
        }
        int d = s.dept();
        pull(d, pub);
        int n = s.targetLots(scale);
        List<Entry> chosen = select(s, d, n, pub);
        if (chosen.size() < n) stShort++;
        chosen.sort(Comparator.comparingInt((Entry e) -> categoryOrder(e)).thenComparingLong(e -> -e.low)
                .thenComparingInt(e -> e.itemId));
        List<SalePlan.Lot> lots = new ArrayList<>(chosen.size());
        List<Sessions.Session> sess = s.live() ? Sessions.plan(s.saleDays(), s.firstMinute(), s.pace(), chosen.size()) : List.of();
        int si = 0;
        for (int k = 0; k < chosen.size(); k++) {
            Entry e = chosen.get(k);
            int lotNo = k + 1;
            long key = Keys.lot(sale.id(), lotNo, 0);
            Rng r = lotStream.at(key);
            long start = Pricing.start(inc, e.low, e.reserve, s.live(), r);
            long opens = s.live() ? 0 : s.biddingOpensAt();
            long close = s.live() ? 0 : Wave.plannedClose(s.startsAt(), k);
            int sessionNo = 0;
            long endWindow = close;
            if (s.live()) {
                while (sess.get(si).lastLotNo() < lotNo) si++;
                sessionNo = sess.get(si).no();
                endWindow = sess.get(si).startsAt();
            }
            long wAt = 0;
            String wBy = null;
            double u = r.nextDouble();
            if (u < WITHDRAW_CONSIGNOR + WITHDRAW_HOUSE) {
                wBy = u < WITHDRAW_CONSIGNOR ? "consignor" : "house";
                wAt = pub + (long) ((endWindow - pub) * r.uniform(0.1, 0.9));
            }
            if (e.reserve > e.low) plantedReserve.add(key);
            lots.add(new SalePlan.Lot(key, lotNo, Keys.lotLabel(key), e.itemId, e.consignmentId, book.consignor(e.consignmentId),
                    e.offeringNo, e.prevLotKey, sessionNo, pub, e.low, e.high, start, e.reserve, opens, close, wAt, wBy));
        }
        List<SalePlan.Session> sessions = new ArrayList<>(sess.size());
        for (Sessions.Session x : sess) {
            Rng r = lotStream.at(s.key(), x.no());
            HouseRules.Staff a = house.pick(StaffRole.AUCTIONEER, null, sale.officeId(), x.day(), r);
            if (a == null) throw new IllegalStateException(sale.code() + ": нет аукциониста " + x.day());
            sessions.add(new SalePlan.Session(x.no(), x.startsAt(), x.firstLotNo(), x.lastLotNo(), a.id(), s.pace()));
        }
        stLots += lots.size();
        return new Built(new SalePlan(sale, lots, sessions), chosen.toArray(new Entry[0]));
    }

    private int categoryOrder(Entry e) {
        return intake.categoryId(e.itemId);
    }

    private List<Entry> select(SaleSpec s, int d, int n, long pub) {
        List<Entry> q = queue.get(d);
        List<Entry> out = new ArrayList<>();
        boolean[] take = new boolean[q.size()];
        if (s.live()) {
            boolean major = !s.tier().equals("regular");
            long nextMajor = next(majorPubs[d], pub);
            List<Integer> cand = new ArrayList<>();
            for (int i = 0; i < q.size(); i++) {
                Entry e = q.get(i);
                if (e.low < Pricing.MIN_LOW_LIVE || e.forceOnline) continue;
                if (!major && e.low >= MAJOR_LOW && nextMajor - pub <= MAJOR_HOLD_S) continue;
                cand.add(i);
            }
            cand.sort((a, b) -> BEST.compare(q.get(a), q.get(b)));
            // лучшие n годных (раньше условие было по пустому out — живые торги забирали все годные вещи отдела); у
            // обычных — от 1 млн ₽ не больше REGULAR_TOP_SHARE каталога, остальные места — лучшие дешевле
            int top = major ? n : (int) Math.floor(REGULAR_TOP_SHARE * n), took = 0, tookTop = 0;
            for (int i = 0; i < cand.size() && took < n; i++) {
                boolean expensive = q.get(cand.get(i)).low >= MAJOR_LOW;
                if (expensive && tookTop >= top) continue;
                take[cand.get(i)] = true;
                took++;
                if (expensive) tookTop++;
            }
        } else {
            String theme = s.thematic() ? Depts.THEMES[s.theme()].category() : null;
            long nextLive = next(livePubs[d], pub);
            int count = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < q.size(); i++) {
                    if (take[i]) continue;
                    Entry e = q.get(i);
                    if (theme != null && !theme.equals(e.topCode)) continue;
                    boolean stale = pub - e.readyAt > STALE_S;
                    if (pass == 0 && count >= n) break;
                    if (pass == 1 && (!stale || count >= 2 * n)) continue;
                    if (!e.forceOnline && e.low >= LIVE_WORTHY[d] && pub - e.readyAt < LIVE_HOLD_S && nextLive - pub <= LIVE_HOLD_S) continue;
                    if (theme == null && themePubs.containsKey(e.topCode) && pub - e.readyAt < THEME_WAIT_S
                            && next(themePubs.get(e.topCode), pub) - pub <= THEME_HOLD_S) continue;
                    take[i] = true;
                    count++;
                    if (pass == 1) stStale++;
                }
            }
        }
        List<Entry> keep = new ArrayList<>(q.size());
        for (int i = 0; i < q.size(); i++) {
            if (take[i]) out.add(q.get(i));
            else keep.add(q.get(i));
        }
        q.clear();
        q.addAll(keep);
        return out;
    }

    public String stats() {
        return "лотов " + stLots + ", залежавшихся " + stStale + ", каталогов меньше плана " + stShort + ", в очереди " + queued();
    }
}
