package io.hammerhall.data.sto.catalog;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.money.Money;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.sto.StoKeys;
import io.hammerhall.data.sto.catalog.Catalog.Assortment;
import io.hammerhall.data.sto.catalog.Catalog.Brand;
import io.hammerhall.data.sto.catalog.Catalog.BundleItem;
import io.hammerhall.data.sto.catalog.Catalog.Product;
import io.hammerhall.data.sto.catalog.Catalog.SpecValue;
import io.hammerhall.data.sto.catalog.Kinds.Kind;
import io.hammerhall.data.sto.catalog.Kinds.Level;
import io.hammerhall.data.sto.catalog.Kinds.Spec;
import io.hammerhall.data.sto.catalog.Kinds.VType;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Каталог: категории, справочник характеристик, бренды, товары (линейки → поколения → варианты), характеристики,
 * аксессуары под конкретные модели, комплекты из комплектов.
 *
 * <p>План — на фиксированной временной оси: у каждого вида — «слоты» (одновременно живущие линейки); в слоте линейки
 * сменяют друг друга, у линейки поколения выходят с шагом смены поколения. Случайность — от (вид, слот, линейка,
 * поколение, вариант), поэтому продление периода добавляет новые поколения в хвост и не трогает прежние; id — по дате
 * запуска. В выпуск идёт то, что продаётся хотя бы день внутри периода.
 */
public final class CatalogModule implements Module {
    public static final String NAME = "catalog";
    /** Масштаб «SKU в окне» из kinds.tsv — подобран так, чтобы каталог был ≈ 50 тыс. товаров. */
    static final double TARGET_SCALE = 0.68;
    static final LocalDate PLAN_START = LocalDate.of(2016, 1, 1);
    /** Доля заложенных неверных контрольных цифр EAN-13 среди товаров только продавцов. */
    static final double BAD_EAN_SHARE = 0.02;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> tables() {
        return List.of("categories", "spec_keys", "brands", "products", "product_specs", "bundle_items");
    }

    // ================================================================ бренды

    private static final String[][] GROUP_QUOTA = {
            {"phones", "12"}, {"computers", "12"}, {"office", "4"}, {"tv", "8"}, {"audio", "11"}, {"photo", "5"},
            {"gaming", "5"}, {"furniture", "4"}, {"emobility", "4"}, {"appliances_large", "11"},
            {"appliances_small", "14"}, {"climate", "7"}, {"beauty", "9"}, {"smart", "5"}, {"lighting", "5"},
            {"tools", "8"}, {"household", "7"}, {"car", "5"}, {"accessories", "12"}};

    private static final Map<String, String[]> AFFINITY = Map.ofEntries(
            Map.entry("phones", new String[]{"computers", "audio", "smart", "accessories"}),
            Map.entry("computers", new String[]{"office", "gaming", "phones", "accessories"}),
            Map.entry("office", new String[]{"computers"}),
            Map.entry("tv", new String[]{"audio", "appliances_large"}),
            Map.entry("audio", new String[]{"phones", "gaming"}),
            Map.entry("photo", new String[]{"accessories"}),
            Map.entry("gaming", new String[]{"computers", "furniture", "audio"}),
            Map.entry("furniture", new String[]{"gaming", "household"}),
            Map.entry("emobility", new String[]{"accessories", "car"}),
            Map.entry("appliances_large", new String[]{"appliances_small", "climate"}),
            Map.entry("appliances_small", new String[]{"beauty", "household", "climate"}),
            Map.entry("climate", new String[]{"appliances_large"}),
            Map.entry("beauty", new String[]{"appliances_small"}),
            Map.entry("smart", new String[]{"lighting", "phones"}),
            Map.entry("lighting", new String[]{"smart", "household"}),
            Map.entry("tools", new String[]{"car", "lighting"}),
            Map.entry("household", new String[]{"appliances_small"}),
            Map.entry("car", new String[]{"accessories", "tools"}),
            Map.entry("accessories", new String[]{"phones", "car", "audio"}));

    private static final String[] TIERS = {"budget", "mid", "premium"};
    private static final String[][] COUNTRY = {
            {"CN:55", "RU:20", "TR:8", "VN:5", "IN:4", "BY:4", "PL:4"},
            {"CN:35", "KR:12", "TW:10", "RU:10", "DE:10", "JP:8", "TR:5", "IT:5", "SE:5"},
            {"JP:25", "DE:22", "US:15", "KR:12", "IT:8", "SE:8", "GB:6", "FI:4"}};

    static String pickCountry(Rng r, int tier) {
        String[] opts = COUNTRY[tier];
        double[] w = new double[opts.length];
        for (int i = 0; i < opts.length; i++) w[i] = Double.parseDouble(opts[i].split(":")[1]);
        return opts[r.weighted(w)].split(":")[0];
    }

