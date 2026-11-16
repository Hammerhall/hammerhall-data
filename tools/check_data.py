"""Проверка скачанного выпуска любого набора (sto-data-v1, air-data-v1, …) против ожиданий из репозитория.

Запуск:  python3 -X utf8 -I tools/check_data.py ПАПКА_ВЫПУСКА [--expected ФАЙЛ] [--no-aggregates]
Windows: py -3 -X utf8 -I tools\\check_data.py ПАПКА_ВЫПУСКА

Ожидания по умолчанию — <код>/expected/<версия>-<профиль>.json в репозитории (папка выше этого скрипта): версия — из
manifest.json выпуска, код набора — её начало до «-data-v», профиль — из манифеста (нет — full: выпуск одним
профилем). Файл ожиданий пишет генератор набора (команда freeze) из эталонного прогона; у набора без генератора — из
манифеста эталонного выпуска (тогда в нём нет агрегатов).

Тождество выпуска — содержимое, от сборки zlib оно не зависит: поля manifest.json кроме списка файлов; у каждой
таблицы — число строк и sha256 разжатого TSV (sha256_tsv); sha256 текстов (LICENSE, DDL, ER-диаграмма, загрузчик);
контрольные агрегаты. Сжатый файл сверяется с manifest.json того же выпуска — это проверка скачивания. Байты .tsv.gz,
manifest.json целиком и SOURCE.md — справочно: другая сборка zlib даёт другие байты того же содержимого.

Нужен только Python 3.8+ (стандартная библиотека). Счёт агрегатов совпадает с генератором (Aggregates.java):
числа — целые со шкалой, текст — побайтно UTF-8, пустое поле — NULL.
"""
import gzip
import hashlib
import json
import pathlib
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

TEXTS_REFERENCE_ONLY = ['manifest.json', 'SOURCE.md']


def scaled(b, scale):
    """b'-12.5' при шкале 2 → -1250; дробных знаков больше шкалы, пусто, мусор — ошибка."""
    neg = b.startswith(b'-')
    s = b[1:] if neg else b
    ip, dot, fp = s.partition(b'.')
    if b'.' in fp or len(fp) > scale or not (ip + fp) or not (ip + fp).isdigit():
        raise ValueError('не число со шкалой: %r' % b)
    v = int(ip + fp.ljust(scale, b'0'))
    return -v if neg else v


def fmt(v, scale):
    """129050 при шкале 2 → '1290.50'; -7 → '-0.07'."""
    if scale == 0:
        return str(v)
    d = str(-v if v < 0 else v).rjust(scale + 1, '0')
    return ('-' if v < 0 else '') + d[:-scale] + '.' + d[-scale:]


class Acc:
    def __init__(self, spec):
        self.spec = spec
        self.op = spec['op']
        self.scale = spec.get('scale')
        self.eq = spec['arg'].encode('utf-8') if 'arg' in spec else None
        self.count = 0
        self.total = 0
        self.best = None
        self.seen = set() if self.op == 'distinct' else None
        if self.op not in ('sum', 'min', 'max', 'nulls', 'distinct', 'count_eq'):
            raise ValueError('неизвестный агрегат ' + self.op)

    def accept(self, f):
        op = self.op
        if op == 'nulls':
            if not f:
                self.count += 1
        elif op == 'count_eq':
            if f == self.eq:
                self.count += 1
        elif op == 'distinct':
            if f:
                self.seen.add(f)
        elif op == 'sum':
            if f:
                self.total += scaled(f, self.scale)
        elif f:
            v = scaled(f, self.scale) if self.scale is not None else f
            if self.best is None or (v < self.best if op == 'min' else v > self.best):
                self.best = v

    def value(self):
        if self.op in ('nulls', 'count_eq'):
            return self.count
        if self.op == 'distinct':
            return len(self.seen)
        if self.op == 'sum':
            return fmt(self.total, self.scale)
        if self.best is None:
            return None
        return fmt(self.best, self.scale) if self.scale is not None else self.best.decode('utf-8')

    def label(self):
        return '%s(%s%s)' % (self.op, self.spec['column'], '=' + self.spec['arg'] if 'arg' in self.spec else '')


def scan(path, specs):
    """Один проход: строки (без заголовка), sha256 разжатого и сжатого, значения агрегатов."""
    accs = [Acc(s) for s in specs]
    plain, packed = hashlib.sha256(), hashlib.sha256()
    with open(path, 'rb') as raw:
        for chunk in iter(lambda: raw.read(1 << 20), b''):
            packed.update(chunk)
    lines = 0
    with gzip.open(path, 'rb') as fi:
        if not accs:
            for chunk in iter(lambda: fi.read(1 << 20), b''):
                plain.update(chunk)
                lines += chunk.count(b'\n')
        else:
            header = fi.readline()
            plain.update(header)
            lines = 1
            names = header.rstrip(b'\n').decode('utf-8').split('\t')
            cols = []
            for a in accs:
                if a.spec['column'] not in names:
                    raise ValueError('%s: нет поля %s в заголовке' % (path.name, a.spec['column']))
                cols.append(names.index(a.spec['column']))
            need = max(cols) + 1
            pairs = list(zip(cols, accs))
            for line in fi:
                plain.update(line)
                lines += 1
                fields = line.rstrip(b'\n').split(b'\t', need)
                for c, a in pairs:
                    a.accept(fields[c])
    return lines - 1, plain.hexdigest(), packed.hexdigest(), [a.value() for a in accs], accs


