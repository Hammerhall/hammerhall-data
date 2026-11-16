package io.hammerhall.data.core.check;

import io.hammerhall.data.core.io.GzipTsvFile;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.hammerhall.data.core.schema.ColType.BOOL;
import static io.hammerhall.data.core.schema.ColType.DATE;
import static io.hammerhall.data.core.schema.ColType.DECIMAL;
import static io.hammerhall.data.core.schema.ColType.INT;
import static io.hammerhall.data.core.schema.ColType.MONEY;
import static io.hammerhall.data.core.schema.ColType.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Контрольные агрегаты: счёт по тексту TSV — точный, тот же, что в {@code tools/check_data.py}. */
class AggregatesTest {
    @TempDir
    Path tmp;

    static Table table() {
        Table t = Table.of("t", "g", "Тест")
                .col("id", INT, "id")
                .col("price", MONEY, "цена").nullable()
                .col("share", DECIMAL, "доля").scale(5, 3)
                .col("name", TEXT, "имя")
                .col("on", DATE, "дата")
                .col("flag", BOOL, "флаг")
                .pk("id");
        new Schema("t", "v").group("g", "g").add(t);
        return t;
    }

    @Test
    void scaledNumbers() {
        byte[] b = "-12.5".getBytes(StandardCharsets.US_ASCII);
        assertEquals(-1250, Aggregates.scaled(b, 0, b.length, 2));
        byte[] z = "0".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0, Aggregates.scaled(z, 0, z.length, 3));
        assertEquals("1290.50", Aggregates.formatScaled(129050, 2));
        assertEquals("-0.07", Aggregates.formatScaled(-7, 2));
        assertEquals("0.000", Aggregates.formatScaled(0, 3));
        assertEquals("-42", Aggregates.formatScaled(-42, 0));
        for (String bad : List.of("1.234", "", "-", ".", "1..2", "1e3", "12a")) {
            byte[] x = bad.getBytes(StandardCharsets.US_ASCII);
            assertThrows(NumberFormatException.class, () -> Aggregates.scaled(x, 0, x.length, 2), bad);
        }
    }

    @Test
    void computeOverTsv() throws IOException {
        Table t = table();
        Path p = tmp.resolve("t.tsv.gz");
        try (GzipTsvFile f = new GzipTsvFile(p, 6)) {
            f.line(t.header());
            f.line("1\t1290.00\t0.5\tЯблоко\t2026-01-02\tt");
            f.line("2\t\t1.25\tapple\t2025-12-31\tf");
            f.line("3\t-5.07\t0\tЁж\t2027-03-01\tt");
            f.line("4\t10.10\t0.125\tapple\t2026-06-30\tt");
        }
        List<Aggregate> as = List.of(Aggregate.sum("t", "price"), Aggregate.nulls("t", "price"), Aggregate.min("t", "price"),
                Aggregate.max("t", "price"), Aggregate.sum("t", "share"), Aggregate.min("t", "name"), Aggregate.max("t", "name"),
                Aggregate.distinct("t", "name"), Aggregate.countEq("t", "flag", "t"), Aggregate.min("t", "on"),
                Aggregate.max("t", "on"), Aggregate.max("t", "id"));
        List<Aggregates.Spec> specs = new ArrayList<>();
        for (Aggregate a : as) specs.add(Aggregates.Spec.of(a, t));
        Aggregates.FileStats st = Aggregates.compute(p, specs);
        assertEquals(4, st.rows());
        assertEquals(List.of("1295.03", 1L, "-5.07", "1290.00", "1.875",
                        "apple", "Яблоко", 3L, 3L, "2025-12-31", "2027-03-01", "4"),
                st.values());
        // спецификация переживает запись в JSON и обратно
        Aggregates.Spec s = Aggregates.Spec.of(Aggregate.sum("t", "share"), t);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> back = (java.util.Map<String, Object>) io.hammerhall.data.core.io.Json.parse(
                io.hammerhall.data.core.io.Json.write(s.toJson("1.875")));
        assertEquals(s, Aggregates.Spec.fromJson(back));
        assertEquals(3, s.scale());
    }

    @Test
    void sumNeedsANumber() {
        assertThrows(IllegalArgumentException.class, () -> Aggregates.Spec.of(Aggregate.sum("t", "name"), table()));
        assertThrows(IllegalArgumentException.class, () -> new Aggregate("t", "avg", "price", null));
        assertThrows(IllegalArgumentException.class, () -> new Aggregate("t", "count_eq", "flag", null));
    }

    @Test
    void defaultsCoverMoneyAndNullable() {
        assertEquals(List.of("sum(price)", "nulls(price)"),
                Aggregates.defaults(table()).stream().map(Aggregate::label).toList());
    }
}
