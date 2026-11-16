package io.hammerhall.data.core.geo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Справочник аэропортов {@code airports.tsv.gz} из выпуска {@code air-data-v1} (MIT © 2025 Postgres Professional):
 * {@code airport_code name_en name_ru city_en city_ru country_en country_ru longitude latitude timezone}.
 * Для повторяемости — sha256 разжатого содержимого (он же {@code sha256_tsv} в манифесте авиа).
 */
public final class Airports {
    /** sha256 содержимого {@code airports.tsv.gz} выпуска {@code air-data-v1} (из {@code check_air_data.py}). */
    public static final String AIR_DATA_V1_SHA256_TSV = "7473494a65e9af17527beebb9d8c632739b079c4b26eb377b65cd5bbde16f62e";

    public record Airport(String code, String nameEn, String nameRu, String cityEn, String cityRu, String countryEn,
                          String countryRu, double lon, double lat, String timezone) {
    }

    private final List<Airport> airports;
    private final String sha256Tsv;

    private Airports(List<Airport> airports, String sha256Tsv) {
        this.airports = airports;
        this.sha256Tsv = sha256Tsv;
    }

    public static Airports load(Path gz) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        List<Airport> out = new ArrayList<>();
        try (InputStream in = new DigestInputStream(new GZIPInputStream(Files.newInputStream(gz), 1 << 16), md);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = r.readLine();
            String expected = "airport_code\tname_en\tname_ru\tcity_en\tcity_ru\tcountry_en\tcountry_ru\tlongitude\tlatitude\ttimezone";
            if (!expected.equals(header)) throw new IOException(gz + ": заголовок не тот: " + header);
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length != 10) throw new IOException(gz + ": полей " + f.length + " в «" + line + "»");
                out.add(new Airport(f[0], f[1], f[2], f[3], f[4], f[5], f[6], Double.parseDouble(f[7]),
                        Double.parseDouble(f[8]), f[9]));
            }
            // дочитать хвост для хеша (readLine мог не дойти до конца буфера — дочитываем поток)
            while (in.read() >= 0) {
                // ничего
            }
        }
        return new Airports(List.copyOf(out), HexFormat.of().formatHex(md.digest()));
    }

    public List<Airport> all() {
        return airports;
    }

    public List<Airport> inCountry(String countryEn) {
        List<Airport> out = new ArrayList<>();
        for (Airport a : airports) if (a.countryEn().equals(countryEn)) out.add(a);
        return out;
    }

    public String sha256Tsv() {
        return sha256Tsv;
    }
}
