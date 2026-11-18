package io.hammerhall.data.auc.catalog;

import io.hammerhall.data.auc.people.Names;
import io.hammerhall.data.auc.people.Translit;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Упрощённые тексты для тестов модуля (быстрее словаря): простые, но правильные по полям — заголовок 20–120 знаков, описание от 200, состояние по шкале
 * раздела, годы внутри датировки, мастер в описании, происхождение одной записью {@code chain}. Мастера — по 60 на
 * отдел, выдуманные. В наборе — {@link VocabTexts}.
 */
final class StubTexts implements Texts {
    static final String[] GENERAL = {"excellent", "very_good", "good", "fair", "poor"};
    static final String[] COIN = {"unc", "au", "xf", "vf", "f", "vg"};
    static final String[] STAMP = {"mnh", "mh", "used"};
    static final String[] ATTR = {"by", "attributed", "workshop", "circle", "follower", "manner", "after"};
    static final double[] ATTR_W = {0.56, 0.12, 0.1, 0.06, 0.05, 0.06, 0.05};
    static final Map<Dept, String[]> KIND = new EnumMap<>(Dept.class);

    static {
        KIND.put(Dept.PAINTING, new String[]{"artist", "artist", "sculptor"});
        KIND.put(Dept.JEWELRY, new String[]{"jeweler", "watchmaker"});
        KIND.put(Dept.COINS, new String[]{"mint", "medalist"});
        KIND.put(Dept.BOOKS, new String[]{"publisher", "printer", "cartographer"});
        KIND.put(Dept.DECORATIVE, new String[]{"porcelain", "silversmith", "furniture", "glass", "foundry"});
        KIND.put(Dept.COLLECTIBLES, new String[]{"toymaker", "printer"});
    }

    private final RandomStreams.Stream stream;
    private final HouseRules house;
    private final List<Maker> makers = new ArrayList<>();
    private final Map<Dept, List<Maker>> byDept = new EnumMap<>(Dept.class);

