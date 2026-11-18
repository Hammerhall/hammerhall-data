package io.hammerhall.data.auc.world;

import java.time.LocalDate;

/**
 * Естественные ключи сущностей — одно место для всех модулей: от них берётся случайность
 * ({@code ctx.stream(...).at(ключ)}), по ним сущности связываются, пока {@code id} выпуска ещё не выданы. Ключ —
 * {@code long} с полями в битах; каждое поле проверяется на ширину.
 */
public final class Keys {
    private Keys() {
    }

    private static long check(long v, int bits, String what) {
        if (v < 0 || v >= (1L << bits)) throw new IllegalArgumentException(what + " вне ключа: " + v);
        return v;
    }

    /** Вещь: (отдел, сутки приёма по Москве, номер в сутках отдела с 0). */
    public static long item(Dept d, LocalDate day, int no) {
        return (long) d.id() << 56 | check(day.toEpochDay(), 24, "сутки") << 32 | check(no, 32, "номер вещи");
    }

    /** Визит приёма: (офис, сутки, номер визита в сутках офиса с 0). */
    public static long visit(int officeId, LocalDate day, int no) {
        return check(officeId, 8, "офис") << 56 | check(day.toEpochDay(), 24, "сутки") << 32 | check(no, 32, "визит");
    }

    /** Торги: (формат, отдел, год, номер в году с 1) — так их ключ не зависит от периода. */
    public static long sale(Format f, Dept d, int year, int no) {
        return (long) f.ordinal() << 48 | (long) d.id() << 40 | check(year, 16, "год") << 24 | check(no, 24, "номер");
    }

    /** Лот: ({@code sales.id}, номер лота, буква добавленного после публикации: 0 — нет, 1 — «A», …). */
    public static long lot(int saleId, int lotNo, int suffix) {
        return check(saleId, 31, "торги") << 32 | check(lotNo, 28, "номер лота") << 4 | check(suffix, 4, "буква лота");
    }

    public static int lotSale(long lotKey) {
        return (int) (lotKey >>> 32);
    }

    public static int lotNo(long lotKey) {
        return (int) (lotKey >>> 4) & 0x0FFF_FFFF;
    }

    public static int lotSuffix(long lotKey) {
        return (int) (lotKey & 0xF);
    }

    /** Подпись лота в каталоге: «123», «123A». */
    public static String lotLabel(long lotKey) {
        int s = lotSuffix(lotKey);
        return s == 0 ? Integer.toString(lotNo(lotKey)) : lotNo(lotKey) + String.valueOf((char) ('A' + s - 1));
    }

    /**
     * Регистрация — одна на клиента и торги ({@code UNIQUE (sale_id, client_id)}): ({@code sales.id},
     * {@code clients.id}). Этот же ключ — {@code Bidder.id} в движке (всегда больше 0).
     */
    public static long registration(int saleId, int clientId) {
        return check(saleId, 31, "торги") << 32 | check(clientId, 32, "клиент");
    }

    public static int registrationSale(long regKey) {
        return (int) (regKey >>> 32);
    }

    public static int registrationClient(long regKey) {
        return (int) regKey;
    }

    /**
     * Редакция поручения: (торги, номер лота, буква, клиент, редакция с 1). Поля: торги — 14 бит, номер — 14 (≤ 16 383:
     * календарь держит онлайн-каталог ≤ 8 400 лотов), буква — 2, клиент — 24, редакция — 8.
     */
    public static long order(long lotKey, int clientId, int revision) {
        return check(lotSale(lotKey), 14, "торги") << 48 | check(lotNo(lotKey), 14, "номер лота") << 34
                | check(lotSuffix(lotKey), 2, "буква лота") << 32 | check(clientId, 24, "клиент") << 8
                | check(revision, 8, "редакция");
    }

    /** Счёт: (торги, клиент, вид, номер счёта этого вида у клиента в торгах с 0). */
    public static long invoice(int saleId, int clientId, InvoiceKind kind, int no) {
        return check(saleId, 19, "торги") << 44 | check(clientId, 28, "клиент") << 16 | (long) kind.ordinal() << 8
                | check(no, 8, "номер");
    }
}
