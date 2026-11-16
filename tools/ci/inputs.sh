#!/usr/bin/env bash
# Входы набора по паспорту (inputs=…, input.<имя>.*): каждый — из выпуска этого репозитория (release: gh release
# download) или по ссылке (url: curl), затем сверка с эталонной суммой (sha256_tsv — содержимого разжатого .tsv.gz,
# sha256 — файла). Путь ко входу — в переменную <КОД>_<ИМЯ> ($GITHUB_ENV, без него — на экран; на Windows — D:/…):
# её читают генератор и тесты.
# Запуск: tools/ci/inputs.sh КОД ПАПКА   (gh — для release, curl — для url; Python — PYTHON, по умолчанию python)
set -euo pipefail
. "$(dirname "$0")/passport.sh"
CODE="$1"
mkdir -p "$2"
DIR="$(cd "$2" && pwd)"
PY="${PYTHON:-python}"
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) WIN=1 ;; *) WIN=0 ;; esac

for NAME in $(passport "$CODE" inputs); do
  FILE="$(passport "$CODE" "input.$NAME.file")"
  RELEASE="$(passport "$CODE" "input.$NAME.release")"
  URL="$(passport "$CODE" "input.$NAME.url")"
  WANT_TSV="$(passport "$CODE" "input.$NAME.sha256_tsv")"
  WANT_RAW="$(passport "$CODE" "input.$NAME.sha256")"
  [[ "$FILE" =~ ^[A-Za-z0-9._-]+$ ]] || fail "$CODE/dataset.properties: input.$NAME.file=«$FILE» — нужно имя файла"
  if [ -n "$RELEASE" ]; then
    gh release download "$RELEASE" --repo "$GITHUB_REPOSITORY" --pattern "$FILE" --dir "$DIR"
  elif [ -n "$URL" ]; then
    curl -fsSL --retry 5 --retry-delay 3 -o "$DIR/$FILE" "$URL"
  else
    fail "$CODE/dataset.properties: у входа $NAME нет ни input.$NAME.release, ни input.$NAME.url"
  fi
  if [ -n "$WANT_TSV" ]; then
    KIND=tsv; SUM=sha256_tsv; WANT="$WANT_TSV"
  elif [ -n "$WANT_RAW" ]; then
    KIND=raw; SUM=sha256; WANT="$WANT_RAW"
  else
    fail "$CODE/dataset.properties: у входа $NAME нет эталонной суммы (input.$NAME.sha256_tsv или input.$NAME.sha256)"
  fi
  P="$DIR/$FILE"
  [ "$WIN" = 1 ] && P="$(cygpath -m "$P")"
  HAVE="$("$PY" -X utf8 -I -c '
import gzip, hashlib, sys
path, kind = sys.argv[1], sys.argv[2]
h = hashlib.sha256()
with (gzip.open(path, "rb") if kind == "tsv" else open(path, "rb")) as f:
    for chunk in iter(lambda: f.read(1 << 20), b""):
        h.update(chunk)
print(h.hexdigest())' "$P" "$KIND")"
  [ "$HAVE" = "$WANT" ] || fail "вход $NAME ($FILE): $SUM $HAVE, в паспорте $WANT — не тот файл"
  VAR="$(printf '%s_%s' "$CODE" "$NAME" | tr '[:lower:]' '[:upper:]')"
  echo "вход $NAME: $FILE — сумма совпала с паспортом; $VAR=$P"
  echo "$VAR=$P" >> "${GITHUB_ENV:-/dev/null}"
done
