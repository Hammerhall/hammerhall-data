package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.List;
import java.util.Locale;

/**
 * Декоративно-прикладное искусство: фарфор и фаянс, серебро, мебель, стекло, бронза, каминные часы, ковры и текстиль.
 * Название — вид предмета («Сервиз чайный на 6 персон» / «Tea Service for 6», «Комод красного дерева» / «Mahogany Chest
 * of Drawers»); описание — материал и техника, декор, состав, клейма и марки, размеры и вес, место и время. Словарь —
 * {@code vocab/decorative.txt}.
 */
final class Decorative {
    private Decorative() {
    }

    private static final String F = "decorative";

    private static V.P[] p(String s) {
        return V.pairs(F, s);
    }

    private static String[] l(String s) {
        return V.list(F, s);
    }

    private static String persons(int n) {
        return n + " " + W.plural(n, "персону", "персоны", "персон");
    }

    /** «Россия, 1880-е годы.» или «Фарфоровый завод …, 1880-е годы.» */
    private static String origin(Ctx c, Maker m, Common.Dated d) {
        Rng r = c.r;
        if (m == null) return Common.placeDate(c.country, d);
        double u = r.nextDouble();
        if (u < 0.4) return W.cap(m.name()) + ", " + d.text() + ".";
        if (u < 0.7) return Common.placeDate(c.country, d) + " Изготовлено " + m.namePrep() + ".";
        return Common.placeDate(c.country, d) + " " + W.cap(m.name()) + " (" + m.placeRu() + ", " + m.years() + ").";
    }

    private static Common.Dated dated(Ctx c, int lo, int hi) {
        int[] sp = Common.span(c.maker, lo, hi);
        Common.Dated d = Common.date(c.r, sp[0], sp[1], 0.08, 0.2, 0.42);
        c.dated(d);
        c.country = c.maker != null ? c.maker.country() : Country.RU;
        c.provenanceStyle = "deco";
        c.attribution = c.maker == null ? Attribution.UNKNOWN : Attribution.BY;
        c.originPlaceRu = c.maker != null && c.maker.placeRu() != null ? c.maker.placeRu() : c.country.nameRu;
        return d;
    }

    /** О мастерской — одно предложение (через раз). */
    private static void about(Ctx c, Maker m) {
        if (m == null || !c.r.chance(0.4) || String.join(" ", c.desc).contains(m.years())) return;
        if (m.isPerson()) {
            c.add(m.name() + " (" + m.years() + ")" + (m.specialty() != null ? " — " + m.specialty() : ""));
        } else {
            c.add(W.cap(m.name()) + (m.yearTo() == null ? " работает с " + m.yearFrom() + " года" : " работала в " + m.yearFrom() + "–"
                    + m.yearTo() + " годах") + (m.specialty() != null ? "; " + m.specialty() : ""));
        }
    }

