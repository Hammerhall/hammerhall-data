package io.hammerhall.data.sto;

import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.SchemaExport;
import io.hammerhall.data.core.schema.Table;
import io.hammerhall.data.sto.catalog.CatalogModule;
import io.hammerhall.data.sto.catalog.Names;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сборка выпуска целиком: самопроверка, детерминизм (два прогона — одинаковые манифесты), стабильность при продлении
 * (прогон на короткий период — префикс длинного), схема и DDL. Первый прогон — общий small-выпуск
 * ({@link DatasetContract#small}); паспорт, схема в git и внутренние имена — в общих тестах ({@link StoContractTest}).
 */
class StoReleaseTest {
    static final String SHORT_TO = "2027-03-31";

    @TempDir
    static Path tmp;
    static Path a, b, shortRun;
    static Release.Result ra, rb, rs;

    static final StoDataset STO = new StoDataset();

    @BeforeAll
    static void build() throws IOException {
        DatasetContract.Small small = DatasetContract.small(STO);
        a = small.dir();
        ra = small.result();
        b = tmp.resolve("b");
        shortRun = tmp.resolve("short");
        rb = Cli.run(STO, Map.of("profile", "small", "out", b.toString()));
        rs = Cli.run(STO, Map.of("profile", "small", "out", shortRun.toString(), "to", SHORT_TO));
    }

    @Test
    void selfCheckPasses() {
        assertTrue(ra.ok(), ra.report().render());
        assertTrue(rs.ok(), rs.report().render());
    }

    @Test
    void twoRunsAreByteIdentical() throws IOException {
        assertArrayEquals(Files.readAllBytes(a.resolve("manifest.json")), Files.readAllBytes(b.resolve("manifest.json")));
        for (String f : List.of("SOURCE.md", "schema-tables.sql", "schema-constraints.sql", "schema.mmd", "products.tsv.gz",
                "prices.tsv.gz")) {
            assertArrayEquals(Files.readAllBytes(a.resolve(f)), Files.readAllBytes(b.resolve(f)), f);
        }
    }

    @Test
    void otherSeedDiffers() throws IOException {
        Path c = tmp.resolve("c");
        Cli.run(STO, Map.of("profile", "small", "seed", "7", "out", c.toString(), "modules", "catalog"));
        assertFalse(java.util.Arrays.equals(Files.readAllBytes(a.resolve("brands.tsv.gz")),
                Files.readAllBytes(c.resolve("brands.tsv.gz"))));
    }

    // ---------------------------------------------------------------- продление

    static List<String[]> rows(Path dir, String table) throws IOException {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(dir.resolve(table + ".tsv.gz"))), StandardCharsets.UTF_8))) {
            r.readLine();
            String l;
            while ((l = r.readLine()) != null) out.add(l.split("\t", -1));
        }
        return out;
    }

    static Map<String, String[]> byKey(List<String[]> rows, int... cols) {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (String[] r : rows) {
            StringBuilder k = new StringBuilder();
            for (int c : cols) k.append(r[c]).append('|');
            m.put(k.toString(), r);
        }
        return m;
    }

    /** Поля, кроме «конца» (он вправе появиться при продлении: снятие или закрытие после прежнего среза). */
    static void sameExcept(String[] s, String[] l, int endCol, String what) {
        for (int i = 0; i < s.length; i++) {
            if (i == endCol) {
                if (!s[i].isEmpty()) assertEquals(s[i], l[i], what + ": конец");
            } else {
                assertEquals(s[i], l[i], what + ": поле " + i);
            }
        }
    }

    @Test
    void extendingThePeriodKeepsHistory() throws IOException {
        long shortEnd = LocalDate.parse(SHORT_TO).plusDays(1).toEpochDay() * 86400L;
        // товары: все короткие — те же в длинном (id, артикул, EAN, названия, даты), снятие — только допишется
        Map<String, String[]> lp = byKey(rows(a, "products"), 0);
        List<String[]> sp = rows(shortRun, "products");
        assertTrue(sp.size() > 40_000 && sp.size() < lp.size(), "коротких " + sp.size() + ", длинных " + lp.size());
        for (String[] s : sp) sameExcept(s, lp.get(s[0] + "|"), 10, "товар " + s[0]);
        // характеристики и состав комплектов коротких товаров — те же
        Map<String, String[]> ls = byKey(rows(a, "product_specs"), 0, 1);
        for (String[] s : rows(shortRun, "product_specs")) assertArrayEquals(s, ls.get(s[0] + "|" + s[1] + "|"), "spec " + s[0]);
        Map<String, String[]> lb = byKey(rows(a, "bundle_items"), 0, 1);
        for (String[] s : rows(shortRun, "bundle_items")) assertArrayEquals(s, lb.get(s[0] + "|" + s[1] + "|"));
        // цены до прежнего среза — те же; у последнего короткого периода конец допишется
        Map<String, String[]> lpr = byKey(rows(a, "prices"), 0, 1);
        for (String[] s : rows(shortRun, "prices")) {
            String[] l = lpr.get(s[0] + "|" + s[1] + "|");
            assertTrue(l != null, "цена " + s[0] + " с " + s[1]);
            sameExcept(s, l, 2, "цена " + s[0]);
            if (!s[2].isEmpty()) assertTrue(Long.parseLong(s[2]) <= shortEnd);
        }
        // акции: те же id и поля, конец обрезанных срезом — допишется
        Map<String, String[]> la = byKey(rows(a, "promotions"), 0);
        for (String[] s : rows(shortRun, "promotions")) {
            String[] l = la.get(s[0] + "|");
            for (int i = 0; i < s.length; i++) {
                if (i == 12 && Long.parseLong(s[i]) == shortEnd) continue;
                assertEquals(s[i], l[i], "акция " + s[0] + ", поле " + i);
            }
        }
        // склады и пункты выдачи: те же id и коды
        Map<String, String[]> lw = byKey(rows(a, "warehouses"), 0);
        for (String[] s : rows(shortRun, "warehouses")) sameExcept(s, lw.get(s[0] + "|"), 10, "склад " + s[3]);
    }

    /** Склад и поставки: журнал движений короткого прогона — начало длинного строка в строку; заказы и партии — те же. */
    @Test
    void stockExtensionKeepsHistory() throws IOException {
        long n = 0;
        try (BufferedReader s = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                Files.newInputStream(shortRun.resolve("stock_movements.tsv.gz"))), StandardCharsets.UTF_8));
             BufferedReader l = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                     Files.newInputStream(a.resolve("stock_movements.tsv.gz"))), StandardCharsets.UTF_8))) {
            String x;
            while ((x = s.readLine()) != null) {
                assertEquals(x, l.readLine(), "движение, строка " + n);
                n++;
            }
            assertTrue(l.readLine() != null, "длинный журнал длиннее");
        }
        assertTrue(n > 100_000, "движений до среза " + n);
        // заказы поставщикам: поставщик, склад, даты размещения и ожидания, назначение — те же; приёмка и статус у
        // заказов, открытых на срезе, допишутся
        Map<String, String[]> lp = byKey(rows(a, "purchase_orders"), 0);
        List<String[]> sp = rows(shortRun, "purchase_orders");
        assertTrue(sp.size() > 1000 && sp.size() < lp.size(), "заказов " + sp.size() + " из " + lp.size());
        for (String[] s : sp) {
            String[] l = lp.get(s[0] + "|");
            assertTrue(l != null, "заказ " + s[0]);
            for (int c : new int[]{1, 2, 3, 4, 7}) assertEquals(s[c], l[c], "заказ " + s[0] + ", поле " + c);
            if (!s[5].isEmpty() || s[6].equals("cancelled")) {
                assertEquals(s[5], l[5], "заказ " + s[0] + ": приёмка");
                assertEquals(s[6], l[6], "заказ " + s[0] + ": статус");
            }
        }
        // партии: всё то же; у партий в пути на срезе допишутся приёмка и принятое количество
        Map<String, String[]> lb = byKey(rows(a, "batches"), 0);
        for (String[] s : rows(shortRun, "batches")) {
            String[] l = lb.get(s[0] + "|");
            assertTrue(l != null, "партия " + s[0]);
            for (int c : new int[]{1, 2, 3, 4, 6, 8}) assertEquals(s[c], l[c], "партия " + s[0] + ", поле " + c);
            if (!s[7].isEmpty()) {
                assertEquals(s[5], l[5], "партия " + s[0] + ": штук");
                assertEquals(s[7], l[7], "партия " + s[0] + ": приёмка");
            }
        }
    }

    /** Поле короткого прогона пусто или равно полю длинного (момент, дописанный после среза). */
    static void sameOrLater(String[] s, String[] l, int col, String what) {
        if (!s[col].isEmpty()) assertEquals(s[col], l[col], what + ": поле " + col);
    }

    /**
     * Заказы, сайт, платежи, отгрузки, возвраты, отзывы: всё, что было фактом до среза, в длинном прогоне то же —
     * строки заказов и журнал совпадают, платежи — начало длинных строка в строку, у прочих моменты после среза
     * дописываются.
     */
    @Test
    void ordersExtensionKeepsHistory() throws IOException {
        long shortEnd = LocalDate.parse(SHORT_TO).plusDays(1).toEpochDay() * 86400L;
        Map<String, String[]> lo = byKey(rows(a, "orders"), 0);
        List<String[]> so = rows(shortRun, "orders");
        assertTrue(so.size() > 10_000 && so.size() < lo.size(), "заказов " + so.size() + " из " + lo.size());
        for (String[] s : so) {
            String[] l = lo.get(s[0] + "|");
            assertTrue(l != null, "заказ " + s[0]);
            for (int c : new int[]{1, 2, 3, 4, 5, 6, 7, 15, 16, 17, 18, 19, 20}) assertEquals(s[c], l[c], "заказ " + s[0] + ", поле " + c);
            // у дошедших до конца — моменты те же; у заказов в пути — момент статуса может стать позже (откат и повтор)
            boolean done = s[14].equals("delivered") || s[14].equals("cancelled") || s[14].equals("returned");
            boolean planted = rs.context().planted().has("invalid_status_transition", Long.parseLong(s[0]));
            for (int c = 8; c <= 12 && !planted; c++) {
                if (done) assertEquals(s[c], l[c], "заказ " + s[0] + ", поле " + c);
                else if (!s[c].isEmpty()) assertTrue(!l[c].isEmpty() && Long.parseLong(l[c]) >= Long.parseLong(s[c]), "заказ " + s[0]);
            }
            if (Long.parseLong(s[13]) < shortEnd - 1) assertEquals(s[13], l[13], "заказ " + s[0] + ": обещано");
            if (s[14].equals("cancelled") || s[14].equals("returned")) assertEquals(s[14], l[14], "заказ " + s[0] + ": статус");
        }
        Map<String, String[]> li = byKey(rows(a, "order_items"), 0, 1);
        for (String[] s : rows(shortRun, "order_items")) assertArrayEquals(s, li.get(s[0] + "|" + s[1] + "|"), "строка " + s[0]);
        Map<String, String[]> ll = byKey(rows(a, "order_status_log"), 0, 1);
        for (String[] s : rows(shortRun, "order_status_log")) assertArrayEquals(s, ll.get(s[0] + "|" + s[1] + "|"), "статус " + s[0]);
        List<String[]> sp = rows(shortRun, "payments"), lp = rows(a, "payments");
        assertTrue(sp.size() < lp.size());
        for (int i = 0; i < sp.size(); i++) assertArrayEquals(sp.get(i), lp.get(i), "платёж " + i);
        Map<String, String[]> lsh = byKey(rows(a, "shipments"), 0);
        for (String[] s : rows(shortRun, "shipments")) {
            String[] l = lsh.get(s[0] + "|");
            assertTrue(l != null, "отгрузка " + s[0]);
            for (int c : new int[]{1, 2, 3, 4, 5, 9, 10}) assertEquals(s[c], l[c], "отгрузка " + s[0] + ", поле " + c);
            sameOrLater(s, l, 6, "отгрузка " + s[0]);
            sameOrLater(s, l, 7, "отгрузка " + s[0]);
        }
        Map<String, String[]> lr = byKey(rows(a, "returns"), 0);
        for (String[] s : rows(shortRun, "returns")) {
            String[] l = lr.get(s[0] + "|");
            assertTrue(l != null, "возврат " + s[0]);
            for (int c : new int[]{1, 2, 3, 4, 6}) assertEquals(s[c], l[c], "возврат " + s[0] + ", поле " + c);
            for (int c : new int[]{5, 7, 8, 9}) sameOrLater(s, l, c, "возврат " + s[0]);
        }
        Map<String, String[]> lv = byKey(rows(a, "reviews"), 0);
        for (String[] s : rows(shortRun, "reviews")) {
            String[] l = lv.get(s[0] + "|");
            assertTrue(l != null, "отзыв " + s[0]);
            for (int c : new int[]{1, 2, 3, 4, 5, 7}) assertEquals(s[c], l[c], "отзыв " + s[0] + ", поле " + c);
            sameOrLater(s, l, 6, "отзыв " + s[0]);
        }
        Map<String, String[]> ls = byKey(rows(a, "sessions"), 0);
        for (String[] s : rows(shortRun, "sessions")) {
            String[] l = ls.get(s[0] + "|");
            assertTrue(l != null, "сессия " + s[0]);
            for (int c : new int[]{1, 2, 4, 5, 6}) assertEquals(s[c], l[c], "сессия " + s[0] + ", поле " + c);
            if (Long.parseLong(s[3]) < shortEnd - 1) assertEquals(s[3], l[3], "сессия " + s[0] + ": конец");
        }
        Map<String, String[]> le = byKey(rows(a, "session_events"), 0, 1);
        for (String[] s : rows(shortRun, "session_events")) assertArrayEquals(s, le.get(s[0] + "|" + s[1] + "|"), "событие " + s[0]);
    }

    /** Суммы заказа по строкам TSV и ссылки журнала движений на настоящие заказы и возвраты. */
    @Test
    void ordersAddUpInTsv() throws IOException {
        Map<String, long[]> sum = new LinkedHashMap<>();
        for (String[] r : rows(a, "order_items")) {
            long[] x = sum.computeIfAbsent(r[0], k -> new long[3]);
            x[0] += Long.parseLong(r[6].replace(".", "")) * Long.parseLong(r[5]);
            x[1] += Long.parseLong(r[7].replace(".", ""));
            x[2] += Long.parseLong(r[9].replace(".", ""));
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        long vatOk = 0;
        for (String[] o : rows(a, "orders")) {
            ids.add(o[0]);
            long[] x = sum.get(o[0]);
            assertTrue(x != null, "заказ без строк " + o[0]);
            long items = Long.parseLong(o[16].replace(".", "")), disc = Long.parseLong(o[17].replace(".", "")),
                    del = Long.parseLong(o[18].replace(".", "")), vat = Long.parseLong(o[19].replace(".", "")),
                    total = Long.parseLong(o[20].replace(".", ""));
            assertEquals(x[0], items, "заказ " + o[0] + ": сумма строк");
            assertEquals(x[1], disc, "заказ " + o[0] + ": скидки");
            assertEquals(items - disc + del, total, "заказ " + o[0] + ": итого");
            assertTrue(vat >= x[2], "заказ " + o[0] + ": НДС");
            vatOk++;
        }
        assertTrue(vatOk > 10_000);
        java.util.Set<String> rets = new java.util.HashSet<>();
        for (String[] r : rows(a, "returns")) rets.add(r[0]);
        long orderRefs = 0, returnRefs = 0;
        for (String[] m : rows(a, "stock_movements")) {
            if (m[8].equals("order")) {
                assertTrue(ids.contains(m[9]), "движение " + m[0] + ": нет заказа " + m[9]);
                orderRefs++;
            } else if (m[8].equals("return")) {
                assertTrue(rets.contains(m[9]), "движение " + m[0] + ": нет возврата " + m[9]);
                returnRefs++;
            }
        }
        assertTrue(orderRefs > 10_000 && returnRefs > 100, "ссылок на заказы " + orderRefs + ", на возвраты " + returnRefs);
    }

    // ---------------------------------------------------------------- схема

    @Test
    void schemaIsComplete() {
        Schema s = StoSchema.build();
        assertEquals(List.of(), s.validate());
        assertEquals(31, s.tables().size());
        String ddl = SchemaExport.tablesSql(s), cons = SchemaExport.constraintsSql(s), er = SchemaExport.mermaid(s);
        for (Table t : s.tables()) {
            assertTrue(ddl.contains("CREATE TABLE " + t.name() + " ("), t.name());
            assertTrue(cons.contains("ALTER TABLE " + t.name() + " ADD CONSTRAINT " + t.name() + "_pkey"), t.name());
            assertTrue(er.contains("    " + t.name() + " {"), t.name());
        }
        assertTrue(cons.contains("FOREIGN KEY (order_id, line_no) REFERENCES order_items (order_id, line_no)"));
        assertFalse(ddl.contains("\\"), "без команд psql");
    }

    @Test
    void ean13() {
        assertEquals(1, CatalogModule.ean13Check("400638133393"));
        assertTrue(CatalogModule.ean13Valid("4006381333931"));
        assertFalse(CatalogModule.ean13Valid("4006381333932"));
    }

    @Test
    void brandNamesAvoidRealOnes() {
        assertTrue(Names.resemblesReal("Samsunq"));
        assertTrue(Names.resemblesReal("Polariss"));
        assertTrue(Names.resemblesReal("Lumixa"));
        assertFalse(Names.resemblesReal("Velmara"));
    }
}
