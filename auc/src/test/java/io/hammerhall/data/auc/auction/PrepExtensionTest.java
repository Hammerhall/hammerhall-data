package io.hammerhall.data.auc.auction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Стабильность при продлении для таблиц подготовки торгов: {@code small} с {@code --to 2027-03-31} против полного —
 * каждая строка короткого есть в длинном по ключу, поля те же, кроме полей «на „сейчас“»; пустое поле может
 * только заполниться. {@code id} короткого — начало длинного.
 */
class PrepExtensionTest {
    /** Поля, которые вправе смениться (состояние на срез). */
    static final Map<String, Set<String>> STATE = Map.of(
            "sales", Set.of("status", "ends_at"),
            "sale_sessions", Set.of("ended_at"),
            "consignments", Set.of(),
            "consignment_items", Set.of("ended_at", "outcome"),
            "registrations", Set.of("status", "decided_at", "decided_by_id", "reject_reason", "premium_approved_at"),
            "bid_orders", Set.of("status", "ended_at"));
    static final Map<String, List<String>> KEY = Map.of(
            "sales", List.of("id"), "sale_sessions", List.of("sale_id", "session_no"), "consignments", List.of("id"),
            "consignment_items", List.of("consignment_id", "line_no"), "registrations", List.of("id"), "bid_orders", List.of("id"));

    @Test
    void shortRunIsPrefixOfLong() throws IOException {
        PrepRuns.Run shorter = PrepRuns.small("2027-03-31");
        PrepRuns.Run longer = PrepRuns.small(null);
        assertTrue(shorter.result().ok(), "самопроверка короткого:\n" + shorter.result().report().render());
        for (String table : STATE.keySet().stream().sorted().toList()) {
            PrepRuns.Table s = PrepRuns.read(shorter.dir(), table), l = PrepRuns.read(longer.dir(), table);
            assertEquals(s.col(), l.col(), table + ": поля");
            Map<String, String[]> byKey = new HashMap<>();
            for (String[] row : l.rows()) byKey.put(key(l, table, row), row);
            int changed = 0;
            for (String[] row : s.rows()) {
                String k = key(s, table, row);
                String[] lr = byKey.get(k);
                assertNotNull(lr, table + " " + k + ": строки короткого нет в длинном");
                boolean diff = false;
                for (Map.Entry<String, Integer> c : s.col().entrySet()) {
                    String a = row[c.getValue()], b = lr[c.getValue()];
                    if (a.equals(b)) continue;
                    boolean state = STATE.get(table).contains(c.getKey());
                    assertTrue(state || a.isEmpty(), table + " " + k + "." + c.getKey() + ": «" + a + "» → «" + b + "»");
                    diff = true;
                }
                if (diff) changed++;
            }
            assertTrue(s.rows().size() < l.rows().size(), table + ": длинный длиннее");
            System.out.println("продление: " + table + " — строк " + s.rows().size() + " из " + l.rows().size()
                    + ", в пути (сменили состояние) " + changed);
        }
    }

    static String key(PrepRuns.Table t, String table, String[] row) {
        StringBuilder sb = new StringBuilder();
        for (String c : KEY.get(table)) sb.append(t.get(row, c)).append('|');
        return sb.toString();
    }
}
