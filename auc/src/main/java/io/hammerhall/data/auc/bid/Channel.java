package io.hammerhall.data.auc.bid;

/** Канал ставки: откуда она пришла. */
public enum Channel {
    /** Через сайт или приложение (онлайн-торги и онлайн-участник живых торгов). */
    ONLINE("online"),
    /** Из зала, по номеру таблички. */
    ROOM("room"),
    /** По телефону — через сотрудника дома у телефона. */
    PHONE("phone"),
    /** Заочная ставка: участник оставил максимум до торгов, аукционист исполняет её как автоставку. */
    ABSENTEE("absentee"),
    /** Сам аукционист: ставки от имени продавца (до резерва). Участнику не назначается. */
    AUCTIONEER("auctioneer");

    private final String code;

    Channel(String code) {
        this.code = code;
    }

    /** Значение для данных. */
    public String code() {
        return code;
    }
}
