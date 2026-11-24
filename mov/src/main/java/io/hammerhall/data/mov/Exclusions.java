package io.hammerhall.data.mov;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Коды, которых нет в наборе: страны фильмов ({@code movies_countries}), гражданство ({@code people_countries}),
 * страны студий ({@code companies}) и выхода по странам ({@code movies_releases}) — тот же список, что у набора «Мир»;
 * места ({@code places}) — номера элементов из ресурса {@value #PLACES_RESOURCE}. Списки — только коды: страны парами
 * ISO 3166-1 alpha-3 и alpha-2, места — номера Q ({@link #DEFAULT} — списки выпуска, тесты подставляют свои).
 *
 * @param countries3 страны — ISO 3166-1 alpha-3
 * @param countries2 те же страны — ISO 3166-1 alpha-2
 * @param places     места — номера элементов Wikidata ({@code Q42} → 42)
 */
public record Exclusions(List<String> countries3, List<String> countries2, Set<Integer> places) {
    /** Коды, которых нет в наборе: страны (ISO 3166-1 alpha-3). */
    public static final List<String> COUNTRIES3 = List.of("ESH", "PSE", "XKX", "TWN", "FLK", "GIB");
    /** Коды, которых нет в наборе: те же страны (ISO 3166-1 alpha-2), по порядку {@link #COUNTRIES3}. */
    public static final List<String> COUNTRIES2 = List.of("EH", "PS", "XK", "TW", "FK", "GI");
    /** Ресурс со списком мест, которых нет в наборе: номер {@code Q…} по строке, {@code #} — комментарий. */
    public static final String PLACES_RESOURCE = "excluded-places.txt";
    /** Места, которых нет в наборе (номера элементов) — из {@link #PLACES_RESOURCE}. */
    public static final Set<Integer> PLACES = readPlaces();

    /** Списки выпуска. */
    public static final Exclusions DEFAULT = new Exclusions(COUNTRIES3, COUNTRIES2, PLACES);

    public Exclusions {
        countries3 = List.copyOf(countries3);
        countries2 = List.copyOf(countries2);
        places = Set.copyOf(places);
        if (countries3.size() != countries2.size()) throw new IllegalArgumentException("списки ISO3 и ISO2 — разной длины");
        for (String c : countries3) {
            if (!c.matches("[A-Z]{3}")) throw new IllegalArgumentException("код страны ISO3 — три латинские буквы: " + c);
        }
        for (String c : countries2) {
            if (!c.matches("[A-Z]{2}")) throw new IllegalArgumentException("код страны ISO2 — две латинские буквы: " + c);
        }
        for (int q : places) if (q <= 0) throw new IllegalArgumentException("номер места — больше нуля: " + q);
    }

    /** Списки стран, мест нет. */
    public Exclusions(List<String> countries3, List<String> countries2) {
        this(countries3, countries2, Set.of());
    }

    public boolean country3(String iso3) {
        return countries3.contains(iso3);
    }

    public boolean country2(String iso2) {
        return countries2.contains(iso2);
    }

    /** Место (номер элемента) — из списка. */
    public boolean place(int q) {
        return places.contains(q);
    }

    /**
     * Список мест из текста: по номеру {@code Q…} на строку; от {@code #} до конца строки — комментарий; пустые строки
     * пропускаются; иное — ошибка с номером строки.
     */
    public static Set<Integer> parsePlaces(String text, String name) {
        Set<Integer> out = new TreeSet<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i];
            int hash = s.indexOf('#');
            if (hash >= 0) s = s.substring(0, hash);
            s = s.strip();
            if (s.isEmpty()) continue;
            if (!s.matches("Q[1-9][0-9]{0,9}") || Long.parseLong(s.substring(1)) > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(name + ":" + (i + 1) + ": не номер элемента Wikidata: «" + s + "»");
            }
            out.add(Integer.parseInt(s.substring(1)));
        }
        return out;
    }

    private static Set<Integer> readPlaces() {
        try (InputStream in = Exclusions.class.getResourceAsStream(PLACES_RESOURCE)) {
            if (in == null) throw new IllegalStateException("нет ресурса " + PLACES_RESOURCE);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                for (String s; (s = r.readLine()) != null; ) sb.append(s).append('\n');
                return Set.copyOf(parsePlaces(sb.toString(), PLACES_RESOURCE));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
