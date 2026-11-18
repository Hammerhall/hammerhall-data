package io.hammerhall.data.auc.people;

import io.hammerhall.data.auc.bid.Strategy;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Horizon;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * База клиентов: функция даты и {@code seed}. Регистрации — по суткам с 01.01.2012: ожидаемое число в
 * сутки — кривая роста с опорой на постоянную дату (01.01.2026; рост 15 % в год, сезон, будни), × масштаб профиля;
 * число в сутки и каждый клиент — от ключа (сутки, номер в сутках). {@code id} — по моменту регистрации, поэтому
 * продление дописывает клиентов в хвост, не трогая прежних. База строится до конца моделирования (срез + горизонт).
 *
 * <p>Скрытые черты — массивы примитивов (≈ 0,9 млн клиентов в {@code full}): интересы по отделам, частота торгов,
 * бюджет, узор поведения онлайн, доли каналов, надёжность и темп оплаты, «до какой поры покупает». Связи учётных
 * записей — подставные, сговор торговцев, повторная регистрация неплательщика — выбираются из базы правилом от ключа.
 */
public final class ClientPopulation implements ClientBase {
    /** Клиентов к 01.09.2027 в {@code full} (≈ 0,73 млн). */
    static final double FULL_AT_NOW = 730_000;
    static final LocalDate ANCHOR = LocalDate.of(2026, 1, 1);
    static final LocalDate NOW_DAY = LocalDate.of(2027, 9, 1);
    static final double GROWTH = 0.15;
    /** Новички активнее: вес участия при регистрации — × 3,5, спадает с возрастом учётной записи (полгода). */
    static final double NEW_BOOST = 3.5, NEW_DECAY_YEARS = 0.5;
    /** Сговор — торговцы, зарегистрированные до 2025 года (постоянный набор, не зависит от периода). */
    static final long RING_BEFORE = LocalDate.of(2025, 1, 1).atStartOfDay(HouseRules.ZONE).toEpochSecond();
    /** Доли главного интереса по отделам (в порядке {@link Dept}). */
    static final double[] DEPT_SHARE = {0.22, 0.19, 0.12, 0.14, 0.17, 0.16};

    /** Доли главного интереса по отделам (для подгонки смеси отделов в приёме). */
    public static double[] mainInterestShares() {
        return DEPT_SHARE.clone();
    }

    // флаги
    static final int COMPANY = 1, INSTITUTION = 2, DEALER = 4, BUYER = 8, CONSIGNOR = 16, EN = 32, MARKETING = 64,
            ANALYTICS = 128, NON_PAYER = 256;

    private final int n;
    private final long[] key, registeredAt, verifiedAt, activeUntil, budget;
    private final int[] city, flags;
    private final short[] birthYear;
    private final byte[] strategy;
    private final float[] interest, salesPerYear, maxShare, phoneShare, roomShare, payRel, payDelay;
    private final long[][] devices;
    private final int[] shillFor, ring;
    /** Блокировка помимо неуплаты: момент и причина (0 — нет; 1 — по просьбе, 2 — подозрение в подставных). */
    private final long[] otherBlockAt;
    private final byte[] otherBlock;
    private final List<Link> links = new ArrayList<>();
    private final int mskCity, spbCity;
    /** Выбор покупателей: по отделу — {@code id} по возрастанию и накопленный вес «интерес × частота». */
    private final int[][] poolIds = new int[Dept.count()][];
    private final double[][] poolCum = new double[Dept.count()][];

    private record Draft(long key, long at) {
    }

