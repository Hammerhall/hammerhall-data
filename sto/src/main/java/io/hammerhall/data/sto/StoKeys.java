package io.hammerhall.data.sto;

import io.hammerhall.data.core.gen.Key;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.PriceBook;
import io.hammerhall.data.sto.catalog.Promotions;
import io.hammerhall.data.sto.geo.Geo;
import io.hammerhall.data.sto.supply.Suppliers;

/** Ключи общего мира домена {@code sto}: что какой модуль кладёт для следующих. */
public final class StoKeys {
    private StoKeys() {
    }

    /** Модуль {@code geo}: города, склады, ячейки, перевозчики, зоны. */
    public static final Key<Geo> GEO = Key.of("sto.geo");
    /** Модуль {@code catalog}: категории, бренды, товары, характеристики, комплекты. */
    public static final Key<Catalog> CATALOG = Key.of("sto.catalog");
    /** Модуль {@code pricing}: цены на любой момент. */
    public static final Key<PriceBook> PRICES = Key.of("sto.prices");
    /** Модуль {@code pricing}: акции и их цели. */
    public static final Key<Promotions> PROMOTIONS = Key.of("sto.promotions");
    /** Модуль {@code suppliers}: поставщики и что они возят. */
    public static final Key<Suppliers> SUPPLIERS = Key.of("sto.suppliers");
    /** Модуль {@code market}: продавцы маркетплейса, их предложения и скрытые черты. */
    public static final Key<io.hammerhall.data.sto.market.Market> MARKET = Key.of("sto.market");
    /** Модуль {@code customers}: клиенты и адреса, черты и активность клиентов. */
    public static final Key<io.hammerhall.data.sto.customers.Customers> CUSTOMERS = Key.of("sto.customers");
}
