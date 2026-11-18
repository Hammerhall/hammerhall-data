package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.List;

/**
 * Живопись и графика: живопись, рисунок, акварель и пастель, театральная графика и иллюстрация, эстамп (и гравюры из
 * книг), фотография, малая скульптура. Название — сюжет по жанру листа категории («Пейзаж с мельницей», «Натюрморт с
 * сиренью и яблоками», «Портрет дамы в голубом») по-русски и по-английски; описание — техника и размер, автор и
 * атрибуция, подпись, сюжет и манера, о художнике, рама и надписи. Словарь — {@code vocab/painting.txt}.
 */
final class Painting {
    private Painting() {
    }

    private static final String F = "painting";

    private static V.P[] p(String s) {
        return V.pairs(F, s);
    }

    private static String[] l(String s) {
        return V.list(F, s);
    }

    static final String[] GENRES = {"landscape", "portrait", "still_life", "genre", "marine", "animal", "city",
            "interior", "abstract"};
    private static final double[] GENRE_W = {30, 14, 14, 12, 7, 5, 9, 3, 6};

    // ---------------------------------------------------------------- названия

    private static final V.P[] LAND_BASE = p("land.base"), LAND_WITH = p("land.with"), LAND_TIME = p("land.time"),
            LAND_PLACE = p("land.place"), LAND_PLACE_RU = p("land.place.ru"), LAND_PLACE_ABROAD = p("land.place.abroad"),
            LAND_ALONE = p("land.alone"), MARINE = p("marine"), MARINE_PLACE = p("marine.place"), STILL_WITH = p("still.with"),
            FLOWERS = p("flowers"), FLOWERS_WHERE = p("flowers.where"), STILL_ALONE = p("still.alone"),
            PORTRAIT_F = p("portrait.f"), PORTRAIT_M = p("portrait.m"), PORTRAIT_C = p("portrait.c"), ATTR_F = p("attr.f"),
            ATTR_M = p("attr.m"), ATTR_C = p("attr.c"), PORTRAIT_ALONE = p("portrait.alone"), GENRE = p("genre"),
            ANIMAL = p("animal"), CITY_NAMES = p("city.names"), CITY_SCENE = p("city.scene"), CITY_ALONE = p("city.alone"),
            INTERIOR = p("interior"), ABSTRACT = p("abstract"), PLAYS = p("plays"), TALES = p("tales"), SERIES = p("series"),
            DRAWING_ALONE = p("drawing.alone"), BOOK_PLATE = p("book.plate"), PHOTO_SUBJECT = p("photo.subject"),
            SCULPTURE_SUBJECT = p("sculpture.subject");

    private static V.P pk(Rng r, V.P[] a) {
        return a[r.nextInt(a.length)];
    }

    private static V.P sw(V.P x) {
        return new V.P(W.s(x.ru()), x.en());
    }

    private static V.P with(String ru, String en, V.P x) {
        return new V.P(ru + x.ru(), en + x.en());
    }

    /** Жанр: у живописи — из листа категории, у графики — по специализации художника или по долям. */
    static String genre(Ctx c) {
        if (c.variant != null) for (String g : GENRES) if (g.equals(c.variant)) return g;
        Rng r = c.r;
        Maker m = c.maker;
        // абстракционист пишет абстракции; у прочих мастеров абстракции нет; без мастера — не раньше 1910-х
        if (Makers.abstractArtist(m) && c.yearFrom >= 1910) return "abstract";
        boolean absOk = m == null && c.yearFrom >= 1910;
        Makers.Specialty s = m == null || m.kind() != MakerKind.ARTIST ? null : Makers.specialty(m.specialty());
        if (s != null && r.chance(0.55)) {
            for (String g : GENRES) if (g.equals(s.genre()) && !g.equals("abstract")) return g;
        }
        String g;
        do {
            g = GENRES[r.weighted(GENRE_W)];
        } while (g.equals("abstract") && !absOk);
        return g;
    }

