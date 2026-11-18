package io.hammerhall.data.auc.bid;

import java.util.Arrays;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Движок торгов: лот и участники с намерениями → события ставок и итог лота ({@link LotOutcome}). Без хранения и без
 * таблиц; генератор набора оборачивает его.
 *
 * <pre>{@code
 * BidEngine engine = new BidEngine(AuctionRules.house(incrementBands), behavior);   // один на поток
 * LotOutcome out = new LotOutcome();                                                  // один на поток
 * engine.run(lot, bidders, new SplittableRandom(lotKey), out);
 * for (int i = 0; i < out.size(); i++) { out.type(i); out.time(i); out.amount(i); … }
 * }</pre>
 *
 * <p><b>Детерминизм.</b> Вся случайность — из переданного генератора, и только через {@code nextLong()}: равномерные
 * целые, доли и экспоненты движок строит сам ({@link StrictMath}), поэтому результат зависит лишь от потока 64-битных
 * чисел — одинаков на любой ОС и версии JDK. Генератор — {@code new SplittableRandom(ключ лота)} или поток ядра:
 * {@code RandomGenerator r = streams.stream("auc.bids").at(lotId)::nextLong}. Ни часов, ни {@code HashMap}, ни
 * порядка потоков: тот же вход → побайтно те же события.
 *
 * <p><b>Онлайн-торги</b> — дискретные события во времени. Участник «смотрит лот» (пробуждение) и решает по тому, что
 * видит; ставка приходит позже на задержку сети. Пока она шла, цену могла поднять чужая — тогда отклонена
 * ({@link RejectReason#PRICE_CHANGED}, в ту же миллисекунду — {@link RejectReason#RACE_LOST}). Приём в одну
 * миллисекунду упорядочен номером приёма ({@code seq}). Мягкое закрытие — {@link AuctionRules#extendedClose}; ставка
 * в момент закрытия и позже — {@link RejectReason#AFTER_CLOSE}.
 *
 * <p><b>Автоставки</b> (свёрнуто, как у онлайн-площадок): когда встречаются два максимума, меньший сразу ставится
 * целиком (автоставка на свой максимум), больший — {@code второй максимум + шаг}, но не выше себя. Равный максимум,
 * поставленный позже, отклоняется ({@link RejectReason#TIED}), а лидер поднимается до него. Против ручной ставки
 * автоставка отвечает минимальным шагом. Резерв между вторым и первым максимумом — цена = резерв
 * ({@link AuctionRules#proxyToReserve()}); так же максимум без соперника, не ниже резерва, сразу ставит резерв.
 *
 * <p><b>Живые торги</b> — пошагово: аукционист объявляет лот, исполняет книгу заочных ставок (это автоставки, раньше
 * оставленная — первая; страховочная телефонная — тоже из книги), просит следующий шаг — обычный, полшага (выше
 * порога, не против книги; сетка дальше — от принятой суммы) или прыжок на 2–5 шагов, — из желающих в зале, на
 * телефоне и онлайн берёт одного; нет желающих — ставка от имени продавца (если резерв не достигнут), иначе последнее
 * предупреждение, после него — молоток. Каждое событие получает длительность; если лот не укладывается в свой слот,
 * времена сжимаются пропорционально.
 *
 * <p><b>Заложенные сбои</b> онлайн-лота ({@link Faults}) и повторы принятой ставки скрыто помечены в итоге, в
 * публичные поля событий не попадают.
 *
 * <p>Экземпляр не потокобезопасен и не создаёт объектов в горячем пути: рабочие массивы растут один раз и
 * переиспользуются.
 */
public final class BidEngine {
    // ---------------------------------------------------------------- постоянные поведения (мс)
    static final long SEC = 1_000, MIN = 60_000, HOUR = 3_600_000;
    /** Онлайн: задержка от решения участника до приёма ставки. */
    static final long LAT_MIN = 80, LAT_MAX = 1_200;
    /** Снайпер: задержка его инструмента. */
    static final long SNIPE_LAT_MIN = 40, SNIPE_LAT_MAX = 900;
    /** Снайпер решает за {@code 0,5 с + Exp(5 с)} до закрытия, не раньше чем за 30 с. */
    static final long SNIPE_LEAD_MIN = 500, SNIPE_LEAD_MEAN = 5_000, SNIPE_LEAD_MAX = 30_000;
    /** Ответ на перебитую ставку: ранний — часы, осторожный — дольше, подставной — десятки минут. */
    static final long EARLY_REACT_MEAN = 3 * HOUR, CAUTIOUS_REACT_MEAN = 8 * HOUR, SHILL_REACT_MEAN = 40 * MIN;
    /**
     * Воюющий («смотрит конец»): приходит за {@code 5 с + Exp(5 мин)} до текущего конца и отвечает на перебитую ставку за
     * {@code 10 с + Exp(105 с)} — ответ медленнее окна мягкого закрытия (2 мин) опаздывает, и цепочка продлений
     * обрывается сама. Подобрано по прогонам набора {@code small} и {@code medium}: поздние ставки, доля продлённых
     * лотов, продлений на продлённый; продления дают и ценовые войны (ниже), поэтому поздние цепочки короче, и среднее
     * продлений на продлённый лот держится у 2,1.
     */
    static final long WAR_ENTRY_MIN = 5 * SEC, WAR_ENTRY_MEAN = 5 * MIN;
    static final long WAR_REACT_MIN = 10 * SEC, WAR_REACT_MEAN = 105 * SEC;
    /**
     * Ценовая война онлайн — «бой»: воюющего перебил другой воюющий, а цена ниже половины его максимума. В бою он
     * отвечает за секунды — {@code 3 с + Exp(15 с)} (окно 2 мин почти всегда успевает), а не как поздний. Так пара
     * воюющих с большими максимумами доходит до узора войны (двое чередуются ≥ 12 раз, молоток ≥ 3× верхней
     * оценки): с ответом позднего война успевала бы за шаг с вероятностью ≈ 0,7 и до 3× верхней не доходила бы. У своего
     * предела (цена от половины максимума) и против не воюющего — ответ позднего.
     */
    static final long DUEL_REACT_MIN = 3 * SEC, DUEL_REACT_MEAN = 15 * SEC;
    static final double DUEL_BELOW = 0.5;
    /**
     * Воюющий, которого перебили, ставит не следующий шаг, а свой максимум с вероятностью {@code min(1, 2·(цена /
     * максимум)⁶)}: далеко от своего предела он воюет шагами (ценовая война), у предела — ставит максимум, и чужая
     * автоставка решает сразу, без цепочки шагов. В бою после {@link #DUEL_TIRED_BIDS} своих ставок шагать надоедает —
     * максимум с вероятностью не меньше {@link #DUEL_TIRED_MAX} на ход: война укладывается в 12–16 чередований (молоток —
     * меньший максимум и шаг), а не тянет продления до потолка.
     */
    static final double WAR_TO_MAX_K = 2.0;
    static final int DUEL_TIRED_BIDS = 6;
    static final double DUEL_TIRED_MAX = 0.8;
    /** Ранний, вернувшись к перебитому лоту, ставит свой максимум, а не следующий шаг, — с этой вероятностью. */
    static final double EARLY_TO_MAX = 0.8;
    /** Снайпер в первый раз ставит в последние секунды ручную ставку на минимальную сумму (иначе — максимум). */
    static final double SNIPE_MANUAL = 0.8;
    /** Повтор после отклонённой ставки (верная сумма). */
    static final long RETRY_MIN = 2 * SEC, RETRY_MAX = 20 * SEC;
    /** Повтор принятой ставки (двойное нажатие, обрыв). */
    static final long DUP_MIN = 150, DUP_MAX = 2_500;
    static final int EARLY_MAX_BIDS = 6, CAUTIOUS_MAX_BIDS = 2, SHILL_MAX_WAITS = 5;
    static final double EARLY_GIVE_UP = 0.15, CAUTIOUS_GIVE_UP = 0.4;
    /** Окно гонок конца лота: ручные ставки в последние 10 с до текущего конца — {@link Behavior#lateRaceShare()}. */
    static final long LATE_RACE_WINDOW = 10 * SEC;
    /** Подставной не ставит в последнюю десятую срока лота. */
    static final double SHILL_STOP = 0.1;
    /** Осторожный сдаётся на 60–95 % своего максимума. */
    static final double CAUTIOUS_COMFORT_MIN = 0.6, CAUTIOUS_COMFORT_MAX = 0.95;
    /** Живые: длительности (от предыдущего события). */
    static final long L_OPEN_MIN = 2_000, L_OPEN_MAX = 6_000;
    static final long L_ROOM_MIN = 1_200, L_ROOM_MAX = 4_000;
    static final long L_PHONE_MIN = 3_000, L_PHONE_MAX = 9_000;
    static final long L_ONLINE_MIN = 1_500, L_ONLINE_MAX = 5_000;
    static final long L_BOOK_MIN = 800, L_BOOK_MAX = 2_000;
    static final long L_VENDOR_MIN = 800, L_VENDOR_MAX = 2_500;
    static final long L_WARN_MIN = 2_000, L_WARN_MAX = 5_000;
    static final long L_HAMMER_MIN = 1_500, L_HAMMER_MAX = 4_000;
    static final long L_LATE_MIN = 200, L_LATE_MAX = 1_500;
    /**
     * Живые: кто берёт шаг — вес стратегии × вес канала. Онлайн-участник жмёт на каждом шаге, зал и телефон вступают,
     * когда без них торги встанут (онлайн — 68 % ставок живых торгов). Победы от весов не зависят —
     * их решают максимумы.
     */
    static final double L_W_ONLINE = 12.0, L_W_ROOM = 0.25, L_W_PHONE = 0.2;
    /** Живые: страховка от зацикливания (шагов сетки до любого максимума — меньше). */
    static final int LIVE_MAX_ROUNDS = 100_000;

    private static final int NONE = LotOutcome.NONE, VENDOR = LotOutcome.VENDOR;
    private static final byte WAKE = 0, ARRIVE = 1;
    private static final byte Q_MAX = 1, Q_TYPO = 2, Q_DUP = 4, Q_SCRIPT = 8, Q_RAISE = 16;
    private static final Strategy[] STRATEGIES = Strategy.values();
    private static final int CH_ONLINE = Channel.ONLINE.ordinal(), CH_PHONE = Channel.PHONE.ordinal(),
            CH_AUCTIONEER = Channel.AUCTIONEER.ordinal();

    private final AuctionRules rules;
    private final Behavior beh;
    private final IncrementTable inc;
    private final Invariants checker = new Invariants();

    // ---------------------------------------------------------------- лот
    private RandomGenerator rnd;
    private LotOutcome out;
    private Lot lot;
    private boolean live;
    private long opening;
    private long price;
    private boolean hasBid;
    private int leader;
    private long leaderMax;
    private long close;
    private long window;
    private int extensions;
    private boolean acceptedNow;
    private int acceptedCount;
    /** Принятые ставки лота: момент, участник, минимальная перед ней — для причин отказа (гонка, цена изменилась). */
    private long[] aT = new long[64], aMin = new long[64];
    private int[] aB = new int[64];
    private long clock;
    private long reserveGrid;
    private Faults faults;
    private long closedAt;
    private boolean doubleArmed, plantedMissed, plantedEarly, plantedDouble;

    // ---------------------------------------------------------------- участники
    private int nb;
    private long[] bId = new long[16];
    private byte[] bChan = new byte[16];
    private byte[] bStrat = new byte[16];
    private long[] bMax = new long[16];
    private long[] bWill = new long[16];
    private long[] bPlaced = new long[16];
    private int[] bBids = new int[16];
    private int[] bWaits = new int[16];
    private boolean[] bOut = new boolean[16];
    /** Онлайн: пробуждений участника в очереди и его ставок в пути (без повторов и сценария). */
    private int[] bWakes = new int[16];
    private int[] bFlying = new int[16];
    private boolean[] bPresent = new boolean[16];
    private boolean[] bSeen = new boolean[16];
    /** Исполняется из книги (заочная, страховочная телефонная). */
    private boolean[] bBook = new boolean[16];
    /** Живые: присутствие участника в торгах лота (см. {@link #dropsOut}). */
    private double[] bExpo = new double[16];
    /** Онлайн: индекс последней своей принятой ручной ставки (для повторов); -1 — нет. */
    private int[] bLastManual = new int[16];
    private int[] cand = new int[16];
    private double[] candW = new double[16];
    private int[] book = new int[16];

    // ---------------------------------------------------------------- очередь онлайн-событий
    private int qn;
    private long[] qTime = new long[64];
    private long[] qTie = new long[64];
    private long[] qAmount = new long[64];
    private long[] qDecided = new long[64];
    private long[] qSeen = new long[64];
    private int[] qBidder = new int[64];
    private byte[] qType = new byte[64];
    private byte[] qFlags = new byte[64];
    /** Повтор: индекс исходной ставки. */
    private int[] qRepeat = new int[64];
    private int[] heap = new int[64];
    private int hn;

    public BidEngine(AuctionRules rules, Behavior behavior) {
        this.rules = rules;
        this.beh = behavior;
        this.inc = rules.increments();
    }

    public BidEngine(AuctionRules rules) {
        this(rules, Behavior.standard());
    }

    public AuctionRules rules() {
        return rules;
    }

    public Behavior behavior() {
        return beh;
    }

    /** Торги лота по стратегиям участников; итог — в {@code out} (перезаписывается). */
    public LotOutcome run(Lot lot, List<Bidder> bidders, RandomGenerator random, LotOutcome out) {
        return run(lot, bidders, List.of(), Faults.NONE, random, out);
    }

    /** Торги лота по стратегиям и сценарию ({@link Action}; только онлайн). */
    public LotOutcome run(Lot lot, List<Bidder> bidders, List<Action> script, RandomGenerator random, LotOutcome out) {
        return run(lot, bidders, script, Faults.NONE, random, out);
    }

    /**
     * Торги лота: стратегии участников, действия сценария ({@link Action}) и заложенные сбои ({@link Faults}) — последние
     * два только онлайн. Итог — в {@code out} (перезаписывается и возвращается). При {@link AuctionRules#selfCheck()}
     * итог проверяется {@link Invariants#check}: нарушение — {@link IllegalStateException}.
     */
    public LotOutcome run(Lot lot, List<Bidder> bidders, List<Action> script, Faults faults, RandomGenerator random,
                          LotOutcome out) {
        this.lot = lot;
        this.rnd = random;
        this.out = out;
        this.live = lot.mode() == Mode.LIVE;
        this.opening = rules.opening(lot);
        out.reset(lot.id(), lot.mode(), opening);
        price = 0;
        hasBid = false;
        leader = NONE;
        leaderMax = 0;
        close = lot.endAt();
        window = lot.endAt() - lot.startAt();
        extensions = 0;
        acceptedCount = 0;
        clock = 0;
        qn = 0;
        hn = 0;
        reserveGrid = rules.reserveOnGrid(lot);
        this.faults = faults;
        closedAt = 0;
        doubleArmed = plantedMissed = plantedEarly = plantedDouble = false;
        prepare(bidders);
        if (live) {
            if (!script.isEmpty()) throw new IllegalArgumentException("лот " + lot.id() + ": сценарий — только у онлайн-торгов");
            if (faults.any()) throw new IllegalArgumentException("лот " + lot.id() + ": заложенные сбои — только у онлайн-торгов");
            runLive();
        } else {
            runOnline(script);
        }
        finish();
        if (rules.selfCheck()) checker.require(lot, bidders, rules, out);
        this.rnd = null;
        this.lot = null;
        this.out = null;
        this.faults = null;
        return out;
    }

    // ---------------------------------------------------------------- случайность: только nextLong()

    private double unit() {
        return (rnd.nextLong() >>> 11) * 0x1.0p-53;
    }

    private boolean chance(double p) {
        return p > 0 && unit() < p;
    }

    /** Равномерно в [0, bound), без смещения (отбрасывание хвоста). */
    private long bounded(long bound) {
        long limit = Long.MAX_VALUE - (Long.MAX_VALUE % bound) - 1;
        long r;
        do {
            r = rnd.nextLong() >>> 1;
        } while (r > limit);
        return r % bound;
    }

    /** Равномерно в [lo, hi] включительно. */
    private long between(long lo, long hi) {
        return lo + bounded(hi - lo + 1);
    }

    private long expo(double mean) {
        return (long) (-mean * StrictMath.log(1.0 - unit()));
    }

    // ---------------------------------------------------------------- участники

    private void prepare(List<Bidder> bidders) {
        nb = bidders.size();
        if (nb > bId.length) {
            int n = Math.max(nb, bId.length * 2);
            bId = new long[n];
            bChan = new byte[n];
            bStrat = new byte[n];
            bMax = new long[n];
            bWill = new long[n];
            bPlaced = new long[n];
            bBids = new int[n];
            bWaits = new int[n];
            bOut = new boolean[n];
            bWakes = new int[n];
            bFlying = new int[n];
            bPresent = new boolean[n];
            bSeen = new boolean[n];
            bBook = new boolean[n];
            bExpo = new double[n];
            bLastManual = new int[n];
            cand = new int[n];
            candW = new double[n];
            book = new int[n];
        }
        for (int i = 0; i < nb; i++) {
            Bidder b = bidders.get(i);
            int ch = b.channel().ordinal();
            if (live ? ch == CH_AUCTIONEER : ch != CH_ONLINE || b.strategy() == Strategy.BOOK) {
                throw new IllegalArgumentException("лот " + lot.id() + " (" + lot.mode().code() + "): участник " + b.id()
                        + " с каналом " + b.channel().code() + " и стратегией " + b.strategy().code());
            }
            bId[i] = b.id();
            bChan[i] = (byte) ch;
            bStrat[i] = (byte) b.strategy().ordinal();
            bMax[i] = inc.floor(b.max());
            bPlaced[i] = b.placedAt();
            bBids[i] = 0;
            bWaits[i] = 0;
            bOut[i] = false;
            bWakes[i] = 0;
            bFlying[i] = 0;
            bPresent[i] = b.strategy() != Strategy.SNIPER;
            bSeen[i] = false;
            bBook[i] = b.book();
            bExpo[i] = 0;
            bLastManual[i] = -1;
            long will = bMax[i];
            if (b.strategy() == Strategy.CAUTIOUS) {
                double f = CAUTIOUS_COMFORT_MIN + (CAUTIOUS_COMFORT_MAX - CAUTIOUS_COMFORT_MIN) * unit();
                will = inc.floor((long) (bMax[i] * f));
            }
            bWill[i] = will;
        }
    }

    private Strategy strat(int b) {
        return STRATEGIES[bStrat[b]];
    }

    private boolean shill(int b) {
        return b >= 0 && bStrat[b] == Strategy.SHILL.ordinal();
    }

    /** Наименьшая допустимая ставка сейчас. */
    private long ask() {
        return hasBid ? inc.minNext(price) : opening;
    }

    // ---------------------------------------------------------------- события

    private void bidEvent(long t, int b, long amount, long max, BidKind kind, RejectReason r) {
        int ch = b >= 0 ? bChan[b] : CH_AUCTIONEER;
        long id = b >= 0 ? bId[b] : 0;
        byte f = shill(b) ? LotOutcome.F_SHILL : 0;
        out.add(EventType.BID, t, b, id, amount, max, ch, kind.ordinal(), r, 0, f);
        if (b >= 0) bSeen[b] = true;
    }

    private void accept(long t, int b, long amount, long max, BidKind kind) {
        if (acceptedCount == aT.length) {
            aT = Arrays.copyOf(aT, acceptedCount * 2);
            aMin = Arrays.copyOf(aMin, acceptedCount * 2);
            aB = Arrays.copyOf(aB, acceptedCount * 2);
        }
        aT[acceptedCount] = t;
        aMin[acceptedCount] = ask();
        aB[acceptedCount] = b;
        bidEvent(t, b, amount, max, kind, RejectReason.NONE);
        price = amount;
        hasBid = true;
        acceptedNow = true;
        acceptedCount++;
    }

    private void reject(long t, int b, long amount, long max, BidKind kind, RejectReason r) {
        bidEvent(t, b, amount, max, kind, r);
    }

    /** Автоставка лидера {@code a} в ответ на чужую: из книги — исполняет аукционист, прочие — система. */
    private void autoBid(long t, int a, long amount) {
        accept(t, a, amount, leaderMax, bBook[a] ? BidKind.ABSENTEE : BidKind.AUTO);
    }

    /**
     * Цена автоставки за максимум {@code mx}: не ниже резерва, если максимум до него дотягивает (T5: резерв между вторым
     * и первым максимумом — цена = резерв).
     */
    private long withReserve(long amount, long mx) {
        if (!rules.proxyToReserve() || reserveGrid == 0 || mx < reserveGrid || amount >= reserveGrid) return amount;
        return reserveGrid;
    }

    /** Время следующего события в ответ на ставку: онлайн — та же миллисекунда; живые — аукционист называет ответ. */
    private long responseTime(long t) {
        if (!live) return t;
        clock += between(L_BOOK_MIN, L_BOOK_MAX);
        return clock;
    }

    /**
     * Ставка участника {@code b} на сумму {@code x} (ручная) или максимум {@code x}, не ниже минимальной (проверено):
     * правила лидерства и автоставок. Первое событие — в момент {@code t}.
     */
    private void applyBid(long t, int b, long x, boolean isMax, BidKind kind) {
        long maxField = isMax ? x : 0;
        if (!hasBid) {
            accept(t, b, isMax ? withReserve(opening, x) : x, maxField, kind);
            leader = b;
            leaderMax = x;
            return;
        }
        int a = leader;
        long ma = leaderMax;
        if (x < ma) {
            // лидер с автоставкой выше — отвечает минимальным шагом (не выше своего максимума, до резерва — резервом)
            accept(t, b, x, maxField, kind);
            autoBid(responseTime(t), a, Math.min(ma, withReserve(inc.minNext(x), ma)));
        } else if (x == ma) {
            // равный максимум: раньше поставленный побеждает, лидер поднимается до него
            reject(t, b, x, maxField, kind, RejectReason.TIED);
            autoBid(responseTime(t), a, ma);
        } else {
            if (isMax) {
                // два максимума: меньший — целиком, больший — второй максимум + шаг
                long at = t;
                if (ma > price) {
                    autoBid(t, a, ma);
                    at = responseTime(t);
                }
                accept(at, b, withReserve(inc.minNext(price), x), x, kind);
            } else {
                accept(t, b, x, 0, kind);
            }
            leader = b;
            leaderMax = x;
        }
    }

    // ================================================================ онлайн

    private void push(long time, long tie, byte type, int b, long amount, byte flags, long decided, long seen) {
        if (qn == qTime.length) {
            int n = qn * 2;
            qTime = Arrays.copyOf(qTime, n);
            qTie = Arrays.copyOf(qTie, n);
            qAmount = Arrays.copyOf(qAmount, n);
            qDecided = Arrays.copyOf(qDecided, n);
            qSeen = Arrays.copyOf(qSeen, n);
            qBidder = Arrays.copyOf(qBidder, n);
            qType = Arrays.copyOf(qType, n);
            qFlags = Arrays.copyOf(qFlags, n);
            qRepeat = Arrays.copyOf(qRepeat, n);
            heap = Arrays.copyOf(heap, n);
        }
        int e = qn++;
        qTime[e] = time;
        qTie[e] = tie;
        qType[e] = type;
        qBidder[e] = b;
        qAmount[e] = amount;
        qFlags[e] = flags;
        qDecided[e] = decided;
        qSeen[e] = seen;
        qRepeat[e] = -1;
        int i = hn++;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (!less(e, heap[p])) break;
            heap[i] = heap[p];
            i = p;
        }
        heap[i] = e;
    }

    /** Порядок очереди: время, затем жребий сети, затем порядок постановки. */
    private boolean less(int a, int b) {
        if (qTime[a] != qTime[b]) return qTime[a] < qTime[b];
        if (qTie[a] != qTie[b]) return qTie[a] < qTie[b];
        return a < b;
    }

    private int pop() {
        int top = heap[0];
        int last = heap[--hn];
        int i = 0;
        while (true) {
            int l = 2 * i + 1;
            if (l >= hn) break;
            int r = l + 1;
            int c = r < hn && less(heap[r], heap[l]) ? r : l;
            if (!less(heap[c], last)) break;
            heap[i] = heap[c];
            i = c;
        }
        if (hn > 0) heap[i] = last;
        return top;
    }

    private void wakeAt(int b, long t) {
        push(t, rnd.nextLong(), WAKE, b, 0, (byte) 0, t, 0);
        bWakes[b]++;
    }

    private long snipeLead() {
        return Math.min(SNIPE_LEAD_MAX, SNIPE_LEAD_MIN + expo(SNIPE_LEAD_MEAN));
    }

    private void runOnline(List<Action> script) {
        long open = lot.startAt();
        for (int b = 0; b < nb; b++) initialWake(b, open);
        for (int k = 0; k < script.size(); k++) {
            Action a = script.get(k);
            if (a.bidder() < 0 || a.bidder() >= nb) {
                throw new IllegalArgumentException("лот " + lot.id() + ": действие " + k + " — нет участника " + a.bidder());
            }
            if (a.at() < open) throw new IllegalArgumentException("лот " + lot.id() + ": действие " + k + " раньше открытия");
            if (a.raise() && inc.floor(a.amount()) > bMax[a.bidder()]) {
                throw new IllegalArgumentException("лот " + lot.id() + ": действие " + k + " — максимум выше потолка участника "
                        + bId[a.bidder()]);
            }
            // действия одной миллисекунды — в порядке сценария: жребий — номер действия
            byte f = (byte) (Q_SCRIPT | (a.max() ? Q_MAX : 0) | (a.repeat() ? Q_DUP : 0) | (a.raise() ? Q_RAISE : 0));
            push(a.at(), Long.MIN_VALUE + k, ARRIVE, a.bidder(), a.repeat() ? 0 : inc.floor(a.amount()), f, a.at(), -1);
        }
        long early = faults.earlyCloseMs();
        boolean closed = false;
        while (hn > 0) {
            int e = pop();
            long t = qTime[e];
            if (!closed && t >= close - early) closeOnline(early);
            closed = closedAt > 0;
            if (closed) {
                // ставка ушла до закрытия, а пришла после — отклонена; пробуждения после закрытия — без следа
                if (qType[e] == ARRIVE && (qDecided[e] < closedAt || (qFlags[e] & Q_SCRIPT) != 0)) {
                    int b = qBidder[e];
                    boolean mx = (qFlags[e] & Q_MAX) != 0;
                    long amt = repeatAmount(e);
                    reject(t, b, amt, mx ? amt : 0, mx ? BidKind.MAX : BidKind.MANUAL, RejectReason.AFTER_CLOSE);
                    markRepeat(e);
                }
                continue;
            }
            if (qType[e] == WAKE) wake(qBidder[e], t);
            else arrive(e, t);
        }
        if (closedAt == 0) closeOnline(early);
    }

    /** Закрытие онлайн-лота: в свой конец или — сбой — на {@code early} мс раньше. */
    private void closeOnline(long early) {
        closedAt = close - early;
        out.add(EventType.CLOSE, closedAt, NONE, 0, 0, 0, -1, -1, RejectReason.NONE, closedAt,
                early > 0 ? LotOutcome.F_EARLY_CLOSE : 0);
        plantedEarly = early > 0;
    }

    /** Сумма ставки из очереди; у повтора сценария — сумма исходной (последней своей ручной). */
    private long repeatAmount(int e) {
        if ((qFlags[e] & (Q_DUP | Q_SCRIPT)) == (Q_DUP | Q_SCRIPT)) {
            int b = qBidder[e];
            if (qRepeat[e] < 0) {
                if (bLastManual[b] < 0) {
                    throw new IllegalArgumentException("лот " + lot.id() + ": повтор у участника " + bId[b] + " без своей принятой ручной ставки");
                }
                qRepeat[e] = bLastManual[b];
                qAmount[e] = out.amount(bLastManual[b]);
            }
        }
        return qAmount[e];
    }

    /** Пометить последнее событие повтором, если ставка из очереди — повтор. */
    private void markRepeat(int e) {
        if ((qFlags[e] & Q_DUP) != 0 && qRepeat[e] >= 0) out.markRepeat(out.size() - 1, qRepeat[e]);
    }

    private void initialWake(int b, long open) {
        Strategy s = strat(b);
        if (s == Strategy.SCRIPTED) return;
        long w = window;
        long t = switch (s) {
            case EARLY -> {
                double u = unit();
                yield open + (long) (w * 0.5 * u * u);
            }
            case CAUTIOUS, MAX -> open + (long) (w * unit());
            case WAR -> close - Math.min(w - 1, WAR_ENTRY_MIN + expo(WAR_ENTRY_MEAN));
            case SNIPER -> close - Math.min(w - 1, snipeLead());
            case SHILL -> open + (long) (w * (0.15 + 0.55 * unit()));
            case SCRIPTED, BOOK -> throw new IllegalStateException();
        };
        wakeAt(b, Math.max(t, Math.max(open, bPlaced[b])));
    }

    /** Участник смотрит лот и решает. */
    private void wake(int b, long t) {
        bWakes[b]--;
        if (bOut[b] || leader == b) return;
        long ask = ask();
        if (ask > bWill[b]) {
            bOut[b] = true;
            return;
        }
        switch (strat(b)) {
            case SNIPER -> {
                if (close - t > SNIPE_LEAD_MAX) {
                    // лот продлили или рано — снайпер ждёт новых последних секунд
                    wakeAt(b, close - snipeLead());
                    return;
                }
                if (bBids[b] == 0 && chance(SNIPE_MANUAL)) send(b, t, ask, false, between(SNIPE_LAT_MIN, SNIPE_LAT_MAX));
                else send(b, t, bWill[b], true, between(SNIPE_LAT_MIN, SNIPE_LAT_MAX));
            }
            case MAX -> send(b, t, bWill[b], true, between(LAT_MIN, LAT_MAX));
            case SHILL -> {
                if (t >= lot.endAt() - (long) (window * SHILL_STOP)) {
                    bOut[b] = true;
                    return;
                }
                if (leader < 0 || shill(leader)) {
                    // разгонять некого — подождать настоящего соперника
                    if (++bWaits[b] > SHILL_MAX_WAITS) bOut[b] = true;
                    else wakeAt(b, t + 30 * SEC + expo(SHILL_REACT_MEAN));
                    return;
                }
                send(b, t, ask, false, between(LAT_MIN, LAT_MAX));
            }
            case WAR -> {
                if (bBids[b] > 0 && chance(warToMax(b))) send(b, t, bWill[b], true, between(LAT_MIN, LAT_MAX));
                else send(b, t, ask, false, between(LAT_MIN, LAT_MAX));
            }
            case EARLY -> {
                if (bBids[b] > 0 && chance(EARLY_TO_MAX)) send(b, t, bWill[b], true, between(LAT_MIN, LAT_MAX));
                else send(b, t, ask, false, between(LAT_MIN, LAT_MAX));
            }
            case CAUTIOUS -> send(b, t, ask, false, between(LAT_MIN, LAT_MAX));
            case SCRIPTED -> {
            }
        }
    }

    /**
     * Вероятность, что воюющий ставит свой максимум: {@code min(1, K·r⁶)}, {@code r} — цена к его максимуму; в бою после
     * {@link #DUEL_TIRED_BIDS} своих ставок — не меньше {@link #DUEL_TIRED_MAX}.
     */
    private double warToMax(int b) {
        double r = (double) price / bWill[b];
        double r3 = r * r * r;
        double p = Math.min(1.0, WAR_TO_MAX_K * r3 * r3);
        return bBids[b] >= DUEL_TIRED_BIDS && rivalWar() ? Math.max(p, DUEL_TIRED_MAX) : p;
    }

    /** Лидер — воюющий: идёт война. */
    private boolean rivalWar() {
        return leader >= 0 && bStrat[leader] == Strategy.WAR.ordinal();
    }

    /** Бой: воюющего {@code b} перебил другой воюющий, а цена ниже половины его максимума ({@link #DUEL_BELOW}). */
    private boolean duel(int b) {
        return leader != b && rivalWar() && price < DUEL_BELOW * bWill[b];
    }

    /** Отправить ставку: придёт через {@code lat}; иногда — с ошибкой суммы или в гонке с соседом. */
    private void send(int b, long t, long amount, boolean isMax, long lat) {
        byte flags = isMax ? Q_MAX : 0;
        long amt = amount;
        if (!isMax && hasBid && chance(beh.typoShare())) {
            amt = price;
            flags |= Q_TYPO;
        }
        long seen = hasBid ? price : 0;
        long arrive = t + lat;
        push(arrive, rnd.nextLong(), ARRIVE, b, amt, flags, t, seen);
        bFlying[b]++;
        bBids[b]++;
        if (!isMax && (flags & Q_TYPO) == 0
                && chance(arrive >= close - LATE_RACE_WINDOW ? beh.lateRaceShare() : beh.raceShare())) {
            int c = racer(b, amt, t);
            if (c >= 0) {
                push(arrive, rnd.nextLong(), ARRIVE, c, amt, (byte) 0, t, seen);
                bFlying[c]++;
                bBids[c]++;
            }
        }
    }

    /**
     * Кто ещё поставит ту же сумму в ту же миллисекунду: участник со своей стратегией, кому она по силам и чья ставка
     * сейчас не в пути (ждущий своего часа — годится: он увидел лот раньше).
     */
    private int racer(int b, long amount, long t) {
        if (nb < 2) return -1;
        int start = (int) bounded(nb);
        for (int k = 0; k < nb; k++) {
            int c = (start + k) % nb;
            if (c == b || c == leader || bOut[c] || bFlying[c] > 0 || bPlaced[c] > t || bStrat[c] == Strategy.SCRIPTED.ordinal()) continue;
            if (bWill[c] >= amount) return c;
        }
        return -1;
    }

    /** Ставка пришла. */
    private void arrive(int e, long t) {
        int b = qBidder[e];
        byte fl = qFlags[e];
        long x = repeatAmount(e);
        boolean isMax = (fl & Q_MAX) != 0;
        boolean repeat = (fl & Q_DUP) != 0;
        if ((fl & (Q_DUP | Q_SCRIPT)) == 0) bFlying[b]--;
        BidKind kind = isMax ? BidKind.MAX : BidKind.MANUAL;
        long maxField = isMax ? x : 0;
        if (leader == b && (fl & Q_RAISE) != 0) {
            raiseMax(b, t, x);
            return;
        }
        if (leader == b) {
            if (repeat && faults.doubleAccept() && !plantedDouble && ask() <= bMax[b]) {
                // сбой: повтор принят второй раз — ведущий перебивает сам себя на минимальный шаг
                plantedDouble = true;
                acceptedNow = false;
                accept(t, b, ask(), 0, BidKind.MANUAL);
                markRepeat(e);
                leaderMax = price;
                maybeExtend(t);
                return;
            }
            reject(t, b, x, maxField, kind, RejectReason.ALREADY_LEADING);
            markRepeat(e);
            if (repeat && faults.doubleAccept() && !plantedDouble) doubleArmed = false;
            return;
        }
        // заложенный «дважды принятый повтор» не вышел (участника уже перебили) — ждать следующей ставки
        if (repeat && faults.doubleAccept() && !plantedDouble) doubleArmed = false;
        long min = ask();
        if (x < min) {
            RejectReason r = classify(e, b, x, t);
            reject(t, b, x, maxField, kind, r);
            markRepeat(e);
            if (!repeat) afterReject(b, t);
            return;
        }
        int prev = leader;
        acceptedNow = false;
        int own = out.size();
        applyBid(t, b, x, isMax, kind);
        if (repeat && qRepeat[e] >= 0) out.markRepeat(own, qRepeat[e]);
        if (!isMax && !repeat && out.accepted(own)) bLastManual[b] = own;
        if (acceptedNow) maybeExtend(t);
        if (prev >= 0 && prev != leader) onOutbid(prev, t);
        if (leader != b) {
            onOutbid(b, t);
        } else if (!isMax && !repeat) {
            boolean forced = faults.doubleAccept() && !doubleArmed && !plantedDouble && (fl & Q_SCRIPT) == 0;
            if (forced || ((fl & Q_SCRIPT) == 0 && chance(beh.duplicateShare()))) {
                if (forced) doubleArmed = true;
                push(t + between(DUP_MIN, DUP_MAX), rnd.nextLong(), ARRIVE, b, x, Q_DUP, t, price);
                qRepeat[qn - 1] = own;
            }
        }
    }

    /**
     * Ведущий поднимает свой максимум (T4): цена прежняя, событие {@link EventType#MAX_RAISE}; не выше действующего —
     * отказ. Если новый максимум дотянул до резерва, а цена ниже, — автоставка на резерв (T5), как у любого максимума.
     */
    private void raiseMax(int b, long t, long x) {
        boolean up = x > leaderMax;
        out.add(EventType.MAX_RAISE, t, b, bId[b], price, x, bChan[b], BidKind.MAX.ordinal(),
                up ? RejectReason.NONE : RejectReason.BELOW_MINIMUM, 0, (byte) 0);
        if (!up) return;
        leaderMax = x;
        long r = withReserve(price, x);
        if (r > price) {
            acceptedNow = false;
            autoBid(t, b, r);
            maybeExtend(t);
        }
    }

    /**
     * Почему сумма ниже минимальной (T3, T15). Гонка и «цена изменилась» — только если сумму сделала недопустимой
     * принятая ставка <i>другого</i> участника, перед которой сумма ещё проходила: в ту же миллисекунду — проигранная
     * гонка, позже решения участника (пока ставка шла) — цена изменилась. Иначе — ниже минимальной (в том числе когда
     * цену сдвинула своя же ставка или отклонённая чужая). У сценария момент решения — момент приёма.
     */
    private RejectReason classify(int e, int b, long x, long t) {
        if ((qFlags[e] & Q_TYPO) != 0) return RejectReason.BELOW_MINIMUM;
        long from = qSeen[e] < 0 ? t : qDecided[e];
        for (int j = acceptedCount - 1; j >= 0 && aT[j] >= from; j--) {
            if (aB[j] == b || x < aMin[j]) continue;
            return aT[j] == t ? RejectReason.RACE_LOST : RejectReason.PRICE_CHANGED;
        }
        return RejectReason.BELOW_MINIMUM;
    }

    private void maybeExtend(long t) {
        long nc = rules.extendedClose(lot.endAt(), close, t);
        if (nc > close) {
            if (faults.missedExtension() && !plantedMissed) {
                // сбой часов: поздняя ставка принята, а конец не сдвинут
                plantedMissed = true;
                out.flag(out.size() - 1, LotOutcome.F_MISSED_EXT);
                return;
            }
            close = nc;
            extensions++;
            out.add(EventType.EXTEND, t, NONE, 0, 0, 0, -1, -1, RejectReason.NONE, nc, (byte) 0);
        }
    }

    /** Участника перебили: ответит ли и когда. */
    private void onOutbid(int b, long t) {
        if (bOut[b] || bWakes[b] + bFlying[b] > 0) return;
        Strategy s = strat(b);
        if (s == Strategy.SCRIPTED) return;
        if (ask() > bWill[b]) {
            bOut[b] = true;
            return;
        }
        switch (s) {
            case EARLY -> {
                if (bBids[b] >= EARLY_MAX_BIDS || chance(EARLY_GIVE_UP)) bOut[b] = true;
                else wakeAt(b, t + MIN + expo(EARLY_REACT_MEAN));
            }
            case CAUTIOUS -> {
                if (bBids[b] >= CAUTIOUS_MAX_BIDS || chance(CAUTIOUS_GIVE_UP)) bOut[b] = true;
                else wakeAt(b, t + 5 * MIN + expo(CAUTIOUS_REACT_MEAN));
            }
            case WAR -> wakeAt(b, t + (duel(b) ? DUEL_REACT_MIN + expo(DUEL_REACT_MEAN) : WAR_REACT_MIN + expo(WAR_REACT_MEAN)));
            case SHILL -> wakeAt(b, t + 30 * SEC + expo(SHILL_REACT_MEAN));
            case SNIPER -> {
                if (close - t > SNIPE_LEAD_MAX) wakeAt(b, close - snipeLead());
                else wakeAt(b, t + between(300, 2_000));
            }
            case MAX -> bOut[b] = true;
            case SCRIPTED -> {
            }
        }
    }

    /** Ставку отклонили (кроме повтора и опоздания): участник повторит верной суммой, если по силам. */
    private void afterReject(int b, long t) {
        if (bOut[b] || bWakes[b] + bFlying[b] > 0) return;
        Strategy s = strat(b);
        if (s == Strategy.SCRIPTED || s == Strategy.MAX) return;
        if (ask() > bWill[b]) {
            bOut[b] = true;
            return;
        }
        long d = switch (s) {
            case WAR -> between(SEC, 8 * SEC);
            case SNIPER -> between(200, 1_500);
            default -> between(RETRY_MIN, RETRY_MAX);
        };
        wakeAt(b, t + d);
    }

    // ================================================================ живые торги

    private long liveDur(int b) {
        long lo, hi;
        int ch = bChan[b];
        if (ch == CH_PHONE) {
            lo = L_PHONE_MIN;
            hi = L_PHONE_MAX;
        } else if (ch == CH_ONLINE) {
            lo = L_ONLINE_MIN;
            hi = L_ONLINE_MAX;
        } else {
            lo = L_ROOM_MIN;
            hi = L_ROOM_MAX;
        }
        long d = between(lo, hi);
        Strategy s = strat(b);
        if (s == Strategy.CAUTIOUS) d = d * 8 / 5;
        else if (s == Strategy.WAR) d = d * 3 / 5;
        return d;
    }

    private double liveWeight(int b) {
        double w = switch (strat(b)) {
            case WAR, SNIPER -> 3.0;
            case EARLY -> 2.0;
            case SHILL -> 1.6;
            case MAX -> 1.4;
            case CAUTIOUS -> 0.7;
            case SCRIPTED, BOOK -> 0.0;
        };
        int ch = bChan[b];
        return ch == CH_PHONE ? w * L_W_PHONE : (ch == CH_ONLINE ? w * L_W_ONLINE : w * L_W_ROOM);
    }

    /**
     * Выйдет ли участник из торгов на этом шаге (не будучи лидером). Считается по «присутствию» — сумме {@code 1/k} за
     * шаги, где он был среди {@code k} желающих, а не по числу своих ставок: так выход не зависит от весов каналов (кто
     * чаще берёт шаг), а только от того, сколько торгов участник выдержал.
     */
    private boolean dropsOut(int b) {
        return switch (strat(b)) {
            case CAUTIOUS -> bExpo[b] >= 0.5 && chance(0.18);
            case EARLY -> bExpo[b] >= 0.5 && chance(Math.min(0.5, 0.04 * bExpo[b]));
            default -> false;
        };
    }

    /** Ставка от имени продавца — по общим правилам: заочный лидер с запасом ответит автоставкой. */
    private void vendorBid(long t, long amount) {
        applyBid(t, VENDOR, amount, false, BidKind.VENDOR);
    }

    /** Может ли аукционист сейчас взять ставку от имени продавца на {@code ask}. */
    private boolean canVendor(long ask) {
        if (!rules.vendorBids() || !lot.hasReserve() || !hasBid || leader == VENDOR || ask >= lot.reserve()) return false;
        // против заочной с запасом — только если не ровно её максимум (иначе ничья в пользу книги)
        return leaderMax == price || ask != leaderMax;
    }

    private void runLive() {
        long reserve = lot.reserve();
        clock = between(L_OPEN_MIN, L_OPEN_MAX);
        // 1. книга (заочные и страховочные телефонные) — в порядке, в каком поручения оставили; по T5: одна не ниже
        //    резерва открывает лот с резерва
        int nBook = 0;
        for (int b = 0; b < nb; b++) {
            if (!bBook[b]) continue;
            int i = nBook++;
            while (i > 0 && (bPlaced[book[i - 1]] > bPlaced[b])) {
                book[i] = book[i - 1];
                i--;
            }
            book[i] = b;
        }
        boolean first = true;
        for (int k = 0; k < nBook; k++) {
            int a = book[k];
            if (bMax[a] < ask()) continue;
            long t = first ? clock : (clock += between(L_BOOK_MIN, L_BOOK_MAX));
            first = false;
            bBids[a]++;
            applyBid(t, a, bMax[a], true, BidKind.ABSENTEE);
        }
        // 2. открытие от имени продавца
        if (!hasBid && rules.vendorBids() && reserve > opening && chance(beh.vendorOpenShare())) vendorBid(clock, opening);
        // 3. шаги аукциониста
        boolean warned = false;
        for (int round = 0; ; round++) {
            if (round > LIVE_MAX_ROUNDS) throw new IllegalStateException("лот " + lot.id() + ": живые торги не сходятся");
            long ask = ask();
            // кто выходит из торгов на этом шаге (из тех, кому шаг по силам)
            for (int b = 0; b < nb; b++) {
                if (bOut[b] || !bPresent[b] || b == leader || bBook[b] || bStrat[b] == Strategy.SCRIPTED.ordinal()) continue;
                if (bWill[b] >= ask && dropsOut(b)) bOut[b] = true;
            }
            // шаг аукциониста: обычный, полшага (T1а, выше порога) или прыжок на 2–5 шагов (чаще в ценовой войне).
            // Полшага — не против книги: заочный максимум мог бы оказаться между полшага и следующим целым шагом, а
            // книга отвечает целым шагом; не ведущая книга уже исчерпана — после полшага ей отвечать не придётся
            long offer = ask;
            boolean bookLeads = leader >= 0 && bBook[leader];
            if (hasBid && !bookLeads && rules.halfStepFrom() > 0 && price >= rules.halfStepFrom()
                    && chance(beh.liveHalfStepShare())) {
                offer = price + inc.step(price) / 2;
            } else if (hasBid && chance(leader >= 0 && bStrat[leader] == Strategy.WAR.ordinal()
                    ? Math.min(1.0, 2 * beh.liveJumpShare()) : beh.liveJumpShare())) {
                long j = between(2, 5);
                for (long s = 1; s < j; s++) offer = inc.minNext(offer);
            }
            int k = candidates(offer);
            if (k == 0 && offer > ask) {
                // прыжок никому не по силам — обычный шаг
                offer = ask;
                k = candidates(offer);
            }
            for (int j = 0; j < k; j++) bExpo[cand[j]] += 1.0 / k;
            double wsum = 0;
            if (k == 0) {
                if (!hasBid) {
                    // ставок нет: аукционист спрашивает ещё раз — поздние вступают, иначе лот снят
                    boolean late = false;
                    for (int b = 0; b < nb; b++) {
                        if (!bOut[b] && !bPresent[b]) {
                            bPresent[b] = true;
                            late = true;
                        }
                    }
                    if (late) continue;
                    break;
                }
                if (canVendor(ask)) {
                    vendorBid(clock += between(L_VENDOR_MIN, L_VENDOR_MAX), ask);
                    continue;
                }
                if (!warned) {
                    clock += between(L_WARN_MIN, L_WARN_MAX);
                    out.add(EventType.FAIR_WARNING, clock, NONE, 0, 0, 0, -1, -1, RejectReason.NONE, 0, (byte) 0);
                    warned = true;
                    // поздние вступают после предупреждения
                    for (int b = 0; b < nb; b++) if (!bOut[b] && !bPresent[b]) bPresent[b] = true;
                    continue;
                }
                break;
            }
            for (int j = 0; j < k; j++) {
                candW[j] = liveWeight(cand[j]);
                wsum += candW[j];
            }
            int c = cand[k - 1];
            double x = unit() * wsum;
            for (int j = 0; j < k; j++) {
                x -= candW[j];
                if (x < 0) {
                    c = cand[j];
                    break;
                }
            }
            long t = clock += liveDur(c);
            bBids[c]++;
            boolean halfOffer = offer < ask;
            ask = offer;
            if (!halfOffer && bChan[c] == CH_ONLINE && chance(beh.liveLateOnlineShare())) {
                int r = -1;
                int from = (int) bounded(k);
                for (int j = 0; j < k; j++) {
                    int o = cand[(from + j) % k];
                    if (o != c) {
                        r = o;
                        break;
                    }
                }
                if (r >= 0) {
                    // аукционист взял другую ставку (из зала, по телефону или онлайн), эта онлайн-ставка опоздала
                    bBids[r]++;
                    applyBid(t, r, ask, false, BidKind.MANUAL);
                    if (leader >= 0 && leader != VENDOR) warned = false;
                    clock += between(L_LATE_MIN, L_LATE_MAX);
                    reject(clock, c, ask, 0, BidKind.MANUAL, RejectReason.PRICE_CHANGED);
                    continue;
                }
            }
            // гонка табличек — когда ставку берут сразу: не полшага и не против книги (ответ книги встал бы между ними)
            boolean bookAnswers = leader >= 0 && bBook[leader] && leaderMax >= ask;
            applyBid(t, c, ask, false, BidKind.MANUAL);
            if (k >= 2 && !halfOffer && !bookAnswers && chance(beh.liveRaceShare())) {
                // две таблички одновременно: аукционист взял одну
                int o = cand[(int) bounded(k)];
                if (o == c) o = cand[(indexOf(cand, k, c) + 1) % k];
                bBids[o]++;
                reject(clock, o, ask, 0, BidKind.MANUAL, RejectReason.RACE_LOST);
            }
            if (leader >= 0) warned = false;
        }
        clock += between(L_HAMMER_MIN, L_HAMMER_MAX);
        boolean sold = hasBid && leader >= 0 && price >= reserve;
        out.add(EventType.HAMMER, clock, sold ? leader : NONE, sold ? bId[leader] : 0, sold ? price : 0, 0, -1, -1,
                RejectReason.NONE, 0, (byte) 0);
        // относительное время → абсолютное, в пределах слота
        long start = lot.startAt();
        long avail = lot.endAt() - start - 1;
        long total = clock;
        int n = out.size();
        for (int i = 0; i < n; i++) {
            long rel = out.time(i);
            long abs = total > avail ? start + Math.multiplyExact(rel, avail) / total : start + rel;
            out.setTime(i, abs);
        }
    }

    /** Желающие взять ставку {@code offer}: в зале, не лидер, не из книги, по силам; подставной — против настоящего. */
    private int candidates(long offer) {
        int k = 0;
        for (int b = 0; b < nb; b++) {
            if (bOut[b] || !bPresent[b] || b == leader || bBook[b] || bStrat[b] == Strategy.SCRIPTED.ordinal()) continue;
            if (bWill[b] < offer) continue;
            if (shill(b) && (leader < 0 || shill(leader))) continue;
            cand[k++] = b;
        }
        return k;
    }

    private static int indexOf(int[] a, int n, int v) {
        for (int i = 0; i < n; i++) if (a[i] == v) return i;
        return -1;
    }

    // ================================================================ итог

    private void finish() {
        int n = out.size();
        int bids = 0, accepted = 0, rejected = 0, auto = 0, vendor = 0, shillBids = 0;
        for (int i = 0; i < n; i++) {
            if (out.type(i) != EventType.BID) continue;
            bids++;
            if (out.accepted(i)) {
                accepted++;
                if (out.kind(i).auto()) auto++;
            } else {
                rejected++;
            }
            if (out.bidderIndex(i) == VENDOR) vendor++;
            if (out.hiddenShill(i)) shillBids++;
        }
        int participants = 0;
        for (int b = 0; b < nb; b++) if (bSeen[b]) participants++;
        long reserve = lot.reserve();
        boolean sold = hasBid && leader >= 0 && price >= reserve;
        long end = live ? out.time(n - 1) : closedAt;
        out.summary(sold, sold ? price : 0, hasBid ? price : 0, sold ? leader : NONE, sold ? bId[leader] : 0, end,
                bids, accepted, rejected, auto, vendor, participants, extensions, shillBids, sold && shill(leader));
        out.planted(plantedMissed, plantedEarly, plantedDouble);
    }
}
