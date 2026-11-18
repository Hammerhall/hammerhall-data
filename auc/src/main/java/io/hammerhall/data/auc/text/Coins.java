package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Монеты, медали и боны. Монета — номинал денежной системы и год («Рубль 1842 года», «5 франков 1868 года»), чекан
 * выдуманного монетного двора для выдуманного эмитента (земли, вольного города, кантона); изображения — нейтральные
 * (номинал в венке, аллегории, щит, парусник), без правителей. Медали — памятные, настольные, наградные выставок,
 * жетоны обществ и состязаний; без военных наград. Боны — кредитные билеты и банкноты выдуманных банков и
 * казначейств. Словарь — {@code vocab/coins.txt}.
 */
final class Coins {
    private Coins() {
    }

    private static final String F = "coins";

    /** Номинал: название (с числом), металл, класс размера, годы системы, по-английски. */
    private record Denom(String name, String metal, char size, int from, int to, String en) {
    }

    private static Denom[] denoms(String sec) {
        List<Denom> out = new ArrayList<>();
        for (V.P p : V.pairs(F, sec)) {
            String[] f = p.ru().split("\\s*/\\s*");
            String[] y = f[f.length - 1].split("-");
            if (f.length == 4) {
                out.add(new Denom(f[0], f[1], f[2].charAt(0), Integer.parseInt(y[0]), Integer.parseInt(y[1]), p.en()));
            } else {
                out.add(new Denom(f[0], "paper", 'N', Integer.parseInt(y[0]), Integer.parseInt(y[1]), p.en()));
            }
        }
        return out.toArray(new Denom[0]);
    }

    private static String sys(Nation n) {
        return switch (n) {
            case RU -> "RU";
            case DE, CZ, PL -> "DE";
            case FR, ES -> "FR";
            case IT -> "IT";
            case NL -> "NL";
            case GB -> "GB";
            case SC -> "SC";
        };
    }

    private static final java.util.Map<String, Denom[]> COINS = new java.util.HashMap<>(), NOTES = new java.util.HashMap<>();

    static {
        for (String s : new String[]{"RU", "DE", "FR", "IT", "NL", "GB", "SC"}) {
            COINS.put(s, denoms("denoms." + s));
            NOTES.put(s, denoms("notes." + s));
        }
    }

    private static final String[] OBVERSE = V.list(F, "obverse"), REVERSE = V.list(F, "reverse"), EDGE = V.list(F, "edge"),
            VARIETY = V.list(F, "variety"), COIN_NOTE = V.list(F, "coin.note"), HOLDER = V.list(F, "coin.holder"),
            MEDAL_SIDE = V.list(F, "medal.side"), MEDAL_BACK = V.list(F, "medal.back"), MEDAL_NOTE = V.list(F, "medal.note"),
            MEDAL_EXTRA = V.list(F, "medal.extra"), NOTE_SIDE = V.list(F, "note.side"), NOTE_BACK = V.list(F, "note.back"),
            NOTE_PAPER = V.list(F, "note.text"), NOTE_NOTE = V.list(F, "note.note");
    private static final V.P[] OPENING = V.pairs(F, "medal.opening"), SUCCESS = V.pairs(F, "medal.success"),
            EXHIB = V.pairs(F, "medal.exhib"), JUBILEE = V.pairs(F, "medal.jubilee"), TABLE = V.pairs(F, "medal.table"),
            TOKEN = V.pairs(F, "medal.token"), SPORT = V.pairs(F, "medal.sport");

    private record Metal(String text, String material, Integer fineness) {
    }

