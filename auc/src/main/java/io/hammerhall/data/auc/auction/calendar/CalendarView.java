package io.hammerhall.data.auc.auction.calendar;

import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.SaleTier;
import io.hammerhall.data.core.time.Period;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Календарь выпуска ({@code CALENDAR}, мир): торги {@link AuctionCalendar#inRelease} с {@code sales.id} по плановой
 * публикации; у назначенных (каталог по плану — на срезе или позже) {@code publishedAt} и {@code biddingOpensAt} — 0.
 * Ключ торгов — {@link Keys#sale}: (формат, отдел, год, номер торгов этого формата и отдела в году по началу).
 */
public final class CalendarView implements Calendar {
    private final List<Sale> sales;
    private final List<SaleSpec> specs;
    private final Map<Long, Integer> byKey = new HashMap<>();
    private final List<Sale> byOpening;
    private final long slice;

    public CalendarView(AuctionCalendar cal, Period period, long slice, HouseRules house) {
        this.slice = slice;
        specs = cal.inRelease(period);
        Map<Long, Integer> number = numbers(cal, specs);
        List<Sale> out = new ArrayList<>(specs.size());
        int msk = house.office("msk").id(), spb = house.office("spb").id();
        for (int i = 0; i < specs.size(); i++) {
            SaleSpec s = specs.get(i);
            Dept d = Dept.byId(s.dept() + 1);
            Format f = s.live() ? Format.LIVE : Format.TIMED;
            long key = Keys.sale(f, d, s.year(), number.get(s.key()));
            int theme = 0;
            if (s.thematic()) {
                HouseRules.Category c = house.categoryByCode(Depts.THEMES[s.theme()].category());
                theme = c == null ? 0 : c.id();
            }
            boolean published = s.publishedAt() < slice;
            Sale sale = new Sale(i + 1, key, s.code(), f, SaleTier.byCode(s.tier()), d, s.office() == 1 ? msk : spb, theme,
                    s.titleRu(), s.titleEn(), published ? s.publishedAt() : 0, published ? s.biddingOpensAt() : 0,
                    s.previewFrom(), s.previewTo(), s.startsAt(), s.days(), s.targetLots(),
                    house.premiumSchedule(d, Period.localDate(s.startsAt(), HouseRules.ZONE)).id(), SaleSpec.PAYMENT_DUE_DAYS);
            out.add(sale);
            byKey.put(s.key(), i + 1);
        }
        sales = List.copyOf(out);
        List<Sale> o = new ArrayList<>(sales);
        o.sort(Comparator.comparingLong((Sale x) -> specs.get(x.id() - 1).biddingOpensAt()).thenComparingInt(Sale::id));
        byOpening = List.copyOf(o);
    }

    /** Номер торгов (формат, отдел) в году по началу — для {@link Keys#sale}; от периода не зависит. */
    private static Map<Long, Integer> numbers(AuctionCalendar cal, List<SaleSpec> specs) {
        Map<Long, Integer> out = new HashMap<>();
        Map<Integer, Boolean> done = new HashMap<>();
        for (SaleSpec s : specs) {
            if (done.putIfAbsent(s.year(), true) != null) continue;
            int[][] n = new int[2][Depts.COUNT];
            for (SaleSpec y : cal.year(s.year())) out.put(y.key(), ++n[y.live() ? 1 : 0][y.dept()]);
        }
        return out;
    }

    @Override
    public List<Sale> sales() {
        return sales;
    }

    @Override
    public Sale sale(int id) {
        return sales.get(id - 1);
    }

    /** План торгов (в том числе публикация и открытие у назначенных). */
    public SaleSpec spec(int id) {
        return specs.get(id - 1);
    }

    /** {@code sales.id} по ключу календаря ({@link SaleSpec#key()}); 0 — торгов нет в выпуске. */
    public int idOf(long specKey) {
        Integer id = byKey.get(specKey);
        return id == null ? 0 : id;
    }

    /** Каталог опубликован до среза. */
    public boolean published(int id) {
        return specs.get(id - 1).publishedAt() < slice;
    }

    public long slice() {
        return slice;
    }

    /** Торги с каталогом до среза, открывающие ставки в {@code [fromAt, toAt)} — по плановому открытию, затем {@code id}. */
    @Override
    public List<Sale> openingBetween(long fromAt, long toAt) {
        List<Sale> out = new ArrayList<>();
        for (Sale s : byOpening) {
            SaleSpec p = specs.get(s.id() - 1);
            if (p.publishedAt() >= slice) continue;
            if (p.biddingOpensAt() >= fromAt && p.biddingOpensAt() < toAt) out.add(s);
        }
        return out;
    }
}
