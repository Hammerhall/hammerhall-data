/*
  Дополнение к генератору демобазы: имена пассажиров для стран расширенной сети.

  Выполняется в базе demo из каталога генератора после hh/network.sql.
  Строки hh/firstnames_extra.dat и hh/lastnames_extra.dat (формат firstnames.dat /
  lastnames.dat: country, grp, name, qty, cume_dist = \N) дописываются к gen.firstnames
  и gen.lastnames, после чего cume_dist пересчитывается для всех стран одной и той же
  процедурой calc_names_cume_dist() (names.sql). Её же генератор ещё раз вызывает на
  этапе INIT (do_init), так что значения всегда согласованы.

  Страна пассажира — страна аэропорта вылета бронирования (make_booking():
  country_code аэропорта airport_from → get_passenger(country_code, ...) →
  get_passenger_id() / get_passenger_name()). Если у страны нет имён или фамилий
  нужной группы, get_passenger_name() зацикливается, поэтому ниже это проверяется
  для всех стран сети.
*/

CREATE TEMP TABLE hh_firstnames (LIKE gen.firstnames);
CREATE TEMP TABLE hh_lastnames (LIKE gen.lastnames);

\copy hh_firstnames FROM 'hh/firstnames_extra.dat'
\copy hh_lastnames FROM 'hh/lastnames_extra.dat'

DO $$
DECLARE
    bad text;
BEGIN
    -- новые страны не пересекаются со странами авторов
    SELECT string_agg(DISTINCT country, ', ') INTO bad
    FROM (
        SELECT country FROM hh_firstnames WHERE country IN (SELECT country FROM gen.firstnames)
        UNION ALL
        SELECT country FROM hh_lastnames WHERE country IN (SELECT country FROM gen.lastnames)
    ) t;
    ASSERT bad IS NULL, 'страны уже есть в справочниках генератора: ' || bad;
END;
$$;

INSERT INTO gen.firstnames(country, grp, name, qty)
SELECT country, grp, name, qty FROM hh_firstnames;

INSERT INTO gen.lastnames(country, grp, name, qty)
SELECT country, grp, name, qty FROM hh_lastnames;

CALL calc_names_cume_dist();

DO $$
DECLARE
    bad text;
BEGIN
    -- у каждой страны с аэропортами сети есть имена
    SELECT string_agg(DISTINCT a.country_code, ', ') INTO bad
    FROM gen.airports_data a
    WHERE a.traffic IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM gen.firstnames f WHERE f.country = a.country_code);
    ASSERT bad IS NULL, 'нет имён для стран: ' || bad;

    -- для каждой группы имён есть фамилии той же группы
    SELECT string_agg(DISTINCT f.country || '/' || f.grp, ', ') INTO bad
    FROM gen.firstnames f
    WHERE NOT EXISTS (SELECT 1 FROM gen.lastnames l WHERE l.country = f.country AND l.grp = f.grp);
    ASSERT bad IS NULL, 'нет фамилий для групп: ' || bad;

    -- cume_dist посчитан везде и доходит до 1
    SELECT string_agg(country, ', ') INTO bad
    FROM (
        SELECT country FROM gen.firstnames GROUP BY country
        HAVING count(cume_dist) <> count(*) OR abs(max(cume_dist) - 1) > 1e-9
        UNION ALL
        SELECT country || '/' || grp FROM gen.lastnames GROUP BY country, grp
        HAVING count(cume_dist) <> count(*) OR abs(max(cume_dist) - 1) > 1e-9
    ) t;
    ASSERT bad IS NULL, 'cume_dist не посчитан: ' || bad;

    RAISE NOTICE 'имена: стран %, имён %, фамилий %',
        (SELECT count(DISTINCT country) FROM gen.firstnames),
        (SELECT count(*) FROM gen.firstnames),
        (SELECT count(*) FROM gen.lastnames);
END;
$$;

DROP TABLE hh_firstnames;
DROP TABLE hh_lastnames;
