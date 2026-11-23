"""Заметки к выпуску GitHub любого набора — из паспорта (<код>/dataset.properties) и manifest.json full.

Запуск: python3 -X utf8 -I tools/ci/notes.py КОД ТЕГ ПАПКА_FULL > notes.md
"""
import json
import pathlib
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def passport(code):
    """Паспорт: строки «ключ=значение», «#» — комментарий (как у tools/ci/passport.sh)."""
    out = {}
    for line in (ROOT / code / 'dataset.properties').read_text(encoding='utf-8').splitlines():
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            out.setdefault(k, v)
    return out


def main(argv):
    if len(argv) != 4:
        print(__doc__)
        return 2
    code, tag, full = argv[1], argv[2], pathlib.Path(argv[3])
    p = passport(code)
    m = json.loads((full / 'manifest.json').read_text(encoding='utf-8'))
    rows = f"{sum(f['rows'] for f in m['files']):,}".replace(',', ' ')
    total = sum(f['bytes'] for f in m['files'])
    size = (f"{total / 1e9:.2f}".replace('.', ',') + ' ГБ') if total >= 1e8 else (f"{total / 1e6:.1f}".replace('.', ',') + ' МБ')
    print(f"{p['title']} — {p['summary']}, `{m['version']}`.")
    print()
    print(f"* `full`: {len(m['files'])} таблиц, {rows} строк, {size} сжатого (файлы `*.tsv.gz` ниже);")
    # что такое профиль у набора — profile.<профиль> в паспорте; нет — масштаб, как у генераторов
    for prof, scale in (('small', 'масштаб 1 %'), ('medium', 'масштаб 10 %')):
        if prof in p.get('profiles', 'small full').split():
            print(f"* `{prof}` ({p.get('profile.' + prof, scale)}) — архив `{tag}-{prof}.tar`;")
    print(f"* seed `{m['seed']}`, генератор `{m['generator']}`; период {m['period']['from']} … {m['period']['to']}.")
    print()
    print("Тождество выпуска — содержимое: число строк и `sha256_tsv` (sha256 разжатого TSV) каждой таблицы.")
    print("Проверка скачанного: `python3 -X utf8 -I tools/check_data.py ПАПКА` из репозитория на этом теге")
    print(f"(Windows: `py -3 -X utf8 -I tools\\check_data.py ПАПКА`). Состав — `SOURCE.md`, лицензия — {p['license']}.")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
