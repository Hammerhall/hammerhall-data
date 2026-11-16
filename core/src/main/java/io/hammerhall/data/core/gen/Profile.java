package io.hammerhall.data.core.gen;

import java.util.Locale;

/**
 * Профиль — прогон генератора в масштабе ({@code ADR-024}, п. 7), а не вырезка. Справочники (каталог, склады,
 * календарь) одинаковы во всех профилях; клиентов, заказов и сессий в сутки — {@link #scale()} от полного.
 */
public enum Profile {
    SMALL(0.01), MEDIUM(0.10), FULL(1.0);

    private final double scale;

    Profile(double scale) {
        this.scale = scale;
    }

    /** Доля «живого» потока (клиенты, заказы, сессии в сутки) от {@code full}. */
    public double scale() {
        return scale;
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Profile parse(String s) {
        for (Profile p : values()) if (p.code().equals(s)) return p;
        throw new IllegalArgumentException("профиль: small | medium | full, а не " + s);
    }
}
