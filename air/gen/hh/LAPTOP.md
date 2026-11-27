# Полный прогон в Docker Desktop (Windows, Git Bash)

Порядок команд для прогона `generate('2025-09-01', '2027-09-01', 16)` в контейнере
`postgres:16`. Все команды — в Git Bash. Строки `powershell` — в PowerShell.

Ожидания (по коротким прогонам на 2 ядрах; допущения — в отчёте): при
`traffic_coeff = 50` около 19 ч на i7-12700H с jobs = 16 (разумный диапазон 14–29 ч),
база в контейнере 55–75 ГБ, выгрузка gzip около 3 ГБ (две части по 1 900 МБ). При 44 —
около 17 ч (12–25 ч), выгрузка около 2,7 ГБ. Свободного места на диске с данными Docker
(обычно C:) нужно не меньше 120 ГБ.

## 0. Перед началом

```sh
export MSYS_NO_PATHCONV=1   # иначе Git Bash превращает /gen, /out и т. п. в пути Windows
```

Ресурсы WSL2 — файл `%UserProfile%\.wslconfig` (создать или дополнить):

```ini
[wsl2]
memory=48GB
processors=20
swap=8GB
```

Применить: закрыть Docker Desktop, выполнить `wsl.exe --shutdown`, запустить Docker Desktop.
Проверить: `docker info --format '{{.NCPU}} CPU, {{.MemTotal}} байт'` — должно быть 20 CPU и около 48 ГБ.

Питание: ноутбук на зарядке и не должен засыпать или уходить в гибернацию до конца прогона.

```sh
powercfg.exe /change standby-timeout-ac 0
powercfg.exe /change hibernate-timeout-ac 0
powercfg.exe /setacvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 0   # закрытая крышка — ничего не делать
powercfg.exe /setactive SCHEME_CURRENT
```

Обновления Windows приостановить на неделю (Параметры → Центр обновления Windows →
Приостановить), чтобы не было перезагрузки посреди прогона.

## 1. Образ, том, контейнер

Ожидаемый образ: `postgres:16.16-trixie` (на 24.11.2026 тег `16` указывает на 16.16 на
Debian 13 trixie; digest отсюда проверить не удалось). Точную версию в `SOURCE.md`
писать по `SELECT version()` (шаг 2) и по digest образа.

```sh
docker pull postgres:16.16-trixie
docker image inspect postgres:16.16-trixie --format '{{index .RepoDigests 0}}'

docker volume create demogen-data

docker run -d --name demogen \
  --shm-size=2g --stop-signal=SIGINT --stop-timeout=300 \
  -e POSTGRES_PASSWORD=demogen \
  -v demogen-data:/var/lib/postgresql/data \
  postgres:16.16-trixie \
  -c shared_buffers=12GB -c effective_cache_size=32GB \
  -c work_mem=16MB -c maintenance_work_mem=2GB -c max_connections=60 \
  -c fsync=off -c synchronous_commit=off -c full_page_writes=off \
  -c wal_level=minimal -c max_wal_senders=0 -c max_wal_size=16GB -c checkpoint_timeout=30min \
  -c autovacuum_max_workers=4
```

* Том `demogen-data` — именованный, внутри WSL2. Не монтировать каталог Windows
  (`-v C:\...`): через него база работает в разы медленнее.
* `--stop-signal=SIGINT` — `docker stop` делает быструю штатную остановку Postgres.
  `--stop-timeout=300` даёт ей до 5 минут.
* Все таблицы генератора — UNLOGGED. После нештатной остановки (пропало питание,
  `docker kill`, сбой Docker Desktop) Postgres при старте **очищает** их, и прогон
  придётся начинать заново. Останавливать — только как в п. 5.

Подождать готовности и проверить версию (её — в `SOURCE.md`):

```sh
until docker exec demogen pg_isready -U postgres; do sleep 2; done
docker exec -u postgres demogen psql -Atc 'SELECT version()'
```

## 2. Форк в контейнер

`./air/gen` — каталог форка в рабочей копии (подставить свой путь).

