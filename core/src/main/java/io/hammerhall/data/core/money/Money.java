package io.hammerhall.data.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Деньги — рубли с копейками. Внутри генератора — {@code long} копеек (точно, быстро, без аллокаций); в TSV —
 * десятичная строка с двумя знаками ({@code 1290.00}); наружу — {@link BigDecimal} со шкалой 2. Округление —
 * половина вверх (от нуля), как в кассовых расчётах.
 */
public final class Money {
    private Money() {
    }

    /** Копейки → «1290.00», «-5.10». */
    public static String format(long kop) {
        boolean neg = kop < 0;
        long a = Math.abs(kop);
        long rub = a / 100, k = a % 100;
        StringBuilder sb = new StringBuilder(16);
        if (neg) sb.append('-');
        sb.append(rub).append('.');
        if (k < 10) sb.append('0');
        sb.append(k);
        return sb.toString();
    }

    public static BigDecimal toBigDecimal(long kop) {
        return BigDecimal.valueOf(kop, 2);
    }

    public static long fromBigDecimal(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).unscaledValue().longValueExact();
    }

    public static long rub(double rubles) {
        return roundHalfUp(rubles * 100.0);
    }

    public static long roundHalfUp(double kop) {
        return kop >= 0 ? (long) StrictMath.floor(kop + 0.5) : -(long) StrictMath.floor(-kop + 0.5);
    }

    /** a·num/den с округлением половины вверх, целочисленно (без потери точности). */
    public static long mulDiv(long a, long num, long den) {
        if (den <= 0) throw new IllegalArgumentException("den " + den);
        long p = Math.multiplyExact(a, num);
        long q = Math.floorDiv(p, den), r = Math.floorMod(p, den);
        return 2 * r >= den ? q + 1 : q;
    }

    /** НДС, входящий в цену с НДС: gross·rate/(100+rate). */
    public static long vatIncluded(long grossKop, int ratePct) {
        return mulDiv(grossKop, ratePct, 100 + ratePct);
    }

    /** НДС сверху на сумму без НДС: net·rate/100. */
    public static long vatOnTop(long netKop, int ratePct) {
        return mulDiv(netKop, ratePct, 100);
    }

    /** Процент от суммы (процент — с точностью до сотых: 12.5 % → 1250). */
    public static long percent(long kop, long pctHundredths) {
        return mulDiv(kop, pctHundredths, 100_00);
    }

    /**
     * Розничная цена «на 90 / на 990»: до 300 ₽ — «199.90»; до 10 000 ₽ — «на 90» ({@code 1 290}); до 50 000 ₽ —
     * шаг 500 ({@code 12 490}, {@code 12 990}); дороже — «на 990» ({@code 54 990}). Округляется к ближайшему такому
     * значению, не ниже {@code 9.90}.
     */
    public static long retail(double rubles) {
        if (rubles < 300) {
            long r = StrictMath.round(rubles);
            return Math.max(9_90L, r * 100 - 10);
        }
        if (rubles < 10_000) {
            long h = StrictMath.round(rubles / 100.0);
            return Math.max(3, h) * 100_00 - 10_00;
        }
        if (rubles < 50_000) {
            long f = StrictMath.round(rubles / 500.0);
            return f * 500_00 - 10_00;
        }
        long t = StrictMath.round(rubles / 1000.0);
        return t * 1000_00 - 10_00;
    }

    /** Розничная цена не ниже {@code minKop} (для ступенек: новая цена не меньше старой плюс сбор). */
    public static long retailAtLeast(double rubles, long minKop) {
        long p = retail(rubles);
        double r = rubles;
        while (p < minKop) {
            r *= 1.01;
            p = retail(r + 1);
        }
        return p;
    }
}
