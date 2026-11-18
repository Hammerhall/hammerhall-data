package io.hammerhall.data.auc.auction.engine;

import java.util.List;

/**
 * Коды перечислений таблиц торгов ({@code lots}, {@code bids}, {@code lot_events}, {@code item_status_log}) и поручений
 * ({@code bid_orders} — пишет подготовка торгов по редакциям обёртки) — как в схеме выпуска. В компактных
 * строках — индекс в списке ({@code byte}); в TSV — строка. Каналы и виды ставок — коды движка как есть
 * ({@code Channel.code()}, {@code BidKind.code()}: порядок перечислений движка совпадает с порядком здесь).
 */
public final class Codes {
    private Codes() {
    }

    // ---------------------------------------------------------------- bids.reject_reason (0 — принята)
    public static final byte R_NONE = 0, R_BELOW_MINIMUM = 1, R_PRICE_CHANGED = 2, R_RACE_LOST = 3, R_TIED = 4,
            R_ALREADY_LEADING = 5, R_AFTER_CLOSE = 6, R_OFF_INCREMENT = 7, R_OVER_LIMIT = 8, R_OWN_LOT = 9,
            R_NOT_REGISTERED = 10, R_BLOCKED = 11, R_PREMIUM_NOT_APPROVED = 12, R_NOT_OPEN = 13, R_LOT_WITHDRAWN = 14,
            R_DUPLICATE = 15;
    /** Индекс → код; {@code [0]} — пусто (NULL). Первые семь — порядок {@code RejectReason} движка. */
    public static final List<String> REASONS = List.of("", "below_minimum", "price_changed", "race_lost", "tied",
            "already_leading", "after_close", "off_increment", "over_limit", "own_lot", "not_registered", "blocked",
            "premium_not_approved", "not_open", "lot_withdrawn", "duplicate");

    public static byte reason(String code) {
        int i = REASONS.indexOf(code);
        if (i <= 0) throw new IllegalArgumentException("нет причины отказа «" + code + "»");
        return (byte) i;
    }

    // ---------------------------------------------------------------- bids.channel, bids.kind (порядок движка)
    public static final List<String> CHANNELS = List.of("online", "room", "phone", "absentee", "auctioneer");
    public static final byte CH_ONLINE = 0, CH_ROOM = 1, CH_PHONE = 2, CH_ABSENTEE = 3, CH_AUCTIONEER = 4;
    public static final List<String> KINDS = List.of("manual", "max", "auto", "absentee", "vendor");
    public static final byte K_MANUAL = 0, K_MAX = 1, K_AUTO = 2, K_ABSENTEE = 3, K_VENDOR = 4;

    // ---------------------------------------------------------------- lot_events.kind
    public static final List<String> EVENTS = List.of("published", "opened", "reserve_met", "extended",
            "extension_capped", "fair_warning", "sold", "bought_in", "withdrawn", "after_sale");
    public static final byte E_PUBLISHED = 0, E_OPENED = 1, E_RESERVE_MET = 2, E_EXTENDED = 3, E_EXTENSION_CAPPED = 4,
            E_FAIR_WARNING = 5, E_SOLD = 6, E_BOUGHT_IN = 7, E_WITHDRAWN = 8, E_AFTER_SALE = 9;

    // ---------------------------------------------------------------- lots.result
    public static final List<String> RESULTS = List.of("open", "sold", "bought_in", "withdrawn", "sold_after_sale");
    public static final byte RES_OPEN = 0, RES_SOLD = 1, RES_BOUGHT_IN = 2, RES_WITHDRAWN = 3, RES_SOLD_AFTER_SALE = 4;

    // ---------------------------------------------------------------- lots.withdrawn_by
    public static final List<String> WITHDRAWN_BY = List.of("", "consignor", "house");
    public static final byte WB_NONE = 0, WB_CONSIGNOR = 1, WB_HOUSE = 2;

    // ---------------------------------------------------------------- bid_orders (редакции поручений)
    public static final List<String> ORDER_KINDS = List.of("auto", "absentee", "phone");
    public static final byte OK_AUTO = 0, OK_ABSENTEE = 1, OK_PHONE = 2;
    public static final List<String> ORDER_REASONS = List.of("", "raise_leading", "rebid_after_outbid");
    public static final byte OR_NONE = 0, OR_RAISE_LEADING = 1, OR_REBID_AFTER_OUTBID = 2;
    public static final List<String> ORDER_STATUSES = List.of("active", "won", "outbid", "exceeded_limit", "superseded",
            "cancelled", "rejected", "unused");
    public static final byte OS_ACTIVE = 0, OS_WON = 1, OS_OUTBID = 2, OS_EXCEEDED_LIMIT = 3, OS_SUPERSEDED = 4,
            OS_CANCELLED = 5, OS_REJECTED = 6, OS_UNUSED = 7;
    public static final List<String> VIA = List.of("site", "app", "phone", "office", "partner_platform");
    public static final byte VIA_SITE = 0, VIA_APP = 1, VIA_PHONE = 2, VIA_OFFICE = 3, VIA_PARTNER = 4;
    public static final List<String> PHONE_OUTCOMES = List.of("", "connected", "no_answer");
    public static final byte PO_NONE = 0, PO_CONNECTED = 1, PO_NO_ANSWER = 2;

    // ---------------------------------------------------------------- item_status_log.status
    public static final List<String> ITEM_STATUSES = List.of("received", "declined", "accepted", "catalogued", "sold",
            "bought_in", "withdrawn", "after_sale", "buyer_paid", "buyer_default", "released", "reoffer_planned",
            "returned", "resale_received");
    public static final byte IS_RECEIVED = 0, IS_DECLINED = 1, IS_ACCEPTED = 2, IS_CATALOGUED = 3, IS_SOLD = 4,
            IS_BOUGHT_IN = 5, IS_WITHDRAWN = 6, IS_AFTER_SALE = 7, IS_BUYER_PAID = 8, IS_BUYER_DEFAULT = 9,
            IS_RELEASED = 10, IS_REOFFER_PLANNED = 11, IS_RETURNED = 12, IS_RESALE_RECEIVED = 13;

    /** Пустая строка — NULL в строке таблицы. */
    public static String orNull(List<String> codes, int i) {
        String s = codes.get(i);
        return s.isEmpty() ? null : s;
    }
}
