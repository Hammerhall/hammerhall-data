package io.hammerhall.data.auc.auction.engine;

import io.hammerhall.data.auc.bid.Faults;
import io.hammerhall.data.core.random.RandomStreams;

/**
 * Какие онлайн-лоты получают заложенный сбой движка ({@code double_bid}, {@code missed_extension},
 * {@code early_close} — «десятки случаев»): решение — от естественного ключа лота (хеш потока), не от порядка и не от
 * {@code id}. Сбой случается, только если в лоте нашёлся подходящий случай — движок сообщает {@code planted…()}; в
 * {@code Planted} попадают ровно такие лоты.
 */
public final class FaultPlan {
    private final RandomStreams.Stream stream;
    private final double rate;

    /** {@code rate} — доля онлайн-лотов, которым назначается сбой (каждому — один из трёх). */
    public FaultPlan(RandomStreams.Stream stream, double rate) {
        this.stream = stream;
        this.rate = rate;
    }

    /**
     * Доля под профиль: в {@code full} (≈ 1,3 млн онлайн-лотов) — ≈ 50 назначений (по ≈ 17 каждого вида, заложится
     * меньше — не у каждого лота есть поздняя или повторная ставка), в {@code small} — единицы.
     */
    public static double rateFor(double scale) {
        return 4.0e-5 / StrictMath.sqrt(scale);
    }

    public Faults forLot(long lotKey) {
        if (stream.unit(lotKey) >= rate) return Faults.NONE;
        long h = stream.hash(lotKey ^ 0x5bd1e9955bd1e995L);
        int k = (int) Long.remainderUnsigned(h, 3);
        return switch (k) {
            case 0 -> Faults.ofDoubleAccept();
            case 1 -> Faults.ofMissedExtension();
            default -> Faults.ofEarlyClose(1_000 + Long.remainderUnsigned(h >>> 8, 2_001));
        };
    }
}
