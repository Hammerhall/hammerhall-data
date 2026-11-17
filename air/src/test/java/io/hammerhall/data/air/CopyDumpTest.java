package io.hammerhall.data.air;

import io.hammerhall.data.air.dump.CopyDump;
import io.hammerhall.data.air.select.KeyCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор выгрузки: блоки COPY в любом порядке, NULL и экранирование, sha256 файла в том же проходе, ошибки. */
class CopyDumpTest {
    @TempDir
    Path tmp;

    static final String DUMP = """
            --
            -- PostgreSQL database dump
            --
            SET client_encoding = 'UTF8';
            CREATE TABLE bookings.tickets (ticket_no text);

            COPY public.other (a, b) FROM stdin;
            1\tCOPY bookings.fake (x) FROM stdin;
            \\.

            COPY bookings.tickets (ticket_no, book_ref, passenger_name) FROM stdin;
            0005432000001\tABC123\tAnna\\tTab
            0005432000002\tABC123\t\\N
            0005432000003\tZZZ999\tBack\\\\slash \\x41\\101\\n
            \\.

            COPY bookings.bookings (book_ref, "book_date") FROM stdin;
            ABC123\t2025-10-01 00:00:00+00
            ZZZ999\t\\N
            \\.

            ALTER TABLE ONLY bookings.tickets ADD CONSTRAINT tickets_pkey PRIMARY KEY (ticket_no);
            """;

    record Seen(String table, List<String> columns, List<List<String>> rows) {
    }

    static List<Seen> read(Path f, Map<String, Long>[] rows) throws IOException {
        List<Seen> seen = new ArrayList<>();
        CopyDump.Result r = CopyDump.read(f, (table, cols) -> {
            Seen s = new Seen(table, cols, new ArrayList<>());
            seen.add(s);
            return row -> {
                List<String> v = new ArrayList<>();
                for (int i = 0; i < row.size(); i++) v.add(row.text(i));
                s.rows().add(v);
            };
        });
        if (rows != null) rows[0] = r.rows();
        return seen;
    }

    private Path gz(String name, String text) throws IOException {
        Path p = tmp.resolve(name);
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(p))) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void blocksNullsAndEscapes() throws Exception {
        for (Path f : List.of(gz("d.sql.gz", DUMP), Files.writeString(tmp.resolve("d.sql"), DUMP))) {
            Map<String, Long>[] rows = new Map[1];
            List<Seen> s = read(f, rows);
            assertEquals(List.of("tickets", "bookings"), s.stream().map(Seen::table).toList(), "блоки других схем — мимо");
            assertEquals(List.of("ticket_no", "book_ref", "passenger_name"), s.get(0).columns());
            assertEquals(List.of("book_ref", "book_date"), s.get(1).columns(), "кавычки имени поля сняты");
            assertEquals("Anna\tTab", s.get(0).rows().get(0).get(2));
            assertNull(s.get(0).rows().get(1).get(2), "\\N — NULL");
            assertEquals("Back\\slash AA\n", s.get(0).rows().get(2).get(2), "\\\\, \\x41, \\101, \\n");
            assertNull(s.get(1).rows().get(1).get(1));
            assertEquals(Map.of("tickets", 3L, "bookings", 2L), rows[0]);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
            assertEquals(sha, CopyDump.read(f, (t, c) -> null).sha256(), "sha256 — файла как есть, в том же проходе");
        }
    }

    @Test
    void keysAndNumbersWithoutStrings() throws IOException {
        Path f = gz("k.sql.gz", "COPY bookings.segments (ticket_no, flight_id) FROM stdin;\n0005432000001\t-17\n\\.\n");
        long[] got = new long[2];
        CopyDump.read(f, (t, c) -> row -> {
            got[0] = row.key(0);
            got[1] = row.longValue(1);
        });
        assertEquals(KeyCodec.encode("0005432000001"), got[0]);
        assertEquals(-17, got[1]);
    }

    @Test
    void sha256CoversTrailingBytes() throws Exception {
        Path f = gz("t.sql.gz", DUMP);
        Files.write(f, new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
        assertEquals(sha, CopyDump.read(f, (t, c) -> null).sha256());
    }

    @Test
    void brokenDumpsFail() throws IOException {
        Path wide = gz("w.sql.gz", "COPY bookings.bookings (book_ref, book_date) FROM stdin;\nA\tB\tC\n\\.\n");
        IOException e = assertThrows(IOException.class, () -> read(wide, null));
        assertTrue(e.getMessage().contains("полей 3"), e.getMessage());
        Path cut = gz("c.sql.gz", "COPY bookings.bookings (book_ref, book_date) FROM stdin;\nA\tB\n");
        e = assertThrows(IOException.class, () -> read(cut, null));
        assertTrue(e.getMessage().contains("оборвана"), e.getMessage());
        Path twice = gz("2.sql.gz", "COPY bookings.seats (a) FROM stdin;\n\\.\nCOPY bookings.seats (a) FROM stdin;\n\\.\n");
        assertThrows(IOException.class, () -> read(twice, null));
    }

    @Test
    void keyCodecRoundTripAndRejects() {
        for (String s : List.of("0005432000000", "6TAS39", "000000", "ZZZZZZ", "0", "A", "99999999999999999", "ZZZZZZZZZZZ")) {
            long c = KeyCodec.encode(s);
            assertTrue(c > 0, s);
            assertEquals(s, KeyCodec.decode(c));
        }
        assertTrue(KeyCodec.encode("000012") != KeyCodec.encode("00012"), "длина — часть кода");
        assertTrue(KeyCodec.encode("12") != KeyCodec.encode("012"));
        for (String bad : List.of("", "abc", "AB-12", "123456789012345678", "ZZZZZZZZZZZZ", "Ж")) {
            assertThrows(IllegalArgumentException.class, () -> KeyCodec.encode(bad), bad);
        }
    }
}
