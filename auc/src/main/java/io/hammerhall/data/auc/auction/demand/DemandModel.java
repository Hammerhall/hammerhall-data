package io.hammerhall.data.auc.auction.demand;

import io.hammerhall.data.auc.auction.calendar.CalendarView;
import io.hammerhall.data.auc.auction.calendar.SaleSpec;
import io.hammerhall.data.auc.bid.BidKind;
import io.hammerhall.data.auc.bid.Bidder;
import io.hammerhall.data.auc.bid.Channel;
import io.hammerhall.data.auc.bid.IncrementTable;
import io.hammerhall.data.auc.bid.Strategy;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.ClientState;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Intake;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.Participants;
import io.hammerhall.data.auc.world.RegMode;
import io.hammerhall.data.auc.world.SalePlan;
import io.hammerhall.data.auc.world.StaffRole;
import io.hammerhall.data.auc.world.Via;
import io.hammerhall.data.core.gen.Planted;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.core.time.RuCalendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongFunction;

/**
 * Спрос на торги: кто придёт на каждый лот, с каким каналом, стратегией и максимумом;
 * регистрации; заочные и телефонные заявки; попытки, отклонённые до движка. Метод {@link #demand} не трогает общего
 * состояния — торги недели считаются параллельно; случайность — только от ключей (лот, клиент, регистрация).
 *
 * <h2>Участники лота</h2>
 * Число желающих — {@link Interest}; каждый — {@code ClientBase.sampleBuyer} (интерес к отделу × торгов в год, не
 * «спящий»; очень частые — реже, {@link #attend}), бюджет — не меньше половины нижней оценки. Своя цена — V × склонность
 * клиента × вкус ({@link Interest}), не выше бюджета; максимум — к сетке вниз.
 * Комитент лота, попавший в желающие, — попытка {@code own_lot}. Сговор: из одной группы — не больше одного на лот.
 * Подставной комитента (связь {@code shill}) приходит на его лоты со стратегией {@code SHILL}. Ценовая война — 2,1 %
 * онлайн- и 4 % живых лотов с двумя и больше желающими: двое {@code WAR} с ценой 3,2–6,5 × верхней оценки и только они.
 *
 * <h2>Регистрация</h2>
 * Одна на клиента и торги; 10 % — в день торгов, ещё 60 % — в остальные 72 ч до начала, 30 % — раньше (с открытия
 * ставок). Онлайн-торги — одобрение сразу; живые — сотрудник отдела работы с клиентами в течение суток. Отказ — по
 * снимку {@code ClientState} недели подачи: блокировка ({@code blocked}), просроченный счёт ({@code unpaid_invoice}), нет
 * проверки ({@code no_verification}). Лимит (T11) — из снимка; максимумы участника в торгах делятся так, чтобы их сумма не
 * превышала лимита (заложенное {@code bid_over_limit} — без деления). Дорогие лоты (от 3 млн ₽) — допуск через 3 рабочих
 * дня и депозит 10 % нижней оценки. Без ставок регистрируется ещё ≈ треть к числу участников.
 *
 * <h2>Живые торги: способ участия</h2>
 * Телефон — склонность клиента и лот от 30 000 ₽ (заявка до 18:00 накануне, 85 % — дозвонились, иначе страховочная «из
 * книги»), зал — склонность к залу, заочно — треть доли максимумов, остальное — онлайн (четверть — через партнёрскую
 * площадку). Заочная подаётся не позже чем за 2 ч до начала, 60 % — в последние 48 ч; 3 % отзывают.
 */
