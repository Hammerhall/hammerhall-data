package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.AucDataset;
import io.hammerhall.data.auc.catalog.CatalogModule;
import io.hammerhall.data.auc.house.HouseModule;
import io.hammerhall.data.auc.people.ClientsModule;
import io.hammerhall.data.auc.people.PeopleModule;
import io.hammerhall.data.auc.people.StubClientBase;
import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.core.gen.Config;
import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.time.Period;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/**
 * Набор «Дом Одина» с упрощёнными торгами ({@link StubAuction}) и настоящим модулем денег — проверка денег целиком.
 * Клиенты — настоящие ({@code people}); {@link People} — запасная упрощённая база на 3 000 человек с разбросом черт
 * оплаты.
 */
final class MoneyStubDataset implements Dataset {
    private final AucDataset base = new AucDataset();

    /** Упрощённая база клиентов с чертами оплаты. */
    static final class People implements Module {
        @Override
        public String name() {
            return "people";
        }

        @Override
        public List<String> requires() {
            return List.of("house");
        }

        @Override
        public List<String> tables() {
            return List.of();
        }

        @Override
        public void generate(GenContext ctx) {
            HouseRules house = ctx.get(AucKeys.HOUSE);
            StubClientBase stub = new StubClientBase(ctx.streams(), house, 3000);
            RandomStreams.Stream st = ctx.stream("stub.pay");
            ClientBase cb = (ClientBase) Proxy.newProxyInstance(ClientBase.class.getClassLoader(),
                    new Class<?>[]{ClientBase.class}, (p, m, a) -> {
                        if (m.getName().equals("payDelayDays")) {
                            return (float) (-4.0 * StrictMath.log(1.0 - st.unit((int) a[0])));
                        }
                        if (m.getName().equals("payReliability")) {
                            int id = (int) a[0];
                            if (stub.serialNonPayer(id)) return 0f;
                            double z = st.at(id, 7).gaussian();
                            return (float) (1.0 - 0.03 * StrictMath.exp(0.5 * z - 0.125));
                        }
                        try {
                            return m.invoke(stub, a);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            ctx.put(AucKeys.CLIENTS, cb);
        }
    }

    @Override
    public List<Module> modules() {
        return List.of(new HouseModule(), new PeopleModule(), new CatalogModule(), new StubAuction(), new ClientsModule(),
                new MoneyModule());
    }

    @Override
    public String code() {
        return base.code();
    }

    @Override
    public String title() {
        return base.title();
    }

    @Override
    public String version() {
        return base.version();
    }

    @Override
    public String generatorVersion() {
        return base.generatorVersion();
    }

    @Override
    public long defaultSeed() {
        return base.defaultSeed();
    }

    @Override
    public Period period() {
        return base.period();
    }

    @Override
    public Schema schema() {
        return base.schema();
    }

    @Override
    public Release.Docs docs() {
        return base.docs();
    }

    @Override
    public List<Input> inputs() {
        return base.inputs();
    }

    @Override
    public List<Map<String, Object>> readInputs(Config cfg, boolean strict) throws IOException {
        return base.readInputs(cfg, strict);
    }

    @Override
    public Map<String, Object> format() {
        return base.format();
    }
}
