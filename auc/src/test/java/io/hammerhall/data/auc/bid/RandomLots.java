package io.hammerhall.data.auc.bid;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.List;

/**
 * Случайные лоты для тестов свойств и замера: вид торгов, стартовая цена, резерв, окно, 0–14 участников с разными
 * стратегиями и каналами, у малой доли лотов — подставной. Лот — функция ключа: тот же ключ — тот же лот (генератор
 * ядра {@link Rng} — алгоритмы зафиксированы в коде, вход тестов не зависит от версии JDK).
 */
final class RandomLots {
    /** 01.03.2026 00:00 UTC — начало окна тестовых лотов. */
    static final long BASE = 1_772_323_200_000L;
    static final long HOUR = 3_600_000L, DAY = 24 * HOUR;

    private RandomLots() {
    }

    record Case(Lot lot, List<Bidder> bidders) {
    }

    /**
     * Подъёмы максимума для онлайн-лота (T4): у 1–2 участников — новая редакция до своего потолка в случайный момент
     * срока. Кому-то она — подъём ведущего, кому-то — новый максимум после перебития, кому-то — отказ (не выше
     * действующего). У живых лотов — пусто.
     */
    static List<Action> raises(Case c, long key) {
        List<Action> out = new ArrayList<>();
        if (c.lot().mode() != Mode.ONLINE || c.bidders().isEmpty()) return out;
        Rng r = new Rng(Rng.mix64(key * 31 + 7));
        int n = 1 + r.nextInt(2);
        long w = c.lot().endAt() - c.lot().startAt();
        for (int i = 0; i < n; i++) {
            int b = r.nextInt(c.bidders().size());
            out.add(Action.raiseMax(c.lot().startAt() + (long) (r.nextDouble() * w), b, c.bidders().get(b).max()));
        }
        return out;
    }

    static final Strategy[] ONLINE_MIX = {Strategy.EARLY, Strategy.EARLY, Strategy.SNIPER, Strategy.WAR, Strategy.CAUTIOUS,
            Strategy.MAX, Strategy.MAX};
    static final Strategy[] LIVE_MIX = {Strategy.EARLY, Strategy.SNIPER, Strategy.WAR, Strategy.CAUTIOUS, Strategy.MAX};

    static Case make(long key, IncrementTable inc) {
        return make(key, inc, -1);
    }

    /** {@code bidders} ≥ 0 — ровно столько участников (для замера), иначе случайно. */
    static Case make(long key, IncrementTable inc, int bidders) {
        Rng r = new Rng(Rng.mix64(key * 0x9e3779b97f4a7c15L + 17));
        boolean live = r.nextInt(10) < 4;
        double rub = 500 * StrictMath.exp(r.nextDouble() * StrictMath.log(10_000));     // 500 ₽ … 5 млн ₽
        long start = Math.max(inc.step(0), inc.floor((long) (rub * 100)));
        long reserve = r.nextInt(10) < 4 ? 0 : inc.floor((long) (start * (1.0 + r.nextDouble())));
        Lot lot;
        if (live) {
            long slotStart = BASE + (key % 1000) * DAY + 10 * HOUR;
            int perHour = 50 + r.nextInt(71);
            lot = Lot.live(key, start, reserve, slotStart, slotStart + 3_600_000L / perHour);
        } else {
            long open = BASE + (key % 1000) * DAY;
            long close = open + (3 + r.nextInt(8)) * DAY + r.nextInt(3600) * 1000L;
            lot = Lot.online(key, start, reserve, open, close);
        }
        int n = bidders >= 0 ? bidders : (r.nextInt(10) == 0 ? 0 : 1 + r.nextInt(r.nextInt(10) < 7 ? 7 : 14));
        List<Bidder> out = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            long max = (long) r.logNormal(start * 1.6, 0.55);
            if (max <= 0) max = start;
            long id = key * 100 + i + 1;
            if (live) {
                int c = r.nextInt(20);
                if (c < 4) {
                    out.add(Bidder.absentee(id, max, lot.startAt() - HOUR - (long) (r.nextDouble() * 10 * DAY)));
                } else if (c == 14) {
                    out.add(Bidder.phoneStandIn(id, max, lot.startAt() - DAY - (long) (r.nextDouble() * 2 * DAY)));
                } else {
                    Channel ch = c < 12 ? Channel.ROOM : (c < 15 ? Channel.PHONE : Channel.ONLINE);
                    out.add(Bidder.of(id, ch, LIVE_MIX[r.nextInt(LIVE_MIX.length)], max));
                }
            } else {
                out.add(Bidder.online(id, ONLINE_MIX[r.nextInt(ONLINE_MIX.length)], max));
            }
        }
        if (n > 0 && r.nextInt(25) == 0) {
            long cap = Math.max(start, reserve > 0 ? reserve : (long) (start * 1.5));
            long id = key * 100 + 99;
            out.add(live ? Bidder.of(id, Channel.ROOM, Strategy.SHILL, cap) : Bidder.online(id, Strategy.SHILL, cap));
        }
        return new Case(lot, out);
    }
}
