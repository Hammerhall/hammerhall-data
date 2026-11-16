package io.hammerhall.data.core.gen;

import java.util.Map;
import java.util.TreeMap;

/**
 * Выдача идентификаторов: последовательность на таблицу, с 1. Стабильность при продлении держится порядком выдачи:
 * id выдаётся в порядке появления сущности во времени (сутки за сутками; внутри суток — по стабильному ключу), тогда
 * продление периода дописывает новые id в хвост, не трогая прежние. Справочники с планом на будущее (товары,
 * пункты выдачи) сортируют план по дате появления и нумеруют — тот же эффект.
 */
public final class Ids {
    public static final class Sequence {
        private long next;

        Sequence(long first) {
            this.next = first;
        }

        public long next() {
            return next++;
        }

        public int nextInt() {
            return Math.toIntExact(next++);
        }

        /** Сколько выдано. */
        public long issued(long first) {
            return next - first;
        }

        public long peek() {
            return next;
        }
    }

    private final Map<String, Sequence> seqs = new TreeMap<>();

    public synchronized Sequence of(String table) {
        return seqs.computeIfAbsent(table, t -> new Sequence(1));
    }
}
