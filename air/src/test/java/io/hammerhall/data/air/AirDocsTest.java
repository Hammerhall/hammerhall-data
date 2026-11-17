package io.hammerhall.data.air;

import io.hammerhall.data.air.load.NetworkModule;
import io.hammerhall.data.core.gen.DatasetContract;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Тексты выпуска: LICENSE — побайтно как у опубликованного air-data-v1; SOURCE.md — без списка исключённых аэропортов. */
class AirDocsTest {
    static {
        try {
            TinyDump.useIfNoDump();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** sha256 файла LICENSE выпуска air-data-v1 (поле texts его ожиданий). */
    static final String LICENSE_V1 = "2fd240f31aa9f8f52310195ebe7f271cd8925753ed75551a2cda34677854f298";

    @Test
    void licenseIsTheReleaseOne() throws Exception {
        byte[] b = new AirDocs().license().getBytes(StandardCharsets.UTF_8);
        assertEquals(LICENSE_V1, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)));
    }

    @Test
    void sourceMdIsNeutralAboutExcludedAirports() throws IOException {
        String md = Files.readString(DatasetContract.small(new AirDataset()).dir().resolve("SOURCE.md"), StandardCharsets.UTF_8);
        assertTrue(md.contains("без нескольких аэропортов источника; в сеть маршрутов они не входят"), md);
        for (String code : NetworkModule.EXCLUDED_AIRPORTS) assertTrue(!md.contains(code), code);
        assertTrue(md.contains("округлены до ближайшей секунды"), "время — как на самом деле");
        assertTrue(md.contains("## Профиль `small`"), "у small — абзац о выборке");
    }
}
