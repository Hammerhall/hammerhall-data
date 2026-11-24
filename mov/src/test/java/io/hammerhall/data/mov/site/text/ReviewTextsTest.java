package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Рецензии на образце в 100 тыс. разных выдуманных фильмов с оценками 1–10: повторов текста нет, на одном фильме с одной
 * оценкой повторы редки, длины и число предложений — в пределах, тон — по оценке (маркеры), о фильме — по делу
 * (название в «ёлочках», имена как даны, жанр, год, сравнение), зритель о себе — без рода, запретных слов нет,
 * детерминизм, устойчивость к пустым полям.
 */
class ReviewTextsTest {
    static final long SEED = 20251001L;
    static final int N = 100_000;
    static final SiteTextsRu TEXTS = new SiteTextsRu();
    static List<ReviewContext> films;
    static List<List<String>> sentences;
    static List<String> bodies, titles;

    @BeforeAll
    static void sample() {
        long t0 = System.nanoTime();
        RandomStreams.Stream fs = new RandomStreams(SEED).stream("films"), rs = new RandomStreams(SEED).stream("reviews");
        films = new ArrayList<>(N);
        sentences = new ArrayList<>(N);
        bodies = new ArrayList<>(N);
        titles = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            Rng f = fs.at(i);
            ReviewContext c = Samples.film(f, i, 1 + i % 10);
            films.add(c);
            Rng r = rs.at(i);
            titles.add(TEXTS.reviewTitle(r, c));
            List<String> s = TEXTS.reviewSentences(r, c);
            sentences.add(s);
            bodies.add(String.join(" ", s));
        }
        System.out.printf(Locale.ROOT, "рецензий: %d за %.1f с%n", N, (System.nanoTime() - t0) / 1e9);
    }

    // ---------------------------------------------------------------- разнообразие

    @Test
    @DisplayName("100 тыс. рецензий на разные фильмы с разными оценками — ни одного повтора текста")
    void noRepeatsAcrossFilms() {
        Set<String> seen = new HashSet<>(N * 2);
        int dup = 0;
        for (String b : bodies) if (!seen.add(b)) dup++;
        assertEquals(0, dup, "повторов текста");
        // и без своих слов фильма (название, имена, год — в маску): повторов всё равно мало — разнообразие словаря
        Set<String> masked = new HashSet<>(N * 2);
        int mdup = 0;
        for (int i = 0; i < N; i++) if (!masked.add(mask(bodies.get(i), films.get(i)))) mdup++;
        System.out.printf(Locale.ROOT, "повторов текста: 0; с маской названий и имён: %d (%.3f %%)%n", mdup, 100.0 * mdup / N);
        assertTrue(mdup < N / 200, "повторов с маской: " + mdup);
        Set<String> t = new HashSet<>(titles);
        System.out.println("разных заголовков: " + t.size() + " из " + N);
        assertTrue(t.size() > N / 10, "разных заголовков: " + t.size());
    }

    static String mask(String body, ReviewContext c) {
        String s = body;
        for (String v : new String[]{c.titleRu(), c.title(), c.director(), c.otherTitle()}) if (v != null) s = s.replace(v, "#");
        if (c.actors() != null) for (String a : c.actors()) if (a != null) s = s.replace(a, "#");
        if (c.year() > 0) s = s.replace(Integer.toString(c.year()), "#");
        return s;
    }

    @Test
    @DisplayName("Один фильм, одна оценка, разные Rng: повторов текста меньше 1 % (по 5000 на каждую оценку)")
    void sameFilmSameRatingRarelyRepeats() {
        RandomStreams.Stream st = new RandomStreams(SEED).stream("same");
        ReviewContext base = films.get(7);
        int[] dups = new int[11];
        for (int rating = 1; rating <= 10; rating++) {
            ReviewContext c = withRating(base, rating);
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 5000; i++) if (!seen.add(TEXTS.reviewBody(st.at(rating, i), c))) dups[rating]++;
            assertTrue(dups[rating] < 50, "оценка " + rating + ": повторов " + dups[rating] + " из 5000");
        }
        // фильм почти без полей — только название: повторов всё равно мало
        ReviewContext bare = new ReviewContext(8, 1, "Bare", null, 0, List.of(), null, List.of(), null);
        Set<String> seen = new HashSet<>();
        int bareDup = 0;
        for (int i = 0; i < 5000; i++) if (!seen.add(TEXTS.reviewBody(st.at(99, i), bare))) bareDup++;
        System.out.println("повторов на одном фильме по оценкам 1–10 (из 5000): " + Arrays.toString(Arrays.copyOfRange(dups, 1, 11))
                + "; фильм без полей: " + bareDup);
        assertTrue(bareDup < 50, "фильм без полей: повторов " + bareDup);
    }

    static ReviewContext withRating(ReviewContext c, int rating) {
        return new ReviewContext(rating, c.movieId(), c.title(), c.titleRu(), c.year(), c.genreIds(), c.director(), c.actors(), c.otherTitle());
    }

    // ---------------------------------------------------------------- длины

    @Test
    @DisplayName("Текст — 2–6 предложений, 150–900 знаков, в среднем ≈ 400; заголовок — 3–8 слов, без точки")
    void lengths() {
        long sum = 0;
        int[] count = new int[7];
        for (int i = 0; i < N; i++) {
            String b = bodies.get(i);
            List<String> s = sentences.get(i);
            assertTrue(b.length() >= 150 && b.length() <= 900, "длина " + b.length() + ": " + b);
            assertTrue(s.size() >= 2 && s.size() <= 6, "предложений " + s.size() + ": " + b);
            for (String x : s) {
                assertTrue(Character.isUpperCase(x.charAt(0)) || Character.isDigit(x.charAt(0)) || x.charAt(0) == '«', "с заглавной: " + x);
                assertTrue(x.endsWith(".") || x.endsWith("!") || x.endsWith("?"), "с точкой: " + x);
            }
            count[s.size()]++;
            sum += b.length();
            String t = titles.get(i);
            int w = Tx.words(t);
            assertTrue(w >= 3 && w <= 8 && !t.endsWith(".") && t.length() <= 120, "заголовок (" + w + " слов): " + t);
        }
        double mean = (double) sum / N;
        System.out.printf(Locale.ROOT, "длина текста: в среднем %.0f знаков; предложений 2–6: %s%n", mean,
                Arrays.toString(Arrays.copyOfRange(count, 2, 7)));
        assertTrue(mean >= 360 && mean <= 440, "средняя длина " + mean);
        assertTrue(count[2] > 0 && count[6] > 0, "есть и короткие, и длинные");
        // reviewBody — те же предложения через пробел
        for (int i = 0; i < 100; i++) assertEquals(bodies.get(i), reviewAgain(i));
    }

    static String reviewAgain(int i) {
        Rng r = new RandomStreams(SEED).stream("reviews").at(i);
        TEXTS.reviewTitle(r, films.get(i));
        return TEXTS.reviewBody(r, films.get(i));
    }

    @Test
    @DisplayName("Длинные названия и имена: текст всё равно в 150–900 знаков, слишком длинное — не упоминается")
    void longValues() {
        String longTitle = "Очень длинное название " + "и ещё длиннее ".repeat(8);
        String hugeTitle = "Х".repeat(400);
        String longName = "Анна-Мария Ланская-Белозерская-Остроумова";
        RandomStreams.Stream st = new RandomStreams(SEED).stream("long");
        for (int i = 0; i < 3000; i++) {
            String t = i % 2 == 0 ? longTitle : hugeTitle;
            ReviewContext c = new ReviewContext(1 + i % 10, i, t, t, 1999, List.of(6, 9), longName, List.of(longName, longName + " II"), longTitle);
            String b = TEXTS.reviewBody(st.at(i), c);
            assertTrue(b.length() >= 150 && b.length() <= 900, b.length() + ": " + b);
            assertFalse(b.contains(hugeTitle), "название в 400 знаков не упоминается");
            int w = Tx.words(TEXTS.reviewTitle(st.at(i), c));
            assertTrue(w >= 3 && w <= 8, "заголовок: " + w + " слов");
        }
    }

    // ---------------------------------------------------------------- тон

    static final Pattern RAPTURE = Pattern.compile("(?iu)шедевр|восторг|великолеп|потряса|гениальн|безупреч|блестящ|идеальн|"
            + "мурашк|влюб|лучш|невероятн|любим");
    static final Pattern RAPTURE_STRICT = Pattern.compile("(?iu)шедевр|восторг|великолеп|потряса|гениальн|безупреч|блестящ|идеальн");
    static final Pattern GOOD = Pattern.compile("(?iu)хорош|понрав|достойн|приятн|рекоменд|удачн|стоит|качествен|добротн|"
            + "крепк|увлекательн|интересн|отличн|удовольств|советую|заслуж");
    static final Pattern MID = Pattern.compile("(?iu)средн|неплох|на один (раз|вечер)|ровн|так себе|двоя|неоднозначн|смешанн|"
            + "в целом|можно (и )?посмотреть|посмотреть можно|ни рыба ни мясо|нормальн|сносн|середн|проходн|почему бы и нет|"
            + "без (особого )?восторга|не более|не больше|без откровений|без огонька|ни хорошо, ни плохо");
    static final Pattern NEG = Pattern.compile("(?iu)разочаров|скучн|затянут|слаб|провал|худш|не советую|не рекомендую|"
            + "пустая трата|жаль|увы|к сожалению|не стоит|зря|мучени|невнятн|беспомощн|натужн|фальшив|утомител|не цепляет|"
            + "не зацеп|не работает|не получ|не оправд|пропуст|с натяжкой|обидно|досадно|вяло|не впечатл|недоумени|не спасает");
    static final Pattern STRONG_NEG = Pattern.compile("(?iu)провал|худш|пустая трата|не советую|не рекомендую|мучени|беспомощн|зря потрач");
    /** Сильная критика, которой нет и в средней рецензии. */
    static final Pattern MID_FORBIDDEN = Pattern.compile("(?iu)пустая трата|не советую|не рекомендую|мучени|беспомощн");
    static final Pattern MID_RAPTURE = Pattern.compile("(?iu)шедевр|великолеп|потряса|гениальн|безупреч|блестящ|идеальн");
    /** Для подсчёта похвалы: без слов, что бывают и в критике («лучше пропустить», «не стоит», «хорошая идея не спасает»). */
    static final Pattern PRAISE = Pattern.compile("(?iu)шедевр|восторг|великолеп|потряса|гениальн|безупреч|блестящ|идеальн|"
            + "мурашк|понрав|(?<!не )рекомендую|(?<!не )советую|удовольств|достойн|увлекательн|отличн|добротн|приятн");

    @Test
    @DisplayName("Тон по оценке: 9–10 — восторг без критики, 7–8 — похвала без разгрома, 5–6 — середина, 3–4 и 1–2 — критика без восторга")
    void toneByRating() {
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < N && bad.size() < 20; i++) {
            int rating = films.get(i).rating();
            String b = bodies.get(i), last = sentences.get(i).get(sentences.get(i).size() - 1);
            String why = switch (Reviews.Tone.of(rating)) {
                case R -> !RAPTURE.matcher(last).find() ? "вердикт без восторга" : NEG.matcher(b).find() ? "критика: " + find(NEG, b)
                        : null;
                case G -> !GOOD.matcher(last).find() && !RAPTURE.matcher(last).find() ? "вердикт без похвалы"
                        : STRONG_NEG.matcher(b).find() ? "разгром: " + find(STRONG_NEG, b) : null;
                case M -> !MID.matcher(last).find() ? "вердикт не средний" : MID_FORBIDDEN.matcher(b).find() ? "разгром: " + find(MID_FORBIDDEN, b)
                        : MID_RAPTURE.matcher(b).find() ? "восторг: " + find(MID_RAPTURE, b) : null;
                case D, H -> !NEG.matcher(last).find() ? "вердикт без критики" : RAPTURE_STRICT.matcher(b).find()
                        ? "восторг: " + find(RAPTURE_STRICT, b) : null;
            };
            if (why != null) bad.add(rating + " — " + why + ": " + b);
        }
        assertEquals(List.of(), bad);
    }

    static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group() : "";
    }

    @Test
    @DisplayName("Тон по оценке в среднем: похвалы тем больше, чем выше оценка, критики — наоборот")
    void toneIsMonotone() {
        double[] pos = new double[11], neg = new double[11];
        int[] n = new int[11];
        for (int i = 0; i < N; i++) {
            int rating = films.get(i).rating();
            String b = bodies.get(i);
            pos[rating] += PRAISE.matcher(b).results().count();
            neg[rating] += NEG.matcher(b).results().count();
            n[rating]++;
        }
        StringBuilder sb = new StringBuilder("маркеров на рецензию (плюс/минус) по оценкам 1–10:");
        for (int k = 1; k <= 10; k++) sb.append(String.format(Locale.ROOT, " %d: %.1f/%.1f", k, pos[k] / n[k], neg[k] / n[k]));
        System.out.println(sb);
        for (int k = 2; k <= 10; k += 2) {
            // по тонам (пары оценок): плюсов больше, минусов меньше
            double p0 = (pos[k - 1] + pos[k]) / (n[k - 1] + n[k]), m0 = (neg[k - 1] + neg[k]) / (n[k - 1] + n[k]);
            if (k < 10) {
                double p1 = (pos[k + 1] + pos[k + 2]) / (n[k + 1] + n[k + 2]), m1 = (neg[k + 1] + neg[k + 2]) / (n[k + 1] + n[k + 2]);
                assertTrue(p1 > p0, "похвала " + (k + 1) + "–" + (k + 2) + " не больше, чем у " + (k - 1) + "–" + k);
                assertTrue(m1 < m0, "критика " + (k + 1) + "–" + (k + 2) + " не меньше, чем у " + (k - 1) + "–" + k);
            }
        }
    }

    // ---------------------------------------------------------------- о фильме

    @Test
    @DisplayName("О фильме: название — в «ёлочках»; актёры, режиссёр, жанр, год и сравнение — упоминаются, имена — как даны")
    void aboutTheFilm() {
        int withDir = 0, dirSaid = 0, withActor = 0, actorSaid = 0, withOther = 0, otherSaid = 0, withYear = 0, yearSaid = 0;
        int titleSaid = 0, genreSaid = 0, own = 0;
        for (int i = 0; i < N; i++) {
            ReviewContext c = films.get(i);
            String b = bodies.get(i);
            String t = c.titleRu() != null ? c.titleRu() : c.title();
            int at = b.indexOf("«" + t + "»");
            if (at >= 0) titleSaid++;
            for (int k = b.indexOf(t); k >= 0; k = b.indexOf(t, k + 1)) {
                if (c.otherTitle() != null && b.startsWith("«" + c.otherTitle() + "»", k - 1)) continue;
                assertTrue(k > 0 && b.charAt(k - 1) == '«' && b.charAt(k + t.length()) == '»', "название не в «ёлочках»: " + b);
            }
            if (c.titleRu() != null) assertFalse(b.contains(c.title()), "есть русское название — английского нет: " + b);
            if (c.director() != null) {
                withDir++;
                if (b.contains(c.director())) dirSaid++;
            }
            if (!c.actors().isEmpty()) {
                withActor++;
                for (String a : c.actors()) {
                    if (b.contains(a)) {
                        actorSaid++;
                        break;
                    }
                }
            }
            if (c.otherTitle() != null) {
                withOther++;
                if (b.contains("«" + c.otherTitle() + "»")) otherSaid++;
            }
            if (c.year() > 0) {
                withYear++;
                if (b.contains(Integer.toString(c.year()))) yearSaid++;
            }
            if (genreMentioned(b, c.genreIds())) genreSaid++;
            boolean any = at >= 0 || (c.director() != null && b.contains(c.director())) || c.actors().stream().anyMatch(b::contains)
                    || (c.year() > 0 && b.contains(Integer.toString(c.year())));
            if (any) own++;
        }
        System.out.printf(Locale.ROOT, "упоминают: название %.0f %%, режиссёра %.0f %%, актёра %.0f %%, жанр %.0f %%, год %.0f %%, "
                        + "сравнение %.0f %% (когда есть с чем); хоть что-то своё — %.2f %%%n", 100.0 * titleSaid / N,
                100.0 * dirSaid / withDir, 100.0 * actorSaid / withActor, 100.0 * genreSaid / N, 100.0 * yearSaid / withYear,
                100.0 * otherSaid / withOther, 100.0 * own / N);
        assertTrue(titleSaid > N * 0.5, "название");
        // имена — только в похвале (люди настоящие), поэтому в плохих рецензиях их почти нет
        assertTrue(dirSaid > withDir * 0.08, "режиссёр");
        assertTrue(actorSaid > withActor * 0.12, "актёры");
        assertTrue(genreSaid > N * 0.15, "жанр");
        assertTrue(yearSaid > withYear * 0.05, "год");
        assertTrue(otherSaid > withOther * 0.2, "сравнение");
        assertTrue(own > N * 0.995, "своё слово фильма");
    }

    static boolean genreMentioned(String b, List<Integer> ids) {
        String low = b.toLowerCase(Locale.ROOT);
        for (Integer id : ids) {
            if (id == GenreRu.ADULT) continue;
            for (GenreRu g : genres().get(id)) {
                if (low.contains(g.nom()) || low.contains(g.gen()) || low.contains(g.pl()) || low.contains(g.plGen())) return true;
            }
        }
        return false;
    }

    static final List<List<GenreRu>> GENRES = GenreRu.load();

    static List<List<GenreRu>> genres() {
        return GENRES;
    }

    /** Критика работы, которой не должно быть рядом с именем человека (сверх {@link #NEG}). */
    static final Pattern CRITIC = Pattern.compile("(?iu)не хватает|недостаёт|переигр|скован|скучает|автопилот|на одной ноте|"
            + "увлекается|(?<!не )злоупотребля|не решается|боится|потерял|не нашёл|не может|не в силах|не попадает|мог бы|"
            + "должен был бы|хотел|пытался|неровн|без огонька|не складыва|разных фильмах|не возникает|не видно|ограничивает|"
            + "схематично|поверхностно|пунктиром|плоско|одной краской|не дотяг|слабее|бледнее|промахнул|не справил|провалил|"
            + "не удивил|не спасает|не вытягивает|старается, но|в ущерб|тяжеловес|растерянн");

    @Test
    @DisplayName("Имена людей (режиссёр, актёры — настоящие) — только в похвале: ни в одном предложении с минусом или критикой")
    void namesOnlyInPraise() {
        List<String> bad = new ArrayList<>();
        int named = 0, reviewsNamed = 0;
        for (int i = 0; i < N; i++) {
            ReviewContext c = films.get(i);
            List<String> people = new ArrayList<>(c.actors());
            if (c.director() != null) people.add(c.director());
            boolean any = false;
            List<String> parts = new ArrayList<>(sentences.get(i));
            parts.add(titles.get(i));
            for (String x : parts) {
                if (people.stream().noneMatch(x::contains)) continue;
                named++;
                any = true;
                for (Pattern p : new Pattern[]{NEG, STRONG_NEG, CRITIC}) {
                    Matcher m = p.matcher(x);
                    if (m.find() && bad.size() < 20) bad.add(c.rating() + " (" + m.group() + "): " + x);
                }
            }
            if (any) reviewsNamed++;
        }
        System.out.printf(Locale.ROOT, "предложений и заголовков с именем человека: %d (в %d рецензиях из %d) — все без минуса и критики%n",
                named, reviewsNamed, N);
        assertEquals(List.of(), bad);
        assertTrue(reviewsNamed > N / 5, "имена упоминаются: " + reviewsNamed);
    }

    @Test
    @DisplayName("Грамматика: «снял» — только у режиссёра; зритель о себе — без прошедшего времени (род неизвестен)")
    void grammar() {
        Pattern snyal = Pattern.compile("(?iu)(?<!\\p{L})снял(?!\\p{L})");
        Pattern snyala = Pattern.compile("(?iu)(?<!\\p{L})сняла(?!\\p{L})");
        List<String> bad = new ArrayList<>();
        for (List<String> s : sentences) {
            for (String x : s) {
                if (snyal.matcher(x).find() && !x.toLowerCase(Locale.ROOT).contains("режиссёр")) bad.add("«снял» без режиссёра: " + x);
                if (snyala.matcher(x).find()) bad.add("«сняла»: " + x);
                Matcher m = Forbidden.REVIEWER_GENDER.matcher(x);
                if (m.find()) bad.add("зритель с родом (" + m.group() + "): " + x);
                if (x.contains("  ") || x.contains(" ,") || x.contains(" .") || x.contains(",,") || x.contains("..")) bad.add("пробелы и знаки: " + x);
            }
            if (bad.size() > 20) break;
        }
        assertEquals(List.of(), bad);
    }

    // ---------------------------------------------------------------- запреты

    @Test
    @DisplayName("Нет запретных слов: внутренние имена (PublicNames, словом и латиницей), политика, брань, оскорбления, компании, национальности")
    void noForbiddenWords(@TempDir Path tmp) throws IOException {
        Map<String, String> hits = new HashMap<>();
        Set<String> words = new HashSet<>();
        StringBuilder all = new StringBuilder(64 << 20);
        for (int i = 0; i < N; i++) {
            for (String s : new String[]{titles.get(i), bodies.get(i)}) {
                all.append(s).append('\n');
                for (String w : s.split("[^\\p{L}]+")) if (!w.isEmpty()) words.add(w);
            }
        }
        for (String w : words) {
            String why = Forbidden.check(w);
            if (why != null) hits.putIfAbsent(why, "слово из " + w.length() + " букв");
            if (PublicNames.isInternal(w) || PublicNames.isInternal(Tx.latin(w))) hits.putIfAbsent("PublicNames", "слово из " + w.length() + " букв");
        }
        // фразы — целиком (компании и национальности ищутся по тексту)
        for (int i = 0; i < N && hits.size() < 10; i++) {
            String why = Forbidden.check(bodies.get(i));
            if (why != null) hits.putIfAbsent(why, bodies.get(i));
        }
        assertEquals(Map.of(), hits);
        Path f = tmp.resolve("reviews.txt");
        Files.writeString(f, all, StandardCharsets.UTF_8);
        PublicNames.Scan scan = PublicNames.scan(f);
        assertTrue(scan.clean(), "PublicNames.scan: " + scan.hits());
        System.out.println("запретов нет: " + words.size() + " разных слов в " + N + " рецензиях");
    }

    @Test
    @DisplayName("Пригодно для TSV: нет табуляций, переводов строк и управляющих знаков; нет «null»")
    void tsvSafe() {
        for (int i = 0; i < N; i++) {
            for (String s : new String[]{titles.get(i), bodies.get(i)}) {
                for (int k = 0; k < s.length(); k++) assertFalse(Character.isISOControl(s.charAt(k)), "управляющий знак: " + s);
                assertFalse(s.contains("null") || s.contains("{") || s.contains("[") || s.contains("<") || s.contains("|"), s);
            }
        }
    }

    // ---------------------------------------------------------------- детерминизм и пустые поля

    @Test
    @DisplayName("Детерминизм: тот же Rng — тот же текст (новый экземпляр, обратный порядок)")
    void deterministic() {
        SiteTextsRu again = new SiteTextsRu();
        RandomStreams.Stream rs = new RandomStreams(SEED).stream("reviews");
        for (int i = 2999; i >= 0; i--) {
            Rng r = rs.at(i);
            assertEquals(titles.get(i), again.reviewTitle(r, films.get(i)), "заголовок " + i);
            assertEquals(bodies.get(i), again.reviewBody(r, films.get(i)), "текст " + i);
        }
        // тот же seed Rng — тот же текст, другой — другой
        ReviewContext c = films.get(42);
        assertEquals(TEXTS.reviewBody(new Rng(5), c), TEXTS.reviewBody(new Rng(5), c));
        assertFalse(TEXTS.reviewBody(new Rng(5), c).equals(TEXTS.reviewBody(new Rng(6), c)));
    }

    @Test
    @DisplayName("Пустые поля: null и пустые списки, null в списках, жанры вне словаря, оценка вне 1–10, контекст null")
    void nullSafe() {
        List<String> withNulls = new ArrayList<>(Arrays.asList(null, "", "  ", "Анна Ланская", null, "Анна Ланская", "Tom\tHolloway\n"));
        List<Integer> genresWithNulls = new ArrayList<>(Arrays.asList(null, 0, 25, -3, 2, 6));
        ReviewContext[] cs = {
                new ReviewContext(8, 1, null, null, 0, null, null, null, null),
                new ReviewContext(3, 2, "", " ", -5, List.of(), "", List.of(), ""),
                new ReviewContext(10, 3, "Title", null, 1999, genresWithNulls, " Пётр Ветров ", withNulls, "Title"),
                new ReviewContext(0, 4, "Title", "Название", 3000, List.of(2), "Иван Градов, Вера Звягина", withNulls, "Другое"),
                new ReviewContext(42, 5, "Title", "Название", 1874, List.of(20, 20, 20), null, List.of("Анна Ланская"), null),
                new ReviewContext(-7, 6, null, "Название\u0000с\u0007мусором", 2026, List.of(24), "Oskar Brandt", null, "Название"),
                null};
        RandomStreams.Stream st = new RandomStreams(SEED).stream("nulls");
        for (int k = 0; k < cs.length; k++) {
            for (int i = 0; i < 300; i++) {
                Rng r = st.at(k, i);
                String t = TEXTS.reviewTitle(r, cs[k]);
                List<String> s = TEXTS.reviewSentences(r, cs[k]);
                String b = String.join(" ", s);
                assertNotNull(t);
                assertTrue(Tx.words(t) >= 3 && Tx.words(t) <= 8, "заголовок: " + t);
                assertTrue(s.size() >= 2 && s.size() <= 6 && b.length() >= 150 && b.length() <= 900, k + ": " + b.length() + ": " + b);
                assertFalse(b.contains("null") || b.contains("«»") || b.contains("\t") || b.contains("\u0000") || b.contains("\u0007"), b);
            }
        }
        // оценка вне 1–10 — к краю: 0 и -7 — как 1, 42 — как 10
        Rng a = new Rng(11), b = new Rng(11);
        assertEquals(TEXTS.reviewBody(a, withRating(cs[2], 1)), TEXTS.reviewBody(b, withRating(cs[2], -100)));
        // имя с пробелами по краям — без них; одно и то же имя дважды — один актёр; «Tom\tHolloway\n» — «Tom Holloway»
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String body = TEXTS.reviewBody(st.at(7, i), cs[2]);
            if (body.contains("Пётр Ветров")) seen.add("режиссёр");
            if (body.contains("Tom Holloway")) seen.add("актёр");
            assertFalse(body.contains(" Пётр Ветров ,"), body);
        }
        assertEquals(Set.of("режиссёр", "актёр"), seen);
    }

    @Test
    @DisplayName("Название с «ёлочками» внутри — в „лапках“; режиссёры через запятую — без имени")
    void quotesAndLists() {
        ReviewContext c = new ReviewContext(9, 1, "X", "Операция «Ы»", 1965, List.of(6), "Иван Градов, Вера Звягина", List.of(), null);
        RandomStreams.Stream st = new RandomStreams(SEED).stream("quotes");
        int said = 0;
        for (int i = 0; i < 1000; i++) {
            String b = TEXTS.reviewBody(st.at(i), c);
            if (b.contains("«Операция „Ы“»")) said++;
            assertFalse(b.contains("«Операция «Ы»»") || b.contains("Градов"), b);
        }
        assertTrue(said > 300, "название упомянуто " + said + " раз");
    }
}
