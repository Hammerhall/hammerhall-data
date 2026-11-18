package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

/**
 * Тексты и поля вещи для каталога: название (по-русски и по-английски), описание, состояние, происхождение звеньями,
 * мастер из справочника, атрибуция, датировка, материал, размеры. Детерминизм — от генератора случайности ядра
 * ({@link Rng}, SplitMix64 без JDK): одно и то же {@code (seed, ключ вещи)} даёт тот же текст на любой ОС и в любом
 * порядке вызовов.
 *
 * <pre>{@code
 * LotTexts texts = new LotTexts(ctx.streams());                       // один раз на прогон: справочник мастеров из seed
 * LotText t = texts.lot(Department.PAINTINGS, itemKey);               // поток catalog.text под (отдел, ключ)
 * LotText u = texts.lot(Category.byCode("coins.silver"), itemKey, rng);  // лист категории и свой поток
 * Maker m = texts.makers().byId(t.makerId());
 * }</pre>
 *
 * Эстимейта, цен и дат торгов здесь нет — их даёт генератор торгов. Экземпляр после построения только читается:
 * вызывать можно из разных потоков.
 */
public final class LotTexts {
    /** Поток случайности текстов, когда генератор не передаёт свой: {@code at(отдел, ключ вещи)}. */
    public static final String STREAM = "catalog.text";
    /** Поток мест торгов (аукционы, галереи, магазины в происхождении). */
    public static final String VENUES_STREAM = "catalog.venues";

    private final Makers makers;
    private final RandomStreams.Stream stream;
    final Common.Venues venues;

    public LotTexts(RandomStreams streams) {
        this(Makers.build(streams), streams);
    }

    public LotTexts(long seed) {
        this(new RandomStreams(seed));
    }

    public LotTexts(Makers makers, RandomStreams streams) {
        this.makers = makers;
        this.stream = streams.stream(STREAM);
        this.venues = new Common.Venues(streams.stream(VENUES_STREAM).at(0));
    }

    public Makers makers() {
        return makers;
    }

    /** Вещь отдела под ключ: случайность — из потока {@link #STREAM} под (отдел, ключ). */
    public LotText lot(Department d, long key) {
        return lot(d, key, stream.at(d.ordinal(), key));
    }

    /** Вещь отдела: лист категории — по долям, мастер — по популярности; вся случайность — из {@code r}. */
    public LotText lot(Department d, long key, Rng r) {
        return lot(Category.pick(d, r), key, r);
    }

    /** Вещь листа категории: мастер — по популярности (у части вещей неизвестен). */
    public LotText lot(Category leaf, long key, Rng r) {
        return lot(leaf, maker(leaf, r), key, r);
    }

    /**
     * Вещь листа категории с заданным мастером ({@code null} — неизвестный мастер). Мастер должен быть того вида, что у
     * листа ({@link Category#makerKind()}); у листов без мастера (рукописи, памятные вещи, камни) он не используется.
     */
    public LotText lot(Category leaf, Maker m, long key, Rng r) {
        return draft(leaf, m, key, r).build();
    }

    /** Мастер для вещи листа: по популярности; у живописи и книг — чаще со своей специализацией. */
    Maker maker(Category leaf, Rng r) {
        MakerKind k = leaf.makerKind();
        if (k == null || r.chance(anonymousShare(leaf.kind()))) return null;
        Maker m = makers.pick(k, r);
        int min = minYear(leaf), max = maxYear(leaf);
        // абстрактная живопись — только у абстракционистов; прочие жанры живописи — не у них
        Boolean abs = leaf.kind() == Kind.PAINTING_OIL ? (Boolean) "abstract".equals(leaf.variant()) : null;
        for (int i = 0; i < 60 && !(fits(m, min, max) && styleOk(m, abs)); i++) m = makers.pick(k, r);
        if (!fits(m, min, max) || !styleOk(m, abs)) return null;
        String want = specialty(leaf);
        if (want != null) {
            for (int i = 0; i < 6 && !want.equals(genreOf(m)); i++) {
                if (!r.chance(0.8)) break;
                Maker x = makers.pick(k, r);
                if (fits(x, min, max) && styleOk(x, abs)) m = x;
            }
        }
        return m;
    }

    private static boolean styleOk(Maker m, Boolean abs) {
        return abs == null || abs == Makers.abstractArtist(m);
    }

    private static boolean fits(Maker m, int min, int max) {
        return m.activeTo(Makers.NOW_YEAR) >= min && m.activeFrom() <= max;
    }

    /** Самый ранний год вещей листа (абстракция — с 1910, шелкография — с 1930, открытки — с 1895). */
    static int minYear(Category leaf) {
        if (leaf.kind() == Kind.POSTCARD) return 1895;
        if (leaf.kind() == Kind.PHOTO) return 1845;
        if (leaf.variant() == null) return 0;
        return switch (leaf.variant()) {
            case "abstract" -> 1910;
            case "screenprint" -> 1930;
            case "wrist" -> 1915;
            case "exhibition" -> 1830;
            case "token" -> 1850;
            case "journal" -> 1800;
            case "soft" -> 1895;
            case "children" -> leaf.kind() == Kind.BOOK ? 1830 : 0;
            default -> 0;
        };
    }

