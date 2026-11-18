package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.auction.calendar.AuctionCalendar;
import io.hammerhall.data.auc.auction.calendar.Depts;
import io.hammerhall.data.auc.auction.calendar.SaleSpec;
import io.hammerhall.data.auc.auction.calendar.Sessions;
import io.hammerhall.data.auc.auction.calendar.Wave;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.core.time.RuCalendar;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Календарь торгов: даты, главные недели, паузы, число торгов, волна, сессии, стабильность при продлении. */
class AuctionCalendarTest {
    static final long SEED = 20261018L;
    static final Period PERIOD = new Period(LocalDate.of(2025, 10, 1), LocalDate.of(2027, 10, 30),
            Instant.parse("2027-09-01T00:00:00Z"), Period.MOSCOW);
    static final AuctionCalendar CAL = new AuctionCalendar(new RandomStreams(SEED));
    static final int[] LIVE_PER_YEAR = {16, 12, 10, 10, 8, 4};

    static LocalDate day(long epoch) {
        return Period.localDate(epoch, Period.MOSCOW);
    }

    static LocalDateTime at(long epoch) {
        return Period.localDateTime(epoch, Period.MOSCOW);
    }

    @Test
    void sixtyLiveSalesAYearBySeason() {
        for (int y = 2025; y <= 2030; y++) {
            int[] dept = new int[Depts.COUNT];
            int[] season = new int[3];
            for (SaleSpec s : CAL.year(y)) {
                if (!s.live()) continue;
                dept[s.dept()]++;
                int md = day(s.startsAt()).getMonthValue() * 100 + day(s.startsAt()).getDayOfMonth();
                season[md >= 215 && md <= 605 ? 0 : md >= 915 && md <= 1215 ? 1 : 2]++;
            }
            for (int d = 0; d < Depts.COUNT; d++) assertEquals(LIVE_PER_YEAR[d], dept[d], y + " " + Depts.CODE[d]);
            assertEquals(27, season[0], y + ": весна ≈ 45 %");
            assertEquals(27, season[1], y + ": осень ≈ 45 %");
            assertEquals(6, season[2], y + ": остальное ≈ 10 %");
        }
    }

    @Test
    void aboutFourHundredTwentyOnlineSalesAYear() {
        for (int y = 2025; y <= 2030; y++) {
            int regular = 0, thematic = 0;
            Map<LocalDate, Integer> sundays = new TreeMap<>();
            for (SaleSpec s : CAL.year(y)) {
                if (s.live()) continue;
                LocalDate d = day(s.startsAt());
                assertEquals(19, at(s.startsAt()).getHour(), s.code() + ": волна с 19:00");
                assertFalse(d.getMonthValue() == 1 && d.getDayOfMonth() <= 10, s.code() + ": 1–10 января торгов нет");
                DayOfWeek w = d.getDayOfWeek();
                if (s.thematic()) {
                    thematic++;
                    assertEquals(DayOfWeek.SUNDAY, w, s.code());
                    assertEquals(s.dept(), Depts.THEMES[s.theme()].dept(), s.code());
                    sundays.merge(d, 1, Integer::sum);
                } else {
                    regular++;
                    DayOfWeek want = switch (s.dept()) {
                        case Depts.COINS, Depts.BOOKS -> DayOfWeek.TUESDAY;
                        case Depts.JEWELLERY, Depts.DECORATIVE -> DayOfWeek.WEDNESDAY;
                        default -> DayOfWeek.THURSDAY;
                    };
                    assertEquals(want, w, s.code());
                }
                int dur = s.durationDays();
                assertTrue(dur == 7 || dur == 10 || dur == 14, s.code() + ": " + dur);
                assertEquals(s.startsAt() - dur * 86_400L, s.biddingOpensAt(), s.code());
                assertEquals(s.biddingOpensAt() - 3 * 86_400L, s.publishedAt(), s.code() + ": каталог за 3 дня");
                assertTrue(s.targetLots() <= 7 * Wave.MAX_PER_EVENING, s.code());
            }
            for (int n : sundays.values()) assertEquals(2, n, y + ": два тематических в воскресенье");
            int total = regular + thematic;
            assertTrue(total >= 395 && total <= 425, y + ": онлайн-торгов " + total);
            assertTrue(thematic >= 95 && thematic <= 106, y + ": тематических " + thematic);
        }
    }

