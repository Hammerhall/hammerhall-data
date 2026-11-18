package io.hammerhall.data.auc.house;

import io.hammerhall.data.auc.world.HouseRules;

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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Вход {@code cities.tsv.gz} выпуска {@code sto-data-v1} (MIT): города магазина «Кременъ» — те же {@code id}, чтобы
 * наборы соединялись по городу. Для повторяемости — sha256 разжатого содержимого ({@code sha256_tsv} манифеста
 * {@code sto-data-v1}). Поля {@code region_en} и {@code airport_code} входа в таблицу дома не идут.
 */
public final class CitiesInput {
    /** {@code sha256_tsv} таблицы {@code cities} в манифесте выпуска {@code sto-data-v1}. */
    public static final String STO_DATA_V1_SHA256_TSV = "d8bbfdf8b08a6df360922c55698e2789835b71f2d1da81c967c9ccd3a8931cee";
    static final String HEADER = "id\tname_ru\tname_en\tregion_ru\tregion_en\tfederal_district\tlat\tlon\ttimezone\tpopulation\tairport_code";

    private final List<HouseRules.City> cities;
    private final String sha256Tsv;

    private CitiesInput(List<HouseRules.City> cities, String sha256Tsv) {
        this.cities = cities;
        this.sha256Tsv = sha256Tsv;
    }

    public static CitiesInput load(Path gz) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        List<HouseRules.City> out = new ArrayList<>();
        try (InputStream in = new DigestInputStream(new GZIPInputStream(Files.newInputStream(gz), 1 << 16), md);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (!HEADER.equals(header)) throw new IOException(gz + ": заголовок не тот: " + header);
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length != 11) throw new IOException(gz + ": полей " + f.length + " в «" + line + "»");
                out.add(new HouseRules.City(Integer.parseInt(f[0]), f[1], f[2], f[3], f[5], Double.parseDouble(f[6]),
                        Double.parseDouble(f[7]), ZoneId.of(f[8]), Integer.parseInt(f[9])));
            }
            while (in.read() >= 0) {
                // дочитать поток до конца — для хеша
            }
        }
        return new CitiesInput(List.copyOf(out), HexFormat.of().formatHex(md.digest()));
    }

    public List<HouseRules.City> cities() {
        return cities;
    }

    public String sha256Tsv() {
        return sha256Tsv;
    }

    /** {@code id} города по русскому названию (для офисов). */
    public int idByNameRu(String nameRu) {
        for (HouseRules.City c : cities) if (c.nameRu().equals(nameRu)) return c.id();
        throw new IllegalArgumentException("во входе cities нет города " + nameRu);
    }
}
