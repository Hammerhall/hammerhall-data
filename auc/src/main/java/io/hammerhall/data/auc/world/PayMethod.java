package io.hammerhall.data.auc.world;

/** Способ платежа ({@code payments.method}). */
public enum PayMethod {
    /** Карта. */
    CARD("card"),
    /** СБП. */
    SBP("sbp"),
    /** Банковский перевод. */
    BANK_TRANSFER("bank_transfer"),
    /** Наличные в офисе. */
    CASH("cash"),
    /** Депозит в зачёт. */
    DEPOSIT("deposit");

    private final String code;

    PayMethod(String code) {
        this.code = code;
    }

    /** Значение для данных. */
    public String code() {
        return code;
    }

    public static PayMethod byCode(String code) {
        for (PayMethod v : values()) if (v.code.equals(code)) return v;
        throw new IllegalArgumentException("PayMethod: нет кода " + code);
    }
}
