package io.hammerhall.data.core.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Окно данных: даты {@code from..to} включительно (границы — полночь UTC), «сейчас» выгрузки и пояс бизнеса — по нему
 * идут суточные события домена (у магазина — Москва: смены цен, начала акций — в 00:00 по Москве). Своё окно у каждого
 * набора — {@code Dataset.period()}; у связанных наборов оно общее — {@link #common(ZoneId)}.
 */
public record Period(LocalDate from, LocalDate to, Instant now, ZoneId businessZone) {
    public static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Начало общего окна связанных наборов — 01.10.2025. */
    public static final LocalDate COMMON_FROM = LocalDate.of(2025, 10, 1);
    /** Конец общего окна (включительно) — 30.10.2027: после «сейчас» — то, что уже запланировано. */
    public static final LocalDate COMMON_TO = LocalDate.of(2027, 10, 30);
    /** «Сейчас» общего окна — 01.09.2027 00:00 UTC (среда, 03:00 по Москве). */
    public static final Instant COMMON_NOW = Instant.parse("2027-09-01T00:00:00Z");

    /**
     * Общее окно связанных наборов — одно на все, чтобы заказ, платёж, бронь и просмотр одного дня жили в одном
     * времени: {@link #COMMON_FROM}..{@link #COMMON_TO}, «сейчас» — {@link #COMMON_NOW}; пояс бизнеса — свой у набора.
     * Набор, которому нужна предыстория (у авиа — продажи с 01.09.2025), берёт раньше только начало.
     */
    public static Period common(ZoneId businessZone) {
        return new Period(COMMON_FROM, COMMON_TO, COMMON_NOW, businessZone);
    }

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
