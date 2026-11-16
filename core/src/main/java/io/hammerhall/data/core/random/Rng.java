package io.hammerhall.data.core.random;

import java.util.List;

/**
 * Генератор случайных чисел SplitMix64 — свой, не из JDK: алгоритм зафиксирован здесь, поэтому выпуск не зависит
 * от версии JDK и платформы. Экземпляр не потокобезопасен и живёт недолго: его выдаёт {@link RandomStreams} под ключ
 * сущности, им пользуются и бросают.
 *
 * <p>Вся математика с плавающей точкой — через {@link StrictMath}: {@code Math.log/cos} вправе давать разный
 * последний бит на разных процессорах, {@code StrictMath} — нет.
 */
public final class Rng {
    private static final long GOLDEN = 0x9e3779b97f4a7c15L;
    private long state;

    public Rng(long seed) {
        this.state = seed;
    }

    /** Финализатор SplitMix64 — хорошее перемешивание 64 бит. */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    public long nextLong() {
        state += GOLDEN;
        return mix64(state);
    }

    /** Равномерно в [0, bound), без смещения (отбрасывание хвоста). */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound " + bound);
        long b = bound;
        long limit = Long.MAX_VALUE - (Long.MAX_VALUE % b) - 1;
        long r;
        do {
            r = nextLong() >>> 1;
        } while (r > limit);
        return (int) (r % b);
    }

    /** Равномерно в [lo, hi] включительно. */
    public int between(int lo, int hi) {
        if (hi < lo) throw new IllegalArgumentException(lo + ".." + hi);
        return lo + nextInt(hi - lo + 1);
    }

    /** Равномерно в [0, 1), 53 бита. */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    public double uniform(double a, double b) {
        return a + (b - a) * nextDouble();
    }

    public boolean chance(double p) {
        return nextDouble() < p;
    }

    /** Нормальное N(0, 1) — Бокс — Мюллер, одно значение на вызов (без запаса — состояние не копится). */
    public double gaussian() {
        double u1 = nextDouble();
        double u2 = nextDouble();
        if (u1 < 1e-300) u1 = 1e-300;
        return StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * StrictMath.PI * u2);
    }

    /** Логнормальное с медианой {@code median} и разбросом {@code sigma} (в логарифме). */
    public double logNormal(double median, double sigma) {
        return median * StrictMath.exp(sigma * gaussian());
    }

    /** Нормальное, обрезанное до [lo, hi]. */
    public double gaussianClamped(double mean, double sd, double lo, double hi) {
        double v = mean + sd * gaussian();
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Экспоненциальное с заданным средним. */
    public double exponential(double mean) {
        double u = nextDouble();
        return -mean * StrictMath.log(1.0 - u);
    }

    /** Пуассон (Кнут для малых λ, нормальное приближение для больших). */
    public int poisson(double lambda) {
        if (lambda <= 0) return 0;
        if (lambda > 60) {
            long v = StrictMath.round(lambda + StrictMath.sqrt(lambda) * gaussian());
            return (int) Math.max(0, v);
        }
        double l = StrictMath.exp(-lambda);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= nextDouble();
        } while (p > l);
        return k - 1;
    }

    public <T> T pick(List<T> items) {
        return items.get(nextInt(items.size()));
    }

    public <T> T pick(T[] items) {
        return items[nextInt(items.length)];
    }

    /** Индекс по весам (веса не обязаны суммироваться в 1). */
    public int weighted(double[] weights) {
        double total = 0;
        for (double w : weights) total += w;
        double x = nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            x -= weights[i];
            if (x < 0) return i;
        }
        return weights.length - 1;
    }

    /** Перемешивание Фишера — Йетса на месте. */
    public <T> void shuffle(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            T t = list.get(i);
            list.set(i, list.get(j));
            list.set(j, t);
        }
    }

    public void shuffle(int[] a) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }
}
