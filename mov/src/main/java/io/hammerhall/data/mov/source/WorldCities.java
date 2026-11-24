package io.hammerhall.data.mov.source;

import io.hammerhall.data.core.geo.GeoMath;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Города набора «Мир» — второй вход сборки ({@code cities.tsv.gz} выпуска {@code wld-data-v1}): TSV с заголовком, как
 * его пишет ядро; нужны поля {@code id} (geonameid), {@code country_code} (ISO 3166-1 alpha-3), {@code latitude},
 * {@code longitude}, прочие не читаются. Для повторяемости — sha256 разжатого содержимого ({@code sha256_tsv}, как в
 * манифесте «Мира»). Ближайший город страны — {@link #nearest}: расстояние по большому кругу ({@link GeoMath#haversineKm},
 * радиус {@value GeoMath#EARTH_RADIUS_KM} км, {@link StrictMath}).
 */
public final class WorldCities {
    /** Имя файла входа (выпуск {@code wld-data-v1}). */
    public static final String FILE = "cities.tsv.gz";
    /**
     * Полоса широт для поиска ближайшего: расстояние по большому кругу не меньше дуги разности широт, так что город
     * дальше {@code km / (π R / 180)} градусов по широте не ближе {@code km} — с запасом.
     */
    static final double DEG_PER_KM = 180.0 / (StrictMath.PI * GeoMath.EARTH_RADIUS_KM);

    /** Города одной страны по возрастанию широты (затем id). */
    private record Country(int[] id, double[] lat, double[] lon) {
    }

    /** Ближайший город: id и расстояние, км (как посчитано, без округления). */
    public record Nearest(int id, double km) {
    }

    private final int[] ids;
    private final Map<String, Country> byCountry;
    private final String sha256Tsv;

    private WorldCities(int[] ids, Map<String, Country> byCountry, String sha256Tsv) {
        this.ids = ids;
        this.byCountry = byCountry;
        this.sha256Tsv = sha256Tsv;
    }

    /** Городов. */
    public int size() {
        return ids.length;
    }

    /** sha256 разжатого содержимого файла. */
    public String sha256Tsv() {
        return sha256Tsv;
    }

    /** Есть ли город с таким id. */
    public boolean has(int id) {
        return Arrays.binarySearch(ids, id) >= 0;
    }

    /**
     * Ближайший город страны {@code iso3} к точке, не дальше {@code maxKm}; при равных расстояниях — меньший id. Нет
     * такого (нет городов страны, все дальше) — {@code null}.
     */
    public Nearest nearest(String iso3, double lat, double lon, double maxKm) {
        Country c = iso3 == null ? null : byCountry.get(iso3);
        if (c == null) return null;
        double band = maxKm * DEG_PER_KM * 1.01 + 1e-9;
        int i = lowerBound(c.lat(), lat - band);
        int best = -1;
        double bestKm = 0;
        for (; i < c.lat().length && c.lat()[i] <= lat + band; i++) {
            double d = GeoMath.haversineKm(lat, lon, c.lat()[i], c.lon()[i]);
            if (d > maxKm) continue;
            if (best < 0 || d < bestKm || (d == bestKm && c.id()[i] < c.id()[best])) {
                best = i;
                bestKm = d;
            }
        }
        return best < 0 ? null : new Nearest(c.id()[best], bestKm);
    }

    private static int lowerBound(double[] a, double v) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Прочитать {@code cities.tsv.gz}: заголовок, строки через табуляцию; ошибка — с именем файла и номером строки. */
    public static WorldCities read(Path gz) throws IOException {
        if (!Files.isRegularFile(gz)) throw new IOException("нет городов «Мира» (" + FILE + " из wld-data-v1): " + gz);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String name = gz.getFileName().toString();
        Map<String, List<double[]>> acc = new HashMap<>();
        List<Integer> all = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(gz);
             DigestInputStream in = new DigestInputStream(new GZIPInputStream(raw, 1 << 16), md);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                     .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)), 1 << 16)) {
            String header = r.readLine();
            if (header == null) throw new IOException(name + ": нет заголовка");
            List<String> h = List.of(header.split("\t", -1));
            int cId = h.indexOf("id"), cCountry = h.indexOf("country_code"), cLat = h.indexOf("latitude"), cLon = h.indexOf("longitude");
            if (cId < 0 || cCountry < 0 || cLat < 0 || cLon < 0) {
                throw new IOException(name + ": в заголовке нужны id, country_code, latitude, longitude: " + h);
            }
            int line = 1;
            for (String s; (s = r.readLine()) != null; ) {
                line++;
                String[] f = s.split("\t", -1);
                if (f.length != h.size()) throw new IOException(name + ":" + line + ": полей " + f.length + ", в заголовке " + h.size());
                int id;
                double la, lo;
                try {
                    id = Integer.parseInt(f[cId]);
                    la = Double.parseDouble(f[cLat]);
                    lo = Double.parseDouble(f[cLon]);
                } catch (NumberFormatException e) {
                    throw new IOException(name + ":" + line + ": id, широта, долгота — числа: " + s);
                }
                all.add(id);
                acc.computeIfAbsent(f[cCountry], k -> new ArrayList<>()).add(new double[]{la, lo, id});
            }
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        int[] ids = all.stream().mapToInt(Integer::intValue).sorted().toArray();
        for (int i = 1; i < ids.length; i++) if (ids[i] == ids[i - 1]) throw new IOException(name + ": город дважды: " + ids[i]);
        Map<String, Country> byCountry = new HashMap<>();
        for (Map.Entry<String, List<double[]>> e : acc.entrySet()) {
            List<double[]> l = e.getValue();
            l.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[2], b[2]));
            int n = l.size();
            int[] id = new int[n];
            double[] la = new double[n], lo = new double[n];
            for (int i = 0; i < n; i++) {
                id[i] = (int) l.get(i)[2];
                la[i] = l.get(i)[0];
                lo[i] = l.get(i)[1];
            }
            byCountry.put(e.getKey(), new Country(id, la, lo));
        }
        return new WorldCities(ids, byCountry, HexFormat.of().formatHex(md.digest()));
    }
}
