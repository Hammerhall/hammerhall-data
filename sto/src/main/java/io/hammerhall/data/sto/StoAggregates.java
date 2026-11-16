package io.hammerhall.data.sto;

import io.hammerhall.data.core.check.Aggregate;

import java.util.List;

import static io.hammerhall.data.core.check.Aggregate.countEq;
import static io.hammerhall.data.core.check.Aggregate.distinct;
import static io.hammerhall.data.core.check.Aggregate.max;
import static io.hammerhall.data.core.check.Aggregate.min;
import static io.hammerhall.data.core.check.Aggregate.sum;

/**
 * Контрольные агрегаты таблиц волны 1 (каталог, цены, география, поставщики) — для файла ожиданий и проверки
 * выпуска. Новые модули объявляют свои в {@code Module.aggregates()}; ядро добавляет к каждой таблице суммы денежных
 * полей и число NULL необязательных. Агрегат — не про заложенные узоры (их доли не публикуются), а про «что
 * разошлось», если содержимое не совпало.
 */
public final class StoAggregates {
    private StoAggregates() {
    }

    public static List<Aggregate> wave1() {
        return List.of(
                // каталог
                countEq("categories", "level", "1"), max("categories", "level"),
                countEq("brands", "is_private_label", "t"), distinct("brands", "origin_country"), countEq("brands", "tier", "premium"),
                countEq("products", "is_bundle", "t"), distinct("products", "brand_id"), distinct("products", "category_id"),
                min("products", "launched_on"), max("products", "launched_on"), sum("products", "weight_g"), max("products", "generation"),
                distinct("product_specs", "key"), sum("product_specs", "value_num"),
                sum("bundle_items", "qty"), distinct("bundle_items", "bundle_id"),
                min("prices", "price"), max("prices", "price"), min("prices", "valid_from"), max("prices", "valid_from"),
                distinct("prices", "product_id"),
                countEq("promotions", "kind", "promocode"), countEq("promotions", "stackable", "t"), sum("promotions", "discount_pct"),
                min("promotions", "valid_from"), max("promotions", "valid_to"),
                distinct("promotion_targets", "promo_id"),
                // география и склады
                sum("cities", "population"), distinct("cities", "timezone"), distinct("cities", "federal_district"),
                countEq("warehouses", "kind", "dc"), countEq("warehouses", "kind", "pickup_point"), max("warehouses", "opened_on"),
                sum("storage_cells", "max_weight_kg"), sum("storage_cells", "max_volume_l"), distinct("storage_cells", "warehouse_id"),
                distinct("carriers", "kind"),
                sum("delivery_zones", "base_days"), max("delivery_zones", "ring"), distinct("delivery_zones", "warehouse_id"),
                // поставщики
                sum("suppliers", "reliability"), sum("suppliers", "lead_time_days"), distinct("suppliers", "city_id"));
    }
}
