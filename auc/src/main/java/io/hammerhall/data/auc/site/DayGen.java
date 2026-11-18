package io.hammerhall.data.auc.site;

import io.hammerhall.data.core.random.Rng;

import java.util.Arrays;

import static io.hammerhall.data.auc.site.Rules.CH_EMAIL;
import static io.hammerhall.data.auc.site.Rules.CH_PUSH;
import static io.hammerhall.data.auc.site.Rules.E_BID;
import static io.hammerhall.data.auc.site.Rules.E_FORM;
import static io.hammerhall.data.auc.site.Rules.E_ORDER;
import static io.hammerhall.data.auc.site.Rules.E_PAGE;
import static io.hammerhall.data.auc.site.Rules.E_SEARCH;
import static io.hammerhall.data.auc.site.Rules.E_UNWATCH;
import static io.hammerhall.data.auc.site.Rules.E_VIEW;
import static io.hammerhall.data.auc.site.Rules.E_WATCH;

/**
 * Генерация одних суток (UTC): визиты вокруг действий клиентов (ставки формой, максимумы, наблюдение), визиты
 * «посмотреть» клиентов и анонимных посетителей, уведомления суток. Экземпляр — на поток (свои рабочие массивы),
 * общее — только чтение ({@link SiteGen}). Сутки не зависят друг от друга: случайность — от (клиент или устройство,
 * сутки, номер), поэтому один и четыре потока дают одно и то же.
 */
final class DayGen {
    private static final long DAY = 86_400;
    private static final byte A_BID = 0, A_ORDER = 1, A_WATCH = 2, A_UNWATCH = 3;

    private final SiteGen g;
    private final SiteInput in;
    private final Watches w;

    // действия суток
    private int na;
    private long[] aKey = new long[1024];
    private int[] aIdx = new int[1024], aClient = new int[1024], aLot = new int[1024], aRef = new int[1024];
    private long[] aT = new long[1024];
    private byte[] aType = new byte[1024], aVia = new byte[1024];

    // видимые лоты суток и выбор лота по весу (метод псевдонимов) — по отделам и общий
    private int nVis;
    private int[] visLot = new int[1024];
    private double[] visW = new double[1024];
    private final Alias[] alias = new Alias[7];
    private final int[] catCount, makerCount;
    /** Проверенные на публичные имена запросы (память чистой функции, на поток). */
    private final java.util.Map<String, Boolean> cleanQueries = new java.util.HashMap<>();
    private double viewsTarget;

    DayGen(SiteGen g) {
        this.g = g;
        this.in = g.in;
        this.w = g.watches;
        for (int i = 0; i < alias.length; i++) alias[i] = new Alias();
        catCount = new int[Math.max(1, in.catName.length)];
        makerCount = new int[Math.max(1, in.makerName.length)];
    }

    DayOut run(long day) {
        DayOut out = new DayOut(day);
        long ds = day * DAY, de = ds + DAY;
        visible(day);
        actions(out, day, ds, de);
        browseClients(out, day, ds);
        browseAnon(out, day, ds);
        notices(out, day, ds, de);
        out.sort();
        return out;
    }

    // ================================================================ видимые лоты

    private void visible(long day) {
        nVis = 0;
        Arrays.fill(catCount, 0);
        Arrays.fill(makerCount, 0);
        double total = 0;
        for (int s = 1; s <= in.nSales; s++) {
            if (in.sPub[s] == 0 || SiteInput.day(in.sPub[s]) > day || g.saleEndDay[s] < day) continue;
            for (int i = in.saleFrom[s]; i < in.saleFrom[s + 1]; i++) {
                int l = in.saleLots[i];
                int pd = g.pubDay[l], ed = g.endDay[l];
                if (pd > day || ed < day) continue;
                int k = (int) (day - pd), kk = ed - pd;
                double wt = g.lotW[l] * Rules.dayWeight(k, kk);
                if (nVis == visLot.length) {
                    visLot = Arrays.copyOf(visLot, nVis * 2);
                    visW = Arrays.copyOf(visW, nVis * 2);
                }
                visLot[nVis] = l;
                visW[nVis++] = wt;
                total += wt;
                int c = in.lCat[l];
                if (c > 0 && c < catCount.length) catCount[c]++;
                int m = in.lMaker[l];
                if (m > 0 && m < makerCount.length) makerCount[m]++;
            }
        }
        viewsTarget = total;
        for (int d = 0; d < 6; d++) alias[d].build(visLot, visW, nVis, l -> in.sDept[in.lSale[l]], d);
        alias[6].build(visLot, visW, nVis, null, -1);
    }

