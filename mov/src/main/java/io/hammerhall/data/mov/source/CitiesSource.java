package io.hammerhall.data.mov.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Города «Мира» одного прогона: читаются один раз (сверка входов — {@code readInputs}, затем модуль), дальше — из
 * памяти. Другой путь или другое содержимое файла (sha256) — прочитать заново.
 */
public final class CitiesSource {
    private Path path;
    private String sha256;
    private WorldCities data;

    /** Прочитать города; тот же путь и та же сумма файла — уже прочитанные. */
    public synchronized WorldCities load(Path p) throws IOException {
        Path abs = p.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) throw new IOException("нет городов «Мира» (" + WorldCities.FILE + " из wld-data-v1): " + abs);
        String sum = Snapshot.sha256(abs);
        if (data == null || !abs.equals(path) || !sum.equals(sha256)) {
            data = null;
            WorldCities d = WorldCities.read(abs);
            path = abs;
            sha256 = sum;
            data = d;
        }
        return data;
    }

    /** Прочитанные города; не прочитаны — ошибка. */
    public synchronized WorldCities loaded() {
        if (data == null) throw new IllegalStateException("города «Мира» ещё не прочитаны");
        return data;
    }
}
