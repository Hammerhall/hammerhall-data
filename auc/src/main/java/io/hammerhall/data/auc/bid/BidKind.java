package io.hammerhall.data.auc.bid;

/** Вид ставки в событии. */
public enum BidKind {
    /** Ручная ставка на сумму. */
    MANUAL("manual", false),
    /**
     * Участник поставил максимум (автоставку): сумма события — цена, которую система сразу поставила за него, поле
     * максимума — сам максимум.
     */
    MAX("max", false),
    /** Автоставка: система подняла ставку за участника в ответ на чужую — до его максимума, минимальным шагом. */
    AUTO("auto", true),
    /** Аукционист исполнил заочную ставку (вступление книги или ответ за заочного участника). */
    ABSENTEE("absentee", true),
    /** Аукционист повёл ставку от имени продавца — только ниже резерва; такой ставкой лот не продаётся. */
    VENDOR("vendor", false);

    private final String code;
    private final boolean auto;

    BidKind(String code, boolean auto) {
        this.code = code;
        this.auto = auto;
    }

    public String code() {
        return code;
    }

    /** Поставлена системой или аукционистом за участника (не его руками в эту минуту). */
    public boolean auto() {
        return auto;
    }
}
