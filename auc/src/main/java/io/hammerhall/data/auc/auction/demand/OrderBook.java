package io.hammerhall.data.auc.auction.demand;

import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.OrderEvents;
import io.hammerhall.data.auc.world.Via;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongToIntFunction;

/**
 * Поручения и их редакции ({@code bid_orders}, T4): заявки до торгов (заочные, телефон — редакция 1, от спроса) и
 * редакции, родившиеся в торгах (от оркестра: {@code PLACED}, {@code REBID}, {@code RAISED}, {@code REJECTED}), с
 * окончательными статусами ({@code OUTBID}, {@code EXCEEDED_LIMIT}, {@code WON}, {@code UNUSED}); подъём максимума
 * закрывает прежнюю редакцию ({@code superseded}). {@code id} — по моменту подачи (мс), затем ключу. Состояние на срез —
 * статус, ставший окончательным раньше среза; иначе {@code active}.
 */
public final class OrderBook {
    /** Редакция поручения. */
    static final class Order {
        final long key, lotKey, regKey;
        final String kind;
        final int revision;
        final long maxKop, placedMs;
        final Via via;
        final String reason;
        final String phoneOutcome;
        final int clerkId;
        String status;
        long endedAt;
        int id;

        Order(long key, long lotKey, long regKey, String kind, int revision, long maxKop, long placedMs, Via via, String reason,
              String phoneOutcome, int clerkId) {
            this.key = key;
            this.lotKey = lotKey;
            this.regKey = regKey;
            this.kind = kind;
            this.revision = revision;
            this.maxKop = maxKop;
            this.placedMs = placedMs;
            this.via = via;
            this.reason = reason;
            this.phoneOutcome = phoneOutcome;
            this.clerkId = clerkId;
        }
    }

    private final Map<Long, Order> byKey = new HashMap<>();
    private final List<Order> pending = new ArrayList<>();
    private final Map<Long, Integer> written = new HashMap<>();
    private final Map<Long, List<Order>> byLot = new HashMap<>();
    private int next = 1;
    /** {@code sales.id} → онлайн-торги (вид редакций, родившихся в торгах: у всех — {@code auto}). */
    long stRaised, stRejected, stEvents;

    /** Заявки до торгов от спроса (потокобезопасно). */
    public synchronized void add(List<OrderDraft> drafts) {
        for (OrderDraft d : drafts) {
            Order o = new Order(d.key(), d.lotKey(), d.regKey(), d.kind(), 1, d.maxKop(), d.placedAtMs(), d.via(), null,
                    d.phoneOutcome(), d.clerkId());
            if (d.cancelledAt() > 0) {
                o.status = "cancelled";
                o.endedAt = d.cancelledAt();
            }
            put(o);
        }
    }

    private void put(Order o) {
        if (byKey.putIfAbsent(o.key, o) != null) throw new IllegalStateException("редакция поручения дважды: " + o.key);
        pending.add(o);
        byLot.computeIfAbsent(o.lotKey, k -> new ArrayList<>()).add(o);
    }

    /** Что движок сделал с максимумами лота. */
    public synchronized void apply(OrderEvents ev) {
        for (OrderEvents.Event e : ev.events()) {
            stEvents++;
            long key = Keys.order(ev.lotKey(), e.clientId(), e.revision());
            long at = Math.floorDiv(e.atMs(), 1000);
            switch (e.type()) {
                case PLACED, REBID, RAISED, REJECTED -> {
                    String reason = e.revision() == 1 ? null
                            : e.type() == OrderEvents.Type.RAISED ? "raise_leading" : "rebid_after_outbid";
                    Order o = new Order(key, ev.lotKey(), e.regKey(), "auto", e.revision(), e.maxKop(), e.atMs(),
                            e.via() == null ? Via.SITE : e.via(), reason, null, 0);
                    if (e.type() == OrderEvents.Type.REJECTED) {
                        o.status = "rejected";
                        o.endedAt = at;
                        stRejected++;
                    }
                    put(o);
                    if (e.type() == OrderEvents.Type.RAISED) {
                        stRaised++;
                        Order prev = byKey.get(Keys.order(ev.lotKey(), e.clientId(), e.revision() - 1));
                        if (prev != null && prev.status == null) {
                            prev.status = "superseded";
                            prev.endedAt = at;
                        }
                    }
                }
                case OUTBID, EXCEEDED_LIMIT, WON, UNUSED -> {
                    Order o = byKey.get(key);
                    if (o == null) throw new IllegalStateException("событие " + e.type() + " без редакции " + key);
                    if (o.status == null) {
                        o.status = switch (e.type()) {
                            case OUTBID -> "outbid";
                            case EXCEEDED_LIMIT -> "exceeded_limit";
                            case WON -> "won";
                            default -> "unused";
                        };
                        o.endedAt = at;
                    }
                }
            }
        }
    }

