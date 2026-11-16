package io.hammerhall.data.core.check;

import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static io.hammerhall.data.core.schema.ColType.INT;
import static io.hammerhall.data.core.schema.ColType.MOMENT;
import static io.hammerhall.data.core.schema.ColType.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверки ядра: уникальность у всех таблиц (по соседям, по группам, множеством и внешней сортировкой), UNIQUE с NULL,
 * потоковая проверка ссылок, сброс отложенных ссылок на диск, план прогона.
 */
class IntegrityTest {
    @TempDir
    Path tmp;

    static Schema schema() {
        Schema s = new Schema("t", "test-v1").group("g", "Тест");
        s.add(Table.of("parent", "g", "Цель").col("id", INT, "id").pk("id"));
        s.add(Table.of("child", "g", "Ссылки").col("id", INT, "id").col("parent_id", INT, "на цель").ref("parent").pk("id"));
        s.add(Table.of("events", "g", "Порядок не по ключу, ключ — целый")
                .col("id", INT, "id").col("at", MOMENT, "момент").pk("id").orderBy("at", "id"));
        s.add(Table.of("codes", "g", "Порядок не по ключу, ключ — текст")
                .col("code", TEXT, "код").col("at", MOMENT, "момент").pk("code").orderBy("at"));
        s.add(Table.of("grouped", "g", "Порядок — с части ключа")
                .col("a", INT, "a").col("b", INT, "b").col("c", INT, "c").pk("a", "b").orderBy("a", "c"));
        s.add(Table.of("mono", "g", "Порядок — ключ и ещё поле")
                .col("a", INT, "a").col("b", INT, "b").pk("a").orderBy("a", "b"));
        s.add(Table.of("uniq", "g", "UNIQUE")
                .col("id", INT, "id").col("code", TEXT, "код").nullable().col("x", INT, "x").col("y", INT, "y")
                .pk("id").unique("code").unique("x", "y"));
        s.add(Table.of("items", "g", "Составной ключ").col("order_id", INT, "заказ").col("line_no", INT, "строка")
                .pk("order_id", "line_no"));
        s.add(Table.of("returns", "g", "Составная ссылка").col("id", INT, "id").col("order_id", INT, "заказ")
                .col("line_no", INT, "строка").pk("id").fk(List.of("order_id", "line_no"), "items"));
        s.add(Table.of("keys", "g", "Текстовый ключ").col("key", TEXT, "ключ").pk("key"));
        s.add(Table.of("specs", "g", "Ссылка на текст").col("id", INT, "id").col("key", TEXT, "ключ").ref("keys").pk("id"));
        assertEquals(List.of(), s.validate());
        return s;
    }

    final Schema schema = schema();

    Integrity ig(int drainAt, int spillAt, int runSize) {
        return new Integrity(schema, new Integrity.Limits(drainAt, spillAt, runSize, tmp));
    }

    static CheckReport.Result result(Integrity ig, String name) {
        CheckReport r = new CheckReport();
        ig.report(r);
        for (CheckReport.Result x : r.results()) if (x.name().equals(name)) return x;
        throw new AssertionError("нет проверки " + name + " в " + r.render());
    }

    Table t(String name) {
        return schema.table(name);
    }

    void rows(Integrity ig, String table, Object[]... rows) {
        Table t = t(table);
        ig.begin(t);
        for (Object[] r : rows) ig.row(t, r);
        ig.end(t);
    }

    static Object[] r(Object... v) {
        return v;
    }

    long tmpFiles() throws IOException {
        try (Stream<Path> s = Files.list(tmp)) {
            return s.count();
        }
    }

    // ---------------------------------------------------------------- уникальность

    @Test
    void keyUniqueWhenOrderIsNotTheKey() {
        Integrity ig = ig(4, 8, 4);
        // повтор id=2 не соседний: порядок — по моменту
        rows(ig, "events", r(1, 100L), r(2, 110L), r(3, 120L), r(2, 130L), r(4, 140L));
        assertFalse(result(ig, "ключ events").ok(), "повтор целого ключа вне порядка");
        assertTrue(result(ig, "ключ events").detail().contains("повтор ключа (2)"), result(ig, "ключ events").detail());
        Integrity ok = ig(4, 8, 4);
        rows(ok, "events", r(1, 100L), r(2, 110L), r(3, 120L));
        assertTrue(result(ok, "ключ events").ok());
    }

    @Test
    void keyUniqueByExternalSortWithSpill() throws IOException {
        Integrity ig = ig(4, 8, 4);
        List<Object[]> rs = new ArrayList<>();
        for (int i = 0; i < 40; i++) rs.add(r("k" + (i % 37), 1000L + i));  // k0, k1, k2 — по два раза
        rows(ig, "codes", rs.toArray(new Object[0][]));
        CheckReport.Result res = result(ig, "ключ codes");
        assertFalse(res.ok());
        assertTrue(res.detail().startsWith("3 нарушений"), res.detail());
        ig.close();
        assertEquals(0, tmpFiles(), "временные отрезки удалены");

        Integrity ok = ig(4, 8, 4);
        List<Object[]> rs2 = new ArrayList<>();
        for (int i = 0; i < 40; i++) rs2.add(r("k" + i, 1000L + i));
        rows(ok, "codes", rs2.toArray(new Object[0][]));
        assertTrue(result(ok, "ключ codes").ok());
    }

