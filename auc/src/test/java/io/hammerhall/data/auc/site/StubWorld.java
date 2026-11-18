package io.hammerhall.data.auc.site;

import io.hammerhall.data.auc.bid.AuctionRules;
import io.hammerhall.data.auc.bid.Behavior;
import io.hammerhall.data.auc.bid.BidEngine;
import io.hammerhall.data.auc.bid.BidKind;
import io.hammerhall.data.auc.bid.Bidder;
import io.hammerhall.data.auc.bid.Channel;
import io.hammerhall.data.auc.bid.EventType;
import io.hammerhall.data.auc.bid.IncrementTable;
import io.hammerhall.data.auc.bid.Lot;
import io.hammerhall.data.auc.bid.LotOutcome;
import io.hammerhall.data.auc.bid.RejectReason;
import io.hammerhall.data.auc.bid.Strategy;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Упрощённый мир для тестов модуля сайта (без модулей дома, клиентов, торгов и денег): правдоподобный календарь (онлайн-торги
 * волной, живые сессиями), лоты, клиенты с устройствами, участники лотов и **ставки настоящего движка торгов**
 * ({@link BidEngine}), регистрации, счета «все платят на 3-й день», отчёты и выплаты. Всё моделируется до среза +
 * 180 суток и только потом отбирается по срезу — как у модуля торгов: прогон с более ранним срезом даёт ту же
 * историю. Числа — порядка набора, не точные: такой мир нужен для скорости и тестов модуля, не для выпуска.
 */
public final class StubWorld {
    private StubWorld() {
    }

    private static final long DAY = 86_400, HOUR = 3600, MSK = 3 * HOUR;
    /** Конец периода набора — календарь упрощённого мира не зависит от среза. */
    private static final LocalDate PERIOD_FROM = LocalDate.of(2025, 10, 1);
    private static final LocalDate CAL_TO = LocalDate.of(2028, 3, 1);
    private static final long HORIZON = 180 * DAY;

    // город: id, смещение от UTC (ч), население (тыс.)
    private static final int[][] CITIES = {
            {1, 3, 12600}, {2, 3, 5600}, {3, 5, 1500}, {4, 7, 1630}, {5, 3, 1310}, {6, 3, 1250}, {7, 4, 1180},
            {8, 3, 1140}, {9, 5, 1040}, {10, 3, 1020}, {11, 4, 1010}, {12, 7, 1000}, {13, 10, 600}, {14, 9, 550},
            {15, 8, 620}, {16, 2, 490}, {17, 6, 1170}, {18, 12, 160}, {19, 11, 90}, {20, 3, 1060}};

    private static final String[][] CATS = {
            {"пейзаж", "портрет", "натюрморт"}, {"брошь", "кольцо", "часы"}, {"рубль", "медаль", "жетон"},
            {"атлас", "рукопись", "книга"}, {"ваза", "сервиз", "подсвечник"}, {"марка", "открытка", "игрушка"}};
    private static final String[] SYL = {"ра", "ло", "ми", "ше", "ве", "до", "ку", "та", "ни", "бо", "ле", "зу", "ки",
            "на", "ро", "пе", "сы", "ха", "гу", "че"};
    private static final String[] END = {"ов", "ин", "ский", "ев", "ых", "ко", "цов", "ман"};

    /** Упрощённый мир: срез {@code cutoff}, масштаб {@code scale}. */
    public static SiteInput build(long seed, double scale, long cutoff) {
        RandomStreams rs = new RandomStreams(seed);
        SiteInput in = new SiteInput();
        in.cutoff = cutoff;
        in.scale = scale;
        in.firstDay = PERIOD_FROM.toEpochDay();

        // города
        int maxCity = 0;
        for (int[] c : CITIES) maxCity = Math.max(maxCity, c[0]);
        in.cityOffset = new int[maxCity + 1];
        in.cityIds = new int[CITIES.length];
        in.cityWeight = new double[CITIES.length];
        for (int i = 0; i < CITIES.length; i++) {
            in.cityOffset[CITIES[i][0]] = CITIES[i][1] * 3600;
            in.cityIds[i] = CITIES[i][0];
            in.cityWeight[i] = CITIES[i][2];
        }
        clients(in, rs, scale);
        in.catName = new String[19];
        int[][] deptCats = new int[6][3];
        for (int d = 0; d < 6; d++) {
            for (int j = 0; j < 3; j++) {
                in.catName[d * 3 + j + 1] = CATS[d][j];
                deptCats[d][j] = d * 3 + j + 1;
            }
        }
        return trade(in, rs, scale, cutoff, deptCats);
    }

