package io.hammerhall.data.mov.source;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV по RFC 4180 — как его пишет модуль {@code csv} Python ({@code QUOTE_MINIMAL}): поля через запятую; поле в
 * кавычках может содержать запятые, переводы строк и кавычки (удвоенные: {@code ""}); конец записи — {@code \n},
 * {@code \r\n} или {@code \r}. Пустые строки — не записи. После закрывающей кавычки — только запятая или конец записи,
 * иначе — ошибка с номером строки. Чтение — своим буфером знаков: файлы снимка большие (миллионы строк).
 */
public final class Csv {
    private final Reader in;
    private final String name;
    private final char[] buf = new char[1 << 16];
    private int pos, len;
    private int line = 1;
    private int pushback = -2;

    public Csv(Reader in, String name) {
        this.in = in;
        this.name = name;
    }

    /** Все записи текста (для тестов и маленьких файлов). */
    public static List<List<String>> parse(String text) throws IOException {
        Csv c = new Csv(new StringReader(text), "csv");
        List<List<String>> out = new ArrayList<>();
        for (List<String> r; (r = c.next()) != null; ) out.add(r);
        return out;
    }

    private int read() throws IOException {
        if (pushback != -2) {
            int c = pushback;
            pushback = -2;
            return c;
        }
        if (pos == len) {
            len = in.read(buf, 0, buf.length);
            pos = 0;
            if (len <= 0) {
                len = 0;
                return -1;
            }
        }
        return buf[pos++];
    }

    private IOException bad(String what) {
        return new IOException(name + ":" + line + ": " + what);
    }

    /** Следующая запись; {@code null} — конец файла. */
    public List<String> next() throws IOException {
        while (true) {
            List<String> rec = new ArrayList<>();
            StringBuilder f = new StringBuilder();
            boolean any = false, quotedSeen = false;
            int c = read();
            if (c == -1) return null;
            while (true) {
                if (c == '"' && f.isEmpty() && !quotedSeen) {
                    // поле в кавычках — до кавычки, за которой не кавычка
                    quotedSeen = true;
                    any = true;
                    while (true) {
                        int d = read();
                        if (d == -1) throw bad("нет закрывающей кавычки");
                        if (d == '"') {
                            int e = read();
                            if (e == '"') {
                                f.append('"');
                                continue;
                            }
                            c = e;
                            break;
                        }
                        if (d == '\n') line++;
                        f.append((char) d);
                    }
                    if (c != ',' && c != '\n' && c != '\r' && c != -1) throw bad("после закрывающей кавычки — «" + (char) c + "»");
                    continue;
                }
                if (c == ',') {
                    rec.add(f.toString());
                    f.setLength(0);
                    quotedSeen = false;
                    any = true;
                } else if (c == '\n' || c == '\r' || c == -1) {
                    if (c == '\r') {
                        int d = read();
                        if (d != '\n') pushback = d;
                    }
                    if (c != -1) line++;
                    rec.add(f.toString());
                    break;
                } else {
                    if (quotedSeen) throw bad("после закрывающей кавычки — «" + (char) c + "»");
                    f.append((char) c);
                    any = true;
                }
                c = read();
            }
            if (!any && rec.size() == 1 && rec.getFirst().isEmpty()) continue;
            return rec;
        }
    }

    /** Таблица с заголовком: поле записи по имени столбца. */
    public static final class Table {
        private final Csv csv;
        private final Map<String, Integer> columns = new HashMap<>();
        private final int width;

        public Table(Csv csv, List<String> required) throws IOException {
            this.csv = csv;
            List<String> header = csv.next();
            if (header == null) throw csv.bad("нет заголовка");
            for (int i = 0; i < header.size(); i++) columns.put(header.get(i), i);
            for (String r : required) if (!columns.containsKey(r)) throw csv.bad("в заголовке нет столбца " + r + ": " + header);
            width = header.size();
        }

        /** Следующая запись или {@code null}; число полей — как в заголовке, иначе — ошибка. */
        public List<String> next() throws IOException {
            List<String> r = csv.next();
            if (r != null && r.size() != width) throw csv.bad("полей " + r.size() + ", в заголовке " + width);
            return r;
        }

        /** Номер столбца по имени (для частых обращений в больших файлах). */
        public int column(String name) {
            Integer i = columns.get(name);
            if (i == null) throw new IllegalArgumentException(csv.name + ": нет столбца " + name);
            return i;
        }

        public String get(List<String> rec, String column) {
            return rec.get(column(column));
        }

        /** Ошибка разбора с именем файла и номером строки. */
        public IOException bad(String what) {
            return csv.bad(what);
        }

        public int line() {
            return csv.line;
        }
    }
}