public final class DemandModel {
    static final long DAY = 86_400, HOUR = 3_600, MSK_OFFSET = 3 * HOUR;
    /**
     * Ценовая война (двое {@code WAR}, своя цена 3,2–6,5 × верхней) — доля лотов с двумя и больше желающими (войн —
     * 1,5 % проданных). Онлайн пара воюющих в бою отвечает за секунды и доходит до узора войны почти всегда (99 % пар,
     * дошедших до торгов с обоими максимумами от 3× верхней); но до торгов парой доходят ≈ 2/3 выпавших войн — у 27 % до
     * движка доходит не двое (один отсеян спросом раньше), у 7 % пар максимум урезан лимитом регистрации ниже 3×
     * верхней. Отсюда 0,021: в full войн 1,5 % проданных, продлений на продлённый лот — 2,3 (каждая война даёт их ≈ 10).
     * На живых война доходит до конца.
     */
    static final double WAR_SHARE_TIMED = 0.021, WAR_SHARE_LIVE = 0.04;
    static final double SHILL_SHOW = 0.6, OWN_LOT_TRY = 0.5;
    /**
     * Попыток найти желающего на живой лот (онлайн — 6): живой каталог — лучшие вещи отдела, у дорогого лота мало клиентов
     * с бюджетом от половины нижней оценки — их ищут дольше (у коллекционных живых медиана нижней оценки ≈ 3 млн ₽).
     */
    static final int LIVE_TRIES = 26;
    static final double EXTRA_REGISTRANTS = 0.45;
    static final double PLANT_OVER_LIMIT = 0.0005;
    static final double OFF_INCREMENT = 0.003, NOT_OPEN = 0.01, OVER_LIMIT_TRY = 0.2;
    /**
     * Живые торги: способ участия из склонностей клиента — телефон (× {@code MODE_PHONE}, только при лоте от 30 000 ₽; у
     * лота от 1 млн ₽ — × {@code MODE_PHONE_MILLION}), зал (× {@code MODE_ROOM}), заочно (доля максимумов ×
     * {@code MODE_ABSENTEE}), остальное — онлайн. Доли каналов (ставки 68 / 12 / 12 / 8 %, победы 50 / 18 / 17 / 15 %,
     * от 1 млн ₽ по телефону — 40 %) — при живых торгах полного размера (в среднем 282 лота): у маленьких живых торгов
     * телефон дорогих лотов заметен меньше.
     */
    static final double MODE_PHONE = 0.092, MODE_PHONE_MILLION = 0.30,
            MODE_ROOM = 0.70,
            MODE_ABSENTEE = 0.071;
    /**
     * Живые: своя цена по способу участия — по телефону приходят серьёзные покупатели дорогих лотов, в зал — знатоки,
     * заочная — «до предела», онлайн — чуть осторожнее (доли побед: онлайн 50, зал 18, телефон 17, заочно 15 %).
     */
    static final double LIVE_PHONE_TASTE = 1.70,
            LIVE_ROOM_TASTE = 1.06,
            LIVE_ONLINE_TASTE = 0.72,
            LIVE_ABSENTEE_TASTE = 1.95;
    static final double PHONE_CONNECTED = 0.85, PHONE_INSURANCE = 0.6, ABSENTEE_CANCEL = 0.03, RANDOM_NO_VERIFICATION = 0.004;
    /**
     * Участник уже зарегистрирован на торги — берёт ещё лот (≈ 1,6 лота на регистрацию). Реже повторы — больше торгов на
     * участника в год (медиана держится ≤ 3) и больше регистраций (0,22 — 5,2 млн в full, 0,30 — 4,9 млн).
     */
    static final double POOL_REUSE = 0.30;
    /**
     * Живые: реже — живой каталог дорогой, лимит участника (T11) делится на его лоты торгов; в full живые торги — 282 лота
     * в среднем, участник с повторами набирает их больше, чем в малых профилях.
     */
    static final double LIVE_POOL_REUSE = 0.20;
    /**
     * Кого из участников торгов берут на следующий лот — по числу торгов в год (у очень частых — сглажено, {@link #attend}),
     * торговцев — с множителем {@code DEALER_REUSE}: частые участники и
     * торговцы ставят на многие лоты торгов (1 % участников выигрывает ≈ 25 % лотов, участников ≈ 2,5 на покупателя).
     */
    static final double DEALER_REUSE = 1.8;
    /** Очень частые участники приходят не на каждый лот: см. {@link #attend}. */
    static final double HEAVY_FROM = 12, HEAVY_ALPHA = 0.70;
    /**
     * Онлайн: участник следит за концом лота и отвечает за секунды (в движке — {@code WAR}); отсюда поздние ставки и
     * продления (15 % ставок — в последние 2 мин, продление — у 30 % лотов со ставками). Снайперы (черта клиента) —
     * всегда {@code SNIPER}.
     */
    static final double LATE_RESPONDER = 0.105;
    /** У участника с самой высокой своей ценой на лоте — чаще: будущий победитель торгуется в конце. */
    static final double LATE_TOP = 0.61;
    /**
     * Без проверки, заблокированный или с просроченным счётом всё же пробует зарегистрироваться (и получает отказ); прочие
     * такие на торги не приходят. Отказов — ≈ 2 % регистраций.
     */
    static final double TRY_ANYWAY = 0.04;

    private final HouseRules house;
    private final ClientBase clients;
    private final Intake intake;
    private final CalendarView cal;
    private final IncrementTable inc;
    private final RandomStreams.Stream st, rej;
    private final RuCalendar ru = new RuCalendar();
    private final Planted planted;
    private final Map<Integer, int[]> shillsOf = new HashMap<>();

