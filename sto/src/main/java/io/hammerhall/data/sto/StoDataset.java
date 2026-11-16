package io.hammerhall.data.sto;

import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.gen.Config;
import io.hammerhall.data.core.gen.Dataset;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.geo.Airports;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.sto.catalog.CatalogModule;
import io.hammerhall.data.sto.catalog.PricingModule;
import io.hammerhall.data.sto.customers.CustomerModule;
import io.hammerhall.data.sto.geo.GeoModule;
import io.hammerhall.data.sto.market.MarketModule;
import io.hammerhall.data.sto.orders.OrderModule;
import io.hammerhall.data.sto.supply.SupplierModule;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Набор {@code sto-data-v1} — магазин «Кременъ»: контракт домена с ядром ({@link Dataset}). Вход — справочник
 * аэропортов {@code airports.tsv.gz} выпуска {@code air-data-v1} (города магазина); паспорт — {@code sto/dataset.properties}.
 */
public final class StoDataset implements Dataset {
    public static final String CODE = "sto";
    public static final String GENERATOR_VERSION = "sto-gen 0.1.0";
    public static final long DEFAULT_SEED = 20251001L;
    /** Период v1 — как у авиа: 01.10.2025 – 30.10.2027, «сейчас» — 01.09.2027 00:00 UTC; магазин живёт по Москве. */
    public static final Period PERIOD = new Period(LocalDate.of(2025, 10, 1), LocalDate.of(2027, 10, 30),
            Instant.parse("2027-09-01T00:00:00Z"), Period.MOSCOW);
    public static final Input AIRPORTS = new Input("airports", "airports.tsv.gz", Airports.AIR_DATA_V1_SHA256_TSV, null);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String title() {
        return "Магазин «Кременъ»";
    }

    @Override
    public String version() {
        return StoSchema.VERSION;
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
        return StoSchema.build();
    }

    /** Все модули домена в порядке сборки (следующие агенты добавляют свои сюда). */
    @Override
    public List<Module> modules() {
        return List.of(new GeoModule(), new CatalogModule(), new PricingModule(), new SupplierModule(),
                new MarketModule(), new CustomerModule(), new OrderModule());
    }

    /** Контрольные агрегаты: объявленные модулями и агрегаты волны 1 ({@link StoAggregates}). */
    @Override
    public List<Aggregate> aggregates() {
        List<Aggregate> out = new ArrayList<>();
        for (Module m : modules()) out.addAll(m.aggregates());
        out.addAll(StoAggregates.wave1());
        return out;
    }

    @Override
    public Release.Docs docs() {
        return new StoDocs();
    }

    @Override
    public List<Input> inputs() {
        return List.of(AIRPORTS);
    }

    @Override
    public List<Map<String, Object>> readInputs(Config cfg, boolean strict) throws IOException {
        Airports air = Airports.load(cfg.input(AIRPORTS.name()));
        if (!AIRPORTS.sha256Tsv().equals(air.sha256Tsv())) {
            String msg = "airports.tsv.gz не совпадает с air-data-v1 (sha256_tsv " + air.sha256Tsv() + ")";
            if (strict) throw new IllegalStateException(msg);
            System.out.println("ВНИМАНИЕ: " + msg);
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("name", "airports.tsv.gz");
        in.put("release", "air-data-v1");
        in.put("license", "MIT, Copyright (c) 2025 Postgres Professional");
        in.put("sha256_tsv", air.sha256Tsv());
        return List.of(in);
    }

    /** Формат ядра и деньги: десятичная строка, два знака, рубли. */
    @Override
    public Map<String, Object> format() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("encoding", "UTF-8");
        f.put("delimiter", "TAB");
        f.put("header", true);
        f.put("null", "empty field");
        f.put("time", "Unix seconds, UTC");
        f.put("date", "ISO 8601, YYYY-MM-DD");
        f.put("money", "decimal string, 2 digits, rubles");
        f.put("boolean", "t / f");
        f.put("compression", "gzip, mtime=0");
        return f;
    }
}
