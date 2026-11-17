package io.hammerhall.data.air;

import io.hammerhall.data.core.gen.Cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/**
 * Крошечная выгрузка в формате pg_dump (COPY text, gzip) — вместо 1,1 ГБ настоящей: тесты набора идут и без неё.
 * Всё выдумано: аэропорты «Airport NN» в странах модели (и в одной стране вне модели), три аэропорта с кодами
 * исключённых и выдуманными названиями, рейсы вокруг праздничных окон, брони с билетами, перелётами и талонами; есть
 * брони без билетов и перелёты на несуществующий рейс. Время — с разными смещениями и долями секунды, блоки — не по
 * алфавиту, строки — не по порядку, у рейсов поля в заголовке COPY переставлены.
 *
 * <p>{@link #model()} — то же в виде значений выпуска, для независимого счёта отбора в тестах.
 */
final class TinyDump {
    private TinyDump() {
    }

    record Airport(String code, String country, String zone) {
    }

    record Flight(int id, String routeNo, long dep, long arr, String depAirport) {
    }

    record Ticket(String no, String book, List<Integer> flights) {
    }

    record Model(Map<String, Airport> airports, List<Flight> flights, List<String> bookings, List<Ticket> tickets) {
    }

    private static Model model;
    private static Path file;

    /** Страны модели и пояса; последняя страна — вне модели (бронь с первым вылетом оттуда не остаётся). */
    static final String[][] PLACES = {
            {"Russia", "Россия", "Europe/Moscow"}, {"Germany", "Германия", "Europe/Berlin"},
            {"United States", "США", "America/New_York"}, {"China", "Китай", "Asia/Shanghai"},
            {"Japan", "Япония", "Asia/Tokyo"}, {"India", "Индия", "Asia/Kolkata"},
            {"Chile", "Чили", "America/Santiago"}, {"New Zealand", "Новая Зеландия", "Pacific/Auckland"},
            {"Canada", "Канада", "America/Toronto"}, {"Testland", "Тестовая страна", "Atlantic/Reykjavik"}};
    static final String[] OFFSETS = {"+00", "+03", "-05", "+05:30", "-03:30"};
    static final int[] OFFSET_SEC = {0, 10800, -18000, 19800, -12600};

    /**
     * Вход набора для тестов: настоящая выгрузка, если её путь задан (или лежит в {@code air/work/}); иначе — эта,
     * крошечная ({@code -Dair.dump}).
     */
    static synchronized void useIfNoDump() throws IOException {
        Path real = Cli.inputPath(new AirDataset(), AirDataset.DUMP, Map.of());
        if (Files.isRegularFile(real) && !real.equals(file)) return;
        System.setProperty("air.dump", path().toString());
    }

    /** Путь к крошечной выгрузке (пишется один раз на JVM, во временную папку). */
    static synchronized Path path() throws IOException {
        if (file == null) {
            Path dir = Files.createTempDirectory("air-tiny-dump-");
            Path f = dir.resolve("demo-20250901-2y.sql.gz");
            model = write(f);
            f.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            file = f;
        }
        return file;
    }

    static synchronized Model model() throws IOException {
        path();
        return model;
    }

