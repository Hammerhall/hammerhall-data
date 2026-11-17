package io.hammerhall.data.air.load;

import io.hammerhall.data.air.dump.DumpScan;
import io.hammerhall.data.air.dump.DumpSource;
import io.hammerhall.data.air.dump.PgValues;
import io.hammerhall.data.air.select.Network;
import io.hammerhall.data.air.select.Selection;
import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Key;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сеть: аэропорты, самолёты, места, маршруты, рейсы — из выгрузки целиком (справочник аэропортов — без
 * {@link #EXCLUDED_AIRPORTS}); затем отбор броней ({@link Selection}) — он нужен модулю продаж.
 */
public final class NetworkModule implements Module {
    /** Отбор броней — для модуля продаж и манифеста. */
    public static final Key<Selection> SELECTION = Key.of("air.selection");

    /**
     * Справочник аэропортов — без нескольких аэропортов источника; в сеть маршрутов они не входят (рейсы и брони это
     * не задевает).
     */
    public static final List<String> EXCLUDED_AIRPORTS = List.of("KHC", "SIP", "UKS");

    private final DumpSource source;
    private long flightsWithoutRoute = -1, flightsOutsidePeriod, routeOverlaps;
    private final List<String> examples = new ArrayList<>();

    public NetworkModule(DumpSource source) {
        this.source = source;
    }

    @Override
    public String name() {
        return "network";
    }

    @Override
    public List<String> tables() {
        return List.of("airports", "airplanes", "seats", "routes", "flights");
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        DumpScan scan = source.scan(ctx.config().input("dump"));
        Map<String, Network.Airport> airports = new LinkedHashMap<>();
        List<Network.Route> routes = new ArrayList<>();
        List<Network.Flight> flights = new ArrayList<>();

        TableSink s = ctx.sink("airports");
        for (String[] r : scan.small.get("airports_data")) {
            if (EXCLUDED_AIRPORTS.contains(r[0])) continue;
            String countryEn = PgValues.jsonString(r[3], "en");
            s.add(r[0], PgValues.jsonString(r[1], "en"), PgValues.jsonString(r[1], "ru"),
                    PgValues.jsonString(r[2], "en"), PgValues.jsonString(r[2], "ru"),
                    countryEn, PgValues.jsonString(r[3], "ru"),
                    PgValues.decimalAsIs(PgValues.pointPart(r[4], 1), 6), PgValues.decimalAsIs(PgValues.pointPart(r[4], 2), 6),
                    r[5]);
            airports.put(r[0], new Network.Airport(r[0], countryEn, r[5]));
        }
        ctx.close("airports");

        s = ctx.sink("airplanes");
        for (String[] r : scan.small.get("airplanes_data")) {
            s.add(r[0], PgValues.jsonString(r[1], "en"), PgValues.jsonString(r[1], "ru"), PgValues.intAsIs(r[2]),
                    PgValues.intAsIs(r[3]));
        }
        ctx.close("airplanes");

        s = ctx.sink("seats");
        for (String[] r : scan.small.get("seats")) s.add(r[0], r[1], r[2]);
        ctx.close("seats");

        s = ctx.sink("routes");
        for (String[] r : scan.small.get("routes")) {
            long from = PgValues.rangeBound(r[1], 1), to = PgValues.rangeBound(r[1], 2);
            s.add(r[0], from, to, r[2], r[3], r[4], PgValues.trim(r[5], "{}"), r[6], PgValues.durationMinutes(r[7]));
            routes.add(new Network.Route(r[0], from, to, r[2]));
        }
        ctx.close("routes");

        s = ctx.sink("flights");
        for (String[] r : scan.small.get("flights")) {
            int id = Integer.parseInt(r[0]);
            long dep = PgValues.epoch(r[3]);
            s.add(id, r[1], r[2], dep, PgValues.epoch(r[4]), PgValues.epoch(r[5]), PgValues.epoch(r[6]));
            flights.add(new Network.Flight(id, r[1], dep));
            if (!ctx.period().contains(dep)) flightsOutsidePeriod++;
        }
        ctx.close("flights");
        overlaps(routes);

        ctx.log("сеть: аэропортов " + airports.size() + ", маршрутов (версий) " + routes.size() + ", рейсов " + flights.size());
        Network net = new Network(airports, routes, flights);
        Selection sel = Selection.compute(net, scan.bookCodes, scan.ticketCode, scan.ticketBook, scan.segmentTicket,
                scan.segmentFlight, ctx.profile().scale(), ctx.stream("air.small"), ctx::log);
        scan.releaseKeys();
        flightsWithoutRoute = sel.stats().flightsWithoutRoute();
        ctx.put(SELECTION, sel);
    }

    /** Версии одного маршрута не пересекаются (в источнике — ограничение EXCLUDE). */
    private void overlaps(List<Network.Route> routes) {
        List<Network.Route> sorted = new ArrayList<>(routes);
        sorted.sort((a, b) -> a.routeNo().equals(b.routeNo()) ? Long.compare(a.validFrom(), b.validFrom())
                : a.routeNo().compareTo(b.routeNo()));
        for (int i = 1; i < sorted.size(); i++) {
            Network.Route p = sorted.get(i - 1), r = sorted.get(i);
            if (p.routeNo().equals(r.routeNo()) && r.validFrom() < p.validTo()) {
                routeOverlaps++;
                if (examples.size() < 5) examples.add(r.routeNo() + " с " + r.validFrom());
            }
        }
    }

    @Override
    public void check(GenContext ctx, CheckReport report) {
        report.expectNone("маршруты: версии одного маршрута не пересекаются", routeOverlaps, examples, "пересечений нет");
        report.expectNone("рейсы: у каждого рейса есть версия маршрута на плановый вылет", flightsWithoutRoute,
                List.of(), "у всех");
        report.expectNone("рейсы: плановый вылет — в окне " + ctx.period().from() + " … " + ctx.period().to(),
                flightsOutsidePeriod, List.of(), "все в окне");
    }

    @Override
    public List<Aggregate> aggregates() {
        return List.of(
                Aggregate.distinct("airports", "country_en"),
                Aggregate.distinct("airports", "timezone"),
                Aggregate.distinct("routes", "route_no"),
                Aggregate.distinct("routes", "departure_airport"),
                Aggregate.min("routes", "valid_from"), Aggregate.max("routes", "valid_to"),
                Aggregate.sum("routes", "duration_min"),
                Aggregate.countEq("seats", "fare_conditions", "Business"),
                Aggregate.min("flights", "scheduled_departure"), Aggregate.max("flights", "scheduled_departure"),
                Aggregate.countEq("flights", "status", "Arrived"),
                Aggregate.countEq("flights", "status", "Cancelled"),
                Aggregate.countEq("flights", "status", "Scheduled"),
                Aggregate.sum("flights", "actual_arrival"));
    }
}
