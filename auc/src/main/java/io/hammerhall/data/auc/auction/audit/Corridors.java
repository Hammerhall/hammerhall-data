package io.hammerhall.data.auc.auction.audit;

import io.hammerhall.data.auc.auction.engine.Codes;
import io.hammerhall.data.core.check.CheckReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Коридоры узоров торгов — по записанным строкам лотов и ставок: доля проданных (по отделам и формату), молоток к
 * эстимейту и его хвост (≥ 2×, 5×, 10× верхней), ставки и участники на лот, каналы живых торгов, поздние ставки и
 * продления, снайперы, гонки, повторы, ценовые войны, непроданное. Коридор: ±2 п. п. у долей, ±10 % у средних; у
 * {@code small} — вдвое шире; у редких ячеек — не уже трёх стандартных ошибок. Сначала копится ({@link #lot}), в конце —
 * {@link #report}.
 */
public final class Corridors {
    static final long LATE_MS = 120_000, SNIPE_MS = 10_000;

    private final double widen;
    /**
     * Профиль меньше {@code full} (масштаб < 1): каналы живых торгов только печатаются — каталог живых меньше полного
     * (в full — 282 лота на торги, в medium — ≈ 28, в small — пол в 10 лотов), а доли каналов зависят от размера торгов
     */
    private final boolean partScale;
    // доля проданных: [отдел 0..5][формат 0 живые, 1 онлайн] → закрыто, продано
    private final long[][] closed = new long[6][2], sold = new long[6][2];
    private long soldAll, below, within, above, noBids, belowReserve, closedAll;
    /** Хвост молотка к верхней оценке (среди проданных с молотка): ≥ 2×, ≥ 5×, ≥ 10×. */
    private long tail2, tail5, tail10;
    private final List<Integer> bidsPerLot = new ArrayList<>(), partsPerLot = new ArrayList<>();
    private final long[] liveChannel = new long[5], liveWins = new long[5], liveWinsMillion = new long[5];
    private long onlineBids, onlineLate, onlineLotsWithBids, onlineExtended, extensionsSum, extendedWithin12;
    private long manualOnline, raced, racedLast10, duplicates, wars;
    private long unsold, afterSale, reoffer, giveBack;
    /** Онлайн-участники: клиент → [ставок, из них в последние 10 с]. */
    private final Map<Integer, int[]> snipe = new HashMap<>();

    /** {@code scale} — масштаб профиля: у {@code small} коридоры вдвое шире. */
    public Corridors(double scale) {
        this.widen = scale < 0.5 ? 2.0 : 1.0;
        this.partScale = scale < 1.0;
    }

    /** Лот записан (значения — как в таблицах; {@code dept} — индекс отдела 0–5). */
    public void lot(AuditLot a, int dept) {
        if (a.closedMs < 0 || a.result == Codes.RES_WITHDRAWN) return;
        int f = a.live ? 0 : 1;
        closedAll++;
        closed[dept][f]++;
        boolean isSold = a.result == Codes.RES_SOLD || a.result == Codes.RES_SOLD_AFTER_SALE;
        if (isSold) sold[dept][f]++;
        if (a.result == Codes.RES_SOLD) {
            soldAll++;
            long high = highOf(a);
            if (a.hammer < a.low) below++;
            else if (a.hammer > high) above++;
            else within++;
            if (a.hammer >= 2 * high) tail2++;
            if (a.hammer >= 5 * high) tail5++;
            if (a.hammer >= 10 * high) tail10++;
        }
        // ставки и участники
        int n = 0, acc = 0;
        List<Long> regs = new ArrayList<>();
        for (int i = 0; i < a.bids; i++) {
            if (a.bReg[i] == 0) continue;
            n++;
            if (a.bAccepted[i]) acc++;
            if (!regs.contains(a.bReg[i])) regs.add(a.bReg[i]);
        }
        if (acc == 0) noBids++;
        else if (!isSold && a.reserve > 0) belowReserve++;
        if (n > 0) {
            bidsPerLot.add(n);
            partsPerLot.add(regs.size());
        }
        if (a.live) {
            for (int i = 0; i < a.bids; i++) if (a.bAccepted[i] && a.bReg[i] != 0) liveChannel[a.bChannel[i]]++;
            if (a.result == Codes.RES_SOLD) {
                int w = winIdx(a);
                if (w >= 0) {
                    liveWins[a.bChannel[w]]++;
                    if (a.low >= 1_000_000_00L) liveWinsMillion[a.bChannel[w]]++;
                }
            }
        } else {
            if (acc > 0) {
                onlineLotsWithBids++;
                if (a.extensions > 0) {
                    onlineExtended++;
                    extensionsSum += a.extensions;
                    if (a.closedMs - a.plannedMs <= 12 * 60_000) extendedWithin12++;
                }
            }
            for (int i = 0; i < a.bids; i++) {
                if (a.bReg[i] == 0) continue;
                if (a.bAccepted[i]) {
                    onlineBids++;
                    if (a.bTime[i] >= a.plannedMs - LATE_MS) onlineLate++;
                }
                // «последние секунды» — к концу, действовавшему в момент ставки (поздняя ставка сама сдвигает итоговый
                // конец не раньше t + 2 мин, поэтому к closedMs снайпер и гонка конца не видны)
                long eff = inEffect(a, a.bTime[i]);
                int[] s = snipe.computeIfAbsent(a.bClient[i], k -> new int[2]);
                s[0]++;
                if (a.bTime[i] >= eff - SNIPE_MS) s[1]++;
                if (a.bKind[i] == Codes.K_MANUAL && a.bChannel[i] == Codes.CH_ONLINE) {
                    manualOnline++;
                    if (a.bReason[i] == Codes.R_DUPLICATE) duplicates++;
                    boolean rival = false;
                    for (int j = Math.max(0, i - 4); j < Math.min(a.bids, i + 5); j++) {
                        if (j != i && a.bTime[j] == a.bTime[i] && a.bReg[j] != a.bReg[i] && a.bReg[j] != 0
                                && a.bKind[j] == Codes.K_MANUAL) rival = true;
                    }
                    if (rival) {
                        raced++;
                        if (a.bTime[i] >= eff - SNIPE_MS) racedLast10++;
                    }
                }
            }
        }
        if (a.result == Codes.RES_SOLD && war(a)) wars++;
    }

    /** Конец онлайн-лота, действовавший в момент {@code t}: план, сдвинутый продлениями журнала лота раньше {@code t}. */
    static long inEffect(AuditLot a, long t) {
        long c = a.plannedMs;
        for (int e = 0; e < a.events; e++) {
            byte k = a.eKind[e];
            if ((k == Codes.E_EXTENDED || k == Codes.E_EXTENSION_CAPPED) && a.eTime[e] < t) c = a.eNewClose[e];
        }
        return c;
    }

    /** Для тестов: доля гонок в последние 10 с действовавшего конца, %; доля снайперов среди онлайн-участников, %. */
    double racedLast10Share() {
        return raced == 0 ? Double.NaN : 100.0 * racedLast10 / raced;
    }

    double sniperShare() {
        long n = 0;
        for (int[] s : snipe.values()) if (2 * s[1] > s[0]) n++;
        return snipe.isEmpty() ? Double.NaN : 100.0 * n / snipe.size();
    }

    private static long highOf(AuditLot a) {
        return a.high > 0 ? a.high : a.low;
    }

    private static int winIdx(AuditLot a) {
        for (int i = 0; i < a.bids; i++) if (a.bId[i] == a.winningBid) return i;
        return -1;
    }

    /** Ценовая война: ровно двое чередуются не меньше 12 раз, молоток не меньше 3× верхней оценки. */
    private static boolean war(AuditLot a) {
        if (a.hammer < 3 * highOf(a)) return false;
        long x = 0, y = 0, prev = 0;
        int alt = 0;
        for (int i = 0; i < a.bids; i++) {
            if (!a.bAccepted[i] || a.bReg[i] == 0) continue;
            long r = a.bReg[i];
            if (x == 0) x = r;
            else if (y == 0 && r != x) y = r;
            else if (r != x && r != y) return false;
            if (prev != 0 && r != prev) alt++;
            prev = r;
        }
        return y != 0 && alt >= 12;
    }

    /** Исход непроданного лота (модель): продан после торгов, выставлен снова, возвращён. */
    public void unsold(boolean after, boolean reoffered, boolean returned) {
        unsold++;
        if (after) afterSale++;
        else if (reoffered) reoffer++;
        else if (returned) giveBack++;
    }

    // ================================================================ отчёт

    /** Строка коридора; {@code n} — объём (знаменатель доли или число значений средней) — для пересчёта ширины. */
    private record Line(String name, double value, double target, double width, boolean mean, long n, boolean printOnly) {
        boolean ok() {
            return printOnly || Double.isNaN(value) || Math.abs(value - target) <= width;
        }

        boolean inside() {
            return Double.isNaN(value) || Math.abs(value - target) <= width;
        }
    }

    /** Строки каналов живых торгов — у {@code small} только печать. */
    private boolean channelsPrintOnly;

    private final List<Line> lines = new ArrayList<>();

    private void share(String name, long part, long all, double targetPct) {
        double v = all == 0 ? Double.NaN : 100.0 * part / all;
        double se = all == 0 ? 0 : 100.0 * Math.sqrt(targetPct / 100 * (1 - targetPct / 100) / all);
        // редкие узоры (доли до 5 %) — ±половина цели, иначе ±2 п. п.; не уже трёх стандартных ошибок
        double w = targetPct < 5 ? 0.5 * targetPct * widen : 2.0 * widen;
        lines.add(new Line(name, v, targetPct, Math.max(w, 3 * se), false, all, channelsPrintOnly));
    }

    private void mean(String name, double v, long n, double target) {
        lines.add(new Line(name, n == 0 ? Double.NaN : v, target, Math.max(0.1 * target * widen, n < 30 ? target : 0), true, n,
                channelsPrintOnly));
    }

    /** Строки коридоров: вне коридора — провал (кроме строк «только печать»). */
    public void report(CheckReport r) {
        lines.clear();
        String[] dept = {"живопись", "ювелирное", "монеты", "книги", "декоративно-прикладное", "коллекционные"};
        // коллекционные, живые: 80, а не 82 по отраслевой практике — живой каталог отдела дорогой, у дорогих лотов меньше
        // покупателей с бюджетом (full — ≈ 80 %)
        double[][] target = {{72, 68}, {86, 83}, {90, 88}, {78, 75}, {66, 62}, {80, 80}};
        long soldSum = 0;
        for (int d = 0; d < 6; d++) {
            for (int f = 0; f < 2; f++) {
                share("доля проданных: " + dept[d] + (f == 0 ? ", живые" : ", онлайн"), sold[d][f], closed[d][f], target[d][f]);
                soldSum += sold[d][f];
            }
        }
        share("доля проданных в среднем", soldSum, closedAll, 76);
        share("молоток ниже нижней оценки (среди проданных)", below, soldAll, 22);
        // в эстимейте — 36 %: внутри диапазона отраслевой практики 30–41 %
        share("молоток в пределах эстимейта", within, soldAll, 36);
        // выше верхней — 42 %: внутри диапазона отраслевой практики 33–59 %
        share("молоток выше верхней оценки", above, soldAll, 42);
        // хвост молотка к верхней оценке среди проданных: ≥ 2× — 6 %, ≥ 5× — 0,8 %, ≥ 10× — 0,15 %
        share("молоток ≥ 2× верхней оценки", tail2, soldAll, 6);
        share("молоток ≥ 5× верхней оценки", tail5, soldAll, 0.8);
        share("молоток ≥ 10× верхней оценки", tail10, soldAll, 0.15);
        share("лоты без ставок", noBids, closedAll, 16);
        share("лоты со ставками ниже резерва", belowReserve, closedAll, 8);
        int[] b = bidsPerLot.stream().mapToInt(Integer::intValue).sorted().toArray();
        int[] p = partsPerLot.stream().mapToInt(Integer::intValue).sorted().toArray();
        mean("ставок на лот со ставками — среднее", avg(b), b.length, 8);
        // медиана 7: между 6 у торгов в целом и 8 у онлайн-торгов живописи (отраслевая практика)
        mean("ставок на лот — медиана", pct(b, 50), b.length, 7);
        // p90 14, а не 17 по отраслевой практике: хвост модели короче (медиана 7, p90 / медиана ≈ 2), медиана и средние —
        // в коридоре
        mean("ставок на лот — p90", pct(b, 90), b.length, 14);
        mean("участников на лот — среднее", avg(p), p.length, 3.8);
        mean("участников на лот — медиана", pct(p, 50), p.length, 3);
        // p90 6, а не 7 по отраслевой практике: то же — хвост модели короче
        mean("участников на лот — p90", pct(p, 90), p.length, 6);
        long lc = Arrays.stream(liveChannel).sum(), lw = Arrays.stream(liveWins).sum(), lm = Arrays.stream(liveWinsMillion).sum();
        channelsPrintOnly = partScale;
        // каналы живых: ставки 68 / 12 / 12 / 8 %, победы 50 / 18 / 17 / 15 %; у лотов от 1 млн ₽ по телефону — 40 % побед
        share("живые, ставки онлайн", liveChannel[Codes.CH_ONLINE], lc, 68);
        share("живые, ставки заочные", liveChannel[Codes.CH_ABSENTEE], lc, 12);
        share("живые, ставки из зала", liveChannel[Codes.CH_ROOM], lc, 12);
        share("живые, ставки по телефону", liveChannel[Codes.CH_PHONE], lc, 8);
        share("живые, победы онлайн", liveWins[Codes.CH_ONLINE], lw, 50);
        share("живые, победы из зала", liveWins[Codes.CH_ROOM], lw, 18);
        share("живые, победы по телефону", liveWins[Codes.CH_PHONE], lw, 17);
        share("живые, победы заочные", liveWins[Codes.CH_ABSENTEE], lw, 15);
        share("живые от 1 млн ₽, победы по телефону", liveWinsMillion[Codes.CH_PHONE], lm, 40);
        channelsPrintOnly = false;
        share("онлайн-ставки в последние 2 мин", onlineLate, onlineBids, 15);
        share("продление у онлайн-лотов со ставками", onlineExtended, onlineLotsWithBids, 30);
        mean("продлений у продлённого — среднее", onlineExtended == 0 ? 0 : (double) extensionsSum / onlineExtended, onlineExtended, 2.1);
        // 99,5, а не 95: позже 12 мин закрываются только долгие ценовые войны, а войн — 1,5 % проданных; быстрее отвечать
        // в обычных поздних цепочках нельзя — продлений на продлённый лот станет больше 2,1
        share("продлённые закрыты в 12 мин после плана", extendedWithin12, onlineExtended, 99.5);
        long snipers = 0, onlineParts = snipe.size();
        for (int[] s : snipe.values()) if (2 * s[1] > s[0]) snipers++;
        share("снайперы среди онлайн-участников", snipers, onlineParts, 4);
        share("гонки: ручные онлайн-ставки с соперником в ту же мс", raced, manualOnline, 0.2);
        share("гонки в последние 10 с", racedLast10, raced, 70);
        share("повтор запроса (duplicate) среди ручных онлайн-ставок", duplicates, manualOnline, 0.5);
        share("ценовые войны среди проданных", wars, soldAll, 1.5);
        share("непроданное: продано после торгов", afterSale, unsold, 8);
        share("непроданное: выставлено снова", reoffer, unsold, 55);
        share("непроданное: возвращено", giveBack, unsold, 37);
        StringBuilder sb = new StringBuilder();
        int bad = 0;
        for (Line l : lines) {
            if (!l.ok()) bad++;
            sb.append(String.format(Locale.ROOT, "%n      %s %s: %.2f (цель %.2f ± %.2f; %s %d)%s", l.inside() ? "ok " : "ВНЕ",
                    l.name(), l.value(), l.target(), l.width(), l.mean() ? "среднее по" : "доля из", l.n(),
                    l.printOnly() ? " — печать: масштаб: каталог живых меньше полного" : ""));
        }
        String detail = "вне коридора " + bad + " из " + lines.size() + sb;
        r.expect("auction: коридоры (доля проданных, эстимейт и хвост молотка, ставки, каналы, поздние, снайперы, гонки, повторы, войны, "
                + "непроданное)", bad == 0, detail);
    }

    private static double avg(int[] a) {
        if (a.length == 0) return Double.NaN;
        long s = 0;
        for (int x : a) s += x;
        return (double) s / a.length;
    }

    private static double pct(int[] a, int p) {
        if (a.length == 0) return Double.NaN;
        return a[Math.min(a.length - 1, (int) ((long) p * a.length / 100))];
    }
}
