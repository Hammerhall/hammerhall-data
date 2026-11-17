package io.hammerhall.data.air;

import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.io.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * С настоящей выгрузкой (её sha256 — в паспорте, {@link AirDataset#DUMP}) {@code small} уже показывает, что импорт
 * повторяет выпуск {@code air-data-v1}: справочники и рейсы в {@code small} — целиком, а счёт модели отбора
 * ({@code bookings_total}, {@code bookings_kept}) — по всей выгрузке. Эталон — манифест опубликованного выпуска.
 * С другой выгрузкой (крошечной, синтетической) сверять не с чем — тест ничего не проверяет и проходит (пропуск в CI
 * — провал, поэтому без допущений).
 */
class RealDumpTest {
    static {
        try {
            TinyDump.useIfNoDump();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** {@code air-data-v1}, опубликованный выпуск: строки и sha256_tsv таблиц, что в small — целиком. */
    static final Map<String, Object[]> RELEASE_V1 = new LinkedHashMap<>();

    static {
        RELEASE_V1.put("airports", new Object[]{5498L, "7473494a65e9af17527beebb9d8c632739b079c4b26eb377b65cd5bbde16f62e"});
        RELEASE_V1.put("airplanes", new Object[]{10L, "f916848f2178174824f55c06426aa2d2479efa9df24ffec57e2d807cd5e836bb"});
        RELEASE_V1.put("seats", new Object[]{1741L, "21e3481b3ace9301a6b390ff36b6e06e4adb64eee0c3713e056ad3ed93d28059"});
        RELEASE_V1.put("routes", new Object[]{7242L, "a4f851b459e0288f76b2eda7e8079ca2652b1f78c589db89df43ff7b1c24d28a"});
        RELEASE_V1.put("flights", new Object[]{135571L, "f411f4a4d1d2f8e9d7434f9b132cd1ebc8424c89337ae1970c1bebfa98c61d17"});
    }

    @Test
    @SuppressWarnings("unchecked")
    void smallFromTheRealDumpRepeatsReleaseV1() throws IOException {
        DatasetContract.Small s = DatasetContract.small(new AirDataset());
        Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(s.dir().resolve("manifest.json"),
                StandardCharsets.UTF_8));
        String sha = (String) ((List<Map<String, Object>>) m.get("inputs")).getFirst().get("sha256");
        if (AirDataset.DUMP.sha256() == null || AirDataset.DUMP.sha256().isEmpty() || !AirDataset.DUMP.sha256().equals(sha)) {
            System.out.println("RealDumpTest: выгрузка не та, что в паспорте (sha256 " + sha + ") — сверять с air-data-v1 не с чем");
            return;
        }
        assertEquals(9706657L, m.get("bookings_total"));
        assertEquals(8470438L, m.get("bookings_kept"));
        assertEquals("2027-08-31 23:59:58.579152+00", m.get("last_booking"));
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) m.get("files")) files.put((String) f.get("table"), f);
        for (Map.Entry<String, Object[]> e : RELEASE_V1.entrySet()) {
            assertEquals(e.getValue()[0], files.get(e.getKey()).get("rows"), e.getKey());
            assertEquals(e.getValue()[1], files.get(e.getKey()).get("sha256_tsv"), e.getKey());
        }
    }
}