    static V.P title(Ctx c, String genre) {
        Rng r = c.r;
        return switch (genre) {
            case "landscape" -> {
                double u = r.nextDouble();
                if (u < 0.35) {
                    V.P b = pk(r, LAND_BASE), w = pk(r, LAND_WITH);
                    yield new V.P(b.ru() + " " + W.s(w.ru()), b.en() + " with " + w.en());
                }
                if (u < 0.7) {
                    boolean ru = c.country == Country.RU;
                    double v = r.nextDouble();
                    V.P[] places = v < 0.5 ? LAND_PLACE : ru ? (v < 0.85 ? LAND_PLACE_RU : LAND_PLACE_ABROAD) : LAND_PLACE_ABROAD;
                    V.P t = pk(r, LAND_TIME), pl = pk(r, places);
                    yield new V.P(t.ru() + " " + pl.ru(), t.en() + " " + pl.en());
                }
                yield pk(r, LAND_ALONE);
            }
            case "marine" -> {
                if (r.chance(0.6)) yield pk(r, MARINE);
                V.P t = pk(r, LAND_TIME), pl = pk(r, MARINE_PLACE);
                yield new V.P(t.ru() + " " + pl.ru(), t.en() + " " + pl.en());
            }
            case "still_life" -> {
                double u = r.nextDouble();
                if (u < 0.35) yield with("Натюрморт ", "Still Life with ", sw(pk(r, STILL_WITH)));
                if (u < 0.55) {
                    V.P a = pk(r, STILL_WITH), b = pk(r, STILL_WITH);
                    yield a.equals(b) ? with("Натюрморт ", "Still Life with ", sw(a))
                            : new V.P("Натюрморт " + W.s(a.ru()) + " и " + b.ru(), "Still Life with " + a.en() + " and " + b.en());
                }
                if (u < 0.85) {
                    V.P f = pk(r, FLOWERS), w = pk(r, FLOWERS_WHERE);
                    yield new V.P(f.ru() + " " + w.ru(), f.en() + " " + w.en());
                }
                yield pk(r, STILL_ALONE);
            }
            case "portrait" -> {
                double u = r.nextDouble();
                V.P[] who, attr;
                double pa;
                if (u < 0.4) {
                    who = PORTRAIT_F;
                    attr = ATTR_F;
                    pa = 0.6;
                } else if (u < 0.7) {
                    who = PORTRAIT_M;
                    attr = ATTR_M;
                    pa = 0.5;
                } else if (u < 0.82) {
                    who = PORTRAIT_C;
                    attr = ATTR_C;
                    pa = 0.6;
                } else {
                    yield pk(r, PORTRAIT_ALONE);
                }
                V.P w = pk(r, who);
                if (!r.chance(pa)) yield with("Портрет ", "Portrait of ", w);
                V.P a = pk(r, attr);
                yield new V.P("Портрет " + w.ru() + " " + a.ru(), "Portrait of " + w.en() + " " + a.en());
            }
            case "genre" -> pk(r, GENRE);
            case "animal" -> pk(r, ANIMAL);
            case "city" -> {
                if (!r.chance(0.55)) yield pk(r, CITY_ALONE);
                V.P n = pk(r, CITY_NAMES), s = pk(r, CITY_SCENE);
                yield new V.P(n.ru() + ". " + s.ru(), n.en() + ". " + s.en());
            }
            case "interior" -> pk(r, INTERIOR);
            default -> {
                V.P t = pk(r, ABSTRACT);
                if (!t.ru().equals("Без названия") && r.chance(0.45)) {
                    int n = r.between(1, 40);
                    t = new V.P(t.ru() + " № " + n, t.en() + " No. " + n);
                }
                yield t;
            }
        };
    }

    private static V.P quoted(String ru, String en, V.P x) {
        return new V.P(ru + "«" + x.ru() + "»", en + "“" + x.en() + "”");
    }

    private static V.P drawingTitle(Ctx c, V.P subject) {
        Rng r = c.r;
        switch (c.variant == null ? "" : c.variant) {
            case "theatre" -> {
                return r.chance(0.55) ? quoted("Эскиз декорации к спектаклю ", "Set Design for ", pk(r, PLAYS))
                        : quoted("Эскиз костюма к спектаклю ", "Costume Design for ", pk(r, PLAYS));
            }
            case "illustration" -> {
                return r.chance(0.6) ? quoted("Иллюстрация к повести ", "Illustration for ", pk(r, TALES))
                        : quoted("Заставка к повести ", "Headpiece for ", pk(r, TALES));
            }
            default -> {
                double u = r.nextDouble();
                if (u < 0.15) return quoted("Этюд к картине ", "Study for ", subject);
                if (u < 0.25 && !"abstract".equals(c.genre)) {
                    c.genre = "sketch";
                    return pk(r, DRAWING_ALONE);
                }
                return subject;
            }
        }
    }

    // ---------------------------------------------------------------- сюжет и манера

    private static final String[] LAND_FG = l("land.fg"), LAND_BG = l("land.bg"), LIGHT = l("light"), MANNER = l("manner"),
            PORTRAIT_POSE = l("portrait.pose"), PORTRAIT_DRESS = l("portrait.dress"), STILL_CLOTH = l("still.cloth"),
            STILL_OBJ = l("still.obj"), FLOWERS_NOTE = l("flowers.note"), GENRE_NOTE = l("genre.note"),
            MARINE_NOTE = l("marine.note"), CITY_NOTE = l("city.note"), ANIMAL_NOTE = l("animal.note"),
            INTERIOR_NOTE = l("interior.note"), ABSTRACT_NOTE = l("abstract.note"), PORTRAIT_NOTE = l("portrait.note"),
            OPEN_OIL = l("open.oil"), OPEN_OIL_SMALL = l("open.oil.small"), OPEN_OIL_LARGE = l("open.oil.large"), OPEN_PAPER = l("open.paper"), NOTE = l("note.specialist"), NOTE_OIL = l("note.oil"), NOTE_ANY = l("note.any"), FRAME = l("frame"),
            PAPER_FRAME = l("paper.frame"), LABELS = l("labels"), PAPER_LABELS = l("paper.labels"), CORNER = l("corner"),
            PAPER_MANNER = l("paper.manner"), PRINT_NOTE = l("print.note"), REVERSE = l("reverse"),
            PHOTO_NOTE = l("photo.note"), PHOTO_MOUNT = l("photo.mount"), PHOTO_SIGN = l("photo.sign"),
            SCULPT_BASE = l("sculpture.base"), SCULPT_NOTE = l("sculpture.note"), SCULPT_CAST = l("sculpture.cast"),
            THEATRE_NOTE = l("theatre.note"), ILLUSTRATION_NOTE = l("illustration.note"), SKETCH_NOTE = l("sketch.note"),
            BOOK_PLATE_NOTE = l("book.plate.note");

