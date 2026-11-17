package io.hammerhall.data.air.dump;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Выгрузка одного прогона: путь и итог первого прохода ({@link DumpScan}). Первый проход делает
 * {@code Dataset.readInputs} — заодно считает и сверяет sha256 (тот же проход, без лишнего чтения файла); модули
 * берут готовое. Если прогон начат без {@code readInputs}, первый проход делает первый модуль.
 */
public final class DumpSource {
    private Path path;
    private DumpScan scan;

    /** Первый проход по выгрузке {@code file} (повторный вызов с тем же файлом — тот же итог). */
    public synchronized DumpScan scan(Path file) throws IOException {
        Path p = file.toAbsolutePath().normalize();
        if (scan != null && p.equals(path)) return scan;
        if (!Files.isRegularFile(p)) {
            throw new IOException("нет выгрузки " + p + " — путь: --dump ПУТЬ, -Dair.dump, AIR_DUMP или air/work/<файл>");
        }
        long t0 = System.nanoTime();
        System.out.println("выгрузка " + p + ": первый проход (sha256, справочники, ключи билетов и перелётов)…");
        DumpScan s = DumpScan.pass1(p);
        System.out.printf(Locale.ROOT, "выгрузка: %.1f с, %d МБ, строк по таблицам %s, полей с экранированием %s%n",
                (System.nanoTime() - t0) / 1e9, s.result.bytes() >> 20, s.result.rows(), s.result.escaped());
        path = p;
        scan = s;
        return s;
    }

    /** Итог первого прохода, если он был. */
    public synchronized DumpScan scanned() {
        return scan;
    }

    public synchronized Path path() {
        return path;
    }
}
