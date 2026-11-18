package io.hammerhall.data.auc.catalog;

import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Intake;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.StaffRole;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Поток приёма вещей: функция даты, {@code seed} и масштаба — не периода и не очереди торгов.
 *
 * <p>Сутки за сутками с 01.06.2025: в каждом открытом офисе — визиты (число — Пуассон от нормы офиса × сезон × будни ×
 * разгон лета 2025 × масштаб, ключ — (офис, сутки)); визит — комитент из базы (вес: торговцы, учреждения, свой офис по
 * округу города), вид договора и число вещей; вещь — отдел (чаще — главный интерес комитента), ключ (отдел, сутки,
 * номер), текст ({@link Texts}), скрытая стоимость V ({@link #value}), решение о приёме (7 % — отказ), готовность к
 * каталогу (решение + 5–20 суток). {@code id} — по ({@code received_at}, ключ): продление дописывает хвост.
 */
public final class IntakeFlow implements Intake {
    /**
     * Вещей в рабочий день во всех офисах в {@code full}: под спрос календаря торгов —
     * ≈ 13 100 лотов в неделю, новых вещей ≈ 0,88 от лотов, отказ при приёме 7 % → ≈ 12 400 принесённых в неделю на
     * 5,5 рабочего дня (суббота — половина), в среднем за год с летом и праздниками — ≈ 5 рабочих дней в неделю.
     */
    static final double ITEMS_PER_WORKDAY_FULL = 2_470;
    /**
     * Целевые доли отделов среди принесённых вещей — по спросу календаря, лотов в неделю: живопись 2 254, ювелирное 1 708,
     * монеты 2 137, книги 2 078, декоративно-прикладное 2 324, коллекционные 2 599.
     */
    static final double[] DEPT_TARGET = share(2_254, 1_708, 2_137, 2_078, 2_324, 2_599);
    /** Доля вещей визита из главного интереса комитента; остальные — так, чтобы смесь дала {@link #DEPT_TARGET}. */
    static final double MAIN_BIAS = 0.5;
    /** Доли визитов по офисам (в порядке {@code offices.id}); у закрытой ещё приёмной — её доля у Москвы. */
    static final double[] OFFICE_SHARE = {0.50, 0.22, 0.05, 0.04, 0.06, 0.05, 0.04, 0.04};
    /** Доли отделов среди вещей «не по главному интересу»: (цель − смещение × доля главного интереса) / (1 − смещение). */
    static final double[] DEPT_INTAKE = compensate();

    private static double[] share(double... w) {
        double s = 0;
        for (double x : w) s += x;
        double[] out = new double[w.length];
        for (int i = 0; i < w.length; i++) out[i] = w[i] / s;
        return out;
    }

    private static double[] compensate() {
        double[] main = io.hammerhall.data.auc.people.ClientPopulation.mainInterestShares();
        double[] out = new double[DEPT_TARGET.length];
        for (int i = 0; i < out.length; i++) out[i] = Math.max(0.01, (DEPT_TARGET[i] - MAIN_BIAS * main[i]) / (1 - MAIN_BIAS));
        return share(out);
    }
    static final String[] DECLINE = {"low_value", "condition", "authenticity_doubt", "duplicate"};
    /** Порог V принятой вещи ({@link Intake#MIN_VALUE_KOP}); дешевле — отказ с этой долей, иначе — в лот с подобными. */
    static final long MIN_VALUE_KOP = Intake.MIN_VALUE_KOP;
    static final double CHEAP_DECLINE = 0.2;
    /** Прочие отказы при приёме (состояние, сомнение в подлинности, такая же вещь в работе, малая цена) — до ≈ 7 % вместе. */
    static final double RANDOM_DECLINE = 0.04;
    static final double[] DECLINE_W = {0.50, 0.20, 0.18, 0.12};
    /** Медиана V по отделам, ₽. */
    static final double[] DEPT_MEDIAN_RUB = {30_000, 28_000, 9_000, 12_000, 17_000, 7_000};

    /** Приёмник строк: вещь с текстом — по возрастанию {@code id}. */
    public interface Sink {
        void item(Item item, Texts.ItemText text) throws IOException;
    }

    private final LocalDate first, last;
    private int n;
    private long[] key = new long[1 << 12], received = new long[1 << 12], decided = new long[1 << 12],
            ready = new long[1 << 12], value = new long[1 << 12], visitKey = new long[1 << 12];
    private int[] category = new int[1 << 12], maker = new int[1 << 12], consignor = new int[1 << 12],
            specialist = new int[1 << 12];
    private byte[] dept = new byte[1 << 12], office = new byte[1 << 12], decline = new byte[1 << 12],
            attribution = new byte[1 << 12];
    private char[] tier = new char[1 << 12];
    private final List<Visit> visits = new ArrayList<>();
    private final long[] dayFirstVisit;
    private final int[][] readyIds = new int[Dept.count()][];
    private final long[][] readyAt = new long[Dept.count()][];

    static final String[] ATTRIBUTIONS = {"by", "attributed", "workshop", "circle", "follower", "manner", "after", "unknown"};
    static final double[] ATTR_FACTOR = {1.0, 0.55, 0.4, 0.3, 0.2, 0.15, 0.08, 0.35};

    /** Вещь визита до текста: ключ, отдел, визит, номер в визите. */
    private record Seed(long key, Dept dept, Visit visit, int no) {
    }

    /** Вещь с текстом; {@code received} — сначала промежуток после предыдущей вещи визита, потом — момент. */
    private record Draft(long key, Dept dept, long received, Visit visit, int seedNo, int specialist, Texts.ItemText text,
                         boolean accepted, String declineReason, long ready, long value, char tier) {
        Draft withReceived(long at) {
            return new Draft(key, dept, at, visit, seedNo, specialist, text, accepted, declineReason, ready, value, tier);
        }

        Draft withVisit(Visit v, long readyAt) {
            return new Draft(key, dept, received, v, seedNo, specialist, text, accepted, declineReason, readyAt, value, tier);
        }
    }

    private static Draft make(Seed sd, LocalDate day, RandomStreams.Stream si, HouseRules house, Texts texts, char[] makerTier) {
        Rng ri = si.at(sd.key());
        Dept d = sd.dept();
        Texts.ItemText text = texts.item(d, sd.key());
        char t = text.makerId() == 0 ? 'D' : makerTier[text.makerId()];
        long v0 = value(house, d, text, t, ri);
        // дешевле порога: часть — отказ «малая цена», остальное специалист собирает в лот с подобными — V не ниже порога
        boolean declined;
        String reason;
        if (v0 < MIN_VALUE_KOP && ri.chance(CHEAP_DECLINE)) {
            declined = true;
            reason = "low_value";
        } else {
            if (v0 < MIN_VALUE_KOP) v0 = MIN_VALUE_KOP + (long) (ri.nextDouble() * 0.8 * MIN_VALUE_KOP);
            declined = ri.chance(RANDOM_DECLINE);
            reason = declined ? DECLINE[ri.weighted(DECLINE_W)] : null;
        }
        HouseRules.Staff sp = house.pick(StaffRole.SPECIALIST, d, sd.visit().officeId(), day, ri);
        long rdy = declined ? 0 : sd.visit().decidedAt() + (5 + ri.nextInt(16)) * 86_400L + ri.nextInt(8 * 3_600);
        long gap = 60L + ri.nextInt(240);
        return new Draft(sd.key(), d, gap, sd.visit(), sd.no(), sp == null ? 0 : sp.id(), text, !declined, reason, rdy, v0, t);
    }

    public IntakeFlow(RandomStreams streams, HouseRules house, ClientBase clients, Texts texts, char[] makerTier,
                      double scale, LocalDate from, LocalDate to, Sink sink) throws IOException {
        this.first = from;
        this.last = to;
        RandomStreams.Stream st = streams.stream("catalog.intake");
        RandomStreams.Stream si = streams.stream("catalog.item");
        ConsignorPicker picker = new ConsignorPicker(clients, house);
        double perVisit = meanItemsPerVisit();
        int days = (int) (to.toEpochDay() - from.toEpochDay() + 1);
        dayFirstVisit = new long[days + 1];
        List<Seed> seeds = new ArrayList<>();
        for (int di = 0; di < days; di++) {
            LocalDate day = from.plusDays(di);
            dayFirstVisit[di] = visits.size();
            double f = dayFactor(day);
            if (f <= 0) continue;
            int[] perDept = new int[Dept.count()];
            for (HouseRules.Office o : house.offices()) {
                double share = officeShare(house, o, day);
                if (share <= 0) continue;
                Rng rc = st.at(o.id(), day.toEpochDay());
                int nv = rc.poisson(ITEMS_PER_WORKDAY_FULL / perVisit * share * f * scale);
                for (int v = 0; v < nv; v++) {
                    long vk = Keys.visit(o.id(), day, v);
                    Rng r = st.at(vk);
                    boolean saturday = day.getDayOfWeek() == DayOfWeek.SATURDAY;
                    double hour = saturday ? r.uniform(11, 16) : r.uniform(10, 19);
                    long at = day.atStartOfDay(HouseRules.ZONE).toEpochSecond() + (long) (hour * 3600);
                    int who = picker.pick(o.id(), at, r);
                    if (who == 0) continue;
                    String kind = clients.dealer(who) ? "dealer" : clients.institution(who) ? "institution"
                            : r.chance(0.12) ? "estate" : "private";
                    int items = switch (kind) {
                        case "dealer" -> 1 + r.poisson(8);
                        case "estate" -> 2 + r.poisson(9);
                        case "institution" -> 1 + r.poisson(4);
                        default -> 1 + r.poisson(2.5);
                    };
                    Dept main = mainInterest(clients, who);
                    long dec = r.chance(0.8) ? at + 3_600 + r.nextInt(3 * 3_600)
                            : workday(day, 1 + r.nextInt(5)).atStartOfDay(HouseRules.ZONE).toEpochSecond()
                            + (12 * 3_600 + r.nextInt(6 * 3_600));
                    HouseRules.Staff head = house.pick(StaffRole.SPECIALIST, main, o.id(), day, r);
                    int[] ids = new int[items];
                    Visit visit = new Visit(vk, who, o.id(), head == null ? 0 : head.id(), kind, at, dec, ids);
                    for (int k = 0; k < items; k++) {
                        Dept d = r.chance(MAIN_BIAS) ? main : Dept.byId(r.weighted(DEPT_INTAKE) + 1);
                        seeds.add(new Seed(Keys.item(d, day, perDept[d.ordinal()]++), d, visit, k));
                    }
                    visits.add(visit);
                }
            }
            // тексты и стоимость — параллельно (каждая вещь — от своего ключа), порядок — как в списке
            LocalDate dayF = day;
            List<Draft> made = java.util.stream.IntStream.range(0, seeds.size()).parallel()
                    .mapToObj(i -> make(seeds.get(i), dayF, si, house, texts, makerTier)).toList();
            // моменты приёма вещей визита — подряд; решение — не раньше последней вещи (у больших визитов осмотр долгий)
            List<Draft> drafts = new ArrayList<>(made.size());
            for (int i = 0; i < made.size(); ) {
                Visit v = made.get(i).visit();
                int j = i;
                long tm = v.receivedAt();
                List<Draft> group = new ArrayList<>();
                while (j < made.size() && made.get(j).visit() == v) {
                    Draft dr = made.get(j++);
                    if (dr.seedNo() > 0) tm += dr.received();
                    group.add(dr.withReceived(tm));
                }
                long dec = decisionAfter(v.receivedAt(), v.decidedAt(), tm);
                if (dec != v.decidedAt()) {
                    Visit v2 = new Visit(v.key(), v.consignorId(), v.officeId(), v.specialistId(), v.kind(), v.receivedAt(),
                            dec, v.itemIds());
                    for (int x = (int) dayFirstVisit[di]; x < visits.size(); x++) if (visits.get(x) == v) visits.set(x, v2);
                    long shift = dec - v.decidedAt();
                    for (Draft dr : group) drafts.add(dr.withVisit(v2, dr.ready() == 0 ? 0 : dr.ready() + shift));
                } else {
                    drafts.addAll(group);
                }
                i = j;
            }
            seeds.clear();
            drafts.sort(Comparator.comparingLong(Draft::received).thenComparingLong(Draft::key));
            // id визита → индексы его вещей
            for (Draft dr : drafts) {
                int id = ++n;
                grow();
                key[id] = dr.key();
                received[id] = dr.received();
                decided[id] = dr.visit().decidedAt();
                ready[id] = dr.ready();
                value[id] = dr.value();
                visitKey[id] = dr.visit().key();
                category[id] = dr.text().categoryId();
                maker[id] = dr.text().makerId();
                consignor[id] = dr.visit().consignorId();
                specialist[id] = dr.specialist();
                dept[id] = (byte) dr.dept().id();
                office[id] = (byte) dr.visit().officeId();
                decline[id] = (byte) (dr.declineReason() == null ? 0 : 1 + Arrays.asList(DECLINE).indexOf(dr.declineReason()));
                attribution[id] = (byte) Arrays.asList(ATTRIBUTIONS).indexOf(dr.text().attribution());
                tier[id] = dr.tier();
                int[] vi = dr.visit().itemIds();
                for (int k = 0; k < vi.length; k++) {
                    if (vi[k] == 0) {
                        vi[k] = id;
                        break;
                    }
                }
                if (sink != null) sink.item(item(id), dr.text());
            }
            List<Visit> today = visits.subList((int) dayFirstVisit[di], visits.size());
            for (Visit v : today) Arrays.sort(v.itemIds());
            today.sort(Comparator.comparingLong(Visit::receivedAt).thenComparingLong(Visit::key));
        }
        dayFirstVisit[days] = visits.size();
        // по отделам — принятые по готовности
        for (Dept d : Dept.values()) {
            int k = 0;
            for (int id = 1; id <= n; id++) if (dept[id] == d.id() && decline[id] == 0) k++;
            Integer[] ids = new Integer[k];
            k = 0;
            for (int id = 1; id <= n; id++) if (dept[id] == d.id() && decline[id] == 0) ids[k++] = id;
            Arrays.sort(ids, Comparator.<Integer>comparingLong(i -> ready[i]).thenComparingInt(i -> i));
            readyIds[d.ordinal()] = new int[k];
            readyAt[d.ordinal()] = new long[k];
            for (int i = 0; i < k; i++) {
                readyIds[d.ordinal()][i] = ids[i];
                readyAt[d.ordinal()][i] = ready[ids[i]];
            }
        }
    }

    private void grow() {
        if (n < key.length) return;
        int c = key.length * 2;
        key = Arrays.copyOf(key, c);
        received = Arrays.copyOf(received, c);
        decided = Arrays.copyOf(decided, c);
        ready = Arrays.copyOf(ready, c);
        value = Arrays.copyOf(value, c);
        visitKey = Arrays.copyOf(visitKey, c);
        category = Arrays.copyOf(category, c);
        maker = Arrays.copyOf(maker, c);
        consignor = Arrays.copyOf(consignor, c);
        specialist = Arrays.copyOf(specialist, c);
        dept = Arrays.copyOf(dept, c);
        office = Arrays.copyOf(office, c);
        decline = Arrays.copyOf(decline, c);
        attribution = Arrays.copyOf(attribution, c);
        tier = Arrays.copyOf(tier, c);
    }

    // ---------------------------------------------------------------- нормы

    /**
     * Решение о приёме визита — не раньше последней принятой на осмотр вещи. У большого визита (20 и больше вещей)
     * вещи принимаются дольше, чем «через 1–4 ч» до решения: тогда решение — через 30–75 мин после последней вещи (запас
     * визита сохраняется: {@code (решение − приход − 1 ч) / 4}); иначе — как было. Без новых случайных чисел, поэтому
     * визиты, где решение и так позже последней вещи, не меняются.
     *
     * @param receivedAt   приход визита (первая вещь)
     * @param decidedAt    решение по плану визита
     * @param lastReceived момент последней вещи визита
     */
    static long decisionAfter(long receivedAt, long decidedAt, long lastReceived) {
        if (decidedAt > lastReceived) return decidedAt;
        long slack = Math.max(0, decidedAt - receivedAt - 3_600);
        return lastReceived + 1_800 + slack / 4;
    }

    /** Среднее число вещей в визите при долях видов (торговцы ≈ 12 % визитов, наследство ≈ 10 %). */
    static double meanItemsPerVisit() {
        return 0.12 * 9 + 0.10 * 11 + 0.03 * 5 + 0.75 * 3.5;
    }

    /** Сезон, будни, праздники и разгон лета 2025 года (первые торги — в октябре). */
    static double dayFactor(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        if (w == DayOfWeek.SUNDAY) return 0;
        int m = d.getMonthValue();
        if (m == 1 && d.getDayOfMonth() <= 10) return 0;
        if (m == 5 && d.getDayOfMonth() <= 3) return 0;
        double f = w == DayOfWeek.SATURDAY ? 0.5 : 1.0;
        if (m >= 6 && m <= 8) f *= 0.85;
        if (m == 12 && d.getDayOfMonth() >= 25) f *= 0.6;
        // разгон лета 2025: торги с каталогом от 01.10.2025 берут вещи с ≈ 01.09 (живые — с ≈ 01.08); июнь–июль — немного
        LocalDate aug = LocalDate.of(2025, 8, 1), sep = LocalDate.of(2025, 9, 1);
        if (d.isBefore(aug)) {
            f *= 0.12;
        } else if (d.isBefore(sep)) {
            f *= 0.3 + 0.6 * (d.toEpochDay() - aug.toEpochDay()) / (double) (sep.toEpochDay() - aug.toEpochDay());
        }
        return f;
    }

    /** Доля визитов офиса в сутки: закрытая ещё приёмная отдаёт свою долю Москве. */
    static double officeShare(HouseRules house, HouseRules.Office o, LocalDate day) {
        if (!o.openOn(day)) return 0;
        double s = OFFICE_SHARE[o.id() - 1];
        if (o.code().equals("msk")) {
            for (HouseRules.Office x : house.offices()) if (!x.openOn(day)) s += OFFICE_SHARE[x.id() - 1];
        }
        return s;
    }

    static Dept mainInterest(ClientBase c, int id) {
        Dept best = Dept.PAINTING;
        float bw = -1;
        for (Dept d : Dept.values()) {
            float w = c.interest(id, d);
            if (w > bw) {
                bw = w;
                best = d;
            }
        }
        return best;
    }

    static LocalDate workday(LocalDate d, int plus) {
        LocalDate x = d;
        int k = 0;
        while (k < plus) {
            x = x.plusDays(1);
            if (x.getDayOfWeek().getValue() <= 5) k++;
        }
        return x;
    }

    /**
     * Скрытая стоимость рынка V, копейки: медиана отдела × категория × уровень мастера × атрибуция × состояние ×
     * логнормальный шум.
     */
    static long value(HouseRules house, Dept d, Texts.ItemText t, char tier, Rng r) {
        double v = DEPT_MEDIAN_RUB[d.ordinal()];
        HouseRules.Category c = house.category(t.categoryId());
        v *= categoryFactor(c.code());
        v *= switch (tier) {
            case 'A' -> 12;
            case 'B' -> 3;
            case 'C' -> 1.2;
            default -> 0.6;
        };
        int a = Arrays.asList(ATTRIBUTIONS).indexOf(t.attribution());
        v *= t.makerId() == 0 || a < 0 ? ATTR_FACTOR[ATTRIBUTIONS.length - 1] : ATTR_FACTOR[a];
        v *= switch (t.conditionLevel()) {
            case 1 -> 1.25;
            case 2 -> 1.0;
            case 3 -> 0.8;
            case 4 -> 0.55;
            default -> 0.35;
        };
        v *= Math.max(1, t.pieces() / 3.0);
        v *= Math.exp(r.gaussian() * 1.05);
        return Math.max(300_00L, Math.round(Math.min(v, 600_000_000) * 100));
    }

    static double categoryFactor(String code) {
        if (code.contains("watch")) return 1.6;
        if (code.contains("map")) return 1.3;
        if (code.contains("print") || code.contains("postcard")) return 0.5;
        if (code.contains("drawing") || code.contains("watercolo")) return 0.7;
        if (code.contains("stamp")) return 0.6;
        if (code.contains("manuscript") || code.contains("autograph")) return 1.2;
        if (code.contains("furniture")) return 1.3;
        return 1.0;
    }

    // ---------------------------------------------------------------- Intake

    @Override
    public LocalDate firstDay() {
        return first;
    }

    @Override
    public LocalDate lastDay() {
        return last;
    }

    @Override
    public int size() {
        return n;
    }

    @Override
    public int countReceivedBefore(long at) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int m = (lo + hi + 1) >>> 1;
            if (received[m] < at) lo = m;
            else hi = m - 1;
        }
        return lo;
    }

    @Override
    public Item item(int id) {
        if (id < 1 || id > n) throw new IllegalArgumentException("нет вещи " + id);
        return new Item(id, key[id], Dept.byId(dept[id]), category[id], maker[id], tier[id],
                ATTRIBUTIONS[attribution[id]], visitKey[id], consignor[id], office[id], specialist[id], received[id],
                decided[id], decline[id] == 0, decline[id] == 0 ? null : DECLINE[decline[id] - 1], ready[id], value[id]);
    }

    @Override
    public List<Visit> visits(LocalDate day) {
        long di = day.toEpochDay() - first.toEpochDay();
        if (di < 0 || di >= dayFirstVisit.length - 1) return List.of();
        return visits.subList((int) dayFirstVisit[(int) di], (int) dayFirstVisit[(int) di + 1]);
    }

    @Override
    public int[] ready(Dept d, long fromAt, long toAt) {
        long[] at = readyAt[d.ordinal()];
        int lo = lower(at, fromAt), hi = lower(at, toAt);
        return Arrays.copyOfRange(readyIds[d.ordinal()], lo, Math.max(lo, hi));
    }

    private static int lower(long[] a, long x) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (a[m] < x) lo = m + 1;
            else hi = m;
        }
        return lo;
    }

    @Override
    public Dept dept(int id) {
        return Dept.byId(dept[id]);
    }

    @Override
    public int categoryId(int id) {
        return category[id];
    }

    @Override
    public long valueKop(int id) {
        return value[id];
    }

    @Override
    public long readyAt(int id) {
        return ready[id];
    }

    @Override
    public int consignorId(int id) {
        return consignor[id];
    }

    /**
     * Выбор комитента визита: по офису — накопленный вес по комитентам базы ({@code id} по возрастанию): торговцы × 6,
     * учреждения × 3; «свой» офис по округу города × 6, Москва — × 2 для всех. Только зарегистрированные раньше визита.
     */
    static final class ConsignorPicker {
        private final ClientBase clients;
        private final int[] ids;
        private final double[][] cum;

        ConsignorPicker(ClientBase clients, HouseRules house) {
            this.clients = clients;
            int k = 0;
            for (int id = 1; id <= clients.size(); id++) if (clients.consignor(id)) k++;
            ids = new int[k];
            k = 0;
            for (int id = 1; id <= clients.size(); id++) if (clients.consignor(id)) ids[k++] = id;
            List<HouseRules.Office> offices = house.offices();
            cum = new double[offices.size()][k];
            for (int i = 0; i < k; i++) {
                int id = ids[i];
                double w = clients.dealer(id) ? 6 : clients.institution(id) ? 3 : 1;
                String home = homeOffice(house.city(clients.cityId(id)));
                for (int o = 0; o < offices.size(); o++) {
                    String code = offices.get(o).code();
                    double x = w * (code.equals(home) ? 6 : 1) * (code.equals("msk") ? 2 : 1);
                    cum[o][i] = (i == 0 ? 0 : cum[o][i - 1]) + x;
                }
            }
        }

        static String homeOffice(HouseRules.City c) {
            return switch (c.federalDistrict()) {
                case "northwest" -> "spb";
                case "volga" -> c.lon() > 47.5 ? "kzn" : "nnv";
                case "ural" -> "ekb";
                case "siberia" -> "nsk";
                case "far_east" -> "vvo";
                case "south", "north_caucasus" -> "krd";
                default -> "msk";
            };
        }

        int pick(int officeId, long before, Rng r) {
            int maxId = clients.countRegisteredBefore(before);
            int k = Arrays.binarySearch(ids, maxId);
            k = k >= 0 ? k + 1 : -k - 1;
            if (k == 0) return 0;
            double[] c = cum[officeId - 1];
            double x = r.nextDouble() * c[k - 1];
            int lo = 0, hi = k - 1;
            while (lo < hi) {
                int m = (lo + hi) >>> 1;
                if (c[m] > x) hi = m;
                else lo = m + 1;
            }
            return ids[lo];
        }
    }
}
