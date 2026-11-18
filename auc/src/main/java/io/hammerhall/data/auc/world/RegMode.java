package io.hammerhall.data.auc.world;

/** Заявленный способ участия ({@code registrations.mode}); у онлайн-торгов — только {@code ONLINE}. */
public enum RegMode {
    /** В зале, табличкой. */
    ROOM("room"),
    /** По телефону. */
    PHONE("phone"),
    /** Онлайн. */
    ONLINE("online"),
    /** Заочно. */
    ABSENTEE("absentee");

    private final String code;

    RegMode(String code) {
        this.code = code;
    }

    /** Значение для данных. */
    public String code() {
        return code;
    }

    public static RegMode byCode(String code) {
        for (RegMode v : values()) if (v.code.equals(code)) return v;
        throw new IllegalArgumentException("RegMode: нет кода " + code);
    }
}