    static String subject(Ctx c, String genre) {
        Rng r = c.r;
        if ("theatre".equals(c.variant)) return W.pick(r, THEATRE_NOTE);
        if ("sketch".equals(c.genre)) return W.pick(r, SKETCH_NOTE);
        if ("book_plate".equals(c.variant)) return W.pick(r, BOOK_PLATE_NOTE);
        if ("illustration".equals(c.variant)) return W.pick(r, ILLUSTRATION_NOTE);
        return switch (genre) {
            case "landscape" -> r.chance(0.6)
                    ? "На переднем плане — " + W.pick(r, LAND_FG) + ", вдали — " + W.pick(r, LAND_BG)
                    : "Пейзаж написан " + W.pick(r, LIGHT);
            case "portrait" -> r.chance(0.75) ? "Модель изображена " + W.pick(r, PORTRAIT_POSE)
                    + (r.chance(0.6) ? ", " + W.pick(r, PORTRAIT_DRESS) : "") : W.pick(r, PORTRAIT_NOTE);
            case "still_life" -> {
                // букет — о цветах; натюрморт с предметами — о предметах на столе
                boolean flowers = false;
                if (c.objectRu != null) for (V.P f : FLOWERS) if (c.objectRu.startsWith(f.ru() + " ")) flowers = true;
                if (flowers) yield W.pick(r, FLOWERS_NOTE);
                String a = W.pick(r, STILL_OBJ), b = W.pick(r, STILL_OBJ);
                yield "На столе, покрытом " + W.pick(r, STILL_CLOTH) + ", — " + a + (a.equals(b) ? "" : " и " + b);
            }
            case "genre" -> W.pick(r, GENRE_NOTE);
            case "marine" -> W.pick(r, MARINE_NOTE);
            case "city" -> W.pick(r, CITY_NOTE);
            case "animal" -> W.pick(r, ANIMAL_NOTE);
            case "interior" -> W.pick(r, INTERIOR_NOTE);
            default -> W.pick(r, ABSTRACT_NOTE);
        };
    }

    /** Второе предложение о сюжете — другого рода, чем первое. */
    static String subject2(Ctx c, String genre) {
        Rng r = c.r;
        return switch (genre) {
            case "landscape", "marine", "city" -> "Работа выдержана " + W.pick(r, LIGHT);
            case "portrait" -> W.pick(r, PORTRAIT_NOTE);
            default -> null;
        };
    }

    // ---------------------------------------------------------------- техника, размер

    private static String oilTechnique(Rng r, int y) {
        if (y < 1800) return W.pick(r, new String[]{"Холст, масло", "Холст, масло", "Холст, масло", "Доска, масло",
                "Медь, масло", "Холст на картоне, масло"});
        if (y < 1900) return W.pick(r, new String[]{"Холст, масло", "Холст, масло", "Холст, масло", "Холст на картоне, масло",
                "Картон, масло", "Доска, масло"});
        if (y < 1950) return W.pick(r, new String[]{"Холст, масло", "Холст, масло", "Картон, масло", "Холст на картоне, масло",
                "Фанера, масло", "Холст, темпера", "Картон, темпера"});
        return W.pick(r, new String[]{"Холст, масло", "Холст, масло", "Оргалит, масло", "Картон, масло", "Холст, акрил",
                "Холст, смешанная техника", "Холст, темпера"});
    }

    private static String drawingTechnique(Rng r, String variant, int y) {
        String[] base = switch (variant == null ? "" : variant) {
            case "watercolour" -> new String[]{"Бумага, акварель", "Бумага, акварель", "Бумага, гуашь", "Бумага, акварель, белила",
                    "Бумага, акварель, графитный карандаш", "Картон, гуашь", "Бумага, акварель, гуашь"};
            case "pastel" -> new String[]{"Бумага, пастель", "Бумага на картоне, пастель", "Тонированная бумага, пастель",
                    "Картон, пастель"};
            case "theatre", "illustration" -> new String[]{"Бумага, гуашь", "Бумага, акварель, тушь", "Картон, гуашь, бронзовая краска",
                    "Бумага, тушь, перо", "Бумага, акварель, белила, графитный карандаш", "Бумага, темпера"};
            default -> new String[]{"Бумага, графитный карандаш", "Бумага, итальянский карандаш", "Бумага, сангина",
                    "Бумага, тушь, перо", "Бумага, тушь, кисть", "Бумага, уголь",
                    "Тонированная бумага, итальянский карандаш, мел", "Бумага, сепия"};
        };
        String t = W.pick(r, base);
        if ("drawing".equals(variant) && y >= 1890 && r.chance(0.08)) t = "Бумага, цветные карандаши";
        if ("drawing".equals(variant) && y >= 1950 && r.chance(0.08)) t = "Бумага, смешанная техника";
        return t;
    }