    static List<Brand> buildBrands(GenContext ctx) {
        RandomStreams.Stream st = ctx.stream("catalog.brands");
        Set<String> taken = new TreeSet<>();
        taken.add("ognivo");
        taken.add("kresalo");
        record Draft(String name, String country, String tier, List<String> groups, double weight, boolean pl) {
        }
        List<Draft> drafts = new ArrayList<>();
        int n = 0;
        for (String[] gq : GROUP_QUOTA) {
            int q = Integer.parseInt(gq[1]);
            for (int i = 0; i < q; i++, n++) {
                Rng r = st.at(gq[0], i);
                String name = Names.brand(r, taken);
                double u = r.nextDouble();
                double premiumShare = gq[0].equals("accessories") || gq[0].equals("household") ? 0.1 : 0.2;
                int tier = u < 0.42 ? 0 : (u < 1 - premiumShare ? 1 : 2);
                List<String> groups = new ArrayList<>(List.of(gq[0]));
                if (r.chance(0.35)) {
                    String[] aff = AFFINITY.get(gq[0]);
                    groups.add(aff[r.nextInt(aff.length)]);
                }
                drafts.add(new Draft(name, pickCountry(r, tier), TIERS[tier], List.copyOf(groups), r.logNormal(1, 0.8), false));
            }
        }
        drafts.add(new Draft("Ognivo", "RU", "budget",
                List.of("accessories", "lighting", "household", "appliances_small", "smart"), 0.6, true));
        drafts.add(new Draft("Kresalo", "RU", "budget", List.of("tools", "car", "climate", "furniture"), 0.6, true));
        drafts.sort(Comparator.comparing(Draft::name));
        List<Brand> out = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft d = drafts.get(i);
            out.add(new Brand(i + 1, d.name, d.country, d.tier, d.pl, d.groups, d.weight));
        }
        return out;
    }

    static int tierIdx(String tier) {
        return switch (tier) {
            case "budget" -> 0;
            case "mid" -> 1;
            default -> 2;
        };
    }

    static Brand pickBrand(Rng r, List<Brand> brands, Kind k) {
        List<Brand> cand = new ArrayList<>();
        for (Brand b : brands) if (b.groups().contains(k.group) && k.tiers.contains(b.tier())) cand.add(b);
        if (cand.isEmpty()) for (Brand b : brands) if (b.groups().contains(k.group)) cand.add(b);
        if (cand.isEmpty()) throw new IllegalStateException("нет брендов группы " + k.group);
        double[] w = new double[cand.size()];
        for (int i = 0; i < w.length; i++) {
            Brand b = cand.get(i);
            w[i] = b.weight() * (b.groups().getFirst().equals(k.group) ? 1.0 : 0.45) * (b.privateLabel() ? 0.5 : 1.0);
        }
        return cand.get(r.weighted(w));
    }

    // ================================================================ значения характеристик

    static int tierWindow(Rng r, int n, String tier, boolean corr) {
        if (!corr || n <= 1) return r.nextInt(n);
        int lo, hi;
        switch (tier) {
            case "budget" -> {
                lo = 0;
                hi = Math.max(1, (int) Math.ceil(n * 0.6));
            }
            case "mid" -> {
                lo = (int) Math.floor(n * 0.2);
                hi = Math.max(lo + 1, (int) Math.ceil(n * 0.8));
            }
            default -> {
                lo = (int) Math.floor(n * 0.4);
                hi = n;
            }
        }
        return lo + r.nextInt(hi - lo);
    }

    static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    static SpecValue valueAt(Spec s, int i) {
        return switch (s.type) {
            case RANGE, NUMLIST -> new SpecValue(s.key, null, null, round3(s.numAt(i)));
            case TEXTLIST, YN -> new SpecValue(s.key, s.ru[i], s.en[i], null);
            case DERIVED -> throw new IllegalStateException("производное " + s.key);
        };
    }

    static SpecValue pick(Spec s, Rng r, String tier) {
        if (s.type == VType.YN) {
            double p = s.yes;
            if (s.tier) p = switch (tier) {
                case "budget" -> p * 0.6;
                case "mid" -> p;
                default -> Math.min(1.0, p * 1.3 + 0.1);
            };
            return valueAt(s, r.chance(p) ? 0 : 1);
        }
        return valueAt(s, tierWindow(r, s.cardinality(), tier, s.tier));
    }

    /** Подмножество значений для вариантов: числа — подряд, текст — любые, порядок — как в описании. */
    static int[] subset(Spec s, Rng r, String tier) {
        int n = s.cardinality();
        int k = r.between(Math.min(s.nMin, n), Math.min(s.nMax, n));
        if (s.type == VType.NUMLIST || s.type == VType.RANGE) {
            int span = n - k;
            int start;
            if (!s.tier || span == 0) start = r.between(0, span);
            else start = switch (tier) {
                case "budget" -> r.between(0, span / 2);
                case "mid" -> r.between(span / 4, (3 * span + 3) / 4);
                default -> r.between((span + 1) / 2, span);
            };
            int[] out = new int[k];
            for (int i = 0; i < k; i++) out[i] = start + i;
            return out;
        }
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        r.shuffle(idx);
        int[] out = Arrays.copyOf(idx, k);
        Arrays.sort(out);
        return out;
    }

    // ================================================================ форматирование названий

    static String num(double v, boolean ru) {
        String s = v == Math.rint(v) ? Long.toString((long) v) : io.hammerhall.data.core.io.RowFormat.trimZeros(
                io.hammerhall.data.core.io.RowFormat.fixed(v, 3));
        return ru ? s.replace('.', ',') : s;
    }

    static String part(SpecValue v, Kinds.SpecKey info, boolean ru) {
        if (v.num() == null) return ru ? v.textRu() : v.textEn();
        double x = v.num();
        switch (v.key()) {
            case "storage_gb", "capacity_gb", "ssd_gb" -> {
                if (x >= 1000) {
                    double tb = x % 1024 == 0 ? x / 1024 : x / 1000;
                    return num(tb, ru) + (ru ? " ТБ" : " TB");
                }
            }
            case "pack_qty" -> {
                return num(x, ru) + (ru ? " шт." : " pcs");
            }
            case "handsets" -> {
                return ru ? "трубок: " + num(x, true) : num(x, false) + " handsets";
            }
            case "sheets" -> {
                return num(x, ru) + (ru ? " листов" : " sheets");
            }
            default -> {
            }
        }
        String unit = ru ? info.unitRu() : info.unit();
        if (unit == null) return num(x, ru);
        if (unit.equals("\"")) return num(x, ru) + "\"";
        return num(x, ru) + " " + unit;
    }

    // ================================================================ план товаров

    /** Черновик поколения линейки: общие значения и варианты. */
    private static final class GenDraft {
        Kind kind;
        CategoryTree.Node kindNode;
        Brand brand;
        String modelLine, display, planKey;
        int generation;
        LocalDate launch, discPlan, successor;
        double basePrice, weight, length, width, height;
        int warranty;
        double linePop;
        Map<String, SpecValue> common = new LinkedHashMap<>();
        List<Spec> varSpecs = new ArrayList<>();
        List<int[]> varSubsets = new ArrayList<>();
        Assortment assortment;
        List<Product> products = new ArrayList<>();
    }

    private static final String[] MFG_RU = {"Китай", "Россия", "Вьетнам", "Корея", "Япония", "Малайзия", "Таиланд",
            "Германия", "Польша", "Турция", "Тайвань", "Мексика", "Италия", "Швеция", "Индия", "Беларусь", "Финляндия",
            "Великобритания"};
    private static final String[] MFG_EN = {"China", "Russia", "Vietnam", "Korea", "Japan", "Malaysia", "Thailand",
            "Germany", "Poland", "Turkey", "Taiwan", "Mexico", "Italy", "Sweden", "India", "Belarus", "Finland",
            "United Kingdom"};
    private static final Map<String, String> MFG_BY_COUNTRY = Map.ofEntries(
            Map.entry("CN", "Китай:100"), Map.entry("RU", "Китай:60,Россия:40"),
            Map.entry("KR", "Корея:30,Вьетнам:50,Китай:20"), Map.entry("JP", "Япония:30,Китай:40,Малайзия:15,Таиланд:15"),
            Map.entry("DE", "Германия:35,Китай:35,Польша:15,Турция:15"), Map.entry("TW", "Тайвань:30,Китай:70"),
            Map.entry("US", "Китай:60,Вьетнам:20,Мексика:20"), Map.entry("IT", "Италия:50,Китай:50"),
            Map.entry("SE", "Швеция:30,Китай:40,Польша:30"), Map.entry("TR", "Турция:100"), Map.entry("VN", "Вьетнам:100"),
            Map.entry("IN", "Индия:100"), Map.entry("BY", "Беларусь:100"), Map.entry("PL", "Польша:100"),
            Map.entry("FI", "Финляндия:40,Китай:60"), Map.entry("GB", "Великобритания:30,Китай:70"));

    static SpecValue manufacture(Rng r, String country) {
        String[] opts = MFG_BY_COUNTRY.get(country).split(",");
        double[] w = new double[opts.length];
        for (int i = 0; i < opts.length; i++) w[i] = Double.parseDouble(opts[i].split(":")[1]);
        String ru = opts[r.weighted(w)].split(":")[0];
        int idx = Arrays.asList(MFG_RU).indexOf(ru);
        return new SpecValue("country_of_origin", ru, MFG_EN[idx], null);
    }

    private static final double[] TIER_PRICE = {0.55, 1.0, 2.0};
    private static final double[] TIER_VOLUME = {1.3, 1.0, 0.55};

    static LocalDate plusMonths(LocalDate d, double months) {
        return d.plusDays(StrictMath.round(months * 30.44));
    }

    private static String lineName(Rng r, Kind k, Brand b, Set<String> used, int[] style, int[] base) {
        for (int attempt = 0; attempt < 200; attempt++) {
            double u = r.nextDouble();
            int st = k.cadenceMonths >= 24 ? (u < 0.65 ? 0 : 1) : (u < 0.55 ? 0 : (u < 0.85 ? 1 : 2));
            String name;
            if (st == 1) {
                name = Names.CODE_LETTERS[r.nextInt(Names.CODE_LETTERS.length)];
                base[0] = r.between(1, 8) * 100 + r.between(0, 9) * 10;
            } else {
                name = Names.LINE_WORDS[r.nextInt(Names.LINE_WORDS.length)];
                base[0] = r.between(1, 9);
                if (b.tier().equals("premium") && r.chance(0.4)) name += Names.TIER_SUFFIX_PREMIUM[r.nextInt(4)];
                else if (b.tier().equals("budget") && r.chance(0.3)) name += Names.TIER_SUFFIX_BUDGET[r.nextInt(4)];
            }
            if (used.add(b.id() + "|" + k.code + "|" + name)) {
                style[0] = st;
                return name;
            }
        }
        throw new IllegalStateException("не придумать линейку " + k.code + " у " + b.name());
    }

    private static String genLabel(int style, int base, int g, LocalDate launch) {
        return switch (style) {
            case 1 -> Integer.toString(base + 10 * (g - 1));
            case 2 -> Integer.toString(launch.getYear());
            default -> Integer.toString(base + g - 1);
        };
    }

    /** Линейка до выдачи имени: имя выдаётся вторым проходом, по дате начала (стабильно при продлении). */
    private static final class LineDraft {
        Kind kind;
        Brand brand;
        String key;
        LocalDate start;
        int style, base;
        String modelLine;
        final List<GenDraft> gens = new ArrayList<>();
    }

    /** Линейки и поколения обычных видов (не аксессуаров под модель). */
    static List<GenDraft> planDevices(GenContext ctx, Kinds kinds, CategoryTree tree, List<Brand> brands) {
        Period period = ctx.period();
        RandomStreams.Stream slots = ctx.stream("catalog.slots");
        RandomStreams.Stream lines = ctx.stream("catalog.lines");
        RandomStreams.Stream gens = ctx.stream("catalog.generations");
        List<GenDraft> out = new ArrayList<>();
        List<LineDraft> allLines = new ArrayList<>();
        // число слотов — от постоянного окна v1 (не от настроенного периода), чтобы продление его не меняло
        double refWindow = (LocalDate.of(2027, 10, 30).toEpochDay() - LocalDate.of(2025, 10, 1).toEpochDay()) / 30.44;
        for (CategoryTree.Node node : tree.kindNodes()) {
            Kind k = kinds.kind(node.kind);
            if (k.isCompat()) continue;
            double cadence = k.cadenceMonths;
            double life = cadence + 5;
            double gensInWindow = (refWindow + life) / cadence;
            double perSlot = gensInWindow * k.expectedVariants();
            int nSlots = (int) Math.max(1, StrictMath.round(k.target * TARGET_SCALE / perSlot));
            for (int s = 0; s < nSlots; s++) {
                Rng rs = slots.at(k.code, s);
                LocalDate t = plusMonths(PLAN_START, rs.uniform(0, cadence * 1.5));
                for (int li = 0; !t.isAfter(period.to()); li++) {
                    String lineKey = k.code + "/" + s + "/" + li;
                    Rng rl = lines.at(k.code + "/" + s, li);
                    Brand b = pickBrand(rl, brands, k);
                    LineDraft line = new LineDraft();
                    line.kind = k;
                    line.brand = b;
                    line.key = lineKey;
                    line.start = t;
                    allLines.add(line);
                    int nGens = Math.min(10, 2 + (int) rl.exponential(3.0));
                    double priceLine = k.price * TIER_PRICE[tierIdx(b.tier())] * rl.logNormal(1, k.sigma);
                    double wLine = k.weightG * rl.logNormal(1, 0.12);
                    double lL = k.lengthMm * rl.logNormal(1, 0.08), lW = k.widthMm * rl.logNormal(1, 0.08),
                            lH = k.heightMm * rl.logNormal(1, 0.08);
                    double pop = rl.logNormal(1, 1.0) * TIER_VOLUME[tierIdx(b.tier())] * StrictMath.sqrt(b.weight());
                    Map<String, SpecValue> lineVals = new LinkedHashMap<>();
                    for (Spec sp : k.specs) if (sp.level == Level.LINE) lineVals.put(sp.key, pick(sp, rl, b.tier()));
                    SpecValue mfg = manufacture(rl, b.country());
                    int warranty = k.warranty[Math.min(k.warranty.length - 1,
                            (int) Math.round(tierIdx(b.tier()) * (k.warranty.length - 1) / 2.0))];
                    Assortment assortLine = assortment(rl, k, b);
                    double gapFactor = rl.uniform(0.0, 0.4);
                    LocalDate launch = t;
                    List<LocalDate> launches = new ArrayList<>();
                    for (int g = 1; g <= nGens + 1; g++) {
                        launches.add(launch);
                        launch = plusMonths(launch, cadence * gens.at(lineKey, g).uniform(0.85, 1.2));
                    }
                    for (int g = 1; g <= nGens; g++) {
                        LocalDate l = launches.get(g - 1);
                        if (l.isAfter(period.to())) break;
                        Rng rg = gens.at(lineKey, 1000 + g);
                        LocalDate disc = g < nGens ? plusMonths(launches.get(g), rg.uniform(2, 8))
                                : plusMonths(l, cadence + rg.uniform(3, 12));
                        if (!disc.isAfter(period.from())) continue;
                        GenDraft gd = new GenDraft();
                        gd.kind = k;
                        gd.kindNode = node;
                        gd.brand = b;
                        gd.generation = g;
                        gd.planKey = lineKey + "/" + g;
                        gd.launch = l;
                        gd.discPlan = disc;
                        gd.successor = g < nGens ? launches.get(g) : null;
                        gd.basePrice = priceLine * StrictMath.pow(1.04, g - 1) * rg.logNormal(1, 0.06);
                        gd.weight = wLine;
                        gd.length = lL;
                        gd.width = lW;
                        gd.height = lH;
                        gd.warranty = warranty;
                        gd.linePop = pop * rg.logNormal(1, 0.25);
                        gd.assortment = rg.chance(0.85) ? assortLine : assortment(rg, k, b);
                        for (Spec sp : k.specs) {
                            switch (sp.level) {
                                case LINE -> gd.common.put(sp.key, lineVals.get(sp.key));
                                case GEN -> {
                                    if (sp.type != VType.DERIVED) gd.common.put(sp.key, pick(sp, rg, b.tier()));
                                }
                                case VARIANT -> {
                                    gd.varSpecs.add(sp);
                                    gd.varSubsets.add(subset(sp, rg, b.tier()));
                                }
                                case PRODUCT -> {
                                }
                            }
                        }
                        gd.common.put("country_of_origin", mfg);
                        line.gens.add(gd);
                        out.add(gd);
                    }
                    t = plusMonths(launches.get(nGens), gapFactor * cadence);
                }
            }
        }
        // имена линеек: по дате начала, своим потоком; новые линейки продления — в хвосте и прежних имён не трогают
        allLines.sort(Comparator.comparing((LineDraft l) -> l.start).thenComparing(l -> l.key));
        RandomStreams.Stream ns = ctx.stream("catalog.line_names");
        Set<String> used = new HashSet<>();
        for (LineDraft l : allLines) {
            int[] style = new int[1], base = new int[1];
            l.modelLine = lineName(ns.at(l.key), l.kind, l.brand, used, style, base);
            for (GenDraft gd : l.gens) {
                gd.modelLine = l.modelLine;
                gd.display = l.modelLine + " " + genLabel(style[0], base[0], gd.generation, gd.launch);
            }
        }
        return out;
    }

    static Assortment assortment(Rng r, Kind k, Brand b) {
        if (b.privateLabel()) return Assortment.ONLY_1P;
        double u = r.nextDouble();
        if (u < k.share3p * 0.5) return Assortment.ONLY_3P;
        if (u < k.share3p * 0.5 + 0.35) return Assortment.BOTH;
        return Assortment.ONLY_1P;
    }

    /** Варианты поколения → товары (без id). */
    static void expand(GenContext ctx, GenDraft gd, CategoryTree tree, Map<String, Kinds.SpecKey> keys, List<Product> sink) {
        RandomStreams.Stream st = ctx.stream("catalog.variants");
        int combos = 1;
        for (int[] sub : gd.varSubsets) combos *= sub.length;
        for (int c = 0; c < combos; c++) {
            int rest = c;
            double priceF = 1, sizeF = 1, share = 1;
            List<SpecValue> varVals = new ArrayList<>();
            for (int i = 0; i < gd.varSpecs.size(); i++) {
                Spec sp = gd.varSpecs.get(i);
                int[] sub = gd.varSubsets.get(i);
                int j = rest % sub.length;
                rest /= sub.length;
                SpecValue v = valueAt(sp, sub[j]);
                varVals.add(v);
                if (v.num() != null) {
                    double ref = round3(sp.numAt(sub[0]));
                    if (sp.elasticity > 0 && ref > 0) priceF *= StrictMath.pow(v.num() / ref, sp.elasticity);
                    if (sp.size) {
                        double first = round3(sp.numAt(0));
                        sizeF *= v.num() / first;
                    }
                } else if (sp.elasticity > 0) {
                    priceF *= StrictMath.pow(1 + sp.elasticity, j);
                }
                share *= 1.0 / (1.0 + 0.45 * Math.abs(j - (sub.length - 1) / 2.0) + (v.num() == null ? 0.25 * j : 0));
            }
            Rng r = st.at(gd.planKey, c);
            Product p = new Product();
            p.kind = gd.kind;
            p.brand = gd.brand;
            p.modelLine = gd.modelLine;
            p.display = gd.display;
            p.generation = gd.generation;
            p.launchedOn = gd.launch;
            p.discontinuedPlan = gd.discPlan;
            p.successorLaunch = gd.successor;
            p.warrantyMonths = gd.warranty;
            p.planKey = gd.planKey + "/" + c;
            p.assortment = gd.assortment;
            p.launchPriceKop = Money.retail(gd.basePrice * priceF * r.logNormal(1, 0.02));
            p.weightG = (int) Math.max(5, StrictMath.round(gd.weight * sizeF * sizeF * r.logNormal(1, 0.03)));
            p.lengthMm = (int) Math.max(5, StrictMath.round(gd.length * sizeF));
            p.widthMm = (int) Math.max(5, StrictMath.round(gd.width * sizeF));
            p.heightMm = (int) Math.max(5, StrictMath.round(gd.height * Math.max(1, Math.sqrt(sizeF))));
            p.popularity = gd.linePop * share;
            Map<String, SpecValue> vals = new LinkedHashMap<>(gd.common);
            for (SpecValue v : varVals) vals.put(v.key(), v);
            for (Spec sp : gd.kind.specs) {
                if (sp.level == Level.PRODUCT) vals.put(sp.key, pick(sp, r, gd.brand.tier()));
                if (sp.type == VType.DERIVED) {
                    SpecValue ref = vals.get(sp.ref);
                    vals.put(sp.key, new SpecValue(sp.key, null, null, (double) StrictMath.round(ref.num() * sp.factor)));
                }
            }
            addCommonSpecs(gd, c, p, r, vals);
            p.specs = new ArrayList<>(vals.values());
            String facet = gd.kindNode.facetKey;
            SpecValue fv = facet == null ? null : vals.get(facet);
            if (facet != null && fv == null) throw new IllegalStateException(gd.kind.code + ": нет значения " + facet);
            p.category = tree.leafFor(gd.kindNode, fv == null ? null : fv.num(), fv == null ? null : fv.textRu());
            StringBuilder ru = new StringBuilder(), en = new StringBuilder();
            for (SpecValue v : varVals) {
                ru.append(", ").append(part(v, keys.get(v.key()), true));
                en.append(", ").append(part(v, keys.get(v.key()), false));
            }
            p.nameRu = gd.kind.nameRu + " " + gd.brand.name() + " " + gd.display + ru;
            p.nameEn = gd.brand.name() + " " + gd.display + " " + gd.kind.nameEn + en;
            gd.products.add(p);
            sink.add(p);
        }
    }

    private static final Set<String> BODY_GROUPS = Set.of("phones", "computers", "tv", "audio", "photo", "gaming");
    private static final Set<String> COLOR_GROUPS = Set.of("phones", "computers", "audio", "photo", "gaming",
            "appliances_small", "climate", "beauty", "smart", "tools", "car", "furniture", "emobility", "accessories");
    private static final Set<String> NO_COLOR = Set.of("memory_card", "ssd_m2", "ssd_sata", "ram", "hdd", "psu", "lens",
            "cartridge", "paper", "battery", "battery_rech", "led_bulb", "smart_bulb", "flash", "glass", "film",
            "vacuum_acc", "brush_heads", "tool_battery", "camera_battery", "remote", "antenna", "net_adapter");
    private static final String[][] BODY = {{"пластик", "plastic"}, {"металл", "metal"}, {"стекло и металл", "glass and metal"}};
    private static final String[][] NEUTRAL = {{"чёрный", "black"}, {"белый", "white"}, {"серый", "grey"},
            {"серебристый", "silver"}};

    /** Общие характеристики: год модели, вес нетто, артикул производителя; у техники — корпус; цвет, если нет. */
    static void addCommonSpecs(GenDraft gd, int variant, Product p, Rng r, Map<String, SpecValue> vals) {
        Rng rg = new Rng(Rng.mix64(io.hammerhall.data.core.random.RandomStreams.fnv64(gd.planKey) ^ 0x5bd1e995L));
        vals.put("model_year", new SpecValue("model_year", null, null, (double) gd.launch.getYear()));
        vals.put("net_weight_g", new SpecValue("net_weight_g", null, null,
                (double) Math.max(1, StrictMath.round(p.weightG * r.uniform(0.6, 0.85)))));
        String brand = Names.normalize(gd.brand.name()).toUpperCase(Locale.ROOT);
        String line = Names.normalize(gd.modelLine).toUpperCase(Locale.ROOT);
        String code = brand.substring(0, Math.min(3, brand.length())) + "-" + line.substring(0, Math.min(3, line.length()))
                + gd.generation + "-" + String.format(Locale.ROOT, "%02d", variant + 1)
                + (char) ('A' + r.nextInt(26)) + (char) ('A' + r.nextInt(26));
        vals.put("vendor_code", new SpecValue("vendor_code", code, code, null));
        if (BODY_GROUPS.contains(gd.kind.group) && !gd.kind.isCompat()) {
            int i = tierWindow(rg, BODY.length, gd.brand.tier(), true);
            vals.put("body_material", new SpecValue("body_material", BODY[i][0], BODY[i][1], null));
        }
        if (!vals.containsKey("color") && COLOR_GROUPS.contains(gd.kind.group) && !NO_COLOR.contains(gd.kind.code)) {
            String[] c = NEUTRAL[rg.nextInt(NEUTRAL.length)];
            vals.put("color", new SpecValue("color", c[0], c[1], null));
        }
    }

    /** Аксессуары под конкретные модели: на каждое поколение устройства — несколько линеек аксессуаров. */
    static void planCompat(GenContext ctx, Kinds kinds, CategoryTree tree, List<Brand> brands, List<GenDraft> devices,
                           List<Product> sink) {
        Period period = ctx.period();
        RandomStreams.Stream st = ctx.stream("catalog.compat");
        Map<String, CategoryTree.Node> nodeOf = new HashMap<>();
        for (CategoryTree.Node n : tree.kindNodes()) nodeOf.put(n.kind, n);
        Set<String> used = new HashSet<>();
        for (Kind a : kinds.kinds().values()) {
            if (!a.isCompat()) continue;
            CategoryTree.Node node = nodeOf.get(a.code);
            for (GenDraft dev : devices) {
                if (!a.compat.contains(dev.kind.code)) continue;
                Rng r = st.at(a.code + "|" + dev.planKey);
                int lines = r.between(a.cpmMin, a.cpmMax);
                for (int li = 0; li < lines; li++) {
                    Rng rl = st.at(a.code + "|" + dev.planKey, li);
                    Brand b = pickBrand(rl, brands, a);
                    String word = null;
                    for (int at = 0; at < 50 && word == null; at++) {
                        String w = Names.ACCESSORY_WORDS[rl.nextInt(Names.ACCESSORY_WORDS.length)];
                        if (used.add(b.id() + "|" + a.code + "|" + dev.planKey + "|" + w)) word = w;
                    }
                    if (word == null) continue;
                    GenDraft gd = new GenDraft();
                    gd.kind = a;
                    gd.kindNode = node;
                    gd.brand = b;
                    gd.modelLine = word;
                    gd.display = word;
                    gd.generation = 1;
                    gd.planKey = a.code + "|" + dev.planKey + "/" + li;
                    gd.launch = dev.launch.plusDays(rl.between(0, 30));
                    gd.discPlan = dev.discPlan == null ? null : dev.discPlan.plusDays(rl.between(60, 240));
                    if (gd.launch.isAfter(period.to()) || (gd.discPlan != null && !gd.discPlan.isAfter(period.from()))) continue;
                    gd.basePrice = a.price * TIER_PRICE[tierIdx(b.tier())] * rl.logNormal(1, a.sigma)
                            * StrictMath.pow(dev.basePrice / dev.kind.price, 0.15);
                    gd.weight = a.weightG * rl.logNormal(1, 0.12);
                    gd.length = a.lengthMm;
                    gd.width = a.widthMm;
                    gd.height = a.heightMm;
                    gd.warranty = a.warranty[Math.min(a.warranty.length - 1, tierIdx(b.tier()) * (a.warranty.length - 1) / 2)];
                    gd.linePop = dev.linePop * rl.logNormal(0.5, 0.6);
                    gd.assortment = assortment(rl, a, b);
                    for (Spec sp : a.specs) {
                        if (sp.level == Level.VARIANT) {
                            gd.varSpecs.add(sp);
                            gd.varSubsets.add(subset(sp, rl, b.tier()));
                        } else if (sp.level != Level.PRODUCT && sp.type != VType.DERIVED) {
                            gd.common.put(sp.key, pick(sp, rl, b.tier()));
                        }
                    }
                    String model = dev.brand.name() + " " + dev.display;
                    gd.common.put("compatible_model", new SpecValue("compatible_model", model, model, null));
                    gd.common.put("country_of_origin", manufacture(rl, b.country()));
                    List<Product> before = new ArrayList<>();
                    expand(ctx, gd, tree, kinds.keys(), before);
                    for (Product p : before) {
                        p.compatibleWith = dev.products;
                        int comma = p.nameRu.indexOf(", ");
                        String tailRu = comma < 0 ? "" : p.nameRu.substring(comma);
                        int commaEn = p.nameEn.indexOf(", ");
                        String tailEn = commaEn < 0 ? "" : p.nameEn.substring(commaEn);
                        p.nameRu = a.nameRu + " " + b.name() + " " + word + " для " + model + tailRu;
                        p.nameEn = b.name() + " " + word + " " + a.nameEn + " for " + model + tailEn;
                        sink.add(p);
                    }
                }
            }
        }
    }

    // ================================================================ комплекты

    private record Comp(String kinds, double prob, boolean compat, int maxQty) {
    }

    private record Tpl(String code, String nameRu, String nameEn, String series, String mainKinds, List<Comp> comps,
                       int perMonth, int level) {
    }

    private static final List<Tpl> TEMPLATES = List.of(
            new Tpl("phone_kit", null, null, "Phone Kit", "smartphone",
                    List.of(new Comp("case", 1.0, true, 1), new Comp("glass", 0.9, true, 1), new Comp("charger", 0.3, false, 1)), 30, 1),
            new Tpl("laptop_kit", null, null, "Laptop Kit", "laptop;ultrabook",
                    List.of(new Comp("mouse", 1.0, false, 1), new Comp("laptop_bag", 0.8, false, 1)), 12, 1),
            new Tpl("tv_kit", null, null, "TV Kit", "tv",
                    List.of(new Comp("tv_mount", 1.0, false, 1), new Comp("soundbar", 0.4, false, 1), new Comp("cable_hdmi", 0.5, false, 1)), 10, 1),
            new Tpl("gaming_kit", null, null, "Gaming Kit", "console",
                    List.of(new Comp("gamepad", 1.0, false, 2), new Comp("gaming_headset", 0.5, false, 1)), 4, 1),
            new Tpl("kitchen_kit", null, null, "Kitchen Set", "blender",
                    List.of(new Comp("mixer", 1.0, false, 1), new Comp("kettle", 1.0, false, 1)), 6, 1),
            new Tpl("school_kit", null, null, "School Kit", "tablet",
                    List.of(new Comp("case_tablet", 1.0, true, 1), new Comp("stylus", 0.5, false, 1), new Comp("tws", 0.3, false, 1)), 8, 1),
            new Tpl("camera_kit", null, null, "Camera Kit", "camera_mirrorless",
                    List.of(new Comp("lens", 1.0, false, 1), new Comp("camera_bag", 0.8, false, 1), new Comp("memory_card", 0.8, false, 1)), 4, 1),
            new Tpl("pc_kit", null, null, "PC Kit", "desktop",
                    List.of(new Comp("monitor", 1.0, false, 1), new Comp("kb_mouse_set", 1.0, false, 1)), 6, 1),
            new Tpl("cleaning_kit", null, null, "Cleaning Set", "vacuum_stick",
                    List.of(new Comp("iron", 1.0, false, 1), new Comp("steamer", 0.5, false, 1)), 4, 1),
            new Tpl("tool_kit", null, null, "Tool Kit", "drill_cordless",
                    List.of(new Comp("tool_battery", 0.8, true, 1), new Comp("tool_set", 1.0, false, 1)), 5, 1),
            new Tpl("housewarming", "Набор «Новоселье»", "Housewarming set", "Housewarming Set", "@kitchen_kit",
                    List.of(new Comp("@cleaning_kit", 1.0, false, 1), new Comp("microwave", 1.0, false, 1)), 3, 2),
            new Tpl("student", "Набор «Студент»", "Student set", "Student Set", "@laptop_kit",
                    List.of(new Comp("tws", 1.0, false, 1), new Comp("flash", 1.0, false, 1)), 4, 2),
            new Tpl("big_home", "Набор «Всё для дома»", "Home essentials set", "Home Essentials Set", "@housewarming",
                    List.of(new Comp("@tv_kit", 1.0, false, 1)), 2, 3));

    private static final class Pool {
        final Map<String, List<Product>> byKind = new HashMap<>();
        final Map<String, List<Product>> bundlesByTpl = new HashMap<>();
    }

    private static List<Product> candidates(Pool pool, String kinds, LocalDate at) {
        List<Product> out = new ArrayList<>();
        for (String k : kinds.split(";")) {
            List<Product> src = k.startsWith("@") ? pool.bundlesByTpl.getOrDefault(k.substring(1), List.of())
                    : pool.byKind.getOrDefault(k, List.of());
            for (Product p : src) {
                if (!p.launchedOn.isAfter(at) && (p.discontinuedPlan == null || p.discontinuedPlan.isAfter(at.plusDays(90)))) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static Product pickByPopularity(Rng r, List<Product> cand) {
        double[] w = new double[cand.size()];
        for (int i = 0; i < w.length; i++) w[i] = cand.get(i).popularity;
        return cand.get(r.weighted(w));
    }

    static List<Product> planBundles(GenContext ctx, Kinds kinds, List<Product> products) {
        Period period = ctx.period();
        RandomStreams.Stream st = ctx.stream("catalog.bundles");
        Pool pool = new Pool();
        List<Product> sorted = new ArrayList<>(products);
        sorted.sort(Comparator.comparing((Product p) -> p.launchedOn).thenComparing(p -> p.planKey));
        for (Product p : sorted) pool.byKind.computeIfAbsent(p.kind.code, k -> new ArrayList<>()).add(p);
        List<Product> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> seq = new TreeMap<>();
        for (int level = 1; level <= 3; level++) {
            for (Tpl t : TEMPLATES) {
                if (t.level != level) continue;
                List<Product> made = pool.bundlesByTpl.computeIfAbsent(t.code, k -> new ArrayList<>());
                for (YearMonth m = YearMonth.of(2025, 1); !m.atDay(1).isAfter(period.to()); m = m.plusMonths(1)) {
                    LocalDate at = m.atDay(1);
                    List<Product> mains = candidates(pool, t.mainKinds, at);
                    if (mains.isEmpty()) continue;
                    for (int i = 0; i < t.perMonth; i++) {
                        Rng r = st.at(t.code + "/" + m, i);
                        Product main = pickByPopularity(r, mains);
                        List<BundleItem> items = new ArrayList<>();
                        items.add(new BundleItem(main, 1));
                        boolean ok = true;
                        for (Comp c : t.comps) {
                            if (!r.chance(c.prob)) continue;
                            List<Product> cand = candidates(pool, c.kinds, at);
                            if (c.compat) {
                                List<Product> fit = new ArrayList<>();
                                for (Product x : cand) if (x.compatibleWith.contains(main)) fit.add(x);
                                cand = fit;
                            }
                            if (cand.isEmpty()) {
                                if (c.prob >= 1.0) ok = false;
                                continue;
                            }
                            List<Product> sameBrand = new ArrayList<>();
                            for (Product x : cand) if (x.brand == main.brand) sameBrand.add(x);
                            Product comp = !sameBrand.isEmpty() && r.chance(0.4) ? pickByPopularity(r, sameBrand) : pickByPopularity(r, cand);
                            items.add(new BundleItem(comp, r.between(1, c.maxQty)));
                        }
                        if (!ok || items.size() < 2) continue;
                        StringBuilder sig = new StringBuilder(t.code);
                        for (BundleItem it : items) sig.append('/').append(it.component().planKey).append('x').append(it.qty());
                        if (!seen.add(sig.toString())) continue;
                        LocalDate launch = at.plusDays(r.between(0, 14));
                        LocalDate disc = at.plusDays(r.between(90, 270));
                        for (BundleItem it : items) {
                            LocalDate cd = it.component().discontinuedPlan;
                            if (cd != null && cd.minusDays(7).isBefore(disc)) disc = cd.minusDays(7);
                        }
                        if (disc.toEpochDay() - launch.toEpochDay() < 30) continue;
                        if (launch.isAfter(period.to()) || !disc.isAfter(period.from())) {
                            continue;
                        }
                        int n = seq.merge(t.code, 1, Integer::sum);
                        Product b = new Product();
                        b.bundle = true;
                        b.kind = main.kind;
                        b.brand = main.brand;
                        b.category = main.category;
                        b.modelLine = t.series;
                        b.display = t.series + " " + n;
                        b.generation = 1;
                        b.launchedOn = launch;
                        b.discontinuedPlan = disc;
                        b.warrantyMonths = main.warrantyMonths;
                        b.planKey = "bundle/" + t.code + "/" + m + "/" + i;
                        b.assortment = Assortment.ONLY_1P;
                        b.popularity = 0.3 * main.popularity;
                        b.components = List.copyOf(items);
                        int w = 0, l = 0, wd = 0, h = 0, pieces = 0;
                        StringBuilder ru = new StringBuilder(), en = new StringBuilder();
                        for (int j = 0; j < items.size(); j++) {
                            BundleItem it = items.get(j);
                            Product c = it.component();
                            w += c.weightG * it.qty();
                            l = Math.max(l, c.lengthMm);
                            wd = Math.max(wd, c.widthMm);
                            h += c.heightMm * it.qty();
                            pieces += it.qty();
                            if (j > 0) {
                                String kr = c.bundle ? "комплект «" + c.display + "»" : lowerFirst(c.kind.nameRu);
                                String ke = c.bundle ? c.display + " kit" : c.kind.nameEn;
                                ru.append(" + ").append(it.qty() > 1 ? it.qty() + " × " : "").append(kr);
                                en.append(" + ").append(it.qty() > 1 ? it.qty() + " × " : "").append(ke);
                            }
                        }
                        b.weightG = w;
                        b.lengthMm = l;
                        b.widthMm = wd;
                        b.heightMm = Math.min(h, 2500);
                        if (t.nameRu == null) {
                            b.nameRu = "Комплект: " + main.nameRu + ru;
                            b.nameEn = "Kit: " + main.nameEn + en;
                        } else {
                            b.nameRu = t.nameRu + " № " + n + ": "
                                    + (main.bundle ? "комплект «" + main.display + "»" : lowerFirst(main.kind.nameRu)) + ru;
                            b.nameEn = t.nameEn + " #" + n + ": " + (main.bundle ? main.display + " kit" : main.kind.nameEn) + en;
                        }
                        b.specs = new ArrayList<>(List.of(new SpecValue("pieces", null, null, (double) pieces)));
                        made.add(b);
                        out.add(b);
                    }
                }
            }
        }
        return out;
    }

    /** Первая буква — строчная, если за ней не заглавная (аббревиатуры не трогаем). */
    static String lowerFirst(String s) {
        if (s.isEmpty() || (s.length() > 1 && Character.isUpperCase(s.charAt(1)))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // ================================================================ EAN-13 и артикул

    /** Контрольная цифра EAN-13 по первым 12 цифрам. */
    public static int ean13Check(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (first12.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return (10 - sum % 10) % 10;
    }

    public static boolean ean13Valid(String ean) {
        if (ean == null || ean.length() != 13) return false;
        for (int i = 0; i < 13; i++) if (!Character.isDigit(ean.charAt(i))) return false;
        return ean13Check(ean.substring(0, 12)) == ean.charAt(12) - '0';
    }

    private static final Map<String, int[]> GS1 = Map.ofEntries(
            Map.entry("RU", new int[]{460, 469}), Map.entry("CN", new int[]{690, 699}), Map.entry("KR", new int[]{880, 880}),
            Map.entry("JP", new int[]{490, 499}), Map.entry("DE", new int[]{400, 440}), Map.entry("TW", new int[]{471, 471}),
            Map.entry("US", new int[]{10, 99}), Map.entry("IT", new int[]{800, 839}), Map.entry("SE", new int[]{730, 739}),
            Map.entry("TR", new int[]{868, 869}), Map.entry("VN", new int[]{893, 893}), Map.entry("IN", new int[]{890, 890}),
            Map.entry("BY", new int[]{481, 481}), Map.entry("PL", new int[]{590, 590}), Map.entry("FI", new int[]{640, 649}),
            Map.entry("GB", new int[]{500, 509}));

    static void assignCodes(GenContext ctx, List<Product> products) {
        RandomStreams.Stream st = ctx.stream("catalog.codes");
        long offset = st.hash(0) & 0x7fffffffL;
        Map<Integer, String> prefix = new HashMap<>();
        Map<Integer, Integer> nextItem = new HashMap<>();
        Set<String> usedPrefix = new HashSet<>();
        int bundleSeq = 0;
        for (Product p : products) {
            p.sku = String.format(Locale.ROOT, "KR%08d", (p.id * 48271L + offset) % 100_000_000L);
            String first12;
            if (p.bundle) {
                first12 = String.format(Locale.ROOT, "22%010d", ++bundleSeq);
            } else {
                int bid = p.brand.id();
                int item = nextItem.getOrDefault(bid, -1);
                if (item < 0 || item > 9999) {
                    Rng r = st.at(bid, prefix.containsKey(bid) ? 2 : 1);
                    String pre;
                    do {
                        int[] range = GS1.get(p.brand.country());
                        pre = String.format(Locale.ROOT, "%03d%05d", r.between(range[0], range[1]), r.nextInt(100_000));
                    } while (!usedPrefix.add(pre));
                    prefix.put(bid, pre);
                    item = r.between(0, 999);
                }
                first12 = prefix.get(bid) + String.format(Locale.ROOT, "%04d", item);
                nextItem.put(bid, item + 1);
            }
            int check = ean13Check(first12);
            if (p.assortment == Assortment.ONLY_3P && st.unit(1_000_000L + p.id) < BAD_EAN_SHARE) {
                check = (check + 1 + st.at(2_000_000L + p.id).nextInt(9)) % 10;
                p.badEan = true;
                ctx.planted().add("ean13_bad_check", p.id);
            }
            p.ean13 = first12 + check;
        }
    }

    // ================================================================ генерация

    @Override
    public void generate(GenContext ctx) throws IOException {
        Period period = ctx.period();
        Kinds kinds = Kinds.load();
        CategoryTree tree = CategoryTree.load(kinds);
        List<Brand> brands = buildBrands(ctx);
        List<GenDraft> devices = planDevices(ctx, kinds, tree, brands);
        List<Product> all = new ArrayList<>();
        for (GenDraft gd : devices) expand(ctx, gd, tree, kinds.keys(), all);
        planCompat(ctx, kinds, tree, brands, devices, all);
        all.addAll(planBundles(ctx, kinds, all));
        all.sort(Comparator.comparing((Product p) -> p.launchedOn).thenComparing(p -> p.planKey));
        for (int i = 0; i < all.size(); i++) {
            Product p = all.get(i);
            p.id = i + 1;
            p.discontinuedOn = p.discontinuedPlan != null && !p.discontinuedPlan.isAfter(period.to()) ? p.discontinuedPlan : null;
        }
        assignCodes(ctx, all);

        TableSink sc = ctx.streamSink("categories");
        for (CategoryTree.Node n : tree.nodes()) sc.add(n.id, n.parentId(), n.level, n.nameRu, n.nameEn);
        sc.close();
        TableSink sk = ctx.sink("spec_keys");
        for (Kinds.SpecKey k : kinds.keys().values()) sk.add(k.key(), k.nameRu(), k.nameEn(), k.unit(), k.numeric() ? "num" : "text");
        sk.close();
        TableSink sb = ctx.streamSink("brands");
        for (Brand b : brands) sb.add(b.id(), b.name(), b.country(), b.tier(), b.privateLabel());
        sb.close();
        TableSink sp = ctx.streamSink("products");
        TableSink ss = ctx.streamSink("product_specs");
        TableSink si = ctx.streamSink("bundle_items");
        for (Product p : all) {
            sp.add(p.id, p.sku, p.ean13, p.brand.id(), p.category.id, p.nameRu, p.nameEn, p.modelLine, p.generation,
                    p.launchedOn, p.discontinuedOn, p.weightG, p.lengthMm, p.widthMm, p.heightMm, p.warrantyMonths, p.bundle);
            List<SpecValue> specs = new ArrayList<>(p.specs);
            specs.sort(Comparator.comparing(SpecValue::key));
            for (SpecValue v : specs) {
                Kinds.SpecKey info = kinds.keys().get(v.key());
                ss.add(p.id, v.key(), v.textRu(), v.num() == null ? null : BigDecimal.valueOf(Math.round(v.num() * 1000), 3),
                        v.num() == null ? null : info.unit());
            }
            List<BundleItem> items = new ArrayList<>(p.components);
            items.sort(Comparator.comparingInt(it -> it.component().id));
            for (BundleItem it : items) si.add(p.id, it.component().id, it.qty());
        }
        sp.close();
        ss.close();
        si.close();
        ctx.put(StoKeys.CATALOG, new Catalog(tree, kinds, brands, all));
    }

    // ================================================================ проверки

    @Override
    public void check(GenContext ctx, CheckReport r) {
        Catalog c = ctx.get(StoKeys.CATALOG);
        CategoryTree t = c.tree();
        List<String> tree = new ArrayList<>();
        int roots = 0, maxLevel = 0;
        for (CategoryTree.Node n : t.nodes()) {
            if (n.parent == null) roots++;
            else if (n.level != n.parent.level + 1) tree.add(n.nameRu + ": уровень " + n.level);
            maxLevel = Math.max(maxLevel, n.level);
        }
        if (roots != 1) tree.add("корней " + roots);
        if (maxLevel > 6) tree.add("глубина " + maxLevel);
        r.expectNone("catalog: дерево категорий — один корень, уровни по порядку, до 6", tree,
                "категорий " + t.nodes().size() + ", глубина " + maxLevel);
        r.expect("catalog: категорий ≈ 600", t.nodes().size() >= 500 && t.nodes().size() <= 700, "" + t.nodes().size());

        List<String> br = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Brand b : c.brands()) {
            if (!names.add(Names.normalize(b.name()))) br.add("повтор " + b.name());
            if (!b.privateLabel() && Names.resemblesReal(b.name())) br.add("похоже на настоящую марку: " + b.name());
        }
        r.expectNone("catalog: бренды — выдуманные, уникальные", br, "брендов " + c.brands().size());

        List<String> pv = new ArrayList<>();
        Set<String> skus = new HashSet<>(), eans = new HashSet<>();
        int badEan = 0, empty = 0;
        Set<Integer> usedLeaves = new HashSet<>();
        for (Product p : c.products()) {
            if (!skus.add(p.sku)) pv.add("повтор SKU " + p.sku);
            if (!eans.add(p.ean13)) pv.add("повтор EAN " + p.ean13);
            boolean valid = ean13Valid(p.ean13);
            if (!valid) {
                badEan++;
                if (!ctx.planted().has("ean13_bad_check", p.id)) pv.add(p.id + ": неверный EAN " + p.ean13);
                if (p.assortment != Assortment.ONLY_3P) pv.add(p.id + ": неверный EAN не у товара продавцов");
            }
            if (p.discontinuedOn != null && !p.discontinuedOn.isAfter(p.launchedOn)) pv.add(p.id + ": снят не позже запуска");
            if (p.weightG <= 0 || p.lengthMm <= 0 || p.widthMm <= 0 || p.heightMm <= 0) pv.add(p.id + ": габариты");
            if (!p.category.leaf()) pv.add(p.id + ": категория не лист");
            if (p.bundle != (p.components.size() >= 2)) pv.add(p.id + ": комплект без состава");
            if (p.launchedOn.isAfter(ctx.period().to())) pv.add(p.id + ": запуск после периода");
            if (p.discontinuedPlan != null && !p.discontinuedPlan.isAfter(ctx.period().from())) pv.add(p.id + ": снят до периода");
            usedLeaves.add(p.category.id);
        }
        for (CategoryTree.Node n : t.nodes()) if (n.leaf() && !usedLeaves.contains(n.id)) empty++;
        r.expectNone("catalog: товары — SKU и EAN уникальны, EAN верны (кроме заложенных), даты и габариты", pv,
                "товаров " + c.products().size() + ", заложено неверных EAN " + badEan);
        r.expect("catalog: товаров ≈ 50 тыс.", c.products().size() >= 40_000 && c.products().size() <= 60_000,
                "" + c.products().size());
        r.ok("catalog: пустых листовых категорий", empty + " из " + t.nodes().stream().filter(CategoryTree.Node::leaf).count());

        // комплекты — DAG: обход в глубину с цветами
        List<String> dag = new ArrayList<>();
        Map<Integer, Integer> color = new HashMap<>();
        int bundles = 0, maxDepth = 0;
        for (Product p : c.products()) {
            if (!p.bundle) continue;
            bundles++;
            maxDepth = Math.max(maxDepth, depth(p, color, dag, 0));
            for (BundleItem it : p.components) {
                Product comp = it.component();
                if (comp.launchedOn.isAfter(p.launchedOn)) dag.add(p.id + ": часть " + comp.id + " вышла позже комплекта");
                if (comp.discontinuedPlan != null && (p.discontinuedPlan == null || comp.discontinuedPlan.isBefore(p.discontinuedPlan))) {
                    dag.add(p.id + ": часть " + comp.id + " снята раньше комплекта");
                }
            }
        }
        r.expectNone("catalog: комплекты — без циклов, состав продаётся всё время комплекта", dag,
                "комплектов " + bundles + ", глубина вложенности " + maxDepth);
    }

    private static int depth(Product p, Map<Integer, Integer> color, List<String> dag, int guard) {
        Integer col = color.get(p.id);
        if (col != null && col == 1) {
            dag.add("цикл через " + p.id);
            return 0;
        }
        if (guard > 20) return 0;
        color.put(p.id, 1);
        int d = 0;
        for (BundleItem it : p.components) if (it.component().bundle) d = Math.max(d, depth(it.component(), color, dag, guard + 1));
        color.put(p.id, 2);
        return d + 1;
    }
}
