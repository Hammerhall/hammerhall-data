/*
  Дополнение к генератору демобазы: постоянные направления хабов и спиц.

  Процедура вызывается из build_routes() (route.sql, место помечено «изменено») после
  случайного графа (по два направления от каждого аэропорта) и до досборки связности,
  только при gen.hh_hubs = on. Направления добавляются через add_direction() (туда и
  обратно, с учётом связности); самолёт и частоту рейсов потом, как обычно, выбирают
  add_route() / suitable_aircraft() по оценке пассажиропотока gen.week_traffic.

  (а) два крупнейших по traffic хаба каждого рынка — к двум крупнейшим хабам каждого
      другого рынка («топ-2 ↔ топ-2»), если расстояние не больше дальности самого
      дальнего самолёта in_use; у остальных хабов межрыночных постоянных направлений нет;
  (б) при gen.hh_spokes = on (по умолчанию) каждый не-хаб — к ближайшему хабу своего
      рынка, который не ближе gen.hh_min_km (кроме аэропортов того же города: их
      генератор не соединяет).
  Пары ближе gen.hh_min_km (км; по умолчанию 0) не соединяются ни по (а), ни по (б).

  Хабы и рынки — gen.hh_network (hh/network.sql). Расстояние считается так же, как в
  suitable_aircraft(): (coordinates <@> coordinates) * 1.609344, км.
*/

CREATE OR REPLACE PROCEDURE hh_permanent_directions()
AS $$
DECLARE
    max_range float;
    min_km float;
    spokes boolean;
    r record;
    n_hub_new integer := 0;
    n_hub_old integer := 0;
    n_spoke_new integer := 0;
    n_spoke_old integer := 0;
    n_far integer := 0;
BEGIN
    max_range := (SELECT max(range) FROM gen.airplanes_data WHERE in_use);
    spokes := coalesce(current_setting('gen.hh_spokes', true), 'on') = 'on';
    min_km := coalesce(nullif(current_setting('gen.hh_min_km', true), '')::float, 0);

    -- (а) два крупнейших хаба рынка — к двум крупнейшим хабам каждого другого рынка
    FOR r IN
        WITH hubs AS (
            SELECT n.airport_code, n.market, a.coordinates,
                   row_number() OVER (PARTITION BY n.market ORDER BY a.traffic DESC, n.airport_code) AS rn
            FROM gen.hh_network n
              JOIN gen.airports_data a ON a.airport_code = n.airport_code
            WHERE n.hub
              AND a.traffic IS NOT NULL
        )
        SELECT h1.airport_code AS airport_from,
               h2.airport_code AS airport_to,
               (h1.coordinates <@> h2.coordinates) * 1.609344 AS distance
        FROM hubs h1
          JOIN hubs h2 ON h2.market <> h1.market AND h2.rn <= 2
        WHERE h1.rn <= 2
          AND (h1.coordinates <@> h2.coordinates) * 1.609344 >= min_km
        ORDER BY h1.airport_code, h2.airport_code
    LOOP
        IF r.distance > max_range THEN
            n_far := n_far + 1;
        ELSIF add_direction(r.airport_from, r.airport_to) THEN
            n_hub_new := n_hub_new + 1;
        ELSE
            n_hub_old := n_hub_old + 1;
        END IF;
    END LOOP;

    -- (б) не-хаб — к ближайшему хабу своего рынка не ближе min_km
    IF spokes THEN
        FOR r IN
            SELECT DISTINCT ON (s.airport_code)
                   s.airport_code AS airport_from,
                   h.airport_code AS airport_to,
                   (sa.coordinates <@> ha.coordinates) * 1.609344 AS distance
            FROM gen.hh_network s
              JOIN gen.airports_data sa ON sa.airport_code = s.airport_code
              JOIN gen.hh_network h ON h.market = s.market AND h.hub
              JOIN gen.airports_data ha ON ha.airport_code = h.airport_code
            WHERE NOT s.hub
              AND sa.traffic IS NOT NULL
              AND ha.traffic IS NOT NULL
              AND sa.city <> ha.city
              AND (sa.coordinates <@> ha.coordinates) * 1.609344 >= min_km
            ORDER BY s.airport_code, sa.coordinates <@> ha.coordinates, h.airport_code
        LOOP
            IF r.distance > max_range THEN
                n_far := n_far + 1;
            ELSIF add_direction(r.airport_from, r.airport_to) THEN
                n_spoke_new := n_spoke_new + 1;
            ELSE
                n_spoke_old := n_spoke_old + 1;
            END IF;
        END LOOP;
    END IF;

    CALL log_message(0, format(
        'Hub directions: hub-hub %s new + %s existing, spokes %s new + %s existing (spokes %s), %s out of range %s km, min %s km',
        n_hub_new, n_hub_old, n_spoke_new, n_spoke_old,
        CASE WHEN spokes THEN 'on' ELSE 'off' END, n_far, max_range, min_km));
END;
$$ LANGUAGE plpgsql;
