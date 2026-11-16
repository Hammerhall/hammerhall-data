package io.hammerhall.data.sto.market;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.money.Money;
import io.hammerhall.data.core.money.Tax;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.sto.StoKeys;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.Catalog.Assortment;
import io.hammerhall.data.sto.catalog.Catalog.Product;
import io.hammerhall.data.sto.catalog.CatalogModule;
import io.hammerhall.data.sto.catalog.CategoryTree;
import io.hammerhall.data.sto.catalog.Names;
import io.hammerhall.data.sto.catalog.PriceBook;
import io.hammerhall.data.sto.catalog.PricingModule;
import io.hammerhall.data.sto.customers.PersonNames;
import io.hammerhall.data.sto.geo.Geo;
import io.hammerhall.data.sto.geo.GeoModule;
import io.hammerhall.data.sto.market.Market.Listing;
import io.hammerhall.data.sto.market.Market.Model;
import io.hammerhall.data.sto.market.Market.Quality;
import io.hammerhall.data.sto.market.Market.Seller;
import io.hammerhall.data.sto.market.Market.TaxRegime;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Маркетплейс: продавцы (≈ 3 000, все выдуманные) и их предложения (≈ 150 тыс. строк-периодов цены). Мир — как
 * каталог: одинаков во всех профилях.
 *
 * <p><b>Продавцы</b> — план на фиксированной оси (площадка открылась {@link #MARKET_START}, план — по
 * {@link #PLAN_END}): подключения по месяцам растут, часть продавцов уходит в первые месяцы, остальные живут годами.
 * В выпуск — кто работал хотя бы момент внутри периода; id — по дате подключения, поэтому продление периода дописывает
 * новых продавцов в хвост. Название — юрформа и выдуманный корень из слогов (тот же генератор и отсев настоящих марок,
 * что у брендов; с брендами не совпадает) или ИП с выдуманным сочетанием фамилии, имени и отчества.
 *
 * <p><b>Предложения</b> — у товаров «оба» и «только продавцы»: листинги (продавец × товар) приходят потоком
 * Пуассона за жизнь товара (у новинки — волна при запуске), живут месяцами, кончаются с уходом продавца или снятием
 * товара; у товара «только продавцы» на любой момент жизни есть хотя бы один листинг. Число одновременных листингов —
 * от популярности товара: у популярных — десятки, у хвоста — один-два. Продавец выбирается среди работающих в отделе
 * каталога, по весу. Цена листинга пересматривается раз в несколько месяцев (и в дни смены своей ставки НДС и
 * техсбора) — относительно цены магазина на момент пересмотра, со своей наценкой и шумом; освобождённые от НДС
 * продавцы чуть дешевле; округление — по привычке продавца.
 */
public final class MarketModule implements Module {
    public static final String NAME = "market";
    static final LocalDate MARKET_START = LocalDate.of(2020, 6, 1);
    static final LocalDate PLAN_END = LocalDate.of(2036, 1, 1);
    /** Листинги планируются до этой даты (фиксировано: продление в её пределах ничего не меняет). */
    static final LocalDate OFFER_PLAN_END = LocalDate.of(2032, 1, 1);
    /** Окно v1 — для решений, которые не должны зависеть от настроенного периода (кто «плохой» продавец). */
    static final LocalDate REF_FROM = LocalDate.of(2025, 10, 1), REF_TO = LocalDate.of(2027, 10, 30);
    static final long DAY = 86_400L;
    /** Средняя жизнь листинга, дней. */
    static final double LISTING_DAYS = 300;
    /** Насколько цена продавца следует за его собственной ставкой НДС против ставки магазина (0 — никак, 1 — целиком). */
    static final double VAT_PASS = 0.35;

    /** Отделы каталога (категории 2-го уровня) и их вес на площадке. */
    static final String[] DEPTS = {"Phones and gadgets", "Computers and laptops", "TV, audio and video", "Photo and video",
            "Gaming and entertainment", "Large appliances", "Kitchen appliances", "Home appliances", "Beauty and health",
            "Smart home and lighting", "Tools", "Home and kitchen", "Car electronics"};
    static final double[] DEPT_W = {3.0, 1.6, 1.4, 0.6, 0.9, 0.5, 1.2, 1.0, 1.5, 1.0, 1.2, 1.5, 1.0};

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return List.of(GeoModule.NAME, CatalogModule.NAME, PricingModule.NAME);
    }

    @Override
    public List<String> tables() {
        return List.of("sellers", "offers");
    }

    // ================================================================ план продавцов

    /** Продавец плана (до отбора окном и выдачи id). */
    static final class Plan {
        int key;
        long joined, left;
        String form;
        int city;
        Model model;
        TaxRegime tax;
        long rev2025;
        double growth, weight, markup, repriceDays;
        /** Вес при выборе продавца для листинга: weight^0,6 — мелкие тоже получают товары. */
        double pickW;
        /** Моменты (полночь по Москве), с которых меняется ставка НДС продавца. */
        long[] vatChanges;
        int[] depts;
        int rounding, handling;
        Quality quality = Quality.NORMAL;
        double returnF, scoreShift, lateF, cancelF, answerRate;
        int id = -1;
        String name;
    }

    /** Подключений в месяц: рост от открытия площадки, затем — ровно. */
    static double joinsPerMonth(YearMonth m) {
        double t = m.getYear() + (m.getMonthValue() - 1) / 12.0;
        double[][] pts = {{2020.4, 10}, {2022.0, 34}, {2024.0, 50}, {2026.0, 54}, {2028.0, 58}, {2040.0, 58}};
        for (int i = 0; i + 1 < pts.length; i++) {
            if (t >= pts[i][0] && t < pts[i + 1][0]) {
                double base = pts[i][1] + (pts[i + 1][1] - pts[i][1]) * (t - pts[i][0]) / (pts[i + 1][0] - pts[i][0]);
                // осенью перед сезоном подключаются чаще
                int mo = m.getMonthValue();
                return base * (mo >= 8 && mo <= 10 ? 1.25 : (mo == 12 || mo == 1 ? 0.8 : 1.0));
            }
        }
        return pts[0][1];
    }

    static int[] pickDepts(Rng r, int n) {
        double[] w = DEPT_W.clone();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int k = r.weighted(w);
            out[i] = k;
            w[k] = 0;
        }
        Arrays.sort(out);
        return out;
    }

    static List<Plan> planSellers(GenContext ctx, Geo geo) {
        Period period = ctx.period();
        RandomStreams.Stream months = ctx.stream("market.seller_months");
        RandomStreams.Stream st = ctx.stream("market.sellers");
        // города: Москва и Петербург — львиная доля, прочие — по населению^1,2 (от 150 тыс.)
        Geo.City moscow = geo.cityByName("Moscow"), spb = geo.cityByName("Saint Petersburg");
        List<Geo.City> others = new ArrayList<>();
        for (Geo.City c : geo.cities()) if (c.population() >= 150_000 && c != moscow && c != spb) others.add(c);
        double[] ow = new double[others.size()];
        for (int i = 0; i < ow.length; i++) ow[i] = StrictMath.pow(others.get(i).population(), 1.2);
        long planEnd = period.businessMidnight(PLAN_END);
        List<Plan> out = new ArrayList<>();
        int mi = 0;
        for (YearMonth m = YearMonth.from(MARKET_START); m.isBefore(YearMonth.from(PLAN_END)); m = m.plusMonths(1), mi++) {
            int n = months.at(mi).poisson(joinsPerMonth(m));
            for (int i = 0; i < n; i++) {
                Rng r = st.at(mi, i);
                Plan p = new Plan();
                p.key = mi * 1000 + i;
                LocalDate day = m.atDay(1 + r.nextInt(m.lengthOfMonth()));
                p.joined = period.businessMidnight(day) + (9 + r.nextInt(12)) * 3600L + r.nextInt(3600);
                double life = r.chance(0.2) ? r.uniform(60, 300) : r.exponential(6 * 365.25);
                long left = p.joined + (long) (life * DAY);
                p.left = left >= planEnd ? Long.MAX_VALUE : left;
                double u = r.nextDouble();
                p.form = u < 0.47 ? "ИП" : (u < 0.94 ? "ООО" : "АО");
                p.weight = r.logNormal(1, 1.15) * (p.form.equals("АО") ? 3.0 : p.form.equals("ООО") ? 1.3 : 0.6);
                p.model = r.chance(p.form.equals("ИП") ? 0.40 : p.form.equals("ООО") ? 0.55 : 0.75) ? Model.FBO : Model.FBS;
                double usn = p.form.equals("ИП") ? 0.92 : p.form.equals("ООО") ? 0.60 : 0.08;
                if (p.weight > 8) usn *= 0.3;
                p.tax = r.chance(usn) ? TaxRegime.USN : TaxRegime.OSN;
                p.growth = r.uniform(0.88, 1.35);
                if (p.tax == TaxRegime.USN) {
                    double med = p.form.equals("ИП") ? 9e6 : 28e6;
                    double cap = 380e6 / StrictMath.pow(p.growth, 3);
                    p.rev2025 = StrictMath.round(Math.max(1e6, Math.min(cap, r.logNormal(med, 1.0))));
                } else {
                    p.rev2025 = StrictMath.round(r.logNormal(150e6 * StrictMath.sqrt(p.weight), 0.8));
                }
                double cu = r.nextDouble();
                int nd = (cu < 0.45 ? 1 : cu < 0.80 ? 2 : 3) + (p.form.equals("АО") ? 1 : 0);
                p.depts = pickDepts(r, nd);
                p.markup = r.logNormal(1.0, 0.035) * (p.form.equals("ИП") ? 0.99 : 1.0);
                double ru = r.nextDouble();
                p.rounding = ru < 0.55 ? 0 : (ru < 0.80 ? 1 : 2);
                p.repriceDays = Math.max(30, Math.min(300, r.logNormal(110, 0.45)));
                p.handling = p.model == Model.FBO ? 0 : (p.form.equals("ИП") ? r.between(1, 3) : r.between(1, 2));
                double cc = r.nextDouble();
                p.city = cc < 0.36 ? moscow.id() : (cc < 0.47 ? spb.id() : others.get(r.weighted(ow)).id());
                p.returnF = r.logNormal(1, 0.15);
                p.scoreShift = r.gaussianClamped(0, 0.12, -0.35, 0.35);
                p.lateF = r.logNormal(1, 0.2);
                p.cancelF = r.logNormal(1, 0.2);
                p.answerRate = r.uniform(0.1, 0.8);
                out.add(p);
            }
        }
        out.sort(Comparator.comparingLong((Plan p) -> p.joined).thenComparingInt(p -> p.key));
        return out;
    }

    /**
     * Узор «плохой продавец»: один крупный продавец FBS (ООО) с частыми возвратами, низкими оценками, опозданиями и
     * низкими ценами (ими и привлекает покупателей) и три «похуже среднего». Выбор — по окну v1 (не по настроенному
     * периоду): работают с полугода до начала окна и до его конца.
     */
    static void assignQuality(GenContext ctx, Period period, List<Plan> plan) {
        long from = period.businessMidnight(REF_FROM.minusDays(180)), to = period.businessMidnight(REF_TO.plusDays(1));
        List<Plan> cand = new ArrayList<>();
        for (Plan p : plan) if (p.joined < from && p.left > to && !p.form.equals("ИП")) cand.add(p);
        cand.sort(Comparator.comparingDouble((Plan p) -> -p.weight).thenComparingInt(p -> p.key));
        RandomStreams.Stream st = ctx.stream("market.quality");
        List<Plan> fbs = new ArrayList<>();
        for (Plan p : cand) if (p.model == Model.FBS && p.form.equals("ООО")) fbs.add(p);
        Plan bad = fbs.get(st.at(0).nextInt(Math.min(8, fbs.size())));
        bad.quality = Quality.BAD;
        bad.weight = Math.max(bad.weight, 22);
        bad.markup = 0.87;
        bad.returnF = 4.0;
        bad.scoreShift = -1.6;
        bad.lateF = 3.0;
        bad.cancelF = 2.5;
        bad.answerRate = 0.03;
        if (Arrays.stream(bad.depts).noneMatch(d -> d == 0)) {
            int[] d = Arrays.copyOf(bad.depts, bad.depts.length + 1);
            d[d.length - 1] = 0;
            Arrays.sort(d);
            bad.depts = d;
        }
        for (int j = 0, made = 0; made < 3 && j < 200; j++) {
            Plan p = cand.get(10 + st.at(1, j).nextInt(Math.min(70, cand.size() - 10)));
            if (p.quality != Quality.NORMAL) continue;
            Rng r = st.at(2, made);
            p.quality = Quality.POOR;
            p.returnF = r.uniform(1.8, 2.3);
            p.scoreShift = -r.uniform(0.5, 0.7);
            p.lateF = r.uniform(1.6, 2.0);
            p.cancelF = r.uniform(1.4, 1.8);
            p.answerRate = r.uniform(0.05, 0.15);
            made++;
        }
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static final String[][] STORE_SUFFIX = {{" Маркет"}, {" Трейд"}, {" Электроникс"}, {" Дом"}, {" Техно"},
            {" Онлайн"}, {" Снаб"}, {" Опт"}};

    /** Названия продавцов окна — по id: корень из слогов брендов (не совпадает с брендами и между собой) или ИП. */
    static void nameSellers(GenContext ctx, Catalog cat, List<Plan> window) {
        RandomStreams.Stream st = ctx.stream("market.seller_names");
        Set<String> taken = new TreeSet<>();
        for (Catalog.Brand b : cat.brands()) taken.add(norm(b.name()));
        taken.add("kremen");
        taken.add("ognivo");
        taken.add("kresalo");
        Set<String> names = new TreeSet<>();
        for (Plan p : window) {
            Rng r = st.at(p.key);
            String name = null;
            while (name == null || !names.add(name)) {
                if (p.form.equals("ИП")) {
                    boolean female = r.chance(0.4);
                    int birth = r.between(1962, 2001);
                    int sur = PersonNames.pickSurname(r), first = PersonNames.pickFirst(r, female, birth);
                    name = "ИП " + PersonNames.surname(sur, female) + " " + PersonNames.first(female, first) + " "
                            + PersonNames.patronymic(r, female, birth);
                } else {
                    String root = Names.toCyrillic(Names.brand(r, taken));
                    // латинский корень уже сверен; сверить и запись кириллицей (обратная транслитерация бывает иной)
                    if (Names.resemblesReal(Names.toLatin(root))) {
                        name = null;
                        continue;
                    }
                    String sfx = r.chance(0.3) ? STORE_SUFFIX[r.nextInt(STORE_SUFFIX.length)][0] : "";
                    name = p.form + " «" + root + sfx + "»";
                    if (PersonNames.mentionsInternal(name)) name = null;
                }
            }
            p.name = name;
        }
    }

    // ================================================================ листинги и цены

    /** Листинг плана: продавец (индекс в плане), начало, конец. */
    private record Draft(int seller, long from, long to) {
    }

    /** Одновременных листингов у товара — от популярности (константы — абсолютные, не от состава каталога). */
    static double concurrency(Product p) {
        double c = StrictMath.pow(Math.max(1e-6, p.popularity), 1.15);
        return Math.min(40, p.assortment == Assortment.ONLY_3P ? 0.5 + 0.35 * c : 0.15 * c);
    }

    /** Смены ставки НДС продавца на горизонте листингов: первые числа месяцев, где ставка не та, что накануне. */
    static long[] vatChanges(Period period, Plan p) {
        List<Long> out = new ArrayList<>();
        int prev = Market.vatRate(p.tax, p.rev2025, p.growth, LocalDate.of(2024, 12, 31));
        for (YearMonth m = YearMonth.of(2025, 1); m.isBefore(YearMonth.from(OFFER_PLAN_END)); m = m.plusMonths(1)) {
            int rate = Market.vatRate(p.tax, p.rev2025, p.growth, m.atDay(1));
            if (rate != prev) out.add(period.businessMidnight(m.atDay(1)));
            prev = rate;
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * Самое раннее начало листинга товара: полночь UTC дня запуска (не полночь по Москве — иначе товар, запущенный
     * на следующий день после конца периода, попал бы в окно тремя часами), но не раньше открытия площадки.
     */
    static long listingStart(Period period, Product p) {
        return Math.max(p.launchedOn.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond(), period.businessMidnight(MARKET_START));
    }

    private static int deptIndex(Product p, CategoryTree tree) {
        String d = tree.node(p.category.departmentId).nameEn;
        for (int i = 0; i < DEPTS.length; i++) if (DEPTS[i].equals(d)) return i;
        throw new IllegalStateException("отдел не в списке: " + d);
    }

    /** Продавец, работающий в момент {@code t} в отделе, — по весу; {@code -1} — нет свободного. */
    private static int pickSeller(Rng r, List<Plan> plan, int[] cand, long t, Set<Integer> used) {
        double total = 0;
        for (int i : cand) {
            Plan p = plan.get(i);
            if (p.joined <= t && p.left > t + 7 * DAY && !used.contains(i)) total += p.pickW;
        }
        if (total <= 0) return -1;
        double x = r.nextDouble() * total;
        int last = -1;
        for (int i : cand) {
            Plan p = plan.get(i);
            if (p.joined <= t && p.left > t + 7 * DAY && !used.contains(i)) {
                last = i;
                x -= p.pickW;
                if (x < 0) return i;
            }
        }
        return last;
    }

    private static long roundPrice(double rub, int style) {
        return switch (style) {
            case 0 -> Money.retail(rub);
            case 1 -> Math.max(10, StrictMath.round(rub)) * 100L;
            default -> Math.max(1, StrictMath.round(rub / 10.0)) * 1000L - 100L;
        };
    }

    /** Растущие колонки строк предложений. */
    private static final class Rows {
        int n;
        int[] seller = new int[1 << 16], product = new int[1 << 16];
        long[] from = new long[1 << 16], to = new long[1 << 16], price = new long[1 << 16];

        void add(int s, int p, long f, long t, long pr) {
            if (n == seller.length) {
                int c = n * 2;
                seller = Arrays.copyOf(seller, c);
                product = Arrays.copyOf(product, c);
                from = Arrays.copyOf(from, c);
                to = Arrays.copyOf(to, c);
                price = Arrays.copyOf(price, c);
            }
            seller[n] = s;
            product[n] = p;
            from[n] = f;
            to[n] = t;
            price[n] = pr;
            n++;
        }
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        Period period = ctx.period();
        Geo geo = ctx.get(StoKeys.GEO);
        Catalog cat = ctx.get(StoKeys.CATALOG);
        PriceBook book = ctx.get(StoKeys.PRICES);
        long winStart = period.startEpoch(), winEnd = period.endEpochExclusive();

        List<Plan> plan = planSellers(ctx, geo);
        assignQuality(ctx, period, plan);
        for (Plan p : plan) {
            p.vatChanges = vatChanges(period, p);
            p.pickW = StrictMath.pow(p.weight, 0.6);
        }
        List<Plan> window = new ArrayList<>();
        for (Plan p : plan) if (p.joined < winEnd && p.left > winStart) window.add(p);
        for (int i = 0; i < window.size(); i++) window.get(i).id = i + 1;
        nameSellers(ctx, cat, window);
        ctx.log("  market: продавцов в плане " + plan.size() + ", в окне " + window.size());

        // продавцы по отделам (индексы плана)
        List<List<Integer>> byDept = new ArrayList<>();
        for (int d = 0; d < DEPTS.length; d++) byDept.add(new ArrayList<>());
        for (int i = 0; i < plan.size(); i++) for (int d : plan.get(i).depts) byDept.get(d).add(i);
        int[][] deptCand = new int[DEPTS.length][];
        for (int d = 0; d < DEPTS.length; d++) deptCand[d] = byDept.get(d).stream().mapToInt(Integer::intValue).toArray();
        int[] all = new int[plan.size()];
        for (int i = 0; i < all.length; i++) all[i] = i;

        RandomStreams.Stream lst = ctx.stream("market.listings");
        RandomStreams.Stream pst = ctx.stream("market.prices");
        long offerEnd = period.businessMidnight(OFFER_PLAN_END);
        long feeAt = period.businessMidnight(Tax.TECH_FEE_FROM);
        Rows rows = new Rows();
        int listingsTotal = 0;
        for (Product p : cat.products()) {
            if (p.assortment == Assortment.ONLY_1P || p.bundle) continue;
            long l0 = listingStart(period, p);
            long l1 = p.discontinuedPlan != null ? Math.min(period.businessMidnight(p.discontinuedPlan), offerEnd) : offerEnd;
            if (l0 >= l1) continue;
            long pkey = RandomStreams.fnv64(p.planKey);
            Rng r = lst.at(pkey);
            int[] cand = deptCand[deptIndex(p, cat.tree())];
            double nc = concurrency(p);
            List<Long> starts = new ArrayList<>();
            if (!p.launchedOn.isBefore(MARKET_START)) {
                int k0 = r.poisson(0.5 * nc);
                for (int j = 0; j < k0; j++) starts.add(l0 + (long) (r.exponential(10) * DAY));
            }
            double gapDays = LISTING_DAYS / Math.max(nc, 1e-4);
            for (long t = l0; nc >= 1e-4; ) {
                t += (long) (r.exponential(gapDays) * DAY);
                if (t >= l1) break;
                starts.add(t);
            }
            starts.sort(null);
            Set<Integer> used = new TreeSet<>();
            List<Draft> drafts = new ArrayList<>();
            for (long s : starts) {
                if (s >= l1) continue;
                int si = pickSeller(r, plan, cand, s, used);
                if (si < 0) continue;
                used.add(si);
                long dur = (long) (Math.max(14, r.exponential(LISTING_DAYS)) * DAY);
                drafts.add(new Draft(si, s, Math.min(Math.min(s + dur, plan.get(si).left), l1)));
            }
            if (p.assortment == Assortment.ONLY_3P) {
                // у товара «только продавцы» на любой момент жизни — хотя бы один листинг
                drafts.sort(Comparator.comparingLong(Draft::from));
                List<Draft> anchors = new ArrayList<>();
                long cur = l0, reach = l0;
                int i = 0;
                Rng ra = lst.at(pkey, 1);
                while (cur < l1) {
                    while (i < drafts.size() && drafts.get(i).from <= cur) reach = Math.max(reach, drafts.get(i++).to);
                    if (reach > cur) {
                        cur = reach;
                        continue;
                    }
                    int si = pickSeller(ra, plan, cand, cur, used);
                    if (si < 0) si = pickSeller(ra, plan, all, cur, used);
                    if (si < 0) {
                        // никто ещё не работает (первые часы площадки) — подождать сутки
                        cur += DAY;
                        reach = cur;
                        continue;
                    }
                    used.add(si);
                    long dur = (long) (Math.max(60, ra.exponential(2 * LISTING_DAYS)) * DAY);
                    Draft a = new Draft(si, cur, Math.min(Math.min(cur + dur, plan.get(si).left), l1));
                    anchors.add(a);
                    reach = a.to;
                    cur = reach;
                }
                drafts.addAll(anchors);
            }
            // цены листингов
            for (Draft d : drafts) {
                if (d.to <= winStart || d.from >= winEnd) continue;
                Plan s = plan.get(d.seller);
                Rng rp = pst.at(pkey, s.key);
                double markup = s.markup * rp.logNormal(1, 0.03) * (p.assortment == Assortment.ONLY_3P ? 1.0 : 0.985);
                double noise = 0;
                long m = d.from, ref = -1;
                long curFrom = -1, curPrice = -1;
                boolean any = false;
                while (m < d.to && m < winEnd) {
                    // пересмотр — раз в несколько недель, а также в день смены своей ставки НДС и техсбора
                    long next = m + (long) (Math.max(3, rp.exponential(s.repriceDays)) * DAY);
                    if (p.kind.feeClass != null && feeAt > m && feeAt < next) next = feeAt;
                    for (long vc : s.vatChanges) if (vc > m && vc < next) next = vc;
                    noise = 0.5 * noise + 0.03 * rp.gaussian();
                    long r1p = book.priceAt(p.id, Math.max(m, winStart));
                    if (r1p > 0) ref = r1p;
                    if (ref <= 0) ref = p.launchPriceKop;
                    LocalDate day = period.businessDate(Math.max(m, winStart));
                    double vat = StrictMath.pow((100.0 + Market.vatRate(s.tax, s.rev2025, s.growth, day))
                            / (100.0 + Tax.vatRate(day)), VAT_PASS);
                    long price = roundPrice(ref / 100.0 * markup * vat * StrictMath.exp(noise), s.rounding);
                    if (price != curPrice) {
                        if (curPrice > 0) any |= emit(rows, s.id, p.id, curFrom, m, curPrice, winStart, winEnd);
                        curFrom = m;
                        curPrice = price;
                    }
                    m = next;
                }
                long end = d.to >= winEnd ? Long.MAX_VALUE : d.to;
                if (curPrice > 0) any |= emit(rows, s.id, p.id, curFrom, end, curPrice, winStart, winEnd);
                if (any) {
                    listingsTotal++;
                    if (s.id < 0) throw new IllegalStateException("листинг продавца вне окна: " + s.key);
                }
            }
        }
        ctx.log("  market: листингов в окне " + listingsTotal + ", строк " + rows.n);
        double[] conc = cat.products().stream().filter(p -> p.assortment != Assortment.ONLY_1P && !p.bundle)
                .mapToDouble(MarketModule::concurrency).sorted().toArray();
        double[] pops = cat.products().stream().filter(p -> p.assortment != Assortment.ONLY_1P && !p.bundle)
                .mapToDouble(p -> p.popularity).sorted().toArray();
        if (conc.length > 0) {
            ctx.log(String.format(Locale.ROOT, "  market: товаров с предложениями %d; одновременных листингов (план): медиана %.1f, "
                            + "90%% %.1f, 99%% %.1f, макс %.1f; популярность: медиана %.3g, 99%% %.3g, макс %.3g", conc.length,
                    conc[conc.length / 2], conc[conc.length * 9 / 10], conc[conc.length * 99 / 100], conc[conc.length - 1],
                    pops[pops.length / 2], pops[pops.length * 99 / 100], pops[pops.length - 1]));
        }

        // id строк — по (начало, продавец, товар): продление дописывает новые строки в хвост
        Integer[] idx = new Integer[rows.n];
        for (int i = 0; i < rows.n; i++) idx[i] = i;
        final Rows rr = rows;
        Arrays.sort(idx, Comparator.<Integer>comparingLong(i -> rr.from[i]).thenComparingInt(i -> rr.seller[i])
                .thenComparingInt(i -> rr.product[i]));
        int n = rows.n;
        int[] rs = new int[n], rpr = new int[n];
        long[] rf = new long[n], rt = new long[n], rp = new long[n];
        for (int i = 0; i < n; i++) {
            int k = idx[i];
            rs[i] = rows.seller[k];
            rpr[i] = rows.product[k];
            rf[i] = rows.from[k];
            rt[i] = rows.to[k];
            rp[i] = rows.price[k];
        }

        List<Seller> sellers = new ArrayList<>();
        TableSink ss = ctx.sink("sellers");
        for (Plan p : window) {
            Long left = p.left >= winEnd ? null : p.left;
            ss.add(p.id, p.name, p.city, p.model.code, p.joined, left, p.tax.code);
            List<Integer> depts = new ArrayList<>();
            for (int d : p.depts) depts.add(deptId(cat.tree(), d));
            sellers.add(new Seller(p.id, p.name, p.form, p.city, p.model, p.joined, left == null ? Long.MAX_VALUE : left,
                    p.tax, p.rev2025, p.growth, p.weight, List.copyOf(depts), p.handling, p.quality, p.returnF,
                    p.scoreShift, p.lateF, p.cancelF, p.answerRate));
        }
        ss.close();
        TableSink so = ctx.streamSink("offers");
        for (int i = 0; i < n; i++) so.add(i + 1, rs[i], rpr[i], rf[i], rt[i] == Long.MAX_VALUE ? null : rt[i], rp[i]);
        so.close();
        ctx.put(StoKeys.MARKET, new Market(sellers, cat.products().size(), rs, rpr, rf, rt, rp));
    }

    /** Строка в окне: обрезка началом окна; конец за окном — «без конца». */
    private static boolean emit(Rows rows, int seller, int product, long from, long to, long price, long winStart,
                                long winEnd) {
        if (to <= winStart || from >= winEnd) return false;
        long f = Math.max(from, winStart);
        long t = to >= winEnd ? Long.MAX_VALUE : to;
        rows.add(seller, product, f, t, price);
        return true;
    }

    private static int deptId(CategoryTree tree, int idx) {
        for (CategoryTree.Node n : tree.nodes()) if (n.level == 2 && n.nameEn.equals(DEPTS[idx])) return n.id;
        throw new IllegalStateException("нет отдела " + DEPTS[idx]);
    }

    // ================================================================ проверки

    @Override
    public void check(GenContext ctx, CheckReport r) {
        Period period = ctx.period();
        Market mk = ctx.get(StoKeys.MARKET);
        Catalog cat = ctx.get(StoKeys.CATALOG);
        PriceBook book = ctx.get(StoKeys.PRICES);
        long winStart = period.startEpoch(), winEnd = period.endEpochExclusive();

        // продавцы
        List<String> v = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        int fbo = 0, usn = 0;
        for (Seller s : mk.sellers()) {
            if (!names.add(s.name())) v.add(s.id() + ": повтор названия " + s.name());
            if (s.joinedAt() >= winEnd) v.add(s.id() + ": подключился после конца периода");
            if (s.leftAt() <= s.joinedAt()) v.add(s.id() + ": ушёл раньше, чем подключился");
            if (s.leftAt() != Long.MAX_VALUE && (s.leftAt() <= winStart || s.leftAt() >= winEnd)) v.add(s.id() + ": уход вне окна");
            if (s.id() > 1 && mk.seller(s.id() - 1).joinedAt() > s.joinedAt()) v.add(s.id() + ": id не по дате подключения");
            if (PersonNames.mentionsInternal(s.name())) v.add(s.id() + ": внутреннее имя в названии");
            if (!s.legalForm().equals("ИП")) {
                String root = s.name().substring(s.name().indexOf('«') + 1, s.name().length() - 1).split(" ")[0];
                if (Names.resemblesReal(Names.toLatin(root))) v.add(s.id() + ": похоже на настоящую марку " + root);
            }
            if (s.fbo()) fbo++;
            if (s.taxRegime() == TaxRegime.USN) usn++;
        }
        int n = mk.sellers().size();
        if (n < 2000 || n > 4500) v.add("продавцов " + n + " — вне ожидаемых 2–4,5 тыс.");
        if (fbo < n * 0.3 || fbo > n * 0.7) v.add("доля FBO " + fbo + "/" + n);
        r.expectNone("market: продавцы — названия, даты, id по подключению, FBO и FBS", v,
                "продавцов " + n + ", FBO " + fbo + ", на УСН " + usn);

        // НДС продавцов на УСН: доля освобождённых падает с порогом 2025 → 2026 → 2027
        List<String> tv = new ArrayList<>();
        int[] exempt = new int[3], usnAlive = new int[3];
        for (Seller s : mk.sellers()) {
            if (s.taxRegime() != TaxRegime.USN) continue;
            for (int y = 0; y < 3; y++) {
                LocalDate d = LocalDate.of(2025 + y, 6, 1);
                usnAlive[y]++;
                int rate = mk.vatRate(s.id(), d);
                if (rate == 0) exempt[y]++;
                if (rate != 0 && rate != 5 && rate != 7) tv.add(s.id() + ": ставка " + rate);
            }
            for (LocalDate d : List.of(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1))) {
                int rate = mk.vatRate(s.id(), d);
                if (rate != 0 && rate != 5 && rate != 7) tv.add(s.id() + ": ставка " + rate);
            }
        }
        for (Seller s : mk.sellers()) {
            if (s.taxRegime() == TaxRegime.OSN && mk.vatRate(s.id(), LocalDate.of(2026, 1, 1)) != 22) tv.add(s.id() + ": ОСН не 22 %");
        }
        if (!(exempt[0] > exempt[1] && exempt[1] > exempt[2])) {
            tv.add("освобождённых не убывает: " + Arrays.toString(exempt));
        }
        r.expectNone("market: НДС продавцов — ставки 0/5/7 на УСН, порог освобождения снижается по годам", tv,
                "освобождено на 1 июня 2025/2026/2027: " + exempt[0] + "/" + exempt[1] + "/" + exempt[2] + " из " + usnAlive[0]);

        // узор «плохой продавец»
        List<Seller> bad = mk.ofQuality(Quality.BAD), poor = mk.ofQuality(Quality.POOR);
        List<String> qv = new ArrayList<>();
        if (bad.size() != 1) qv.add("плохих " + bad.size());
        if (poor.size() < 2 || poor.size() > 3) qv.add("похуже среднего " + poor.size());
        for (Seller s : bad) {
            if (s.joinedAt() > winStart || s.leftAt() < winEnd) qv.add("плохой работает не весь период");
            int rows = 0;
            for (Listing l : mk.listingsOfSeller(s.id())) rows += l.offerIds().length;
            if (rows < 50) qv.add("у плохого мало предложений: " + rows);
        }
        r.expectNone("market: узор — один плохой продавец и 2–3 похуже среднего, работают весь период", qv,
                "плохой " + (bad.isEmpty() ? "—" : bad.getFirst().id()) + ", похуже — " + poor.stream().map(Seller::id).toList());

        // предложения: периоды листинга встык, внутри жизни товара и продавца, ассортимент, цена
        List<String> ov = new ArrayList<>();
        int listings3p = 0;
        for (Listing l : mk.listings()) {
            Product p = cat.product(l.productId());
            Seller s = mk.seller(l.sellerId());
            if (p.assortment == Assortment.ONLY_1P) ov.add(l.productId() + ": у товара «только магазин» предложение");
            if (p.assortment == Assortment.ONLY_3P) listings3p++;
            long lifeFrom = Math.max(period.businessMidnight(p.launchedOn), winStart);
            long lifeTo = p.discontinuedOn != null ? period.businessMidnight(p.discontinuedOn) : Long.MAX_VALUE;
            int[] ids = l.offerIds();
            for (int k = 0; k < ids.length; k++) {
                long f = mk.offerFrom(ids[k]), t = mk.offerTo(ids[k]);
                if (t <= f) ov.add(ids[k] + ": пустой период");
                if (k + 1 < ids.length && mk.offerFrom(ids[k + 1]) != t) ov.add(ids[k] + ": разрыв или наложение периодов");
                if (k + 1 < ids.length && mk.offerPriceKop(ids[k + 1]) == mk.offerPriceKop(ids[k])) ov.add(ids[k] + ": два периода с одной ценой подряд");
                if (f < lifeFrom || (lifeTo != Long.MAX_VALUE && (t == Long.MAX_VALUE || t > lifeTo))) ov.add(ids[k] + ": вне жизни товара");
                if (f < s.joinedAt() || (s.leftAt() != Long.MAX_VALUE && (t == Long.MAX_VALUE || t > s.leftAt()))) ov.add(ids[k] + ": вне жизни продавца");
                if (f < winStart || f >= winEnd || (t != Long.MAX_VALUE && t > winEnd)) ov.add(ids[k] + ": вне окна");
                long ref = book.priceAt(p.id, f);
                double ratio = ref > 0 ? (double) mk.offerPriceKop(ids[k]) / ref : 0;
                if (mk.offerPriceKop(ids[k]) <= 0 || ratio < 0.45 || ratio > 2.2) {
                    ov.add(ids[k] + ": цена " + Money.format(mk.offerPriceKop(ids[k])) + " при цене магазина " + Money.format(ref));
                }
            }
        }
        for (int id = 2; id <= mk.offerCount(); id++) if (mk.offerFrom(id) < mk.offerFrom(id - 1)) ov.add(id + ": id не по началу");
        r.expectNone("market: предложения — периоды встык без наложений, в жизни товара и продавца, цена у цены магазина",
                ov, "строк " + mk.offerCount() + ", листингов " + mk.listings().size() + " (у товаров «только продавцы» — " + listings3p + ")");

        // покрытие «только продавцы» и концентрация: выборочно по моментам
        RandomStreams.Stream st = ctx.stream("market.check");
        List<String> cv = new ArrayList<>();
        int probes = 0, maxConc = 0, ones = 0, probes3 = 0;
        for (Product p : cat.products()) {
            if (p.assortment == Assortment.ONLY_1P || p.bundle) continue;
            long a = Math.max(listingStart(period, p), winStart);
            long b = p.discontinuedOn != null ? period.businessMidnight(p.discontinuedOn) : winEnd;
            if (b <= a) continue;
            Rng rr = st.at(p.id);
            for (int i = 0; i < 2; i++) {
                long t = a + (long) (rr.nextDouble() * (b - a));
                int c = mk.offersAt(p.id, t).size();
                probes++;
                maxConc = Math.max(maxConc, c);
                if (p.assortment == Assortment.ONLY_3P) {
                    probes3++;
                    if (c == 0) {
                        StringBuilder sb = new StringBuilder();
                        for (Listing l : mk.listingsOf(p.id)) sb.append(' ').append(l.from()).append('-').append(l.to());
                        cv.add(p.id + ": нет предложений на " + t + " (запуск " + p.launchedOn + ", листинги" + sb + ")");
                    }
                    if (c == 1) ones++;
                }
            }
        }
        if (maxConc < 20) cv.add("у самых популярных — всего " + maxConc + " предложений одновременно");
        if (mk.offerCount() < 100_000 || mk.offerCount() > 220_000) cv.add("строк предложений " + mk.offerCount() + " — вне 100–220 тыс.");
        r.expectNone("market: у товара «только продавцы» всегда есть предложение; у популярных — десятки, хвост — один-два",
                cv, "проверок " + probes + ", наибольшее одновременно " + maxConc + ", с одним предложением "
                        + ones + " из " + probes3);
    }
}
