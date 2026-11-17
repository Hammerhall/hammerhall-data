package io.hammerhall.data.air.load;

import io.hammerhall.data.air.dump.CopyDump;
import io.hammerhall.data.air.dump.CopyRow;
import io.hammerhall.data.air.dump.DumpScan;
import io.hammerhall.data.air.dump.DumpSource;
import io.hammerhall.data.air.dump.PgValues;
import io.hammerhall.data.air.select.LongSet;
import io.hammerhall.data.air.select.Selection;
import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.RowFormat;
import io.hammerhall.data.core.io.TableSink;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Продажи: брони, билеты, перелёты, посадочные талоны — второй проход по выгрузке. Строка берётся, если её бронь
 * (у билета) или её билет (у перелёта и талона) в отборе ({@link NetworkModule#SELECTION}); строки уходят во внешнюю
 * сортировку ({@link RowSorter}; отрезки пишет свой поток, пока разбор идёт дальше), затем — в файлы выпуска двумя
 * дорожками ({@link #write}). Порядок строк в выгрузке не важен.
 */
public final class SalesModule implements Module {
    /** Строк в отрезке внешней сортировки: ≈ 150–300 МБ кучи на таблицу (четыре таблицы — сразу, во втором проходе). */
    static final int RUN_ROWS = 1_000_000;

    private final DumpSource source;
    private long bookingsMissing = -1;

    public SalesModule(DumpSource source) {
        this.source = source;
    }

    @Override
    public String name() {
        return "sales";
    }

    @Override
    public List<String> requires() {
        return List.of("network");
    }

    @Override
    public List<String> tables() {
        return List.of("bookings", "tickets", "segments", "boarding_passes");
    }

    /** Повторяющиеся значения — одним объектом (классы обслуживания: три значения на десятки миллионов строк). */
    private static final Map<String, String> FARES = new LinkedHashMap<>();

    static {
        for (String f : io.hammerhall.data.air.AirSchema.FARES) FARES.put(f, f);
    }

    private static String fare(String s) {
        String f = FARES.get(s);
        return f != null ? f : s;
    }

    private static Integer toInt(String s) {
        return s == null ? null : Integer.parseInt(s);
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        Selection sel = ctx.get(NetworkModule.SELECTION);
        LongSet books = sel.books(), tickets = sel.tickets();
        String tmp = System.getProperty("data.tmpdir");
        Path tmpDir = tmp == null || tmp.isBlank() ? null : Path.of(tmp);
        Map<String, RowSorter> sorters = new LinkedHashMap<>();
        RowSorter.Spiller spiller = new RowSorter.Spiller();
        for (String t : tables()) {
            sorters.put(t, new RowSorter(t, RowFormat.comparator(ctx.schema().table(t)), RUN_ROWS, tmpDir, spiller));
        }
        DumpScan first = source.scan(ctx.config().input("dump"));
        Path file = source.path();
        ctx.log("выгрузка: второй проход (брони, билеты, перелёты, посадочные талоны отбора)…");
        try {
            CopyDump.Result r = CopyDump.read(file, (table, header) -> {
                List<String> want = DumpScan.COLUMNS.get(table);
                if (want == null || !sorters.containsKey(table)) return null;
                int[] c = DumpScan.COLUMNS.get(table).stream().mapToInt(header::indexOf).toArray();
                for (int i = 0; i < c.length; i++) {
                    if (c[i] < 0) throw new IOException("в блоке COPY " + table + " нет поля " + want.get(i));
                }
                RowSorter out = sorters.get(table);
                return switch (table) {
                    case "bookings" -> (CopyRow row) -> {
                        if (books.contains(row.key(c[0]))) {
                            out.add(new Object[]{row.text(c[0]), PgValues.epoch(row.text(c[1])), PgValues.moneyAsIs(row.text(c[2]))});
                        }
                    };
                    case "tickets" -> (CopyRow row) -> {
                        if (books.contains(row.key(c[1]))) {
                            out.add(new Object[]{row.text(c[0]), row.text(c[1]), row.text(c[2]), row.text(c[3]),
                                    PgValues.bool(row.text(c[4]))});
                        }
                    };
                    case "segments" -> (CopyRow row) -> {
                        if (tickets.contains(row.key(c[0]))) {
                            out.add(new Object[]{row.text(c[0]), toInt(row.text(c[1])), fare(row.text(c[2])),
                                    PgValues.moneyAsIs(row.text(c[3]))});
                        }
                    };
                    default -> (CopyRow row) -> {
                        if (tickets.contains(row.key(c[0]))) {
                            out.add(new Object[]{row.text(c[0]), toInt(row.text(c[1])), row.text(c[2]),
                                    toInt(row.text(c[3])), PgValues.epoch(row.text(c[4]))});
                        }
                    };
                };
            });
            for (RowSorter s : sorters.values()) s.await();
            if (!r.sha256().equals(first.result.sha256())) {
                throw new IOException("выгрузка " + file + " изменилась между проходами (sha256 " + first.result.sha256()
                        + " → " + r.sha256() + ")");
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, RowSorter> e : sorters.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue().rows()).append(" (отрезков ")
                        .append(e.getValue().runs()).append(", ").append(e.getValue().spilledBytes() >> 20).append(" МБ)");
            }
            ctx.log("второй проход: строк отбора —" + sb);
            bookingsMissing = books.size() - sorters.get("bookings").rows();
            sel.dropSets();
            write(ctx, sorters);
        } finally {
            for (RowSorter s : sorters.values()) s.close();
            spiller.close();
        }
    }

    /**
     * Запись таблиц двумя дорожками — брони, билеты, перелёты и отдельно посадочные талоны (на полной выгрузке сжатие
     * gzip 9 у обеих — примерно поровну, ≈ 3,5 мин): узкое место — сжатие (у каждого файла — свой поток), и на двух
     * ядрах две таблицы сразу почти вдвое быстрее. Байты файлов от этого не зависят. Ссылки талонов на перелёты, пока
     * перелёты не дописаны, ядро откладывает (сверх порога — на диск) и проверяет при их закрытии.
     */
    private static void write(GenContext ctx, Map<String, RowSorter> sorters) throws IOException {
        Map<String, TableSink> sinks = new LinkedHashMap<>();
        for (String t : sorters.keySet()) sinks.put(t, ctx.streamSink(t));
        List<List<String>> lanes = List.of(List.of("bookings", "tickets", "segments"), List.of("boarding_passes"));
        List<Thread> threads = new java.util.ArrayList<>();
        Throwable[] failure = new Throwable[lanes.size()];
        for (int i = 0; i < lanes.size(); i++) {
            int lane = i;
            threads.add(Thread.ofPlatform().name("air-write-" + i).start(() -> {
                try {
                    for (String t : lanes.get(lane)) {
                        long t0 = System.nanoTime();
                        TableSink sink = sinks.get(t);
                        sorters.get(t).drain(sink::add);
                        ctx.close(t);
                        ctx.log(String.format(Locale.ROOT, "  %s: %d строк, %.1f с", t, sink.rows(), (System.nanoTime() - t0) / 1e9));
                    }
                } catch (Throwable e) {
                    failure[lane] = e;
                }
            }));
        }
        for (Thread th : threads) {
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("прервано", e);
            }
        }
        for (Throwable e : failure) {
            if (e instanceof IOException io) throw io;
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error er) throw er;
        }
    }

    @Override
    public void check(GenContext ctx, CheckReport report) {
        report.expectNone("брони: каждая бронь отбора есть в таблице броней", bookingsMissing, List.of(),
                "все " + ctx.get(NetworkModule.SELECTION).stats().inProfile());
    }

    @Override
    public List<Aggregate> aggregates() {
        return List.of(
                Aggregate.min("bookings", "book_date"), Aggregate.max("bookings", "book_date"),
                Aggregate.sum("bookings", "book_date"),
                Aggregate.countEq("tickets", "outbound", "t"),
                Aggregate.min("tickets", "ticket_no"), Aggregate.max("tickets", "ticket_no"),
                Aggregate.countEq("segments", "fare_conditions", "Business"),
                Aggregate.countEq("segments", "fare_conditions", "Comfort"),
                Aggregate.sum("segments", "flight_id"),
                Aggregate.sum("boarding_passes", "boarding_no"),
                Aggregate.sum("boarding_passes", "boarding_time"));
    }
}
