package io.hammerhall.data.auc.auction.demand;

import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.Participants;
import io.hammerhall.data.auc.world.RegMode;
import io.hammerhall.data.auc.world.Registrations;
import io.hammerhall.data.auc.world.Via;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Регистрации ({@code registrations}) и редакции поручений ({@code bid_orders}) по ключам: {@code id} — по моменту
 * подачи (затем ключу), выдаётся за водоразделом записи ({@link #flush}). Состояние на срез: решение после среза —
 * {@code pending} без решения; допуск к дорогим лотам после среза — пусто.
 *
 * <p>Память: черновики ({@link RegDraft}) держатся, пока у торгов есть регистрации без {@code id} (поданные раньше
 * среза); когда все записаны, торги сворачиваются в массивы ({@link Packed}) — ответы {@link Registrations} те же.
 */
public final class RegistrationBook implements Registrations {
    private static final Via[] VIA = Via.values();
    private static final RegMode[] MODE = RegMode.values();
    private static final byte F_LOTS = 1;

    /** Торги в работе: черновики по клиенту. */
    private final Map<Integer, Map<Integer, RegDraft>> bySale = new HashMap<>();
    /** Торги в работе: сколько регистраций ждут записи (поданы раньше среза, {@code id} не выдан). */
    private final Map<Integer, int[]> waiting = new HashMap<>();
    /** Свёрнутые торги. */
    private final Map<Integer, Packed> packed = new HashMap<>();
    private final List<RegDraft> pending = new ArrayList<>();
    /** Участие по ключу регистрации, которой нет в книге (не бывает; чтобы {@link #hasLots} отвечал точно). */
    private final Set<Long> strayLots = new HashSet<>();
    /** Словарь строк свёрнутых торгов (статус, причина отказа): индекс 0 — {@code null}. */
    private final List<String> words = new ArrayList<>();
    private int next = 1;
    private OrderBook orders;

    public RegistrationBook() {
        words.add(null);
    }

    public void orders(OrderBook orders) {
        this.orders = orders;
    }

    /**
     * Регистрации торгов от спроса и участники лотов (ставки и попытки — для «регистраций без ставок»);
     * потокобезопасно, один раз на торги.
     */
    public synchronized void add(int saleId, List<RegDraft> regs, List<Participants> lots) {
        if (packed.containsKey(saleId)) throw new IllegalStateException("регистрации торгов " + saleId + " после свёртки");
        Map<Integer, RegDraft> m = bySale.computeIfAbsent(saleId, k -> new TreeMap<>());
        for (RegDraft g : regs) {
            if (m.putIfAbsent(g.clientId, g) != null) throw new IllegalStateException("регистрация дважды: " + g.key);
            pending.add(g);
        }
        waiting.computeIfAbsent(saleId, k -> new int[1])[0] += regs.size();
        for (Participants p : lots) {
            for (Participants.Participant x : p.bidders()) markLots(x.regKey());
            for (Participants.Rejected x : p.rejected()) markLots(x.regKey());
        }
    }

    private void markLots(long key) {
        RegDraft g = draft(key);
        if (g != null) {
            g.withLots = true;
            return;
        }
        Packed p = packed.get(Keys.registrationSale(key));
        int i = p == null ? -1 : p.index(Keys.registrationClient(key));
        if (i >= 0) p.flags[i] |= F_LOTS;
        else strayLots.add(key);
    }

    synchronized RegDraft draft(long key) {
        Map<Integer, RegDraft> m = bySale.get(Keys.registrationSale(key));
        return m == null ? null : m.get(Keys.registrationClient(key));
    }

    /** Выдать {@code id} регистрациям, поданным раньше {@code watershed}, и записать их строки (на срез {@code cut}). */
    public synchronized void flush(long watershed, long cut, Sink out) throws IOException {
        List<RegDraft> ready = new ArrayList<>();
        List<RegDraft> keep = new ArrayList<>();
        for (RegDraft g : pending) {
            if (g.registeredAt >= cut) waiting.get(g.saleId)[0]--; // не в выпуске: id не будет
            else (g.registeredAt < watershed ? ready : keep).add(g);
        }
        ready.sort(Comparator.comparingLong(RegDraft::registeredAt).thenComparingLong(RegDraft::key));
        for (RegDraft g : ready) {
            int id = next++;
            g.id = id;
            waiting.get(g.saleId)[0]--;
            boolean decided = g.decidedAt < cut;
            out.row(new Object[]{id, g.saleId, g.clientId, g.paddle, g.registeredAt, g.via.code(), g.mode.code(),
                    decided ? g.status : "pending", decided ? g.decidedAt : null, decided && g.decidedBy > 0 ? g.decidedBy : null,
                    decided ? g.rejectReason : null, g.limit > 0 ? g.limit : null,
                    g.premiumAt > 0 && g.premiumAt < cut ? g.premiumAt : null, g.deposit > 0 ? g.deposit : null});
        }
        pending.clear();
        pending.addAll(keep);
        // торги без ждущих записи — свернуть
        for (Iterator<Map.Entry<Integer, int[]>> it = waiting.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, int[]> e = it.next();
            if (e.getValue()[0] > 0) continue;
            it.remove();
            Map<Integer, RegDraft> m = bySale.remove(e.getKey());
            if (m != null) packed.put(e.getKey(), new Packed(e.getKey(), m.values(), words));
        }
    }

    /** Выдано {@code id}. */
    public synchronized int issued() {
        return next - 1;
    }

    /** Регистрация участвовала хотя бы в одном лоте своих торгов (ставка или попытка). */
    public synchronized boolean hasLots(long key) {
        RegDraft g = draft(key);
        if (g != null) return g.withLots;
        Packed p = packed.get(Keys.registrationSale(key));
        int i = p == null ? -1 : p.index(Keys.registrationClient(key));
        return i >= 0 ? (p.flags[i] & F_LOTS) != 0 : strayLots.contains(key);
    }

    /** Обход регистраций торгов по ключу — для проверок. */
    public synchronized void visit(int saleId, Visitor v) {
        Map<Integer, RegDraft> m = bySale.get(saleId);
        if (m != null) {
            List<RegDraft> l = new ArrayList<>(m.values());
            l.sort(Comparator.comparingLong(RegDraft::key));
            for (RegDraft g : l) v.accept(g.key, g.clientId, g.registeredAt, g.approved(), g.withLots);
            return;
        }
        Packed p = packed.get(saleId);
        if (p == null) return;
        for (int i = 0; i < p.n; i++) {
            v.accept(p.key(i), p.client[i], p.regAt[i], "approved".equals(words.get(p.status[i])), (p.flags[i] & F_LOTS) != 0);
        }
    }

    /** Регистрация для проверок: ключ, клиент, подана, одобрена, участвовала в лотах. */
    public interface Visitor {
        void accept(long key, int clientId, long registeredAt, boolean approved, boolean withLots);
    }

    // ---------------------------------------------------------------- Registrations

    @Override
    public synchronized Registration get(long key) {
        RegDraft g = draft(key);
        if (g != null) return view(g);
        Packed p = packed.get(Keys.registrationSale(key));
        int i = p == null ? -1 : p.index(Keys.registrationClient(key));
        return i < 0 ? null : p.view(i, words);
    }

    private static Registration view(RegDraft g) {
        return new Registration(g.key, g.id, g.saleId, g.clientId, g.paddle, g.registeredAt, g.via, g.mode,
                g.status, g.decidedAt, g.decidedBy, g.rejectReason, g.limit, g.premiumAt, g.deposit);
    }

    @Override
    public synchronized int id(long key) {
        RegDraft g = draft(key);
        if (g != null) return g.id;
        Packed p = packed.get(Keys.registrationSale(key));
        int i = p == null ? -1 : p.index(Keys.registrationClient(key));
        return i < 0 ? 0 : p.id[i];
    }

    @Override
    public synchronized List<Registration> ofSale(int saleId) {
        List<Registration> out = new ArrayList<>();
        Map<Integer, RegDraft> m = bySale.get(saleId);
        if (m != null) {
            List<RegDraft> l = new ArrayList<>(m.values());
            l.sort(Comparator.comparingLong(RegDraft::key));
            for (RegDraft g : l) out.add(view(g));
            return out;
        }
        Packed p = packed.get(saleId);
        if (p != null) for (int i = 0; i < p.n; i++) out.add(p.view(i, words));
        return out;
    }

    @Override
    public int orderId(long orderKey) {
        return orders == null ? 0 : orders.id(orderKey);
    }

    /** Приёмник строк. */
    public interface Sink {
        void row(Object[] values) throws IOException;
    }

    /**
     * Регистрации свёрнутых торгов — по ключу (= по клиенту): моменты — секунды в {@code int}, строки — по словарю,
     * лимит, допуск и депозит — массивом, только если есть хоть у одной (лимит — целыми рублями, если у всех без копеек),
     * кто решил — {@code short}, если влезает.
     */
    private static final class Packed {
        final int sale, n;
        final int[] client, id, paddle, regAt, decAt;
        final int[] decBy;
        final short[] decByShort;
        final byte[] via, mode, status, reason, flags;
        final long[] limit, deposit;
        final int[] limitRub;
        final int[] premiumAt;

        Packed(int sale, Collection<RegDraft> drafts, List<String> words) {
            List<RegDraft> l = new ArrayList<>(drafts);
            l.sort(Comparator.comparingLong(RegDraft::key));
            this.sale = sale;
            this.n = l.size();
            client = new int[n];
            id = new int[n];
            paddle = new int[n];
            regAt = new int[n];
            decAt = new int[n];
            via = new byte[n];
            mode = new byte[n];
            status = new byte[n];
            reason = new byte[n];
            flags = new byte[n];
            boolean anyLimit = false, anyPremium = false, anyDeposit = false, limitRubles = true, byShort = true;
            for (RegDraft g : l) {
                anyLimit |= g.limit != 0;
                anyPremium |= g.premiumAt != 0;
                anyDeposit |= g.deposit != 0;
                limitRubles &= g.limit % 100 == 0 && g.limit / 100 >= 0 && g.limit / 100 <= Integer.MAX_VALUE;
                byShort &= g.decidedBy >= Short.MIN_VALUE && g.decidedBy <= Short.MAX_VALUE;
            }
            decBy = byShort ? null : new int[n];
            decByShort = byShort ? new short[n] : null;
            limitRub = anyLimit && limitRubles ? new int[n] : null;
            limit = anyLimit && !limitRubles ? new long[n] : null;
            premiumAt = anyPremium ? new int[n] : null;
            deposit = anyDeposit ? new long[n] : null;
            for (int i = 0; i < n; i++) {
                RegDraft g = l.get(i);
                if (g.saleId != sale || g.key != keyOf(g.clientId) || i > 0 && g.clientId <= client[i - 1]) {
                    throw new IllegalStateException("регистрация " + g.key + ": ключ не по торгам и клиенту");
                }
                client[i] = g.clientId;
                id[i] = g.id;
                paddle[i] = g.paddle;
                regAt[i] = Math.toIntExact(g.registeredAt);
                decAt[i] = Math.toIntExact(g.decidedAt);
                if (decBy != null) decBy[i] = g.decidedBy;
                else decByShort[i] = (short) g.decidedBy;
                via[i] = (byte) g.via.ordinal();
                mode[i] = (byte) g.mode.ordinal();
                status[i] = word(words, g.status);
                reason[i] = word(words, g.rejectReason);
                flags[i] = g.withLots ? F_LOTS : 0;
                if (limit != null) limit[i] = g.limit;
                if (limitRub != null) limitRub[i] = (int) (g.limit / 100);
                if (premiumAt != null) premiumAt[i] = Math.toIntExact(g.premiumAt);
                if (deposit != null) deposit[i] = g.deposit;
            }
        }

        private static byte word(List<String> words, String s) {
            int i = s == null ? 0 : words.indexOf(s);
            if (i < 0) {
                i = words.size();
                if (i > Byte.MAX_VALUE) throw new IllegalStateException("словарь регистраций переполнен");
                words.add(s);
            }
            return (byte) i;
        }

        long keyOf(int clientId) {
            return (long) sale << 32 | (clientId & 0xFFFF_FFFFL);
        }

        long key(int i) {
            return keyOf(client[i]);
        }

        int index(int clientId) {
            int i = Arrays.binarySearch(client, clientId);
            return i < 0 ? -1 : i;
        }

        Registration view(int i, List<String> words) {
            long lim = limit != null ? limit[i] : limitRub != null ? limitRub[i] * 100L : 0;
            return new Registration(key(i), id[i], sale, client[i], paddle[i], regAt[i], VIA[via[i]], MODE[mode[i]],
                    words.get(status[i]), decAt[i], decBy != null ? decBy[i] : decByShort[i], words.get(reason[i]), lim,
                    premiumAt == null ? 0 : premiumAt[i], deposit == null ? 0 : deposit[i]);
        }
    }
}
