package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.mov.site.text.Tpl.Slot;
import io.hammerhall.data.mov.site.text.Tpl.Slots;

import java.util.List;
import java.util.Map;

/**
 * Названия подборок ({@code collections.txt}): чаще ({@link #OWN}) — своё название жанра («Лучшее про космос»,
 * «Смотреть с детьми на выходных»), иначе — общий шаблон с жанром во множественном («Лучшие боевики 1990-х»,
 * «Недооценённые мелодрамы»). Adult — только свои названия; жанр вне словаря — подборка без жанра.
 */
final class CollectionTitles {
    static final double OWN = 0.55;
    /** Десятилетия для общих шаблонов. */
    static final String[] DECADES = {"1960-х", "1970-х", "1980-х", "1990-х", "2000-х", "2010-х", "семидесятых",
            "восьмидесятых", "девяностых", "нулевых"};

    private final List<List<GenreRu>> genres;
    private final Section any, none;
    private final Section[] own;

    CollectionTitles(List<List<GenreRu>> genres) {
        this.genres = genres;
        Map<String, Section> sec = Section.load("collections.txt");
        any = need(sec, "any");
        none = need(sec, "none");
        own = new Section[GenreRu.COUNT + 1];
        for (int id = 1; id <= GenreRu.COUNT; id++) own[id] = need(sec, "g" + id);
    }

    private static Section need(Map<String, Section> m, String name) {
        Section s = m.get(name);
        if (s == null) throw new IllegalStateException("collections.txt: нет секции [" + name + "]");
        return s;
    }

    String title(Rng r, int genreId) {
        Slots s = new Slots();
        if (genreId < 1 || genreId > GenreRu.COUNT) return expand(r, none, s);
        if (genreId == GenreRu.ADULT || r.chance(OWN)) return expand(r, own[genreId], s);
        GenreRu g = GenreRu.pick(genres, genreId, r);
        s.set(Slot.Gp, g.pl());
        s.set(Slot.Gpg, g.plGen());
        s.set(Slot.DEC, r.pick(DECADES));
        s.gender = g.gender();
        return expand(r, any, s);
    }

    private static String expand(Rng r, Section sec, Slots s) {
        Tpl t = sec.pick(r, s);
        return Tx.cap(Tx.tidy(t.expand(r, s)));
    }

    List<Section> allSections() {
        List<Section> out = new java.util.ArrayList<>(List.of(any, none));
        for (int id = 1; id <= GenreRu.COUNT; id++) out.add(own[id]);
        return out;
    }
}
