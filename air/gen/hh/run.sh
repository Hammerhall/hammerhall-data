#!/usr/bin/env bash
#
# Запуск генератора демобазы с дополнениями из hh/ (расширенная сеть, имена, хабы).
# Один и тот же скрипт — для локального кластера и для контейнера postgres:16
# (внутри контейнера: bash + psql, пользователь postgres, локальный сокет).
#
# Подключение — через стандартные переменные libpq: PGHOST, PGPORT, PGUSER (и при
# необходимости PGPASSWORD). Если задан PGBIN, он ставится первым в PATH (psql, pg_dump
# той же основной версии, что и сервер).
#
#   hh/run.sh install [mode=net|base] [spokes=on|off] [names_tiebreak=on|off] [min_km=N]
#                     [traffic_coeff=N] [max_hops=N]
#       Создать базу demo заново (install.sql авторов), затем при mode=net (по умолчанию)
#       загрузить сеть, имена и процедуру хабов (hh/install.sql). mode=base — только
#       авторская установка (исходная сеть из 73 аэропортов, без дополнений).
#       Остальные gen.* — как в install.sql (traffic_coeff по умолчанию 50, max_hops — 4);
#       min_km — gen.hh_min_km (по умолчанию 150, только для mode=net).
#   hh/run.sh start <начало> <конец> <jobs>
#       CALL generate(<начало>, <конец>, <jobs>). Процессы генерации работают в фоне
#       (dblink); сеанс запуска держится открытым в фоне (setsid) до конца генерации,
#       команда возвращается сразу. HH_FOREGROUND=1 — ждать конца на переднем плане.
#       При jobs > 1 — сначала разгон одним процессом до <начала> (см. cmd_start).
#       При ошибке в gen.log генерация прерывается (HH_ABORT_ON_ERROR=0 — не прерывать).
#   hh/run.sh continue <конец> <jobs>
#       CALL continue(<конец>, <jobs>) — продолжить остановившуюся генерацию (в том числе
#       после abort и штатной остановки сервера). События, которые обрабатывались в момент
#       остановки (pid задан, процесса нет), возвращаются в очередь: их транзакции откатились.
#   hh/run.sh status
#       Модельное время, скорость (событий/с), бронирования, «нет пути», ошибки, размер.
#   hh/run.sh export <папка> [имя]
#       export.sh авторов → gzip → части по 1900 МБ (split) → SHA256SUMS и MANIFEST.txt.
#   hh/run.sh check     — check.sql авторов (после окончания генерации).
#   hh/run.sh abort     — CALL abort().
#
set -euo pipefail

HH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GEN_DIR="$(cd "$HH_DIR/.." && pwd)"
[ -n "${PGBIN:-}" ] && export PATH="$PGBIN:$PATH"
PART_SIZE="${PART_SIZE:-1900M}"
STATE_FILE="${STATE_FILE:-${TMPDIR:-/tmp}/demogen-status-${PGPORT:-5432}.prev}"

die() { echo "ошибка: $*" >&2; exit 1; }
q() { psql -X -v ON_ERROR_STOP=1 -At -d "${1}" -c "${2}"; }   # одно значение / строки без оформления
busy() { [ "$(q demo 'SELECT busy()' 2>/dev/null || echo f)" = t ]; }
db_exists() { [ "$(q postgres "SELECT count(*) FROM pg_database WHERE datname = 'demo'")" = 1 ]; }

connstr() {
    local s="dbname=demo"
    [ -n "${PGHOST:-}" ] && s="$s host=$PGHOST"
    [ -n "${PGPORT:-}" ] && s="$s port=$PGPORT"
    [ -n "${PGUSER:-}" ] && s="$s user=$PGUSER"
    echo "$s"
}