    private static String printTechnique(Rng r, String variant, int y) {
        return switch (variant == null ? "" : variant) {
            case "lithograph" -> y >= 1890 && r.chance(0.3) ? "Автолитография" : W.pick(r, new String[]{"Литография", "Цветная литография",
                    "Литография с ручной раскраской"});
            case "woodcut" -> y >= 1905 && r.chance(0.4) ? W.pick(r, new String[]{"Линогравюра", "Цветная линогравюра"})
                    : W.pick(r, new String[]{"Ксилография", "Цветная ксилография", "Гравюра на дереве"});
            case "screenprint" -> W.pick(r, new String[]{"Шелкография", "Цветная шелкография", "Шелкография в несколько цветов"});
            case "book_plate" -> W.pick(r, new String[]{"Гравюра резцом", "Офорт с ручной раскраской", "Литография",
                    "Цветная литография", "Гравюра на меди с ручной раскраской", "Хромолитография"});
            default -> W.pick(r, new String[]{"Офорт", "Офорт, акватинта", "Гравюра резцом", "Меццо-тинто", "Сухая игла",
                    "Офорт, сухая игла", "Мягкий лак"});
        };
    }

    private static final String[] PRINT_PAPER = {"бумага", "бумага", "бумага верже", "японская бумага", "плотная бумага", "китайская бумага"};

    private static final int[][] CANVAS = {{24, 30}, {27, 35}, {33, 41}, {38, 46}, {46, 55}, {50, 61}, {54, 65}, {60, 73},
            {65, 81}, {73, 92}, {81, 100}, {89, 116}, {97, 130}, {114, 146}, {35, 50}, {40, 60}, {50, 70}, {70, 100}};

    /** Размер, см: высота × ширина; портрет — вертикальный, пейзаж — горизонтальный. */
    private static double[] oilSize(Rng r, String genre, String tech) {
        double h, w;
        if (tech.startsWith("Доска") || tech.startsWith("Медь") || (tech.startsWith("Картон") && r.chance(0.6))) {
            h = W.half(r, 14, 45);
            w = W.half(r, 18, 55);
        } else if (r.chance(0.7)) {
            int[] s = CANVAS[r.nextInt(CANVAS.length)];
            h = s[0];
            w = s[1];
            if (r.chance(0.3)) {
                h += W.half(r, -1.5, 1.5);
                w += W.half(r, -1.5, 1.5);
            }
        } else {
            h = W.half(r, 25, 120);
            w = W.half(r, 30, 150);
        }
        boolean vertical = genre.equals("portrait") || genre.equals("interior") || (genre.equals("still_life") && r.chance(0.6))
                || (genre.equals("abstract") && r.chance(0.5));
        if (vertical == (h < w)) {
            double t = h;
            h = w;
            w = t;
        }
        return new double[]{h, w};
    }

    private static String size(double h, double w) {
        return W.cm(h) + " × " + W.cm(w) + " см";
    }

    /** «Холст, масло» → материал «холст», техника «масло». */
    private static void split(Ctx c, String tech) {
        int i = tech.indexOf(", ");
        c.materialRu = tech.substring(0, i).toLowerCase(java.util.Locale.ROOT);
        c.techniqueRu = tech.substring(i + 2);
    }

    // ---------------------------------------------------------------- подпись

    private static String signature(Ctx c, Maker m, int year) {
        Rng r = c.r;
        if (c.attribution != Attribution.BY || m == null) {
            c.signed = false;
            if (m == null) return W.pick(r, new String[]{"Без подписи", "Подпись не обнаружена",
                    "На обороте — неразборчивая надпись чернилами"});
            return W.pick(r, new String[]{"Подпись отсутствует; атрибуция — по стилистическим признакам",
                    "Без подписи", "На обороте — старая надпись карандашом с фамилией художника, не авторская"});
        }
        c.signed = true;
        String sig = m.mark();
        boolean cyr = m.signatureRu() != null;
        double u = r.nextDouble();
        String corner = W.pick(r, CORNER);
        if (u < 0.08) {
            c.signed = false;
            return "Без подписи; на обороте — надпись с именем автора";
        }
        if (u < 0.18) return "Монограмма «" + m.person().monogram() + "» в " + corner;
        if (u < 0.28) return "На обороте — авторская надпись с названием, подписью и датой";
        if (!cyr) return u < 0.6 ? "Подписано латиницей в " + corner : "Подписано и датировано латиницей в " + corner + ": " + year;
        if (u < 0.6) return "Подписано в " + corner + ": «" + sig + "»";
        if (u < 0.85) return "Подписано и датировано в " + corner + ": «" + sig + ". " + year + "»";
        return "Подпись и дата в " + corner + ": «" + sig + " " + W.yy(year) + "»";
    }

    private static String printSignature(Ctx c, String tech) {
        Rng r = c.r;
        if (c.maker == null || c.attribution != Attribution.BY) {
            c.signed = false;
            return c.attribution == Attribution.AFTER ? "Подпись художника — в доске, под изображением" : "Без подписи";
        }
        c.signed = true;
        double u = r.nextDouble();
        if (u < 0.4) return "Подписано автором карандашом на нижнем поле справа";
        if (u < 0.6 && tech.contains("итограф")) return "Подпись на камне";
        if (u < 0.6 && (tech.contains("ксилограф") || tech.contains("дереве"))) return "Подписано в доске";
        if (u < 0.75) return "Подпись и номер оттиска карандашом на нижнем поле";
        return "Монограмма «" + c.maker.person().monogram() + "» в доске; на поле — авторская подпись карандашом";
    }

    private static String edition(Rng r) {
        double u = r.nextDouble();
        if (u < 0.45) {
            int n = r.between(10, 150);
            return "Оттиск " + r.between(1, n) + "/" + n;
        }
        if (u < 0.6) return "Пробный оттиск";
        if (u < 0.72) return "Авторский оттиск";
        return "Оттиск из тиража";
    }

