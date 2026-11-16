package io.hammerhall.data.sto.orders;

import io.hammerhall.data.core.money.Money;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.CategoryTree;
import io.hammerhall.data.sto.catalog.Promotions;
import io.hammerhall.data.sto.catalog.Promotions.Promotion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Скидки строк по акциям магазина — правила из javadoc {@link Promotions}. Акции магазина действуют на его товары
 * (строки 1P); строки продавцов маркетплейса — по цене предложения, без акций.
 *
 * <ul>
 *   <li>кандидаты строки — акции, действующие в момент заказа, с целью-товаром или категорией на пути товара к корню;</li>
 *   <li>{@code bundle} — если в заказе не меньше двух разных целевых товаров; {@code n_plus_m} — из каждых
 *       {@code buy_qty} целевых единиц заказа {@code free_qty} самых дешёвых бесплатно;</li>
 *   <li>несуммируемая — сама по себе (берётся лучшая), суммируемые — последовательно от остатка (по id); строке
 *       достаётся лучший из двух вариантов;</li>
 *   <li>промокод — только введённый; с целями — как процент на целевые строки; без целей — сумма на заказ при сумме
 *       строк магазина после скидок не ниже порога, делится по строкам пропорционально (несуммируемый — только на строки
 *       без других скидок);</li>
 *   <li>{@code promo_id} строки — акция с самой большой долей скидки строки.</li>
 * </ul>
 * Скидка строки не больше её суммы; у {@code amount} — не ниже 1 ₽ за единицу остаётся.
 */
final class PromoCalc {
    private final Catalog cat;
    private final List<Promotion> all;
    private final int[] prodHead, catHead;
    private int[] nNext = new int[1 << 14], nPromo = new int[1 << 14];
    private int nTop;
    private int[] touchedP = new int[1 << 12], touchedC = new int[256];
    private int tpN, tcN;
    private final List<Promotion> codes = new ArrayList<>();

    PromoCalc(Catalog cat, Promotions promos, int maxProduct) {
        this.cat = cat;
        this.all = promos.all();
        prodHead = new int[maxProduct + 1];
        catHead = new int[cat.tree().nodes().size() + 1];
        Arrays.fill(prodHead, -1);
        Arrays.fill(catHead, -1);
    }

    /** Акции, действующие хоть момент в {@code [a, b)} — индекс по целям. */
    void day(long a, long b) {
        for (int i = 0; i < tpN; i++) prodHead[touchedP[i]] = -1;
        for (int i = 0; i < tcN; i++) catHead[touchedC[i]] = -1;
        tpN = tcN = nTop = 0;
        codes.clear();
        // по убыванию id: списки собираются в голову — в итоге по возрастанию id
        for (int i = all.size() - 1; i >= 0; i--) {
            Promotion p = all.get(i);
            if (p.validFrom() >= b || p.validTo() <= a) continue;
            if (p.kind().equals("promocode")) codes.add(p);
            for (Promotions.Target t : p.targets()) {
                if (t.productId() != null) {
                    int pid = t.productId();
                    if (pid >= prodHead.length) continue;
                    if (prodHead[pid] < 0) touchedP = push(touchedP, tpN++, pid);
                    prodHead[pid] = node(i, prodHead[pid]);
                } else {
                    int cid = t.categoryId();
                    if (catHead[cid] < 0) touchedC = push(touchedC, tcN++, cid);
                    catHead[cid] = node(i, catHead[cid]);
                }
            }
        }
        codes.sort((x, y) -> Integer.compare(x.id(), y.id()));
    }

    private static int[] push(int[] a, int i, int v) {
        if (i == a.length) a = Arrays.copyOf(a, a.length * 2);
        a[i] = v;
        return a;
    }

    private int node(int promoIdx, int next) {
        if (nTop == nNext.length) {
            nNext = Arrays.copyOf(nNext, nTop * 2);
            nPromo = Arrays.copyOf(nPromo, nTop * 2);
        }
        nNext[nTop] = next;
        nPromo[nTop] = promoIdx;
        return nTop++;
    }

    /** Промокоды, действующие в момент. */
    List<Promotion> codesAt(long t) {
        List<Promotion> out = new ArrayList<>();
        for (Promotion p : codes) if (p.activeAt(t)) out.add(p);
        return out;
    }