    private static Metal metal(Rng r, Denom d, int y) {
        return switch (d.metal) {
            case "gold" -> {
                int[] opt = {900, 917, 986, 900};
                int f = opt[r.nextInt(opt.length)];
                yield new Metal("Золото " + f + "-й пробы", "золото", f);
            }
            case "silver" -> {
                int[] opt = y < 1800 ? new int[]{750, 802, 833} : new int[]{868, 900, 835, 500, 925};
                int f = opt[r.nextInt(opt.length)];
                yield new Metal("Серебро " + f + "-й пробы", "серебро", f);
            }
            case "billon" -> new Metal("Биллон", "биллон", null);
            default -> {
                String m = y < 1860 ? "Медь" : y < 1920 ? W.pick(r, new String[]{"Медь", "Бронза"})
                        : W.pick(r, new String[]{"Бронза", "Медно-никелевый сплав", "Алюминиевая бронза"});
                yield new Metal(m, m.toLowerCase(Locale.ROOT), null);
            }
        };
    }

    /** Вес и диаметр по классу размера. */
    private static void weightDiam(Ctx c, char size) {
        Rng r = c.r;
        double[] g;
        int[] mm;
        switch (size) {
            case 'G' -> {
                g = new double[]{3, 8.7};
                mm = new int[]{17, 24};
            }
            case 'L' -> {
                g = new double[]{22, 30};
                mm = new int[]{36, 42};
            }
            case 'M' -> {
                g = new double[]{9, 15};
                mm = new int[]{27, 33};
            }
            case 'S' -> {
                g = new double[]{1.5, 6};
                mm = new int[]{15, 24};
            }
            case 'C' -> {
                g = new double[]{9, 40};
                mm = new int[]{28, 42};
            }
            default -> {
                g = new double[]{1, 8};
                mm = new int[]{14, 26};
            }
        }
        c.weightG = StrictMath.round(r.uniform(g[0], g[1]) * 100) / 100.0;
        c.diameterMm = r.between(mm[0], mm[1]) + (r.chance(0.3) ? 0.5 : 0);
    }

    /** «37 мм», «37,5 мм». */
    static String mm(double v) {
        return v == StrictMath.floor(v) ? Long.toString((long) v) : W.dec(v, 1);
    }