    private static void finish(Ctx c, String[] notes, double pNone, double pLit) {
        Rng r = c.r;
        c.add(W.pick(r, notes));
        if (r.chance(0.4)) c.add(W.pick(r, notes));
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, pNone, pLit, 0.03, 0.02);
    }

    private static V.P q(String ru, String en, V.P x) {
        return new V.P(ru + " «" + x.ru() + "»", en + " “" + x.en() + "”");
    }

    private static V.P plus(V.P a, String ru, String en) {
        return new V.P(a.ru() + ru, a.en() + en);
    }

    private static final String MONO_LETTERS = "АБВГДЕЖЗИКЛМНОПРСТУФХЧШЯ";

    private static String mono(Rng r) {
        return "" + MONO_LETTERS.charAt(r.nextInt(MONO_LETTERS.length())) + MONO_LETTERS.charAt(r.nextInt(MONO_LETTERS.length()));
    }

    private static String cmH(Ctx c, double lo, double hi) {
        double h = W.half(c.r, lo, hi);
        c.heightMm = (int) StrictMath.round(h * 10);
        return W.cm(h);
    }

    // ---------------------------------------------------------------- фарфор

    private static final V.P[] FIGURE = p("porc.figure"), FIGURE_KIND = p("porc.figure.kind"), PLATE_SCENE = p("porc.plate.scene"),
            PLATE = p("porc.plate"), CUP = p("porc.cup"), VASE = p("porc.vase"), TABLEWARE = p("porc.tableware");
    private static final String[] PORC_DECOR = l("porc.decor"), PORC_PATTERN = l("porc.pattern"), PORC_NOTE = l("porc.note");
    private static final int[] TEA_SIZES = {2, 4, 6, 6, 8, 12};

    private static V.P porcelainQualifier(String pattern) {
        if (pattern.contains("пейзаж") || pattern.contains("виды города")) return new V.P(" с пейзажными медальонами", " with Landscape Medallions");
        if (pattern.contains("птицы")) return new V.P(" с птицами", " with Birds");
        if (pattern.contains("пастора")) return new V.P(" с пасторальными сценами", " with Pastoral Scenes");
        if (pattern.contains("золот")) return new V.P(" с золотым бордюром", " with Gilt Border");
        if (pattern.contains("рокайл")) return new V.P(" с рокайльным декором", " with Rocaille Decoration");
        if (pattern.contains("орнамент")) return new V.P(" с орнаментом", " with Ornament");
        return new V.P(" с цветочной росписью", " with Floral Decoration");
    }

    static void fillPorcelain(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        Common.Dated d = dated(c, 1750, 1995);
        int y = c.year;
        String v = c.variant == null ? "vase" : c.variant;
        V.P t;
        int n = 0;
        boolean service = false;
        switch (v) {
            case "tea" -> {
                service = true;
                double u = r.nextDouble();
                if (u < 0.6) {
                    n = TEA_SIZES[r.nextInt(TEA_SIZES.length)];
                    t = new V.P("Сервиз чайный на " + persons(n), "Tea Service for " + n);
                } else if (u < 0.85) {
                    n = r.chance(0.5) ? 6 : 4;
                    t = new V.P("Сервиз кофейный на " + persons(n), "Coffee Service for " + n);
                } else {
                    n = 2;
                    t = new V.P("Сервиз «тет-а-тет»", "Tête-à-Tête Tea Service");
                }
            }
            case "dinner" -> {
                service = true;
                n = r.chance(0.5) ? 12 : 6;
                t = new V.P("Сервиз столовый на " + persons(n), "Dinner Service for " + n);
            }
            case "cup" -> t = V.pick(r, CUP);
            case "plate" -> t = r.chance(0.3) ? new V.P("Тарелка с росписью «" + V.pick(r, PLATE_SCENE).ru() + "»", "") : V.pick(r, PLATE);
            case "figure" -> t = q(V.pick(r, FIGURE_KIND).ru(), "", V.pick(r, FIGURE));
            case "tableware" -> t = V.pick(r, TABLEWARE);
            default -> t = V.pick(r, VASE);
        }
        // английские названия статуэток и тарелок с сюжетом — по той же паре
        if (v.equals("figure")) {
            String subj = t.ru().substring(t.ru().indexOf('«') + 1, t.ru().length() - 1);
            String kind = t.ru().substring(0, t.ru().indexOf(' '));
            String kindEn = kind.equals("Фигура") ? "Figure" : kind.equals("Скульптурная") ? "Figure Group" : "Figurine";
            if (kind.equals("Скульптурная")) kind = "Скульптурная группа";
            String en = subj;
            for (V.P x : FIGURE) if (x.ru().equals(subj)) en = x.en();
            t = new V.P(kind + " «" + subj + "»", kindEn + " “" + en + "”");
        } else if (t.en().isEmpty()) {
            String subj = t.ru().substring(t.ru().indexOf('«') + 1, t.ru().length() - 1);
            String en = subj;
            for (V.P x : PLATE_SCENE) if (x.ru().equals(subj)) en = x.en();
            t = new V.P(t.ru(), "Plate Painted with “" + en + "”");
        }
        boolean figure = v.equals("figure");
        String mat = figure && r.chance(0.25) ? "Бисквит" : r.chance(0.15) ? "Фаянс" : "Фарфор";
        String decor = W.pick(r, PORC_DECOR);
        if (y >= 1900 && r.chance(0.15)) decor = "деколь, позолота";
        if (mat.equals("Бисквит")) decor = "без глазури";
        if (figure && !mat.equals("Бисквит")) decor = "роспись надглазурными красками";
        c.materialRu = mat.toLowerCase(Locale.ROOT);
        c.techniqueRu = decor;
        c.add(mat + ", " + decor);
        if (!figure && !decor.startsWith("без росписи") && r.chance(0.7)) {
            String pat = W.pick(r, PORC_PATTERN);
            c.add("Декор — " + pat);
            if (!service && !t.ru().contains("«") && r.chance(0.5)) t = plus(t, porcelainQualifier(pat).ru(), porcelainQualifier(pat).en());
        } else if (decor.contains("кобальт") && !figure && !t.ru().contains("«") && r.chance(0.4)) {
            t = plus(t, " с кобальтовой росписью", " with Cobalt Decoration");
        }
        c.objectRu = t.ru();
        c.objectEn = t.en();
        if (v.equals("tea")) {
            boolean plates = r.chance(0.35);
            int total = 3 + 2 * n + (plates ? n : 0);
            c.pieces = total;
            String pot = t.ru().contains("кофейный") ? "кофейник" : "чайник";
            c.add("Состав: " + pot + ", сахарница, молочник, " + W.count(n, "чашка", "чашки", "чашек") + " и "
                    + W.count(n, "блюдце", "блюдца", "блюдец") + (plates ? ", " + W.count(n, "десертная тарелка",
                    "десертные тарелки", "десертных тарелок") : "") + " — всего " + W.count(total, "предмет", "предмета", "предметов"));
            c.add("Высота " + pot + "а " + cmH(c, 12, 24) + " см");
        } else if (v.equals("dinner")) {
            int k = r.between(2, 6);
            int total = 2 * n + 2 + k;
            c.pieces = total;
            c.add("Состав: " + n + " глубоких и " + n + " мелких тарелок, супница, соусник, " + W.count(k, "блюдо", "блюда", "блюд")
                    + " — всего " + W.count(total, "предмет", "предмета", "предметов"));
            c.add("Диаметр мелкой тарелки " + dia(c, 22, 27) + " см");
        } else if (v.equals("plate")) {
            if (t.ru().startsWith("Пара")) c.pieces = 2;
            c.add("Диаметр " + dia(c, 18, 40) + " см");
        } else if (v.equals("cup")) {
            c.pieces = t.ru().startsWith("Пара") ? 4 : 2;
            c.add("Высота чашки " + cmH(c, 5, 11) + " см, диаметр блюдца " + W.cm(W.half(r, 12, 17)) + " см");
        } else if (figure) {
            c.add("Высота " + cmH(c, 8, 45) + " см");
        } else {
            if (t.ru().startsWith("Пара")) c.pieces = 2;
            c.add("Высота " + cmH(c, t.ru().startsWith("Ваза") || t.ru().startsWith("Пара ваз") ? 15 : 6,
                    t.ru().startsWith("Ваза") || t.ru().startsWith("Пара ваз") ? 60 : 24) + " см");
        }
        if (m == null) {
            c.signed = false;
            c.add(W.pick(r, new String[]{"Без марки", "Марка стёрта", "Подглазурная марка синего цвета, не определена"}));
        } else {
            c.signed = true;
            c.add(W.pick(r, new String[]{"Подглазурная марка " + m.nameGen() + " синего цвета",
                    "Надглазурная марка " + m.nameGen() + " красного цвета", "Марка " + m.nameGen() + " и номер формы " + r.between(1, 3000),
                    "Марка «" + m.mark() + "» на дне; изделие " + m.nameGen(), "Марка " + m.nameGen() + "; марка художника-росписчика «" + mono(r) + "»"}));
        }
        if (figure && r.chance(0.4)) {
            Maker s = sculptor(c, y);
            if (s != null) c.add("Модель скульптора " + s.nameGen());
        }
        c.add(origin(c, m, d));
        about(c, m);
        finish(c, PORC_NOTE, 0.55, 0.04);
    }

    private static String dia(Ctx c, double lo, double hi) {
        double d = W.half(c.r, lo, hi);
        c.diameterMm = d * 10;
        return W.cm(d);
    }

    private static Maker sculptor(Ctx c, int y) {
        List<Maker> s = c.lt.makers().of(MakerKind.SCULPTOR);
        for (int i = 0; i < 15; i++) {
            Maker t = s.get(c.r.nextInt(s.size()));
            if (t.activeFrom() <= y && t.activeTo(Makers.NOW_YEAR) >= y - 30) return t;
        }
        return null;
    }

    // ---------------------------------------------------------------- серебро

    private static final V.P[] S_TEA = p("silver.tea"), S_FLAT = p("silver.flatware"), S_SPOONS = p("silver.spoons"),
            S_BOXES = p("silver.boxes"), S_CANDLE = p("silver.candlesticks"), S_TABLE = p("silver.table");
    private static final String[] SILVER_TECH = l("silver.tech"), SILVER_DECOR = l("silver.decor"), SILVER_NOTE = l("silver.note");

    /** Проба серебра: как выбита и метрическая. */
    private static int[] silverProba(Rng r, Country c, int y) {
        return switch (c) {
            case RU -> y < 1927 ? new int[][]{{84, 875}, {84, 875}, {88, 916}, {91, 947}}[r.nextInt(4)]
                    : y < 1994 ? (r.chance(0.5) ? new int[]{875, 875} : new int[]{916, 916}) : new int[]{925, 925};
            case GB, US -> new int[]{925, 925};
            case FR -> r.chance(0.5) ? new int[]{950, 950} : new int[]{800, 800};
            case DE, AT, CH -> r.chance(0.5) ? new int[]{800, 800} : new int[]{835, 835};
            case NL, BE -> new int[]{833, 833};
            case SE, NO, DK, FI -> new int[]{830, 830};
            default -> new int[]{800, 800};
        };
    }

    static void fillSilver(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        Common.Dated d = dated(c, 1750, 1995);
        int y = c.year;
        String v = c.variant == null ? "table" : c.variant;
        V.P t;
        boolean set = false;
        switch (v) {
            case "tea" -> t = V.pick(r, S_TEA);
            case "flatware" -> {
                double u = r.nextDouble();
                if (u < 0.45) {
                    int n = r.chance(0.6) ? 6 : 12;
                    V.P s = V.pick(r, S_SPOONS);
                    t = new V.P(s.ru() + ", " + n + " шт.", "Set of " + n + " " + s.en());
                    c.pieces = n;
                    set = true;
                } else if (u < 0.65) {
                    int n = r.chance(0.5) ? 6 : 12;
                    t = new V.P("Набор столовых приборов на " + persons(n), "Flatware Service for " + n);
                    c.pieces = n * 3;
                    set = true;
                } else {
                    t = V.pick(r, S_FLAT);
                }
            }
            case "boxes" -> t = V.pick(r, S_BOXES);
            case "candlesticks" -> {
                if (r.chance(0.3)) {
                    int n = 3 + 2 * r.nextInt(3);
                    t = new V.P("Канделябр на " + n + " " + W.plural(n, "свечу", "свечи", "свечей"), n + "-Light Candelabrum");
                } else {
                    t = V.pick(r, S_CANDLE);
                }
            }
            default -> t = V.pick(r, S_TABLE);
        }
        if (t.ru().startsWith("Пара")) c.pieces = 2;
        int[] pr = silverProba(r, c.country, y);
        c.materialRu = "серебро";
        c.fineness = pr[1];
        String proba = Integer.toString(pr[0]);
        String tech = W.pick(r, SILVER_TECH);
        c.techniqueRu = tech;
        c.add("Серебро " + (pr[0] < 100 ? pr[0] + " пробы (" + pr[1] + ")" : proba) + ", " + tech);
        String dec = null;
        if (!tech.startsWith("гладкое") && r.chance(0.75)) {
            dec = W.pick(r, SILVER_DECOR);
            if (r.chance(0.25)) dec += "; гравированный вензель «" + mono(r) + "»";
            c.add("Декор — " + dec);
        }
        if (!set && r.chance(0.6)) {
            if (dec != null && dec.contains("тройка")) t = plus(t, " «Тройка»", " “Troika”");
            else if (tech.contains("эмаль")) t = plus(t, " с эмалью", " with Enamel");
            else if (tech.contains("чернь")) t = plus(t, " с чернью", " with Niello");
            else if (tech.contains("филигрань")) t = plus(t, " с филигранью", " with Filigree");
            else if (dec != null && dec.contains("вензель")) t = plus(t, " с вензелем", " with Monogram");
            else if (tech.contains("гравировка")) t = plus(t, " с гравировкой", " with Engraving");
            else if (tech.contains("золочение")) t = plus(t, " с позолотой", ", Parcel-Gilt");
            else if (tech.contains("чеканка")) t = plus(t, " с чеканным орнаментом", " with Chased Ornament");
        }
        c.objectRu = t.ru();
        c.objectEn = t.en();
        if (m == null) {
            c.signed = false;
            c.add(W.pick(r, new String[]{"Клейма читаются нечётко", "Пробирное клеймо «" + proba + "», именник не определён"}));
        } else {
            c.signed = true;
            if (m.country() == Country.RU) c.add(W.pick(r, new String[]{
                    "Клейма: проба «" + proba + "», именник «" + m.mark() + "», городское клеймо; работа " + m.nameGen(),
                    "Клейма " + m.nameGen() + ", проба «" + proba + "»",
                    "Клейма " + m.nameGen() + ": именник «" + m.mark() + "», проба «" + proba + "», пробирное клеймо с датой «" + y + "»"}));
            else c.add(W.pick(r, new String[]{"Пробирные клейма и клеймо «" + m.mark() + "» (" + m.name() + ")",
                    "Клейма " + m.nameGen() + ", проба «" + proba + "»", "Клеймо " + m.nameGen() + " и буквенная дата"}));
        }
        // размер и вес
        String ru = t.ru();
        int g;
        if (ru.startsWith("Поднос")) {
            int a = r.between(20, 60), b = r.between(14, 40);
            c.sizeCm((double) b, (double) a, null);
            g = r.between(350, 2200);
            c.add("Размер " + a + " × " + b + " см. Вес " + g + " г");
        } else if (set) {
            g = ru.startsWith("Набор") ? r.between(1200, 6500) : r.between(60, 520);
            double len = StrictMath.round(r.uniform(11, 21) * 2) / 2.0;
            c.widthMm = (int) StrictMath.round(len * 10);
            c.add("Общий вес " + g + " г. Длина " + (ru.startsWith("Набор") ? "столовой ложки " : "ложки ") + W.cm(len) + " см");
        } else if (v.equals("flatware") || ru.startsWith("Щипцы") || ru.startsWith("Ситечко")) {
            double len = W.half(r, 9, 30);
            c.widthMm = (int) StrictMath.round(len * 10);
            g = r.between(20, 220);
            c.add("Длина " + W.cm(len) + " см. Вес " + g + " г");
        } else if (v.equals("boxes") || ru.startsWith("Кольцо") || ru.startsWith("Солонка")) {
            double len = W.half(r, 4, 14), w = W.half(r, 3, 9);
            c.sizeCm(null, len, w);
            g = r.between(20, 260);
            c.add("Размер " + W.cm(len) + " × " + W.cm(w) + " см. Вес " + g + " г");
        } else {
            g = r.between(120, 1500);
            c.add("Высота " + cmH(c, 6, 35) + " см. Вес " + g + " г");
        }
        c.weightG = (double) g;
        if (r.chance(0.3)) c.add(W.pick(r, new String[]{"Внутри — позолота", "В футляре", "Ручка деревянная", "Вкладыш стеклянный, синий",
                "На крышке — гравированная надпись «" + W.pick(r, new String[]{"На добрую память", "В день свадьбы", "С благодарностью"}) + "»"}));
        c.add(origin(c, m, d));
        about(c, m);
        finish(c, SILVER_NOTE, 0.55, 0.03);
    }

    // ---------------------------------------------------------------- мебель

    private static final V.P[] F_CASE = p("furn.case"), F_TABLE = p("furn.table"), F_SEAT = p("furn.seating"),
            F_MIRROR = p("furn.mirror"), F_OTHER = p("furn.other"), WOOD = p("wood");
    private static final String[] BUILD_CASE = l("furn.build.case"), BUILD_TABLE = l("furn.build.table"),
            FABRIC = l("furn.fabric"), FURN_NOTE = l("furn.note");

    /** Стиль: {по-русски, по-английски} или {@code null}. */
    private static String[] furnStyle(Rng r, int y) {
        if (y >= 1770 && y < 1810 && r.chance(0.5)) return new String[]{"в стиле неоклассицизма", "Neoclassical"};
        if (y >= 1795 && y < 1835) return new String[]{"в стиле ампир", "Empire"};
        if (y >= 1815 && y < 1850) return new String[]{"в стиле бидермейер", "Biedermeier"};
        if (y >= 1840 && y < 1880) return new String[]{"в стиле второго рококо", "Rococo Revival"};
        if (y >= 1860 && y < 1900) return new String[]{"в стиле неоренессанс", "Renaissance Revival"};
        if (y >= 1895 && y < 1916) return new String[]{"в стиле модерн", "Art Nouveau"};
        if (y >= 1920 && y < 1941) return new String[]{"в стиле ар-деко", "Art Deco"};
        if (y >= 1950 && y < 1976) return new String[]{"середины XX века", "Mid-Century"};
        return null;
    }

    /** Пределы размеров по виду мебели: высота, ширина, глубина (от, до), см. */
    private static int[] furnSize(String item) {
        String i = item.toLowerCase(Locale.ROOT);
        if (i.contains("часы")) return new int[]{180, 240, 40, 60, 25, 35};
        if (i.contains("ширма")) return new int[]{150, 190, 120, 240, 2, 5};
        if (i.contains("зеркало") || i.contains("трюмо")) return new int[]{60, 220, 40, 120, 4, 45};
        if (i.contains("шкаф") || i.contains("горка") || i.contains("буфет") || i.contains("шифоньер") || i.contains("секретер"))
            return new int[]{150, 240, 80, 200, 35, 60};
        if (i.contains("этажерка") || i.contains("пюпитр") || i.contains("жардиньерка") || i.contains("подставка")) return new int[]{90, 180, 35, 90, 25, 45};
        if (i.contains("комод") || i.contains("бюро") || i.contains("конторка") || i.contains("кабинет"))
            return new int[]{75, 130, 60, 150, 40, 65};
        if (i.contains("стол письменный") || i.contains("стол обеденный") || i.contains("стол круглый")) return new int[]{74, 80, 100, 200, 60, 110};
        if (i.contains("столик")) return new int[]{60, 80, 40, 95, 30, 60};
        if (i.contains("диван") || i.contains("канапе") || i.contains("козетка")) return new int[]{80, 110, 110, 220, 55, 90};
        if (i.contains("кресл")) return new int[]{85, 115, 55, 75, 50, 75};
        if (i.contains("стул")) return new int[]{80, 110, 40, 55, 40, 55};
        if (i.contains("банкетка")) return new int[]{40, 55, 50, 120, 35, 50};
        if (i.contains("сундук")) return new int[]{40, 70, 70, 130, 40, 70};
        return new int[]{60, 120, 40, 120, 30, 60};
    }

    static void fillFurniture(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        Common.Dated d = dated(c, 1730, 1990);
        int y = c.year;
        String v = c.variant == null ? "case" : c.variant;
        V.P item = V.pick(r, switch (v) {
            case "table" -> F_TABLE;
            case "seating" -> F_SEAT;
            case "mirror" -> F_MIRROR;
            case "other" -> F_OTHER;
            default -> F_CASE;
        });
        if (item.ru().equals("Кресло-качалка") && y < 1860) item = F_SEAT[0];
        String[] st = furnStyle(r, y);
        V.P wood = V.pick(r, WOOD);
        String[] wf = wood.ru().split("\\s*:\\s*");
        String woodNom = wf[0], woodIn = wf[1];
        if (y >= 1950 && r.chance(0.3)) {
            woodNom = "Тик";
            woodIn = "из тика";
            wood = new V.P("Тик : из тика", "Teak");
        }
        V.P t = item;
        if (!item.ru().contains(" ") && r.chance(0.45)) {
            t = new V.P(item.ru() + " " + woodIn, wood.en() + " " + item.en());
            if (st != null && r.chance(0.35)) t = new V.P(t.ru() + " " + st[0], st[1] + " " + t.en());
        } else if (st != null && r.chance(0.45)) {
            t = new V.P(item.ru() + " " + st[0], st[1] + " " + item.en());
        }
        if (item.ru().startsWith("Пара")) c.pieces = 2;
        if (item.ru().startsWith("Комплект из 4")) c.pieces = 4;
        c.objectRu = t.ru();
        c.objectEn = t.en();
        StringBuilder mat = new StringBuilder(woodNom);
        StringBuilder tech = new StringBuilder();
        if (r.chance(0.6)) tech.append("шпон");
        if (r.chance(0.25)) tech.append(tech.isEmpty() ? "" : ", ").append("маркетри");
        if (r.chance(0.25)) tech.append(tech.isEmpty() ? "" : ", ").append("резьба");
        if (y >= 1860 && r.chance(0.1)) {
            mat = new StringBuilder("Бук");
            tech = new StringBuilder("гнутое дерево");
        }
        c.materialRu = mat.toString().toLowerCase(Locale.ROOT);
        c.techniqueRu = tech.isEmpty() ? null : tech.toString();
        c.add(mat + (tech.isEmpty() ? "" : ", " + tech) + (r.chance(0.2) && y < 1920 ? "; накладки из золочёной бронзы" : ""));
        String ir = item.ru().toLowerCase(Locale.ROOT);
        boolean soft = ir.contains("кресл") || ir.contains("стул") || ir.contains("канапе") || ir.contains("диван")
                || ir.contains("банкетка") || ir.contains("козетка");
        if (soft) c.add("Обивка — " + W.pick(r, FABRIC) + (r.chance(0.4) ? ", поздняя" : ""));
        else if (ir.contains("часы")) c.add("Механизм с боем, маятник и гири в наличии");
        else if (ir.contains("ширма")) c.add("Три створки, обтянутые шёлком");
        else if (ir.contains("зеркало") || ir.contains("трюмо")) c.add(W.pick(r, new String[]{"Зеркало фацетированное",
                "Рама с резным картушем", "Зеркальное полотно заменено"}));
        else if (ir.contains("сундук")) c.add("Оковка из кованого железа, внутри — отделение для мелочей");
        else if (ir.contains("стол")) c.add(W.pick(r, BUILD_TABLE));
        else if (v.equals("other")) c.add("Три полки на точёных стойках");
        else c.add(W.pick(r, BUILD_CASE));
        int[] dim = furnSize(item.ru());
        int h = r.between(dim[0], dim[1]), w = r.between(dim[2], dim[3]), dd = r.between(dim[4], dim[5]);
        c.sizeCm((double) h, (double) w, (double) dd);
        c.add("Высота " + h + ", ширина " + w + ", глубина " + dd + " см");
        if (m != null) {
            c.signed = r.chance(0.6);
            c.add(c.signed ? W.pick(r, new String[]{"Клеймо " + m.nameGen() + " на задней стенке",
                    "Бумажная этикетка " + m.nameGen() + " на внутренней стороне ящика", "Клеймо «" + m.mark() + "» на царге; работа " + m.nameGen()})
                    : "Без клейма; по конструкции и фурнитуре — работа " + m.nameGen());
        } else {
            c.signed = false;
        }
        if (st != null && !t.ru().contains(st[0])) c.add("Предмет " + st[0]);
        if (r.chance(0.35)) c.add(W.pick(r, new String[]{"Ключ в наличии", "Фурнитура оригинальная", "Фурнитура частично заменена",
                "Поздняя реставрация обивки", "Ящики ходят легко"}));
        c.add(origin(c, m, d));
        about(c, m);
        finish(c, FURN_NOTE, 0.6, 0.02);
    }

    // ---------------------------------------------------------------- стекло

    private static final V.P[] GLASS_ITEM = p("glass.item");
    private static final String[] GLASS_KIND = l("glass.kind.art"), GLASS_TECH = l("glass.tech"), GLASS_DECOR = l("glass.decor"),
            GLASS_NOTE = l("glass.note");

    static void fillGlass(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        Common.Dated d = dated(c, 1780, 1995);
        V.P t = V.pick(r, GLASS_ITEM);
        boolean crystal = "crystal".equals(c.variant);
        String kind = crystal ? "Хрусталь" : W.pick(r, GLASS_KIND);
        String tech = crystal ? W.pick(r, new String[]{"алмазная грань", "шлифовка", "гравировка", "резьба, шлифовка"}) : W.pick(r, GLASS_TECH);
        c.materialRu = kind.toLowerCase(Locale.ROOT);
        c.techniqueRu = tech;
        if (t.ru().startsWith("Пара")) c.pieces = 2;
        c.add(kind + ", " + tech);
        if (r.chance(0.6)) {
            String dec = W.pick(r, GLASS_DECOR);
            c.add("Декор — " + dec);
            if (!t.ru().contains("«") && r.chance(0.5)) {
                t = dec.contains("ирис") ? plus(t, " «Ирисы»", " “Irises”") : dec.contains("стрекоз") ? plus(t, " «Стрекозы»", " “Dragonflies”")
                        : dec.contains("озер") ? plus(t, " с пейзажем", " with Landscape") : dec.contains("ягод") ? plus(t, " «Ягоды»", " “Berries”")
                        : tech.equals("гравировка") ? plus(t, " с гравировкой", " with Engraving") : plus(t, " с резным декором", " with Cut Decoration");
            }
        } else if (!t.ru().contains("«") && crystal && r.chance(0.5)) {
            t = plus(t, ", хрусталь", ", Crystal");
        }
        c.objectRu = t.ru();
        c.objectEn = t.en();
        if (r.chance(0.7)) c.add("Высота " + cmH(c, 6, 45) + " см");
        else c.add("Диаметр " + dia(c, 8, 40) + " см");
        if (m == null) {
            c.signed = false;
            c.add("Без марки");
        } else {
            c.signed = r.chance(0.75);
            c.add(c.signed ? W.pick(r, new String[]{"Подпись «" + m.mark() + "» в рельефе; изделие " + m.nameGen(),
                    "Гравированная подпись на дне: " + m.name(), "Бумажная этикетка " + m.nameGen()})
                    : "Без марки; атрибутируется как работа " + m.nameGen());
        }
        c.add(origin(c, m, d));
        about(c, m);
        finish(c, GLASS_NOTE, 0.6, 0.03);
    }

    // ---------------------------------------------------------------- бронза

    private static final V.P[] B_ANIMAL = p("bronze.animal"), B_FIGURAL = p("bronze.figural"), B_BUST = p("bronze.bust"),
            B_DESK = p("bronze.desk"), B_PAPERWEIGHT = p("bronze.paperweight");
    private static final String[] BRONZE_NOTE = l("bronze.note");
    private static final String[][] B_MAT = {{"Бронза", "литьё, патинирование"}, {"Бронза", "литьё, золочение"},
            {"Бронза", "литьё, тёмная патина"}, {"Чугун", "литьё"}, {"Шпиатр", "литьё, патинирование"},
            {"Бронза", "литьё, чеканка, патинирование"}};

    static void fillBronze(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        Common.Dated d = dated(c, 1780, 1990);
        int y = c.year;
        String v = c.variant == null ? "animal" : c.variant;
        V.P t;
        switch (v) {
            case "lighting" -> {
                double u = r.nextDouble();
                if (u < 0.4) {
                    t = new V.P("Пара подсвечников", "Pair of Candlesticks");
                    c.pieces = 2;
                } else if (u < 0.7) {
                    int n = 3 + 2 * r.nextInt(3);
                    t = new V.P("Канделябр на " + n + " " + W.plural(n, "свечу", "свечи", "свечей"), n + "-Light Candelabrum");
                } else if (u < 0.85 && y >= 1885) {
                    t = new V.P("Настольная лампа", "Table Lamp");
                } else {
                    t = new V.P("Подсвечник", "Candlestick");
                }
            }
            case "desk" -> {
                V.P x = V.pick(r, B_DESK);
                t = x.ru().equals("Пресс-папье") ? q("Пресс-папье", "Paperweight", V.pick(r, B_PAPERWEIGHT)) : x;
            }
            case "figural" -> {
                double u = r.nextDouble();
                if (u < 0.12) t = new V.P("Бюст " + V.pick(r, B_BUST).ru(), "");
                else if (u < 0.22) t = q("Барельеф", "Relief", V.pick(r, B_FIGURAL));
                else t = r.chance(0.5) ? q("Скульптура", "Sculpture", V.pick(r, B_FIGURAL)) : q("Статуэтка", "Statuette", V.pick(r, B_FIGURAL));
                if (t.en().isEmpty()) {
                    String w = t.ru().substring(5);
                    String en = w;
                    for (V.P x : B_BUST) if (x.ru().equals(w)) en = x.en();
                    t = new V.P(t.ru(), "Bust of " + en);
                }
            }
            default -> t = r.chance(0.5) ? q("Скульптура", "Sculpture", V.pick(r, B_ANIMAL)) : q("Статуэтка", "Statuette", V.pick(r, B_ANIMAL));
        }
        c.objectRu = t.ru();
        c.objectEn = t.en();
        String[] mt = B_MAT[r.nextInt(B_MAT.length)];
        if (y >= 1880 && r.chance(0.08)) mt = new String[]{"Гипс", "тонировка"};
        c.materialRu = mt[0].toLowerCase(Locale.ROOT);
        c.techniqueRu = mt[1];
        String base = W.pick(r, new String[]{"мраморный цоколь", "на деревянном основании", "цоколь из серпентинита", "без цоколя"});
        c.add(mt[0] + ", " + mt[1] + "; " + base);
        if (m != null) {
            c.signed = true;
            if (m.isPerson()) {
                String sig = m.signatureRu();
                c.add(sig != null ? "Подпись скульптора на основании: «" + sig + "»" : "Подпись скульптора латиницей на основании");
                c.add("Автор модели — " + m.name() + " (" + m.years() + ")" + (m.specialty() != null ? ", " + m.specialty() : ""));
            } else {
                c.add("Клеймо " + m.nameGen() + " на обороте основания");
            }
        } else {
            c.signed = false;
            c.add("Без подписи");
        }
        if (!mt[0].equals("Гипс")) {
            if (m == null || m.isPerson()) {
                List<Maker> fs = c.lt.makers().of(MakerKind.FOUNDRY);
                Maker f = fs.get(r.nextInt(fs.size()));
                if (r.chance(0.5) && f.yearFrom() <= y && f.activeTo(Makers.NOW_YEAR) >= y) {
                    c.add(r.chance(0.5) ? "Клеймо " + f.nameGen() : "Отлито " + f.namePrep());
                }
            }
            c.add(W.pick(r, m != null && m.isPerson() ? new String[]{"Прижизненная отливка", "Поздняя отливка", "Отливка из ограниченной серии, № "
                    + r.between(1, 25), "Отливка по модели " + Math.max(1700, y - r.between(0, 15)) + " года"}
                    : new String[]{"Отливка хорошего качества", "Отливка по модели " + Math.max(1700, y - r.between(0, 15)) + " года"}));
        }
        c.add("Высота " + cmH(c, 8, v.equals("desk") ? 30 : 90) + " см");
        c.add(Common.placeDate(c.country, d));
        if (m != null && !m.isPerson()) about(c, m);
        finish(c, BRONZE_NOTE, 0.55, 0.05);
    }

    // ---------------------------------------------------------------- часы

    private static final V.P[] CLOCK_ITEM = p("clock.item"), CLOCK_SUBJECT = p("clock.subject");
    private static final String[] CLOCK_NOTE = l("clock.note");

    static void fillClock(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        Common.Dated d = dated(c, 1770, 1960);
        int y = c.year;
        V.P t = V.pick(r, CLOCK_ITEM);
        if (t.ru().equals("Часы каминные") && r.chance(0.4)) t = q("Часы каминные", "Mantel Clock", V.pick(r, CLOCK_SUBJECT));
        if (t.ru().startsWith("Часы с гарнитуром")) c.pieces = 3;
        c.objectRu = t.ru();
        c.objectEn = t.en();
        String[][] mats = {{"Бронза", "литьё, золочение"}, {"Бронза", "литьё, патинирование"}, {"Мрамор", "резьба, полировка"},
                {"Дерево", "шпон, резьба"}, {"Латунь", "литьё, гравировка"}};
        String[] mt = mats[r.nextInt(mats.length)];
        if (t.ru().contains("каретные")) mt = new String[]{"Латунь", "литьё, гравировка"};
        if (t.ru().contains("настенные")) mt = new String[]{"Дерево", "шпон, резьба"};
        c.materialRu = mt[0].toLowerCase(Locale.ROOT);
        c.techniqueRu = mt[1];
        c.add("Корпус — " + mt[0].toLowerCase(Locale.ROOT) + ", " + mt[1]);
        c.add("Механизм " + W.pick(r, new String[]{"с боем на колокол", "с боем на спираль", "без боя", "с боем каждые полчаса"})
                + ", завод " + W.pick(r, new String[]{"восьмидневный", "недельный", "суточный"}));
        c.add("Циферблат " + W.pick(r, new String[]{"эмалевый, с римскими цифрами", "эмалевый, с арабскими цифрами",
                "латунный, с серебрёным кольцом цифр", "бронзовый, с накладными цифрами"}));
        if (m != null) {
            c.signed = true;
            c.add("Корпус — работа " + m.nameGen());
        } else {
            c.signed = false;
        }
        List<Maker> ws = c.lt.makers().of(MakerKind.WATCHMAKER);
        Maker w = ws.get(r.nextInt(ws.size()));
        if (r.chance(0.5) && w.yearFrom() <= y) c.add("Механизм подписан: " + w.name() + ", номер " + r.between(100, 99999));
        else c.add("На механизме — номер " + r.between(100, 99999));
        c.add("Высота " + cmH(c, t.ru().contains("каретные") ? 10 : 25, t.ru().contains("настенные") ? 110 : 65) + " см");
        c.add(Common.placeDate(c.country, d));
        about(c, m);
        c.defects = "CLOCK";
        finish(c, CLOCK_NOTE, 0.6, 0.02);
    }

    // ---------------------------------------------------------------- ковры и текстиль

    private static final V.P[] CARPET_ITEM = p("carpet.item");
    private static final String[] CARPET_FIELD = l("carpet.field"), CARPET_BORDER = l("carpet.border"), CARPET_NOTE = l("carpet.note");

    static void fillCarpet(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        Common.Dated d = dated(c, 1780, 1990);
        V.P t = V.pick(r, CARPET_ITEM);
        c.objectRu = t.ru();
        c.objectEn = t.en();
        String ru = t.ru();
        boolean pile = ru.startsWith("Ковёр") || ru.startsWith("Дорожка");
        boolean tapestry = ru.startsWith("Гобелен");
        String mat = pile ? W.pick(r, new String[]{"Шерсть", "Шерсть, хлопок", "Шёлк", "Шерсть"}) : tapestry ? "Шерсть, шёлк"
                : W.pick(r, new String[]{"Лён", "Хлопок", "Шёлк", "Лён, шёлк"});
        String tech = pile ? "ручное ворсовое ткачество" : tapestry ? "шпалерное ткачество" : "вышивка гладью";
        c.materialRu = mat.toLowerCase(Locale.ROOT);
        c.techniqueRu = tech;
        c.add(mat + "; " + tech);
        if (pile) c.add("Плотность около " + Coins.thousands(r.between(8, 60) * 1000L) + " узлов на квадратный метр");
        c.add(W.cap(W.pick(r, CARPET_FIELD)) + "; " + W.pick(r, CARPET_BORDER));
        int a, b;
        if (ru.startsWith("Дорожка")) {
            a = r.between(250, 600);
            b = r.between(70, 110);
        } else if (pile) {
            a = r.between(120, 420);
            b = r.between(80, 300);
        } else {
            a = r.between(60, 240);
            b = r.between(40, 180);
        }
        c.heightMm = a * 10;
        c.widthMm = b * 10;
        c.add("Размер " + a + " × " + b + " см");
        if (m != null) {
            c.signed = r.chance(0.6);
            c.add(c.signed ? "Вытканное клеймо " + m.nameGen() + " в углу" : "Работа " + m.nameGen() + "; клейма нет");
        } else {
            c.signed = false;
        }
        c.add(origin(c, m, d));
        about(c, m);
        c.defects = "CARPET";
        finish(c, CARPET_NOTE, 0.65, 0.02);
    }
}
