package io.hammerhall.data.auc.bid;

import java.util.Arrays;

/**
 * Таблица шага ставки: от текущей цены — шаг. Деньги — {@code long} копеек. Правило торгов: следующая ставка — не
 * меньше {@code текущая + шаг(текущая)} ({@link #minNext}), первая — не меньше стартовой цены.
 *
 * <p><b>Сетка.</b> Ступень {@code i} действует на {@code [from[i], from[i+1])}; порог ступени кратен и своему шагу, и
 * шагу предыдущей ступени. Тогда допустимые суммы ступени — кратные её шагу («на сетке»), {@code текущая + шаг} с
 * сетки не сходит, а следующий порог достигается ровно. Все суммы, которые видит движок (максимумы, ручные ставки,
 * стартовая цена), сначала приводятся к сетке: максимум и ставка — вниз ({@link #floor}, как дома округляют заочные
 * ставки «до ближайшего шага вниз»), стартовая цена — вверх ({@link #ceil}).
 *
 * <p>Из сетки следует свойство, на котором держатся автоставки: если {@code a < b} и обе на сетке, то
 * {@code b ≥ a + шаг(a)}. Поэтому «второй максимум + шаг» никогда не перескакивает больший максимум.
 */
public final class IncrementTable {
    private final long[] from;
    private final long[] step;

    private IncrementTable(long[] from, long[] step) {
        this.from = from;
        this.step = step;
    }

    /**
     * Таблица из порогов и шагов (копейки): {@code from[0] = 0}, пороги строго растут, шаги положительны, каждый порог
     * кратен своему шагу и шагу предыдущей ступени.
     */
    public static IncrementTable of(long[] fromKop, long[] stepKop) {
        if (fromKop.length == 0 || fromKop.length != stepKop.length) {
            throw new IllegalArgumentException("порогов " + fromKop.length + ", шагов " + stepKop.length);
        }
        if (fromKop[0] != 0) throw new IllegalArgumentException("первый порог — 0, а не " + fromKop[0]);
        for (int i = 0; i < fromKop.length; i++) {
            if (stepKop[i] <= 0) throw new IllegalArgumentException("шаг ступени " + i + " — " + stepKop[i]);
            if (i > 0) {
                if (fromKop[i] <= fromKop[i - 1]) throw new IllegalArgumentException("пороги не растут: " + i);
                if (fromKop[i] % stepKop[i] != 0 || fromKop[i] % stepKop[i - 1] != 0) {
                    throw new IllegalArgumentException("порог " + fromKop[i] + " не кратен шагам " + stepKop[i - 1]
                            + " и " + stepKop[i] + " — сетка ставок разойдётся");
                }
            }
        }
        return new IncrementTable(fromKop.clone(), stepKop.clone());
    }

    /** Таблица в рублях: пары «порог, шаг» подряд — {@code rub(0, 100, 2_000, 200, …)}. */
    public static IncrementTable rub(long... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("нечётное число значений: " + pairs.length);
        long[] f = new long[pairs.length / 2], s = new long[pairs.length / 2];
        for (int i = 0; i < f.length; i++) {
            f[i] = Math.multiplyExact(pairs[2 * i], 100L);
            s[i] = Math.multiplyExact(pairs[2 * i + 1], 100L);
        }
        return of(f, s);
    }

    /**
     * Таблица для тестов движка — шаги дома (ряд 1 — 3 — 6 — 10, шаг 3–10 % цены): до 1 000 ₽ — 50;
     * 1–3 тыс. — 100; 3–6 тыс. — 250; 6–10 тыс. — 500; 10–30 тыс. — 1 000; 30–60 тыс. — 2 500; 60–100 тыс. — 5 000;
     * 100–300 тыс. — 10 000; 300–600 тыс. — 25 000; 0,6–1 млн — 50 000; 1–3 млн — 100 000; 3–6 млн — 250 000;
     * 6–10 млн — 500 000; от 10 млн — 1 000 000. <b>Генератор набора её не берёт:</b> таблица шагов всегда приходит
     * снаружи — из таблицы выпуска {@code increment_bands} ({@link AuctionRules#house(IncrementTable)}).
     */
    public static IncrementTable standard() {
        return STANDARD;
    }

    private static final IncrementTable STANDARD = rub(
            0, 50,
            1_000, 100,
            3_000, 250,
            6_000, 500,
            10_000, 1_000,
            30_000, 2_500,
            60_000, 5_000,
            100_000, 10_000,
            300_000, 25_000,
            600_000, 50_000,
            1_000_000, 100_000,
            3_000_000, 250_000,
            6_000_000, 500_000,
            10_000_000, 1_000_000);

    private int band(long price) {
        if (price < 0) throw new IllegalArgumentException("цена " + price);
        int lo = 0, hi = from.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (from[mid] <= price) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** Шаг при текущей цене {@code price}. */
    public long step(long price) {
        return step[band(price)];
    }

    /** Наименьшая допустимая следующая ставка: {@code price + шаг(price)}. */
    public long minNext(long price) {
        return price + step[band(price)];
    }

    /** На сетке ли сумма. */
    public boolean onGrid(long amount) {
        return amount >= 0 && amount % step[band(amount)] == 0;
    }

    /** Вниз к сетке (не выходит из своей ступени: порог ступени — на её сетке). */
    public long floor(long amount) {
        if (amount <= 0) return 0;
        return amount - amount % step[band(amount)];
    }

    /** Вверх к сетке: наименьшая сумма сетки, не меньше {@code amount}. */
    public long ceil(long amount) {
        long f = floor(amount);
        return f == amount ? f : minNext(f);
    }

    public int bands() {
        return from.length;
    }

    /** Порог ступени {@code i}, копейки. */
    public long bandFrom(int i) {
        return from[i];
    }

    /** Шаг ступени {@code i}, копейки. */
    public long bandStep(int i) {
        return step[i];
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IncrementTable t && Arrays.equals(from, t.from) && Arrays.equals(step, t.step);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(from) + Arrays.hashCode(step);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("IncrementTable[");
        for (int i = 0; i < from.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(from[i] / 100).append('+').append(step[i] / 100);
        }
        return sb.append(" ₽]").toString();
    }
}
