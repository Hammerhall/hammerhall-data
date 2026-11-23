package io.hammerhall.data.wld;

import java.util.List;
import java.util.Locale;

/**
 * Коды, которых нет в наборе: страны, регионы первого уровня и отдельные города. Списки — только коды; сборка их
 * записи не берёт ({@link #DEFAULT} — списки выпуска, тесты подставляют свои).
 *
 * @param countries страны — ISO 3166-1 alpha-2
 * @param regions   регионы первого уровня — {@code CC.ADM1} ({@code admin1CodesASCII.txt})
 * @param cities    города — geonameid
 */
public record Exclusions(List<String> countries, List<String> regions, List<Long> cities) {
    /** Коды, которых нет в наборе: страны (ISO 3166-1 alpha-2). */
    public static final List<String> COUNTRIES = List.of("EH", "PS", "XK", "TW", "FK", "GI");
    /** Коды, которых нет в наборе: регионы первого уровня ({@code CC.ADM1}). */
    public static final List<String> REGIONS = List.of(
            "AZ.55", "AZ.56", "AZ.61", "AZ.64", "AZ.65", "CY.02", "GE.02", "IL.WE", "IN.12", "IN.41",
            "MD.58", "MD.62", "PK.06", "PK.07", "UA.11", "UA.20");
    /** Коды, которых нет в наборе: города (geonameid). */
    public static final List<Long> CITIES = List.of(
            145955L, 146027L, 146105L, 146286L, 146386L, 146391L, 146392L, 146617L, 146638L, 146767L,
            146842L, 167167L, 167444L, 170378L, 171200L, 172119L, 173550L, 173601L, 173882L, 282163L,
            282582L, 282597L, 282727L, 283047L, 283989L, 443084L, 443088L, 443090L, 443091L, 443092L,
            443093L, 443100L, 584747L, 610838L, 611403L, 611719L, 613961L, 614217L, 615914L, 2461874L,
            2463161L, 8199378L, 8199384L, 8199387L, 8199388L, 8199389L, 8199390L, 8199391L, 8199393L,
            8199422L, 9645313L, 11071896L);

    /** Списки выпуска. */
    public static final Exclusions DEFAULT = new Exclusions(COUNTRIES, REGIONS, CITIES);

    public Exclusions {
        countries = List.copyOf(countries);
        regions = List.copyOf(regions);
        cities = List.copyOf(cities);
        for (String c : countries) {
            if (!c.matches("[A-Z]{2}")) throw new IllegalArgumentException("код страны — две латинские буквы: " + c);
        }
        for (String r : regions) {
            if (!r.matches("[A-Z]{2}\\.[0-9A-Za-z]+")) throw new IllegalArgumentException("код региона — CC.ADM1: " + r);
        }
    }

    public boolean country(String iso2) {
        return countries.contains(iso2);
    }

    /** Регион {@code CC.ADM1} города; пустой код региона — не в списке. */
    public boolean region(String iso2, String admin1) {
        return admin1 != null && !admin1.isEmpty() && regions.contains(iso2.toUpperCase(Locale.ROOT) + "." + admin1);
    }

    public boolean city(long geonameid) {
        return cities.contains(geonameid);
    }
}
