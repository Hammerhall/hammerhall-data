"""Сверка двух manifest.json одного выпуска — двух прогонов или двух ОС (Linux и Windows).

Запуск: python3 -X utf8 -I tools/compare_manifests.py A/manifest.json B/manifest.json [--names A B] [--summary ФАЙЛ]

Тождество — всё, кроме сжатых байтов: поля манифеста (версия, генератор, seed, профиль, период, модули, входы,
формат) и у каждого файла — имя, таблица, строки, sha256_tsv. Разошлись — выход 1.
Справочно — bytes и sha256 файлов .tsv.gz: другая сборка zlib даёт другие байты того же содержимого; различие
печатается (и дописывается в --summary, например $GITHUB_STEP_SUMMARY), но выход — 0.
"""
import json
import pathlib
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

IDENTITY_FILE_FIELDS = ['name', 'table', 'rows', 'sha256_tsv']
REFERENCE_FILE_FIELDS = ['bytes', 'sha256']


def load(p):
    return json.loads(pathlib.Path(p).read_text(encoding='utf-8'))


def compare(a, b):
    """→ (различия тождества, различия справочные) — списки строк."""
    hard, soft = [], []
    for k in dict.fromkeys([k for k in a if k != 'files'] + [k for k in b if k != 'files']):
        if a.get(k) != b.get(k):
            hard.append('поле «%s»: %s ≠ %s' % (k, json.dumps(a.get(k), ensure_ascii=False),
                                               json.dumps(b.get(k), ensure_ascii=False)))
    fa = {f['name']: f for f in a.get('files', [])}
    fb = {f['name']: f for f in b.get('files', [])}
    for n in dict.fromkeys(list(fa) + list(fb)):
        x, y = fa.get(n), fb.get(n)
        if x is None or y is None:
            hard.append('%s: есть только в %s' % (n, 'B' if x is None else 'A'))
            continue
        for k in IDENTITY_FILE_FIELDS:
            if x.get(k) != y.get(k):
                hard.append('%s: %s %s ≠ %s' % (n, k, x.get(k), y.get(k)))
        if any(x.get(k) != y.get(k) for k in REFERENCE_FILE_FIELDS):
            soft.append('%s: %s байт / %s байт' % (n, x.get('bytes'), y.get('bytes')))
    return hard, soft


def main(argv):
    opts = argv[1:]
    names = ['A', 'B']
    summary = None
    if '--names' in opts:
        i = opts.index('--names')
        names = opts[i + 1:i + 3]
        del opts[i:i + 3]
    if '--summary' in opts:
        i = opts.index('--summary')
        summary = opts[i + 1]
        del opts[i:i + 2]
    if len(opts) != 2:
        print(__doc__)
        return 2
    a, b = load(opts[0]), load(opts[1])
    hard, soft = compare(a, b)
    title = 'Сверка манифестов %s: %s ↔ %s' % (a.get('profile', '?'), names[0], names[1])
    lines = ['### ' + title, '']
    if hard:
        lines.append('**Содержимое разошлось** (тождество — rows и sha256_tsv):')
        lines += ['* ' + h for h in hard]
    else:
        lines.append('Содержимое тождественно: %d файлов, rows и sha256_tsv совпали.' % len(a.get('files', [])))
    lines.append('')
    if soft:
        lines.append('Справочно: сжатые байты отличаются у %d файлов (другая сборка zlib; не ошибка):' % len(soft))
        lines += ['* ' + s for s in soft]
    else:
        lines.append('Сжатые байты тоже совпали у всех файлов.')
    text = '\n'.join(lines) + '\n'
    print(text, end='')
    if summary:
        with open(summary, 'a', encoding='utf-8') as fo:
            fo.write(text + '\n')
    return 1 if hard else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
