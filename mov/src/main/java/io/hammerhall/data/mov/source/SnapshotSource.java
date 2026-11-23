package io.hammerhall.data.mov.source;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Снимок источников одного прогона: разбирается один раз (сверка входов — {@code readInputs}, затем модуль), дальше —
 * из памяти. Другой путь или другое содержимое файла (sha256) — разобрать заново.
 */
public final class SnapshotSource {
    private Path path;
    private String sha256;
    private SourceData data;

    /** Прочитать снимок и его sha256; тот же путь и та же сумма — уже разобранный. */
    public synchronized SourceData load(Path p) throws IOException {
        Path abs = p.toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(abs)) throw new IOException("нет снимка источников: " + abs);
        String sum = Snapshot.sha256(abs);
        if (data == null || !abs.equals(path) || !sum.equals(sha256)) {
            data = null; // старый снимок не держим, пока читаем новый
            SourceData d = SourceData.read(abs);
            path = abs;
            sha256 = sum;
            data = d;
        }
        return data;
    }

    /** Прочитанный снимок; не прочитан — ошибка. */
    public synchronized SourceData loaded() {
        if (data == null) throw new IllegalStateException("снимок источников ещё не прочитан");
        return data;
    }

    public synchronized boolean isLoaded() {
        return data != null;
    }

    /** sha256 прочитанного файла снимка. */
    public synchronized String sha256() {
        loaded();
        return sha256;
    }
}
