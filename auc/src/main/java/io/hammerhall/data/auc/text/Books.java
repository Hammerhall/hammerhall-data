package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.List;
import java.util.Locale;

/**
 * Книги, рукописи, карты. Книга — библиографической записью: автор (выдуманный, из справочника), название, выходные
 * данные («Санкт-Петербург: Типография А. Ф. Волховского, 1884»), объём, формат, переплёт, особенности экземпляра;
 * заголовок — «Сухотин И. П. Путешествие по Волге. Санкт-Петербург, 1884» / «Sukhotin, I. P. A Journey along the
 * Volga. Saint Petersburg, 1884». Рукописи — дневники, письма, альбомы выдуманных людей. Карты — мира, морей, планы
 * городов, без государственных границ. Словарь — {@code vocab/books.txt}.
 */
final class Books {
    private Books() {
    }

    private static final String F = "books";

    private static V.P[] p(String s) {
        return V.pairs(F, s);
    }

    private static final V.P[] TRAVEL_DAT = p("travel.dat"), REGION_GEN = p("region.gen"), PLACE_PREP = p("place.prep"),
            PLACE_GEN = p("place.gen"), TRAVEL_ALONE = p("travel.alone"), POETRY = p("poetry"), NOVEL = p("novel"),
            PROSE_ALONE = p("prose.alone"), HISTORY_GEN = p("history.gen"), STUDY_GEN = p("study.gen"), ATLAS_GEN = p("atlas.gen"),
            COURSE_GEN = p("course.gen"), KIDS = p("kids"), HOUSE = p("house"), MUSIC = p("music"), JOURNAL = p("journal"),
            EARLY = p("early"), ART_CITY = p("art.city"), ART_ALONE = p("art.alone"), PROF = p("manuscript.prof"),
            SEA_GEN = p("sea.gen"), RIVER_GEN = p("river.gen"), CONTINENT_GEN = p("continent.gen");
    private static final String[] BINDING = V.list(F, "binding"), FEATURE = V.list(F, "feature"), BOOK_NOTE = V.list(F, "book.note"),
            PROF_NOM = V.list(F, "manuscript.prof.nom"), TOPIC = V.list(F, "manuscript.topic"), WATERMARK = V.list(F, "watermark"),
            MS_NOTE = V.list(F, "manuscript.note"), MAP_NOTE = V.list(F, "map.note"),
            MAP_NOTE_SEA = V.list(F, "map.note.sea"), MAP_NOTE_SKY = V.list(F, "map.note.celestial");

    private static V.P pre(String ru, String en, V.P x) {
        return new V.P(ru + x.ru(), en + x.en());
    }

    private static V.P bookTitle(Ctx c, String g, int y) {
        Rng r = c.r;
        return switch (g) {
            case "early" -> V.pick(r, EARLY);
            case "travel" -> {
                double u = r.nextDouble();
                if (u < 0.35) yield pre("Путешествие по ", "A Journey ", V.pick(r, TRAVEL_DAT));
                if (u < 0.5) yield pre("Очерки ", "Sketches of ", V.pick(r, REGION_GEN));
                if (u < 0.65) yield pre("Год в ", "A Year in ", V.pick(r, PLACE_PREP));
                if (u < 0.8) yield pre("Письма из ", "Letters from ", V.pick(r, PLACE_GEN));
                yield V.pick(r, TRAVEL_ALONE);
            }
            case "poetry" -> V.pick(r, POETRY);
            case "prose" -> {
                double u = r.nextDouble();
                if (u < 0.45) {
                    V.P n = V.pick(r, NOVEL);
                    yield new V.P(n.ru() + ": роман", n.en() + ": A Novel");
                }
                if (u < 0.65) {
                    V.P n = V.pick(r, NOVEL);
                    yield new V.P(n.ru() + ": повесть", n.en() + ": A Novella");
                }
                if (u < 0.8) yield V.pick(r, PROSE_ALONE);
                int t = r.between(2, 12);
                yield new V.P("Собрание сочинений в " + t + " томах", "Collected Works in " + t + " Volumes");
            }
            case "science" -> {
                double u = r.nextDouble();
                if (u < 0.25) yield pre("Очерки по истории ", "Essays on the History of ", V.pick(r, HISTORY_GEN));
                if (u < 0.45) yield pre("Руководство к изучению ", "A Guide to the Study of ", V.pick(r, STUDY_GEN));
                if (u < 0.65) yield pre("Атлас ", "Atlas of ", V.pick(r, ATLAS_GEN));
                if (u < 0.8) yield pre("Полный курс ", "A Complete Course of ", V.pick(r, COURSE_GEN));
                yield pre("Флора ", "Flora of ", V.pick(r, REGION_GEN));
            }
            case "art" -> {
                if (r.chance(0.35)) {
                    City city = Nation.RU.city(r);
                    V.P a = V.pick(r, ART_CITY);
                    yield new V.P(a.ru() + " " + city.gen(), a.en() + " " + city.en());
                }
                if (r.chance(0.2)) yield pre("Старинные усадьбы ", "Old Country Houses of ", V.pick(r, REGION_GEN));
                yield V.pick(r, ART_ALONE);
            }
            case "children" -> {
                V.P k = V.pick(r, KIDS);
                yield y < 1890 && (k.ru().contains("Книжка") || k.ru().contains("паровоза")) ? KIDS[1] : k;
            }
            case "house" -> r.chance(0.25) ? new V.P("Хозяйственный календарь на " + (y + 1) + " год", "Household Almanac for " + (y + 1))
                    : V.pick(r, HOUSE);
            case "music" -> V.pick(r, MUSIC);
            default -> {
                V.P j = V.pick(r, JOURNAL);
                yield new V.P("Журнал «" + j.ru() + "» за " + y + " год, комплект", "“" + j.en() + "”, Complete Year " + y);
            }
        };
    }