    /** «1 250 000». */
    static String thousands(long n) {
        String s = Long.toString(n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) sb.append(' ');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    /** Буквы двора («ВР») — у русских дворов; у прочих знак-символ — {@code null}. */
    private static String mintCode(Maker mint) {
        String mk = mint.mark();
        return mk.matches("[А-ЯЁ]{2,4}") ? mk : null;
    }

    /** «буквы «ВР»» или «знак «колос»». */
    private static String mintMark(Maker mint) {
        return mintCode(mint) != null ? "буквы «" + mint.mark() + "»" : "знак «" + mint.mark() + "»";
    }

    /** Эмитент той же страны, что работал в год {@code y} (или ближайший). */
    static Maker issuer(Ctx c, Country country, int y) {
        List<Maker> all = c.lt.makers().of(MakerKind.ISSUER);
        List<Maker> same = new ArrayList<>();
        for (Maker m : all) if (m.country() == country) same.add(m);
        if (same.isEmpty()) same = all;
        for (int i = 0; i < 12; i++) {
            Maker m = same.get(c.r.nextInt(same.size()));
            if (m.yearFrom() <= y && m.activeTo(Makers.NOW_YEAR) >= y) return m;
        }
        return same.get(c.r.nextInt(same.size()));
    }

    // ---------------------------------------------------------------- монеты

    static void fillCoin(Ctx c) {
        Rng r = c.r;
        Maker mint = c.maker;
        Nation n = mint == null ? Nation.RU : Common.nationOf(mint.country());
        Denom[] all = COINS.get(sys(n));
        int[] sp = mint == null ? new int[]{1700, 1990} : new int[]{mint.yearFrom(), mint.activeTo(1991)};
        c.country = mint == null ? Country.RU : mint.country();
        c.provenanceStyle = "coin";
        c.attribution = mint == null ? Attribution.UNKNOWN : Attribution.BY;
        if ("set".equals(c.variant)) {
            fillSet(c, all, sp);
            return;
        }
        String want = c.variant == null ? null : c.variant;
        Denom d = null;
        int y = 0;
        for (int a = 0; a < 40 && d == null; a++) {
            Denom t = all[r.nextInt(all.length)];
            if (want != null && !(t.metal.equals(want) || (want.equals("silver") && t.metal.equals("billon")))) continue;
            int lo = Math.max(t.from, sp[0]), hi = Math.min(t.to, sp[1]);
            if (lo <= hi) {
                d = t;
                y = lo + r.nextInt(hi - lo + 1);
            }
        }
        if (d == null) {
            for (Denom t : all) if (want == null || t.metal.equals(want)) d = t;
            if (d == null) d = all[0];
            y = Math.max(d.from, Math.min(d.to, sp[0]));
        }
        c.dated(Common.exact(y));
        Maker iss = issuer(c, c.country, y);
        String code = mint == null ? null : mintCode(mint);
        String head = d.name + " " + y + " года" + (code != null && r.chance(0.4) ? ", " + code : "");
        String headEn = d.en + " " + y;
        c.titleRu = mint != null ? Titles.fit(head + ". " + W.cap(mint.name()), head + ". " + W.cap(iss.name()), head)
                : Titles.fit(head + ". " + W.cap(iss.name()), head);
        c.titleEn = mint != null ? Titles.fit(headEn + ", " + mint.nameEn(), headEn + ", " + iss.nameEn(), headEn)
                : Titles.fit(headEn + ", " + iss.nameEn(), headEn);
        Metal mt = metal(r, d, y);
        c.materialRu = mt.material;
        c.fineness = mt.fineness;
        c.techniqueRu = "чекан";
        c.originPlaceRu = mint != null ? mint.placeRu() : null;
        weightDiam(c, d.size);
        c.add(mt.text + ", " + W.dec(c.weightG, 2) + " г, диаметр " + mm(c.diameterMm) + " мм");
        c.add("Аверс: " + W.pick(r, OBVERSE) + ". Реверс: " + W.pick(r, REVERSE));
        if (r.chance(0.6)) c.add(W.pick(r, EDGE));
        c.add(r.chance(0.5) ? "Монета " + iss.nameGen() + ", тип " + W.decadeGen(y)
                : "Эмитент — " + iss.name() + " (" + iss.years() + ")");
        if (mint != null) {
            c.signed = true;
            c.add(r.chance(0.5) ? "Чекан " + mint.nameGen() + ", " + mintMark(mint) + (code != null ? " под датой" : " у даты")
                    : "Отчеканено " + mint.namePrep() + "; " + mintMark(mint));
            if (r.chance(0.4)) c.add(W.cap(mint.name()) + " работал " + (mint.yearTo() == null ? "с " + mint.yearFrom() + " года"
                    : "в " + mint.yearFrom() + "–" + mint.yearTo() + " годах") + (mint.placeRu() != null ? ", " + mint.placeRu() : ""));
        } else {
            c.signed = false;
            c.add("Знак монетного двора не читается");
        }
        double u = r.nextDouble();
        if (u < 0.35) c.add("Тираж " + thousands(roundMintage(r, d)) + " экз.");
        else if (u < 0.5) c.add("Разновидность: " + W.pick(r, VARIETY));
        else if (u < 0.58) c.add("Редкий год: тираж не установлен, известны единичные экземпляры");
        else if (u < 0.64 && y >= 1840) c.add("Полированный чекан (пруф)");
        c.add(W.pick(r, COIN_NOTE));
        if (r.chance(0.3)) c.add(W.pick(r, HOLDER));
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.55, 0.12, 0.01, 0.06);
    }

    private static long roundMintage(Rng r, Denom d) {
        double base = d.metal.equals("gold") ? r.logNormal(60_000, 1.2) : r.logNormal(900_000, 1.3);
        long v = (long) base;
        long step = v > 1_000_000 ? 1000 : v > 10_000 ? 100 : 10;
        return Math.max(step, v / step * step);
    }