    public DemandModel(HouseRules house, ClientBase clients, Intake intake, CalendarView cal, RandomStreams streams,
                       Planted planted) {
        this.house = house;
        this.clients = clients;
        this.intake = intake;
        this.cal = cal;
        this.inc = house.increments();
        this.st = streams.stream("auction.demand");
        this.rej = streams.stream("auction.reject");
        this.planted = planted;
        Map<Integer, List<Integer>> m = new TreeMap<>();
        for (ClientBase.Link l : clients.links()) {
            if (l.kind().equals("shill")) m.computeIfAbsent(l.otherId(), k -> new ArrayList<>()).add(l.clientId());
        }
        for (Map.Entry<Integer, List<Integer>> e : m.entrySet()) {
            shillsOf.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
    }

    /** Итог спроса торгов: участники по лотам плана, регистрации, заявки до торгов. */
    public record SaleDemand(List<Participants> lots, List<RegDraft> registrations, List<OrderDraft> orders) {
    }

    /** Намерение клиента на лот (до деления лимита). */
    private static final class Intent {
        final int lot;
        final int clientId;
        final long value;
        final Strategy forced;
        boolean top;

        Intent(int lot, int clientId, long value, Strategy forced) {
            this.lot = lot;
            this.clientId = clientId;
            this.value = value;
            this.forced = forced;
        }
    }

    // ---------------------------------------------------------------- время

    static long mskDayStart(long t) {
        return Math.floorDiv(t + MSK_OFFSET, DAY) * DAY - MSK_OFFSET;
    }

    /** Понедельник 00:00 по Москве недели момента. */
    public static long monday(long t) {
        long day = Math.floorDiv(t + MSK_OFFSET, DAY);
        long dow = Math.floorMod(day + 3, 7); // 1970-01-01 — четверг: 0 — понедельник
        return (day - dow) * DAY - MSK_OFFSET;
    }

    private long plusWorkdays(long t, int n) {
        LocalDate d = Period.localDate(t, HouseRules.ZONE);
        int k = 0;
        while (k < n) {
            d = d.plusDays(1);
            if (ru.isWorking(d)) k++;
        }
        return t + (d.toEpochDay() - Period.localDate(t, HouseRules.ZONE).toEpochDay()) * DAY;
    }

    // ---------------------------------------------------------------- спрос торгов

    /**
     * Спрос торгов. {@code states} — снимок {@code ClientState} на понедельник недели (секунды); {@code null} в ответе —
     * без ограничений.
     */
    public SaleDemand demand(SalePlan plan, LongFunction<ClientState> states) {
        int saleId = plan.sale().id();
        SaleSpec spec = cal.spec(saleId);
        boolean live = spec.live();
        Dept dept = plan.sale().dept();
        long opens = spec.biddingOpensAt(), start = spec.startsAt();
        List<SalePlan.Lot> lots = plan.lots();
        int nl = lots.size();
        Map<Integer, RegDraft> regs = new TreeMap<>();
        Map<Integer, List<Intent>> byClient = new TreeMap<>();
        List<List<Participants.Rejected>> rejected = new ArrayList<>(nl);
        for (int i = 0; i < nl; i++) rejected.add(new ArrayList<>());
        long[] sessionStart = new long[plan.sessions().size() + 1];
        for (SalePlan.Session s : plan.sessions()) sessionStart[s.no()] = s.startsAt();

        // 1. желающие по лотам; участник торгов охотно берёт ещё лоты тех же торгов
        List<Integer> pool = new ArrayList<>();
        List<Double> poolCum = new ArrayList<>();
        Set<Integer> poolSet = new HashSet<>();
        for (int i = 0; i < nl; i++) {
            SalePlan.Lot lot = lots.get(i);
            Rng r = st.at(lot.key());
            long lotEnd = live ? sessionStart[lot.sessionNo()] : lot.closesAtPlanned();
            if (lot.withdrawnAt() > 0) {
                if (r.chance(0.5)) {
                    int c = clients.sampleBuyer(dept, lot.withdrawnAt(), r);
                    if (c > 0 && c != lot.consignorId() && eligible(c, lot.withdrawnAt())) {
                        RegDraft g = register(regs, spec, c, Math.min(lotEnd, lot.withdrawnAt() + DAY), states, r);
                        if (g == null) continue;
                        long at = lot.withdrawnAt() + 60 + r.nextInt((int) Math.max(61, Math.min(lotEnd, lot.withdrawnAt() + 2 * DAY) - lot.withdrawnAt()));
                        if (g.approved() && at > g.decidedAt) {
                            rejected.get(i).add(new Participants.Rejected(at * 1000 + r.nextInt(1000), g.key, c,
                                    live ? Channel.ONLINE : Channel.ONLINE, BidKind.MANUAL, lot.startKop(), "lot_withdrawn"));
                        }
                    }
                }
                continue;
            }
            long value = intake.valueKop(lot.itemId());
            double mid = (lot.lowKop() + lot.highKop()) / 2.0;
            int n = Interest.unnoticed(dept.ordinal(), live, r) ? 0
                    : Interest.count(Interest.lambda(dept.ordinal(), live, value, mid, lot.highKop()), r);
            boolean war = n >= 2 && r.chance(live ? WAR_SHARE_LIVE : WAR_SHARE_TIMED);
            // ценовая война — двое и только двое: прочие желающие на этот лот не приходят
            if (war) n = 2;
            Set<Integer> on = new HashSet<>();
            Set<Integer> rings = new HashSet<>();
            int got = 0;
            for (int j = 0; j < n; j++) {
                int c = 0;
                for (int t = 0, tries = live ? LIVE_TRIES : 6; t < tries && c == 0; t++) {
                    boolean reuse = !pool.isEmpty() && r.chance(live ? LIVE_POOL_REUSE : POOL_REUSE);
                    int x = reuse ? pool.get(pick(poolCum, r)) : clients.sampleBuyer(dept, lotEnd, r);
                    if (x == 0 || on.contains(x)) continue;
                    if (!reuse && !r.chance(attend(x))) continue;
                    if (!eligible(x, lotEnd) && !r.chance(TRY_ANYWAY)) continue;
                    if (clients.budgetKop(x) < lot.lowKop() / 2 && !(war && got < 2)) continue;
                    int ring = clients.ring(x);
                    if (ring != 0 && rings.contains(ring)) continue;
                    c = x;
                }
                if (c == 0) continue;
                if (c == lot.consignorId()) {
                    if (r.chance(OWN_LOT_TRY)) {
                        RegDraft g = register(regs, spec, c, lotEnd, states, r);
                        if (g != null && g.approved()) {
                            long at = Math.max(g.decidedAt + 60, opens) + r.nextInt((int) Math.max(60, Math.min(DAY, lotEnd - Math.max(g.decidedAt, opens))));
                            rejected.get(i).add(new Participants.Rejected(at * 1000 + r.nextInt(1000), g.key, c,
                                    live ? Channel.ONLINE : Channel.ONLINE, BidKind.MANUAL, lot.startKop(), "own_lot"));
                        }
                    }
                    continue;
                }
                on.add(c);
                if (poolSet.add(c)) {
                    pool.add(c);
                    double w = Math.max(0.5, clients.salesPerYear(c)) * attend(c) * (clients.dealer(c) ? DEALER_REUSE : 1.0);
                    poolCum.add((poolCum.isEmpty() ? 0 : poolCum.get(poolCum.size() - 1)) + w);
                }
                if (clients.ring(c) != 0) rings.add(clients.ring(c));
                Rng p = st.at(lot.key(), c);
                long v;
                Strategy forced = null;
                if (war && got < 2) {
                    v = (long) (lot.highKop() * p.uniform(3.2, 6.5));
                    forced = Strategy.WAR;
                } else {
                    v = Math.min((long) Interest.valuation(value, Interest.affinity(st, c, dept.ordinal(), clients.salesPerYear(c),
                            (lotEnd - clients.registeredAt(c)) / (365.25 * DAY)), p), clients.budgetKop(c));
                }
                got++;
                byClient.computeIfAbsent(c, k -> new ArrayList<>()).add(new Intent(i, c, v, forced));
            }
            int[] sh = shillsOf.get(lot.consignorId());
            if (sh != null && lot.reserveKop() > 0 && !war) {
                for (int s : sh) {
                    if (on.contains(s) || clients.registeredAt(s) >= lotEnd || !r.chance(SHILL_SHOW)) continue;
                    long cap = (long) (Math.max(lot.reserveKop(), lot.lowKop()) * r.uniform(0.9, 1.4));
                    byClient.computeIfAbsent(s, k -> new ArrayList<>()).add(new Intent(i, s, cap, Strategy.SHILL));
                }
            }
        }

        // самая высокая своя цена лота
        {
            long[] best = new long[nl];
            Intent[] who = new Intent[nl];
            for (List<Intent> l : byClient.values()) {
                for (Intent x : l) {
                    if (x.forced == null && (who[x.lot] == null || x.value > best[x.lot])) {
                        best[x.lot] = x.value;
                        who[x.lot] = x;
                    }
                }
            }
            for (Intent x : who) if (x != null) x.top = true;
        }

        // 2. регистрации участников и деление лимита
        List<List<Participants.Participant>> bidders = new ArrayList<>(nl);
        for (int i = 0; i < nl; i++) bidders.add(new ArrayList<>());
        List<OrderDraft> orders = new ArrayList<>();
        for (Map.Entry<Integer, List<Intent>> e : byClient.entrySet()) {
            int c = e.getKey();
            List<Intent> in = e.getValue();
            long firstEnd = Long.MAX_VALUE;
            for (Intent x : in) {
                SalePlan.Lot lot = lots.get(x.lot);
                firstEnd = Math.min(firstEnd, live ? sessionStart[lot.sessionNo()] : lot.closesAtPlanned());
            }
            Rng rr = st.at(Keys.registration(saleId, c));
            RegDraft g = regs.get(c);
            if (g == null) g = register(regs, spec, c, firstEnd, states, rr, in, lots);
            if (g == null) continue;
            long[] max = new long[in.size()];
            long sum = 0;
            double chan = !live ? 1.0 : g.mode == RegMode.ABSENTEE ? LIVE_ABSENTEE_TASTE : g.mode == RegMode.PHONE ? LIVE_PHONE_TASTE
                    : g.mode == RegMode.ROOM ? LIVE_ROOM_TASTE : LIVE_ONLINE_TASTE;
            for (int k = 0; k < in.size(); k++) {
                max[k] = inc.floor(in.get(k).forced != null ? in.get(k).value : (long) (in.get(k).value * chan));
                sum += max[k];
            }
            boolean plantOver = false;
            if (g.limit > 0 && sum > g.limit && in.size() >= 2 && rr.chance(PLANT_OVER_LIMIT)) {
                plantOver = true;
                planted.add("bid_over_limit", g.key);
            }
            if (g.limit > 0 && sum > g.limit && !plantOver) {
                for (int k = 0; k < in.size(); k++) {
                    long share = inc.floor((long) ((double) max[k] * g.limit / sum));
                    if (share < max[k] && rr.chance(OVER_LIMIT_TRY) && (!live || g.mode == RegMode.ONLINE)) {
                        SalePlan.Lot lot = lots.get(in.get(k).lot);
                        long over = inc.ceil(Math.max(g.limit + 1, (long) (max[k] * 1.02)));
                        long t0 = Math.max(g.decidedAt, opens) + 60;
                        long t1 = live ? sessionStart[lot.sessionNo()] : lot.closesAtPlanned() - 60;
                        if (t1 > t0 && over > g.limit) {
                            long at = t0 + (long) ((t1 - t0) * rr.uniform(0.5, 0.98));
                            if (live) at = Math.max(t0, t1 - 600);
                            rejected.get(in.get(k).lot).add(new Participants.Rejected(at * 1000 + rr.nextInt(1000), g.key, c,
                                    Channel.ONLINE, BidKind.MANUAL, over, "over_limit"));
                        }
                    }
                    max[k] = share;
                }
            }
            for (int k = 0; k < in.size(); k++) {
                Intent x = in.get(k);
                SalePlan.Lot lot = lots.get(x.lot);
                participate(spec, plan, lot, x, max[k], g, rr, sessionStart, bidders.get(x.lot), rejected.get(x.lot), orders);
            }
        }

        // 3. регистрации без ставок
        int withLots = byClient.size();
        int extra = (int) StrictMath.round(withLots * EXTRA_REGISTRANTS);
        Rng rx = st.at(spec.key(), 1);
        for (int k = 0; k < extra; k++) {
            int c = clients.sampleBuyer(dept, start, rx);
            if (c == 0 || regs.containsKey(c) || !eligible(c, start) && !rx.chance(TRY_ANYWAY)) continue;
            register(regs, spec, c, start, states, st.at(Keys.registration(saleId, c)));
        }

        // 4. таблички: по моменту регистрации, в диапазоне способа
        List<RegDraft> list = new ArrayList<>(regs.values());
        list.sort((a, b) -> a.registeredAt != b.registeredAt ? Long.compare(a.registeredAt, b.registeredAt) : Long.compare(a.key, b.key));
        int room = 0, book = 0, onl = 0;
        for (RegDraft g : list) {
            if (!live) g.paddle = 10_000 + onl++;
            else if (g.mode == RegMode.ROOM) g.paddle = 1 + room++;
            else if (g.mode == RegMode.ONLINE) g.paddle = 10_000 + onl++;
            else g.paddle = 2_000 + book++;
        }
        if (onl > 90_000 || room > 1_999 || book > 1_000) {
            throw new IllegalStateException(plan.sale().code() + ": таблички кончились (" + room + ", " + book + ", " + onl + ")");
        }
        List<Participants> out = new ArrayList<>(nl);
        for (int i = 0; i < nl; i++) {
            List<Participants.Rejected> rj = rejected.get(i);
            rj.sort((a, b) -> a.atMs() != b.atMs() ? Long.compare(a.atMs(), b.atMs()) : Long.compare(a.regKey(), b.regKey()));
            out.add(new Participants(lots.get(i).key(), bidders.get(i), rj));
        }
        orders.sort((a, b) -> a.placedAtMs() != b.placedAtMs() ? Long.compare(a.placedAtMs(), b.placedAtMs()) : Long.compare(a.key(), b.key()));
        return new SaleDemand(out, list, orders);
    }

    /** Участник на лоте: движку или отказ до движка; заявки до торгов (живые). */
    private void participate(SaleSpec spec, SalePlan plan, SalePlan.Lot lot, Intent x, long max, RegDraft g, Rng rr,
                             long[] sessionStart, List<Participants.Participant> out, List<Participants.Rejected> rejected,
                             List<OrderDraft> orders) {
        boolean live = spec.live();
        int c = x.clientId;
        Rng p = st.at(lot.key(), c);
        long lotStart = live ? sessionStart[lot.sessionNo()] : lot.opensAt();
        long lotEnd = live ? sessionStart[lot.sessionNo()] : lot.closesAtPlanned();
        long tryAt = Math.max(g.registeredAt + 30, live ? lotStart - 3_600 : (g.registeredAt + lotEnd) / 2);
        if (!g.approved() || g.decidedAt >= lotEnd) {
            if (p.chance(0.6) && tryAt < lotEnd) {
                String reason = "blocked".equals(g.rejectReason) ? "blocked" : "not_registered";
                rejected.add(new Participants.Rejected(tryAt * 1000 + p.nextInt(1000), g.key, c, Channel.ONLINE,
                        BidKind.MANUAL, lot.startKop(), reason));
            }
            return;
        }
        long blockAt = clients.otherBlockAt(c);
        if (blockAt > g.decidedAt && blockAt < lotEnd) {
            long at = blockAt + 60 + p.nextInt(3_600);
            if (at < lotEnd) {
                rejected.add(new Participants.Rejected(at * 1000 + p.nextInt(1000), g.key, c, Channel.ONLINE, BidKind.MANUAL,
                        lot.startKop(), "blocked"));
            }
            return;
        }
        boolean premiumLot = lot.lowKop() >= HouseRules.PREMIUM_LOT_FROM;
        if (premiumLot && (g.premiumAt == 0 || g.premiumAt >= (live ? lotStart : lotEnd - 3_600))) {
            long at = Math.max(g.decidedAt + 60, Math.min(tryAt, lotEnd - 60));
            if (at < lotEnd) {
                rejected.add(new Participants.Rejected(at * 1000 + p.nextInt(1000), g.key, c, Channel.ONLINE, BidKind.MANUAL,
                        lot.startKop(), "premium_not_approved"));
            }
            return;
        }
        if (max < lot.startKop()) return;
        long readyMs = Math.max(Math.max(g.decidedAt, premiumLot ? g.premiumAt : 0), live ? spec.publishedAt() : lot.opensAt()) * 1000
                + p.nextInt(1000);
        Strategy own = clients.strategy(c);
        Strategy strat = x.forced != null ? x.forced
                : own == Strategy.SNIPER ? Strategy.SNIPER
                : !live && p.chance(x.top ? LATE_TOP : LATE_RESPONDER) ? Strategy.WAR
                : p.chance(clients.maxShare(c)) ? Strategy.MAX : own;
        Via bidVia = g.via == Via.APP || g.via == Via.PARTNER_PLATFORM ? g.via : Via.SITE;
        if (!live) {
            long from = Math.max(readyMs, lot.opensAt() * 1000);
            out.add(new Participants.Participant(g.key, c, new Bidder(g.key, Channel.ONLINE, strat, max, from), bidVia, 0, 0, false));
            if (p.chance(OFF_INCREMENT) && strat != Strategy.SHILL) {
                long at = Math.max(from / 1000 + 60, lotEnd - (long) ((lotEnd - from / 1000) * p.uniform(0.05, 0.9)));
                long amt = lot.startKop() + (inc.step(lot.startKop()) > 100 ? 100 : 1);
                if (at < lotEnd) {
                    rejected.add(new Participants.Rejected(at * 1000 + p.nextInt(1000), g.key, c, Channel.ONLINE, BidKind.MANUAL,
                            amt, "off_increment"));
                }
            }
            return;
        }
        long saleStart = spec.startsAt();
        switch (g.mode) {
            case ROOM -> out.add(new Participants.Participant(g.key, c, Bidder.of(g.key, Channel.ROOM, strat, max), Via.OFFICE, 0, 0,
                    false));
            case PHONE -> {
                if (lot.lowKop() < HouseRules.PHONE_MIN_LOW) {
                    absentee(spec, lot, g, c, max, p, out, orders, saleStart);
                    return;
                }
                long dayBefore18 = mskDayStart(saleStart) - 6 * HOUR;
                long placed = Math.min(dayBefore18 - 60, g.decidedAt + 60 + (long) (Math.max(0, dayBefore18 - g.decidedAt - 120) * p.nextDouble()));
                if (placed <= g.registeredAt) placed = g.registeredAt + 1;
                long placedMs = placed * 1000 + p.nextInt(1000);
                boolean connected = p.chance(PHONE_CONNECTED);
                long insurance = p.chance(PHONE_INSURANCE) ? inc.floor((long) (max * p.uniform(0.75, 1.0))) : 0;
                if (insurance > 0 && insurance < lot.startKop()) insurance = 0;
                LocalDate day = Period.localDate(saleStart, HouseRules.ZONE);
                HouseRules.Staff clerk = house.pick(StaffRole.PHONE_CLERK, null, plan.sale().officeId(), day, p);
                long key = Keys.order(lot.key(), c, 1);
                orders.add(new OrderDraft(key, lot.key(), g.key, c, "phone", insurance, placedMs, g.via == Via.PHONE ? Via.PHONE : Via.SITE,
                        connected ? "connected" : "no_answer", clerk == null ? 0 : clerk.id(), 0));
                Bidder b = connected ? Bidder.of(g.key, Channel.PHONE, strat == Strategy.WAR ? Strategy.WAR : Strategy.MAX, max)
                        : insurance > 0 ? Bidder.phoneStandIn(g.key, insurance, placedMs) : null;
                if (b != null) {
                    out.add(new Participants.Participant(g.key, c, b, Via.PHONE, key, clerk == null ? 0 : clerk.id(), !connected));
                }
            }
            case ABSENTEE -> absentee(spec, lot, g, c, max, p, out, orders, saleStart);
            default -> {
                out.add(new Participants.Participant(g.key, c, Bidder.of(g.key, Channel.ONLINE, strat, max), bidVia, 0, 0, false));
                if (p.chance(NOT_OPEN)) {
                    long at = Math.max(g.decidedAt + 60, lotStart - 60 - p.nextInt(7_200));
                    if (at < lotStart) {
                        rejected.add(new Participants.Rejected(at * 1000 + p.nextInt(1000), g.key, c, Channel.ONLINE,
                                BidKind.MANUAL, lot.startKop(), "not_open"));
                    }
                }
            }
        }
    }

    private void absentee(SaleSpec spec, SalePlan.Lot lot, RegDraft g, int c, long max, Rng p,
                          List<Participants.Participant> out, List<OrderDraft> orders, long saleStart) {
        long cutoff = saleStart - HouseRules.ABSENTEE_CUTOFF_S;
        long from = g.decidedAt + 30;
        if (from >= cutoff - 60) return;
        long placed = p.chance(0.6) && cutoff - 48 * HOUR > from ? cutoff - (long) (48 * HOUR * p.nextDouble())
                : from + (long) ((Math.max(from + 60, cutoff - 48 * HOUR) - from) * p.nextDouble());
        placed = Math.max(from, Math.min(cutoff - 1, placed));
        long placedMs = placed * 1000 + p.nextInt(1000);
        long key = Keys.order(lot.key(), c, 1);
        long cancelled = 0;
        if (p.chance(ABSENTEE_CANCEL)) cancelled = placed + 1 + (long) ((cutoff - placed - 1) * p.nextDouble());
        Via via = g.via == Via.OFFICE || g.via == Via.PHONE ? g.via : Via.SITE;
        orders.add(new OrderDraft(key, lot.key(), g.key, c, "absentee", max, placedMs, via, null, 0, cancelled));
        if (cancelled == 0) out.add(new Participants.Participant(g.key, c, Bidder.absentee(g.key, max, placedMs), via, key, 0, false));
    }

    /**
     * Частота у очень частых участников (от {@link #HEAVY_FROM} торгов в год — торговцы и коллекционеры) растёт медленнее
     * черты: не на каждый лот своего отдела они приходят — вероятность прийти (торгов в год / {@code HEAVY_FROM})
     * <sup>−{@link #HEAVY_ALPHA}</sup>. Так лоты-участия распределены шире (участников ≈ 2,5 на покупателя, 1 %
     * участников выигрывает ≈ 25 % лотов).
     */
    private double attend(int c) {
        double s = clients.salesPerYear(c);
        return s <= HEAVY_FROM ? 1.0 : StrictMath.pow(s / HEAVY_FROM, -HEAVY_ALPHA);
    }

    /** Индекс по накопленным весам. */
    private static int pick(List<Double> cum, Rng r) {
        double x = r.nextDouble() * cum.get(cum.size() - 1);
        int lo = 0, hi = cum.size() - 1;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (cum.get(m) > x) hi = m;
            else lo = m + 1;
        }
        return lo;
    }