    /** Лот по весу дня (отдел — с долей 0.7, если задан), уже опубликованный к моменту {@code t}; 0 — нет. */
    private int pickLot(Rng r, int dept, long t) {
        for (int tries = 0; tries < 4; tries++) {
            Alias a = dept >= 0 && alias[dept].n > 0 && r.chance(0.7) ? alias[dept] : alias[6];
            if (a.n == 0) return 0;
            int l = a.pick(r);
            if (in.lPub[l] <= t) return l;
        }
        return 0;
    }

    // ================================================================ визиты вокруг действий

    private void addAction(int c, long t, byte type, int lot, int ref, byte via) {
        if (na == aKey.length) {
            int n = na * 2;
            aKey = Arrays.copyOf(aKey, n);
            aIdx = Arrays.copyOf(aIdx, n);
            aClient = Arrays.copyOf(aClient, n);
            aLot = Arrays.copyOf(aLot, n);
            aRef = Arrays.copyOf(aRef, n);
            aT = Arrays.copyOf(aT, n);
            aType = Arrays.copyOf(aType, n);
            aVia = Arrays.copyOf(aVia, n);
        }
        aClient[na] = c;
        aT[na] = t;
        aType[na] = type;
        aLot[na] = lot;
        aRef[na] = ref;
        aVia[na] = via;
        na++;
    }

    private void actions(DayOut out, long day, long ds, long de) {
        na = 0;
        // ставки формой
        int from = Radix.lowerBound(in.manualAt, 0, in.manualAt.length, ds);
        int to = Radix.lowerBound(in.manualAt, from, in.manualAt.length, de);
        for (int j = from; j < to; j++) {
            int i = in.manual[j];
            int c = in.bClient[i];
            if ((in.cFlags[c] & SiteInput.C_ANALYTICS) == 0) continue;
            addAction(c, in.bAt[i], A_BID, in.bLot[i], i, g.rules.via(in, c, day));
        }
        // максимумы с сайта и приложения
        from = Radix.lowerBound(in.oAt, 0, in.nOrders, ds);
        to = Radix.lowerBound(in.oAt, from, in.nOrders, de);
        for (int j = from; j < to; j++) {
            int c = in.oClient[j];
            if ((in.cFlags[c] & SiteInput.C_ANALYTICS) == 0) continue;
            addAction(c, in.oAt[j], A_ORDER, in.oLot[j], j, in.oVia[j]);
        }
        // наблюдение
        from = Radix.lowerBound(w.evAt, 0, w.nEv, ds);
        to = Radix.lowerBound(w.evAt, from, w.nEv, de);
        for (int j = from; j < to; j++) {
            int ref = w.evRef[j], row = ref >>> 1;
            boolean rem = (ref & 1) != 0;
            int c = w.client[row];
            byte via = rem ? g.rules.via(in, c, day) : w.via[row];
            addAction(c, w.evAt[j], rem ? A_UNWATCH : A_WATCH, w.lot[row], row, via);
        }
        // по (клиент, откуда, момент, порядок сбора)
        for (int i = 0; i < na; i++) {
            aKey[i] = ((long) aClient[i] << 43) | ((long) aVia[i] << 42) | ((aT[i] - ds) << 25) | i;
            aIdx[i] = i;
        }
        Radix.sort(aKey, aIdx, na, 64);
        int prevClient = -1, no = 0;
        for (int p = 0; p < na; ) {
            int i0 = aIdx[p];
            int c = aClient[i0];
            byte via = aVia[i0];
            int q = p + 1;
            while (q < na) {
                int iq = aIdx[q], ip = aIdx[q - 1];
                if (aClient[iq] != c || aVia[iq] != via || aT[iq] - aT[ip] > Rules.SESSION_GAP) break;
                q++;
            }
            no = c == prevClient ? no + 1 : 0;
            prevClient = c;
            int e0 = out.ne;
            actionSession(out, day, ds, c, via, no, p, q);
            out.mix[0]++;
            out.mix[1] += out.ne - e0;
            p = q;
        }
    }

