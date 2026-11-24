package io.hammerhall.data.mov.site;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Чтение готовой таблицы выпуска ({@code <таблица>.tsv.gz}) построчно — для самопроверки по записанному: заголовок
 * сверяется, поля строки — в переиспользуемом массиве, пустое поле — пустая строка.
 */
final class Tsv {
    private Tsv() {
    }

    interface Row {
        void row(String[] f);
    }

    static long read(Path dir, String table, String header, Row row) throws IOException {
        Path p = dir.resolve(table + ".tsv.gz");
        if (!Files.isRegularFile(p)) return -1;
        long n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(p), 1 << 16),
                StandardCharsets.UTF_8), 1 << 20)) {
            String head = r.readLine();
            if (!header.equals(head)) throw new IOException(table + ": заголовок «" + head + "», ожидался «" + header + "»");
            int width = header.split("\t", -1).length;
            String[] f = new String[width];
            for (String line; (line = r.readLine()) != null; ) {
                int k = 0, from = 0;
                for (int i = 0; i <= line.length() && k < width; i++) {
                    if (i == line.length() || line.charAt(i) == '\t') {
                        f[k++] = line.substring(from, i);
                        from = i + 1;
                    }
                }
                if (k != width) throw new IOException(table + ": строка " + (n + 2) + " — полей " + k);
                row.row(f);
                n++;
            }
        }
        return n;
    }

    /** Целое или {@code -1} для пустого. */
    static long num(String s) {
        return s.isEmpty() ? -1 : Long.parseLong(s);
    }

    static int i(String s) {
        return s.isEmpty() ? -1 : Integer.parseInt(s);
    }

    /** Деньги «1290.00» → копейки; пустое — {@code -1}. */
    static long kop(String s) {
        if (s.isEmpty()) return -1;
        int dot = s.indexOf('.');
        if (dot < 0) return Long.parseLong(s) * 100;
        String frac = (s.substring(dot + 1) + "00").substring(0, 2);
        return Long.parseLong(s.substring(0, dot)) * 100 + Long.parseLong(frac);
    }

    static boolean bool(String s) {
        return s.equals("t");
    }
}
