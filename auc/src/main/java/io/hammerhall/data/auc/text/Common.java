package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Общее для всех отделов: датировка (по-русски и по-английски), места торгов, происхождение — звеньями. */
final class Common {
    private Common() {
    }

    /** Последний год событий в происхождении (торги, покупки) — до «сейчас» набора. */
    static final int LAST_EVENT_YEAR = 2025;
    /** С какого года дом проводит торги — раньше в происхождении его торгов нет. */
    static final int HOUSE_SINCE = 2008;

    // ---------------------------------------------------------------- датировка

    /**
     * Датировка: опорный год и диапазон; {@code text} — как в каталоге («1912», «около 1912 года», «1910-е годы»,
     * «начало XX века»); {@code shortRu} и {@code en} — для заголовка («около 1912», «circa 1912», «1910s»).
     */
    record Dated(int year, int from, int to, String text, boolean exact, String shortRu, String en) {
        /** Предложение для описания: «1912 год.», «Около 1912 года.» */
        String line() {
            return exact ? year + " год." : W.cap(text) + ".";
        }
    }

    static Dated exact(int y) {
        return new Dated(y, y, y, Integer.toString(y), true, Integer.toString(y), Integer.toString(y));
    }

    static String ordinalEn(int c) {
        return c + (c % 10 == 1 && c != 11 ? "st" : c % 10 == 2 && c != 12 ? "nd" : c % 10 == 3 && c != 13 ? "rd" : "th");
    }

    /** Год в [lo, hi] и запись: точная доля {@code pExact}, «около» — {@code pAbout}, десятилетие — {@code pDecade}, прочее — часть века. */
    static Dated date(Rng r, int lo, int hi, double pExact, double pAbout, double pDecade) {
        if (hi < lo) hi = lo;
        int y = lo + r.nextInt(hi - lo + 1);
        double u = r.nextDouble();
        if (u < pExact) return exact(y);
        if (u < pExact + pAbout) {
            return new Dated(y, y - 3, Math.min(y + 3, Makers.NOW_YEAR), "около " + y + " года", false, "около " + y, "circa " + y);
        }
        if (u < pExact + pAbout + pDecade) return decade(y);
        return partOfCentury(r, y, lo, hi);
    }

    static Dated decade(int y) {
        int d = y / 10 * 10;
        return new Dated(y, d, Math.min(d + 9, Makers.NOW_YEAR), W.decade(y), false, d + "-е", d + "s");
    }

    private record Part(String ru, String en, int from, int to) {
    }

    /** Часть века, в которую попадает {@code y}; только те, что не выходят за [lo, hi] больше чем на 5 лет. */
    static Dated partOfCentury(Rng r, int y, int lo, int hi) {
        int c = W.century(y);
        int base = (c - 1) * 100;
        int pos = y - base;
        String cg = W.roman(c) + " века", ce = ordinalEn(c) + " century";
        List<Part> ps = new ArrayList<>();
        if (pos <= 15) ps.add(new Part("начало " + cg, "early " + ce, 1, 15));
        if (pos <= 25) ps.add(new Part("первая четверть " + cg, "first quarter of the " + ce, 1, 25));
        if (pos <= 50) ps.add(new Part("первая половина " + cg, "first half of the " + ce, 1, 50));
        if (pos > 25 && pos <= 50) ps.add(new Part("вторая четверть " + cg, "second quarter of the " + ce, 26, 50));
        if (pos >= 40 && pos <= 60) ps.add(new Part("середина " + cg, "mid-" + ce, 40, 60));
        if (pos > 50 && pos <= 75) ps.add(new Part("третья четверть " + cg, "third quarter of the " + ce, 51, 75));
        if (pos > 50) ps.add(new Part("вторая половина " + cg, "second half of the " + ce, 51, 100));
        if (pos > 75) ps.add(new Part("последняя четверть " + cg, "last quarter of the " + ce, 76, 100));
        if (pos > 85) ps.add(new Part("конец " + cg, "late " + ce, 86, 100));
        if (r.chance(0.1)) ps.add(new Part(W.roman(c) + " век", ce, 1, 100));
        ps.removeIf(p -> base + p.from < lo - 5 || base + p.to > hi + 5);
        if (ps.isEmpty()) return decade(y);
        Part p = ps.get(r.nextInt(ps.size()));
        return new Dated(y, base + p.from, Math.min(base + p.to, Makers.NOW_YEAR), p.ru, false, p.ru, p.en);
    }

