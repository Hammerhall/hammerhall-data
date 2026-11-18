package io.hammerhall.data.auc.text;

/** Атрибуция вещи — как в каталогах; коды — значения поля {@code items.attribution}. */
public enum Attribution {
    BY("by"), ATTRIBUTED("attributed"), WORKSHOP("workshop"), CIRCLE("circle"), FOLLOWER("follower"), MANNER("manner"),
    AFTER("after"), UNKNOWN("unknown");

    public final String code;

    Attribution(String code) {
        this.code = code;
    }
}