    @Test
    void liveDaysAvoidPausesAndHolidays() {
        RuCalendar ru = new RuCalendar();
        for (int y = 2025; y <= 2030; y++) {
            Map<String, String> room = new TreeMap<>();
            for (SaleSpec s : CAL.year(y)) {
                if (!s.live()) continue;
                assertEquals(s.days(), s.saleDays().size(), s.code());
                assertTrue(s.days() >= 1 && s.days() <= 3, s.code() + ": дней " + s.days());
                for (LocalDate d : s.saleDays()) {
                    DayOfWeek w = d.getDayOfWeek();
                    assertTrue(w != DayOfWeek.SUNDAY && w != DayOfWeek.MONDAY, s.code() + " " + d + ": только вт–сб");
                    int m = d.getMonthValue(), dd = d.getDayOfMonth();
                    assertFalse(m == 1 && dd <= 10 || m == 5 && dd <= 10 || m == 7 && dd >= 15 || m == 8, s.code() + " " + d + ": пауза");
                    assertFalse(ru.isHoliday(d), s.code() + " " + d + ": праздник");
                    if (w != DayOfWeek.SATURDAY) assertTrue(ru.isWorking(d), s.code() + " " + d);
                    if (!s.tier().equals("evening")) {
                        String prev = room.put(s.office() + " " + d, s.code());
                        assertEquals(null, prev, "зал " + s.office() + " " + d + ": " + prev + " и " + s.code());
                    }
                }
                LocalDate first = s.saleDays().getFirst();
                assertEquals(first, day(s.startsAt()), s.code());
                long lead = first.toEpochDay() - day(s.publishedAt()).toEpochDay();
                assertTrue(lead >= 14 && lead <= 35, s.code() + ": каталог за " + lead + " дней");
                assertEquals(s.publishedAt(), s.biddingOpensAt(), s.code() + ": живые — ставки с публикацией");
                assertEquals(first.minusDays(6), s.previewFrom(), s.code());
                assertEquals(first.minusDays(1), s.previewTo(), s.code());
                assertTrue(s.office() == 1 || s.office() == 2, s.code());
            }
        }
    }

