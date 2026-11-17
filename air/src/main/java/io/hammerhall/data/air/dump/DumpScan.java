package io.hammerhall.data.air.dump;

import io.hammerhall.data.air.select.Longs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Первый проход по выгрузке: sha256 файла; справочники и рейсы — целиком (текстом, по нужным полям); у броней — число
 * и максимум текста {@code book_date}; у билетов и перелётов — только ключи (коды {@code long}): кто чей, чтобы
 * найти первый вылет каждой брони. Посадочные талоны в этом проходе не разбираются. Порядок блоков — любой.
 *
 * <p>Память: ≈ 16 байт на билет и на перелёт (≈ 0,8 ГБ на полной выгрузке), справочники — единицы мегабайт.
 */
public final class DumpScan {
    /** Таблицы выгрузки (схема {@code bookings}) и поля, которые берёт сборка, — как в SQL сборки. */
    public static final Map<String, List<String>> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("airports_data", List.of("airport_code", "airport_name", "city", "country", "coordinates", "timezone"));
        COLUMNS.put("airplanes_data", List.of("airplane_code", "model", "range", "speed"));
        COLUMNS.put("seats", List.of("airplane_code", "seat_no", "fare_conditions"));
        COLUMNS.put("routes", List.of("route_no", "validity", "departure_airport", "arrival_airport", "airplane_code",
                "days_of_week", "scheduled_time", "duration"));
        COLUMNS.put("flights", List.of("flight_id", "route_no", "status", "scheduled_departure", "scheduled_arrival",
                "actual_departure", "actual_arrival"));
        COLUMNS.put("bookings", List.of("book_ref", "book_date", "total_amount"));
        COLUMNS.put("tickets", List.of("ticket_no", "book_ref", "passenger_id", "passenger_name", "outbound"));
        COLUMNS.put("segments", List.of("ticket_no", "flight_id", "fare_conditions", "price"));
        COLUMNS.put("boarding_passes", List.of("ticket_no", "flight_id", "seat_no", "boarding_no", "boarding_time"));
    }

    /** Строки справочников и рейсов — значения полей {@link #COLUMNS} текстом (NULL — {@code null}). */
    public final Map<String, List<String[]>> small = new LinkedHashMap<>();
    public long bookingsTotal;
    /** {@code max(book_date)} по тексту выгрузки (как {@code max} строки в DuckDB) — поле {@code last_booking}. */
    public String lastBooking;
    public long[] bookCodes;
    public long[] ticketCode, ticketBook;
    public long[] segmentTicket, segmentFlight;
    public CopyDump.Result result;

    private DumpScan() {
    }

    /** Номера полей {@code want} по заголовку COPY; поля нет — ошибка. */
    static int[] resolve(String table, List<String> header, List<String> want) throws IOException {
        int[] idx = new int[want.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = header.indexOf(want.get(i));
            if (idx[i] < 0) throw new IOException("в блоке COPY " + table + " нет поля " + want.get(i) + " (есть " + header + ")");
        }
        return idx;
    }

    /** Все ли таблицы {@link #COLUMNS} были в выгрузке. */
    static void requireAll(CopyDump.Result r, Path file) throws IOException {
        List<String> missing = new ArrayList<>();
        for (String t : COLUMNS.keySet()) if (!r.rows().containsKey(t)) missing.add(t);
        if (!missing.isEmpty()) throw new IOException("нет таблиц " + missing + " в " + file);
    }

    public static DumpScan pass1(Path file) throws IOException {
        DumpScan s = new DumpScan();
        Longs books = new Longs(), tCode = new Longs(), tBook = new Longs(), sTicket = new Longs(), sFlight = new Longs();
        byte[][] last = {null};
        s.result = CopyDump.read(file, (table, header) -> {
            List<String> want = COLUMNS.get(table);
            if (want == null) return null;
            int[] c = resolve(table, header, want);
            switch (table) {
                case "bookings" -> {
                    return r -> {
                        s.bookingsTotal++;
                        books.add(r.key(c[0]));
                        if (!r.isNull(c[1]) && (last[0] == null || r.compareRaw(c[1], last[0]) > 0)) last[0] = r.raw(c[1]);
                    };
                }
                case "tickets" -> {
                    return r -> {
                        tCode.add(r.key(c[0]));
                        tBook.add(r.key(c[1]));
                    };
                }
                case "segments" -> {
                    return r -> {
                        sTicket.add(r.key(c[0]));
                        sFlight.add(r.longValue(c[1]));
                    };
                }
                case "boarding_passes" -> {
                    return null;
                }
                default -> {
                    List<String[]> rows = new ArrayList<>();
                    s.small.put(table, rows);
                    return r -> {
                        String[] v = new String[c.length];
                        for (int i = 0; i < c.length; i++) v[i] = r.text(c[i]);
                        rows.add(v);
                    };
                }
            }
        });
        requireAll(s.result, file);
        s.lastBooking = last[0] == null ? null : new String(last[0], StandardCharsets.UTF_8);
        s.bookCodes = books.take();
        s.ticketCode = tCode.take();
        s.ticketBook = tBook.take();
        s.segmentTicket = sTicket.take();
        s.segmentFlight = sFlight.take();
        return s;
    }

    /** Отпустить ключи билетов и перелётов (после отбора). */
    public void releaseKeys() {
        bookCodes = ticketCode = ticketBook = segmentTicket = segmentFlight = null;
    }
}