    // ---------------------------------------------------------------- о художнике

    private static String period(Maker m, int year) {
        int a = m.activeFrom(), b = m.activeTo(Makers.NOW_YEAR);
        double t = b > a ? (year - a) / (double) (b - a) : 0.5;
        return t < 0.33 ? "раннему" : t < 0.7 ? "зрелому" : "позднему";
    }

    /** Строка об авторе и атрибуции — с именем мастера (если он известен). */
    private static String author(Ctx c, Maker m, Common.Dated d) {
        String unknown = switch (c.kind) {
            case PHOTO -> "Неизвестный фотограф";
            case SCULPTURE -> "Неизвестный скульптор";
            default -> "Неизвестный художник";
        };
        if (m == null) return unknown + " " + c.country.adjFemGen + " школы, " + d.text();
        Rng r = c.r;
        return switch (c.attribution) {
            case BY -> {
                Person p = m.person();
                String spec = fittingSpecialty(c, m);
                String who = p.female() && c.kind != Kind.PHOTO && c.kind != Kind.SCULPTURE ? m.country().adjFem + " художница"
                        : m.country().adjMasc + " " + spec;
                String school = m.school() == null || !r.chance(0.6) ? ""
                        : ", представитель" + (p.female() ? "ница " : " ") + Makers.movementGen(m.school());
                yield m.name() + " (" + m.years() + ") — " + who + school;
            }
            case ATTRIBUTED -> "Приписывается: " + m.name() + " (" + m.years() + ")";
            case WORKSHOP -> "Мастерская " + m.nameGen() + " (" + m.years() + ")";
            case CIRCLE -> "Круг " + m.nameGen() + " (" + m.years() + ")";
            case FOLLOWER -> "Последователь " + m.nameGen() + ", " + d.text();
            case MANNER -> "В манере " + m.nameGen() + ", " + d.text();
            case AFTER -> c.kind == Kind.SCULPTURE ? "По модели " + m.nameGen() + " (" + m.years() + "), поздняя отливка"
                    : "По оригиналу " + m.nameGen() + " (" + m.years() + ")";
            default -> unknown + " " + c.country.adjFemGen + " школы, " + d.text();
        };
    }

    /**
     * Специализация мастера в строке об авторе — только если она согласна с вещью (пейзажист — у пейзажа, гравёр — у
     * эстампа, акварелист — у акварели); иначе — «художник».
     */
    static String fittingSpecialty(Ctx c, Maker m) {
        if (m.specialty() == null) return c.kind == Kind.PHOTO ? "фотограф" : c.kind == Kind.SCULPTURE ? "скульптор" : "художник";
        if (m.kind() != MakerKind.ARTIST) return m.specialty();
        Makers.Specialty s = Makers.specialty(m.specialty());
        if (s == null) return "художник";
        boolean fits = switch (s.genre()) {
            case "graphic" -> c.kind == Kind.DRAWING || c.kind == Kind.PRINT;
            case "illustrator" -> c.kind == Kind.PRINT || "illustration".equals(c.variant) || "theatre".equals(c.variant);
            case "printmaker" -> c.kind == Kind.PRINT;
            case "watercolor" -> "watercolour".equals(c.variant);
            default -> s.genre().equals(c.genre) && (c.kind == Kind.PAINTING_OIL || c.kind == Kind.DRAWING || c.kind == Kind.PRINT);
        };
        return fits ? m.specialty() : "художник";
    }

    private static String aboutArtist(Ctx c, Maker m, int year) {
        Rng r = c.r;
        Person p = m.person();
        double u = r.nextDouble();
        String art = c.kind == Kind.PHOTO ? "Фотограф" : c.kind == Kind.SCULPTURE ? "Скульптор" : p.female() ? "Художница" : "Художник";
        String verb = p.female() ? "а" : "";
        if (u < 0.3) return "Характерная работа " + m.nameGen() + " " + W.decadeGen(year);
        if (u < 0.5) return "Работа относится к " + period(m, year) + " периоду творчества " + m.nameGen();
        if (u < 0.75 && c.kind != Kind.PHOTO) {
            Maker t = teacher(c, m);
            if (t != null) return art + " учил" + (p.female() ? "ась" : "ся") + " у " + t.person().initialsGen()
                    + (r.chance(0.5) && t.city() != null ? " в " + t.city().prep() : "");
        }
        if (m.city() != null) return art + " работал" + verb + " в " + m.city().prep()
                + (r.chance(0.3) ? " и подолгу жил" + verb + " за городом, где написаны многие этюды" : "");
        return "Работа характерна для " + m.nameGen();
    }

    /** Учитель — мастер того же вида и страны, старше на 18–45 лет. */
    private static Maker teacher(Ctx c, Maker m) {
        List<Maker> same = c.lt.makers().of(m.kind());
        for (int i = 0; i < 25; i++) {
            Maker t = same.get(c.r.nextInt(same.size()));
            int d = m.yearFrom() - t.yearFrom();
            if (t != m && t.country() == m.country() && d >= 18 && d <= 45) return t;
        }
        return null;
    }

    // ---------------------------------------------------------------- лот

    private static final Country[] UNKNOWN_SCHOOL = {Country.RU, Country.RU, Country.RU, Country.RU, Country.RU,
            Country.RU, Country.FR, Country.DE, Country.IT, Country.NL};

