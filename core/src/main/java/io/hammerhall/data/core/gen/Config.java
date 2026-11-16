package io.hammerhall.data.core.gen;

import io.hammerhall.data.core.time.Period;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Параметры прогона. Всё, от чего зависит содержимое выпуска, — здесь и попадает в манифест: {@code seed}, профиль,
 * период, версия генератора, хеши входов. Пути и уровень сжатия на содержимое TSV не влияют.
 *
 * @param seed             зерно случайности
 * @param profile          масштаб живого потока
 * @param period           окно данных и «сейчас»
 * @param out              папка выпуска
 * @param inputs           пути ко входам набора по имени входа ({@link Dataset#inputs()})
 * @param gzipLevel        уровень gzip (1–9; по умолчанию 6: на содержимое не влияет, по объёму +0,5 % к 9, втрое быстрее)
 * @param modules          какие модули гонять (пусто — все)
 * @param datasetVersion   имя выпуска, например {@code sto-data-v1}
 * @param generatorVersion версия генератора (смена логики → новая версия)
 */
public record Config(long seed, Profile profile, Period period, Path out, Map<String, Path> inputs, int gzipLevel,
                     List<String> modules, String datasetVersion, String generatorVersion) {
    public Config {
        modules = modules == null ? List.of() : List.copyOf(modules);
        inputs = inputs == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        if (gzipLevel < 1 || gzipLevel > 9) throw new IllegalArgumentException("gzip 1..9: " + gzipLevel);
    }

    public boolean wants(String module) {
        return modules.isEmpty() || modules.contains(module);
    }

    /** Путь ко входу по имени ({@code airports}, {@code dump}, …). */
    public Path input(String name) {
        Path p = inputs.get(name);
        if (p == null) throw new IllegalArgumentException("нет входа " + name + " (входы: " + inputs.keySet() + ")");
        return p;
    }
}