    private void actionSession(DayOut out, long day, long ds, int c, byte via, int no, int p, int q) {
        Rng r = g.stAct.at(in.cKey[c], day, no);
        int first = aIdx[p];
        long t0 = aT[first];
        long preroll = 20 + (long) Math.min(1500, r.logNormal(150, 0.9));
        long start = Math.max(ds, t0 - preroll);
        int dev = device(c, via, r);
        byte device = dev >= 0 ? in.devKind[dev] : (via == SiteInput.VIA_APP ? SiteInput.DEV_APP : SiteInput.DEV_DESKTOP);
        long devKey = dev >= 0 ? in.devKey[dev] : Rng.mix64(in.cKey[c] ^ (via + 0x5eedL));
        out.session(start, c, devKey, device, source(device, r), r.chance(0.94) ? in.cCity[c] : 0);
        // вход: поиск, страница каталога — и просмотр лота первого действия
        int lot0 = aLot[first], sale0 = in.lSale[lot0];
        long span = Math.max(0, t0 - start);
        double u = r.nextDouble();
        int pre = u < 0.2 ? 2 : u < 0.5 ? 1 : 0;
        if (pre == 2) {
            long at = start;
            searchEvent(out, at, lot0, r);
        }
        if (pre >= 1) {
            long at = Math.max(Math.max(out.lastAt(), start + span / 3), in.sPub[sale0]);
            if (at <= t0) pageEvent(out, at, sale0, in.lotPos[lot0] / 20 + 1);
        }
        int lastLot = 0;
        long lastBid = Long.MIN_VALUE;
        int lastBidLot = 0;
        for (int k = p; k < q; k++) {
            int i = aIdx[k];
            long t = aT[i];
            int lot = aLot[i], sale = in.lSale[lot];
            byte type = aType[i];
            boolean repeat = type == A_BID && (in.bFlags[aRef[i]] & SiteInput.B_REPEAT) != 0 && lastBidLot == lot
                    && t - lastBid <= 15;
            if (lot != lastLot && !(type == A_UNWATCH && r.chance(0.5)) && !repeat) {
                long at = Math.max(Math.max(out.lastAt(), t - 5 - r.nextInt(90)), in.lPub[lot]);
                out.event(Math.min(at, t), E_VIEW, sale, lot);
                lastLot = lot;
            }
            switch (type) {
                case A_BID -> {
                    if (!repeat) out.event(Math.min(t, Math.max(out.lastAt(), t - 3 - r.nextInt(38))), E_FORM, sale, lot);
                    out.event(t, E_BID, sale, lot);
                    out.eBid[out.ne - 1] = in.bidId(aRef[i]);
                    lastBid = t;
                    lastBidLot = lot;
                }
                case A_ORDER -> {
                    out.event(Math.min(t, Math.max(out.lastAt(), t - 5 - r.nextInt(56))), E_FORM, sale, lot);
                    out.event(t, E_ORDER, sale, lot);
                    out.eOrder[out.ne - 1] = in.oId[aRef[i]];
                }
                case A_WATCH -> out.event(t, E_WATCH, sale, lot);
                default -> out.event(t, E_UNWATCH, sale, lot);
            }
        }
        // хвост: соседние лоты тех же торгов
        int lastL = aLot[aIdx[q - 1]];
        int tail = r.weighted(TAIL);
        long at = out.lastAt();
        int s = in.lSale[lastL];
        int size = in.saleFrom[s + 1] - in.saleFrom[s];
        for (int k = 0; k < tail && size > 1; k++) {
            int pos = Math.floorMod(in.lotPos[lastL] + 1 + r.nextInt(10), size);
            int l = in.saleLots[in.saleFrom[s] + pos];
            at += gap(r);
            if (in.lPub[l] <= at) out.event(at, E_VIEW, s, l);
        }
        out.sEnd[out.ns - 1] = out.lastAt() + 5 + r.nextInt(85);
    }