    /** Атрибуция при известном мастере: чаще «работа мастера», иногда круг, мастерская, последователь. */
    private static Attribution attribution(Ctx c) {
        if (c.maker == null) return Attribution.UNKNOWN;
        double u = c.r.nextDouble();
        return switch (c.kind) {
            case PHOTO -> u < 0.05 ? Attribution.ATTRIBUTED : Attribution.BY;
            case SCULPTURE -> u < 0.04 ? Attribution.ATTRIBUTED : u < 0.07 ? Attribution.WORKSHOP : u < 0.12 ? Attribution.AFTER : Attribution.BY;
            case PRINT -> u < 0.07 ? Attribution.AFTER : Attribution.BY;
            default -> u < 0.03 ? Attribution.ATTRIBUTED : u < 0.07 ? Attribution.CIRCLE : u < 0.09 ? Attribution.WORKSHOP
                    : u < 0.11 ? Attribution.FOLLOWER : u < 0.13 ? Attribution.MANNER : u < 0.14 ? Attribution.AFTER : Attribution.BY;
        };
    }

    private static Common.Dated dating(Ctx c, int lo) {
        Rng r = c.r;
        Maker m = c.maker;
        if (Makers.abstractArtist(m)) lo = Math.max(lo, 1910);
        int[] sp = Common.span(m, lo, Makers.NOW_YEAR - 1);
        if (m == null) return Common.date(r, Math.max(lo, 1720), Math.max(lo, 1960), 0, 0.15, 0.25);
        if (sp[0] < lo) sp[0] = Math.min(lo, sp[1]);
        if (c.attribution != Attribution.BY) return Common.date(r, sp[0], Math.min(sp[1] + 20, Makers.NOW_YEAR - 1), 0, 0.2, 0.3);
        return Common.date(r, sp[0], sp[1], 0.55, 0.15, 0.2);
    }

    /** Датировка — если её ещё нет в тексте (в подписи, в строке об авторе). */
    private static void dateLine(Ctx c, Common.Dated d) {
        String sofar = String.join(" ", c.desc);
        if (d.exact() ? !sofar.contains(Integer.toString(c.year)) && !sofar.contains(" " + W.yy(c.year) + "»")
                : !sofar.contains(d.text())) {
            c.add(d.line());
        }
    }

    static void fill(Ctx c) {
        switch (c.kind) {
            case PHOTO -> photo(c);
            case SCULPTURE -> sculpture(c);
            default -> picture(c);
        }
    }

