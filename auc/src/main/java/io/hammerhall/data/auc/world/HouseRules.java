package io.hammerhall.data.auc.world;

import io.hammerhall.data.auc.bid.AuctionRules;
import io.hammerhall.data.auc.bid.IncrementTable;
import io.hammerhall.data.core.money.Tax;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * Правила дома ({@code HOUSE}, модуль {@code house}): то, что одинаково для генератора, движка, самопроверки и задач
 * кузен — шаги ставки, шкалы премии и комиссии, сборы, НДС, сроки — и справочники дома: отделы,
 * категории, города (вход {@code cities}), офисы, сотрудники. Строки таблиц {@code departments}, {@code categories},
 * {@code cities}, {@code offices}, {@code staff}, {@code increment_bands}, {@code fee_schedules}, {@code fee_tiers}
 * пишутся из этого объекта — числа в одном месте ({@link #standardIncrements()}, {@link #standardFees()}).
 *
 * <p>Деньги — копейки; ставки сборов — <b>тысячные доли процента</b> ({@code 23 %} = {@code 23_000}, {@code 0,1 %} =
 * {@code 100}): так {@code decimal(6,3)} таблицы {@code fee_schedules.rate_pct} считается без потерь.
 */
public final class HouseRules {
    // ---------------------------------------------------------------- числа дома

    /** Пояс бизнеса: сетка торгов, сроки оплаты, «дата торгов». */
    public static final ZoneId ZONE = Period.MOSCOW;
    /** Версия таблицы шагов и шкал «весь период». */
    public static final LocalDate RULES_FROM = LocalDate.of(2024, 9, 1);
    /** Смена шкалы премии {@code BP-2024} → {@code BP-2026} (по дате торгов). */
    public static final LocalDate BP_2026_FROM = LocalDate.of(2026, 9, 1);
    /** Лимит нового клиента на торги (T11): до двух оплаченных счетов. */
    public static final long NEW_CLIENT_LIMIT = 300_000_00L;
    /** Оплаченных счетов, после которых лимита нет (T11). */
    public static final int PAID_INVOICES_FOR_NO_LIMIT = 2;
    /** Дорогой лот (T13): нижняя оценка от 3 млн ₽ — одобрение за 3 рабочих дня и депозит 10 % нижней оценки. */
    public static final long PREMIUM_LOT_FROM = 3_000_000_00L;
    public static final int PREMIUM_APPROVAL_WORKDAYS = 3;
    /** Телефонные заявки — только у лотов с нижней оценкой от 30 000 ₽; заявка — до 18:00 накануне. */
    public static final long PHONE_MIN_LOW = 30_000_00L;
    /** Наименьший эстимейт (нижняя оценка): живые — 5 000 ₽, онлайн — 1 000 ₽. */
    public static final long LIVE_MIN_LOW = 5_000_00L, TIMED_MIN_LOW = 1_000_00L;
    /** Стартовая цена онлайн-лота — не меньше 500 ₽. */
    public static final long TIMED_MIN_START = 500_00L;
    /** Расширенная проверка клиента — при счёте от 1 млн ₽. */
    public static final long ENHANCED_CHECK_FROM = 1_000_000_00L;
    /** Дорогие живые торги — темп 40 лотов в час (нижняя оценка от 1 млн ₽). */
    public static final long EXPENSIVE_LOT_FROM = 1_000_000_00L;
    /** Срок оплаты — 10 календарных дней; отмена за неуплату — на 31-й день; выплата комитенту — на 35-й. */
    public static final int PAYMENT_DUE_DAYS = 10, CANCEL_DAY = 31, PAYOUT_DAY = 35;
    /** Оплачен позже 35-го дня — выплата через 5 рабочих дней после оплаты (M7). */
    public static final int PAYOUT_AFTER_PAYMENT_WORKDAYS = 5;
    /** Онлайн-волна: 5 лотов в группе, группа — каждые 60 с, с 19:00 по Москве; за вечер — не больше 1 200 лотов. */
    public static final int LOTS_PER_GROUP = 5, GROUP_INTERVAL_S = 60, WAVE_START_HOUR = 19, MAX_LOTS_PER_EVENING = 1200;
    /** Мягкое закрытие (T10): окно, продление, потолок — секунды. */
    public static final int EXTENSION_WINDOW_S = 120, EXTENSION_S = 120, EXTENSION_CAP_S = 3600;
    /** Живые торги: лотов в сессии — не больше 250; заочные — не позже чем за 2 ч до начала. */
    public static final int MAX_LOTS_PER_SESSION = 250, ABSENTEE_CUTOFF_S = 2 * 3600;
    /** Повтор ручной онлайн-ставки с тем же ключом в течение 10 мин — {@code duplicate} (T14). */
    public static final int DUPLICATE_WINDOW_S = 600;

    // ---------------------------------------------------------------- записи справочников

    /** Отдел ({@code departments}). */
    public record Department(Dept dept, String nameRu, String nameEn, int sortOrder) {
        public int id() {
            return dept.id();
        }

        public String code() {
            return dept.code();
        }
    }

    /**
     * Категория ({@code categories}) — дерево до 3 уровней; {@code parentId} 0 — верхний уровень отдела; {@code leaf} —
     * у вещи категория всегда лист.
     */
    public record Category(int id, Dept dept, int parentId, int level, String code, String nameRu, String nameEn,
                           boolean leaf) {
    }

    /** Город ({@code cities}) — строка входа {@code cities.tsv.gz} выпуска {@code sto-data-v1} как есть. */
    public record City(int id, String nameRu, String nameEn, String regionRu, String federalDistrict, double lat,
                       double lon, ZoneId zone, int population) {
    }

    /** Офис ({@code offices}); {@code closedOn} — {@code null}, если работает. */
    public record Office(int id, String code, String kind, int cityId, String nameRu, String nameEn,
                         boolean hasSaleroom, LocalDate openedOn, LocalDate closedOn) {
        public boolean openOn(LocalDate d) {
            return !d.isBefore(openedOn) && (closedOn == null || d.isBefore(closedOn));
        }
    }

    /** Сотрудник ({@code staff}); {@code dept} — {@code null}, если не за отделом; {@code leftOn} — {@code null}, работает. */
    public record Staff(int id, int officeId, Dept dept, StaffRole role, String firstName, String lastName,
                        LocalDate hiredOn, LocalDate leftOn) {
        public boolean activeOn(LocalDate d) {
            return !d.isBefore(hiredOn) && (leftOn == null || d.isBefore(leftOn));
        }
    }

    /**
     * Шкала или сбор ({@code fee_schedules}). Ровно одно из: {@code rateMilliPct} (единая ставка), {@code fixedKop}
     * (сумма за лот), ступени ({@link #tiers}, у {@code BP-2024}, {@code BP-2026}, {@code SC-2024}). {@code dept} —
     * {@code null} у всех, кроме {@code BP-COIN}; {@code validTo} — последний день (включительно) или {@code null}.
     */
    public record FeeSchedule(int id, String code, String kind, String basis, String appliesTo, Dept dept,
                              LocalDate validFrom, LocalDate validTo, Integer rateMilliPct, Long fixedKop,
                              String nameRu, List<FeeTier> tiers) {
        public FeeSchedule {
            tiers = List.copyOf(tiers);
            int set = (rateMilliPct != null ? 1 : 0) + (fixedKop != null ? 1 : 0) + (tiers.isEmpty() ? 0 : 1);
            if (set != 1) throw new IllegalArgumentException(code + ": ставка, сумма или ступени — ровно одно");
        }

        public boolean validOn(LocalDate d) {
            return !d.isBefore(validFrom) && (validTo == null || !d.isAfter(validTo));
        }

        public boolean tiered() {
            return !tiers.isEmpty();
        }
    }

    /** Ступень ({@code fee_tiers}): ставка — на часть суммы от {@code fromKop} (не включая) до начала следующей. */
    public record FeeTier(int scheduleId, int tierNo, long fromKop, int rateMilliPct) {
    }

    // ---------------------------------------------------------------- коды шкал и сборов

    public static final String BP_2024 = "BP-2024", BP_2026 = "BP-2026", BP_COIN = "BP-COIN", SC_2024 = "SC-2024",
            PLT_2024 = "PLT-2024", INS_2024 = "INS-2024", CAT_LIVE = "CAT-LIVE", CAT_TIMED = "CAT-TIMED",
            WDR_2024 = "WDR-2024", LATE_2024 = "LATE-2024", DEP_2024 = "DEP-2024";
    /** Скидка торговца на каждой ступени комиссии — 5 п. п. (M4). */
    public static final int DEALER_DISCOUNT_MILLI_PCT = 5_000;

    // ---------------------------------------------------------------- содержимое

    private final List<Department> departments;
    private final List<Category> categories;
    private final Map<Integer, Category> categoryById = new TreeMap<>();
    private final Map<String, Category> categoryByCode = new TreeMap<>();
    private final Map<Dept, List<Category>> leaves = new EnumMap<>(Dept.class);
    private final List<City> cities;
    private final Map<Integer, City> cityById = new TreeMap<>();
    private final List<Office> offices;
    private final List<Staff> staff;
    private final IncrementTable increments;
    private final AuctionRules auctionRules;
    private final List<FeeSchedule> fees;
    private final Map<String, FeeSchedule> feeByCode = new TreeMap<>();

    public HouseRules(List<Department> departments, List<Category> categories, List<City> cities, List<Office> offices,
                      List<Staff> staff, IncrementTable increments, List<FeeSchedule> fees) {
        this.departments = List.copyOf(departments);
        this.categories = List.copyOf(categories);
        this.cities = List.copyOf(cities);
        this.offices = List.copyOf(offices);
        this.staff = List.copyOf(staff);
        this.increments = increments;
        this.auctionRules = AuctionRules.house(increments);
        this.fees = List.copyOf(fees);
        for (Dept d : Dept.values()) leaves.put(d, new ArrayList<>());
        for (Category c : categories) {
            categoryById.put(c.id(), c);
            categoryByCode.put(c.code(), c);
            if (c.leaf()) leaves.get(c.dept()).add(c);
        }
        for (City c : cities) cityById.put(c.id(), c);
        for (FeeSchedule f : fees) feeByCode.put(f.code(), f);
        for (Dept d : Dept.values()) {
            if (leaves.get(d).isEmpty()) throw new IllegalArgumentException("у отдела " + d.code() + " нет категорий-листьев");
            leaves.put(d, List.copyOf(leaves.get(d)));
        }
    }

    // ---------------------------------------------------------------- торги

    /** Таблица шагов ({@code increment_bands}) — для движка и проверок сетки. */
    public IncrementTable increments() {
        return increments;
    }

    /** Правила движка: {@code AuctionRules.house(increments())} — мягкое закрытие, потолок, за продавца, до резерва, полшага. */
    public AuctionRules auctionRules() {
        return auctionRules;
    }

    // ---------------------------------------------------------------- справочники

    public List<Department> departments() {
        return departments;
    }

    public Department department(Dept d) {
        return departments.get(d.ordinal());
    }

    public List<Category> categories() {
        return categories;
    }

    public Category category(int id) {
        Category c = categoryById.get(id);
        if (c == null) throw new IllegalArgumentException("нет категории " + id);
        return c;
    }

    /** Категория по коду; {@code null} — нет такой. */
    public Category categoryByCode(String code) {
        return categoryByCode.get(code);
    }

    /** Листья дерева отдела — в порядке {@code id}. */
    public List<Category> leaves(Dept d) {
        return leaves.get(d);
    }

    public List<City> cities() {
        return cities;
    }

    public City city(int id) {
        City c = cityById.get(id);
        if (c == null) throw new IllegalArgumentException("нет города " + id);
        return c;
    }

    public List<Office> offices() {
        return offices;
    }

    public Office office(int id) {
        return offices.get(id - 1);
    }

    public Office office(String code) {
        for (Office o : offices) if (o.code().equals(code)) return o;
        throw new IllegalArgumentException("нет офиса " + code);
    }

    /** Все сотрудники в порядке {@code id} (по дате приёма). */
    public List<Staff> staff() {
        return staff;
    }

    public Staff staff(int id) {
        return staff.get(id - 1);
    }

    /**
     * Работающие на дату сотрудники роли; {@code dept} — {@code null}: любой отдел; {@code officeId} — 0: любой офис.
     * Порядок — по {@code id}.
     */
    public List<Staff> active(StaffRole role, Dept dept, int officeId, LocalDate on) {
        List<Staff> out = new ArrayList<>();
        for (Staff s : staff) {
            if (s.role() != role || !s.activeOn(on)) continue;
            if (dept != null && s.dept() != dept) continue;
            if (officeId != 0 && s.officeId() != officeId) continue;
            out.add(s);
        }
        return out;
    }

    /**
     * Выбрать работающего сотрудника роли ({@link #active}); если в отделе и офисе никого — сначала любой офис, потом
     * любой отдел. {@code null} — роли нет на дату вовсе. Случайность — от вызывающего ({@code ctx.stream(...).at(ключ)}).
     */
    public Staff pick(StaffRole role, Dept dept, int officeId, LocalDate on, Rng r) {
        List<Staff> l = active(role, dept, officeId, on);
        if (l.isEmpty() && officeId != 0) l = active(role, dept, 0, on);
        if (l.isEmpty() && dept != null) l = active(role, null, 0, on);
        return l.isEmpty() ? null : l.get(r.nextInt(l.size()));
    }

    // ---------------------------------------------------------------- деньги (M1–M7)

    public List<FeeSchedule> fees() {
        return fees;
    }

    public FeeSchedule fee(String code) {
        FeeSchedule f = feeByCode.get(code);
        if (f == null) throw new IllegalArgumentException("нет шкалы " + code);
        return f;
    }

    /** Шкала премии торгов (M1): у монет — {@code BP-COIN}, иначе — по дате торгов ({@code sales.starts_at} по Москве). */
    public FeeSchedule premiumSchedule(Dept d, LocalDate saleDate) {
        if (d == Dept.COINS) return fee(BP_COIN);
        return fee(saleDate.isBefore(BP_2026_FROM) ? BP_2024 : BP_2026);
    }

    /** Премия покупателя по шкале (M1), копейки: ступени — на части молотка; единая ставка — на весь. */
    public static long premium(FeeSchedule s, long hammerKop) {
        return s.tiered() ? tiered(s.tiers(), hammerKop, 0) : percent(hammerKop, s.rateMilliPct());
    }

    /**
     * Комиссия продавца (M4), копейки: договорная единая ставка ({@code flatMilliPct}, не {@code null}) или ступени
     * {@code SC-2024}, у торговца — на 5 п. п. ниже на каждой ступени.
     */
    public long commission(long hammerKop, boolean dealerDiscount, Integer flatMilliPct) {
        if (flatMilliPct != null) return percent(hammerKop, flatMilliPct);
        return tiered(fee(SC_2024).tiers(), hammerKop, dealerDiscount ? DEALER_DISCOUNT_MILLI_PCT : 0);
    }

    /** Сумма по ступеням: каждая ставка (минус {@code discountMilliPct}) — на свою часть; округление — один раз, половина вверх. */
    public static long tiered(List<FeeTier> tiers, long amountKop, int discountMilliPct) {
        long acc = 0; // копейки × тысячные доли процента
        for (int i = 0; i < tiers.size(); i++) {
            long lo = tiers.get(i).fromKop();
            long hi = i + 1 < tiers.size() ? tiers.get(i + 1).fromKop() : Long.MAX_VALUE;
            if (amountKop <= lo) break;
            long part = Math.min(amountKop, hi) - lo;
            acc = Math.addExact(acc, Math.multiplyExact(part, (long) Math.max(0, tiers.get(i).rateMilliPct() - discountMilliPct)));
        }
        return divHalfUp(acc, 100_000);
    }

    /** {@code kop × rate}, ставка — тысячные доли процента; до копейки, половина вверх. */
    public static long percent(long kop, int rateMilliPct) {
        return divHalfUp(Math.multiplyExact(kop, (long) rateMilliPct), 100_000);
    }

    /** Ставка НДС на дату торгов, %: 20 по 31.12.2025, 22 с 01.01.2026 ({@code core} {@link Tax#vatRate}). */
    public static int vatPct(LocalDate saleDate) {
        return Tax.vatRate(saleDate);
    }

    /** НДС сверху на сумму услуг дома (M2, M5): до копейки, половина вверх. */
    public static long vat(long baseKop, LocalDate saleDate) {
        return divHalfUp(Math.multiplyExact(baseKop, (long) vatPct(saleDate)), 100);
    }

    private static long divHalfUp(long a, long b) {
        if (a < 0) return -divHalfUp(-a, b);
        return (a + b / 2) / b;
    }

    /**
     * Срок оплаты счёта (M6): 23:59:59 по Москве дня «дата выставления по Москве + {@code dueDays}» — одно правило для
     * оркестра (черновик счёта) и денег; дней просрочки = дата оплаты по Москве − дата срока.
     */
    public static long dueAt(long issuedAt, int dueDays) {
        LocalDate d = Period.localDate(issuedAt, ZONE).plusDays(dueDays);
        return d.plusDays(1).atStartOfDay(ZONE).toEpochSecond() - 1;
    }

    // ---------------------------------------------------------------- стандартные числа дома

    /** Таблица шагов дома: ряд 1 — 3 — 6 — 10, 14 полос, одна версия на весь период. */
    public static IncrementTable standardIncrements() {
        return IncrementTable.rub(
                0, 50,
                1_000, 100,
                3_000, 250,
                6_000, 500,
                10_000, 1_000,
                30_000, 2_500,
                60_000, 5_000,
                100_000, 10_000,
                300_000, 25_000,
                600_000, 50_000,
                1_000_000, 100_000,
                3_000_000, 250_000,
                6_000_000, 500_000,
                10_000_000, 1_000_000);
    }

    /** Шкалы и сборы дома: 11 строк {@code fee_schedules}, 10 ступеней {@code fee_tiers}; {@code id} — по порядку. */
    public static List<FeeSchedule> standardFees() {
        List<FeeSchedule> out = new ArrayList<>();
        out.add(new FeeSchedule(1, BP_2024, "buyer_premium", "hammer", "all", null, RULES_FROM, BP_2026_FROM.minusDays(1),
                null, null, "Премия покупателя, шкала 2024", tiers(1, 0, 23_000, 2_000_000, 20_000, 20_000_000, 12_000)));
        out.add(new FeeSchedule(2, BP_2026, "buyer_premium", "hammer", "all", null, BP_2026_FROM, null,
                null, null, "Премия покупателя, шкала 2026", tiers(2, 0, 25_000, 2_000_000, 20_000, 20_000_000, 12_000)));
        out.add(new FeeSchedule(3, BP_COIN, "buyer_premium", "hammer", "all", Dept.COINS, RULES_FROM, null,
                17_000, null, "Премия покупателя, монеты и медали", List.of()));
        out.add(new FeeSchedule(4, SC_2024, "seller_commission", "hammer", "all", null, RULES_FROM, null,
                null, null, "Комиссия продавца", tiers(4, 0, 20_000, 30_000, 15_000, 300_000, 12_000, 3_000_000, 8_000)));
        out.add(new FeeSchedule(5, PLT_2024, "platform", "hammer", "live", null, RULES_FROM, null,
                3_500, null, "Сбор партнёрской площадки", List.of()));
        out.add(new FeeSchedule(6, INS_2024, "insurance", "hammer", "all", null, RULES_FROM, null,
                1_500, null, "Страхование проданного лота", List.of()));
        out.add(new FeeSchedule(7, CAT_LIVE, "catalogue", "lot", "live", null, RULES_FROM, null,
                null, 400_00L, "Каталог и фото, живые торги", List.of()));
        out.add(new FeeSchedule(8, CAT_TIMED, "catalogue", "lot", "timed", null, RULES_FROM, null,
                null, 150_00L, "Каталог и фото, онлайн-торги", List.of()));
        out.add(new FeeSchedule(9, WDR_2024, "withdrawal", "low_estimate", "all", null, RULES_FROM, null,
                12_000, null, "Снятие лота по просьбе комитента", List.of()));
        out.add(new FeeSchedule(10, LATE_2024, "late_payment", "invoice_total_per_day", "all", null, RULES_FROM, null,
                100, null, "Пени за просрочку оплаты, в день", List.of()));
        out.add(new FeeSchedule(11, DEP_2024, "deposit", "low_estimate", "all", null, RULES_FROM, null,
                10_000, null, "Депозит на дорогой лот", List.of()));
        return Collections.unmodifiableList(out);
    }

    /** Ступени: пары «от, ₽ — ставка, тысячные доли %». */
    private static List<FeeTier> tiers(int scheduleId, long... pairs) {
        List<FeeTier> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add(new FeeTier(scheduleId, i / 2 + 1, pairs[i] * 100, (int) pairs[i + 1]));
        }
        return out;
    }

    /** Отделы дома — названия по-русски и по-английски. */
    public static List<Department> standardDepartments() {
        return List.of(
                new Department(Dept.PAINTING, "Живопись и графика", "Paintings and Works on Paper", 1),
                new Department(Dept.JEWELRY, "Ювелирные украшения и часы", "Jewellery and Watches", 2),
                new Department(Dept.COINS, "Монеты и медали", "Coins and Medals", 3),
                new Department(Dept.BOOKS, "Книги, рукописи, карты", "Books, Manuscripts and Maps", 4),
                new Department(Dept.DECORATIVE, "Декоративно-прикладное искусство", "Decorative Arts", 5),
                new Department(Dept.COLLECTIBLES, "Коллекционные предметы", "Collectibles", 6));
    }

    /**
     * Офисы дома: Москва — главный офис и зал торгов, Санкт-Петербург — зал и приёмная, шесть приёмных;
     * Краснодар и Владивосток открыты внутри периода. {@code cityId} — город входа по русскому названию.
     */
    public static List<Office> standardOffices(ToIntFunction<String> cityIdByNameRu) {
        return List.of(
                new Office(1, "msk", "headquarters", cityIdByNameRu.applyAsInt("Москва"), "Москва — главный офис и зал торгов",
                        "Moscow — headquarters and saleroom", true, LocalDate.of(2006, 3, 1), null),
                new Office(2, "spb", "saleroom", cityIdByNameRu.applyAsInt("Санкт-Петербург"), "Санкт-Петербург — зал торгов",
                        "Saint Petersburg — saleroom", true, LocalDate.of(2011, 9, 5), null),
                new Office(3, "kzn", "intake", cityIdByNameRu.applyAsInt("Казань"), "Казань — приёмная",
                        "Kazan — consignment office", false, LocalDate.of(2016, 4, 4), null),
                new Office(4, "nnv", "intake", cityIdByNameRu.applyAsInt("Нижний Новгород"), "Нижний Новгород — приёмная",
                        "Nizhny Novgorod — consignment office", false, LocalDate.of(2018, 2, 12), null),
                new Office(5, "ekb", "intake", cityIdByNameRu.applyAsInt("Екатеринбург"), "Екатеринбург — приёмная",
                        "Yekaterinburg — consignment office", false, LocalDate.of(2017, 10, 2), null),
                new Office(6, "nsk", "intake", cityIdByNameRu.applyAsInt("Новосибирск"), "Новосибирск — приёмная",
                        "Novosibirsk — consignment office", false, LocalDate.of(2019, 5, 20), null),
                new Office(7, "krd", "intake", cityIdByNameRu.applyAsInt("Краснодар"), "Краснодар — приёмная",
                        "Krasnodar — consignment office", false, LocalDate.of(2026, 3, 2), null),
                new Office(8, "vvo", "intake", cityIdByNameRu.applyAsInt("Владивосток"), "Владивосток — приёмная",
                        "Vladivostok — consignment office", false, LocalDate.of(2026, 10, 1), null));
    }
}
