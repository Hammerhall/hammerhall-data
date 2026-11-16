package io.hammerhall.data.sto.catalog;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.money.Money;
import io.hammerhall.data.core.money.Tax;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.core.time.RuCalendar;
import io.hammerhall.data.core.time.RuCalendar.SalesEvent;
import io.hammerhall.data.sto.StoKeys;
import io.hammerhall.data.sto.catalog.Catalog.Product;
import io.hammerhall.data.sto.catalog.Promotions.Promotion;
import io.hammerhall.data.sto.catalog.Promotions.Target;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Цены и акции. Акции: события календаря (11.11, «чёрная пятница», Новый год, 23.02, 8.03, май, лето, школа, зимняя
 * распродажа), «скидки недели», недели брендов, «вместе дешевле», промокоды; периоды пересекаются. Цены: запуск →
 * еженедельные решения о смене (электроника дешевеет, товары для дома дорожают), распродажа перед снятием, ступенька
 * НДС 01.01.2026, ступенька технологического сбора 01.12.2026, «фальшивая скидка» — подъём цены за 1–3 недели до части
 * акций и возврат после. Смены — в 00:00 по Москве; в выпуск — только внутри окна.
 */
public final class PricingModule implements Module {
    public static final String NAME = "pricing";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return List.of(CatalogModule.NAME);
    }

    @Override
    public List<String> tables() {
        return List.of("promotions", "promotion_targets", "prices");
    }

    // ================================================================ акции

    private static final Set<String> ELECTRONICS = Set.of("Phones and gadgets", "Computers and laptops",
            "TV, audio and video", "Photo and video", "Gaming and entertainment");
    private static final Set<String> APPLIANCES = Set.of("Large appliances", "Kitchen appliances", "Home appliances");

    private static List<String> focus(SalesEvent e) {
        return switch (e) {
            case NEW_YEAR -> List.of("Phones and gadgets", "TV, audio and video", "Kitchen appliances",
                    "Beauty and health", "Gaming and entertainment", "Smart home and lighting");
            case DEFENDERS_DAY -> List.of("Gaming and entertainment", "Tools", "Car electronics", "Beauty and health",
                    "Computers and laptops");
            case WOMENS_DAY -> List.of("Beauty and health", "Kitchen appliances", "Home appliances", "Phones and gadgets");
            case MAY_HOLIDAYS -> List.of("Tools", "Kitchen appliances", "Car electronics", "Photo and video", "Home and kitchen");
            case BACK_TO_SCHOOL -> List.of("Computers and laptops", "Phones and gadgets", "TV, audio and video", "Home and kitchen");
            default -> List.of();
        };
    }

    private static final Map<SalesEvent, String> EVENT_WORD = Map.of(
            SalesEvent.ELEVEN_ELEVEN, "SALE11", SalesEvent.BLACK_FRIDAY, "BLACK", SalesEvent.NEW_YEAR, "NEWYEAR",
            SalesEvent.WINTER_SALE, "WINTER", SalesEvent.DEFENDERS_DAY, "ZASCHITA", SalesEvent.WOMENS_DAY, "VESNA",
            SalesEvent.MAY_HOLIDAYS, "DACHA", SalesEvent.SUMMER_SALE, "LETO", SalesEvent.BACK_TO_SCHOOL, "SHKOLA");
    private static final String[] PROMO_WORDS = {"ISKRA", "PLAMYA", "GORN", "MOLOT", "KUZNYA", "ZHAR", "UGOLEK",
            "OGNIVO", "KRESALO", "PODAROK", "SKIDKA", "BONUS", "PRIVET", "DRUG", "VMESTE", "NOVOSELYE", "DOSTAVKA", "APP"};
    private static final Set<String> NPM_KINDS = Set.of("case", "glass", "film", "cable_usbc", "cable_micro", "battery",
            "led_bulb", "memory_card", "flash", "container", "brush_heads");

    /** Акция до выдачи id. */
    private static final class Draft {
        String key, kind, nameRu, nameEn, event, code;
        BigDecimal pct;
        Long amount, minOrder;
        Integer buy, free;
        long from, to;
        boolean stackable, fake;
        List<Target> targets = new ArrayList<>();
        List<Product> productTargets = new ArrayList<>();
    }

    /** Кэш «что продаётся в день»: товары и число живых товаров в поддеревьях категорий. */
    private static final class Alive {
        final Catalog cat;
        final Map<LocalDate, List<Product>> byDate = new HashMap<>();
        final Map<LocalDate, int[]> counts = new HashMap<>();

        Alive(Catalog cat) {
            this.cat = cat;
        }

        List<Product> on(LocalDate d) {
            return byDate.computeIfAbsent(d, x -> {
                List<Product> out = new ArrayList<>();
                for (Product p : cat.products()) {
                    if (!p.launchedOn.isAfter(x) && (p.discontinuedPlan == null || p.discontinuedPlan.isAfter(x.plusDays(7)))) {
                        out.add(p);
                    }
                }
                return out;
            });
        }

        int[] subtreeCounts(LocalDate d) {
            return counts.computeIfAbsent(d, x -> {
                int[] c = new int[cat.tree().nodes().size() + 1];
                for (Product p : on(x)) for (CategoryTree.Node n = p.category; n != null; n = n.parent) c[n.id]++;
                return c;
            });
        }
    }

    private static String pctText(BigDecimal pct) {
        return pct.stripTrailingZeros().toPlainString();
    }

    private static String rub(long kop) {
        return String.format(Locale.ROOT, "%,d", kop / 100).replace(',', ' ');
    }

    /** Выборка целей: веса популярность^alpha по id товара считаются один раз на alpha (на прогон). */
    private static final class Sampler {
        final List<Product> all;
        final Map<Double, double[]> weights = new HashMap<>();

        Sampler(List<Product> all) {
            this.all = all;
        }

        double[] weights(double alpha) {
            return weights.computeIfAbsent(alpha, a -> {
                double[] w = new double[all.size() + 1];
                for (Product p : all) w[p.id] = StrictMath.pow(Math.max(1e-9, p.popularity), a);
                return w;
            });
        }
    }

    /**
     * Выборка k разных товаров, вероятность — пропорционально популярности^alpha: накопленные веса и двоичный поиск,
     * повтор — новая попытка. Результат — по возрастанию id.
     */
    private static List<Product> sample(Sampler sm, Rng r, List<Product> cand, int k, double alpha) {
        if (cand.size() <= k) return new ArrayList<>(cand);
        double[] w = sm.weights(alpha);
        double[] cum = new double[cand.size()];
        double total = 0;
        for (int i = 0; i < cum.length; i++) {
            total += w[cand.get(i).id];
            cum[i] = total;
        }
        java.util.BitSet taken = new java.util.BitSet(cand.size());
        List<Product> out = new ArrayList<>(k);
        int guard = 0;
        while (out.size() < k && guard++ < k * 50) {
            double x = r.nextDouble() * total;
            int i = java.util.Arrays.binarySearch(cum, x);
            if (i < 0) i = -i - 1;
            if (i >= cum.length) i = cum.length - 1;
            if (taken.get(i)) continue;
            taken.set(i);
            out.add(cand.get(i));
        }
        out.sort(Comparator.comparingInt(p -> p.id));
        return out;
    }

    private static BigDecimal pct(Rng r, int lo, int hi) {
        return BigDecimal.valueOf(r.between(lo, hi));
    }

    private static int deptId(CategoryTree tree, String nameEn) {
        for (CategoryTree.Node n : tree.root().children) if (n.nameEn.equals(nameEn)) return n.id;
        throw new IllegalArgumentException("нет отдела " + nameEn);
    }

    static List<Draft> planPromotions(GenContext ctx, Catalog cat) {
        Period period = ctx.period();
        CategoryTree tree = cat.tree();
        Sampler sm = new Sampler(cat.products());
        Alive alive = new Alive(cat);
        List<Draft> out = new ArrayList<>();
        long winStart = period.startEpoch(), winEnd = period.endEpochExclusive();
        // план — на 30 дней за конец окна: подъёмы цен перед акциями сразу за срезом уже видны в истории, и продление
        // периода их не меняет; в выпуск идут только акции окна
        LocalDate horizon = period.to().plusDays(30);

        // ---------------------------------------------------------- события календаря
        RandomStreams.Stream es = ctx.stream("pricing.events");
        for (RuCalendar.EventWindow w : ctx.calendar().eventWindows(period.from().minusDays(60), horizon)) {
            long from = period.businessMidnight(w.from()), to = period.businessMidnight(w.toExclusive());
            if (from < winStart) continue;
            String ek = w.event().code + "/" + w.year();
            List<String> f = focus(w.event());
            List<Integer> depts = new ArrayList<>();
            for (CategoryTree.Node d : tree.root().children) if (f.isEmpty() || f.contains(d.nameEn)) depts.add(d.id);
            List<Product> cand = new ArrayList<>();
            for (Product p : alive.on(w.from())) if (depts.contains(p.category.departmentId)) cand.add(p);
            int[] counts = alive.subtreeCounts(w.from());
            List<CategoryTree.Node> catCand = new ArrayList<>();
            for (CategoryTree.Node n : tree.nodes()) {
                if (n.level >= 3 && n.level <= 4 && depts.contains(n.departmentId) && counts[n.id] >= 15) catCand.add(n);
            }
            Rng r = es.at(ek);
            String yy = String.format(Locale.ROOT, "%02d", w.year() % 100);
            int nCat = r.between(4, 8), nProd = r.between(6, 12), nAmount = r.between(1, 3), nCode = r.between(1, 2);
            for (int i = 0; i < nCat && !catCand.isEmpty(); i++) {
                Rng ri = es.at(ek + "/cat", i);
                Draft d = base(ek + "/cat/" + i, "percent", w.event().code, from, to);
                d.pct = pct(ri, 5, 20);
                int nt = ri.between(1, 3);
                List<String> names = new ArrayList<>();
                for (int j = 0; j < nt; j++) {
                    CategoryTree.Node n = catCand.get(ri.nextInt(catCand.size()));
                    if (d.targets.stream().anyMatch(t -> t.categoryId() == n.id)) continue;
                    d.targets.add(new Target(null, n.id));
                    names.add(CatalogModule.lowerFirst(n.nameRu));
                }
                d.nameRu = w.event().nameRu + ": −" + pctText(d.pct) + "% на " + String.join(", ", names);
                d.nameEn = w.event().nameEn + ": " + pctText(d.pct) + "% off selected categories";
                out.add(d);
            }
            for (int i = 0; i < nProd; i++) {
                Rng ri = es.at(ek + "/prod", i);
                Draft d = base(ek + "/prod/" + i, "percent", w.event().code, from, to);
                d.pct = pct(ri, 10, 40);
                d.fake = d.pct.intValue() >= 12 && ri.chance(0.35);
                d.productTargets = sample(sm, ri, d.fake ? settled(cand, w.from()) : cand, ri.between(60, 600), 0.5);
                d.stackable = ri.chance(0.3);
                d.nameRu = w.event().nameRu + ": скидки до " + pctText(d.pct) + "%";
                d.nameEn = w.event().nameEn + ": up to " + pctText(d.pct) + "% off";
                out.add(d);
            }
            List<CategoryTree.Node> expensive = new ArrayList<>();
            for (CategoryTree.Node n : catCand) {
                String dn = tree.node(n.departmentId).nameEn;
                if (APPLIANCES.contains(dn) || dn.equals("TV, audio and video") || dn.equals("Computers and laptops")) expensive.add(n);
            }
            for (int i = 0; i < nAmount && !expensive.isEmpty(); i++) {
                Rng ri = es.at(ek + "/amount", i);
                Draft d = base(ek + "/amount/" + i, "amount", w.event().code, from, to);
                d.amount = (long) new int[]{1000, 2000, 3000, 5000, 7000}[ri.nextInt(5)] * 100;
                CategoryTree.Node n = expensive.get(ri.nextInt(expensive.size()));
                d.targets.add(new Target(null, n.id));
                d.nameRu = w.event().nameRu + ": −" + rub(d.amount) + " ₽ на " + CatalogModule.lowerFirst(n.nameRu);
                d.nameEn = w.event().nameEn + ": " + rub(d.amount) + " RUB off " + CatalogModule.lowerFirst(n.nameEn);
                out.add(d);
            }
            for (int i = 0; i < nCode; i++) {
                Rng ri = es.at(ek + "/code", i);
                Draft d = base(ek + "/code/" + i, "promocode", w.event().code, from, to);
                d.code = EVENT_WORD.get(w.event()) + yy + (char) ('A' + i);
                d.amount = (long) new int[]{300, 500, 1000, 2000}[ri.nextInt(4)] * 100;
                d.minOrder = d.amount * new int[]{10, 15, 20}[ri.nextInt(3)];
                d.stackable = true;
                d.nameRu = "Промокод " + d.code + ": −" + rub(d.amount) + " ₽ к заказу от " + rub(d.minOrder) + " ₽";
                d.nameEn = "Promo code " + d.code + ": " + rub(d.amount) + " RUB off orders from " + rub(d.minOrder) + " RUB";
                out.add(d);
            }
            {
                Rng ri = es.at(ek + "/npm", 0);
                Draft d = base(ek + "/npm/0", "n_plus_m", w.event().code, from, to);
                d.buy = ri.chance(0.6) ? 3 : 2;
                d.free = 1;
                List<CategoryTree.Node> npm = new ArrayList<>();
                for (CategoryTree.Node n : tree.kindNodes()) if (NPM_KINDS.contains(n.kind)) npm.add(n);
                int nt = ri.between(1, 3);
                for (int j = 0; j < nt; j++) {
                    CategoryTree.Node n = npm.get(ri.nextInt(npm.size()));
                    if (d.targets.stream().noneMatch(t -> t.categoryId() == n.id)) d.targets.add(new Target(null, n.id));
                }
                d.nameRu = w.event().nameRu + ": " + d.buy + " по цене " + (d.buy - d.free) + " на аксессуары";
                d.nameEn = w.event().nameEn + ": " + d.buy + " for the price of " + (d.buy - d.free) + " on accessories";
                out.add(d);
            }
        }

        // ---------------------------------------------------------- скидки недели
        RandomStreams.Stream ws = ctx.stream("pricing.weekly_deals");
        LocalDate monday = period.from().with(java.time.DayOfWeek.MONDAY);
        for (LocalDate wk = monday; !wk.isAfter(horizon); wk = wk.plusWeeks(1)) {
            long from = period.businessMidnight(wk);
            if (from < winStart) continue;
            List<Product> cand = alive.on(wk);
            for (int i = 0; i < 8; i++) {
                Rng ri = ws.at(wk.toEpochDay(), i);
                Draft d = base("week/" + wk + "/" + i, "percent", null, from, period.businessMidnight(wk.plusDays(ri.between(3, 7))));
                d.pct = pct(ri, 7, 30);
                d.fake = d.pct.intValue() >= 12 && ri.chance(0.15);
                d.productTargets = sample(sm, ri, d.fake ? settled(cand, wk) : cand, ri.between(30, 180), 0.7);
                d.stackable = ri.chance(0.3);
                d.nameRu = "Скидки недели: −" + pctText(d.pct) + "%";
                d.nameEn = "Deals of the week: " + pctText(d.pct) + "% off";
                out.add(d);
            }
        }

        // ---------------------------------------------------------- по месяцам: бренды, «вместе дешевле», промокоды
        RandomStreams.Stream ms = ctx.stream("pricing.monthly");
        List<Catalog.Brand> brands = cat.brands();
        double[] bw = new double[brands.size()];
        for (int i = 0; i < bw.length; i++) bw[i] = brands.get(i).weight();
        List<CategoryTree.Node> codeCats = new ArrayList<>();
        for (CategoryTree.Node n : tree.nodes()) if (n.level == 3) codeCats.add(n);
        for (YearMonth m = YearMonth.from(period.from()); !m.atDay(1).isAfter(period.to()); m = m.plusMonths(1)) {
            String mk = m.toString();
            String yymm = String.format(Locale.ROOT, "%02d%02d", m.getYear() % 100, m.getMonthValue());
            for (int i = 0; i < 6; i++) {
                Rng ri = ms.at("brand/" + mk, i);
                Catalog.Brand b = brands.get(ri.weighted(bw));
                LocalDate start = m.atDay(ri.between(1, Math.min(21, m.lengthOfMonth())));
                List<Product> cand = new ArrayList<>();
                for (Product p : alive.on(start)) if (p.brand == b && !p.bundle) cand.add(p);
                if (cand.size() < 5) continue;
                Draft d = base("brand/" + mk + "/" + i, "percent", null, period.businessMidnight(start),
                        period.businessMidnight(start.plusDays(ri.between(7, 10))));
                d.pct = pct(ri, 10, 15);
                d.productTargets = sample(sm, ri, cand, 600, 0.3);
                d.nameRu = "Неделя бренда " + b.name() + ": −" + pctText(d.pct) + "%";
                d.nameEn = b.name() + " brand week: " + pctText(d.pct) + "% off";
                out.add(d);
            }
            List<Product> monthAlive = alive.on(m.atDay(1));
            Map<String, List<Product>> byKind = new TreeMap<>();
            for (Product p : monthAlive) if (!p.bundle) byKind.computeIfAbsent(p.kind.code, k -> new ArrayList<>()).add(p);
            List<Catalog.Companion> rules = Catalog.COMPANIONS;
            for (int i = 0; i < 30; i++) {
                Rng ri = ms.at("bundle/" + mk, i);
                Catalog.Companion rule = rules.get(ri.nextInt(rules.size()));
                LocalDate start = m.atDay(ri.between(1, m.lengthOfMonth()));
                List<Product> anchors = new ArrayList<>();
                for (Product x : byKind.getOrDefault(rule.anchor(), List.of())) if (sellsOn(x, start)) anchors.add(x);
                if (anchors.isEmpty()) continue;
                Product a = sample(sm, ri, anchors, 1, 1.0).getFirst();
                List<Product> comps = new ArrayList<>();
                for (Product x : byKind.getOrDefault(rule.companion(), List.of())) {
                    if (sellsOn(x, start) && (!rule.compatibleOnly() || x.compatibleWith.contains(a))) comps.add(x);
                }
                if (comps.isEmpty()) continue;
                Draft d = base("bundle/" + mk + "/" + i, "bundle", null, period.businessMidnight(start),
                        period.businessMidnight(start.plusDays(ri.between(14, 30))));
                d.pct = pct(ri, 10, 20);
                d.productTargets.add(a);
                d.productTargets.addAll(sample(sm, ri, comps, ri.between(1, 2), 1.0));
                d.productTargets.sort(Comparator.comparingInt(p -> p.id));
                d.nameRu = "Вместе дешевле: " + CatalogModule.lowerFirst(a.kind.nameRu) + " + "
                        + CatalogModule.lowerFirst(cat.kinds().kind(rule.companion()).nameRu) + ", −" + pctText(d.pct) + "%";
                d.nameEn = "Better together: " + pctText(d.pct) + "% off " + a.kind.nameEn + " and "
                        + cat.kinds().kind(rule.companion()).nameEn;
                out.add(d);
            }
            for (int i = 0; i < 20; i++) {
                Rng ri = ms.at("code/" + mk, i);
                LocalDate start = m.atDay(ri.between(1, m.lengthOfMonth()));
                Draft d = base("code/" + mk + "/" + i, "promocode", null, period.businessMidnight(start),
                        period.businessMidnight(start.plusDays(ri.between(7, 30))));
                d.code = PROMO_WORDS[ri.nextInt(PROMO_WORDS.length)] + yymm + (char) ('A' + i);
                d.stackable = ri.chance(0.8);
                if (ri.chance(0.6)) {
                    d.amount = (long) new int[]{300, 500, 700, 1000, 1500, 2000, 3000}[ri.nextInt(7)] * 100;
                    d.minOrder = d.amount * new int[]{8, 10, 15, 20}[ri.nextInt(4)];
                    d.nameRu = "Промокод " + d.code + ": −" + rub(d.amount) + " ₽ к заказу от " + rub(d.minOrder) + " ₽";
                    d.nameEn = "Promo code " + d.code + ": " + rub(d.amount) + " RUB off orders from " + rub(d.minOrder) + " RUB";
                } else {
                    d.pct = pct(ri, 5, 15);
                    CategoryTree.Node n = codeCats.get(ri.nextInt(codeCats.size()));
                    d.targets.add(new Target(null, n.id));
                    d.nameRu = "Промокод " + d.code + ": −" + pctText(d.pct) + "% на " + CatalogModule.lowerFirst(n.nameRu);
                    d.nameEn = "Promo code " + d.code + ": " + pctText(d.pct) + "% off " + CatalogModule.lowerFirst(n.nameEn);
                }
                out.add(d);
            }
        }
        for (Draft d : out) for (Product p : d.productTargets) d.targets.add(new Target(p.id, null));
        out.sort(Comparator.comparingLong((Draft d) -> d.from).thenComparing(d -> d.key));
        return out;
    }

    /**
     * Цели «фальшивой скидки» — только товары, запущенные не позже чем за 30 дней до акции: такие есть в каталоге
     * при любом срезе периода, на который подъём цены уже виден (стабильность при продлении).
     */
    private static List<Product> settled(List<Product> cand, LocalDate start) {
        List<Product> out = new ArrayList<>();
        for (Product p : cand) if (!p.launchedOn.isAfter(start.minusDays(30))) out.add(p);
        return out;
    }

    private static boolean sellsOn(Product p, LocalDate d) {
        return !p.launchedOn.isAfter(d) && (p.discontinuedPlan == null || p.discontinuedPlan.isAfter(d.plusDays(7)));
    }

    private static Draft base(String key, String kind, String event, long from, long to) {
        Draft d = new Draft();
        d.key = key;
        d.kind = kind;
        d.event = event;
        d.from = from;
        d.to = to;
        return d;
    }

    // ================================================================ цены

    private record Bump(long on, long off, double factor) {
    }

    private record Ev(long at, int type, double factor) {
    }

    private static final int REG = 0, CLEAR = 1, VAT = 2, FEE = 3, BUMP_ON = 4, BUMP_OFF = 5;

    static double[] regime(Product p, CategoryTree tree) {
        String dept = tree.node(p.category.departmentId).nameEn;
        if (p.kind.group.equals("accessories")) return new double[]{0.50, -0.02};
        if (ELECTRONICS.contains(dept)) return new double[]{0.70, -0.15};
        if (APPLIANCES.contains(dept)) return new double[]{0.55, -0.04};
        return new double[]{0.45, 0.05};
    }

    /** История цены одного товара: периоды (момент начала, цена) от запуска, без обрезки окном. */
    static List<long[]> path(GenContext ctx, Product p, long launchPrice, List<Bump> bumps, CategoryTree tree,
                             long feeKop, double q, double drift) {
        Period period = ctx.period();
        RandomStreams.Stream st = ctx.stream("pricing.changes");
        long seedKey = RandomStreams.fnv64(p.planKey);
        // смены — с запасом в неделю за конец окна: набор событий внутри окна не зависит от того, где окно кончается
        LocalDate stop = p.discontinuedPlan != null && p.discontinuedPlan.isBefore(period.to().plusDays(8))
                ? p.discontinuedPlan : period.to().plusDays(8);
        List<Ev> evs = new ArrayList<>();
        // цена = тренд × шум: тренд идёт с заданным годовым дрейфом, шум — AR(1) (возвращается к тренду)
        double mu = drift / (52.0 * q), sd = 0.035, rho = 0.6;
        long w0 = Math.floorDiv(p.launchedOn.toEpochDay(), 7) + 1, w1 = Math.floorDiv(stop.toEpochDay(), 7);
        for (long w = w0; w <= w1; w++) {
            Rng r = st.at(seedKey, w);
            if (!r.chance(q)) continue;
            LocalDate d = LocalDate.ofEpochDay(w * 7 + r.nextInt(7));
            if (!d.isAfter(p.launchedOn.plusDays(13)) || !d.isBefore(stop)) continue;
            if (d.equals(Tax.VAT_22_FROM) || d.equals(Tax.TECH_FEE_FROM)) continue;
            evs.add(new Ev(period.businessMidnight(d), REG, sd * r.gaussian()));
        }
        Rng rf = st.at(seedKey, -1);
        if (p.discontinuedPlan != null) {
            LocalDate d = p.discontinuedPlan.minusDays(rf.between(20, 45));
            if (d.equals(Tax.VAT_22_FROM) || d.equals(Tax.TECH_FEE_FROM)) d = d.plusDays(1);
            if (d.isAfter(p.launchedOn.plusDays(60))) evs.add(new Ev(period.businessMidnight(d), CLEAR, rf.uniform(0.75, 0.88)));
        }
        boolean vat = rf.chance(0.75);
        if (vat && p.launchedOn.isBefore(Tax.VAT_22_FROM) && p.aliveOn(Tax.VAT_22_FROM)) {
            evs.add(new Ev(period.businessMidnight(Tax.VAT_22_FROM), VAT, 122.0 / 120.0));
        }
        if (feeKop > 0 && p.launchedOn.isBefore(Tax.TECH_FEE_FROM) && p.aliveOn(Tax.TECH_FEE_FROM)) {
            evs.add(new Ev(period.businessMidnight(Tax.TECH_FEE_FROM), FEE, 0));
        }
        for (Bump b : bumps) {
            evs.add(new Ev(b.on, BUMP_ON, b.factor));
            evs.add(new Ev(b.off, BUMP_OFF, 0));
        }
        evs.sort(Comparator.comparingLong(Ev::at).thenComparingInt(Ev::type));
        List<long[]> out = new ArrayList<>();
        long launch = period.businessMidnight(p.launchedOn);
        long price = launchPrice, base = 0;
        boolean bumped = false;
        double trend = StrictMath.log(launchPrice / 100.0), noise = 0;
        out.add(new long[]{launch, price});
        for (Ev e : evs) {
            long np = price;
            switch (e.type) {
                case REG -> {
                    trend += mu;
                    noise = rho * noise + e.factor;
                    if (!bumped) np = Money.retail(StrictMath.exp(trend + noise));
                }
                case CLEAR -> {
                    trend += StrictMath.log(e.factor);
                    if (!bumped) np = Money.retail(price / 100.0 * e.factor);
                }
                case VAT -> {
                    trend += StrictMath.log(e.factor);
                    np = Money.retailAtLeast(price / 100.0 * e.factor, price + 1);
                    if (bumped) base = Money.retailAtLeast(base / 100.0 * e.factor, base + 1);
                }
                case FEE -> {
                    long fee = feeKop * 122 / 100;
                    trend += StrictMath.log((price + fee) / (double) price);
                    np = Money.retailAtLeast((price + fee) / 100.0, price + fee);
                    if (bumped) base = Money.retailAtLeast((base + fee) / 100.0, base + fee);
                }
                case BUMP_ON -> {
                    if (!bumped) {
                        base = price;
                        np = Money.retailAtLeast(price / 100.0 * e.factor, price + 1);
                        bumped = true;
                    }
                }
                case BUMP_OFF -> {
                    if (bumped) {
                        np = base;
                        bumped = false;
                    }
                }
                default -> throw new IllegalStateException();
            }
            if (np <= 0) np = 9_90;
            if (np != price) {
                long[] last = out.getLast();
                if (last[0] == e.at) last[1] = np;
                else out.add(new long[]{e.at, np});
                price = np;
            }
        }
        // склеить соседние равные (после совпадений моментов)
        List<long[]> merged = new ArrayList<>();
        for (long[] x : out) if (merged.isEmpty() || merged.getLast()[1] != x[1]) merged.add(x);
        return merged;
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        Period period = ctx.period();
        Catalog cat = ctx.get(StoKeys.CATALOG);
        CategoryTree tree = cat.tree();
        List<Draft> planned = planPromotions(ctx, cat);
        long winStart0 = period.startEpoch(), winEnd0 = period.endEpochExclusive();
        // в выпуск — акции окна (начало в окне, конец — не дальше конца окна); подъёмы — по всему плану
        List<Draft> drafts = new ArrayList<>();
        for (Draft d : planned) {
            if (d.from < winStart0 || d.from >= winEnd0) continue;
            if (Math.min(d.to, winEnd0) <= d.from) continue;
            drafts.add(d);
        }
        ctx.log("  pricing: план акций — " + planned.size() + ", в окне " + drafts.size());

        // «фальшивая скидка»: подъём за 7–21 день до акции, возврат после её конца
        RandomStreams.Stream fs = ctx.stream("pricing.fake");
        Map<Integer, List<Bump>> bumps = new HashMap<>();
        for (Draft d : planned) {
            if (!d.fake) continue;
            for (Product p : d.productTargets) {
                if (p.bundle) continue;
                Rng r = fs.at(d.key, p.id);
                long on = d.from - 86400L * r.between(7, 21);
                long off = d.to + 86400L * r.between(0, 3);
                if (on < period.businessMidnight(p.launchedOn.plusDays(14))) continue;
                if (p.discontinuedPlan != null && off >= period.businessMidnight(p.discontinuedPlan.minusDays(46))) continue;
                List<Bump> list = bumps.computeIfAbsent(p.id, k -> new ArrayList<>());
                boolean overlap = false;
                for (Bump b : list) if (on < b.off + 86400L * 7 && b.on < off + 86400L * 7) overlap = true;
                if (overlap) continue;
                double factor = 1.0 + d.pct.doubleValue() / 100.0 * r.uniform(0.8, 1.15);
                list.add(new Bump(on, off, factor));
                ctx.planted().add("fake_discount", ((long) d.key.hashCode() << 32) ^ p.id);
            }
        }

        ctx.log("  pricing: подъёмы цен перед акциями — товаров " + bumps.size());
        PriceBook book = new PriceBook(cat.products().size());
        long winStart = period.startEpoch(), winEnd = period.endEpochExclusive();
        TreeMap<Integer, List<long[]>> emitted = new TreeMap<>();
        // сначала обычные товары, затем комплекты по уровню вложенности
        List<Product> order = new ArrayList<>(cat.products());
        Map<Integer, Integer> level = new HashMap<>();
        for (Product p : order) bundleLevel(p, level);
        order.sort(Comparator.comparingInt((Product p) -> level.get(p.id)).thenComparingInt(p -> p.id));
        RandomStreams.Stream bs = ctx.stream("pricing.bundles");
        for (Product p : order) {
            long launchPrice = p.launchPriceKop;
            long fee = 0;
            if (p.bundle) {
                long at = Math.max(period.businessMidnight(p.launchedOn), winStart);
                long sum = 0;
                for (Catalog.BundleItem it : p.components) {
                    long cp = book.priceAt(it.component().id, at);
                    if (cp < 0) cp = it.component().launchPriceKop;
                    sum += cp * it.qty();
                    fee += feeOf(it.component(), it.qty());
                }
                launchPrice = Money.retail(sum / 100.0 * bs.at(p.id).uniform(0.85, 0.95));
                p.launchPriceKop = launchPrice;
            } else {
                fee = Tax.techFeeKop(p.kind.feeClass, Tax.TECH_FEE_FROM);
            }
            double[] reg = p.bundle ? new double[]{0.15, -0.05} : regime(p, tree);
            List<long[]> full = path(ctx, p, launchPrice, bumps.getOrDefault(p.id, List.of()), tree, fee, reg[0], reg[1]);
            long stop = p.discontinuedOn != null ? period.businessMidnight(p.discontinuedOn) : Long.MAX_VALUE;
            List<long[]> win = new ArrayList<>();
            for (int i = 0; i < full.size(); i++) {
                long f = full.get(i)[0], nextF = i + 1 < full.size() ? full.get(i + 1)[0] : Long.MAX_VALUE;
                long to = Math.min(nextF, stop);
                if (to <= winStart || f >= winEnd || f >= stop) continue;
                win.add(new long[]{Math.max(f, winStart), full.get(i)[1], to});
            }
            if (win.isEmpty()) throw new IllegalStateException(p + ": нет цены в окне");
            long[] last = win.getLast();
            long end = last[2] == Long.MAX_VALUE || last[2] >= winEnd && stop == Long.MAX_VALUE ? Long.MAX_VALUE : last[2];
            if (end != Long.MAX_VALUE && end > winEnd) end = Long.MAX_VALUE;
            last[2] = end;
            long[] fr = new long[win.size()], pr = new long[win.size()];
            for (int i = 0; i < win.size(); i++) {
                fr[i] = win.get(i)[0];
                pr[i] = win.get(i)[1];
            }
            book.set(p.id, fr, pr, end);
            emitted.put(p.id, win);
        }

        ctx.log("  pricing: истории цен посчитаны");
        // запись
        TableSink sp = ctx.streamSink("promotions");
        TableSink st = ctx.streamSink("promotion_targets");
        List<Promotion> promos = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft d = drafts.get(i);
            int id = i + 1;
            long to = Math.min(d.to, winEnd);
            sp.add(id, d.kind, d.nameRu, d.nameEn, d.event, d.pct, d.amount, d.buy, d.free, d.code, d.minOrder,
                    d.from, to, d.stackable);
            List<Target> ts = new ArrayList<>(d.targets);
            for (int j = 0; j < ts.size(); j++) st.add(id, j + 1, ts.get(j).productId(), ts.get(j).categoryId());
            promos.add(new Promotion(id, d.kind, d.nameRu, d.nameEn, d.event, d.pct, d.amount, d.buy, d.free, d.code,
                    d.minOrder, d.from, to, d.stackable, List.copyOf(ts), d.fake));
        }
        sp.close();
        st.close();
        TableSink pr = ctx.streamSink("prices");
        for (Map.Entry<Integer, List<long[]>> e : emitted.entrySet()) {
            for (long[] x : e.getValue()) {
                pr.add(e.getKey(), x[0], x[2] == Long.MAX_VALUE ? null : x[2], x[1]);
            }
        }
        pr.close();
        ctx.put(StoKeys.PRICES, book);
        ctx.put(StoKeys.PROMOTIONS, new Promotions(promos));
    }

    private static long feeOf(Product p, int qty) {
        if (p.bundle) {
            long s = 0;
            for (Catalog.BundleItem it : p.components) s += feeOf(it.component(), it.qty());
            return s * qty;
        }
        return Tax.techFeeKop(p.kind.feeClass, Tax.TECH_FEE_FROM) * qty;
    }

    private static int bundleLevel(Product p, Map<Integer, Integer> memo) {
        Integer l = memo.get(p.id);
        if (l != null) return l;
        int v = 0;
        for (Catalog.BundleItem it : p.components) v = Math.max(v, bundleLevel(it.component(), memo) + 1);
        memo.put(p.id, v);
        return v;
    }

    // ================================================================ проверки

    @Override
    public void check(GenContext ctx, CheckReport r) {
        Period period = ctx.period();
        Catalog cat = ctx.get(StoKeys.CATALOG);
        PriceBook book = ctx.get(StoKeys.PRICES);
        Promotions promos = ctx.get(StoKeys.PROMOTIONS);
        long winStart = period.startEpoch(), winEnd = period.endEpochExclusive();
        List<String> v = new ArrayList<>();
        long periods = 0;
        for (Product p : cat.products()) {
            int n = book.periods(p.id);
            periods += n;
            if (n == 0) {
                v.add(p.id + ": нет цен");
                continue;
            }
            long expectFrom = Math.max(period.businessMidnight(p.launchedOn), winStart);
            if (book.periodFrom(p.id, 0) != expectFrom) v.add(p.id + ": первая цена не с запуска / начала окна");
            for (int i = 1; i < n; i++) {
                if (book.periodFrom(p.id, i) <= book.periodFrom(p.id, i - 1)) v.add(p.id + ": периоды не по порядку");
                if (book.periodPrice(p.id, i) == book.periodPrice(p.id, i - 1)) v.add(p.id + ": два периода с одной ценой подряд");
            }
            for (int i = 0; i < n; i++) if (book.periodPrice(p.id, i) <= 0) v.add(p.id + ": цена ≤ 0");
            long end = book.end(p.id);
            if (p.discontinuedOn != null) {
                if (end != period.businessMidnight(p.discontinuedOn)) v.add(p.id + ": последняя цена не до снятия");
            } else if (end != Long.MAX_VALUE) {
                v.add(p.id + ": последняя цена кончается, а товар не снят");
            }
            if (end != Long.MAX_VALUE && (end > winEnd || end <= winStart)) v.add(p.id + ": конец цены вне окна");
        }
        r.expectNone("pricing: цена на любой момент — ровно одна (без разрывов и наложений, с запуска до снятия)", v,
                "периодов цен " + periods);

        // выборочно: на случайный момент жизни товара — ровно одна цена (через таблицу-модель)
        RandomStreams.Stream st = ctx.stream("pricing.check");
        List<String> s = new ArrayList<>();
        int probes = 0;
        for (Product p : cat.products()) {
            Rng rr = st.at(p.id);
            long a = Math.max(period.businessMidnight(p.launchedOn), winStart);
            long b = p.discontinuedOn != null ? period.businessMidnight(p.discontinuedOn) : winEnd;
            for (int i = 0; i < 3; i++) {
                long t = a + (long) (rr.nextDouble() * (b - a));
                int hits = 0;
                for (int k = 0; k < book.periods(p.id); k++) {
                    long f = book.periodFrom(p.id, k), to = k + 1 < book.periods(p.id) ? book.periodFrom(p.id, k + 1) : book.end(p.id);
                    if (t >= f && t < to) hits++;
                }
                probes++;
                if (hits != 1) s.add(p.id + " на " + t + ": цен " + hits);
            }
        }
        r.expectNone("pricing: выборочно — на момент ровно одна цена", s, "проверок " + probes);

        // ступенька техсбора: у охваченных и живых по обе стороны 01.12.2026 — цена после ≥ цены до + сбор
        long feeAt = period.businessMidnight(Tax.TECH_FEE_FROM);
        List<String> fee = new ArrayList<>();
        int feeChecked = 0;
        for (Product p : cat.products()) {
            if (p.bundle || p.kind.feeClass == null) continue;
            long before = book.priceAt(p.id, feeAt - 1), after = book.priceAt(p.id, feeAt);
            if (before < 0 || after < 0) continue;
            feeChecked++;
            long min = before + Tax.techFeeKop(p.kind.feeClass, Tax.TECH_FEE_FROM) * 122 / 100;
            if (after < min && !inBump(promos, p, feeAt)) fee.add(p.id + ": " + Money.format(before) + " → " + Money.format(after));
        }
        r.expectNone("pricing: ступенька технологического сбора 01.12.2026", fee, "товаров со сбором " + feeChecked);

        List<String> pv = new ArrayList<>();
        Set<String> codes = new java.util.HashSet<>();
        int overlapping = 0;
        for (Promotion p : promos.all()) {
            if (p.validTo() <= p.validFrom()) pv.add(p.id() + ": пустой период");
            if (p.validFrom() < winStart || p.validTo() > winEnd) pv.add(p.id() + ": вне окна");
            boolean hasPct = p.discountPct() != null, hasAmt = p.discountAmountKop() != null;
            switch (p.kind()) {
                case "percent", "bundle" -> {
                    if (!hasPct || hasAmt) pv.add(p.id() + ": скидка");
                }
                case "amount" -> {
                    if (hasPct || !hasAmt) pv.add(p.id() + ": скидка");
                }
                case "n_plus_m" -> {
                    if (p.buyQty() == null || p.freeQty() == null || p.freeQty() >= p.buyQty()) pv.add(p.id() + ": n+m");
                }
                case "promocode" -> {
                    if (p.promoCode() == null || hasPct == hasAmt) pv.add(p.id() + ": промокод");
                }
                default -> pv.add(p.id() + ": вид " + p.kind());
            }
            if (p.promoCode() != null && !codes.add(p.promoCode())) pv.add("повтор кода " + p.promoCode());
            if (!p.kind().equals("promocode") && p.targets().isEmpty()) pv.add(p.id() + ": без целей");
            for (Target t : p.targets()) {
                if ((t.productId() == null) == (t.categoryId() == null)) pv.add(p.id() + ": цель");
                if (t.productId() != null && book.priceAt(t.productId(), p.validFrom()) < 0) {
                    pv.add(p.id() + ": товар " + t.productId() + " не продаётся к началу акции");
                }
            }
        }
        for (int i = 1; i < promos.all().size(); i++) {
            if (promos.all().get(i).validFrom() < promos.all().get(i - 1).validTo()) overlapping++;
        }
        r.expectNone("pricing: акции — периоды, механики, цели, коды", pv,
                "акций " + promos.all().size() + ", пересекающихся с предыдущей " + overlapping);
        r.ok("pricing: «фальшивая скидка» — заложено подъёмов цены", "" + ctx.planted().count("fake_discount"));
    }

    private static boolean inBump(Promotions promos, Product p, long t) {
        for (Promotion pr : promos.all()) {
            if (!pr.fakeDiscount()) continue;
            if (t >= pr.validFrom() - 86400L * 22 && t < pr.validTo() + 86400L * 4) {
                for (Target x : pr.targets()) if (x.productId() != null && x.productId() == p.id) return true;
            }
        }
        return false;
    }
}
