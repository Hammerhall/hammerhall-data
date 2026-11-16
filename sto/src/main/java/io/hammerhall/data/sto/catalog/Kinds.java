package io.hammerhall.data.sto.catalog;

import io.hammerhall.data.core.time.RuCalendar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Виды товаров ({@code kinds.tsv}) и справочник характеристик ({@code spec-keys.tsv}). Вид — архетип товара: цена,
 * габариты, гарантия, смена поколений, сезон, характеристики и их варианты. Формат строк — в шапке kinds.tsv.
 */
public final class Kinds {

    public record SpecKey(String key, String nameRu, String nameEn, String unit, String unitRu, boolean numeric) {
    }

    public enum Level {LINE, GEN, VARIANT, PRODUCT}

    public enum VType {RANGE, NUMLIST, TEXTLIST, YN, DERIVED}

    /** Описание одной характеристики вида. */
    public static final class Spec {
        public String key;
        public Level level = Level.GEN;
        public VType type;
        public double lo, hi, step;
        public double[] nums;
        public String[] ru, en;
        public double yes;
        public String ref;
        public double factor;
        public int nMin = 1, nMax = 1;
        public double elasticity;
        public boolean size;
        public boolean tier;
        public SpecKey info;

        /** Число возможных значений (для списков и диапазона). */
        public int cardinality() {
            return switch (type) {
                case NUMLIST -> nums.length;
                case TEXTLIST -> ru.length;
                case RANGE -> (int) StrictMath.round((hi - lo) / step) + 1;
                case YN -> 2;
                case DERIVED -> 1;
            };
        }

        public double numAt(int i) {
            return type == VType.NUMLIST ? nums[i] : lo + i * step;
        }
    }

    /** Вид товара. */
    public static final class Kind {
        public String code, nameRu, nameEn, group;
        public double price, sigma;
        public int weightG, lengthMm, widthMm, heightMm;
        public int[] warranty;
        public int cadenceMonths;
        public int target;
        public RuCalendar.Season season;
        public String feeClass;
        public double share3p = 0.3;
        public List<String> tiers = List.of("budget", "mid", "premium");
        public List<String> compat = List.of();
        public int cpmMin, cpmMax;
        public final List<Spec> specs = new ArrayList<>();

        public boolean isCompat() {
            return !compat.isEmpty();
        }

        public List<Spec> variantSpecs() {
            List<Spec> out = new ArrayList<>();
            for (Spec s : specs) if (s.level == Level.VARIANT) out.add(s);
            return out;
        }

        public Spec spec(String key) {
            for (Spec s : specs) if (s.key.equals(key)) return s;
            return null;
        }

        /** Ожидаемое число вариантов поколения (для расчёта числа линеек). */
        public double expectedVariants() {
            double v = 1;
            for (Spec s : variantSpecs()) v *= (s.nMin + s.nMax) / 2.0;
            return v;
        }
    }

    private final Map<String, SpecKey> keys = new LinkedHashMap<>();
    private final Map<String, Kind> kinds = new LinkedHashMap<>();

    public Map<String, SpecKey> keys() {
        return keys;
    }

    public Map<String, Kind> kinds() {
        return kinds;
    }

    public Kind kind(String code) {
        Kind k = kinds.get(code);
        if (k == null) throw new IllegalArgumentException("нет вида " + code);
        return k;
    }

