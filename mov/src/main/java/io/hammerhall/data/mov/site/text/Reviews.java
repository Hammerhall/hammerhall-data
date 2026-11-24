package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.mov.site.text.Tpl.Slot;
import io.hammerhall.data.mov.site.text.Tpl.Slots;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Рецензии: заголовок и текст из шаблонов {@code reviews.txt} и {@code review-titles.txt}.
 *
 * <p>Текст — 2–6 предложений: начало по тону, 0–4 предложения об аспектах фильма (сюжет, темп, финал, атмосфера, музыка,
 * камера, актёры — по имени, режиссёр — по имени, сценарий, монтаж, жанр, год, оформление, герои, впечатление, жанровое
 * предложение, сравнение с другим фильмом), вердикт по тону (часто с оценкой числом). Полярность аспектов — от тона:
 * 9–10 — восторг и похвала, 7–8 — похвала и иногда мягкий минус («Правда, …»), 5–6 — плюсы и минусы вперемешку («Зато …»),
 * 3–4 — критика и иногда плюс («Справедливости ради, …»), 1–2 — резкая критика. Длина — 150–900 знаков: короткий текст
 * дополняется аспектом, длинный — теряет самое длинное среднее предложение.
 *
 * <p>Актёры и режиссёры — настоящие люди из Wikidata, а рецензии выдуманы: имя человека бывает только в похвале и
 * нейтральных предложениях; минус и критика об игре и режиссуре — безлично («режиссура местами тяжеловесна»,
 * «актёрам не хватает материала»), см. {@link Aspect#negKey}.
 */
final class Reviews {
    /** Тон рецензии по оценке. */
    enum Tone {
        /** 1–2: резко отрицательно. */
        H,
        /** 3–4: разочарование. */
        D,
        /** 5–6: средне. */
        M,
        /** 7–8: хорошо. */
        G,
        /** 9–10: восторг. */
        R;

        static Tone of(int rating) {
            if (rating <= 2) return H;
            if (rating <= 4) return D;
            if (rating <= 6) return M;
            if (rating <= 8) return G;
            return R;
        }
    }

    /** Полярность предложения об аспекте. */
    enum Pol {
        P2, P1, N1, N2;

        boolean positive() {
            return this == P2 || this == P1;
        }

        /** Та же полярность другой силы (если нужной секции нет). */
        Pol sibling() {
            return switch (this) {
                case P2 -> P1;
                case P1 -> P2;
                case N1 -> N2;
                case N2 -> N1;
            };
        }

        String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Аспекты фильма: секция словаря, вес выбора, группа (из группы — не больше одного), порядок в тексте. У аспектов с
     * именем человека (актёр, дуэт, режиссёр) минусы — из безличной секции ({@code negKey}): люди настоящие, рецензии
     * выдуманы, поэтому имя — только в похвале и нейтральных предложениях.
     */
    enum Aspect {
        GENRE("genre", 0.8, 0), GS(null, 1.1, 0), YEAR("year", 0.5, 0), PLOT("plot", 1.2, 0), HERO("hero", 0.8, 0),
        SCRIPT("script", 0.8, 0), PACE("pace", 1.0, 0), EDIT("edit", 0.4, 0), ATMO("atmo", 1.0, 0),
        CAMERA("camera", 0.8, 0), VISUAL("visual", 0.5, 0), MUSIC("music", 0.7, 0), CAST("cast", 0.6, 1),
        ACTOR("actor", "cast", 1.4, 1), DUO("duo", "cast", 0.7, 1), DIRECTION("direction", 0.5, 2),
        DIR("dir", "direction", 1.1, 2), EMOTION("emotion", 0.7, 0), ENDING("ending", 0.9, 0);

        final String key, negKey;
        final double weight;
        final int group;

        Aspect(String key, double weight, int group) {
            this(key, key, weight, group);
        }

        Aspect(String key, String negKey, double weight, int group) {
            this.key = key;
            this.negKey = negKey;
            this.weight = weight;
            this.group = group;
        }
    }

    static final int MIN = 150, MAX = 900;
    static final int MAX_TITLE = 150, MAX_NAME = 60;
    /** Соль выбора жанра по {@code movieId}. */
    private static final long GENRE_SALT = 0x5f3759df2b1e8a47L;
    /** Число предложений 2–6: доли. */
    private static final double[] COUNT_W = {0.03, 0.09, 0.22, 0.31, 0.35};
    /** Доля предложений об аспекте с «хвостом» («…, и это дорогого стоит»), если предложение короче {@link #TAIL_MAX}. */
    private static final double TAIL = 0.6;
    /** Доля начал рецензии с «хвостом» (кроме средних). */
    private static final double TAIL_OPEN = 0.4;
    private static final int TAIL_MAX = 120;
    /** Доля рецензий со сравнением, если другой фильм известен. */
    private static final double COMPARE = 0.45;

    private final List<List<GenreRu>> genres;
    private final Section[][] aspects;
    /** Жанровые предложения: [id][0 — плюс, 1 — минус]; {@code null} — у жанра нет. */
    private final Section[][] gs;
    private final Section[] open, close, compare, titles;
    private final Section connMinus, connPlus, connBut, connYet, tailP, tailN, tailOpenP, tailOpenN;

    Reviews(List<List<GenreRu>> genres) {
        this.genres = genres;
        Map<String, Section> sec = Section.load("reviews.txt");
        Map<String, Section> tit = Section.load("review-titles.txt");
        Aspect[] as = Aspect.values();
        aspects = new Section[as.length][];
        for (Aspect a : as) {
            if (a.key == null) continue;
            Section[] p = new Section[Pol.values().length];
            for (Pol x : Pol.values()) p[x.ordinal()] = need(sec, (x.positive() ? a.key : a.negKey) + "." + x.key());
            aspects[a.ordinal()] = p;
        }
        gs = new Section[GenreRu.COUNT + 1][];
        for (int id = 1; id <= GenreRu.COUNT; id++) {
            Section p = sec.get("gs." + id + ".p"), n = sec.get("gs." + id + ".n");
            if (p != null && n != null) gs[id] = new Section[]{p, n};
            else if (p != null || n != null) throw new IllegalStateException("reviews.txt: у жанра " + id + " нужны и gs.p, и gs.n");
        }
        Tone[] ts = Tone.values();
        open = new Section[ts.length];
        close = new Section[ts.length];
        compare = new Section[ts.length];
        titles = new Section[ts.length];
        for (Tone t : ts) {
            open[t.ordinal()] = need(sec, "open." + t);
            close[t.ordinal()] = need(sec, "close." + t);
            compare[t.ordinal()] = need(sec, "compare." + t);
            titles[t.ordinal()] = need(tit, "title." + t);
        }
        connMinus = need(sec, "conn.minus");
        connPlus = need(sec, "conn.plus");
        connBut = need(sec, "conn.but");
        connYet = need(sec, "conn.yet");
        tailP = need(sec, "tail.p");
        tailN = need(sec, "tail.n");
        tailOpenP = need(sec, "tail.open.p");
        tailOpenN = need(sec, "tail.open.n");
    }

    private static Section need(Map<String, Section> m, String name) {
        Section s = m.get(name);
        if (s == null) throw new IllegalStateException("нет секции [" + name + "]");
        return s;
    }

    /** Что известно для одного текста: поля шаблонов, тон, оценка, выбранный жанр. */
    static final class Ctx {
        final Slots s = new Slots();
        Tone tone;
        int rating;
        /** Выбранный для текста жанр (0 — нет). */
        int genre;
        /** «Хвосты», уже взятые в текущем тексте: второй раз — нет. */
        final java.util.Set<Tpl> tails = new java.util.HashSet<>();
    }

    /** Поля из контекста: значения со стороны чистятся, длинные и с внутренними именами — молчат. */
    Ctx ctx(Rng r, ReviewContext c) {
        Ctx x = new Ctx();
        x.rating = c == null ? 7 : Math.max(1, Math.min(10, c.rating()));
        x.tone = Tone.of(x.rating);
        Slots s = x.s;
        if (c == null) {
            numbers(x);
            return x;
        }
        String title = Tx.clean(c.titleRu(), MAX_TITLE);
        if (title == null) title = Tx.clean(c.title(), MAX_TITLE);
        if (title != null) s.set(Slot.T, Tx.quote(title));
        String other = Tx.clean(c.otherTitle(), MAX_TITLE);
        if (other != null && !other.equals(title) && !other.equals(Tx.clean(c.title(), MAX_TITLE))) s.set(Slot.O, Tx.quote(other));
        String dir = Tx.clean(c.director(), MAX_NAME);
        if (dir != null && dir.indexOf(',') < 0 && dir.indexOf(';') < 0) s.set(Slot.D, dir);
        List<String> actors = new ArrayList<>();
        if (c.actors() != null) {
            for (String a : c.actors()) {
                String v = Tx.clean(a, MAX_NAME);
                if (v != null && v.indexOf(',') < 0 && !actors.contains(v) && !v.equals(dir)) actors.add(v);
            }
        }
        if (!actors.isEmpty()) {
            int ia = r.nextInt(Math.min(3, actors.size()));
            s.set(Slot.A, actors.get(ia));
            if (actors.size() > 1) {
                int m = Math.min(4, actors.size());
                int ib = r.nextInt(m - 1);
                if (ib >= ia) ib++;
                s.set(Slot.B, actors.get(ib));
            }
        }
        int y = c.year();
        if (y >= 1874 && y <= 2100) {
            s.set(Slot.Y, Integer.toString(y));
            s.set(Slot.DEC, (y / 10 * 10) + "-х");
        }
        // жанр — от фильма, а не от Rng: у заголовка и текста (и у всех рецензий фильма) он один
        Rng gr = new Rng(Rng.mix64(c.movieId() ^ GENRE_SALT));
        x.genre = chooseGenre(gr, c.genreIds());
        if (x.genre > 0) {
            GenreRu g = GenreRu.pick(genres, x.genre, gr);
            s.set(Slot.G, g.nom());
            s.set(Slot.Gg, g.gen());
            s.set(Slot.Gp, g.pl());
            s.set(Slot.Gpg, g.plGen());
            s.gender = g.gender();
        }
        numbers(x);
        return x;
    }

    private static void numbers(Ctx x) {
        x.s.set(Slot.N, Integer.toString(x.rating));
        x.s.set(Slot.Nb, x.rating + " " + Tx.plural(x.rating, "балл", "балла", "баллов"));
    }

    /** Жанр для текста: из жанров фильма в словаре (кроме Adult); драма и короткий метр — реже прочих. */
    static int chooseGenre(Rng r, List<Integer> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int[] ok = new int[ids.size()];
        double[] w = new double[ids.size()];
        int n = 0;
        for (Integer id : ids) {
            if (id == null || id < 1 || id > GenreRu.COUNT || id == GenreRu.ADULT) continue;
            boolean dup = false;
            for (int i = 0; i < n; i++) dup |= ok[i] == id;
            if (dup) continue;
            ok[n] = id;
            w[n] = id == GenreRu.DRAMA ? 0.6 : id == GenreRu.SHORT ? 0.5 : 1.0;
            n++;
        }
        if (n == 0) return 0;
        return ok[r.weighted(Arrays.copyOf(w, n))];
    }

    // ---------------------------------------------------------------- текст

    /** Предложения текста рецензии (2–6, вместе — 150–900 знаков). */
    List<String> sentences(Rng r, ReviewContext c) {
        Ctx x = ctx(r, c);
        List<String> best = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            List<String> out = compose(r, x);
            int len = length(out);
            // о фильме — хоть одно своё слово (название, имя, год), иначе тексты разных фильмов могут совпасть
            if (len >= MIN && len <= MAX && (mentions(out, x.s) || attempt >= 5)) return out;
            if (best == null || distance(len) < distance(length(best))) best = out;
        }
        return best;
    }

    /** Есть ли в тексте название, имя или год фильма (если они известны вообще; другой фильм — не в счёт). */
    static boolean mentions(List<String> sentences, Slots s) {
        boolean any = false;
        for (Slot k : new Slot[]{Slot.T, Slot.A, Slot.B, Slot.D, Slot.Y}) {
            String v = s.get(k);
            if (v == null) continue;
            any = true;
            for (String x : sentences) if (x.contains(v)) return true;
        }
        return !any;
    }

    private static int distance(int len) {
        return len < MIN ? MIN - len : Math.max(0, len - MAX);
    }

    static int length(List<String> sentences) {
        int n = Math.max(0, sentences.size() - 1);
        for (String s : sentences) n += s.length();
        return n;
    }

    private List<String> compose(Rng r, Ctx x) {
        Slots s = x.s;
        x.tails.clear();
        int t = x.tone.ordinal();
        int n = 2 + r.weighted(COUNT_W);
        int k = n - 2;
        boolean cmp = k > 0 && s.has(Slot.O) && r.chance(COMPARE);
        boolean[] used = new boolean[Aspect.values().length];
        List<Aspect> chosen = chooseAspects(r, x, cmp ? k - 1 : k, used);
        Pol[] pols = polarities(r, x.tone, chosen.size());
        List<String> mid = new ArrayList<>();
        Pol prev = null;
        for (int i = 0; i < chosen.size(); i++) {
            String sent = aspect(r, x, chosen.get(i), pols[i], prev);
            if (sent != null) {
                mid.add(sent);
                prev = pols[i];
            }
        }
        if (cmp) {
            String sent = say(r, compare[t], s, null);
            if (sent != null) mid.add(sent);
        }
        List<String> out = new ArrayList<>();
        Section openTail = x.tone == Tone.M || !r.chance(TAIL_OPEN) ? null : x.tone.ordinal() > Tone.M.ordinal() ? tailOpenP : tailOpenN;
        String first = say(r, open[t], x, null, openTail), last = say(r, close[t], s, null);
        if (first != null) out.add(first);
        out.addAll(mid);
        if (last != null) out.add(last);
        // короткий — ещё аспект перед вердиктом, длинный — без самого длинного среднего предложения
        for (int guard = 0; (length(out) < MIN || out.size() < 2) && out.size() < 6 && guard < 8; guard++) {
            List<Aspect> extra = chooseAspects(r, x, 1, used);
            if (extra.isEmpty()) break;
            Pol p = polarities(r, x.tone, 1)[0];
            if (x.tone == Tone.M && prev != null) p = prev.positive() ? Pol.N1 : Pol.P1;
            String sent = aspect(r, x, extra.get(0), p, prev);
            if (sent != null && !out.contains(sent)) {
                out.add(Math.max(0, out.size() - 1), sent);
                prev = p;
            }
        }
        while (length(out) > MAX && out.size() > 2) {
            int longest = 1;
            for (int i = 2; i < out.size() - 1; i++) if (out.get(i).length() > out.get(longest).length()) longest = i;
            out.remove(longest);
        }
        return out;
    }

    /**
     * Предложение об аспекте; против тона — со связкой («Правда, …», «Справедливости ради, …»), в средней рецензии на
     * смене знака — «Зато …», «Но …». Нет секции нужной силы — берётся та же полярность другой силы, но не сильнее
     * тона: похвала в плохой рецензии не становится восторгом, минус в хорошей — разгромом.
     */
    private String aspect(Rng r, Ctx x, Aspect a, Pol p, Pol prev) {
        Section sec = section(a, p, x);
        if (sec == null || !sec.any(x.s)) {
            boolean stronger = (p == Pol.P1 && x.tone.ordinal() <= Tone.M.ordinal())
                    || (p == Pol.N1 && x.tone.ordinal() >= Tone.M.ordinal());
            if (stronger) return null;
            p = p.sibling();
            sec = section(a, p, x);
            if (sec == null || !sec.any(x.s)) return null;
        }
        Section conn = null;
        boolean contrary = switch (x.tone) {
            case R, M -> false;
            case G -> !p.positive();
            case D, H -> p.positive();
        };
        if (contrary) {
            if (r.chance(x.tone == Tone.G ? 0.85 : 0.9)) conn = x.tone == Tone.G ? connMinus : connPlus;
        } else if (x.tone == Tone.M && prev != null && prev.positive() != p.positive() && r.chance(0.65)) {
            conn = p.positive() ? connYet : connBut;
        }
        Section tail = r.chance(TAIL) ? (p.positive() ? tailP : tailN) : null;
        return say(r, sec, x, conn, tail);
    }

    private Section section(Aspect a, Pol p, Ctx x) {
        if (a == Aspect.GS) {
            Section[] g = x.genre > 0 ? gs[x.genre] : null;
            return g == null ? null : g[p.positive() ? 0 : 1];
        }
        return aspects[a.ordinal()][p.ordinal()];
    }

    /** Есть ли у фильма поля для аспекта. */
    private boolean available(Aspect a, Ctx x) {
        Slots s = x.s;
        return switch (a) {
            case ACTOR -> s.has(Slot.A);
            case DUO -> s.has(Slot.A) && s.has(Slot.B);
            case DIR -> s.has(Slot.D);
            case GENRE -> s.has(Slot.G);
            case YEAR -> s.has(Slot.Y);
            case GS -> x.genre > 0 && gs[x.genre] != null;
            default -> true;
        };
    }

    /**
     * До {@code k} аспектов по весам, кроме уже взятых ({@code used} — отмечаются; из группы — один), в порядке текста
     * с небольшим разбросом.
     */
    private List<Aspect> chooseAspects(Rng r, Ctx x, int k, boolean[] used) {
        Aspect[] as = Aspect.values();
        double[] w = new double[as.length];
        for (Aspect a : as) {
            boolean groupUsed = false;
            if (a.group != 0) for (Aspect b : as) groupUsed |= b.group == a.group && used[b.ordinal()];
            w[a.ordinal()] = !used[a.ordinal()] && !groupUsed && available(a, x) ? a.weight : 0;
        }
        List<Aspect> out = new ArrayList<>(Math.max(k, 0));
        for (int i = 0; i < k; i++) {
            double sum = 0;
            for (double v : w) sum += v;
            if (sum <= 0) break;
            Aspect a = as[r.weighted(w)];
            out.add(a);
            used[a.ordinal()] = true;
            w[a.ordinal()] = 0;
            if (a.group != 0) for (Aspect b : as) if (b.group == a.group) w[b.ordinal()] = 0;
        }
        int[] key = new int[as.length];
        for (Aspect a : out) key[a.ordinal()] = a.ordinal() * 2 + r.nextInt(5);
        out.sort(Comparator.comparingInt(a -> key[a.ordinal()]));
        return out;
    }

    /** Полярности средних предложений по тону; «против тона» — не больше одного (кроме средних рецензий). */
    private static Pol[] polarities(Rng r, Tone t, int k) {
        Pol[] p = new Pol[k];
        switch (t) {
            case R -> {
                for (int i = 0; i < k; i++) p[i] = r.chance(0.7) ? Pol.P2 : Pol.P1;
            }
            case G -> {
                for (int i = 0; i < k; i++) p[i] = r.chance(0.65) ? Pol.P1 : Pol.P2;
                if (k >= 1 && r.chance(0.45)) p[r.nextInt(k)] = Pol.N1;
            }
            case M -> {
                boolean pos = r.chance(0.5);
                for (int i = 0; i < k; i++) {
                    if (i > 0 && r.chance(0.6)) pos = !pos;
                    p[i] = pos ? Pol.P1 : Pol.N1;
                }
            }
            case D -> {
                for (int i = 0; i < k; i++) p[i] = r.chance(0.6) ? Pol.N1 : Pol.N2;
                if (k >= 2 && r.chance(0.4)) p[r.nextInt(k)] = Pol.P1;
            }
            case H -> {
                for (int i = 0; i < k; i++) p[i] = r.chance(0.7) ? Pol.N2 : Pol.N1;
                if (k >= 2 && r.chance(0.15)) p[r.nextInt(k)] = Pol.P1;
            }
        }
        return p;
    }

    /** Предложение из секции (со связкой впереди, если дана); {@code null} — годного шаблона нет. */
    private static String say(Rng r, Section sec, Slots s, Section conn) {
        Tpl t = sec.pick(r, s);
        return t == null ? null : finish(r, s, Tx.tidy(t.expand(r, s)), conn);
    }

    /**
     * Связка впереди (первое слово — со строчной, если это не название и не имя), заглавная и точка. В предложении уже
     * есть противопоставление («…, но …») — связки нет: «Однако X старается, но …» не пишется.
     */
    private static String finish(Rng r, Slots s, String raw, Section conn) {
        if (conn != null && !raw.contains(", но ") && !raw.contains(", однако ")) {
            // связка с двоеточием — не перед предложением, где двоеточие уже есть
            for (int i = 0; i < 4; i++) {
                String c = Tx.tidy(conn.pick(r, s).expand(r, s));
                if (c.endsWith(":") && raw.indexOf(':') >= 0) continue;
                raw = c + " " + (s.startsWithValue(raw) ? raw : Tx.uncap(raw));
                break;
            }
        }
        return Tx.sentence(raw);
    }

    /**
     * Предложение из секции: со связкой впереди и «хвостом» в конце, если даны (хвост — только к короткому предложению
     * с точкой на конце); {@code null} — годного шаблона нет.
     */
    private static String say(Rng r, Section sec, Ctx x, Section conn, Section tail) {
        Slots s = x.s;
        Tpl t = sec.pick(r, s);
        if (t == null) return null;
        String raw = Tx.tidy(t.expand(r, s));
        if (tail != null && raw.length() < TAIL_MAX && raw.endsWith(".") && !raw.endsWith("».")) {
            // хвост не повторяет строй предложения: второго тире, второго «, и», хвоста после «, что» — нет
            for (int i = 0; i < 4; i++) {
                Tpl tp = tail.pick(r, s);
                String tt = Tx.tidy(tp.expand(r, s));
                boolean fits = !x.tails.contains(tp) && !raw.contains(", что") && (tt.startsWith("—") ? !raw.contains("—")
                        : !raw.contains(", и ") && !raw.contains(" — и "));
                if (fits) {
                    x.tails.add(tp);
                    raw = Tx.tidy(raw.substring(0, raw.length() - 1) + " " + tt) + ".";
                    break;
                }
            }
        }
        return finish(r, s, raw, conn);
    }

    // ---------------------------------------------------------------- заголовок

    /** Запасные заголовки по тону (если ни один шаблон не уложился в 3–8 слов). */
    private static final String[] FALLBACK = {"Не советую, увы", "Скорее разочарование, увы", "Средне, на один раз",
            "Хорошее кино на вечер", "Настоящий шедевр жанра"};

    String title(Rng r, ReviewContext c) {
        Ctx x = ctx(r, c);
        Section sec = titles[x.tone.ordinal()];
        for (int i = 0; i < 16; i++) {
            Tpl t = sec.pick(r, x.s);
            if (t == null) break;
            String s = Tx.cap(Tx.tidy(t.expand(r, x.s)));
            while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
            int w = Tx.words(s);
            if (w >= 3 && w <= 8 && s.length() <= 120) return s;
        }
        return FALLBACK[x.tone.ordinal()];
    }

    /** Секции словаря для проверок: все шаблоны текстов и заголовков (каждая — один раз). */
    List<Section> allSections() {
        java.util.Set<Section> out = new java.util.LinkedHashSet<>();
        for (Section[] p : aspects) if (p != null) out.addAll(Arrays.asList(p));
        for (Section[] g : gs) if (g != null) out.addAll(Arrays.asList(g));
        out.addAll(Arrays.asList(open));
        out.addAll(Arrays.asList(close));
        out.addAll(Arrays.asList(compare));
        out.addAll(Arrays.asList(titles));
        out.addAll(List.of(connMinus, connPlus, connBut, connYet, tailP, tailN, tailOpenP, tailOpenN));
        return new ArrayList<>(out);
    }
}
