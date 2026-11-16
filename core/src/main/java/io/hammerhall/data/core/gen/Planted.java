package io.hammerhall.data.core.gen;

import io.hammerhall.data.core.check.LongKeys;

import java.util.Map;
import java.util.TreeMap;

/**
 * Заложенные нарушения (спецификация, §6): код нарушения → ключи сущностей. Проверки инвариантов знают о них и
 * пропускают ровно их; доли печатаются в журнал сборки, в выпуск (SOURCE.md, манифест) числа не попадают.
 */
public final class Planted {
    private final Map<String, LongKeys> keys = new TreeMap<>();

    public synchronized void add(String code, long key) {
        keys.computeIfAbsent(code, k -> new LongKeys()).add(key);
    }

    public synchronized boolean has(String code, long key) {
        LongKeys k = keys.get(code);
        return k != null && k.contains(key);
    }

    public synchronized long count(String code) {
        LongKeys k = keys.get(code);
        return k == null ? 0 : k.size();
    }

    public synchronized Map<String, Long> counts() {
        Map<String, Long> m = new TreeMap<>();
        for (Map.Entry<String, LongKeys> e : keys.entrySet()) m.put(e.getKey(), e.getValue().size());
        return m;
    }
}
