package io.hammerhall.data.auc.people;

import io.hammerhall.data.auc.house.HouseModule;
import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Horizon;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;

import java.util.List;

/**
 * Модуль {@code people}: база клиентов ({@link ClientBase}) в мир ({@link AucKeys#CLIENTS}) — до приёма вещей и торгов.
 * Таблиц не пишет: поля «на „сейчас“» таблицы {@code clients} зависят от торгов, её пишет модуль {@code clients} после
 * {@code auction} ({@link ClientsModule}).
 */
public final class PeopleModule implements Module {
    public static final String NAME = "people";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return List.of(HouseModule.NAME);
    }

    @Override
    public List<String> tables() {
        return List.of();
    }

    @Override
    public void generate(GenContext ctx) {
        HouseRules house = ctx.get(AucKeys.HOUSE);
        ClientPopulation base = new ClientPopulation(ctx.streams(), house, ctx.profile().scale(), Horizon.end(ctx.period()));
        ctx.put(AucKeys.CLIENTS, base);
        ctx.log("клиенты: " + base.size() + " в базе до конца моделирования, " + base.countRegisteredBefore(Horizon.cut(ctx.period()))
                + " до среза, связей " + base.links().size());
    }
}
