package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Коллекционные предметы: марки и почтовая история, открытки, игрушки, памятные вещи (театр, кино, спорт,
 * путешествия, фотографии, техника, предметы быта). Марки выпускают выдуманные почтовые ведомства и земские почты
 * (справочник мастеров); номиналы — в валюте языковой группы ведомства; сюжеты нейтральные (птицы, цветы, транспорт,
 * спорт). Игрушки — без солдатиков и оружия. Словарь — {@code vocab/collectibles.txt}.
 */
final class Collectibles {
    private Collectibles() {
    }

    private static final String F = "collectibles";

    private static V.P[] p(String s) {
        return V.pairs(F, s);
    }

    private static String[] l(String s) {
        return V.list(F, s);
    }

    private static V.P q(String ru, String en, V.P x) {
        return new V.P(ru + " «" + x.ru() + "»", en + " “" + x.en() + "”");
    }

    // ---------------------------------------------------------------- марки

    private record Currency(String[] small, String[] big) {
    }

    private static final Map<String, Currency> CURRENCY = new HashMap<>();

    static {
        for (String s : l("currency")) {
            String[] f = s.split("\\s*:\\s*");
            CURRENCY.put(f[0], new Currency(f[1].split(","), f[2].split(",")));
        }
    }

    private static final V.P[] STAMP_EARLY = p("stamp.early"), STAMP_SUBJ = p("stamp.subject"), STAMP_MODERN = p("stamp.modern"),
            STAMP_TOPIC = p("stamp.topic"), STAMP_TOPIC_MODERN = p("stamp.topic.modern");
    private static final String[] PERF = l("perf"), WATERMARK = l("stamp.watermark"), PRINTING = l("stamp.printing"),
            VARIETY = l("stamp.variety"), COLOR = l("stamp.color"), STAMP_NOTE = l("stamp.note");

    private static String nominal(Rng r, Currency cu) {
        if (r.chance(0.7)) {
            int[] v = {5, 10, 15, 20, 25, 30, 40, 50, 60, 75, 80};
            int n = v[r.nextInt(v.length)];
            return n + " " + W.plural(n, cu.small[0], cu.small[1], cu.small[2]);
        }
        int[] v = {1, 2, 3, 5, 10};
        int n = v[r.nextInt(v.length)];
        return n + " " + W.plural(n, cu.big[0], cu.big[1], cu.big[2]);
    }

