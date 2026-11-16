package io.hammerhall.data.sto.market;

import io.hammerhall.data.core.money.Tax;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.sto.catalog.Catalog.Assortment;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Модель маркетплейса для следующих модулей ({@code ctx.get(StoKeys.MARKET)}): продавцы ({@code sellers}) и их
 * предложения ({@code offers}) — ровно как в таблицах, плюс скрытые черты продавцов, которых в таблицах нет.
 * Неизменяема после модуля {@code market}; одинакова во всех профилях (мир, как каталог).
 *
 * <h2>Предложения</h2>
 * Строка {@code offers} — период цены одного «листинга» (продавец × товар): {@code [from, to)}, у листинга периоды
 * идут встык, без разрывов и наложений; у пары продавец × товар — не больше одного листинга. {@code offer_id} в
 * строке заказа — строка, действующая в момент заказа: {@link #offersAt(int, long)} отдаёт именно её.
 *
 * <ul>
 *   <li>{@link #offersAt(int, long)} — предложения товара на момент (id строки, продавец, цена, FBO/FBS);</li>
 *   <li>{@link #choose(int, long, long, Assortment, Rng)} — выбор покупателя: магазин (1P, {@code null}) или
 *       предложение продавца; дешевле — вероятнее, крупный продавец — вероятнее;</li>
 *   <li>{@link #listings()}, {@link #listingsOf(int)}, {@link #listingsOfSeller(int)} — листинги целиком (для поставок
 *       FBO: что продавец держит на складах магазина и когда).</li>
 * </ul>
 *
 * <h2>Продавцы</h2>
 * {@link Seller} — поля таблицы и скрытые черты: вес (размер оборота на площадке), специализация (отделы каталога),
 * срок сборки FBS, «качество» ({@link Quality}: один плохой продавец и несколько похуже среднего — узор для заказов,
 * возвратов и отзывов) и множители к базовым вероятностям следующих модулей ({@link Seller#returnFactor()} и др.).
 * НДС продавца на дату — {@link #vatRate(int, LocalDate)} (общий режим — 20/22 %, УСН — освобождение до порога
 * выручки, который снижается по годам, выше — 5 или 7 %).
 *
 * <p>Моменты — секунды Unix; «нет конца» — {@link Long#MAX_VALUE}. Цены — копейки, с НДС.
 */
public final class Market {

    /** Модель работы: товар продавца на складе магазина (FBO) или продавец отгружает сам (FBS). */
    public enum Model {
        FBO("fbo"), FBS("fbs");
        public final String code;

        Model(String code) {
            this.code = code;
        }
    }

    /** Налоговый режим: общий (плательщик НДС) или упрощённый (освобождение от НДС до порога выручки). */
    public enum TaxRegime {
        OSN("osn"), USN("usn");
        public final String code;

        TaxRegime(String code) {
            this.code = code;
        }
    }

    /** Скрытое «качество» продавца — узор для заказов, возвратов и отзывов. */
    public enum Quality {NORMAL, POOR, BAD}

    /**
     * Продавец: поля таблицы {@code sellers} и скрытые черты.
     *
     * @param id            id в таблице
     * @param name          название (с юрформой: ООО, АО, ИП)
     * @param legalForm     «ООО», «АО» или «ИП»
     * @param cityId        город (откуда отгружает FBS)
     * @param model         FBO / FBS
     * @param joinedAt      подключился (момент; может быть до начала периода)
     * @param leftAt        ушёл; {@link Long#MAX_VALUE} — работает до конца периода (в таблице — NULL)
     * @param taxRegime     налоговый режим
     * @param revenue2025   оборот за 2025 год, руб. (весь бизнес продавца, не только на площадке; от профиля не зависит)
     * @param revenueGrowth годовой рост оборота (оборот за год y = revenue2025 · growth^(y − 2025))
     * @param weight        вес продавца на площадке (≈ доля спроса при равных ценах; медиана ≈ 1, тяжёлый хвост)
     * @param departmentIds отделы каталога (id категорий 2-го уровня), в которых продавец торгует
     * @param handlingDays  FBS: сколько дней собирает и передаёт заказ перевозчику (FBO — 0: собирает магазин)
     * @param quality       обычный, «похуже среднего» или «плохой»
     * @param returnFactor  множитель к базовой вероятности возврата строки
     * @param scoreShift    сдвиг средней оценки отзыва (баллов; плохой — около −1,6)
     * @param lateFactor    множитель к вероятности и величине опоздания отгрузки (FBS)
     * @param cancelFactor  множитель к вероятности отмены заказа продавцом
     * @param answerRate    доля отзывов, на которые продавец отвечает
     */
    public record Seller(int id, String name, String legalForm, int cityId, Model model, long joinedAt, long leftAt,
                         TaxRegime taxRegime, long revenue2025, double revenueGrowth, double weight,
                         List<Integer> departmentIds, int handlingDays, Quality quality, double returnFactor,
                         double scoreShift, double lateFactor, double cancelFactor, double answerRate) {

        /** Работает в момент {@code t}. */
        public boolean activeAt(long t) {
            return t >= joinedAt && t < leftAt;
        }

        public boolean fbo() {
            return model == Model.FBO;
        }

        /** Оборот за год, руб. */
        public long revenueRub(int year) {
            return StrictMath.round(revenue2025 * StrictMath.pow(revenueGrowth, year - 2025));
        }
    }

    /** Предложение на момент: строка {@code offers} (её id — в {@code order_items.offer_id}), продавец, цена. */
    public record OfferAt(int offerId, int sellerId, long priceKop, boolean fbo) {
    }

    /**
     * Листинг — продавец × товар: строки-периоды цены по порядку, {@code [from, to)} — от первой до конца последней.
     */
    public record Listing(int sellerId, int productId, long from, long to, int[] offerIds) {
    }

    private final List<Seller> sellers;
    private final int[] rowSeller, rowProduct;
    private final long[] rowFrom, rowTo, rowPrice;
    private final List<Listing> listings;
    private final int[] productOffset, productListings;
    private final List<List<Listing>> bySeller;

    /**
     * @param sellers    продавцы по id (индекс = id − 1)
     * @param maxProduct наибольший id товара
     * @param rowSeller  строки {@code offers} по id (индекс = id − 1): продавец, товар, с, по ({@link Long#MAX_VALUE}
     *                   — без конца), цена
     */
    public Market(List<Seller> sellers, int maxProduct, int[] rowSeller, int[] rowProduct, long[] rowFrom, long[] rowTo,
                  long[] rowPrice) {
        this.sellers = List.copyOf(sellers);
        this.rowSeller = rowSeller;
        this.rowProduct = rowProduct;
        this.rowFrom = rowFrom;
        this.rowTo = rowTo;
        this.rowPrice = rowPrice;
        int n = rowSeller.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.<Integer>comparingInt(i -> rowProduct[i]).thenComparingInt(i -> rowSeller[i])
                .thenComparingLong(i -> rowFrom[i]));
        List<Listing> ls = new ArrayList<>();
        for (int a = 0; a < n; ) {
            int b = a;
            while (b < n && rowProduct[idx[b]] == rowProduct[idx[a]] && rowSeller[idx[b]] == rowSeller[idx[a]]) b++;
            int[] ids = new int[b - a];
            for (int k = a; k < b; k++) ids[k - a] = idx[k] + 1;
            ls.add(new Listing(rowSeller[idx[a]], rowProduct[idx[a]], rowFrom[idx[a]], rowTo[idx[b - 1]], ids));
            a = b;
        }
        this.listings = Collections.unmodifiableList(ls);
        productOffset = new int[maxProduct + 2];
        for (Listing l : ls) productOffset[l.productId + 1]++;
        for (int i = 1; i < productOffset.length; i++) productOffset[i] += productOffset[i - 1];
        productListings = new int[ls.size()];
        int[] fill = Arrays.copyOf(productOffset, productOffset.length);
        for (int i = 0; i < ls.size(); i++) productListings[fill[ls.get(i).productId]++] = i;
        List<List<Listing>> bs = new ArrayList<>();
        for (int i = 0; i <= sellers.size(); i++) bs.add(new ArrayList<>());
        for (Listing l : ls) bs.get(l.sellerId).add(l);
        this.bySeller = bs;
    }

    // ---------------------------------------------------------------- продавцы

    public List<Seller> sellers() {
        return sellers;
    }

    public Seller seller(int id) {
        return sellers.get(id - 1);
    }

    /** Продавцы данного качества, по id. */
    public List<Seller> ofQuality(Quality q) {
        List<Seller> out = new ArrayList<>();
        for (Seller s : sellers) if (s.quality == q) out.add(s);
        return out;
    }

    // ---------------------------------------------------------------- НДС продавцов

    /**
     * Порог выручки за прошлый год (и нарастающим итогом за текущий), до которого продавец на УСН освобождён от НДС,
     * руб.: 2025 — 60 млн, 2026 — 20 млн, 2027 — 15 млн, с 2028 — 10 млн (налоговая реформа 2025 года; числа — по
     * открытым источникам на 14.11.2026, до выпуска сверить с Налоговым кодексом). До 2025 года УСН — без НДС.
     */
    public static long usnExemptionLimitRub(int year) {
        if (year <= 2025) return 60_000_000L;
        if (year == 2026) return 20_000_000L;
        if (year == 2027) return 15_000_000L;
        return 10_000_000L;
    }

    /** Пониженная ставка НДС на УСН сверх порога: 5 % до 250 млн руб. оборота, 7 % — до 450 млн. */
    public static int usnReducedRate(long revenueRub) {
        return revenueRub <= 250_000_000L ? 5 : 7;
    }

    /**
     * Ставка НДС продавца на дату, % — для {@code order_items.vat_rate} строки продавца: общий режим — 20 % (по
     * 31.12.2025) или 22 %; УСН — 0 (освобождён), если выручка прошлого года не выше порога года
     * ({@link #usnExemptionLimitRub}), а нарастающая выручка текущего года (равномерно по месяцам) ещё не превысила
     * его; с первого числа месяца после превышения — пониженная ставка ({@link #usnReducedRate}).
     */
    public int vatRate(int sellerId, LocalDate d) {
        Seller s = seller(sellerId);
        return vatRate(s.taxRegime, s.revenue2025, s.revenueGrowth, d);
    }

    /** То же по режиму и обороту (для генератора, до выдачи id). */
    static int vatRate(TaxRegime regime, long revenue2025, double growth, LocalDate d) {
        if (regime == TaxRegime.OSN) return Tax.vatRate(d);
        int y = d.getYear();
        if (y < 2025) return 0;
        long prev = StrictMath.round(revenue2025 * StrictMath.pow(growth, y - 1 - 2025));
        long cur = StrictMath.round(revenue2025 * StrictMath.pow(growth, y - 2025));
        long limit = usnExemptionLimitRub(y);
        int reduced = usnReducedRate(Math.max(prev, cur));
        if (prev > limit) return reduced;
        // нарастающим итогом: к концу месяца m — cur·m/12; превышение в месяце m → ставка с первого числа m+1
        long m = cur <= 0 ? 13 : (limit * 12) / cur + 1;
        if (m <= 11 && !d.isBefore(YearMonth.of(y, (int) m).plusMonths(1).atDay(1))) return reduced;
        return 0;
    }

    // ---------------------------------------------------------------- предложения

    /** Строк в {@code offers}. */
    public int offerCount() {
        return rowSeller.length;
    }

    public int offerSeller(int offerId) {
        return rowSeller[offerId - 1];
    }

    public int offerProduct(int offerId) {
        return rowProduct[offerId - 1];
    }

    public long offerFrom(int offerId) {
        return rowFrom[offerId - 1];
    }

    /** Конец периода строки; {@link Long#MAX_VALUE} — без конца в периоде данных. */
    public long offerTo(int offerId) {
        return rowTo[offerId - 1];
    }

    public long offerPriceKop(int offerId) {
        return rowPrice[offerId - 1];
    }

    public List<Listing> listings() {
        return listings;
    }

    /** Листинги товара (по продавцу). */
    public List<Listing> listingsOf(int productId) {
        if (productId + 1 >= productOffset.length) return List.of();
        List<Listing> out = new ArrayList<>(productOffset[productId + 1] - productOffset[productId]);
        for (int i = productOffset[productId]; i < productOffset[productId + 1]; i++) out.add(listings.get(productListings[i]));
        return out;
    }

    public List<Listing> listingsOfSeller(int sellerId) {
        return Collections.unmodifiableList(bySeller.get(sellerId));
    }

    /** Строка листинга, действующая в момент {@code t}; −1 — листинг в этот момент не действует. */
    public int offerIdAt(Listing l, long t) {
        if (t < l.from || t >= l.to) return -1;
        int lo = 0, hi = l.offerIds.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (rowFrom[l.offerIds[mid] - 1] <= t) lo = mid;
            else hi = mid - 1;
        }
        return l.offerIds[lo];
    }

    /** Предложения товара в момент {@code t} (по id продавца); пусто — продавцы его сейчас не продают. */
    public List<OfferAt> offersAt(int productId, long t) {
        if (productId + 1 >= productOffset.length) return List.of();
        List<OfferAt> out = new ArrayList<>();
        for (int i = productOffset[productId]; i < productOffset[productId + 1]; i++) {
            Listing l = listings.get(productListings[i]);
            int id = offerIdAt(l, t);
            if (id > 0) out.add(new OfferAt(id, l.sellerId, rowPrice[id - 1], seller(l.sellerId).fbo()));
        }
        return out;
    }

    /** Самое дешёвое предложение на момент; {@code null} — нет предложений. */
    public OfferAt cheapestAt(int productId, long t) {
        OfferAt best = null;
        for (OfferAt o : offersAt(productId, t)) if (best == null || o.priceKop < best.priceKop) best = o;
        return best;
    }

    /** Чувствительность выбора к цене: вес предложения ∝ (минимальная цена / цена)^PRICE_POWER. */
    public static final double PRICE_POWER = 6.0;
    /** Вес магазина (1P) против всех продавцов при равных ценах: при 1,5 магазин берёт ≈ 60 % покупок товара «оба». */
    public static final double SHOP_WEIGHT = 1.5;

    /**
     * Выбор покупателя для товара в момент {@code t}: {@code null} — купить у магазина (1P), иначе — предложение
     * продавца. Товар «только магазин» — всегда {@code null}; «только продавцы» — {@code null}, только если
     * предложений нет. Вес предложения — √(вес продавца) · (min/цена)^{@value #PRICE_POWER}; вес магазина — {@link
     * #SHOP_WEIGHT} · Σ√(вес продавца) · (min/цена 1P)^{@value #PRICE_POWER}.
     *
     * @param price1pKop цена магазина на момент ({@code PriceBook.priceAt}); ≤ 0 — магазин не продаёт
     */
    public OfferAt choose(int productId, long t, long price1pKop, Assortment assortment, Rng r) {
        if (assortment == Assortment.ONLY_1P) return null;
        List<OfferAt> offs = offersAt(productId, t);
        if (offs.isEmpty()) return null;
        boolean shop = assortment == Assortment.BOTH && price1pKop > 0;
        long min = shop ? price1pKop : Long.MAX_VALUE;
        for (OfferAt o : offs) min = Math.min(min, o.priceKop);
        double[] w = new double[offs.size() + 1];
        double sumSqrt = 0;
        for (int i = 0; i < offs.size(); i++) {
            double sq = StrictMath.sqrt(seller(offs.get(i).sellerId).weight);
            sumSqrt += sq;
            w[i] = sq * StrictMath.pow((double) min / offs.get(i).priceKop, PRICE_POWER);
        }
        w[offs.size()] = shop ? SHOP_WEIGHT * sumSqrt * StrictMath.pow((double) min / price1pKop, PRICE_POWER) : 0;
        int k = r.weighted(w);
        return k == offs.size() ? null : offs.get(k);
    }
}
