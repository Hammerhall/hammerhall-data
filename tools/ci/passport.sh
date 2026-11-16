# Чтение паспорта набора (<код>/dataset.properties) из bash — подключается точкой:
#   . tools/ci/passport.sh; passport sto version        → sto-data-v1
# Паспорт — строки «ключ=значение» (без пробелов вокруг «=», без экранирования), «#» — комментарий.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# passport КОД КЛЮЧ — значение ключа (пусто, если ключа нет); ключ сравнивается буквально (точки — не «любой символ»)
passport() {
  local file="$ROOT/$1/dataset.properties"
  [ -f "$file" ] || return 1
  awk -v k="$2" 'index($0, k "=") == 1 { v = substr($0, length(k) + 2); sub(/\r$/, "", v); print v; exit }' "$file"
}

# fail ТЕКСТ — ошибка шага GitHub Actions (и в сводку запуска, если она есть)
fail() {
  echo "::error::$*"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "**Стоп:** $*" >> "$GITHUB_STEP_SUMMARY"; fi
  exit 1
}
