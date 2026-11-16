package io.hammerhall.data.core.gen;

import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;

import java.io.IOException;
import java.util.List;

/**
 * Модуль генератора: пишет свои таблицы и кладёт модель в общий мир для следующих модулей.
 *
 * <ol>
 *   <li>{@link #requires()} — модули, чьи модели нужны (их {@code Key} — в мире к началу {@link #generate});</li>
 *   <li>{@link #tables()} — таблицы, которые модуль пишет (каждая — ровно одним модулем; описание — в схеме домена);</li>
 *   <li>{@link #generate} — строки через {@code ctx.sink(...)} / {@code ctx.streamSink(...)}, модель — {@code ctx.put};</li>
 *   <li>{@link #check} — доменные инварианты своей части; ключи и ссылки проверяет ядро само.</li>
 * </ol>
 * Случайность — только из {@code ctx.stream("модуль.смысл").at(ключ сущности[, дата])}; никакого {@code new Random()},
 * {@code Math.random()}, порядка {@code HashMap} и системного времени.
 */
public interface Module {
    String name();

    default List<String> requires() {
        return List.of();
    }

    List<String> tables();

    void generate(GenContext ctx) throws IOException;

    default void check(GenContext ctx, CheckReport report) {
    }

    /**
     * Контрольные агрегаты своих таблиц для файла ожиданий выпуска ({@code freeze}) и проверки у пользователя
     * ({@code check}, {@code tools/check_data.py}): например, {@code Aggregate.sum("orders", "total")},
     * {@code Aggregate.countEq("orders", "status", "cancelled")}. Ядро добавляет к каждой таблице свои (суммы денежных
     * полей, число NULL необязательных). Агрегаты считаются по тексту готового выпуска, на генерацию не влияют.
     */
    default List<Aggregate> aggregates() {
        return List.of();
    }
}
