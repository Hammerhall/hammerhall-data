package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.world.HouseRules;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Время денег — по Москве: дата выставления и оплаты, срок оплаты, 31-й день, день выплаты. Москва без перехода
 * на летнее время — UTC+3; это проверяется при загрузке класса по поясу дома ({@link HouseRules#ZONE}).
 */
public final class MoneyTime {
    private MoneyTime() {
    }

    public static final long DAY = 86_400;
    /** Смещение Москвы, секунды. */
    public static final long MSK = 3 * 3600;

    static {
        for (int y = 2015; y <= 2040; y++) {
            for (int m : new int[]{1, 7}) {
                Instant i = LocalDate.of(y, m, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
                if (HouseRules.ZONE.getRules().getOffset(i).getTotalSeconds() != MSK) {
                    throw new IllegalStateException("пояс дома — не UTC+3 в " + y + "-" + m);
                }
            }
        }
    }

    /** Номер суток по Москве (эпоха — 1970-01-01). */
    public static long epochDay(long at) {
        return Math.floorDiv(at + MSK, DAY);
    }

    public static LocalDate date(long at) {
        return LocalDate.ofEpochDay(epochDay(at));
    }

    /** Начало суток по Москве: 00:00:00 дня с номером {@code epochDay}. */
    public static long dayStart(long epochDay) {
        return epochDay * DAY - MSK;
    }

    public static long dayStart(LocalDate d) {
        return dayStart(d.toEpochDay());
    }

    /** Момент: сутки по Москве и секунда от полуночи. */
    public static long at(long epochDay, long secondOfDay) {
        return dayStart(epochDay) + secondOfDay;
    }

    /**
     * Срок оплаты (M6): 23:59:59 по Москве дня «дата выставления + {@code dueDays}» (10 календарных дней). Счёт
     * просрочен, когда {@code due_at} &lt; «сейчас»; дней просрочки — дата полной оплаты по Москве минус дата срока.
     */
    public static long dueAt(long issuedAt, int dueDays) {
        return HouseRules.dueAt(issuedAt, dueDays);
    }

    /** Дней просрочки на момент {@code at}: дата {@code at} по Москве − дата срока; не меньше 0. */
    public static int lateDays(long dueAt, long at) {
        return (int) Math.max(0, epochDay(at) - epochDay(dueAt));
    }
}
