package io.hammerhall.data.wld.build;

import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Key;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.wld.Exclusions;
import io.hammerhall.data.wld.source.SnapshotSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Единственный модуль набора: собирает {@link World} из снимка и пишет пять таблиц — страны (и закрывает их: на них
 * ссылаются все), города, языки, соседство, ряды по годам. Ссылку {@code countries.capital → cities} ядро проверяет,
 * когда закрыты города.
 */
public final class WorldModule implements Module {
    /** Собранный набор — для манифеста. */
    public static final Key<World> WORLD = Key.of("wld.world");

    private final SnapshotSource source;
    private final Exclusions exclusions;

    public WorldModule(SnapshotSource source, Exclusions exclusions) {
        this.source = source;
        this.exclusions = exclusions;
    }

    @Override
    public String name() {
        return "world";
    }

    @Override
    public List<String> tables() {
        return List.of("countries", "cities", "country_languages", "country_borders", "country_years");
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        World w = World.build(source.load(ctx.config().input("source")), ctx.profile(), exclusions);
        ctx.put(WORLD, w);
        ctx.log("снимок " + source.loaded().snapshot() + ": " + w.stats());

        TableSink s = ctx.sink("countries");
        for (World.Country c : w.countries()) {
            s.add(c.code(), c.code2(), c.name(), c.nameRu(), c.localName(), c.continent(), c.region(), c.regionRu(),
                    c.surfaceArea(), c.population(), c.lifeExpectancy(), c.gnp(), c.gnpOld(),
                    c.capital() == null ? null : Math.toIntExact(c.capital()));
        }
        ctx.close("countries");

        s = ctx.sink("cities");
        for (World.City c : w.cities()) {
            s.add(Math.toIntExact(c.id()), c.name(), c.nameRu(), c.countryCode(), c.district(), c.population(),
                    c.latitude(), c.longitude(), c.timezone());
        }
        ctx.close("cities");

        s = ctx.sink("country_languages");
        for (World.Language l : w.languages()) {
            s.add(l.countryCode(), l.languageCode(), l.language(), l.languageRu(), l.official(), l.percentage());
        }
        ctx.close("country_languages");

        s = ctx.sink("country_borders");
        for (World.Border b : w.borders()) s.add(b.countryCode(), b.neighbourCode());
        ctx.close("country_borders");

        s = ctx.sink("country_years");
        for (World.Year y : w.years()) {
            s.add(y.countryCode(), y.year(), y.population(), y.gnp(), y.gdp(), y.lifeExpectancy());
        }
        ctx.close("country_years");
    }

    /** Свои инварианты: порог профиля, столицы среди городов, соседство в обе стороны, годы рядов. */
    @Override
    public void check(GenContext ctx, CheckReport report) {
        World w = ctx.get(WORLD);
        Set<Long> capitals = new HashSet<>();
        for (World.Country c : w.countries()) if (c.capital() != null) capitals.add(c.capital());
        List<String> bad = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (World.City c : w.cities()) {
            ids.add(c.id());
            if (c.population() < w.minPopulation() && !capitals.contains(c.id())) bad.add(c.id() + " (" + c.population() + ")");
        }
        report.expectNone("города: население от порога профиля или столица", bad,
                "порог " + w.minPopulation() + ", городов " + w.cities().size());
        List<String> lost = new ArrayList<>();
        for (Long c : capitals) if (!ids.contains(c)) lost.add(String.valueOf(c));
        report.expectNone("столицы — среди городов профиля", lost, "столиц " + capitals.size());

        Set<String> pairs = new HashSet<>();
        for (World.Border b : w.borders()) pairs.add(b.countryCode() + ">" + b.neighbourCode());
        List<String> oneWay = new ArrayList<>();
        for (World.Border b : w.borders()) {
            if (!pairs.contains(b.neighbourCode() + ">" + b.countryCode())) oneWay.add(b.countryCode() + "-" + b.neighbourCode());
        }
        report.expectNone("соседство — в обе стороны", oneWay, "пар " + w.borders().size());

        List<String> outside = new ArrayList<>();
        for (World.Year y : w.years()) {
            if (y.year() < World.FIRST_YEAR || w.lastYear() == null || y.year() > w.lastYear()) {
                outside.add(y.countryCode() + " " + y.year());
            }
        }
        report.expectNone("ряды по годам — от " + World.FIRST_YEAR + " до последнего года данных", outside,
                "строк " + w.years().size() + ", последний год " + w.lastYear());
        Map<String, Long> st = w.stats();
        report.ok("столицы и языки", "стран без столицы — " + st.get("capitals_empty") + "; языков без английского названия "
                + "(не взяты) — " + st.get("languages_without_english_name"));
    }

    @Override
    public List<Aggregate> aggregates() {
        return List.of(
                Aggregate.sum("countries", "population"),
                Aggregate.distinct("countries", "continent"),
                Aggregate.distinct("countries", "region"),
                Aggregate.sum("cities", "population"),
                Aggregate.distinct("cities", "country_code"),
                Aggregate.countEq("country_languages", "official", "t"),
                Aggregate.sum("country_languages", "percentage"),
                Aggregate.min("country_years", "year"),
                Aggregate.max("country_years", "year"),
                Aggregate.sum("country_years", "gdp"));
    }
}
