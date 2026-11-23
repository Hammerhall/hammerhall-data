package io.hammerhall.data.wld.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Файлы GeoNames ({@code https://download.geonames.org/export/dump/}, readme.txt там же): табуляция, UTF-8, строки с
 * {@code #} — комментарии. Значения не меняются: текст — как есть, числа разбираются, где нужны числа.
 */
public final class GeoNames {
    private GeoNames() {
    }

    /**
     * Строка {@code countryInfo.txt}.
     *
     * @param area       {@code Area(in sq km)} текстом, как в файле ({@code 1.71E7} тоже бывает); пусто — {@code ""}
     * @param population {@code Population} текстом
     * @param neighbours {@code neighbours}: ISO2 через запятую
     */
    public record Country(String iso2, String iso3, String name, String capital, String area, String population,
                          String continent, List<String> neighbours) {
    }

    /** Строка {@code cities500.txt} или {@code <CC>.txt} (формат один — таблица {@code geoname} из readme.txt). */
    public record Place(long id, String name, String asciiName, String alternateNames, String featureClass,
                        String featureCode, String country, String admin1, long population, String latitude,
                        String longitude, String timezone) {
    }

    static String[] fields(String line, int min, String file, int n) throws IOException {
        String[] f = line.split("\t", -1);
        if (f.length < min) throw new IOException(file + ":" + n + ": полей " + f.length + ", нужно не меньше " + min);
        return f;
    }

    /** {@code countryInfo.txt}: строки данных по порядку файла. */
    public static List<Country> countries(BufferedReader r, String file) throws IOException {
        List<Country> out = new ArrayList<>();
        int n = 0;
        for (String line; (line = r.readLine()) != null; ) {
            n++;
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = fields(line, 18, file, n);
            List<String> nb = new ArrayList<>();
            for (String s : f[17].split(",")) if (!s.isBlank()) nb.add(s.strip());
            out.add(new Country(f[0], f[1], f[4], f[5], f[6], f[7], f[8], List.copyOf(nb)));
        }
        return out;
    }

    /** {@code admin1CodesASCII.txt}: {@code CC.ADM1} → {@code name}. */
    public static Map<String, String> admin1(BufferedReader r, String file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        int n = 0;
        for (String line; (line = r.readLine()) != null; ) {
            n++;
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = fields(line, 2, file, n);
            out.putIfAbsent(f[0], f[1]);
        }
        return out;
    }

    /** Строки таблицы {@code geoname}: каждая — читателю, по порядку файла. */
    public static void places(BufferedReader r, String file, java.util.function.Consumer<Place> sink) throws IOException {
        int n = 0;
        for (String line; (line = r.readLine()) != null; ) {
            n++;
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = fields(line, 18, file, n);
            long id, pop;
            try {
                id = Long.parseLong(f[0]);
                pop = f[14].isEmpty() ? 0 : Long.parseLong(f[14]);
            } catch (NumberFormatException e) {
                throw new IOException(file + ":" + n + ": geonameid или население — не число: " + f[0] + ", " + f[14], e);
            }
            sink.accept(new Place(id, f[1], f[2], f[3], f[6], f[7], f[8], f[10], pop, f[4], f[5], f[17]));
        }
    }
}