    private static String ts(long t, Random r, boolean frac) {
        int k = r.nextInt(OFFSETS.length);
        long d = frac ? r.nextInt(999_999) - 499_999 : 0;
        long micros = t * 1_000_000L + d + OFFSET_SEC[k] * 1_000_000L;
        long sec = Math.floorDiv(micros, 1_000_000L), us = Math.floorMod(micros, 1_000_000L);
        LocalDateTime l = LocalDateTime.ofEpochSecond(sec, 0, ZoneOffset.UTC);
        String s = String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d", l.getYear(), l.getMonthValue(),
                l.getDayOfMonth(), l.getHour(), l.getMinute(), l.getSecond());
        if (us != 0) s += "." + String.format(Locale.ROOT, "%06d", us).replaceAll("0+$", "");
        return s + OFFSETS[k];
    }

    private static String json(String en, String ru) {
        return "{\"en\": \"" + en + "\", \"ru\": \"" + ru + "\"}";
    }

    /** Строки блока — в обратном порядке (выгрузка не обязана быть упорядочена). */
    private static void block(Writer w, String header, List<String> rows) throws IOException {
        w.write(header + "\n");
        for (int i = rows.size() - 1; i >= 0; i--) w.write(rows.get(i) + "\n");
        w.write("\\.\n\n");
    }

    static Model write(Path f) throws IOException {
        Random r = new Random(20250901);
        Map<String, Airport> airports = new LinkedHashMap<>();
        List<String> apRows = new ArrayList<>();
        int n = 0;
        for (String[] p : PLACES) {
            for (int k = 0; k < 2; k++) {
                n++;
                String code = String.format(Locale.ROOT, "T%02d", n);
                airports.put(code, new Airport(code, p[0], p[2]));
                apRows.add(code + "\t" + json("Airport " + n, "Аэропорт " + n) + "\t" + json("City " + n, "Город " + n)
                        + "\t" + json(p[0], p[1]) + "\t(" + (10 + n) + "." + (1 + n % 9) + "," + (-5 - n) + ".25)\t" + p[2]);
            }
        }
        // коды исключённых аэропортов — с выдуманными названиями; в маршрутах их нет
        for (String code : io.hammerhall.data.air.load.NetworkModule.EXCLUDED_AIRPORTS) {
            apRows.add(code + "\t" + json("Spare Field " + code, "Запасное поле " + code) + "\t" + json("Nowhere", "Нигде")
                    + "\t" + json("Testland", "Тестовая страна") + "\t(0,0)\tUTC");
        }
        List<String> codes = new ArrayList<>(airports.keySet());

        // маршруты: по две версии, граница — 2026-01-01; рейсы ежедневно 2025-12-01 … 2026-01-29
        long split = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long from = LocalDate.of(2025, 10, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long to = LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        List<String> routeRows = new ArrayList<>(), flightRows = new ArrayList<>();
        List<Flight> flights = new ArrayList<>();
        int fid = 0;
        for (int i = 0; i < codes.size(); i++) {
            String rno = String.format(Locale.ROOT, "TT%04d", i + 1);
            String dep = codes.get(i), arr = codes.get((i + 7) % codes.size());
            int depMin = 60 + 37 * i % 1300, dur = 90 + 13 * i;
            routeRows.add(rno + "\t[\"" + ts(from, r, false) + "\",\"" + ts(split, r, false) + "\")\t" + dep + "\t" + arr
                    + "\tA1\t{1,2,3,4,5,6,7}\t" + String.format(Locale.ROOT, "%02d:%02d:00", depMin / 60, depMin % 60) + "\t"
                    + String.format(Locale.ROOT, "%02d:%02d:00", dur / 60, dur % 60));
            routeRows.add(rno + "\t[\"" + ts(split, r, false) + "\",\"" + ts(to, r, false) + "\")\t" + dep + "\t" + arr
                    + "\tB2\t{1,3,5}\t" + String.format(Locale.ROOT, "%02d:%02d:00", depMin / 60, depMin % 60) + "\t"
                    + String.format(Locale.ROOT, "%02d:%02d:00", (dur + 5) / 60, (dur + 5) % 60));
            for (int day = 0; day < 60; day++) {
                long d = LocalDate.of(2025, 12, 1).plusDays(day).atStartOfDay(ZoneOffset.UTC).toEpochSecond() + depMin * 60L;
                long a = d + (d < split ? dur : dur + 5) * 60L;
                fid++;
                flights.add(new Flight(fid, rno, d, a, dep));
                boolean flown = day < 40;
                flightRows.add(fid + "\t" + ts(d, r, false) + "\t" + rno + "\t" + (flown ? "Arrived" : "Scheduled") + "\t"
                        + ts(a, r, false) + "\t" + (flown ? ts(d + 300 + r.nextInt(900), r, true) : "\\N") + "\t"
                        + (flown ? ts(a + 120 + r.nextInt(900), r, true) : "\\N"));
            }
        }
        // брони: 1–3 билета, у билета — 1–2 перелёта (второй — позже), талоны — с уникальным местом и номером посадки
        List<String> bookings = new ArrayList<>(), bookRows = new ArrayList<>(), ticketRows = new ArrayList<>(),
                segRows = new ArrayList<>(), bpRows = new ArrayList<>();
        List<Ticket> tickets = new ArrayList<>();
        Map<Integer, Integer> seatsTaken = new HashMap<>();
        long ticketNo = 5_432_000_000L;
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        java.util.Set<String> refs = new java.util.HashSet<>();
        for (int b = 0; b < 3000; b++) {
            String ref;
            do {
                char[] c = new char[6];
                for (int i = 0; i < 6; i++) c[i] = alphabet.charAt(r.nextInt(36));
                ref = new String(c);
            } while (!refs.add(ref));
            bookings.add(ref);
            // у каждой 97-й брони билетов нет, у каждой 41-й — только билет на несуществующий рейс: первого вылета нет
            int nt = b % 97 == 0 || b % 41 == 0 ? 0 : 1 + r.nextInt(3);
            int f1 = r.nextInt(flights.size()), f2 = f1 + 1 + r.nextInt(30);
            boolean two = r.nextInt(3) == 0 && f2 < flights.size() && flights.get(f2).dep() > flights.get(f1).dep();
            long total = 0;
            for (int t = 0; t < nt; t++) {
                String tno = String.format(Locale.ROOT, "%013d", ticketNo++);
                List<Integer> fl = new ArrayList<>();
                fl.add(flights.get(f1).id());
                if (two) fl.add(flights.get(f2).id());
                tickets.add(new Ticket(tno, ref, fl));
                ticketRows.add(tno + "\t" + ref + "\t" + "TS " + (1_000_000 + r.nextInt(9_000_000)) + "\t" + "Passenger "
                        + (b * 3 + t) + (b % 50 == 1 ? " Ñuñez" : "") + "\t" + (t % 2 == 0 ? "t" : "f"));
                for (int id : fl) {
                    long price = 1000 + 250L * r.nextInt(40);
                    total += price;
                    segRows.add(tno + "\t" + id + "\t" + (price > 8000 ? "Business" : "Economy") + "\t" + price + ".00");
                    int seat = seatsTaken.merge(id, 1, Integer::sum);
                    Flight fl0 = flights.get(id - 1);
                    // рейсы до 10.01.2026 — посадка была (каждый десятый не пришёл: NULL), позже — ещё нет
                    boolean boarded = fl0.dep() < LocalDate.of(2026, 1, 10).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                            && r.nextInt(10) != 0;
                    bpRows.add(tno + "\t" + id + "\t" + seat + "A\t" + (boarded ? Integer.toString(seat) : "\\N") + "\t"
                            + (boarded ? ts(fl0.dep() - 3600 + seat * 7L, r, true) : "\\N"));
                }
            }
            if (b % 41 == 0 && b % 97 != 0) {
                String tno = String.format(Locale.ROOT, "%013d", ticketNo++);
                tickets.add(new Ticket(tno, ref, List.of(999_999)));
                ticketRows.add(tno + "\t" + ref + "\tTS 1\tPassenger X\tt");
                segRows.add(tno + "\t999999\tEconomy\t100.00");
                total += 100;
            }
            long bookDate = LocalDate.of(2025, 9, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() + r.nextInt(80 * 86_400);
            bookRows.add(ref + "\t" + ts(bookDate, r, true) + "\t" + total + ".00");
        }
        try (OutputStream o = Files.newOutputStream(f);
             Writer w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(o), StandardCharsets.UTF_8))) {
            w.write("--\n-- PostgreSQL database dump (крошечная, для тестов)\n--\n\nSET client_encoding = 'UTF8';\n\n");
            block(w, "COPY bookings.segments (ticket_no, flight_id, fare_conditions, price) FROM stdin;", segRows);
            block(w, "COPY bookings.tickets (ticket_no, book_ref, passenger_id, passenger_name, outbound) FROM stdin;", ticketRows);
            block(w, "COPY bookings.flights (flight_id, scheduled_departure, route_no, status, scheduled_arrival, actual_departure, actual_arrival) FROM stdin;", flightRows);
            block(w, "COPY bookings.bookings (book_ref, book_date, total_amount) FROM stdin;", bookRows);
            block(w, "COPY bookings.boarding_passes (ticket_no, flight_id, seat_no, boarding_no, boarding_time) FROM stdin;", bpRows);
            block(w, "COPY bookings.airports_data (airport_code, airport_name, city, country, coordinates, timezone) FROM stdin;", apRows);
            block(w, "COPY bookings.routes (route_no, validity, departure_airport, arrival_airport, airplane_code, days_of_week, scheduled_time, duration) FROM stdin;", routeRows);
            block(w, "COPY bookings.airplanes_data (airplane_code, model, range, speed) FROM stdin;", List.of(
                    "A1\t" + json("Plane One", "Самолёт один") + "\t5000\t800",
                    "B2\t" + json("Plane Two", "Самолёт два") + "\t9000\t850"));
            List<String> seatRows = new ArrayList<>();
            for (String a : List.of("A1", "B2")) {
                for (int s = 1; s <= 6; s++) seatRows.add(a + "\t" + s + "A\t" + (s <= 2 ? "Business" : "Economy"));
            }
            block(w, "COPY bookings.seats (airplane_code, seat_no, fare_conditions) FROM stdin;", seatRows);
        }
        return new Model(airports, flights, bookings, tickets);
    }

    /** Момент выпуска → местная дата в поясе. */
    static LocalDate local(long epoch, String zone) {
        return Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.of(zone)).toLocalDate();
    }
}
