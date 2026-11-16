package io.hammerhall.data.core.gen;

/**
 * Типизированный ключ общего мира: модуль кладёт модель ({@code ctx.put(GEO, geo)}), следующие берут
 * ({@code ctx.get(GEO)}). Без рефлексии: тип зашит в ключ.
 */
public final class Key<T> {
    private final String name;

    private Key(String name) {
        this.name = name;
    }

    public static <T> Key<T> of(String name) {
        return new Key<>(name);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
