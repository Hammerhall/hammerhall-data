package io.hammerhall.data.sto.geo;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.geo.Airports;
import io.hammerhall.data.core.geo.GeoMath;
import io.hammerhall.data.core.geo.Polygon;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.sto.StoKeys;
import io.hammerhall.data.sto.geo.Geo.Carrier;
import io.hammerhall.data.sto.geo.Geo.Cell;
import io.hammerhall.data.sto.geo.Geo.City;
import io.hammerhall.data.sto.geo.Geo.Kind;
import io.hammerhall.data.sto.geo.Geo.Warehouse;
import io.hammerhall.data.sto.geo.Geo.Zone;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * География и склады: 149 городов (из аэропортов {@code air-data-v1}), дерево хаб → 8 РЦ → 30 городских складов →
 * ≈ 3 000 пунктов выдачи, ячейки хранения, 8 перевозчиков, зоны доставки — многоугольники вокруг складов.
 *
 * <p>План складов и пунктов выдачи — на фиксированный горизонт (по 2035 год), в выпуск — то, что работает внутри
 * периода; id — по дате открытия. Продление периода дописывает новые пункты в хвост, прежние не меняются.
 */
public final class GeoModule implements Module {
    public static final String NAME = "geo";
    private static final LocalDate PLAN_FROM = LocalDate.of(2019, 6, 1);
    private static final LocalDate PLAN_TO = LocalDate.of(2036, 1, 1);
    /** Пунктов выдачи в плане на весь горизонт; в окне v1 работает примерно половина. */
    private static final int PICKUP_PLAN_TOTAL = 6200;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> tables() {
        return List.of("cities", "warehouses", "storage_cells", "carriers", "delivery_zones");
    }

    // ---------------------------------------------------------------- города

    private record CityRef(String cityEn, String nameEn, String regionRu, String regionEn, String district,
                           int population, String mainAirport, Set<String> exclude) {
    }

