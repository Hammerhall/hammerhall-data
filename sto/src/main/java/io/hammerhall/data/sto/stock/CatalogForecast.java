package io.hammerhall.data.sto.stock;

import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.sto.catalog.Catalog;

import java.time.LocalDate;

/**
 * Прогноз из моделей каталога: популярность × жизненный цикл × доля магазина (у товаров «и магазин, и продавцы» — 60 %
 * строк у магазина) × масштаб профиля × среднее штук в строке. Нормирован так, чтобы в {@code full} в средний день было
 * ≈ {@value #LINES_PER_DAY_FULL} строк 1P (база — товары в продаже в первый день периода: он у всех прогонов общий).
 * Этим же прогнозом живёт {@link DemandDriver}; модуль заказов может взять его как есть.
 */
public final class CatalogForecast implements Forecast {
    public static final double LINES_PER_DAY_FULL = 11_500;
    private final Catalog cat;
    private final double scale;
    private final double kappa;

    public CatalogForecast(Catalog cat, Period period, double scale) {
        this.cat = cat;
        this.scale = scale;
        double sum = 0;
        LocalDate d = period.from();
        for (Catalog.Product p : cat.products()) {
            if (p.aliveOn(d)) sum += p.popularity * cat.lifecycle(p, d) * share1P(p);
        }
        this.kappa = sum > 0 ? LINES_PER_DAY_FULL / sum : 0;
    }

    /** Доля строк товара у магазина (1P). */
    public static double share1P(Catalog.Product p) {
        return switch (p.assortment) {
            case ONLY_1P -> 1.0;
            case BOTH -> 0.6;
            case ONLY_3P -> 0.0;
        };
    }

    /** Среднее штук в строке: мелочь берут по несколько. */
    public static double meanQty(Catalog.Product p) {
        return p.launchPriceKop < 1000_00 ? 1.4 : 1.05;
    }

    /** Ожидаемые строки 1P в сутки без календаря, в масштабе профиля. */
    public double lineRate(Catalog.Product p, LocalDate d) {
        return kappa * p.popularity * cat.lifecycle(p, d) * share1P(p) * scale;
    }

    @Override
    public double baseRate(Catalog.Product p, LocalDate d) {
        return lineRate(p, d) * meanQty(p);
    }

    @Override
    public double scale() {
        return scale;
    }
}
