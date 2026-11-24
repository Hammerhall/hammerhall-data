package io.hammerhall.data.mov;

import java.util.List;

/**
 * Коды, которых нет в наборе: страны фильмов ({@code movies_countries}), гражданство ({@code people_countries}),
 * страны студий ({@code companies}) и выхода по странам ({@code movies_releases}) — тот же список, что у набора «Мир».
 * Списки — только коды, парами ISO 3166-1 alpha-3 и alpha-2 ({@link #DEFAULT} — списки выпуска, тесты подставляют
 * свои).
 *
 * @param countries3 страны — ISO 3166-1 alpha-3
 * @param countries2 те же страны — ISO 3166-1 alpha-2
 */
public record Exclusions(List<String> countries3, List<String> countries2) {
    /** Коды, которых нет в наборе: страны (ISO 3166-1 alpha-3). */
    public static final List<String> COUNTRIES3 = List.of("ESH", "PSE", "XKX", "TWN", "FLK", "GIB");
    /** Коды, которых нет в наборе: те же страны (ISO 3166-1 alpha-2), по порядку {@link #COUNTRIES3}. */
    public static final List<String> COUNTRIES2 = List.of("EH", "PS", "XK", "TW", "FK", "GI");

    /** Списки выпуска. */
    public static final Exclusions DEFAULT = new Exclusions(COUNTRIES3, COUNTRIES2);

    public Exclusions {
        countries3 = List.copyOf(countries3);
        countries2 = List.copyOf(countries2);
        if (countries3.size() != countries2.size()) throw new IllegalArgumentException("списки ISO3 и ISO2 — разной длины");
        for (String c : countries3) {
            if (!c.matches("[A-Z]{3}")) throw new IllegalArgumentException("код страны ISO3 — три латинские буквы: " + c);
        }
        for (String c : countries2) {
            if (!c.matches("[A-Z]{2}")) throw new IllegalArgumentException("код страны ISO2 — две латинские буквы: " + c);
        }
    }

    public boolean country3(String iso3) {
        return countries3.contains(iso3);
    }

    public boolean country2(String iso2) {
        return countries2.contains(iso2);
    }
}