    /** Клиент может участвовать: проверен и не заблокирован (не за неуплату) к концу лота. */
    private boolean eligible(int c, long at) {
        long v = clients.verifiedAt(c), b = clients.otherBlockAt(c);
        return v > 0 && v < at && (b == 0 || b >= at);
    }

    // ---------------------------------------------------------------- регистрация

    /** Дорогой лот для телефона: от 1 млн ₽ (у таких лотов телефон — 40 % побед). */
    static final long MILLION = 1_000_000_00L;

    /** Вместимость живых торгов: мест в зале, линий телефона, заочных поручений. */
    static final int ROOM_SEATS = 600, PHONE_LINES = 300, ABSENTEE_BOOK = 650;

    private static int count(Map<Integer, RegDraft> regs, RegMode m) {
        int n = 0;
        for (RegDraft g : regs.values()) if (g.mode == m) n++;
        return n;
    }

    private RegDraft register(Map<Integer, RegDraft> regs, SaleSpec spec, int c, long deadline, LongFunction<ClientState> states,
                              Rng r) {
        return register(regs, spec, c, deadline, states, r, List.of(), List.of());
    }

    private RegDraft register(Map<Integer, RegDraft> regs, SaleSpec spec, int c, long deadline, LongFunction<ClientState> states,
                              Rng r, List<Intent> in, List<SalePlan.Lot> lots) {
        RegDraft have = regs.get(c);
        if (have != null) return have;
        int saleId = cal.idOf(spec.key());
        long key = Keys.registration(saleId, c);
        boolean live = spec.live();
        long opens = spec.biddingOpensAt(), start = spec.startsAt();
        long low = Math.max(opens, clients.registeredAt(c) + 60);
        RegMode mode = RegMode.ONLINE;
        boolean phoneLot = false, premium = false, million = false;
        long premiumLow = 0;
        for (Intent x : in) {
            long lw = lots.get(x.lot).lowKop();
            if (lw >= HouseRules.PHONE_MIN_LOW) phoneLot = true;
            if (lw >= MILLION) million = true;
            if (lw >= HouseRules.PREMIUM_LOT_FROM) {
                premium = true;
                premiumLow += lw;
            }
        }
        double u = r.nextDouble();
        if (live) {
            double ph = phoneLot ? Math.min(0.9, clients.phoneShare(c) * (million ? MODE_PHONE_MILLION : MODE_PHONE)) : 0,
                    rm = clients.roomShare(c) * MODE_ROOM,
                    ab = clients.maxShare(c) * MODE_ABSENTEE;
            mode = u < ph ? RegMode.PHONE : u < ph + rm ? RegMode.ROOM : u < ph + rm + ab ? RegMode.ABSENTEE : RegMode.ONLINE;
            boolean forced = false;
            for (Intent x : in) forced |= x.forced != null;
            if (forced && (mode == RegMode.ABSENTEE || mode == RegMode.PHONE)) mode = RegMode.ONLINE;
            // вместимость торгов (у крупных живых торгов желающих «в зале» — тысячи): мест в зале, линий
            // телефона и заочных поручений — не больше, чем может принять дом; кто не поместился — ставит онлайн.
            // Порядок регистраций в торгах детерминирован — и итог тоже.
            if (mode == RegMode.PHONE && count(regs, RegMode.PHONE) >= PHONE_LINES) mode = RegMode.ABSENTEE;
            if (mode == RegMode.ABSENTEE && count(regs, RegMode.ABSENTEE) >= ABSENTEE_BOOK) mode = RegMode.ONLINE;
            if (mode == RegMode.ROOM && count(regs, RegMode.ROOM) >= ROOM_SEATS) mode = RegMode.ONLINE;
        }
        long high = Math.min(deadline, start) - (live ? HOUR : 300);
        if (mode == RegMode.PHONE) high = Math.min(high, mskDayStart(start) - 6 * HOUR - 600);
        if (mode == RegMode.ABSENTEE) high = Math.min(high, start - HouseRules.ABSENTEE_CUTOFF_S - 3_600);
        if (high <= low) {
            high = low + 60;
            if (mode != RegMode.ONLINE) mode = RegMode.ONLINE;
        }
        // 10 % — в день торгов, ещё 60 % — в остальные 72 ч, 30 % — раньше
        long dayStart = mskDayStart(start), w72 = start - 72 * HOUR;
        double v = r.nextDouble();
        long a, b;
        if (v < 0.10) {
            a = dayStart;
            b = high;
        } else if (v < 0.70) {
            a = w72;
            b = Math.min(high, dayStart);
        } else {
            a = low;
            b = Math.min(high, w72);
        }
        a = Math.max(a, low);
        b = Math.min(b, high);
        if (b <= a) {
            a = low;
            b = high;
        }
        long at = a + (long) ((b - a) * r.nextDouble());
        Via via;
        double w = r.nextDouble();
        if (!live) via = w < 0.55 ? Via.SITE : Via.APP;
        else via = switch (mode) {
            case ROOM -> w < 0.5 ? Via.SITE : w < 0.8 ? Via.OFFICE : Via.PHONE;
            case PHONE -> w < 0.7 ? Via.PHONE : Via.SITE;
            case ABSENTEE -> w < 0.65 ? Via.SITE : w < 0.8 ? Via.OFFICE : Via.PHONE;
            default -> w < 0.25 ? Via.PARTNER_PLATFORM : w < 0.70 ? Via.SITE : Via.APP;
        };
        ClientState s = states.apply(monday(at));
        String reason = null;
        if (s != null && (s.blocked(c) || clients.otherBlockAt(c) > 0 && clients.otherBlockAt(c) <= at)) reason = "blocked";
        else if (s != null && s.overdue(c)) reason = "unpaid_invoice";
        else if (clients.verifiedAt(c) == 0 || clients.verifiedAt(c) > at || live && r.chance(RANDOM_NO_VERIFICATION)) {
            reason = "no_verification";
        }
        if (("blocked".equals(reason) || "unpaid_invoice".equals(reason)) && !st.at(key, 7).chance(TRY_ANYWAY)) return null;
        long decided;
        int by = 0;
        if (!live) {
            decided = at + 1 + r.nextInt(30);
        } else {
            long latest = start - 1_800;
            decided = at + 600 + (long) (Math.max(0, Math.min(DAY - 1_200, latest - at - 600)) * r.nextDouble());
            if (decided >= latest) decided = Math.min(at + 120 + r.nextInt(1_080), Math.max(at + 60, latest));
            LocalDate day = Period.localDate(decided, HouseRules.ZONE);
            HouseRules.Staff staff = house.pick(StaffRole.CLIENT_SERVICE, null, cal.sale(saleId).officeId(), day, r);
            by = staff == null ? 0 : staff.id();
        }
        long limit = s == null ? 0 : s.bidLimitKop(c);
        long premiumAt = 0, deposit = 0;
        if (premium && reason == null) {
            premiumAt = plusWorkdays(decided, HouseRules.PREMIUM_APPROVAL_WORKDAYS);
            deposit = premiumLow / 10;
        }
        RegDraft g = new RegDraft(key, saleId, c, at, via, mode, reason == null ? "approved" : "rejected", decided, by, reason,
                limit, premiumAt, deposit);
        regs.put(c, g);
        return g;
    }
}
