package io.hammerhall.data.core.money;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * Налоговый календарь внутри периода (сверено по открытым источникам 14.11.2026; до выпуска — сверить ещё раз).
 *
 * <ul>
 *   <li>НДС: 20 % по 31.12.2025, 22 % с 01.01.2026 (закон подписан 28.11.2025).</li>
 *   <li>Технологический сбор с электроники — с 01.12.2026: фиксированная сумма за единицу, платят импортёр и
 *       производитель; в розничной цене — ступенька на сбор с НДС. Ставки предварительные: смартфон 250 ₽,
 *       ноутбук 500 ₽; прочие — по постановлению правительства (пока не охвачены).</li>
 * </ul>
 */
public final class Tax {
    private Tax() {
    }

    public static final LocalDate VAT_22_FROM = LocalDate.of(2026, 1, 1);
    public static final LocalDate TECH_FEE_FROM = LocalDate.of(2026, 12, 1);

    /** Классы технологического сбора → копеек за единицу. */
    private static final Map<String, Long> TECH_FEE_KOP = new TreeMap<>(Map.of(
            "smartphone", 250_00L,
            "laptop", 500_00L));

    /** Ставка НДС, %, на дату (по дате операции). */
    public static int vatRate(LocalDate d) {
        return d.isBefore(VAT_22_FROM) ? 20 : 22;
    }

    /** Технологический сбор за единицу, копеек, на дату; 0 — не охвачено или раньше срока. */
    public static long techFeeKop(String feeClass, LocalDate d) {
        if (feeClass == null || d.isBefore(TECH_FEE_FROM)) return 0;
        return TECH_FEE_KOP.getOrDefault(feeClass, 0L);
    }

    public static Map<String, Long> techFeeClasses() {
        return TECH_FEE_KOP;
    }
}
