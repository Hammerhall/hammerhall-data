package io.hammerhall.data.sto.supply;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Поставщики магазина ({@code ctx.get(StoKeys.SUPPLIERS)}): как в таблице {@code suppliers}, плюс какие бренды каждый
 * возит — этого в таблицах нет (связь видна через заказы поставщикам и партии, которые пишет модуль поставок).
 */
public final class Suppliers {

    public record Supplier(int id, String nameRu, String nameEn, String kind, int cityId, int leadTimeDays,
                           BigDecimal reliability, List<Integer> brandIds) {
    }

    private final List<Supplier> all;
    private final Map<Integer, List<Supplier>> byBrand = new TreeMap<>();

    public Suppliers(List<Supplier> all) {
        this.all = List.copyOf(all);
        for (Supplier s : all) for (int b : s.brandIds()) byBrand.computeIfAbsent(b, k -> new ArrayList<>()).add(s);
    }

    public List<Supplier> all() {
        return all;
    }

    public Supplier supplier(int id) {
        return all.get(id - 1);
    }

    /** Кто возит бренд (по id поставщика). */
    public List<Supplier> ofBrand(int brandId) {
        return Collections.unmodifiableList(byBrand.getOrDefault(brandId, List.of()));
    }
}