    /** Устройство визита: приложение — первое приложение клиента; сайт — компьютер или телефон клиента. */
    private int device(int c, byte via, Rng r) {
        // устройство — того же класса, что и «откуда» (приложение или сайт); нет такого — −1 (визит возьмёт
        // постоянное устройство клиента этого класса, выведенное из его ключа)
        if (via == SiteInput.VIA_APP) return in.appDev[c];
        int from = in.cDevFrom[c], to = in.cDevFrom[c + 1];
        int k = 0;
        for (int j = from; j < to; j++) if (in.devKind[j] != SiteInput.DEV_APP) k++;
        if (k == 0) return -1;
        int pick = k == 1 ? 0 : r.nextInt(k);
        for (int j = from; j < to; j++) {
            if (in.devKind[j] == SiteInput.DEV_APP) continue;
            if (pick-- == 0) return j;
        }
        return -1;
    }

    private static final double[] SRC_APP = {0.55, 0.03, 0.08, 0.3, 0.04};
    private static final double[] SRC_WEB = {0.35, 0.33, 0.17, 0, 0.15};

    private static byte source(byte device, Rng r) {
        return (byte) r.weighted(device == SiteInput.DEV_APP ? SRC_APP : SRC_WEB);
    }

    /** Пауза между событиями визита: 6 с + логнормальное (медиана 30 с) — по таблице квантилей, без exp на событие. */
    private static long gap(Rng r) {
        return 6 + GAPS[(int) (r.nextLong() >>> 54)];
    }

    private static final int[] GAPS = quantiles(30, 0.8, 900);

    /** 1024 квантиля логнормального распределения (медиана, σ), обрезанных сверху; {@link StrictMath} — одинаково везде. */
    static int[] quantiles(double median, double sigma, int cap) {
        int[] q = new int[1024];
        for (int i = 0; i < q.length; i++) {
            double z = inverseNormal((i + 0.5) / q.length);
            q[i] = (int) Math.min(cap, median * StrictMath.exp(sigma * z));
        }
        return q;
    }

