package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Секция словаря шаблонов: выбор шаблона, чьи поля все есть, — поровну среди годных. */
final class Section {
    final String name;
    final List<Tpl> items;

    Section(String name, List<Tpl> items) {
        this.name = name;
        this.items = List.copyOf(items);
    }

    /** Годный шаблон или {@code null}, если годных нет (у фильма нет нужных полей). */
    Tpl pick(Rng r, Tpl.Slots s) {
        int k = 0;
        for (Tpl t : items) if (s.fits(t.need)) k++;
        if (k == 0) return null;
        int i = r.nextInt(k);
        for (Tpl t : items) {
            if (s.fits(t.need) && i-- == 0) return t;
        }
        throw new IllegalStateException(name);
    }

    /** Есть ли годный шаблон. */
    boolean any(Tpl.Slots s) {
        for (Tpl t : items) if (s.fits(t.need)) return true;
        return false;
    }

    /** Все секции файла словаря: строка — шаблон. */
    static Map<String, Section> load(String file) {
        Map<String, Section> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : Vocab.sections(file).entrySet()) {
            List<Tpl> t = new ArrayList<>();
            for (String line : e.getValue()) {
                try {
                    t.add(Tpl.parse(line));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalStateException(file + " [" + e.getKey() + "]: " + ex.getMessage(), ex);
                }
            }
            out.put(e.getKey(), new Section(e.getKey(), t));
        }
        return out;
    }
}
