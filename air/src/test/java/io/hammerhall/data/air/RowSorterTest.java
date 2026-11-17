package io.hammerhall.data.air;

import io.hammerhall.data.air.load.RowSorter;
import io.hammerhall.data.core.io.RowFormat;
import io.hammerhall.data.core.schema.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Внешняя сортировка: много отрезков на диске, запись в своём потоке, слияние в порядке таблицы (NULL — в конце). */
class RowSorterTest {
    @TempDir
    Path tmp;

    @Test
    void manyRunsMergeInTableOrder() throws IOException {
        Table bp = AirSchema.build().table("boarding_passes");
        Random r = new Random(7);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            Integer no = r.nextInt(5) == 0 ? null : r.nextInt(1000);
            rows.add(new Object[]{String.format("%013d", r.nextInt(100_000)), r.nextInt(30), "1A", no,
                    no == null ? null : 1_759_000_000L + r.nextInt(1000)});
        }
        rows.add(new Object[]{"0000000000001", 1, "2B", null, null});
        List<Object[]> want = new ArrayList<>(rows);
        want.sort(RowFormat.comparator(bp));
        List<Object[]> got = new ArrayList<>();
        try (RowSorter.Spiller sp = new RowSorter.Spiller()) {
            RowSorter s = new RowSorter("bp", RowFormat.comparator(bp), 333, tmp, sp);
            for (Object[] x : rows) s.add(x);
            s.await();
            assertEquals(15, s.runs(), "отрезков (остаток — в памяти до drain)");
            assertTrue(s.spilledBytes() > 0);
            s.drain(got::add);
        }
        assertEquals(want.size(), got.size());
        for (int i = 0; i < want.size(); i++) assertEquals(Arrays.asList(want.get(i)), Arrays.asList(got.get(i)), "строка " + i);
        // NULL boarding_no — после номеров того же рейса
        for (int i = 1; i < got.size(); i++) {
            if (got.get(i)[1].equals(got.get(i - 1)[1]) && got.get(i - 1)[3] == null) assertEquals(null, got.get(i)[3]);
        }
        try (Stream<Path> left = Files.walk(tmp)) {
            assertEquals(1, left.count(), "временные файлы удалены");
        }
    }

    @Test
    void typesSurviveTheDisk() throws IOException {
        Object[] row = {"Ñuñez \"x\"", 1L, 2, true, false, new BigDecimal("1290.50"), null};
        List<Object[]> got = new ArrayList<>();
        RowSorter s = new RowSorter("t", (a, b) -> 0, 1, tmp);
        s.add(row);
        s.add(row.clone());
        s.drain(got::add);
        assertEquals(2, got.size());
        assertEquals(Arrays.asList(row), Arrays.asList(got.get(1)));
    }
}