cmd_install() {
    local mode=net spokes="" tiebreak="" coeff="" minkm="" hops="" kv
    for kv in "$@"; do
        case "$kv" in
            mode=net|mode=base) mode="${kv#mode=}" ;;
            spokes=on|spokes=off) spokes="${kv#spokes=}" ;;
            names_tiebreak=on|names_tiebreak=off) tiebreak="${kv#names_tiebreak=}" ;;
            traffic_coeff=*) coeff="${kv#traffic_coeff=}"; [[ "$coeff" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "traffic_coeff: $coeff" ;;
            min_km=*) minkm="${kv#min_km=}"; [[ "$minkm" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "min_km: $minkm" ;;
            max_hops=*) hops="${kv#max_hops=}"; [[ "$hops" =~ ^[1-9]$ ]] || die "max_hops: $hops" ;;
            *) die "неизвестный параметр install: $kv" ;;
        esac
    done
    [ "$mode" = base ] && { [ -n "$spokes$tiebreak$minkm" ] && die "spokes/names_tiebreak/min_km — только для mode=net"; }

    q postgres 'SELECT 1' >/dev/null || die "нет подключения к серверу (PGHOST/PGPORT/PGUSER?)"
    for e in btree_gist cube earthdistance dblink; do
        [ "$(q postgres "SELECT count(*) FROM pg_available_extensions WHERE name = '$e'")" = 1 ] \
            || die "нет расширения $e"
    done
    if db_exists; then
        busy && die "генерация идёт — сначала дождитесь конца или hh/run.sh abort"
        # прочие сеансы (например, оставшийся сеанс запуска) мешают DROP DATABASE в install.sql
        q postgres "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity
                    WHERE datname = 'demo' AND pid <> pg_backend_pid()" >/dev/null
        sleep 1
    else
        # install.sql начинается с DROP DATABASE demo — база должна существовать
        q postgres 'CREATE DATABASE demo' >/dev/null
    fi

    cd "$GEN_DIR"
    echo "== install.sql (авторская установка)"
    psql -X -q -v ON_ERROR_STOP=1 -d postgres -f install.sql
    q postgres "ALTER DATABASE demo SET gen.connstr = '$(connstr)'" >/dev/null

    if [ "$mode" = net ]; then
        echo "== hh/install.sql (сеть, имена, хабы)"
        psql -X -q -v ON_ERROR_STOP=1 -d demo -f hh/install.sql
        [ -n "$spokes" ] && q postgres "ALTER DATABASE demo SET gen.hh_spokes = '$spokes'" >/dev/null
        [ -n "$tiebreak" ] && q postgres "ALTER DATABASE demo SET gen.hh_names_tiebreak = '$tiebreak'" >/dev/null
        [ -n "$minkm" ] && q postgres "ALTER DATABASE demo SET gen.hh_min_km = '$minkm'" >/dev/null
        if [ -n "$tiebreak" ]; then
            psql -X -q -v ON_ERROR_STOP=1 -d demo -c 'CALL calc_names_cume_dist()'
        fi
    fi
    [ -n "$coeff" ] && q postgres "ALTER DATABASE demo SET gen.traffic_coeff = $coeff" >/dev/null
    [ -n "$hops" ] && q postgres "ALTER DATABASE demo SET gen.max_hops = $hops" >/dev/null

    echo "== готово: режим $mode"
    psql -X -v ON_ERROR_STOP=1 -d demo -c "
        SELECT count(*) AS airports, sum(traffic) AS traffic_sum,
               count(DISTINCT country_code) AS countries
        FROM gen.airports_data WHERE traffic IS NOT NULL;" \
        -c "
        SELECT s.name, s.setting
        FROM pg_db_role_setting d
          CROSS JOIN LATERAL unnest(d.setconfig) c
          CROSS JOIN LATERAL (SELECT split_part(c, '=', 1) AS name, substr(c, strpos(c, '=') + 1) AS setting) s
        WHERE d.setdatabase = (SELECT oid FROM pg_database WHERE datname = 'demo') AND d.setrole = 0
          AND s.name LIKE 'gen.%'
        ORDER BY 1;"
}

# Сеанс, вызвавший generate()/continue(), должен жить, пока работают процессы: они
# подключены к нему через dblink, и сообщение NOTICE (например, от TRUNCATE ... CASCADE
# в do_init) в закрытое соединение завершает процесс с FATAL «connection to client lost»
# — событие INIT так и остаётся недообработанным. Поэтому psql держится открытым (вход —
# из цикла, который раз в минуту проверяет busy()) и закрывается после конца генерации.
# Сам этот сеанс запросов не выполняет и снимков не держит.
# Ошибка в процессе генерации (строка «Error at event ...» в gen.log) завершает только
# этот процесс, его событие остаётся необработанным навсегда, остальные продолжают —
# данные уже испорчены. Поэтому при HH_ABORT_ON_ERROR=1 (по умолчанию) генерация
# прерывается целиком (CALL abort()).
hold_session() {   # hold_session <SQL-вызов>...: вызовы по очереди, каждый — в своём сеансе
    local c
    for c in "$@"; do
        {
            echo "$c"
            sleep 20
            while [ "$(q demo 'SELECT busy()' 2>/dev/null || echo t)" = t ]; do
                if [ "${HH_ABORT_ON_ERROR:-1}" = 1 ] \
                   && [ "$(q demo "SELECT count(*) FROM gen.log WHERE message LIKE 'Error%'" 2>/dev/null || echo 0)" != 0 ]; then
                    echo "\\echo 'ошибка в gen.log — генерация прервана (CALL abort())'"
                    echo "SELECT at, message FROM gen.log WHERE message LIKE 'Error%' ORDER BY at;"
                    echo "CALL abort();"
                    break
                fi
                sleep 60
            done
            echo "SELECT 'этап завершён', now();"
        } | psql -X -v ON_ERROR_STOP=1 -d demo || return 1
        [ "$(q demo "SELECT count(*) FROM gen.log WHERE message LIKE 'Error%'")" = 0 ] || return 1
    done
    echo "генерация завершена"
}

