"""SOURCE.md выпуска снимка авиа (air-source-YYYYMMDD) по папке выгрузки hh/run.sh export.

python3 -X utf8 -I source_md.py --dump ПАПКА --tag air-source-YYYYMMDD --commit SHA --image ОБРАЗ@DIGEST --version 'PostgreSQL …'
Пишет SOURCE.md в stdout. Читает MANIFEST.txt, SHA256SUMS и *.sql.gz.sha256 из ПАПКИ; сверяет суммы частей с файлами.
Параметр gen.connstr (адрес подключения внутри машины) в SOURCE.md не попадает."""
import argparse
import hashlib
import pathlib
import re
import sys


def num(n):
    return f"{n:,}".replace(",", " ")


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    for k in ("dump", "tag", "commit", "image", "version"):
        ap.add_argument("--" + k, required=True)
    a = ap.parse_args()
    d = pathlib.Path(a.dump)
    if not re.fullmatch(r"air-source-[0-9]{8}", a.tag):
        sys.exit(f"тег «{a.tag}» — не air-source-YYYYMMDD")
    if not re.fullmatch(r"[0-9a-f]{40}", a.commit):
        sys.exit(f"коммит «{a.commit}» — не 40 шестнадцатеричных знаков")
    man = (d / "MANIFEST.txt").read_text(encoding="utf-8").splitlines()
    head, settings, rows, stat = {}, [], [], ""
    sect = None
    for ln in man:
        if ln.startswith("  ") and sect == "settings":
            k, _, v = ln.strip().partition("=")
            if k != "gen.connstr":
                settings.append((k, v))
        elif ln.startswith("  ") and sect == "rows":
            k, _, v = ln.strip().partition(": ")
            rows.append((k, int(v)))
        else:
            k, _, v = ln.partition(":")
            sect = k.strip()
            if v.strip():
                head[sect] = v.strip()
            if sect == "stat_bookings":
                stat = v.strip()
    sums = {}
    for ln in (d / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        h, name = ln.split(None, 1)
        name = name.lstrip("*")
        if sha256(d / name) != h:
            sys.exit(f"{name}: sha256 не совпадает с SHA256SUMS")
        sums[name] = h
    whole = head.get("whole_sha256", "")
    name = head["name"]
    out = [
        f"# {a.tag} — снимок источника набора «Авиаперевозки»",
        "",
        "Выгрузка демобазы «Авиаперевозки» (pg_dump, текстовый SQL, gzip), из которой собирается набор `air-data-v1`.",
        "Генератор — изменённая копия [postgrespro/demodb](https://github.com/postgrespro/demodb) (MIT © 2025 Postgres",
        f"Professional) в каталоге `air/gen` этого репозитория, коммит `{a.commit}`; что изменено — `air/gen/NOTICE.md`:",
        "маршрутная сеть из 304 аэропортов 87 стран (хабы, межрыночные рейсы, ни одной поездки короче 150 км), имена",
        "пассажиров новых стран, исправления генератора.",
        "",
        f"* Выгрузка — `{name}`: {head.get('version', '')}; модельное «сейчас» — {head.get('now', '')}.",
        f"* Сервер — {a.version}; образ Docker — `{a.image}`.",
        f"* Выгружено — {head.get('exported_at', '')} (UTC).",
        f"* Бронирования генератора: {stat}.",
        "",
        "## Файлы",
        "",
        "| файл | байт | sha256 |",
        "|---|--:|---|",
    ]
    for n, h in sorted(sums.items()):
        out.append(f"| `{n}` | {num((d / n).stat().st_size)} | `{h}` |")
    out += [
        "",
        f"Собрать выгрузку: `cat {name}.sql.gz.part-* > {name}.sql.gz` — sha256 склеенного файла `{whole}`",
        f"(`{name}.sql.gz.sha256`). Сборщик набора читает части подряд без склейки.",
        "",
        "## Строки",
        "",
        "| таблица | строк |",
        "|---|--:|",
    ]
    out += [f"| `{k}` | {num(v)} |" for k, v in rows]
    out += [
        "",
        "## Параметры генератора",
        "",
        "| параметр | значение |",
        "|---|---|",
    ]
    out += [f"| `{k}` | `{v}` |" for k, v in settings]
    out += [
        "",
        "Запуск: `hh/run.sh install` → `hh/run.sh start 2025-09-01 2027-09-01 16` → `hh/run.sh export` (`air/gen/hh/LAPTOP.md`).",
        "",
        "## Лицензия",
        "",
        "Генератор и выгрузка — MIT © 2025 Postgres Professional (`air/gen/LICENSE`); дополнения `air/gen/hh/` — на тех же",
        "условиях. Частоты имён пассажиров новых стран — по открытым данным Wikidata (CC0 1.0).",
        "",
    ]
    sys.stdout.write("\n".join(out))


if __name__ == "__main__":
    main()