    static void fillStamp(Ctx c) {
        Rng r = c.r;
        Maker post = c.maker != null ? c.maker : c.lt.makers().pick(MakerKind.POSTAL, r);
        c.maker = post;
        c.attribution = Attribution.BY;
        c.provenanceStyle = "stamp";
        c.country = post.country();
        Nation n = Common.nationOf(post.country());
        Currency cu = CURRENCY.get(n.name());
        int lo = Math.max(post.yearFrom(), 1840), hi = Math.max(lo, Math.min(post.activeTo(2010), 2010));
        int y = r.between(lo, hi);
        c.dated(Common.exact(y));
        c.originPlaceRu = post.placeRu();
        c.materialRu = "бумага";
        String v = c.variant == null ? "single" : c.variant;
        V.P subj = y < 1900 ? V.pick(r, STAMP_EARLY) : y >= 1950 && r.chance(0.3) ? V.pick(r, STAMP_MODERN) : V.pick(r, STAMP_SUBJ);
        if (y < 1910 && (subj.ru().equals("Дирижабль") || subj.ru().equals("Ледокол"))) subj = STAMP_EARLY[3];
        V.P topic = y >= 1950 && r.chance(0.25) ? V.pick(r, STAMP_TOPIC_MODERN) : V.pick(r, STAMP_TOPIC);
        V.P t;
        boolean series = false;
        switch (v) {
            case "set" -> {
                double u = r.nextDouble();
                if (u < 0.6 || y < 1930) {
                    int k = r.between(3, 12);
                    t = new V.P("Серия «" + topic.ru() + "» из " + k + " марок", "Set of " + k + " Stamps “" + topic.en() + "”");
                    c.pieces = k;
                    series = true;
                } else if (u < 0.8) {
                    t = q("Почтовый блок", "Souvenir Sheet", subj);
                } else {
                    t = q("Лист марок", "Sheet of Stamps", subj);
                }
            }
            case "cover" -> {
                double u = r.nextDouble();
                t = u < 0.4 && y >= 1930 ? q("Конверт первого дня", "First Day Cover", subj)
                        : u < 0.7 ? q("Почтовая карточка", "Postal Card", subj) : new V.P("Конверт, прошедший почту", "Postally Used Cover");
            }
            case "collection" -> {
                int k = r.between(50, 3000);
                int y2 = Math.min(hi, y + r.between(5, 60));
                c.yearTo = Math.max(y, y2);
                c.dated = new Common.Dated(y, y, c.yearTo, y + "–" + c.yearTo, false, y + "–" + c.yearTo, y + "–" + c.yearTo);
                c.pieces = k;
                c.objectRu = "Коллекция марок в альбоме, " + k + " шт.";
                c.objectEn = "Stamp Collection in an Album, " + k + " Stamps";
                c.materialRu = null;
                c.techniqueRu = null;
                c.signed = null;
                c.add("Марки " + post.nameGen() + " и других почтовых ведомств, " + y + "–" + c.yearTo);
                c.add("В альбоме " + W.count(r.between(10, 80), "лист", "листа", "листов") + " с кляйстерами");
                c.add(W.pick(r, new String[]{"Гашёные и негашёные", "Преимущественно гашёные", "Есть полные серии"}));
                c.add(W.pick(r, new String[]{"Собрание составлено прежним владельцем за много лет", "Марки разложены по годам и сериям",
                        "Встречаются редкие выпуски и разновидности", "Хорошая основа для тематической коллекции"}));
                c.add(W.pick(r, STAMP_NOTE));
                c.add("Опись прилагается");
                Condition.fill(c);
                c.provenance = Common.provenance(c, 0.8, 0.0, 0.0, 0.0);
                return;
            }
            default -> {
                double u = r.nextDouble();
                t = u < 0.85 ? q("Марка", "Stamp", subj) : q("Пробная марка", "Stamp Proof", subj);
            }
        }
        c.objectRu = t.ru();
        c.objectEn = t.en();
        String printing = W.pick(r, PRINTING);
        c.techniqueRu = printing.equals("металлография") || printing.equals("литография") ? printing : printing + " печать";
        c.signed = null;
        c.add(W.cap(post.name()) + " (" + post.years() + "), " + y + ". " + (series ? "Номиналы от " + nominal(r, cu)
                + " и выше, тематика «" + topic.ru() + "»" : "Номинал " + nominal(r, cu)));
        c.add("Бумага" + (r.chance(0.3) ? " " + W.pick(r, new String[]{"мелованная", "тонкая", "плотная", "с сеткой"}) : "")
                + ", печать " + printing + (v.equals("cover") ? "" : ", " + (r.chance(0.85) ? "зубцовка " + W.pick(r, PERF) : "беззубцовая"))
                + (r.chance(0.3) ? ", водяной знак " + W.pick(r, WATERMARK) : ""));
        c.add("Цвет — " + W.pick(r, COLOR) + (r.chance(0.4) ? ". Тираж " + Coins.thousands(r.between(2, 900) * 10_000L) + " экз."
                : r.chance(0.3) ? ". Рисунок художника " + n.anyone(r, y - 40, 0.2).initials() : ""));
        if (v.equals("cover")) {
            c.add(W.pick(r, new String[]{"Конверт с маркой, прошёл почту", "Адрес написан от руки чернилами",
                    "На обороте — штемпель прибытия", "Конверт с художественным рисунком"}));
            double cw = W.half(r, 14, 22), ch = W.half(r, 9, 15);
            c.sizeCm(ch, cw, null);
            c.add("Размер " + W.cm(cw) + " × " + W.cm(ch) + " см");
        }
        if (r.chance(0.2)) c.add("Разновидность: " + W.pick(r, VARIETY));
        c.add(W.pick(r, STAMP_NOTE));
        if (r.chance(0.4)) c.add(W.pick(r, new String[]{"В кляссере", "На листе альбома", "В пергаминовом конверте",
                "С сертификатом эксперта"}));
        Condition.fill(c);
        if (c.condition.startsWith("Гашёная") && r.chance(0.6)) {
            City city = n.city(r);
            String date = r.between(1, 28) + "." + W.yy(r.between(1, 12) + 100) + "." + y;
            c.add(city.country() == post.country() ? "Гашение «" + city.name() + " " + date + "»" : "Гашение календарным штемпелем " + date);
        }
        Painting.dedupe(c);
        c.provenance = Common.provenance(c, 0.86, 0.03, 0.0, 0.02);
    }

