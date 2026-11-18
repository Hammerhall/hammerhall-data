package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ювелирные украшения, часы, камни. Название — вид и камни («Кольцо с сапфиром и бриллиантами», «Sapphire and Diamond
 * Ring»; «Часы карманные с репетиром»); описание — металл и проба, камни (огранка, вес в каратах), устройство, клейма,
 * размер и вес, время и мастер, футляр. Проба — метрическая (у русских золотниковых — в скобках). Словарь —
 * {@code vocab/jewellery.txt}.
 */
final class Jewelry {
    private Jewelry() {
    }

    private static final String F = "jewellery";

    /** Камень: именительный, творительный ед. и мн., родительный мн., огранка обычно кабошон; по-английски. */
    private record Stone(String nom, String instr, String instrPl, String genPl, boolean cabochon, double weight, String en) {
        /** Именительный мн.: «сапфиры», «лунные камни»; у бирюзы, янтаря, жемчуга — как ед. */
        String nomPl() {
            if (nom.equals("бирюза") || nom.equals("янтарь") || nom.equals("жемчуг")) return nom;
            if (nom.equals("лунный камень")) return "лунные камни";
            if (nom.endsWith("ь")) return nom.substring(0, nom.length() - 1) + "и";
            return nom + "ы";
        }
    }

    private static final Stone[] STONES;
    private static final double[] STONE_W;

    static {
        List<Stone> s = new ArrayList<>();
        for (V.P p : V.pairs(F, "stones")) {
            String[] f = p.ru().split("\\s*:\\s*");
            s.add(new Stone(f[0], f[1], f[2], f[3], f[4].equals("y"), Double.parseDouble(f[5]), p.en()));
        }
        STONES = s.toArray(new Stone[0]);
        STONE_W = new double[STONES.length];
        for (int i = 0; i < STONES.length; i++) STONE_W[i] = STONES[i].weight;
    }

    private static final V.P[] MOTIF = V.pairs(F, "motifs");
    private static final String[] CUT = V.list(F, "cuts"), DEDICATION = V.list(F, "dedications"),
            NOTE = V.list(F, "jewel.note"), NOTE_STONES = V.list(F, "jewel.note.stones"), DIAL = V.list(F, "watch.dial"), NUMERALS = V.list(F, "watch.numerals"),
            SECONDS = V.list(F, "watch.seconds"), WATCH_CASE = V.list(F, "watch.case"), WATCH_NOTE = V.list(F, "watch.note");

    private static String[] design(String kind) {
        return V.list(F, "design." + kind);
    }

    /** Металл: текст («Золото 56 пробы (583)», «Золото 750 (18 карат)»), материал и метрическая проба. */
    private record Metal(String text, String material, int fineness) {
    }

    private static Metal metal(Rng r, Country c, int y) {
        String[][] opt;
        if (c == Country.RU) {
            if (y < 1927) opt = new String[][]{{"Золото 56 пробы (583)", "золото", "583"}, {"Золото 56 пробы (583)", "золото", "583"},
                    {"Золото 72 пробы (750)", "золото", "750"}, {"Серебро 84 пробы (875)", "серебро", "875"},
                    {"Серебро 84 пробы (875), позолота", "серебро", "875"}};
            else if (y < 1994) opt = new String[][]{{"Золото 583", "золото", "583"}, {"Золото 583", "золото", "583"},
                    {"Золото 750", "золото", "750"}, {"Серебро 875", "серебро", "875"}, {"Серебро 875, позолота", "серебро", "875"}};
            else opt = new String[][]{{"Золото 585", "золото", "585"}, {"Золото 585", "золото", "585"}, {"Золото 750", "золото", "750"},
                    {"Серебро 925", "серебро", "925"}, {"Платина 950", "платина", "950"}};
        } else if (y >= 1905) {
            opt = new String[][]{{"Золото 750 (18 карат)", "золото", "750"}, {"Золото 750 (18 карат)", "золото", "750"},
                    {"Золото 585 (14 карат)", "золото", "585"}, {"Серебро 925", "серебро", "925"}, {"Платина 950", "платина", "950"},
                    {"Золото 750, платина", "золото", "750"}};
        } else {
            opt = new String[][]{{"Золото 750 (18 карат)", "золото", "750"}, {"Золото 585 (14 карат)", "золото", "585"},
                    {"Серебро 925", "серебро", "925"}, {"Серебро 800, позолота", "серебро", "800"}};
        }
        String[] m = opt[r.nextInt(opt.length)];
        if (c == Country.GB && r.chance(0.2)) m = new String[]{"Золото 375 (9 карат)", "золото", "375"};
        return new Metal(m[0], m[1], Integer.parseInt(m[2]));
    }

