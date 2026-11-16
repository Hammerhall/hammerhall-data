package io.hammerhall.data.core.time;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Календарь России: праздники и нерабочие дни (производственный календарь), торговые события, сезоны спроса.
 *
 * <p>Производственный календарь: 2025 и 2026 — по постановлениям правительства (переносы: 2025 — 1 ноября рабочая
 * суббота, 3 ноября и 31 декабря выходные; 2026 — 9 января и 31 декабря выходные); 2027 — <b>прогноз по правилу</b>
 * (праздник в выходной → ближайший рабочий день; выходные 2 и 3 января → 22 февраля и 5 ноября; 31 декабря выходной),
 * до выпуска сверить с постановлением. Прочие годы — только правило переноса.
 */
public final class RuCalendar {

    /** Государственные праздники (ТК РФ, ст. 112): месяц, день, название. */
    private static final int[][] HOLIDAYS = {
            {1, 1}, {1, 2}, {1, 3}, {1, 4}, {1, 5}, {1, 6}, {1, 7}, {1, 8},
            {2, 23}, {3, 8}, {5, 1}, {5, 9}, {6, 12}, {11, 4}};

    private static final Set<LocalDate> EXTRA_OFF = new TreeSet<>(List.of(
            // 2025
            LocalDate.of(2025, 5, 2), LocalDate.of(2025, 5, 8),
            LocalDate.of(2025, 6, 13), LocalDate.of(2025, 11, 3), LocalDate.of(2025, 12, 31),
            // 2026
            LocalDate.of(2026, 1, 9), LocalDate.of(2026, 3, 9), LocalDate.of(2026, 5, 11), LocalDate.of(2026, 12, 31),
            // 2027 — прогноз
            LocalDate.of(2027, 2, 22), LocalDate.of(2027, 5, 3), LocalDate.of(2027, 5, 10), LocalDate.of(2027, 6, 14),
            LocalDate.of(2027, 11, 5), LocalDate.of(2027, 12, 31)));

    private static final Set<LocalDate> WORKING_WEEKEND = new TreeSet<>(List.of(LocalDate.of(2025, 11, 1)));

    /** Годы с явным календарём (переносы внесены вручную); для остальных — правило. */
    private static final Set<Integer> EXPLICIT_YEARS = Set.of(2025, 2026, 2027);

    public boolean isHoliday(LocalDate d) {
        for (int[] h : HOLIDAYS) if (d.getMonthValue() == h[0] && d.getDayOfMonth() == h[1]) return true;
        return false;
    }

    public static String holidayName(LocalDate d) {
        int m = d.getMonthValue(), day = d.getDayOfMonth();
        if (m == 1 && day <= 8) return day == 7 ? "Рождество Христово" : "Новогодние каникулы";
        if (m == 2 && day == 23) return "День защитника Отечества";
        if (m == 3 && day == 8) return "Международный женский день";
        if (m == 5 && day == 1) return "Праздник Весны и Труда";
        if (m == 5 && day == 9) return "День Победы";
        if (m == 6 && day == 12) return "День России";
        if (m == 11 && day == 4) return "День народного единства";
        return null;
    }

    private static boolean weekend(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }

    /** Нерабочий день по производственному календарю. */
    public boolean isNonWorking(LocalDate d) {
        if (WORKING_WEEKEND.contains(d)) return false;
        if (weekend(d) || isHoliday(d) || EXTRA_OFF.contains(d)) return true;
        if (EXPLICIT_YEARS.contains(d.getYear())) return false;
        // правило переноса для прочих лет: праздник (кроме 1–8 января) в выходной → ближайший будний день
        for (int back = 1; back <= 2; back++) {
            LocalDate p = d.minusDays(back);
            if (isHoliday(p) && weekend(p) && !(p.getMonthValue() == 1 && p.getDayOfMonth() <= 8)) {
                LocalDate next = p;
                do next = next.plusDays(1); while (weekend(next) || isHoliday(next));
                if (next.equals(d)) return true;
            }
        }
        return false;
    }

    public boolean isWorking(LocalDate d) {
        return !isNonWorking(d);
    }

