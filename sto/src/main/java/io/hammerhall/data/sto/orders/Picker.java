package io.hammerhall.data.sto.orders;

import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.RuCalendar;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.CategoryTree;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Выбор товара в корзину: вес — популярность (тяжёлый хвост) × жизненный цикл модели (разгон, спад, вытеснение
 * преемником, распродажа перед снятием) × сезон вида по календарю. Гладкая часть веса — одна на неделю (четверг
 * недели), календарь — на сутки; таблица недели строится в первый её день из уже запущенных товаров, запущенные позже
 * — отдельным списком: на дату видны только товары, запущенные не позже неё (продление периода прошлого не меняет).
 *
 * <p>Корзины по отделам (категории 2-го уровня) и сезонам: любимый отдел покупателя — поиск только в нём.
 * «Покупают вместе» — правила {@link Catalog#COMPANIONS}: подходящий к устройству аксессуар или товар вида.
 */
final class Picker {
    private final Catalog cat;
    private final RuCalendar cal;
    private final int nDept, nSeason;
    private final int[] deptIds;
    private final int[] deptOf, seasonOf, kindOf;
    private final Map<String, Integer> kindIdx = new TreeMap<>();
    private final List<Catalog.Product> byLaunch;
    private int launchPtr;
    private final int[][] compatOf;
    private final Catalog.Companion[][] rulesOf;

    // неделя
    private long week = Long.MIN_VALUE;
    private int[][][] bPid;
    private double[][][] bCum;
    private double[][] bTot;
    private int[][] kPid;
    private double[][] kCum;
    private final double[] weekW;
    private int[] freshPid = new int[64];
    private double[] freshW = new double[64];
    private int freshN;
    // сутки
    private final double[] calRel;
    private final double[] deptW, deptCum, freshDeptW;
    private LocalDate day;

    Picker(Catalog cat, RuCalendar cal, int maxProduct) {
        this.cat = cat;
        this.cal = cal;
        List<CategoryTree.Node> depts = new ArrayList<>(cat.tree().root().children);
        nDept = depts.size();
        deptIds = new int[nDept];
        for (int i = 0; i < nDept; i++) deptIds[i] = depts.get(i).id;
        nSeason = RuCalendar.Season.values().length;
        deptOf = new int[maxProduct + 1];
        seasonOf = new int[maxProduct + 1];
        kindOf = new int[maxProduct + 1];
        weekW = new double[maxProduct + 1];
        for (String k : cat.kinds().kinds().keySet()) kindIdx.put(k, kindIdx.size());
        for (Catalog.Product p : cat.products()) {
            int di = -1;
            for (int i = 0; i < nDept; i++) if (deptIds[i] == p.category.departmentId) di = i;
            if (di < 0) throw new IllegalStateException("товар " + p.id + " вне отделов");
            deptOf[p.id] = di;
            seasonOf[p.id] = p.kind.season.ordinal();
            kindOf[p.id] = kindIdx.get(p.kind.code);
        }
        byLaunch = new ArrayList<>(cat.products());
        byLaunch.sort((x, y) -> x.launchedOn.compareTo(y.launchedOn) != 0 ? x.launchedOn.compareTo(y.launchedOn)
                : Integer.compare(x.id, y.id));
        // аксессуары под устройство (обратный индекс совместимости)
        List<List<Integer>> comp = new ArrayList<>();
        for (int i = 0; i <= maxProduct; i++) comp.add(null);
        for (Catalog.Product a : cat.products()) {
            for (Catalog.Product dv : a.compatibleWith) {
                if (comp.get(dv.id) == null) comp.set(dv.id, new ArrayList<>());
                comp.get(dv.id).add(a.id);
            }
        }
        compatOf = new int[maxProduct + 1][];
        for (int i = 0; i <= maxProduct; i++) {
            List<Integer> l = comp.get(i);
            compatOf[i] = l == null ? new int[0] : l.stream().mapToInt(Integer::intValue).toArray();
        }
        Map<String, List<Catalog.Companion>> rules = new TreeMap<>();
        for (Catalog.Companion c : Catalog.COMPANIONS) rules.computeIfAbsent(c.anchor(), k -> new ArrayList<>()).add(c);
        rulesOf = new Catalog.Companion[kindIdx.size()][];
        for (Map.Entry<String, Integer> e : kindIdx.entrySet()) {
            List<Catalog.Companion> l = rules.getOrDefault(e.getKey(), List.of());
            rulesOf[e.getValue()] = l.toArray(new Catalog.Companion[0]);
        }
        calRel = new double[nSeason];
        deptW = new double[nDept];
        deptCum = new double[nDept];
        freshDeptW = new double[nDept];
    }

    int departments() {
        return nDept;
    }

    int deptIndexOfCategory(int categoryId) {
        for (int i = 0; i < nDept; i++) if (deptIds[i] == categoryId) return i;
        return -1;
    }

    int deptOf(int pid) {
        return deptOf[pid];
    }

    Catalog.Companion[] rules(Catalog.Product p) {
        return rulesOf[kindOf[p.id]];
    }

    /** Гладкий вес товара на неделю: популярность × жизненный цикл в четверг недели (или в день запуска). */
    private double weekRate(Catalog.Product p, LocalDate d, long monday) {
        LocalDate at = LocalDate.ofEpochDay(monday + 3);
        if (at.isBefore(p.launchedOn)) at = p.launchedOn;
        if (p.discontinuedPlan != null && !at.isBefore(p.discontinuedPlan)) at = d;
        return p.aliveOn(at) ? p.popularity * cat.lifecycle(p, at) : 0;
    }

    private void buildWeek(LocalDate d, long monday) {
        week = monday;
        while (launchPtr < byLaunch.size() && !byLaunch.get(launchPtr).launchedOn.isAfter(d)) launchPtr++;
        int nk = kindIdx.size();
        int[][] cnt = new int[nDept][nSeason];
        int[] kc = new int[nk];
        Arrays.fill(weekW, 0);
        for (int i = 0; i < launchPtr; i++) {
            Catalog.Product p = byLaunch.get(i);
            if (p.discontinuedPlan != null && p.discontinuedPlan.toEpochDay() <= d.toEpochDay()) continue;
            double x = weekRate(p, d, monday);
            if (x <= 0) continue;
            weekW[p.id] = x;
            cnt[deptOf[p.id]][seasonOf[p.id]]++;
            kc[kindOf[p.id]]++;
        }
        bPid = new int[nDept][nSeason][];
        bCum = new double[nDept][nSeason][];
        bTot = new double[nDept][nSeason];
        for (int a = 0; a < nDept; a++) {
            for (int s = 0; s < nSeason; s++) {
                bPid[a][s] = new int[cnt[a][s]];
                bCum[a][s] = new double[cnt[a][s]];
            }
        }
        kPid = new int[nk][];
        kCum = new double[nk][];
        for (int k = 0; k < nk; k++) {
            kPid[k] = new int[kc[k]];
            kCum[k] = new double[kc[k]];
        }
        int[][] fill = new int[nDept][nSeason];
        int[] kf = new int[nk];
        double[] kacc = new double[nk];
        // по id — порядок от запуска не зависит
        for (int pid = 1; pid < weekW.length; pid++) {
            double x = weekW[pid];
            if (x <= 0) continue;
            int a = deptOf[pid], s = seasonOf[pid], k = kindOf[pid];
            int j = fill[a][s]++;
            bPid[a][s][j] = pid;
            bTot[a][s] += x;
            bCum[a][s][j] = bTot[a][s];
            int q = kf[k]++;
            kPid[k][q] = pid;
            kacc[k] += x;
            kCum[k][q] = kacc[k];
        }
        freshN = 0;
    }

    /** Начало суток: таблица недели, новинки суток, календарь. */
    void day(LocalDate d) {
        day = d;
        long ed = d.toEpochDay();
        long monday = ed - (d.getDayOfWeek().getValue() - 1);
        if (monday != week) buildWeek(d, monday);
        while (launchPtr < byLaunch.size() && !byLaunch.get(launchPtr).launchedOn.isAfter(d)) {
            Catalog.Product p = byLaunch.get(launchPtr++);
            double x = weekRate(p, d, monday);
            if (x <= 0) continue;
            if (freshN == freshPid.length) {
                freshPid = Arrays.copyOf(freshPid, freshN * 2);
                freshW = Arrays.copyOf(freshW, freshN * 2);
            }
            freshPid[freshN] = p.id;
            freshW[freshN++] = x;
        }
        double base = cal.demand(d, RuCalendar.Season.BASE);
        for (RuCalendar.Season s : RuCalendar.Season.values()) calRel[s.ordinal()] = cal.demand(d, s) / base;
        Arrays.fill(freshDeptW, 0);
        for (int i = 0; i < freshN; i++) freshDeptW[deptOf[freshPid[i]]] += freshW[i] * calRel[seasonOf[freshPid[i]]];
        double acc = 0;
        for (int a = 0; a < nDept; a++) {
            double x = freshDeptW[a];
            for (int s = 0; s < nSeason; s++) x += calRel[s] * bTot[a][s];
            deptW[a] = x;
            acc += x;
            deptCum[a] = acc;
        }
    }

    /** Отдел по общему спросу суток. */
    int pickDept(Rng r) {
        double x = r.nextDouble() * deptCum[nDept - 1];
        for (int a = 0; a < nDept; a++) if (x < deptCum[a]) return a;
        return nDept - 1;
    }

    /** Товар отдела по весу суток; −1 — в отделе нечего продавать. */
    int pickInDept(int a, Rng r) {
        if (deptW[a] <= 0) return -1;
        double x = r.nextDouble() * deptW[a];
        for (int s = 0; s < nSeason; s++) {
            double part = calRel[s] * bTot[a][s];
            if (x < part && bPid[a][s].length > 0) return bPid[a][s][search(bCum[a][s], x / calRel[s])];
            x -= part;
        }
        int last = -1;
        for (int i = 0; i < freshN; i++) {
            int pid = freshPid[i];
            if (deptOf[pid] != a) continue;
            last = pid;
            x -= freshW[i] * calRel[seasonOf[pid]];
            if (x < 0) return pid;
        }
        return last;
    }

    /**
     * Товар-спутник по правилу «покупают вместе»: подходящий к устройству аксессуар своего вида (по популярности)
     * или товар вида; −1 — подходящего в продаже нет.
     */
    int companion(Catalog.Product anchor, Catalog.Companion rule, Rng r) {
        Integer ki = kindIdx.get(rule.companion());
        if (ki == null) return -1;
        if (rule.compatibleOnly()) {
            int[] list = compatOf[anchor.id];
            double tot = 0;
            for (int pid : list) if (kindOf[pid] == ki && cat.product(pid).aliveOn(day)) tot += cat.product(pid).popularity;
            if (tot <= 0) return -1;
            double x = r.nextDouble() * tot;
            int last = -1;
            for (int pid : list) {
                if (kindOf[pid] != ki || !cat.product(pid).aliveOn(day)) continue;
                last = pid;
                x -= cat.product(pid).popularity;
                if (x < 0) return pid;
            }
            return last;
        }
        int[] pids = kPid[ki];
        if (pids.length == 0) return -1;
        double[] cum = kCum[ki];
        for (int tries = 0; tries < 4; tries++) {
            int pid = pids[search(cum, r.nextDouble() * cum[cum.length - 1])];
            if (cat.product(pid).aliveOn(day)) return pid;
        }
        return -1;
    }

    /** Товар того же отдела для просмотров в сессии. */
    int related(int pid, Rng r) {
        return pickInDept(deptOf[pid], r);
    }

    static int search(double[] cum, double x) {
        int lo = 0, hi = cum.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] > x) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }
}
