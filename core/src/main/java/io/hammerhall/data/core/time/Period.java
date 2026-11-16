package io.hammerhall.data.core.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Окно данных: даты {@code from..to} включительно (границы — полночь UTC), «сейчас» выгрузки и пояс бизнеса — по нему
 * идут суточные события домена (у магазина — Москва: смены цен, начала акций — в 00:00 по Москве). Своё окно у каждого
 * набора — {@code Dataset.period()}.
 */
public record Period(LocalDate from, LocalDate to, Instant now, ZoneId businessZone) {
    public static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    public Period {
        if (!from.isBefore(to)) throw new IllegalArgumentException("период пуст: " + from + ".." + to);
    }

    public Period withTo(LocalDate newTo) {
        return new Period(from, newTo, now, businessZone);
    }

    /** Начало окна, секунды Unix (полночь UTC даты {@code from}). */
    public long startEpoch() {
        return from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    /** Конец окна, исключая (полночь UTC после даты {@code to}). */
    public long endEpochExclusive() {
        return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    public long nowEpoch() {
        return now.getEpochSecond();
    }

    public boolean contains(long epochSecond) {
        return epochSecond >= startEpoch() && epochSecond < endEpochExclusive();
    }

    public boolean containsDate(LocalDate d) {
        return !d.isBefore(from) && !d.isAfter(to);
    }

    /** Полночь даты по поясу бизнеса — момент смены цен и начала акций. */
    public long businessMidnight(LocalDate d) {
        return d.atStartOfDay(businessZone).toEpochSecond();
    }

    public LocalDate businessDate(long epochSecond) {
        return LocalDateTime.ofEpochSecond(epochSecond, 0,
                businessZone.getRules().getOffset(Instant.ofEpochSecond(epochSecond))).toLocalDate();
    }

    public static LocalDate localDate(long epochSecond, ZoneId zone) {
        Instant i = Instant.ofEpochSecond(epochSecond);
        return LocalDateTime.ofEpochSecond(epochSecond, 0, zone.getRules().getOffset(i)).toLocalDate();
    }

    public static LocalDateTime localDateTime(long epochSecond, ZoneId zone) {
        Instant i = Instant.ofEpochSecond(epochSecond);
        return LocalDateTime.ofEpochSecond(epochSecond, 0, zone.getRules().getOffset(i));
    }

    /** Момент местного времени в поясе (для переходов на летнее время — первое из двух возможных). */
    public static long epochOf(LocalDateTime local, ZoneId zone) {
        return local.atZone(zone).toEpochSecond();
    }
}
