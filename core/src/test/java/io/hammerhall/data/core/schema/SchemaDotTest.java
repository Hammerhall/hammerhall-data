package io.hammerhall.data.core.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.hammerhall.data.core.schema.ColType.INT;
import static io.hammerhall.data.core.schema.ColType.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Картинки схемы в DOT: одна или карта с группами, справочники, смысловые связи, предел таблиц, отметка SVG. */
class SchemaDotTest {

    /** Справочник городов, магазин: заказы → клиенты → города; номер акции у заказа — смысловая связь. */
    static Schema shop(int extraOrders) {
        Schema s = new Schema("t", "t-data-v1").group("geo", "Города: справочник", true).group("shop", "Магазин");
        s.add(Table.of("cities", "geo", "Города").title("Города").col("id", INT, "Город").pk("id"));
        s.add(Table.of("customers", "shop", "Клиенты").title("Клиенты").col("id", INT, "Клиент")
                .col("city_id", INT, "Город").ref("cities").pk("id"));
        s.add(Table.of("promos", "shop", "Акции").title("Акции").col("code", TEXT, "Код").pk("code"));
        s.add(Table.of("orders", "shop", "Заказы").title("Заказы").col("id", INT, "Заказ")
                .col("customer_id", INT, "Клиент").ref("customers")
                .col("promo_code", TEXT, "Промокод").nullable()
                .col("city_id", INT, "Город доставки").nullable().ref("cities")
                .pk("id").link(List.of("promo_code"), "promos"));
        for (int i = 0; i < extraOrders; i++) {
            s.add(Table.of("lines" + i, "shop", "Строки").title("Строки").col("order_id", INT, "Заказ").ref("orders")
                    .col("no", INT, "Строка").pk("order_id", "no"));
        }
        return s;
    }

    @Test
    void smallSchemaIsOnePictureWithFields() {
        Map<String, String> f = SchemaDot.files(shop(0));
        assertEquals(List.of("schema.dot"), List.copyOf(f.keySet()));
        String dot = f.get("schema.dot");
        assertTrue(dot.contains("<B>orders</B><BR/>Заказы"), "шапка: имя и название");
        assertTrue(dot.contains("label=<<B>Города</B>>"), "рамка — название группы до двоеточия");
        assertTrue(dot.contains("># </FONT>id<") && dot.contains(">∗ </FONT>customer_id<")
                && dot.contains(">○ </FONT>promo_code<"), "значки полей");
        assertTrue(dot.contains("\"customers\":\"id\" -> \"orders\":\"customer_id\";"), "обязательная связь — сплошная, от поля к полю");
        assertTrue(dot.contains("\"promos\":\"code\" -> \"orders\":\"promo_code\" [style=dashed];"), "смысловая — пунктир");
        assertTrue(dot.contains("\"cities\":\"id\" -> \"orders\":\"city_id\" [style=dashed];"), "необязательная — пунктир");
        assertEquals(dot, SchemaDot.files(shop(0)).get("schema.dot"), "тот же вход — тот же файл");
    }

    @Test
    void largeSchemaIsMapAndGroups() {
        Schema s = shop(SchemaDot.MAX_TABLES - 3);
        Map<String, String> f = SchemaDot.files(s);
        assertEquals(List.of("schema.dot", "schema-geo.dot", "schema-shop.dot"), List.copyOf(f.keySet()));
        assertEquals(List.of("schema.svg", "schema-geo.svg", "schema-shop.svg"), SchemaDot.pictures(s));
        String map = f.get("schema.dot");
        assertFalse(map.contains("customer_id"), "на карте нет полей");
        assertFalse(map.contains("\"cities\" ->"), "на карте нет связей в справочник");
        assertTrue(map.contains("\"customers\" -> \"orders\";"), "одна стрелка на пару");
        assertTrue(map.contains("справочник"), "справочник подписан");
        String geo = f.get("schema-geo.dot");
        assertFalse(geo.contains("customers"), "кто ссылается на группу — не на её картинке");
        String shop = f.get("schema-shop.dot");
        assertTrue(shop.contains("\"cities\" -> \"customers\":\"city_id\";"), "связь из группы — к шапке чужой таблицы");
        assertTrue(shop.contains("<FONT COLOR=\"#666666\">cities<BR/>Города</FONT>"), "чужая таблица — серым, только шапка");
        assertTrue(shop.contains("\"orders\":\"id\" -> \"lines0\":\"order_id\";"), "связи внутри группы — от поля к полю");
        for (Map.Entry<String, String> e : f.entrySet()) {
            if (e.getKey().equals("schema.dot")) continue;
            int n = e.getValue().split(" -> ", -1).length - 1;
            assertEquals(e.getKey().equals("schema-shop.dot") ? 3 + 1 + (SchemaDot.MAX_TABLES - 3) : 0, n,
                    e.getKey() + ": каждая связь — ровно на одной картинке группы");
        }
    }

    @Test
    void groupOverLimitFails() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SchemaDot.files(shop(SchemaDot.MAX_TABLES)));
        assertTrue(e.getMessage().contains("«Магазин»") && e.getMessage().contains("предел — 12"), e.getMessage());
    }

    @Test
    void titleAndLinkAreChecked() {
        Schema s = new Schema("t", "t-data-v1").group("g", "Группа");
        s.add(Table.of("a", "g", "А").col("id", INT, "id").pk("id"));
        s.add(Table.of("b", "g", "Б").title("Б").col("id", INT, "id").col("a_code", TEXT, "код").pk("id")
                .link(List.of("a_code"), "a"));
        List<String> errors = s.validate();
        assertTrue(errors.stream().anyMatch(x -> x.startsWith("a: нет короткого названия")), errors.toString());
        assertTrue(errors.stream().anyMatch(x -> x.contains("тип a_code TEXT ≠ INT")), errors.toString());
    }

    @Test
    void svgMarker() {
        String dot = "digraph schema {\n}\n";
        String svg = "<?xml version=\"1.0\"?>\n" + SchemaDot.svgMarker(dot) + "\n<svg></svg>\n";
        assertEquals(SchemaDot.sha256(dot), SchemaDot.markedSha256(svg));
        assertNull(SchemaDot.markedSha256("<?xml version=\"1.0\"?>\n<svg><!-- schema-dot sha256: x --></svg>"),
                "отметка — только до <svg");
        assertEquals("schema-shop.svg", SchemaDot.svgName("schema-shop.dot"));
    }
}
