package io.hammerhall.data.auc.world;

/** Вид счёта ({@code invoices.kind}). */
public enum InvoiceKind {
    /** Счёт по итогам торгов. */
    SALE("sale"),
    /** Продажа после торгов. */
    AFTER_SALE("after_sale"),
    /** Второй ставке после отказа победителя. */
    SECOND_CHANCE("second_chance"),
    /** Недостача неплательщику после повторной продажи дешевле. */
    SHORTFALL("shortfall"),
    /** Переизданный счёт (замена ошибочного). */
    REISSUE("reissue");

    private final String code;

    InvoiceKind(String code) {
        this.code = code;
    }

    /** Значение для данных. */
    public String code() {
        return code;
    }

    public static InvoiceKind byCode(String code) {
        for (InvoiceKind v : values()) if (v.code.equals(code)) return v;
        throw new IllegalArgumentException("InvoiceKind: нет кода " + code);
    }
}
