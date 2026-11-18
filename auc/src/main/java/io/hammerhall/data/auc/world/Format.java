package io.hammerhall.data.auc.world;

/** Формат торгов ({@code sales.format}). */
public enum Format {
    /** Живые торги в зале: сессии, аукционист, четыре канала ставок. */
    LIVE("live"),
    /** Онлайн-торги по времени: волна закрытий, мягкое закрытие. */
    TIMED("timed");

    private final String code;

    Format(String code) {
        this.code = code;
    }

    /** Значение для данных. */
    public String code() {
        return code;
    }

    public static Format byCode(String code) {
        for (Format v : values()) if (v.code.equals(code)) return v;
        throw new IllegalArgumentException("Format: нет кода " + code);
    }
}