    /** Годы работы мастера (или диапазон, если мастера нет), не позже «сейчас». */
    static int[] span(Maker m, int lo, int hi) {
        if (m == null) return new int[]{lo, hi};
        int a = Math.max(m.activeFrom(), lo), b = Math.min(m.activeTo(Makers.NOW_YEAR), hi);
        if (b < a) {
            // мастер работал вне периода вида — ближайший к его годам год периода
            a = Math.max(lo, Math.min(hi, m.activeFrom()));
            b = a;
        }
        return new int[]{a, b};
    }

    /** «Россия, 1880-е годы.» */
    static String placeDate(Country c, Dated d) {
        return c.nameRu + ", " + d.text() + ".";
    }

    static Nation nationOf(Country c) {
        return switch (c) {
            case RU -> Nation.RU;
            case FR, BE, CH -> Nation.FR;
            case IT -> Nation.IT;
            case DE, AT -> Nation.DE;
            case NL -> Nation.NL;
            case GB, US -> Nation.GB;
            case SE, NO, DK, FI -> Nation.SC;
            case ES -> Nation.ES;
            case PL -> Nation.PL;
            case CZ -> Nation.CZ;
        };
    }

    // ---------------------------------------------------------------- места торгов и продаж

    /** Аукционный дом, галерея или магазин: именительный (владелец звена), родительный, с предлогом места, город. */
    record Venue(String nom, String gen, String prep, City city) {
    }

    private static final String[][] HOUSE_FORMS = {{"аукционный дом", "аукционного дома", "в аукционном доме"},
            {"торговый дом", "торгового дома", "в торговом доме"}, {"аукционная контора", "аукционной конторы", "в аукционной конторе"}};
    private static final String[][] GALLERY_FORMS = {{"галерея", "галереи", "в галерее"},
            {"художественная галерея", "художественной галереи", "в художественной галерее"},
            {"художественный салон", "художественного салона", "в художественном салоне"}};
    private static final String[][] SHOP_FORMS = {{"антикварный магазин", "антикварного магазина", "в антикварном магазине"},
            {"антикварный салон", "антикварного салона", "в антикварном салоне"},
            {"антикварная лавка", "антикварной лавки", "в антикварной лавке"},
            {"букинистический магазин", "букинистического магазина", "в букинистическом магазине"}};

    static final class Venues {
        final Venue[] houses, galleries, shops;

        Venues(Rng r) {
            Set<String> seen = new HashSet<>();
            houses = venues(r, 320, HOUSE_FORMS, seen);
            galleries = venues(r, 420, GALLERY_FORMS, seen);
            shops = venues(r, 360, SHOP_FORMS, seen);
        }

        private static Venue[] venues(Rng r, int n, String[][] forms, Set<String> seen) {
            List<Venue> out = new ArrayList<>();
            double[] w = {30, 15, 6, 15, 5, 15, 8, 2, 2, 2};
            while (out.size() < n) {
                Nation nat = Nation.values()[r.weighted(w)];
                Nation.Surname sa = nat.surname(r);
                String a = sa.ru();
                if (RealNames.blocked(a) || RealNames.resemblesTrade(a) || RealNames.resemblesTrade(sa.en())) continue;
                String name;
                double u = r.nextDouble();
                if (u < 0.45) {
                    Nation.Surname sb = nat.surname(r);
                    String b = sb.ru();
                    if (b.equals(a) || RealNames.blocked(b) || RealNames.resemblesTrade(b) || RealNames.resemblesTrade(sb.en())) continue;
                    name = "«" + a + " и " + b + "»";
                } else if (u < 0.6) {
                    name = "«" + a + " и сыновья»";
                } else {
                    name = "«" + a + "»";
                }
                String[] f = forms[r.nextInt(forms.length)];
                if (!seen.add(f[0] + name)) continue;
                out.add(new Venue(f[0] + " " + name, f[1] + " " + name, f[2] + " " + name, nat.city(r)));
            }
            return out.toArray(new Venue[0]);
        }
    }

    // ---------------------------------------------------------------- происхождение

    private static final String[] PROF_ANY = V.list("common", "prov.prof");
    private static final String[] PROF_NOM = V.list("common", "prov.prof.nom");
    private static final String[] PROF_F = V.list("common", "prov.prof.female");
    private static final String[] PROF_F_NOM = V.list("common", "prov.prof.female.nom");
    private static final String[] SOCIETIES = V.list("common", "prov.societies");
    private static final String[] LOT_NOTE = V.list("common", "prov.lot.note");
    private static final String[] GENRE_GEN = V.list("common", "lit.genre.gen");