    public ClientPopulation(RandomStreams streams, HouseRules house, double scale, long endAt) {
        RandomStreams.Stream base = streams.stream("clients.base");
        // ---- регистрации по суткам
        double norm = FULL_AT_NOW / shapeSum(Horizon.CLIENTS_FROM, NOW_DAY);
        List<Draft> drafts = new ArrayList<>();
        LocalDate endDay = java.time.Instant.ofEpochSecond(endAt).atZone(HouseRules.ZONE).toLocalDate();
        for (LocalDate d = Horizon.CLIENTS_FROM; !d.isAfter(endDay); d = d.plusDays(1)) {
            Rng r = base.at(d.toEpochDay());
            int k = r.poisson(norm * shape(d) * scale);
            long midnight = d.atStartOfDay(HouseRules.ZONE).toEpochSecond();
            for (int j = 0; j < k; j++) {
                Rng rj = base.at(d.toEpochDay(), j + 1);
                // днём и вечером чаще, чем ночью
                double h = rj.chance(0.92) ? rj.uniform(8, 24) : rj.uniform(0, 8);
                long at = midnight + (long) (h * 3600);
                if (at < endAt) drafts.add(new Draft(d.toEpochDay() << 20 | j, at));
            }
        }
        drafts.sort(Comparator.comparingLong(Draft::at).thenComparingLong(Draft::key));
        n = drafts.size();
        key = new long[n + 1];
        registeredAt = new long[n + 1];
        verifiedAt = new long[n + 1];
        activeUntil = new long[n + 1];
        budget = new long[n + 1];
        city = new int[n + 1];
        flags = new int[n + 1];
        birthYear = new short[n + 1];
        strategy = new byte[n + 1];
        interest = new float[(n + 1) * Dept.count()];
        salesPerYear = new float[n + 1];
        maxShare = new float[n + 1];
        phoneShare = new float[n + 1];
        roomShare = new float[n + 1];
        payRel = new float[n + 1];
        payDelay = new float[n + 1];
        devices = new long[n + 1][];
        shillFor = new int[n + 1];
        ring = new int[n + 1];
        otherBlockAt = new long[n + 1];
        otherBlock = new byte[n + 1];

        // ---- города: вес — население, Москва и Петербург — сверх веса (дом там)
        List<HouseRules.City> cities = house.cities();
        double[] cityW = new double[cities.size()];
        int msk = house.office("msk").cityId(), spb = house.office("spb").cityId();
        mskCity = msk;
        spbCity = spb;
        for (int i = 0; i < cityW.length; i++) {
            HouseRules.City c = cities.get(i);
            cityW[i] = c.population() * (c.id() == msk ? 2.6 : c.id() == spb ? 1.8 : 1.0);
        }

        RandomStreams.Stream tr = streams.stream("clients.traits");
        for (int id = 1; id <= n; id++) {
            Draft dr = drafts.get(id - 1);
            key[id] = dr.key();
            registeredAt[id] = dr.at();
            traits(id, tr.at(dr.key()), cities, cityW);
        }
        links(streams.stream("clients.links"));
        pools();
    }

    // ---------------------------------------------------------------- кривая роста