    /** Проба, как она выбита: у русских до 1927 — золотники. */
    private static String stamp(Metal m) {
        String t = m.text;
        if (t.contains("56 пробы")) return "56";
        if (t.contains("72 пробы")) return "72";
        if (t.contains("84 пробы")) return "84";
        return Integer.toString(m.fineness);
    }

    private static String style(Rng r, int y) {
        if (y < 1795) return "в духе рококо";
        if (y < 1830) return "в стиле ампир";
        if (y < 1888) return "в духе историзма";
        if (y < 1916) return "в стиле модерн";
        if (y < 1940) return "в стиле ар-деко";
        if (y < 1956) return "в стиле ретро";
        if (y < 1976) return r.chance(0.5) ? "в духе середины века" : "геометричного рисунка";
        return "современного дизайна";
    }

    private static String ct(Rng r, double lo, double hi) {
        return W.dec(r.uniform(lo, hi), 2);
    }

    /** «1,25 карата» — при дробном числе всегда «карата». */
    private static String carat(String v) {
        return v + " карата";
    }

    private static final String MONO = "АБВГДЕЗИКЛМНОПРСТУФЧШЯ";

    // ---------------------------------------------------------------- украшения

    /** Название украшения: по-русски и по-английски; {@code plain} — без камней. */
    private record Name(String ru, String en, boolean plain) {
    }

