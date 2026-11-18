package io.hammerhall.data.auc.text;

import io.hammerhall.data.auc.House;
import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.random.RandomStreams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тексты и поля вещей на образце в 100 тыс. (по отделам поровну): детерминизм (и английских полей), разнообразие (доли
 * повторов названий и описаний), длины в заданных пределах, согласие текста с полями (мастер, годы, материал, размеры,
 * вес, проба), шкалы состояния, происхождение звеньями, запрещённые темы, имя дома, внутренние имена платформы
 * ({@link PublicNames}) — словами и проверкой файла целиком, пригодность для TSV.
 */
class LotTextsTest {
    static final long SEED = 20251001L;
    static final int N = 100_000;
    static LotTexts texts;
    static List<LotText> lots;
    /** Жанр живописи и графики у вещи (из заготовки); {@code null} — не картина. */
    static List<String> genres;

    @BeforeAll
    static void sample() {
        long t0 = System.nanoTime();
        texts = new LotTexts(SEED);
        lots = new ArrayList<>(N);
        genres = new ArrayList<>(N);
        Department[] ds = Department.values();
        for (int i = 0; i < N; i++) {
            Ctx c = texts.draft(ds[i % ds.length], i);
            genres.add(c.genre);
            lots.add(c.build());
        }
        System.out.printf(Locale.ROOT, "вещей: %d за %.1f с%n", N, (System.nanoTime() - t0) / 1e9);
    }

    static List<String> fields(LotText t) {
        List<String> f = new ArrayList<>(List.of(t.titleRu(), t.titleEn(), t.descriptionRu(), t.conditionRu(), t.datingRu()));
        for (String s : new String[]{t.originPlaceRu(), t.materialRu(), t.techniqueRu()}) if (s != null) f.add(s);
        for (Provenance p : t.provenance()) {
            f.add(p.textRu());
            if (p.placeRu() != null) f.add(p.placeRu());
            if (p.ownerRu() != null) f.add(p.ownerRu());
        }
        return f;
    }

    // ---------------------------------------------------------------- детерминизм

    @Test
    void sameSeedAndKeySameText() {
        LotTexts again = new LotTexts(SEED);
        Department[] ds = Department.values();
        // в обратном порядке — от порядка вызовов текст не зависит (запись сравнивается целиком, с английскими полями)
        for (int i = 5999; i >= 0; i--) assertEquals(lots.get(i), again.lot(ds[i % ds.length], i), "вещь " + i);
        // свой поток генератора: тот же Rng — та же вещь
        RandomStreams.Stream st = new RandomStreams(SEED).stream("catalog.items");
        for (int i = 0; i < 500; i++) {
            LotText a = texts.lot(Department.PAINTINGS, i, st.at(i)), b = again.lot(Department.PAINTINGS, i, st.at(i));
            assertEquals(a, b);
            assertEquals(a.titleEn(), b.titleEn());
        }
        // лист категории и мастер заданы — тоже детерминированно
        Category leaf = Category.byCode("coins.silver");
        for (int i = 0; i < 200; i++) assertEquals(texts.lot(leaf, i, st.at(1_000_000 + i)), again.lot(leaf, i, st.at(1_000_000 + i)));
        // заготовка и lot(…) — одно и то же
        assertEquals(lots.get(7), texts.draft(ds[7 % ds.length], 7).build());
    }

    @Test
    void otherSeedOtherText() {
        LotTexts other = new LotTexts(SEED + 1);
        int same = 0, sameEn = 0;
        for (int i = 0; i < 2000; i++) {
            LotText t = other.lot(Department.values()[i % 6], i);
            if (t.descriptionRu().equals(lots.get(i).descriptionRu())) same++;
            if (t.titleEn().equals(lots.get(i).titleEn())) sameEn++;
        }
        assertTrue(same < 20, "другой seed — другие тексты, совпало " + same);
        assertTrue(sameEn < 200, "другой seed — другие названия, совпало " + sameEn);
    }

    // ---------------------------------------------------------------- разнообразие и длины

