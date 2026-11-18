package io.hammerhall.data.auc.world;

/** Итог лота на «сейчас» ({@code lots.result}). */
public enum LotResultCode {
    /** Не закрыт (или торги ещё впереди). */
    OPEN("open"),
    /** Продан с молотка. */
    SOLD("sold"),
    /** Не продан. */
    BOUGHT_IN("bought_in"),
    /** Снят до торгов. */
    WITHDRAWN("withdrawn"),
    /** Продан после торгов по предложению не ниже резерва. */
    SOLD_AFTER_SALE("sold_after_sale");

    private final String code;

    LotResultCode(String code) {
        this.code = code;
    }

    /** Значение для данных. */
    public String code() {
        return code;
    }

    public static LotResultCode byCode(String code) {
        for (LotResultCode v : values()) if (v.code.equals(code)) return v;
        throw new IllegalArgumentException("LotResultCode: нет кода " + code);
    }
}