    /** Последняя пятница ноября — «чёрная пятница». */
    public static LocalDate blackFriday(int year) {
        return LocalDate.of(year, 11, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.FRIDAY));
    }

    /**
     * Торговые события. Окно — местные (московские) даты {@code [from, toExclusive)}; {@code uplift} — во сколько
     * раз спрос в окне выше обычного у затронутых сезонов (ориентир для модулей заказов и сессий).
     */
    public enum SalesEvent {
        ELEVEN_ELEVEN("eleven_eleven", "Распродажа 11.11", "11.11 sale", 2.2),
        BLACK_FRIDAY("black_friday", "Чёрная пятница", "Black Friday", 2.6),
        NEW_YEAR("new_year", "Новогодние подарки", "New Year gifts", 1.7),
        WINTER_SALE("winter_sale", "Зимняя распродажа", "Winter sale", 1.2),
        DEFENDERS_DAY("feb23", "23 февраля", "Defender of the Fatherland Day", 1.4),
        WOMENS_DAY("mar8", "8 марта", "International Women's Day", 1.5),
        MAY_HOLIDAYS("may_holidays", "Майские: дача и отдых", "May holidays", 1.3),
        SUMMER_SALE("summer_sale", "Летняя распродажа", "Summer sale", 1.2),
        BACK_TO_SCHOOL("back_to_school", "Снова в школу", "Back to school", 1.6);

        public final String code;
        public final String nameRu;
        public final String nameEn;
        public final double uplift;

        SalesEvent(String code, String nameRu, String nameEn, double uplift) {
            this.code = code;
            this.nameRu = nameRu;
            this.nameEn = nameEn;
            this.uplift = uplift;
        }

        /** Окно события в году {@code year}: {@code [0]} — первый день, {@code [1]} — день после последнего. */
        public LocalDate[] window(int year) {
            return switch (this) {
                case ELEVEN_ELEVEN -> new LocalDate[]{LocalDate.of(year, 11, 9), LocalDate.of(year, 11, 12)};
                case BLACK_FRIDAY -> {
                    LocalDate f = blackFriday(year);
                    yield new LocalDate[]{f.minusDays(1), f.plusDays(4)};
                }
                case NEW_YEAR -> new LocalDate[]{LocalDate.of(year, 12, 10), LocalDate.of(year, 12, 31)};
                case WINTER_SALE -> new LocalDate[]{LocalDate.of(year, 1, 3), LocalDate.of(year, 1, 26)};
                case DEFENDERS_DAY -> new LocalDate[]{LocalDate.of(year, 2, 14), LocalDate.of(year, 2, 24)};
                case WOMENS_DAY -> new LocalDate[]{LocalDate.of(year, 3, 1), LocalDate.of(year, 3, 9)};
                case MAY_HOLIDAYS -> new LocalDate[]{LocalDate.of(year, 4, 25), LocalDate.of(year, 5, 12)};
                case SUMMER_SALE -> new LocalDate[]{LocalDate.of(year, 7, 1), LocalDate.of(year, 8, 1)};
                case BACK_TO_SCHOOL -> new LocalDate[]{LocalDate.of(year, 8, 5), LocalDate.of(year, 9, 8)};
            };
        }

        public boolean covers(LocalDate d) {
            LocalDate[] w = window(d.getYear());
            return !d.isBefore(w[0]) && d.isBefore(w[1]);
        }
    }

    /** Одно событие в одном году. */
    public record EventWindow(SalesEvent event, int year, LocalDate from, LocalDate toExclusive) {
    }

    /** Все окна событий, начинающиеся в {@code [from, to]} (по годам — стабильно при продлении периода). */
    public List<EventWindow> eventWindows(LocalDate from, LocalDate to) {
        List<EventWindow> out = new ArrayList<>();
        for (int y = from.getYear(); y <= to.getYear(); y++) {
            for (SalesEvent e : SalesEvent.values()) {
                LocalDate[] w = e.window(y);
                if (!w[0].isBefore(from) && !w[0].isAfter(to)) out.add(new EventWindow(e, y, w[0], w[1]));
            }
        }
        out.sort((a, b) -> a.from.compareTo(b.from) != 0 ? a.from.compareTo(b.from) : a.event.compareTo(b.event));
        return out;
    }

    public List<SalesEvent> eventsOn(LocalDate d) {
        List<SalesEvent> out = new ArrayList<>();
        for (SalesEvent e : SalesEvent.values()) if (e.covers(d)) out.add(e);
        return out;
    }

    /**
     * Сезон спроса товара (у вида товара в каталоге). Множитель спроса по дате — {@link #demand(LocalDate, Season)}:
     * месяц × день недели × событие. Это отправная модель для модулей заказов и сессий; уточнять — там.
     */
    public enum Season {
        /** ровный спрос, события — как у всех */
        BASE,
        /** подарочное: Новый год, 23.02, 8.03 */
        GIFTS,
        /** мужские подарки — пик к 23.02 */
        GIFTS_MEN,
        /** женские подарки, красота — пик к 8.03 */
        GIFTS_WOMEN,
        /** школа и учёба: август — начало сентября */
        SCHOOL,
        /** охлаждение: кондиционеры, вентиляторы — июнь – август */
        COOLING,
        /** обогрев: октябрь – февраль */
        HEATING,
        /** дача и сад: апрель – август */
        GARDEN,
        /** гирлянды и украшения — декабрь */
        DECOR
    }

    //                                     янв   фев   мар   апр   май   июн   июл   авг   сен   окт   ноя   дек
    private static final double[] M_BASE = {0.85, 0.85, 0.92, 0.92, 0.92, 0.90, 0.92, 0.98, 1.00, 1.00, 1.18, 1.35};
    private static final double[] M_COOL = {0.25, 0.25, 0.35, 0.60, 1.30, 2.60, 2.90, 1.80, 0.60, 0.30, 0.25, 0.30};
    private static final double[] M_HEAT = {1.70, 1.40, 0.90, 0.45, 0.20, 0.15, 0.15, 0.25, 0.70, 1.60, 1.90, 1.90};
    private static final double[] M_GARD = {0.20, 0.25, 0.60, 1.60, 2.20, 2.00, 1.70, 1.20, 0.70, 0.40, 0.25, 0.25};
    private static final double[] M_SCHL = {0.80, 0.80, 0.85, 0.85, 0.80, 0.80, 0.95, 1.90, 1.60, 0.95, 1.05, 1.20};
    private static final double[] M_DECO = {0.30, 0.10, 0.10, 0.10, 0.10, 0.10, 0.10, 0.10, 0.20, 0.50, 1.80, 4.50};
    /** День недели (пн … вс) — интернет-магазин: будни ровнее, вечер воскресенья — пик (суточный ход — у модулей). */
    private static final double[] WEEKDAY = {1.04, 1.00, 0.98, 0.98, 0.96, 0.95, 1.09};

    public double demand(LocalDate d, Season s) {
        int m = d.getMonthValue() - 1;
        double f = switch (s) {
            case COOLING -> M_COOL[m];
            case HEATING -> M_HEAT[m];
            case GARDEN -> M_GARD[m];
            case SCHOOL -> M_SCHL[m];
            case DECOR -> M_DECO[m];
            default -> M_BASE[m];
        };
        f *= WEEKDAY[d.getDayOfWeek().getValue() - 1];
        for (SalesEvent e : eventsOn(d)) f *= eventUplift(e, s);
        // 1–2 января — провал, 31 декабря — до обеда
        if (d.getMonth() == Month.JANUARY && d.getDayOfMonth() <= 2) f *= 0.45;
        if (d.getMonth() == Month.DECEMBER && d.getDayOfMonth() == 31) f *= 0.6;
        return f;
    }

    private static double eventUplift(SalesEvent e, Season s) {
        return switch (e) {
            case ELEVEN_ELEVEN, BLACK_FRIDAY -> e.uplift;
            case NEW_YEAR -> s == Season.GIFTS || s == Season.DECOR ? e.uplift : 1.15;
            case DEFENDERS_DAY -> s == Season.GIFTS_MEN ? 2.0 : (s == Season.GIFTS ? e.uplift : 1.0);
            case WOMENS_DAY -> s == Season.GIFTS_WOMEN ? 2.2 : (s == Season.GIFTS ? e.uplift : 1.0);
            case BACK_TO_SCHOOL -> s == Season.SCHOOL ? e.uplift : 1.0;
            case MAY_HOLIDAYS -> s == Season.GARDEN ? e.uplift : 1.0;
            case WINTER_SALE, SUMMER_SALE -> e.uplift;
        };
    }

    /** Нерабочие дни в интервале — для отчётов и SOURCE.md. */
    public TreeMap<LocalDate, String> nonWorkingDays(LocalDate from, LocalDate to) {
        TreeMap<LocalDate, String> out = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isNonWorking(d) && !weekend(d)) {
                String n = holidayName(d);
                out.put(d, n != null ? n : "перенос выходного");
            } else if (isHoliday(d)) {
                out.put(d, holidayName(d));
            }
        }
        return out;
    }
}
