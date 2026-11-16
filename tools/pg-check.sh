#!/usr/bin/env bash
# Проверка выпуска любого набора в Postgres: временный кластер → schema-tables.sql → load-postgres.psql →
# schema-constraints.sql → общие запросы (таблиц, ограничений) и контрольные запросы набора (<код>/pg-check.sql, если
# есть; код — из версии в manifest.json выпуска) → кластер удаляется.
# Запуск: tools/pg-check.sh ПАПКА_ВЫПУСКА [КАТАЛОГ_PG_BIN]   (Linux; KEEP=1 — не удалять target/pg)
# Под root кластер поднимается в пространстве имён пользователя (unshare), чтобы initdb не отказывался.
set -euo pipefail
REL="$(cd "$1" && pwd)"
PGBIN="${2:-/usr/lib/postgresql/16/bin}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' "$REL/manifest.json" | head -n 1)"
CODE="${VERSION%%-data-v*}"
[[ "$CODE" =~ ^[a-z][a-z0-9]*$ ]] && [ "$CODE" != "$VERSION" ] \
  || { echo "в $REL/manifest.json версия не <код>-data-vN: «$VERSION»"; exit 2; }
QUERIES="$ROOT/$CODE/pg-check.sql"
[ -f "$QUERIES" ] || QUERIES=''
echo "выпуск $VERSION, контрольные запросы: ${QUERIES:-нет}"
WORK="$ROOT/target/pg"
rm -rf "$WORK"; mkdir -p "$WORK/sock"
run() { if [ "$(id -u)" = 0 ]; then unshare --user --map-user=1000 --map-group=1000 "$@"; else "$@"; fi; }
run bash -c "
  set -euo pipefail
  '$PGBIN/initdb' -D '$WORK/data' -U '$CODE' -E UTF8 --locale=C.UTF-8 -A trust >/dev/null
  # порт: PGCHECK_PORT или свободный из 55400–55899 (параллельные проверки не мешают друг другу)
  PORT=''
  for try in 0 1 2 3 4 5 6 7 8 9; do
    P=\${PGCHECK_PORT:-\$((55400 + (\$\$ + try * 37) % 500))}
    if '$PGBIN/pg_ctl' -D '$WORK/data' -o \"-k '' -c listen_addresses=127.0.0.1 -p \$P\" -l '$WORK/log' -w start >/dev/null; then
      PORT=\$P
      break
    fi
    [ -z \"\${PGCHECK_PORT:-}\" ] || break
  done
  [ -n \"\$PORT\" ] || { echo 'Postgres не поднялся — см. $WORK/log'; exit 1; }
  trap \"'$PGBIN/pg_ctl' -D '$WORK/data' -m fast stop >/dev/null\" EXIT
  cd '$REL'
  export PGHOST=127.0.0.1 PGPORT=\$PORT PGUSER='$CODE'
  '$PGBIN/createdb' '$CODE'
  START=\$(date +%s)
  '$PGBIN/psql' -q -v ON_ERROR_STOP=1 -d '$CODE' -f schema-tables.sql -f load-postgres.psql
  LOADED=\$(date +%s)
  '$PGBIN/psql' -q -v ON_ERROR_STOP=1 -d '$CODE' -f schema-constraints.sql
  DONE=\$(date +%s)
  echo \"загрузка: \$((LOADED-START)) с, ограничения: \$((DONE-LOADED)) с\"
  '$PGBIN/psql' -d '$CODE' -At -c \"select 'таблиц: ' || count(*) from information_schema.tables where table_schema='public'\"
  '$PGBIN/psql' -d '$CODE' -At -c \"select 'ограничений: ' || count(*) from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname='public'\"
  if [ -n '$QUERIES' ]; then '$PGBIN/psql' -v ON_ERROR_STOP=1 -d '$CODE' -At -f '$QUERIES'; fi
"
[ -n "${KEEP:-}" ] || rm -rf "$WORK"