    /** Собиратель: {«доктора П. А. Сухотина», «доктор П. А. Сухотин»} — родительный для текста и именительный для поля. */
    static String[] collector(Rng r, Nation n, int born) {
        Person p = n.anyone(r, born, 0.25);
        boolean fem = p.female() && r.chance(0.4);
        int k = r.nextInt(fem ? PROF_F.length : PROF_ANY.length);
        boolean prof = r.chance(0.8);
        String gen = (prof ? (fem ? PROF_F[k] : PROF_ANY[k]) + " " : "") + p.initialsGen();
        String nom = (prof ? (fem ? PROF_F_NOM[k] : PROF_NOM[k]) + " " : "") + p.initials();
        return new String[]{gen, nom};
    }

    /** Звено до сортировки. */
    private record L(String kind, String text, Integer from, Integer to, String place, String owner) {
    }

    /**
     * Происхождение вещи: цепочка владельцев и торгов (1–3 звена, по времени), иногда — выставка, литература,
     * экспертиза. Доля вещей без цепочки — {@code pNone}; литература, выставки, экспертиза — своими долями.
     */
    static List<Provenance> provenance(Ctx c, double pNone, double pLit, double pExh, double pExpert) {
        Rng r = c.r;
        List<L> links = new ArrayList<>();
        Country home = c.country != null ? c.country : Country.RU;
        Nation n = r.chance(0.75) ? nationOf(home) : Nation.RU;
        if (!r.chance(pNone)) chain(c, r, n, links);
        if (r.chance(pExh)) links.add(exhibition(c, r, home));
        if (r.chance(pLit)) links.add(literature(c, r, home));
        if (r.chance(pExpert)) links.add(expertise(c, r, n));
        // по времени: звено без года — за предыдущим
        int[] key = new int[links.size()];
        int last = c.year;
        for (int i = 0; i < links.size(); i++) {
            L l = links.get(i);
            key[i] = l.from != null ? l.from : last;
            last = key[i];
        }
        Integer[] idx = new Integer[links.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> key[a] != key[b] ? Integer.compare(key[a], key[b]) : Integer.compare(a, b));
        List<Provenance> out = new ArrayList<>();
        for (int i = 0; i < idx.length; i++) {
            L l = links.get(idx[i]);
            out.add(new Provenance(i + 1, l.kind, W.sentence(l.text), l.from, l.to, l.place, l.owner));
        }
        return out;
    }

