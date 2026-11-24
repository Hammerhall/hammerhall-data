package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.List;

/**
 * Жанр словаря набора по-русски (одна строка {@code genres.tsv}): род для согласования и формы — именительный,
 * родительный, множественное (им. и род.). У жанра бывает несколько синонимов одного рода.
 */
record GenreRu(int id, int gender, String nom, String gen, String pl, String plGen) {
    /** Жанров в словаре набора. */
    static final int COUNT = 24;
    /** Adult — в рецензиях не называется. */
    static final int ADULT = 2;
    static final int DRAMA = 9;
    static final int SHORT = 20;

    /** Синонимы по id (индекс 1–24). */
    static List<List<GenreRu>> load() {
        List<List<GenreRu>> by = new ArrayList<>();
        for (int i = 0; i <= COUNT; i++) by.add(new ArrayList<>());
        for (String[] f : Vocab.tsv("genres.tsv")) {
            if (f.length != 6) throw new IllegalStateException("genres.tsv: шесть полей, а не " + f.length + ": " + String.join("|", f));
            int id = Integer.parseInt(f[0]);
            int g = switch (f[1]) {
                case "m" -> 0;
                case "f" -> 1;
                case "n" -> 2;
                default -> throw new IllegalStateException("genres.tsv: род «" + f[1] + "»");
            };
            by.get(id).add(new GenreRu(id, g, f[2], f[3], f[4], f[5]));
        }
        List<List<GenreRu>> out = new ArrayList<>();
        for (int i = 0; i <= COUNT; i++) {
            if (i > 0 && by.get(i).isEmpty()) throw new IllegalStateException("genres.tsv: нет жанра " + i);
            out.add(List.copyOf(by.get(i)));
        }
        return List.copyOf(out);
    }

    static GenreRu pick(List<List<GenreRu>> all, int id, Rng r) {
        return r.pick(all.get(id));
    }
}
