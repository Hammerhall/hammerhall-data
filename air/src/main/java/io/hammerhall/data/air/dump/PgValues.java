package io.hammerhall.data.air.dump;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Значения выгрузки → значения выпуска — те же преобразования, что в SQL сборки {@code air-data-v1}
 * (прежняя сборка на Python и DuckDB): там поля читались текстом как есть ({@code all_varchar}), и здесь всё, что
 * не преобразуется, идёт текстом как есть. Каждая функция — одно выражение того SQL; имя выражения — в описании.
 */
public final class PgValues {
    private PgValues() {
    }

    // ---------------------------------------------------------------- время

    /**
     * Как сборка переводит микросекунды Unix в целые секунды: {@code epoch(x::timestamptz)::bigint} в DuckDB —
     * {@code epoch} даёт {@code double} (микросекунды / 10⁶), приведение {@code double → bigint} в DuckDB —
     * {@code nearbyint}, то есть <b>округление к ближайшему, половина — к чётному</b>, а не отбрасывание долей.
     *
     * <p>Описание выпуска {@code air-data-v1} говорило «доли секунды отброшены», но сам выпуск — округление: последняя
     * бронь выгрузки — {@code 2027-08-31 23:59:58.579152+00} (поле {@code last_booking} манифеста, максимум текста
     * {@code book_date}), а в {@code bookings.tsv.gz} есть бронь с {@code book_date = 1819756799} (23:59:59). При
     * отбрасывании такой не было бы. Окончательно — сверкой с настоящей выгрузкой; вариант меняется здесь, в одном
     * месте ({@link #ROUNDING}).
     */
    public static long seconds(long micros) {
        return switch (ROUNDING) {
            case NEAREST_EVEN -> (long) Math.rint((double) micros / 1_000_000.0);
            case TRUNCATE -> Math.floorDiv(micros, 1_000_000L);
        };
    }

    /** Как {@link #seconds} обходится с долями секунды. */
    public enum Rounding {NEAREST_EVEN, TRUNCATE}

    /** Выбор для {@link #seconds}: округление (подтверждено выпуском {@code air-data-v1}, см. там же). */
    public static final Rounding ROUNDING = Rounding.NEAREST_EVEN;

    /** Строка поля {@code format.time} манифеста — по {@link #ROUNDING}. */
    public static String timeFormatNote() {
        return ROUNDING == Rounding.NEAREST_EVEN
                ? "Unix seconds, UTC (fractions rounded to the nearest second, half to even)"
                : "Unix seconds, UTC (fractions dropped)";
    }

    /** {@code epoch(x::timestamptz)::bigint}; {@code null} — {@code null}. */
    public static Long epoch(String timestamptz) {
        return timestamptz == null ? null : seconds(micros(timestamptz));
    }

    /**
     * Микросекунды Unix из текста {@code timestamptz} Postgres: {@code YYYY-MM-DD HH:MM:SS[.ffffff][±HH[:MM[:SS]]]}
     * (смещение — как пишет Postgres в любом поясе сеанса; без смещения — UTC, как у сборки с {@code timezone='UTC'}).
     * Даты до нашей эры, {@code infinity} и прочее — ошибка.
     */
    public static long micros(String s) {
        int n = s.length();
        if (n < 19 || s.charAt(4) != '-' || s.charAt(7) != '-' || (s.charAt(10) != ' ' && s.charAt(10) != 'T')
                || s.charAt(13) != ':' || s.charAt(16) != ':') {
            throw bad("timestamptz", s);
        }
        int year = digits(s, 0, 4), month = digits(s, 5, 7), day = digits(s, 8, 10);
        int hh = digits(s, 11, 13), mm = digits(s, 14, 16), ss = digits(s, 17, 19);
        int p = 19;
        long frac = 0;
        if (p < n && s.charAt(p) == '.') {
            int q = ++p;
            while (p < n && Character.isDigit(s.charAt(p))) p++;
            if (p == q || p - q > 6) throw bad("timestamptz", s);
            frac = digits(s, q, p);
            for (int k = p - q; k < 6; k++) frac *= 10;
        }
        int offset = 0;
        if (p < n) {
            char sign = s.charAt(p);
            if (sign != '+' && sign != '-') throw bad("timestamptz", s);
            int q = p + 1;
            int oh = digits(s, q, q + 2), om = 0, os = 0;
            q += 2;
            if (q < n) {
                if (s.charAt(q) != ':') throw bad("timestamptz", s);
                om = digits(s, q + 1, q + 3);
                q += 3;
                if (q < n) {
                    if (s.charAt(q) != ':') throw bad("timestamptz", s);
                    os = digits(s, q + 1, q + 3);
                    q += 3;
                }
            }
            if (q != n || om > 59 || os > 59) throw bad("timestamptz", s);
            offset = (sign == '+' ? 1 : -1) * (oh * 3600 + om * 60 + os);
        }
        if (hh > 24 || mm > 59 || ss > 60) throw bad("timestamptz", s);
        long epochDay = LocalDate.of(year, month, day).toEpochDay();
        long secs = epochDay * 86_400L + hh * 3600L + mm * 60L + ss - offset;
        return secs * 1_000_000L + frac;
    }

