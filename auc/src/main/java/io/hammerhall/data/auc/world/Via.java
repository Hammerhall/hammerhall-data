package io.hammerhall.data.auc.world;

/** Откуда пришла регистрация или поручение ({@code registrations.via}, {@code bid_orders.via}). */
public enum Via {
    /** Сайт дома. */
    SITE("site"),
    /** Приложение дома. */
    APP("app"),
    /** По телефону. */
    PHONE("phone"),
    /** В офисе. */
    OFFICE("office"),
    /** Партнёрская площадка (только живые торги; покупатель платит сбор). */
    PARTNER_PLATFORM("partner_platform");

    private final String code;

    Via(String code) {
        this.code = code;
    }

    /** Значение для данных. */
    public String code() {
        return code;
    }

    public static Via byCode(String code) {
        for (Via v : values()) if (v.code.equals(code)) return v;
        throw new IllegalArgumentException("Via: нет кода " + code);
    }
}