    private static void chain(Ctx c, Rng r, Nation n, List<L> links) {
        Venues v = c.lt.venues;
        int steps = r.chance(0.55) ? 1 : (r.chance(0.73) ? 2 : 3);
        if (c.year >= LAST_EVENT_YEAR - 1) {
            City city = n.city(r);
            links.add(new L("private_collection", "Частное собрание, " + city.name() + ", приобретено в " + LAST_EVENT_YEAR + " году", LAST_EVENT_YEAR,
                    LAST_EVENT_YEAR, city.name(), null));
            return;
        }
        int y = Math.max(c.year, 1700) + r.between(1, 30);
        boolean house = r.chance(0.1);
        String style = c.provenanceStyle;
        for (int i = 0; i < steps; i++) {
            boolean lastStep = i == steps - 1;
            if (y > LAST_EVENT_YEAR) {
                City city = n.city(r);
                int d = Math.max(c.year + 1, LAST_EVENT_YEAR - 5);
                links.add(new L("private_collection", "Частное собрание, " + city.name() + ", " + W.decade(d), d, LAST_EVENT_YEAR,
                        city.name(), null));
                return;
            }
            if (lastStep && house) {
                int hy = Math.max(Math.max(y, HOUSE_SINCE), c.year + 1);
                if (hy <= LAST_EVENT_YEAR) {
                    int yy = r.between(hy, LAST_EVENT_YEAR);
                    links.add(new L("auction", "Торги нашего дома, " + yy + ", лот № " + r.between(1, 480), yy, yy, "Москва", null));
                    return;
                }
            }
            City city = n.city(r);
            int d = Math.max(y / 10 * 10, c.year);
            int d9 = Math.min(y / 10 * 10 + 9, LAST_EVENT_YEAR);
            double u = r.nextDouble();
            if (i == 0 && c.fromArtist && c.maker != null && c.maker.isPerson() && r.chance(0.35)) {
                int ay = Math.max(c.year, Math.min(y, c.maker.activeTo(LAST_EVENT_YEAR)));
                links.add(new L("acquired", r.chance(0.5) ? "Приобретено у автора в мастерской, " + ay
                        : "Приобретено непосредственно у автора в " + ay + " году", ay, ay, c.maker.placeRu(), c.maker.name()));
            } else if (u < 0.30) {
                String[] who = collector(r, n, y - r.between(30, 60));
                String t = switch (style) {
                    case "book" -> "Из библиотеки " + who[0] + ", " + city.name() + (r.chance(0.5) ? " (экслибрис)" : ", " + W.decade(y));
                    case "coin" -> "Из коллекции " + who[0] + ", " + city.name() + ", собранной в " + W.decade(y);
                    default -> "Из собрания " + who[0] + ", " + city.name() + ", " + W.decade(y);
                };
                links.add(new L("private_collection", t, d, d9, city.name(), who[1]));
            } else if (u < 0.42) {
                Nation.Surname s = n.surname(r);
                links.add(new L("private_collection", "Из собрания семьи " + Ru.pluralGenSurname(s.ru()) + ", " + city.name() + ", "
                        + W.decade(y), d, d9, city.name(), "семья " + Ru.pluralSurname(s.ru())));
                if (!lastStep && r.chance(0.6)) links.add(new L("by_descent", "По наследству в семье владельца", null, null, null, null));
            } else if (u < 0.52) {
                links.add(new L("private_collection", "Частное собрание, " + city.name() + ", " + W.decade(y), d, d9, city.name(), null));
            } else if (u < 0.58 && !lastStep) {
                links.add(new L("by_descent", "По наследству в семье прежнего владельца", null, null, null, null));
            } else if (u < 0.74 && y >= 1780) {
                Venue h = v.houses[r.nextInt(v.houses.length)];
                links.add(new L("auction", "Торги " + h.gen() + ", " + h.city().name() + ", " + y + ", лот № " + r.between(1, 900)
                        + (r.chance(0.3) ? " (" + W.pick(r, LOT_NOTE) + ")" : ""), y, y, h.city().name(), h.nom()));
            } else if (u < 0.86 && y >= 1850 && (style.equals("art") || style.equals("deco"))) {
                Venue g = v.galleries[r.nextInt(v.galleries.length)];
                links.add(new L("gallery", "Приобретено " + g.prep() + ", " + g.city().name() + ", " + y, y, y, g.city().name(), g.nom()));
            } else if (u < 0.95 && y >= 1800) {
                Venue s = v.shops[r.nextInt(v.shops.length)];
                links.add(new L("acquired", "Приобретено " + s.prep() + ", " + s.city().name() + ", " + y, y, y, s.city().name(), s.nom()));
            } else if (y >= 1900) {
                links.add(new L("acquired", "Приобретено нынешним владельцем в " + W.decade(y), d, d9, null, null));
            } else {
                String[] who = collector(r, n, y - 40);
                links.add(new L("private_collection", "Из собрания " + who[0] + ", " + city.name() + ", " + W.decade(y), d, d9,
                        city.name(), who[1]));
            }
            y += r.between(10, 45);
        }
    }

    private static String bookTitle(Ctx c, Rng r, Country home, int year) {
        int cent = W.century(Math.max(c.year, 1701));
        String cg = W.roman(cent) + " века";
        Maker m = c.maker;
        return switch (c.provenanceStyle) {
            case "coin" -> c.kind == Kind.MEDAL
                    ? W.pick(r, new String[]{"Медали и жетоны " + cg, "Памятные медали: справочник", "Медальеры " + cg + ": словарь"})
                    : W.pick(r, new String[]{"Монеты " + cg + ": каталог", "Редкие монеты: справочник коллекционера",
                    m != null && !m.isPerson() ? "Каталог монет " + m.nameGen() : "Свод монет " + cg, "Монетное дело " + cg + ": очерки"});
            case "book" -> W.pick(r, new String[]{"Библиография редких изданий", "Иллюстрированные издания " + cg + ": каталог",
                    "Редкие книги: справочник библиофила", "Книга " + cg + ": библиографический указатель",
                    "Карты и атласы " + cg + ": каталог"});
            case "stamp" -> W.pick(r, new String[]{"Каталог почтовых марок " + year, "Почтовые марки " + cg + ": справочник",
                    "Разновидности и ошибки почтовых марок"});
            case "deco" -> W.pick(r, new String[]{c.cat.nameRu() + " " + cg, "Клейма и мастера " + cg + ": справочник",
                    m != null && !m.isPerson() ? "Каталог изделий " + m.nameGen() : "Декоративное искусство " + cg,
                    "Предметы интерьера " + cg});
            case "jewel" -> W.pick(r, new String[]{"Ювелирное искусство " + cg, "Клейма ювелиров: справочник",
                    "Часы и часовщики: справочник", "Украшения " + cg + " в частных собраниях"});
            case "toy" -> W.pick(r, new String[]{"Игрушки " + cg + ": справочник коллекционера", "Старинные игрушки",
                    "Предметы быта " + cg});
            default -> {
                List<String> t = new ArrayList<>(List.of(W.cap(home.adjFem) + " живопись " + cg, "Мастера " + W.pick(r, GENRE_GEN),
                        "Пейзаж в " + home.langPrep + " искусстве", "Забытые имена: художники провинции",
                        "Графика " + cg + " в частных собраниях"));
                if (m != null && m.isPerson()) {
                    t.add(m.person().firstLast() + ". Каталог произведений");
                    t.add(m.person().firstLast() + ": жизнь и творчество");
                }
                yield t.get(r.nextInt(t.size()));
            }
        };
    }