    /** Обратная функция нормального распределения (приближение Акклама, точность ≈ 1e-9). */
    static double inverseNormal(double p) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02,
                -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01,
                -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00,
                4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double lo = 0.02425, hi = 1 - lo;
        if (p < lo) {
            double q = StrictMath.sqrt(-2 * StrictMath.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > hi) {
            double q = StrictMath.sqrt(-2 * StrictMath.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double q = p - 0.5, r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    private void searchEvent(DayOut out, long at, int lot, Rng r) {
        int dept = in.sDept[in.lSale[lot]];
        Queries.Query q = Queries.make(in, lot, dept, r, cleanQueries);
        out.event(at, E_SEARCH, 0, 0);
        out.eQuery[out.ne - 1] = q.text();
        int cat = in.lCat[lot], maker = in.lMaker[lot];
        int cc = cat > 0 && cat < catCount.length ? catCount[cat] : 0;
        int mc = maker > 0 && maker < makerCount.length ? makerCount[maker] : 0;
        int n = switch (q.by()) {
            case 1 -> mc;
            case 2 -> Math.min(cc, mc);
            default -> cc;
        };
        if (q.fuzzy()) n = r.chance(0.4) ? 0 : n / (2 + r.nextInt(4));
        out.eResults[out.ne - 1] = n;
    }

    private void pageEvent(DayOut out, long at, int sale, int page) {
        out.event(at, E_PAGE, sale, 0);
        out.ePage[out.ne - 1] = (short) Math.min(page, Short.MAX_VALUE);
    }

    // ================================================================ визиты «посмотреть»

    private void browseClients(DayOut out, long day, long ds) {
        int fi = Rules.dayFactorIndex(day);
        float[] p0 = g.p0[fi];
        double f = Rules.DAY_FACTORS[fi];
        int n = Radix.lowerBound(in.consentingReg, 0, in.consentingReg.length, ds + DAY);
        for (int i = 0; i < n; i++) {
            int c = in.consenting[i];
            double u = g.stBrowse.unit(in.cKey[c] * 0x9E3779B97F4A7C15L + day);
            if (u < p0[i]) continue;
            double lambda = in.cVisits[c] * f;
            double prob = p0[i], cdf = prob;
            int k = 0;
            while (u >= cdf && k < 12) {
                k++;
                prob *= lambda / k;
                cdf += prob;
            }
            for (int j = 0; j < k; j++) {
                Rng r = g.stBrowse.at(in.cKey[c], day, 100 + j);
                byte via = g.rules.via(in, c, day);
                int dev = device(c, via, r);
                if (dev < 0) continue;
                boolean logged = !r.chance(Rules.ANON_CLIENT_SHARE);
                int city = r.chance(0.94) ? in.cCity[c] : 0;
                long start = startAt(ds, in.offsetOfCity(in.cCity[c]), r);
                if (start < in.cRegAt[c]) continue;
                int e0 = out.ne, s0 = out.ns;
                out.session(start, logged ? c : 0, in.devKey[dev], in.devKind[dev], source(in.devKind[dev], r), city);
                browse(out, r, in.cDept[c], start);
                out.mix[2] += out.ns - s0;
                out.mix[3] += out.ne - e0;
            }
        }
    }

    private void browseAnon(DayOut out, long day, long ds) {
        // добор до просмотров дня: сколько не дали визиты клиентов (но не меньше доли анонимных)
        double x = Math.max(viewsTarget * Rules.ANON_SHARE, viewsTarget - out.views) / Rules.ANON_VIEWS;
        long n = (long) x;
        if (g.stAnon.unit(day) < x - n) n++;
        long pool = g.anonKey.length;
        if (pool == 0) return;
        for (int k = 0; k < n; k++) {
            double u = g.stAnon.unit(day * 1_000_003L + k);
            int a = (int) Math.min(pool - 1, (long) (pool * u * u));
            Rng r = g.stAnon.at(a, day, k);
            int city = g.anonCity[a];
            long start = startAt(ds, in.offsetOfCity(city), r);
            int e0 = out.ne, s0 = out.ns;
            out.session(start, 0, g.anonKey[a], g.anonKind[a], source(g.anonKind[a], r), r.chance(0.95) ? city : 0);
            browse(out, r, -1, start);
            out.mix[4] += out.ns - s0;
            out.mix[5] += out.ne - e0;
        }
    }

    private static long startAt(long ds, int offset, Rng r) {
        int h = Rules.hour(r.nextDouble());
        long local = h * 3600L + r.nextInt(3600);
        return ds + Math.floorMod(local - offset, DAY);
    }

    /** События визита «посмотреть»: отскок, каталог по страницам, поиск, просмотр лотов подряд. */
    private void browse(DayOut out, Rng r, int dept, long start) {
        double u = r.nextDouble();
        long t = start;
        if (u < 0.12) {
            int l = pickLot(r, dept, t);
            if (l == 0) {
                out.dropEmpty();
                return;
            }
            if (r.chance(0.75)) out.event(t, E_VIEW, in.lSale[l], l);
            else pageEvent(out, t, in.lSale[l], in.lotPos[l] / 20 + 1);
            out.sEnd[out.ns - 1] = t + 5 + r.nextInt(55);
            return;
        }
        if (u < 0.5) {
            catalogue(out, r, dept, t);
        } else if (u < 0.71) {
            int l = pickLot(r, dept, t);
            if (l != 0) {
                searchEvent(out, t, l, r);
                if (r.chance(0.9)) out.event(t += gap(r), E_VIEW, in.lSale[l], l);
                int more = r.weighted(SEARCH_MORE);
                int d = in.sDept[in.lSale[l]];
                for (int k = 0; k < more; k++) {
                    t += gap(r);
                    int m = pickLot(r, d, t);
                    if (m != 0) out.event(t, E_VIEW, in.lSale[m], m);
                }
                for (int k = 0; k < 3 && r.chance(0.35); k++) {
                    t += gap(r);
                    int m = pickLot(r, d, t);
                    if (m == 0) break;
                    searchEvent(out, t, m, r);
                    out.event(t += gap(r), E_VIEW, in.lSale[m], m);
                    int again = r.nextInt(3);
                    for (int j = 0; j < again; j++) {
                        t += gap(r);
                        int x = pickLot(r, d, t);
                        if (x != 0) out.event(t, E_VIEW, in.lSale[x], x);
                    }
                }
            }
        } else {
            int views = 3 + r.nextInt(14);
            for (int k = 0; k < views; k++) {
                int l = pickLot(r, dept, t);
                if (l == 0) break;
                if (r.chance(0.12)) {
                    pageEvent(out, t, in.lSale[l], in.lotPos[l] / 20 + 1);
                    t += gap(r);
                }
                out.event(t, E_VIEW, in.lSale[l], l);
                t += gap(r);
            }
        }
        if (out.sEvN[out.ns - 1] == 0) {
            out.dropEmpty();
            return;
        }
        out.sEnd[out.ns - 1] = out.lastAt() + 5 + r.nextInt(85);
    }

    private static final double[] PAGE_VIEWS = {0.12, 0.25, 0.25, 0.18, 0.12, 0.08};
    private static final double[] SEARCH_MORE = {0.12, 0.16, 0.18, 0.16, 0.13, 0.1, 0.08, 0.05, 0.02};
    private static final double[] TAIL = {0.25, 0.25, 0.2, 0.15, 0.1, 0.05};

    private void catalogue(DayOut out, Rng r, int dept, long t) {
        int l = pickLot(r, dept, t);
        if (l == 0) return;
        int s = in.lSale[l];
        int size = in.saleFrom[s + 1] - in.saleFrom[s];
        int pages = (size + 19) / 20;
        int page = r.chance(0.3) ? 1 : in.lotPos[l] / 20 + 1;
        boolean firstPage = true;
        for (int g2 = 0; g2 < 10 && page <= pages; g2++) {
            pageEvent(out, t, s, page);
            int v = r.weighted(PAGE_VIEWS);
            int base = in.saleFrom[s] + (page - 1) * 20, onPage = Math.min(20, size - (page - 1) * 20);
            for (int k = 0; k < v; k++) {
                t += gap(r);
                int m = firstPage && k == 0 && in.lotPos[l] / 20 + 1 == page ? l : in.saleLots[base + r.nextInt(onPage)];
                if (in.lPub[m] <= t) out.event(t, E_VIEW, s, m);
            }
            firstPage = false;
            if (!r.chance(0.72)) break;
            page++;
            t += gap(r);
        }
    }

    // ================================================================ уведомления

    /** Задержка постановки в очередь после события, с: от и до, по виду ({@code Rules.N_*}). */
    private static final int[][] DELAY = {{1, 120}, {0, 3}, {0, 30}, {5, 60}, {0, 1800}, {1, 60}, {0, 300}, {1, 60},
            {1, 120}, {1, 120}, {1, 300}, {1, 300}};

    private void notices(DayOut out, long day, long ds, long de) {
        // события мира: регистрации, ставки, итоги, деньги
        int from = Radix.lowerBound(in.srcAt, 0, in.nSrc, ds);
        int to = Radix.lowerBound(in.srcAt, from, in.nSrc, de);
        for (int j = from; j < to; j++) {
            byte kind = in.srcKind[j];
            int c = in.srcClient[j];
            int[] dl = DELAY[kind];
            int dmin = dl[0], dmax = dl[1];
            if (kind == Rules.N_WON && in.srcLot[j] > 0 && in.sFormat[in.lSale[in.srcLot[j]]] == SiteInput.F_LIVE) {
                dmin = 60;
                dmax = 600;
            }
            byte ch = kind == Rules.N_OUTBID ? pushOr(c) : CH_EMAIL;
            notice(out, kind, c, ch, in.srcAt[j], dmin, dmax, de, in.srcSale[j], in.srcLot[j], in.srcInvoice[j],
                    in.srcKeyA[j], in.srcKeyB[j]);
            if ((kind == Rules.N_WON || kind == Rules.N_INVOICE) && pushable(c)) {
                notice(out, kind, c, CH_PUSH, in.srcAt[j], dmin, dmax, de, in.srcSale[j], in.srcLot[j], in.srcInvoice[j],
                        in.srcKeyA[j], in.srcKeyB[j]);
            }
        }
        // лот закрывается: за час до плана — наблюдающим на этот момент
        from = Radix.lowerBound(in.timedPlan, 0, in.timedPlan.length, ds + 3600);
        to = Radix.lowerBound(in.timedPlan, from, in.timedPlan.length, de + 3600);
        for (int j = from; j < to; j++) {
            int l = in.timedByPlan[j];
            long t = in.lPlan[l] - 3600;
            if (t < in.lPub[l] || (in.lWithdrawn[l] != 0 && in.lWithdrawn[l] <= t)) continue;
            for (int k = w.lotFrom[l]; k < w.lotFrom[l + 1]; k++) {
                if (w.added[k] > t || (w.removed[k] != 0 && w.removed[k] <= t)) continue;
                int c = w.client[k];
                notice(out, Rules.N_CLOSING, c, pushOr(c), t, 0, 30, de, in.lSale[l], l, 0, l, c);
            }
        }
    }

    private boolean pushable(int c) {
        return (in.cFlags[c] & SiteInput.C_PUSH) != 0 && in.appDev[c] >= 0;
    }

    private byte pushOr(int c) {
        return pushable(c) ? CH_PUSH : CH_EMAIL;
    }

    /**
     * Уведомление: в очередь через {@code [dmin, dmax]} с после события (не позже конца суток), отправка с повторами,
     * тихие часы push, отписка от итогов торгов; на срезе — в очереди. Случайность — от (вид, ключ события, получатель,
     * канал).
     */
    private void notice(DayOut out, byte kind, int c, byte ch, long trigger, int dmin, int dmax, long de, int sale, int lot,
                        int invoice, long keyA, long keyB) {
        Rng r = g.stNotify.at(kind * 1_000_003L + keyA, keyB, ((long) c << 1) | ch);
        long created = Math.min(de - 1, trigger + dmin + r.nextInt(dmax - dmin + 1));
        if (created >= in.cutoff) return;
        int i = out.notice(created, kind, c, ch, sale, lot, invoice, keyA, keyB);
        deliver(out, i, kind, c, ch, created, r);
        if (out.nStatus[i] == Rules.ST_DELIVERED && r.chance(Rules.DUPLICATE_SHARE)) {
            long dc = Math.min(de - 1, created + 1 + r.nextInt(3));
            if (dc >= in.cutoff) return;
            int d = out.notice(dc, kind, c, ch, sale, lot, invoice, keyA, keyB);
            deliver(out, d, kind, c, ch, dc, r);
            out.nDup[d] = true;
        }
    }

    private void deliver(DayOut out, int i, byte kind, int c, byte ch, long created, Rng r) {
        long cutoff = in.cutoff;
        boolean push = ch == CH_PUSH;
        if (kind == Rules.N_SUMMARY && (in.cFlags[c] & SiteInput.C_MARKETING) == 0) {
            out.nStatus[i] = Rules.ST_SUPPRESSED;
            return;
        }
        boolean bad = g.stNotify.unit(in.cKey[c] * 2 + ch) < (push ? 0.01 : 0.003);
        long t = created + (push ? 1 + r.nextInt(4) : 2 + r.nextInt(39));
        long sent = 0;
        for (int a = 1; a <= Rules.MAX_ATTEMPTS; a++) {
            if (t >= cutoff) {
                out.nStatus[i] = Rules.ST_PENDING;
                break;
            }
            if (push && Rules.quiet(in, c, t)) {
                out.nStatus[i] = Rules.ST_SUPPRESSED;
                break;
            }
            sent = t;
            out.nAttempts[i] = (short) a;
            if (!bad && r.chance(push ? 0.97 : 0.985)) {
                long dl = t + (push ? r.nextInt(4) : 1 + r.nextInt(90));
                if (dl >= cutoff) out.nStatus[i] = Rules.ST_PENDING;
                else {
                    out.nDelivered[i] = dl;
                    out.nStatus[i] = Rules.ST_DELIVERED;
                }
                break;
            }
            if (a == Rules.MAX_ATTEMPTS) {
                out.nStatus[i] = Rules.ST_FAILED;
                break;
            }
            t += Rules.BACKOFF[a - 1];
        }
        out.nSent[i] = sent;
    }

    // ================================================================ выбор по весу

    /** Метод псевдонимов (Уокер — Воуз) над подмножеством видимых лотов. */
    static final class Alias {
        int n;
        int[] lot = new int[64], other = new int[64];
        double[] prob = new double[64];
        private int[] small = new int[64], large = new int[64];
        private double[] scaled = new double[64];

        interface DeptOf {
            int dept(int lot);
        }

        void build(int[] lots, double[] wts, int count, DeptOf deptOf, int dept) {
            n = 0;
            double total = 0;
            for (int i = 0; i < count; i++) {
                if (deptOf != null && deptOf.dept(lots[i]) != dept) continue;
                if (n == lot.length) grow(n * 2);
                lot[n] = lots[i];
                scaled[n] = wts[i];
                total += wts[i];
                n++;
            }
            if (n == 0 || total <= 0) {
                n = 0;
                return;
            }
            int ns = 0, nl = 0;
            for (int i = 0; i < n; i++) {
                scaled[i] = scaled[i] * n / total;
                if (scaled[i] < 1) small[ns++] = i;
                else large[nl++] = i;
            }
            while (ns > 0 && nl > 0) {
                int s = small[--ns], l = large[--nl];
                prob[s] = scaled[s];
                other[s] = l;
                scaled[l] = scaled[l] + scaled[s] - 1;
                if (scaled[l] < 1) small[ns++] = l;
                else large[nl++] = l;
            }
            while (nl > 0) {
                int l = large[--nl];
                prob[l] = 1;
                other[l] = l;
            }
            while (ns > 0) {
                int s = small[--ns];
                prob[s] = 1;
                other[s] = s;
            }
        }

        private void grow(int k) {
            lot = Arrays.copyOf(lot, k);
            other = Arrays.copyOf(other, k);
            prob = Arrays.copyOf(prob, k);
            small = Arrays.copyOf(small, k);
            large = Arrays.copyOf(large, k);
            scaled = Arrays.copyOf(scaled, k);
        }

        int pick(Rng r) {
            double u = r.nextDouble() * n;
            int i = (int) u;
            if (i >= n) i = n - 1;
            return u - i < prob[i] ? lot[i] : lot[other[i]];
        }
    }
}
