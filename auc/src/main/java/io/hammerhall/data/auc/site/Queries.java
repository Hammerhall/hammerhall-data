package io.hammerhall.data.auc.site;

import io.hammerhall.data.core.random.Rng;

import java.util.Locale;
import java.util.Map;

/**
 * Поисковые запросы посетителей — из полей лота: категория (предмет), мастер, материал или эпоха; 1–5 слов,
 * ≈ 5 % — с опечаткой, ≈ 3 % — латиницей (транслит), часть — синонимом. Число найденного — по каталогу открытых торгов
 * на момент запроса: лоты той же категории или мастера, видимые в эти сутки (опечатка и транслит находят меньше).
 */
final class Queries {
    private Queries() {
    }

    /** Слова отдела: материал, техника, эпоха — для запросов «предмет + уточнение». */
    private static final String[][] EXTRA = {
            {"масло", "акварель", "холст", "графика", "XIX век", "XX век", "этюд", "оригинал"},
            {"золото", "серебро", "гранат", "жемчуг", "винтаж", "эмаль", "бриллиант", "старинные"},
            {"серебро", "медь", "золото", "XVIII век", "XIX век", "редкая", "пробная", "набор"},
            {"XVIII век", "первое издание", "автограф", "гравюры", "переплёт", "старопечатная", "карта", "прижизненное"},
            {"фарфор", "серебро", "бронза", "хрусталь", "модерн", "ампир", "пара", "клеймо"},
            {"редкая", "коллекция", "винтаж", "1950-е", "набор", "оригинал", "серия", "старая"}};
    private static final String[] FREE = {"редкий", "старинный", "антикварный", "недорого", "коллекционный", "купить",
            "аукцион", "лот"};
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("ваза", "кашпо"), Map.entry("гравюра", "офорт"), Map.entry("брошь", "брошка"),
            Map.entry("часы", "хронометр"), Map.entry("книга", "издание"), Map.entry("атлас", "карты"),
            Map.entry("рубль", "монета рубль"), Map.entry("сервиз", "набор посуды"),
            Map.entry("открытка", "почтовая карточка"), Map.entry("подсвечник", "канделябр"),
            Map.entry("кольцо", "перстень"), Map.entry("рукопись", "автограф"), Map.entry("пейзаж", "пейзажи"),
            Map.entry("портрет", "портреты"), Map.entry("медаль", "медали"), Map.entry("марка", "марки"),
            Map.entry("игрушка", "игрушки"), Map.entry("натюрморт", "натюрморты"), Map.entry("жетон", "жетоны"));

    private static final String CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] LAT = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o",
            "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"};

    /** Запрос и способ подсчёта найденного: {@code kind} — 0 по категории, 1 по мастеру, 2 по обоим. */
    record Query(String text, int by, boolean fuzzy) {
    }

    /**
     * Запрос, прошедший проверку публичных имён ({@code PublicNames}): опечатка или транслит изредка складывают
     * внутреннее имя платформы — тогда запрос — просто предмет. {@code clean} — память проверенных (чистая функция).
     */
    static Query make(SiteInput in, int lot, int dept, Rng r, java.util.Map<String, Boolean> clean) {
        Query q = make(in, lot, dept, r);
        if (isClean(q.text(), clean)) return q;
        String noun = noun(in, in.lCat[lot]);
        return new Query(isClean(noun, clean) ? noun : "лот", 0, false);
    }

    private static boolean isClean(String q, java.util.Map<String, Boolean> clean) {
        Boolean b = clean.get(q);
        if (b == null) {
            if (clean.size() > 200_000) clean.clear();
            b = !io.hammerhall.data.core.check.PublicNames.isInternal(q);
            clean.put(q, b);
        }
        return b;
    }

    static Query make(SiteInput in, int lot, int dept, Rng r) {
        int cat = in.lCat[lot], maker = in.lMaker[lot];
        String noun = noun(in, cat);
        String mk = maker > 0 && maker < in.makerName.length ? in.makerName[maker] : null;
        double u = r.nextDouble();
        String q;
        int by;
        if (u < 0.33 || (mk == null && u < 0.58)) {
            q = r.chance(0.25) ? SYNONYMS.getOrDefault(noun, noun) : noun;
            by = 0;
        } else if (u < 0.58) {
            q = mk;
            by = 1;
        } else if (u < 0.72 && mk != null) {
            q = r.chance(0.5) ? mk + " " + noun : noun + " " + mk;
            by = 2;
        } else if (u < 0.9) {
            String[] ex = EXTRA[Math.floorMod(dept, EXTRA.length)];
            q = noun + " " + ex[r.nextInt(ex.length)];
            by = 0;
        } else {
            q = FREE[r.nextInt(FREE.length)] + " " + noun;
            by = 0;
        }
        if (r.chance(0.85)) q = q.toLowerCase(Locale.ROOT);
        boolean fuzzy = false;
        double v = r.nextDouble();
        if (v < 0.05) {
            q = typo(q, r);
            fuzzy = true;
        } else if (v < 0.08) {
            q = translit(q);
            fuzzy = true;
        }
        return new Query(q, by, fuzzy);
    }

    /** Предмет категории — одно-два первых слова названия, строчными. */
    static String noun(SiteInput in, int cat) {
        String n = cat > 0 && cat < in.catName.length && in.catName[cat] != null ? in.catName[cat] : "лот";
        n = n.toLowerCase(Locale.ROOT);
        int sp = n.indexOf(' ');
        if (sp > 0) {
            int sp2 = n.indexOf(' ', sp + 1);
            n = sp2 > 0 ? n.substring(0, sp2) : n;
        }
        return n.replace(",", "");
    }

    private static String typo(String q, Rng r) {
        if (q.length() < 4) return q + q.charAt(q.length() - 1);
        StringBuilder sb = new StringBuilder(q);
        int i = 1 + r.nextInt(sb.length() - 2);
        if (sb.charAt(i) == ' ') i--;
        switch (r.nextInt(4)) {
            case 0 -> {
                char c = sb.charAt(i);
                sb.setCharAt(i, sb.charAt(i + 1));
                sb.setCharAt(i + 1, c);
            }
            case 1 -> sb.deleteCharAt(i);
            case 2 -> sb.insert(i, sb.charAt(i));
            default -> {
                char c = sb.charAt(i);
                int k = CYR.indexOf(Character.toLowerCase(c));
                if (k >= 0) sb.setCharAt(i, CYR.charAt((k + 1 + r.nextInt(3)) % CYR.length()));
                else sb.deleteCharAt(i);
            }
        }
        String s = sb.toString().strip();
        return s.isEmpty() ? q : s.replace("  ", " ");
    }

    static String translit(String q) {
        StringBuilder sb = new StringBuilder(q.length() + 8);
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            char lc = Character.toLowerCase(c);
            int k = CYR.indexOf(lc);
            if (k < 0) {
                sb.append(c);
                continue;
            }
            String t = LAT[k];
            if (c != lc && !t.isEmpty()) t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
            sb.append(t);
        }
        String s = sb.toString().strip();
        return s.isEmpty() ? "lot" : s;
    }
}
