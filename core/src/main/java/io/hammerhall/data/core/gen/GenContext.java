package io.hammerhall.data.core.gen;

import io.hammerhall.data.core.check.Integrity;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.core.time.RuCalendar;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Всё, что модуль получает от ядра: параметры, календарь, потоки случайности, выдачу id, приёмники таблиц, общий мир
 * (модели предыдущих модулей), учёт заложенных нарушений.
 */
public final class GenContext {
    private final Config config;
    private final Schema schema;
    private final RuCalendar calendar = new RuCalendar();
    private final RandomStreams streams;
    private final Ids ids = new Ids();
    private final Planted planted = new Planted();
    private final Integrity integrity;
    private final Map<Key<?>, Object> world = new HashMap<>();
    private final Map<String, TableSink> sinks = new LinkedHashMap<>();
    private final Map<String, String> owner = new HashMap<>();
    private String currentModule;
    private final long t0 = System.nanoTime();

    GenContext(Config config, Schema schema) {
        this.config = config;
        this.schema = schema;
        this.streams = new RandomStreams(config.seed());
        this.integrity = new Integrity(schema, Integrity.Limits.fromSystem());
    }

    public Config config() {
        return config;
    }

    public Profile profile() {
        return config.profile();
    }

    public Period period() {
        return config.period();
    }

    public RuCalendar calendar() {
        return calendar;
    }

    public Schema schema() {
        return schema;
    }

    public RandomStreams streams() {
        return streams;
    }

    /** Поток случайности; имя — «модуль.смысл», например {@code catalog.prices}. */
    public RandomStreams.Stream stream(String name) {
        return streams.stream(name);
    }

    public Ids ids() {
        return ids;
    }

    public Planted planted() {
        return planted;
    }

    // ---------------------------------------------------------------- общий мир

    public <T> void put(Key<T> key, T value) {
        if (world.putIfAbsent(key, value) != null) throw new IllegalStateException("в мире уже есть " + key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        Object v = world.get(key);
        if (v == null) throw new IllegalStateException("в мире нет " + key + " — модуль-поставщик не запущен?");
        return (T) v;
    }

    public boolean has(Key<?> key) {
        return world.containsKey(key);
    }

    // ---------------------------------------------------------------- таблицы

    void enter(String module, List<String> tables) {
        currentModule = module;
        for (String t : tables) {
            String prev = owner.putIfAbsent(t, module);
            if (prev != null && !prev.equals(module)) {
                throw new IllegalStateException("таблицу " + t + " пишут два модуля: " + prev + " и " + module);
            }
        }
    }

    private TableSink open(String table, TableSink.Mode mode) throws IOException {
        if (!module().equals(owner.get(table))) {
            throw new IllegalStateException("модуль " + currentModule + " не объявил таблицу " + table);
        }
        if (sinks.containsKey(table)) throw new IllegalStateException("таблица " + table + " уже открыта");
        TableSink s = new TableSink(schema.table(table), mode, config.out(), config.gzipLevel(), integrity);
        sinks.put(table, s);
        return s;
    }

    private String module() {
        return currentModule;
    }

    /** Приёмник с сортировкой в памяти (справочники, таблицы до нескольких миллионов строк). */
    public TableSink sink(String table) throws IOException {
        return open(table, TableSink.Mode.SORT);
    }

    /** Приёмник потоком: строки — уже в порядке таблицы. */
    public TableSink streamSink(String table) throws IOException {
        return open(table, TableSink.Mode.STREAM);
    }

    /**
     * Закрыть свою таблицу раньше конца модуля (то же, что {@code sink.close()}): файл дописан, таблица запечатана в
     * проверках — отложенные ссылки на неё проверяются сейчас, новые — сразу, без накопления. Для таблиц, на которые
     * ссылаются следующие таблицы того же модуля (каталог до цен, склады до движений и т. п.).
     */
    public io.hammerhall.data.core.io.GzipTsvFile.Info close(String table) throws IOException {
        TableSink s = sinks.get(table);
        if (s == null) throw new IllegalStateException("таблица " + table + " не открыта");
        if (!module().equals(owner.get(table))) {
            throw new IllegalStateException("модуль " + currentModule + " не владеет таблицей " + table);
        }
        return s.close();
    }

    Map<String, TableSink> sinks() {
        return sinks;
    }

    Integrity integrity() {
        return integrity;
    }

    public void log(String msg) {
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf(java.util.Locale.ROOT, "[%6.1f s] %s%n", ms / 1000.0, msg);
    }
}