    static List<CityRef> loadCityRefs() throws IOException {
        List<CityRef> out = new ArrayList<>();
        try (InputStream in = GeoModule.class.getResourceAsStream("/io/hammerhall/data/sto/cities-ru.tsv")) {
            if (in == null) throw new IOException("нет ресурса cities-ru.tsv");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = java.util.Arrays.copyOf(line.split("\t", -1), 8);
                for (int i = 0; i < f.length; i++) if (f[i] == null) f[i] = "";
                if (f[5].isEmpty()) throw new IOException("cities-ru.tsv: мало полей: " + line);
                Set<String> ex = new HashSet<>();
                if (!f[7].isEmpty()) ex.addAll(List.of(f[7].split(",")));
                out.add(new CityRef(f[0], f[1].isEmpty() ? f[0] : f[1], f[2], f[3], f[4], Integer.parseInt(f[5]),
                        f[6].isEmpty() ? null : f[6], ex));
            }
        }
        return out;
    }

    /** Поправки пояса источника: Балаково — Саратовская область, UTC+4 (в источнике — московский пояс). */
    static final Map<String, String> TZ_FIX = Map.of("Balakovo", "Europe/Saratov");

    static List<City> buildCities(Airports air) throws IOException {
        Map<String, List<Airports.Airport>> byCity = new TreeMap<>();
        for (Airports.Airport a : air.inCountry("Russia")) byCity.computeIfAbsent(a.cityEn(), k -> new ArrayList<>()).add(a);
        Map<String, CityRef> refs = new TreeMap<>();
        for (CityRef c : loadCityRefs()) refs.put(c.cityEn, c);
        if (!refs.keySet().equals(byCity.keySet())) {
            Set<String> a = new java.util.TreeSet<>(byCity.keySet());
            a.removeAll(refs.keySet());
            Set<String> b = new java.util.TreeSet<>(refs.keySet());
            b.removeAll(byCity.keySet());
            throw new IllegalStateException("города аэропортов и справочник регионов разошлись: нет в справочнике " + a
                    + ", нет в аэропортах " + b);
        }
        List<City> tmp = new ArrayList<>();
        for (Map.Entry<String, List<Airports.Airport>> e : byCity.entrySet()) {
            CityRef ref = refs.get(e.getKey());
            List<Airports.Airport> aps = new ArrayList<>();
            for (Airports.Airport a : e.getValue()) if (!ref.exclude.contains(a.code())) aps.add(a);
            aps.sort(Comparator.comparing(Airports.Airport::code));
            double lat = 0, lon = 0;
            for (Airports.Airport a : aps) {
                lat += a.lat();
                lon += a.lon();
            }
            lat /= aps.size();
            lon /= aps.size();
            String tz = aps.getFirst().timezone();
            for (Airports.Airport a : aps) {
                if (!a.timezone().equals(tz)) throw new IllegalStateException(e.getKey() + ": разные пояса аэропортов");
            }
            tz = TZ_FIX.getOrDefault(e.getKey(), tz);
            String main = ref.mainAirport != null ? ref.mainAirport : aps.getFirst().code();
            String code = ref.nameEn.equals("Moscow") ? "MOW" : main;
            tmp.add(new City(0, aps.getFirst().cityRu(), ref.nameEn, ref.regionRu, ref.regionEn, ref.district,
                    GeoMath.round6(lat), GeoMath.round6(lon), ZoneId.of(tz), ref.population, main, code));
        }
        tmp.sort(Comparator.comparing(City::nameEn));
        List<City> out = new ArrayList<>();
        for (int i = 0; i < tmp.size(); i++) {
            City c = tmp.get(i);
            out.add(new City(i + 1, c.nameRu(), c.nameEn(), c.regionRu(), c.regionEn(), c.district(), c.lat(), c.lon(),
                    c.zone(), c.population(), c.airportCode(), c.code()));
        }
        return out;
    }

    /** Радиус города, км: где живут клиенты и стоят пункты выдачи. */
    public static double cityRadiusKm(City c) {
        return 2.5 + 4.2 * StrictMath.log10(Math.max(1000, c.population()) / 1000.0);
    }

    // ---------------------------------------------------------------- перевозчики

    static List<Carrier> buildCarriers() {
        return List.of(
                new Carrier(1, "Кременъ Доставка", "Kremen Delivery", "own_courier"),
                new Carrier(2, "Верстовой", "Verstovoy", "courier"),
                new Carrier(3, "Северная ямская служба", "Northern Yamskaya Service", "courier"),
                new Carrier(4, "Рысь Экспресс", "Rys Express", "express"),
                new Carrier(5, "Полка рядом", "Polka Ryadom", "pickup_network"),
                new Carrier(6, "Почтовый двор", "Pochtovy Dvor", "post"),
                new Carrier(7, "Тягач Карго", "Tyagach Cargo", "freight"),
                new Carrier(8, "Небесный обоз", "Nebesny Oboz", "air"));
    }

    // ---------------------------------------------------------------- склады

    private record DcDef(String district, String city, String opened) {
    }

    private record WhDef(String city, String district, int n, String suffixRu, String suffixEn, String opened) {
    }

    private static final List<DcDef> DCS = List.of(
            new DcDef("central", "Shchyolkovo", "2018-04-02"),
            new DcDef("northwest", "Saint Petersburg", "2018-05-14"),
            new DcDef("volga", "Kazan", "2018-08-01"),
            new DcDef("south", "Rostov-on-Don", "2018-09-03"),
            new DcDef("ural", "Yekaterinburg", "2018-10-15"),
            new DcDef("siberia", "Novosibirsk", "2019-02-04"),
            new DcDef("north_caucasus", "Mineralnye Vody", "2019-04-01"),
            new DcDef("far_east", "Khabarovsk", "2019-06-03"));

    private static final List<WhDef> WHS = List.of(
            new WhDef("Moscow", "central", 1, "Москва-Север", "Moscow North", null),
            new WhDef("Moscow", "central", 2, "Москва-Юг", "Moscow South", null),
            new WhDef("Moscow", "central", 3, "Москва-Восток", "Moscow East", "2026-08-17"),
            new WhDef("Voronezh", "central", 1, "Воронеж", "Voronezh", null),
            new WhDef("Yaroslavl", "central", 1, "Ярославль", "Yaroslavl", null),
            new WhDef("Saint Petersburg", "northwest", 1, "Санкт-Петербург-Север", "Saint Petersburg North", null),
            new WhDef("Saint Petersburg", "northwest", 2, "Санкт-Петербург-Юг", "Saint Petersburg South", null),
            new WhDef("Kaliningrad", "northwest", 1, "Калининград", "Kaliningrad", null),
            new WhDef("Murmansk", "northwest", 1, "Мурманск", "Murmansk", null),
            new WhDef("Rostov-on-Don", "south", 1, "Ростов-на-Дону", "Rostov-on-Don", null),
            new WhDef("Krasnodar", "south", 1, "Краснодар", "Krasnodar", null),
            new WhDef("Volgograd", "south", 1, "Волгоград", "Volgograd", null),
            new WhDef("Sochi", "south", 1, "Сочи", "Sochi", null),
            new WhDef("Stavropol", "north_caucasus", 1, "Ставрополь", "Stavropol", null),
            new WhDef("Makhachkala", "north_caucasus", 1, "Махачкала", "Makhachkala", null),
            new WhDef("Kazan", "volga", 1, "Казань", "Kazan", null),
            new WhDef("Nizhny Novgorod", "volga", 1, "Нижний Новгород", "Nizhny Novgorod", null),
            new WhDef("Samara", "volga", 1, "Самара", "Samara", null),
            new WhDef("Ufa", "volga", 1, "Уфа", "Ufa", null),
            new WhDef("Perm", "volga", 1, "Пермь", "Perm", null),
            new WhDef("Yekaterinburg", "ural", 1, "Екатеринбург", "Yekaterinburg", null),
            new WhDef("Chelyabinsk", "ural", 1, "Челябинск", "Chelyabinsk", null),
            new WhDef("Tyumen", "ural", 1, "Тюмень", "Tyumen", "2027-03-15"),
            new WhDef("Novosibirsk", "siberia", 1, "Новосибирск", "Novosibirsk", null),
            new WhDef("Krasnoyarsk", "siberia", 1, "Красноярск", "Krasnoyarsk", null),
            new WhDef("Omsk", "siberia", 1, "Омск", "Omsk", null),
            new WhDef("Irkutsk", "siberia", 1, "Иркутск", "Irkutsk", null),
            new WhDef("Khabarovsk", "far_east", 1, "Хабаровск", "Khabarovsk", null),
            new WhDef("Vladivostok", "far_east", 1, "Владивосток", "Vladivostok", null),
            new WhDef("Yakutsk", "far_east", 1, "Якутск", "Yakutsk", null));

    /** Черновик склада до выдачи id. */
    private static final class Draft {
        Kind kind;
        String code, nameRu, nameEn, district;
        City city;
        double lat, lon;
        LocalDate opened, closed;
        Draft parent;
        int id;
    }

    private static LocalDate dayBetween(Rng r, LocalDate a, LocalDate b) {
        long span = b.toEpochDay() - a.toEpochDay();
        return a.plusDays((long) (r.nextDouble() * span));
    }

    private static double[] place(Rng r, City c, double minKm, double maxKm) {
        double d = r.uniform(minKm, maxKm);
        double[] p = GeoMath.polar(c.lat(), c.lon(), d, 360.0 * r.nextDouble());
        return new double[]{GeoMath.round6(p[0]), GeoMath.round6(p[1])};
    }

    static List<Warehouse> buildWarehouses(GenContext ctx, List<City> cities) {
        Period period = ctx.period();
        Map<String, City> byName = new TreeMap<>();
        for (City c : cities) byName.put(c.nameEn(), c);
        RandomStreams.Stream st = ctx.stream("geo.warehouses");
        List<Draft> all = new ArrayList<>();

        Draft hub = new Draft();
        hub.kind = Kind.HUB;
        hub.city = byName.get("Zhukovsky");
        hub.code = "HUB-" + hub.city.code();
        hub.nameRu = "Федеральный хаб, Жуковский";
        hub.nameEn = "Federal hub, Zhukovsky";
        hub.district = "central";
        hub.opened = LocalDate.parse("2018-03-01");
        double[] hp = place(st.at(hub.code), hub.city, 4, 9);
        hub.lat = hp[0];
        hub.lon = hp[1];
        all.add(hub);

        Map<String, Draft> dcByDistrict = new TreeMap<>();
        for (DcDef d : DCS) {
            Draft x = new Draft();
            x.kind = Kind.DC;
            x.city = byName.get(d.city);
            x.code = "DC-" + x.city.code();
            x.nameRu = "Распределительный центр " + x.city.nameRu();
            x.nameEn = x.city.nameEn() + " distribution centre";
            x.district = d.district;
            x.opened = LocalDate.parse(d.opened);
            x.parent = hub;
            double[] p = place(st.at(x.code), x.city, 10, 24);
            x.lat = p[0];
            x.lon = p[1];
            dcByDistrict.put(d.district, x);
            all.add(x);
        }

        List<Draft> whs = new ArrayList<>();
        for (WhDef w : WHS) {
            Draft x = new Draft();
            x.kind = Kind.WAREHOUSE;
            x.city = byName.get(w.city);
            if (!x.city.district().equals(w.district)) throw new IllegalStateException(w.city + ": округ " + x.city.district());
            x.code = "WH-" + x.city.code() + "-" + w.n;
            x.nameRu = "Склад " + w.suffixRu;
            x.nameEn = w.suffixEn + " warehouse";
            x.district = w.district;
            Rng r = st.at(x.code);
            x.opened = w.opened != null ? LocalDate.parse(w.opened)
                    : dayBetween(r, LocalDate.of(2018, 11, 1), LocalDate.of(2021, 10, 1));
            x.parent = dcByDistrict.get(w.district);
            double rc = cityRadiusKm(x.city);
            double[] p;
            Double bearing = w.suffixRu.contains("Север") ? Double.valueOf(0) : w.suffixRu.contains("Юг") ? Double.valueOf(180)
                    : w.suffixRu.contains("Восток") ? Double.valueOf(90) : w.suffixRu.contains("Запад") ? Double.valueOf(270) : null;
            if (bearing == null) {
                p = place(r, x.city, 0.35 * rc, 0.85 * rc);
            } else {
                double[] q = GeoMath.polar(x.city.lat(), x.city.lon(), r.uniform(0.45 * rc, 0.85 * rc), bearing + r.uniform(-25, 25));
                p = new double[]{GeoMath.round6(q[0]), GeoMath.round6(q[1])};
            }
            x.lat = p[0];
            x.lon = p[1];
            whs.add(x);
            all.add(x);
        }

        // пункты выдачи: план на горизонт по городам, вес — население^0,8
        double wsum = 0;
        for (City c : cities) wsum += StrictMath.pow(c.population(), 0.8);
        RandomStreams.Stream pst = ctx.stream("geo.pickup");
        for (City c : cities) {
            int n = (int) Math.max(1, StrictMath.round(PICKUP_PLAN_TOTAL * StrictMath.pow(c.population(), 0.8) / wsum));
            double rc = cityRadiusKm(c);
            for (int i = 0; i < n; i++) {
                Rng r = pst.at(c.code(), i);
                Draft x = new Draft();
                x.kind = Kind.PICKUP_POINT;
                x.city = c;
                x.district = c.district();
                x.code = "PVZ-" + c.code() + "-" + String.format(java.util.Locale.ROOT, "%04d", i + 1);
                x.nameRu = "Пункт выдачи " + c.nameRu() + " № " + (i + 1);
                x.nameEn = c.nameEn() + " pickup point #" + (i + 1);
                if (i == 0) {
                    x.opened = dayBetween(r, PLAN_FROM, LocalDate.of(2024, 6, 1));
                } else {
                    x.opened = dayBetween(r, PLAN_FROM, PLAN_TO);
                    if (r.chance(0.18)) x.closed = x.opened.plusDays(r.between(400, 2500));
                }
                double[] p = GeoMath.around(r, c.lat(), c.lon(), rc, 0.62);
                x.lat = GeoMath.round6(p[0]);
                x.lon = GeoMath.round6(p[1]);
                all.add(x);
            }
        }

        // в окне: открыт не позже конца периода и не закрыт до его начала; закрытие после конца — неизвестно
        List<Draft> kept = new ArrayList<>();
        for (Draft d : all) {
            if (d.opened.isAfter(period.to())) continue;
            if (d.closed != null && !d.closed.isAfter(period.from())) continue;
            if (d.closed != null && d.closed.isAfter(period.to())) d.closed = null;
            kept.add(d);
        }
        // родитель пункта выдачи — ближайший городской склад округа, открытый к дню открытия пункта; иначе — РЦ округа
        for (Draft d : kept) {
            if (d.kind != Kind.PICKUP_POINT) continue;
            Draft best = null;
            double bd = Double.MAX_VALUE;
            for (Draft w : whs) {
                if (!w.district.equals(d.district) || w.opened.isAfter(d.opened) || w.opened.isAfter(period.to())) continue;
                double dist = GeoMath.haversineKm(d.lat, d.lon, w.lat, w.lon);
                if (dist < bd) {
                    bd = dist;
                    best = w;
                }
            }
            d.parent = best != null ? best : dcByDistrict.get(d.district);
        }
        kept.sort(Comparator.comparing((Draft d) -> d.opened).thenComparing(d -> d.kind.ordinal()).thenComparing(d -> d.code));
        for (int i = 0; i < kept.size(); i++) kept.get(i).id = i + 1;
        List<Warehouse> out = new ArrayList<>();
        for (Draft d : kept) {
            out.add(new Warehouse(d.id, d.parent == null ? null : d.parent.id, d.kind, d.code, d.nameRu, d.nameEn,
                    d.city, d.lat, d.lon, d.opened, d.closed));
        }
        return out;
    }

    // ---------------------------------------------------------------- ячейки

    private record ZoneDef(String zone, char letter, double share, int shelves, int perShelf, int maxKg, int maxL) {
    }

    private static final List<ZoneDef> CELL_ZONES = List.of(
            new ZoneDef("pallet", 'P', 0.30, 4, 3, 1000, 1800),
            new ZoneDef("shelf", 'S', 0.45, 6, 10, 120, 250),
            new ZoneDef("mezzanine", 'M', 0.15, 8, 20, 15, 25),
            new ZoneDef("oversize", 'O', 0.05, 2, 2, 2000, 6000),
            new ZoneDef("returns", 'R', 0.05, 4, 8, 150, 400));

    static int cellsFor(Kind k) {
        return switch (k) {
            case HUB -> 75_000;
            case DC -> 15_000;
            case WAREHOUSE -> 3_200;
            case PICKUP_POINT -> 0;
        };
    }

    static List<Cell> buildCells(List<Warehouse> warehouses) {
        List<Cell> out = new ArrayList<>();
        int id = 1;
        for (Warehouse w : warehouses) {
            int total = cellsFor(w.kind());
            if (total == 0) continue;
            int done = 0;
            for (int z = 0; z < CELL_ZONES.size(); z++) {
                ZoneDef zd = CELL_ZONES.get(z);
                int n = z == CELL_ZONES.size() - 1 ? total - done : (int) StrictMath.round(total * zd.share);
                done += n;
                int made = 0;
                for (int rack = 1; made < n; rack++) {
                    for (int shelf = 1; shelf <= zd.shelves && made < n; shelf++) {
                        for (int cell = 1; cell <= zd.perShelf && made < n; cell++) {
                            boolean top = shelf == zd.shelves && zd.shelves > 2;
                            int kg = top ? zd.maxKg * 3 / 5 : zd.maxKg;
                            String code = String.format(java.util.Locale.ROOT, "%c-%03d-%d-%02d", zd.letter, rack, shelf, cell);
                            out.add(new Cell(id++, w.id(), zd.zone, code, rack, shelf, cell, kg, zd.maxL));
                            made++;
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- зоны доставки

    private record RingPlan(double[] radiiKm, int[] baseDays, int[] sectors, boolean scaleByCity) {
    }

    private static RingPlan plan(String carrierKind) {
        return switch (carrierKind) {
            case "own_courier" -> new RingPlan(new double[]{12, 35, 70}, new int[]{1, 1, 2}, new int[]{1, 4, 4}, true);
            case "courier" -> new RingPlan(new double[]{20, 60, 150}, new int[]{1, 2, 3}, new int[]{1, 4, 4}, true);
            case "express" -> new RingPlan(new double[]{15, 40}, new int[]{0, 1}, new int[]{1, 4}, true);
            case "pickup_network" -> new RingPlan(new double[]{30, 120}, new int[]{1, 3}, new int[]{1, 4}, true);
            case "post" -> new RingPlan(new double[]{60, 300, 900, 1800}, new int[]{2, 4, 7, 11}, new int[]{1, 4, 6, 6}, false);
            case "freight" -> new RingPlan(new double[]{150, 600, 1500}, new int[]{2, 4, 7}, new int[]{1, 4, 4}, false);
            case "air" -> new RingPlan(new double[]{300, 1500, 3000}, new int[]{2, 3, 5}, new int[]{1, 4, 4}, false);
            default -> throw new IllegalArgumentException(carrierKind);
        };
    }

    /** Какие перевозчики возят от склада (id перевозчиков — из {@link #buildCarriers()}). */
    static List<Carrier> carriersOf(Warehouse w, List<Carrier> carriers) {
        List<Carrier> out = new ArrayList<>();
        boolean north = Set.of("northwest", "ural", "siberia", "far_east").contains(w.city().district());
        for (Carrier c : carriers) {
            boolean use = switch (c.kind()) {
                case "own_courier", "pickup_network", "post" -> w.kind() == Kind.WAREHOUSE;
                case "courier" -> w.kind() == Kind.WAREHOUSE && (c.id() == 3) == north;
                case "express" -> w.kind() == Kind.WAREHOUSE && w.city().population() >= 1_000_000;
                case "freight" -> w.kind() == Kind.DC;
                case "air" -> (w.kind() == Kind.DC && Set.of("siberia", "far_east").contains(w.city().district()))
                        || (w.kind() == Kind.WAREHOUSE && w.city().nameEn().equals("Yakutsk"));
                default -> false;
            };
            if (use) out.add(c);
        }
        return out;
    }

    private static final int GRID = 48;

    static List<Zone> buildZones(GenContext ctx, List<Warehouse> warehouses, List<Carrier> carriers) {
        RandomStreams.Stream st = ctx.stream("geo.zones");
        List<Zone> out = new ArrayList<>();
        int id = 1;
        for (Warehouse w : warehouses) {
            if (w.kind() != Kind.WAREHOUSE && w.kind() != Kind.DC) continue;
            for (Carrier c : carriersOf(w, carriers)) {
                RingPlan p = plan(c.kind());
                Rng r = st.at(w.code(), c.id());
                double scale = p.scaleByCity ? Math.max(0.7, Math.min(1.8, cityRadiusKm(w.city()) / 12.0)) : 1.0;
                double theta0 = r.uniform(0, 360.0 / GRID);
                int rings = p.radiiKm.length;
                // не дальше ±179,5° долготы и 84° широты: внешний пояс с запасом на «волну» радиуса (≤ +16 %)
                double cosLat = StrictMath.cos(StrictMath.toRadians(w.lat()));
                double room = Math.min(Math.min((179.5 - w.lon()) * GeoMath.KM_PER_DEG * cosLat,
                        (w.lon() + 179.5) * GeoMath.KM_PER_DEG * cosLat), (84.0 - w.lat()) * GeoMath.KM_PER_DEG);
                double outer = p.radiiKm[rings - 1] * Math.max(1.0, scale * 0.8) * 1.17;
                double fit = Math.min(1.0, room / outer);
                double[][] radius = new double[rings][GRID];
                for (int k = 0; k < rings; k++) {
                    double a1 = r.uniform(0.04, 0.10), a2 = r.uniform(0.02, 0.06);
                    double f1 = r.uniform(0, 2 * StrictMath.PI), f2 = r.uniform(0, 2 * StrictMath.PI);
                    double base = p.radiiKm[k] * (k == 0 ? scale : Math.max(1.0, scale * 0.8)) * fit;
                    for (int j = 0; j < GRID; j++) {
                        double th = StrictMath.toRadians(theta0 + 360.0 * j / GRID);
                        radius[k][j] = base * (1 + a1 * StrictMath.sin(2 * th + f1) + a2 * StrictMath.sin(5 * th + f2));
                    }
                }
                for (int k = 0; k < rings; k++) {
                    int sectors = p.sectors[k];
                    int step = GRID / sectors;
                    for (int s = 0; s < sectors; s++) {
                        List<double[]> pts = new ArrayList<>();
                        if (k == 0) {
                            for (int j = 0; j < GRID; j++) pts.add(vertex(w, radius[0][j], theta0 + 360.0 * j / GRID));
                        } else {
                            for (int j = s * step; j <= (s + 1) * step; j++) {
                                pts.add(vertex(w, radius[k][j % GRID], theta0 + 360.0 * (j % GRID) / GRID));
                            }
                            for (int j = (s + 1) * step; j >= s * step; j--) {
                                pts.add(vertex(w, radius[k - 1][j % GRID], theta0 + 360.0 * (j % GRID) / GRID));
                            }
                        }
                        Polygon poly = ccw(pts);
                        int days = p.baseDays[k] + (k > 0 && r.chance(0.3) ? 1 : 0);
                        out.add(new Zone(id++, w.id(), c.id(), k, poly, days));
                    }
                }
            }
        }
        return out;
    }

    private static double[] vertex(Warehouse w, double km, double bearing) {
        double[] p = GeoMath.polar(w.lat(), w.lon(), km, bearing);
        double lat = Math.max(-85.0, Math.min(85.0, p[0]));
        if (p[1] <= -180 || p[1] >= 180) throw new IllegalStateException(w.code() + ": зона пересекает 180-й меридиан");
        return new double[]{lat, p[1]};
    }

    private static Polygon ccw(List<double[]> pts) {
        int n = pts.size();
        double[] lon = new double[n], lat = new double[n];
        for (int i = 0; i < n; i++) {
            lat[i] = pts.get(i)[0];
            lon[i] = pts.get(i)[1];
        }
        Polygon p = new Polygon(lon, lat);
        if (p.signedArea2() > 0) return p;
        double[] rl = new double[n], ra = new double[n];
        for (int i = 0; i < n; i++) {
            rl[i] = lon[n - 1 - i];
            ra[i] = lat[n - 1 - i];
        }
        return new Polygon(rl, ra);
    }

    // ---------------------------------------------------------------- генерация

    @Override
    public void generate(GenContext ctx) throws IOException {
        Airports air = Airports.load(ctx.config().input("airports"));
        List<City> cities = buildCities(air);
        List<Carrier> carriers = buildCarriers();
        List<Warehouse> warehouses = buildWarehouses(ctx, cities);
        List<Cell> cells = buildCells(warehouses);
        List<Zone> zones = buildZones(ctx, warehouses, carriers);

        TableSink sc = ctx.sink("cities");
        for (City c : cities) {
            sc.add(c.id(), c.nameRu(), c.nameEn(), c.regionRu(), c.regionEn(), c.district(), c.lat(), c.lon(),
                    c.zone().getId(), c.population(), c.airportCode());
        }
        sc.close();
        TableSink sca = ctx.sink("carriers");
        for (Carrier c : carriers) sca.add(c.id(), c.nameRu(), c.nameEn(), c.kind());
        sca.close();
        TableSink sw = ctx.sink("warehouses");
        for (Warehouse w : warehouses) {
            sw.add(w.id(), w.parentId(), w.kind().code, w.code(), w.nameRu(), w.nameEn(), w.city().id(), w.lat(), w.lon(),
                    w.openedOn(), w.closedOn());
        }
        sw.close();
        TableSink scl = ctx.streamSink("storage_cells");
        for (Cell c : cells) {
            scl.add(c.id(), c.warehouseId(), c.zone(), c.code(), c.rack(), c.shelf(), c.cell(), c.maxWeightKg(), c.maxVolumeL());
        }
        scl.close();
        TableSink sz = ctx.sink("delivery_zones");
        for (Zone z : zones) sz.add(z.id(), z.warehouseId(), z.carrierId(), z.ring(), z.polygon().wkt(), z.baseDays());
        sz.close();
        ctx.put(StoKeys.GEO, new Geo(cities, warehouses, cells, carriers, zones));
    }

    // ---------------------------------------------------------------- проверки

    @Override
    public void check(GenContext ctx, CheckReport r) {
        Geo g = ctx.get(StoKeys.GEO);
        Period p = ctx.period();
        r.expect("geo: 149 городов", g.cities().size() == 149, "городов " + g.cities().size());
        List<String> bad = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (City c : g.cities()) {
            try {
                ZoneId.of(c.zone().getId());
            } catch (DateTimeException e) {
                bad.add(c.nameEn() + ": пояс " + c.zone());
            }
            if (!names.add(c.nameRu())) bad.add("повтор имени " + c.nameRu());
            if (c.lat() < 41 || c.lat() > 82) bad.add(c.nameEn() + ": широта " + c.lat());
        }
        r.expectNone("geo: города — пояса, имена, координаты", bad, "поясов " + g.cities().stream().map(c -> c.zone().getId()).distinct().count());

        // дерево складов
        List<String> tree = new ArrayList<>();
        int roots = 0;
        for (Warehouse w : g.warehouses()) {
            if (w.parentId() == null) {
                roots++;
                if (w.kind() != Kind.HUB) tree.add(w.code() + ": корень не хаб");
                continue;
            }
            Warehouse par = g.warehouse(w.parentId());
            boolean ok = switch (w.kind()) {
                case HUB -> false;
                case DC -> par.kind() == Kind.HUB;
                case WAREHOUSE -> par.kind() == Kind.DC;
                case PICKUP_POINT -> par.kind() == Kind.WAREHOUSE || par.kind() == Kind.DC;
            };
            if (!ok) tree.add(w.code() + ": родитель " + par.code());
            if (par.openedOn().isAfter(w.openedOn())) tree.add(w.code() + ": открыт раньше родителя " + par.code());
            if (!par.city().district().equals(w.city().district()) && w.kind() != Kind.DC) {
                tree.add(w.code() + ": родитель из другого округа");
            }
        }
        if (roots != 1) tree.add("корней " + roots);
        r.expectNone("geo: дерево складов — один корень, виды по уровням", tree,
                "хаб 1, РЦ " + g.ofKind(Kind.DC).size() + ", складов " + g.ofKind(Kind.WAREHOUSE).size()
                        + ", пунктов выдачи " + g.ofKind(Kind.PICKUP_POINT).size());

        List<String> pvz = new ArrayList<>();
        for (City c : g.cities()) {
            if (g.pickupPointsOpen(c.id(), p.from()).isEmpty()) pvz.add(c.nameEn() + ": нет пункта на " + p.from());
            if (g.pickupPointsOpen(c.id(), p.to()).isEmpty()) pvz.add(c.nameEn() + ": нет пункта на " + p.to());
        }
        for (Warehouse w : g.ofKind(Kind.PICKUP_POINT)) {
            double d = GeoMath.haversineKm(w.lat(), w.lon(), w.city().lat(), w.city().lon());
            if (d > cityRadiusKm(w.city()) * 1.05) pvz.add(w.code() + ": " + Math.round(d) + " км от центра");
        }
        r.expectNone("geo: в каждом городе работает пункт выдачи весь период, пункты — у города", pvz, "");

        // ячейки
        List<String> cells = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (Cell c : g.cells()) {
            if (!g.warehouse(c.warehouseId()).stores()) cells.add(c.code() + " в пункте выдачи");
            if (!codes.add(c.warehouseId() + "/" + c.code())) cells.add("повтор " + c.warehouseId() + "/" + c.code());
        }
        r.expectNone("geo: ячейки — только на складах, адреса уникальны", cells, "ячеек " + g.cells().size());

        // зоны
        List<String> zb = new ArrayList<>();
        Map<Long, Boolean> centreCovered = new TreeMap<>();
        for (Zone z : g.zones()) {
            Polygon poly = z.polygon();
            if (poly.lon(0) != poly.lon(poly.vertices() - 1) || poly.lat(0) != poly.lat(poly.vertices() - 1)) zb.add(z.id() + ": не замкнут");
            if (poly.signedArea2() <= 0) zb.add(z.id() + ": обход не против часовой");
            Warehouse w = g.warehouse(z.warehouseId());
            long key = ((long) z.warehouseId() << 32) | z.carrierId();
            if (z.ring() == 0) centreCovered.put(key, poly.contains(w.lon(), w.lat()));
            if (!Polygon.parseWkt(poly.wkt()).wkt().equals(poly.wkt())) zb.add(z.id() + ": WKT не читается обратно");
        }
        for (Map.Entry<Long, Boolean> e : centreCovered.entrySet()) if (!e.getValue()) zb.add("склад " + (e.getKey() >> 32) + ": вне ближней зоны");
        // зоны одного склада и перевозчика не перекрываются: случайные точки вокруг склада
        RandomStreams.Stream st = ctx.stream("geo.check");
        int samples = 0;
        for (Map.Entry<Long, Boolean> e : centreCovered.entrySet()) {
            int wid = (int) (e.getKey() >> 32), cid = (int) (e.getKey() & 0xffffffffL);
            Warehouse w = g.warehouse(wid);
            List<Zone> zs = g.zonesOf(wid, cid);
            double maxR = plan(g.carrier(cid).kind()).radiiKm[plan(g.carrier(cid).kind()).radiiKm.length - 1] * 1.3;
            Rng rr = st.at(wid, cid);
            for (int i = 0; i < 400; i++) {
                double[] pt = GeoMath.around(rr, w.lat(), w.lon(), maxR, 0.5);
                int n = 0;
                for (Zone z : zs) if (z.polygon().contains(pt[1], pt[0])) n++;
                samples++;
                if (n > 1) zb.add("склад " + wid + ", перевозчик " + cid + ": точка в " + n + " зонах");
            }
        }
        r.expectNone("geo: зоны — замкнуты, против часовой, склад в ближней зоне, не перекрываются", zb,
                "зон " + g.zones().size() + ", проверено точек " + samples);
    }
}
