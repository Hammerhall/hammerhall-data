package io.hammerhall.data.auc;

import io.hammerhall.data.auc.auction.AuctionModule;
import io.hammerhall.data.auc.auction.intake.AuctionPrep;
import io.hammerhall.data.auc.catalog.CatalogModule;
import io.hammerhall.data.auc.house.CitiesInput;
import io.hammerhall.data.auc.house.HouseModule;
import io.hammerhall.data.auc.money.MoneyModule;
import io.hammerhall.data.auc.people.ClientsModule;
import io.hammerhall.data.auc.people.PeopleModule;
import io.hammerhall.data.auc.site.SiteModule;
import io.hammerhall.data.core.gen.Config;
import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.time.Period;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Набор {@code auc-data-v1} — аукционный дом «Дом Одина»: контракт домена с ядром ({@link Dataset}). Вход — справочник
 * городов {@code cities.tsv.gz} выпуска {@code sto-data-v1} (те же {@code id}); паспорт — {@code auc/dataset.properties};
 * схема — {@link AucSchema}; общий мир модулей — пакет {@code io.hammerhall.data.auc.world}.
 *
 * <p>Порядок модулей: {@code house} → {@code people} (база клиентов в мир) → {@code catalog} → {@code auction} →
 * {@code clients} (таблица клиентов с полями «на „сейчас“» — после торгов) → {@code money} → {@code site}.
 */
public final class AucDataset implements Dataset {
    public static final String CODE = "auc";
    /** Название дома — одно место в коде: паспорт ({@code title}) и {@code SOURCE.md} берут отсюда; в таблицах имени нет. */
    public static final String HOUSE_NAME = "Дом Одина";
    public static final String HOUSE_NAME_EN = "Odin's House";
    public static final String VERSION = AucSchema.VERSION;
    public static final String GENERATOR_VERSION = "auc-gen 0.1.0";
    public static final long DEFAULT_SEED = 20261018L;
    /** Период — как у «Кременъ»: 01.10.2025 – 30.10.2027, «сейчас» — 01.09.2027 00:00 UTC (среда, 03:00 по Москве). */
    public static final Period PERIOD = new Period(LocalDate.of(2025, 10, 1), LocalDate.of(2027, 10, 30),
            Instant.parse("2027-09-01T00:00:00Z"), Period.MOSCOW);
    /** Вход: города выпуска {@code sto-data-v1}. */
    public static final Input CITIES = new Input("cities", "cities.tsv.gz", CitiesInput.STO_DATA_V1_SHA256_TSV, null);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String title() {
        return HOUSE_NAME;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String generatorVersion() {
        return GENERATOR_VERSION;
    }

    @Override
    public long defaultSeed() {
        return DEFAULT_SEED;
    }

    @Override
    public Period period() {
        return PERIOD;
    }

    @Override
    public Schema schema() {
        return AucSchema.build();
    }

    /** Все модули в порядке сборки (см. описание класса). */
    @Override
    public List<Module> modules() {
        List<Module> m = new ArrayList<>();
        m.add(new HouseModule());
        m.add(new PeopleModule());
        m.add(new CatalogModule());
        // торги: оркестр и движок, подготовка — календарь, каталог, участники; план оплаты — от модуля денег
        m.add(new AuctionModule(AuctionPrep::new, AuctionModule::moneyPlanner));
        m.add(ClientsModule.withCorridors());
        m.add(new MoneyModule());
        // сайт и уведомления — последним: визиты вокруг ставок, наблюдение, уведомления из торгов и денег
        m.add(SiteModule.ofWorld(List.of("auction", "money")));
        return m;
    }

    @Override
    public Release.Docs docs() {
        return new AucDocs();
    }

    @Override
    public List<Input> inputs() {
        return List.of(CITIES);
    }

    @Override
    public List<Map<String, Object>> readInputs(Config cfg, boolean strict) throws IOException {
        CitiesInput c = CitiesInput.load(cfg.input(CITIES.name()));
        if (!CITIES.sha256Tsv().equals(c.sha256Tsv())) {
            String msg = "cities.tsv.gz не совпадает с sto-data-v1 (sha256_tsv " + c.sha256Tsv() + ")";
            if (strict) throw new IllegalStateException(msg);
            System.out.println("ВНИМАНИЕ: " + msg);
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("name", "cities.tsv.gz");
        in.put("release", "sto-data-v1");
        in.put("license", "MIT");
        in.put("sha256_tsv", c.sha256Tsv());
        return List.of(in);
    }

    /** Формат ядра, деньги и миллисекунды. */
    @Override
    public Map<String, Object> format() {
        Map<String, Object> f = new LinkedHashMap<>(Dataset.super.format());
        f.put("money", "decimal string, 2 digits, rubles");
        f.put("milliseconds", "separate *_ms fields, 0-999");
        return f;
    }
}
