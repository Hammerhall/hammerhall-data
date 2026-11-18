package io.hammerhall.data.auc.house;

import io.hammerhall.data.auc.bid.IncrementTable;
import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.Horizon;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.time.Period;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Модуль {@code house}: дом и правила — 8 таблиц и {@link HouseRules} в мир ({@link AucKeys#HOUSE}). Города — из
 * входа {@code cities} как есть; отделы, офисы, шаги, шкалы и сборы — постоянные списки ({@link HouseRules}); штат —
 * {@link StaffRoster}; категории — дерево словаря.
 */
public final class HouseModule implements Module {
    public static final String NAME = "house";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> tables() {
        return List.of("departments", "categories", "cities", "offices", "staff", "increment_bands", "fee_schedules",
                "fee_tiers");
    }

    /** Дерево категорий дома — словаря ({@code text.Category}: 18 разделов и подразделы, те же {@code id}). */
    public static List<HouseRules.Category> categories() {
        return io.hammerhall.data.auc.catalog.VocabTexts.categories();
    }

    /** Правила дома для входа городов — то же, что модуль кладёт в мир (для тестов и заглушек). */
    public static HouseRules build(GenContext ctx, CitiesInput cities) {
        return build(ctx.streams(), cities);
    }

    /** То же по потокам случайности (для тестов без прогона). */
    public static HouseRules build(io.hammerhall.data.core.random.RandomStreams streams, CitiesInput cities) {
        List<HouseRules.Office> offices = HouseRules.standardOffices(cities::idByNameRu);
        return new HouseRules(HouseRules.standardDepartments(), categories(), cities.cities(), offices,
                StaffRoster.build(streams, offices), HouseRules.standardIncrements(), HouseRules.standardFees());
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        CitiesInput cities = CitiesInput.load(ctx.config().input("cities"));
        HouseRules h = build(ctx, cities);
        ctx.put(AucKeys.HOUSE, h);
        long cut = Horizon.cut(ctx.period());
        LocalDate cutDay = Period.localDate(cut - 1, HouseRules.ZONE);

        TableSink t = ctx.sink("departments");
        for (HouseRules.Department d : h.departments()) t.add(d.id(), d.code(), d.nameRu(), d.nameEn(), d.sortOrder());
        t.close();

        t = ctx.sink("categories");
        for (HouseRules.Category c : h.categories()) {
            t.add(c.id(), c.dept().id(), c.parentId() == 0 ? null : c.parentId(), c.level(), c.code(), c.nameRu(), c.nameEn());
        }
        t.close();

        t = ctx.sink("cities");
        for (HouseRules.City c : h.cities()) {
            t.add(c.id(), c.nameRu(), c.nameEn(), c.regionRu(), c.federalDistrict(), c.lat(), c.lon(), c.zone().getId(),
                    c.population());
        }
        t.close();

        t = ctx.sink("offices");
        // офисы — постоянный список: все восемь, и открытые после среза (назначенное, как торги)
        for (HouseRules.Office o : h.offices()) {
            t.add(o.id(), o.code(), o.kind(), o.cityId(), o.nameRu(), o.nameEn(), o.hasSaleroom(), o.openedOn(), o.closedOn());
        }
        t.close();

        t = ctx.sink("staff");
        for (HouseRules.Staff s : h.staff()) {
            if (s.hiredOn().isAfter(cutDay)) continue;
            t.add(s.id(), s.officeId(), s.dept() == null ? null : s.dept().id(), s.role().code(), s.firstName(),
                    s.lastName(), s.hiredOn(), s.leftOn());
        }
        t.close();

        t = ctx.sink("increment_bands");
        IncrementTable inc = h.increments();
        for (int i = 0; i < inc.bands(); i++) t.add(HouseRules.RULES_FROM, inc.bandFrom(i), inc.bandStep(i));
        t.close();

        t = ctx.sink("fee_schedules");
        for (HouseRules.FeeSchedule f : h.fees()) {
            t.add(f.id(), f.code(), f.kind(), f.basis(), f.appliesTo(), f.dept() == null ? null : f.dept().id(),
                    f.validFrom(), f.validTo(), f.rateMilliPct() == null ? null : pct(f.rateMilliPct()), f.fixedKop(),
                    f.nameRu());
        }
        t.close();

        t = ctx.sink("fee_tiers");
        for (HouseRules.FeeSchedule f : h.fees()) {
            for (HouseRules.FeeTier x : f.tiers()) t.add(x.scheduleId(), x.tierNo(), x.fromKop(), pct(x.rateMilliPct()));
        }
        t.close();
        ctx.log("дом: " + h.cities().size() + " городов, " + h.offices().size() + " офисов, " + h.staff().size()
                + " сотрудников в штате, " + h.categories().size() + " категорий");
    }

    @Override
    public List<io.hammerhall.data.core.check.Aggregate> aggregates() {
        return List.of(
                io.hammerhall.data.core.check.Aggregate.sum("increment_bands", "step"),
                io.hammerhall.data.core.check.Aggregate.sum("fee_tiers", "rate_pct"),
                io.hammerhall.data.core.check.Aggregate.distinct("staff", "role"),
                io.hammerhall.data.core.check.Aggregate.countEq("staff", "role", "specialist"),
                io.hammerhall.data.core.check.Aggregate.countEq("staff", "role", "auctioneer"),
                io.hammerhall.data.core.check.Aggregate.min("staff", "hired_on"),
                io.hammerhall.data.core.check.Aggregate.max("staff", "hired_on"),
                io.hammerhall.data.core.check.Aggregate.sum("cities", "population"),
                io.hammerhall.data.core.check.Aggregate.distinct("categories", "department_id"));
    }

    /** Тысячные доли процента → {@code decimal(6,3)}. */
    static BigDecimal pct(int milli) {
        return BigDecimal.valueOf(milli, 3);
    }

    @Override
    public void check(GenContext ctx, CheckReport report) {
        HouseRules h = ctx.get(AucKeys.HOUSE);
        // дерево категорий: без циклов, у ребёнка — отдел родителя, лист — у каждого отдела
        Set<Integer> seen = new HashSet<>();
        boolean tree = true;
        for (HouseRules.Category c : h.categories()) {
            if (c.parentId() != 0) {
                HouseRules.Category p = h.category(c.parentId());
                tree &= seen.contains(p.id()) && p.dept() == c.dept() && p.level() == c.level() - 1 && !p.leaf();
            } else {
                tree &= c.level() == 1;
            }
            seen.add(c.id());
        }
        report.expect("дерево категорий: родитель раньше ребёнка, тот же отдел, уровень +1", tree, h.categories().size() + " узлов");
        // шкалы: контрольные примеры до копейки
        long p1 = HouseRules.premium(h.fee(HouseRules.BP_2026), 25_000_000_00L);
        long p2 = HouseRules.premium(h.fee(HouseRules.BP_COIN), 7_300_00L);
        long c1 = h.commission(1_000_000_00L, false, null), c2 = h.commission(1_000_000_00L, true, null);
        report.expect("премия и комиссия — контрольные примеры", p1 == 4_700_000_00L && p2 == 1_241_00L && c1 == 130_500_00L
                && c2 == 80_500_00L, p1 + " " + p2 + " " + c1 + " " + c2);
    }
}
