package io.hammerhall.data.auc.people;

import io.hammerhall.data.auc.bid.Strategy;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Упрощённая база клиентов для тестов: сто клиентов, зарегистрированных с 2012 по май 2025 года, с простыми чертами —
 * чтобы тесты модулей собирали {@code small} без настоящей базы. Каждый — покупатель с одним любимым отделом, каждый
 * пятый — комитент, каждый десятый — торговец; связей нет, кроме одного подставного и одной повторной регистрации.
 */
public final class StubClientBase implements ClientBase {
    public static final int SIZE = 100;
    private final int n;
    private final long[] registeredAt;
    private final int[] city;
    private final long[] budget;
    private final long[][] devices;
    private final List<Link> links;

    public StubClientBase(RandomStreams streams, HouseRules house) {
        this(streams, house, SIZE);
    }

    public StubClientBase(RandomStreams streams, HouseRules house, int n) {
        this.n = n;
        registeredAt = new long[n + 1];
        city = new int[n + 1];
        budget = new long[n + 1];
        devices = new long[n + 1][];
        RandomStreams.Stream st = streams.stream("clients.base");
        long from = LocalDate.of(2012, 1, 1).atStartOfDay(HouseRules.ZONE).toEpochSecond();
        long to = LocalDate.of(2025, 5, 31).atStartOfDay(HouseRules.ZONE).toEpochSecond();
        List<HouseRules.City> cities = house.cities();
        int moscow = house.office("msk").cityId();
        for (int id = 1; id <= n; id++) {
            Rng r = st.at(id);
            registeredAt[id] = from + (to - from) * (id - 1) / n + r.nextInt(86_400);
            city[id] = r.chance(0.5) ? moscow : cities.get(r.nextInt(cities.size())).id();
            budget[id] = (long) (r.logNormal(60_000, 1.2) * 100);
            devices[id] = new long[]{r.nextLong(), r.nextLong()};
        }
        // общее устройство подставного и комитента; новая учётная запись неплательщика — с его устройства
        devices[7][0] = devices[5][0];
        devices[n][0] = devices[13][0];
        links = List.of(new Link("reregistration", n, 13, 0, null), new Link("shill", 7, 5, 0, null));
    }

    private static int dept(int id) {
        return (id - 1) % Dept.count() + 1;
    }

    @Override
    public int size() {
        return n;
    }

    @Override
    public int countRegisteredBefore(long at) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int m = (lo + hi + 1) >>> 1;
            if (registeredAt[m] < at) lo = m;
            else hi = m - 1;
        }
        return lo;
    }

    @Override
    public long registeredAt(int id) {
        return registeredAt[id];
    }

    @Override
    public boolean company(int id) {
        return id % 25 == 0;
    }

    @Override
    public boolean institution(int id) {
        return id % 50 == 0;
    }

    @Override
    public boolean dealer(int id) {
        return id % 10 == 3;
    }

    @Override
    public int cityId(int id) {
        return city[id];
    }

    @Override
    public long verifiedAt(int id) {
        return id % 7 == 0 ? 0 : registeredAt[id] + 3_600;
    }

    @Override
    public int birthYear(int id) {
        return company(id) ? 0 : 1950 + id % 50;
    }

    @Override
    public String language(int id) {
        return id % 20 == 0 ? "en" : "ru";
    }

    @Override
    public boolean marketingConsent(int id) {
        return id % 3 != 0;
    }

    @Override
    public boolean analyticsConsent(int id) {
        return id % 4 != 0;
    }

    @Override
    public float interest(int id, Dept d) {
        return d.id() == dept(id) ? 1f : 0f;
    }

    @Override
    public boolean buyer(int id) {
        return true;
    }

    @Override
    public float salesPerYear(int id) {
        return 2 + id % 10;
    }

    @Override
    public long activeUntil(int id) {
        return Long.MAX_VALUE;
    }

    @Override
    public long budgetKop(int id) {
        return budget[id];
    }

    @Override
    public Strategy strategy(int id) {
        return switch (id % 10) {
            case 0 -> Strategy.SNIPER;
            case 1, 2, 3 -> Strategy.MAX;
            case 4 -> Strategy.CAUTIOUS;
            default -> Strategy.EARLY;
        };
    }

    @Override
    public float maxShare(int id) {
        return 0.45f;
    }

    @Override
    public float phoneShare(int id) {
        return 0.1f;
    }

    @Override
    public float roomShare(int id) {
        return 0.15f;
    }

    @Override
    public float payReliability(int id) {
        return serialNonPayer(id) ? 0f : 0.97f;
    }

    @Override
    public float payDelayDays(int id) {
        return 4f;
    }

    @Override
    public boolean serialNonPayer(int id) {
        return id == 13 || id == n;
    }

    @Override
    public boolean consignor(int id) {
        return id % 5 == 0 || dealer(id);
    }

    @Override
    public long otherBlockAt(int id) {
        return 0;
    }

    @Override
    public String otherBlockReason(int id) {
        return null;
    }

    @Override
    public long[] devices(int id) {
        return devices[id].clone();
    }

    @Override
    public List<Link> links() {
        return links;
    }

    @Override
    public int shillFor(int id) {
        return id == 7 ? 5 : 0;
    }

    @Override
    public int ring(int id) {
        return 0;
    }

    @Override
    public int sampleBuyer(Dept d, long before, Rng r) {
        int k = countRegisteredBefore(before);
        List<Integer> l = new ArrayList<>();
        for (int id = 1; id <= k; id++) if (dept(id) == d.id()) l.add(id);
        return l.isEmpty() ? 0 : l.get(r.nextInt(l.size()));
    }
}