    StubTexts(RandomStreams streams, HouseRules house) {
        this.stream = streams.stream("catalog.text");
        this.house = house;
        RandomStreams.Stream ms = streams.stream("catalog.makers");
        Set<String> names = new HashSet<>();
        for (Dept d : Dept.values()) {
            List<Maker> l = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                Rng r = ms.at(d.id(), i);
                String kind = KIND.get(d)[i % KIND.get(d).length];
                boolean person = kind.equals("artist") || kind.equals("sculptor") || kind.equals("jeweler") || kind.equals("medalist");
                int from = 1780 + r.nextInt(180);
                Integer to = person ? (from + 40 + r.nextInt(45) > 2025 ? null : from + 40 + r.nextInt(45)) : (r.chance(0.5) ? from + 20 + r.nextInt(80) : null);
                if (to != null && to > 2025) to = null;
                Maker m;
                if (person) {
                    Names.Person p = Names.person(r, r.chance(0.2), 0.7);
                    String name = p.first() + (p.patronymic() == null ? "" : " " + p.patronymic()) + " " + p.last();
                    if (!names.add(name)) continue;
                    m = new Maker(makers.size() + 1, kind, d, true, name, Translit.en(name), p.first(), p.patronymic(),
                            p.last(), from, to, "ru", "Санкт-Петербург", d == Dept.PAINTING ? "реализм" : null,
                            d == Dept.PAINTING ? "пейзажист" : "мастер", "подпись");
                } else {
                    String last = Names.SURNAMES_PUBLIC.get(r.nextInt(Names.SURNAMES_PUBLIC.size()));
                    String name = workshop(kind) + " " + last.charAt(0) + ". " + Names.genitive(last);
                    if (!names.add(name)) continue;
                    m = new Maker(makers.size() + 1, kind, d, false, name, Translit.en(name), null, null, null, from, to,
                            "ru", kind.equals("mint") ? null : "Москва", null, "изделия отдела", "клеймо");
                }
                makers.add(m);
                l.add(m);
            }
            byDept.put(d, l);
        }
    }

    private static String workshop(String kind) {
        return switch (kind) {
            case "mint" -> "Монетный двор";
            case "publisher" -> "Издательство";
            case "printer" -> "Типография";
            case "cartographer" -> "Картографическое заведение";
            case "porcelain" -> "Фарфоровый завод";
            case "silversmith" -> "Серебряная фабрика";
            case "furniture" -> "Мебельная мастерская";
            case "glass" -> "Стекольный завод";
            case "foundry" -> "Бронзолитейная мастерская";
            case "toymaker" -> "Фабрика игрушек";
            default -> "Мастерская";
        };
    }

    @Override
    public List<Maker> makers() {
        return makers;
    }

    @Override
    public ItemText item(Dept d, long key) {
        Rng r = stream.at(key);
        List<HouseRules.Category> leaves = house.leaves(d);
        HouseRules.Category c = leaves.get(r.nextInt(leaves.size()));
        List<Maker> ms = byDept.get(d);
        Maker m = r.chance(0.7) && !ms.isEmpty() ? ms.get(r.nextInt(ms.size())) : null;
        String attr = m == null ? "unknown" : ATTR[r.weighted(ATTR_W)];
        String[] scale = d == Dept.COINS ? COIN : c.code().equals("stamps") ? STAMP : GENERAL;
        int gi = Math.min(scale.length - 1, (int) Math.abs(r.gaussian() * 1.3));
        String grade = scale[gi];
        int level = Math.min(5, 1 + gi * 5 / scale.length);
        int year = (m != null ? m.activeFrom() + 20 : 1850) + r.nextInt(30);
        if (year > 2024) year = 2024;
        boolean about = r.chance(0.4);
        int yFrom = about ? year - 5 : year, yTo = about ? year + 5 : year;
        String dating = about ? "около " + year + " года" : year + " год";
        String who = m == null ? "Неизвестный мастер" : m.nameRu();
        String titleRu = cut(who + ". " + c.nameRu() + ". " + (about ? "Около " + year : String.valueOf(year)), 120);
        String titleEn = cut((m == null ? "Unknown maker" : m.nameEn()) + ". " + c.nameEn() + ". " + (about ? "Circa " + year : String.valueOf(year)), 120);
        String material = materialOf(d, r);
        StringBuilder desc = new StringBuilder();
        desc.append(c.nameRu()).append(". ");
        desc.append(m == null ? "Мастер не установлен. " : "Работа: " + m.nameRu() + " (" + attrRu(attr) + "). ");
        desc.append("Датировка — ").append(dating).append(". ");
        if (material != null) desc.append("Материал — ").append(material).append(". ");
        boolean coin = d == Dept.COINS;
        Integer fineness = coin && material != null && material.equals("серебро") ? 900 : null;
        if (fineness != null) desc.append("Проба ").append(fineness).append(". ");
        desc.append("Предмет из частного собрания, представлен на торгах впервые. Описание составлено специалистом отдела ")
                .append("по осмотру предмета; размеры и вес указаны по замерам при приёме.");
        int h = 40 + r.nextInt(900), w = 30 + r.nextInt(700);
        String cond = "Состояние: " + gradeRu(grade) + ". Следы времени, соответствующие возрасту предмета.";
        List<Link> prov = new ArrayList<>();
        double provShare = d == Dept.PAINTING ? 0.8 : d == Dept.COLLECTIBLES ? 0.15 : 0.45;
        if (r.chance(provShare)) {
            String owner = "собрание " + Names.genitive(Names.SURNAMES_PUBLIC.get(r.nextInt(Names.SURNAMES_PUBLIC.size())));
            prov.add(new Link("chain", "Частное собрание, Москва; " + owner + ", приобретено в " + (year + 30 + r.nextInt(60)) + " году; по наследству.",
                    null, null, "Москва", owner));
        }
        return new ItemText(c.id(), m == null ? 0 : m.id(), attr, titleRu.length() < 20 ? titleRu + " — предмет коллекции" : titleRu,
                titleEn.length() < 20 ? titleEn + " — collection item" : titleEn, desc.toString(), cond, grade, dating,
                yFrom, yTo, "Россия", material, null, coin ? null : h, coin ? null : w, null,
                coin ? 20.0 + r.nextInt(200) / 10.0 : null, coin ? 3.0 + r.nextInt(3000) / 100.0 : null,
                fineness, 1, m == null ? null : r.chance(0.7), level, prov);
    }

    private static String materialOf(Dept d, Rng r) {
        return switch (d) {
            case PAINTING -> r.chance(0.6) ? "холст, масло" : "бумага, акварель";
            case JEWELRY -> r.chance(0.5) ? "золото" : "серебро";
            case COINS -> r.chance(0.5) ? "серебро" : "медь";
            case BOOKS -> "бумага";
            case DECORATIVE -> r.chance(0.5) ? "фарфор" : "бронза";
            case COLLECTIBLES -> r.chance(0.5) ? "бумага" : "жесть";
        };
    }

    private static String attrRu(String a) {
        return switch (a) {
            case "by" -> "работа мастера";
            case "attributed" -> "приписывается";
            case "workshop" -> "мастерская";
            case "circle" -> "круг мастера";
            case "follower" -> "последователь";
            case "manner" -> "в манере мастера";
            case "after" -> "по оригиналу мастера";
            default -> "мастер неизвестен";
        };
    }

    private static String gradeRu(String g) {
        return switch (g) {
            case "excellent" -> "отличное";
            case "very_good" -> "очень хорошее";
            case "good" -> "хорошее";
            case "fair" -> "удовлетворительное";
            case "poor" -> "плохое";
            case "mnh" -> "негашёная, полный клей";
            case "mh" -> "негашёная, след наклейки";
            case "used" -> "гашёная";
            default -> g.toUpperCase(java.util.Locale.ROOT);
        };
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
