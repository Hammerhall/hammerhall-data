package io.hammerhall.data.auc.site;

import io.hammerhall.data.core.schema.Schema;

import java.util.List;


/**
 * Четыре таблицы модуля {@code site} и коды их перечислений (индексы — коды {@code Rules}). Описание таблиц —
 * в {@code AucSchema}; тест модуля сверяет коды с ним.
 */
public final class SiteTables {
    private SiteTables() {
    }

    public static final String GROUP = "site";
    public static final List<String> TABLES = List.of("watchlist", "sessions", "session_events", "notifications");

    public static final String[] DEVICES = {"desktop", "mobile_web", "app"};
    public static final String[] SOURCES = {"direct", "search", "email", "push", "referral"};
    public static final String[] EVENT_KINDS = {"search", "catalogue_page", "lot_view", "watch_add", "watch_remove",
            "bid_form", "bid_submit", "order_set"};
    public static final String[] VIA = {"site", "app"};
    public static final String[] NOTIFY_KINDS = {"registration_decided", "outbid", "closing_soon", "lot_won",
            "sale_summary", "invoice_issued", "payment_reminder", "payment_received", "invoice_cancelled",
            "deposit_refunded", "settlement_ready", "settlement_paid"};
    public static final String[] CHANNELS = {"email", "push"};
    public static final String[] NOTIFY_STATUS = {"delivered", "failed", "suppressed", "pending"};

    /** Схема набора — одно описание ({@code AucSchema}); модуль пишет четыре её таблицы. */
    public static Schema schema() {
        return io.hammerhall.data.auc.AucSchema.build();
    }
}
