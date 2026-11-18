package io.hammerhall.data.auc.bid;

/**
 * Заложенные сбои онлайн-лота — малой долей, по выбору генератора. Каждый сбой скрыто помечен в
 * событиях ({@link LotOutcome#hiddenMissedExtension}, {@link LotOutcome#hiddenEarlyClose},
 * {@link LotOutcome#hiddenRepeat}), а в сводке — заложен ли он на самом деле ({@code planted…}): сбой случается, только
 * если в лоте нашёлся подходящий случай. Проверка инвариантов пропускает ровно помеченное.
 *
 * @param missedExtension первая принятая поздняя ставка, которая должна продлить лот, не продлевает (сбой часов)
 * @param earlyCloseMs    лот закрывается на столько мс раньше своего конца (ставки в эти мс — после закрытия); 0 — нет
 * @param doubleAccept    повтор принятой ручной ставки (двойное нажатие) принят второй раз: участник, уже ведущий,
 *                        перебивает сам себя на минимальный шаг — две принятые ставки одного запроса
 */
public record Faults(boolean missedExtension, long earlyCloseMs, boolean doubleAccept) {
    /** Без сбоев. */
    public static final Faults NONE = new Faults(false, 0, false);

    public Faults {
        if (earlyCloseMs < 0) throw new IllegalArgumentException("ранний конец " + earlyCloseMs);
    }

    public static Faults ofMissedExtension() {
        return new Faults(true, 0, false);
    }

    public static Faults ofEarlyClose(long ms) {
        return new Faults(false, ms, false);
    }

    public static Faults ofDoubleAccept() {
        return new Faults(false, 0, true);
    }

    public boolean any() {
        return missedExtension || earlyCloseMs > 0 || doubleAccept;
    }
}