    @Test
    void keyUniqueInsideGroups() {
        Integrity ig = ig(4, 8, 4);
        // порядок (a, c): повтор (1, 1) внутри группы a=1 не соседний; b=1 в группе a=2 — не повтор
        rows(ig, "grouped", r(1, 1, 1), r(1, 2, 2), r(1, 1, 3), r(2, 1, 1), r(2, 2, 2));
        CheckReport.Result res = result(ig, "ключ grouped");
        assertFalse(res.ok());
        assertTrue(res.detail().startsWith("1 нарушений"), res.detail());
        Integrity ok = ig(4, 8, 4);
        rows(ok, "grouped", r(1, 1, 1), r(1, 2, 2), r(2, 1, 1));
        assertTrue(result(ok, "ключ grouped").ok());
    }

    @Test
    void keyUniqueByNeighbours() {
        Integrity ig = ig(4, 8, 4);
        rows(ig, "mono", r(1, 1), r(1, 2), r(2, 1));
        assertFalse(result(ig, "ключ mono").ok());
        Integrity ok = ig(4, 8, 4);
        rows(ok, "mono", r(1, 1), r(2, 1), r(3, 0));
        assertTrue(result(ok, "ключ mono").ok());
    }

    @Test
    void uniqueConstraintsSkipNulls() {
        Integrity ig = ig(4, 8, 4);
        rows(ig, "uniq", r(1, null, 1, 1), r(2, null, 1, 2), r(3, "A", 2, 1), r(4, "B", 2, 2), r(5, "A", 1, 1));
        assertTrue(result(ig, "ключ uniq").ok());
        assertFalse(result(ig, "уникальность uniq(code)").ok(), "A — дважды; два NULL — можно");
        assertTrue(result(ig, "уникальность uniq(code)").detail().startsWith("1 нарушений"));
        assertFalse(result(ig, "уникальность uniq(x, y)").ok(), "(1, 1) — дважды");
    }

    // ---------------------------------------------------------------- ссылки

    @Test
    void referencesToAStreamedTableAreCheckedRightAway() {
        Integrity ig = ig(4, 8, 4);
        Table p = t("parent"), c = t("child");
        ig.begin(p);
        ig.begin(c);
        int cid = 0;
        for (int i = 1; i <= 100; i++) {
            if (i != 50) ig.row(p, r(i));              // id 50 пропущен
            if (i > 1) ig.row(c, r(++cid, i - 1));      // ссылка на уже пройденный id — сразу
        }
        long[] k = ig.counters();
        assertEquals(99, k[0], "все ссылки проверены сразу");
        assertEquals(0, k[1], "ничего не отложено");
        ig.end(p);
        ig.end(c);
        CheckReport.Result res = result(ig, "ссылка child(parent_id) → parent");
        assertFalse(res.ok());
        assertTrue(res.detail().contains("(50)"), res.detail());
    }

    @Test
    void futureReferencesAreDrainedAndSpilled() throws IOException {
        Integrity ig = ig(4, 8, 4);
        Table p = t("parent"), c = t("child");
        ig.begin(p);
        ig.begin(c);
        int cid = 0;
        // ссылки вперёд: на 30 позже текущей строки цели — копятся, дочищаются по мере продвижения, часть — на диск
        for (int i = 1; i <= 200; i++) {
            ig.row(p, r(i));
            ig.row(c, r(++cid, i + 30));
            ig.row(c, r(++cid, i + 180));
        }
        ig.row(c, r(++cid, 999));                       // такой строки не будет
        long[] k = ig.counters();
        assertTrue(k[1] > 0, "отложены");
        assertTrue(k[3] > 0, "часть сброшена на диск");
        assertTrue(k[2] <= 8, "в памяти одновременно — не больше spillAt: " + k[2]);
        ig.end(p);
        ig.end(c);
        CheckReport.Result res = result(ig, "ссылка child(parent_id) → parent");
        assertFalse(res.ok());
        // нарушения — ссылки на 201..230, на 201..380 и на 999: строк цели нет
        assertTrue(res.detail().startsWith("211 нарушений"), res.detail());
        ig.close();
        assertEquals(0, tmpFiles(), "сброшенные ссылки удалены");
    }

    @Test
    void earlyCloseMakesReferencesImmediate() {
        Integrity ig = ig(4, 8, 4);
        rows(ig, "parent", r(1), r(2), r(3));
        Table c = t("child");
        ig.begin(c);
        for (int i = 1; i <= 10; i++) ig.row(c, r(i, 1 + i % 3));
        assertEquals(0, ig.counters()[1], "цель закрыта — ничего не копится");
        ig.end(c);
        assertTrue(result(ig, "ссылка child(parent_id) → parent").ok());
    }

