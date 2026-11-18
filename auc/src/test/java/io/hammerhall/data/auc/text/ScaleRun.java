package io.hammerhall.data.auc.text;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Проверка на объёме выпуска: {@code java … ScaleRun [ЧИСЛО] [SEED]} — вещи по отделам с долями, близкими к выпуску
 * (по умолчанию 1,3 млн), доли повторов названий (по-русски и по-английски) и описаний — по 64-битным отпечаткам,
 * длины, скорость. Не тест: запускается скриптом с журналом.
 */
public final class ScaleRun {
    private ScaleRun() {
    }

    /** Доли отделов в приёме — примерно как в генераторе торгов. */
    static final double[] DEPT_W = {0.22, 0.18, 0.2, 0.12, 0.16, 0.12};

    static long fp(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        long h = 0xcbf29ce484222325L, g = 0x9E3779B97F4A7C15L;
        for (byte x : b) {
            h = (h ^ (x & 0xff)) * 0x100000001b3L;
            g = Long.rotateLeft(g ^ (x & 0xff), 7) * 0xff51afd7ed558ccdL;
        }
        return h ^ Long.rotateLeft(g, 31);
    }

    static double repeatShare(long[] a, int n) {
        long[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        int distinct = n == 0 ? 0 : 1;
        for (int i = 1; i < n; i++) if (c[i] != c[i - 1]) distinct++;
        return 1 - distinct / (double) n;
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 1_300_000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20251001L;
        long t0 = System.nanoTime();
        LotTexts texts = new LotTexts(seed);
        long tMakers = System.nanoTime();
        long[] ti = new long[n], te = new long[n], de = new long[n];
        long chars = 0;
        int minD = Integer.MAX_VALUE, maxD = 0, prov = 0;
        int[] lens = new int[1201];
        double[] cum = new double[DEPT_W.length];
        double s = 0;
        for (int i = 0; i < cum.length; i++) cum[i] = s += DEPT_W[i];
        Department[] ds = Department.values();
        for (int i = 0; i < n; i++) {
            double u = ((Long.hashCode(i * 0x9E3779B97F4A7C15L) & 0x7fffffff) / (double) Integer.MAX_VALUE) * s;
            int d = 0;
            while (d < cum.length - 1 && u > cum[d]) d++;
            LotText t = texts.lot(ds[d], i);
            ti[i] = fp(t.titleRu());
            te[i] = fp(t.titleEn());
            de[i] = fp(t.descriptionRu());
            int L = t.descriptionRu().length();
            chars += L + t.titleRu().length() + t.titleEn().length() + t.conditionRu().length();
            for (Provenance p : t.provenance()) chars += p.textRu().length();
            if (!t.provenance().isEmpty()) prov++;
            minD = Math.min(minD, L);
            maxD = Math.max(maxD, L);
            lens[Math.min(L, 1200)]++;
            if ((i + 1) % 100_000 == 0) {
                System.out.printf(Locale.ROOT, "  %,d вещей, %.1f с%n", i + 1, (System.nanoTime() - tMakers) / 1e9);
            }
        }
        long t1 = System.nanoTime();
        int acc = 0, median = 0;
        for (int i = 0; i < lens.length; i++) {
            acc += lens[i];
            if (acc >= n / 2) {
                median = i;
                break;
            }
        }
        System.out.printf(Locale.ROOT, "вещей %,d: справочник и места торгов %.2f с, тексты %.1f с (%.0f вещей/с, %.1f МБ текста)%n",
                n, (tMakers - t0) / 1e9, (t1 - tMakers) / 1e9, n / ((t1 - tMakers) / 1e9), chars / 1e6);
        System.out.printf(Locale.ROOT, "повторы: названий %.1f %%, английских названий %.1f %%, описаний %.4f %%%n",
                100 * repeatShare(ti, n), 100 * repeatShare(te, n), 100 * repeatShare(de, n));
        System.out.printf(Locale.ROOT, "длина описания %d…%d, медиана %d; с происхождением %.1f %%%n", minD, maxD, median, 100.0 * prov / n);
    }
}