    private static L literature(Ctx c, Rng r, Country home) {
        Nation nat = r.chance(0.7) ? Nation.RU : nationOf(home);
        int lo = Math.min(Math.max(c.yearTo + 1, 1850), LAST_EVENT_YEAR);
        int year = r.between(lo, LAST_EVENT_YEAR);
        Person a = nat.anyone(r, year - r.between(35, 60), 0.35);
        City city = nat.city(r);
        String title = bookTitle(c, r, home, year);
        String tail = switch (c.provenanceStyle) {
            case "coin" -> "№ " + r.between(1, 2400) + (r.chance(0.4) ? ", редкость R" + r.between(1, 4) : "");
            case "book", "stamp" -> "№ " + r.between(1, 5200);
            default -> "С. " + r.between(8, 480) + (r.chance(0.6) ? ", ил. " + r.between(1, 300) : "");
        };
        String book = a.biblio() + " " + title;
        return new L("literature", "Литература: " + book + ". " + city.name() + ", " + year + ". " + tail, year, year, city.name(), null);
    }

    private static L exhibition(Ctx c, Rng r, Country home) {
        Nation nat = nationOf(home);
        int lo = Math.min(c.year + r.between(0, 3), LAST_EVENT_YEAR);
        int year = r.between(lo, Math.max(lo, Math.min(LAST_EVENT_YEAR, c.year + 120)));
        City city = nat.city(r);
        String name;
        double u = r.nextDouble();
        Maker m = c.maker;
        if (u < 0.3 && m != null && m.isPerson() && year <= m.activeTo(LAST_EVENT_YEAR)) {
            name = "Персональная выставка " + m.nameGen();
        } else if (u < 0.55 && m != null && m.isPerson()) {
            name = m.person().firstLast() + ". Ретроспектива";
            year = Math.max(year, Math.min(LAST_EVENT_YEAR, m.activeTo(LAST_EVENT_YEAR) + r.between(5, 40)));
        } else if (u < 0.8) {
            name = W.pick(r, new String[]{"Весенняя", "Осенняя", "Годовая", "Юбилейная"}) + " выставка " + W.pick(r, SOCIETIES);
        } else {
            name = W.cap(home.adjFem) + " " + W.pick(r, new String[]{"живопись", "графика", "акварель", "скульптура"})
                    + " из частных собраний";
        }
        if (year > LAST_EVENT_YEAR) year = LAST_EVENT_YEAR;
        return new L("exhibition", "Выставка «" + name + "», " + city.name() + ", " + year + (r.chance(0.6) ? ", № " + r.between(1, 450) : ""),
                year, year, city.name(), null);
    }

    private static L expertise(Ctx c, Rng r, Nation n) {
        int lo = Math.min(Math.max(c.year + 1, 1990), LAST_EVENT_YEAR);
        int year = r.between(lo, LAST_EVENT_YEAR);
        Person p = n.anyone(r, year - r.between(35, 65), 0.4);
        City city = n.city(r);
        if (c.provenanceStyle.equals("jewel") && r.chance(0.6)) {
            String lab = "геммологическая лаборатория " + city.gen();
            return new L("expertise", "Заключение: " + lab + ", " + year + ", № " + r.between(100, 99999), year, year, city.name(), null);
        }
        return new L("expertise", "Заключение эксперта " + p.initialsGen() + ", " + city.name() + ", " + year, year, year, city.name(),
                null);
    }
}