    /**
     * Заявки до торгов, о которых оркестр не сообщил итога: по итогу лота — {@code won} у покупателя, иначе {@code unused}
     * (лот закрыт в {@code closedAt}); снятый лот — {@code cancelled} в момент снятия.
     */
    public synchronized void settleLot(long lotKey, long closedAt, long buyerRegKey, long withdrawnAt) {
        List<Order> l = byLot.get(lotKey);
        if (l == null) return;
        for (Order o : l) {
            if (o.status != null) continue;
            if (withdrawnAt > 0) {
                o.status = "cancelled";
                o.endedAt = Math.max(withdrawnAt, Math.floorDiv(o.placedMs, 1000));
            } else if (closedAt > 0) {
                o.status = o.regKey == buyerRegKey ? "won" : "unused";
                o.endedAt = closedAt;
            }
        }
    }

    public synchronized int id(long orderKey) {
        Order o = byKey.get(orderKey);
        if (o != null) return o.id;
        Integer w = written.get(orderKey);
        return w == null ? 0 : w;
    }

    /**
     * Выдать {@code id} редакциям, поданным раньше {@code watershed} (секунды), и записать их строки на срез {@code cut}.
     * {@code lotId}, {@code regId} — {@code id} лота и регистрации по ключу (выданы раньше).
     */
    public synchronized void flush(long watershed, long cut, LongToIntFunction lotId, LongToIntFunction regId,
                                   RegistrationBook.Sink out) throws IOException {
        List<Order> ready = new ArrayList<>();
        List<Order> keep = new ArrayList<>();
        long wMs = Math.min(watershed, cut) * 1000;
        for (Order o : pending) (o.placedMs < wMs ? ready : keep).add(o);
        ready.sort(Comparator.comparingLong((Order o) -> o.placedMs).thenComparingLong(o -> o.key));
        for (Order o : ready) {
            o.id = next++;
            int lot = lotId.applyAsInt(o.lotKey), reg = regId.applyAsInt(o.regKey);
            if (lot == 0 || reg == 0) throw new IllegalStateException("поручение " + o.key + ": нет id лота или регистрации");
            Integer prev = null;
            if (o.revision > 1) {
                long pk = Keys.order(o.lotKey, Keys.registrationClient(o.regKey), o.revision - 1);
                int p = id(pk);
                if (p == 0) throw new IllegalStateException("поручение " + o.key + ": прежняя редакция без id");
                prev = p;
            }
            boolean ended = o.status != null && o.endedAt < cut;
            out.row(new Object[]{o.id, lot, reg, o.kind, o.revision, prev, o.maxKop > 0 ? o.maxKop : null,
                    Math.floorDiv(o.placedMs, 1000), (int) Math.floorMod(o.placedMs, 1000L), o.via.code(), o.reason,
                    ended ? o.status : "active", ended ? o.endedAt : null, o.phoneOutcome, o.clerkId > 0 ? o.clerkId : null});
            written.put(o.key, o.id);
            byKey.remove(o.key);
        }
        pending.clear();
        pending.addAll(keep);
    }

    /**
     * Лот записан (строка {@code lots} и все его ставки): редакции лота больше не нужны — отпустить, если все записаны
     * (у лота, открытого на срезе, редакции после среза не записываются — такие держатся до конца).
     */
    public synchronized void lotWritten(long lotKey) {
        List<Order> l = byLot.get(lotKey);
        if (l == null) return;
        for (Order o : l) if (byKey.containsKey(o.key)) return;
        byLot.remove(lotKey);
        for (Order o : l) written.remove(o.key);
    }

    public synchronized int issued() {
        return next - 1;
    }

    public synchronized String stats() {
        return "редакций " + (next - 1) + " (+" + pending.size() + " ждут), подъёмов " + stRaised + ", отклонённых " + stRejected
                + ", событий " + stEvents;
    }
}
