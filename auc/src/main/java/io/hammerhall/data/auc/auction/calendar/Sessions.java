package io.hammerhall.data.auc.auction.calendar;

import io.hammerhall.data.core.time.Period;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Сессии живых торгов: начало 12:00, 16:00 или 19:00 по Москве, по 2–3,5 ч, не больше 250 лотов в сессии.
 * День торгов — сессии 12:00 (до 3,5 ч) и 16:00 (до 3 ч, до вечера); вечерние торги — одна сессия 19:00 (до 3,5 ч).
 * Вместимость сессии — {@code min(250, темп × часы)}. Лоты делятся между сессиями поровну по вместимости, по порядку
 * номеров. Дни торгов — план календаря (по размеру каталога в {@code full}); в профиле лотов меньше, сессий — тоже.
 */
public final class Sessions {
    private Sessions() {
    }

    public static final int MAX_LOTS = 250;
    public static final int NOON = 720, AFTERNOON = 960, EVENING = 1140;

    /** Сессия живых торгов: номер с 1, день, начало (минут от полуночи по Москве), лоты с — по (номера каталога). */
    public record Session(int no, LocalDate day, int startMinute, int firstLotNo, int lastLotNo) {
        public int lots() {
            return lastLotNo - firstLotNo + 1;
        }

        /** Начало, секунды Unix. */
        public long startsAt() {
            return Period.epochOf(LocalDateTime.of(day, java.time.LocalTime.of(startMinute / 60, startMinute % 60)),
                    Period.MOSCOW);
        }
    }

    /** Наибольшая длительность сессии, минут: 12:00 и 19:00 — 3,5 ч, 16:00 — 3 ч. */
    static int maxMinutes(int startMinute) {
        return startMinute == AFTERNOON ? 180 : 210;
    }

    /** Вместимость сессии, лотов. */
    public static int cap(int pace, int startMinute) {
        return Math.min(MAX_LOTS, pace * maxMinutes(startMinute) / 60);
    }

    /** Начала сессий дня {@code dayIndex} (с 0) торгов, первая сессия которых — в {@code firstMinute}. */
    static int[] daySlots(int dayIndex, int firstMinute) {
        if (firstMinute == EVENING) return new int[]{EVENING};
        if (dayIndex == 0 && firstMinute == AFTERNOON) return new int[]{AFTERNOON};
        return new int[]{NOON, AFTERNOON};
    }

    /** Сколько дней нужно каталогу из {@code lots} лотов. */
    public static int daysFor(int lots, int pace, int firstMinute) {
        int left = lots, d = 0;
        while (left > 0) {
            for (int s : daySlots(d, firstMinute)) left -= cap(pace, s);
            d++;
        }
        return Math.max(1, d);
    }

    /**
     * Сессии каталога из {@code lots} лотов: наименьшее число сессий по порядку слотов дней {@code saleDays}, лоты — поровну
     * по вместимости. Не хватило дней плана (каталог больше плана) — следующие дни подряд.
     */
    public static List<Session> plan(List<LocalDate> saleDays, int firstMinute, int pace, int lots) {
        if (lots <= 0) return List.of();
        List<LocalDate> days = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<Integer> caps = new ArrayList<>();
        int total = 0;
        for (int d = 0; total < lots; d++) {
            LocalDate day = d < saleDays.size() ? saleDays.get(d) : saleDays.getLast().plusDays(d - saleDays.size() + 1);
            for (int s : daySlots(d, firstMinute)) {
                if (total >= lots) break;
                days.add(day);
                starts.add(s);
                int c = cap(pace, s);
                caps.add(c);
                total += c;
            }
        }
        int n = caps.size();
        int[] share = new int[n];
        int given = 0;
        long acc = 0;
        for (int i = 0; i < n; i++) {
            acc += caps.get(i);
            int upto = (int) (acc * lots / total);
            share[i] = upto - given;
            given = upto;
        }
        List<Session> out = new ArrayList<>(n);
        int next = 1;
        for (int i = 0; i < n; i++) {
            if (share[i] <= 0) continue;
            out.add(new Session(out.size() + 1, days.get(i), starts.get(i), next, next + share[i] - 1));
            next += share[i];
        }
        return out;
    }
}