    @Test
    void diversityAndLength() {
        Map<Department, Set<String>> titles = new EnumMap<>(Department.class), descs = new EnumMap<>(Department.class);
        Map<Department, Integer> count = new EnumMap<>(Department.class), withProv = new EnumMap<>(Department.class);
        Set<String> allTitles = new HashSet<>(), allTitlesEn = new HashSet<>(), allDescs = new HashSet<>();
        List<Integer> lens = new ArrayList<>(N);
        List<String> bad = new ArrayList<>();
        Map<String, int[]> provKinds = new TreeMap<>();
        for (LotText t : lots) {
            Department d = t.department();
            titles.computeIfAbsent(d, k -> new HashSet<>()).add(t.titleRu());
            descs.computeIfAbsent(d, k -> new HashSet<>()).add(t.descriptionRu());
            count.merge(d, 1, Integer::sum);
            if (!t.provenance().isEmpty()) withProv.merge(d, 1, Integer::sum);
            allTitles.add(t.titleRu());
            allTitlesEn.add(t.titleEn());
            allDescs.add(t.descriptionRu());
            lens.add(t.descriptionRu().length());
            if (t.titleRu().length() < 20 || t.titleRu().length() > 120) bad.add("title_ru " + t.titleRu().length() + ": " + t.titleRu());
            if (t.titleEn().length() < 20 || t.titleEn().length() > 120) bad.add("title_en " + t.titleEn().length() + ": " + t.titleEn());
            if (t.descriptionRu().length() < 200 || t.descriptionRu().length() > 1200) bad.add("описание " + t.descriptionRu().length() + ": " + t.key());
            if (t.conditionRu().length() < 30 || t.conditionRu().length() > 400) bad.add("состояние " + t.conditionRu().length() + ": " + t.conditionRu());
            if (t.datingRu().isBlank() || t.datingRu().length() > 80) bad.add("датировка: " + t.datingRu());
            for (Provenance p : t.provenance()) {
                if (p.textRu().length() < 30 || p.textRu().length() > 300) bad.add("звено " + p.textRu().length() + ": " + p.textRu());
                provKinds.computeIfAbsent(p.kind(), k -> new int[1])[0]++;
            }
            if (bad.size() > 20) break;
        }
        assertEquals(List.of(), bad);
        Collections.sort(lens);
        int median = lens.get(N / 2);
        double titleRepeat = 1 - allTitles.size() / (double) N, titleEnRepeat = 1 - allTitlesEn.size() / (double) N;
        double descRepeat = 1 - allDescs.size() / (double) N;
        System.out.printf(Locale.ROOT, "названий разных %d (повторов %.1f %%), по-английски %d (%.1f %%); описаний разных %d "
                        + "(повторов %.3f %%); длина описания %d…%d, медиана %d%n", allTitles.size(), 100 * titleRepeat,
                allTitlesEn.size(), 100 * titleEnRepeat, allDescs.size(), 100 * descRepeat, lens.get(0), lens.get(N - 1), median);
        StringBuilder pk = new StringBuilder("звенья происхождения:");
        for (var e : provKinds.entrySet()) pk.append(' ').append(e.getKey()).append(" — ").append(e.getValue()[0]);
        System.out.println(pk);
        for (Department d : Department.values()) {
            int n = count.get(d);
            System.out.printf(Locale.ROOT, "  %-12s вещей %d: названий разных %.1f %%, описаний разных %.2f %%, с происхождением %.0f %%%n",
                    d.code, n, 100.0 * titles.get(d).size() / n, 100.0 * descs.get(d).size() / n,
                    100.0 * withProv.getOrDefault(d, 0) / n);
            assertTrue(descs.get(d).size() >= 0.995 * n, d + ": описания повторяются — " + descs.get(d).size() + " из " + n);
            assertTrue(titles.get(d).size() >= 0.05 * n, d + ": мало разных названий — " + titles.get(d).size());
        }
        assertTrue(descRepeat < 0.002, "повторы описаний " + descRepeat);
        assertTrue(median >= 400 && median <= 520, "медиана длины описания " + median);
        double painting = withProv.get(Department.PAINTINGS) / (double) count.get(Department.PAINTINGS);
        assertTrue(painting > 0.7 && painting < 0.9, "живопись с происхождением " + painting);
    }