    @Test
    void compositeReferenceByFirstColumnWatermark() {
        Integrity ig = ig(4, 8, 4);
        Table it = t("items"), rt = t("returns");
        ig.begin(it);
        ig.begin(rt);
        int rid = 0;
        for (int o = 1; o <= 50; o++) {
            for (int l = 1; l <= 3; l++) ig.row(it, r(o, l));
            if (o > 1) ig.row(rt, r(++rid, o - 1, 2));  // заказ o-1 уже дописан — сразу
        }
        ig.row(rt, r(++rid, 10, 7));                    // строки 7 нет
        assertEquals(0, ig.counters()[1], "составные ссылки на пройденные заказы — сразу");
        ig.end(it);
        ig.end(rt);
        CheckReport.Result res = result(ig, "ссылка returns(order_id,line_no) → items");
        assertFalse(res.ok());
        assertTrue(res.detail().startsWith("1 нарушений"), res.detail());
    }

    @Test
    void textKeysAreDeferredUntilClose() {
        Integrity ig = ig(4, 8, 4);
        Table k = t("keys"), s = t("specs");
        ig.begin(s);
        ig.row(s, r(1, "diag"));
        ig.row(s, r(2, "nope"));
        ig.begin(k);
        ig.row(k, r("diag"));
        ig.row(k, r("ram"));
        ig.end(k);
        ig.end(s);
        CheckReport.Result res = result(ig, "ссылка specs(key) → keys");
        assertFalse(res.ok());
        assertTrue(res.detail().startsWith("1 нарушений"), res.detail());
    }

    @Test
    void planSkipsTablesOutsideTheRun() {
        Integrity ig = ig(4, 8, 4);
        ig.plan(List.of("child"));
        rows(ig, "child", r(1, 5), r(2, 6));
        assertEquals(0, ig.counters()[1], "цели нет в плане — не копим");
        CheckReport.Result res = result(ig, "ссылка child(parent_id) → parent");
        assertTrue(res.ok());
        assertTrue(res.detail().startsWith("не проверено"));
    }

    @Test
    void rowsOutOfOrderAreAViolation() {
        Integrity ig = ig(4, 8, 4);
        rows(ig, "parent", r(1), r(3), r(2));
        assertFalse(result(ig, "ключ parent").ok(), "потоковая проверка ссылок держится на порядке");
    }

    // ---------------------------------------------------------------- строительные блоки

    @Test
    void longRunsFindsExactDuplicates() throws IOException {
        try (LongRuns runs = new LongRuns(16, tmp)) {
            Set<Long> seen = new HashSet<>();
            long expected = 0;
            java.util.SplittableRandom rnd = new java.util.SplittableRandom(1);
            for (int i = 0; i < 1000; i++) {
                long v = rnd.nextLong(600);
                if (!seen.add(v)) expected++;
                runs.add(v);
            }
            assertTrue(runs.spilledRuns() > 10);
            List<Long> dups = new ArrayList<>();
            assertEquals(expected, runs.duplicates(dups::add));
            assertEquals(expected, dups.size());
        }
        assertEquals(0, tmpFiles());
        try (LongRuns small = new LongRuns(1 << 10, tmp)) {
            small.add(5);
            small.add(-5);
            small.add(5);
            assertEquals(1, small.duplicates(v -> assertEquals(5, v)));
            assertEquals(0, small.spilledRuns());
        }
    }

    @Test
    void longSpillKeepsOrder() throws IOException {
        LongSpill sp = new LongSpill(tmp);
        sp.append(new long[]{3, 1, 2}, 0, 3);
        sp.append(new long[]{9, 8}, 1, 2);
        assertEquals(4, sp.size());
        List<Long> out = new ArrayList<>();
        sp.drain(out::add);
        assertEquals(List.of(3L, 1L, 2L, 8L), out);
        assertEquals(0, tmpFiles());
    }

    @Test
    void keyStoreSortedThenHashed() {
        KeyStore ks = new KeyStore(true);
        for (long i = 0; i < 3_000_000; i += 3) assertTrue(ks.add((i << 32) | 7));
        assertTrue(ks.sortedMode());
        assertFalse(ks.add((2_999_997L << 32) | 7), "повтор последнего");
        assertTrue(ks.contains((300L << 32) | 7));
        assertFalse(ks.contains((301L << 32) | 7));
        assertTrue(ks.add(5), "ключ не по порядку — переход на хеш");
        assertFalse(ks.sortedMode());
        assertTrue(ks.contains(5) && ks.contains((300L << 32) | 7));
        assertFalse(ks.add((300L << 32) | 7));
        assertEquals(1_000_001, ks.size());
    }

    @Test
    void compositeIntegerKeysArePackedExactly() {
        assertEquals((7L << 32) | 3, Integrity.key(r(7, 3), new int[]{0, 1}));
        assertEquals(42, Integrity.key(r(42), new int[]{0}));
        assertTrue(Integrity.key(r(-1, 3), new int[]{0, 1}) != ((-1L << 32) | 3), "вне [0, 2³²) — хеш");
        assertEquals(Integrity.key(r("a", 1), new int[]{0, 1}), Integrity.key(r("a", 1L), new int[]{0, 1}), "Integer и Long — одно");
    }
}
