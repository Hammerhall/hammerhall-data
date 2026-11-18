package io.hammerhall.data.auc.auction.calendar;

import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.core.time.RuCalendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Календарь торгов — <b>функция даты</b>: правило даёт торги любого года от {@code seed} и ключа торгов
 * (формат, вид, отдел, год, номер), не от периода, поэтому продление дописывает календарь, не трогая прежний.
 *
 * <h2>Онлайн-торги</h2>
 * Каждый отдел — раз в неделю, закрытие вечером: вт — монеты и книги, ср — ювелирное и декоративно-прикладное, чт —
 * живопись и коллекционные; вс — два тематических (темы по кругу {@link Depts#THEMES}). В 1–10 января закрытий нет.
 * Ставки открыты 7 (55 %), 10 (30 %) или 14 суток (15 %); каталог виден за 3 дня до открытия. Лотов в {@code full} —
 * логнормально: медиана 1 200, 95-й процентиль 5 000 (путь 2), летом (июнь–август) × 0,85; не больше 7 вечеров волны
 * (8 400 лотов) — торги закрываются до следующих торгов отдела.
 *
 * <h2>Живые торги</h2>
 * 60 в год (живопись 16, ювелирное 12, монеты 10, книги 10, декоративно-прикладное 8, коллекционные 4), вт–сб, в двух
 * залах (Москва, Петербург); весна (15.02–05.06) и осень (15.09–15.12) — по 27 торгов, остальное время — 6; нет торгов
 * 1–10 января, 1–10 мая, с 15 июля по 31 августа. <b>Главные недели</b> — неделя третьего четверга апреля и ноября: вт —
 * ювелирное, ср — вечерние торги живописи (19:00, ≈ 60 лотов), чт — живопись, пт — декоративно-прикладное (все — Москва,
 * темп 40 лотов в час). Прочие — равномерно по сезону со случайным сдвигом, без двух торгов в одном зале в один день и
 * без двух торгов одного отдела ближе недели. Лотов — логнормально: медиана 220, 95-й процентиль 700 (монеты × 1,5, до
 * 1 500 за 2–3 дня). Каталог — за 21 день (14–35), выставка — последние 6 дней до торгов.
 *
 * <p>Номер в коде и названии — по порядку начала торгов формата в году. {@code sales.id} — по плановой публикации
 * каталога ({@link #inRelease}): так короткий прогон ({@code --to}) — начало длинного, а продление дописывает торги в
 * хвост.
 */
public final class AuctionCalendar {
    static final long DAY = 86_400;
    /** Онлайн: лотов в {@code full} — медиана и σ логарифма (p95 = 5 000). */
    static final double ONLINE_MEDIAN = 1200, ONLINE_SIGMA = StrictMath.log(5000.0 / 1200.0) / 1.6448536269514722;
    static final int ONLINE_MAX = 7 * Wave.MAX_PER_EVENING;
    static final double SUMMER = 0.85;
    /** Живые: медиана 220, p95 700. */
    static final double LIVE_MEDIAN = 220, LIVE_SIGMA = StrictMath.log(700.0 / 220.0) / 1.6448536269514722;
    static final int LIVE_MAX = 950, COINS_MAX = 1500, LIVE_MIN = 30;
    static final double COINS_FACTOR = 1.5;
    /** Сколько живых торгов в год по отделам, из них — в остальное время года, весной и осенью (без главных). */
    static final int[] LIVE_PER_YEAR = {16, 12, 10, 10, 8, 4};
    static final int[] LIVE_REST = {2, 1, 1, 1, 1, 0};
    static final int[] LIVE_SPRING = {5, 5, 4, 4, 3, 2};
    static final int[] LIVE_AUTUMN = {5, 4, 5, 5, 2, 2};
    /** Смена шкалы премии покупателя. */
    static final LocalDate BP_2026_FROM = LocalDate.of(2026, 9, 1);
    /** Горизонт моделирования после среза. */
    public static final long HORIZON_S = 180 * DAY;

    private final RandomStreams.Stream stream;
    private final RuCalendar ru;
    private final Map<Integer, List<SaleSpec>> years = new TreeMap<>();

    /** {@code stream} — поток {@code auction.calendar}. */
    public AuctionCalendar(RandomStreams.Stream stream, RuCalendar ru) {
        this.stream = stream;
        this.ru = ru;
    }

    public AuctionCalendar(RandomStreams streams) {
        this(streams.stream("auction.calendar"), new RuCalendar());
    }

    // ---------------------------------------------------------------- год

    /** Все торги, начавшиеся (по Москве) в году {@code y}, по началу. */
    public synchronized List<SaleSpec> year(int y) {
        return years.computeIfAbsent(y, this::build);
    }

    private List<SaleSpec> build(int y) {
        List<SaleSpec> online = number(online(y), y, false);
        List<SaleSpec> live = number(new LivePlanner(y).plan(), y, true);
        List<SaleSpec> all = new ArrayList<>(online.size() + live.size());
        all.addAll(online);
        all.addAll(live);
        all.sort(BY_START);
        return List.copyOf(all);
    }

    static final Comparator<SaleSpec> BY_START = Comparator.comparingLong(SaleSpec::startsAt).thenComparingLong(SaleSpec::key);
    /** Порядок {@code sales.id}: плановая публикация, начало, код. */
    public static final Comparator<SaleSpec> BY_PUBLICATION = Comparator.comparingLong(SaleSpec::publishedAt)
            .thenComparingLong(SaleSpec::startsAt).thenComparing(SaleSpec::code);

    private static List<SaleSpec> number(List<SaleSpec> drafts, int y, boolean live) {
        List<SaleSpec> s = new ArrayList<>(drafts);
        s.sort(BY_START);
        List<SaleSpec> out = new ArrayList<>(s.size());
        for (int i = 0; i < s.size(); i++) {
            SaleSpec d = s.get(i);
            int n = i + 1;
            String code = live ? String.format(java.util.Locale.ROOT, "L-%d-%03d", y, n)
                    : String.format(java.util.Locale.ROOT, "T-%d-%04d", y, n);
            out.add(new SaleSpec(d.key(), code, n, d.live(), d.tier(), d.dept(), d.theme(), d.office(), d.publishedAt(),
                    d.biddingOpensAt(), d.previewFrom(), d.previewTo(), d.startsAt(), d.durationDays(), d.days(), d.saleDays(),
                    d.firstMinute(), d.pace(), d.targetLots(), d.premium()));
        }
        return out;
    }

    /** Торги с плановой публикацией в {@code [from, to)}, по {@link #BY_PUBLICATION}. */
    public List<SaleSpec> publishedBetween(long from, long to) {
        List<SaleSpec> out = new ArrayList<>();
        int y0 = Period.localDate(from, Period.MOSCOW).getYear();
        int y1 = Period.localDate(Math.max(from, to - 1), Period.MOSCOW).getYear() + 1;
        for (int y = y0; y <= y1; y++) {
            for (SaleSpec s : year(y)) if (s.publishedAt() >= from && s.publishedAt() < to) out.add(s);
        }
        out.sort(BY_PUBLICATION);
        return out;
    }

    /**
     * Торги выпуска, по порядку {@code sales.id}: каталог по плану опубликован (или будет опубликован) внутри периода —
     * {@code [начало, конец + 1 сутки)} (назначенные торги — до конца периода). Набор замкнут по порядку публикации,
     * поэтому выпуск короче ({@code --to}) или раньше («сейчас») — начало длинного, и {@code id} при продлении не
     * сдвигаются. Состояние на срез ({@code published_at}, {@code status}) — у вызывающего.
     */
    public List<SaleSpec> inRelease(Period p) {
        return publishedBetween(p.startEpoch(), p.endEpochExclusive());
    }

    /** Торги, которые моделируются: каталог с начала периода до среза + горизонт 180 суток. */
    public List<SaleSpec> modeled(Period p, long slice) {
        return publishedBetween(p.startEpoch(), Math.max(p.endEpochExclusive(), slice + HORIZON_S));
    }

    // ---------------------------------------------------------------- общее

    static long moscow(LocalDate d, int minute) {
        return Period.epochOf(LocalDateTime.of(d, LocalTime.of(minute / 60, minute % 60)), Period.MOSCOW);
    }

    static String premium(int dept, LocalDate saleDay) {
        if (dept == Depts.COINS) return "BP-COIN";
        return saleDay.isBefore(BP_2026_FROM) ? "BP-2024" : "BP-2026";
    }

    /** Январские праздники: 1–10 января торгов нет. */
    static boolean januaryPause(LocalDate d) {
        return d.getMonthValue() == 1 && d.getDayOfMonth() <= 10;
    }

    static boolean summer(LocalDate d) {
        int m = d.getMonthValue();
        return m >= 6 && m <= 8;
    }

    // ---------------------------------------------------------------- онлайн

    private List<SaleSpec> online(int y) {
        List<SaleSpec> out = new ArrayList<>();
        for (LocalDate d = LocalDate.of(y, 1, 1); d.getYear() == y; d = d.plusDays(1)) {
            if (januaryPause(d)) continue;
            int k = (d.getDayOfYear() - 1) / 7 + 1;
            switch (d.getDayOfWeek()) {
                case TUESDAY -> {
                    out.add(timed(d, k, Depts.COINS, -1, 0));
                    out.add(timed(d, k, Depts.BOOKS, -1, 0));
                }
                case WEDNESDAY -> {
                    out.add(timed(d, k, Depts.JEWELLERY, -1, 0));
                    out.add(timed(d, k, Depts.DECORATIVE, -1, 0));
                }
                case THURSDAY -> {
                    out.add(timed(d, k, Depts.PAINTINGS, -1, 0));
                    out.add(timed(d, k, Depts.COLLECTIBLES, -1, 0));
                }
                case SUNDAY -> {
                    long week = Math.floorDiv(d.toEpochDay() - 3, 7);
                    for (int slot = 0; slot < 2; slot++) {
                        int theme = Math.floorMod(week * 2 + slot, Depts.THEMES.length);
                        out.add(timed(d, k, Depts.THEMES[theme].dept(), theme, slot));
                    }
                }
                default -> {
                }
            }
        }
        return out;
    }

    private SaleSpec timed(LocalDate close, int k, int dept, int theme, int slot) {
        boolean thematic = theme >= 0;
        long key = SaleSpec.Keys.of(false, thematic ? 1 : 0, thematic ? slot : dept, close.getYear(), k);
        Rng r = stream.at(key);
        double u = r.nextDouble();
        int dur = u < 0.55 ? 7 : u < 0.85 ? 10 : 14;
        double size = r.logNormal(ONLINE_MEDIAN, ONLINE_SIGMA) * (summer(close) ? SUMMER : 1.0);
        int lots = (int) Math.max(1, Math.min(ONLINE_MAX, StrictMath.round(size)));
        long first = moscow(close, Sessions.EVENING);
        long opens = first - dur * DAY;
        long published = opens - 3 * DAY;
        return new SaleSpec(key, null, 0, false, thematic ? "thematic" : "regular", dept, theme, 1, published, opens, null, null,
                first, dur, Wave.evenings(lots), List.of(), 0, 0, lots, premium(dept, close));
    }

    // ---------------------------------------------------------------- живые

    /** Сезон дня: 0 — весна (15.02–05.06), 1 — осень (15.09–15.12), 2 — остальное время. */
    static int season(LocalDate d) {
        int md = d.getMonthValue() * 100 + d.getDayOfMonth();
        if (md >= 215 && md <= 605) return 0;
        if (md >= 915 && md <= 1215) return 1;
        return 2;
    }

    /** День, в который могут идти живые торги: вт–сб, не праздник, не паузы (1–10.01, 1–10.05, 15.07–31.08, 31.12). */
    boolean liveDay(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        if (w == DayOfWeek.SUNDAY || w == DayOfWeek.MONDAY) return false;
        int m = d.getMonthValue(), day = d.getDayOfMonth();
        if (m == 1 && day <= 10 || m == 5 && day <= 10 || m == 7 && day >= 15 || m == 8 || m == 12 && day == 31) return false;
        if (w == DayOfWeek.SATURDAY) return !ru.isHoliday(d);
        return ru.isWorking(d);
    }

    /** Понедельник главной недели месяца — недели третьего четверга. */
    static LocalDate majorWeekMonday(int y, int month) {
        LocalDate d = LocalDate.of(y, month, 1);
        while (d.getDayOfWeek() != DayOfWeek.THURSDAY) d = d.plusDays(1);
        return d.plusDays(14).minusDays(3);
    }

    /** Главные недели года: апрель и ноябрь (вт–сб). */
    public static List<LocalDate[]> majorWeeks(int y) {
        List<LocalDate[]> out = new ArrayList<>();
        for (int m : new int[]{4, 11}) {
            LocalDate mon = majorWeekMonday(y, m);
            out.add(new LocalDate[]{mon.plusDays(1), mon.plusDays(5)});
        }
        return out;
    }

    /** Раскладка живых торгов одного года. */
    private final class LivePlanner {
        final int y;
        /** Занятые дни залов: офис (1, 2) → день → отдел. */
        final List<Map<LocalDate, Integer>> dayBusy = List.of(new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        final List<Map<LocalDate, Integer>> eveningBusy = List.of(new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        final List<List<LocalDate>> deptDays = new ArrayList<>();
        final List<SaleSpec> out = new ArrayList<>();

        LivePlanner(int y) {
            this.y = y;
            for (int d = 0; d < Depts.COUNT; d++) deptDays.add(new ArrayList<>());
        }

        /** Заявка на обычные торги: отдел, сезон, номер в сезоне, параметры — до раскладки. */
        record Req(long key, int dept, int season, double ideal, int office, int lots, int pace, int firstMinute, int days,
                   int lead) {
        }

        List<SaleSpec> plan() {
            majors();
            List<Req> reqs = new ArrayList<>();
            List<List<LocalDate>> windows = new ArrayList<>();
            for (int s = 0; s < 3; s++) windows.add(new ArrayList<>());
            for (LocalDate d = LocalDate.of(y, 1, 1); d.getYear() == y; d = d.plusDays(1)) {
                if (liveDay(d)) windows.get(season(d)).add(d);
            }
            for (int dept = 0; dept < Depts.COUNT; dept++) {
                int[] counts = {LIVE_SPRING[dept], LIVE_AUTUMN[dept], LIVE_REST[dept]};
                for (int s = 0; s < 3; s++) {
                    for (int k = 0; k < counts[s]; k++) {
                        long key = SaleSpec.Keys.of(true, 0, dept, y, s * 32 + k + 1);
                        Rng r = stream.at(key);
                        double ideal = (k + 0.5 + r.uniform(-0.3, 0.3)) / counts[s];
                        boolean hall = dept == Depts.PAINTINGS || dept == Depts.JEWELLERY || dept == Depts.DECORATIVE;
                        int office = r.chance(hall ? 0.70 : 0.60) ? 1 : 2;
                        double size = r.logNormal(LIVE_MEDIAN, LIVE_SIGMA) * (dept == Depts.COINS ? COINS_FACTOR : 1.0);
                        int lots = (int) Math.max(LIVE_MIN, Math.min(dept == Depts.COINS ? COINS_MAX : LIVE_MAX, StrictMath.round(size)));
                        int pace = dept == Depts.COINS || dept == Depts.COLLECTIBLES ? 110 : 75;
                        int first = lots <= Sessions.cap(pace, Sessions.AFTERNOON) && r.chance(0.3) ? Sessions.AFTERNOON : Sessions.NOON;
                        int lead = lead(r, 21);
                        reqs.add(new Req(key, dept, s, ideal, office, lots, pace, first, Sessions.daysFor(lots, pace, first), lead));
                    }
                }
            }
            reqs.sort(Comparator.comparingDouble((Req q) -> q.ideal).thenComparingInt(q -> q.dept).thenComparingLong(q -> q.key));
            for (Req q : reqs) place(q, windows.get(q.season));
            return out;
        }

        private int lead(Rng r, int mean) {
            return (int) Math.max(14, Math.min(35, StrictMath.round(mean + 4 * r.gaussian())));
        }

        private void majors() {
            int mi = 0;
            for (int month : new int[]{4, 11}) {
                LocalDate mon = majorWeekMonday(y, month);
                major(SaleSpec.Keys.of(true, 2, Depts.JEWELLERY, y, mi + 1), Depts.JEWELLERY, "major", mon.plusDays(1));
                major(SaleSpec.Keys.of(true, 3, Depts.PAINTINGS, y, mi + 1), Depts.PAINTINGS, "evening", mon.plusDays(2));
                major(SaleSpec.Keys.of(true, 2, Depts.PAINTINGS, y, mi + 1), Depts.PAINTINGS, "major", mon.plusDays(3));
                major(SaleSpec.Keys.of(true, 2, Depts.DECORATIVE, y, mi + 1), Depts.DECORATIVE, "major", mon.plusDays(4));
                mi++;
            }
        }

        private void major(long key, int dept, String tier, LocalDate day) {
            Rng r = stream.at(key);
            boolean evening = tier.equals("evening");
            int lots = evening ? r.between(50, 70) : (int) Math.max(60, Math.min(260, StrictMath.round(r.logNormal(160, 0.3))));
            int first = evening ? Sessions.EVENING : Sessions.NOON;
            int lead = lead(r, 30);
            if (evening) eveningBusy.get(1).put(day, dept);
            else dayBusy.get(1).put(day, dept);
            deptDays.get(dept).add(day);
            emit(key, dept, tier, 1, List.of(day), lots, 40, first, lead);
        }

        private void place(Req q, List<LocalDate> window) {
            int n = window.size();
            int at = (int) Math.max(0, Math.min(n - 1, StrictMath.floor(q.ideal * n)));
            for (int pass = 0; pass < 3; pass++) {
                int office = pass == 1 ? 3 - q.office : q.office;
                boolean spacing = pass < 2;
                for (int step = 0; step < 2 * n; step++) {
                    int i = at + ((step & 1) == 0 ? step / 2 : -(step / 2 + 1));
                    if (i < 0 || i >= n) continue;
                    List<LocalDate> days = run(window.get(i), q.days, q.season);
                    if (days == null || !free(office, days) || spacing && near(q.dept, days)) continue;
                    for (LocalDate d : days) dayBusy.get(office).put(d, q.dept);
                    deptDays.get(q.dept).addAll(days);
                    emit(q.key, q.dept, "regular", office, days, q.lots, q.pace, q.firstMinute, q.lead);
                    return;
                }
            }
            throw new IllegalStateException("живые торги " + y + ": нет места для " + Depts.CODE[q.dept] + " в сезоне " + q.season);
        }

        /** Дни торгов подряд с {@code start}: все — дни живых торгов того же сезона; иначе {@code null}. */
        private List<LocalDate> run(LocalDate start, int days, int season) {
            List<LocalDate> out = new ArrayList<>(days);
            for (int i = 0; i < days; i++) {
                LocalDate d = start.plusDays(i);
                if (d.getYear() != y || !liveDay(d) || season(d) != season) return null;
                out.add(d);
            }
            return out;
        }

        private boolean free(int office, List<LocalDate> days) {
            for (LocalDate d : days) if (dayBusy.get(office).containsKey(d)) return false;
            return true;
        }

        /** Торги того же отдела ближе 7 дней. */
        private boolean near(int dept, List<LocalDate> days) {
            for (LocalDate p : deptDays.get(dept)) {
                for (LocalDate d : days) if (Math.abs(p.toEpochDay() - d.toEpochDay()) < 7) return true;
            }
            return false;
        }

        private void emit(long key, int dept, String tier, int office, List<LocalDate> days, int lots, int pace, int first, int lead) {
            LocalDate start = days.getFirst();
            long published = moscow(start.minusDays(lead), Sessions.NOON);
            out.add(new SaleSpec(key, null, 0, true, tier, dept, -1, office, published, published, start.minusDays(6),
                    start.minusDays(1), moscow(start, first), 0, days.size(), List.copyOf(days), first, pace, lots,
                    premium(dept, start)));
        }
    }
}
