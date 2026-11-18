#!/usr/bin/env bash
# Картинки схемы набора: каждый <код>/schema/*.dot (их пишет генератор: ./build.sh <код> exec schema) → *.svg рядом.
#   tools/schema-svg.sh КОД
# Нужен Graphviz (dot в PATH) и шрифт DejaVu Sans; Linux, macOS, Windows (Git Bash). Тесту набора и CI Graphviz не нужен:
# они сверяют только отметку в SVG — первой строкой после XML-пролога идёт <!-- schema-dot sha256: … --> с sha256
# исходного .dot. Схема изменилась, а картинку не перерисовали — тест набора падает с подсказкой.
#
# Шрифт: Graphviz размечает текст по DejaVu Sans (значки полей — DejaVu Sans Mono), а SVG рисует браузер зрителя
# (GitHub показывает SVG как есть), у которого DejaVu может не быть: в SVG — запасные шрифты, первым у текста — Verdana,
# по ширине букв она близка к DejaVu Sans.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"

CODE="${1:-}"
[ -n "$CODE" ] || { echo "tools/schema-svg.sh КОД — КОД: папка набора (air, sto, …)"; exit 2; }
DIR="$HERE/$CODE/schema"
[ -d "$DIR" ] || { echo "нет папки $DIR"; exit 2; }
command -v dot >/dev/null || { echo "нет Graphviz: dot не найден в PATH"; exit 2; }

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -c1-64; else shasum -a 256 "$1" | cut -c1-64; fi
}

shopt -s nullglob
dots=("$DIR"/*.dot)
[ "${#dots[@]}" -gt 0 ] || { echo "в $DIR нет .dot — сначала ./build.sh $CODE exec schema"; exit 2; }

for f in "${dots[@]}"; do
  svg="${f%.dot}.svg"
  tmp="$svg.tmp"
  sum="$(sha256 "$f")"
  # Graphviz пишет SVG; переводы строк — LF и на Windows; отметка — сразу после <?xml …?>; шрифт — с запасными
  if ! dot -Tsvg "$f" | tr -d '\r' | awk -v mark="<!-- schema-dot sha256: $sum -->" '
    { gsub(/font-family="DejaVu Sans"/, "font-family=\"DejaVu Sans, Verdana, Arial, sans-serif\"")
      gsub(/font-family="DejaVu Sans Mono"/, "font-family=\"DejaVu Sans Mono, Consolas, Menlo, monospace\"") }
    NR == 1 && /^<\?xml/ { print; print mark; next }
    NR == 1 { print mark }
    { print }
  ' > "$tmp"; then
    rm -f "$tmp"
    echo "dot не нарисовал $(basename "$f")"
    exit 1
  fi
  mv "$tmp" "$svg"
  echo "$(basename "$f") → $(basename "$svg")"
done
