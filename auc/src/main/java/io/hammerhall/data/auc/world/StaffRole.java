package io.hammerhall.data.auc.world;

/** Роль сотрудника ({@code staff.role}). */
public enum StaffRole {
    /** Специалист отдела: приём вещей, каталог. */
    SPECIALIST("specialist"),
    /** Руководитель отдела. */
    HEAD_OF_DEPARTMENT("head_of_department"),
    /** Аукционист: ведёт живые торги, ставки из книги и за продавца. */
    AUCTIONEER("auctioneer"),
    /** Сотрудник на телефонной линии. */
    PHONE_CLERK("phone_clerk"),
    /** Работа с клиентами: регистрации на живые торги. */
    CLIENT_SERVICE("client_service"),
    /** Бухгалтер. */
    ACCOUNTANT("accountant"),
    /** Администратор. */
    ADMINISTRATOR("administrator");

    private final String code;

    StaffRole(String code) {
        this.code = code;
    }

    /** Значение для данных. */
    public String code() {
        return code;
    }

    public static StaffRole byCode(String code) {
        for (StaffRole v : values()) if (v.code.equals(code)) return v;
        throw new IllegalArgumentException("StaffRole: нет кода " + code);
    }
}
