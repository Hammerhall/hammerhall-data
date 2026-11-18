package io.hammerhall.data.auc.bid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Итог торгов лота: события по порядку и сводка. Один объект на поток, переиспользуется от лота к лоту
 * ({@link BidEngine#run} перезаписывает его): события лежат в массивах по полям, без объекта на событие — генератор
 * читает их по индексу {@code 0 … size()-1}. Содержимое живёт до следующего {@code run} с этим объектом.
 *
 * <p>Скрытое для проверки и для модуля набора — в публичные поля событий ({@link BidEvent}) не попадает:
 * подставные ставки ({@link #hiddenShill(int)}, {@link #shillBids()}, {@link #shillWon()}), повторы принятой ставки
 * ({@link #hiddenRepeat(int)}, {@link #repeatOf(int)}) и заложенные сбои ({@link Faults}:
 * {@link #hiddenMissedExtension(int)}, {@link #hiddenEarlyClose(int)}, {@code planted…}).
 */
public final class LotOutcome {
    static final byte F_SHILL = 1, F_REPEAT = 2, F_MISSED_EXT = 4, F_EARLY_CLOSE = 8;
    private static final EventType[] TYPES = EventType.values();
    private static final Channel[] CHANNELS = Channel.values();
    private static final BidKind[] KINDS = BidKind.values();
    private static final RejectReason[] REASONS = RejectReason.values();
    /** Индекс участника у событий без участника. */
    public static final int NONE = -1;
    /** Индекс «участника» у ставок от имени продавца. */
    public static final int VENDOR = -2;

    // ---------------------------------------------------------------- события
    private int size;
    private byte[] type = new byte[64];
    private long[] time = new long[64];
    private int[] bidder = new int[64];
    private long[] bidderId = new long[64];
    private long[] amount = new long[64];
    private long[] max = new long[64];
    private byte[] channel = new byte[64];
    private byte[] kind = new byte[64];
    private byte[] reason = new byte[64];
    private long[] closeAt = new long[64];
    private byte[] flags = new byte[64];
    private int[] repeatOf = new int[64];

    // ---------------------------------------------------------------- сводка
    private long lotId;
    private Mode mode;
    private long opening;
    private boolean sold;
    private long hammer;
    private long highBid;
    private int winner = NONE;
    private long winnerId;
    private long closedAt;
    private int bids, accepted, rejected, autoBids, vendorBids, participants, extensions, shillBids;
    private boolean shillWon;
    private boolean plantedMissed, plantedEarly, plantedDouble;

    void reset(long lotId, Mode mode, long opening) {
        this.size = 0;
        this.lotId = lotId;
        this.mode = mode;
        this.opening = opening;
        this.sold = false;
        this.hammer = 0;
        this.highBid = 0;
        this.winner = NONE;
        this.winnerId = 0;
        this.closedAt = 0;
        this.bids = accepted = rejected = autoBids = vendorBids = participants = extensions = shillBids = 0;
        this.shillWon = false;
        this.plantedMissed = plantedEarly = plantedDouble = false;
    }

    private void grow() {
        int n = type.length * 2;
        type = Arrays.copyOf(type, n);
        time = Arrays.copyOf(time, n);
        bidder = Arrays.copyOf(bidder, n);
        bidderId = Arrays.copyOf(bidderId, n);
        amount = Arrays.copyOf(amount, n);
        max = Arrays.copyOf(max, n);
        channel = Arrays.copyOf(channel, n);
        kind = Arrays.copyOf(kind, n);
        reason = Arrays.copyOf(reason, n);
        closeAt = Arrays.copyOf(closeAt, n);
        flags = Arrays.copyOf(flags, n);
        repeatOf = Arrays.copyOf(repeatOf, n);
    }

    /** Дописать событие; {@code ch}, {@code k} — порядковые номера перечислений или -1. */
    void add(EventType t, long at, int b, long bid, long amt, long mx, int ch, int k, RejectReason r, long close, byte f) {
        if (size == type.length) grow();
        int i = size++;
        type[i] = (byte) t.ordinal();
        time[i] = at;
        bidder[i] = b;
        bidderId[i] = bid;
        amount[i] = amt;
        max[i] = mx;
        channel[i] = (byte) ch;
        kind[i] = (byte) k;
        reason[i] = (byte) r.ordinal();
        closeAt[i] = close;
        flags[i] = f;
        repeatOf[i] = -1;
    }

    /** Скрытая метка на событие. */
    void flag(int i, byte f) {
        flags[i] |= f;
    }

    /** Событие {@code i} — повтор принятой ставки {@code of}. */
    void markRepeat(int i, int of) {
        flags[i] |= F_REPEAT;
        repeatOf[i] = of;
    }

    void planted(boolean missed, boolean early, boolean twice) {
        plantedMissed = missed;
        plantedEarly = early;
        plantedDouble = twice;
    }

    /** Поправить время события (живые торги: из относительного в абсолютное). */
    void setTime(int i, long at) {
        time[i] = at;
    }

    void summary(boolean sold, long hammer, long highBid, int winner, long winnerId, long closedAt, int bids,
                 int accepted, int rejected, int autoBids, int vendorBids, int participants, int extensions,
                 int shillBids, boolean shillWon) {
        this.sold = sold;
        this.hammer = hammer;
        this.highBid = highBid;
        this.winner = winner;
        this.winnerId = winnerId;
        this.closedAt = closedAt;
        this.bids = bids;
        this.accepted = accepted;
        this.rejected = rejected;
        this.autoBids = autoBids;
        this.vendorBids = vendorBids;
        this.participants = participants;
        this.extensions = extensions;
        this.shillBids = shillBids;
        this.shillWon = shillWon;
    }

    // ---------------------------------------------------------------- события: чтение

    /** Число событий. */
    public int size() {
        return size;
    }

    /** Порядковый номер события в лоте: 1, 2, … (порядок приёма и обработки). */
    public int seq(int i) {
        check(i);
        return i + 1;
    }

    public EventType type(int i) {
        check(i);
        return TYPES[type[i]];
    }

    /** Момент, миллисекунды Unix (UTC). */
    public long time(int i) {
        check(i);
        return time[i];
    }

    /** Индекс участника в списке участников лота; {@link #NONE} — нет, {@link #VENDOR} — от имени продавца. */
    public int bidderIndex(int i) {
        check(i);
        return bidder[i];
    }

    /** Ключ участника ({@link Bidder#id()}); 0 — нет участника. */
    public long bidderId(int i) {
        check(i);
        return bidderId[i];
    }

    /** Сумма ставки, копейки; у молотка — цена молотка (0 — не продан). */
    public long amount(int i) {
        check(i);
        return amount[i];
    }

    /** Максимум участника (автоставка, поставленный максимум, заочная; у {@link EventType#MAX_RAISE} — новый); иначе 0. */
    public long maxAmount(int i) {
        check(i);
        return max[i];
    }

    /** Канал ставки; у прочих событий — {@code null}. */
    public Channel channel(int i) {
        check(i);
        return channel[i] < 0 ? null : CHANNELS[channel[i]];
    }

    /** Вид ставки; у прочих событий — {@code null}. */
    public BidKind kind(int i) {
        check(i);
        return kind[i] < 0 ? null : KINDS[kind[i]];
    }

    /** Ставка принята; у {@link EventType#MAX_RAISE} — новая редакция максимума применена; у прочих — {@code false}. */
    public boolean accepted(int i) {
        check(i);
        return (type[i] == EventType.BID.ordinal() || type[i] == EventType.MAX_RAISE.ordinal()) && reason[i] == 0;
    }

    public RejectReason reason(int i) {
        check(i);
        return REASONS[reason[i]];
    }

    /** Продление и закрытие — время закрытия лота; иначе 0. */
    public long closeAt(int i) {
        check(i);
        return closeAt[i];
    }

    /** Скрытая метка: ставка подставного участника. В публичные поля событий не входит. */
    public boolean hiddenShill(int i) {
        check(i);
        return (flags[i] & F_SHILL) != 0;
    }

    /**
     * Скрытая метка: повтор принятой ручной ставки тем же запросом (двойное нажатие, повтор после обрыва). Обычно
     * отклонён ({@link RejectReason#ALREADY_LEADING} или ниже минимальной) — в данных причина «повтор» и тот же ключ
     * запроса, что у исходной; принят — заложенный сбой {@link Faults#doubleAccept()}.
     */
    public boolean hiddenRepeat(int i) {
        check(i);
        return (flags[i] & F_REPEAT) != 0;
    }

    /** Индекс исходной принятой ставки повтора ({@link #hiddenRepeat}); иначе -1. */
    public int repeatOf(int i) {
        check(i);
        return repeatOf[i];
    }

    /** Скрытая метка сбоя: принятая поздняя ставка, которая по правилу продлевала лот, но продления не было. */
    public boolean hiddenMissedExtension(int i) {
        check(i);
        return (flags[i] & F_MISSED_EXT) != 0;
    }

    /** Скрытая метка сбоя: закрытие лота раньше его конца ({@link Faults#earlyCloseMs()}). */
    public boolean hiddenEarlyClose(int i) {
        check(i);
        return (flags[i] & F_EARLY_CLOSE) != 0;
    }

    private void check(int i) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i + " из " + size);
    }

    /** Событие объектом (публичные поля). */
    public BidEvent event(int i) {
        check(i);
        return new BidEvent(i + 1, TYPES[type[i]], time[i], bidderId[i], channel(i), kind(i), amount[i], max[i],
                accepted(i), REASONS[reason[i]], closeAt[i]);
    }

    /** Все события объектами — для тестов и отладки (в горячем пути не нужно). */
    public List<BidEvent> events() {
        List<BidEvent> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) out.add(event(i));
        return out;
    }

    // ---------------------------------------------------------------- сводка: чтение

    public long lotId() {
        return lotId;
    }

    public Mode mode() {
        return mode;
    }

    /** Стартовая цена на сетке шагов — с неё допустима первая ставка. */
    public long opening() {
        return opening;
    }

    /** Продан: есть ставка участника (не от имени продавца), и она не ниже резерва. */
    public boolean sold() {
        return sold;
    }

    /** Цена молотка, копейки; 0 — не продан. */
    public long hammerPrice() {
        return hammer;
    }

    /** Наибольшая принятая ставка (и у непроданного: «не дошли до резерва»); 0 — ставок не было. */
    public long highBid() {
        return highBid;
    }

    /** Индекс победителя в списке участников; {@link #NONE} — не продан. */
    public int winnerIndex() {
        return winner;
    }

    /** Ключ победителя; 0 — не продан. */
    public long winnerId() {
        return winnerId;
    }

    /** Онлайн — окончательное закрытие (с продлениями); живые — удар молотка. */
    public long closedAt() {
        return closedAt;
    }

    /** Событий-ставок всего: принятые и отклонённые, автоставки, от имени продавца. */
    public int bids() {
        return bids;
    }

    public int acceptedBids() {
        return accepted;
    }

    public int rejectedBids() {
        return rejected;
    }

    /** Принятых автоставок и исполненных заочных ({@link BidKind#auto()}). */
    public int autoBids() {
        return autoBids;
    }

    /** Ставок от имени продавца. */
    public int vendorBids() {
        return vendorBids;
    }

    /** Участников, сделавших хотя бы одну ставку (принятую или отклонённую). */
    public int participants() {
        return participants;
    }

    public int extensions() {
        return extensions;
    }

    /** Скрыто: ставок подставного участника. */
    public int shillBids() {
        return shillBids;
    }

    /** Скрыто: лот достался подставному участнику. */
    public boolean shillWon() {
        return shillWon;
    }

    /** Заложен ли сбой «пропущенное продление» (нашлась поздняя ставка). */
    public boolean plantedMissedExtension() {
        return plantedMissed;
    }

    /** Заложен ли сбой «ранний конец». */
    public boolean plantedEarlyClose() {
        return plantedEarly;
    }

    /** Заложен ли сбой «повтор принят дважды» (нашлась принятая ручная ставка, повтор пришёл, пока участник вёл). */
    public boolean plantedDoubleAccept() {
        return plantedDouble;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("лот ").append(lotId).append(' ').append(mode == null ? "?" : mode.code()).append(": ")
                .append(sold ? "продан за " + io.hammerhall.data.core.money.Money.format(hammer) + " участнику " + winnerId
                        : "не продан" + (highBid > 0 ? " (наибольшая " + io.hammerhall.data.core.money.Money.format(highBid) + ")" : ""))
                .append(", ставок ").append(bids).append(" (принято ").append(accepted).append("), участников ")
                .append(participants).append(", продлений ").append(extensions).append('\n');
        for (int i = 0; i < size; i++) {
            sb.append("  ").append(event(i));
            if (hiddenShill(i)) sb.append("  [подставная]");
            if (hiddenRepeat(i)) sb.append("  [повтор #").append(repeatOf[i] + 1).append(']');
            if (hiddenMissedExtension(i)) sb.append("  [сбой: без продления]");
            if (hiddenEarlyClose(i)) sb.append("  [сбой: ранний конец]");
            sb.append('\n');
        }
        return sb.toString();
    }
}