def parse(argv):
    """→ (папка, файл ожиданий или None, с агрегатами); неверный вызов — None."""
    rest, expected, with_aggs = [], None, True
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == '--expected' and i + 1 < len(argv):
            expected = pathlib.Path(argv[i + 1])
            i += 1
        elif a == '--no-aggregates':
            with_aggs = False
        elif a.startswith('--'):
            return None
        else:
            rest.append(a)
        i += 1
    return (pathlib.Path(rest[0]), expected, with_aggs) if len(rest) == 1 else None


def default_expected(m):
    """<корень репозитория>/<код>/expected/<версия>-<профиль>.json."""
    version = m.get('version', '')
    code, sep, _ = version.partition('-data-v')
    if not sep or not code:
        return None
    root = pathlib.Path(__file__).resolve().parent.parent
    return root / code / 'expected' / ('%s-%s.json' % (version, m.get('profile', 'full')))


def main(argv):
    parsed = parse(argv)
    if parsed is None:
        print(__doc__)
        return 2
    d, exp_path, with_aggs = parsed
    if not (d / 'manifest.json').exists():
        print('НЕТ  manifest.json в', d, '— это не папка выпуска')
        return 2
    m = json.loads((d / 'manifest.json').read_text(encoding='utf-8'))
    if exp_path is None:
        exp_path = default_expected(m)
        if exp_path is None:
            print('НЕТ  manifest.json: версия «%s» — не <код>-data-vN; файл ожиданий — --expected ФАЙЛ' % m.get('version'))
            return 2
    if not exp_path.exists():
        print('НЕТ  ожиданий', exp_path, '— выпуск этой версии и профиля в репозитории не описан (или --expected ФАЙЛ)')
        return 2
    e = json.loads(exp_path.read_text(encoding='utf-8'))
    print('проверка', d, 'против', exp_path)
    ok = True

    have = {k: v for k, v in m.items() if k != 'files'}
    want = e['manifest']
    bad = [k for k in dict.fromkeys(list(want) + list(have)) if want.get(k) != have.get(k)]
    for k in bad:
        print('НЕТ  manifest.json: «%s» — ждали %s, в выпуске %s' % (
            k, json.dumps(want.get(k), ensure_ascii=False), json.dumps(have.get(k), ensure_ascii=False)))
    ok &= not bad
    if not bad:
        print('OK   manifest.json: %s, профиль %s%s' % (m['version'], m.get('profile', 'full'),
                                                       ', seed %s' % m['seed'] if 'seed' in m else ''))

    in_manifest = {f['name']: f for f in m['files']}
    gz_differ = 0
    for ef in e['files']:
        name = ef['name']
        mf = in_manifest.pop(name, None)
        p = d / name
        if mf is None or not p.exists():
            ok = False
            print('НЕТ ', name, '— нет в manifest.json' if mf is None else '— нет файла')
            continue
        # агрегатов может не быть: ожидания набора без генератора собраны из манифеста эталонного выпуска
        specs = [a for a in e.get('aggregates', []) if a['table'] == ef['table']] if with_aggs else []
        rows, sha_tsv, sha_gz, values, accs = scan(p, specs)
        why = []
        if rows != ef['rows']:
            why.append('строк %d, ждали %d' % (rows, ef['rows']))
        if sha_tsv != ef['sha256_tsv']:
            why.append('содержимое другое (sha256_tsv)')
        if sha_gz != mf['sha256']:
            why.append('файл не совпал с manifest.json (sha256) — скачан с ошибкой?')
        if mf['rows'] != ef['rows'] or mf['sha256_tsv'] != ef['sha256_tsv']:
            why.append('manifest.json не тот (rows / sha256_tsv)')
        for s, v, a in zip(specs, values, accs):
            if v != s['value']:
                why.append('%s = %s, ждали %s' % (a.label(), v, s['value']))
        if sha_gz != ef.get('sha256_gz_reference'):
            gz_differ += 1
        ok &= not why
        print('OK  ' if not why else 'НЕТ ', name, '— %d строк' % rows
              + (', агрегатов %d' % len(specs) if specs else '') + (': ' + '; '.join(why) if why else ''))
    for extra in in_manifest:
        ok = False
        print('НЕТ ', extra, '— лишний файл в manifest.json (в ожиданиях его нет)')
    for t in e.get('texts', []):
        p = d / t['name']
        good = p.exists() and hashlib.sha256(p.read_bytes()).hexdigest() == t['sha256']
        ok &= good
        print('OK  ' if good else 'НЕТ ', t['name'], '' if good else ('— текст другой' if p.exists() else '— нет файла'))
    for r in e.get('reference_only', TEXTS_REFERENCE_ONLY):
        present = (d / r).exists()
        ok &= present
        print('OK  ' if present else 'НЕТ ', r, '(есть; справочно — зависит от байтов gzip)' if present else '— нет файла')
    if gz_differ:
        print('справочно: у %d файлов .tsv.gz сжатые байты не те, что при заморозке (другая сборка zlib); '
              'содержимое — то же, это не ошибка' % gz_differ)
    print('ВЫПУСК В ПОРЯДКЕ' if ok else 'ВЫПУСК НЕ СОВПАЛ С ОЖИДАНИЯМИ')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv))