    private static void picture(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        c.attribution = attribution(c);
        Common.Dated d = dating(c, Math.max(1700, LotTexts.minYear(c.cat)));
        c.dated(d);
        c.country = m != null ? m.country() : UNKNOWN_SCHOOL[r.nextInt(UNKNOWN_SCHOOL.length)];
        c.originPlaceRu = m != null && c.attribution == Attribution.BY && m.city() != null && r.chance(0.5) ? m.city().name() : c.country.nameRu;
        c.fromArtist = c.attribution == Attribution.BY;
        String genre = genre(c);
        c.genre = genre;
        V.P subj = title(c, genre);
        String tech;
        String sizeLine;
        String v = c.variant;
        switch (c.kind) {
            case PAINTING_OIL -> {
                tech = oilTechnique(r, c.year);
                double[] s = oilSize(r, genre, tech);
                c.sizeCm(s[0], s[1], null);
                sizeLine = size(s[0], s[1]);
                split(c, tech);
                c.objectRu = subj.ru();
                c.objectEn = subj.en();
            }
            case DRAWING -> {
                tech = drawingTechnique(r, v, c.year);
                double h = W.half(r, 12, 65), w = W.half(r, 12, 65);
                if (genre.equals("portrait") && h < w) {
                    double t = h;
                    h = w;
                    w = t;
                }
                c.sizeCm(h, w, null);
                sizeLine = size(h, w);
                split(c, tech);
                V.P t = drawingTitle(c, subj);
                c.objectRu = t.ru();
                c.objectEn = t.en();
            }
            default -> {
                tech = printTechnique(r, v, c.year);
                String paper = W.pick(r, PRINT_PAPER);
                c.materialRu = paper;
                c.techniqueRu = tech.toLowerCase(java.util.Locale.ROOT);
                double h = W.half(r, 12, 50), w = W.half(r, 12, 50);
                double ph = h + W.half(r, 4, 16), pw = w + W.half(r, 4, 16);
                if (r.chance(0.6)) {
                    c.sizeCm(ph, pw, null);
                    sizeLine = "Лист " + size(ph, pw) + ", оттиск " + size(h, w);
                } else {
                    c.sizeCm(h, w, null);
                    sizeLine = "Оттиск " + size(h, w);
                }
                tech = tech + ", " + paper;
                V.P t;
                if ("book_plate".equals(v)) {
                    V.P b = pk(r, BOOK_PLATE);
                    int n = r.between(1, 120);
                    t = new V.P(b.ru() + ". Лист " + n, b.en() + ". Plate " + n);
                } else if (r.chance(0.2) && !"abstract".equals(c.genre)) {
                    t = quoted("Лист из серии ", "Plate from the Series ", pk(r, SERIES));
                    c.genre = "series";
                } else {
                    t = subj;
                }
                c.objectRu = t.ru();
                c.objectEn = t.en();
            }
        }
        boolean oil = c.kind == Kind.PAINTING_OIL;
        // слова о формате и основе — по размерам и материалу: «полотно», «подрамник» — только у холста
        double side = Math.max(c.heightMm == null ? 0 : c.heightMm, c.widthMm == null ? 0 : c.widthMm) / 10.0;
        boolean canvas = "холст".equals(c.materialRu);
        if (!canvas) c.forbid.addAll(List.of("подрамник", "дублир", "полотн", "кромк"));
        if (c.materialRu == null || !c.materialRu.startsWith("холст")) c.forbid.add("холст");
        if (!canvas || side < 120) c.forbid.addAll(List.of("крупноформат", "масштабн", "большое"));
        if (side > 60) c.forbid.addAll(List.of("камерн", "небольш"));
        // 0. вступление
        if (r.chance(0.4)) {
            String[] pool = !oil ? OPEN_PAPER : canvas && side >= 120 && r.chance(0.6) ? OPEN_OIL_LARGE
                    : side <= 60 && r.chance(0.5) ? OPEN_OIL_SMALL : OPEN_OIL;
            c.add(c.pick(pool));
        }
        // 1. техника и размер
        c.add(c.kind == Kind.PRINT ? tech + ". " + sizeLine + ". " + edition(r)
                : tech + ". " + sizeLine);
        if ("book_plate".equals(v)) c.add("Лист из иллюстрированного издания, отпечатан с гравированной доски");
        // 2. автор и атрибуция
        c.add(author(c, m, d));
        // 3. подпись и датировка
        c.add(c.kind == Kind.PRINT ? printSignature(c, tech) : signature(c, m, c.year));
        dateLine(c, d);
        // 4. сюжет и манера (у листа из серии — без сюжета: о серии говорит название)
        if (!"series".equals(c.genre)) c.add(subject(c, c.genre.equals("sketch") ? "sketch" : genre));
        if (r.chance(0.35) && !"theatre".equals(v) && !"illustration".equals(v) && !"book_plate".equals(v)
                && genre.equals(c.genre)) c.add(subject2(c, genre));
        if (oil && r.chance(0.45)) c.add(W.pick(r, MANNER));
        if (c.kind == Kind.DRAWING && r.chance(0.45)) c.add(W.pick(r, PAPER_MANNER));
        if (c.kind == Kind.PRINT && r.chance(0.5)) c.add(W.pick(r, PRINT_NOTE));
        // 5. о художнике
        if (m != null && c.attribution == Attribution.BY && r.chance(0.6)) c.add(aboutArtist(c, m, c.year));
        // 6. замечание, рама, надписи
        if (r.chance(0.4)) c.add(c.pick(NOTE));
        if (oil && r.chance(0.25)) c.add(c.pick(NOTE_OIL));
        if (r.chance(0.55)) c.add(c.pick(oil ? FRAME : PAPER_FRAME));
        if (r.chance(0.2)) c.add(c.pick(oil ? LABELS : PAPER_LABELS));
        else if (r.chance(0.15)) c.add(c.pick(REVERSE));

        Condition.fill(c);
        boolean by = c.attribution == Attribution.BY;
        c.provenance = Common.provenance(c, 0.2, by ? 0.1 : 0.03, by ? 0.1 : 0.02, 0.06);
    }

    // ---------------------------------------------------------------- фотография

    private record Process(String ru, int from, int to) {
    }

    private static final Process[] PROCESSES = {new Process("Отпечаток на солевой бумаге", 1840, 1865),
            new Process("Альбуминовый отпечаток", 1855, 1905), new Process("Платинотипия", 1880, 1935),
            new Process("Гуммиарабиковая печать", 1895, 1935), new Process("Пигментный отпечаток", 1865, 1930),
            new Process("Серебряно-желатиновый отпечаток", 1890, 2026), new Process("Серебряно-желатиновый отпечаток", 1890, 2026),
            new Process("Бромосеребряный отпечаток", 1900, 1960), new Process("Цветной хромогенный отпечаток", 1955, 2026)};