    /** Самый поздний год вещей листа (старопечатные книги — до 1830, карманные часы — до 1975). */
    static int maxYear(Category leaf) {
        if (leaf.variant() == null) return Makers.NOW_YEAR;
        return switch (leaf.variant()) {
            case "early" -> 1830;
            case "pocket" -> 1975;
            case "pendant" -> leaf.kind() == Kind.WATCH ? 1960 : Makers.NOW_YEAR;
            default -> Makers.NOW_YEAR;
        };
    }

    private static String genreOf(Maker m) {
        if (m.kind() == MakerKind.ARTIST) {
            Makers.Specialty s = Makers.specialty(m.specialty());
            return s == null ? null : s.genre();
        }
        return m.specialty();
    }

    /** Желательная специализация мастера листа: жанр живописи или род автора книги. */
    private static String specialty(Category leaf) {
        if (leaf.kind() == Kind.PAINTING_OIL) return leaf.variant();
        if (leaf.kind() == Kind.BOOK && leaf.variant() != null) {
            return switch (leaf.variant()) {
                case "poetry" -> "поэт";
                case "travel" -> "путешественник";
                case "science" -> "натуралист";
                case "art" -> "историк искусства";
                case "children" -> "детский писатель";
                case "music" -> "композитор";
                case "prose" -> "писатель";
                default -> null;
            };
        }
        return null;
    }

    /** Заготовка вещи с предложениями описания по отдельности (для тестов). */
    Ctx draft(Category leaf, Maker m, long key, Rng r) {
        if (!leaf.isLeaf()) throw new IllegalArgumentException("не лист дерева категорий: " + leaf);
        if (m != null && leaf.makerKind() != m.kind()) {
            throw new IllegalArgumentException("мастер " + m.kind() + " не для листа " + leaf + " (нужен " + leaf.makerKind() + ")");
        }
        Ctx x = new Ctx(this, r, leaf, m, key);
        x.attribution = m == null ? Attribution.UNKNOWN : Attribution.BY;
        switch (leaf.kind()) {
            case PAINTING_OIL, DRAWING, PRINT, PHOTO, SCULPTURE -> Painting.fill(x);
            case JEWELRY -> Jewelry.fillJewelry(x);
            case WATCH -> Jewelry.fillWatch(x);
            case GEMSTONE -> Jewelry.fillGem(x);
            case COIN -> Coins.fillCoin(x);
            case MEDAL -> Coins.fillMedal(x);
            case BANKNOTE -> Coins.fillBanknote(x);
            case BOOK -> Books.fillBook(x);
            case MANUSCRIPT -> Books.fillManuscript(x);
            case MAP -> Books.fillMap(x);
            case PORCELAIN -> Decorative.fillPorcelain(x);
            case SILVER -> Decorative.fillSilver(x);
            case FURNITURE -> Decorative.fillFurniture(x);
            case GLASS -> Decorative.fillGlass(x);
            case BRONZE -> Decorative.fillBronze(x);
            case CLOCK -> Decorative.fillClock(x);
            case CARPET -> Decorative.fillCarpet(x);
            case STAMP -> Collectibles.fillStamp(x);
            case POSTCARD -> Collectibles.fillPostcard(x);
            case TOY -> Collectibles.fillToy(x);
            case MEMORABILIA -> Collectibles.fillMemorabilia(x);
        }
        if (x.yearTo < x.yearFrom) x.yearTo = x.yearFrom;
        return x;
    }

    /** Заготовка вещи отдела под ключ — как {@link #lot(Department, long)}. */
    Ctx draft(Department d, long key) {
        Rng r = stream.at(d.ordinal(), key);
        Category leaf = Category.pick(d, r);
        return draft(leaf, maker(leaf, r), key, r);
    }

    /** Доля вещей вида без известного мастера. */
    static double anonymousShare(Kind k) {
        return switch (k) {
            case PAINTING_OIL -> 0.08;
            case DRAWING, PRINT -> 0.1;
            case PHOTO -> 0.2;
            case SCULPTURE -> 0.15;
            case JEWELRY -> 0.3;
            case WATCH -> 0.1;
            case COIN -> 0.03;
            case MEDAL -> 0.25;
            case BOOK -> 0.04;
            case MAP -> 0.15;
            case PORCELAIN, GLASS -> 0.25;
            case SILVER -> 0.2;
            case FURNITURE -> 0.45;
            case BRONZE -> 0.2;
            case CLOCK -> 0.4;
            case CARPET -> 0.5;
            case POSTCARD -> 0.2;
            case TOY -> 0.3;
            case BANKNOTE, STAMP -> 0;
            default -> 1;
        };
    }
}
