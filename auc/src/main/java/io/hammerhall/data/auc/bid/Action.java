package io.hammerhall.data.auc.bid;

/**
 * Действие сценария онлайн-лота: ставка, которая приходит в движок в момент {@code at} (миллисекунды Unix). Действия
 * одной миллисекунды принимаются в порядке списка — это и есть порядок приёма. Для тестов и особых случаев генератора;
 * участник сценария обычно со стратегией {@link Strategy#SCRIPTED}, но автоставки за него система ведёт как обычно.
 *
 * @param at     момент приёма
 * @param bidder индекс участника в списке участников лота
 * @param amount сумма ручной ставки или максимум (приводится к сетке вниз); у повтора не используется
 * @param max    {@code true} — поставить максимум (автоставку), {@code false} — ручная ставка на сумму
 * @param repeat повтор последней своей ручной ставки (тот же запрос: двойное нажатие, повтор после обрыва) — скрыто
 *               помечен {@link LotOutcome#hiddenRepeat}, ссылка на исходную — {@link LotOutcome#repeatOf}
 * @param raise  новая редакция максимума (T4): ведущему — событие {@link EventType#MAX_RAISE} без смены цены (не выше
 *               действующего — отказ); не ведущему — обычный поставленный максимум. Новый максимум — не выше
 *               {@link Bidder#max()} участника (это его потолок)
 */
public record Action(long at, int bidder, long amount, boolean max, boolean repeat, boolean raise) {
    public Action {
        if (raise && (!max || repeat)) throw new IllegalArgumentException("редакция максимума — это максимум, не повтор");
        if (repeat && max) throw new IllegalArgumentException("повтор — ручной ставки");
    }

    public static Action bid(long at, int bidder, long amount) {
        return new Action(at, bidder, amount, false, false, false);
    }

    public static Action maxBid(long at, int bidder, long max) {
        return new Action(at, bidder, max, true, false, false);
    }

    /** Повтор последней ручной ставки участника. */
    public static Action repeat(long at, int bidder) {
        return new Action(at, bidder, 0, false, true, false);
    }

    /** Поднять свой максимум до {@code newMax} (T4). */
    public static Action raiseMax(long at, int bidder, long newMax) {
        return new Action(at, bidder, newMax, true, false, true);
    }
}
