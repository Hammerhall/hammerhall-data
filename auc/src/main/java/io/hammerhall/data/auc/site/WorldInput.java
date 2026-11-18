package io.hammerhall.data.auc.site;

import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.Horizon;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Intake;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.LotResult;
import io.hammerhall.data.auc.world.MoneyWorld;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.random.Rng;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Переходник «мир → вход сайта» ({@link SiteInput}): города и категории — из {@code HOUSE}, клиенты — из
 * {@code CLIENTS} (устройства, пояс города, согласия, частота торгов, интерес к отделам); торги, ставки и события — из
 * {@code CALENDAR}, {@code AUCTION} и {@code MONEY}. Без торгов и денег в мире вход не строится; дом и клиенты —
 * {@link #base}.
 *
 * <p>Вид устройства выводится из его ключа (свойство устройства: одно у всех учётных записей с ним): компьютер,
 * телефон (сайт) или приложение; push — у клиентов с приложением (почти у всех — разрешены).
 */
final class WorldInput {
    private WorldInput() {
    }

    static SiteInput build(GenContext ctx) {
        Base b = base(ctx);
        if (!ctx.has(AucKeys.AUCTION) || !ctx.has(AucKeys.CALENDAR)) {
            throw new IllegalStateException("модулю site нужны торги и деньги в мире: модули auction и money");
        }
        auction(b.in(), ctx);
        return b.in().finish();
    }

    /** Общая часть входа — дом и клиенты; {@code deptCats} — листья категорий по отделам. */
    record Base(SiteInput in, int[][] deptCats) {
    }

    static Base base(GenContext ctx) {
        long cutoff = Horizon.cut(ctx.period());
        SiteInput in = new SiteInput();
        in.cutoff = cutoff;
        in.scale = ctx.profile().scale();
        in.firstDay = Math.floorDiv(ctx.period().startEpoch(), 86_400L);
        HouseRules h = ctx.get(AucKeys.HOUSE);
        cities(in, h, cutoff);
        int[][] deptCats = categories(in, h);
        clients(in, ctx.get(AucKeys.CLIENTS), cutoff);
        return new Base(in, deptCats);
    }

    // ---------------------------------------------------------------- дом

    private static void cities(SiteInput in, HouseRules h, long cutoff) {
        List<HouseRules.City> cs = h.cities();
        int max = 0;
        for (HouseRules.City c : cs) max = Math.max(max, c.id());
        in.cityOffset = new int[max + 1];
        in.cityIds = new int[cs.size()];
        in.cityWeight = new double[cs.size()];
        Instant at = Instant.ofEpochSecond(cutoff);
        for (int i = 0; i < cs.size(); i++) {
            HouseRules.City c = cs.get(i);
            in.cityOffset[c.id()] = c.zone().getRules().getOffset(at).getTotalSeconds();
            in.cityIds[i] = c.id();
            in.cityWeight[i] = Math.max(1, c.population());
        }
    }

    private static int[][] categories(SiteInput in, HouseRules h) {
        int max = 0;
        for (HouseRules.Category c : h.categories()) max = Math.max(max, c.id());
        in.catName = new String[max + 1];
        for (HouseRules.Category c : h.categories()) in.catName[c.id()] = c.nameRu();
        int[][] out = new int[Dept.count()][];
        for (Dept d : Dept.values()) {
            List<HouseRules.Category> leaves = h.leaves(d);
            out[d.ordinal()] = new int[leaves.size()];
            for (int i = 0; i < leaves.size(); i++) out[d.ordinal()][i] = leaves.get(i).id();
        }
        return out;
    }

    // ---------------------------------------------------------------- клиенты

    /** Вид устройства по его ключу: компьютер — 45 %, телефон (сайт) — 33 %, приложение — 22 %. */
    static byte deviceKind(long device) {
        double u = (Rng.mix64(device ^ 0x6465766963654b4cL) >>> 11) * 0x1.0p-53;
        return u < 0.45 ? SiteInput.DEV_DESKTOP : u < 0.78 ? SiteInput.DEV_MOBILE : SiteInput.DEV_APP;
    }

    private static void clients(SiteInput in, ClientBase cb, long cutoff) {
        // вся смоделированная база (срез + горизонт): выбор клиентов «зарегистрирован раньше момента» — префикс по id,
        // от среза не зависит; строки выпуска ссылаются только на зарегистрированных до своих моментов (до среза)
        int n = cb.size();
        in.nClients = n;
        in.cKey = new long[n + 1];
        in.cCity = new int[n + 1];
        in.cRegAt = new long[n + 1];
        in.cFlags = new byte[n + 1];
        in.cVisits = new float[n + 1];
        in.cDept = new byte[n + 1];
        in.cDevFrom = new int[n + 2];
        long[] keys = new long[n * 2 + 16];
        byte[] kinds = new byte[keys.length];
        int nd = 0;
        Dept[] depts = Dept.values();
        for (int c = 1; c <= n; c++) {
            in.cKey[c] = c;
            in.cCity[c] = cb.cityId(c);
            in.cRegAt[c] = cb.registeredAt(c);
            byte f = 0;
            if (cb.analyticsConsent(c)) f |= SiteInput.C_ANALYTICS;
            if (cb.marketingConsent(c)) f |= SiteInput.C_MARKETING;
            in.cDevFrom[c] = nd;
            boolean app = false;
            for (long d : cb.devices(c)) {
                if (nd == keys.length) {
                    keys = Arrays.copyOf(keys, nd * 2);
                    kinds = Arrays.copyOf(kinds, nd * 2);
                }
                keys[nd] = d;
                kinds[nd] = deviceKind(d);
                app |= kinds[nd] == SiteInput.DEV_APP;
                nd++;
            }
            // push разрешены у 90 % владельцев приложения
            if (app && (Rng.mix64(c * 0x9E3779B97F4A7C15L + 77) >>> 11) * 0x1.0p-53 < 0.9) f |= SiteInput.C_PUSH;
            in.cFlags[c] = f;
            if (cb.buyer(c)) {
                int best = 0;
                float bw = -1;
                for (Dept d : depts) {
                    float w = cb.interest(c, d);
                    if (w > bw) {
                        bw = w;
                        best = d.ordinal();
                    }
                }
                in.cDept[c] = (byte) best;
                // визиты «посмотреть»: ≈ 0,6 в год на торги, в которых участвует, и немного у всех
                in.cVisits[c] = (float) Math.min(1.0, 0.0012 * cb.salesPerYear(c) + 0.0008);
            } else {
                in.cDept[c] = -1;
                in.cVisits[c] = 0.001f;
            }
        }
        in.cDevFrom[n + 1] = nd;
        in.devKey = Arrays.copyOf(keys, nd);
        in.devKind = Arrays.copyOf(kinds, nd);
    }

    // ---------------------------------------------------------------- торги и деньги из мира

    /**
     * Торги, лоты, ставки и источники уведомлений — из мира: план конца лота, участники лотов и максимумы с сайта
     * ({@code order_set}) — из записей мира торгов; если мир их не даёт — план конца по волне (5 лотов в 60 с, 1 200 за
     * вечер) и темпу зала (75 в час), участники — по ставкам до среза.
     */
    private static void auction(SiteInput in, GenContext ctx) {
        Calendar cal = ctx.get(AucKeys.CALENDAR);
        AuctionWorld aw = ctx.get(AucKeys.AUCTION);
        // источники уведомлений — первыми: сайт — последний генератор, после них уведомления, поручения, участники,
        // планы лотов и счета мира больше не нужны — мир их отпускает до остального входа (память full)
        sources(in, aw.notices(), ctx.has(AucKeys.MONEY) ? ctx.get(AucKeys.MONEY).events() : List.of());
        aw.generatorsDone();
        if (ctx.has(AucKeys.MONEY)) ctx.get(AucKeys.MONEY).generatorsDone();
        Intake intake = ctx.has(AucKeys.CATALOG) ? ctx.get(AucKeys.CATALOG) : null;
        List<Calendar.Sale> sales = cal.sales();
        int ns = 0;
        for (Calendar.Sale s : sales) ns = Math.max(ns, s.id());
        in.nSales = ns;
        in.sFormat = new byte[ns + 1];
        in.sDept = new byte[ns + 1];
        in.sPub = new long[ns + 1];
        in.sOpens = new long[ns + 1];
        in.sStart = new long[ns + 1];
        in.sEnd = new long[ns + 1];
        for (Calendar.Sale s : sales) {
            int id = s.id();
            in.sFormat[id] = s.format() == Format.TIMED ? SiteInput.F_TIMED : SiteInput.F_LIVE;
            in.sDept[id] = (byte) s.dept().ordinal();
            in.sPub[id] = s.publishedAt() < in.cutoff ? s.publishedAt() : 0;
            in.sOpens[id] = s.biddingOpensAt();
            in.sStart[id] = s.startsAt();
        }
        int nl = aw.lotCount();
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
        boolean[] open = new boolean[ns + 1];
        for (int l = 1; l <= nl; l++) {
            LotResult r = aw.lot(l);
            int s = r.saleId();
            in.lKey[l] = r.lotKey();
            in.lSale[l] = s;
            in.lNo[l] = Keys.lotNo(r.lotKey());
            in.lPub[l] = in.sPub[s];
            int k = in.lNo[l] - 1;
            in.lPlan[l] = in.sFormat[s] == SiteInput.F_TIMED
                    ? in.sStart[s] + (k / 1200) * 86_400L + (k % 1200) / 5 * 60L
                    : in.sStart[s] + k * 48L;
            in.lClosed[l] = r.closedAt();
            if (r.closedAt() == 0) open[s] = true;
            else in.sEnd[s] = Math.max(in.sEnd[s], r.closedAt());
            in.lWithdrawn[l] = r.withdrawnAt();
            in.lBuyer[l] = r.sold() && r.closedAt() != 0 ? r.buyerClientId() : 0;
            in.lPop[l] = interest(r.highKop());
            if (intake != null && r.itemId() > 0) {
                Intake.Item it = intake.item(r.itemId());
                in.lCat[l] = it.categoryId();
                in.lMaker[l] = it.makerId();
            }
        }
        for (int s = 1; s <= ns; s++) if (open[s]) in.sEnd[s] = 0;
        in.makerName = new String[0];

        // ставки
        AuctionWorld.BidLog bl = aw.bids();
        int nb = bl.size();
        in.nBids = nb;
        boolean seq = true;
        for (int i = 0; i < nb && seq; i++) seq = bl.id(i) == i + 1L;
        in.bId = seq ? null : new long[nb];
        in.bLot = new int[nb];
        in.bClient = new int[nb];
        in.bAt = new int[nb];
        in.bMs = new short[nb];
        in.bFlags = new short[nb];
        for (int i = 0; i < nb; i++) {
            if (!seq) in.bId[i] = bl.id(i);
            in.bLot[i] = bl.lotId(i);
            in.bClient[i] = bl.clientId(i);
            long ms = bl.atMs(i);
            in.bAt[i] = Math.toIntExact(Math.floorDiv(ms, 1000L));
            in.bMs[i] = (short) Math.floorMod(ms, 1000L);
            short f = 0;
            if (bl.accepted(i)) f |= SiteInput.B_ACCEPTED;
            if (bl.manualOnline(i)) f |= SiteInput.B_MANUAL | SiteInput.B_ONLINE;
            if (bl.clientId(i) == 0) f |= SiteInput.B_VENDOR;
            in.bFlags[i] = f;
        }
        participantsFromBids(in);
        in.nOrders = 0;
        in.oId = new int[0];
        in.oAt = new long[0];
        in.oLot = new int[0];
        in.oClient = new int[0];
        in.oVia = new byte[0];
    }

    /**
     * Интерес лота — ожидаемое число участников по верхней оценке (каталог, от среза не зависит): в среднем ≈ 3,2,
     * +1 на каждое удвоение верхней оценки выше 100 000 ₽. Ставки до среза для этого не годятся: у лота,
     * открытого на срезе, их число меняется при продлении, а с ним — веса просмотров и визиты до среза.
     */
    static float interest(long highKop) {
        double x = highKop / 100_000_00.0;
        return (float) (3.2 + (x > 1 ? StrictMath.log(x) / StrictMath.log(2) : 0));
    }

    /**
     * Участники лота по ставкам до среза (если мир не даёт смоделированных): первая ставка
     * и её канал — факты, одинаковые при любом срезе после неё.
     */
    private static void participantsFromBids(SiteInput in) {
        int nb = in.nBids;
        long[] key = new long[nb];
        int[] idx = new int[nb];
        int m = 0;
        for (int i = 0; i < nb; i++) {
            if (in.bClient[i] == 0) continue;
            key[m] = ((long) in.bLot[i] << 32) | in.bClient[i];
            idx[m++] = i;
        }
        Radix.sort(key, idx, m, 64);
        int np = 0;
        for (int j = 0; j < m; j++) if (j == 0 || key[j] != key[j - 1]) np++;
        in.nPart = np;
        in.pLot = new int[np];
        in.pClient = new int[np];
        in.pFirstAt = new long[np];
        in.pOnline = new boolean[np];
        np = -1;
        for (int j = 0; j < m; j++) {
            int i = idx[j];
            if (j == 0 || key[j] != key[j - 1]) {
                np++;
                in.pLot[np] = in.bLot[i];
                in.pClient[np] = in.bClient[i];
                in.pFirstAt[np] = in.bAt[i];
                in.pOnline[np] = (in.bFlags[i] & SiteInput.B_ONLINE) != 0;
            }
        }
    }

    /** Источники уведомлений: события торгов и денег; {@code event_key} разбирается в числа ({@code <вид>:<a>[:<b>]}). */
    private static void sources(SiteInput in, List<AuctionWorld.Notice> notices, List<MoneyWorld.Event> money) {
        if (merged(in, notices, money)) return;
        int n = 0;
        for (AuctionWorld.Notice x : notices) if (!"closing_soon".equals(x.kind())) n++;
        n += money.size();
        long[] at = new long[n], a = new long[n], b = new long[n];
        byte[] kind = new byte[n];
        int[] client = new int[n], sale = new int[n], lot = new int[n], inv = new int[n];
        int k = 0;
        for (AuctionWorld.Notice x : notices) {
            if ("closing_soon".equals(x.kind())) continue;
            at[k] = Math.floorDiv(x.atMs(), 1000L);
            kind[k] = kindCode(x.kind());
            client[k] = x.clientId();
            sale[k] = x.saleId();
            lot[k] = x.lotId();
            long[] ab = parseKey(x.eventKey(), x.kind());
            a[k] = ab[0];
            b[k] = ab[1];
            k++;
        }
        for (MoneyWorld.Event x : money) {
            at[k] = x.at();
            kind[k] = kindCode(x.kind());
            client[k] = x.clientId();
            sale[k] = x.saleId();
            inv[k] = x.invoiceId();
            long[] ab = parseKey(x.eventKey(), x.kind());
            a[k] = ab[0];
            b[k] = ab[1];
            k++;
        }
        long[] key = Arrays.copyOf(at, n);
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Radix.sort(key, idx, n, 40);
        in.nSrc = n;
        in.srcAt = new int[n];
        in.srcKind = new byte[n];
        in.srcClient = new int[n];
        in.srcSale = new int[n];
        in.srcLot = new int[n];
        in.srcInvoice = new int[n];
        in.srcKeyA = new int[n];
        in.srcKeyB = new int[n];
        for (int j = 0; j < n; j++) {
            int i = idx[j];
            in.srcAt[j] = Math.toIntExact(key[j]);
            in.srcKind[j] = kind[i];
            in.srcClient[j] = client[i];
            in.srcSale[j] = sale[i];
            in.srcLot[j] = lot[i];
            in.srcInvoice[j] = inv[i];
            in.srcKeyA[j] = Math.toIntExact(a[i]);
            in.srcKeyB[j] = Math.toIntExact(b[i]);
        }
    }

    /** Момент источника годится для слияния: порядок поразрядной сортировки (40 бит без знака) — числовой. */
    private static final long MERGE_MAX_AT = 1L << 40;

    /**
     * Источники слиянием двух потоков, уже упорядоченных по моменту (мир торгов отдаёт уведомления по мс, деньги — по
     * моменту): тот же порядок, что у устойчивой сортировки общего списка «сначала торги, потом деньги» по моменту
     * (при равном моменте — торги, внутри потока — его порядок), но без промежуточных массивов на всё (память
     * {@code full}). Поток не по порядку — {@code false}: тогда общая сортировка.
     */
    private static boolean merged(SiteInput in, List<AuctionWorld.Notice> notices, List<MoneyWorld.Event> money) {
        int na = notices.size(), nm = money.size(), cap = na + nm;
        int[] at = new int[cap], a = new int[cap], b = new int[cap];
        byte[] kind = new byte[cap];
        int[] client = new int[cap], sale = new int[cap], lot = new int[cap], inv = new int[cap];
        int i = 0, j = 0, k = 0;
        AuctionWorld.Notice x = null;
        long xAt = 0, prevX = Long.MIN_VALUE, prevY = Long.MIN_VALUE;
        MoneyWorld.Event y = null;
        long yAt = 0;
        while (true) {
            while (x == null && i < na) {
                AuctionWorld.Notice c = notices.get(i++);
                if ("closing_soon".equals(c.kind())) continue;
                x = c;
                xAt = Math.floorDiv(c.atMs(), 1000L);
                if (xAt < prevX || xAt < 0 || xAt >= MERGE_MAX_AT) return false;
                prevX = xAt;
            }
            if (y == null && j < nm) {
                y = money.get(j++);
                yAt = y.at();
                if (yAt < prevY || yAt < 0 || yAt >= MERGE_MAX_AT) return false;
                prevY = yAt;
            }
            if (x == null && y == null) break;
            if (x != null && (y == null || xAt <= yAt)) {
                at[k] = Math.toIntExact(xAt);
                kind[k] = kindCode(x.kind());
                client[k] = x.clientId();
                sale[k] = x.saleId();
                lot[k] = x.lotId();
                long[] ab = parseKey(x.eventKey(), x.kind());
                a[k] = Math.toIntExact(ab[0]);
                b[k] = Math.toIntExact(ab[1]);
                x = null;
            } else {
                at[k] = Math.toIntExact(yAt);
                kind[k] = kindCode(y.kind());
                client[k] = y.clientId();
                sale[k] = y.saleId();
                inv[k] = y.invoiceId();
                long[] ab = parseKey(y.eventKey(), y.kind());
                a[k] = Math.toIntExact(ab[0]);
                b[k] = Math.toIntExact(ab[1]);
                y = null;
            }
            k++;
        }
        if (k < cap) {
            at = Arrays.copyOf(at, k);
            a = Arrays.copyOf(a, k);
            b = Arrays.copyOf(b, k);
            kind = Arrays.copyOf(kind, k);
            client = Arrays.copyOf(client, k);
            sale = Arrays.copyOf(sale, k);
            lot = Arrays.copyOf(lot, k);
            inv = Arrays.copyOf(inv, k);
        }
        in.nSrc = k;
        in.srcAt = at;
        in.srcKind = kind;
        in.srcClient = client;
        in.srcSale = sale;
        in.srcLot = lot;
        in.srcInvoice = inv;
        in.srcKeyA = a;
        in.srcKeyB = b;
        return true;
    }

    static byte kindCode(String kind) {
        for (int i = 0; i < SiteTables.NOTIFY_KINDS.length; i++) if (SiteTables.NOTIFY_KINDS[i].equals(kind)) return (byte) i;
        throw new IllegalArgumentException("неизвестный вид уведомления: " + kind);
    }

    /** {@code <вид>:<a>[:<b>]} → (a, b); b = −1 — нет. Другой формат — ошибка (формат — по уговору). */
    static long[] parseKey(String key, String kind) {
        String[] p = key.split(":");
        if (p.length < 2 || p.length > 3 || !p[0].equals(kind)) {
            throw new IllegalArgumentException("ключ события не по формату <вид>:<число>[:<число>]: " + key);
        }
        try {
            return new long[]{Long.parseLong(p[1]), p.length == 3 ? Long.parseLong(p[2]) : -1};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ключ события не по формату <вид>:<число>[:<число>]: " + key, e);
        }
    }
}