    private static void fillSet(Ctx c, Denom[] sys, int[] sp) {
        Rng r = c.r;
        int n = r.between(3, 25);
        int lo = Math.max(sp[0], 1700), hi = Math.max(lo, Math.min(sp[1], 1990));
        int a = lo + r.nextInt(hi - lo + 1), b = Math.min(hi, a + r.between(5, 60));
        c.dated = new Common.Dated(a, a, b, a + "–" + b, false, a + "–" + b, a + "–" + b);
        c.year = a;
        c.yearFrom = a;
        c.yearTo = b;
        c.pieces = n;
        Maker iss = issuer(c, c.country, a);
        String lot = r.chance(0.5) ? "Набор монет (" + n + " шт.)" : "Лот из " + W.count(n, "монеты", "монет", "монет");
        c.titleRu = Titles.fit(lot + ", " + a + "–" + b + ". " + W.cap(iss.name()), lot + ", " + a + "–" + b);
        c.titleEn = Titles.fit("Lot of " + n + " Coins, " + a + "–" + b + ", " + iss.nameEn(), "Lot of " + n + " Coins, " + a + "–" + b);
        List<String> items = new ArrayList<>();
        java.util.Set<String> metals = new java.util.TreeSet<>();
        for (int i = 0; i < 20 && items.size() < Math.min(n, 5); i++) {
            Denom d = sys[r.nextInt(sys.length)];
            int lo2 = Math.max(a, d.from), hi2 = Math.min(b, d.to);
            if (lo2 > hi2) continue;
            String it = d.name.toLowerCase(Locale.ROOT) + " " + (lo2 + r.nextInt(hi2 - lo2 + 1));
            if (!items.contains(it)) {
                items.add(it);
                metals.add(d.metal);
            }
        }
        if (items.isEmpty()) items.add("монеты мелких номиналов");
        c.add("В лоте " + W.count(n, "монета", "монеты", "монет") + " " + iss.nameGen() + ", в том числе: " + String.join(", ", items)
                + (n > items.size() ? " и другие" : ""));
        String mat = metals.size() > 2 ? "разные металлы" : metals.equals(java.util.Set.of("gold")) ? "золото"
                : metals.contains("gold") ? "золото и " + (metals.contains("silver") ? "серебро" : "медь")
                : metals.equals(java.util.Set.of("copper")) ? "медь и бронза" : metals.equals(java.util.Set.of("silver")) ? "серебро"
                : "серебро и медь";
        c.materialRu = mat;
        c.techniqueRu = "чекан";
        c.add("Металл: " + mat);
        if (c.maker != null) c.add("Чекан " + c.maker.nameGen() + " и других дворов");
        c.signed = c.maker != null;
        c.add(W.pick(r, new String[]{"Сохранность разная, от F до XF", "Монеты из обращения", "Подобраны по годам",
                "В альбоме коллекционера"}));
        c.add(W.pick(r, new String[]{"Лот собран прежним владельцем за много лет", "Хорошая основа для собрания монет эмитента",
                "Монеты разложены по ячейкам и подписаны", "Встречаются редкие годы"}));
        c.add(W.pick(r, COIN_NOTE));
        c.add("Опись лота прилагается");
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.6, 0.0, 0.0, 0.0);
    }

    // ---------------------------------------------------------------- медали

    private static final int[] JUBILEE_YEARS = {10, 25, 25, 50, 50, 75, 100, 100, 150, 200};

    private static V.P q(String ru, String en, V.P x) {
        return new V.P(ru + "«" + x.ru() + "»", en + "“" + x.en() + "”");
    }

    static void fillMedal(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        String v = c.variant == null ? "commemorative" : c.variant;
        int lo = v.equals("exhibition") ? 1830 : v.equals("token") ? 1850 : 1760;
        int[] sp = Common.span(m, lo, 1990);
        if (sp[0] < lo) sp[0] = Math.min(lo, sp[1]);
        int y = sp[0] + r.nextInt(Math.max(1, sp[1] - sp[0] + 1));
        c.dated(Common.exact(y));
        c.country = m != null ? m.country() : Country.RU;
        c.provenanceStyle = "coin";
        c.attribution = m == null ? Attribution.UNKNOWN : Attribution.BY;
        V.P t;
        switch (v) {
            case "table" -> t = r.chance(0.6) ? q("Настольная медаль ", "Table Medal ", V.pick(r, TABLE)) : q("Плакета ", "Plaquette ", V.pick(r, TABLE));
            case "exhibition" -> {
                if (r.chance(0.35)) {
                    V.P s = V.pick(r, SUCCESS);
                    t = new V.P("Медаль «За успехи в " + s.ru() + "»", "Medal for Achievement in " + s.en());
                } else {
                    V.P e = V.pick(r, EXHIB);
                    t = new V.P("Наградная медаль " + e.ru(), "Award Medal of the " + e.en());
                }
            }
            case "token" -> {
                if (y >= 1880 && r.chance(0.3)) {
                    V.P s = V.pick(r, SPORT);
                    t = new V.P("Медаль чемпионата по " + s.ru(), s.en() + " Championship Medal");
                } else {
                    V.P s = V.pick(r, TOKEN);
                    t = new V.P("Жетон " + s.ru(), "Token of the " + s.en());
                }
            }
            default -> {
                if (r.chance(0.6)) {
                    V.P o = V.pick(r, OPENING);
                    t = new V.P("Медаль «В память открытия " + o.ru() + "»", "Medal Commemorating the Opening of " + o.en());
                } else {
                    V.P j = V.pick(r, JUBILEE);
                    int ny = JUBILEE_YEARS[r.nextInt(JUBILEE_YEARS.length)];
                    t = new V.P("Медаль «" + ny + " лет " + j.ru() + "»", "Medal for " + ny + " Years of " + j.en());
                }
            }
        }
        c.objectRu = t.ru();
        c.objectEn = t.en();
        boolean plaque = t.ru().startsWith("Плакета");
        String[][] metals = {{"Бронза", "бронза"}, {"Бронза, золочение", "бронза"}, {"Серебро 900", "серебро"}, {"Белый металл", "белый металл"},
                {"Медь, посеребрение", "медь"}, {"Бронза, патинирование", "бронза"}, {"Томпак", "томпак"}};
        String[] mt = metals[r.nextInt(metals.length)];
        c.materialRu = mt[1];
        if (mt[1].equals("серебро")) c.fineness = 900;
        boolean struck = r.chance(0.8);
        c.techniqueRu = struck ? "чекан" : "литьё";
        String size;
        if (plaque) {
            c.heightMm = r.between(40, 120);
            c.widthMm = r.between(30, 90);
            size = "Размер " + c.heightMm + " × " + c.widthMm + " мм";
        } else {
            c.diameterMm = r.between(25, 75) + (r.chance(0.2) ? 0.5 : 0);
            c.weightG = StrictMath.round(c.diameterMm * c.diameterMm * r.uniform(0.012, 0.03) * 100) / 100.0;
            size = "Диаметр " + mm(c.diameterMm) + " мм, вес " + W.dec(c.weightG, 2) + " г";
        }
        c.add(mt[0] + ", " + c.techniqueRu + ". " + size);
        String side = W.pick(r, MEDAL_SIDE), back = W.pick(r, MEDAL_BACK);
        for (int i = 0; i < 5 && back.equals(side); i++) back = W.pick(r, MEDAL_BACK);
        c.add("Аверс: " + side + ". Реверс: " + back);
        if (m != null) {
            c.signed = true;
            c.add(r.chance(0.5) ? "Медальер — " + m.name() + "; подпись на аверсе у нижнего края"
                    : "Подпись медальера «" + m.mark() + "» под изображением; работа " + m.nameGen());
        } else {
            c.signed = false;
            c.add("Подписи медальера нет");
        }
        c.add(y + " год.");
        List<Maker> mints = c.lt.makers().of(MakerKind.MINT);
        Maker mint = mints.get(r.nextInt(mints.size()));
        for (int i = 0; i < 10 && mint.country() != c.country; i++) mint = mints.get(r.nextInt(mints.size()));
        if (struck && mint.country() == c.country && r.chance(0.5) && mint.yearFrom() <= y && mint.activeTo(Makers.NOW_YEAR) >= y) {
            c.originPlaceRu = mint.placeRu();
            c.add("Отчеканено " + mint.namePrep());
        } else {
            c.originPlaceRu = m != null && m.city() != null ? m.city().name() : c.country.nameRu;
        }
        c.add(W.pick(r, MEDAL_NOTE));
        if (r.chance(0.5)) c.add(W.pick(r, MEDAL_EXTRA));
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.6, 0.1, 0.02, 0.02);
    }

    // ---------------------------------------------------------------- боны

    static void fillBanknote(Ctx c) {
        Rng r = c.r;
        Maker iss = c.maker;
        if (iss == null) iss = c.lt.makers().pick(MakerKind.ISSUER, r);
        c.maker = iss;
        c.attribution = Attribution.BY;
        c.country = iss.country();
        c.provenanceStyle = "coin";
        Denom[] all = NOTES.get(sys(Common.nationOf(iss.country())));
        int a = Math.max(iss.yearFrom(), 1769), b = Math.min(iss.activeTo(1995), 1995);
        Denom d = null;
        int y = 0;
        for (int i = 0; i < 30 && d == null; i++) {
            Denom t = all[r.nextInt(all.length)];
            int lo = Math.max(t.from, a), hi = Math.min(t.to, b);
            if (lo <= hi) {
                d = t;
                y = lo + r.nextInt(hi - lo + 1);
            }
        }
        if (d == null) {
            d = all[0];
            y = Math.max(d.from, Math.min(d.to, a));
        }
        c.dated(Common.exact(y));
        boolean ru = iss.country() == Country.RU;
        String type = ru ? W.pick(r, new String[]{"Кредитный билет", "Казначейский знак", "Разменный знак"}) : "Банкнота";
        String typeEn = switch (type) {
            case "Кредитный билет" -> "Credit Note";
            case "Казначейский знак" -> "Treasury Note";
            case "Разменный знак" -> "Exchange Note";
            default -> "Banknote";
        };
        String head = type + " " + d.name.toLowerCase(Locale.ROOT).replaceFirst("^рубль$", "1 рубль") + " " + y + " года";
        c.titleRu = Titles.fit(head + ". " + W.cap(iss.name()), head);
        c.titleEn = Titles.fit(typeEn + ", " + d.en + " " + y + ", " + iss.nameEn(), typeEn + ", " + d.en + " " + y);
        c.materialRu = "бумага";
        c.techniqueRu = W.pick(r, new String[]{"типографская печать", "металлографская печать", "офсетная печать"});
        c.originPlaceRu = iss.placeRu();
        int w = r.between(100, 210), h = r.between(60, 130);
        c.widthMm = w;
        c.heightMm = h;
        c.signed = true;
        c.add(W.pick(r, NOTE_PAPER) + "; " + c.techniqueRu + " в " + W.pick(r, new String[]{"два", "три", "несколько"}) + " цвета. Размер "
                + w + " × " + h + " мм");
        c.add("Выпуск " + iss.nameGen() + ", " + y + " год");
        c.add("Лицевая сторона: " + W.pick(r, NOTE_SIDE) + ". Оборот: " + W.pick(r, NOTE_BACK));
        String series = "" + "АБВГДЕЖЗИКЛМНОПРСТУФ".charAt(r.nextInt(20)) + "АБВГДЕЖЗИКЛМНОПРСТУФ".charAt(r.nextInt(20));
        c.add("Серия " + (ru ? series : W.latin(series).toUpperCase(Locale.ROOT)) + ", номер " + String.format(Locale.ROOT, "%06d", r.between(1, 999999)));
        c.add(W.pick(r, NOTE_NOTE));
        if (r.chance(0.5)) c.add(W.pick(r, NOTE_NOTE));
        if (r.chance(0.3)) c.add(W.cap(iss.name()) + " выпускал" + (iss.name().startsWith("Земля") || iss.name().endsWith("земля") ? "а"
                : iss.name().startsWith("Казначейство") ? "о" : "") + " боны в " + W.decadeGen(y));
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.75, 0.05, 0.0, 0.02);
    }
}
