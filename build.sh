#!/usr/bin/env bash
# Сборка и проверки генератора набора без Maven: javac + JUnit 5 из локального ~/.m2 (заполнить: mvn -pl core
# dependency:resolve). Ядро (core/) и набор (<код>/) собираются вместе, тесты — ядра и набора.
#   ./build.sh КОД               — собрать (предупреждения — ошибки) и прогнать тесты, например ./build.sh sto
#   ./build.sh КОД compile       — только собрать
#   ./build.sh КОД run ARGS      — собрать и запустить генератор (io.hammerhall.data.КОД.Main; ARGS — команда и параметры)
#   ./build.sh КОД exec ARGS     — запустить генератор без пересборки (после compile)
# Linux, macOS и Windows (Git Bash): на Windows пути для java переводятся cygpath, разделитель classpath — «;»,
# исходники передаются javac файлом-аргументом (@). Python не нужен.
# Переменные: JAVA_HOME — JDK 25; M2_REPO — локальный репозиторий Maven; JAVA_XMX — куча (по умолчанию 4g); JAVA_OPTS —
# ещё параметры JVM (например, -Ddata.tmpdir=…); CI — если задана, отсутствие JUnit — ошибка, а не пропуск тестов.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) WIN=1 ;;
  *) WIN=0 ;;
esac
SEP=':'
[ "$WIN" = 1 ] && SEP=';'
# путь для java и javac: на Windows — D:/a/b (cygpath -m), иначе — как есть
jpath() { if [ "$WIN" = 1 ]; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
upath() { if [ "$WIN" = 1 ]; then cygpath -u "$1"; else printf '%s' "$1"; fi; }

usage() { echo "build.sh КОД [compile|test|run ARGS|exec ARGS] — КОД: папка набора (sto, …)"; exit 2; }

CODE="${1:-}"
[ -n "$CODE" ] || usage
shift
[[ "$CODE" =~ ^[a-z][a-z0-9]*$ ]] || { echo "код набора — латиница и цифры, с буквы: «$CODE»"; exit 2; }
if [ ! -d "$HERE/$CODE/src/main/java/io/hammerhall/data/$CODE" ]; then
  if [ -f "$HERE/$CODE/dataset.properties" ]; then
    echo "у набора $CODE нет генератора на Java ($CODE/src/main/java/io/hammerhall/data/$CODE) — см. $CODE/README.md"
  else
    echo "нет набора $CODE: нет папки $CODE/ с паспортом dataset.properties"
  fi
  exit 2
fi
MAIN="io.hammerhall.data.$CODE.Main"

JAVA_HOME="${JAVA_HOME:-}"
[ -n "$JAVA_HOME" ] && JAVA_HOME="$(upath "$JAVA_HOME")"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
M2="$(upath "${M2_REPO:-$HOME/.m2/repository}")"
OUT="$HERE/target/$CODE"
# data.dir — папка набора: паспорт, схема и ожидания по умолчанию, входы в work/
JOPTS=(-Xmx"${JAVA_XMX:-4g}" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
       -Ddata.dir="$(jpath "$HERE/$CODE")")
EXTRA=()
[ -n "${JAVA_OPTS:-}" ] && read -r -a EXTRA <<< "$JAVA_OPTS"

JUNIT=(org/junit/jupiter/junit-jupiter-api/5.14.0/junit-jupiter-api-5.14.0.jar
       org/junit/jupiter/junit-jupiter-engine/5.14.0/junit-jupiter-engine-5.14.0.jar
       org/junit/platform/junit-platform-commons/1.14.0/junit-platform-commons-1.14.0.jar
       org/junit/platform/junit-platform-engine/1.14.0/junit-platform-engine-1.14.0.jar
       org/junit/platform/junit-platform-launcher/1.14.0/junit-platform-launcher-1.14.0.jar
       org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar
       org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar)
CP_TEST=""
MISSING=""
for j in "${JUNIT[@]}"; do
  if [ -f "$M2/$j" ]; then CP_TEST="$CP_TEST$SEP$(jpath "$M2/$j")"; else MISSING="$MISSING $j"; fi
done

# список исходников — файлом-аргументом javac: пути в кавычках, через «/» (и на Windows), порядок — постоянный
sources() {
  local out="$1"
  shift
  local dirs=()
  for d in "$@"; do [ -d "$d" ] && dirs+=("$d"); done
  find "${dirs[@]}" -name '*.java' | LC_ALL=C sort | { if [ "$WIN" = 1 ]; then cygpath -m -f -; else cat; fi; } \
    | sed 's/.*/"&"/' > "$out"
}

compile() {
  rm -rf "$OUT/classes" "$OUT/test-classes"
  mkdir -p "$OUT/classes" "$OUT/test-classes"
  sources "$OUT/main-sources.txt" "$HERE/core/src/main/java" "$HERE/$CODE/src/main/java"
  "$JAVAC" --release 25 -encoding UTF-8 -Xlint:all -Werror -d "$(jpath "$OUT/classes")" "@$(jpath "$OUT/main-sources.txt")"
  for r in "$HERE/core/src/main/resources" "$HERE/$CODE/src/main/resources"; do
    [ -d "$r" ] && cp -r "$r/." "$OUT/classes/"
  done
  return 0
}

run_java() {
  exec "$JAVA" "${JOPTS[@]}" ${EXTRA[@]+"${EXTRA[@]}"} "$@"
}

case "${1:-test}" in
  compile) compile ;;
  run)
    compile
    shift
    run_java -cp "$(jpath "$OUT/classes")" "$MAIN" "$@"
    ;;
  exec)
    [ -d "$OUT/classes/io" ] || { echo "нет $OUT/classes — сначала ./build.sh $CODE compile"; exit 2; }
    shift
    run_java -cp "$(jpath "$OUT/classes")" "$MAIN" "$@"
    ;;
  test)
    compile
    if [ -n "$MISSING" ]; then
      echo "JUnit 5 не найден в $M2:$MISSING"
      if [ -n "${CI:-}" ]; then echo "в CI тесты обязательны: mvn -B -pl core dependency:resolve"; exit 1; fi
      echo "тесты пропущены"
      exit 0
    fi
    sources "$OUT/test-sources.txt" "$HERE/core/src/test/java" "$HERE/$CODE/src/test/java"
    "$JAVAC" --release 25 -encoding UTF-8 -d "$(jpath "$OUT/test-classes")" -cp "$(jpath "$OUT/classes")$CP_TEST" \
      "@$(jpath "$OUT/test-sources.txt")"
    run_java -cp "$(jpath "$OUT/classes")$SEP$(jpath "$OUT/test-classes")$CP_TEST" io.hammerhall.data.TestMain
    ;;
  *) usage ;;
esac
