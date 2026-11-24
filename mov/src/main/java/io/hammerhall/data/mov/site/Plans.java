package io.hammerhall.data.mov.site;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Тарифы и история цен (§1.2): бесплатный с рекламой, базовый, стандарт, семейный и годовые к ним (цена года — как
 * десять месяцев). Цены — ступенями: ввод тарифа, повышение 01.02.2024 и повышение ≈ 10 % с 01.03.2026. Цена списания
 * — по московской дате списания ({@link #priceKop}). Постоянный список: от периода не зависит.
 */
final class Plans {
    private Plans() {
    }

    /** Ступень тарифа: 0 — бесплатный, 1 — базовый, 2 — стандарт, 3 — семейный. */
    record Plan(int id, String code, String name, int tier, int months, int streams, int quality, boolean ads,
                LocalDate introduced, LocalDate[] from, long[] priceKop) {
        long monthlyKop() {
            return months == 1 ? priceKop[0] : Math.round(priceKop[0] / 12.0);
        }
    }

    static final int FREE = 1;
    static final LocalDate RISE_2024 = LocalDate.of(2024, 2, 1);

    static final List<Plan> ALL = build();

    private static List<Plan> build() {
        LocalDate s = SiteRules.SERVICE_START, fam = LocalDate.of(2020, 9, 1), year = LocalDate.of(2021, 3, 1);
        LocalDate r = SiteRules.PRICE_RISE;
        List<Plan> p = new ArrayList<>();
        p.add(new Plan(1, "free", "Бесплатный", 0, 1, 1, 0, true, s, new LocalDate[]{s}, new long[]{0}));
        p.add(new Plan(2, "basic", "Базовый", 1, 1, 1, 1, false, s, new LocalDate[]{s, RISE_2024, r},
                new long[]{249_00, 299_00, 329_00}));
        p.add(new Plan(3, "standard", "Стандарт", 2, 1, 2, 2, false, s, new LocalDate[]{s, RISE_2024, r},
                new long[]{379_00, 449_00, 499_00}));
        p.add(new Plan(4, "family", "Семейный", 3, 1, 4, 3, false, fam, new LocalDate[]{fam, RISE_2024, r},
                new long[]{549_00, 649_00, 719_00}));
        p.add(new Plan(5, "basic_year", "Базовый на год", 1, 12, 1, 1, false, year, new LocalDate[]{year, RISE_2024, r},
                new long[]{2490_00, 2990_00, 3290_00}));
        p.add(new Plan(6, "standard_year", "Стандарт на год", 2, 12, 2, 2, false, year, new LocalDate[]{year, RISE_2024, r},
                new long[]{3790_00, 4490_00, 4990_00}));
        p.add(new Plan(7, "family_year", "Семейный на год", 3, 12, 4, 3, false, year, new LocalDate[]{year, RISE_2024, r},
                new long[]{5490_00, 6490_00, 7190_00}));
        return List.copyOf(p);
    }

    static Plan get(int id) {
        return ALL.get(id - 1);
    }

    /** Тариф ступени {@code tier} с периодом {@code months}. */
    static Plan of(int tier, int months) {
        for (Plan p : ALL) if (p.tier == tier && p.months == months) return p;
        throw new IllegalArgumentException("нет тарифа " + tier + "/" + months);
    }

    /** Цена за период оплаты на московскую дату. */
    static long priceKop(int planId, LocalDate mskDate) {
        Plan p = get(planId);
        long v = p.priceKop[0];
        for (int i = 0; i < p.from.length; i++) if (!mskDate.isBefore(p.from[i])) v = p.priceKop[i];
        return v;
    }

    /** Цена со скидкой {@code pct} %, до рубля вниз — как в кассе сервиса. */
    static long discounted(long kop, int pct) {
        long v = kop * (100 - pct) / 100;
        return v / 100 * 100;
    }
}
