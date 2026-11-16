package io.hammerhall.data.core.gen;

import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.time.Period;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Набор данных — контракт домена с ядром, один для всех наборов репозитория. Домен с кодом {@code <код>} — это папка
 * {@code <код>/} с исходниками в пакете {@code io.hammerhall.data.<код>}, где лежат:
 * <ul>
 *   <li>класс, реализующий этот интерфейс;</li>
 *   <li>{@code Main} — {@code public static void main(String[] a) { Cli.main(new XxxDataset(), a); }}: команды
 *       {@code generate}, {@code schema}, {@code verify}, {@code freeze}, {@code check}, {@code names} даёт
 *       {@link Cli};</li>
 *   <li>в тестах — наследник {@code DatasetContract} (общие тесты: паспорт, схема, выпуск без внутренних имён).</li>
 * </ul>
 * Рядом с исходниками — паспорт {@code <код>/dataset.properties} (его читают сборка и CI; тест контракта сверяет его с
 * этим классом), схема {@code <код>/schema/} и ожидания {@code <код>/expected/<версия>-<профиль>.json}.
 */
public interface Dataset {
    /** Код набора: папка в репозитории, последняя часть пакета, начало тега {@code <код>-data-vN}. */
    String code();

    /** Название набора для людей, например «Магазин «Кременъ»». */
    String title();

    /** Версия выпуска (она же тег): {@code <код>-data-vN}. */
    String version();

    /** Версия генератора: смена логики, меняющая содержимое, — новая версия. */
    String generatorVersion();

    long defaultSeed();

    /** Окно данных и «сейчас» выгрузки ({@code generate --to} укорачивает окно). */
    Period period();

    /** Описание схемы выпуска: каждая таблица — ровно одного модуля. */
    Schema schema();

    /** Все модули в порядке сборки. */
    List<Module> modules();

    /** Контрольные агрегаты для {@code freeze} и {@code check}: по умолчанию — объявленные модулями. */
    default List<Aggregate> aggregates() {
        List<Aggregate> out = new ArrayList<>();
        for (Module m : modules()) out.addAll(m.aggregates());
        return out;
    }

    /** SOURCE.md и LICENSE выпуска. */
    Release.Docs docs();

    /**
     * Входы набора — файлы, от которых зависит содержимое (выпуск другого набора, дамп). Путь к входу {@code имя}:
     * {@code --имя ПУТЬ} → {@code -D<код>.имя=ПУТЬ} → переменная {@code <КОД>_<ИМЯ>} → {@code <код>/work/<файл>}.
     */
    List<Input> inputs();

    /**
     * Прочитать и сверить входы с эталоном ({@link Input}): {@code strict} — расхождение — ошибка, иначе —
     * предупреждение. Возвращает их описание для поля {@code inputs} манифеста.
     */
    List<Map<String, Object>> readInputs(Config cfg, boolean strict) throws IOException;

    /** Поле {@code format} манифеста: как читать TSV выпуска. Домен дополняет его своим (например, деньгами). */
    default Map<String, Object> format() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("encoding", "UTF-8");
        f.put("delimiter", "TAB");
        f.put("header", true);
        f.put("null", "empty field");
        f.put("time", "Unix seconds, UTC");
        f.put("date", "ISO 8601, YYYY-MM-DD");
        f.put("boolean", "t / f");
        f.put("compression", "gzip, mtime=0");
        return f;
    }

    /**
     * Вход набора: как он называется в командной строке и паспорте ({@code name}), имя файла и эталонная контрольная
     * сумма — {@code sha256Tsv} (содержимого разжатого {@code .tsv.gz}) или {@code sha256} (файла как есть); вторая —
     * {@code null}. Те же значения — в паспорте: {@code input.<name>.file}, {@code input.<name>.sha256_tsv} /
     * {@code input.<name>.sha256}.
     */
    record Input(String name, String file, String sha256Tsv, String sha256) {
    }
}
