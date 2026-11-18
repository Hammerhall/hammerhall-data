package io.hammerhall.data.auc.world;

/**
 * Шесть отделов дома: {@code id} в таблице {@code departments} = {@code ordinal() + 1}, код — поле
 * {@code departments.code}. Названия — в {@link HouseRules.Department}.
 */
public enum Dept {
    PAINTING("painting"),
    JEWELRY("jewelry"),
    COINS("coins"),
    BOOKS("books"),
    DECORATIVE("decorative"),
    COLLECTIBLES("collectibles");

    private static final Dept[] ALL = values();
    private final String code;

    Dept(String code) {
        this.code = code;
    }

    /** Код для данных ({@code departments.code}). */
    public String code() {
        return code;
    }

    /** {@code departments.id}: 1–6. */
    public int id() {
        return ordinal() + 1;
    }

    public static Dept byId(int id) {
        if (id < 1 || id > ALL.length) throw new IllegalArgumentException("нет отдела " + id);
        return ALL[id - 1];
    }

    public static Dept byCode(String code) {
        for (Dept d : ALL) if (d.code.equals(code)) return d;
        throw new IllegalArgumentException("нет отдела " + code);
    }

    public static int count() {
        return ALL.length;
    }
}