    @Test
    void majorWeeksOfTheSpecification() {
        LocalDate[][] weeks = {{LocalDate.of(2025, 11, 18), LocalDate.of(2025, 11, 22)},
                {LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 18)}, {LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 21)},
                {LocalDate.of(2027, 4, 13), LocalDate.of(2027, 4, 17)}};
        for (LocalDate[] w : weeks) {
            boolean found = false;
            for (LocalDate[] m : AuctionCalendar.majorWeeks(w[0].getYear())) found |= m[0].equals(w[0]) && m[1].equals(w[1]);
            assertTrue(found, "главная неделя " + w[0]);
            Set<String> seen = new HashSet<>();
            for (SaleSpec s : CAL.year(w[0].getYear())) {
                if (!s.live() || s.tier().equals("regular")) continue;
                LocalDate d = day(s.startsAt());
                if (d.isBefore(w[0]) || d.isAfter(w[1])) continue;
                seen.add(s.tier() + " " + Depts.CODE[s.dept()]);
                assertEquals(1, s.office(), s.code() + ": главные — в Москве");
                assertEquals(40, s.pace(), s.code() + ": темп дорогих торгов");
                if (s.tier().equals("evening")) {
                    assertEquals(19, at(s.startsAt()).getHour(), s.code());
                    assertTrue(s.targetLots() >= 50 && s.targetLots() <= 70, s.code() + ": ≈ 60 лотов");
                }
            }
            assertEquals(Set.of("major jewellery", "major paintings", "evening paintings", "major decorative"), seen, "неделя " + w[0]);
        }
        int majors = 0;
        for (SaleSpec s : CAL.year(2026)) if (s.live() && !s.tier().equals("regular")) majors++;
        assertEquals(8, majors, "главных и вечерних — 8 из 60");
    }

    @Test
    void waveFiveLotsAMinuteNoMoreThan1200AnEvening() {
        long start = Period.epochOf(LocalDateTime.of(2026, 3, 3, 19, 0), Period.MOSCOW);
        Map<Long, Integer> perMinute = new TreeMap<>();
        Map<LocalDate, Integer> perEvening = new TreeMap<>();
        int lots = 3_700;
        for (int k = 0; k < lots; k++) {
            long t = Wave.plannedClose(start, k);
            perMinute.merge(t, 1, Integer::sum);
            perEvening.merge(day(t), 1, Integer::sum);
            LocalDateTime l = at(t);
            assertTrue(l.getHour() >= 19 && l.getHour() < 23, "волна 19:00–23:00: " + l);
            if (k > 0) assertTrue(t >= Wave.plannedClose(start, k - 1), "по порядку");
        }
        for (int n : perMinute.values()) assertEquals(Wave.LOTS_PER_GROUP, n == 5 ? 5 : n, "группа по 5");
        for (Map.Entry<Long, Integer> e : perMinute.entrySet()) assertTrue(e.getValue() <= 5);
        for (int n : perEvening.values()) assertTrue(n <= Wave.MAX_PER_EVENING, "за вечер " + n);
        assertEquals(4, perEvening.size(), "3 700 лотов — 4 вечера");
        assertEquals(Wave.MAX_PER_EVENING, perEvening.values().iterator().next());
        assertEquals(start + 60, Wave.plannedClose(start, 5), "следующая группа — через 60 с");
        assertEquals(start + 86_400, Wave.plannedClose(start, 1200), "1 201-й лот — следующим вечером");
        assertEquals(4, Wave.evenings(lots));
    }

    @Test
    void durationsShares() {
        int[] c = new int[15];
        int n = 0;
        for (int y = 2025; y <= 2032; y++) {
            for (SaleSpec s : CAL.year(y)) {
                if (s.live()) continue;
                c[s.durationDays()]++;
                n++;
            }
        }
        assertEquals(0.55, c[7] / (double) n, 0.04, "7 суток");
        assertEquals(0.30, c[10] / (double) n, 0.04, "10 суток");
        assertEquals(0.15, c[14] / (double) n, 0.03, "14 суток");
    }

    @Test
    void catalogSizesLognormal() {
        List<Integer> on = new ArrayList<>(), live = new ArrayList<>();
        for (int y = 2025; y <= 2034; y++) {
            for (SaleSpec s : CAL.year(y)) {
                if (s.live()) {
                    if (s.tier().equals("regular")) live.add(s.targetLots());
                } else {
                    LocalDate d = day(s.startsAt());
                    if (d.getMonthValue() < 6 || d.getMonthValue() > 8) on.add(s.targetLots());
                }
            }
        }
        on.sort(null);
        live.sort(null);
        int medOn = on.get(on.size() / 2), p95On = on.get((int) (on.size() * 0.95));
        assertTrue(medOn > 1080 && medOn < 1320, "онлайн: медиана " + medOn);
        assertTrue(p95On > 4300 && p95On < 5800, "онлайн: p95 " + p95On);
        double mean = on.stream().mapToInt(Integer::intValue).average().orElse(0);
        assertTrue(mean > 1550 && mean < 1900, "онлайн: среднее " + mean);
        int medLive = live.get(live.size() / 2);
        assertTrue(medLive > 190 && medLive < 270, "живые: медиана " + medLive);
    }

    @Test
    void sessionsAtMost250LotsInAllowedSlots() {
        for (int y = 2025; y <= 2028; y++) {
            for (SaleSpec s : CAL.year(y)) {
                if (!s.live()) continue;
                for (double scale : new double[]{1.0, 0.1, 0.01}) {
                    int lots = s.targetLots(scale);
                    List<Sessions.Session> plan = Sessions.plan(s.saleDays(), s.firstMinute(), s.pace(), lots);
                    assertFalse(plan.isEmpty(), s.code());
                    int next = 1;
                    Map<LocalDate, Integer> perDay = new TreeMap<>();
                    for (Sessions.Session x : plan) {
                        assertEquals(next, x.firstLotNo(), s.code() + ": лоты подряд");
                        assertTrue(x.lots() >= 1 && x.lots() <= Sessions.MAX_LOTS, s.code() + ": в сессии " + x.lots());
                        assertTrue(x.startMinute() == Sessions.NOON || x.startMinute() == Sessions.AFTERNOON
                                || x.startMinute() == Sessions.EVENING, s.code());
                        double hours = x.lots() / (double) s.pace();
                        assertTrue(hours <= 3.5 + 1e-9, s.code() + ": сессия " + hours + " ч");
                        assertTrue(s.saleDays().contains(x.day()), s.code() + ": день сессии вне плана " + x.day());
                        perDay.merge(x.day(), 1, Integer::sum);
                        next = x.lastLotNo() + 1;
                    }
                    assertEquals(lots + 1, next, s.code());
                    for (int n : perDay.values()) assertTrue(n >= 1 && n <= 3, s.code());
                    if (scale == 1.0) assertEquals(plan.getFirst().startsAt(), s.startsAt(), s.code());
                }
            }
        }
        assertEquals(10, CAL.year(2026).stream().filter(SaleSpec::live).findFirst().orElseThrow().targetLots(0.01),
                "small: не меньше 10 лотов у живых");
    }

    @Test
    void codesNumberedByStartWithinYear() {
        for (int y = 2025; y <= 2028; y++) {
            Set<String> codes = new HashSet<>();
            int lastLive = 0, lastOn = 0;
            for (SaleSpec s : CAL.year(y)) {
                assertTrue(codes.add(s.code()), s.code());
                assertEquals(y, day(s.startsAt()).getYear());
                String want = s.live() ? String.format("L-%d-%03d", y, s.number()) : String.format("T-%d-%04d", y, s.number());
                assertEquals(want, s.code());
                if (s.live()) assertEquals(++lastLive, s.number());
                else assertEquals(++lastOn, s.number());
                assertTrue(s.titleRu().endsWith("№ " + s.number()), s.titleRu());
                String bp = s.dept() == Depts.COINS ? "BP-COIN" : day(s.startsAt()).isBefore(LocalDate.of(2026, 9, 1)) ? "BP-2024" : "BP-2026";
                assertEquals(bp, s.premium(), s.code());
            }
        }
    }

    @Test
    void releaseMatchesVolumeAndShortRunIsPrefix() {
        long slice = PERIOD.nowEpoch();
        List<SaleSpec> r = CAL.inRelease(PERIOD);
        int pubOn = 0, pubLive = 0, sched = 0;
        for (SaleSpec s : r) {
            assertTrue(s.publishedAt() >= PERIOD.startEpoch() && s.publishedAt() < PERIOD.endEpochExclusive(), s.code());
            if (s.publishedAt() < slice) {
                if (s.live()) pubLive++;
                else pubOn++;
            } else {
                sched++;
            }
        }
        assertTrue(r.size() >= 950 && r.size() <= 1010, "торгов в выпуске " + r.size());
        assertTrue(pubOn >= 760 && pubOn <= 800, "онлайн с каталогом " + pubOn);
        assertTrue(pubLive >= 100 && pubLive <= 120, "живых с каталогом " + pubLive);
        assertTrue(sched >= 60 && sched <= 110, "назначенных " + sched);
        for (int i = 1; i < r.size(); i++) {
            assertTrue(AuctionCalendar.BY_PUBLICATION.compare(r.get(i - 1), r.get(i)) < 0, "порядок id");
        }
        // короткий прогон и продление: начало длинного
        List<SaleSpec> s = CAL.inRelease(PERIOD.withTo(LocalDate.of(2027, 3, 31)));
        List<SaleSpec> longer = CAL.inRelease(PERIOD.withTo(LocalDate.of(2028, 6, 30)));
        assertTrue(s.size() < r.size() && r.size() < longer.size());
        for (int i = 0; i < s.size(); i++) assertEquals(r.get(i).key(), s.get(i).key(), "короткий — начало полного: " + i);
        for (int i = 0; i < r.size(); i++) assertEquals(r.get(i).key(), longer.get(i).key(), "полный — начало длинного: " + i);
        assertEquals("T-2025-0316", r.get(0).code(), "первые торги выпуска");
        List<SaleSpec> m = CAL.modeled(PERIOD, slice);
        for (int i = 0; i < r.size(); i++) assertEquals(r.get(i).key(), m.get(i).key(), "выпуск — начало моделируемых");
    }

    @Test
    void calendarIsAFunctionOfDateNotOfPeriodOrOrder() {
        AuctionCalendar a = new AuctionCalendar(new RandomStreams(SEED));
        AuctionCalendar b = new AuctionCalendar(new RandomStreams(SEED));
        List<SaleSpec> b27 = b.year(2027);
        b.year(2025);
        assertEquals(a.year(2027), b27, "год не зависит от порядка расчёта");
        assertEquals(a.year(2026), b.year(2026));
        AuctionCalendar other = new AuctionCalendar(new RandomStreams(SEED + 1));
        assertNotEquals(a.year(2026), other.year(2026), "другой seed — другой календарь");
        // торги на любой год вперёд
        for (int y = 2031; y <= 2040; y += 3) assertEquals(60, a.year(y).stream().filter(SaleSpec::live).count(), "год " + y);
    }
}
