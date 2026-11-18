package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Справочник выдуманных мастеров ({@link MakerKind#count} каждого вида, ≈ 4 200): люди и организации, имена — по-русски
 * и по-английски, годы, место, уровень рынка A–D и справка у A и B. Строится из {@code seed}: мастер {@code i} вида — из
 * потока {@link #STREAM} под ключ (вид, i); имена — без повторов и без настоящих ({@link RealNames}). Один и тот же
 * {@code seed} — тот же справочник на любой ОС и в любом профиле.
 *
 * <p>Популярность мастера (сколько у него вещей) — логнормальный вес: у немногих — много вещей, у большинства — по
 * нескольку ({@link #pick}); уровень рынка — по месту в этом ряду внутри вида (A — верхние 3 %, B — 12 %, C — 35 %).
 */
public final class Makers {
    /** Поток случайности справочника. */
    public static final String STREAM = "catalog.makers";
    /** «Сейчас» текстов: позже этого года ничего не датируется. */
    static final int NOW_YEAR = 2026;

    private final List<Maker> all;
    private final Map<MakerKind, List<Maker>> byKind = new EnumMap<>(MakerKind.class);
    private final Map<MakerKind, double[]> cumWeight = new EnumMap<>(MakerKind.class);

    private Makers(List<Maker> all, Map<MakerKind, double[]> weights) {
        this.all = List.copyOf(all);
        for (Maker m : all) byKind.computeIfAbsent(m.kind(), k -> new ArrayList<>()).add(m);
        for (Map.Entry<MakerKind, double[]> e : weights.entrySet()) {
            double[] w = e.getValue(), c = new double[w.length];
            double s = 0;
            for (int i = 0; i < w.length; i++) {
                s += w[i];
                c[i] = s;
            }
            cumWeight.put(e.getKey(), c);
        }
    }

    public List<Maker> all() {
        return all;
    }

    public Maker byId(int id) {
        return all.get(id - 1);
    }

    public List<Maker> of(MakerKind kind) {
        return byKind.getOrDefault(kind, List.of());
    }

    /** Мастер вида с учётом популярности. */
    public Maker pick(MakerKind kind, Rng r) {
        double[] c = cumWeight.get(kind);
        double x = r.nextDouble() * c[c.length - 1];
        int i = Arrays.binarySearch(c, x);
        if (i < 0) i = -i - 1;
        return byKind.get(kind).get(Math.min(i, c.length - 1));
    }

    public static Makers build(RandomStreams streams) {
        return new Builder(streams.stream(STREAM)).build();
    }

    public static Makers build(long seed) {
        return build(new RandomStreams(seed));
    }

    // ---------------------------------------------------------------- таблица

    /** Поля таблицы {@code makers} — порядок значений {@link #row}. */
    public static final List<String> COLUMNS = List.of("id", "kind", "department_id", "is_person", "name_ru", "name_en",
            "first_name", "patronymic", "last_name", "active_from", "active_to", "country", "city_ru", "school_ru",
            "specialty_ru", "mark_ru", "tier");

    /** Строка таблицы мастеров: значения по {@link #COLUMNS} ({@code null} — пусто); отдел — его {@code sortOrder}. */
    public static Object[] row(Maker m) {
        Person p = m.isPerson() ? m.person() : null;
        return new Object[]{m.id(), m.kind().code, (short) m.department().sortOrder(), m.isPerson(), m.name(), m.nameEn(),
                p == null ? null : p.first(), p == null ? null : p.patronymic(), p == null ? null : p.last(),
                (short) m.yearFrom(), m.yearTo() == null ? null : (short) (int) m.yearTo(), m.country().name(),
                m.city() == null ? null : m.city().name(), m.school(), m.specialty(), m.mark(), m.tier()};
    }

    public List<Object[]> table() {
        List<Object[]> out = new ArrayList<>();
        for (Maker m : all) out.add(row(m));
        return out;
    }

    // ---------------------------------------------------------------- направления и специализации

    /** Направление: годы, страна ({@code null} — любая), род: {@code fig} — фигуративное, {@code abs} — абстрактное, {@code any}. */
    record Movement(String nom, String gen, int from, int to, Country only, String style) {
    }

    static final Movement[] MOVEMENTS;

    record Specialty(String genre, String nameRu, int bornFrom, double weight) {
    }

    static final Specialty[] ARTIST_SPECIALTIES;

    static {
        List<Movement> mv = new ArrayList<>();
        for (String s : V.list("common", "movements")) {
            String[] f = s.split("\\s*:\\s*");
            mv.add(new Movement(f[0], f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4].equals("-") ? null : Country.valueOf(f[4]), f[5]));
        }
        MOVEMENTS = mv.toArray(new Movement[0]);
        List<Specialty> sp = new ArrayList<>();
        for (String s : V.list("common", "artist.specialties")) {
            String[] f = s.split("\\s*:\\s*");
            sp.add(new Specialty(f[0], f[1], Integer.parseInt(f[2]), Double.parseDouble(f[3])));
        }
        ARTIST_SPECIALTIES = sp.toArray(new Specialty[0]);
    }

    static Movement movement(String nom) {
        for (Movement m : MOVEMENTS) if (m.nom.equals(nom)) return m;
        return null;
    }

    /** Абстракционист: специализация «художник-абстракционист». */
    static boolean abstractArtist(Maker m) {
        if (m == null || m.kind() != MakerKind.ARTIST) return false;
        Specialty s = specialty(m.specialty());
        return s != null && s.genre().equals("abstract");
    }

    static String movementGen(String nom) {
        for (Movement m : MOVEMENTS) if (m.nom.equals(nom)) return m.gen;
        return nom;
    }

    static Specialty specialty(String nameRu) {
        for (Specialty s : ARTIST_SPECIALTIES) if (s.nameRu.equals(nameRu)) return s;
        return null;
    }

    // ---------------------------------------------------------------- названия организаций

    /** Шапка: им., род., с предлогом места, английский шаблон ({@code {o}} — владелец или название). */
    record Head(String nom, String gen, String prep, String en) {
        String en(String o) {
            return en.replace("{o}", o);
        }
    }

    private static final Map<String, List<Head>> HEADS = new HashMap<>();

    static {
        for (String line : V.lines("common", "heads")) {
            String[] f = line.split("\\s*;\\s*");
            HEADS.computeIfAbsent(f[0], k -> new ArrayList<>()).add(new Head(f[1], f[2], f[3], f[4]));
        }
    }

    static List<Head> heads(MakerKind k, String type) {
        return HEADS.getOrDefault(k.name() + "." + type, List.of());
    }

    private static final V.P[] SYL_START = V.pairs("common", "syllables.start");
    private static final V.P[] SYL_MID = V.pairs("common", "syllables.mid");
    private static final V.P[] SYL_END = V.pairs("common", "syllables.end");
    private static final String VOWELS = "аеёиоуыэюя";

    private static String dash(String s) {
        return s.equals("-") ? "" : s;
    }

    private static boolean badCluster(String s) {
        int cons = 0;
        String low = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < low.length(); i++) {
            char ch = low.charAt(i);
            boolean v = VOWELS.indexOf(ch) >= 0;
            if (v && i > 0 && VOWELS.indexOf(low.charAt(i - 1)) >= 0) return true;
            if (ch == 'ь' || ch == 'ъ') continue;
            cons = v ? 0 : cons + 1;
            if (cons >= 3) return true;
            if (i > 0 && ch == low.charAt(i - 1) && !v) return true;
        }
        return false;
    }

    /** Неблагозвучные основы — выдуманные названия с ними не берём. */
    private static final String[] UGLY = {"клоак", "срак", "сран", "жоп", "говн", "дерьм", "блев", "пук", "перд", "сопл",
            "гной", "моч", "глист", "вон", "гнил", "хер", "хуй", "бля", "сук", "как", "пис", "задн", "дур", "туп", "гад"};

    static boolean ugly(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        for (String u : UGLY) if (l.contains(u)) return true;
        return false;
    }

    /** Слоговое название {ру, en} — {@code null}, если не вышло (длина, стечения, похоже на марку). */
    static String[] coined(Rng r) {
        V.P a = V.pick(r, SYL_START), b = V.pick(r, SYL_MID), c = V.pick(r, SYL_END);
        String ru = a.ru() + dash(b.ru()) + c.ru(), en = a.en() + dash(b.en()) + c.en();
        if (ru.length() < 5 || ru.length() > 9 || badCluster(ru) || ugly(ru)) return null;
        if (RealNames.resemblesBrand(ru)) return null;
        return new String[]{ru, en};
    }

    private static final V.P[] TOWN_DE_START = V.pairs("common", "town.de.start");
    private static final V.P[] TOWN_DE_END = V.pairs("common", "town.de.end");
    private static final V.P[] TOWN_FR_START = V.pairs("common", "town.fr.start");
    private static final V.P[] TOWN_FR_END = V.pairs("common", "town.fr.end");
    private static final V.P[] TOWN_RU_PREFIX = V.pairs("common", "town.ru.prefix");
    private static final V.P[] TOWN_RU_ROOT = V.pairs("common", "town.ru.root");
    private static final String[] PRIVY = V.list("common", "privy");

    /** Выдуманный город: {им., род., en}; {@code null} — не вышло. */
    static String[] town(Rng r, Nation n) {
        V.P a, b;
        if (n == Nation.FR || n == Nation.IT || n == Nation.ES) {
            a = V.pick(r, TOWN_FR_START);
            b = V.pick(r, TOWN_FR_END);
        } else {
            a = V.pick(r, TOWN_DE_START);
            b = V.pick(r, TOWN_DE_END);
        }
        String s = a.ru() + b.ru();
        String en = a.en() + b.en();
        if (s.length() < 5 || RealNames.resemblesBrand(s) || ugly(s)) return null;
        if (s.toLowerCase(Locale.ROOT).matches(".*[^аеёиоуыэюяь]{4}.*")) return null;
        return new String[]{s, Ru.genSurname(s, false), en};
    }

    private static final Set<MakerKind> COINED = Set.of(MakerKind.WATCHMAKER, MakerKind.PORCELAIN, MakerKind.SILVERSMITH,
            MakerKind.GLASS, MakerKind.TOYMAKER, MakerKind.CARPET);

    private static final Set<String> REAL_MINT_MARKS = Set.of("СПБ", "ЕМ", "СМ", "КМ", "ММ", "АМ", "ВМ", "ЛМД", "ММД",
            "СПМ", "МД", "БМ", "ТМ", "СПБМ", "ЛМ", "СП", "НМ", "ИМ", "СА");

    // ---------------------------------------------------------------- построение

    private static final double[] BIRTH_BUCKET_W = {0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.2, 2.2, 2.0, 1.6, 1.2, 0.6};
    static final int MAX_PER_SURNAME = 8;

    /** Веса языковых групп по виду мастера (порядок — {@link Nation#values()}). */
    private static double[] nationWeights(MakerKind k) {
        //                                     RU   FR   IT   DE   NL   GB   SC   ES   PL   CZ
        return switch (k) {
            case ARTIST, PHOTOGRAPHER -> new double[]{48, 14, 7, 9, 4, 7, 4, 3, 2, 2};
            case SCULPTOR -> new double[]{45, 20, 10, 10, 2, 5, 3, 2, 2, 1};
            case MEDALIST -> new double[]{50, 15, 5, 15, 2, 8, 4, 0, 1, 0};
            case JEWELER -> new double[]{50, 15, 7, 10, 2, 10, 4, 1, 1, 0};
            case WATCHMAKER -> new double[]{15, 25, 3, 35, 0, 15, 5, 0, 1, 1};
            case MINT, ISSUER, POSTAL -> new double[]{40, 10, 8, 25, 5, 7, 5, 0, 0, 0};
            case AUTHOR -> new double[]{70, 8, 3, 7, 2, 6, 2, 1, 1, 0};
            case PUBLISHER, PRINTER, CARTOGRAPHER -> new double[]{60, 10, 3, 12, 3, 8, 2, 1, 1, 0};
            case PORCELAIN -> new double[]{45, 10, 3, 25, 0, 8, 5, 0, 0, 4};
            case SILVERSMITH -> new double[]{50, 10, 3, 10, 5, 15, 5, 0, 2, 0};
            case FURNITURE -> new double[]{50, 15, 4, 12, 4, 10, 3, 1, 1, 0};
            case GLASS -> new double[]{45, 12, 7, 10, 0, 3, 8, 0, 0, 15};
            case CARPET -> new double[]{40, 25, 3, 10, 8, 8, 3, 3, 0, 0};
            case FOUNDRY -> new double[]{50, 25, 8, 12, 0, 5, 0, 0, 0, 0};
            case TOYMAKER -> new double[]{30, 12, 3, 35, 0, 10, 3, 0, 0, 3};
        };
    }

    private static int[] foundRange(MakerKind k) {
        return switch (k) {
            case JEWELER -> new int[]{1780, 1990};
            case WATCHMAKER -> new int[]{1760, 1980};
            case MINT, ISSUER -> new int[]{1700, 1880};
            case POSTAL -> new int[]{1840, 1930};
            case PUBLISHER -> new int[]{1760, 1990};
            case PRINTER -> new int[]{1740, 1960};
            case CARTOGRAPHER -> new int[]{1720, 1940};
            case PORCELAIN -> new int[]{1745, 1960};
            case SILVERSMITH -> new int[]{1750, 1960};
            case FURNITURE -> new int[]{1740, 1970};
            case GLASS -> new int[]{1760, 1960};
            case CARPET -> new int[]{1740, 1960};
            case FOUNDRY -> new int[]{1790, 1940};
            case TOYMAKER -> new int[]{1840, 1980};
            default -> new int[]{1700, 1990};
        };
    }

    /** Мастер до уровня и справки. */
    private record Draft(MakerKind kind, String name, String nameEn, String nameGen, String namePrep, Person person,
                         int from, Integer to, Country country, City city, String place, String school, String specialty,
                         String mark) {
    }

    private static final class Builder {
        final RandomStreams.Stream st;
        final Set<String> takenPeople = new HashSet<>();
        final Set<String> takenFirms = new HashSet<>();
        final Map<String, Integer> surnameUse = new HashMap<>();
        final Map<MakerKind, double[]> weights = new EnumMap<>(MakerKind.class);

        Builder(RandomStreams.Stream st) {
            this.st = st;
        }

        Makers build() {
            List<Maker> makers = new ArrayList<>();
            for (MakerKind k : MakerKind.values()) {
                double[] w = new double[k.count];
                List<Draft> ds = new ArrayList<>();
                List<Rng> rs = new ArrayList<>();
                for (int i = 0; i < k.count; i++) {
                    Rng r = st.at(k.ordinal(), i);
                    Draft d = null;
                    for (int attempt = 0; attempt < 400 && d == null; attempt++) {
                        d = make(k, r);
                        // мастерские и торговые дома — не созвучны известным аукционам, антикварам и галереям
                        if (d != null && d.namePrep() != null && (RealNames.resemblesTrade(d.name()) || RealNames.resemblesTrade(d.nameEn()))) d = null;
                    }
                    if (d == null) throw new IllegalStateException("не придумать мастера " + k + " #" + i);
                    ds.add(d);
                    rs.add(r);
                    w[i] = r.logNormal(1.0, 1.1);
                }
                // уровень рынка — по месту веса в виде: A — верхние 3 %, B — 12 %, C — 35 %, D — прочие
                Integer[] order = new Integer[k.count];
                for (int i = 0; i < order.length; i++) order[i] = i;
                Arrays.sort(order, (a, b) -> Double.compare(w[b], w[a]) != 0 ? Double.compare(w[b], w[a]) : Integer.compare(a, b));
                String[] tier = new String[k.count];
                for (int pos = 0; pos < order.length; pos++) {
                    double q = (pos + 0.5) / order.length;
                    tier[order[pos]] = q < 0.03 ? "A" : q < 0.15 ? "B" : q < 0.5 ? "C" : "D";
                }
                for (int i = 0; i < k.count; i++) {
                    Draft d = ds.get(i);
                    int id = makers.size() + 1;
                    Maker tmp = new Maker(id, k, d.name, d.nameEn, d.nameGen, d.namePrep, d.person, d.from, d.to, d.country,
                            d.city, d.place, d.school, d.specialty, d.mark, tier[i], null);
                    String bio = tier[i].equals("A") || tier[i].equals("B") ? Bio.of(tmp, rs.get(i)) : null;
                    makers.add(new Maker(id, k, d.name, d.nameEn, d.nameGen, d.namePrep, d.person, d.from, d.to, d.country,
                            d.city, d.place, d.school, d.specialty, d.mark, tier[i], bio));
                }
                weights.put(k, w);
            }
            return new Makers(makers, weights);
        }

        Nation nation(MakerKind k, Rng r) {
            return Nation.values()[r.weighted(nationWeights(k))];
        }

        int birthYear(Rng r, int lo, int hi) {
            for (int a = 0; a < 100; a++) {
                int b = r.weighted(BIRTH_BUCKET_W);
                int y = 1690 + b * 20 + r.nextInt(20);
                if (y >= lo && y <= hi) return y;
            }
            return lo + r.nextInt(hi - lo + 1);
        }

        Integer deathYear(Rng r, int born) {
            int age = (int) StrictMath.round(r.gaussianClamped(71, 12, 28, 98));
            int d = born + age;
            return d >= NOW_YEAR ? null : d;
        }

        double femaleShare(int born) {
            return born < 1850 ? 0.04 : born < 1900 ? 0.12 : born < 1950 ? 0.25 : 0.4;
        }

        Nation.Surname surname(Rng r, Nation n) {
            for (int a = 0; a < 50; a++) {
                Nation.Surname s = n.surname(r);
                if (RealNames.blocked(s.ru())) continue;
                if (surnameUse.getOrDefault(n + ":" + s.ru(), 0) >= MAX_PER_SURNAME) continue;
                return s;
            }
            return null;
        }

        void use(Nation n, Nation.Surname s) {
            surnameUse.merge(n + ":" + s.ru(), 1, Integer::sum);
        }

        Draft make(MakerKind k, Rng r) {
            return switch (k) {
                case ARTIST, PHOTOGRAPHER, SCULPTOR, MEDALIST, AUTHOR -> personMaker(k, r);
                case JEWELER -> r.chance(0.4) ? personMaker(k, r) : firm(k, r);
                case MINT -> mint(r);
                case ISSUER, POSTAL -> issuer(k, r);
                default -> firm(k, r);
            };
        }

        Draft personMaker(MakerKind k, Rng r) {
            Nation n = nation(k, r);
            int born = switch (k) {
                case MEDALIST -> birthYear(r, 1690, 1960);
                case PHOTOGRAPHER -> birthYear(r, 1815, 1995);
                case AUTHOR -> birthYear(r, 1690, 1985);
                default -> birthYear(r, 1690, 1998);
            };
            Integer died = deathYear(r, born);
            Nation.Surname sm = surname(r, n);
            if (sm == null) return null;
            City city = n.city(r);
            Person p = n.person(r, r.chance(femaleShare(born)), born, sm, city.country());
            if (RealNames.blocked(p.last()) || !takenPeople.add(p.full())) return null;
            use(n, sm);
            String school = null, spec;
            String mark;
            switch (k) {
                case ARTIST -> {
                    spec = artistSpecialty(r, born);
                    boolean abs = specialty(spec).genre().equals("abstract");
                    school = movement(r, born, city.country(), abs ? "abs" : "fig");
                    if (school == null && abs) {
                        // абстрактного направления в эти годы нет — художник другой специализации
                        do {
                            spec = artistSpecialty(r, born);
                        } while (specialty(spec).genre().equals("abstract"));
                        school = movement(r, born, city.country(), "fig");
                    }
                    if (school == null) school = born + 30 < 1760 ? "барокко" : "реализм";
                    mark = p.signature() != null ? p.signature() : p.monogram();
                }
                case PHOTOGRAPHER -> {
                    spec = W.pick(r, V.list("common", "photographer.specialties"));
                    mark = p.monogram();
                }
                case SCULPTOR -> {
                    spec = W.pick(r, V.list("common", "sculptor.specialties"));
                    school = movement(r, born, city.country(), "fig");
                    if (school == null) school = "реализм";
                    mark = p.signature() != null ? p.signature() : p.monogram();
                }
                case MEDALIST -> {
                    spec = "медальер";
                    mark = p.monogram();
                }
                case AUTHOR -> {
                    spec = W.pick(r, V.list("common", "author.specialties"));
                    mark = p.monogram();
                }
                default -> {
                    spec = W.pick(r, V.list("common", "jeweler.specialties"));
                    mark = p.monogram();
                }
            }
            return new Draft(k, p.full(), p.fullEn(), p.firstLastGen(), null, p, born, died, city.country(), city, city.name(),
                    school, spec, mark);
        }

        String artistSpecialty(Rng r, int born) {
            double[] w = new double[ARTIST_SPECIALTIES.length];
            for (int i = 0; i < w.length; i++) w[i] = born >= ARTIST_SPECIALTIES[i].bornFrom ? ARTIST_SPECIALTIES[i].weight : 0;
            return ARTIST_SPECIALTIES[r.weighted(w)].nameRu;
        }

        /** Направление по годам (с 30 лет), стране и роду специализации: {@code abs} — abs/any, {@code fig} — fig/any. */
        String movement(Rng r, int born, Country c, String style) {
            int start = born + 30;
            List<Movement> fit = new ArrayList<>();
            for (Movement m : MOVEMENTS) {
                if (start < m.from || start > m.to) continue;
                if (m.only != null && m.only != c) continue;
                if (!m.style.equals("any") && !m.style.equals(style)) continue;
                fit.add(m);
            }
            if (fit.isEmpty()) return null;
            return fit.get(r.nextInt(fit.size())).nom;
        }

        Draft firm(MakerKind k, Rng r) {
            Nation n = nation(k, r);
            int[] fr = foundRange(k);
            int founded = fr[0] + r.nextInt(fr[1] - fr[0] + 1);
            Integer closed = null;
            if (r.chance(0.75)) {
                int c = founded + (int) StrictMath.round(r.logNormal(55, 0.6));
                if (c < NOW_YEAR) closed = Math.max(c, founded + 5);
            }
            boolean ru = n == Nation.RU;
            if (ru) {
                // у русских мастерских два окна лет: XVIII — начало XX века и с 1991 года
                if (r.chance(0.8)) {
                    int top = Math.max(fr[0], 1910);
                    founded = fr[0] + r.nextInt(top - fr[0] + 1);
                    int c = founded + (int) StrictMath.round(r.logNormal(45, 0.6));
                    closed = Math.max(founded + 5, Math.min(c, 1918 + r.nextInt(10)));
                } else {
                    founded = 1991 + r.nextInt(25);
                    closed = r.chance(0.3) ? Math.min(founded + r.between(3, 20), NOW_YEAR - 1) : null;
                }
            }
            City city = n.city(r);
            if (city.country() == Country.US && founded < 1830) city = n.cities[0];
            Person owner = null;
            String nom, gen, prep, en, mark;
            double u = r.nextDouble();
            List<Head> quoted = heads(k, "quoted"), owners = heads(k, "owner"), brothers = heads(k, "brothers"),
                    towns = heads(k, "town");
            if (!towns.isEmpty() && !ru && n != Nation.GB && u < 0.25) {
                String[] t = town(r, n);
                if (t == null) return null;
                Head h = towns.get(r.nextInt(towns.size()));
                nom = h.nom + " " + t[1];
                gen = h.gen + " " + t[1];
                prep = h.prep + " " + t[1];
                en = h.en(t[2]);
                mark = t[0];
            } else if (COINED.contains(k) && u < (ru ? 0.12 : 0.45)) {
                String[] c = coined(r);
                if (c == null) return null;
                Head h = quoted.get(r.nextInt(quoted.size()));
                nom = h.nom + " «" + c[0] + "»";
                gen = h.gen + " «" + c[0] + "»";
                prep = h.prep + " «" + c[0] + "»";
                en = h.en(c[1]);
                mark = c[0];
            } else {
                Nation.Surname sm = surname(r, n);
                if (sm == null) return null;
                owner = n.person(r, false, founded - 35, sm, city.country());
                if (RealNames.blocked(owner.last())) return null;
                double v = r.nextDouble();
                if (ru && !brothers.isEmpty() && v < 0.2) {
                    Head h = brothers.get(r.nextInt(brothers.size()));
                    String pl = Ru.pluralGenSurname(sm.ru());
                    nom = h.nom + " " + pl;
                    gen = h.gen + " " + pl;
                    prep = h.prep + " " + pl;
                    en = h.en(sm.en());
                    mark = "Бр. " + Ru.pluralSurname(sm.ru());
                } else if (ru && v < 0.8) {
                    Head h = owners.get(r.nextInt(owners.size()));
                    String o = owner.initialsGen();
                    nom = h.nom + " " + o;
                    gen = h.gen + " " + o;
                    prep = h.prep + " " + o;
                    en = h.en(owner.initialsEn());
                    mark = owner.monogram();
                } else {
                    Head h = quoted.get(r.nextInt(quoted.size()));
                    String[] q = quotedName(r, n, sm);
                    if (q == null) return null;
                    nom = h.nom + " «" + q[0] + "»";
                    gen = h.gen + " «" + q[0] + "»";
                    prep = h.prep + " «" + q[0] + "»";
                    en = h.en(q[1]);
                    mark = owner.monogram();
                }
                use(n, sm);
            }
            if (!takenFirms.add(nom)) return null;
            String spec = W.pick(r, V.list("common", "firm." + k.name()));
            return new Draft(k, nom, en, gen, prep, owner, founded, closed, city.country(), city, city.name(), null, spec, mark);
        }

        /** {«Бастид и сыновья», «Bastide &amp; Sons»} и подобные. */
        String[] quotedName(Rng r, Nation n, Nation.Surname sm) {
            double v = r.nextDouble();
            if (v < 0.3) return new String[]{sm.ru() + " и сыновья", sm.en() + " & Sons"};
            if (v < 0.45) return new String[]{sm.ru() + " и сын", sm.en() + " & Son"};
            if (v < 0.6 && n == Nation.RU) return new String[]{sm.ru() + " и К°", sm.en() + " & Co."};
            if (v < 0.8) {
                Nation.Surname s2 = surname(r, n);
                if (s2 == null || s2.ru().equals(sm.ru())) return null;
                return new String[]{sm.ru() + " и " + s2.ru(), sm.en() + " & " + s2.en()};
            }
            return new String[]{sm.ru(), sm.en()};
        }

        /** Русский выдуманный город: {прил. м., прил. м. род., прил. м. пред., прил. ж., en, город}. */
        String[] ruTown(Rng r) {
            V.P pre = V.pick(r, TOWN_RU_PREFIX), root = V.pick(r, TOWN_RU_ROOT);
            String[] rf = root.ru().split("\\s*:\\s*");
            String adj = pre.ru() + rf[0];
            if (RealNames.resemblesBrand(adj)) return null;
            return new String[]{adj, pre.ru() + rf[1], pre.ru() + rf[2], pre.ru() + rf[3], pre.en() + root.en(),
                    pre.ru() + rf[0].replace("ский", "ск"), pre.ru(), rf[0]};
        }

        Draft mint(Rng r) {
            Nation n = nation(MakerKind.MINT, r);
            int[] fr = foundRange(MakerKind.MINT);
            int founded = fr[0] + r.nextInt(fr[1] - fr[0] + 1);
            Integer closed = null;
            if (r.chance(0.7)) {
                int c = founded + (int) StrictMath.round(r.logNormal(120, 0.5));
                if (c < NOW_YEAR) closed = Math.max(c, founded + 20);
            }
            String nom, gen, prep, en, mark, place;
            Country country;
            if (n == Nation.RU) {
                String[] t = ruTown(r);
                if (t == null) return null;
                nom = t[0] + " монетный двор";
                gen = t[1] + " монетного двора";
                prep = "на " + t[2] + " монетном дворе";
                en = t[4] + " Mint";
                String mm = (t[6].substring(0, 1) + t[7].substring(0, 1)).toUpperCase(Locale.ROOT);
                if (REAL_MINT_MARKS.contains(mm)) mm = mm + "Д";
                if (REAL_MINT_MARKS.contains(mm)) return null;
                mark = mm;
                country = Country.RU;
                place = t[5];
            } else {
                String[] t = town(r, n);
                if (t == null) return null;
                Head h = heads(MakerKind.MINT, "town").get(0);
                nom = h.nom + " " + t[1];
                gen = h.gen + " " + t[1];
                prep = h.prep + " " + t[1];
                en = h.en(t[2]);
                mark = W.pick(r, PRIVY);
                country = n.cities[0].country();
                place = t[0];
            }
            if (!takenFirms.add(nom)) return null;
            return new Draft(MakerKind.MINT, nom, en, gen, prep, null, founded, closed, country, null, place, null,
                    W.pick(r, V.list("common", "firm.MINT")), mark);
        }

        /** Эмитент: выдуманные вольный город, земля, кантон, банк или почтовое ведомство. */
        Draft issuer(MakerKind k, Rng r) {
            Nation n = nation(k, r);
            int[] fr = foundRange(k);
            int founded = fr[0] + r.nextInt(fr[1] - fr[0] + 1);
            Integer closed = null;
            if (r.chance(0.6)) {
                int c = founded + (int) StrictMath.round(r.logNormal(90, 0.4));
                if (c < NOW_YEAR) closed = c;
            }
            if (n == Nation.RU && k == MakerKind.POSTAL) {
                // земская почта: с 1865 года и не позже 1918-го
                founded = 1865 + r.nextInt(41);
                closed = Math.max(founded + 5, 1890) + r.nextInt(Math.max(1, 1918 - Math.max(founded + 5, 1890) + 1));
                if (closed > 1918) closed = 1918;
            }
            String nom, gen, prep, en, place;
            Country country;
            if (n == Nation.RU) {
                String[] t = ruTown(r);
                if (t == null) return null;
                String stem = t[3].substring(0, t[3].length() - 2);
                if (k == MakerKind.POSTAL) {
                    nom = t[3] + " земская почта";
                    gen = stem + "ой земской почты";
                    prep = "на " + stem + "ой земской почте";
                    en = t[4] + " Zemstvo Post";
                } else {
                    nom = t[3] + " земля";
                    gen = stem + "ой земли";
                    prep = "в " + stem + "ой земле";
                    en = "Land of " + t[4];
                }
                country = Country.RU;
                place = t[5];
            } else {
                String[] t = town(r, n);
                if (t == null) return null;
                List<Head> hs = new ArrayList<>(heads(k, "bank"));
                if (k == MakerKind.ISSUER) hs.addAll(heads(k, "town"));
                Head h = hs.get(r.nextInt(hs.size()));
                boolean townHead = h.nom.equals("Вольный город") || h.nom.equals("Земля") || h.nom.equals("Кантон");
                String name = townHead ? t[0] : t[1];
                nom = h.nom + " " + name;
                gen = h.gen + " " + name;
                prep = h.prep + " " + name;
                en = h.en(t[2]);
                country = n.cities[0].country();
                place = t[0];
            }
            if (!takenFirms.add(nom)) return null;
            return new Draft(k, nom, en, gen, prep, null, founded, closed, country, null, place, null,
                    W.pick(r, V.list("common", "firm." + k.name())), W.pick(r, PRIVY));
        }
    }
}