    private static Name name(Rng r, String kind, Stone s, boolean pearl, boolean diamonds, boolean diamondsOnly, boolean enamel,
                             boolean many) {
        String with = diamondsOnly ? "бриллиантами" : pearl ? "жемчугом" : diamonds ? s.instr + " и бриллиантами"
                : many ? s.instrPl : s.instr;
        String sw = W.s(with);
        String en = diamondsOnly ? "Diamond" : pearl ? "Pearl" : diamonds ? s.en + " and Diamond" : s.en;
        V.P motif = MOTIF[r.nextInt(MOTIF.length)];
        return switch (kind) {
            case "ring" -> {
                if (r.chance(0.12)) yield r.chance(0.5) ? new Name("Кольцо-печатка", "Signet Ring", true)
                        : new Name("Кольцо с монограммой", "Monogrammed Ring", true);
                yield r.chance(0.2) ? new Name("Перстень " + sw, en + " Dress Ring", false) : new Name("Кольцо " + sw, en + " Ring", false);
            }
            case "earrings" -> {
                if (pearl && r.chance(0.5)) yield new Name("Серьги-подвески с жемчугом", "Pearl Drop Earrings", false);
                if (enamel) yield new Name("Серьги с эмалью", "Enamel Earrings", true);
                yield new Name("Серьги " + sw, en + " Earrings", false);
            }
            case "brooch" -> {
                double u = r.nextDouble();
                if (u < 0.35) yield new Name("Брошь «" + motif.ru() + "»", motif.en() + " Brooch", true);
                if (u < 0.5) yield new Name("Брошь «" + motif.ru() + "» " + sw, en + " " + motif.en() + " Brooch", false);
                yield enamel ? new Name("Брошь с эмалью", "Enamel Brooch", true) : new Name("Брошь " + sw, en + " Brooch", false);
            }
            case "pendant" -> {
                double u = r.nextDouble();
                if (u < 0.2) yield new Name("Медальон для фотографии", "Photograph Locket", true);
                if (u < 0.4) yield new Name("Подвеска «" + motif.ru() + "»", motif.en() + " Pendant", true);
                yield new Name("Кулон " + sw, en + " Pendant", false);
            }
            case "necklace" -> {
                if (pearl) yield r.chance(0.5) ? new Name("Ожерелье из жемчуга", "Pearl Necklace", false)
                        : new Name("Колье из жемчуга в два ряда", "Two-Strand Pearl Necklace", false);
                if (s.cabochon && !diamonds && !diamondsOnly && r.chance(0.5)) yield new Name("Бусы из " + s.genPl, s.en + " Bead Necklace", false);
                yield new Name("Колье " + sw, en + " Necklace", false);
            }
            case "bracelet" -> {
                if (r.chance(0.25)) yield enamel ? new Name("Браслет жёсткий с эмалью", "Enamel Bangle", true)
                        : new Name("Браслет-цепь", "Chain Bracelet", true);
                yield new Name("Браслет " + sw, en + " Bracelet", false);
            }
            case "cufflinks" -> {
                if (r.chance(0.3)) yield pearl || r.chance(0.4) ? new Name("Булавка для галстука с жемчужиной", "Pearl Tie Pin", false)
                        : new Name("Булавка для галстука с " + s.instr, s.en + " Tie Pin", false);
                if (enamel) yield new Name("Запонки с эмалью", "Enamel Cufflinks", true);
                if (r.chance(0.2)) yield new Name("Запонки с монограммой", "Monogrammed Cufflinks", true);
                yield new Name("Запонки " + sw, en + " Cufflinks", false);
            }
            default -> {
                String[][] sets = {{"серьги и кольцо", "Earrings and Ring"}, {"брошь и серьги", "Brooch and Earrings"},
                        {"колье и серьги", "Necklace and Earrings"}, {"кольцо и подвеска", "Ring and Pendant"}};
                String[] x = sets[r.nextInt(sets.length)];
                yield new Name("Гарнитур: " + x[0] + " " + sw, en + " Parure: " + x[1], false);
            }
        };
    }

    static void fillJewelry(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        int[] sp = Common.span(m, 1760, Makers.NOW_YEAR - 1);
        Common.Dated d = Common.date(r, sp[0], sp[1], 0.15, 0.2, 0.35);
        c.dated(d);
        c.country = m != null ? m.country() : r.chance(0.7) ? Country.RU : r.chance(0.5) ? Country.FR : Country.GB;
        c.provenanceStyle = "jewel";
        c.attribution = m == null ? Attribution.UNKNOWN : Attribution.BY;
        String kind = c.variant != null ? c.variant : "ring";
        Stone s = STONES[r.weighted(STONE_W)];
        boolean pearl = s.nom.equals("жемчуг");
        boolean diamonds = !pearl && r.chance(0.45);
        boolean diamondsOnly = !pearl && r.chance(0.12);
        boolean enamel = r.chance(0.1);
        Metal metal = metal(r, c.country, c.year);
        boolean many = !pearl && !diamonds && !diamondsOnly && r.chance(0.5);
        Name n = name(r, kind, s, pearl, diamonds, diamondsOnly, enamel, many);
        c.objectRu = n.ru;
        c.objectEn = n.en;
        c.materialRu = metal.material;
        c.fineness = metal.fineness;
        c.originPlaceRu = m != null && m.city() != null ? m.city().name() : c.country.nameRu;
        c.pieces = kind.equals("earrings") || n.ru.startsWith("Запонки") ? 2 : kind.equals("parure") ? (n.ru.contains("кольцо и подвеска") ? 2 : 3) : 1;
        if (n.plain) {
            c.add(metal.text + (n.ru.contains("эмал") || enamel ? ", эмаль" : ""));
            c.add(plainDetail(r, n.ru));
            c.techniqueRu = n.ru.contains("эмал") ? "эмаль" : "литьё, чеканка, гравировка";
            c.defects = "jewel_plain";
        } else {
            stones(c, r, s, metal, pearl, diamonds, diamondsOnly, enamel, many);
            c.techniqueRu = "закрепка камней" + (enamel ? ", эмаль" : "");
            if (pearl) c.defects = "pearl";
        }
        if (r.chance(0.7)) c.add(W.pick(r, design(kind)));
        if (!n.plain && r.chance(0.5)) c.add(W.pick(r, NOTE_STONES));
        tail(c, r, m, d, kind, metal);
    }

