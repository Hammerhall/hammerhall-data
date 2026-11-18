package io.hammerhall.data.auc.house;

import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Дерево категорий: без циклов, до 3 уровней, у ребёнка — отдел родителя, коды уникальны, у каждого отдела есть листья. */
class CategoryTreeTest {
    @Test
    void treeIsSound() {
        List<HouseRules.Category> all = HouseModule.categories();
        assertTrue(all.size() >= 18, "разделов верхнего уровня — 18 и больше узлов: " + all.size());
        Map<Integer, HouseRules.Category> byId = new HashMap<>();
        Set<String> codes = new HashSet<>();
        int top = 0;
        for (HouseRules.Category c : all) {
            assertEquals(byId.size() + 1, c.id(), "id — подряд с 1");
            assertTrue(codes.add(c.code()), "код дважды: " + c.code());
            assertTrue(c.code().matches("[a-z][a-z0-9_.]*"), "код латиницей: " + c.code());
            assertTrue(c.level() >= 1 && c.level() <= 3, c.code());
            if (c.parentId() == 0) {
                assertEquals(1, c.level(), c.code());
                top++;
            } else {
                HouseRules.Category p = byId.get(c.parentId());
                assertTrue(p != null, "родитель раньше ребёнка: " + c.code());
                assertEquals(p.dept(), c.dept(), "отдел ребёнка — отдел родителя: " + c.code());
                assertEquals(p.level() + 1, c.level(), c.code());
                assertTrue(!p.leaf(), "у листа нет детей: " + p.code());
            }
            byId.put(c.id(), c);
        }
        // 18 разделов верхнего уровня; словарь даёт их и сверх того (часы, марки, фотография… — отдельными) — не меньше 18
        assertTrue(top >= 18, "разделов верхнего уровня: " + top);
        for (HouseRules.Category c : all) {
            boolean hasChild = all.stream().anyMatch(x -> x.parentId() == c.id());
            assertEquals(!hasChild, c.leaf(), "лист ⇔ нет детей: " + c.code());
        }
        for (Dept d : Dept.values()) assertTrue(all.stream().anyMatch(c -> c.dept() == d && c.leaf()), d.code());
    }
}
