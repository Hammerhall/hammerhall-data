package io.hammerhall.data.sto.supply;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.sto.StoKeys;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.CatalogModule;
import io.hammerhall.data.sto.catalog.Names;
import io.hammerhall.data.sto.geo.Geo;
import io.hammerhall.data.sto.geo.GeoModule;
import io.hammerhall.data.sto.supply.Suppliers.Supplier;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Поставщики (≈ 300, все выдуманные): представительства брендов, дистрибьюторы, импортёры, российские производители.
 * Справочник без времени: от периода не зависит. Заказы поставщикам, партии и движения — модуль {@code stock}.
 *
 * <p>Имена дистрибьюторов, импортёров и заводов — из слогов, тем же генератором и с тем же отсевом настоящих марок,
 * что у брендов ({@link Names#brand}); корни не повторяют бренды каталога. Общеупотребительных слов в корнях нет —
 * «Агат-Импорт» и подобные почти наверняка совпали бы с настоящими фирмами.
 */
public final class SupplierModule implements Module {
    public static final String NAME = "suppliers";

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
        return List.of("suppliers");
    }

    private static final String[][] SUFFIX = {{"-Импорт", "-Import"}, {" Дистрибуция", " Distribution"},
            {"-Снаб", "-Snab"}, {" Трейд", " Trade"}, {"-Логистик", "-Logistic"}, {" Поставка", " Supply"},
            {"-Опт", "-Opt"}, {" Электроторг", " Electrotorg"}, {" Техносбыт", " Tekhnosbyt"}};

    @Override
    public void generate(GenContext ctx) throws IOException {
        Geo geo = ctx.get(StoKeys.GEO);
        Catalog cat = ctx.get(StoKeys.CATALOG);
        RandomStreams.Stream st = ctx.stream("suppliers");
        record Draft(String nameRu, String nameEn, String kind, int cityId, int lead, BigDecimal rel, List<Integer> brands) {
        }
        List<Draft> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        // корни имён — из слогов, как бренды; заняты имена брендов (нижний регистр, только буквы и цифры)
        Set<String> takenRoots = new TreeSet<>();
        for (Catalog.Brand b : cat.brands()) takenRoots.add(b.name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", ""));
        RandomStreams.Stream nameSt = ctx.stream("suppliers.names");
        List<Geo.City> big = new ArrayList<>();
        for (Geo.City c : geo.cities()) if (c.population() >= 300_000) big.add(c);
        double[] bigW = new double[big.size()];
        for (int i = 0; i < bigW.length; i++) bigW[i] = StrictMath.pow(big.get(i).population(), 1.2);
        Geo.City moscow = geo.cityByName("Moscow"), spb = geo.cityByName("Saint Petersburg");

        // представительства брендов: у зарубежных брендов — 80 %
        Set<Integer> covered = new TreeSet<>();
        for (Catalog.Brand b : cat.brands()) {
            if (b.privateLabel() || b.country().equals("RU")) continue;
            Rng r = st.at("office", b.id());
            if (!r.chance(0.8)) continue;
            String ru = "ООО «" + Names.toCyrillic(b.name()) + " Рус»", en = b.name() + " Rus LLC";
            if (!names.add(ru)) continue;
            double u = r.nextDouble();
            int city = u < 0.75 ? moscow.id() : (u < 0.9 ? spb.id() : big.get(r.nextInt(big.size())).id());
            out.add(new Draft(ru, en, "brand_office", city, r.between(5, 14), rel(r), List.of(b.id())));
            covered.add(b.id());
        }
        List<Catalog.Brand> nonPl = new ArrayList<>();
        for (Catalog.Brand b : cat.brands()) if (!b.privateLabel()) nonPl.add(b);
        // дистрибьюторы, импортёры, производители — до 300 всего
        int nDist = 130, nImp = 50, nMan = 300 - out.size() - nDist - nImp;
        for (int i = 0; i < nDist + nImp + nMan; i++) {
            String kind = i < nDist ? "distributor" : (i < nDist + nImp ? "importer" : "manufacturer");
            Rng r = st.at(kind, i);
            Rng rn = nameSt.at(kind, i);
            String ru = null, en = null;
            for (int a = 0; a < 500 && ru == null; a++) {
                String rootEn = Names.brand(rn, takenRoots);
                String rootRu = Names.toCyrillic(rootEn);
                if (kind.equals("manufacturer")) {
                    boolean plant = rn.chance(0.5);
                    String c1 = "АО «" + (plant ? "Завод" : "Фабрика") + " «" + rootRu + "»»";
                    if (names.add(c1)) {
                        ru = c1;
                        en = "JSC " + rootEn + (plant ? " Plant" : " Factory");
                    }
                } else {
                    String[] sfx = SUFFIX[rn.nextInt(SUFFIX.length)];
                    String c1 = "ООО «" + rootRu + sfx[0] + "»";
                    if (names.add(c1)) {
                        ru = c1;
                        en = rootEn + sfx[1] + " LLC";
                    }
                }
            }
            if (ru == null) throw new IllegalStateException("не придумать имя поставщика");
            List<Integer> brands = new ArrayList<>();
            int city, lead;
            switch (kind) {
                case "distributor" -> {
                    int n = r.between(3, 12);
                    for (int j = 0; j < n; j++) brands.add(nonPl.get(r.nextInt(nonPl.size())).id());
                    city = big.get(r.weighted(bigW)).id();
                    lead = r.between(3, 10);
                }
                case "importer" -> {
                    List<Catalog.Brand> cheap = new ArrayList<>();
                    for (Catalog.Brand b : nonPl) if (Set.of("CN", "TR", "VN", "IN").contains(b.country())) cheap.add(b);
                    int n = r.between(5, 15);
                    for (int j = 0; j < n; j++) brands.add(cheap.get(r.nextInt(cheap.size())).id());
                    String[] ports = {"Moscow", "Saint Petersburg", "Vladivostok", "Kaliningrad", "Rostov-on-Don", "Yekaterinburg", "Novosibirsk"};
                    city = geo.cityByName(ports[r.nextInt(ports.length)]).id();
                    lead = r.between(25, 60);
                }
                default -> {
                    List<Catalog.Brand> local = new ArrayList<>();
                    for (Catalog.Brand b : cat.brands()) if (b.country().equals("RU") || b.privateLabel()) local.add(b);
                    int n = r.between(1, 4);
                    for (int j = 0; j < n; j++) brands.add(local.get(r.nextInt(local.size())).id());
                    city = big.get(r.weighted(bigW)).id();
                    lead = r.between(10, 30);
                }
            }
            brands = new ArrayList<>(new TreeSet<>(brands));
            covered.addAll(brands);
            out.add(new Draft(ru, en, kind, city, lead, rel(r), brands));
        }
        // бренды без поставщика — к дистрибьюторам по кругу
        List<Integer> distIdx = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) if (out.get(i).kind.equals("distributor")) distIdx.add(i);
        int k = 0;
        for (Catalog.Brand b : cat.brands()) {
            if (covered.contains(b.id())) continue;
            int i = distIdx.get(k++ % distIdx.size());
            Draft d = out.get(i);
            List<Integer> bl = new ArrayList<>(new TreeSet<>(d.brands));
            bl.add(b.id());
            out.set(i, new Draft(d.nameRu, d.nameEn, d.kind, d.cityId, d.lead, d.rel, List.copyOf(new TreeSet<>(bl))));
        }
        // три «ненадёжных» поставщика — заложенный узор (поставки с опозданием)
        Set<Integer> weak = new TreeSet<>();
        for (int j = 0; j < 3; j++) {
            int i = st.at("unreliable", j).nextInt(out.size());
            for (int k2 = 1; weak.contains(i); k2++) i = st.at("unreliable", j * 100 + k2).nextInt(out.size());
            weak.add(i);
            Draft d = out.get(i);
            out.set(i, new Draft(d.nameRu, d.nameEn, d.kind, d.cityId, d.lead,
                    BigDecimal.valueOf(Math.round(st.at("unreliable", j).uniform(0.55, 0.7) * 1000), 3), d.brands));
            ctx.planted().add("unreliable_supplier", i + 1);
        }
        List<Supplier> sup = new ArrayList<>();
        TableSink s = ctx.streamSink("suppliers");
        for (int i = 0; i < out.size(); i++) {
            Draft d = out.get(i);
            sup.add(new Supplier(i + 1, d.nameRu, d.nameEn, d.kind, d.cityId, d.lead, d.rel, d.brands));
            s.add(i + 1, d.nameRu, d.nameEn, d.kind, d.cityId, d.lead, d.rel);
        }
        s.close();
        ctx.put(StoKeys.SUPPLIERS, new Suppliers(sup));
    }

    private static BigDecimal rel(Rng r) {
        return BigDecimal.valueOf(Math.round(r.gaussianClamped(0.94, 0.03, 0.8, 0.995) * 1000), 3);
    }

    @Override
    public void check(GenContext ctx, CheckReport r) {
        Suppliers s = ctx.get(StoKeys.SUPPLIERS);
        Catalog cat = ctx.get(StoKeys.CATALOG);
        List<String> v = new ArrayList<>();
        for (Supplier x : s.all()) {
            if (x.leadTimeDays() <= 0) v.add(x.id() + ": срок");
            if (x.reliability().signum() <= 0 || x.reliability().compareTo(BigDecimal.ONE) > 0) v.add(x.id() + ": надёжность");
            if (x.brandIds().isEmpty()) v.add(x.id() + ": без брендов");
        }
        for (Catalog.Brand b : cat.brands()) if (s.ofBrand(b.id()).isEmpty()) v.add("бренд " + b.name() + " без поставщика");
        r.expectNone("suppliers: сроки, надёжность, у каждого бренда есть поставщик", v, "поставщиков " + s.all().size());
        List<String> names = new ArrayList<>();
        for (Supplier x : s.all()) {
            if (x.kind().equals("brand_office")) continue;
            String[] w = x.nameEn().split(" ");
            String root = (x.kind().equals("manufacturer") ? w[1] : w[0]).replaceAll("-.*", "");
            if (Names.resemblesReal(root)) names.add(x.nameEn());
        }
        r.expectNone("suppliers: корни имён не похожи на настоящие марки", names, "проверено по списку брендов");
    }
}