# Разгон: при jobs > 1 сначала generate(<начало>, <начало>, 1) — один процесс доходит
# до начала продаж, — затем continue(<конец>, <jobs>). Иначе, пока первый BUILD ROUTES
# (на этапе INIT, модельно за месяц до начала) строит маршруты в одной транзакции,
# остальные процессы уже берут из очереди бронирования начала продаж, рейсов для них
# ещё не видно, и все они уходят в nopath (на сети из 304 аэропортов — около 1 500
# первых попыток). HH_WARMUP=0 — запускать как у авторов, одним generate().
cmd_start() {   # start <начало> <конец> <jobs> | continue <конец> <jobs>
    local log
    local -a calls
    if [ "$1" = continue ]; then
        [ $# -eq 3 ] || die "использование: continue <конец> <jobs>"
        [[ "$3" =~ ^[0-9]+$ ]] || die "jobs: $3"
        q demo "SELECT '$2'::timestamptz" >/dev/null || die "неверная дата"
        calls=("UPDATE gen.events SET pid = NULL WHERE pid IS NOT NULL; CALL continue('$2'::timestamptz, $3);")
    else
        [ $# -eq 3 ] || die "использование: start <начало> <конец> <jobs>"
        [[ "$3" =~ ^[0-9]+$ ]] || die "jobs: $3"
        q demo "SELECT '$1'::timestamptz, '$2'::timestamptz" >/dev/null || die "неверные даты"
        if [ "$3" -gt 1 ] && [ "${HH_WARMUP:-1}" != 0 ]; then
            calls=("CALL generate('$1'::timestamptz, '$1'::timestamptz, 1);"
                   "CALL continue('$2'::timestamptz, $3);")
        else
            calls=("CALL generate('$1'::timestamptz, '$2'::timestamptz, $3);")
        fi
    fi
    busy && die "генерация уже идёт"
    rm -f "$STATE_FILE"
    log="${START_LOG:-${TMPDIR:-/tmp}/demogen-start-${PGPORT:-5432}.log}"
    if [ -n "${HH_FOREGROUND:-}" ]; then
        hold_session "${calls[@]}"
    else
        export -f hold_session q
        setsid nohup bash -c 'hold_session "$@"' _ "${calls[@]}" > "$log" 2>&1 < /dev/null &
        sleep 10
        cat "$log"
        q demo "SELECT 'процессов генерации: ' || count(*) FROM pg_stat_activity WHERE application_name = 'Airlines processor'"
        echo "сеанс запуска держится в фоне до конца генерации; журнал: $log"
    fi
}

cmd_status() {
    local now_s ev prev_s prev_ev
    now_s=$(date +%s)
    ev=$(q demo "SELECT coalesce(sum(events), 0) FROM gen.stat_jobs")
    psql -X -v ON_ERROR_STOP=1 -d demo -P pager=off <<'SQL'
\pset footer off
SELECT busy() AS busy,
       (SELECT count(*) FROM pg_stat_activity WHERE application_name = 'Airlines processor') AS jobs,
       (SELECT min(at) FROM gen.events WHERE pid IS NULL) AS model_now,
       bookings.now() AS model_end,
       (SELECT max(at) FROM gen.log) - (SELECT min(at) FROM gen.log) AS real_elapsed;

WITH d AS (
    SELECT at, (regexp_match(message, '^(\d{4}-\d{2}-\d{2}): one day in (.*)$')) m
    FROM gen.log WHERE message ~ '^\d{4}-\d{2}-\d{2}: one day in'
), last7 AS (
    SELECT m[1]::date AS day, m[2]::interval AS took FROM d ORDER BY at DESC LIMIT 7
)
SELECT max(day) AS last_model_day,
       avg(took) AS real_per_model_day_last7,
       CASE WHEN bookings.now() IS NOT NULL AND max(day) IS NOT NULL
            THEN avg(took) * greatest(bookings.now()::date - max(day), 0) END AS eta
FROM last7;

SELECT success AS bookings, forceoneway, nopath, noseat,
       round(100.0 * nopath / nullif(success + nopath + noseat, 0), 2) AS nopath_pct,
       round(100.0 * noseat / nullif(success + nopath + noseat, 0), 2) AS noseat_pct,
       pg_sequence_last_value('gen.ticket_s') - 5432000000 + 1 AS tickets_approx,
       (SELECT sum(events) FROM gen.stat_jobs) AS events,
       round((SELECT sum(events) FROM gen.stat_jobs) /
             nullif(extract(epoch FROM (SELECT max(at) - min(at) FROM gen.log)), 0)) AS events_per_s_total,
       pg_size_pretty(pg_database_size('demo')) AS db_size
FROM gen.stat_bookings;

SELECT count(*) FILTER (WHERE message LIKE 'Error%') AS errors,
       count(*) FILTER (WHERE message LIKE 'Cannot choose an aircraft%') AS cannot_choose_aircraft,
       count(*) FILTER (WHERE message LIKE 'End date reached%') AS jobs_finished
FROM gen.log;

SELECT at, left(message, 160) AS message
FROM gen.log
WHERE message LIKE 'Error%' OR message LIKE 'New bookings%' OR message LIKE 'Hub directions%'
   OR message LIKE 'Building routes%' OR message LIKE 'End date%'
ORDER BY at DESC LIMIT 6;
SQL
    if [ -f "$STATE_FILE" ]; then
        read -r prev_s prev_ev < "$STATE_FILE" || true
        if [ -n "${prev_s:-}" ] && [ "$now_s" -gt "$prev_s" ]; then
            echo "событий/с с прошлого status ($((now_s - prev_s)) с назад): $(( (ev - prev_ev) / (now_s - prev_s) ))"
        fi
    fi
    echo "$now_s $ev" > "$STATE_FILE"
}

cmd_export() {
    [ $# -ge 1 ] || die "использование: export <папка> [имя]"
    busy && die "генерация идёт — выгрузка только после её окончания"
    local out name ver
    mkdir -p "$1"; out="$(cd "$1" && pwd)"
    ver=$(q demo "SELECT bookings.version()")
    name="${2:-$(q demo "SELECT 'demo-' || replace(substring(bookings.version() FROM '\d{4}-\d{2}-\d{2}'), '-', '')
                               || '-' || coalesce(substring(bookings.version() FROM '\((\d+) days'), '0') || 'd'")}"
    ls "$out/$name".sql.gz.part-* >/dev/null 2>&1 && die "в $out уже есть части $name"
    cd "$GEN_DIR"
    echo "== выгрузка $name ($ver) → $out"
    # pipefail — и внутри export.sh, чтобы сбой pg_dump не прошёл незамеченным
    bash -o pipefail ./export.sh | gzip -6 | split -b "$PART_SIZE" -d -a 3 - "$out/$name.sql.gz.part-"
    ( cd "$out"
      sha256sum "$name".sql.gz.part-* > SHA256SUMS
      cat "$name".sql.gz.part-* | sha256sum | sed "s/-\$/$name.sql.gz/" > "$name.sql.gz.sha256"
      # конец дампа на месте
      cat "$name".sql.gz.part-* | gzip -dc | tail -n 5 | grep -q 'PostgreSQL database dump complete' \
          || { echo "ошибка: дамп неполный" >&2; exit 1; } )
    {
        echo "name: $name"
        echo "version: $ver"
        echo "now: $(q demo 'SELECT bookings.now()')"
        echo "exported_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "server: $(q demo 'SHOW server_version')"
        echo "whole_sha256: $(cut -d' ' -f1 "$out/$name.sql.gz.sha256")"
        echo "parts: $(ls "$out/$name".sql.gz.part-* | wc -l) (по $PART_SIZE; собрать: cat $name.sql.gz.part-* > $name.sql.gz)"
        echo "settings:"
        q demo "SELECT '  ' || c FROM pg_db_role_setting d CROSS JOIN LATERAL unnest(d.setconfig) c
                WHERE d.setdatabase = (SELECT oid FROM pg_database WHERE datname = 'demo') AND d.setrole = 0
                  AND c LIKE 'gen.%' ORDER BY c"
        echo "stat_bookings: $(q demo "SELECT format('success %s, forceoneway %s, nopath %s, noseat %s', success, forceoneway, nopath, noseat) FROM gen.stat_bookings")"
        echo "rows:"
        for t in airplanes_data airports_data seats routes flights bookings tickets segments boarding_passes; do
            echo "  bookings.$t: $(q demo "SELECT count(*) FROM bookings.$t")"
        done
        echo "network_airports: $(q demo "SELECT count(*) FROM gen.airports_data WHERE traffic IS NOT NULL")"
    } > "$out/MANIFEST.txt"
    ls -l "$out"
}

case "${1:-}" in
    install) shift; cmd_install "$@" ;;
    start)   shift; cmd_start "$@" ;;
    continue) cmd_start "$@" ;;
    status)  cmd_status ;;
    export)  shift; cmd_export "$@" ;;
    check)   cd "$GEN_DIR"; psql -X -d demo -f check.sql ;;
    abort)   psql -X -v ON_ERROR_STOP=1 -d demo -c 'CALL abort()' ;;
    *) sed -n '2,/^set -euo/p' "$0" | grep '^#'; exit 1 ;;
esac
