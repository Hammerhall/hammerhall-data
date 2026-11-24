package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.random.Rng;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Города зрителей — справочник генератора {@code cities.tsv} ({@code id} = geonameid города World, код страны, жители,
 * пояс города из World): российские города от 10 000 жителей и крупные города ряда стран. Страна зрителя — по весам
 * {@link SiteRules}, город в стране — по числу жителей; суточный ход просмотров — по поясу города.
 */
final class Cities {
    static final String RESOURCE = "cities.tsv";

    final int[] id;
    final int[] country;
    final long[] population;
    /** Пояс города — индекс в {@link #zones} (разные пояса справочника по порядку появления). */
    final int[] zone;
    final java.time.ZoneId[] zones;
    /** Города страны (индексы) и накопленное население — для выбора по жителям. */
    private final int[][] byCountry;
    private final double[][] cum;
    private final double[] countryCum;

    private Cities(int[] id, int[] country, long[] population, int[] zone, java.time.ZoneId[] zones) {
        this.id = id;
        this.country = country;
        this.population = population;
        this.zone = zone;
        this.zones = zones;
        int nc = SiteRules.COUNTRIES.length;
        List<List<Integer>> lists = new ArrayList<>();
        for (int c = 0; c < nc; c++) lists.add(new ArrayList<>());
        for (int i = 0; i < id.length; i++) lists.get(country[i]).add(i);
        byCountry = new int[nc][];
        cum = new double[nc][];
        countryCum = new double[nc];
        double cw = 0;
        for (int c = 0; c < nc; c++) {
            List<Integer> l = lists.get(c);
            byCountry[c] = l.stream().mapToInt(Integer::intValue).toArray();
            cum[c] = new double[l.size()];
            double s = 0;
            for (int k = 0; k < l.size(); k++) cum[c][k] = s += population[l.get(k)];
            if (!l.isEmpty()) cw += SiteRules.COUNTRY_WEIGHTS[c];
            countryCum[c] = cw;
        }
    }

    static Cities load() {
        try (InputStream in = Cities.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("нет ресурса " + RESOURCE);
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Cities parse(BufferedReader r) throws IOException {
        String head = r.readLine();
        if (!"id\tcountry_code\tpopulation\ttimezone".equals(head)) throw new IllegalStateException(RESOURCE + ": заголовок " + head);
        List<int[]> rows = new ArrayList<>();
        List<Long> pop = new ArrayList<>();
        List<java.time.ZoneId> zones = new ArrayList<>();
        java.util.Map<String, Integer> zoneIdx = new java.util.HashMap<>();
        for (String line; (line = r.readLine()) != null; ) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            int c = SiteRules.countryIndex(f[1]);
            if (c < 0) throw new IllegalStateException(RESOURCE + ": страна " + f[1] + " не из списка");
            Integer z = zoneIdx.get(f[3]);
            if (z == null) {
                z = zones.size();
                zones.add(java.time.ZoneId.of(f[3]));
                zoneIdx.put(f[3], z);
            }
            rows.add(new int[]{Integer.parseInt(f[0]), c, z});
            pop.add(Long.parseLong(f[2]));
        }
        int n = rows.size();
        int[] id = new int[n], co = new int[n], zo = new int[n];
        long[] p = new long[n];
        for (int i = 0; i < n; i++) {
            id[i] = rows.get(i)[0];
            co[i] = rows.get(i)[1];
            zo[i] = rows.get(i)[2];
            p[i] = pop.get(i);
        }
        return new Cities(id, co, p, zo, zones.toArray(new java.time.ZoneId[0]));
    }

    /** Пояс города (индекс в справочнике). */
    java.time.ZoneId zoneOf(int city) {
        return zones[zone[city]];
    }

    int size() {
        return id.length;
    }

    /** Город зрителя (индекс): страна по весам, город — по жителям. */
    int pick(Rng r) {
        double x = r.nextDouble() * countryCum[countryCum.length - 1];
        int c = 0;
        while (c < countryCum.length - 1 && x >= countryCum[c]) c++;
        double[] cc = cum[c];
        double y = r.nextDouble() * cc[cc.length - 1];
        int lo = 0, hi = cc.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cc[mid] > y) hi = mid;
            else lo = mid + 1;
        }
        return byCountry[c][lo];
    }
}
