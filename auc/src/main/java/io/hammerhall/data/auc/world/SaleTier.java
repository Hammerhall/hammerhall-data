package io.hammerhall.data.auc.world;

/** Вид торгов ({@code sales.tier}). */
public enum SaleTier {
    /** Обычные торги отдела. */
    REGULAR("regular"),
    /** Тематические онлайн-торги (часы, серебро, карты, марки…). */
    THEMATIC("thematic"),
    /** Торги главной недели сезона. */
    MAJOR("major"),
    /** Вечерние торги живописи главной недели. */
    EVENING("evening");

    private final String code;

    SaleTier(String code) {
        this.code = code;
    }

    /** Значение для данных. */
    public String code() {
        return code;
    }

    public static SaleTier byCode(String code) {
        for (SaleTier v : values()) if (v.code.equals(code)) return v;
        throw new IllegalArgumentException("SaleTier: нет кода " + code);
    }
}
