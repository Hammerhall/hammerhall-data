package io.hammerhall.data.auc.bid;

/**
 * Правила торгов дома — то, что проверяется инвариантами ({@link Invariants}). В наборе правила собираются
 * {@link #house(IncrementTable)} из таблиц выпуска (шаги — {@code increment_bands}); {@link #standard()} — только для
 * тестов движка. Номера T1–T16 — сквозные номера правил торгов дома.
 *
 * @param increments    таблица шага ставки (T1)
 * @param softWindowMs  мягкое закрытие онлайн-лота: принятая ставка в последние {@code N} мс до текущего конца
 *                      продлевает лот (T10)
 * @param extendMs      на сколько продлевается ({@code M} мс)
 * @param extension     от чего отсчитывается продление
 * @param maxExtendMs   потолок продлений по времени: конец лота — не позже планового + столько мс; 0 — без потолка
 * @param vendorBids    живые торги: аукционист может вести ставки от имени продавца — только ниже резерва (T9)
 * @param proxyToReserve автоставка дотягивает до резерва (T5, T7): если максимум лидера не ниже резерва, а цена по
 *                      правилу «второй максимум + шаг» — ниже, цена = резерв (приведённый к сетке вверх). Так же —
 *                      поставленный максимум без соперника и книга заочных ставок в зале: одна заочная не ниже
 *                      резерва открывает лот с резерва
 * @param halfStepFrom  живые торги: ведущий может принять полшага ({@code цена + шаг/2}) при текущей цене от этой
 *                      суммы, копейки; после полшага сетка идёт от принятой суммы (T1а); 0 — полшага нет
 * @param selfCheck     проверять инварианты после каждого лота ({@link Invariants#check}); провал — исключение
 */
public record AuctionRules(IncrementTable increments, long softWindowMs, long extendMs, Extension extension,
                           long maxExtendMs, boolean vendorBids, boolean proxyToReserve, long halfStepFrom,
                           boolean selfCheck) {

    /** От чего отсчитывается продление онлайн-лота. */
    public enum Extension {
        /** Новый конец — через {@code M} после поздней ставки (если это позже прежнего): лот закрывается, когда M прошло без ставок. */
        FROM_BID("from_bid"),
        /** Новый конец — прежний плюс {@code M} за каждую позднюю ставку. */
        FROM_CLOSE("from_close");

        private final String code;

        Extension(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Мягкое закрытие дома: окно 2 мин, продление 2 мин от ставки, потолок 60 мин сверх плана. */
    public static final long HOUSE_WINDOW_MS = 120_000, HOUSE_EXTEND_MS = 120_000, HOUSE_CAP_MS = 3_600_000;
    /** Полшага в зале — выше 100 000 ₽. */
    public static final long HOUSE_HALF_STEP_FROM = 100_000_00L;

    public AuctionRules {
        if (increments == null) throw new IllegalArgumentException("нет таблицы шагов");
        if (softWindowMs < 0 || extendMs < 0) throw new IllegalArgumentException("окно " + softWindowMs + ", продление " + extendMs);
        if (softWindowMs > 0 && extendMs == 0) throw new IllegalArgumentException("окно мягкого закрытия без продления");
        if (extension == null) throw new IllegalArgumentException("нет правила продления");
        if (maxExtendMs < 0) throw new IllegalArgumentException("потолок продлений " + maxExtendMs);
        if (halfStepFrom < 0) throw new IllegalArgumentException("полшага от " + halfStepFrom);
    }

    /**
     * Правила дома с таблицей шагов снаружи (в наборе — {@code increment_bands}): мягкое закрытие 2 мин / 2 мин от
     * ставки, потолок 60 мин сверх плана (T10), ставки от имени продавца ниже резерва, автоставка до резерва, полшага
     * в зале от 100 000 ₽, самопроверка.
     */
    public static AuctionRules house(IncrementTable increments) {
        return new AuctionRules(increments, HOUSE_WINDOW_MS, HOUSE_EXTEND_MS, Extension.FROM_BID, HOUSE_CAP_MS, true, true,
                HOUSE_HALF_STEP_FROM, true);
    }

    /** Для тестов движка: правила дома с тестовой таблицей шагов {@link IncrementTable#standard()}. */
    public static AuctionRules standard() {
        return house(IncrementTable.standard());
    }

    public AuctionRules withIncrements(IncrementTable t) {
        return new AuctionRules(t, softWindowMs, extendMs, extension, maxExtendMs, vendorBids, proxyToReserve, halfStepFrom, selfCheck);
    }

    /** Мягкое закрытие: окно {@code windowMs}, продление {@code extendByMs}; 0 и 0 — без мягкого закрытия. */
    public AuctionRules withSoftClose(long windowMs, long extendByMs, Extension mode) {
        return new AuctionRules(increments, windowMs, extendByMs, mode, maxExtendMs, vendorBids, proxyToReserve, halfStepFrom, selfCheck);
    }

    /** Потолок продлений: конец — не позже планового + {@code ms}; 0 — без потолка. */
    public AuctionRules withMaxExtendMs(long ms) {
        return new AuctionRules(increments, softWindowMs, extendMs, extension, ms, vendorBids, proxyToReserve, halfStepFrom, selfCheck);
    }

    public AuctionRules withVendorBids(boolean on) {
        return new AuctionRules(increments, softWindowMs, extendMs, extension, maxExtendMs, on, proxyToReserve, halfStepFrom, selfCheck);
    }

    public AuctionRules withProxyToReserve(boolean on) {
        return new AuctionRules(increments, softWindowMs, extendMs, extension, maxExtendMs, vendorBids, on, halfStepFrom, selfCheck);
    }

    /** Полшага в зале от суммы {@code fromKop}; 0 — без полшага. */
    public AuctionRules withHalfStepFrom(long fromKop) {
        return new AuctionRules(increments, softWindowMs, extendMs, extension, maxExtendMs, vendorBids, proxyToReserve, fromKop, selfCheck);
    }

    public AuctionRules withSelfCheck(boolean on) {
        return new AuctionRules(increments, softWindowMs, extendMs, extension, maxExtendMs, vendorBids, proxyToReserve, halfStepFrom, on);
    }

    /** Есть ли мягкое закрытие. */
    public boolean softClose() {
        return softWindowMs > 0;
    }

    /**
     * Новый конец лота после принятой ставки в момент {@code t} при текущем конце {@code close} и плановом
     * {@code planned} (T10): {@code min(max(C, t + M), P + потолок)} по {@link Extension#FROM_BID} или
     * {@code min(C + M, P + потолок)} по {@link Extension#FROM_CLOSE}. Равен {@code close} — продления нет (ставка вне
     * окна, новый конец не позже прежнего или потолок достигнут).
     */
    public long extendedClose(long planned, long close, long t) {
        if (!softClose() || t < close - softWindowMs || t >= close) return close;
        long nc = extension == Extension.FROM_BID ? Math.max(close, t + extendMs) : close + extendMs;
        if (maxExtendMs > 0) nc = Math.min(nc, planned + maxExtendMs);
        return Math.max(nc, close);
    }

    /** Стартовая цена лота на сетке шагов (вверх). */
    public long opening(Lot lot) {
        return increments.ceil(lot.startPrice());
    }

    /** Резерв лота на сетке шагов (вверх); 0 — без резерва. */
    public long reserveOnGrid(Lot lot) {
        return lot.reserve() > 0 ? increments.ceil(lot.reserve()) : 0;
    }
}