    /** Особенность экземпляра — своего времени (хромолитография — с 1840-х, фототипия — с 1870-х). */
    private static String feature(Rng r, int y) {
        for (int i = 0; i < 10; i++) {
            String f = W.pick(r, FEATURE);
            if (f.contains("Хромолитограф") && y < 1840 || f.contains("фототип") && y < 1870) continue;
            return f;
        }
        return "Первое издание";
    }

    /** Формат: «8° (23 × 15 см)»; размеры — в поля вещи. */
    private static String format(Ctx c, String g) {
        Rng r = c.r;
        double u = r.nextDouble();
        String f;
        int h, w;
        if (g.equals("art") || g.equals("science") && u < 0.3 || u < 0.08) {
            f = "in-folio";
            h = r.between(38, 50);
            w = r.between(27, 34);
        } else if (u < 0.3) {
            f = "4°";
            h = r.between(27, 34);
            w = r.between(20, 26);
        } else if (u < 0.8) {
            f = "8°";
            h = r.between(20, 25);
            w = r.between(13, 17);
        } else if (u < 0.93) {
            f = "12°";
            h = r.between(16, 19);
            w = r.between(10, 12);
        } else {
            f = "16°";
            h = r.between(13, 16);
            w = r.between(9, 11);
        }
        c.sizeCm((double) h, (double) w, null);
        return "Формат " + f + " (" + h + " × " + w + " см)";
    }

    private static String roman(Rng r) {
        String[] a = {"IV", "VI", "VIII", "X", "XII", "XIV", "XVI", "XX", "XXIV", "XXXII"};
        return W.pick(r, a);
    }

    private static String collation(Rng r, String g) {
        int p = g.equals("children") ? r.between(8, 48) : g.equals("poetry") ? r.between(48, 220) : r.between(80, 720);
        double u = r.nextDouble();
        if (g.equals("children")) return p + " с., цв. ил.";
        if (u < 0.3) return roman(r) + ", " + p + " с.";
        if (u < 0.55) return p + " с., " + r.between(2, 60) + " л. ил.";
        if (u < 0.7) return "[" + r.between(2, 8) + "], " + p + " с., ил.";
        if (u < 0.8) return p + " с., " + r.between(1, 8) + " л. карт";
        return p + " с.";
    }

    /** Издатель книги того же края, работавший в год издания. */
    private static Maker publisher(Ctx c, Country country, int y) {
        List<Maker> pubs = c.lt.makers().of(MakerKind.PUBLISHER);
        Maker best = null;
        for (int i = 0; i < 30; i++) {
            Maker m = pubs.get(c.r.nextInt(pubs.size()));
            boolean years = m.yearFrom() <= y && m.activeTo(Makers.NOW_YEAR) >= y;
            if (years && m.country() == country) return m;
            if (years && best == null) best = m;
        }
        return best;
    }

