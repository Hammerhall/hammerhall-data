package io.hammerhall.data.sto.stock;

import io.hammerhall.data.core.money.Money;
import io.hammerhall.data.core.money.Tax;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.PriceBook;
import io.hammerhall.data.sto.supply.Suppliers;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * Закупочная цена партии, копеек за штуку без НДС. От цены запуска без НДС отнимается наценка группы товаров;
 * дальше — инфляция закупочных цен группы (от запуска или от начала периода, что позже), условия поставщика (у
 * представительства бренда дешевле, у дистрибьютора дороже), разброс партии и с 01.12.2026 технологический сбор
 * (его платит импортёр — он в закупочной цене). Сверху — потолок: не дороже розницы на дату без НДС за вычетом трети
 * наценки (у дешевеющей электроники маржа сжимается, но закупка остаётся ниже розницы).
 */
final class CostModel {
    /** Валовая наценка группы — доля розницы без НДС. */
    private static final Map<String, Double> MARGIN = new TreeMap<>(Map.ofEntries(
            Map.entry("phones", 0.14), Map.entry("computers", 0.13), Map.entry("tv", 0.18), Map.entry("gaming", 0.15),
            Map.entry("photo", 0.16), Map.entry("audio", 0.28), Map.entry("smart", 0.30), Map.entry("accessories", 0.52),
            Map.entry("appliances_large", 0.22), Map.entry("appliances_small", 0.30), Map.entry("climate", 0.27),
            Map.entry("beauty", 0.33), Map.entry("car", 0.32), Map.entry("emobility", 0.20), Map.entry("furniture", 0.35),
            Map.entry("household", 0.38), Map.entry("lighting", 0.42), Map.entry("office", 0.30), Map.entry("tools", 0.30)));
    /** Годовой рост закупочных цен группы. */
    private static final Map<String, Double> INFLATION = new TreeMap<>(Map.ofEntries(
            Map.entry("phones", 0.05), Map.entry("computers", 0.05), Map.entry("tv", 0.05), Map.entry("gaming", 0.05),
            Map.entry("photo", 0.05), Map.entry("audio", 0.06), Map.entry("smart", 0.06), Map.entry("accessories", 0.08)));
    private static final double OTHER_INFLATION = 0.10;

    private final Catalog cat;
    private final PriceBook prices;
    private final Suppliers sup;
    private final RandomStreams.Stream st;
    private final LocalDate from;
    private final double[] supplierFactor;

    CostModel(Catalog cat, PriceBook prices, Suppliers sup, RandomStreams.Stream st, Period period) {
        this.cat = cat;
        this.prices = prices;
        this.sup = sup;
        this.st = st;
        this.from = period.from();
        supplierFactor = new double[sup.all().size() + 1];
        for (Suppliers.Supplier s : sup.all()) {
            double k = switch (s.kind()) {
                case "brand_office" -> 0.97;
                case "distributor" -> 1.03;
                case "importer" -> 0.94;
                default -> 0.99;
            };
            supplierFactor[s.id()] = k * st.at("supplier", s.id()).logNormal(1, 0.02);
        }
    }

    static double margin(Catalog.Product p) {
        return MARGIN.getOrDefault(p.kind.group, 0.30);
    }

    static double inflation(Catalog.Product p) {
        return INFLATION.getOrDefault(p.kind.group, OTHER_INFLATION);
    }

    /** Розница без НДС на момент, копеек (до запуска — цена запуска). */
    long netRetail(Catalog.Product p, long at, LocalDate d) {
        long gross = prices.priceAt(p.id, at);
        if (gross <= 0) gross = p.launchPriceKop;
        int vat = Tax.vatRate(d);
        return Money.mulDiv(gross, 100, 100 + vat);
    }

    /**
     * Закупочная цена за штуку. {@code supplierId} — поставщик (0 — продавец FBO: его заявленная себестоимость);
     * {@code key} — ключ партии для разброса.
     */
    long unitCost(Catalog.Product p, int supplierId, long at, LocalDate d, long key) {
        int launchVat = Tax.vatRate(p.launchedOn);
        double base = p.launchPriceKop * 100.0 / (100 + launchVat) * (1 - margin(p));
        LocalDate since = p.launchedOn.isAfter(from) ? p.launchedOn : from;
        double years = Math.max(0, d.toEpochDay() - since.toEpochDay()) / 365.25;
        double c = base * StrictMath.pow(1 + inflation(p), years);
        Rng r = st.at(p.id, key);
        c *= (supplierId > 0 ? supplierFactor[supplierId] : 1.0) * r.logNormal(1, 0.025);
        long cap = (long) (netRetail(p, at, d) * (1 - margin(p) / 3));
        long kop = Math.min(Money.roundHalfUp(c), cap);
        kop += Tax.techFeeKop(p.kind.feeClass, d);
        long net = netRetail(p, at, d);
        if (kop >= net) kop = net - Math.max(1, net / 50);
        return Math.max(1, kop);
    }

    Catalog catalog() {
        return cat;
    }

    Suppliers suppliers() {
        return sup;
    }
}
