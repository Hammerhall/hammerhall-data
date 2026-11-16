package io.hammerhall.data.core.check;

import io.hammerhall.data.core.io.RowFormat;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.schema.ColType;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Проверки по описанию схемы, на лету при записи: у каждой таблицы первичный ключ и ограничения UNIQUE соблюдены,
 * каждая ссылка находит строку.
 *
 * <p><b>Значение ключа</b> приводится к 64 битам: одиночное целое — как есть; два целых из [0, 2³²) — упаковкой без
 * потерь; текстовый и прочий составной — хешем (FNV-1a + SplitMix): ложные «нашлось» и «повтор» при хеше
 * практически исключены, ложного «не нашлось» нет.
 *
 * <p><b>Уникальность</b> — по порядку строк таблицы ({@link Table#orderBy()}), экономно по памяти:
 * <ul>
 *   <li>порядок начинается со всех полей ключа — повтор бывает только у соседей: сравнение с предыдущей строкой;</li>
 *   <li>порядок начинается с части полей ключа — повторы только внутри группы равных начальных полей: ключи группы
 *       сортируются при её смене;</li>
 *   <li>иначе — одиночный целый id битовой картой ({@link LongKeys}), прочее — внешней сортировкой ({@link LongRuns}:
 *       8 байт на ключ, отрезки — на диск);</li>
 *   <li>на таблицу ссылаются — уникальность попутно даёт множество ключей для ссылок ({@link KeyStore}).</li>
 * </ul>
 * Строка с NULL в поле UNIQUE в проверке не участвует — как в Postgres.
 *
 * <p><b>Ссылки</b> — потоково, где можно:
 * <ul>
 *   <li>целевая таблица уже записана — проверка сразу;</li>
 *   <li>целевая таблица пишется сейчас в порядке первого поля своего ключа (сутки за сутками, id по возрастанию), и
 *       ссылка — на уже пройденный ключ (одиночный — не больше последнего записанного; составной — первое поле
 *       меньше текущего) — сразу;</li>
 *   <li>иначе ссылка откладывается: в памяти — до {@link Limits#spillAt()} на внешний ключ, по мере продвижения цели
 *       отложенные дочищаются; сверх — на диск ({@link LongSpill}); проверяются, когда цель закроется.</li>
 * </ul>
 * Модуль может закрыть таблицу раньше конца своей работы ({@code ctx.close(таблица)} или {@code TableSink.close()}) —
 * ссылки на неё дальше проверяются сразу. Если известен план прогона ({@link #plan}), ссылки на таблицы вне плана не
 * копятся («не проверено»), а множество ключей таблицы освобождается, как только закрыты она и все таблицы плана,
 * которые на неё ссылаются.
 *
 * <p>Строки таблицы приходят в порядке {@code orderBy} — его держит {@code TableSink}; строка не по порядку первого
 * поля ключа — нарушение (иначе потоковая проверка ссылок была бы неверна).
 */
public final class Integrity implements AutoCloseable {

    /**
     * Пределы памяти проверок.
     *
     * @param drainAt начальная ёмкость отложенных ссылок одного внешнего ключа
     * @param spillAt сколько отложенных ссылок одного внешнего ключа держать в памяти, прежде чем сбросить на диск
     * @param runSize длина отрезка внешней сортировки ключей (ключей в памяти на одну проверку уникальности)
     * @param tmpDir  папка временных файлов; {@code null} — системная
     */
    public record Limits(int drainAt, int spillAt, int runSize, Path tmpDir) {
        /** 64 тыс. → 4 млн отложенных ссылок (≤ 64 МБ на внешний ключ), отрезок — 4 млн ключей (32 МБ). */
        public static final Limits DEFAULT = new Limits(1 << 16, 1 << 22, 1 << 22, null);

        public Limits {
            if (drainAt < 1 || spillAt < drainAt || runSize < 2) {
                throw new IllegalArgumentException("пределы: " + drainAt + ", " + spillAt + ", " + runSize);
            }
        }

        /** {@link #DEFAULT}; папка временных файлов — из {@code -Ddata.tmpdir}, если задана. */
        public static Limits fromSystem() {
            String d = System.getProperty("data.tmpdir");
            return d == null || d.isBlank() ? DEFAULT : new Limits(DEFAULT.drainAt, DEFAULT.spillAt, DEFAULT.runSize, Path.of(d));
        }
    }

    private static final long[] EMPTY = new long[0];

    private final Schema schema;
    private final Limits limits;
    private final Map<String, Set<String>> referencedBy = new LinkedHashMap<>();
    private Set<String> planned;
    private final Map<String, TableState> states = new LinkedHashMap<>();
    private final Set<String> sealed = new HashSet<>();
    private final Map<String, List<String>> violations = new LinkedHashMap<>();
    private final Map<String, Long> violationCount = new LinkedHashMap<>();
    private long immediate, deferred, pendingNow, peakPending, spilled, peakStoreBytes;

    public Integrity(Schema schema) {
        this(schema, Limits.DEFAULT);
    }

    public Integrity(Schema schema, Limits limits) {
        this.schema = schema;
        this.limits = limits;
        for (Table t : schema.tables()) {
            for (Table.ForeignKey f : t.foreignKeys(schema)) {
                referencedBy.computeIfAbsent(f.refTable(), k -> new LinkedHashSet<>()).add(t.name());
            }
        }
    }

    /**
     * План прогона: какие таблицы будут записаны. Ссылки на таблицы вне плана не копятся, ключи освобождаются
     * раньше. Без плана — всё копится до конца (как будто записано будет всё).
     */
    public synchronized void plan(Collection<String> tables) {
        planned = new HashSet<>(tables);
    }

    // ---------------------------------------------------------------- значение ключа

    private static boolean ordinal(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte
                || v instanceof Instant || v instanceof LocalDate;
    }

    private static long num(Object v) {
        if (v instanceof Instant i) return i.getEpochSecond();
        if (v instanceof LocalDate d) return d.toEpochDay();
        return ((Number) v).longValue();
    }

    private static long part(Object v) {
        if (ordinal(v)) return num(v);
        if (v instanceof String s) return RandomStreams.fnv64(s);
        if (v instanceof BigDecimal b) return RandomStreams.fnv64("decimal:" + b.stripTrailingZeros().toPlainString());
        if (v instanceof Double d) return Double.doubleToLongBits(d == 0.0 ? 0.0 : d) ^ 0x6a09e667f3bcc909L;
        if (v instanceof Boolean b) return b ? 1 : 0;
        return RandomStreams.fnv64(v.getClass().getName() + ":" + v);
    }

    /** Ключ по полям строки (см. описание класса). */
    static long key(Object[] row, int[] cols) {
        if (cols.length == 1) {
            Object v = row[cols[0]];
            return ordinal(v) ? num(v) : Rng.mix64(part(v));
        }
        if (cols.length == 2 && ordinal(row[cols[0]]) && ordinal(row[cols[1]])) {
            long a = num(row[cols[0]]), b = num(row[cols[1]]);
            if (a >= 0 && a <= 0xffffffffL && b >= 0 && b <= 0xffffffffL) return (a << 32) | b;
        }
        long h = 0x243f6a8885a308d3L;
        for (int i = 0; i < cols.length; i++) h = Rng.mix64(h ^ Rng.mix64(part(row[cols[i]]) + 0x9e3779b97f4a7c15L * (i + 1)));
        return h;
    }

    private static int[] idx(Table t, List<String> cols) {
        int[] c = new int[cols.size()];
        for (int i = 0; i < c.length; i++) c[i] = t.indexOf(cols.get(i));
        return c;
    }

    private static boolean orderedType(ColType c) {
        return c.integral() || c == ColType.MOMENT || c == ColType.DATE;
    }

    /** Строки таблицы идут по возрастанию первого поля ключа — по нему держится потоковая проверка ссылок. */
    private static boolean watermarked(Table t) {
        String first = t.primaryKey().getFirst();
        return t.orderBy().getFirst().equals(first) && orderedType(t.column(first).type());
    }

    private static String describe(Object[] row, int[] cols) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(row[cols[i]]);
        }
        return sb.append(')').toString();
    }

    private static Object[] values(Object[] row, int[] cols) {
        Object[] v = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) v[i] = row[cols[i]];
        return v;
    }

    private static boolean same(Object[] prev, Object[] row, int[] cols) {
        for (int i = 0; i < cols.length; i++) if (RowFormat.compareValues(prev[i], row[cols[i]]) != 0) return false;
        return true;
    }

    private static boolean anyNull(Object[] row, int[] cols) {
        for (int c : cols) if (row[c] == null) return true;
        return false;
    }

    // ---------------------------------------------------------------- уникальность

    /** Проверка уникальности одного набора полей. */
    private abstract static class Uniq {
        final String check;
        final int[] cols;
        final boolean skipNull;

        Uniq(String check, int[] cols, boolean skipNull) {
            this.check = check;
            this.cols = cols;
            this.skipNull = skipNull;
        }

        final void add(Object[] row, Integrity ig) {
            if (skipNull && anyNull(row, cols)) return;
            accept(row, ig);
        }

        abstract void accept(Object[] row, Integrity ig);

        void finish(Integrity ig) {
        }

        void close() {
        }
    }

    /** Порядок строк начинается со всех полей — сравнение с соседом. */
    private static final class MonoUniq extends Uniq {
        private Object[] prev;

        MonoUniq(String check, int[] cols, boolean skipNull) {
            super(check, cols, skipNull);
        }

        @Override
        void accept(Object[] row, Integrity ig) {
            if (prev != null && same(prev, row, cols)) ig.violation(check, "повтор ключа " + describe(row, cols));
            prev = values(row, cols);
        }
    }

    /** Порядок начинается с части полей — повторы внутри группы равных начальных полей. */
    private static final class GroupUniq extends Uniq {
        private final int[] groupCols;
        private Object[] group;
        private long[] keys = new long[64];
        private int n;

        GroupUniq(String check, int[] cols, int[] groupCols, boolean skipNull) {
            super(check, cols, skipNull);
            this.groupCols = groupCols;
        }

        @Override
        void accept(Object[] row, Integrity ig) {
            if (group == null || !same(group, row, groupCols)) {
                flush(ig);
                group = values(row, groupCols);
            }
            if (n == keys.length) keys = Arrays.copyOf(keys, n * 2);
            keys[n++] = key(row, cols);
        }

        private void flush(Integrity ig) {
            if (n > 1) {
                Arrays.sort(keys, 0, n);
                for (int i = 1; i < n; i++) {
                    if (keys[i] == keys[i - 1]) ig.violation(check, "повтор ключа в группе " + Arrays.toString(group));
                }
            }
            n = 0;
            if (keys.length > 1 << 16) keys = new long[64];
        }

        @Override
        void finish(Integrity ig) {
            flush(ig);
        }
    }

    /** Порядок строк с ключом не связан — множество целых id или внешняя сортировка хешей. */
    private static final class SetUniq extends Uniq {
        private final LongKeys ids;
        private final LongRuns runs;

        SetUniq(String check, int[] cols, boolean skipNull, LongKeys ids, LongRuns runs) {
            super(check, cols, skipNull);
            this.ids = ids;
            this.runs = runs;
        }

        @Override
        void accept(Object[] row, Integrity ig) {
            long k = key(row, cols);
            if (ids != null) {
                if (!ids.add(k)) ig.violation(check, "повтор ключа " + describe(row, cols));
            } else {
                runs.add(k);
            }
        }

        @Override
        void finish(Integrity ig) {
            if (runs == null) return;
            runs.duplicates(h -> ig.violation(check, "повтор ключа (хеш " + Long.toHexString(h) + ")"));
            runs.close();
        }

        @Override
        void close() {
            if (runs != null) runs.close();
        }
    }

    private Uniq uniq(String check, Table t, List<String> cols, boolean skipNull) {
        int[] c = idx(t, cols);
        List<String> order = t.orderBy();
        int k = 0;
        while (k < order.size() && cols.contains(order.get(k))) k++;
        if (k >= cols.size()) return new MonoUniq(check, c, skipNull);
        if (k > 0) return new GroupUniq(check, c, idx(t, order.subList(0, k)), skipNull);
        if (c.length == 1 && t.column(cols.getFirst()).type().integral()) return new SetUniq(check, c, skipNull, new LongKeys(), null);
        return new SetUniq(check, c, skipNull, null, new LongRuns(limits.runSize(), limits.tmpDir()));
    }

    // ---------------------------------------------------------------- состояние таблиц и ссылок

    private static final class TableState {
        final Table table;
        final int[] pkCols;
        final boolean watermarked;
        KeyStore store;
        Uniq pkUniq;
        final List<Uniq> uniques = new ArrayList<>();
        final List<FkState> fks = new ArrayList<>();
        boolean any, orderBroken;
        long last;

        TableState(Table table) {
            this.table = table;
            this.pkCols = idx(table, table.primaryKey());
            this.watermarked = watermarked(table);
        }
    }

    private static final class FkState {
        final Table table;
        final Table.ForeignKey fk;
        final int[] cols;
        final String target;
        final boolean single;
        final boolean watermarked;
        long checked;
        long[] keys = EMPTY, firsts = EMPTY;
        int n;
        LongSpill spill;

        FkState(Table table, Table.ForeignKey fk, boolean targetWatermarked) {
            this.table = table;
            this.fk = fk;
            this.cols = idx(table, fk.columns());
            this.target = fk.refTable();
            this.single = cols.length == 1;
            this.watermarked = targetWatermarked;
        }

        String name() {
            return table.name() + "(" + String.join(",", fk.columns()) + ") → " + fk.refTable();
        }
    }

    // ---------------------------------------------------------------- поток строк

    /** Таблица начата: готовим её ключи, проверки уникальности и ссылки. */
    public synchronized void begin(Table t) {
        if (states.containsKey(t.name())) throw new IllegalStateException("таблица " + t + " уже начата");
        if (planned != null) planned.add(t.name());
        TableState s = new TableState(t);
        boolean needKeys = false;
        for (String r : referencedBy.getOrDefault(t.name(), Set.of())) needKeys |= planned == null || planned.contains(r);
        if (needKeys) {
            boolean id = s.pkCols.length == 1 && t.column(t.primaryKey().getFirst()).type().integral();
            s.store = new KeyStore(!id);
        } else {
            s.pkUniq = uniq("ключ " + t.name(), t, t.primaryKey(), false);
        }
        for (List<String> u : t.uniques()) {
            s.uniques.add(uniq("уникальность " + t.name() + "(" + String.join(", ", u) + ")", t, u, true));
        }
        for (Table.ForeignKey f : t.foreignKeys(schema)) s.fks.add(new FkState(t, f, watermarked(schema.table(f.refTable()))));
        states.put(t.name(), s);
    }

    /** Строка записана: ключ и уникальность — учесть, ссылки — проверить или отложить. */
    public synchronized void row(Table t, Object[] row) {
        TableState s = states.get(t.name());
        if (s == null) throw new IllegalStateException("строка таблицы " + t + " до begin");
        if (s.watermarked) {
            long first = num(row[s.pkCols[0]]);
            if (s.any && first < s.last) {
                s.orderBroken = true;
                violation("ключ " + t.name(), "строка не по порядку " + t.orderBy() + ": " + describe(row, s.pkCols));
            }
            if (!s.any || first > s.last) s.last = first;
            s.any = true;
        }
        if (s.store != null) {
            if (!s.store.add(key(row, s.pkCols))) violation("ключ " + t.name(), "повтор ключа " + describe(row, s.pkCols));
        } else {
            s.pkUniq.add(row, this);
        }
        for (Uniq u : s.uniques) u.add(row, this);
        for (FkState f : s.fks) reference(f, row);
    }

    private void reference(FkState f, Object[] row) {
        if (anyNull(row, f.cols)) return;
        long k = key(row, f.cols);
        TableState ts = states.get(f.target);
        if (sealed.contains(f.target)) {
            immediate++;
            check(f, ts, k, row);
            return;
        }
        if (planned != null && !planned.contains(f.target)) return;
        long first = 0;
        if (f.watermarked) {
            first = num(row[f.cols[0]]);
            if (passed(f, ts, first)) {
                immediate++;
                check(f, ts, k, row);
                return;
            }
        }
        pend(f, k, first);
    }

    private static boolean passed(FkState f, TableState ts, long first) {
        if (ts == null || !ts.any || ts.orderBroken) return false;
        return f.single ? first <= ts.last : first < ts.last;
    }

    private void check(FkState f, TableState ts, long k, Object[] row) {
        f.checked++;
        if (ts.store == null) {
            violation(f.name(), "ключи " + f.target + " уже освобождены: таблицы " + f.table + " не было в плане прогона");
        } else if (!ts.store.contains(k)) {
            violation(f.name(), "нет строки для " + (row != null ? describe(row, f.cols) : "ключа " + k));
        }
    }

    private void pend(FkState f, long k, long first) {
        if (f.n == f.keys.length) {
            if (f.watermarked) drain(f);
            if (f.keys.length == 0 || f.n > f.keys.length / 2) {
                if (f.keys.length >= limits.spillAt()) spill(f);
                else grow(f);
            }
        }
        f.keys[f.n] = k;
        if (f.watermarked) f.firsts[f.n] = first;
        f.n++;
        deferred++;
        if (++pendingNow > peakPending) peakPending = pendingNow;
    }

    private void grow(FkState f) {
        int len = f.keys.length == 0 ? limits.drainAt() : (int) Math.min(limits.spillAt(), 2L * f.keys.length);
        f.keys = Arrays.copyOf(f.keys, len);
        if (f.watermarked) f.firsts = Arrays.copyOf(f.firsts, len);
    }

    /** Дочистить отложенные ссылки, которые цель уже прошла. */
    private void drain(FkState f) {
        TableState ts = states.get(f.target);
        if (ts == null || !ts.any || ts.orderBroken) return;
        int w = 0;
        for (int i = 0; i < f.n; i++) {
            if (passed(f, ts, f.firsts[i])) {
                check(f, ts, f.keys[i], null);
            } else {
                f.keys[w] = f.keys[i];
                f.firsts[w] = f.firsts[i];
                w++;
            }
        }
        pendingNow -= f.n - w;
        f.n = w;
    }

    private void spill(FkState f) {
        if (f.spill == null) f.spill = new LongSpill(limits.tmpDir());
        f.spill.append(f.keys, 0, f.n);
        spilled += f.n;
        pendingNow -= f.n;
        f.n = 0;
    }

    void violation(String check, String what) {
        violationCount.merge(check, 1L, Long::sum);
        List<String> v = violations.computeIfAbsent(check, k -> new ArrayList<>());
        if (v.size() < 20) v.add(what);
    }

    /** Таблица записана целиком: уникальность — досчитать, отложенные ссылки на неё — проверить. */
    public synchronized void end(Table t) {
        TableState s = states.get(t.name());
        if (s == null) throw new IllegalStateException("таблица " + t + " не начата");
        if (sealed.contains(t.name())) return;
        if (s.pkUniq != null) s.pkUniq.finish(this);
        for (Uniq u : s.uniques) u.finish(this);
        sealed.add(t.name());
        for (TableState o : states.values()) {
            for (FkState f : o.fks) {
                if (!f.target.equals(t.name())) continue;
                for (int i = 0; i < f.n; i++) check(f, s, f.keys[i], null);
                pendingNow -= f.n;
                f.n = 0;
                f.keys = EMPTY;
                f.firsts = EMPTY;
                if (f.spill != null) {
                    f.spill.drain(k -> check(f, s, k, null));
                    f.spill = null;
                }
            }
        }
        long bytes = 0;
        for (TableState o : states.values()) if (o.store != null) bytes += o.store.bytes();
        peakStoreBytes = Math.max(peakStoreBytes, bytes);
        release();
    }

    /** Освободить ключи таблиц, на которые больше никто из плана не сошлётся. */
    private void release() {
        if (planned == null) return;
        for (TableState ts : states.values()) {
            if (ts.store == null || !sealed.contains(ts.table.name())) continue;
            boolean needed = false;
            for (String r : referencedBy.getOrDefault(ts.table.name(), Set.of())) needed |= planned.contains(r) && !sealed.contains(r);
            if (!needed) ts.store = null;
        }
    }

    /** Повтор ключа, найденный модулем или приёмником сам (сохранено для совместимости). */
    public synchronized void duplicate(Table t, String what) {
        violation("ключ " + t.name(), "повтор ключа " + what);
    }

    private void expect(CheckReport r, String name, String bucket, String okDetail) {
        r.expectNone(name, violationCount.getOrDefault(bucket, 0L), violations.getOrDefault(bucket, List.of()), okDetail);
    }

    /** Итог: ключи, уникальность и ссылки по всем записанным таблицам. */
    public synchronized void report(CheckReport r) {
        for (TableState s : states.values()) {
            String t = s.table.name();
            expect(r, "ключ " + t, "ключ " + t, "первичный ключ уникален");
            for (Uniq u : s.uniques) expect(r, u.check, u.check, "соблюдена");
            for (FkState f : s.fks) {
                if (!sealed.contains(f.target)) {
                    r.ok("ссылка " + f.name(), "не проверено — таблицы " + f.target + " нет в прогоне");
                } else {
                    expect(r, "ссылка " + f.name(), f.name(), "проверено " + f.checked + " ссылок");
                }
            }
        }
    }

    /** Сводка расхода памяти проверок — для журнала сборки. */
    public synchronized String stats() {
        return "ссылок проверено сразу " + immediate + ", отложено " + deferred + " (в памяти одновременно — максимум "
                + peakPending + ", сброшено на диск " + spilled + "); ключи для ссылок — максимум ≈ "
                + (peakStoreBytes + (1 << 19) >> 20) + " МБ";
    }

    /** Сколько ссылок проверено сразу и сколько отложено (для тестов и журнала). */
    public synchronized long[] counters() {
        return new long[]{immediate, deferred, peakPending, spilled};
    }

    /** Освободить память и временные файлы (после {@link #report}). */
    @Override
    public synchronized void close() {
        for (TableState s : states.values()) {
            if (s.pkUniq != null) s.pkUniq.close();
            for (Uniq u : s.uniques) u.close();
            for (FkState f : s.fks) {
                if (f.spill != null) f.spill.close();
                f.spill = null;
                f.keys = EMPTY;
                f.firsts = EMPTY;
                f.n = 0;
            }
            s.store = null;
        }
    }
}