    private static void photo(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        c.attribution = attribution(c);
        Common.Dated d = dating(c, 1845);
        c.dated(d);
        c.country = m != null ? m.country() : UNKNOWN_SCHOOL[r.nextInt(UNKNOWN_SCHOOL.length)];
        c.originPlaceRu = m != null && m.city() != null ? m.city().name() : c.country.nameRu;
        c.fromArtist = c.attribution == Attribution.BY;
        Process pr = null;
        for (int i = 0; i < 20 && pr == null; i++) {
            Process x = PROCESSES[r.nextInt(PROCESSES.length)];
            if (c.year >= x.from && c.year <= x.to) pr = x;
        }
        if (pr == null) pr = c.year < 1890 ? PROCESSES[1] : PROCESSES[5];
        V.P s = pk(r, PHOTO_SUBJECT);
        if (r.chance(0.3)) {
            V.P city = pk(r, CITY_NAMES);
            s = new V.P(s.ru() + ". " + city.ru(), s.en() + ". " + city.en());
        }
        c.objectRu = s.ru();
        c.objectEn = s.en();
        double h = W.half(r, 9, 50), w = W.half(r, 12, 60);
        if (r.chance(0.5)) {
            double t = h;
            h = w;
            w = t;
        }
        c.sizeCm(h, w, null);
        c.materialRu = "фотобумага";
        c.techniqueRu = pr.ru.toLowerCase(java.util.Locale.ROOT);
        String mount = W.pick(r, PHOTO_MOUNT);
        if (r.chance(0.3)) c.add(W.pick(r, OPEN_PAPER));
        c.add(pr.ru + ", фотобумага; " + mount + ". " + size(h, w));
        c.add(author(c, m, d));
        if (m != null && c.attribution == Attribution.BY) {
            c.signed = true;
            c.add(W.pick(r, PHOTO_SIGN));
        } else {
            c.signed = false;
            c.add(W.pick(r, new String[]{"Без подписи и штампов", "Подписи нет; на обороте — карандашная надпись с местом съёмки"}));
        }
        dateLine(c, d);
        c.add(W.pick(r, PHOTO_NOTE));
        if (r.chance(0.5)) c.add(W.pick(r, PHOTO_NOTE));
        if (m != null && c.attribution == Attribution.BY && r.chance(0.5)) c.add(aboutArtist(c, m, c.year));
        if (r.chance(0.3)) c.add(W.pick(r, NOTE_ANY));
        if (r.chance(0.4)) c.add(W.pick(r, PAPER_FRAME));
        dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.5, 0.04, 0.06, 0.02);
    }

    // ---------------------------------------------------------------- малая скульптура

    private record Mat(String material, String technique, int from, int to) {
    }

    private static final Mat[] MATERIALS = {new Mat("Бронза", "литьё, патинирование", 1700, 2026),
            new Mat("Бронза", "литьё, патинирование", 1700, 2026), new Mat("Бронза", "литьё, чеканка, патинирование", 1700, 2026),
            new Mat("Бронза", "литьё, частичное золочение", 1750, 1930), new Mat("Чугун", "литьё", 1810, 1960),
            new Mat("Мрамор", "резьба", 1700, 2026), new Mat("Тонированный гипс", "лепка", 1800, 2026),
            new Mat("Терракота", "лепка, обжиг", 1750, 2026), new Mat("Дерево", "резьба, тонировка", 1700, 2026),
            new Mat("Шамот", "лепка, обжиг", 1920, 2026), new Mat("Алюминий", "литьё", 1930, 2026)};

    private static void sculpture(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        c.attribution = attribution(c);
        Common.Dated d = dating(c, 1750);
        c.dated(d);
        c.country = m != null ? m.country() : UNKNOWN_SCHOOL[r.nextInt(UNKNOWN_SCHOOL.length)];
        c.originPlaceRu = m != null && m.city() != null && r.chance(0.6) ? m.city().name() : c.country.nameRu;
        c.fromArtist = c.attribution == Attribution.BY;
        Mat mat = null;
        for (int i = 0; i < 20 && mat == null; i++) {
            Mat x = MATERIALS[r.nextInt(MATERIALS.length)];
            if (c.year >= x.from && c.year <= x.to) mat = x;
        }
        if (mat == null) mat = MATERIALS[0];
        V.P s = pk(r, SCULPTURE_SUBJECT);
        c.objectRu = s.ru();
        c.objectEn = s.en();
        c.materialRu = mat.material.toLowerCase(java.util.Locale.ROOT);
        c.techniqueRu = mat.technique;
        double h = W.half(r, 10, 70);
        String sizeLine;
        if (r.chance(0.5)) {
            double w = W.half(r, 6, Math.max(8, h)), dd = W.half(r, 5, Math.max(7, h * 0.7));
            c.sizeCm(h, w, dd);
            sizeLine = "Размеры " + W.cm(h) + " × " + W.cm(w) + " × " + W.cm(dd) + " см";
        } else {
            c.sizeCm(h, null, null);
            sizeLine = "Высота " + W.cm(h) + " см";
        }
        if (r.chance(0.3)) c.add(W.pick(r, new String[]{"Камерная скульптура для интерьера", "Небольшая станковая скульптура",
                "Кабинетная скульптура"}));
        c.add(mat.material + ", " + mat.technique + ". " + sizeLine);
        c.add(author(c, m, d));
        if (m != null && c.attribution == Attribution.BY) {
            c.signed = true;
            String sig = m.signatureRu();
            c.add(sig != null && r.chance(0.5) ? "Подпись на основании: «" + sig + "»" : W.pick(r, new String[]{
                    "Подпись автора на основании", "Подпись автора и дата на тыльной стороне", "Монограмма автора на основании"}));
            if (mat.material.equals("Бронза") && r.chance(0.5)) c.add(W.pick(r, SCULPT_CAST));
        } else {
            c.signed = false;
            c.add(W.pick(r, new String[]{"Без подписи", "Подпись не обнаружена"}));
        }
        dateLine(c, d);
        c.add(W.pick(r, SCULPT_NOTE));
        if (r.chance(0.5)) c.add(W.pick(r, SCULPT_NOTE));
        c.add(W.pick(r, SCULPT_BASE));
        if (m != null && c.attribution == Attribution.BY && r.chance(0.5)) c.add(aboutArtist(c, m, c.year));
        if (r.chance(0.3)) c.add(W.pick(r, NOTE_ANY));
        dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.3, 0.06, 0.08, 0.04);
    }

    /** Убрать повторы предложений (два одинаковых замечания подряд). */
    static void dedupe(Ctx c) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>(c.desc);
        if (s.size() != c.desc.size()) {
            c.desc.clear();
            c.desc.addAll(s);
        }
    }
}