    /** Доля вещей с происхождением у марок и игрушек — ≈ 15 %. */
    @Test
    void provenanceShareStampsToys() {
        int n = 0, with = 0;
        for (LotText t : lots) {
            if (t.category().kind() == Kind.STAMP || t.category().kind() == Kind.TOY) {
                n++;
                if (!t.provenance().isEmpty()) with++;
            }
        }
        double share = with / (double) n;
        System.out.printf(Locale.ROOT, "марки и игрушки с происхождением: %.1f %% из %d%n", 100 * share, n);
        assertTrue(share > 0.08 && share < 0.25, "доля " + share);
    }

    // ---------------------------------------------------------------- поля и текст

    static boolean mentions(String desc, Maker m) {
        String d = desc.toLowerCase(Locale.ROOT);
        List<String> forms = new ArrayList<>(List.of(m.name(), m.nameGen(), m.shortName()));
        if (m.namePrep() != null) forms.add(m.namePrep());
        if (m.isPerson()) {
            forms.add(m.person().fullGen());
            forms.add(m.person().firstLastGen());
        }
        for (String f : forms) if (d.contains(f.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** Число в тексте — в одном из видов, как его пишут генераторы: «65», «65,5», «20,15». */
    static boolean hasNumber(String desc, double v) {
        List<String> forms = new ArrayList<>(List.of(W.dec(v, 1), W.dec(v, 2), W.cm(v)));
        if (v == StrictMath.floor(v)) forms.add(Long.toString((long) v));
        for (String f : forms) if (desc.matches("(?s).*(?<![\\d,])" + Pattern.quote(f) + "(?![\\d]|,\\d).*")) return true;
        return false;
    }

    @Test
    void fieldsAgreeWithText() {
        Pattern year = Pattern.compile("(?<!\\d)(1[6-9]\\d\\d|20\\d\\d)(?!\\d)");
        List<String> bad = new ArrayList<>();
        Set<String> provKinds = Set.of("by_descent", "private_collection", "acquired", "auction", "gallery", "exhibition",
                "literature", "expertise");
        int checked = 0;
        for (LotText t : lots) {
            String d = t.descriptionRu();
            for (String f : fields(t)) {
                if (f.isBlank() || f.indexOf('\t') >= 0 || f.indexOf('\n') >= 0 || f.indexOf('\r') >= 0 || f.indexOf('\\') >= 0) {
                    bad.add("поле не для TSV: " + t.key());
                }
                if (f.contains("  ") || f.contains(" .") || f.contains(" ,") || f.contains("..")) bad.add("пробелы/точки: " + f);
                if (f.contains("null") || f.contains("{")) bad.add("заготовка в тексте: " + f);
            }
            if (!Condition.ALL_CODES.contains(t.conditionGrade())) bad.add("шкала: " + t.conditionGrade());
            Kind k = t.category().kind();
            boolean coin = k == Kind.COIN || k == Kind.MEDAL || k == Kind.BANKNOTE;
            if (coin != List.of(Condition.COIN_GRADES).contains(t.conditionGrade())) bad.add("шкала монет: " + t.conditionGrade() + " у " + k);
            if ((k == Kind.STAMP) != List.of(Condition.STAMP_GRADES).contains(t.conditionGrade())) bad.add("шкала марок: " + t.conditionGrade());
            if (t.conditionLevel() < 1 || t.conditionLevel() > 5) bad.add("уровень " + t.conditionLevel());
            if (t.yearFrom() < 1690 || t.yearTo() > Makers.NOW_YEAR || t.yearFrom() > t.yearTo()) bad.add("годы " + t.yearFrom() + "–" + t.yearTo());
            Matcher dm = year.matcher(t.datingRu());
            while (dm.find()) {
                int y = Integer.parseInt(dm.group(1));
                if (y < t.yearFrom() || y > t.yearTo()) bad.add("датировка «" + t.datingRu() + "» вне " + t.yearFrom() + "–" + t.yearTo());
            }
            if (!t.category().isLeaf()) bad.add("не лист: " + t.category());
            if (t.category().department() != t.department()) bad.add("отдел категории: " + t.category());
            if (t.makerId() != null) {
                Maker m = texts.makers().byId(t.makerId());
                boolean kindOk = m.kind() == t.category().makerKind() || (k == Kind.BOOK && m.kind() == MakerKind.PUBLISHER);
                if (!kindOk) bad.add("мастер не того вида: " + m.kind() + " у " + t.category());
                if (t.attribution() != Attribution.UNKNOWN && !mentions(d, m)) bad.add("мастера нет в описании: " + m.name() + " — " + d);
                if (t.attribution() == Attribution.UNKNOWN) bad.add("мастер при «unknown»: " + t.key());
            } else if (t.attribution() != Attribution.UNKNOWN) {
                bad.add("атрибуция без мастера: " + t.attribution());
            }
            String dl = d.toLowerCase(Locale.ROOT);
            if (t.materialRu() != null && !dl.contains(t.materialRu().toLowerCase(Locale.ROOT))) bad.add("материал «" + t.materialRu() + "»: " + d);
            if (t.metalFineness() != null) {
                if (t.metalFineness() < 375 || t.metalFineness() > 999) bad.add("проба " + t.metalFineness());
                if (!d.contains(Integer.toString(t.metalFineness()))) bad.add("проба " + t.metalFineness() + " не в тексте: " + d);
            }
            if (t.weightG() != null && !hasNumber(d, t.weightG())) bad.add("вес " + t.weightG() + ": " + d);
            if (t.diameterMm() != null && !hasNumber(d, t.diameterMm()) && !hasNumber(d, t.diameterMm() / 10)) {
                bad.add("диаметр " + t.diameterMm() + ": " + d);
            }
            for (Integer mm : new Integer[]{t.heightMm(), t.widthMm(), t.depthMm()}) {
                if (mm != null && !hasNumber(d, mm / 10.0) && !hasNumber(d, mm)) bad.add("размер " + mm + " мм: " + d);
            }
            if (t.pieces() < 1) bad.add("предметов " + t.pieces());
            int seq = 0;
            for (Provenance p : t.provenance()) {
                if (p.seq() != ++seq) bad.add("порядок звеньев: " + t.key());
                if (!provKinds.contains(p.kind())) bad.add("вид звена: " + p.kind());
                if (p.yearFrom() != null && (p.yearFrom() > Common.LAST_EVENT_YEAR || p.yearFrom() < t.yearFrom())) {
                    bad.add("год звена " + p.yearFrom() + " у вещи " + t.yearFrom() + ": " + p.textRu());
                }
                if (p.yearFrom() != null && p.yearTo() != null && p.yearTo() < p.yearFrom()) bad.add("годы звена: " + p.textRu());
            }
            checked++;
            if (bad.size() > 20) break;
        }
        assertEquals(List.of(), bad);
        assertEquals(N, checked);
    }

    // ---------------------------------------------------------------- согласованность

    /** Направление × жанр × годы: абстракция — только у абстракционистов и не раньше 1910 года; без «пленэра» и абстракции вместе. */
    @Test
    void schoolGenreYearsAgree() {
        List<String> bad = new ArrayList<>();
        int abstracts = 0;
        for (int i = 0; i < N; i++) {
            LotText t = lots.get(i);
            String g = genres.get(i);
            if (g == null) continue;
            Maker m = t.makerId() == null ? null : texts.makers().byId(t.makerId());
            String d = t.descriptionRu().toLowerCase(Locale.ROOT);
            if (g.equals("abstract")) {
                abstracts++;
                if (t.yearFrom() < 1910) bad.add("абстракция " + t.yearFrom() + ": " + t.titleRu());
                if (m != null && !Makers.abstractArtist(m)) bad.add("абстракция у " + m.specialty() + " (" + m.school() + "): " + t.titleRu());
                if (d.contains("пленэр")) bad.add("абстракция и пленэр: " + d);
            } else if (m != null && Makers.abstractArtist(m)) {
                bad.add("абстракционист, жанр " + g + ": " + t.titleRu());
            }
            if (m != null && m.school() != null && t.attribution() == Attribution.BY) {
                Makers.Movement mv = Makers.movement(m.school());
                if (mv == null) bad.add("нет направления " + m.school());
                else if (mv.style().equals("abs") != g.equals("abstract") && !mv.style().equals("any")) {
                    bad.add("направление «" + m.school() + "» и жанр " + g + ": " + t.titleRu());
                }
            }
            if ((d.contains("абстракцион") || d.contains("абстрактн")) && !g.equals("abstract")) bad.add("абстракция в тексте при жанре " + g + ": " + d);
            if (m != null && m.kind() == MakerKind.ARTIST && t.category().kind() == Kind.PAINTING_OIL) {
                Makers.Specialty sp = Makers.specialty(m.specialty());
                boolean genreSpec = sp != null && List.of(Painting.GENRES).contains(sp.genre());
                if (genreSpec && !sp.genre().equals(g) && d.contains(" " + m.specialty())) bad.add("«" + m.specialty() + "» при жанре " + g + ": " + d);
                if (sp != null && !genreSpec && d.contains(" " + m.specialty() + ",") ) bad.add("«" + m.specialty() + "» у живописи: " + d);
            }
            if (bad.size() > 20) break;
        }
        assertEquals(List.of(), bad);
        assertTrue(abstracts > 100, "абстракций " + abstracts);
    }

    /** У выставок, литературы и экспертиз владельца нет. */
    @Test
    void noOwnerForExhibitionsLiteratureExpertise() {
        int links = 0;
        for (LotText t : lots) {
            for (Provenance p : t.provenance()) {
                if (p.kind().equals("exhibition") || p.kind().equals("literature") || p.kind().equals("expertise")) {
                    links++;
                    assertEquals(null, p.ownerRu(), p.kind() + ": " + p.textRu());
                }
            }
        }
        assertTrue(links > 1000, "звеньев выставок, литературы, экспертиз: " + links);
    }

    /** Слова о формате и основе следуют из размеров и материала: «полотно», «подрамник» — у холста; крупный формат — от 120 см. */
    @Test
    void formatAndSupportWordsAgree() {
        List<String> bad = new ArrayList<>();
        int large = 0;
        for (LotText t : lots) {
            Kind k = t.category().kind();
            if (k != Kind.PAINTING_OIL && k != Kind.DRAWING && k != Kind.PRINT) continue;
            String all = (t.descriptionRu() + " " + t.conditionRu()).toLowerCase(Locale.ROOT);
            double side = Math.max(t.heightMm() == null ? 0 : t.heightMm(), t.widthMm() == null ? 0 : t.widthMm()) / 10.0;
            boolean canvas = "холст".equals(t.materialRu());
            if ((all.contains("полотн") || all.contains("подрамник") || all.contains("дублир")) && !canvas) {
                bad.add("холст в тексте, основа «" + t.materialRu() + "»: " + all);
            }
            if (all.contains("крупноформат") || all.contains("масштабная станковая") || all.contains("большое станковое")) {
                large++;
                if (!canvas || side < 120) bad.add("крупный формат при " + t.materialRu() + " " + side + " см: " + all);
            }
            if ((all.contains("камерн") || all.contains("небольш")) && side > 60) bad.add("малый формат при " + side + " см: " + all);
            if (bad.size() > 20) break;
        }
        assertEquals(List.of(), bad);
        assertTrue(large > 20, "крупноформатных: " + large);
    }

    // ---------------------------------------------------------------- содержание

    /**
     * Запрещённые темы, открытым текстом — нейтральные (с начала слова): вино и крепкое, оружие, война, религия. Остальные
     * темы — {@link #HIDDEN_STEM} и {@link #HIDDEN_WORD}.
     */
    static final Pattern FORBIDDEN = Pattern.compile("(?iu)(?<!\\p{L})("
            + "вин(о|а|у|ом|е|ный|ная|ное|ные)(?!\\p{L})|винн|виногр|коньяк|водк|виски|ликёр|ликер|шампанск|портвейн|алкогол|спиртн|"
            + "пив(о|а|ом)(?!\\p{L})|бокал|рюмк|графин|штоф|чарк|"
            + "оруж|сабл|шпаг|кинжал|пистолет|ружь|ружей|револьвер|штык|клинок|кортик|патрон|пушк(а|и|у|ой)(?!\\p{L})|меч(а|ом|и)?(?!\\p{L})|"
            + "войн|военн|арми|солдат|"
            + "икон|церк|храм|религ"
            + ")");
    static final Pattern FORBIDDEN_EN = Pattern.compile("(?i)\\b(wine|vodka|whisk(e)?y|liqueur|brandy|beer|weapon|sword|sabre|"
            + "dagger|pistol|rifle|gun|bayonet|war|army|military|soldier|church|icon|religio\\w*)\\b");
    /**
     * Прочие запрещённые темы — отпечатками, как внутренние имена в {@code PublicNames}: сам список в открытом коде не
     * пишется. Отпечаток — первые 16 знаков sha256 основы в нижнем регистре. {@code HIDDEN_STEM} — с начала слова: у
     * каждого слова текста сверяются его начала длиной от {@link #STEM_MIN} до {@link #STEM_MAX} знаков (все длины основ
     * набора); {@code HIDDEN_WORD} — слово целиком.
     */
    static final Set<String> HIDDEN_STEM = Set.of(
            "0099e8518a55cea0", "0bad1136a07245ad", "17cc9ffee979e489", "250ca5f634761438", "2d89f5d462a7b85d",
            "346b1f894918b8c1", "38c600eb474c0839", "3d4095ffdf3332d3", "424f0b3bc7fa8a5d", "4fa90540847174ca",
            "542c3c630f506623", "55c56a2ef11c223a", "699b8c2b3a02a6ed", "6b4a9e6d7e2bc7a8", "719e14f27d779c10",
            "75752f9628df7fe2", "7ff831471486d376", "804a8c96b1e93d27", "83797b4f3450a625", "893b9d4722a8987e",
            "945ecede56a1064f", "997d44c81bb0c250", "9c2c323608b838b3", "9c84b7def28ca583", "ac092ab0b10c0501",
            "aeb6d1fc5ec80490", "b5c5fa3f74b47bb7", "b79c4a4df06df348", "b7e1d4f3fcec88be", "bc776c628a60e184",
            "bcf4844421df2dfe", "c84cc59223a57a3b", "c9dae648be5fa23e", "cd727563977ee841", "d4d8457a44dcf61c",
            "d8f5fda488169474", "db791344a891dcb9", "f4ec40292a1f0ff7", "f9f0f735efcac4e5", "fca9d7b84c8fa290",
            "fd6f0a815733b600");
    static final Set<String> HIDDEN_WORD = Set.of(
            "32ded712566e352e", "3ad9075ff1e87180", "52f3141f5cb06025", "a8adf040d5380e11", "d0924987f8b8de57",
            "e43b64ac515180de", "f687c689ea5eac51");
    static final int STEM_MIN = 4, STEM_MAX = 10;

    static String fingerprint(String word) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(word.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Первое слово текста из скрытых тем (в нижнем регистре); {@code null} — нет. */
    static String hidden(String text) {
        if (text == null) return null;
        String s = text.toLowerCase(Locale.ROOT);
        int n = s.length();
        for (int i = 0; i < n; ) {
            if (!Character.isLetter(s.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && Character.isLetter(s.charAt(j))) j++;
            String w = s.substring(i, j);
            if (HIDDEN_WORD.contains(fingerprint(w))) return w;
            for (int len = STEM_MIN; len <= Math.min(STEM_MAX, w.length()); len++) {
                if (HIDDEN_STEM.contains(fingerprint(w.substring(0, len)))) return w;
            }
            i = j;
        }
        return null;
    }

    @Test
    void noForbiddenTopics() {
        Map<String, String> hits = new HashMap<>();
        for (LotText t : lots) {
            for (String f : fields(t)) {
                Matcher m = FORBIDDEN.matcher(f);
                if (m.find()) hits.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), f);
            }
            Matcher e = FORBIDDEN_EN.matcher(t.titleEn());
            if (e.find()) hits.putIfAbsent(e.group(1).toLowerCase(Locale.ROOT), t.titleEn());
            for (String f : fields(t)) {
                String h = hidden(f);
                if (h != null) hits.putIfAbsent(h, f);
            }
        }
        for (Maker m : texts.makers().all()) {
            Matcher e = FORBIDDEN_EN.matcher(m.nameEn());
            if (e.find()) hits.putIfAbsent(e.group(1), m.nameEn());
            Matcher r = FORBIDDEN.matcher(m.name());
            if (r.find()) hits.putIfAbsent(r.group(1), m.name());
            for (String f : new String[]{m.name(), m.nameEn()}) {
                String h = hidden(f);
                if (h != null) hits.putIfAbsent(h, f);
            }
        }
        assertEquals(Map.of(), hits);
    }

    /** Имени дома в данных нет: торги дома в происхождении — «торги нашего дома». */
    @Test
    void houseNameNotInData() {
        int own = 0;
        for (LotText t : lots) {
            for (String f : fields(t)) {
                assertTrue(!f.contains(House.NAME) && !f.contains(House.GEN) && !f.contains(House.PREP) && !f.contains(House.NAME_EN), f);
            }
            for (Provenance p : t.provenance()) if (p.textRu().startsWith("Торги нашего дома")) own++;
        }
        assertTrue(own > N / 500, "торги дома в происхождении: " + own);
        System.out.println("торги нашего дома в происхождении: " + own + " звеньев");
    }

    @Test
    void noInternalNamesInSample(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        // по словам: каждое разное слово всех полей — и как есть, и латиницей
        Set<String> words = new HashSet<>();
        StringBuilder all = new StringBuilder(96 << 20);
        for (LotText t : lots) {
            for (String f : fields(t)) {
                all.append(f).append('\n');
                for (String w : f.split("[^\\p{L}]+")) if (!w.isEmpty()) words.add(w);
            }
        }
        for (Maker m : texts.makers().all()) {
            for (String n : new String[]{m.name(), m.nameEn()}) {
                all.append(n).append('\n');
                for (String w : n.split("[^\\p{L}]+")) if (!w.isEmpty()) words.add(w);
            }
            if (m.bioRu() != null) all.append(m.bioRu()).append('\n');
        }
        List<String> hits = new ArrayList<>();
        for (String w : words) if (PublicNames.isInternal(w) || PublicNames.isInternal(W.latin(w))) hits.add("слово #" + w.length());
        assertEquals(List.of(), hits, "внутренние имена в текстах (печатаем только длину слова)");
        Path f = tmp.resolve("lots.txt");
        Files.writeString(f, all, StandardCharsets.UTF_8);
        PublicNames.Scan scan = PublicNames.scan(f);
        assertTrue(scan.clean(), "PublicNames.scan: " + scan.hits());
        System.out.println("PublicNames: " + scan.words() + " разных слов в " + N + " вещах — чисто");
    }

    @Test
    void everyLeafProducesItems() {
        RandomStreams.Stream st = new RandomStreams(SEED).stream("leaf");
        for (Category c : Category.all()) {
            if (!c.isLeaf()) continue;
            Set<String> titles = new HashSet<>();
            for (int i = 0; i < 300; i++) {
                LotText t = texts.lot(c, i, st.at(c.id(), i));
                assertEquals(c, t.category());
                titles.add(t.titleRu());
            }
            assertTrue(titles.size() >= 30, c + ": разных названий из 300 — " + titles.size());
        }
    }

    @Test
    void wrongMakerKindRejected() {
        Maker painter = texts.makers().of(MakerKind.ARTIST).get(0);
        try {
            texts.lot(Category.byCode("coins.gold"), painter, 1, new RandomStreams(1).stream("x").at(1));
            throw new AssertionError("ожидали отказ");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("не для листа"));
        }
    }
}