    /** Действующие акции строки (без чужих промокодов), по id. */
    private int candidates(int pid, long t, String code, int[] out) {
        int n = 0;
        for (int k = prodHead[pid]; k >= 0; k = nNext[k]) n = addCand(all.get(nPromo[k]), t, code, out, n);
        for (CategoryTree.Node c = cat.product(pid).category; c != null; c = c.parent) {
            for (int k = catHead[c.id]; k >= 0; k = nNext[k]) n = addCand(all.get(nPromo[k]), t, code, out, n);
        }
        Arrays.sort(out, 0, n);
        int m = 0;
        for (int i = 0; i < n; i++) if (m == 0 || out[m - 1] != out[i]) out[m++] = out[i];
        return m;
    }

    private static int addCand(Promotion p, long t, String code, int[] out, int n) {
        if (!p.activeAt(t)) return n;
        if (p.kind().equals("promocode") && (code == null || !code.equals(p.promoCode()))) return n;
        if (n < out.length) out[n++] = p.id();
        return n;
    }

    private static long pctOf(long amount, Promotion p) {
        return Money.percent(amount, p.discountPct().movePointRight(2).longValueExact());
    }

    /**
     * Скидки строк заказа (только строки магазина, {@code seller == 0}); введённый промокод — {@code code} (может не
     * подойти). Возвращает, применился ли промокод.
     */
    boolean apply(Order.Line[] lines, String code, long t) {
        int n = lines.length;
        int[][] cand = new int[n][];
        int[] buf = new int[64];
        for (int i = 0; i < n; i++) {
            lines[i].discount = 0;
            lines[i].promo = 0;
            if (lines[i].seller != 0) {
                cand[i] = new int[0];
                continue;
            }
            int m = candidates(lines[i].product, t, code, buf);
            cand[i] = Arrays.copyOf(buf, m);
        }
        // акции уровня заказа: «вместе дешевле» (≥ 2 разных целевых товара) и n+m (бесплатные самые дешёвые единицы)
        long[][] fixed = new long[n][];
        for (int i = 0; i < n; i++) fixed[i] = new long[cand[i].length];
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < cand[i].length; j++) {
                Promotion p = all.get(cand[i][j] - 1);
                if (!(p.kind().equals("bundle") || p.kind().equals("n_plus_m")) || seen.contains(p.id())) continue;
                seen.add(p.id());
                orderLevel(p, lines, cand, fixed);
            }
        }
        boolean codeUsed = false;
        long[] best = new long[n];
        long[] bestMain = new long[n];
        for (int i = 0; i < n; i++) {
            if (cand[i].length == 0) continue;
            Order.Line l = lines[i];
            long gross = l.gross();
            // вариант 1: лучшая одиночная (несуммируемая — сама по себе)
            long single = 0;
            int singleId = 0;
            for (int j = 0; j < cand[i].length; j++) {
                Promotion p = all.get(cand[i][j] - 1);
                if (p.stackable()) continue;
                long d = discount(p, l, gross, fixed[i][j]);
                if (d > single) {
                    single = d;
                    singleId = p.id();
                }
            }
            // вариант 2: суммируемые последовательно
            long rest = gross, stack = 0, mainD = 0;
            int mainId = 0;
            for (int j = 0; j < cand[i].length; j++) {
                Promotion p = all.get(cand[i][j] - 1);
                if (!p.stackable()) continue;
                long d = Math.min(rest, discount(p, l, rest, fixed[i][j]));
                if (d <= 0) continue;
                rest -= d;
                stack += d;
                if (d > mainD) {
                    mainD = d;
                    mainId = p.id();
                }
            }
            if (single >= stack && single > 0) {
                l.discount = single;
                l.promo = singleId;
                best[i] = single;
            } else if (stack > 0) {
                l.discount = stack;
                l.promo = mainId;
                best[i] = mainD;
            }
            bestMain[i] = best[i];
            for (int j = 0; j < cand[i].length; j++) {
                Promotion p = all.get(cand[i][j] - 1);
                if (p.kind().equals("promocode") && p.id() == l.promo) codeUsed = true;
            }
        }
        // промокод на заказ (без целей)
        if (code != null) {
            for (Promotion p : codes) {
                if (!p.activeAt(t) || !code.equals(p.promoCode()) || !p.targets().isEmpty()) continue;
                long base = 0;
                List<Integer> elig = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    Order.Line l = lines[i];
                    if (l.seller != 0) continue;
                    base += l.net();
                    if (p.stackable() || l.discount == 0) elig.add(i);
                }
                if (p.minOrderKop() != null && base < p.minOrderKop()) break;
                long room = 0, eligNet = 0;
                for (int i : elig) {
                    room += Math.max(0, lines[i].net() - 100L * lines[i].qty);
                    eligNet += lines[i].net();
                }
                long amount = p.discountAmountKop() != null ? p.discountAmountKop() : pctOf(eligNet, p);
                long total = Math.min(amount, room);
                if (total <= 0) break;
                long[] share = split(total, lines, elig);
                for (int k = 0; k < elig.size(); k++) {
                    Order.Line l = lines[elig.get(k)];
                    l.discount += share[k];
                    if (share[k] > bestMain[elig.get(k)]) {
                        bestMain[elig.get(k)] = share[k];
                        l.promo = p.id();
                    }
                }
                codeUsed = true;
                break;
            }
        }
        return codeUsed;
    }

    /** Скидка акции на строку от суммы {@code base} (для суммируемых — от остатка). */
    private static long discount(Promotion p, Order.Line l, long base, long fixed) {
        return switch (p.kind()) {
            case "percent" -> pctOf(base, p);
            case "bundle" -> fixed > 0 ? pctOf(base, p) : 0;
            case "promocode" -> p.discountPct() != null ? pctOf(base, p)
                    : Math.max(0, Math.min(p.discountAmountKop() * l.qty, base - 100L * l.qty));
            case "amount" -> Math.max(0, Math.min(p.discountAmountKop() * l.qty, base - 100L * l.qty));
            case "n_plus_m" -> Math.min(fixed, base);
            default -> 0;
        };
    }

    /**
     * Акции уровня заказа: для «вместе дешевле» — 1 у строк, если целевых товаров в заказе ≥ 2 (иначе 0); для n+m —
     * стоимость бесплатных единиц строки.
     */
    private void orderLevel(Promotion p, Order.Line[] lines, int[][] cand, long[][] fixed) {
        int n = lines.length;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) for (int c : cand[i]) if (c == p.id()) idx.add(i);
        if (p.kind().equals("bundle")) {
            int distinct = 0;
            List<Integer> prods = new ArrayList<>();
            for (int i : idx) if (!prods.contains(lines[i].product)) prods.add(lines[i].product);
            distinct = prods.size();
            for (int i : idx) set(fixed, cand, i, p.id(), distinct >= 2 ? 1 : 0);
            return;
        }
        int units = 0;
        for (int i : idx) units += lines[i].qty;
        int free = units / p.buyQty() * p.freeQty();
        idx.sort((a, b) -> lines[a].unit != lines[b].unit ? Long.compare(lines[a].unit, lines[b].unit) : Integer.compare(a, b));
        for (int i : idx) {
            int f = Math.min(free, lines[i].qty);
            free -= f;
            set(fixed, cand, i, p.id(), f * lines[i].unit);
        }
    }

    private static void set(long[][] fixed, int[][] cand, int i, int promoId, long v) {
        for (int j = 0; j < cand[i].length; j++) if (cand[i][j] == promoId) fixed[i][j] = v;
    }

    /**
     * Сумма по строкам пропорционально их сумме после скидок, наибольшими остатками (сумма долей — ровно {@code total};
     * у строки остаётся не меньше 1 ₽ за единицу).
     */
    private static long[] split(long total, Order.Line[] lines, List<Integer> elig) {
        int m = elig.size();
        long[] share = new long[m];
        long[] cap = new long[m];
        long sum = 0;
        for (int k = 0; k < m; k++) {
            Order.Line l = lines[elig.get(k)];
            cap[k] = Math.max(0, l.net() - 100L * l.qty);
            sum += Math.max(0, l.net());
        }
        if (sum <= 0) return share;
        long given = 0;
        long[] rem = new long[m];
        for (int k = 0; k < m; k++) {
            long num = Math.multiplyExact(total, Math.max(0, lines[elig.get(k)].net()));
            share[k] = Math.min(cap[k], num / sum);
            rem[k] = num % sum;
            given += share[k];
        }
        while (given < total) {
            int bi = -1;
            for (int k = 0; k < m; k++) if (share[k] < cap[k] && (bi < 0 || rem[k] > rem[bi])) bi = k;
            if (bi < 0) break;
            share[bi]++;
            rem[bi] = -1;
            given++;
        }
        return share;
    }
}
