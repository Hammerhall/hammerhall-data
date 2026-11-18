package io.hammerhall.data.auc.bid;

/** Вид торгов лота. */
public enum Mode {
    /** Онлайн-торги по времени: лот открыт несколько дней, закрывается по часам, поздняя ставка продлевает. */
    ONLINE("online"),
    /** Живые торги в зале: аукционист ведёт лот в своём слоте, ставки из зала, по телефону, онлайн и заочные. */
    LIVE("live");

    private final String code;

    Mode(String code) {
        this.code = code;
    }

    /** Значение для данных. */
    public String code() {
        return code;
    }
}
