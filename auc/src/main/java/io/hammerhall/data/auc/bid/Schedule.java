package io.hammerhall.data.auc.bid;

/**
 * Расписание лотов в торгах — целочисленно, без накопления ошибки. Время — миллисекунды Unix (UTC).
 *
 * <ul>
 *   <li><b>Волна закрытия онлайн-торгов</b>: лоты закрываются по одному, лот с порядковым номером {@code k}
 *       ({@code 0, 1, …}) — в {@code первое + k·интервал}. Продление одного лота других не сдвигает.</li>
 *   <li><b>Слоты живых торгов</b>: темп — лотов в час; слот лота {@code k} — {@code [начало + ⌊k·час/темп⌋,
 *       начало + ⌊(k+1)·час/темп⌋)}. Перерывы и смены аукциониста — забота генератора (новое начало).</li>
 * </ul>
 */
public final class Schedule {
    private static final long HOUR = 3_600_000;

    private Schedule() {
    }

    /** Назначенное закрытие онлайн-лота номер {@code ordinal} в волне. */
    public static long waveClose(long firstCloseAt, long intervalMs, int ordinal) {
        if (intervalMs < 0 || ordinal < 0) throw new IllegalArgumentException("интервал " + intervalMs + ", номер " + ordinal);
        return firstCloseAt + Math.multiplyExact(intervalMs, (long) ordinal);
    }

    /** Начало слота живого лота номер {@code ordinal} при темпе {@code lotsPerHour}. */
    public static long liveSlotStart(long saleStart, int lotsPerHour, int ordinal) {
        if (lotsPerHour <= 0 || ordinal < 0) throw new IllegalArgumentException("темп " + lotsPerHour + ", номер " + ordinal);
        return saleStart + Math.multiplyExact(HOUR, (long) ordinal) / lotsPerHour;
    }

    /** Конец слота (исключая) — начало следующего. */
    public static long liveSlotEnd(long saleStart, int lotsPerHour, int ordinal) {
        return liveSlotStart(saleStart, lotsPerHour, ordinal + 1);
    }
}
