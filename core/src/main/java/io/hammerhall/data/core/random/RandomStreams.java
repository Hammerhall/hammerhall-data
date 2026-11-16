package io.hammerhall.data.core.random;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Потоки случайности. Случайность сущности — функция {@code (seed, имя потока, ключ сущности[, дата])}, а не порядка
 * вызовов: генерация в другом порядке, в другом числе потоков или на более длинный период даёт для той же сущности
 * то же самое. Так держатся детерминизм ({@code ADR-024}, п. 4) и стабильность при продлении (п. 13).
 *
 * <pre>{@code
 * RandomStreams.Stream orders = streams.stream("orders.daily");   // один раз
 * Rng r = orders.at(customerId, day);                             // на сущность и сутки
 * }</pre>
 *
 * Имя потока — «таблица или модуль . смысл»; два модуля не должны делить имя потока.
 */
public final class RandomStreams {
    private final long seed;

    public RandomStreams(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    public Stream stream(String name) {
        return new Stream(name, Rng.mix64(Rng.mix64(seed) ^ fnv64(name)));
    }

    /** FNV-1a, 64 бита, по байтам UTF-8 — стабильный хеш строки (не {@code String.hashCode}). */
    public static long fnv64(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Именованный поток; ключи сущностей смешиваются с его базой. */
    public static final class Stream {
        private final String name;
        private final long base;

        Stream(String name, long base) {
            this.name = name;
            this.base = base;
        }

        public String name() {
            return name;
        }

        private static long step(long h, long k) {
            return Rng.mix64(h + 0x9e3779b97f4a7c15L + Rng.mix64(k ^ 0x5851f42d4c957f2dL));
        }

        public Rng at(long key) {
            return new Rng(step(base, key));
        }

        public Rng at(long k1, long k2) {
            return new Rng(step(step(base, k1), k2));
        }

        public Rng at(long k1, long k2, long k3) {
            return new Rng(step(step(step(base, k1), k2), k3));
        }

        public Rng at(long key, LocalDate day) {
            return at(key, day.toEpochDay());
        }

        public Rng at(String key) {
            return at(fnv64(key));
        }

        public Rng at(String key, long k2) {
            return at(fnv64(key), k2);
        }

        /** Сырое 64-битное значение под ключ (для хеш-отбора без создания генератора). */
        public long hash(long key) {
            return step(base, key);
        }

        /** Доля в [0, 1) под ключ — для решений «да/нет» по сущности. */
        public double unit(long key) {
            return (hash(key) >>> 11) * 0x1.0p-53;
        }
    }
}