    // ---------------------------------------------------------------- открытки

    private static final V.P[] GREETING = p("postcard.greeting");
    private static final String[] VIEWS = l("postcard.view"), POSTCARD_NOTE = l("postcard.note");

    static void fillPostcard(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        int[] sp = Common.span(m, 1895, 1990);
        Common.Dated d = Common.date(r, sp[0], sp[1], 0.3, 0.2, 0.4);
        c.dated(d);
        int y = c.year;
        c.country = m != null ? m.country() : Country.RU;
        c.provenanceStyle = "stamp";
        c.attribution = m == null ? Attribution.UNKNOWN : Attribution.BY;
        Nation n = Common.nationOf(c.country);
        City city = n.city(r);
        double u = r.nextDouble();
        boolean set = false;
        V.P t;
        if (u < 0.35) {
            String view = W.pick(r, VIEWS);
            t = new V.P("Открытка «" + city.name() + ". " + W.cap(view) + "»", "Postcard “" + city.en() + "”");
        } else if (u < 0.55) {
            int k = r.between(6, 60);
            t = new V.P("Открытки «Виды " + city.gen() + "», " + k + " шт.", "Postcards “Views of " + city.en() + "”, " + k + " Cards");
            c.pieces = k;
            set = true;
        } else if (u < 0.75) {
            t = q("Поздравительная открытка", "Greeting Card", V.pick(r, GREETING));
        } else {
            V.P pic = V.pick(r, V.pairs("painting", "land.alone"));
            t = q("Открытка с репродукцией картины", "Postcard after the Painting", pic);
        }
        c.objectRu = t.ru();
        c.objectEn = t.en();
        String[] tp = y < 1900 ? new String[]{"хромолитография", "фототипия", "литография"}
                : y < 1935 ? new String[]{"хромолитография", "фототипия", "типографская печать", "цветная печать"}
                : new String[]{"офсетная печать", "типографская печать", "цветная печать"};
        String tech = W.pick(r, tp);
        c.materialRu = "картон";
        c.techniqueRu = tech;
        c.sizeCm(9.0, 14.0, null);
        c.add("Картон, " + tech + ". Размер " + (set ? "каждой " : "") + "9 × 14 см");
        if (m != null) {
            c.signed = true;
            c.add("Издание " + m.nameGen() + (m.placeRu() != null ? ", " + m.placeRu() : "") + (r.chance(0.5) ? ", серия № " + r.between(1, 400) : ""));
        } else {
            c.signed = false;
            c.add("Издатель не указан");
        }
        c.add(d.line());
        if (set) {
            List<String> vv = new ArrayList<>();
            for (int i = 0; i < 6 && vv.size() < 3; i++) {
                String s = W.pick(r, VIEWS);
                if (!vv.contains(s)) vv.add(s);
            }
            int sent = r.between(0, Math.min(12, c.pieces));
            c.add("Виды: " + String.join(", ", vv) + " и другие; " + (sent == 0 ? "все чистые"
                    : W.count(sent, "открытка подписана и отправлена", "открытки подписаны и отправлены", "открыток подписаны и отправлены")));
        } else {
            c.add(r.chance(0.5) ? "Чистая, не подписана" : "Подписана и отправлена; на обороте — текст чернилами и почтовый штемпель");
        }
        c.add(W.pick(r, POSTCARD_NOTE));
        if (r.chance(0.5)) c.add(W.pick(r, POSTCARD_NOTE));
        if (m != null && r.chance(0.4)) c.add(W.cap(m.name()) + " — " + (m.specialty() != null ? m.specialty() + ", " : "") + m.years());
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.85, 0.02, 0.0, 0.0);
    }

    // ---------------------------------------------------------------- игрушки

    private static final V.P[] TIN = p("toy.tin"), DOLL = p("toy.doll"), SOFT = p("toy.soft"), WOOD = p("toy.wood"),
            TRAIN = p("toy.train"), GAME = p("toy.game"), FESTIVE = p("toy.festive"), FIGURESET = p("toy.figureset");
    private static final String[] TOY_NOTE = l("toy.note");

    static void fillToy(Ctx c) {
        Rng r = c.r;
        Maker m = c.maker;
        String v0 = c.variant == null ? "tin" : c.variant;
        int lo = switch (v0) {
            case "soft" -> 1895;
            case "tin", "train", "festive" -> 1860;
            case "game" -> 1850;
            case "doll" -> 1820;
            default -> 1800;
        };
        int[] sp = Common.span(m, lo, 1995);
        if (sp[0] < lo) sp[0] = Math.min(lo, sp[1]);
        Common.Dated d = Common.date(r, sp[0], sp[1], 0.05, 0.25, 0.45);
        c.dated(d);
        int y = c.year;
        c.country = m != null ? m.country() : Country.DE;
        c.provenanceStyle = "toy";
        c.attribution = m == null ? Attribution.UNKNOWN : Attribution.BY;
        c.originPlaceRu = m != null && m.placeRu() != null ? m.placeRu() : c.country.nameRu;
        String v = c.variant == null ? "tin" : c.variant;
        V.P t = switch (v) {
            case "doll" -> {
                V.P x = V.pick(r, DOLL);
                yield y >= 1905 && r.chance(0.2) ? new V.P("Кукла целлулоидная", "Celluloid Doll") : x;
            }
            case "soft" -> V.pick(r, SOFT);
            case "wood" -> V.pick(r, WOOD);
            case "train" -> r.chance(0.2) ? q("Набор оловянных фигурок", "Set of Tin Figures", V.pick(r, FIGURESET)) : V.pick(r, TRAIN);
            case "game" -> {
                V.P g = V.pick(r, GAME);
                yield new V.P("Настольная игра " + g.ru(), "Board Game " + g.en());
            }
            case "festive" -> {
                V.P f = V.pick(r, FESTIVE);
                int k = r.between(6, 40);
                c.pieces = k;
                yield new V.P(f.ru() + ", набор " + k + " шт.", "Set of " + k + " " + f.en());
            }
            default -> V.pick(r, TIN);
        };
        c.objectRu = t.ru();
        c.objectEn = t.en();
        String ru = t.ru();
        String mat = switch (v) {
            case "tin" -> y >= 1955 && r.chance(0.3) ? "Пластмасса, жесть" : r.chance(0.7) ? "Литографированная жесть" : "Жесть, роспись";
            case "train" -> ru.startsWith("Набор оловянных") ? "Олово, роспись" : ru.startsWith("Модель парусника") ? "Дерево, ткань, нить"
                    : ru.startsWith("Конструктор") ? "Металл, лак" : "Жесть, литьё, роспись";
            case "doll" -> ru.contains("целлулоид") ? "Целлулоид" : ru.contains("папье") ? "Папье-маше, роспись, ткань"
                    : ru.contains("тряпич") ? "Ткань, набивка, роспись" : "Фарфор, ткань, мохер (парик)";
            case "soft" -> y >= 1950 && r.chance(0.3) ? "Плюш, поролон, пластмассовые глаза" : "Плюш, опилки, стеклянные глаза";
            case "wood" -> ru.startsWith("Кукольн") || ru.startsWith("Игрушечная") ? "Дерево, картон, ткань" : "Дерево, роспись";
            case "game" -> "Картон, литография";
            default -> y >= 1950 && r.chance(0.5) ? "Стекло, роспись; картон" : ru.contains("ваты") ? "Вата, картон, роспись" : "Стекло, вата, картон, роспись";
        };
        String[] mp = mat.split(",\\s*|;\\s*", 2);
        c.materialRu = mp[0].toLowerCase(Locale.ROOT);
        c.techniqueRu = mp.length > 1 ? mp[1] : null;
        c.add(mat);
        switch (v) {
            case "tin" -> c.add(y >= 1920 && r.chance(0.15) ? "Электрический привод" : y >= 1940 && r.chance(0.2) ? "Инерционный механизм"
                    : W.pick(r, new String[]{"Пружинный механизм, ключ в наличии", "Механизм заводной, рабочий",
                    "Пружинный механизм, ключ отсутствует", "Механизм требует ремонта"}));
            case "train" -> {
                if (ru.startsWith("Железная")) {
                    c.add("Комплект: паровоз, тендер, " + W.count(r.between(2, 6), "вагон", "вагона", "вагонов") + ", "
                            + W.count(r.between(6, 24), "секция", "секции", "секций") + " рельсов" + (y >= 1920 && r.chance(0.3)
                            ? "; электрический привод" : "; пружинный механизм"));
                } else if (ru.startsWith("Набор оловянных")) {
                    int k = r.between(8, 60);
                    c.pieces = k;
                    c.add("В наборе " + W.count(k, "фигурка", "фигурки", "фигурок"));
                } else {
                    c.add(W.pick(r, new String[]{"Все детали на месте", "Комплект неполный", "Собрано по оригинальной инструкции"}));
                }
            }
            case "doll" -> c.add(W.pick(r, new String[]{"Глаза закрываются", "Тело на шарнирах", "Парик из натуральных волос",
                    "Одежда оригинальная", "Одежда поздняя"}));
            case "game" -> c.add("Комплект полный: поле, " + W.count(r.between(2, 8), "фишка", "фишки", "фишек") + ", кубик, правила");
            default -> {
            }
        }
        String size;
        switch (v) {
            case "doll" -> size = "Высота " + h(c, 20, 75) + " см";
            case "soft" -> size = "Высота " + h(c, 15, 60) + " см";
            case "wood" -> size = ru.contains("Лошадка") ? "Высота " + h(c, 40, 95) + " см" : ru.startsWith("Кукольный домик")
                    ? "Высота " + h(c, 30, 90) + " см" : "Высота " + h(c, 6, 30) + " см";
            case "festive" -> size = "Высота игрушек до " + h(c, 6, 16) + " см";
            case "game" -> {
                int a = r.between(20, 45), b = r.between(15, 35);
                c.sizeCm(null, (double) a, (double) b);
                size = "Коробка " + a + " × " + b + " см";
            }
            default -> {
                double len = W.half(r, 10, 45);
                c.widthMm = (int) StrictMath.round(len * 10);
                size = "Длина " + W.cm(len) + " см";
            }
        }
        c.add(size);
        if (m == null) {
            c.signed = false;
            c.add("Без клейма");
        } else {
            c.signed = true;
            c.add(v.equals("doll") && r.chance(0.5) ? "Клеймо на затылке: «" + m.mark() + "», номер формы " + r.between(10, 999) + "; работа " + m.nameGen()
                    : W.pick(r, new String[]{"Клеймо " + m.nameGen() + " на днище", "Литографированная марка " + m.nameGen(),
                    "Бумажная этикетка " + m.nameGen()}));
        }
        boolean boxed = !(v.equals("wood") || v.equals("soft"));
        if (boxed && r.chance(0.45)) c.add(W.pick(r, v.equals("game") ? new String[]{"Коробка оригинальная", "Коробка с надрывами",
                "Коробка с литографированной крышкой"} : new String[]{"В оригинальной коробке", "Коробка с надрывами", "Без коробки",
                "Коробка с литографированной крышкой"}));
        c.add(m != null && r.chance(0.5) ? W.cap(m.name()) + ", " + d.text() + "." : Common.placeDate(c.country, d));
        if (m != null && r.chance(0.35)) c.add(W.cap(m.name()) + (m.yearTo() == null ? " работает с " + m.yearFrom() + " года"
                : " работала в " + m.yearFrom() + "–" + m.yearTo() + " годах"));
        c.add(W.pick(r, TOY_NOTE));
        if (r.chance(0.4)) c.add(W.pick(r, TOY_NOTE));
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.88, 0.01, 0.01, 0.0);
    }

    private static String h(Ctx c, double lo, double hi) {
        double v = W.half(c.r, lo, hi);
        c.heightMm = (int) StrictMath.round(v * 10);
        return W.cm(v);
    }

    // ---------------------------------------------------------------- памятные вещи

    private static final V.P[] PLAY = p("mem.play"), SONG = p("mem.song"), FILM = p("mem.film"), PERFORMER = p("mem.performer"),
            SPORT = p("mem.sport"), SPORT_THING = p("mem.sport.thing"), TRAVEL = p("mem.travel"), EPHEMERA = p("mem.ephemera"),
            TECH = p("mem.tech");
    private static final String[] MEM_NOTE = l("mem.note");

    private static V.P tech(Rng r, int y) {
        for (int i = 0; i < 40; i++) {
            V.P x = V.pick(r, TECH);
            String[] f = x.ru().split("\\s*/\\s*");
            String[] yy = f[1].split("-");
            if (y >= Integer.parseInt(yy[0]) && y <= Integer.parseInt(yy[1])) return new V.P(f[0], x.en());
        }
        return new V.P("Пишущая машинка", "Typewriter");
    }

    private static final Country[] MEM_ABROAD = {Country.FR, Country.DE, Country.GB, Country.IT};

    static void fillMemorabilia(Ctx c) {
        Rng r = c.r;
        c.maker = null;
        c.attribution = Attribution.UNKNOWN;
        String v = c.variant == null ? "ephemera" : c.variant;
        int y = r.between(v.equals("cinema") ? 1912 : 1880, 2010);
        c.dated(Common.exact(y));
        c.country = r.chance(0.75) ? Country.RU : MEM_ABROAD[r.nextInt(MEM_ABROAD.length)];
        c.provenanceStyle = "art";
        c.originPlaceRu = c.country.nameRu;
        Nation n = Common.nationOf(c.country);
        City city = n.city(r);
        String type;
        V.P t;
        switch (v) {
            case "theatre" -> {
                double u = r.nextDouble();
                if (u < 0.3) {
                    V.P pl = V.pick(r, PLAY);
                    t = r.chance(0.6) ? new V.P("Афиша спектакля «" + pl.ru() + "», " + y, "Theatre Poster “" + pl.en() + "”, " + y)
                            : new V.P("Афиша концерта, " + city.name() + ", " + y, "Concert Poster, " + city.en() + ", " + y);
                    type = "poster";
                } else if (u < 0.55) {
                    V.P pl = V.pick(r, PLAY);
                    t = new V.P("Программа спектакля «" + pl.ru() + "», " + y, "Theatre Programme “" + pl.en() + "”, " + y);
                    type = "programme";
                } else if (u < 0.8) {
                    t = autograph(c, n, y);
                    type = "photo";
                } else if (y >= 1900) {
                    V.P s = V.pick(r, SONG);
                    t = new V.P("Граммофонная пластинка «" + s.ru() + "»", "Gramophone Record “" + s.en() + "”");
                    type = "record";
                } else {
                    t = new V.P("Афиша циркового представления, " + y, "Circus Poster, " + y);
                    type = "poster";
                }
            }
            case "cinema" -> {
                double u = r.nextDouble();
                V.P f = V.pick(r, FILM);
                if (u < 0.45) {
                    t = new V.P("Киноафиша фильма «" + f.ru() + "», " + y, "Film Poster “" + f.en() + "”, " + y);
                    type = "poster";
                } else if (u < 0.7) {
                    t = new V.P("Программа кинотеатра с фильмом «" + f.ru() + "», " + y, "Cinema Programme “" + f.en() + "”, " + y);
                    type = "programme";
                } else {
                    t = autograph(c, n, y);
                    type = "photo";
                }
            }
            case "sport" -> {
                if (r.chance(0.45)) {
                    V.P s = V.pick(r, SPORT);
                    t = new V.P("Программа соревнований по " + s.ru() + ", " + y, s.en() + " Competition Programme, " + y);
                    type = "programme";
                } else {
                    t = V.pick(r, SPORT_THING);
                    type = t.ru().startsWith("Фотография") ? "photo" : "thing";
                }
            }
            case "travel" -> {
                t = V.pick(r, TRAVEL);
                type = t.ru().startsWith("Путеводитель") || t.ru().startsWith("Расписание") || t.ru().startsWith("Билет")
                        || t.ru().startsWith("Набор") ? "programme" : "thing";
            }
            case "photo" -> {
                double u = r.nextDouble();
                if (u < 0.5) {
                    Nation.Surname s = n.surname(r);
                    t = new V.P("Альбом фотографий семьи " + Ru.pluralGenSurname(s.ru()), "Family Photograph Album, the " + s.en() + " Family");
                    type = "album";
                } else if (u < 0.8) {
                    t = autograph(c, n, y);
                    type = "photo";
                } else {
                    t = new V.P("Стереофотографии, набор", "Set of Stereo Photographs");
                    type = "album";
                }
            }
            case "tech" -> {
                t = tech(r, y);
                type = "tech";
                c.defects = "tech";
            }
            default -> {
                t = V.pick(r, EPHEMERA);
                if (t.ru().startsWith("Пригласительный") || t.ru().startsWith("Билет") || t.ru().startsWith("Памятный")
                        || t.ru().startsWith("Меню") || t.ru().startsWith("Календарь")) t = new V.P(t.ru() + ", " + y, t.en() + ", " + y);
                type = "thing";
            }
        }
        boolean hasYear = t.ru().matches(".*\\d{4}.*");
        c.titleRu = hasYear ? Titles.fit(t.ru(), t.ru() + ". " + c.country.nameRu) : Titles.fit(t.ru() + ". " + c.country.nameRu + ", " + y, t.ru());
        c.titleEn = hasYear ? Titles.fit(t.en(), t.en() + ". " + c.country.nameEn) : Titles.fit(t.en() + ". " + c.country.nameEn + ", " + y, t.en());
        if (c.titleEn.length() < Titles.MIN) c.titleEn = Titles.fit(W.dot(t.en()) + c.country.adjEn + " Work, " + y, c.titleEn);
        if (c.titleRu.length() < Titles.MIN) c.titleRu = Titles.fit(W.dot(t.ru()) + W.cap(c.country.adjFem) + " работа, " + y, c.titleRu);
        switch (type) {
            case "poster", "programme" -> {
                String[] tp = y < 1900 ? new String[]{"литография", "хромолитография", "типографская печать"}
                        : y < 1935 ? new String[]{"литография", "хромолитография", "типографская печать", "цветная печать"}
                        : new String[]{"офсетная печать", "типографская печать", "цветная печать"};
                String tech = W.pick(r, tp);
                c.materialRu = "бумага";
                c.techniqueRu = tech;
                int a = type.equals("poster") ? r.between(40, 110) : r.between(15, 30), b = type.equals("poster") ? r.between(30, 80) : r.between(10, 22);
                c.sizeCm((double) a, (double) b, null);
                c.add("Бумага, " + tech + ". Размер " + a + " × " + b + " см");
                List<Maker> ps = c.lt.makers().of(MakerKind.PRINTER);
                for (int i = 0; i < 12; i++) {
                    Maker pr = ps.get(r.nextInt(ps.size()));
                    if (pr.country() == c.country && pr.yearFrom() <= y && pr.activeTo(Makers.NOW_YEAR) >= y) {
                        c.add("Отпечатано " + pr.namePrep());
                        break;
                    }
                }
                c.add(type.equals("poster") ? W.pick(r, new String[]{"Текст в две краски", "С портретом исполнителя в овале",
                        "Шрифтовая, с орнаментальной рамкой", "С литографированным рисунком сцены"})
                        : W.pick(r, new String[]{"В программе — состав исполнителей и краткое содержание",
                        "Внутри — фотографии участников", "Сохранилась вкладка с расписанием", "На обложке — рисунок художника"}));
            }
            case "photo" -> {
                Nation.Surname atelier = n.surname(r);
                c.materialRu = "фотобумага";
                c.techniqueRu = "серебряно-желатиновый отпечаток";
                int a = r.between(9, 30), b = r.between(6, 24);
                c.sizeCm((double) a, (double) b, null);
                c.add("Серебряно-желатиновый отпечаток, фотобумага на картоне фотоателье «" + atelier.ru() + "», " + city.name());
                if (t.ru().contains("автограф")) c.add("Автограф " + W.pick(r, new String[]{"чернилами", "карандашом"}) + " на лицевой стороне, с датой «" + y + "»");
                c.add("Размер " + a + " × " + b + " см");
            }
            case "album" -> {
                c.materialRu = "фотобумага";
                int k = r.between(12, 120);
                c.pieces = k;
                c.add("Альбом в " + W.pick(r, new String[]{"бархатном", "кожаном", "коленкоровом"}) + " переплёте, "
                        + W.count(k, "фотография", "фотографии", "фотографий") + "; фотобумага на картоне");
                c.add("Снимки " + W.decadeGen(y) + ", фотоателье " + city.gen() + " и любительские");
            }
            case "record" -> {
                c.materialRu = "шеллак";
                c.diameterMm = 250.0;
                c.add("Шеллак, диаметр 25 см, " + (r.chance(0.5) ? "односторонняя" : "двусторонняя"));
                c.add("Исполняет " + n.anyone(r, y - 30, 0.5).firstLast() + ", с оркестром");
                c.add("В бумажном конверте");
            }
            case "tech" -> {
                String[] mats = {"Дерево, латунь", "Металл, эмаль", y >= 1930 ? "Бакелит, металл" : "Чугун, дерево", "Металл, хромирование"};
                String mt = W.pick(r, mats);
                c.materialRu = mt.split(",")[0].toLowerCase(Locale.ROOT);
                c.add("Корпус — " + mt.toLowerCase(Locale.ROOT));
                c.add("На корпусе — латунная табличка фирмы с номером " + r.between(100, 99999));
                c.add(W.pick(r, new String[]{"Механизм в рабочем состоянии", "Не проверялся", "Требует наладки", "В рабочем состоянии"}));
                int a = r.between(10, 60), b = r.between(10, 50), dd = r.between(5, 40);
                c.sizeCm((double) a, (double) b, (double) dd);
                c.add("Размер " + a + " × " + b + " × " + dd + " см");
            }
            default -> {
                String[] mt = thingMaterial(t.ru());
                boolean paper = mt[1].equals("бумага") || mt[1].equals("картон");
                c.materialRu = mt[1];
                c.add(mt[0]);
                int a = paper ? r.between(10, 35) : r.between(8, 90), b = paper ? r.between(8, 25) : r.between(6, 60);
                c.sizeCm((double) a, (double) b, null);
                c.add("Размер " + a + " × " + b + " см");
                c.add(paper ? W.pick(r, new String[]{"На обороте — надпись чернилами с датой", "Сохранились следы сгиба",
                        "Память о " + W.pick(r, new String[]{"выпуске", "свадьбе", "поездке", "юбилее"}) + " " + n.anyone(r, y - 30, 0.5).initialsGen()})
                        : W.pick(r, new String[]{"Сохранилась бумажная этикетка", "Вещь хранилась в семье",
                        "Память о " + W.pick(r, new String[]{"выпуске", "свадьбе", "поездке", "юбилее"}) + " " + n.anyone(r, y - 30, 0.5).initialsGen()}));
            }
        }
        c.add(c.country.nameRu + ", " + y + ".");
        c.signed = null;
        c.add(W.pick(r, MEM_NOTE));
        if (r.chance(0.5)) c.add(W.pick(r, MEM_NOTE));
        Painting.dedupe(c);
        Condition.fill(c);
        c.provenance = Common.provenance(c, 0.85, 0.01, 0.01, 0.0);
    }

    /** Материал вещи по её названию: {текст, поле}. */
    private static String[] thingMaterial(String t) {
        if (t.contains("кожан") || t.startsWith("Саквояж")) return new String[]{"Кожа, металл", "кожа"};
        if (t.startsWith("Коньки")) return new String[]{"Сталь, кожа", "сталь"};
        if (t.startsWith("Ракетка")) return new String[]{"Дерево, струны", "дерево"};
        if (t.contains("вымпел")) return new String[]{"Шёлк, бахрома, вышивка", "шёлк"};
        if (t.startsWith("Значок")) return new String[]{"Латунь, эмаль", "латунь"};
        if (t.contains("сундук")) return new String[]{"Дерево, кожа, металлическая оковка", "дерево"};
        if (t.contains("несессер")) return new String[]{"Кожа, стекло, металл", "кожа"};
        if (t.startsWith("Жестяная")) return new String[]{"Жесть, литография", "жесть"};
        if (t.startsWith("Коробка")) return new String[]{"Картон, литография", "картон"};
        if (t.startsWith("Веер")) return new String[]{"Перламутр, шёлк", "перламутр"};
        if (t.contains("бинокль")) return new String[]{"Латунь, перламутр, стекло", "латунь"};
        return new String[]{"Бумага, печать", "бумага"};
    }

    private static V.P autograph(Ctx c, Nation n, int y) {
        Rng r = c.r;
        V.P role = V.pick(r, PERFORMER);
        String[] f = role.ru().split("\\s*:\\s*");
        boolean fem = f[1].equals("f");
        Person p = n.person(r, fem, y - r.between(20, 50), n.surname(r), c.country);
        return new V.P("Фотография с автографом " + f[0] + " " + p.initialsGen(), "Signed Photograph of the " + role.en() + " " + p.initialsEn());
    }
}
