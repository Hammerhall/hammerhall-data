package io.hammerhall.data.air;

import io.hammerhall.data.air.select.Seasonality;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.io.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Импорт крошечной выгрузки ({@link TinyDump}) целиком: отбор броней совпадает с независимым счётом по модели;
 * билеты, перелёты и талоны — со своими бронями; {@code small} — подмножество {@code full} со справочниками целиком;
 * два прогона {@code small} — один манифест побайтно; порядок строк, время, NULL — как у сборки.
 */
class SelectionTest {
    @TempDir
    static Path tmp;
    static Path full, small, small2;
    static Release.Result rf, rs;

    @BeforeAll
    static void build() throws IOException {
        String dump = TinyDump.path().toString();
        full = tmp.resolve("full");
        small = tmp.resolve("small");
        small2 = tmp.resolve("small2");
        rf = Cli.run(new AirDataset(), Map.of("profile", "full", "out", full.toString(), "dump", dump));
        rs = Cli.run(new AirDataset(), Map.of("profile", "small", "out", small.toString(), "dump", dump));
        Cli.run(new AirDataset(), Map.of("profile", "small", "out", small2.toString(), "dump", dump));
    }

    static List<String[]> rows(Path dir, String table) throws IOException {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                Files.newInputStream(dir.resolve(table + ".tsv.gz"))), StandardCharsets.UTF_8))) {
            r.readLine();
            for (String l; (l = r.readLine()) != null; ) out.add(l.split("\t", -1));
        }
        return out;
    }

    static Set<String> col(List<String[]> rows, int i) {
        Set<String> s = new TreeSet<>();
        for (String[] r : rows) s.add(r[i]);
        return s;
    }

    /** Отбор, посчитанный прямо по модели TinyDump: первый вылет брони → страна, местная дата → доля → u. */
    static Set<String> expectedKept() throws IOException {
        TinyDump.Model m = TinyDump.model();
        Map<Integer, TinyDump.Flight> fl = new HashMap<>();
        for (TinyDump.Flight f : m.flights()) fl.put(f.id(), f);
        Map<String, TinyDump.Flight> first = new HashMap<>();
        for (TinyDump.Ticket t : m.tickets()) {
            for (int id : t.flights()) {
                TinyDump.Flight f = fl.get(id);
                if (f == null) continue;
                TinyDump.Flight cur = first.get(t.book());
                if (cur == null || f.dep() < cur.dep()) first.put(t.book(), f);
            }
        }
        Set<String> kept = new TreeSet<>();
        for (Map.Entry<String, TinyDump.Flight> e : first.entrySet()) {
            TinyDump.Airport a = m.airports().get(e.getValue().depAirport());
            if (Seasonality.keep(e.getKey(), a.country(), TinyDump.local(e.getValue().dep(), a.zone()))) kept.add(e.getKey());
        }
        return kept;
    }

    @Test
    void selfCheckPasses() {
        assertTrue(rf.ok(), rf.report().render());
        assertTrue(rs.ok(), rs.report().render());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullKeepsExactlyTheModel() throws IOException {
        Set<String> want = expectedKept();
        Set<String> have = col(rows(full, "bookings"), 0);
        assertEquals(want, have);
        Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(full.resolve("manifest.json")));
        assertEquals((long) TinyDump.model().bookings().size(), m.get("bookings_total"));
        assertEquals((long) want.size(), m.get("bookings_kept"));
        assertTrue(want.size() > 1000 && want.size() < TinyDump.model().bookings().size() - 200,
                "модель и отбрасывает, и оставляет: " + want.size());
    }

    @Test
    void ticketsSegmentsAndPassesGoWithTheirBookings() throws IOException {
        for (Path dir : List.of(full, small)) {
            Set<String> books = col(rows(dir, "bookings"), 0);
            Set<String> tickets = new HashSet<>();
            int perBook = 0;
            for (TinyDump.Ticket t : TinyDump.model().tickets()) {
                if (books.contains(t.book())) {
                    tickets.add(t.no());
                    perBook++;
                }
            }
            List<String[]> tk = rows(dir, "tickets");
            assertEquals(perBook, tk.size(), "все билеты броней выпуска — и только они");
            for (String[] t : tk) assertTrue(books.contains(t[1]));
            for (String[] s : rows(dir, "segments")) assertTrue(tickets.contains(s[0]));
            for (String[] p : rows(dir, "boarding_passes")) assertTrue(tickets.contains(p[0]));
        }
    }

    @Test
    void smallIsSubsetWithWholeNetwork() throws IOException {
        Set<String> f = col(rows(full, "bookings"), 0), s = col(rows(small, "bookings"), 0);
        assertTrue(f.containsAll(s));
        assertTrue(!s.isEmpty() && s.size() < f.size() / 20, "small ≈ 1 %: " + s.size() + " из " + f.size());
        for (String t : List.of("airports", "airplanes", "seats", "routes", "flights")) {
            assertArrayEquals(Files.readAllBytes(full.resolve(t + ".tsv.gz")), Files.readAllBytes(small.resolve(t + ".tsv.gz")), t);
        }
    }

    @Test
    void twoSmallRunsAreOneRelease() throws IOException {
        for (String f : List.of("manifest.json", "SOURCE.md", "LICENSE", "schema-tables.sql", "load-postgres.psql")) {
            assertArrayEquals(Files.readAllBytes(small.resolve(f)), Files.readAllBytes(small2.resolve(f)), f);
        }
    }

    @Test
    void excludedAirportsAndOrder() throws IOException {
        List<String[]> ap = rows(full, "airports");
        Set<String> codes = col(ap, 0);
        for (String x : io.hammerhall.data.air.load.NetworkModule.EXCLUDED_AIRPORTS) assertTrue(!codes.contains(x), x);
        assertEquals(TinyDump.model().airports().size(), ap.size());
        // посадочные талоны: flight_id, boarding_no (NULL — в конце), ticket_no
        List<String[]> bp = rows(full, "boarding_passes");
        boolean sawNullThenValue = false, sawValueThenNull = false;
        for (int i = 1; i < bp.size(); i++) {
            String[] a = bp.get(i - 1), b = bp.get(i);
            int c = Integer.compare(Integer.parseInt(a[1]), Integer.parseInt(b[1]));
            assertTrue(c <= 0, "flight_id по возрастанию");
            if (c == 0) {
                if (a[3].isEmpty() && !b[3].isEmpty()) sawNullThenValue = true;
                if (!a[3].isEmpty() && b[3].isEmpty()) sawValueThenNull = true;
                if (!a[3].isEmpty() && !b[3].isEmpty()) assertTrue(Integer.parseInt(a[3]) < Integer.parseInt(b[3]));
                if (a[3].isEmpty() && b[3].isEmpty()) assertTrue(a[0].compareTo(b[0]) < 0);
            }
        }
        assertTrue(sawValueThenNull && !sawNullThenValue, "NULL boarding_no — после номеров того же рейса");
        // брони: book_date, book_ref; время — целые секунды
        List<String[]> bk = rows(full, "bookings");
        for (int i = 1; i < bk.size(); i++) {
            long a = Long.parseLong(bk.get(i - 1)[1]), b = Long.parseLong(bk.get(i)[1]);
            assertTrue(a < b || a == b && bk.get(i - 1)[0].compareTo(bk.get(i)[0]) < 0);
        }
        // маршрут: период — секунды, дни — без скобок, длительность — минуты
        String[] r = rows(full, "routes").getFirst();
        assertEquals("1,2,3,4,5,6,7", r[6]);
        assertEquals("1759276800", r[1]);
    }
}