```sh
docker exec demogen mkdir -p /gen /out
docker cp ./air/gen/. demogen:/gen
docker exec demogen chown -R postgres:postgres /gen /out
# переводы строк должны быть LF (см. .gitattributes): ожидается 0
docker exec demogen bash -c "grep -l \$'\r' /gen/*.sql /gen/*.sh /gen/hh/*.sql /gen/hh/*.sh /gen/hh/*.tsv /gen/hh/*.dat | wc -l"
```

## 3. Установка и запуск

```sh
docker exec -u postgres -w /gen demogen bash hh/run.sh install traffic_coeff=50   # или 44 — по решению
docker exec -d -u postgres -w /gen -e HH_FOREGROUND=1 demogen \
  bash -c 'hh/run.sh start 2025-09-01 2027-09-01 16 > /out/start.log 2>&1'
```

`install` пересоздаёт базу `demo` и печатает сеть (304 аэропорта, сумма traffic 6268,3)
и параметры `gen.*`. `start` сначала проходит разгон одним процессом до 2025-09-01
(несколько минут), затем запускает 16 процессов. `docker exec -d` оставляет сеанс
запуска работать в контейнере до конца генерации; закрывать Git Bash можно.

## 4. Ход

```sh
docker exec -u postgres -w /gen demogen bash hh/run.sh status
docker exec demogen tail -n 5 /out/start.log
```

`status` показывает модельное время (`model_now`, конец — `2027-09-01`), бронирования,
`nopath`, ошибки, размер базы и оценку конца `eta` по последним 7 модельным дням.
Оценка становится надёжной после первых 7 дней октября 2025 (с 1 октября начинаются
рейсы, и день становится тяжелее). Конец генерации — `busy = f` и строка
`генерация завершена` в `/out/start.log`.

## 5. Остановка и продолжение (перезагрузка, перенос)

```sh
docker exec -u postgres -w /gen demogen bash hh/run.sh abort
docker stop demogen
# … перезагрузка …
docker start demogen
until docker exec demogen pg_isready -U postgres; do sleep 2; done
docker exec -u postgres -w /gen demogen bash hh/run.sh status   # bookings должно быть > 0
docker exec -d -u postgres -w /gen -e HH_FOREGROUND=1 demogen \
  bash -c 'hh/run.sh continue 2027-09-01 16 >> /out/start.log 2>&1'
```

`abort` прерывает процессы: их незавершённые события откатываются. `continue` вернёт эти
события в очередь и продолжит с места остановки. Если после `docker start` в `status`
`bookings = 0`, значит, остановка была нештатной и таблицы очищены: начать заново с п. 3.

Сон и гибернация не останавливают Postgres штатно. Если ноутбук всё же уснул, после
пробуждения проверить `status`: модельное время должно расти, `errors = 0`.

## 6. Ошибка в gen.log

Если в `status` `errors > 0`, сеанс запуска сам прерывает генерацию (в `/out/start.log` —
строка `ошибка в gen.log — генерация прервана`). Сохранить журнал и прислать:

```sh
docker exec -u postgres demogen psql -d demo -c \
  "SELECT at, message FROM gen.log WHERE message LIKE 'Error%' OR message LIKE 'Call stack%' ORDER BY at" > gen-errors.txt
docker exec -u postgres -w /gen demogen bash hh/run.sh status > status.txt
```

Продолжать такой прогон не нужно: после разбора причины — заново с п. 3.

## 7. Выгрузка

После конца генерации:

```sh
docker exec -u postgres -w /gen demogen bash hh/run.sh export /out/dump
docker exec -u postgres demogen psql -Atc 'SELECT version()'
docker cp demogen:/out/dump ./dump
(cd dump && sha256sum -c SHA256SUMS)
```

В `./dump`: части `demo-20250901-730d.sql.gz.part-000`, `-001`, `SHA256SUMS`,
`demo-20250901-730d.sql.gz.sha256` (сумма склеенного файла) и `MANIFEST.txt` (версия,
параметры, число строк). Проверка авторов (`hh/run.sh check`) на базе такого размера идёт
долго (часы), её можно не запускать.

## 8. Уборка

```sh
docker rm -f demogen
docker volume rm demogen-data
```

Место на диске Windows само не возвращается: виртуальный диск WSL2 не сжимается.
Вернуть его: Docker Desktop → Troubleshoot → Clean / Purge data, либо сжать файл
`docker_data.vhdx` (после `wsl.exe --shutdown`).
