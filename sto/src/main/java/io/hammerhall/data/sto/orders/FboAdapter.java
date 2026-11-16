package io.hammerhall.data.sto.orders;

import io.hammerhall.data.sto.catalog.Catalog;
import io.hammerhall.data.sto.geo.Geo;
import io.hammerhall.data.sto.market.Market;
import io.hammerhall.data.sto.stock.CatalogForecast;
import io.hammerhall.data.sto.stock.FboListings;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Позиции продавцов FBO на складах магазина — из листингов маркетплейса: товар продавца лежит на РЦ его округа
 * (продавцы центрального округа — на хабе). Ожидаемый расход позиции — спрос продавцов на товар (та же нормировка, что
 * у прогноза магазина, доля «не магазина») × доля продавца среди предложений на момент (по формуле выбора покупателя
 * {@link Market#choose}) × запас 1,3; срок подвоза — от расстояния от города продавца до склада.
 */
final class FboAdapter implements FboListings {
    private final World w;
    private final List<Market.Listing> fbo = new ArrayList<>();
    private final int[] whOf, lead;
    private final double kappa;

    FboAdapter(World w) {
        this.w = w;
        Market m = w.market;
        for (Market.Listing l : m.listings()) if (m.seller(l.sellerId()).fbo()) fbo.add(l);
        fbo.sort((a, b) -> a.sellerId() != b.sellerId() ? Integer.compare(a.sellerId(), b.sellerId())
                : Integer.compare(a.productId(), b.productId()));
        Map<String, Geo.Warehouse> dcOf = new TreeMap<>();
        for (Geo.Warehouse x : w.geo.ofKind(Geo.Kind.DC)) dcOf.putIfAbsent(x.city().district(), x);
        Geo.Warehouse hub = w.geo.ofKind(Geo.Kind.HUB).getFirst();
        whOf = new int[fbo.size()];
        lead = new int[fbo.size()];
        for (int i = 0; i < fbo.size(); i++) {
            Market.Seller s = m.seller(fbo.get(i).sellerId());
            Geo.City c = w.geo.city(s.cityId());
            Geo.Warehouse wh = c.district().equals("central") ? hub : dcOf.getOrDefault(c.district(), hub);
            whOf[i] = wh.id();
            lead[i] = 2 + (int) StrictMath.ceil(World.km(c.lat(), c.lon(), wh.lat(), wh.lon()) / 650.0);
        }
        double sum = 0;
        LocalDate d0 = w.period.from();
        for (Catalog.Product p : w.cat.products()) {
            if (p.aliveOn(d0)) sum += p.popularity * w.cat.lifecycle(p, d0) * CatalogForecast.share1P(p);
        }
        kappa = sum > 0 ? CatalogForecast.LINES_PER_DAY_FULL / sum : 0;
    }

    int listings() {
        return fbo.size();
    }

    @Override
    public void forEach(LocalDate d, Sink sink) {
        long t = World.dayStart(d) + 12 * World.HOUR;
        Market m = w.market;
        for (int i = 0; i < fbo.size(); i++) {
            Market.Listing l = fbo.get(i);
            if (t < l.from() || t >= l.to()) continue;
            Market.Seller s = m.seller(l.sellerId());
            if (!s.activeAt(t)) continue;
            Catalog.Product p = w.cat.product(l.productId());
            if (!p.aliveOn(d)) continue;
            int offerId = m.offerIdAt(l, t);
            if (offerId <= 0) continue;
            long price = m.offerPriceKop(offerId);
            double share = share(p, l.sellerId(), price, t);
            double rate = kappa * p.popularity * w.cat.lifecycle(p, d) * (1 - CatalogForecast.share1P(p)) * w.scale
                    * CatalogForecast.meanQty(p) * share * 1.3;
            if (p.assortment == Catalog.Assortment.ONLY_3P) rate = kappa * p.popularity * w.cat.lifecycle(p, d) * w.scale
                    * CatalogForecast.meanQty(p) * share * 1.3;
            long cost = Math.max(100, price * 55 / 120);
            sink.accept(new Listing(l.sellerId(), l.productId(), whOf[i], rate, cost, lead[i]));
        }
    }

    /** Ожидаемая доля продавца в покупках товара по формуле выбора покупателя. */
    private double share(Catalog.Product p, int seller, long price, long t) {
        Market m = w.market;
        List<Market.OfferAt> offs = m.offersAt(p.id, t);
        long p1 = w.prices.priceAt(p.id, t);
        boolean shop = p.assortment == Catalog.Assortment.BOTH && p1 > 0;
        long min = shop ? p1 : Long.MAX_VALUE;
        for (Market.OfferAt o : offs) min = Math.min(min, o.priceKop());
        double tot = 0, mine = 0, sumSqrt = 0;
        for (Market.OfferAt o : offs) {
            double sq = StrictMath.sqrt(m.seller(o.sellerId()).weight());
            sumSqrt += sq;
            double x = sq * StrictMath.pow((double) min / o.priceKop(), Market.PRICE_POWER);
            tot += x;
            if (o.sellerId() == seller) mine = x;
        }
        if (shop) tot += Market.SHOP_WEIGHT * sumSqrt * StrictMath.pow((double) min / p1, Market.PRICE_POWER);
        // доля среди покупок «у продавцов»: прогноз выше уже считает только долю не магазина
        double sellersTot = tot - (shop ? Market.SHOP_WEIGHT * sumSqrt * StrictMath.pow((double) min / p1, Market.PRICE_POWER) : 0);
        return sellersTot > 0 ? mine / sellersTot : 0;
    }
}
