"""Проверка собранного набора air-data-v1: строки и содержимое совпадают с эталонной сборкой.

Запуск: python check_air_data.py air-data-v1
Сверяется содержимое (sha256 разжатого TSV) — от версии zlib не зависит; сжатый файл — с manifest.json.
"""
import gzip, hashlib, json, pathlib, sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

EXPECTED = {
 "airports.tsv.gz": [
  5501,
  "a049dbc3ff9ee29a70f8c3546953090b326961ec600b0318e975288d47dfeb41"
 ],
 "airplanes.tsv.gz": [
  10,
  "f916848f2178174824f55c06426aa2d2479efa9df24ffec57e2d807cd5e836bb"
 ],
 "seats.tsv.gz": [
  1741,
  "21e3481b3ace9301a6b390ff36b6e06e4adb64eee0c3713e056ad3ed93d28059"
 ],
 "routes.tsv.gz": [
  7242,
  "a4f851b459e0288f76b2eda7e8079ca2652b1f78c589db89df43ff7b1c24d28a"
 ],
 "flights.tsv.gz": [
  135571,
  "f411f4a4d1d2f8e9d7434f9b132cd1ebc8424c89337ae1970c1bebfa98c61d17"
 ],
 "bookings.tsv.gz": [
  8470438,
  "b4dc338f60fce7bb157430938fb7a364f3c900efb8c043baf10cec73fb6b76c0"
 ],
 "tickets.tsv.gz": [
  18400054,
  "797c3c5efb943237870fc2bae9aa7c985f59bea38261944cfcd9a3e37fdeb1fc"
 ],
 "segments.tsv.gz": [
  24058980,
  "9d1646341e84d20099199a18e93875309d12e52bceceacf892702e24796eef4b"
 ],
 "boarding_passes.tsv.gz": [
  22957941,
  "09cd839559e40ba79c34e2059a5fdb74c53ca37e31ded0b01fdfab1c605e24f7"
 ]
}

d = pathlib.Path(sys.argv[1])
m = json.loads((d / 'manifest.json').read_text(encoding='utf-8'))
ok = True
for f in m['files']:
    p = d / f['name']
    h, rows = hashlib.sha256(), -1
    with gzip.open(p, 'rb') as fi:
        for chunk in iter(lambda: fi.read(1 << 20), b''):
            h.update(chunk); rows += chunk.count(b'\n')
    gz = hashlib.sha256(p.read_bytes()).hexdigest()
    exp_rows, exp_sha = EXPECTED[f['name']]
    good = rows == exp_rows and h.hexdigest() == exp_sha and gz == f['sha256']
    ok &= good
    print(('OK  ' if good else 'НЕТ '), f['name'], f'{rows:,} строк', '' if good else f'(ждали {exp_rows:,}, содержимое {"совпало" if h.hexdigest() == exp_sha else "другое"})')
for extra in ['LICENSE', 'SOURCE.md']:
    present = (d / extra).exists(); ok &= present
    print(('OK  ' if present else 'НЕТ '), extra)
print('НАБОР В ПОРЯДКЕ' if ok else 'НАБОР НЕ СОВПАЛ — выкладывать нельзя')
sys.exit(0 if ok else 1)
