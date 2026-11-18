package io.hammerhall.data.auc.bid;

/** Вид события торгов лота. */
public enum EventType {
    /** Ставка (принятая или отклонённая). */
    BID("bid"),
    /** Продление онлайн-лота поздней ставкой: новое время закрытия — в {@code closeAt}. */
    EXTEND("extend"),
    /** Закрытие онлайн-лота по часам. */
    CLOSE("close"),
    /** Живые торги: последнее предупреждение аукциониста перед молотком. */
    FAIR_WARNING("fair_warning"),
    /** Живые торги: удар молотка — продан или снят. */
    HAMMER("hammer"),
    /**
     * Ведущий поднял свой максимум (новая редакция поручения, T4): цена не меняется, это не ставка — в журнал ставок не
     * идёт; сумма — текущая цена, {@code max} — новый максимум. Не выше действующего — отказ
     * {@link RejectReason#BELOW_MINIMUM} (снизить максимум нельзя), максимум прежний.
     */
    MAX_RAISE("max_raise");

    private final String code;

    EventType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
