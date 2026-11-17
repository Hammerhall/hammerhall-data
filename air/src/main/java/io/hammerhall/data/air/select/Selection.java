package io.hammerhall.data.air.select;

import io.hammerhall.data.core.random.RandomStreams;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Какие брони попадают в выпуск — то же, что запросы {@code first_dep} и {@code keep} сборки {@code air-data-v1}:
 * <ol>
 *   <li>рейс → аэропорт вылета: версия маршрута с тем же {@code route_no}, где {@code valid_from ≤ вылет < valid_to}
 *       (рейс без такой версии в счёт первого вылета не идёт);</li>
 *   <li>первый вылет брони — самый ранний плановый вылет среди перелётов всех её билетов
 *       ({@code arg_min(departure_airport, scheduled_departure)});</li>
 *   <li>доля и {@code u} — {@link Seasonality}; бронь остаётся при {@code u < доля};</li>
 *   <li>профиль меньше {@code full} — из оставшихся броней ещё отбор по отдельному хешу номера брони:
 *       {@code unit(seed, «air.small», fnv64(book_ref)) < scale} (у {@code small} scale = 0,01).</li>
 * </ol>
 * Билеты, перелёты и посадочные талоны идут со своими бронями: множество билетов — те, чья бронь осталась.
 *
 * <p>Ничья в {@code arg_min} (у брони два перелёта с одинаковым самым ранним вылетом из разных аэропортов) в DuckDB
 * решается как придётся; здесь — аэропорт с меньшим кодом. Сколько таких броней — в {@link Stats#ties()}: не ноль —
 * место, где выпуск может разойтись со сборкой на Python.
 */
public final class Selection {
    /** Итоги отбора — в журнал и манифест. */
    public record Stats(long bookings, long withFirstDeparture, long withoutFirstDeparture, long keptByModel,
                        long inProfile, long tickets, long ties, long outsideModel, long flightsWithoutRoute,
                        long flightsWithTwoRoutes) {
    }

    private LongSet books;
    private LongSet tickets;
    private final Stats stats;

    private Selection(LongSet books, LongSet tickets, Stats stats) {
        this.books = books;
        this.tickets = tickets;
        this.stats = stats;
    }

    /** Брони выпуска (коды {@link KeyCodec}). */
    public LongSet books() {
        return books;
    }

    /** Билеты броней выпуска. */
    public LongSet tickets() {
        return tickets;
    }

    public Stats stats() {
        return stats;
    }

    /** Отпустить множества (≈ 0,4 ГБ на полной выгрузке), когда строки отобраны; итоги ({@link #stats}) остаются. */
    public void dropSets() {
        books = null;
        tickets = null;
    }

    private static final long NONE = Long.MAX_VALUE;

    /**
     * @param bookCodes   номера броней таблицы броней (для счёта броней без первого вылета)
     * @param ticketCode  номер билета, {@code ticketBook} — его бронь (массивы переставляются)
     * @param segTicket   билет перелёта, {@code segFlight} — его рейс (массивы портятся)
     * @param scale       доля профиля ({@code 1.0} — весь отбор модели)
     * @param sample      поток хеша для профиля меньше {@code full}
     */
    public static Selection compute(Network net, long[] bookCodes, long[] ticketCode, long[] ticketBook,
                                    long[] segTicket, long[] segFlight, double scale, RandomStreams.Stream sample,
                                    Consumer<String> log) {
        // --- аэропорты вылета маршрутов: индекс по коду (меньше код — меньше индекс: так решается ничья)
        TreeSet<String> depCodes = new TreeSet<>();
        for (Network.Route r : net.routes()) depCodes.add(r.departure());
        List<String> codes = new ArrayList<>(depCodes);
        if (codes.size() >= 1 << 16) throw new IllegalStateException("аэропортов вылета больше 65535");
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < codes.size(); i++) idx.put(codes.get(i), i);

        // --- рейс → (плановый вылет, аэропорт вылета) упаковкой: вылет << 16 | индекс аэропорта
        Map<String, List<Network.Route>> byNo = new HashMap<>();
        for (Network.Route r : net.routes()) byNo.computeIfAbsent(r.routeNo(), k -> new ArrayList<>()).add(r);
        int maxId = 0;
        for (Network.Flight f : net.flights()) {
            if (f.id() < 0) throw new IllegalStateException("flight_id < 0: " + f.id());
            maxId = Math.max(maxId, f.id());
        }
        if (maxId > 1 << 26) throw new IllegalStateException("flight_id до " + maxId + " — больше, чем ждали");
        long[] flight = new long[maxId + 1];
        Arrays.fill(flight, -1);
        long noRoute = 0, twoRoutes = 0;
        for (Network.Flight f : net.flights()) {
            long dep = f.scheduledDeparture();
            if (dep < 0 || dep >= 1L << 46) throw new IllegalStateException("вылет рейса " + f.id() + " вне диапазона: " + dep);
            int best = -1, matches = 0;
            for (Network.Route r : byNo.getOrDefault(f.routeNo(), List.of())) {
                if (dep >= r.validFrom() && dep < r.validTo()) {
                    matches++;
                    int a = idx.get(r.departure());
                    if (best < 0 || a < best) best = a;
                }
            }
            if (matches == 0) noRoute++;
            if (matches > 1) twoRoutes++;
            if (flight[f.id()] != -1) throw new IllegalStateException("flight_id дважды: " + f.id());
            flight[f.id()] = best < 0 ? -2 : (dep << 16) | best;
        }

        // --- перелёт → упакованный вылет его рейса (рейса нет или нет версии маршрута — перелёт не в счёт)
        int nS = 0;
        for (int i = 0; i < segTicket.length; i++) {
            long fid = segFlight[i];
            long v = fid >= 0 && fid <= maxId ? flight[(int) fid] : -1;
            if (v < 0) continue;
            segTicket[nS] = segTicket[i];
            segFlight[nS] = v;
            nS++;
        }
        int nT = ticketCode.length;
        Radix.sort(ticketCode, ticketBook, nT);
        Radix.sort(segTicket, segFlight, nS);

        // --- самый ранний вылет каждого билета (слиянием по номеру билета)
        long[] tMin = new long[nT];
        Arrays.fill(tMin, NONE);
        for (int i = 0, j = 0; i < nT && j < nS; ) {
            long t = ticketCode[i], s = segTicket[j];
            if (t < s) {
                i++;
            } else if (s < t) {
                j++;
            } else {
                long m = NONE;
                int j2 = j;
                while (j2 < nS && segTicket[j2] == t) m = Math.min(m, segFlight[j2++]);
                while (i < nT && ticketCode[i] == t) tMin[i++] = m;
                j = j2;
            }
        }

        // --- по броням: самый ранний вылет среди билетов брони, затем модель
        long[] bk = ticketBook.clone();
        Radix.sort(bk, tMin, nT);
        ZoneId[] zone = new ZoneId[codes.size()];
        Network.Airport[] info = new Network.Airport[codes.size()];
        for (int i = 0; i < codes.size(); i++) info[i] = net.airports().get(codes.get(i));
        Longs keptModel = new Longs(), keptProfile = new Longs(), withFirst = new Longs();
        long ties = 0, outside = 0;
        for (int g = 0; g < nT; ) {
            int g2 = g;
            long book = bk[g], m = NONE;
            while (g2 < nT && bk[g2] == book) m = Math.min(m, tMin[g2++]);
            if (m != NONE) {
                for (int k = g; k < g2; k++) {
                    if (tMin[k] != m && tMin[k] >>> 16 == m >>> 16) {
                        ties++;
                        break;
                    }
                }
                withFirst.add(book);
                long dep = m >>> 16;
                int a = (int) (m & 0xffff);
                Network.Airport ap = info[a];
                String ref = KeyCodec.decode(book);
                if (ap == null || Seasonality.region(ap.country()) == null) {
                    outside++;
                } else {
                    if (zone[a] == null) zone[a] = ZoneId.of(ap.timezone());
                    LocalDate local = Instant.ofEpochSecond(dep).atZone(zone[a]).toLocalDate();
                    if (Seasonality.keep(ref, ap.country(), local)) {
                        keptModel.add(book);
                        if (scale >= 1.0 || sample.unit(RandomStreams.fnv64(ref)) < scale) keptProfile.add(book);
                    }
                }
            }
            g = g2;
        }
        bk = null;
        tMin = null;

        // --- брони таблицы броней без первого вылета
        long[] first = withFirst.take();
        long[] all = bookCodes.clone();
        Arrays.sort(all);
        long matched = 0;
        for (int i = 0, j = 0; i < all.length && j < first.length; ) {
            if (all[i] < first[j]) i++;
            else if (first[j] < all[i]) j++;
            else {
                matched++;
                i++;
                j++;
            }
        }
        long[] prof = keptProfile.take();
        LongSet books = new LongSet(prof.length);
        for (long b : prof) books.add(b);
        Longs t = new Longs();
        for (int i = 0; i < nT; i++) if (books.contains(ticketBook[i])) t.add(ticketCode[i]);
        long[] tk = t.take();
        LongSet tickets = new LongSet(tk.length);
        for (long x : tk) tickets.add(x);
        Stats st = new Stats(bookCodes.length, first.length, bookCodes.length - matched, keptModel.size(), prof.length,
                tickets.size(), ties, outside, noRoute, twoRoutes);
        log.accept(String.format(Locale.ROOT, "отбор: броней %d, с первым вылетом %d (без него — %d), оставлено моделью %d"
                        + " (%.1f %%), в профиле %d, билетов %d; ничьих первого вылета из разных аэропортов %d, первый"
                        + " вылет вне стран модели %d; рейсов без версии маршрута %d, с двумя версиями %d",
                st.bookings(), st.withFirstDeparture(), st.withoutFirstDeparture(), st.keptByModel(),
                st.bookings() == 0 ? 0.0 : 100.0 * st.keptByModel() / st.bookings(), st.inProfile(), st.tickets(),
                st.ties(), st.outsideModel(), st.flightsWithoutRoute(), st.flightsWithTwoRoutes()));
        return new Selection(books, tickets, st);
    }
}