    static void fillBook(Ctx c) {
        Rng r = c.r;
        String g = c.variant == null ? "prose" : c.variant;
        boolean authored = !g.equals("journal") && !g.equals("house") && !(g.equals("children") && r.chance(0.4));
        Maker author = authored ? c.maker : null;
        int lo = g.equals("early") ? 1700 : g.equals("journal") ? 1800 : g.equals("children") ? 1830 : 1730;
        int hi = g.equals("early") ? 1830 : 2010;
        int[] sp = Common.span(author, lo, hi);
        if (sp[0] < lo) sp[0] = lo;
        if (sp[1] > hi) sp[1] = hi;
        if (sp[1] < sp[0]) sp[1] = sp[0];
        int y = sp[0] + r.nextInt(sp[1] - sp[0] + 1);
        c.dated(Common.exact(y));
        c.country = author != null ? author.country() : r.chance(0.8) ? Country.RU : Country.FR;
        c.provenanceStyle = "book";
        Maker pub = publisher(c, c.country, y);
        if (author == null) {
            c.maker = pub;
            c.attribution = pub == null ? Attribution.UNKNOWN : Attribution.BY;
        } else {
            c.attribution = Attribution.BY;
        }
        V.P title = bookTitle(c, g, y);
        City city = pub != null && pub.city() != null ? pub.city() : Common.nationOf(c.country).city(r);
        c.originPlaceRu = city.name();
        String bRu = author != null ? author.person().biblio() + " " : "";
        String bEn = author != null ? author.person().biblioEn() + " " : "";
        c.titleRu = Titles.fit(bRu + title.ru() + ". " + city.name() + ", " + y, bRu + title.ru() + ", " + y, bRu + title.ru());
        c.titleEn = Titles.fit(bEn + title.en() + ". " + city.en() + ", " + y, bEn + title.en() + ", " + y, bEn + title.en());
        if (c.titleRu.length() < Titles.MIN && pub != null) c.titleRu = Titles.fit(title.ru() + ". " + city.name() + ": " + pub.name() + ", " + y, c.titleRu);
        if (c.titleEn.length() < Titles.MIN && pub != null) c.titleEn = Titles.fit(title.en() + ". " + city.en() + ": " + pub.nameEn() + ", " + y, c.titleEn);
        if (c.titleEn.length() < Titles.MIN) c.titleEn = Titles.fit(title.en() + ". Printed in " + city.en() + ", " + y, c.titleEn);
        if (c.titleRu.length() < Titles.MIN) c.titleRu = Titles.fit(title.ru() + ". " + city.name() + ", " + y + ", без указания издателя", c.titleRu);
        c.signed = null;
        // 1. выходные данные
        String imprint = city.name() + ": " + (pub != null ? pub.name() : "Издание автора");
        if (pub != null && r.chance(0.3)) {
            List<Maker> printers = c.lt.makers().of(MakerKind.PRINTER);
            Maker pr = printers.get(r.nextInt(printers.size()));
            if (pr.country() == pub.country() && pr.yearFrom() <= y && pr.activeTo(Makers.NOW_YEAR) >= y) imprint += "; " + pr.name();
        }
        c.add(imprint + ", " + y);
        // 2. объём и формат
        c.add(collation(r, g) + " " + format(c, g).replaceFirst("^Ф", "ф"));
        if (c.country != Country.RU) c.add("На " + c.country.langPrep + " языке");
        // 3. автор
        if (author != null) {
            String spec = author.specialty() == null ? "автор" : author.specialty();
            c.add("Автор — " + author.name() + " (" + author.years() + "), " + spec);
        }
        // 4. переплёт и экземпляр
        c.add(W.pick(r, BINDING));
        double u = r.nextDouble();
        if (u < 0.2) c.add("Тираж " + Coins.thousands(r.between(1, 60) * 100L) + " экз.");
        else if (u < 0.3) {
            int total = r.between(1, 30) * 50;
            c.add("Нумерованный экземпляр № " + r.between(1, total) + " из " + total);
        } else if (u < 0.45) {
            Maker ill = illustrator(c, y);
            if (ill != null) c.add("С иллюстрациями по рисункам " + ill.nameGen());
            else c.add(feature(r, y));
        } else if (u < 0.55) {
            c.add("Экслибрис " + Common.collector(r, Common.nationOf(c.country), y - r.between(0, 40))[0] + " на форзаце");
        } else {
            c.add(feature(r, y));
        }
        if (r.chance(0.4)) c.add(feature(r, y));
        if (pub != null && r.chance(0.35)) {
            c.add(W.cap(pub.name()) + " (" + pub.placeRu() + ", " + pub.years() + ")"
                    + (pub.specialty() != null ? " — " + pub.specialty() : ""));
        }
        c.add(W.pick(r, BOOK_NOTE));
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.55, 0.15, 0.03, 0.01);
    }

    /** Иллюстратор — художник, работавший в год издания. */
    private static Maker illustrator(Ctx c, int y) {
        List<Maker> artists = c.lt.makers().of(MakerKind.ARTIST);
        for (int i = 0; i < 20; i++) {
            Maker a = artists.get(c.r.nextInt(artists.size()));
            if (a.activeFrom() <= y && a.activeTo(Makers.NOW_YEAR) >= y) return a;
        }
        return null;
    }

    // ---------------------------------------------------------------- рукописи

    private static final Country[] MANUSCRIPT_ABROAD = {Country.FR, Country.DE, Country.GB, Country.IT};

    static void fillManuscript(Ctx c) {
        Rng r = c.r;
        c.maker = null;
        c.attribution = Attribution.UNKNOWN;
        int y = r.between(1760, 1990);
        c.country = r.chance(0.8) ? Country.RU : MANUSCRIPT_ABROAD[r.nextInt(MANUSCRIPT_ABROAD.length)];
        Nation n = Common.nationOf(c.country);
        Person p = n.anyone(r, y - r.between(20, 60), 0.3);
        int y2 = Math.min(y + r.between(1, 15), Makers.NOW_YEAR - 1);
        c.provenanceStyle = "book";
        int span = y2;
        int profIdx = r.nextInt(PROF.length);
        String v = c.variant == null ? "diary" : c.variant;
        V.P t;
        String kindNote = null;
        switch (v) {
            case "letters" -> {
                if (r.chance(0.5)) {
                    t = new V.P("Письмо " + p.initialsGen() + ", " + y, "Letter from " + p.initialsEn() + ", " + y);
                    span = y;
                } else {
                    int k = r.between(5, 120);
                    t = new V.P("Переписка семьи " + Ru.pluralGenSurname(p.lastMale()) + ": " + W.count(k, "письмо", "письма", "писем")
                            + ", " + y + "–" + y2, "Correspondence of the " + p.lastMaleEn() + " Family: " + k + " Letters, " + y + "–" + y2);
                }
            }
            case "album" -> {
                double u = r.nextDouble();
                if (u < 0.45) t = new V.P("Альбом со стихами и рисунками, " + W.decade(y), "Album of Poems and Drawings, " + (y / 10 * 10) + "s");
                else if (u < 0.75) t = new V.P("Альбом с автографами, " + W.decade(y), "Autograph Album, " + (y / 10 * 10) + "s");
                else {
                    t = new V.P("Рукописная нотная тетрадь, " + W.decade(y), "Manuscript Music Book, " + (y / 10 * 10) + "s");
                    kindNote = "Романсы и пьесы для фортепиано, переписанные от руки";
                }
                span = Math.min(y / 10 * 10 + 9, Makers.NOW_YEAR - 1);
                y = Math.max(y / 10 * 10, 1760);
            }
            case "literary" -> {
                V.P nv = V.pick(r, NOVEL);
                t = r.chance(0.6) ? new V.P("Рукопись повести «" + nv.ru() + "» с правкой автора, " + y,
                        "Manuscript of the Novella “" + nv.en() + "” with Author's Corrections, " + y)
                        : new V.P("Рукопись сборника стихов «" + nv.ru() + "», " + y, "Manuscript of the Poetry Collection “" + nv.en() + "”, " + y);
                span = y;
            }
            case "records" -> {
                double u = r.nextDouble();
                if (u < 0.4) {
                    t = new V.P("Журнал наблюдений за погодой, " + y + "–" + y2, "Weather Observation Journal, " + y + "–" + y2);
                    kindNote = "Ежедневные записи температуры, ветра и осадков";
                } else if (u < 0.75) {
                    t = y < 1917 ? new V.P("Хозяйственная книга усадьбы, " + y + "–" + y2, "Country House Account Book, " + y + "–" + y2)
                            : new V.P("Хозяйственная книга семьи, " + y + "–" + y2, "Household Account Book, " + y + "–" + y2);
                    kindNote = "Записи о работах в саду, покупках и урожае";
                } else {
                    t = new V.P("Журнал ботанических наблюдений, " + y + "–" + y2, "Journal of Botanical Observations, " + y + "–" + y2);
                    kindNote = "Записи о цветении растений, сборах семян и погоде";
                }
            }
            default -> {
                double u = r.nextDouble();
                if (u < 0.4) {
                    V.P d = V.pick(r, TRAVEL_DAT);
                    t = new V.P("Дневник путешествия по " + d.ru() + ", " + y, "Diary of a Journey " + d.en() + ", " + y);
                    kindNote = "Записи о дороге, встречах и местах остановок";
                    span = y;
                } else if (u < 0.7) {
                    V.P pr = PROF[profIdx];
                    t = new V.P("Записная книжка " + pr.ru() + " " + p.initialsGen() + ", " + y + "–" + y2,
                            "Notebook of " + pr.en() + " " + p.initialsEn() + ", " + y + "–" + y2);
                } else {
                    t = new V.P("Дневник " + p.initialsGen() + ", " + y + "–" + y2, "Diary of " + p.initialsEn() + ", " + y + "–" + y2);
                }
            }
        }
        c.year = y;
        c.yearFrom = y;
        c.yearTo = Math.max(y, span);
        c.dated = new Common.Dated(y, c.yearFrom, c.yearTo, c.yearFrom == c.yearTo ? Integer.toString(y) : c.yearFrom + "–" + c.yearTo,
                c.yearFrom == c.yearTo, Integer.toString(y), Integer.toString(y));
        c.titleRu = Titles.fit(t.ru());
        c.titleEn = Titles.fit(t.en());
        c.originPlaceRu = c.country.nameRu;
        int leaves = r.between(1, 240);
        String ink = W.pick(r, new String[]{"чернила", "карандаш", "чернила и карандаш", "тушь", "чернила, акварель"});
        c.materialRu = "бумага";
        c.techniqueRu = ink;
        c.pieces = 1;
        c.signed = null;
        c.add(W.count(leaves, "лист", "листа", "листов") + ", бумага, " + ink);
        c.add("Почерк " + W.pick(r, new String[]{"разборчивый", "беглый", "каллиграфический", "мелкий, местами неразборчивый"}));
        if (kindNote != null) c.add(kindNote);
        else if (!v.equals("letters") && r.chance(0.7)) c.add("Записи о " + W.pick(r, TOPIC));
        c.add("Автор — " + p.full() + ", " + PROF_NOM[profIdx]);
        if (r.chance(0.4)) c.add("На бумаге с водяным знаком " + W.pick(r, WATERMARK));
        c.add(W.pick(r, new String[]{"В картонной папке", "Сшито в тетрадь", "В кожаном переплёте", "Отдельные листы в конверте",
                "В коленкоровом переплёте"}));
        if (c.country != Country.RU) c.add("На " + c.country.langPrep + " языке");
        c.add(W.pick(r, MS_NOTE));
        if (r.chance(0.4)) c.add(W.pick(r, MS_NOTE));
        double h = W.half(r, 15, 36), w = W.half(r, 10, 25);
        c.sizeCm(h, w, null);
        c.add("Размер листа " + W.cm(h) + " × " + W.cm(w) + " см");
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.45, 0.04, 0.02, 0.02);
    }

    // ---------------------------------------------------------------- карты

    static void fillMap(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        int[] sp = Common.span(m, 1700, 1990);
        int y = sp[0] + r.nextInt(Math.max(1, sp[1] - sp[0] + 1));
        c.dated(Common.exact(y));
        c.country = m != null ? m.country() : Country.RU;
        c.provenanceStyle = "book";
        c.attribution = m == null ? Attribution.UNKNOWN : Attribution.BY;
        City city = Common.nationOf(c.country).city(r);
        String v = c.variant == null ? "world" : c.variant;
        V.P t;
        boolean globe = false, atlas = false;
        switch (v) {
            case "sea" -> {
                double u = r.nextDouble();
                t = u < 0.5 ? pre("Морская карта ", "Sea Chart of ", V.pick(r, SEA_GEN))
                        : u < 0.75 ? pre("Лоция ", "Pilot Guide to ", V.pick(r, SEA_GEN)) : pre("Карта ", "Map of ", V.pick(r, RIVER_GEN));
                atlas = t.ru().startsWith("Лоция");
            }
            case "city" -> {
                double u = r.nextDouble();
                t = u < 0.6 ? new V.P("План " + city.gen(), "Plan of " + city.en())
                        : u < 0.8 ? new V.P("Вид " + city.gen() + " с высоты птичьего полёта", "Bird's-Eye View of " + city.en())
                        : new V.P("Карта окрестностей " + city.gen(), "Map of the Environs of " + city.en());
            }
            case "atlas" -> {
                atlas = true;
                t = W.pick(r, new String[]{"a", "b", "c"}).equals("a") ? new V.P("Атлас мира", "World Atlas")
                        : r.chance(0.5) ? new V.P("Учебный атлас", "School Atlas") : new V.P("Атлас морей и океанов", "Atlas of Seas and Oceans");
            }
            case "celestial" -> {
                atlas = r.chance(0.3);
                t = atlas ? new V.P("Атлас звёздного неба", "Celestial Atlas") : new V.P("Карта звёздного неба", "Celestial Map");
            }
            case "globe" -> {
                globe = true;
                t = r.chance(0.75) ? new V.P("Глобус настольный", "Table Globe") : new V.P("Небесный глобус", "Celestial Globe");
            }
            default -> {
                double u = r.nextDouble();
                t = u < 0.35 ? new V.P("Карта Европы", "Map of Europe") : u < 0.7 ? new V.P("Карта мира в двух полушариях",
                        "Map of the World in Two Hemispheres") : pre("Карта ", "Map of ", V.pick(r, CONTINENT_GEN));
            }
        }
        c.objectRu = t.ru();
        c.objectEn = t.en();
        c.materialRu = "бумага";
        c.originPlaceRu = m != null ? m.placeRu() : city.name();
        if (globe) {
            int d = r.between(15, 45);
            c.diameterMm = d * 10.0;
            c.techniqueRu = y < 1900 ? "гравюра, раскраска от руки" : "цветная печать";
            c.add("Диаметр " + d + " см; бумага, наклеенная сегментами на шар, " + c.techniqueRu);
            c.add(W.pick(r, new String[]{"На деревянной подставке, меридиан латунный", "На чугунной подставке",
                    "На точёной деревянной ножке"}));
            c.add(t.ru().startsWith("Небесный") ? "Созвездия показаны фигурами; надписи мелкие, чёткие"
                    : "Подробно показаны моря, реки и горы; надписи мелкие, чёткие");
        } else {
            String tech = y < 1850 ? W.pick(r, new String[]{"Гравюра на меди, раскраска от руки", "Гравюра на меди"})
                    : y < 1930 ? W.pick(r, new String[]{"Литография", "Хромолитография", "Цветная литография",
                    "Гравюра на меди, раскраска от руки"})
                    : W.pick(r, new String[]{"Цветная офсетная печать", "Цветная литография"});
            c.techniqueRu = tech.toLowerCase(Locale.ROOT);
            int a = atlas ? r.between(28, 50) : r.between(30, 110), b = atlas ? r.between(22, 36) : r.between(40, 130);
            c.sizeCm((double) a, (double) b, null);
            if (atlas) c.pieces = 1;
            c.add(tech + "; бумага. " + (atlas ? W.count(r.between(12, 120), "карта", "карты", "карт") + ", формат " + a + " × " + b + " см"
                    : "Лист " + a + " × " + b + " см"));
            if (!t.ru().contains("звёздного неба") && r.chance(0.6)) {
                c.add(c.country == Country.RU && y < 1920 && r.chance(0.6)
                        ? "Масштаб: в английском дюйме " + W.count(r.between(1, 60) * 5, "верста", "версты", "вёрст")
                        : "Масштаб 1 : " + Coins.thousands(r.between(1, 400) * 25_000L));
            }
            if (r.chance(0.4)) c.add("Картуш с названием в " + W.pick(r, new String[]{"левом верхнем углу", "правом нижнем углу",
                    "левом нижнем углу"}));
            if (!atlas && r.chance(0.2)) c.add("Расчленена на " + r.between(8, 36) + " сегментов и наклеена на холст, в картонном футляре");
        }
        if (m != null) {
            c.signed = true;
            c.add(r.chance(0.5) ? "Издание " + m.nameGen() + ", " + m.placeRu() + ", " + y : W.cap(m.name()) + ", " + m.placeRu() + ", " + y);
            if (r.chance(0.35)) c.add(W.cap(m.name()) + " — " + (m.specialty() != null ? m.specialty() + ", " : "") + m.years());
        } else {
            c.signed = false;
            c.add("Без указания издателя, " + y);
        }
        if (c.country != Country.RU) c.add("Надписи на " + c.country.langPrep + " языке");
        String[] notes = v.equals("celestial") || t.ru().startsWith("Небесный") ? MAP_NOTE_SKY : v.equals("sea") ? MAP_NOTE_SEA : MAP_NOTE;
        c.add(W.pick(r, notes));
        if (r.chance(0.4)) c.add(W.pick(r, notes));
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.55, 0.12, 0.02, 0.01);
    }
}
