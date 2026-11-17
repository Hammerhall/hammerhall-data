package io.hammerhall.data.air.select;

import java.util.List;
import java.util.Map;

/**
 * Сеть, нужная отбору: аэропорты справочника выпуска (страна, пояс), версии маршрутов (период и аэропорт вылета) и
 * рейсы (маршрут и плановый вылет, секунды Unix) — уже в значениях выпуска.
 */
public record Network(Map<String, Airport> airports, List<Route> routes, List<Flight> flights) {
    public record Airport(String code, String country, String timezone) {
    }

    public record Route(String routeNo, long validFrom, long validTo, String departure) {
    }

    public record Flight(int id, String routeNo, long scheduledDeparture) {
    }
}
