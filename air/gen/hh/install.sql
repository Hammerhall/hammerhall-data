/*
  Дополнения к генератору демобазы: расширенная сеть, имена, хабы.

  Запуск — после install.sql, в базе demo, из каталога генератора:
      \i hh/install.sql
  (или hh/run.sh install). До CALL generate(): сеть и имена нужны уже на этапе INIT.

  Параметры уровня базы (ALTER DATABASE demo SET ...), меняются до generate():
    gen.hh_hubs            = on  — постоянные направления хабов (hh/routes.sql);
    gen.hh_spokes          = on  — и спицы: не-хаб — к ближайшему хабу своего рынка;
    gen.hh_names_tiebreak  = on  — различать имена с одинаковым qty (см. names.sql);
    gen.hh_min_km          = 150 — пары аэропортов ближе 150 км не становятся ни
                                   назначением брони, ни направлением графа, ни спицей
                                   (engine.sql, do_init; hh/routes.sql).
*/

\set ON_ERROR_STOP on

ALTER DATABASE demo SET gen.hh_hubs = 'on';
ALTER DATABASE demo SET gen.hh_spokes = 'on';
ALTER DATABASE demo SET gen.hh_names_tiebreak = 'on';
ALTER DATABASE demo SET gen.hh_min_km = '150';
\c

SET search_path = public; -- процедуры — в public, как у авторов

\i hh/network.sql
\i hh/names.sql
\i hh/routes.sql

SET search_path = bookings,"$user",public;