    /**
     * Торги, ставки и деньги упрощённого мира поверх готовых клиентов, городов и категорий входа (у набора — из {@code HOUSE} и
     * {@code CLIENTS}): {@code deptCats[отдел]} — категории-листья отдела.
     */
    static SiteInput trade(SiteInput in, RandomStreams rs, double scale, long cutoff, int[][] deptCats) {
        long horizonEnd = cutoff + HORIZON;
        int nMakers = 300;
        in.makerName = new String[nMakers + 1];
        RandomStreams.Stream sm = rs.stream("stub.makers");
        for (int m = 1; m <= nMakers; m++) {
            Rng r = sm.at(m);
            String s = SYL[r.nextInt(SYL.length)] + SYL[r.nextInt(SYL.length)] + END[r.nextInt(END.length)];
            in.makerName[m] = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        // календарь: торги с каталогом до среза
        List<long[]> sales = calendar(rs, scale, cutoff);
        // id торгов — по публикации каталога, затем началу (как у календаря торгов: префикс при --to)
        sales.sort((a, b) -> a[2] != b[2] ? Long.compare(a[2], b[2]) : a[3] != b[3] ? Long.compare(a[3], b[3])
                : Long.compare(a[6], b[6]));
        int ns = sales.size();
        in.nSales = ns;
        in.sFormat = new byte[ns + 1];
        in.sDept = new byte[ns + 1];
        in.sPub = new long[ns + 1];
        in.sOpens = new long[ns + 1];
        in.sStart = new long[ns + 1];
        in.sEnd = new long[ns + 1];
        int nl = 0;
        for (int s = 1; s <= ns; s++) {
            long[] x = sales.get(s - 1);
            in.sFormat[s] = (byte) x[0];
            in.sDept[s] = (byte) x[1];
            in.sPub[s] = x[2];
            in.sStart[s] = x[3];
            in.sOpens[s] = x[4];
            nl += (int) x[5];
        }
        // лоты: по (публикация, торги, номер) — торги уже по началу, публикация растёт не строго, поэтому сортировка
        long[] lk = new long[nl];
        int[] lv = new int[nl];
        int[] lotSale = new int[nl], lotNo = new int[nl];
        int q = 0;
        for (int s = 1; s <= ns; s++) {
            int n = (int) sales.get(s - 1)[5];
            for (int k = 1; k <= n; k++) {
                lotSale[q] = s;
                lotNo[q] = k;
                lk[q] = (in.sPub[s] << 30) | ((long) s << 14) | k;
                lv[q] = q;
                q++;
            }
        }
        Radix.sort(lk, lv, nl, 64);
        in.nLots = nl;
        in.lKey = new long[nl + 1];
        in.lSale = new int[nl + 1];
        in.lNo = new int[nl + 1];
        in.lPub = new long[nl + 1];
        in.lPlan = new long[nl + 1];
        in.lClosed = new long[nl + 1];
        in.lWithdrawn = new long[nl + 1];
        in.lBuyer = new int[nl + 1];
        in.lPop = new float[nl + 1];
        in.lCat = new int[nl + 1];
        in.lMaker = new int[nl + 1];
        long[] lHigh = new long[nl + 1];
        RandomStreams.Stream sl = rs.stream("stub.lots");
        for (int j = 0; j < nl; j++) {
            int l = j + 1, src = lv[j], s = lotSale[src];
            in.lSale[l] = s;
            in.lNo[l] = lotNo[src];
            in.lKey[l] = ((long) s << 16) | lotNo[src];
            in.lPub[l] = in.sPub[s];
            int k = lotNo[src] - 1;
            if (in.sFormat[s] == SiteInput.F_TIMED) {
                in.lPlan[l] = in.sStart[s] + (k / 1200) * DAY + (k % 1200) / 5 * 60L;
            } else {
                in.lPlan[l] = in.sStart[s] + (k / 220) * DAY + (k % 220) * 48L;
            }
            Rng r = sl.at(in.lKey[l]);
            int d = in.sDept[s];
            in.lCat[l] = deptCats[d][r.nextInt(deptCats[d].length)];
            in.lMaker[l] = r.chance(0.6) ? 1 + d * 50 + r.nextInt(50) : 0;
            double med = in.sFormat[s] == SiteInput.F_TIMED ? 12_000 : 120_000;
            lHigh[l] = Math.max(1500, Math.round(r.logNormal(med, 1.0) / 100) * 100);
        }
        bidding(in, rs, lHigh, cutoff, horizonEnd);
        money(in, rs, cutoff);
        return in.finish(x -> new StubSources(x).build());
    }

    /** Торги и деньги поверх дома и клиентов мира ({@link WorldInput#base}) — вход сайта без модулей торгов и денег. */
    static SiteInput trade(io.hammerhall.data.core.gen.GenContext ctx) {
        WorldInput.Base b = WorldInput.base(ctx);
        return trade(b.in(), ctx.streams(), b.in().scale, b.in().cutoff, b.deptCats());
    }

    // ---------------------------------------------------------------- клиенты

    private static void clients(SiteInput in, RandomStreams rs, double scale) {
        int n = (int) Math.max(300, Math.round(730_000 * scale));
        in.nClients = n;
        in.cKey = new long[n + 1];
        in.cCity = new int[n + 1];
        in.cRegAt = new long[n + 1];
        in.cFlags = new byte[n + 1];
        in.cVisits = new float[n + 1];
        in.cDept = new byte[n + 1];
        in.cDevFrom = new int[n + 2];
        long[] dk = new long[n * 3];
        byte[] dkind = new byte[n * 3];
        int nd = 0;
        double[] cum = new double[CITIES.length];
        double acc = 0;
        for (int i = 0; i < CITIES.length; i++) cum[i] = acc += CITIES[i][2];
        long from = LocalDate.of(2012, 1, 1).toEpochDay() * DAY, mid = PERIOD_FROM.toEpochDay() * DAY;
        long to = LocalDate.of(2027, 10, 30).toEpochDay() * DAY;
        RandomStreams.Stream st = rs.stream("stub.clients");
        long[] reg = new long[n];
        for (int i = 0; i < n; i++) {
            Rng r = st.at(i, 0);
            reg[i] = r.chance(0.55) ? from + (long) (r.nextDouble() * (mid - from)) : mid + (long) (r.nextDouble() * (to - mid));
        }
        Arrays.sort(reg);
        for (int c = 1; c <= n; c++) {
            Rng r = st.at(c, 1);
            in.cKey[c] = 1_000_000_000L + c;
            in.cRegAt[c] = reg[c - 1];
            double u = r.nextDouble() * acc;
            int ci = 0;
            while (cum[ci] <= u) ci++;
            in.cCity[c] = CITIES[ci][0];
            byte f = 0;
            if (r.chance(0.62)) f |= SiteInput.C_ANALYTICS;
            if (r.chance(0.45)) f |= SiteInput.C_MARKETING;
            boolean app = r.chance(0.42);
            if (app) f |= SiteInput.C_PUSH;
            in.cFlags[c] = f;
            in.cVisits[c] = (float) Math.min(1.5, r.logNormal(0.006, 1.1));
            in.cDept[c] = (byte) r.weighted(new double[]{0.24, 0.2, 0.16, 0.12, 0.16, 0.12});
            in.cDevFrom[c] = nd;
            boolean desk = r.chance(0.7), mob = !desk || r.chance(0.5);
            if (desk) {
                dk[nd] = r.nextLong();
                dkind[nd++] = SiteInput.DEV_DESKTOP;
            }
            if (mob) {
                dk[nd] = r.nextLong();
                dkind[nd++] = SiteInput.DEV_MOBILE;
            }
            if (app) {
                dk[nd] = r.nextLong();
                dkind[nd++] = SiteInput.DEV_APP;
            }
        }
        in.cDevFrom[n + 1] = nd;
        // связанные учётные записи (подставные, сговор): одно устройство на две записи — у каждой сотой пары
        for (int c = 2; c <= n; c += 997) dk[in.cDevFrom[c]] = dk[in.cDevFrom[c - 1]];
        in.devKey = Arrays.copyOf(dk, nd);
        in.devKind = Arrays.copyOf(dkind, nd);
    }

    // ---------------------------------------------------------------- календарь

    /** Торги: {формат, отдел, публикация, начало (первое закрытие или первый слот), открытие ставок, лотов, ключ}. */
    private static List<long[]> calendar(RandomStreams rs, double scale, long cutoff) {
        List<long[]> out = new ArrayList<>();
        RandomStreams.Stream st = rs.stream("stub.calendar");
        int thematic = 0;
        for (LocalDate d = PERIOD_FROM; d.isBefore(CAL_TO); d = d.plusDays(1)) {
            DayOfWeek w = d.getDayOfWeek();
            int[] depts = switch (w) {
                case TUESDAY -> new int[]{2, 3};
                case WEDNESDAY -> new int[]{1, 4};
                case THURSDAY -> new int[]{0, 5};
                case SUNDAY -> new int[]{-1, -1};
                default -> new int[0];
            };
            if (d.getMonthValue() == 1 && d.getDayOfMonth() <= 10) continue;
            for (int j = 0; j < depts.length; j++) {
                int dept = depts[j] >= 0 ? depts[j] : (thematic++ % 6);
                long key = (d.toEpochDay() << 4) | (j << 1) | 1;
                Rng r = st.at(key);
                int dur = r.weighted(new double[]{0.55, 0.30, 0.15});
                int days = dur == 0 ? 7 : dur == 1 ? 10 : 14;
                long close = d.toEpochDay() * DAY + 19 * HOUR - MSK;
                long pub = close - (days + 3) * DAY - 7 * HOUR;
                if (pub < PERIOD_FROM.toEpochDay() * DAY || pub >= cutoff) continue;
                double summer = d.getMonthValue() >= 6 && d.getMonthValue() <= 8 ? 0.85 : 1.0;
                int lots = (int) Math.max(5, Math.round(r.logNormal(1200 * summer, 0.87) * scale));
                out.add(new long[]{SiteInput.F_TIMED, dept, pub, close, pub + 3 * DAY, lots, key});
            }
            // живые: вт–сб в сезоны, ≈ 60 в год
            boolean season = (d.getMonthValue() >= 3 && d.getMonthValue() <= 5) || (d.getMonthValue() >= 10 && d.getMonthValue() <= 11);
            boolean workday = w != DayOfWeek.SUNDAY && w != DayOfWeek.MONDAY;
            if (workday && (season || d.getDayOfMonth() == 20)) {
                long key = (d.toEpochDay() << 4) | 8;
                Rng r = st.at(key);
                if (r.chance(season ? 0.42 : 0.5)) {
                    int dept = r.weighted(new double[]{16, 12, 10, 10, 8, 4});
                    long start = d.toEpochDay() * DAY + 12 * HOUR - MSK;
                    long pub = start - 21 * DAY;
                    if (pub < PERIOD_FROM.toEpochDay() * DAY || pub >= cutoff) continue;
                    int lots = (int) Math.max(10, Math.round(r.logNormal(220, 0.7) * scale));
                    out.add(new long[]{SiteInput.F_LIVE, dept, pub, start, pub, lots, key});
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- торги

    private static void bidding(SiteInput in, RandomStreams rs, long[] lHigh, long cutoff, long horizonEnd) {
        BidEngine engine = new BidEngine(AuctionRules.house(IncrementTable.standard()), Behavior.standard());
        LotOutcome out = new LotOutcome();
        RandomStreams.Stream sp = rs.stream("stub.participants"), sb = rs.stream("stub.bids");
        int nl = in.nLots;
        // участники: (лот, клиент, первая ставка, онлайн)
        int cap = nl * 4 + 16;
        int[] pLot = new int[cap], pClient = new int[cap];
        long[] pFirst = new long[cap];
        boolean[] pOnline = new boolean[cap];
        int np = 0;
        // ставки (факты): лот, seq, мс, клиент, флаги
        int bcap = nl * 8 + 16;
        int[] bLot = new int[bcap], bSeq = new int[bcap], bClient = new int[bcap];
        long[] bMsAll = new long[bcap];
        short[] bFl = new short[bcap];
        int nb = 0;
        // поручения (факты)
        int ocap = nl * 2 + 16;
        int[] oLot = new int[ocap], oClient = new int[ocap], oSeq = new int[ocap];
        long[] oMs = new long[ocap];
        int no = 0;
        int[] who = new int[64];
        boolean[] online = new boolean[64];
        boolean[] partner = new boolean[64];
        long[] firstMs = new long[64];
        for (int l = 1; l <= nl; l++) {
            int s = in.lSale[l];
            boolean timed = in.sFormat[s] == SiteInput.F_TIMED;
            Rng r = sp.at(in.lKey[l]);
            int k = 0;
            if (!r.chance(0.16)) {
                double mean = 3.8 + Math.max(0, Math.log(lHigh[l] / 100_000.0) / Math.log(2));
                k = 1 + (int) Math.min(39, r.exponential(mean - 1) + r.nextDouble());
            }
            if (k > who.length) {
                who = new int[k * 2];
                online = new boolean[k * 2];
                partner = new boolean[k * 2];
                firstMs = new long[k * 2];
            }
            int dept = in.sDept[s];
            long regBefore = in.sOpens[s];
            int m = 0;
            for (int t = 0; t < k * 3 && m < k; t++) {
                int c;
                if (r.chance(0.7)) {
                    int[] pool = poolOf(in, dept);
                    int bound = upper(regOf(in, pool), regBefore);
                    if (bound == 0) continue;
                    c = pool[r.nextInt(bound)];
                } else {
                    // клиенты — по дате регистрации: зарегистрированные раньше — первые id (от среза не зависит)
                    int known = upper(in.cRegAt, 1, in.nClients + 1, regBefore) - 1;
                    if (known <= 0) continue;
                    c = 1 + r.nextInt(known);
                    if (in.cDept[c] < 0) continue;
                }
                boolean dup = false;
                for (int x = 0; x < m; x++) dup |= who[x] == c;
                if (dup) continue;
                who[m] = c;
                online[m] = timed || r.chance(0.55);
                partner[m] = !timed && online[m] && r.chance(0.25);
                m++;
            }
            in.lPop[l] = m;
            if (m == 0) {
                if (timed) in.lClosed[l] = in.lPlan[l] < cutoff ? in.lPlan[l] : 0;
                else in.lClosed[l] = in.lPlan[l] + 20 < cutoff ? in.lPlan[l] + 20 : 0;
                continue;
            }
            long high = lHigh[l] * 100;
            long low = high * 2 / 3;
            long start = Math.max(50_00, low * 6 / 10);
            long reserve = r.chance(0.85) ? low * 8 / 10 : 0;
            List<Bidder> bidders = new ArrayList<>(m);
            boolean war = m >= 2 && r.chance(0.02);
            for (int x = 0; x < m; x++) {
                long max = Math.max(start, (long) (high * r.logNormal(0.95, 0.45)));
                Bidder b;
                if (timed) {
                    Strategy stg = war && x < 2 ? Strategy.WAR : pickStrategy(r);
                    b = Bidder.online(x + 1, stg, max);
                } else if (online[x]) {
                    b = Bidder.of(x + 1, Channel.ONLINE, pickStrategy(r), max);
                } else {
                    double u = r.nextDouble();
                    if (u < 0.4) b = Bidder.absentee(x + 1, max, (in.lPlan[l] - 3600 - r.nextInt(40 * 3600)) * 1000);
                    else if (u < 0.6 && lHigh[l] >= 30_000) b = Bidder.of(x + 1, Channel.PHONE, Strategy.MAX, max);
                    else b = Bidder.of(x + 1, Channel.ROOM, pickStrategy(r), max);
                }
                bidders.add(b);
            }
            Lot lot = timed
                    ? Lot.online(in.lKey[l], start, reserve, in.sOpens[s] * 1000, in.lPlan[l] * 1000)
                    : Lot.live(in.lKey[l], start, reserve, in.lPlan[l] * 1000, (in.lPlan[l] + 48) * 1000);
            Rng er = sb.at(in.lKey[l]);
            RandomGenerator g = er::nextLong;
            engine.run(lot, bidders, g, out);
            Arrays.fill(firstMs, 0, m, Long.MAX_VALUE);
            for (int i = 0; i < out.size(); i++) {
                EventType et = out.type(i);
                if (et != EventType.BID && et != EventType.MAX_RAISE) continue;
                int bi = out.bidderIndex(i);
                long t = out.time(i);
                if (bi >= 0 && et == EventType.BID) firstMs[bi] = Math.min(firstMs[bi], t);
                if (t / 1000 >= cutoff) continue;
                int client = bi >= 0 ? who[bi] : 0;
                BidKind kind = out.kind(i);
                boolean isMax = et == EventType.MAX_RAISE || (kind == BidKind.MAX);
                if (isMax && bi >= 0 && out.channel(i) == Channel.ONLINE && !partner[bi]
                        && (et == EventType.BID || out.accepted(i))) {
                    if (no == oLot.length) {
                        oLot = Arrays.copyOf(oLot, no * 2);
                        oClient = Arrays.copyOf(oClient, no * 2);
                        oSeq = Arrays.copyOf(oSeq, no * 2);
                        oMs = Arrays.copyOf(oMs, no * 2);
                    }
                    oLot[no] = l;
                    oClient[no] = client;
                    oSeq[no] = out.seq(i);
                    oMs[no++] = t;
                }
                if (et != EventType.BID) continue;
                if (nb == bLot.length) {
                    bLot = Arrays.copyOf(bLot, nb * 2);
                    bSeq = Arrays.copyOf(bSeq, nb * 2);
                    bClient = Arrays.copyOf(bClient, nb * 2);
                    bMsAll = Arrays.copyOf(bMsAll, nb * 2);
                    bFl = Arrays.copyOf(bFl, nb * 2);
                }
                short f = 0;
                if (out.accepted(i)) f |= SiteInput.B_ACCEPTED;
                if (kind == BidKind.MANUAL && out.channel(i) == Channel.ONLINE) f |= SiteInput.B_MANUAL;
                if (out.channel(i) == Channel.ONLINE) f |= SiteInput.B_ONLINE;
                if (out.hiddenRepeat(i)) f |= SiteInput.B_REPEAT;
                if (kind == BidKind.VENDOR) f |= SiteInput.B_VENDOR;
                if (bi >= 0 && partner[bi]) f |= SiteInput.B_PARTNER;
                if ((f & SiteInput.B_MANUAL) != 0 && out.reason(i) == RejectReason.BELOW_MINIMUM
                        && (Rng.mix64(in.lKey[l] * 31 + i) & 63) == 0) f |= SiteInput.B_NO_FORM;
                bLot[nb] = l;
                bSeq[nb] = out.seq(i);
                bClient[nb] = client;
                bMsAll[nb] = t;
                bFl[nb++] = f;
            }
            long closedMs = out.closedAt();
            if (closedMs / 1000 < cutoff) {
                in.lClosed[l] = closedMs / 1000;
                if (out.sold()) in.lBuyer[l] = who[out.winnerIndex()];
            }
            // участники — по лоту
            for (int x = 0; x < m; x++) {
                if (np == pLot.length) {
                    pLot = Arrays.copyOf(pLot, np * 2);
                    pClient = Arrays.copyOf(pClient, np * 2);
                    pFirst = Arrays.copyOf(pFirst, np * 2);
                    pOnline = Arrays.copyOf(pOnline, np * 2);
                }
                pLot[np] = l;
                pClient[np] = who[x];
                pFirst[np] = firstMs[x] == Long.MAX_VALUE ? in.lPlan[l] : firstMs[x] / 1000;
                pOnline[np++] = online[x];
            }
        }
        in.nPart = np;
        in.pLot = Arrays.copyOf(pLot, np);
        in.pClient = Arrays.copyOf(pClient, np);
        in.pFirstAt = Arrays.copyOf(pFirst, np);
        in.pOnline = Arrays.copyOf(pOnline, np);

        // ставки — по (момент, мс, лот, seq)
        long[] key = new long[nb];
        int[] idx = new int[nb];
        for (int i = 0; i < nb; i++) {
            key[i] = (bMsAll[i] << 22) | ((long) bLot[i] << 0);
            idx[i] = i;
        }
        // ключ: мс (42 бита) и лот (22 бита) — seq в пределах лота растёт по времени, порядок генерации его держит
        Radix.sort(key, idx, nb, 64);
        in.nBids = nb;
        in.bId = new long[nb];
        in.bLot = new int[nb];
        in.bClient = new int[nb];
        in.bAt = new int[nb];
        in.bMs = new short[nb];
        in.bFlags = new short[nb];
        for (int j = 0; j < nb; j++) {
            int i = idx[j];
            in.bId[j] = j + 1;
            in.bLot[j] = bLot[i];
            in.bClient[j] = bClient[i];
            in.bAt[j] = Math.toIntExact(bMsAll[i] / 1000);
            in.bMs[j] = (short) (bMsAll[i] % 1000);
            in.bFlags[j] = bFl[i];
        }
        // поручения — по моменту
        key = new long[no];
        idx = new int[no];
        for (int i = 0; i < no; i++) {
            key[i] = (oMs[i] << 22) | oLot[i];
            idx[i] = i;
        }
        Radix.sort(key, idx, no, 64);
        in.nOrders = no;
        in.oId = new int[no];
        in.oAt = new long[no];
        in.oLot = new int[no];
        in.oClient = new int[no];
        in.oVia = new byte[no];
        RandomStreams.Stream sv = rs.stream("stub.via");
        for (int j = 0; j < no; j++) {
            int i = idx[j];
            in.oId[j] = j + 1;
            in.oAt[j] = oMs[i] / 1000;
            in.oLot[j] = oLot[i];
            in.oClient[j] = oClient[i];
            boolean app = (in.cFlags[oClient[i]] & SiteInput.C_PUSH) != 0 && sv.unit(in.cKey[oClient[i]] ^ oMs[i]) < 0.55;
            in.oVia[j] = app ? SiteInput.VIA_APP : SiteInput.VIA_SITE;
        }
        registrations(in, rs, cutoff);
    }

    private static Strategy pickStrategy(Rng r) {
        double u = r.nextDouble();
        return u < 0.40 ? Strategy.MAX : u < 0.72 ? Strategy.EARLY : u < 0.92 ? Strategy.CAUTIOUS : Strategy.SNIPER;
    }

    private static int[] poolOf(SiteInput in, int dept) {
        if (in.deptPool == null) buildPools(in);
        return in.deptPool[dept];
    }

    private static long[] regOf(SiteInput in, int[] pool) {
        for (int d = 0; d < 6; d++) if (in.deptPool[d] == pool) return in.deptPoolReg[d];
        throw new IllegalStateException();
    }

    private static void buildPools(SiteInput in) {
        in.deptPool = new int[6][];
        in.deptPoolReg = new long[6][];
        for (int d = 0; d < 6; d++) {
            int n = 0;
            for (int c = 1; c <= in.nClients; c++) if (in.cDept[c] == d) n++;
            int[] p = new int[n];
            n = 0;
            for (int c = 1; c <= in.nClients; c++) if (in.cDept[c] == d) p[n++] = c;
            // клиенты уже по дате регистрации (id — по регистрации)
            in.deptPool[d] = p;
            in.deptPoolReg[d] = new long[n];
            for (int i = 0; i < n; i++) in.deptPoolReg[d][i] = in.cRegAt[p[i]];
        }
    }

    private static int upper(long[] a, long x) {
        return upper(a, 0, a.length, x);
    }

    /** Первый индекс в возрастающем {@code a[from..to)} со значением ≥ {@code x}. */
    private static int upper(long[] a, int from, int to, long x) {
        int lo = from, hi = to;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (a[m] < x) lo = m + 1;
            else hi = m;
        }
        return lo;
    }

    // ---------------------------------------------------------------- регистрации

    private static void registrations(SiteInput in, RandomStreams rs, long cutoff) {
        // пары (торги, клиент) из участников; момент — до первой ставки в торгах
        int np = in.nPart;
        long[] key = new long[np];
        int[] idx = new int[np];
        for (int i = 0; i < np; i++) {
            key[i] = ((long) in.lSale[in.pLot[i]] << 32) | in.pClient[i];
            idx[i] = i;
        }
        Radix.sort(key, idx, np, 64);
        List<long[]> regs = new ArrayList<>();
        RandomStreams.Stream st = rs.stream("stub.regs");
        for (int j = 0; j < np; ) {
            int e = j;
            long first = Long.MAX_VALUE;
            while (e < np && key[e] == key[j]) first = Math.min(first, in.pFirstAt[idx[e++]]);
            int s = (int) (key[j] >>> 32), c = (int) key[j];
            Rng r = st.at(key[j]);
            long at = Math.max(in.sOpens[s], first - 60 - (long) (r.nextDouble() * 3 * DAY));
            at = Math.max(at, in.cRegAt[c] + 1);
            if (at < cutoff) regs.add(new long[]{at, s, c});
            j = e;
        }
        regs.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[2], b[2]));
        int n = regs.size();
        in.nRegs = n;
        in.rId = new int[n];
        in.rClient = new int[n];
        in.rSale = new int[n];
        in.rDecided = new long[n];
        in.rFlags = new byte[n];
        for (int i = 0; i < n; i++) {
            long[] x = regs.get(i);
            int s = (int) x[1];
            in.rId[i] = i + 1;
            in.rSale[i] = s;
            in.rClient[i] = (int) x[2];
            byte f = 0;
            long dec = x[0];
            if (in.sFormat[s] == SiteInput.F_LIVE) {
                f |= SiteInput.R_MANUAL;
                dec = x[0] + 600 + Math.floorMod(Rng.mix64(x[0] ^ x[2]), 20 * HOUR);
            }
            in.rDecided[i] = dec < cutoff ? dec : 0;
            in.rFlags[i] = f;
        }
    }

    // ---------------------------------------------------------------- деньги

    private static void money(SiteInput in, RandomStreams rs, long cutoff) {
        // конец торгов — последний закрытый лот, если закрыты все
        int ns = in.nSales;
        long[] last = new long[ns + 1];
        boolean[] open = new boolean[ns + 1];
        for (int l = 1; l <= in.nLots; l++) {
            int s = in.lSale[l];
            if (in.lClosed[l] == 0) open[s] = true;
            else last[s] = Math.max(last[s], in.lClosed[l]);
        }
        for (int s = 1; s <= ns; s++) in.sEnd[s] = open[s] ? 0 : last[s];
        // счета: (торги, покупатель)
        List<long[]> inv = new ArrayList<>();
        long[] key = new long[in.nLots];
        int[] idx = new int[in.nLots];
        int n = 0;
        for (int l = 1; l <= in.nLots; l++) {
            if (in.lBuyer[l] == 0 || in.sEnd[in.lSale[l]] == 0) continue;
            key[n] = ((long) in.lSale[l] << 32) | in.lBuyer[l];
            idx[n++] = l;
        }
        Radix.sort(key, idx, n, 64);
        RandomStreams.Stream st = rs.stream("stub.money");
        for (int j = 0; j < n; ) {
            int e = j;
            while (e < n && key[e] == key[j]) e++;
            int s = (int) (key[j] >>> 32), c = (int) key[j];
            long issued = in.sEnd[s] + (in.sFormat[s] == SiteInput.F_TIMED ? 2 * HOUR : HOUR);
            if (issued < cutoff) inv.add(new long[]{issued, s, c});
            j = e;
        }
        inv.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[2], b[2]));
        int ni = inv.size();
        in.nInv = ni;
        in.iId = new int[ni];
        in.iClient = new int[ni];
        in.iSale = new int[ni];
        in.iIssued = new long[ni];
        in.iDue = new long[ni];
        in.iPaid = new long[ni];
        in.iCancelled = new long[ni];
        List<long[]> pays = new ArrayList<>();
        for (int i = 0; i < ni; i++) {
            long[] x = inv.get(i);
            in.iId[i] = i + 1;
            in.iIssued[i] = x[0];
            in.iSale[i] = (int) x[1];
            in.iClient[i] = (int) x[2];
            in.iDue[i] = x[0] + 10 * DAY;
            Rng r = st.at(x[0], x[2]);
            if (r.chance(0.97)) {
                long paid = x[0] + 3 * DAY + r.nextInt(8 * 3600);
                if (paid < cutoff) {
                    in.iPaid[i] = paid;
                    pays.add(new long[]{paid, i, x[2]});
                }
            } else if (x[0] + 31 * DAY < cutoff) {
                in.iCancelled[i] = x[0] + 31 * DAY;
            }
        }
        pays.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        in.nPay = pays.size();
        in.payAt = new long[in.nPay];
        in.payInvoice = new int[in.nPay];
        in.payClient = new int[in.nPay];
        in.payId = new int[in.nPay];
        for (int i = 0; i < in.nPay; i++) {
            long[] x = pays.get(i);
            in.payAt[i] = x[0];
            in.payInvoice[i] = in.iId[(int) x[1]];
            in.payClient[i] = (int) x[2];
        }
        in.nRefund = 0;
        in.refAt = new long[0];
        in.refClient = new int[0];
        in.refSale = new int[0];
        in.refId = new int[0];
        // отчёты комитентам: комитент лота — по ключу лота
        List<long[]> sets = new ArrayList<>();
        key = new long[in.nLots];
        idx = new int[in.nLots];
        n = 0;
        for (int l = 1; l <= in.nLots; l++) {
            int s = in.lSale[l];
            if (in.sEnd[s] == 0) continue;
            int known = upper(in.cRegAt, 1, in.nClients + 1, in.sPub[s]) - 1;
            if (known <= 0) continue;
            int c = 1 + (int) Long.remainderUnsigned(Rng.mix64(in.lKey[l] ^ 0x77), known);
            key[n] = ((long) s << 32) | c;
            idx[n++] = l;
        }
        Radix.sort(key, idx, n, 64);
        for (int j = 0; j < n; ) {
            int e = j;
            while (e < n && key[e] == key[j]) e++;
            int s = (int) (key[j] >>> 32), c = (int) key[j];
            long created = (in.sEnd[s] / DAY + 1) * DAY + 7 * HOUR;
            if (created < cutoff) sets.add(new long[]{created, s, c});
            j = e;
        }
        sets.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[2], b[2]));
        in.nSet = sets.size();
        in.stId = new int[in.nSet];
        in.stClient = new int[in.nSet];
        in.stSale = new int[in.nSet];
        in.stCreated = new long[in.nSet];
        List<long[]> payouts = new ArrayList<>();
        for (int i = 0; i < in.nSet; i++) {
            long[] x = sets.get(i);
            in.stId[i] = i + 1;
            in.stCreated[i] = x[0];
            in.stSale[i] = (int) x[1];
            in.stClient[i] = (int) x[2];
            long at = in.sEnd[(int) x[1]] + 35 * DAY + 4 * HOUR;
            if (at < cutoff && st.at(x[0], x[2] + 7).chance(0.8)) payouts.add(new long[]{at, i, x[2], x[1]});
        }
        payouts.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        in.nPayout = payouts.size();
        in.poAt = new long[in.nPayout];
        in.poSettlement = new int[in.nPayout];
        in.poClient = new int[in.nPayout];
        in.poSale = new int[in.nPayout];
        in.poId = new int[in.nPayout];
        for (int i = 0; i < in.nPayout; i++) {
            long[] x = payouts.get(i);
            in.poAt[i] = x[0];
            in.poSettlement[i] = in.stId[(int) x[1]];
            in.poClient[i] = (int) x[2];
            in.poSale[i] = (int) x[3];
        }
        // id платежей — по моменту: оплаты счетов и выплаты вместе (префикс при --to)
        int np = in.nPay, no = in.nPayout, pi = 0, oi = 0, id = 1;
        while (pi < np || oi < no) {
            if (oi >= no || (pi < np && in.payAt[pi] <= in.poAt[oi])) in.payId[pi++] = id++;
            else in.poId[oi++] = id++;
        }
        // флаги регистраций: ставки и выигрыши
        {
            long[] rk = new long[in.nRegs];
            for (int i = 0; i < in.nRegs; i++) rk[i] = ((long) in.rSale[i] << 32) | in.rClient[i];
            // rk упорядочен не по ключу — ищем по отсортированной копии
            int[] ord = Radix.order(rk, in.nRegs, 64);
            long[] sorted = new long[in.nRegs];
            for (int i = 0; i < in.nRegs; i++) sorted[i] = rk[ord[i]];
            for (int i = 0; i < in.nBids; i++) {
                if ((in.bFlags[i] & SiteInput.B_ACCEPTED) == 0 || in.bClient[i] == 0) continue;
                int r = find(sorted, ((long) in.lSale[in.bLot[i]] << 32) | in.bClient[i]);
                if (r >= 0) in.rFlags[ord[r]] |= SiteInput.R_BIDS;
            }
            for (int l = 1; l <= in.nLots; l++) {
                if (in.lBuyer[l] == 0) continue;
                int r = find(sorted, ((long) in.lSale[l] << 32) | in.lBuyer[l]);
                if (r >= 0) in.rFlags[ord[r]] |= SiteInput.R_WON;
            }
        }
    }

    private static int find(long[] a, long x) {
        int lo = 0, hi = a.length - 1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            if (a[m] < x) lo = m + 1;
            else if (a[m] > x) hi = m - 1;
            else return m;
        }
        return -1;
    }
}
