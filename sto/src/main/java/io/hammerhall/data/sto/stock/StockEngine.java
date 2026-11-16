package io.hammerhall.data.sto.stock;

import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Ids;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.core.time.Period;
import io.hammerhall.data.core.time.RuCalendar;
import io.hammerhall.data.sto.StoKeys;
import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.catalog.PriceBook;
import io.hammerhall.data.sto.geo.Geo;
import io.hammerhall.data.sto.stock.StockLedger.Kind;
import io.hammerhall.data.sto.stock.StockLedger.Ref;
import io.hammerhall.data.sto.supply.Suppliers;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * Движок остатков: склады магазина сутки за сутками — заказы поставщикам и приёмка партий, размещение по ячейкам,
 * переброски хаб → РЦ → склады, резерв и отбор под строки заказов (FIFO по партиям), возвраты, инвентаризации. Пишет
 * {@code stock_movements} (сразу), {@code purchase_orders} и {@code batches} (в {@link #finish}).
 *
 * <h2>Как вести (модуль заказов или {@link DemandDriver})</h2>
 * <pre>{@code
 * StockEngine e = StockEngine.open(ctx, forecast, FboListings.NONE);   // в generate модуля, владеющего тремя таблицами
 * e.setListener(listener);                                             // «строка дождалась товара»
 * for (LocalDate d = e.firstDay(); !d.isAfter(e.lastDay()); d = d.plusDays(1)) {
 *     e.beginDay(d);
 *     // события суток по времени: route1P/routeFbo → reserve → (ждать готовности) → pick; cancel; customerReturn
 *     e.endDay(d);
 * }
 * e.finish();                                                          // заказы поставщикам и партии — в таблицы
 * }</pre>
 *
 * <ul>
 *   <li><b>Время.</b> Сутки — UTC, как окно периода. Все вызовы суток — с моментами {@code at} из
 *       {@code [начало, конец)} суток и по неубыванию: движок до вызова сам проигрывает свои события до {@code at}
 *       (приёмки, переброски, пересчёты), поэтому журнал движений идёт строго по времени, а id растут вместе с ним.
 *       Момент раньше уже прожитого — {@link IllegalStateException}. Факты — только до «сейчас» выгрузки:
 *       {@link #lastDay()} — последние сутки до него (или до конца окна, если оно раньше).</li>
 *   <li><b>Выбор склада 1P</b> — {@link #route1P}: ближайший к покупателю открытый городской склад → РЦ его округа
 *       → хаб, первый, где свободно ({@code остаток − резерв}) не меньше {@code qty} и нет очереди ждущих. Иначе —
 *       нехватка: склад цепочки, куда товар придёт раньше (по ожидаемым датам заказов поставщикам и переброскам), и
 *       когда; нет ничего в пути — хаб и оценка по ближайшему заказу поставщику; товар не продаётся — {@code readyAt
 *       = −1}. Решение «ждать или отменить» — за вызывающим.</li>
 *   <li><b>FBO</b> — {@link #routeFbo}: склад, где у продавца свободен товар, ближайший к покупателю; партии продавца
 *       — с {@code seller_id}, пополняет их сам движок по {@link FboListings}. <b>FBS</b> — движок не трогать.</li>
 *   <li><b>Резерв</b> — {@link #reserve}: товара хватает — резерв сразу ({@link #isReady}); не хватает — строка в
 *       очередь склада (FIFO по времени резерва), движок закажет товар и, когда он придёт, зарезервирует и вызовет
 *       {@link Listener#ready}. Отмена — {@link #cancel} (резерв или место в очереди).</li>
 *   <li><b>Отбор</b> — {@link #pick}: только готовый резерв; расход партий FIFO (старшая по приёмке — первой), из
 *       ячеек ({@code pick}); если товар ещё в зоне приёмки — прямо оттуда ({@code ship}, ячейка NULL: так уходят
 *       предзаказы новинок). Возвращает партию и себестоимость FIFO.</li>
 *   <li><b>Возврат</b> — {@link #customerReturn}: годный — в ячейку хранения, снова в продаже; брак — в зону
 *       возвратов и через сутки списание ({@code writeoff}).</li>
 *   <li><b>Спрос для прогноза.</b> {@link #route1P} учитывает запрос (штуки) в факте спроса — звать один раз на строку,
 *       даже если строка не будет зарезервирована; по факту движок раз в неделю поправляет прогноз. Запрос «зажигает»
 *       позицию ближайшего склада и его РЦ: товар из ассортимента склада, которого там не было, с этой минуты
 *       пополняется.</li>
 *   <li><b>Что ещё.</b> {@link #available} — свободно на складе; резерв не привязан к партии (партия выбирается при
 *       отборе); ждущие строки получают пришедший товар раньше перебросок; в {@code ref_id} движений по заказу —
 *       {@code orderId} из {@link #reserve}, по возврату — номер, переданный в {@link #customerReturn}.</li>
 * </ul>
 * <b>Гарантии:</b> остаток по (склад, ячейка, товар, партия) и по (склад, товар, партия) не уходит в минус — кроме
 * заложенных инцидентов инвентаризации (код {@code negative_stock_after_inventory}); отбор и переброска — FIFO;
 * свободный остаток не меньше резерва; партии в пути движений не имеют. Слушатель вызывается изнутри движка и не
 * должен вызывать движок — только запомнить «готово» и запланировать отбор не раньше {@link #clock()}.
 */
public final class StockEngine {

    /** Строка дождалась товара: резерв готов к отбору с момента {@code at}. */
    public interface Listener {
        void ready(long reservationId, long at);
    }

    /** Ответ выбора склада: склад, с какого момента можно отбирать, есть ли сейчас. */
    public record Offer(int warehouseId, long readyAt, boolean inStock) {
    }

    /** Итог отбора: склад, первая (старшая) партия, штук, себестоимость FIFO, копеек. */
    public record Picked(int warehouseId, int batchId, int qty, long costKop) {
    }

    // ---------------------------------------------------------------- параметры политики

    /**
     * В ассортименте городского склада: ожидаемый спрос на складе за 28 дней в «полных» штуках (спрос профиля /
     * масштаб) не ниже. Ассортимент от профиля не зависит — те же товары на тех же складах, меньше только глубина
     * запаса (не меньше штуки): так в малых профилях сохраняются география отгрузок и переброски.
     */
    static final double CITY_IN = 2.0;
    static final double DC_IN = 3.0;
    /** Хаб держит товар, если за год в «полных» штуках ждут хотя бы 0,8 штуки (иначе — под заказ у поставщика). */
    static final double HUB_IN = 0.8 * 28 / 365.0;
    static final double HYSTERESIS = 0.4;
    /** Позиция склада или РЦ получает запас сразу, если за 13 недель на нём ждут хотя бы столько штук профиля. */
    static final double ARM_UNITS = 0.5;
    static final double Z_CITY = 1.0, Z_DC = 1.3, Z_HUB = 1.5;
    /** Цикл пополнения, дней спроса сверх точки заказа: склад, РЦ, хаб. */
    static final int CYCLE_CITY = 21, CYCLE_DC = 28, CYCLE_HUB = 42;
    /** Доля товаров, которые РЦ вне центра берёт у поставщика своего округа (если тот возит бренд), а не с хаба. */
    static final double DIRECT_SHARE = 0.35;
    /** Ходовая новинка: на пике в «полных» штуках — не меньше в сутки. */
    static final double HOT_RATE = 2.5;
    static final double INV_DISCREPANCY = 0.012;
    /** Доля расхождений «последняя штука в ячейке», где недостачу провели дважды (заложенный инцидент). */
    static final double INV_PLANT = 0.15;
    static final long PUTAWAY_DELAY = 3 * 3600L;
    static final int HORIZON_WEEKS = 13;
    static final String PLANT_CELL = "negative_stock_after_inventory";
    static final String PLANT_TOTAL = "negative_stock_after_inventory_total";

    private static final byte F_ASSORT = 1, F_DIRECT = 2, F_MANAGED = 4, F_LISTED = 8, F_ORDERED = 16, F_ARMED = 32;
    private static final byte R_FREE = 0, R_ACTIVE = 1, R_BACK = 2, R_CANCELLED = 3;
    private static final int EV_REVIEWS = 1, EV_DISPATCH = 2, EV_PO_ARRIVAL = 3, EV_PO_CANCEL = 4, EV_PUTAWAY = 5,
            EV_TRANSFER_ARRIVAL = 6, EV_INVENTORY = 7, EV_WRITEOFF = 8, EV_FBO = 9, EV_FBO_ARRIVAL = 10;
    static final String[] PURPOSE = {"opening", "launch", "replenishment"};
    static final byte P_OPENING = 0, P_LAUNCH = 1, P_REPL = 2;

    // ---------------------------------------------------------------- мир

    private final GenContext ctx;
    private final Catalog cat;
    private final Suppliers sup;
    private final RuCalendar cal;
    private final Period period;
    final Network net;
    final Cells cells;
    private final CostModel cost;
    private final StockLedger ledger;
    final StockAudit audit;
    private final Forecast fc;
    private final FboListings fbo;
    private final double scale;
    final int P;
    final int N;
    private Listener listener = (r, t) -> {
    };
    private final RandomStreams.Stream stPo, stOpen, stInv, stSup, stLaunch, stFbo;
    private final Ids.Sequence movementIds, poIds, batchIds;

    final int[] vMl, wG;
    private final int[] casePack;
    private final RuCalendar.Season[] season;
    private final int[] primary, secondary;
    private final int[][] directSup;
    private final boolean[] hot;
    private final double[] corr, obsWeek, fcWeek, corrNum, corrDen;
    private final List<Catalog.Product> byLaunch;
    private int launchPtr;
    private final Map<Long, int[]> reviewLists = new TreeMap<>();
    private final LocalDate calFrom;
    private final double[][] calSum;

    // ---------------------------------------------------------------- состояние: слоты (узел, товар[, продавец])

    private int slots;
    int[] onHand, reserved, backordered, inbound, rop, upTo, posHead, inHead, boHead, boTail;
    byte[] flags;
    private int[] fboNode = new int[0], fboProduct = new int[0], fboSeller = new int[0];
    private double[] fboRate = new double[0];
    private int[] fboLead = new int[0];
    private long[] fboCost = new long[0];
    private final LongIntMap fboSlots = new LongIntMap(1024);
    /** (продавец, товар) → маска узлов, где у продавца есть позиция (только поиск по ключу). */
    private final java.util.HashMap<Long, Long> fboNodes = new java.util.HashMap<>();
    private final int[][] managed;
    private final int[] managedN;
    private final int[][] listed;
    private final int[] listedN;

    // позиции: (слот, ячейка, партия, штук), список слота — по возрасту партии
    int[] posSlot = new int[1 << 16], posCell = new int[1 << 16], posBatch = new int[1 << 16],
            posQty = new int[1 << 16], posNext = new int[1 << 16];
    private int posTop, posFree = -1, posLive;

    // поступления в пути
    private int[] inSlot = new int[1 << 12], inQty = new int[1 << 12], inNext = new int[1 << 12];
    private long[] inEta = new long[1 << 12];
    private int inTop, inFree = -1;

    // резервы
    private int[] rSlot = new int[1 << 12], rQty = new int[1 << 12], rNext = new int[1 << 12], rLine = new int[1 << 12];
    private long[] rOrder = new long[1 << 12], rReady = new long[1 << 12];
    private byte[] rState = new byte[1 << 12];
    private int rTop, rFree = -1;

    // партии (индекс = id)
    int[] bPo = new int[1 << 14], bSeller = new int[1 << 14], bProduct = new int[1 << 14], bWh = new int[1 << 14],
            bQty = new int[1 << 14], bOrdered = new int[1 << 14];
    long[] bCost = new long[1 << 14], bRecv = new long[1 << 14];
    int batchTop;

    // заказы поставщикам (индекс = id) и их строки
    int[] poSup = new int[1 << 12], poWh = new int[1 << 12], poLine0 = new int[1 << 12], poLines = new int[1 << 12];
    byte[] poPurpose = new byte[1 << 12];
    boolean[] poCancelFate = new boolean[1 << 12];
    long[] poOrdered = new long[1 << 12], poExpected = new long[1 << 12], poConfirm = new long[1 << 12],
            poShipped = new long[1 << 12], poArrival = new long[1 << 12], poCancel = new long[1 << 12];
    int poTop;
    private int[] lnProduct = new int[1 << 14], lnOrdered = new int[1 << 14], lnDelivered = new int[1 << 14],
            lnBatch = new int[1 << 14], lnIn = new int[1 << 14], lnSlot = new int[1 << 14];
    private int lnTop;

    // события
    private static final class Ev {
        final long t;
        final long seq;
        final int type, a, b;
        final Object o;

        Ev(long t, long seq, int type, int a, int b, Object o) {
            this.t = t;
            this.seq = seq;
            this.type = type;
            this.a = a;
            this.b = b;
            this.o = o;
        }
    }

    private final PriorityQueue<Ev> events = new PriorityQueue<>((x, y) -> x.t != y.t ? Long.compare(x.t, y.t)
            : Long.compare(x.seq, y.seq));
    private long evSeq;

    private static final class Transfer {
        final long id;
        final int src, dst;
        final long outAt;
        int n;
        int[] slot = new int[16], batch = new int[16], qty = new int[16], in = new int[16];

        Transfer(long id, int src, int dst, long outAt) {
            this.id = id;
            this.src = src;
            this.dst = dst;
            this.outAt = outAt;
        }

        void add(int s, int b, int q, int inRec) {
            if (n == slot.length) {
                slot = Arrays.copyOf(slot, n * 2);
                batch = Arrays.copyOf(batch, n * 2);
                qty = Arrays.copyOf(qty, n * 2);
                in = Arrays.copyOf(in, n * 2);
            }
            slot[n] = s;
            batch[n] = b;
            qty[n] = q;
            in[n] = inRec;
            n++;
        }
    }

    private record Putaway(Ref ref, long refId, int[] slots, int[] batches) {
    }

    private record Quarantine(int node, int cell, int product, int batch, int qty, long returnId) {
    }

    private record Plant(int slot, int cell, int batch, int qty) {
    }

    private final List<List<Plant>> planted = new ArrayList<>();
    private long transferSeq, inventorySeq;

    // время
    private LocalDate day;
    private long dayStart, dayEnd, clock = Long.MIN_VALUE;
    private final LocalDate first, last;
    private final long simEnd;
    private final Cells.Placement pl = new Cells.Placement();
    private final int[] needSum, availStart;

    // учёт для журнала
    long stFill, stDepleted, stTopup;
    long stOpening, stLaunchPo, stReplPo, stTransfers, stTransferLines, stPicks, stShips, stBackorders, stLostEta,
            stUnplaced, stInventoryRows, stPlanted, stObsolete, stReturns, stDefects;

    // ---------------------------------------------------------------- создание

    /** Движок с приёмниками выпуска (три таблицы должен объявить модуль, из {@code generate} которого вызов). */
    public static StockEngine open(GenContext ctx, Forecast forecast, FboListings fbo) {
        return new StockEngine(ctx, StockLedger.open(ctx), forecast, fbo);
    }

    public StockEngine(GenContext ctx, StockLedger ledger, Forecast forecast, FboListings fbo) {
        this.ctx = ctx;
        Geo geo = ctx.get(StoKeys.GEO);
        this.cat = ctx.get(StoKeys.CATALOG);
        PriceBook prices = ctx.get(StoKeys.PRICES);
        this.sup = ctx.get(StoKeys.SUPPLIERS);
        this.cal = ctx.calendar();
        this.period = ctx.period();
        this.ledger = ledger;
        this.fc = forecast;
        this.fbo = fbo;
        this.scale = forecast.scale();
        this.net = new Network(geo);
        this.cells = new Cells(geo, net);
        this.cost = new CostModel(cat, prices, sup, ctx.stream("stock.cost"), period);
        this.stPo = ctx.stream("stock.po");
        this.stOpen = ctx.stream("stock.opening");
        this.stInv = ctx.stream("stock.inventory");
        this.stSup = ctx.stream("stock.suppliers");
        this.stLaunch = ctx.stream("stock.launch");
        this.stFbo = ctx.stream("stock.fbo");
        this.movementIds = ctx.ids().of("stock_movements");
        this.poIds = ctx.ids().of("purchase_orders");
        this.batchIds = ctx.ids().of("batches");
        N = net.n;
        int maxPid = 0;
        for (Catalog.Product p : cat.products()) maxPid = Math.max(maxPid, p.id);
        P = maxPid + 1;
        slots = N * P;
        onHand = new int[slots];
        reserved = new int[slots];
        backordered = new int[slots];
        inbound = new int[slots];
        rop = new int[slots];
        upTo = new int[slots];
        posHead = filled(slots);
        inHead = filled(slots);
        boHead = filled(slots);
        boTail = filled(slots);
        flags = new byte[slots];
        managed = new int[N][];
        managedN = new int[N];
        listed = new int[N][];
        listedN = new int[N];
        for (int i = 0; i < N; i++) {
            managed[i] = new int[256];
            listed[i] = new int[256];
            planted.add(new ArrayList<>());
        }
        needSum = new int[P];
        for (int c : net.cityNodes) cityMask |= 1L << c;
        availStart = new int[P];

        vMl = new int[P];
        wG = new int[P];
        casePack = new int[P];
        season = new RuCalendar.Season[P];
        hot = new boolean[P];
        corr = new double[P];
        obsWeek = new double[P];
        fcWeek = new double[P];
        corrNum = new double[P];
        corrDen = new double[P];
        Arrays.fill(corr, 1.0);
        smpRaw = new double[P * HORIZON_WEEKS];
        smpDay = new long[P];
        Arrays.fill(smpDay, Long.MIN_VALUE);
        retired = new boolean[P];
        nodeMask = new long[P];
        launchEp = new long[P];
        discEp = new long[P];
        for (Catalog.Product p : cat.products()) {
            launchEp[p.id] = p.launchedOn.toEpochDay();
            discEp[p.id] = p.discontinuedPlan == null ? Long.MAX_VALUE : p.discontinuedPlan.toEpochDay();
        }
        for (Catalog.Product p : cat.products()) {
            long v = (long) p.lengthMm * p.widthMm * p.heightMm / 1000;
            vMl[p.id] = (int) Math.max(1, v);
            wG[p.id] = Math.max(1, p.weightG);
            long rub = p.launchPriceKop / 100;
            casePack[p.id] = rub >= 15_000 || v > 30_000 || p.weightG > 8_000 ? 1 : rub >= 3_000 ? 2 : rub >= 800 ? 5 : 10;
            season[p.id] = forecast.season(p);
        }
        // календарь: префиксные суммы спроса по сезонам на всё окно и 120 дней вперёд
        calFrom = period.from();
        int days = (int) (period.to().toEpochDay() - calFrom.toEpochDay()) + 130;
        RuCalendar.Season[] ss = RuCalendar.Season.values();
        calSum = new double[ss.length][days + 1];
        for (RuCalendar.Season s : ss) {
            double acc = 0;
            for (int k = 0; k < days; k++) {
                calSum[s.ordinal()][k] = acc;
                acc += cal.demand(calFrom.plusDays(k), s);
            }
            calSum[s.ordinal()][days] = acc;
        }
        // поставщики товара: основной и запасной (для хаба), региональный — для РЦ вне центра
        primary = new int[P];
        secondary = new int[P];
        directSup = new int[N][];
        Map<String, List<Suppliers.Supplier>> regional = new TreeMap<>();
        for (Suppliers.Supplier s : sup.all()) {
            if (!s.kind().equals("distributor") && !s.kind().equals("manufacturer")) continue;
            regional.computeIfAbsent(geo.city(s.cityId()).district(), k -> new ArrayList<>()).add(s);
        }
        Map<Long, List<Integer>> lists = new TreeMap<>();
        for (Catalog.Product p : cat.products()) {
            if (p.assortment == Catalog.Assortment.ONLY_3P) continue;
            List<Suppliers.Supplier> of = sup.ofBrand(p.brand.id());
            if (of.isEmpty()) continue;
            Rng r = stSup.at(p.id);
            double[] w = new double[of.size()];
            for (int i = 0; i < w.length; i++) {
                w[i] = switch (of.get(i).kind()) {
                    case "brand_office" -> 4;
                    case "manufacturer" -> 3;
                    case "distributor" -> 2;
                    default -> 1.5;
                };
            }
            int a = r.weighted(w);
            primary[p.id] = of.get(a).id();
            if (of.size() > 1 && r.chance(0.5)) {
                w[a] = 0;
                secondary[p.id] = of.get(r.weighted(w)).id();
            }
            lists.computeIfAbsent(key(primary[p.id], net.hub), k -> new ArrayList<>()).add(p.id);
            if (secondary[p.id] > 0) lists.computeIfAbsent(key(secondary[p.id], net.hub), k -> new ArrayList<>()).add(p.id);
        }
        for (int node = 0; node < N; node++) {
            if (net.kind[node] != Geo.Kind.DC || net.district[node].equals("central")) continue;
            directSup[node] = new int[P];
            List<Suppliers.Supplier> reg = regional.getOrDefault(net.district[node], List.of());
            if (reg.isEmpty()) continue;
            for (Catalog.Product p : cat.products()) {
                if (p.assortment == Catalog.Assortment.ONLY_3P) continue;
                List<Suppliers.Supplier> can = new ArrayList<>();
                for (Suppliers.Supplier s : reg) if (s.brandIds().contains(p.brand.id())) can.add(s);
                if (can.isEmpty()) continue;
                Rng r = stSup.at(p.id, node);
                if (!r.chance(DIRECT_SHARE)) continue;
                int s = can.get(r.nextInt(can.size())).id();
                directSup[node][p.id] = s;
                lists.computeIfAbsent(key(s, node), k -> new ArrayList<>()).add(p.id);
            }
        }
        for (Map.Entry<Long, List<Integer>> e : lists.entrySet()) {
            reviewLists.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
            int onSale = 0;
            for (int pid : e.getValue()) if (cat.product(pid).aliveOn(period.from())) onSale++;
            if (onSale >= 300) bigSupplier.add(e.getKey());
        }
        byLaunch = new ArrayList<>(cat.products());
        byLaunch.sort((x, y) -> x.launchedOn.compareTo(y.launchedOn) != 0 ? x.launchedOn.compareTo(y.launchedOn)
                : Integer.compare(x.id, y.id));
        first = period.from();
        LocalDate lastFact = Period.localDate(period.nowEpoch() - 1, ZoneOffset.UTC);
        last = lastFact.isBefore(period.to()) ? lastFact : period.to();
        simEnd = last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        this.audit = new StockAudit(this, geo, cat, ctx.planted());
    }

    private static int[] filled(int n) {
        int[] a = new int[n];
        Arrays.fill(a, -1);
        return a;
    }

    private static long key(int supplier, int node) {
        return ((long) supplier << 8) | node;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public LocalDate firstDay() {
        return first;
    }

    /** Последние сутки фактов: до «сейчас» выгрузки или до конца окна. */
    public LocalDate lastDay() {
        return last;
    }

    /** Текущий момент движка (секунды Unix): все вызовы — не раньше. */
    public long clock() {
        return clock;
    }

    public Network network() {
        return net;
    }

    // ---------------------------------------------------------------- сутки

    public void beginDay(LocalDate d) {
        if (day == null ? !d.equals(first) : !d.equals(day.plusDays(1))) {
            throw new IllegalStateException("сутки по порядку: ждали " + (day == null ? first : day.plusDays(1)) + ", а не " + d);
        }
        if (d.isAfter(last)) throw new IllegalStateException(d + " — после последних суток фактов " + last);
        day = d;
        dayStart = d.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        dayEnd = dayStart + 86_400;
        if (clock < dayStart) clock = dayStart;
        for (int i = 0; i < N; i++) openNow[i] = net.open(i, d);
        List<Catalog.Product> launched = new ArrayList<>();
        while (launchPtr < byLaunch.size() && !byLaunch.get(launchPtr).launchedOn.isAfter(d)) {
            Catalog.Product p = byLaunch.get(launchPtr++);
            launched.add(p);
            // ходовая новинка — запуск внутри периода и высокий спрос на пике (решается один раз, в день запуска)
            hot[p.id] = p.launchedOn.isAfter(first) && p.assortment != Catalog.Assortment.ONLY_3P
                    && fc.baseRate(p, p.launchedOn.plusDays(40)) / scale >= HOT_RATE;
            if (hot[p.id]) pendingHot.add(p);
        }
        if (d.equals(first)) {
            recompute(d, true);
            opening(d);
        } else {
            boolean netChange = false;
            for (int i = 0; i < N; i++) if (net.opened[i].equals(d)) netChange = true;
            if (d.getDayOfWeek() == DayOfWeek.MONDAY || netChange) {
                recompute(d, false);
            } else if (!launched.isEmpty()) {
                double[] sh = net.shares(d);
                for (Catalog.Product p : launched) if (p.assortment != Catalog.Assortment.ONLY_3P) product(p, d, sh);
            }
        }
        // распорядок суток
        if (cal.isWorking(d)) schedule(dayStart + 7 * 3600L, EV_REVIEWS, 0, 0, null);
        schedule(dayStart + 9 * 3600L, EV_FBO, 0, 0, null);
        for (int node = 0; node < N; node++) {
            if (!net.open(node, d) || net.children[node].length == 0) continue;
            long t = node == net.hub ? dayStart + 15 * 3600L : inDay(local(d, node, 19));
            schedule(t, EV_DISPATCH, node, 0, null);
        }
        for (int node = 0; node < N; node++) {
            if (!net.open(node, d)) continue;
            int filter = countFilter(node, d);
            if (filter == Integer.MIN_VALUE) continue;
            schedule(inDay(local(d, node, 2)), EV_INVENTORY, node, filter, null);
        }
    }

    /** Момент местного времени, перенесённый в текущие сутки UTC (± сутки). */
    private long inDay(long t) {
        if (t < dayStart) t += 86_400;
        if (t >= dayEnd) t -= 86_400;
        return t;
    }

    public void endDay(LocalDate d) {
        if (!d.equals(day)) throw new IllegalStateException("закрываем " + d + ", а идут " + day);
        advanceTo(dayEnd - 1);
    }

    /** Момент местного времени склада ({@code hour}:00) в сутках {@code d} по UTC. */
    private long local(LocalDate d, int node, int hour) {
        return Period.epochOf(LocalDateTime.of(d, java.time.LocalTime.of(hour, 0)), net.zone[node]);
    }

    /** Пересчёт: городские склады — раз в квартал, РЦ — в марте и сентябре, хаб — ежемесячно шестая часть ячеек. */
    private int countFilter(int node, LocalDate d) {
        int m = d.getMonthValue(), dom = d.getDayOfMonth(), wh = net.whId[node];
        return switch (net.kind[node]) {
            case WAREHOUSE -> (m == 1 || m == 4 || m == 7 || m == 10) && dom == 3 + (wh * 7) % 20 ? -1 : Integer.MIN_VALUE;
            case DC -> (m == 3 || m == 9) && dom == 4 + (wh * 5) % 18 ? -1 : Integer.MIN_VALUE;
            case HUB -> dom == 12 ? m % 6 : Integer.MIN_VALUE;
            default -> Integer.MIN_VALUE;
        };
    }

    private void schedule(long t, int type, int a, int b, Object o) {
        events.add(new Ev(t, evSeq++, type, a, b, o));
    }

    /** Прожить свои события до момента {@code at} включительно. */
    private void advanceTo(long at) {
        if (day == null) throw new IllegalStateException("сутки не начаты");
        if (at < clock) throw new IllegalStateException("момент " + at + " раньше уже прожитого " + clock);
        if (at >= dayEnd || at < dayStart) throw new IllegalStateException("момент " + at + " вне суток " + day);
        while (!events.isEmpty() && events.peek().t <= at) {
            Ev e = events.poll();
            if (e.t > clock) clock = e.t;
            handle(e);
        }
        clock = at;
    }

    private void handle(Ev e) {
        switch (e.type) {
            case EV_REVIEWS -> reviews();
            case EV_DISPATCH -> dispatch(e.a);
            case EV_PO_ARRIVAL -> poArrival(e.a);
            case EV_PO_CANCEL -> poCancel(e.a);
            case EV_PUTAWAY -> putaway(e.a, (Putaway) e.o);
            case EV_TRANSFER_ARRIVAL -> transferArrival((Transfer) e.o);
            case EV_INVENTORY -> inventory(e.a, e.b);
            case EV_WRITEOFF -> writeoff((Quarantine) e.o);
            case EV_FBO -> fboCheck();
            case EV_FBO_ARRIVAL -> fboArrival(e.a);
            default -> throw new IllegalStateException("событие " + e.type);
        }
    }

    // ---------------------------------------------------------------- слоты

    int slot(int node, int pid) {
        return node * P + pid;
    }

    int slotNode(int s) {
        return s < N * P ? s / P : fboNode[s - N * P];
    }

    int slotProduct(int s) {
        return s < N * P ? s % P : fboProduct[s - N * P];
    }

    int slotSeller(int s) {
        return s < N * P ? 0 : fboSeller[s - N * P];
    }

    private int free(int s) {
        return onHand[s] - reserved[s];
    }

    private int ip(int s) {
        return onHand[s] + inbound[s] - reserved[s] - backordered[s];
    }

    private static long fboKey(int node, int pid, int seller) {
        return ((long) node << 50) | ((long) seller << 24) | pid;
    }

    /** Слот товара продавца на складе (создаётся при первом обращении). */
    private int fboSlot(int node, int pid, int seller) {
        long k = fboKey(node, pid, seller);
        int s = fboSlots.get(k, -1);
        if (s >= 0) return s;
        s = slots++;
        if (s >= onHand.length) growSlots(Math.max(onHand.length + 1024, (int) (onHand.length * 1.1)));
        int i = s - N * P;
        if (i >= fboNode.length) {
            int n = Math.max(1024, fboNode.length * 2);
            fboNode = Arrays.copyOf(fboNode, n);
            fboProduct = Arrays.copyOf(fboProduct, n);
            fboSeller = Arrays.copyOf(fboSeller, n);
            fboRate = Arrays.copyOf(fboRate, n);
            fboLead = Arrays.copyOf(fboLead, n);
            fboCost = Arrays.copyOf(fboCost, n);
        }
        fboNode[i] = node;
        fboProduct[i] = pid;
        fboSeller[i] = seller;
        fboSlots.put(k, s);
        fboNodes.merge(((long) seller << 24) | pid, 1L << node, (x, y) -> x | y);
        return s;
    }

    private void growSlots(int n) {
        int old = onHand.length;
        onHand = Arrays.copyOf(onHand, n);
        reserved = Arrays.copyOf(reserved, n);
        backordered = Arrays.copyOf(backordered, n);
        inbound = Arrays.copyOf(inbound, n);
        rop = Arrays.copyOf(rop, n);
        upTo = Arrays.copyOf(upTo, n);
        posHead = Arrays.copyOf(posHead, n);
        inHead = Arrays.copyOf(inHead, n);
        boHead = Arrays.copyOf(boHead, n);
        boTail = Arrays.copyOf(boTail, n);
        flags = Arrays.copyOf(flags, n);
        for (int i = old; i < n; i++) {
            posHead[i] = inHead[i] = boHead[i] = boTail[i] = -1;
        }
    }

    private void markManaged(int s) {
        if (s >= N * P || (flags[s] & F_MANAGED) != 0) return;
        flags[s] |= F_MANAGED;
        int node = s / P;
        if (managedN[node] == managed[node].length) managed[node] = Arrays.copyOf(managed[node], managedN[node] * 2);
        managed[node][managedN[node]++] = s;
    }

    private void markListed(int s) {
        if ((flags[s] & F_LISTED) != 0) return;
        flags[s] |= F_LISTED;
        int node = slotNode(s);
        if (listedN[node] == listed[node].length) listed[node] = Arrays.copyOf(listed[node], listedN[node] * 2);
        listed[node][listedN[node]++] = s;
    }

    // ---------------------------------------------------------------- позиции

    private int newPos() {
        int p;
        if (posFree >= 0) {
            p = posFree;
            posFree = posNext[p];
        } else {
            p = posTop++;
            if (p == posSlot.length) {
                int n = p * 2;
                posSlot = Arrays.copyOf(posSlot, n);
                posCell = Arrays.copyOf(posCell, n);
                posBatch = Arrays.copyOf(posBatch, n);
                posQty = Arrays.copyOf(posQty, n);
                posNext = Arrays.copyOf(posNext, n);
            }
        }
        posLive++;
        return p;
    }

    /** Порядок FIFO: возраст партии (момент приёмки), id партии, ячейка (зона приёмки — первой). */
    private boolean before(int b1, int c1, int b2, int c2) {
        if (bRecv[b1] != bRecv[b2]) return bRecv[b1] < bRecv[b2];
        if (b1 != b2) return b1 < b2;
        return c1 < c2;
    }

    /** Прибавить к позиции (создать, если нет); остаток слота и загрузка ячейки — тоже. */
    private void addPos(int s, int cell, int batch, int q) {
        addPos(s, cell, batch, q, true);
    }

    /** То же; {@code load = false} — учётная правка без физического движения (загрузка ячейки не меняется). */
    private void addPos(int s, int cell, int batch, int q, boolean load) {
        int prev = -1, cur = posHead[s];
        while (cur >= 0) {
            if (posBatch[cur] == batch && posCell[cur] == cell) break;
            if (before(batch, cell, posBatch[cur], posCell[cur])) {
                cur = -1;
                break;
            }
            prev = cur;
            cur = posNext[cur];
        }
        if (cur >= 0) {
            posQty[cur] += q;
            if (posQty[cur] == 0) unlink(s, cur);
        } else {
            int p = newPos();
            posSlot[p] = s;
            posCell[p] = cell;
            posBatch[p] = batch;
            posQty[p] = q;
            posNext[p] = prev < 0 ? posHead[s] : posNext[prev];
            if (prev < 0) posHead[s] = p;
            else posNext[prev] = p;
        }
        onHand[s] += q;
        if (cell > 0 && load) cells.load(cell, vMl[slotProduct(s)], wG[slotProduct(s)], q);
        markListed(s);
    }

    private void unlink(int s, int p) {
        int prev = -1, cur = posHead[s];
        while (cur >= 0 && cur != p) {
            prev = cur;
            cur = posNext[cur];
        }
        if (cur < 0) throw new IllegalStateException("позиции нет в списке");
        if (prev < 0) posHead[s] = posNext[p];
        else posNext[prev] = posNext[p];
        posNext[p] = posFree;
        posFree = p;
        posLive--;
    }

    private int findPos(int s, int cell, int batch) {
        for (int cur = posHead[s]; cur >= 0; cur = posNext[cur]) if (posBatch[cur] == batch && posCell[cur] == cell) return cur;
        return -1;
    }

    // ---------------------------------------------------------------- журнал

    private void emit(int node, int cell, int pid, int batch, Kind kind, int qty, Ref ref, long refId) {
        long id = movementIds.next();
        int wh = net.whId[node];
        ledger.movement(id, clock, wh, cell, pid, batch, kind, qty, ref, refId);
        audit.movement(id, clock, node, cell, pid, batch, kind, qty, ref, refId);
    }

    /**
     * Расход FIFO из слота: позиции по возрасту партии; ячейка — {@code cellKind}, зона приёмки — {@code dockKind}.
     * Партии и штуки по порядку — в {@code outB}/{@code outQ} (если не null). Возвращает себестоимость, копеек.
     */
    private long take(int s, int qty, Kind cellKind, Kind dockKind, Ref ref, long refId, IntPairs out) {
        int node = slotNode(s), pid = slotProduct(s);
        int rem = qty;
        long costSum = 0;
        int cur = posHead[s];
        while (cur >= 0 && rem > 0) {
            int next = posNext[cur];
            int q = posQty[cur];
            if (q > 0) {
                int t = Math.min(rem, q);
                int cell = posCell[cur], b = posBatch[cur];
                emit(node, cell, pid, b, cell > 0 ? cellKind : dockKind, -t, ref, refId);
                if (cell == 0 && dockKind == Kind.SHIP) stShips++;
                posQty[cur] -= t;
                onHand[s] -= t;
                if (cell > 0) cells.load(cell, vMl[pid], wG[pid], -t);
                costSum += (long) t * bCost[b];
                if (out != null) out.add(b, t);
                rem -= t;
                if (posQty[cur] == 0) unlink(s, cur);
            }
            cur = next;
        }
        if (rem > 0) throw new IllegalStateException("не хватило остатка: слот " + s + ", нужно ещё " + rem);
        return costSum;
    }

    /** Пары (партия, штук) — без упаковки. */
    static final class IntPairs {
        int n;
        int[] a = new int[8], b = new int[8];

        void clear() {
            n = 0;
        }

        void add(int x, int y) {
            if (n > 0 && a[n - 1] == x) {
                b[n - 1] += y;
                return;
            }
            if (n == a.length) {
                a = Arrays.copyOf(a, n * 2);
                b = Arrays.copyOf(b, n * 2);
            }
            a[n] = x;
            b[n] = y;
            n++;
        }
    }

    private final IntPairs tmpPairs = new IntPairs();

    /** Разместить штуки партии по ячейкам склада с журналом {@code kind} (+); что не влезло — в зону приёмки. */
    private void putIntoCells(int s, int batch, int qty, Kind kind, Ref ref, long refId) {
        int node = slotNode(s), pid = slotProduct(s);
        int prefer = homeCell(s);
        int rem = cells.place(node, vMl[pid], wG[pid], qty, prefer, pl);
        for (int i = 0; i < pl.n; i++) {
            emit(node, pl.cell[i], pid, batch, kind, pl.qty[i], ref, refId);
            addPos(s, pl.cell[i], batch, pl.qty[i]);
        }
        if (rem > 0) {
            stUnplaced += rem;
            emit(node, 0, pid, batch, kind, rem, ref, refId);
            addPos(s, 0, batch, rem);
        }
    }

    private int homeCell(int s) {
        int c = 0;
        for (int cur = posHead[s]; cur >= 0; cur = posNext[cur]) if (posCell[cur] > 0 && posQty[cur] > 0) c = posCell[cur];
        return c;
    }

    // ---------------------------------------------------------------- прогноз и цели

    /**
     * Накопленный прогноз товара от {@code d} на {@code 7·HORIZON_WEEKS} дней вперёд: гладкая часть — выборкой раз в
     * неделю, календарь — по дням. {@code cum[k]} — штук за дни {@code [0, k)}.
     */
    private void cumulate(int seasonIdx, LocalDate d) {
        int base = (int) (d.toEpochDay() - calFrom.toEpochDay());
        double[] cs = calSum[seasonIdx];
        int last = cs.length - 1;
        double acc = 0;
        cum[0] = 0;
        for (int k = 0; k < HDAYS; k++) {
            double s = smp[k / 7];
            if (s != 0) {
                int i0 = Math.min(last, base + k), i1 = Math.min(last, base + k + 1);
                acc += s * (cs[i1] - cs[i0]);
            }
            cum[k + 1] = acc;
        }
    }

    private double horizon(int a, int b) {
        return cum[Math.min(b, HDAYS)] - cum[Math.min(a, HDAYS)];
    }

    private static final int HDAYS = 7 * HORIZON_WEEKS;
    private int[] eventWin;
    private final double[] smpRaw;
    private final long[] smpDay;
    private final boolean[] retired;
    private final long[] launchEp, discEp;
    private final long[] nodeMask;
    private final boolean[] openNow = new boolean[64];
    private long cityMask;
    private static final int[] NO_NODES = {};
    private double[] maxShareOf;
    private double maxShareVal;

    /** Наибольшая доля спроса среди складов и РЦ (для быстрого «ни на один склад не проходит»). */
    private double maxShare(double[] sh) {
        if (sh != maxShareOf) {
            double m = 0;
            for (int node : net.bottomUp) m = Math.max(m, sh[node]);
            maxShareOf = sh;
            maxShareVal = m;
        }
        return maxShareVal;
    }
    private final double[] smp = new double[HORIZON_WEEKS];
    private final double[] cum = new double[HDAYS + 1];

    /** Пересчёт ассортимента и точек заказа всех запущенных товаров; поправка прогноза по факту недели. */
    private void recompute(LocalDate d, boolean firstTime) {
        double[] sh = net.shares(d);
        eventWin = eventWindow(d);
        // управляемые списки собираются заново: ассортимент отметит product(), очереди — добавим из прежних
        int[][] old = new int[N][];
        for (int node = 0; node < N; node++) {
            old[node] = Arrays.copyOf(managed[node], managedN[node]);
            for (int s : old[node]) flags[s] &= ~F_MANAGED;
            managedN[node] = 0;
        }
        for (int i = 0; i < launchPtr; i++) {
            Catalog.Product p = byLaunch.get(i);
            if (p.assortment == Catalog.Assortment.ONLY_3P || retired[p.id]) continue;
            if (!firstTime) {
                corrNum[p.id] = 0.75 * corrNum[p.id] + 0.25 * obsWeek[p.id];
                corrDen[p.id] = 0.75 * corrDen[p.id] + 0.25 * fcWeek[p.id];
                double c = (corrNum[p.id] + 0.5) / (corrDen[p.id] + 0.5);
                corr[p.id] = Math.max(0.5, Math.min(2.0, c));
                obsWeek[p.id] = 0;
            }
            product(p, d, sh);
        }
        for (int node = 0; node < N; node++) {
            for (int s : old[node]) if (backordered[s] > 0) markManaged(s);
            Arrays.sort(managed[node], 0, managedN[node]);
        }
    }

    /** Ассортимент и цели товара по всем узлам на дату. */
    private void product(Catalog.Product p, LocalDate d, double[] sh) {
        int pid = p.id;
        long ed = d.toEpochDay();
        boolean alive = launchEp[pid] <= ed && ed < discEp[pid];
        if (!alive) {
            for (int node = 0; node < N; node++) {
                int s = slot(node, pid);
                flags[s] &= ~(F_ASSORT | F_DIRECT | F_ARMED);
                rop[s] = 0;
                upTo[s] = 0;
            }
            nodeMask[pid] = 0;
            fcWeek[pid] = 0;
            if (!p.launchedOn.isAfter(d)) retired[pid] = true;
            return;
        }
        // выборки гладкого прогноза раз в 7 дней; через неделю — сдвиг и одна новая выборка
        int o = pid * HORIZON_WEEKS;
        if (smpDay[pid] == ed - 7) {
            System.arraycopy(smpRaw, o + 1, smpRaw, o, HORIZON_WEEKS - 1);
            smpRaw[o + HORIZON_WEEKS - 1] = fc.baseRate(p, d.plusDays(7L * (HORIZON_WEEKS - 1) + 3));
        } else if (smpDay[pid] != ed) {
            for (int j = 0; j < HORIZON_WEEKS; j++) smpRaw[o + j] = fc.baseRate(p, d.plusDays(7L * j + 3));
        }
        smpDay[pid] = ed;
        for (int j = 0; j < HORIZON_WEEKS; j++) smp[j] = smpRaw[o + j] * corr[pid];
        cumulate(season[pid].ordinal(), d);
        double r28 = horizon(0, 28) / scale;
        fcWeek[pid] = horizon(0, 7) / corr[pid];
        // узлы снизу вверх: склады (по убыванию доли спроса — пока доля проходит порог; плюс те, где товар уже есть),
        // РЦ (РЦ держит всё, что держат его склады), хаб
        double directShare = 0;
        if (nodeMask[pid] != 0 || r28 * maxShare(sh) >= Math.min(CITY_IN, DC_IN)) {
            long done = 0;
            for (int node : citiesByShare(sh)) {
                if (r28 * sh[node] < CITY_IN) break;
                evalNode(node, pid, r28, sh);
                done |= 1L << node;
            }
            long rest = nodeMask[pid] & cityMask & ~done;
            while (rest != 0) {
                int node = Long.numberOfTrailingZeros(rest);
                rest &= rest - 1;
                evalNode(node, pid, r28, sh);
            }
            for (int node : net.dcNodes) directShare += evalNode(node, pid, r28, sh);
        }
        int s = slot(net.hub, pid);
        boolean in = (flags[s] & F_ASSORT) != 0;
        boolean nowIn = in ? r28 >= HUB_IN * HYSTERESIS : r28 >= HUB_IN;
        if (!nowIn) {
            flags[s] &= ~F_ASSORT;
            rop[s] = 0;
            upTo[s] = 0;
            return;
        }
        flags[s] |= F_ASSORT;
        markManaged(s);
        int ps = primary[pid];
        int T = (ps > 0 ? sup.supplier(ps).leadTimeDays() : 14) + 7;
        double hs = Math.max(0.0, 1.0 - directShare);
        double mu = horizon(0, T) * hs;
        int r = (int) Math.floor(mu + Z_HUB * Math.sqrt(mu));
        // цикл хаба — по оборачиваемости: ходовое заказывают чаще, хвост — реже (в «полных» штуках в сутки)
        double fullPerDay = r28 / 28;
        int cycle = fullPerDay >= 10 ? CYCLE_HUB / 2 : fullPerDay >= 1 ? CYCLE_HUB : CYCLE_HUB * 5 / 3;
        int hi = Math.min(HDAYS, T + cycle);
        double cyc = hi > T ? horizon(T, hi) * hs * cycle / (hi - T) : 0;
        rop[s] = r;
        upTo[s] = Math.max(r + casePack[pid], (int) Math.ceil(r + cyc));
        if (estimate != null) estimate[2] += horizon(0, 28) / 28 * hs / Math.max(1, upTo[s] - r);
    }

    /** Ассортимент и цели товара на складе или РЦ; возвращает долю спроса узла, если его снабжает свой поставщик. */
    private double evalNode(int node, int pid, double r28, double[] sh) {
        int s = slot(node, pid);
        boolean in = (flags[s] & F_ASSORT) != 0;
        double need = r28 * sh[node];
        boolean dc = net.kind[node] == Geo.Kind.DC;
        double th = dc ? DC_IN : CITY_IN;
        boolean nowIn = openNow[node] && (in ? need >= th * HYSTERESIS : need >= th);
        boolean childArmed = false;
        if (dc && openNow[node]) {
            for (int c : net.children[node]) {
                int cs = slot(c, pid);
                if ((flags[cs] & F_ASSORT) != 0) nowIn = true;
                if ((flags[cs] & F_ARMED) != 0) childArmed = true;
            }
        }
        if (!nowIn) {
            if (in) {
                flags[s] &= ~(F_ASSORT | F_DIRECT | F_ARMED);
                rop[s] = 0;
                upTo[s] = 0;
                nodeMask[pid] &= ~(1L << node);
            }
            return 0;
        }
        flags[s] |= F_ASSORT;
        nodeMask[pid] |= 1L << node;
        // запас — сразу, если за 13 недель ждут хотя бы полштуки или его ждёт склад-ребёнок; иначе — по первому спросу
        if (childArmed || horizon(0, HDAYS) * sh[node] >= ARM_UNITS) flags[s] |= F_ARMED;
        if ((flags[s] & F_ARMED) != 0) markManaged(s);
        int dsup = dc && directSup[node] != null ? directSup[node][pid] : 0;
        int T;
        if (dsup > 0) {
            flags[s] |= F_DIRECT;
            T = sup.supplier(dsup).leadTimeDays() + 7;
        } else {
            flags[s] &= ~F_DIRECT;
            T = (int) (net.transitSeconds(net.parent[node], node) / 86_400) + 1;
        }
        double mu = horizon(0, T) * sh[node];
        double z = dc ? Z_DC : Z_CITY;
        int r = (int) Math.floor(mu + z * Math.sqrt(mu));
        double cyc = horizon(T, T + (dsup > 0 ? CYCLE_HUB : dc ? CYCLE_DC : CYCLE_CITY)) * sh[node];
        // перед большим событием — запас и под него (не меньше штуки на позицию, где товар уже держат)
        if (eventWin != null && (flags[s] & F_ARMED) != 0) cyc += Math.max(1.0, horizon(eventWin[0], eventWin[1]) * sh[node]);
        rop[s] = r;
        upTo[s] = Math.max(r + 1, (int) Math.ceil(r + cyc));
        if (dsup > 0) upTo[s] = Math.max(upTo[s], r + Math.max(1, casePack[pid] / 2));
        if (estimate != null) estimate[dsup > 0 ? 3 : dc ? 1 : 0] += horizon(0, 28) / 28 * sh[node] / Math.max(1, upTo[s] - r);
        return dsup > 0 ? sh[node] : 0;
    }

    private double[] byShareOf;
    private int[] byShare;

    /** Городские склады по убыванию доли спроса (при равенстве — по узлу). */
    private int[] citiesByShare(double[] sh) {
        if (sh != byShareOf) {
            Integer[] a = new Integer[net.cityNodes.length];
            for (int i = 0; i < a.length; i++) a[i] = net.cityNodes[i];
            Arrays.sort(a, (x, y) -> sh[x] != sh[y] ? Double.compare(sh[y], sh[x]) : Integer.compare(x, y));
            byShare = new int[a.length];
            for (int i = 0; i < a.length; i++) byShare[i] = a[i];
            byShareOf = sh;
        }
        return byShare;
    }

    /** Окно ближайшего большого события (11.11, «чёрная пятница», Новый год), если оно начинается через 3–16 дней. */
    private int[] eventWindow(LocalDate d) {
        for (RuCalendar.SalesEvent e : new RuCalendar.SalesEvent[]{RuCalendar.SalesEvent.ELEVEN_ELEVEN,
                RuCalendar.SalesEvent.BLACK_FRIDAY, RuCalendar.SalesEvent.NEW_YEAR}) {
            LocalDate[] w = e.window(d.getYear());
            long a = w[0].toEpochDay() - d.toEpochDay(), b = w[1].toEpochDay() - d.toEpochDay();
            if (a >= 3 && a <= 16) return new int[]{(int) a, (int) Math.min(b, 90)};
        }
        return null;
    }

    // ---------------------------------------------------------------- входящие остатки

    /** Остатки на начало периода: заказы «opening» по поставщикам и складам, приняты в первую секунду сразу в ячейки. */
    private void opening(LocalDate d) {
        Map<Long, List<int[]>> byPo = new TreeMap<>();
        for (int node = 0; node < N; node++) {
            if (!net.open(node, d)) continue;
            for (int i = 0; i < launchPtr; i++) {
                Catalog.Product p = byLaunch.get(i);
                int s = slot(node, p.id);
                if ((flags[s] & F_ASSORT) == 0 || upTo[s] <= 0) continue;
                if (node != net.hub && (flags[s] & F_ARMED) == 0) continue;
                Rng r = stOpen.at(node, p.id);
                int q = rop[s] + 1 + r.nextInt(Math.max(1, upTo[s] - rop[s]));
                int supId = node != net.hub && directSup[node] != null && directSup[node][p.id] > 0
                        ? directSup[node][p.id] : primary[p.id];
                if (supId <= 0) continue;
                byPo.computeIfAbsent(key(supId, node), k -> new ArrayList<>()).add(new int[]{p.id, q});
            }
        }
        for (Map.Entry<Long, List<int[]>> e : byPo.entrySet()) {
            int supId = (int) (e.getKey() >>> 8), node = (int) (e.getKey() & 0xff);
            int po = newPo(supId, node, P_OPENING, clock, clock, clock, clock, clock, false);
            for (int[] l : e.getValue()) {
                Catalog.Product p = cat.product(l[0]);
                int b = newBatch(po, 0, p.id, node, l[1], l[1],
                        Math.max(1, cost.unitCost(p, supId, clock, d, po) * 97 / 100));
                newLine(po, p.id, l[1], l[1], b, -1, slot(node, p.id));
                flags[slot(node, p.id)] |= F_ORDERED;
                bRecv[b] = clock;
                putIntoCells(slot(node, p.id), b, l[1], Kind.RECEIPT, Ref.PURCHASE_ORDER, po);
                stOpening++;
            }
        }
    }

    // ---------------------------------------------------------------- заказы поставщикам

    private int newPo(int supId, int node, byte purpose, long ordered, long expected, long confirm, long shipped,
                      long arrival, boolean cancel) {
        int id = poIds.nextInt();
        if (id >= poSup.length) {
            int n = Math.max(id + 1, poSup.length * 2);
            poSup = Arrays.copyOf(poSup, n);
            poWh = Arrays.copyOf(poWh, n);
            poLine0 = Arrays.copyOf(poLine0, n);
            poLines = Arrays.copyOf(poLines, n);
            poPurpose = Arrays.copyOf(poPurpose, n);
            poCancelFate = Arrays.copyOf(poCancelFate, n);
            poOrdered = Arrays.copyOf(poOrdered, n);
            poExpected = Arrays.copyOf(poExpected, n);
            poConfirm = Arrays.copyOf(poConfirm, n);
            poShipped = Arrays.copyOf(poShipped, n);
            poArrival = Arrays.copyOf(poArrival, n);
            poCancel = Arrays.copyOf(poCancel, n);
        }
        poTop = id;
        poSup[id] = supId;
        poWh[id] = net.whId[node];
        poLine0[id] = lnTop;
        poLines[id] = 0;
        poPurpose[id] = purpose;
        poCancelFate[id] = cancel;
        poOrdered[id] = ordered;
        poExpected[id] = expected;
        poConfirm[id] = confirm;
        poShipped[id] = shipped;
        poArrival[id] = arrival;
        poCancel[id] = -1;
        return id;
    }

    private void newLine(int po, int pid, int ordered, int delivered, int batch, int inRec, int s) {
        if (lnTop == lnProduct.length) {
            int n = lnTop * 2;
            lnProduct = Arrays.copyOf(lnProduct, n);
            lnOrdered = Arrays.copyOf(lnOrdered, n);
            lnDelivered = Arrays.copyOf(lnDelivered, n);
            lnBatch = Arrays.copyOf(lnBatch, n);
            lnIn = Arrays.copyOf(lnIn, n);
            lnSlot = Arrays.copyOf(lnSlot, n);
        }
        lnProduct[lnTop] = pid;
        lnOrdered[lnTop] = ordered;
        lnDelivered[lnTop] = delivered;
        lnBatch[lnTop] = batch;
        lnIn[lnTop] = inRec;
        lnSlot[lnTop] = s;
        lnTop++;
        poLines[po]++;
    }

    private int newBatch(int po, int seller, int pid, int node, int qty, int ordered, long unitCost) {
        int id = batchIds.nextInt();
        if (id >= bPo.length) {
            int n = Math.max(id + 1, bPo.length * 2);
            bPo = Arrays.copyOf(bPo, n);
            bSeller = Arrays.copyOf(bSeller, n);
            bProduct = Arrays.copyOf(bProduct, n);
            bWh = Arrays.copyOf(bWh, n);
            bQty = Arrays.copyOf(bQty, n);
            bOrdered = Arrays.copyOf(bOrdered, n);
            bCost = Arrays.copyOf(bCost, n);
            bRecv = Arrays.copyOf(bRecv, n);
        }
        batchTop = id;
        bPo[id] = po;
        bSeller[id] = seller;
        bProduct[id] = pid;
        bWh[id] = net.whId[node];
        bQty[id] = qty;
        bOrdered[id] = ordered;
        bCost[id] = unitCost;
        bRecv[id] = -1;
        return id;
    }

    private int newInbound(int s, int qty, long eta) {
        int r;
        if (inFree >= 0) {
            r = inFree;
            inFree = inNext[r];
        } else {
            r = inTop++;
            if (r == inSlot.length) {
                int n = r * 2;
                inSlot = Arrays.copyOf(inSlot, n);
                inQty = Arrays.copyOf(inQty, n);
                inNext = Arrays.copyOf(inNext, n);
                inEta = Arrays.copyOf(inEta, n);
            }
        }
        inSlot[r] = s;
        inQty[r] = qty;
        inEta[r] = eta;
        int prev = -1, cur = inHead[s];
        while (cur >= 0 && inEta[cur] <= eta) {
            prev = cur;
            cur = inNext[cur];
        }
        inNext[r] = cur;
        if (prev < 0) inHead[s] = r;
        else inNext[prev] = r;
        inbound[s] += qty;
        return r;
    }

    private void dropInbound(int r) {
        int s = inSlot[r];
        int prev = -1, cur = inHead[s];
        while (cur >= 0 && cur != r) {
            prev = cur;
            cur = inNext[cur];
        }
        if (cur < 0) throw new IllegalStateException("поступления нет в списке");
        if (prev < 0) inHead[s] = inNext[r];
        else inNext[prev] = inNext[r];
        inbound[s] -= inQty[r];
        inNext[r] = inFree;
        inFree = r;
    }

    /** Недели года для чередования поставщиков. */
    private static long week(LocalDate d) {
        return Math.floorDiv(d.toEpochDay() + 3, 7);
    }

    /**
     * День заказа поставщику на узел: раз в неделю в свой будний день, у крупных — дважды. «Крупный» — по товарам,
     * что были в продаже в начале периода (не по всему списку: он зависит от длины окна, а прошлое — не должно).
     */
    private boolean reviewDay(int supId, int node, LocalDate d) {
        int dow = d.getDayOfWeek().getValue() - 1;
        if (dow > 4) return false;
        int wd = (supId + node * 3) % 5;
        return dow == wd || (bigSupplier.contains(key(supId, node)) && dow == (wd + 3) % 5);
    }

    private final java.util.Set<Long> bigSupplier = new java.util.TreeSet<>();

    /**
     * Заказы поставщикам: сначала срочные — первые поставки ходовых новинок, запущенных с прошлого рабочего дня (у
     * поставщика выделена квота — первая партия меньше спроса); потом плановые — у поставщиков, чей сегодня день.
     * Заказ, где все строки — первые поставки товара на склад, — {@code launch}.
     */
    private void reviews() {
        LocalDate d = day;
        long w = week(d), ed = d.toEpochDay();
        Map<Long, IntPairs> urgent = new TreeMap<>();
        for (Catalog.Product p : pendingHot) {
            for (int node = 0; node < N; node++) {
                if (!net.open(node, d)) continue;
                int supId = node == net.hub ? primary[p.id] : directSup[node] != null ? directSup[node][p.id] : 0;
                if (supId <= 0) continue;
                int q = orderQty(node, p, d);
                if (q > 0) urgent.computeIfAbsent(key(supId, node), k -> new IntPairs()).add(p.id, q);
            }
        }
        pendingHot.clear();
        for (Map.Entry<Long, IntPairs> e : urgent.entrySet()) {
            placePo((int) (e.getKey() >>> 8), (int) (e.getKey() & 0xff), P_LAUNCH, e.getValue());
        }
        for (Map.Entry<Long, int[]> e : reviewLists.entrySet()) {
            int supId = (int) (e.getKey() >>> 8), node = (int) (e.getKey() & 0xff);
            int[] list = e.getValue();
            if (!openNow[node] || !reviewDay(supId, node, d)) continue;
            tmpPairs.clear();
            boolean firstOnly = true;
            for (int pid : list) {
                if (launchEp[pid] > ed) continue;
                Catalog.Product p = cat.product(pid);
                if (node == net.hub && secondary[pid] > 0) {
                    int chosen = stSup.unit(((long) pid << 32) ^ w) < 0.25 ? secondary[pid] : primary[pid];
                    if (chosen != supId) continue;
                }
                int q = orderQty(node, p, d);
                if (q <= 0) continue;
                if ((flags[slot(node, pid)] & F_ORDERED) != 0) firstOnly = false;
                tmpPairs.add(pid, q);
            }
            if (tmpPairs.n > 0) placePo(supId, node, firstOnly ? P_LAUNCH : P_REPL, tmpPairs);
        }
    }

    /** Сколько заказать поставщику на узел: ниже точки заказа — до цели, кратно упаковке; новинке — с квотой. */
    private int orderQty(int node, Catalog.Product p, LocalDate d) {
        int pid = p.id, s = slot(node, pid);
        boolean stocked = (flags[s] & F_ASSORT) != 0;
        if (!stocked && backordered[s] == 0) return 0;
        if (node != net.hub && ((flags[s] & F_DIRECT) == 0 || (flags[s] & F_ARMED) == 0 && backordered[s] == 0)) return 0;
        int ipv = ip(s);
        if (stocked ? ipv > rop[s] : ipv >= 0) return 0;
        int need = (stocked ? upTo[s] : 0) - ipv;
        if (need <= 0) return 0;
        int pack = node == net.hub ? casePack[pid] : Math.max(1, casePack[pid] / 2);
        int q = (need + pack - 1) / pack * pack;
        if (hot[pid] && d.toEpochDay() - p.launchedOn.toEpochDay() < 60) {
            double cap = (flags[s] & F_ORDERED) == 0 ? stLaunch.at(pid, node).uniform(0.3, 0.6) : 0.75;
            q = Math.max(pack, (int) StrictMath.ceil(q * cap / pack) * pack);
        }
        return q;
    }

    private final List<Catalog.Product> pendingHot = new ArrayList<>();

    /** Разместить заказ поставщику: судьба (отмена, опоздание, недопоставка) разыгрывается сразу, по ключу заказа. */
    private void placePo(int supId, int node, byte purpose, IntPairs lines) {
        Suppliers.Supplier s = sup.supplier(supId);
        LocalDate d = day;
        Rng r = stPo.at(supId, node, d.toEpochDay());
        double rel = s.reliability().doubleValue();
        boolean weak = rel < 0.8;
        long ordered = clock;
        // ожидаемая дата — срок поставщика, на рабочий день не позже (в нерабочие поставщики не возят; так и заказ
        // в последний день фактов с самым долгим сроком ждут внутри окна данных)
        LocalDate expDay = Period.localDate(ordered + s.leadTimeDays() * 86_400L, ZoneOffset.UTC);
        while (cal.isNonWorking(expDay) && expDay.isAfter(d.plusDays(1))) expDay = expDay.minusDays(1);
        while (cal.isNonWorking(expDay)) expDay = expDay.plusDays(1);
        long expected = expDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 6 * 3600L;
        long confirm = ordered + r.between(2, 20) * 3600L;
        boolean cancel = r.chance(weak ? 0.04 : 0.006);
        int delay = 0;
        if (r.nextDouble() > rel) delay = 1 + (int) r.exponential(weak ? 6 : 2);
        else if (s.leadTimeDays() > 3 && r.chance(0.06)) delay = -1;
        LocalDate arrDay = Period.localDate(expected, ZoneOffset.UTC).plusDays(delay);
        while (cal.isNonWorking(arrDay)) arrDay = arrDay.plusDays(1);
        long arrival = arrDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond() + r.between(6 * 60, 14 * 60) * 60L;
        if (arrival <= confirm) arrival = confirm + 3600;
        double km = net.kmFromCity(node, ctx.get(StoKeys.GEO).city(s.cityId()));
        long transit = 86_400L * Math.max(1, (long) StrictMath.ceil(km / 700));
        long shipped = Math.max(confirm, arrival - transit);
        boolean shortPo = r.chance((1 - rel) * 0.8);
        int po = newPo(supId, node, purpose, ordered, expected, confirm, cancel ? Long.MAX_VALUE : shipped,
                cancel ? Long.MAX_VALUE : arrival, cancel);
        for (int i = 0; i < lines.n; i++) {
            int pid = lines.a[i], q = lines.b[i];
            int delivered = q;
            if (shortPo && q >= 2 && r.chance(0.35)) delivered = Math.max(1, (int) StrictMath.round(q * r.uniform(0.4, 0.9)));
            int sl = slot(node, pid);
            flags[sl] |= F_ORDERED;
            int inRec = newInbound(sl, q, expected);
            int b = 0;
            if (!cancel) b = newBatch(po, 0, pid, node, delivered, q, cost.unitCost(cat.product(pid), supId, ordered, d, po));
            newLine(po, pid, q, delivered, b, inRec, sl);
            markManaged(sl);
        }
        if (cancel) {
            long at = Math.min(Math.max(confirm, ordered + r.between(1, 4) * 86_400L), expected - 3600);
            poCancel[po] = Math.max(at, confirm);
            schedule(poCancel[po], EV_PO_CANCEL, po, 0, null);
        } else {
            schedule(arrival, EV_PO_ARRIVAL, po, 0, null);
        }
        if (purpose == P_LAUNCH) stLaunchPo++;
        else stReplPo++;
    }

    private void poArrival(int po) {
        int n = poLines[po], l0 = poLine0[po];
        int node = net.node(poWh[po]);
        int[] sl = new int[n], bs = new int[n];
        for (int i = 0; i < n; i++) {
            int l = l0 + i;
            dropInbound(lnIn[l]);
            int b = lnBatch[l], s = lnSlot[l];
            bRecv[b] = clock;
            emit(node, 0, lnProduct[l], b, Kind.RECEIPT, lnDelivered[l], Ref.PURCHASE_ORDER, po);
            addPos(s, 0, b, lnDelivered[l]);
            sl[i] = s;
            bs[i] = b;
        }
        for (int i = 0; i < n; i++) allocate(sl[i]);
        schedule(clock + PUTAWAY_DELAY, EV_PUTAWAY, node, 0, new Putaway(Ref.PURCHASE_ORDER, po, sl, bs));
    }

    private void poCancel(int po) {
        for (int i = 0; i < poLines[po]; i++) dropInbound(lnIn[poLine0[po] + i]);
    }

    /** Из зоны приёмки — в ячейки: пара «−приёмка, +ячейка» по каждой партии. */
    private void putaway(int node, Putaway pw) {
        for (int i = 0; i < pw.slots.length; i++) {
            int s = pw.slots[i], b = pw.batches[i];
            int p = findPos(s, 0, b);
            if (p < 0 || posQty[p] <= 0) continue;
            int q = posQty[p], pid = slotProduct(s);
            int rem = cells.place(node, vMl[pid], wG[pid], q, homeCell(s), pl);
            int placed = q - rem;
            if (placed == 0) continue;
            emit(node, 0, pid, b, Kind.MOVE, -placed, pw.ref, pw.refId);
            addPos(s, 0, b, -placed);
            for (int k = 0; k < pl.n; k++) {
                emit(node, pl.cell[k], pid, b, Kind.MOVE, pl.qty[k], pw.ref, pw.refId);
                addPos(s, pl.cell[k], b, pl.qty[k]);
            }
            if (rem > 0) stUnplaced += rem;
        }
    }

    // ---------------------------------------------------------------- переброски

    private final IntPairs taken = new IntPairs();

    /** Отправка с узла детям: позиции ниже точки заказа — до цели; не хватает — делим пропорционально нужде. */
    private void dispatch(int src) {
        LocalDate d = day;
        int[] ch = net.children[src];
        List<int[]> req = new ArrayList<>();
        for (int c : ch) {
            if (!openNow[c]) continue;
            int cnt = managedN[c];
            int[] lst = managed[c];
            int k = 0;
            int[] pids = new int[cnt], needs = new int[cnt];
            for (int i = 0; i < cnt; i++) {
                int s = lst[i];
                if ((flags[s] & F_DIRECT) != 0) continue;
                if ((flags[s] & F_ARMED) == 0 && backordered[s] == 0) continue;
                boolean in = (flags[s] & F_ASSORT) != 0;
                int ipv = ip(s);
                int need;
                if (in && ipv <= rop[s]) need = upTo[s] - ipv;
                else if (!in && ipv < 0) need = -ipv;
                else continue;
                if (need <= 0) continue;
                int pid = s % P;
                pids[k] = pid;
                needs[k] = need;
                if (needSum[pid] == 0) {
                    int ss = slot(src, pid);
                    availStart[pid] = Math.max(0, free(ss) - backordered[ss]);
                }
                needSum[pid] += need;
                k++;
            }
            req.add(new int[]{c, k});
            req.add(Arrays.copyOf(pids, k));
            req.add(Arrays.copyOf(needs, k));
        }
        for (int j = 0; j < req.size(); j += 3) {
            int c = req.get(j)[0], k = req.get(j)[1];
            int[] pids = req.get(j + 1), needs = req.get(j + 2);
            Transfer t = null;
            for (int i = 0; i < k; i++) {
                int pid = pids[i];
                int ss = slot(src, pid);
                int avail = Math.max(0, free(ss) - backordered[ss]);
                if (avail <= 0) continue;
                int total = needSum[pid], a0 = availStart[pid];
                // нехватка на родителе — делим пропорционально нужде (с округлением вверх, пока есть)
                int q = total <= a0 ? needs[i] : (int) (((long) needs[i] * a0 + total - 1) / total);
                q = Math.min(q, avail);
                if (q <= 0) continue;
                if (t == null) t = new Transfer(++transferSeq, src, c, clock);
                taken.clear();
                take(ss, q, Kind.TRANSFER_OUT, Kind.TRANSFER_OUT, Ref.TRANSFER, t.id, taken);
                int cs = slot(c, pid);
                if ((flags[cs] & F_ORDERED) == 0) stFill++;
                else if (ip(cs) <= 0) stDepleted++;
                else stTopup++;
                flags[cs] |= F_ORDERED;
                long arr = clock + net.transitSeconds(src, c);
                for (int x = 0; x < taken.n; x++) t.add(cs, taken.a[x], taken.b[x], newInbound(cs, taken.b[x], arr));
                stTransferLines++;
            }
            if (t != null) {
                stTransfers++;
                schedule(clock + net.transitSeconds(src, c), EV_TRANSFER_ARRIVAL, 0, 0, t);
            }
        }
        for (int j = 0; j < req.size(); j += 3) for (int pid : req.get(j + 1)) needSum[pid] = 0;
    }

    private void transferArrival(Transfer t) {
        for (int i = 0; i < t.n; i++) {
            dropInbound(t.in[i]);
            putIntoCells(t.slot[i], t.batch[i], t.qty[i], Kind.TRANSFER_IN, Ref.TRANSFER, t.id);
        }
        for (int i = 0; i < t.n; i++) allocate(t.slot[i]);
    }

    // ---------------------------------------------------------------- резерв, отбор, возврат (API)

    private int newRes() {
        int r;
        if (rFree >= 0) {
            r = rFree;
            rFree = rNext[r];
        } else {
            r = rTop++;
            if (r == rSlot.length) {
                int n = r * 2;
                rSlot = Arrays.copyOf(rSlot, n);
                rQty = Arrays.copyOf(rQty, n);
                rNext = Arrays.copyOf(rNext, n);
                rLine = Arrays.copyOf(rLine, n);
                rOrder = Arrays.copyOf(rOrder, n);
                rReady = Arrays.copyOf(rReady, n);
                rState = Arrays.copyOf(rState, n);
            }
        }
        rNext[r] = -1;
        return r;
    }

    private void freeRes(int r) {
        rState[r] = R_FREE;
        rNext[r] = rFree;
        rFree = r;
    }

    private int resIndex(long id) {
        if (id < 0 || id >= rTop || rState[(int) id] == R_FREE) throw new IllegalArgumentException("нет резерва " + id);
        return (int) id;
    }

    /** Строки очереди слота — пока свободного хватает голове очереди (FIFO, без обгона). */
    private void allocate(int s) {
        while (boHead[s] >= 0) {
            int h = boHead[s];
            if (rState[h] == R_CANCELLED) {
                boHead[s] = rNext[h];
                if (boHead[s] < 0) boTail[s] = -1;
                freeRes(h);
                continue;
            }
            if (free(s) < rQty[h]) break;
            boHead[s] = rNext[h];
            if (boHead[s] < 0) boTail[s] = -1;
            rNext[h] = -1;
            rState[h] = R_ACTIVE;
            backordered[s] -= rQty[h];
            reserved[s] += rQty[h];
            rReady[h] = clock;
            listener.ready(h, clock);
        }
    }

    /**
     * Склад исполнения строки 1P (см. javadoc класса). Учитывает запрос в факте спроса для прогноза — вызывать один раз
     * на строку, даже если строка потом не будет зарезервирована.
     */
    public Offer route1P(int productId, int qty, double lat, double lon, long at) {
        advanceTo(at);
        if (qty <= 0) throw new IllegalArgumentException("штук " + qty);
        obsWeek[productId] += qty;
        int w = net.nearestCity(lat, lon, day);
        int[] chain = {w, w >= 0 ? net.parent[w] : -1, net.hub};
        // спрос «зажигает» позицию склада и его РЦ: в ассортименте, но без запаса — пополнять с этой минуты
        for (int i = 0; i < 2; i++) {
            if (chain[i] < 0) continue;
            int s = slot(chain[i], productId);
            if ((flags[s] & (F_ASSORT | F_ARMED)) == F_ASSORT) {
                flags[s] |= F_ARMED;
                markManaged(s);
            }
        }
        for (int n : chain) {
            if (n < 0 || !openNow[n]) continue;
            int s = slot(n, productId);
            if (free(s) >= qty && boHead[s] < 0) return new Offer(net.whId[n], at, true);
        }
        long best = Long.MAX_VALUE;
        int bn = -1;
        for (int n : chain) {
            if (n < 0 || !openNow[n]) continue;
            long eta = eta(slot(n, productId), qty);
            if (eta >= 0 && eta < best) {
                best = eta;
                bn = n;
            }
        }
        if (bn >= 0) return new Offer(net.whId[bn], Math.max(at, best), false);
        Catalog.Product p = cat.product(productId);
        if (p.assortment == Catalog.Assortment.ONLY_3P || !p.aliveOn(day) || primary[productId] <= 0) {
            stLostEta++;
            return new Offer(net.whId[net.hub], -1, false);
        }
        return new Offer(net.whId[net.hub], supplierEta(productId), false);
    }

    /** Когда в слоте освободится {@code q} штук по ожидаемым поступлениям; −1 — не освободится. */
    private long eta(int s, int q) {
        long avail = (long) onHand[s] - reserved[s] - backordered[s];
        for (int r = inHead[s]; r >= 0; r = inNext[r]) {
            avail += inQty[r];
            if (avail >= q) return inEta[r];
        }
        return -1;
    }

    /** Оценка для товара без поступлений: ближайший рабочий день заказа у основного поставщика + его срок. */
    private long supplierEta(int pid) {
        int s = primary[pid];
        Suppliers.Supplier x = sup.supplier(s);
        LocalDate d = day.plusDays(1);
        for (int i = 0; i < 14 && !(cal.isWorking(d) && reviewDay(s, net.hub, d)); i++) d = d.plusDays(1);
        return d.atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 7 * 3600L + x.leadTimeDays() * 86_400L;
    }

    /** Склад FBO для строки продавца: где у продавца свободен товар, ближайший к покупателю; иначе — когда придёт. */
    public Offer routeFbo(int sellerId, int productId, int qty, double lat, double lon, long at) {
        advanceTo(at);
        long mask = fboNodes.getOrDefault(((long) sellerId << 24) | productId, 0L);
        long okMask = 0;
        for (int n = 0; n < N; n++) {
            if ((mask & (1L << n)) == 0) continue;
            int s = fboSlots.get(fboKey(n, productId, sellerId), -1);
            if (free(s) >= qty && boHead[s] < 0) okMask |= 1L << n;
        }
        if (okMask != 0) return new Offer(net.whId[net.nearestOf(okMask, lat, lon)], at, true);
        long best = Long.MAX_VALUE;
        int bn = -1;
        for (int n = 0; n < N; n++) {
            if ((mask & (1L << n)) == 0) continue;
            long eta = eta(fboSlots.get(fboKey(n, productId, sellerId), -1), qty);
            if (eta >= 0 && eta < best) {
                best = eta;
                bn = n;
            }
        }
        return bn < 0 ? new Offer(0, -1, false) : new Offer(net.whId[bn], Math.max(at, best), false);
    }

    /** Свободно на складе (остаток − резерв) — товар магазина ({@code sellerId = 0}) или продавца FBO. */
    public int available(int warehouseId, int productId, int sellerId, long at) {
        advanceTo(at);
        int node = net.node(warehouseId);
        if (node < 0) return 0;
        int s = sellerId == 0 ? slot(node, productId) : fboSlots.get(fboKey(node, productId, sellerId), -1);
        return s < 0 ? 0 : Math.max(0, free(s));
    }

    /**
     * Резерв строки заказа на складе (из {@link Offer}). Хватает и нет очереди — резерв готов сразу; иначе строка
     * встаёт в очередь склада. Возвращает id резерва для {@link #pick}/{@link #cancel}.
     */
    public long reserve(long orderId, int lineNo, int warehouseId, int productId, int sellerId, int qty, long at) {
        advanceTo(at);
        int node = net.node(warehouseId);
        if (node < 0) throw new IllegalArgumentException("склад " + warehouseId + " не хранит товар");
        if (qty <= 0) throw new IllegalArgumentException("штук " + qty);
        int s = sellerId == 0 ? slot(node, productId) : fboSlot(node, productId, sellerId);
        int r = newRes();
        rSlot[r] = s;
        rQty[r] = qty;
        rOrder[r] = orderId;
        rLine[r] = lineNo;
        if (free(s) >= qty && boHead[s] < 0) {
            rState[r] = R_ACTIVE;
            reserved[s] += qty;
            rReady[r] = at;
        } else {
            rState[r] = R_BACK;
            rReady[r] = -1;
            backordered[s] += qty;
            if (boTail[s] < 0) boHead[s] = r;
            else rNext[boTail[s]] = r;
            boTail[s] = r;
            markManaged(s);
            stBackorders++;
        }
        return r;
    }

    public boolean isReady(long reservationId) {
        return rState[resIndex(reservationId)] == R_ACTIVE;
    }

    /** Момент готовности резерва; −1 — ещё в очереди. */
    public long readyAt(long reservationId) {
        int r = resIndex(reservationId);
        return rState[r] == R_ACTIVE ? rReady[r] : -1;
    }

    /** Отбор готового резерва: FIFO по партиям; резерв закрыт. */
    public Picked pick(long reservationId, long at) {
        advanceTo(at);
        int r = resIndex(reservationId);
        if (rState[r] != R_ACTIVE) throw new IllegalStateException("резерв " + reservationId + " не готов");
        int s = rSlot[r], q = rQty[r];
        taken.clear();
        long c = take(s, q, Kind.PICK, Kind.SHIP, Ref.ORDER, rOrder[r], taken);
        reserved[s] -= q;
        stPicks++;
        Picked out = new Picked(net.whId[slotNode(s)], taken.n > 0 ? taken.a[0] : 0, q, c);
        freeRes(r);
        return out;
    }

    /** Отмена резерва или места в очереди. */
    public void cancel(long reservationId, long at) {
        advanceTo(at);
        int r = resIndex(reservationId);
        int s = rSlot[r];
        if (rState[r] == R_ACTIVE) {
            reserved[s] -= rQty[r];
            freeRes(r);
            allocate(s);
        } else if (rState[r] == R_BACK) {
            backordered[s] -= rQty[r];
            rState[r] = R_CANCELLED;
            allocate(s);
        } else {
            throw new IllegalStateException("резерв " + reservationId + " уже закрыт");
        }
    }

    /**
     * Возврат покупателя на склад хранения: годный — в ячейку (снова в продаже), брак — в зону возвратов и через
     * сутки списание. {@code batchId} — из {@link Picked}; {@code ref} — обычно {@link Ref#RETURN}.
     */
    public void customerReturn(long refId, Ref ref, int warehouseId, int productId, int sellerId, int batchId, int qty,
                               boolean resellable, long at) {
        advanceTo(at);
        int node = net.node(warehouseId);
        if (node < 0) throw new IllegalArgumentException("склад " + warehouseId + " не хранит товар");
        if (batchId <= 0 || batchId > batchTop || bProduct[batchId] != productId || bSeller[batchId] != sellerId) {
            throw new IllegalArgumentException("партия " + batchId + " не того товара или владельца");
        }
        stReturns++;
        int s = sellerId == 0 ? slot(node, productId) : fboSlot(node, productId, sellerId);
        if (resellable) {
            putIntoCells(s, batchId, qty, Kind.CUSTOMER_RETURN, ref, refId);
            allocate(s);
        } else {
            stDefects++;
            int cell = cells.returnsCell(node, vMl[productId], wG[productId], qty);
            emit(node, cell, productId, batchId, Kind.CUSTOMER_RETURN, qty, ref, refId);
            Quarantine q = new Quarantine(node, cell, productId, batchId, qty, refId);
            if (cell > 0) {
                cells.load(cell, vMl[productId], wG[productId], qty);
                schedule(clock + 26 * 3600L, EV_WRITEOFF, 0, 0, q);
            } else {
                writeoff(q); // в зону возвратов не влезает — списание сразу, с приёмки
            }
        }
    }

    private void writeoff(Quarantine q) {
        emit(q.node, q.cell, q.product, q.batch, Kind.WRITEOFF, -q.qty, Ref.RETURN, q.returnId);
        if (q.cell > 0) cells.load(q.cell, vMl[q.product], wG[q.product], -q.qty);
    }

    // ---------------------------------------------------------------- инвентаризация

    /** Пересчёт склада (хаб — ячейки с {@code id % 6 == filter}): расхождения, неликвид, исправление прошлых ошибок. */
    private void inventory(int node, int filter) {
        long countId = ++inventorySeq;
        LocalDate d = day;
        // сначала — исправить прошлые ошибки этого склада: пересчёт находит товар, списанный дважды
        List<Plant> pl0 = planted.get(node);
        for (int i = 0; i < pl0.size(); ) {
            Plant x = pl0.get(i);
            if (filter >= 0 && x.cell % 6 != filter) {
                i++;
                continue;
            }
            pl0.remove(i);
            emit(node, x.cell, slotProduct(x.slot), x.batch, Kind.INVENTORY, x.qty, Ref.INVENTORY, countId);
            stInventoryRows++;
            addPos(x.slot, x.cell, x.batch, x.qty, false); // товар всё время лежал в ячейке — правка только учёта
            allocate(x.slot);
        }
        for (int li = 0; li < listedN[node]; li++) {
            int s = listed[node][li];
            int pid = slotProduct(s);
            Catalog.Product p = cat.product(pid);
            boolean obsolete = p.discontinuedPlan != null && p.discontinuedPlan.plusDays(90).isBefore(d) && s < N * P;
            int cur = posHead[s];
            while (cur >= 0) {
                int next = posNext[cur];
                int cell = posCell[cur];
                if (cell > 0 && (filter < 0 || cell % 6 == filter)) {
                    int b = posBatch[cur];
                    if (obsolete && posQty[cur] > 0 && free(s) >= posQty[cur]) {
                        int q = posQty[cur];
                        emit(node, cell, pid, b, Kind.WRITEOFF, -q, Ref.INVENTORY, countId);
                        addPos(s, cell, b, -q);
                        stObsolete += q;
                    } else if (posQty[cur] > 0) {
                        Rng r = stInv.at(((long) node << 40) | ((long) cell << 20), b, d.toEpochDay());
                        if (r.chance(INV_DISCREPANCY)) {
                            int q = posQty[cur];
                            if (r.chance(0.75)) {
                                int k = q >= 10 && r.chance(0.3) ? 2 : 1;
                                if (q >= k && free(s) >= k) {
                                    boolean plant = q < 2 * k && free(s) >= 2 * k && r.chance(INV_PLANT);
                                    emit(node, cell, pid, b, Kind.INVENTORY, -k, Ref.INVENTORY, countId);
                                    stInventoryRows++;
                                    if (plant) {
                                        // заложено: корректировку провели дважды — остаток ячейки и партии ниже нуля
                                        // до следующего пересчёта (ключи — до строки, чтобы проверка их знала)
                                        ctx.planted().add(PLANT_CELL, StockAudit.posKey(node, cell, b));
                                        ctx.planted().add(PLANT_TOTAL, StockAudit.aggKey(node, b));
                                        emit(node, cell, pid, b, Kind.INVENTORY, -k, Ref.INVENTORY, countId);
                                        stInventoryRows++;
                                        stPlanted++;
                                        posQty[cur] -= 2 * k;
                                        onHand[s] -= 2 * k;
                                        cells.load(cell, vMl[pid], wG[pid], -k); // физически ушла одна недостача
                                        pl0.add(new Plant(s, cell, b, k));
                                    } else {
                                        addPos(s, cell, b, -k);
                                    }
                                }
                            } else if (cells.fits(cell, vMl[pid], wG[pid]) >= 1) {
                                // излишек: нашлась штука, не проведённая раньше (если ячейка её вмещает)
                                emit(node, cell, pid, b, Kind.INVENTORY, 1, Ref.INVENTORY, countId);
                                stInventoryRows++;
                                addPos(s, cell, b, 1);
                                allocate(s);
                            }
                        }
                    }
                }
                cur = next;
            }
        }
    }

    // ---------------------------------------------------------------- FBO

    private void fboCheck() {
        LocalDate d = day;
        fbo.forEach(d, l -> {
            int node = net.node(l.warehouseId());
            if (node < 0) throw new IllegalArgumentException("FBO на складе " + l.warehouseId() + " — не склад хранения");
            int s = fboSlot(node, l.productId(), l.sellerId());
            int i = s - N * P;
            fboRate[i] = l.unitsPerDay();
            fboLead[i] = Math.max(1, l.leadDays());
            fboCost[i] = l.declaredCostKop();
            double mu = l.unitsPerDay() * (fboLead[i] + 2);
            int r = (int) StrictMath.floor(mu + 1.3 * StrictMath.sqrt(mu));
            rop[s] = r;
            upTo[s] = Math.max(r + 1, (int) StrictMath.ceil(r + l.unitsPerDay() * 14));
            int ipv = ip(s);
            if (ipv > r) return;
            int q = upTo[s] - ipv;
            Rng rr = stFbo.at(l.sellerId(), l.productId(), d.toEpochDay());
            int delivered = rr.chance(0.05) && q >= 3 ? Math.max(1, q - rr.between(1, q / 3)) : q;
            int b = newBatch(0, l.sellerId(), l.productId(), node, delivered, q, Math.max(1, l.declaredCostKop()));
            int inRec = newInbound(s, q, clock + fboLead[i] * 86_400L);
            long arr = clock + fboLead[i] * 86_400L + rr.between(-6, 10) * 3600L;
            bWh[b] = net.whId[node];
            bOrdered[b] = q;
            inbOfBatch.put(b, inRec);
            schedule(Math.max(arr, clock + 3600), EV_FBO_ARRIVAL, b, 0, null);
        });
    }

    private final LongIntMap inbOfBatch = new LongIntMap(1024);

    private void fboArrival(int b) {
        int node = net.node(bWh[b]);
        int s = fboSlot(node, bProduct[b], bSeller[b]);
        dropInbound(inbOfBatch.get(b, -1));
        bRecv[b] = clock;
        emit(node, 0, bProduct[b], b, Kind.RECEIPT, bQty[b], Ref.NONE, 0);
        addPos(s, 0, b, bQty[b]);
        allocate(s);
        schedule(clock + PUTAWAY_DELAY, EV_PUTAWAY, node, 0, new Putaway(Ref.NONE, 0, new int[]{s}, new int[]{b}));
    }

    // ---------------------------------------------------------------- конец

    /** Конец прогона: заказы поставщикам и партии — в таблицы (статусы — на конец фактов), приёмники закрыты. */
    public void finish() {
        if (finished) throw new IllegalStateException("движок уже завершён");
        finished = true;
        long end = simEnd();
        for (int po = 1; po <= poTop; po++) {
            String status;
            long recv = -1;
            if (poCancelFate[po] && poCancel[po] < end) {
                status = "cancelled";
            } else if (poArrival[po] < end) {
                recv = poArrival[po];
                boolean partial = false;
                for (int i = 0; i < poLines[po]; i++) {
                    int l = poLine0[po] + i;
                    if (lnDelivered[l] < lnOrdered[l]) partial = true;
                }
                status = partial ? "partially_received" : "received";
            } else if (poShipped[po] <= end) {
                status = "in_transit";
            } else if (poConfirm[po] <= end) {
                status = "confirmed";
            } else {
                status = "placed";
            }
            ledger.purchaseOrder(po, poSup[po], poWh[po], poOrdered[po], poExpected[po], recv, status,
                    PURPOSE[poPurpose[po]]);
        }
        ledger.purchaseOrdersDone();
        for (int b = 1; b <= batchTop; b++) {
            boolean received = bRecv[b] >= 0;
            ledger.batch(b, bPo[b], bSeller[b], bProduct[b], bWh[b], received ? bQty[b] : bOrdered[b], bCost[b],
                    received ? bRecv[b] : -1, bOrdered[b]);
        }
        ledger.close();
        audit.finish();
    }

    /** Строка журнала прогона. */
    public String stats() {
        return String.format(java.util.Locale.ROOT,
                "заказов поставщикам %d (входящие %d строк, новинки %d, пополнение %d), партий %d, переброски %d (%d строк: "
                        + "первые %d, после продажи %d, доливка %d), "
                        + "отборов %d, очередей %d, без поставки %d, не размещено %d шт., инвентаризация %d строк (заложено %d), "
                        + "неликвид %d шт., возвратов %d (брак %d); позиций %d, резервов %d, слотов %d",
                poTop, stOpening, stLaunchPo, stReplPo, batchTop, stTransfers, stTransferLines, stFill, stDepleted, stTopup,
                stPicks, stBackorders,
                stLostEta, stUnplaced, stInventoryRows, stPlanted, stObsolete, stReturns, stDefects, posLive, rTop, slots);
    }

    // ---------------------------------------------------------------- для проверок

    /** Конец фактов: конец последних прожитых суток (не позже «сейчас» и конца окна). */
    long simEnd() {
        return day == null ? simEnd : Math.min(simEnd, dayEnd);
    }

    private boolean finished;

    /**
     * Оценка для пересчёта объёмов на другой масштаб: при пересчёте целей — строк в сутки (сток узла / заказ до цели) по
     * видам: [0] переброски на склады, [1] на РЦ, [2] строки заказов хаба, [3] строки заказов РЦ у своих поставщиков.
     */
    double[] estimate;

    /** Остаток слота по журналу движка (для сверки с журналом движений). */
    void forEachPosition(PositionVisitor v) {
        for (int s = 0; s < slots; s++) {
            if (posHead[s] < 0) continue;
            for (int p = posHead[s]; p >= 0; p = posNext[p]) v.visit(slotNode(s), posCell[p], slotProduct(s), posBatch[p], posQty[p]);
        }
    }

    interface PositionVisitor {
        void visit(int node, int cell, int product, int batch, int qty);
    }

    boolean poCancelled(int po) {
        return poCancelFate[po];
    }
}
