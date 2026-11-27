/*
  Дополнение к генератору демобазы: расширенная маршрутная сеть.

  Выполняется в базе demo из каталога генератора (пути \copy — относительные) после
  install.sql и до CALL generate(): таблица gen.airport_to_prob и прочие предрасчёты
  строятся на этапе INIT по gen.airports_data.traffic, поэтому сеть должна быть
  загружена раньше.

  Входы:
    hh/markets.tsv — рынки: code, name_en, name_ru (с заголовком);
    hh/network.tsv — аэропорты сети: code, country2, market, hub (t/f), traffic, why (с заголовком).

  Результат:
    gen.hh_markets, gen.hh_network — рынки и аэропорты сети (хабы — hub = true);
    gen.airports_data.traffic = traffic из network.tsv для аэропортов сети, NULL — для остальных.
*/

DROP TABLE IF EXISTS gen.hh_network;
DROP TABLE IF EXISTS gen.hh_markets;

CREATE UNLOGGED TABLE gen.hh_markets (
    market  text PRIMARY KEY,
    name_en text NOT NULL,
    name_ru text NOT NULL
);

\copy gen.hh_markets FROM 'hh/markets.tsv' WITH (FORMAT text, HEADER true)

CREATE UNLOGGED TABLE gen.hh_network (
    airport_code char(3) PRIMARY KEY,
    country_code char(2) NOT NULL,
    market       text    NOT NULL REFERENCES gen.hh_markets(market),
    hub          boolean NOT NULL,
    traffic      numeric NOT NULL CHECK (traffic > 0),
    why          text    NOT NULL
);

\copy gen.hh_network FROM 'hh/network.tsv' WITH (FORMAT text, HEADER true)

DO $$
DECLARE
    n integer;
    bad text;
BEGIN
    -- каждый аэропорт сети есть в справочнике генератора, и страна совпадает
    SELECT string_agg(n.airport_code, ', ') INTO bad
    FROM gen.hh_network n
      LEFT JOIN gen.airports_data a ON a.airport_code = n.airport_code
    WHERE a.airport_code IS NULL OR a.country_code <> n.country_code;
    ASSERT bad IS NULL, 'аэропорты сети не найдены в gen.airports_data или страна не совпадает: ' || bad;

    -- прежняя сеть (traffic в airports_data.dat) входит в новую с теми же значениями
    SELECT count(*), string_agg(a.airport_code || ' ' || a.traffic || ' -> ' || coalesce(n.traffic::text, 'нет'), ', ')
           FILTER (WHERE n.traffic IS DISTINCT FROM a.traffic)
    INTO n, bad
    FROM gen.airports_data a
      LEFT JOIN gen.hh_network n ON n.airport_code = a.airport_code
    WHERE a.traffic IS NOT NULL;
    ASSERT bad IS NULL, 'traffic прежних аэропортов отличается: ' || bad;
    RAISE NOTICE 'прежних аэропортов с traffic: %, значения совпадают', n;

    -- у каждого рынка есть хаб
    SELECT string_agg(m.market, ', ') INTO bad
    FROM gen.hh_markets m
    WHERE NOT EXISTS (SELECT 1 FROM gen.hh_network n WHERE n.market = m.market AND n.hub);
    ASSERT bad IS NULL, 'рынки без хабов: ' || bad;
END;
$$;

UPDATE gen.airports_data a
SET traffic = n.traffic
FROM gen.hh_network n
WHERE n.airport_code = a.airport_code;

UPDATE gen.airports_data a
SET traffic = NULL
WHERE a.traffic IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM gen.hh_network n WHERE n.airport_code = a.airport_code);

/*
  Часовые пояса аэропортов сети: устаревшие имена (ссылки из файла backward базы tz)
  заменяются каноническими. Сервер с системной tzdata без устаревших имён (например,
  Ubuntu 24.04 без пакета tzdata-legacy, Debian 13) их не знает, и next_flight()
  падает с «time zone ... not recognized». Замена — всегда, независимо от сервера,
  чтобы данные не зависели от машины; аэропорты вне сети не меняются.
*/
UPDATE gen.airports_data a
SET timezone = m.canonical
FROM (VALUES
    ('Africa/Asmera',         'Africa/Asmara'),
    ('America/Buenos_Aires',  'America/Argentina/Buenos_Aires'),
    ('America/Catamarca',     'America/Argentina/Catamarca'),
    ('America/Cordoba',       'America/Argentina/Cordoba'),
    ('America/Godthab',       'America/Nuuk'),
    ('America/Jujuy',         'America/Argentina/Jujuy'),
    ('America/Mendoza',       'America/Argentina/Mendoza'),
    ('Antarctica/South_Pole', 'Pacific/Auckland'),
    ('Asia/Rangoon',          'Asia/Yangon'),
    ('Asia/Saigon',           'Asia/Ho_Chi_Minh'),
    ('Atlantic/Faeroe',       'Atlantic/Faroe'),
    ('Europe/Kiev',           'Europe/Kyiv'),
    ('Pacific/Enderbury',     'Pacific/Kanton'),
    ('Pacific/Ponape',        'Pacific/Pohnpei'),
    ('Pacific/Truk',          'Pacific/Chuuk')
) m(legacy, canonical)
WHERE a.timezone = m.legacy
  AND a.airport_code IN (SELECT airport_code FROM gen.hh_network);

DO $$
DECLARE
    bad text;
BEGIN
    SELECT string_agg(a.airport_code || ' ' || a.timezone, ', ') INTO bad
    FROM gen.airports_data a
    WHERE a.traffic IS NOT NULL
      AND a.timezone NOT IN (SELECT name FROM pg_timezone_names);
    ASSERT bad IS NULL, 'сервер не знает часовых поясов аэропортов сети: ' || bad;
END;
$$;

DO $$
DECLARE
    n_net integer;
    n_gen integer;
    s_net numeric;
    s_gen numeric;
BEGIN
    SELECT count(*), sum(traffic) INTO n_net, s_net FROM gen.hh_network;
    SELECT count(*), sum(traffic) INTO n_gen, s_gen FROM gen.airports_data WHERE traffic IS NOT NULL;
    ASSERT n_net = n_gen AND s_net = s_gen,
        format('сеть не применилась: в файле %s / %s, в gen.airports_data %s / %s', n_net, s_net, n_gen, s_gen);
    RAISE NOTICE 'сеть: аэропортов %, хабов %, стран %, сумма traffic %',
        n_gen, (SELECT count(*) FROM gen.hh_network WHERE hub),
        (SELECT count(DISTINCT country_code) FROM gen.hh_network), s_gen;
END;
$$;
