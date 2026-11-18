package io.hammerhall.data.auc.world;

import io.hammerhall.data.core.time.Period;

import java.time.LocalDate;

/**
 * Срез фактов и горизонт моделирования — одно правило для всех модулей. Срез = min(«сейчас», конец
 * периода + 1 сутки); в выпуске — факты с моментом раньше среза. Моделирование идёт до среза + 180 суток (постоянный
 * горизонт — дольше любого пути «приём → каталог → закрытие → оплата на 31-й день»), чтобы история до среза не
 * зависела от конца периода.
 */
public final class Horizon {
    private Horizon() {
    }

    /** Постоянный горизонт моделирования сверх среза, сутки. */
    public static final int DAYS = 180;
    /** Начало потока приёма вещей и договоров — для первых торгов периода. */
    public static final LocalDate INTAKE_FROM = LocalDate.of(2025, 6, 1);
    /** Клиенты регистрируются с 2012 года. */
    public static final LocalDate CLIENTS_FROM = LocalDate.of(2012, 1, 1);

    /** Срез фактов, секунды Unix. */
    public static long cut(Period p) {
        return Math.min(p.nowEpoch(), p.endEpochExclusive());
    }

    /** Конец моделирования: срез + {@link #DAYS} суток, секунды Unix. */
    public static long end(Period p) {
        return cut(p) + DAYS * 86_400L;
    }

    /** Сутки среза по Москве (последние сутки с фактами — накануне, если срез — полночь). */
    public static LocalDate cutDay(Period p) {
        return Period.localDate(cut(p), HouseRules.ZONE);
    }

    /** Последние сутки моделирования по Москве. */
    public static LocalDate endDay(Period p) {
        return Period.localDate(end(p) - 1, HouseRules.ZONE);
    }
}
