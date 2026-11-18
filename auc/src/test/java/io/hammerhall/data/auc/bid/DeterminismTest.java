package io.hammerhall.data.auc.bid;

import io.hammerhall.data.core.random.RandomStreams;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.LongFunction;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Детерминизм: тот же вход → побайтно те же события; переиспользование движка и итога не протекает из лота в лот;
 * движок берёт из генератора только {@code nextLong()}; отпечаток событий заморожен — смена логики видна сразу.
 */
class DeterminismTest {
    static final int LOTS = 5_000;
    /**
     * SHA-256 событий и итогов {@value #LOTS} случайных лотов ({@link RandomLots}, {@code SplittableRandom(ключ)}).
     * Меняется только вместе с логикой движка — тогда и версия генератора набора.
     */
    static final String GOLDEN = "24123e2e6c1666edf80e5d623c7f577e138128e235c4a4a9448c97fd45b81c16";
    /** То же для онлайн- и живых лотов с подъёмами максимума ({@link RandomLots#raises}) — 2 000 лотов. */
    static final String GOLDEN_RAISES = "25c6256022270af8568004ffec7aaa6bd41332d88492d7d46f1f8ca8048ec472";

    /** Все поля событий и сводки — в байты, в фиксированном порядке. */
    static void feed(MessageDigest md, LotOutcome o) {
        ByteBuffer bb = ByteBuffer.allocate(128);
        for (int i = 0; i < o.size(); i++) {
            bb.clear();
            bb.put((byte) o.type(i).ordinal()).putLong(o.time(i)).putLong(o.bidderId(i)).putLong(o.amount(i))
                    .putLong(o.maxAmount(i)).put((byte) (o.channel(i) == null ? -1 : o.channel(i).ordinal()))
                    .put((byte) (o.kind(i) == null ? -1 : o.kind(i).ordinal())).put((byte) o.reason(i).ordinal())
                    .putLong(o.closeAt(i)).put((byte) (o.hiddenShill(i) ? 1 : 0));
            md.update(bb.array(), 0, bb.position());
        }
        bb.clear();
        bb.put((byte) (o.sold() ? 1 : 0)).putLong(o.hammerPrice()).putLong(o.highBid()).putLong(o.winnerId())
                .putLong(o.closedAt()).putInt(o.bids()).putInt(o.acceptedBids()).putInt(o.participants())
                .putInt(o.extensions()).putInt(o.vendorBids()).putInt(o.shillBids());
        md.update(bb.array(), 0, bb.position());
    }

    static String digest(BidEngine e, int from, int lots, LongFunction<RandomGenerator> rnd) {
        return digest(e, from, lots, rnd, false);
    }

    static String digest(BidEngine e, int from, int lots, LongFunction<RandomGenerator> rnd, boolean raises) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            LotOutcome o = new LotOutcome();
            for (int k = from; k < from + lots; k++) {
                RandomLots.Case c = RandomLots.make(k, e.rules().increments());
                e.run(c.lot(), c.bidders(), raises ? RandomLots.raises(c, k) : List.of(), Faults.NONE, rnd.apply(k), o);
                feed(md, o);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static BidEngine engine() {
        return new BidEngine(AuctionRules.standard(), Behavior.standard());
    }

    @Test
    void sameInputSameEvents() {
        String a = digest(engine(), 1, LOTS, SplittableRandom::new);
        String b = digest(engine(), 1, LOTS, SplittableRandom::new);
        assertEquals(a, b);
        RandomLots.Case c = RandomLots.make(77, IncrementTable.standard());
        List<BidEvent> x = engine().run(c.lot(), c.bidders(), new SplittableRandom(77), new LotOutcome()).events();
        List<BidEvent> y = engine().run(c.lot(), c.bidders(), new SplittableRandom(77), new LotOutcome()).events();
        assertEquals(x, y);
    }

    @Test
    void frozenFingerprint() {
        String d = digest(engine(), 1, LOTS, SplittableRandom::new);
        if (GOLDEN.isEmpty()) System.out.println("отпечаток событий: " + d);
        else assertEquals(GOLDEN, d, "события движка изменились — новая логика: обновить отпечаток и версию генератора");
    }

    @Test
    void frozenFingerprintWithRaises() {
        String a = digest(engine(), 1, 2_000, SplittableRandom::new, true);
        assertEquals(a, digest(engine(), 1, 2_000, SplittableRandom::new, true));
        assertNotEquals(digest(engine(), 1, 2_000, SplittableRandom::new, false), a, "подъёмы меняют торги");
        if (GOLDEN_RAISES.isEmpty()) System.out.println("отпечаток с подъёмами: " + a);
        else assertEquals(GOLDEN_RAISES, a, "события движка изменились — обновить отпечаток и версию генератора");
    }

    /** Лот, сыгранный после сотни других тем же движком и итогом, — тот же, что на свежих. */
    @Test
    void reuseDoesNotLeak() {
        BidEngine e = engine();
        String warm = digest(e, 1, 300, SplittableRandom::new);
        assertNotEquals("", warm);
        String after = digest(e, 200, 100, SplittableRandom::new);
        String fresh = digest(engine(), 200, 100, SplittableRandom::new);
        assertEquals(fresh, after);
    }

    /** Поток ядра (SplitMix64 по ключу лота) годится так же: свой отпечаток, тоже повторяемый. */
    @Test
    void coreStreamWorksToo() {
        RandomStreams.Stream s = new RandomStreams(20251001L).stream("auc.bids");
        String a = digest(engine(), 1, 1_000, k -> s.at(k)::nextLong);
        String b = digest(engine(), 1, 1_000, k -> s.at(k)::nextLong);
        assertEquals(a, b);
        assertNotEquals(digest(engine(), 1, 1_000, SplittableRandom::new), a);
    }

    /** Генератор, у которого есть только {@code nextLong()}: всё прочее — ошибка. */
    static final class OnlyNextLong implements RandomGenerator {
        private final SplittableRandom r;

        OnlyNextLong(long seed) {
            r = new SplittableRandom(seed);
        }

        @Override
        public long nextLong() {
            return r.nextLong();
        }

        private static UnsupportedOperationException no() {
            return new UnsupportedOperationException("движок взял у генератора не nextLong()");
        }

        @Override
        public int nextInt() {
            throw no();
        }

        @Override
        public int nextInt(int bound) {
            throw no();
        }

        @Override
        public int nextInt(int origin, int bound) {
            throw no();
        }

        @Override
        public long nextLong(long bound) {
            throw no();
        }

        @Override
        public long nextLong(long origin, long bound) {
            throw no();
        }

        @Override
        public double nextDouble() {
            throw no();
        }

        @Override
        public double nextDouble(double bound) {
            throw no();
        }

        @Override
        public double nextDouble(double origin, double bound) {
            throw no();
        }

        @Override
        public float nextFloat() {
            throw no();
        }

        @Override
        public boolean nextBoolean() {
            throw no();
        }

        @Override
        public double nextGaussian() {
            throw no();
        }

        @Override
        public double nextExponential() {
            throw no();
        }
    }

    @Test
    void onlyNextLongIsUsed() {
        // тот же поток 64-битных чисел — те же события, через какой бы интерфейс он ни пришёл
        assertEquals(digest(engine(), 1, 2_000, SplittableRandom::new), digest(engine(), 1, 2_000, OnlyNextLong::new));
    }
}
