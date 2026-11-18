package io.hammerhall.data.auc.auction.calendar;

/**
 * Волна закрытий онлайн-торгов: с 19:00 по Москве группами по 5 лотов каждые 60 с — ≈ 300 лотов в час, за вечер
 * не больше 1 200 лотов (19:00–23:00); торги больше закрываются несколько вечеров подряд. Лот с порядковым номером
 * {@code k} в каталоге ({@code 0, 1, …}) закрывается по плану в вечер {@code k / 1200}, в группе
 * {@code (k mod 1200) / 5}. У Москвы нет перехода на летнее время, поэтому «тот же час следующего дня» — +86 400 с.
 * Мягкое закрытие (T10): окно, продление и потолок — поля торгов.
 */
public final class Wave {
    private Wave() {
    }

    public static final int LOTS_PER_GROUP = 5, GROUP_INTERVAL_S = 60, MAX_PER_EVENING = 1200;
    public static final int EXTENSION_WINDOW_S = 120, EXTENSION_S = 120, EXTENSION_CAP_S = 3600;
    static final long DAY = 86_400;

    /** Плановое закрытие лота номер {@code ordinal} (с 0) торгов с первой группой в {@code startsAt}, секунды. */
    public static long plannedClose(long startsAt, int ordinal) {
        if (ordinal < 0) throw new IllegalArgumentException("номер в волне " + ordinal);
        int evening = ordinal / MAX_PER_EVENING;
        int group = (ordinal % MAX_PER_EVENING) / LOTS_PER_GROUP;
        return startsAt + evening * DAY + (long) group * GROUP_INTERVAL_S;
    }

    /** Вечеров волны у каталога из {@code lots} лотов. */
    public static int evenings(int lots) {
        return Math.max(1, (lots + MAX_PER_EVENING - 1) / MAX_PER_EVENING);
    }

    /** Плановое закрытие последнего лота. */
    public static long lastPlannedClose(long startsAt, int lots) {
        return plannedClose(startsAt, Math.max(0, lots - 1));
    }
}
