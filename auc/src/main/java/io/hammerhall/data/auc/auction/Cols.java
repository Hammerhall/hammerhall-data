package io.hammerhall.data.auc.auction;

import java.util.Arrays;

/**
 * Столбцы примитивов кусками по 2^14 значений: растут без копирования и без запаса «вдвое» (лишнего — не больше куска).
 * Для мира ({@link WorldData}): миллионы лотов, ставок и уведомлений держатся до конца прогона.
 */
public final class Cols {
    static final int SHIFT = 14, CHUNK = 1 << SHIFT, MASK = CHUNK - 1;

    private Cols() {
    }

    /** {@code long}. */
    public static final class Longs {
        private long[][] c = new long[8][];
        private int n;

        public void add(long v) {
            int k = n >>> SHIFT;
            if (k == c.length) c = Arrays.copyOf(c, k * 2);
            if (c[k] == null) c[k] = new long[CHUNK];
            c[k][n & MASK] = v;
            n++;
        }

        public long get(int i) {
            return c[i >>> SHIFT][i & MASK];
        }

        public void set(int i, long v) {
            if (i >= n) throw new IndexOutOfBoundsException(i);
            c[i >>> SHIFT][i & MASK] = v;
        }

        public int size() {
            return n;
        }
    }

    /** {@code int}. */
    public static final class Ints {
        private int[][] c = new int[8][];
        private int n;

        public void add(int v) {
            int k = n >>> SHIFT;
            if (k == c.length) c = Arrays.copyOf(c, k * 2);
            if (c[k] == null) c[k] = new int[CHUNK];
            c[k][n & MASK] = v;
            n++;
        }

        public int get(int i) {
            return c[i >>> SHIFT][i & MASK];
        }

        public void set(int i, int v) {
            if (i >= n) throw new IndexOutOfBoundsException(i);
            c[i >>> SHIFT][i & MASK] = v;
        }

        public int size() {
            return n;
        }
    }

    /** Секунды Unix в {@code int} (до 2038 г.; вне диапазона — ошибка, не обрезка). */
    public static final class Secs {
        private final Ints s = new Ints();

        public void add(long sec) {
            s.add(Math.toIntExact(sec));
        }

        public long get(int i) {
            return s.get(i);
        }

        public void set(int i, long sec) {
            s.set(i, Math.toIntExact(sec));
        }

        public int size() {
            return s.size();
        }
    }

    /** Момент в мс: секунды ({@code int}) и миллисекунды ({@code short}). */
    public static final class Millis {
        private final Ints sec = new Ints();
        private final Shorts ms = new Shorts();

        public void add(long atMs) {
            sec.add(Math.toIntExact(Math.floorDiv(atMs, 1000L)));
            ms.add((short) Math.floorMod(atMs, 1000L));
        }

        public long get(int i) {
            return sec.get(i) * 1000L + ms.get(i);
        }

        public int size() {
            return sec.size();
        }
    }

    /**
     * Деньги в копейках: целыми рублями в {@code int}, если копеек нет и рублей меньше 2^31 (почти всегда — шаги ставок и
     * эстимейты в рублях); иначе — точное значение сбоку. Читается то же число, что записано.
     */
    public static final class Kops {
        private static final int ASIDE = Integer.MIN_VALUE;
        private final Ints rub = new Ints();
        private final java.util.HashMap<Integer, Long> aside = new java.util.HashMap<>();

        public void add(long kop) {
            long r = kop / 100;
            if (kop % 100 == 0 && r > ASIDE && r <= Integer.MAX_VALUE) {
                rub.add((int) r);
            } else {
                aside.put(rub.size(), kop);
                rub.add(ASIDE);
            }
        }

        public long get(int i) {
            int r = rub.get(i);
            return r != ASIDE ? r * 100L : aside.get(i);
        }

        public int size() {
            return rub.size();
        }
    }

    /** {@code short}. */
    public static final class Shorts {
        private short[][] c = new short[8][];
        private int n;

        public void add(short v) {
            int k = n >>> SHIFT;
            if (k == c.length) c = Arrays.copyOf(c, k * 2);
            if (c[k] == null) c[k] = new short[CHUNK];
            c[k][n & MASK] = v;
            n++;
        }

        public short get(int i) {
            return c[i >>> SHIFT][i & MASK];
        }

        public int size() {
            return n;
        }
    }

    /** {@code byte}. */
    public static final class Bytes {
        private byte[][] c = new byte[8][];
        private int n;

        public void add(byte v) {
            int k = n >>> SHIFT;
            if (k == c.length) c = Arrays.copyOf(c, k * 2);
            if (c[k] == null) c[k] = new byte[CHUNK];
            c[k][n & MASK] = v;
            n++;
        }

        public void add(int v) {
            add((byte) v);
        }

        public byte get(int i) {
            return c[i >>> SHIFT][i & MASK];
        }

        public int size() {
            return n;
        }
    }

    /** Сравнение индексов. */
    public interface IndexOrder {
        int compare(int a, int b);
    }

    /**
     * Индексы {@code 0..n-1}, упорядоченные по {@code order}; при равенстве — по возрастанию индекса (слияние устойчиво).
     * Память — два {@code int[n]}.
     */
    public static int[] sortedIndex(int n, IndexOrder order) {
        int[] a = new int[n], b = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        // вставки отрезками по 32, затем слияния снизу вверх
        final int run = 32;
        for (int lo = 0; lo < n; lo += run) {
            int hi = Math.min(n, lo + run);
            for (int i = lo + 1; i < hi; i++) {
                int x = a[i], j = i - 1;
                while (j >= lo && order.compare(a[j], x) > 0) {
                    a[j + 1] = a[j];
                    j--;
                }
                a[j + 1] = x;
            }
        }
        for (int w = run; w < n; w *= 2) {
            for (int lo = 0; lo < n; lo += 2 * w) {
                int mid = Math.min(n, lo + w), hi = Math.min(n, lo + 2 * w);
                int i = lo, j = mid, k = lo;
                if (mid >= hi || order.compare(a[mid - 1], a[mid]) <= 0) {
                    System.arraycopy(a, lo, b, lo, hi - lo);
                    continue;
                }
                while (i < mid && j < hi) b[k++] = order.compare(a[j], a[i]) < 0 ? a[j++] : a[i++];
                while (i < mid) b[k++] = a[i++];
                while (j < hi) b[k++] = a[j++];
            }
            int[] t = a;
            a = b;
            b = t;
        }
        return a;
    }
}