    /** Форма регистраций в сутки: рост от опорной даты, сезон, выходные; январские праздники — тише. */
    static double shape(LocalDate d) {
        double years = (d.toEpochDay() - ANCHOR.toEpochDay()) / 365.25;
        double s = Math.exp(GROWTH * years);
        int m = d.getMonthValue();
        if (m >= 9 && m <= 12) s *= 1.15;
        else if (m >= 6 && m <= 8) s *= 0.85;
        if (m == 1 && d.getDayOfMonth() <= 10) s *= 0.5;
        DayOfWeek w = d.getDayOfWeek();
        if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY) s *= 0.8;
        return s;
    }

    static double shapeSum(LocalDate from, LocalDate toExclusive) {
        double s = 0;
        for (LocalDate d = from; d.isBefore(toExclusive); d = d.plusDays(1)) s += shape(d);
        return s;
    }

    // ---------------------------------------------------------------- черты

    private void traits(int id, Rng r, List<HouseRules.City> cities, double[] cityW) {
        int f = 0;
        boolean company = r.chance(0.06);
        if (company) f |= COMPANY;
        if (company && r.chance(0.15)) f |= INSTITUTION;
        boolean dealer = !((f & INSTITUTION) != 0) && r.chance(company ? 0.12 : 0.002);
        if (dealer) f |= DEALER;
        boolean buyer = dealer || r.chance(company ? 0.7 : 0.92);
        if (buyer) f |= BUYER;
        if (dealer || (f & INSTITUTION) != 0 || r.chance(buyer ? 0.22 : 0.9)) f |= CONSIGNOR;
        if (r.chance(0.04)) f |= EN;
        if (r.chance(0.65)) f |= MARKETING;
        if (r.chance(0.70)) f |= ANALYTICS;
        city[id] = cities.get(r.weighted(cityW)).id();

        // интересы: главный отдел и до двух соседних
        int base = id * Dept.count();
        int main = r.weighted(DEPT_SHARE);
        double mainW = r.uniform(0.5, 0.95);
        interest[base + main] = (float) mainW;
        double rest = 1 - mainW;
        int others = r.chance(0.6) ? 2 : 1;
        for (int k = 0; k < others; k++) {
            int o = r.weighted(DEPT_SHARE);
            if (o == main) o = (main + 1 + r.nextInt(Dept.count() - 1)) % Dept.count();
            double w = k == others - 1 ? rest : rest * r.uniform(0.3, 0.7);
            interest[base + o] += (float) w;
            rest -= w;
        }
        // возраст: участники моложе 40 — 20 %, на торгах украшений и часов — 30 %: у главного интереса «украшения» — 40 %
        if (!company) {
            double young = main == Dept.JEWELRY.ordinal() ? 0.40 : 0.175;
            int age = r.chance(young) ? r.between(22, 39) : r.chance(0.55) ? r.between(40, 59) : r.between(60, 86);
            birthYear[id] = (short) (2026 - age);
        }
        // частота торгов в год: логнормально, медиана 2, среднее ≈ 5; торговцы — 40–150
        double spy = dealer ? r.uniform(40, 150) : Math.min(150, r.logNormal(2.0, 1.354));
        salesPerYear[id] = buyer ? (float) spy : 0f;
        // бюджет на лот — верх своей цены
        // частые покупатели тратят больше: бюджет растёт с частотой (повторные покупки — у них)
        double b = r.logNormal(35_000, 1.3) * (dealer ? 10 : company ? 3 : 1) * Math.pow(Math.max(0.5, spy) / 2.0, 0.3);
        budget[id] = Math.round(Math.min(b, 400_000_000) * 100);
        // узор онлайн: снайперы 4 %, по максимуму, осторожные, ранние
        double s = r.nextDouble();
        strategy[id] = (byte) (s < 0.04 ? Strategy.SNIPER.ordinal() : s < 0.34 ? Strategy.MAX.ordinal()
                : s < 0.54 ? Strategy.CAUTIOUS.ordinal() : Strategy.EARLY.ordinal());
        maxShare[id] = (float) Math.min(0.95, Math.max(0.02, r.gaussian() * 0.18 + (strategy[id] == Strategy.MAX.ordinal() ? 0.8 : 0.33)));
        boolean capital = city[id] == mskCity || city[id] == spbCity;
        phoneShare[id] = (float) (budget[id] > 300_000_00L ? r.uniform(0.2, 0.6) : r.uniform(0.0, 0.08));
        roomShare[id] = (float) (r.uniform(0.0, 0.25) + (capital ? 0.1 : 0));
        // оплата (для планировщика оплаты): 1 − p ~ логнормально со средним 0,03; задержка ~ экспоненциально, среднее 4 дня
        boolean nonPayer = r.chance(0.002);
        if (nonPayer) f |= NON_PAYER;
        payRel[id] = nonPayer ? 0f : (float) (1 - Math.min(0.6, r.logNormal(0.0182, 1.0)));
        payDelay[id] = (float) r.exponential(4.0);
        // проверка документа и карты: у большинства — в первые часы
        verifiedAt[id] = r.chance(company ? 0.95 : 0.88) ? registeredAt[id] + 300 + (long) Math.min(259_200, r.exponential(21_600)) : 0;
        // покупает до поры: экспоненциально, среднее 9 лет (торговцы — 15): активных покупателей ≈ половина базы
        activeUntil[id] = registeredAt[id] + (long) (r.exponential(dealer ? 15 : 9) * 365.25 * 86_400);
        // устройства: 1–3
        int dn = r.chance(0.55) ? 1 : r.chance(0.7) ? 2 : 3;
        devices[id] = new long[dn];
        for (int i = 0; i < dn; i++) devices[id][i] = r.nextLong();
        flags[id] = f;
    }

    // ---------------------------------------------------------------- связи

    private void links(RandomStreams.Stream st) {
        // подставные: 0,3 % покупателей; каждый привязан к 1–3 комитентам-торговцам, зарегистрированным раньше;
        // общее устройство — первое устройство подставного = устройство комитента
        int[] dealers = idsWith(DEALER | CONSIGNOR);
        for (int id = 1; id <= n; id++) {
            if ((flags[id] & BUYER) == 0 || (flags[id] & DEALER) != 0) continue;
            Rng r = st.at(key[id]);
            if (!r.chance(0.003)) continue;
            int before = Arrays.binarySearch(dealers, id);
            int avail = before < 0 ? -before - 1 : before;
            if (avail == 0) continue;
            int links = 1 + r.nextInt(3);
            int primary = 0;
            // комитенты подставного — разные: повторно выпавший (не только главный) связи не даёт, иначе спрос посадит
            // подставного на лот этого комитента дважды — два участника одной регистрации, у них совпадут ключи запросов
            // (T14); случайные числа те же — прочие связи не меняются
            int[] chosen = new int[links];
            int nc = 0;
            for (int k = 0; k < links; k++) {
                int c = dealers[r.nextInt(avail)];
                boolean again = false;
                for (int j = 0; j < nc; j++) again |= chosen[j] == c;
                if (again) continue;
                if (k == 0) {
                    primary = c;
                    devices[id][0] = devices[c][0];
                }
                chosen[nc++] = c;
                this.links.add(new Link("shill", id, c, 0, null));
            }
            shillFor[id] = primary;
        }
        // сговор: две группы по 3–5 торговцев — живопись и монеты; участники — торговцы с этим главным интересом
        Dept[] ringDepts = {Dept.PAINTING, Dept.COINS};
        for (int g = 0; g < ringDepts.length; g++) {
            Rng r = st.at(-1 - g);
            List<Integer> cand = new ArrayList<>();
            for (int id = 1; id <= n; id++) {
                if ((flags[id] & DEALER) != 0 && shillFor[id] == 0 && ring[id] == 0
                        && interest[id * Dept.count() + ringDepts[g].ordinal()] >= 0.5f && registeredAt[id] < RING_BEFORE) {
                    cand.add(id);
                }
            }
            int size = Math.min(cand.size(), 3 + r.nextInt(3));
            if (size < 3) continue;
            for (int k = 0; k < size; k++) {
                int c = cand.remove(r.nextInt(cand.size()));
                ring[c] = g + 1;
                links.add(new Link("ring", c, 0, g + 1, ringDepts[g]));
            }
        }
        // повторная регистрация неплательщика: половина неплательщиков заводит новую учётную запись с того же устройства —
        // новый клиент, зарегистрированный позже, становится неплательщиком-двойником
        for (int id = 1; id <= n; id++) {
            if ((flags[id] & NON_PAYER) == 0) continue;
            Rng r = st.at(key[id], 1);
            if (!r.chance(0.5)) continue;
            long from = registeredAt[id] + (120L + r.nextInt(280)) * 86_400;
            int twin = countRegisteredBefore(from) + 1 + r.nextInt(20);
            if (twin > n) continue; // двойник — позже конца моделирования: в этом прогоне его нет
            if ((flags[twin] & (DEALER | COMPANY | NON_PAYER)) != 0 || shillFor[twin] != 0 || ring[twin] != 0) continue;
            flags[twin] |= NON_PAYER | BUYER;
            if (salesPerYear[twin] == 0) salesPerYear[twin] = 3f;
            payRel[twin] = 0f;
            devices[twin][0] = devices[id][0];
            links.add(new Link("reregistration", twin, id, 0, null));
        }
        // блокировки помимо неуплаты: по просьбе — 0,3 % клиентов; подставные — каждый пятый, через 200–600 суток
        for (int id = 1; id <= n; id++) {
            Rng r = st.at(key[id], 2);
            if (shillFor[id] != 0 && r.chance(0.2)) {
                otherBlockAt[id] = registeredAt[id] + (200L + r.nextInt(400)) * 86_400 + r.nextInt(86_400);
                otherBlock[id] = 2;
            } else if (r.chance(0.003)) {
                otherBlockAt[id] = registeredAt[id] + (long) (r.exponential(3 * 365.25) * 86_400);
                otherBlock[id] = 1;
            }
        }
        links.sort(Comparator.comparing(Link::kind).thenComparingInt(Link::clientId).thenComparingInt(Link::otherId));
    }

    private int[] idsWith(int mask) {
        int k = 0;
        for (int id = 1; id <= n; id++) if ((flags[id] & mask) == mask) k++;
        int[] out = new int[k];
        k = 0;
        for (int id = 1; id <= n; id++) if ((flags[id] & mask) == mask) out[k++] = id;
        return out;
    }

    private void pools() {
        for (Dept d : Dept.values()) {
            int k = 0;
            for (int id = 1; id <= n; id++) if (salesPerYear[id] > 0 && interest(id, d) > 0) k++;
            int[] ids = new int[k];
            double[] cum = new double[k];
            double s = 0;
            k = 0;
            for (int id = 1; id <= n; id++) {
                if (salesPerYear[id] > 0 && interest(id, d) > 0) {
                    s += interest(id, d) * salesPerYear[id];
                    ids[k] = id;
                    cum[k++] = s;
                }
            }
            poolIds[d.ordinal()] = ids;
            poolCum[d.ordinal()] = cum;
        }
    }

    // ---------------------------------------------------------------- ClientBase

    @Override
    public int size() {
        return n;
    }

    @Override
    public int countRegisteredBefore(long at) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int m = (lo + hi + 1) >>> 1;
            if (registeredAt[m] < at) lo = m;
            else hi = m - 1;
        }
        return lo;
    }

    /** Естественный ключ клиента: (сутки регистрации, номер в сутках) — для случайности его строк. */
    public long key(int id) {
        return key[id];
    }

    @Override
    public long registeredAt(int id) {
        return registeredAt[id];
    }

    @Override
    public boolean company(int id) {
        return (flags[id] & COMPANY) != 0;
    }

    @Override
    public boolean institution(int id) {
        return (flags[id] & INSTITUTION) != 0;
    }

    @Override
    public boolean dealer(int id) {
        return (flags[id] & DEALER) != 0;
    }

    @Override
    public int cityId(int id) {
        return city[id];
    }

    @Override
    public long verifiedAt(int id) {
        return verifiedAt[id];
    }

    @Override
    public int birthYear(int id) {
        return birthYear[id];
    }

    @Override
    public String language(int id) {
        return (flags[id] & EN) != 0 ? "en" : "ru";
    }

    @Override
    public boolean marketingConsent(int id) {
        return (flags[id] & MARKETING) != 0;
    }

    @Override
    public boolean analyticsConsent(int id) {
        return (flags[id] & ANALYTICS) != 0;
    }

    @Override
    public float interest(int id, Dept d) {
        return interest[id * Dept.count() + d.ordinal()];
    }

    @Override
    public boolean buyer(int id) {
        return (flags[id] & BUYER) != 0;
    }

    @Override
    public float salesPerYear(int id) {
        return salesPerYear[id];
    }

    @Override
    public long budgetKop(int id) {
        return budget[id];
    }

    @Override
    public Strategy strategy(int id) {
        return Strategy.values()[strategy[id]];
    }

    @Override
    public float maxShare(int id) {
        return maxShare[id];
    }

    @Override
    public float phoneShare(int id) {
        return phoneShare[id];
    }

    @Override
    public float roomShare(int id) {
        return roomShare[id];
    }

    @Override
    public float payReliability(int id) {
        return payRel[id];
    }

    @Override
    public float payDelayDays(int id) {
        return payDelay[id];
    }

    @Override
    public boolean serialNonPayer(int id) {
        return (flags[id] & NON_PAYER) != 0;
    }

    @Override
    public boolean consignor(int id) {
        return (flags[id] & CONSIGNOR) != 0;
    }

    @Override
    public long activeUntil(int id) {
        return activeUntil[id];
    }

    @Override
    public long otherBlockAt(int id) {
        return otherBlockAt[id];
    }

    @Override
    public String otherBlockReason(int id) {
        return otherBlock[id] == 1 ? "own_request" : otherBlock[id] == 2 ? "shill_suspected" : null;
    }

    @Override
    public long[] devices(int id) {
        return devices[id].clone();
    }

    @Override
    public List<Link> links() {
        return links;
    }

    @Override
    public int shillFor(int id) {
        return shillFor[id];
    }

    @Override
    public int ring(int id) {
        return ring[id];
    }

    @Override
    public int sampleBuyer(Dept d, long before, Rng r) {
        int[] ids = poolIds[d.ordinal()];
        double[] cum = poolCum[d.ordinal()];
        int k = upper(ids, countRegisteredBefore(before));
        if (k == 0) return 0;
        for (int attempt = 0; attempt < 96; attempt++) {
            double x = r.nextDouble() * cum[k - 1];
            int lo = 0, hi = k - 1;
            while (lo < hi) {
                int m = (lo + hi) >>> 1;
                if (cum[m] > x) hi = m;
                else lo = m + 1;
            }
            int id = ids[lo];
            if (activeUntil[id] <= before) continue;
            // новички активнее: в первый год после регистрации — до × NEW_BOOST (≈ 25 % участников года — новые)
            double age = (before - registeredAt[id]) / (365.25 * 86_400);
            double w = 1 + (NEW_BOOST - 1) * Math.exp(-age / NEW_DECAY_YEARS);
            if (r.nextDouble() * NEW_BOOST < w) return id;
        }
        return 0;
    }

    /** Сколько элементов возрастающего {@code ids} не больше {@code maxId}. */
    private static int upper(int[] ids, int maxId) {
        int i = Arrays.binarySearch(ids, maxId);
        return i >= 0 ? i + 1 : -i - 1;
    }

    /** Флаги клиента (для тестов и документации). */
    int flags(int id) {
        return flags[id];
    }
}