    private static String plainDetail(Rng r, String t) {
        if (t.contains("печатка") || t.contains("монограмм")) return "Щиток с гравированной монограммой «" + MONO.charAt(r.nextInt(MONO.length()))
                + MONO.charAt(r.nextInt(MONO.length())) + "»";
        if (t.contains("Медальон")) return "Открывается, внутри — место для двух фотографий под стеклом";
        if (t.contains("цепь")) return W.pick(r, new String[]{"Плетение «якорь»", "Плетение «панцирь»", "Плетение из овальных звеньев"});
        if (t.contains("эмал")) return W.pick(r, new String[]{"Эмаль по гильошированному фону", "Расписная эмаль с цветочным мотивом",
                "Перегородчатая эмаль в синих и белых тонах"});
        return W.pick(r, new String[]{"Чеканка и гравировка", "Литьё, ручная доводка", "Рельефный рисунок, матовая и полированная поверхность",
                "Филигрань и зернь"});
    }

    private static void stones(Ctx c, Rng r, Stone s, Metal metal, boolean pearl, boolean diamonds, boolean diamondsOnly,
                               boolean enamel, boolean many) {
        List<String> mats = new ArrayList<>();
        mats.add(metal.text);
        if (diamondsOnly || diamonds) mats.add(c.year < 1850 ? "алмазы" : "бриллианты");
        if (!diamondsOnly) mats.add(many ? s.nomPl() : s.nom);
        if (enamel) mats.add("эмаль");
        c.add(String.join(", ", mats));
        if (pearl) {
            double a = r.uniform(3, 9);
            c.add("Жемчуг " + (r.chance(0.3) ? "барочной формы" : "диаметром " + W.dec(a, 1) + "–" + W.dec(a + r.uniform(0.3, 1.5), 1) + " мм")
                    + (r.chance(0.3) ? ", натуральный, без проверки лабораторией" : ""));
        } else if (diamondsOnly) {
            int n = r.between(1, 60);
            c.add(c.year < 1850 ? "Алмазы огранки «роза», " + W.count(n, "камень", "камня", "камней")
                    : W.count(n, "бриллиант", "бриллианта", "бриллиантов") + " круглой огранки общим весом около "
                    + carat(ct(r, 0.05 * n + 0.05, 0.03 * n + 0.6)));
        } else if (s.cabochon) {
            c.add(many ? W.cap(s.nomPl()) + " огранки кабошон, всего " + r.between(2, 24) + " шт."
                    : W.cap(s.nom) + " огранки кабошон" + (r.chance(0.5) ? ", размер " + W.dec(r.uniform(5, 22), 1) + " × "
                    + W.dec(r.uniform(4, 16), 1) + " мм" : ""));
        } else if (many) {
            c.add("Вставки — " + s.nomPl() + " " + W.pick(r, CUT).replace(" —", "").replace("огранки «кушон»", "«кушон»")
                    + " огранки, всего " + r.between(2, 30) + " шт., общим весом около " + carat(ct(r, 0.4, 9)));
        } else {
            String cut = W.pick(r, CUT);
            String head = (s.nom.endsWith("ь") ? "Центральная " : "Центральный ") + s.nom + " " + cut;
            String main = cut.endsWith("—") ? head + " около " + carat(ct(r, 0.3, 6))
                    : head + " огранки весом около " + carat(ct(r, 0.3, 6));
            if (diamonds) {
                int n = r.between(6, 40);
                main += ", в окружении " + W.count(n, "бриллианта", "бриллиантов", "бриллиантов") + " "
                        + (c.year < 1880 ? "старинной огранки" : "круглой огранки") + " общим весом около "
                        + carat(ct(r, 0.1, 1.8));
            }
            c.add(main);
        }
    }

