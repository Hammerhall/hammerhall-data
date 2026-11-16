package io.hammerhall.data.sto.customers;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.geo.GeoMath;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.core.time.RuCalendar;
import io.hammerhall.data.core.time.RuCalendar.SalesEvent;
import io.hammerhall.data.sto.StoKeys;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.CatalogModule;
import io.hammerhall.data.sto.catalog.CategoryTree;
import io.hammerhall.data.sto.geo.Geo;
import io.hammerhall.data.sto.geo.GeoModule;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Клиенты и адреса. Регистрации — процесс по суткам (UTC) с открытия магазина {@link #SHOP_START}: рост, сезон и
 * события календаря, суточный ход — по местному времени города клиента; масштаб профиля — к числу регистраций в сутки.
 * Клиенты, зарегистрированные до начала периода, — те, кто к его началу ещё покупал (ушедшие раньше в выпуск не
 * попадают). id — по моменту регистрации; сутки после прежнего конца периода дописываются в хвост.
 *
 * <p>Клиент: выдуманное сочетание распространённого имени (по поколению) и фамилии (в форме своего пола), год
 * рождения, город — по населению, откуда пришёл; малая доля — b2b с выдуманной организацией. Скрытые черты (для
 * заказов и сессий) — в модели {@link Customers}. Адреса — точки вокруг центра города в радиусе
 * {@link GeoModule#cityRadiusKm}: дом, работа, другое; первый — к первому заказу, следующие — за жизнь клиента;
 * переехавшие добавляют дом в новом городе.
 *
 * <p>Заложено ({@code shared_address}): несколько групп аккаунтов, заведённых один за другим на одну точку
 * (одинаковые координаты у разных клиентов).
 */
public final class CustomerModule implements Module {
    public static final String NAME = "customers";
    static final LocalDate SHOP_START = LocalDate.of(2018, 3, 1);
    /** Опора кривой роста — начало окна v1 (константа: продление периода её не двигает). */
    static final LocalDate REF_FROM = LocalDate.of(2025, 10, 1);
    static final LocalDate HORIZON = LocalDate.of(2036, 1, 1);
    /** Регистраций в сутки в {@code full}: при открытии, к началу окна v1; рост за год внутри окна. */
    static final double RATE_START = 140, RATE_REF = 1650, GROWTH = 1.15;
    static final double B2B_SHARE = 0.025;
    /** Групп «один адрес — разные клиенты» в {@code full} (в профиле — пропорционально, не меньше трёх). */
    static final int RINGS_FULL = 70;
    static final long DAY = 86_400L;

    /** Регистрации по местному часу (0–23). */
    static final double[] DIURNAL = {0.9, 0.5, 0.3, 0.2, 0.15, 0.2, 0.4, 0.8, 1.2, 1.5, 1.7, 1.8, 1.9, 1.9, 1.8, 1.8, 1.8,
            1.9, 2.1, 2.4, 2.6, 2.5, 2.0, 1.4};

    /** Любимые отделы: вес у мужчин и женщин (по названию отдела). */
    static final Map<String, double[]> FAV_W = Map.ofEntries(
            Map.entry("Phones and gadgets", new double[]{1.2, 1.2}), Map.entry("Computers and laptops", new double[]{1.3, 0.8}),
            Map.entry("TV, audio and video", new double[]{1.1, 0.8}), Map.entry("Photo and video", new double[]{0.6, 0.5}),
            Map.entry("Gaming and entertainment", new double[]{1.0, 0.3}), Map.entry("Large appliances", new double[]{0.7, 0.9}),
            Map.entry("Kitchen appliances", new double[]{0.6, 1.2}), Map.entry("Home appliances", new double[]{0.7, 1.1}),
            Map.entry("Beauty and health", new double[]{0.4, 1.3}), Map.entry("Smart home and lighting", new double[]{0.7, 0.6}),
            Map.entry("Tools", new double[]{1.0, 0.3}), Map.entry("Home and kitchen", new double[]{0.5, 1.1}),
            Map.entry("Car electronics", new double[]{0.8, 0.2}));

    static final String[] ORG_DESC = {"", "", "", " Сервис", " Строй", " Проект", " Инжиниринг", " Логистика", " Медиа",
            " Офис", " Системы", " Консалт", " Трейд", " Энерго", " Агро", " Мед", " Девелопмент", " Студия", " Групп",
            " Плюс", " Техно", " Монтаж"};

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return List.of(GeoModule.NAME, CatalogModule.NAME);
    }

    @Override
    public List<String> tables() {
        return List.of("customers", "addresses");
    }

    /** Регистраций в сутки в {@code full} без сезона: экспонента от открытия к началу окна v1, дальше — рост. */
    static double baseRate(LocalDate d) {
        double t = (d.toEpochDay() - REF_FROM.toEpochDay()) / 365.25;
        if (t < 0) {
            double span = (REF_FROM.toEpochDay() - SHOP_START.toEpochDay()) / 365.25;
            return RATE_REF * StrictMath.exp(StrictMath.log(RATE_REF / RATE_START) / span * t);
        }
        return RATE_REF * StrictMath.pow(GROWTH, t);
    }

    // ================================================================ черновики

    /** Клиент до выдачи id. */
    private static final class C {
        long order;
        int reg, churn, first = -1, move = -1;
        int city, moveCity = -1, birth, firstName, surname, favDepts;
        byte flags, gender, acquired, device, priceSens, promoSens, pickup;
        float activity, basket;
        String companyName;
        // для адресов
        Rng addr;
        int ring = -1;
        double ringLat, ringLon;
    }

    /** Растущие колонки адресов (в порядке генерации). */
    private static final class Addr {
        int n;
        int[] cust = new int[1 << 16], lat = new int[1 << 16], lon = new int[1 << 16], created = new int[1 << 16],
                group = new int[1 << 16];
        short[] city = new short[1 << 16];
        byte[] kind = new byte[1 << 16];

        void add(int c, int cityId, double la, double lo, int k, long at, int g) {
            if (n == cust.length) {
                int cap = n + (n >> 1);
                cust = Arrays.copyOf(cust, cap);
                lat = Arrays.copyOf(lat, cap);
                lon = Arrays.copyOf(lon, cap);
                created = Arrays.copyOf(created, cap);
                group = Arrays.copyOf(group, cap);
                city = Arrays.copyOf(city, cap);
                kind = Arrays.copyOf(kind, cap);
            }
            cust[n] = c;
            city[n] = (short) cityId;
            lat[n] = (int) StrictMath.round(la * 1e6);
            lon[n] = (int) StrictMath.round(lo * 1e6);
            kind[n] = (byte) k;
            created[n] = Math.toIntExact(at);
            group[n] = g;
            n++;
        }
    }

    /** Группа заложенного нарушения: город, точка, первый день, число аккаунтов. */
    private record Ring(int g, int city, double lat, double lon, LocalDate anchor, int members) {
    }

    // ================================================================ генерация

    private Geo geo;
    private double[] cityCum;
    private double[][] hourCum;
    private boolean[] eventDay;
    private long eventBase;
    private List<String> orgRoots;
    private List<CategoryTree.Node> depts;
    private long horizon;

    @Override
    public void generate(GenContext ctx) throws IOException {
        Period period = ctx.period();
        geo = ctx.get(StoKeys.GEO);
        Catalog cat = ctx.get(StoKeys.CATALOG);
        double scale = ctx.profile().scale();
        long winStart = period.startEpoch(), winEnd = period.endEpochExclusive();
        horizon = HORIZON.atStartOfDay(ZoneOffset.UTC).toEpochSecond();

        depts = new ArrayList<>();
        for (CategoryTree.Node nd : cat.tree().nodes()) if (nd.level == 2) depts.add(nd);
        int[] deptIds = depts.stream().mapToInt(x -> x.id).toArray();
        cityCum = new double[geo.cities().size()];
        double c = 0;
        for (int i = 0; i < cityCum.length; i++) {
            c += StrictMath.pow(geo.cities().get(i).population(), 1.08);
            cityCum[i] = c;
        }
        hourCum = new double[27][];
        eventBase = SHOP_START.toEpochDay() - 2;
        eventDay = new boolean[(int) (period.to().toEpochDay() - eventBase + 4)];
        RuCalendar cal = ctx.calendar();
        for (int i = 0; i < eventDay.length; i++) {
            List<SalesEvent> ev = cal.eventsOn(LocalDate.ofEpochDay(eventBase + i));
            eventDay[i] = ev.contains(SalesEvent.ELEVEN_ELEVEN) || ev.contains(SalesEvent.BLACK_FRIDAY);
        }
        orgRoots = orgRoots(ctx);
        List<Ring> rings = planRings(ctx, scale);
        Map<Long, List<int[]>> ringDays = new TreeMap<>();
        for (Ring rg : rings) {
            Rng r = ctx.stream("customers.ring_days").at(rg.g);
            LocalDate d = rg.anchor;
            for (int j = 0; j < rg.members; j++) {
                ringDays.computeIfAbsent(d.toEpochDay(), k -> new ArrayList<>()).add(new int[]{rg.g, j});
                d = d.plusDays(r.between(0, 10));
            }
        }

        RandomStreams.Stream days = ctx.stream("customers.days");
        RandomStreams.Stream reg = ctx.stream("customers.registrations");
        RandomStreams.Stream addr = ctx.stream("customers.addresses");
        RandomStreams.Stream ringSt = ctx.stream("customers.ring_members");
        double expected = 0;
        for (LocalDate d = SHOP_START; !d.isAfter(period.to()); d = d.plusDays(1)) {
            expected += scale * baseRate(d) * (d.isBefore(period.from()) ? 0.35 : 1.1);
        }
        Customers cs = new Customers((int) (expected * 1.05) + 1024, deptIds);
        Addr ad = new Addr();
        long drawn = 0;
        for (LocalDate d = SHOP_START; !d.isAfter(period.to()); d = d.plusDays(1)) {
            long day = d.toEpochDay(), dayStart = day * DAY;
            double lam = scale * baseRate(d) * StrictMath.pow(cal.demand(d, RuCalendar.Season.BASE), 0.7);
            int n = days.at(day).poisson(lam);
            drawn += n;
            List<C> today = new ArrayList<>(n + 4);
            for (int i = 0; i < n; i++) {
                C x = regular(reg.at(day, i), d, dayStart);
                if (x.reg < winStart && ((x.flags & Customers.BUYER) == 0 || x.churn <= winStart)) continue;
                x.order = i;
                x.addr = addr.at(day, i);
                today.add(x);
            }
            for (int[] gj : ringDays.getOrDefault(day, List.of())) {
                C x = ringMember(ringSt.at(gj[0], gj[1]), rings.get(gj[0]), dayStart);
                x.order = 1_000_000L + gj[0] * 100L + gj[1];
                x.addr = ringSt.at(gj[0], 1000 + gj[1]);
                today.add(x);
            }
            today.sort(Comparator.comparingInt((C x) -> x.reg).thenComparingLong(x -> x.order));
            cs.ensure(cs.n + today.size());
            for (C x : today) {
                int k = cs.n++;
                cs.registeredAt[k] = x.reg;
                cs.churnAt[k] = x.churn;
                cs.firstOrderAt[k] = x.first;
                cs.moveAt[k] = x.move;
                cs.city[k] = (short) x.city;
                cs.moveCity[k] = (short) x.moveCity;
                cs.birthYear[k] = (short) x.birth;
                cs.firstName[k] = (short) x.firstName;
                cs.surname[k] = (short) x.surname;
                cs.favDepts[k] = (short) x.favDepts;
                cs.flags[k] = x.flags;
                cs.gender[k] = x.gender;
                cs.acquired[k] = x.acquired;
                cs.device[k] = x.device;
                cs.priceSens[k] = x.priceSens;
                cs.promoSens[k] = x.promoSens;
                cs.pickup[k] = x.pickup;
                cs.activity[k] = x.activity;
                cs.basket[k] = x.basket;
                if (x.companyName != null) {
                    cs.companyRef[k] = cs.companies.size();
                    cs.companies.add(x.companyName);
                } else {
                    cs.companyRef[k] = -1;
                }
                addresses(x, k + 1, ad, winEnd);
            }
        }
        ctx.log(String.format(Locale.ROOT, "  customers: регистраций разыграно %d, в выпуске клиентов %d, адресов %d",
                drawn, cs.n, ad.n));

        // адреса: id — по моменту добавления (продление дописывает в хвост)
        long base = SHOP_START.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long[] keys = new long[ad.n];
        for (int i = 0; i < ad.n; i++) keys[i] = ((ad.created[i] - base) << 33) | i;
        Arrays.sort(keys);
        int na = ad.n;
        int[] aCust = new int[na], aLat = new int[na], aLon = new int[na], aCreated = new int[na];
        short[] aCity = new short[na];
        byte[] aKind = new byte[na];
        List<int[]> shared = new ArrayList<>();
        for (int j = 0; j < na; j++) {
            int i = (int) (keys[j] & ((1L << 33) - 1));
            aCust[j] = ad.cust[i];
            aLat[j] = ad.lat[i];
            aLon[j] = ad.lon[i];
            aCreated[j] = ad.created[i];
            aCity[j] = ad.city[i];
            aKind[j] = ad.kind[i];
            if (ad.group[i] >= 0) shared.add(new int[]{j + 1, ad.group[i]});
        }
        keys = null;
        int[] sharedAddr = new int[shared.size()], sharedGroup = new int[shared.size()];
        for (int i = 0; i < shared.size(); i++) {
            sharedAddr[i] = shared.get(i)[0];
            sharedGroup[i] = shared.get(i)[1];
            ctx.planted().add("shared_address", sharedAddr[i]);
        }
        cs.seal(aCust, aCity, aLat, aLon, aKind, aCreated, sharedAddr, sharedGroup);

        TableSink sc = ctx.streamSink("customers");
        for (int id = 1; id <= cs.n; id++) {
            sc.add(id, cs.firstName(id), cs.lastName(id), cs.gender(id), (short) cs.birthYear(id), cs.registeredAt(id),
                    cs.cityId(id), cs.acquiredVia(id), cs.b2b(id), cs.companyName(id));
        }
        sc.close();
        TableSink sa = ctx.streamSink("addresses");
        for (int id = 1; id <= na; id++) {
            sa.add(id, cs.addressCustomer(id), cs.addressCity(id), cs.addressLat(id), cs.addressLon(id),
                    cs.addressKind(id), cs.addressCreatedAt(id));
        }
        sa.close();
        ctx.put(StoKeys.CUSTOMERS, cs);
        logDemand(ctx, cs);
    }

    /** Ожидаемый поток заказов на середину окна — ориентир для модуля заказов (в журнал сборки). */
    private static void logDemand(GenContext ctx, Customers cs) {
        LocalDate mid = ctx.period().from().plusDays((ctx.period().to().toEpochDay() - ctx.period().from().toEpochDay()) / 2);
        Customers.Sampler s = cs.sampler(mid);
        ctx.log(String.format(Locale.ROOT, "  customers: на %s активных с покупками %d, ожидаемых повторных заказов в сутки %.0f, "
                + "первых заказов %d", mid, s.size(), s.totalActivity() / 30.0, cs.firstOrdersOn(mid).length));
    }

    // ---------------------------------------------------------------- один клиент

    private static int pickCum(Rng r, double[] cum) {
        return PersonNames.pickCum(r, cum);
    }

    private double[] hours(int offsetHours) {
        int k = offsetHours + 12;
        if (hourCum[k] == null) {
            double[] cum = new double[24];
            double c = 0;
            for (int h = 0; h < 24; h++) {
                c += DIURNAL[Math.floorMod(h + offsetHours, 24)];
                cum[h] = c;
            }
            hourCum[k] = cum;
        }
        return hourCum[k];
    }

    private static byte pct(double v) {
        return (byte) Math.max(0, Math.min(100, StrictMath.round(v * 100)));
    }

    private int churnOf(long reg, double days) {
        long t = reg + (long) (days * DAY);
        return t >= horizon ? Customers.NEVER : (int) t;
    }

    private C regular(Rng r, LocalDate d, long dayStart) {
        C x = new C();
        x.city = geo.cities().get(pickCum(r, cityCum)).id();
        Geo.City city = geo.city(x.city);
        int off = city.zone().getRules().getOffset(Instant.ofEpochSecond(dayStart + 43_200)).getTotalSeconds() / 3600;
        int h = pickCum(r, hours(off));
        x.reg = Math.toIntExact(dayStart + h * 3600L + r.nextInt(3600));
        LocalDate local = Period.localDate(x.reg, city.zone());
        boolean event = eventDay[(int) (local.toEpochDay() - eventBase)];
        boolean b2b = r.chance(B2B_SHARE);
        boolean female = r.chance(b2b ? 0.4 : 0.5);
        int age = b2b ? (int) StrictMath.round(r.gaussianClamped(40, 9, 22, 70))
                : (int) StrictMath.round(r.gaussianClamped(36, 12, 18, 85));
        x.birth = d.getYear() - age;
        x.gender = (byte) (female ? 1 : 0);
        x.firstName = PersonNames.pickFirst(r, female, x.birth);
        x.surname = PersonNames.pickSurname(r);
        // откуда пришёл
        double[] aw = {0.30, 0.24, 0.18, 0.12, 0.08, 0.08};
        if (event) {
            aw[2] *= 2.2;
            aw[3] *= 1.4;
        }
        if (age > 55) {
            aw[5] *= 2;
            aw[3] *= 0.5;
        } else if (age < 28) {
            aw[3] *= 1.6;
        }
        if (d.getYear() < 2020) aw[3] *= 0.6;
        if (b2b) {
            aw[1] *= 1.5;
            aw[5] *= 1.5;
            aw[3] *= 0.3;
        }
        x.acquired = (byte) r.weighted(aw);
        // устройство: desktop, mobile_web, tablet, app
        double[] dw = b2b ? new double[]{0.80, 0.08, 0.02, 0.10} : age < 30 ? new double[]{0.15, 0.30, 0.05, 0.50}
                : age <= 50 ? new double[]{0.30, 0.25, 0.05, 0.40} : new double[]{0.40, 0.25, 0.15, 0.20};
        if (d.getYear() < 2021) dw[3] *= 0.6;
        x.device = (byte) r.weighted(dw);
        boolean app = x.device == 3, ads = x.acquired == 2, social = x.acquired == 3, referral = x.acquired == 4;
        boolean buyer = r.chance(b2b ? 0.95 : 0.88);
        // удержание по когортам
        double p1 = 0.30 + (event ? 0.12 : 0) + (ads ? 0.06 : 0) + (social ? 0.04 : 0) - (referral ? 0.06 : 0)
                - (b2b ? 0.15 : 0) - (app ? 0.05 : 0);
        boolean one = r.chance(p1);
        double life = one ? r.uniform(14, 120)
                : r.exponential(1000 * (b2b ? 1.6 : 1) * (app ? 1.2 : 1) * (referral ? 1.2 : 1) * (event ? 0.8 : 1));
        x.flags = (byte) ((b2b ? Customers.B2B : 0) | (event ? Customers.EVENT : 0) | (one ? Customers.ONE_TIMER : 0)
                | (buyer ? Customers.BUYER : 0));
        if (buyer) {
            double u = r.nextDouble();
            double lag = u < 0.62 ? r.uniform(60, 2400) : (u < 0.88 ? r.exponential(3 * DAY) : r.exponential(25 * DAY));
            x.first = Math.toIntExact(Math.min(horizon - 1, x.reg + (long) lag));
            x.churn = churnOf(x.reg, life);
            if (x.churn != Customers.NEVER && x.churn <= x.first) x.churn = churnOf(x.first, 1);
        } else {
            x.churn = x.reg;
        }
        double act = r.logNormal(0.12, 0.85) * (b2b ? 0.6 : 1) * (event ? 0.8 : 1) * (referral ? 1.15 : 1) * (app ? 1.25 : 1)
                * (age >= 28 && age <= 45 ? 1.1 : 1);
        x.activity = (float) act;
        double ps = r.gaussianClamped(0.5 + (age < 25 ? 0.1 : 0) + (event ? 0.12 : 0) - (b2b ? 0.2 : 0), 0.18, 0, 1);
        x.priceSens = pct(ps);
        x.promoSens = pct(Math.min(1, 0.6 * ps + 0.4 * r.nextDouble() + (ads || social ? 0.08 : 0) + (event ? 0.12 : 0)));
        x.favDepts = favorites(r, female, age, b2b);
        double pu = r.nextDouble();
        x.pickup = pct(b2b ? r.uniform(0, 0.2) : pu < 0.40 ? r.uniform(0.85, 1.0) : pu < 0.75 ? r.uniform(0.3, 0.7) : r.uniform(0, 0.15));
        x.basket = (float) (b2b ? Math.max(2, Math.min(25, r.logNormal(5, 0.5))) : Math.max(0.5, Math.min(3, r.logNormal(1, 0.25))));
        if (b2b) {
            double fu = r.nextDouble();
            String root = orgRoots.get(r.nextInt(orgRoots.size()));
            if (fu < 0.78) {
                x.companyName = "ООО «" + root + ORG_DESC[r.nextInt(ORG_DESC.length)] + "»";
            } else if (fu < 0.93) {
                x.companyName = "ИП " + PersonNames.surname(x.surname, female) + " " + PersonNames.first(female, x.firstName)
                        + " " + PersonNames.patronymic(r, female, x.birth);
            } else {
                x.companyName = "АО «" + root + "»";
            }
        }
        // переезд
        if (buyer && life > 180 && r.chance(0.05)) {
            double at = r.uniform(90, Math.min(life - 30, 4 * 365.0));
            long m = x.reg + (long) (at * DAY);
            if (m < horizon) {
                x.move = (int) m;
                int nc;
                do nc = geo.cities().get(pickCum(r, cityCum)).id(); while (nc == x.city);
                x.moveCity = nc;
            }
        }
        return x;
    }

    private int favorites(Rng r, boolean female, int age, boolean b2b) {
        double[] w = new double[depts.size()];
        for (int i = 0; i < w.length; i++) {
            String name = depts.get(i).nameEn;
            double[] fw = FAV_W.get(name);
            w[i] = fw == null ? 0.5 : fw[female ? 1 : 0];
            if (age < 30 && name.startsWith("Gaming")) w[i] *= 1.6;
            if (age > 55 && name.startsWith("Gaming")) w[i] *= 0.3;
            if (age > 50 && (name.startsWith("Kitchen") || name.startsWith("Home and"))) w[i] *= 1.2;
            if (b2b && (name.startsWith("Computers") || name.startsWith("Large") || name.startsWith("Tools"))) w[i] *= 3;
        }
        double u = r.nextDouble();
        int k = u < 0.35 ? 1 : (u < 0.75 ? 2 : 3);
        int packed = k << 12;
        for (int j = 0; j < k; j++) {
            int i = r.weighted(w);
            w[i] = 0;
            packed |= i << (4 * j);
        }
        return packed;
    }

    private C ringMember(Rng r, Ring rg, long dayStart) {
        C x = new C();
        x.city = rg.city;
        x.reg = Math.toIntExact(dayStart + r.nextInt((int) DAY));
        boolean female = r.chance(0.5);
        int age = (int) StrictMath.round(r.gaussianClamped(30, 7, 19, 55));
        x.birth = LocalDate.ofEpochDay(dayStart / DAY).getYear() - age;
        x.gender = (byte) (female ? 1 : 0);
        x.firstName = PersonNames.pickFirst(r, female, x.birth);
        x.surname = PersonNames.pickSurname(r);
        x.acquired = (byte) r.weighted(new double[]{0, 0, 0.3, 0.2, 0.5, 0});
        x.device = (byte) (r.chance(0.7) ? 3 : 1);
        x.flags = (byte) (Customers.BUYER | Customers.RING);
        x.first = Math.toIntExact(x.reg + r.between(120, 1800));
        x.churn = churnOf(x.reg, r.uniform(45, 240));
        x.activity = (float) r.uniform(0.6, 1.5);
        x.priceSens = pct(r.uniform(0.6, 0.9));
        x.promoSens = pct(r.uniform(0.8, 1.0));
        x.favDepts = (1 << 12);
        x.pickup = 0;
        x.basket = 1;
        x.ring = rg.g;
        x.ringLat = rg.lat;
        x.ringLon = rg.lon;
        return x;
    }

    // ---------------------------------------------------------------- адреса клиента

    private double[] point(Rng r, int cityId, double shape) {
        Geo.City c = geo.city(cityId);
        double[] p = GeoMath.around(r, c.lat(), c.lon(), GeoModule.cityRadiusKm(c), shape);
        return new double[]{GeoMath.round6(p[0]), GeoMath.round6(p[1])};
    }

    private int cityAt(C x, long t) {
        return x.move >= 0 && t >= x.move ? x.moveCity : x.city;
    }

    private void addresses(C x, int id, Addr ad, long winEnd) {
        Rng r = x.addr;
        boolean buyer = (x.flags & Customers.BUYER) != 0, b2b = (x.flags & Customers.B2B) != 0;
        if (x.ring >= 0) {
            long at = Math.max(x.reg + 1L, x.first - r.between(30, 120));
            if (at < winEnd) ad.add(id, x.city, x.ringLat, x.ringLon, r.chance(0.7) ? 0 : 2, at, x.ring);
            return;
        }
        boolean has = buyer ? (b2b || (x.pickup & 0xff) < 85 || r.chance(0.55)) : r.chance(0.25);
        if (!has) return;
        long first = buyer ? Math.max(x.reg + 1L, x.first - r.between(30, 300)) : x.reg + r.between(60, 3 * 86_400);
        int k0 = b2b ? r.weighted(new double[]{0.15, 0.75, 0.10}) : r.weighted(new double[]{0.82, 0.10, 0.08});
        int c0 = cityAt(x, first);
        if (first < winEnd) {
            double[] p = point(r, c0, k0 == 1 ? 0.8 : 0.62);
            ad.add(id, c0, p[0], p[1], k0, first, -1);
        }
        long end = Math.min(x.churn == Customers.NEVER ? horizon : x.churn, Math.min(horizon, first + 3 * 365 * DAY));
        if (buyer && end > first + DAY) {
            double more = b2b ? 0.7 : 0.45;
            List<Long> at = new ArrayList<>();
            while (at.size() < 6 && r.chance(more)) {
                at.add(first + (long) (r.nextDouble() * (end - first)));
                more = 0.35;
            }
            at.sort(null);
            for (long t : at) {
                int k = r.weighted(new double[]{0.25, 0.45, 0.30});
                int cc = k == 2 && r.chance(0.12) ? geo.cities().get(pickCum(r, cityCum)).id() : cityAt(x, t);
                double[] p = point(r, cc, k == 1 ? 0.8 : 0.62);
                if (t < winEnd) ad.add(id, cc, p[0], p[1], k, t, -1);
            }
        }
        if (x.move >= 0) {
            long t = x.move + r.between(0, 5 * 86_400);
            double[] p = point(r, x.moveCity, 0.62);
            if (t < winEnd && t < horizon) ad.add(id, x.moveCity, p[0], p[1], 0, t, -1);
        }
    }

    // ---------------------------------------------------------------- организации и группы

    /** Корни названий организаций b2b — мир: от профиля и периода не зависят. */
    static List<String> orgRoots(GenContext ctx) {
        RandomStreams.Stream st = ctx.stream("customers.org_roots");
        Set<String> out = new TreeSet<>();
        List<String> list = new ArrayList<>();
        for (int i = 0; list.size() < 1500 && i < 200_000; i++) {
            String s = PersonNames.orgRoot(st.at(i));
            if (s != null && out.add(s)) list.add(s);
        }
        return list;
    }

    private List<Ring> planRings(GenContext ctx, double scale) {
        int g = Math.max(3, (int) StrictMath.round(RINGS_FULL * scale));
        List<Geo.City> big = new ArrayList<>();
        for (Geo.City c : geo.cities()) if (c.population() >= 300_000) big.add(c);
        double[] w = new double[big.size()];
        for (int i = 0; i < w.length; i++) w[i] = big.get(i).population();
        RandomStreams.Stream st = ctx.stream("customers.rings");
        LocalDate a = LocalDate.of(2025, 10, 20), b = LocalDate.of(2027, 7, 20);
        List<Ring> out = new ArrayList<>();
        for (int i = 0; i < g; i++) {
            Rng r = st.at(i);
            Geo.City c = big.get(r.weighted(w));
            double[] p = point(r, c.id(), 0.62);
            LocalDate anchor = a.plusDays(r.nextInt((int) (b.toEpochDay() - a.toEpochDay())));
            out.add(new Ring(i, c.id(), p[0], p[1], anchor, r.between(3, 8)));
        }
        return out;
    }

    // ================================================================ проверки

    @Override
    public void check(GenContext ctx, CheckReport r) {
        Period period = ctx.period();
        Customers cs = ctx.get(StoKeys.CUSTOMERS);
        Geo g = ctx.get(StoKeys.GEO);
        long winStart = period.startEpoch(), winEnd = period.endEpochExclusive();
        long shop = SHOP_START.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        int n = cs.size();

        // клиенты: порядок, даты, имена по полу, возраст, b2b
        List<String> v = new ArrayList<>();
        int b2b = 0, pre = 0, women = 0;
        int[] acq = new int[Customers.ACQUIRED.length];
        for (int id = 1; id <= n; id++) {
            long t = cs.registeredAt(id);
            if (t < shop || t >= winEnd) v.add(id + ": регистрация вне [открытие, конец периода)");
            if (id > 1 && cs.registeredAt(id - 1) > t) v.add(id + ": id не по регистрации");
            if (t < winStart) {
                pre++;
                if (!cs.buyer(id) || cs.churnAt(id) <= winStart) v.add(id + ": старый клиент ушёл до начала периода");
            }
            boolean f = cs.female(id);
            if (f) women++;
            if (!PersonNames.isFirst(cs.firstName(id), f)) v.add(id + ": имя " + cs.firstName(id) + " не по полу");
            if (!PersonNames.isSurname(cs.lastName(id), f)) v.add(id + ": фамилия " + cs.lastName(id) + " не по полу");
            int age = Period.localDate(t, ZoneOffset.UTC).getYear() - cs.birthYear(id);
            if (age < 18 || age > 90) v.add(id + ": возраст при регистрации " + age);
            if (cs.b2b(id) != (cs.companyName(id) != null)) v.add(id + ": b2b без организации или наоборот");
            if (cs.b2b(id) && PersonNames.mentionsInternal(cs.companyName(id))) v.add(id + ": внутреннее имя в названии организации");
            if (cs.b2b(id)) b2b++;
            acq[cs.acquired[id - 1]]++;
            if (cs.buyer(id) && cs.firstOrderAt(id) < t) v.add(id + ": первый заказ раньше регистрации");
            if (cs.movedAt(id) >= 0 && cs.movedAt(id) < t) v.add(id + ": переезд раньше регистрации");
        }
        double b2bShare = n == 0 ? 0 : (double) b2b / n;
        if (b2bShare < 0.01 || b2bShare > 0.05) v.add("доля b2b " + b2bShare);
        for (int i = 0; i < acq.length; i++) if (acq[i] == 0) v.add("никто не пришёл через " + Customers.ACQUIRED[i]);
        if (pre == 0 || pre == n) v.add("старых клиентов " + pre + " из " + n);
        r.expectNone("customers: id по регистрации, имена по полу, возраст, b2b с организацией", v,
                String.format(Locale.ROOT, "клиентов %d (до начала периода — %d), женщин %d, b2b %d", n, pre, women, b2b));

        // адреса: не раньше регистрации, в окне, у центра своего города, id по времени
        List<String> av = new ArrayList<>();
        int moved = 0;
        Set<Integer> movedCustomers = new TreeSet<>();
        for (int a = 1; a <= cs.addressCount(); a++) {
            int c = cs.addressCustomer(a);
            long t = cs.addressCreatedAt(a);
            if (t < cs.registeredAt(c)) av.add(a + ": адрес раньше регистрации клиента " + c);
            if (t >= winEnd) av.add(a + ": адрес после конца периода");
            if (a > 1 && cs.addressCreatedAt(a - 1) > t) av.add(a + ": id не по времени");
            Geo.City city = g.city(cs.addressCity(a));
            double dist = GeoMath.haversineKm(cs.addressLat(a), cs.addressLon(a), city.lat(), city.lon());
            if (dist > GeoModule.cityRadiusKm(city) * 1.02 + 0.01) av.add(a + ": " + Math.round(dist) + " км от центра");
            if (cs.addressCity(a) != cs.cityId(c) && cs.movedAt(c) >= 0 && t >= cs.movedAt(c)) movedCustomers.add(c);
        }
        moved = movedCustomers.size();
        int withAddr = 0;
        for (int id = 1; id <= n; id++) if (cs.addressesOf(id).length > 0) withAddr++;
        if (moved == 0) av.add("нет переехавших с адресом в новом городе");
        r.expectNone("customers: адреса — не раньше регистрации, в радиусе города, id по времени", av,
                String.format(Locale.ROOT, "адресов %d, клиентов с адресом %d, переехавших с адресом в новом городе %d",
                        cs.addressCount(), withAddr, moved));

        // заложено: одна точка — разные клиенты; вне групп точки не совпадают
        long[] pts = new long[cs.addressCount()];
        for (int a = 1; a <= cs.addressCount(); a++) pts[a - 1] = ((long) cs.aLat[a - 1] << 32) ^ (cs.aLon[a - 1] & 0xffffffffL);
        long[] sorted = pts.clone();
        Arrays.sort(sorted);
        Set<Long> dup = new TreeSet<>();
        for (int i = 1; i < sorted.length; i++) if (sorted[i] == sorted[i - 1]) dup.add(sorted[i]);
        List<String> pv = new ArrayList<>();
        Map<Integer, Set<Integer>> groupCustomers = new TreeMap<>();
        for (int a = 1; a <= cs.addressCount(); a++) {
            int grp = cs.sharedGroup(a);
            boolean d = dup.contains(pts[a - 1]);
            if (grp >= 0) {
                groupCustomers.computeIfAbsent(grp, k -> new TreeSet<>()).add(cs.addressCustomer(a));
                if (!ctx.planted().has("shared_address", a)) pv.add(a + ": группа не заложена в учёт");
            } else if (d) {
                pv.add(a + ": точка совпала с чужой вне заложенных групп");
            }
        }
        int real = 0;
        for (Set<Integer> s : groupCustomers.values()) if (s.size() >= 2) real++;
        int expect = 0;
        // группы, чьи аккаунты успели появиться в окне (второй и далее — до конца периода)
        for (Set<Integer> s : groupCustomers.values()) if (!s.isEmpty()) expect++;
        if (period.to().isAfter(LocalDate.of(2027, 8, 31)) && real < 3) pv.add("групп с общей точкой " + real);
        if (real == 0 && expect > 1) pv.add("ни одной группы из двух и более клиентов");
        r.expectNone("customers: заложено — группы аккаунтов на одной точке; вне групп точки не совпадают", pv,
                "групп в окне " + real);

        // узоры: когорты распродаж удерживаются хуже; регистраций на «чёрной пятнице» больше обычного
        int ev = 0, evOne = 0, other = 0, otherOne = 0;
        long bfDays = 0, bfRegs = 0, novDays = 0, novRegs = 0;
        int[] perDay = new int[(int) ((winEnd - winStart) / DAY)];
        for (int id = 1; id <= n; id++) {
            long t = cs.registeredAt(id);
            if (t < winStart) continue;
            perDay[(int) ((t - winStart) / DAY)]++;
            if (!cs.buyer(id)) continue;
            if (cs.eventCohort(id)) {
                ev++;
                if (cs.oneTimer(id)) evOne++;
            } else if ((cs.flags[id - 1] & Customers.RING) == 0) {
                other++;
                if (cs.oneTimer(id)) otherOne++;
            }
        }
        for (int i = 0; i < perDay.length; i++) {
            LocalDate d = period.from().plusDays(i);
            if (SalesEvent.BLACK_FRIDAY.covers(d)) {
                bfDays++;
                bfRegs += perDay[i];
            } else if (d.getMonthValue() == 11 && !SalesEvent.ELEVEN_ELEVEN.covers(d)) {
                novDays++;
                novRegs += perDay[i];
            }
        }
        List<String> uv = new ArrayList<>();
        double evRate = ev == 0 ? 0 : (double) evOne / ev, otRate = other == 0 ? 0 : (double) otherOne / other;
        if (ev > 50 && evRate <= otRate) uv.add(String.format(Locale.ROOT, "одноразовых в когортах распродаж %.2f ≤ прочих %.2f", evRate, otRate));
        double bf = bfDays == 0 ? 0 : (double) bfRegs / bfDays, nov = novDays == 0 ? 0 : (double) novRegs / novDays;
        if (bfDays > 0 && novDays > 0 && bf < 1.3 * nov) uv.add(String.format(Locale.ROOT, "в «чёрную пятницу» %.1f в сутки против %.1f", bf, nov));
        r.expectNone("customers: узоры — сезонность регистраций, когорты распродаж уходят раньше", uv,
                String.format(Locale.ROOT, "одноразовых: распродажи %.2f, прочие %.2f; регистраций в сутки: «чёрная пятница» %.1f, ноябрь %.1f",
                        evRate, otRate, bf, nov));
    }
}