    static List<String> lines(String resource) throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = Kinds.class.getResourceAsStream("/io/hammerhall/data/sto/" + resource)) {
            if (in == null) throw new IOException("нет ресурса " + resource);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) out.add(l);
        }
        return out;
    }

    public static Kinds load() throws IOException {
        Kinds k = new Kinds();
        List<String> sk = lines("spec-keys.tsv");
        for (int i = 1; i < sk.size(); i++) {
            if (sk.get(i).isBlank()) continue;
            String[] f = sk.get(i).split("\t", -1);
            k.keys.put(f[0], new SpecKey(f[0], f[1], f[2], f[3].isEmpty() ? null : f[3], f[4].isEmpty() ? null : f[4],
                    f[5].equals("num")));
        }
        Map<String, String> palettes = new LinkedHashMap<>();
        for (String l : lines("kinds.tsv")) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] f = l.split("\t", -1);
            if (l.startsWith("$")) {
                palettes.put(f[0], f[1]);
                continue;
            }
            if (f.length != 14) throw new IOException("kinds.tsv: полей " + f.length + " у " + f[0]);
            Kind x = new Kind();
            x.code = f[0];
            x.nameRu = f[1];
            x.nameEn = f[2];
            x.group = f[3];
            x.price = Double.parseDouble(f[4]);
            x.sigma = Double.parseDouble(f[5]);
            x.weightG = Integer.parseInt(f[6]);
            String[] d = f[7].split("x");
            x.lengthMm = Integer.parseInt(d[0]);
            x.widthMm = Integer.parseInt(d[1]);
            x.heightMm = Integer.parseInt(d[2]);
            String[] w = f[8].split(",");
            x.warranty = new int[w.length];
            for (int i = 0; i < w.length; i++) x.warranty[i] = Integer.parseInt(w[i]);
            x.cadenceMonths = Integer.parseInt(f[9]);
            x.target = Integer.parseInt(f[10]);
            x.season = RuCalendar.Season.valueOf(f[11]);
            if (!f[12].isEmpty()) {
                for (String flag : f[12].split(",")) {
                    String[] kv = flag.split("=", 2);
                    switch (kv[0]) {
                        case "fee" -> x.feeClass = kv[1];
                        case "p3" -> x.share3p = Double.parseDouble(kv[1]);
                        case "tiers" -> x.tiers = List.of(kv[1].split(";"));
                        case "compat" -> x.compat = List.of(kv[1].split(";"));
                        case "cpm" -> {
                            String[] mm = kv[1].split("-");
                            x.cpmMin = Integer.parseInt(mm[0]);
                            x.cpmMax = Integer.parseInt(mm[1]);
                        }
                        default -> throw new IOException(x.code + ": неизвестный флаг " + flag);
                    }
                }
            }
            for (String sp : f[13].split(";")) x.specs.add(parseSpec(x.code, sp.trim(), palettes, k.keys));
            k.kinds.put(x.code, x);
        }
        for (Kind x : k.kinds.values()) {
            for (String c : x.compat) if (!k.kinds.containsKey(c)) throw new IOException(x.code + ": compat " + c);
            for (Spec s : x.specs) {
                if (s.type == VType.DERIVED && x.spec(s.ref) == null) throw new IOException(x.code + ": &" + s.ref);
            }
        }
        return k;
    }

    private static final Pattern SPEC = Pattern.compile(
            "([a-z0-9_]+)(?:@([lgvp]))?=(.+?)(?:#(\\d+)-(\\d+))?(?:\\*([0-9.]+))?(!)?(~)?");

    static Spec parseSpec(String kind, String s, Map<String, String> palettes, Map<String, SpecKey> keys) throws IOException {
        Matcher m = SPEC.matcher(s);
        if (!m.matches()) throw new IOException(kind + ": не разобрать характеристику «" + s + "»");
        Spec x = new Spec();
        x.key = m.group(1);
        x.info = keys.get(x.key);
        if (x.info == null) throw new IOException(kind + ": нет ключа " + x.key + " в spec-keys.tsv");
        if (m.group(2) != null) {
            x.level = switch (m.group(2)) {
                case "l" -> Level.LINE;
                case "v" -> Level.VARIANT;
                case "p" -> Level.PRODUCT;
                default -> Level.GEN;
            };
        }
        String v = m.group(3);
        if (v.startsWith("$")) {
            String p = palettes.get(v);
            if (p == null) throw new IOException(kind + ": нет палитры " + v);
            v = p;
        }
        if (v.startsWith("[")) {
            x.type = VType.TEXTLIST;
            String[] items = v.substring(1, v.length() - 1).split("\\|");
            x.ru = new String[items.length];
            x.en = new String[items.length];
            for (int i = 0; i < items.length; i++) {
                int eq = items[i].indexOf('=');
                x.ru[i] = eq < 0 ? items[i] : items[i].substring(0, eq);
                x.en[i] = eq < 0 ? items[i] : items[i].substring(eq + 1);
            }
        } else if (v.startsWith("{")) {
            x.type = VType.NUMLIST;
            String[] items = v.substring(1, v.length() - 1).split("\\|");
            x.nums = new double[items.length];
            for (int i = 0; i < items.length; i++) x.nums[i] = Double.parseDouble(items[i]);
        } else if (v.startsWith("yn:")) {
            x.type = VType.YN;
            x.yes = Double.parseDouble(v.substring(3));
            x.ru = new String[]{"да", "нет"};
            x.en = new String[]{"yes", "no"};
        } else if (v.startsWith("&")) {
            x.type = VType.DERIVED;
            String[] rf = v.substring(1).split(":");
            x.ref = rf[0];
            x.factor = Double.parseDouble(rf[1]);
        } else if (v.contains("..")) {
            x.type = VType.RANGE;
            String[] ab = v.split("\\.\\.");
            String[] bs = ab[1].split("/");
            x.lo = Double.parseDouble(ab[0]);
            x.hi = Double.parseDouble(bs[0]);
            x.step = Double.parseDouble(bs[1]);
        } else {
            throw new IOException(kind + ": значения «" + v + "»");
        }
        if (m.group(4) != null) {
            x.nMin = Integer.parseInt(m.group(4));
            x.nMax = Integer.parseInt(m.group(5));
        }
        if (m.group(6) != null) x.elasticity = Double.parseDouble(m.group(6));
        x.size = m.group(7) != null;
        x.tier = m.group(8) != null;
        if (x.info.numeric() != (x.type == VType.RANGE || x.type == VType.NUMLIST || x.type == VType.DERIVED)) {
            throw new IOException(kind + ": тип значения " + x.key + " не совпадает со справочником");
        }
        return x;
    }
}
