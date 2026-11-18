package io.hammerhall.data.auc.auction.intake;

import io.hammerhall.data.auc.bid.IncrementTable;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Intake;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Договоры комиссии ({@code consignments}) и вещи в них ({@code consignment_items}).
 *
 * <ul>
 *   <li><b>Договор приёма</b> — один на визит ({@link Intake.Visit}) с хотя бы одной принятой вещью; подписан в момент
 *       решения; вид — из визита. Эстимейт и резерв каждой вещи — при приёме ({@link Pricing}, поток
 *       {@code auction.lots} по ключу вещи). Комиссия: договорная единая ставка 5–10 % у крупных договоров (вещь с
 *       нижней оценкой от 1,5 млн ₽ или сумма нижних от 3 млн ₽), иначе шкала {@code SC-2024}, у торговцев — со скидкой;
 *       с непроданным: выставить снова 44 %, спросить 30 %, вернуть 26 %; срок — 12 месяцев.</li>
 *   <li><b>Сдача купленного обратно</b> ({@code resale}) — новый договор покупателя через 3–15 месяцев после передачи вещи
 *       ({@link #resale}).</li>
 * </ul>
 * {@code id} — по моменту подписания, затем ключу: договор получает {@code id}, когда момент подписания позади всех ещё
 * возможных договоров ({@link #advance}); сдачи купленного рождаются не раньше чем через 3 месяца после торгов, поэтому
 * к любому моменту каталога все договоры до него известны.
 */
public final class ConsignmentBook {
    public static final String[] POLICIES = {"reoffer", "ask", "return"};
    /** Договорная ставка — у крупных договоров. */
    static final long BIG_ITEM_LOW = 1_500_000_00L, BIG_TOTAL_LOW = 3_000_000_00L;

    /** Договор до выдачи {@code id}. */
    record Draft(long key, int consignorId, int officeId, int specialistId, String kind, long signedAt, Integer rateMilliPct,
                 boolean dealerDiscount, String policy, LocalDate expiresOn, int[] items, long[] low, long[] high,
                 long[] reserve, long prevLotKey) {
    }

    private final Intake intake;
    private final ClientBase clients;
    private final HouseRules house;
    private final IncrementTable inc;
    private final RandomStreams.Stream lotsStream, contractStream;
    private LocalDate nextVisitDay;
    private final List<Draft> pending = new ArrayList<>();

    // ---- выданные договоры: по id с 1
    private int n;
    private int[] consignor = new int[1024], office = new int[1024], specialist = new int[1024], rate = new int[1024];
    private long[] signedAt = new long[1024], key = new long[1024];
    private String[] kind = new String[1024], policy = new String[1024];
    private boolean[] dealer = new boolean[1024];
    private LocalDate[] expires = new LocalDate[1024];
    private int[] firstLine = new int[1025];
    // ---- строки договоров: подряд по договорам
    private int lines;
    private int[] lineItem = new int[4096];
    private long[] lineLow = new long[4096], lineHigh = new long[4096], lineReserve = new long[4096], lineEnded = new long[4096];
    private long[] linePrevLot = new long[4096];
    private byte[] lineOutcome = new byte[4096];
    /** Договор приёма вещи: {@code items.id} → {@code consignments.id}. */
    private int[] intakeOf;
    /** Выданные, но ещё не записанные договоры — с этого {@code id}. */
    private int written;
    /** Сдачи обратно, получившие {@code id} после последнего {@link #takeNewResales}. */
    private final List<Integer> newResales = new ArrayList<>();

    public static final byte OUT_NONE = 0, OUT_SOLD = 1, OUT_RETURNED = 2, OUT_WITHDRAWN = 3;
    static final String[] OUTCOME = {null, "sold", "returned", "withdrawn"};

    public ConsignmentBook(Intake intake, ClientBase clients, HouseRules house, RandomStreams streams) {
        this.intake = intake;
        this.clients = clients;
        this.house = house;
        this.inc = house.increments();
        this.lotsStream = streams.stream("auction.lots");
        this.contractStream = streams.stream("auction.contracts");
        this.nextVisitDay = intake.firstDay();
        this.intakeOf = new int[intake.size() + 1];
    }

    // ---------------------------------------------------------------- выдача id

    /** Выдать {@code id} договорам, подписанным раньше {@code at} (визиты — по сутки {@code at} включительно). */
    public void advance(long at) {
        LocalDate last = Period.localDate(at, HouseRules.ZONE);
        if (last.isAfter(intake.lastDay())) last = intake.lastDay();
        for (; !nextVisitDay.isAfter(last); nextVisitDay = nextVisitDay.plusDays(1)) {
            for (Intake.Visit v : intake.visits(nextVisitDay)) {
                Draft d = fromVisit(v);
                if (d != null) pending.add(d);
            }
        }
        pending.sort(Comparator.comparingLong(Draft::signedAt).thenComparingLong(Draft::key));
        int k = 0;
        while (k < pending.size() && pending.get(k).signedAt() < at) assign(pending.get(k++));
        if (k > 0) pending.subList(0, k).clear();
    }

    private Draft fromVisit(Intake.Visit v) {
        int m = 0;
        for (int id : v.itemIds()) if (intake.item(id).accepted()) m++;
        if (m == 0) return null;
        int[] items = new int[m];
        long[] low = new long[m], high = new long[m], res = new long[m];
        long total = 0, maxLow = 0;
        int j = 0;
        for (int id : v.itemIds()) {
            Intake.Item it = intake.item(id);
            if (!it.accepted()) continue;
            Rng r = lotsStream.at(it.key());
            Pricing.Estimate e = Pricing.estimate(inc, it.valueKop(), it.dept().ordinal(), r);
            items[j] = id;
            low[j] = e.low();
            high[j] = e.high();
            if (r.chance(Pricing.RESERVE_ABOVE_LOW_SHARE)) res[j] = Pricing.reserveAboveLow(inc, e.low(), r);
            else res[j] = Pricing.noReserve(e.low(), it.dept().ordinal(), r) ? 0 : Pricing.reserve(inc, e.low(), it.dept().ordinal(), r);
            total += e.low();
            maxLow = Math.max(maxLow, e.low());
            j++;
        }
        Rng r = contractStream.at(v.key());
        Integer rateMp = null;
        if (maxLow >= BIG_ITEM_LOW || total >= BIG_TOTAL_LOW) rateMp = 5_000 + 500 * r.nextInt(11);
        boolean dealerDiscount = rateMp == null && (v.kind().equals("dealer") || clients.dealer(v.consignorId()));
        double u = r.nextDouble();
        String pol = u < 0.44 ? "reoffer" : u < 0.74 ? "ask" : "return";
        LocalDate exp = Period.localDate(v.decidedAt(), HouseRules.ZONE).plusMonths(12);
        return new Draft(v.key(), v.consignorId(), v.officeId(), v.specialistId(), v.kind(), v.decidedAt(), rateMp,
                dealerDiscount, pol, exp, items, low, high, res, 0);
    }

    /**
     * Сдача купленного обратно: покупатель {@code buyerId} вещи {@code itemId} (продана лотом {@code lotKey}, оплачена в
     * {@code releasedAt}; вещь передают в течение двух недель после оплаты) приносит её новым договором через 3–15
     * месяцев после передачи — не раньше 105 дней после оплаты. Эстимейт — заново от V (рынок за это время
     * сдвинулся); резерв — как при приёме.
     */
    public Draft resale(int itemId, int buyerId, long lotKey, long releasedAt, Rng r) {
        Intake.Item it = intake.item(itemId);
        long signed = releasedAt + (105 + r.nextInt(361)) * 86_400L + r.nextInt(8 * 3600);
        Dept d = it.dept();
        int officeId = r.chance(0.7) ? house.office("msk").id() : house.office("spb").id();
        LocalDate day = Period.localDate(signed, HouseRules.ZONE);
        HouseRules.Staff spec = house.pick(io.hammerhall.data.auc.world.StaffRole.SPECIALIST, d, officeId, day, r);
        long v = (long) (it.valueKop() * StrictMath.exp(0.12 * r.gaussian()) * 1.04);
        Pricing.Estimate e = Pricing.estimate(inc, v, d.ordinal(), r);
        long res = Pricing.noReserve(e.low(), d.ordinal(), r) ? 0 : Pricing.reserve(inc, e.low(), d.ordinal(), r);
        boolean dealerDiscount = clients.dealer(buyerId);
        double u = r.nextDouble();
        String pol = u < 0.44 ? "reoffer" : u < 0.74 ? "ask" : "return";
        Draft dr = new Draft(Long.MIN_VALUE + ((long) itemId << 20) + (lotKey & 0xFFFFF), buyerId, officeId,
                spec == null ? 0 : spec.id(), "resale", signed, null, dealerDiscount, pol, day.plusMonths(12),
                new int[]{itemId}, new long[]{e.low()}, new long[]{e.high()}, new long[]{res}, lotKey);
        pending.add(dr);
        return dr;
    }

    private void assign(Draft d) {
        int id = ++n;
        grow(id + 1, d.items().length);
        consignor[id] = d.consignorId();
        office[id] = d.officeId();
        specialist[id] = d.specialistId();
        kind[id] = d.kind();
        signedAt[id] = d.signedAt();
        key[id] = d.key();
        rate[id] = d.rateMilliPct() == null ? -1 : d.rateMilliPct();
        dealer[id] = d.dealerDiscount();
        policy[id] = d.policy();
        expires[id] = d.expiresOn();
        firstLine[id] = lines;
        for (int i = 0; i < d.items().length; i++) {
            int l = lines++;
            lineItem[l] = d.items()[i];
            lineLow[l] = d.low()[i];
            lineHigh[l] = d.high()[i];
            lineReserve[l] = d.reserve()[i];
            linePrevLot[l] = d.prevLotKey();
            if (!d.kind().equals("resale")) intakeOf[d.items()[i]] = id;
        }
        if (d.kind().equals("resale")) newResales.add(id);
        firstLine[id + 1] = lines;
    }

    private void grow(int ids, int more) {
        if (ids + 1 >= consignor.length) {
            int c = consignor.length * 2;
            consignor = Arrays.copyOf(consignor, c);
            office = Arrays.copyOf(office, c);
            specialist = Arrays.copyOf(specialist, c);
            rate = Arrays.copyOf(rate, c);
            signedAt = Arrays.copyOf(signedAt, c);
            key = Arrays.copyOf(key, c);
            kind = Arrays.copyOf(kind, c);
            policy = Arrays.copyOf(policy, c);
            dealer = Arrays.copyOf(dealer, c);
            expires = Arrays.copyOf(expires, c);
            firstLine = Arrays.copyOf(firstLine, c + 1);
        }
        if (lines + more >= lineItem.length) {
            int c = Math.max(lineItem.length * 2, lines + more + 16);
            lineItem = Arrays.copyOf(lineItem, c);
            lineLow = Arrays.copyOf(lineLow, c);
            lineHigh = Arrays.copyOf(lineHigh, c);
            lineReserve = Arrays.copyOf(lineReserve, c);
            lineEnded = Arrays.copyOf(lineEnded, c);
            linePrevLot = Arrays.copyOf(linePrevLot, c);
            lineOutcome = Arrays.copyOf(lineOutcome, c);
        }
    }

    /** Договоры сдачи обратно, получившие {@code id} с прошлого вызова. */
    public List<Integer> takeNewResales() {
        List<Integer> out = new ArrayList<>(newResales);
        newResales.clear();
        return out;
    }

    // ---------------------------------------------------------------- чтение

    /** Выдано договоров. */
    public int size() {
        return n;
    }

    /** Договор приёма вещи; 0 — не принята или договор ещё без {@code id}. */
    public int intakeConsignment(int itemId) {
        return itemId > 0 && itemId < intakeOf.length ? intakeOf[itemId] : 0;
    }

    public AuctionWorld.Consignment consignment(int id) {
        return new AuctionWorld.Consignment(id, consignor[id], kind[id], dealer[id], rate[id] < 0 ? null : rate[id], signedAt[id]);
    }

    public int consignor(int id) {
        return consignor[id];
    }

    public long signedAt(int id) {
        return signedAt[id];
    }

    public String kind(int id) {
        return kind[id];
    }

    public String policy(int id) {
        return policy[id];
    }

    public LocalDate expiresOn(int id) {
        return expires[id];
    }

    /** Строка договора с вещью; −1 — нет. */
    public int line(int id, int itemId) {
        for (int l = firstLine[id]; l < firstLine[id + 1]; l++) if (lineItem[l] == itemId) return l;
        return -1;
    }

    public int firstLine(int id) {
        return firstLine[id];
    }

    public int lineEnd(int id) {
        return firstLine[id + 1];
    }

    public int lineItem(int l) {
        return lineItem[l];
    }

    public long lineLow(int l) {
        return lineLow[l];
    }

    public long lineHigh(int l) {
        return lineHigh[l];
    }

    public long lineReserve(int l) {
        return lineReserve[l];
    }

    public long linePrevLot(int l) {
        return linePrevLot[l];
    }

    /** Вещь вышла из договора: итог и момент (первый окончательный — остаётся). */
    public void end(int id, int itemId, byte outcome, long at) {
        int l = line(id, itemId);
        if (l < 0) throw new IllegalStateException("договор " + id + ": нет вещи " + itemId);
        if (lineOutcome[l] != OUT_NONE) return;
        lineOutcome[l] = outcome;
        lineEnded[l] = at;
    }

    public byte lineOutcome(int l) {
        return lineOutcome[l];
    }

    public long lineEnded(int l) {
        return lineEnded[l];
    }

    // ---------------------------------------------------------------- строки

    /** Строки {@code consignments} с {@code id} от последней записи по выданный (подписаны раньше водораздела). */
    public void rows(int commissionScheduleId, long cut, RowSink out) throws java.io.IOException {
        for (; written < n; written++) {
            int id = written + 1;
            if (signedAt[id] >= cut) break;
            out.row(new Object[]{id, consignor[id], office[id], specialist[id], kind[id], signedAt[id], commissionScheduleId,
                    rate[id] < 0 ? null : java.math.BigDecimal.valueOf(rate[id], 3), dealer[id], policy[id], expires[id]});
        }
    }

    /** Записано договоров. */
    public int written() {
        return written;
    }

    /** Строки {@code consignment_items} записанных договоров — состояние на срез {@code cut}. */
    public void lineRows(long cut, RowSink out) throws java.io.IOException {
        for (int id = 1; id <= written; id++) {
            for (int l = firstLine[id]; l < firstLine[id + 1]; l++) {
                boolean ended = lineOutcome[l] != OUT_NONE && lineEnded[l] < cut;
                out.row(new Object[]{id, l - firstLine[id] + 1, lineItem[l], lineLow[l], lineHigh[l],
                        lineReserve[l] == 0 ? null : lineReserve[l], ended ? lineEnded[l] : null,
                        ended ? OUTCOME[lineOutcome[l]] : null});
            }
        }
    }

    /** Приёмник строк. */
    public interface RowSink {
        void row(Object[] values) throws java.io.IOException;
    }
}