    private static int digits(String s, int from, int to) {
        if (to > s.length() || from >= to) throw bad("число", s);
        int v = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') throw bad("число", s);
            v = v * 10 + (c - '0');
        }
        return v;
    }

    /**
     * {@code (epoch(duration::interval)/60)::int}: интервал Postgres ({@code 02:05:00}, {@code 1 day 02:00:00},
     * {@code 1 mon}, {@code 1 year 2 mons}…) → секунды (месяц — 30 дней, год — 12 месяцев, как у DuckDB), {@code double}
     * / 60 → целое округлением к ближайшему, половина — к чётному (то же приведение, что у {@link #seconds}).
     */
    public static Integer durationMinutes(String interval) {
        if (interval == null) return null;
        String[] w = interval.strip().split("\\s+");
        long months = 0, days = 0, micros = 0;
        int i = 0;
        while (i < w.length && !w[i].contains(":")) {
            if (i + 1 >= w.length) throw bad("interval", interval);
            long v;
            try {
                v = Long.parseLong(w[i]);
            } catch (NumberFormatException e) {
                throw bad("interval", interval);
            }
            String unit = w[i + 1];
            switch (unit) {
                case "year", "years" -> months += 12 * v;
                case "mon", "mons" -> months += v;
                case "day", "days" -> days += v;
                default -> throw bad("interval", interval);
            }
            i += 2;
        }
        if (i < w.length) {
            if (i != w.length - 1) throw bad("interval", interval);
            String t = w[i];
            boolean neg = t.startsWith("-");
            if (neg || t.startsWith("+")) t = t.substring(1);
            String[] hms = t.split(":");
            if (hms.length != 3) throw bad("interval", interval);
            long h = Long.parseLong(hms[0]), m = Long.parseLong(hms[1]);
            String sec = hms[2];
            int dot = sec.indexOf('.');
            long sWhole = Long.parseLong(dot < 0 ? sec : sec.substring(0, dot));
            long f = 0;
            if (dot >= 0) {
                String fs = sec.substring(dot + 1);
                if (fs.isEmpty() || fs.length() > 6) throw bad("interval", interval);
                f = Long.parseLong(fs);
                for (int k = fs.length(); k < 6; k++) f *= 10;
            }
            long us = ((h * 60 + m) * 60 + sWhole) * 1_000_000L + f;
            micros += neg ? -us : us;
        }
        if (i == 0 && w.length == 0) throw bad("interval", interval);
        long total = months * 30L * 86_400_000_000L + days * 86_400_000_000L + micros;
        double seconds = (double) total / 1_000_000.0;
        double minutes = seconds / 60;
        return (int) Math.rint(minutes);
    }

    // ---------------------------------------------------------------- строки

    /** {@code trim(s, chars)} DuckDB: срезать с обоих концов любые символы из {@code chars}. */
    public static String trim(String s, String chars) {
        if (s == null) return null;
        int a = 0, b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    /** {@code split_part(s, sep, k)} DuckDB, {@code k} с 1: часть между разделителями; нет такой — пустая строка. */
    public static String splitPart(String s, char sep, int k) {
        if (s == null) return null;
        int from = 0;
        for (int part = 1; ; part++) {
            int to = s.indexOf(sep, from);
            if (part == k) return to < 0 ? s.substring(from) : s.substring(from, to);
            if (to < 0) return "";
            from = to + 1;
        }
    }

    /**
     * Граница периода {@code tstzrange}: {@code epoch(replace(split_part(trim(validity,'[)'),',',k),'"','')::timestamptz)::bigint},
     * {@code k} = 1 — начало, 2 — конец.
     */
    public static Long rangeBound(String range, int k) {
        if (range == null) return null;
        String b = splitPart(trim(range, "[)"), ',', k).replace("\"", "");
        if (b.isEmpty()) throw bad("граница tstzrange", range);
        return seconds(micros(b));
    }

    /** {@code split_part(trim(coordinates,'()'),',',k)} — долгота ({@code k} = 1) или широта (2), текстом как есть. */
    public static String pointPart(String point, int k) {
        return point == null ? null : splitPart(trim(point, "()"), ',', k);
    }

    /**
     * {@code json_extract_string(json, '$.key')}: строка — без кавычек и экранирования; число, логическое, объект,
     * массив — их текстом JSON; нет ключа или {@code null} — {@code null}. Только верхний уровень объекта.
     */
    public static String jsonString(String json, String key) {
        if (json == null) return null;
        int[] p = {0};
        skip(json, p);
        expect(json, p, '{');
        skip(json, p);
        if (peek(json, p) == '}') return null;
        String found = null;
        boolean hit = false;
        while (true) {
            skip(json, p);
            String k = string(json, p);
            skip(json, p);
            expect(json, p, ':');
            skip(json, p);
            int start = p[0];
            char c = peek(json, p);
            String v;
            boolean isNull = false;
            if (c == '"') {
                v = string(json, p);
            } else {
                skipValue(json, p);
                v = json.substring(start, p[0]).strip();
                isNull = v.equals("null");
            }
            if (k.equals(key)) {
                found = isNull ? null : v;
                hit = true;
            }
            skip(json, p);
            char d = peek(json, p);
            p[0]++;
            if (d == '}') break;
            if (d != ',') throw bad("json", json);
        }
        skip(json, p);
        if (p[0] != json.length()) throw bad("json", json);
        return hit ? found : null;
    }

    private static char peek(String s, int[] p) {
        if (p[0] >= s.length()) throw bad("json", s);
        return s.charAt(p[0]);
    }

    private static void skip(String s, int[] p) {
        while (p[0] < s.length() && " \t\n\r".indexOf(s.charAt(p[0])) >= 0) p[0]++;
    }

    private static void expect(String s, int[] p, char c) {
        if (peek(s, p) != c) throw bad("json", s);
        p[0]++;
    }

    private static String string(String s, int[] p) {
        expect(s, p, '"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek(s, p);
            p[0]++;
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = peek(s, p);
            p[0]++;
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (p[0] + 4 > s.length()) throw bad("json", s);
                    sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16));
                    p[0] += 4;
                }
                default -> throw bad("json", s);
            }
        }
    }

    private static void skipValue(String s, int[] p) {
        int depth = 0;
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]);
            if (c == '"') {
                string(s, p);
                continue;
            }
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                if (depth == 0) return;
                depth--;
            } else if (c == ',' && depth == 0) {
                return;
            }
            p[0]++;
        }
    }

    // ---------------------------------------------------------------- числа и логические «как есть»

    /**
     * Целое текстом как есть (в сборке — {@code all_varchar} без приведения или {@code ::int} канонического текста
     * Postgres): число, чья запись совпадает с текстом; иначе — ошибка, а не тихо другая запись в выпуске.
     */
    public static Integer intAsIs(String s) {
        if (s == null) return null;
        int v;
        try {
            v = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw bad("integer", s);
        }
        if (!Integer.toString(v).equals(s)) throw bad("integer (запись не каноническая)", s);
        return v;
    }

    /** Деньги текстом как есть: {@code numeric(…,2)} Postgres — ровно два знака после точки; иначе — ошибка. */
    public static BigDecimal moneyAsIs(String s) {
        if (s == null) return null;
        BigDecimal b;
        try {
            b = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw bad("numeric", s);
        }
        if (b.scale() != 2 || !b.toPlainString().equals(s)) throw bad("numeric(…,2)", s);
        return b;
    }

    /**
     * Десятичное текстом как есть для поля DECIMAL со шкалой {@code scale}: запись выпуска (без хвостовых нулей) должна
     * совпасть с текстом — иначе ошибка.
     */
    public static BigDecimal decimalAsIs(String s, int scale) {
        if (s == null) return null;
        BigDecimal b;
        try {
            b = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw bad("число", s);
        }
        if (b.scale() > scale || !io.hammerhall.data.core.io.RowFormat.trimZeros(
                b.setScale(scale, java.math.RoundingMode.UNNECESSARY).toPlainString()).equals(s)) {
            throw bad("число (запись выпуска не совпала бы с выгрузкой)", s);
        }
        return b;
    }

    /** Логическое COPY ({@code t} / {@code f}). */
    public static Boolean bool(String s) {
        if (s == null) return null;
        return switch (s) {
            case "t" -> Boolean.TRUE;
            case "f" -> Boolean.FALSE;
            default -> throw bad("boolean", s);
        };
    }

    /** Момент UTC → дата по смещению 0 (для журнала). */
    public static LocalDate utcDate(long epochSecond) {
        return java.time.LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC).toLocalDate();
    }

    private static IllegalArgumentException bad(String what, String value) {
        String v = value.length() > 80 ? value.substring(0, 80) + "…" : value;
        return new IllegalArgumentException("значение выгрузки «" + v + "» — не " + what);
    }
}
