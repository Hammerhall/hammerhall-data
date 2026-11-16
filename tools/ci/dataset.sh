#!/usr/bin/env bash
# Какой набор собирает workflow data.yml и годится ли он: код — из тега <код>-data-vN (push) или из поля «dataset»
# ручного запуска; дальше — паспорт <код>/dataset.properties. Итог — строки «ключ=значение» в $GITHUB_OUTPUT (без
# него — на экран): code, version, title, environment, xmx, budget_minutes. Набор, который этот workflow не собирает
# (нет паспорта, нет генератора на Java, версия ≠ тег), — ошибка с причиной, остальные задачи не идут.
# Переменные: EVENT — github.event_name, TAG — github.ref_name, DATASET — inputs.dataset.
set -euo pipefail
. "$(dirname "$0")/passport.sh"

if [ "${EVENT:-}" = push ]; then
  [[ "${TAG:-}" =~ ^([a-z][a-z0-9]*)-data-v[0-9]+$ ]] || fail "тег «${TAG:-}» — не <код>-data-vN (например, sto-data-v1)"
  CODE="${BASH_REMATCH[1]}"
else
  CODE="${DATASET:-}"
  [[ "$CODE" =~ ^[a-z][a-z0-9]*$ ]] || fail "код набора «$CODE» — латиница и цифры, с буквы (sto, air, …)"
fi
[ -f "$ROOT/$CODE/dataset.properties" ] || fail "нет набора «$CODE»: в репозитории нет $CODE/dataset.properties"
[ "$(passport "$CODE" code)" = "$CODE" ] || fail "$CODE/dataset.properties: code=$(passport "$CODE" code), а папка — $CODE"
VERSION="$(passport "$CODE" version)"
BUILD="$(passport "$CODE" build)"
if [ "$BUILD" != java ] || [ ! -d "$ROOT/$CODE/src/main/java/io/hammerhall/data/$CODE" ]; then
  fail "набор $CODE ($VERSION) этим workflow не собирается: генератора на Java у него нет (build=$BUILD в $CODE/dataset.properties, нужен $CODE/src/main/java/io/hammerhall/data/$CODE) — как собран его выпуск, см. $CODE/README.md"
fi
[[ "$VERSION" =~ ^$CODE-data-v[0-9]+$ ]] || fail "$CODE/dataset.properties: version=$VERSION — не $CODE-data-vN"
if [ "${EVENT:-}" = push ] && [ "$VERSION" != "$TAG" ]; then
  fail "тег $TAG, а версия набора в $CODE/dataset.properties — $VERSION"
fi
ENVIRONMENT="$(passport "$CODE" release.environment)"
[ "$ENVIRONMENT" = "$CODE-data-release" ] || fail "$CODE/dataset.properties: release.environment=$ENVIRONMENT, ждали $CODE-data-release"
XMX="$(passport "$CODE" full.xmx)"
BUDGET="$(passport "$CODE" full.budget_minutes)"
[[ "$XMX" =~ ^[0-9]+[gm]$ ]] || fail "$CODE/dataset.properties: full.xmx=$XMX — не вида 4g"
[[ "$BUDGET" =~ ^[0-9]+$ ]] || fail "$CODE/dataset.properties: full.budget_minutes=$BUDGET — не число"

{
  echo "code=$CODE"
  echo "version=$VERSION"
  echo "title=$(passport "$CODE" title)"
  echo "environment=$ENVIRONMENT"
  echo "xmx=$XMX"
  echo "budget_minutes=$BUDGET"
} >> "${GITHUB_OUTPUT:-/dev/stdout}"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "Набор **$CODE** — $(passport "$CODE" title), \`$VERSION\`" >> "$GITHUB_STEP_SUMMARY"
fi
echo "набор $CODE: $VERSION, окружение выпуска $ENVIRONMENT"