    private static void tail(Ctx c, Rng r, Maker m, Common.Dated d, String kind, Metal metal) {
        c.add(marks(c, metal));
        c.signed = m != null;
        double g = StrictMath.round(r.uniform(1.5, kind.equals("necklace") || kind.equals("bracelet") || kind.equals("parure") ? 45 : 18) * 100) / 100.0;
        c.weightG = g;
        String gs = W.dec(g, 2);
        switch (kind) {
            case "ring" -> c.add("Размер " + W.dec(W.half(r, 15, 21), 1).replace(",0", "") + ". Общий вес " + gs + " г");
            case "earrings" -> {
                double h = StrictMath.round(r.uniform(1, 6) * 10) / 10.0;
                c.heightMm = (int) StrictMath.round(h * 10);
                c.add("Длина " + W.dec(h, 1) + " см. Общий вес " + gs + " г");
            }
            case "brooch", "pendant" -> {
                double h = StrictMath.round(r.uniform(2, 7) * 10) / 10.0, w = StrictMath.round(r.uniform(1.5, 5) * 10) / 10.0;
                c.heightMm = (int) StrictMath.round(h * 10);
                c.widthMm = (int) StrictMath.round(w * 10);
                c.add("Размер " + W.dec(h, 1) + " × " + W.dec(w, 1) + " см. Вес " + gs + " г");
            }
            case "necklace", "bracelet" -> {
                double len = W.half(r, kind.equals("necklace") ? 38 : 16, kind.equals("necklace") ? 60 : 21);
                c.widthMm = (int) StrictMath.round(len * 10);
                c.add("Длина " + W.cm(len) + " см. Вес " + gs + " г");
            }
            default -> c.add("Общий вес " + gs + " г");
        }
        c.add(placeAndMaker(c, m, d, style(r, c.year)));
        if (m != null && r.chance(0.45)) c.add(aboutMaker(c, m));
        if (r.chance(0.4)) c.add(W.pick(r, NOTE));
        if (r.chance(0.4)) {
            List<String> opt = new ArrayList<>(List.of(m != null && !m.isPerson() ? "В оригинальном футляре " + m.nameGen() : "В футляре",
                    "В футляре, не оригинальном", "Сопровождается геммологическим заключением"));
            if (kind.equals("earrings") || kind.equals("necklace") || kind.equals("bracelet")) opt.add("Застёжка заменена на современную");
            if (kind.equals("ring")) opt.add("На внутренней стороне шинки — гравировка «" + W.pick(r, DEDICATION) + "»");
            if (kind.equals("pendant") || kind.equals("brooch")) opt.add("На обороте — гравировка «" + W.pick(r, DEDICATION) + "»");
            c.add(opt.get(r.nextInt(opt.size())));
        }
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.6, 0.02, 0.02, 0.12);
    }

    private static String marks(Ctx c, Metal metal) {
        Rng r = c.r;
        Maker m = c.maker;
        String p = stamp(metal);
        double u = r.nextDouble();
        if (m == null) return u < 0.5 ? "Клейма: проба «" + p + "», именник читается нечётко" : "Именника нет; проба «" + p + "»";
        if (m.isPerson()) {
            if (u < 0.6) return "Клейма: проба «" + p + "», именник «" + m.mark() + "»";
            return "Клеймо мастера «" + m.mark() + "» и пробирное клеймо «" + p + "»";
        }
        if (u < 0.5) return "Клейма " + m.nameGen() + ", проба «" + p + "»";
        if (u < 0.8) return "Клейма: «" + m.mark() + "», проба «" + p + "», номер " + r.between(100, 99999);
        return "Клейма читаются частично: проба «" + p + "», «" + m.mark() + "»";
    }

    private static String placeAndMaker(Ctx c, Maker m, Common.Dated d, String style) {
        Rng r = c.r;
        double u = r.nextDouble();
        if (m == null) return Common.placeDate(c.country, d) + " Украшение " + style;
        if (m.isPerson()) {
            if (u < 0.5) return "Работа мастера " + m.nameGen() + " (" + m.years() + "), " + d.text();
            return Common.placeDate(c.country, d) + " Работа " + m.nameGen() + ", " + style;
        }
        if (u < 0.5) return "Изготовлено " + m.namePrep() + ", " + d.text() + ", " + style;
        return Common.placeDate(c.country, d) + " " + W.cap(m.name()) + " (" + m.placeRu() + ", " + m.years() + ")";
    }

    /** О мастере или фирме — одно предложение. */
    static String aboutMaker(Ctx c, Maker m) {
        Rng r = c.r;
        if (m.isPerson()) {
            String v = m.person().female() ? "а" : "";
            return W.pick(r, new String[]{
                    "Мастер работал" + v + " в " + (m.city() != null ? m.city().prep() : "своей мастерской") + ", исполняя заказы частных клиентов",
                    "Мастер известен" + v + " тонкой работой и вниманием к мелочам",
                    "Изделия мастера встречаются в частных собраниях и на торгах"});
        }
        String since = m.yearTo() == null ? "работает с " + m.yearFrom() + " года" : "работала в " + m.yearFrom() + "–" + m.yearTo() + " годах";
        return W.cap(m.name()) + " " + since + (m.placeRu() != null ? ", " + m.placeRu() : "");
    }

    // ---------------------------------------------------------------- камни без оправы

    private static final String[] GEM_CUTS = {"овальной", "круглой", "грушевидной", "квадратной", "изумрудной", "прямоугольной"};
    private static final String[] GEM_CUTS_EN = {"Oval", "Round", "Pear-Shaped", "Square", "Emerald-Cut", "Rectangular"};

    static void fillGem(Ctx c) {
        Rng r = c.r;
        Stone s;
        do {
            s = STONES[r.weighted(STONE_W)];
        } while (s.cabochon);
        boolean diamond = r.chance(0.3);
        int k = r.nextInt(GEM_CUTS.length);
        double ct = StrictMath.round(r.uniform(0.3, diamond ? 4 : 12) * 100) / 100.0;
        String cts = W.dec(ct, 2);
        int y = r.between(1950, 2024);
        c.dated(Common.exact(y));
        c.dated = new Common.Dated(y, 1900, Math.min(2024, Makers.NOW_YEAR), "XX–XXI век", false, "XX–XXI век", "20th–21st century");
        c.year = y;
        c.yearFrom = 1900;
        c.yearTo = 2024;
        c.provenanceStyle = "jewel";
        c.attribution = Attribution.UNKNOWN;
        c.signed = null;
        String nom = diamond ? "бриллиант" : s.nom;
        String en = diamond ? "Diamond" : s.en;
        c.titleRu = Titles.fit(W.cap(nom) + " " + GEM_CUTS[k] + " огранки, " + cts + " карата, без оправы",
                W.cap(nom) + " " + GEM_CUTS[k] + " огранки, " + cts + " карата");
        c.titleEn = Titles.fit("Unmounted " + GEM_CUTS_EN[k] + " " + en + ", " + cts.replace(',', '.') + " ct",
                GEM_CUTS_EN[k] + " " + en + ", " + cts.replace(',', '.') + " ct");
        c.materialRu = nom;
        c.techniqueRu = GEM_CUTS[k].replace("ой", "ая") + " огранка";
        c.weightG = StrictMath.round(ct * 0.2 * 100) / 100.0;
        double a = StrictMath.round(StrictMath.cbrt(ct) * r.uniform(5.2, 6.8) * 10) / 10.0;
        double b = StrictMath.round(a * r.uniform(0.7, 1.0) * 10) / 10.0;
        String colour;
        if (diamond) colour = "цвета " + W.pick(r, new String[]{"D", "E", "F", "G", "H", "I", "J", "K"}) + ", чистоты "
                + W.pick(r, new String[]{"VVS1", "VVS2", "VS1", "VS2", "SI1", "SI2"});
        else {
            String[] col = s.en.equals("Sapphire") || s.en.equals("Ruby") || s.en.equals("Emerald")
                    ? V.list(F, "gem.colour." + s.en) : V.list(F, "gem.colour.any");
            colour = W.pick(r, col) + " цвета";
        }
        if (r.chance(0.3)) c.add("Камень без оправы, для вставки в украшение");
        c.add(W.cap(nom) + " " + GEM_CUTS[k] + " огранки, " + colour);
        c.add("Вес " + cts + " карата (" + W.dec(c.weightG, 2) + " г). Размер " + W.dec(a, 1) + " × " + W.dec(b, 1) + " мм");
        c.add(W.pick(r, new String[]{"Огранка ровная, с хорошими пропорциями", "Камень живой, с хорошей игрой",
                "Цвет распределён равномерно", "Огранка выполнена с сохранением веса камня"}));
        c.add(W.pick(r, new String[]{"Сопровождается заключением геммологической лаборатории",
                "Заключение геммологической лаборатории — в комплекте", "Без лабораторного заключения"}));
        if (!diamond) c.add(W.pick(r, new String[]{"Признаков термической обработки не обнаружено", "Облагораживание: нагрев",
                "Облагораживание не установлено"}));
        c.add(W.pick(r, new String[]{"Камень хранится в пакете с номером лаборатории", "Подходит для кольца или подвески",
                "Камни подобного качества и размера нечасто встречаются на торгах", "Включения под лупой мелкие, не видны глазом"}));
        c.add(W.pick(r, NOTE));
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.85, 0.01, 0.0, 0.5);
    }

    // ---------------------------------------------------------------- часы

    static void fillWatch(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        String v = c.variant == null ? "pocket" : c.variant;
        boolean wrist = v.equals("wrist");
        int lo = wrist ? 1915 : 1780, hi = wrist ? Makers.NOW_YEAR - 1 : v.equals("pocket") ? 1975 : 1960;
        int[] sp = Common.span(m, lo, hi);
        if (sp[0] < lo) sp[0] = lo;
        if (sp[1] < sp[0]) sp[1] = sp[0];
        Common.Dated d = Common.date(r, sp[0], sp[1], 0.1, 0.25, 0.4);
        c.dated(d);
        c.country = m != null ? m.country() : Country.CH;
        c.provenanceStyle = "jewel";
        c.attribution = m == null ? Attribution.UNKNOWN : Attribution.BY;
        int y = c.year;
        String type, en;
        boolean gold = false;
        if (v.equals("pendant")) {
            type = "Часы-кулон";
            en = "Pendant Watch";
        } else if (!wrist) {
            double u = r.nextDouble();
            if (u < 0.5) {
                type = "Часы карманные";
                en = "Pocket Watch";
            } else if (u < 0.65) {
                type = "Часы карманные с крышкой (савонетка)";
                en = "Hunter-Case Pocket Watch";
            } else if (u < 0.75 && y >= 1850) {
                type = "Часы карманные с репетиром";
                en = "Repeating Pocket Watch";
            } else if (u < 0.85 && y >= 1880) {
                type = "Часы карманные с хронографом";
                en = "Chronograph Pocket Watch";
            } else {
                type = "Часы карманные в золотом корпусе";
                en = "Gold Pocket Watch";
                gold = true;
            }
        } else {
            double u = r.nextDouble();
            if (u < 0.35) {
                type = "Часы наручные мужские";
                en = "Gentleman's Wristwatch";
            } else if (u < 0.6) {
                type = "Часы наручные женские";
                en = "Lady's Wristwatch";
            } else if (u < 0.72 && y >= 1932) {
                type = "Часы наручные с хронографом";
                en = "Chronograph Wristwatch";
            } else if (u < 0.84 && y >= 1950) {
                type = "Часы наручные с автоподзаводом";
                en = "Automatic Wristwatch";
            } else if (u < 0.92 && y >= 1945) {
                type = "Часы наручные с календарём";
                en = "Calendar Wristwatch";
            } else {
                type = "Часы наручные";
                en = "Wristwatch";
            }
        }
        c.objectRu = type;
        c.objectEn = en;
        boolean lady = type.contains("женские") || type.contains("кулон");
        String metalText;
        if (!gold && r.chance(0.35)) {
            boolean steel = y >= 1930;
            metalText = steel ? "сталь" : "серебро";
            c.materialRu = metalText;
            if (!steel) {
                c.fineness = c.country == Country.RU ? 875 : 800;
                metalText = "серебро " + c.fineness;
            }
        } else {
            Metal mt;
            do {
                mt = metal(r, c.country, y);
            } while (gold && !mt.material.equals("золото"));
            metalText = mt.text.toLowerCase(Locale.ROOT);
            c.materialRu = mt.material;
            c.fineness = mt.fineness;
        }
        int diam = wrist ? (lady ? r.between(16, 28) : r.between(28, 42)) : (lady ? r.between(26, 36) : r.between(42, 56));
        c.diameterMm = (double) diam;
        c.originPlaceRu = m != null && m.placeRu() != null ? m.placeRu() : c.country.nameRu;
        c.add("Корпус — " + metalText + ", диаметр " + diam + " мм");
        String mech;
        if (y >= 1975 && wrist && r.chance(0.35)) mech = "Механизм кварцевый";
        else if (y < 1880 && !wrist && r.chance(0.4)) mech = "Механизм с ключевым заводом, " + W.pick(r, new String[]{"цилиндровый ход", "анкерный ход"});
        else if (type.contains("автоподзавод")) mech = "Механизм механический с автоподзаводом, " + W.count(r.between(17, 30), "камень", "камня", "камней");
        else mech = "Механизм механический с ручным подзаводом, " + W.count(r.between(7, 23), "камень", "камня", "камней")
                    + (r.chance(0.4) ? ", калибр " + r.between(10, 990) : "");
        c.techniqueRu = mech.toLowerCase(Locale.ROOT).replace("механизм ", "");
        c.add(mech);
        c.add("Циферблат " + W.pick(r, DIAL) + ", " + W.pick(r, NUMERALS) + ", " + W.pick(r, SECONDS));
        c.add(W.pick(r, WATCH_CASE));
        if (m != null) {
            c.signed = true;
            c.add(r.chance(0.5) ? "Подписаны циферблат и механизм: " + m.name()
                    : "На механизме — клеймо " + m.nameGen() + " и номер " + r.between(1000, 999999));
        } else {
            c.signed = false;
            c.add("Циферблат без подписи, на механизме — номер " + r.between(1000, 999999));
        }
        if (r.chance(0.3)) c.add("На внутренней крышке — гравировка «" + W.pick(r, DEDICATION) + "»");
        if (m != null && r.chance(0.6)) c.add(W.cap(m.name()) + " — " + m.placeRu() + ", " + m.years());
        c.add(Common.placeDate(c.country, d));
        if (r.chance(0.5)) c.add(W.pick(r, WATCH_NOTE));
        if (r.chance(0.55)) {
            c.add(wrist ? W.pick(r, new String[]{"Ремешок кожаный, не оригинальный", "Браслет стальной", "Ремешок кожаный",
                    "В оригинальной коробке с паспортом"}) : W.pick(r, new String[]{"Цепочка в комплекте", "В футляре",
                    "Без цепочки", "В дорожном футляре"}));
        }
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.6, 0.02, 0.01, 0.05);
    }
}
