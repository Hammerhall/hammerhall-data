package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.List;

/**
 * Языковые группы имён — словарь {@code vocab/names.txt}: имена (у русских — с отчествами и «эпохой»), фамилии, города —
 * по-русски и по-английски. Все сочетания выдуманы. Фамилии подобраны вручную, без фамилий известных художников,
 * ювелиров, мастеров, фабрикантов и марок; страховка — {@link RealNames}. Иностранные мужские фамилии на -а и
 * прилагательные в словари не берём — их не склоняют правила {@link Ru}.
 */
enum Nation {
    RU(Fem.SLAVIC), FR(Fem.NONE), IT(Fem.NONE), DE(Fem.NONE), NL(Fem.NONE), GB(Fem.NONE), SC(Fem.NONE), ES(Fem.NONE),
    PL(Fem.SLAVIC), CZ(Fem.CZECH);

    /** Как образуется женская форма фамилии. */
    enum Fem {NONE, SLAVIC, CZECH}

    record Male(String name, String patrM, String patrF, char era, String en) {
    }

    record Female(String name, char era, String en) {
    }

    record Surname(String ru, String en) {
    }

    final Male[] male;
    final Female[] female;
    final Surname[] surnames;
    final City[] cities;
    final double[] cityWeights;
    final Fem fem;

    Nation(Fem fem) {
        this.fem = fem;
        List<Male> m = new ArrayList<>();
        for (V.P p : V.pairs("names", name() + ".male")) {
            String[] a = p.ru().split("\\s*:\\s*");
            m.add(a.length == 1 ? new Male(a[0], null, null, 'a', p.en()) : new Male(a[0], a[1], a[2], a[3].charAt(0), p.en()));
        }
        this.male = m.toArray(new Male[0]);
        List<Female> f = new ArrayList<>();
        for (V.P p : V.pairs("names", name() + ".female")) {
            String[] a = p.ru().split("\\s*:\\s*");
            f.add(new Female(a[0], a.length > 1 ? a[1].charAt(0) : 'a', p.en()));
        }
        this.female = f.toArray(new Female[0]);
        List<Surname> s = new ArrayList<>();
        if (name().equals("RU")) {
            for (String x : V.list("names", "RU.surnames")) s.add(new Surname(x, Translit.ru(x)));
        } else {
            for (V.P x : V.pairs("names", name() + ".surnames")) s.add(new Surname(x.ru(), x.en()));
        }
        this.surnames = s.toArray(new Surname[0]);
        List<City> c = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        for (V.P p : V.pairs("names", name() + ".cities")) {
            String[] a = p.ru().split("\\s*:\\s*");
            c.add(new City(a[0], a[1], a[2], Country.valueOf(a[3]), p.en()));
            w.add(Double.parseDouble(a[4]));
        }
        this.cities = c.toArray(new City[0]);
        this.cityWeights = new double[w.size()];
        for (int i = 0; i < cityWeights.length; i++) cityWeights[i] = w.get(i);
    }

    City city(Rng r) {
        return cities[r.weighted(cityWeights)];
    }

    /** Подходит ли имя эпохе: «o» — родившимся до 1900, «n» — после 1895, «a» — всем. */
    private static boolean fits(char era, int birthYear) {
        return era == 'a' || (era == 'o' && birthYear < 1900) || (era == 'n' && birthYear > 1895);
    }

    String feminine(String m) {
        return switch (fem) {
            case SLAVIC -> Ru.feminineSlavic(m);
            case CZECH -> Ru.feminineCzech(m);
            case NONE -> m;
        };
    }

    /** Женская форма фамилии по-английски: польские -ski → -ska, чешские + ová, русские — транслитерация женской формы. */
    String feminineEn(Surname s) {
        return switch (this) {
            case RU -> Translit.ru(Ru.feminineSlavic(s.ru()));
            case PL -> s.en().endsWith("ski") || s.en().endsWith("cki") ? s.en().substring(0, s.en().length() - 1) + "a" : s.en();
            case CZ -> s.en() + "ová";
            default -> s.en();
        };
    }

    Surname surname(Rng r) {
        return surnames[r.nextInt(surnames.length)];
    }

    /** Выдуманный человек этой группы с заданной фамилией (вызывающий отбирает занятые и настоящие). */
    Person person(Rng r, boolean isFemale, int birthYear, Surname sur, Country country) {
        String patr = null;
        if (this == RU) {
            Male father;
            do {
                father = male[r.nextInt(male.length)];
            } while (!fits(father.era, birthYear - 28));
            patr = isFemale ? father.patrF : father.patrM;
        }
        if (isFemale) {
            Female f;
            do {
                f = female[r.nextInt(female.length)];
            } while (!fits(f.era, birthYear));
            return new Person(f.name, patr, feminine(sur.ru()), sur.ru(), true, country, f.en, feminineEn(sur), sur.en());
        }
        Male m;
        do {
            m = male[r.nextInt(male.length)];
        } while (!fits(m.era, birthYear));
        return new Person(m.name, patr, sur.ru(), sur.ru(), false, country, m.en, sur.en(), sur.en());
    }

    /** Случайный человек: фамилия из словаря без сверок (для собирателей, адресатов, исполнителей). */
    Person anyone(Rng r, int birthYear, double femaleShare) {
        City c = city(r);
        return person(r, r.chance(femaleShare), birthYear, surname(r), c.country());
    }
}
