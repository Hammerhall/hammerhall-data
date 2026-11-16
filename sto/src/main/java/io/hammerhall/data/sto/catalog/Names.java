package io.hammerhall.data.sto.catalog;

import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.random.Rng;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Выдуманные имена: бренды (слоги), модельные линейки, транслитерация. Настоящих марок нет: каждое имя бренда
 * сверяется со списком известных марок и сетей электроники и бытовой техники ({@link #REAL}) — отбрасывается
 * совпадение, расстояние Левенштейна ≤ 1 и вхождение известной марки длиной от 4 букв. Список не исчерпывает мир:
 * совпадение с малоизвестной маркой где-то ещё не исключено (риск — в отчёте).
 */
public final class Names {
    private Names() {
    }

    /** Известные марки, сети, перевозчики — чего в данных быть не должно (нижний регистр, латиница). */
    static final Set<String> REAL = new TreeSet<>(List.of(
            "samsung", "apple", "xiaomi", "huawei", "honor", "realme", "oppo", "vivo", "tecno", "infinix", "itel", "nokia",
            "motorola", "zte", "alcatel", "oneplus", "poco", "redmi", "sony", "lg", "lenovo", "asus", "acer", "hp", "dell",
            "msi", "gigabyte", "razer", "logitech", "defender", "genius", "sven", "oklick", "canon", "nikon", "fujifilm",
            "olympus", "gopro", "dji", "jbl", "marshall", "sennheiser", "bose", "jabra", "edifier", "harper", "rombica",
            "prestigio", "digma", "irbis", "oysters", "texet", "ginzzu", "maunfeld", "krona", "weissgauff", "hansa",
            "kuppersberg", "korting", "smeg", "liebherr", "miele", "nord", "saturn", "sinbo", "centek", "galaxy", "endever",
            "marta", "lumme", "vitesse", "vitek", "polaris", "scarlett", "redmond", "dexp", "kitfort", "bork", "starwind",
            "supra", "hyundai", "erisson", "rubin", "horizont", "vityaz", "elenberg", "mystery", "bbk", "telefunken",
            "thomson", "sencor", "tefal", "moulinex", "rowenta", "braun", "bosch", "siemens", "gorenje", "indesit",
            "ariston", "hotpoint", "beko", "candy", "atlant", "biryusa", "pozis", "haier", "hisense", "tcl", "midea",
            "gree", "ballu", "electrolux", "timberk", "zanussi", "aeg", "whirlpool", "panasonic", "philips", "sharp",
            "toshiba", "hitachi", "daewoo", "kenwood", "delonghi", "krups", "nespresso", "dyson", "karcher", "makita",
            "dewalt", "metabo", "ryobi", "interskol", "zubr", "patriot", "stihl", "husqvarna", "gardena", "einhell",
            "huter", "fiskars", "tescoma", "rondell", "vinzer", "polaris", "garmin", "amazfit", "fitbit", "suunto",
            "huami", "kingston", "transcend", "sandisk", "seagate", "toshiba", "crucial", "corsair", "hyperx", "adata",
            "netac", "patriot", "tplink", "keenetic", "mikrotik", "dlink", "zyxel", "netgear", "tenda", "mercusys",
            "ubiquiti", "pantum", "kyocera", "brother", "epson", "ricoh", "xerox", "benq", "viewsonic", "aoc", "iiyama",
            "nvidia", "amd", "intel", "qualcomm", "mediatek", "nintendo", "xbox", "playstation", "valve", "steam",
            "pioneer", "yamaha", "denon", "onkyo", "technics", "audiotechnica", "shure", "rode", "beyerdynamic", "akg",
            "koss", "skullcandy", "anker", "baseus", "ugreen", "belkin", "hoco", "borofone", "remax", "pitaka", "spigen",
            "otterbox", "deppa", "interstep", "gerffins", "uag", "ozon", "wildberries", "yandex", "sber", "mvideo",
            "eldorado", "citilink", "technopark", "megamarket", "lamoda", "aliexpress", "avito", "lenta", "auchan",
            "leroy", "obi", "ikea", "cdek", "boxberry", "dpd", "pickpoint", "pek", "dellin", "baikal", "energia", "kit",
            "dostavista", "pochta", "mts", "beeline", "megafon", "tele2", "rostelecom", "kaspersky", "drweb", "lumix",
            "bravia", "pixel", "zenbook", "thinkpad", "pavilion", "inspiron", "xperia", "iphone", "ipad", "macbook",
            "airpods", "watch", "alisa", "marusya", "salute", "kremen", "aceline", "vist", "artel", "shivaki", "orion",
            "vesta", "lada", "tesla", "puma", "jaguar", "lynx", "fender", "gibson", "casio", "seiko", "citizen", "tissot",
            "omega", "rolex", "swatch", "zepp", "realfit", "blackview", "doogee", "ulefone", "oukitel", "cubot", "umidigi",
            "teclast", "chuwi", "alldocube", "blackberry", "kodak", "polaroid", "leica", "hasselblad", "sigma", "tamron",
            "tokina", "zeiss", "manfrotto", "insta", "segway", "ninebot", "kugoo", "xiaomi", "roborock", "dreame",
            "ecovacs", "irobot", "roomba", "tineco", "viomi", "deerma", "kyvol", "cosori", "ninja", "instantpot",
            "vitamix", "nutribullet", "oral", "philips", "waterpik", "omron", "beurer", "medisana", "laica", "and",
            "gezatone", "babyliss", "remington", "wahl", "moser", "rowenta", "tefal", "gamma", "valera", "dewal"));

    private static final String[] START = {"Ar", "Bel", "Bor", "Bra", "Cal", "Cor", "Dal", "Dor", "Dra", "El", "Fal",
            "Fen", "Gal", "Gor", "Hal", "Hel", "Il", "Jar", "Kal", "Kel", "Kor", "Kra", "Lan", "Lor", "Mal", "Mar", "Mir",
            "Nal", "Nor", "Oro", "Pal", "Pel", "Quo", "Ral", "Ren", "Sal", "Sel", "Sor", "Tal", "Tor", "Tre", "Ul", "Val",
            "Vel", "Vor", "Wen", "Yar", "Zal", "Zor", "Bri", "Cle", "Fro", "Gle", "Plo", "Ske", "Sta", "Tri", "Vin", "Ost",
            "Ask", "Ebr", "Isk", "Orv", "Umb", "Kvi", "Bru", "Dre", "Gry", "Hro", "Lyr", "Myr", "Nyr", "Pry", "Thal", "Vys"};
    private static final String[] MID = {"", "", "", "a", "e", "i", "o", "u", "ar", "en", "or", "is", "an", "el", "ov"};
    private static final String[] END = {"ra", "na", "ta", "vex", "rix", "lon", "nex", "mor", "vik", "dal", "tis", "sen",
            "ko", "no", "sa", "ven", "tra", "lix", "mar", "bel", "ron", "va", "nia", "dor", "ka", "lo", "ma", "xa", "ro",
            "za", "vel", "dra", "mex", "thorn", "wyn", "quist", "berg", "holm", "ard", "esk", "ovo", "ent", "ium", "yx"};

    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** Похоже ли имя на известную марку. */
    public static boolean resemblesReal(String name) {
        if (PublicNames.isInternal(name)) return true;
        String n = normalize(name);
        for (String r : REAL) {
            if (n.equals(r)) return true;
            if (r.length() >= 4 && Math.abs(n.length() - r.length()) <= 1 && levenshtein(n, r) <= 1) return true;
            if (r.length() >= 4 && n.contains(r)) return true;
        }
        return false;
    }

    /** Выдуманное имя бренда из слогов: 5–9 букв, без тройных согласных, не похожее на настоящее. */
    public static String brand(Rng r, Set<String> taken) {
        for (int attempt = 0; attempt < 10_000; attempt++) {
            String s = START[r.nextInt(START.length)] + MID[r.nextInt(MID.length)] + END[r.nextInt(END.length)];
            s = s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
            if (s.length() < 5 || s.length() > 9) continue;
            if (s.matches(".*[^aeiouy]{3}.*") || s.matches(".*(.)\\1\\1.*")) continue;
            if (taken.contains(normalize(s)) || resemblesReal(s)) continue;
            boolean close = false;
            for (String t : taken) if (Math.abs(t.length() - s.length()) <= 1 && levenshtein(t, normalize(s)) <= 1) close = true;
            if (close) continue;
            taken.add(normalize(s));
            return s;
        }
        throw new IllegalStateException("не придумать имя бренда");
    }

    /** Слова модельных линеек техники. */
    static final String[] LINE_WORDS = {"Aster", "Lyra", "Nimbus", "Zephyr", "Vertex", "Prism", "Flux", "Atlas", "Comet", "Breeze", "Frost",
            "Ember", "Terra", "Ridge", "Fjord", "Rhea", "Juno", "Iris", "Luna", "Sol", "Mira", "Cora", "Boreal",
            "Taiga", "Steppe", "Granit", "Basalt", "Quartz", "Agate", "Jasper", "Opal", "Topaz", "Beryl", "Argon",
            "Xenon", "Photon", "Tempo", "Canto", "Glide", "Drift", "Dash", "Lumen", "Kelvin", "Joule", "Faraday",
            "Gauss", "Euler", "Halley", "Altair", "Deneb", "Rigel", "Antares", "Draco", "Hydra", "Kite", "Falcon",
            "Hawk", "Wren", "Robin", "Finch", "Lark", "Heron", "Crane", "Ibis", "Owl", "Badger", "Otter", "Marten",
            "Sable", "Bison", "Elk", "Ibex", "Ocelot", "Vortex", "Cyclone", "Monsoon", "Sirocco", "Pampas",
            "Savanna", "Mesa", "Glacier", "Aurora", "Nadir", "Summit", "Harbor", "Beacon", "Anchor", "Sextant",
            "Kestrel", "Merlin", "Condor", "Albatross", "Petrel", "Plover", "Grove", "Cedar", "Maple", "Birch",
            "Aspen", "Larch", "Rowan", "Willow", "Alder", "Linden", "Flint", "Crucible", "Forge", "Hearth", "Kiln",
            "Ingot", "Bellows", "Temper", "Quench", "Smelt"};

    /** Слова линеек аксессуаров. */
    static final String[] ACCESSORY_WORDS = {"Shield", "Guard", "Armor", "Skin", "Clear", "Grip", "Slim", "Cover", "Wrap", "Mantle", "Cloak",
            "Sleeve", "Frame", "Bumper", "Plate", "Pure", "Basic", "Fit", "Mag", "Flex", "Soft", "Hard", "Tough",
            "Rugged", "Lite", "Core"};

    static final String[] CODE_LETTERS = {"QX", "LT", "NV", "RS", "KX", "ZT", "VM", "HX", "GT", "PX", "MX", "TX", "DX",
            "SV", "AV", "EV", "BX", "CX", "FX", "JX", "KR", "LR", "NR", "TR", "VR", "ZR", "QM", "LM", "NM", "RM"};

    static final String[] TIER_SUFFIX_PREMIUM = {" Pro", " Max", " Ultra", " Plus"};
    static final String[] TIER_SUFFIX_BUDGET = {" Lite", " Neo", " Go", " Start"};

    // ---------------------------------------------------------------- транслитерация

    private static final String[][] TR = {{"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"}, {"е", "e"},
            {"ё", "yo"}, {"ж", "zh"}, {"з", "z"}, {"и", "i"}, {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"},
            {"о", "o"}, {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"}, {"у", "u"}, {"ф", "f"}, {"х", "kh"}, {"ц", "ts"},
            {"ч", "ch"}, {"ш", "sh"}, {"щ", "shch"}, {"ъ", ""}, {"ы", "y"}, {"ь", ""}, {"э", "e"}, {"ю", "yu"}, {"я", "ya"}};

    /** Кириллица → латиница (упрощённо, как в загранпаспорте). */
    public static String toLatin(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char lc = Character.toLowerCase(c);
            String rep = null;
            for (String[] t : TR) {
                if (t[0].charAt(0) == lc) {
                    rep = t[1];
                    break;
                }
            }
            if (rep == null) {
                sb.append(c);
            } else if (Character.isUpperCase(c) && !rep.isEmpty()) {
                sb.append(Character.toUpperCase(rep.charAt(0))).append(rep.substring(1));
            } else {
                sb.append(rep);
            }
        }
        return sb.toString();
    }

    private static final String[][] TO_CYR = {{"shch", "щ"}, {"sch", "ш"}, {"ch", "ч"}, {"sh", "ш"}, {"zh", "ж"},
            {"kh", "х"}, {"ts", "ц"}, {"ya", "я"}, {"yu", "ю"}, {"yo", "ё"}, {"th", "т"}, {"ph", "ф"}, {"qu", "кв"},
            {"x", "кс"}, {"w", "в"}, {"q", "к"}, {"a", "а"}, {"b", "б"}, {"c", "к"}, {"d", "д"}, {"e", "е"}, {"f", "ф"},
            {"g", "г"}, {"h", "х"}, {"i", "и"}, {"j", "дж"}, {"k", "к"}, {"l", "л"}, {"m", "м"}, {"n", "н"}, {"o", "о"},
            {"p", "п"}, {"r", "р"}, {"s", "с"}, {"t", "т"}, {"u", "у"}, {"v", "в"}, {"y", "и"}, {"z", "з"}};

    /** Латиница → кириллица (для русских названий фирм по имени бренда). */
    public static String toCyrillic(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < low.length()) {
            boolean done = false;
            for (String[] t : TO_CYR) {
                if (low.startsWith(t[0], i)) {
                    sb.append(t[1]);
                    i += t[0].length();
                    done = true;
                    break;
                }
            }
            if (!done) sb.append(low.charAt(i++));
        }
        if (sb.isEmpty()) return sb.toString();
        return Character.toUpperCase(sb.charAt(0)) + sb.substring(1);
    }
}
