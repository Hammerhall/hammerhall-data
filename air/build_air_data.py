"""Сборка набора данных «Авиаперевозки» для Stream Forge (air-data-v1).

Вход — выгрузка демобазы Postgres Pro «Авиаперевозки» 2.0 (pg_dump, текстовый SQL; можно .gz)
или папка с уже нарезанными TSV (см. --tsv). Выход — папка с таблицами .tsv.gz, LICENSE,
SOURCE.md и manifest.json.

Значения не меняются. Делается три вещи:
  1. моменты времени — в секундах Unix (UTC), доли секунды отброшены;
  2. брони отбираются целиком (со всеми билетами, сегментами, посадочными талонами) —
     сезонность по региону, празднику и дню недели первого вылета брони (модель ниже);
  3. строки упорядочены, файлы сжаты gzip без отметки времени — сборка повторяема.

Запуск:  python build_air_data.py --sql demo-20250901-2y.sql --out air-data-v1
         python build_air_data.py --tsv нарезанные-tsv --out air-data-v1
Нужно:   Python 3.11+, duckdb==2.0.0 (pip install duckdb==2.0.0 pytz)
"""
import argparse, datetime as dt, gzip, hashlib, json, os, pathlib, re, shutil, sys, tempfile

import duckdb

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

VERSION = 'air-data-v1'

# ---------------------------------------------------------------- модель сезонности
REGION = {
    'Germany': 'EU', 'Italy': 'EU', 'France': 'EU', 'United Kingdom': 'EU', 'Sweden': 'EU',
    'Finland': 'EU', 'Czech Republic': 'EU', 'Austria': 'EU',
    'United States': 'NA', 'Canada': 'NA', 'China': 'CN', 'Japan': 'JP',
    'India': 'IN', 'Nepal': 'IN', 'Russia': 'RU', 'Chile': 'SH', 'New Zealand': 'SH',
}
#            янв   фев   мар   апр   май   июн   июл   авг   сен   окт   ноя   дек
MONTH = {
    'EU': [0.84, 0.84, 0.88, 0.92, 0.94, 0.97, 1.00, 1.00, 0.95, 0.92, 0.85, 0.92],
    'NA': [0.84, 0.85, 0.95, 0.92, 0.95, 1.00, 1.00, 0.97, 0.88, 0.92, 0.90, 0.94],
    'CN': [0.92, 0.95, 0.86, 0.90, 0.93, 0.90, 1.00, 1.00, 0.88, 0.93, 0.86, 0.88],
    'JP': [0.90, 0.85, 0.95, 0.92, 0.92, 0.85, 0.95, 1.00, 0.90, 0.92, 0.90, 0.93],
    'IN': [0.93, 0.90, 0.90, 0.95, 1.00, 0.98, 0.85, 0.84, 0.86, 0.95, 0.97, 0.97],
    'RU': [0.90, 0.86, 0.85, 0.88, 0.94, 0.98, 1.00, 1.00, 0.93, 0.88, 0.84, 0.92],
    'SH': [1.00, 1.00, 0.92, 0.88, 0.84, 0.84, 0.95, 0.86, 0.90, 0.90, 0.93, 1.00],
}
# ISO-день недели местного вылета: 1 — понедельник … 7 — воскресенье
WEEKDAY = {1: 0.95, 2: 0.86, 3: 0.88, 4: 0.94, 5: 0.98, 6: 0.90, 7: 1.00}
# праздники и пики: (кто — регион или страна, с, по) — по местной дате вылета, включительно
HOLIDAYS = [
    ('CN', '2025-09-30', '2025-10-07', 'Золотая неделя'),
    ('CN', '2026-02-02', '2026-03-13', 'Чуньюнь (Новый год 17.02.2026)'),
    ('CN', '2026-04-30', '2026-05-05', 'праздник труда'),
    ('CN', '2026-09-30', '2026-10-07', 'Золотая неделя'),
    ('CN', '2027-01-22', '2027-03-02', 'Чуньюнь (Новый год 06.02.2027)'),
    ('CN', '2027-04-30', '2027-05-05', 'праздник труда'),
    ('CN', '2027-09-30', '2027-10-07', 'Золотая неделя'),
    ('JP', '2025-12-27', '2026-01-04', 'новогодние'), ('JP', '2026-04-29', '2026-05-06', 'Золотая неделя'),
    ('JP', '2026-08-11', '2026-08-17', 'Обон'), ('JP', '2026-12-27', '2027-01-04', 'новогодние'),
    ('JP', '2027-04-29', '2027-05-05', 'Золотая неделя'), ('JP', '2027-08-11', '2027-08-17', 'Обон'),
    ('IN', '2025-10-17', '2025-10-26', 'Дивали (20.10.2025)'), ('IN', '2025-12-20', '2026-01-03', 'новогодние'),
    ('IN', '2026-11-05', '2026-11-14', 'Дивали (08.11.2026)'), ('IN', '2026-12-20', '2027-01-03', 'новогодние'),
    ('IN', '2027-10-26', '2027-11-04', 'Дивали (29.10.2027)'),
    ('United States', '2025-11-22', '2025-12-01', 'День благодарения (27.11.2025)'),
    ('United States', '2026-11-21', '2026-11-30', 'День благодарения (26.11.2026)'),
    ('United States', '2026-07-01', '2026-07-06', 'День независимости'),
    ('United States', '2027-07-01', '2027-07-06', 'День независимости'),
    ('Canada', '2025-10-10', '2025-10-13', 'День благодарения (13.10.2025)'),
    ('Canada', '2026-10-09', '2026-10-12', 'День благодарения (12.10.2026)'),
    ('Canada', '2027-10-08', '2027-10-11', 'День благодарения (11.10.2027)'),
    ('NA', '2025-12-18', '2026-01-04', 'Рождество'), ('NA', '2026-03-07', '2026-03-29', 'весенние каникулы'),
    ('NA', '2026-12-18', '2027-01-04', 'Рождество'), ('NA', '2027-03-06', '2027-03-28', 'весенние каникулы'),
    ('EU', '2025-12-19', '2026-01-04', 'Рождество'), ('EU', '2026-03-28', '2026-04-12', 'Пасха (05.04.2026)'),
    ('EU', '2026-12-19', '2027-01-04', 'Рождество'), ('EU', '2027-03-20', '2027-04-04', 'Пасха (28.03.2027)'),
    ('RU', '2025-12-28', '2026-01-11', 'новогодние каникулы'), ('RU', '2026-05-01', '2026-05-11', 'майские'),
    ('RU', '2026-12-28', '2027-01-10', 'новогодние каникулы'), ('RU', '2027-05-01', '2027-05-10', 'майские'),
    ('Chile', '2026-09-16', '2026-09-21', 'Фьестас Патриас'), ('Chile', '2027-09-16', '2027-09-21', 'Фьестас Патриас'),
    ('SH', '2025-12-15', '2026-01-31', 'южное лето, праздники'), ('SH', '2026-12-15', '2027-01-31', 'южное лето, праздники'),
]
# Бронь остаётся, если u < доля; u — первые 4 байта MD5(номер брони) как беззнаковое целое
# little-endian, делённое на 2^32 (в DuckDB это md5_number(...) % 2^32).

TABLES = ['airports', 'airplanes', 'seats', 'routes', 'flights',
          'bookings', 'tickets', 'segments', 'boarding_passes']


def split_sql(sql_path, out_dir):
    """Режет блоки COPY выгрузки в TSV (формат COPY text, \\N — NULL)."""
    hdr = re.compile(r'^COPY bookings\.(\w+) \((.*)\) FROM stdin;$')
    opener = gzip.open if str(sql_path).endswith('.gz') else open
    cur, name, header_lines = None, None, []
    with opener(sql_path, 'rt', encoding='utf-8', newline='\n') as f:
        for i, line in enumerate(f):
            if i < 40:
                header_lines.append(line)
            if cur is None:
                m = hdr.match(line.rstrip('\n'))
                if m:
                    name = m.group(1)
                    cur = open(out_dir / f'{name}.tsv', 'w', encoding='utf-8', newline='\n')
                    cur.write('\t'.join(c.strip() for c in m.group(2).split(',')) + '\n')
            elif line == '\\.\n':
                cur.close(); cur = None
            else:
                cur.write(line)
    (out_dir / 'dump-header.txt').write_text(''.join(header_lines), encoding='utf-8')


def raw(src, t):
    files = sorted(src.glob(f'{t}.tsv*'))
    if not files:
        sys.exit(f'нет таблицы {t} в {src}')
    return (f"read_csv('{files[0].as_posix()}', delim='\t', header=true, quote='', escape='', "
            f"nullstr='\\N', all_varchar=true)")


def main():
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument('--sql'); g.add_argument('--tsv')
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
    work = pathlib.Path(tempfile.mkdtemp(prefix='air-build-', dir=out.parent))
    try:
        if a.sql:
            print('режу выгрузку на таблицы…', flush=True)
            split_sql(pathlib.Path(a.sql), work); src = work
        else:
            src = pathlib.Path(a.tsv)
        con = duckdb.connect(str(work / 'build.duckdb'))
        con.execute("set timezone='UTC'")
        print('загружаю и считаю отбор броней…', flush=True)
        con.execute(f"""create table airports as select airport_code,
            json_extract_string(airport_name,'$.en') name_en, json_extract_string(airport_name,'$.ru') name_ru,
            json_extract_string(city,'$.en') city_en, json_extract_string(city,'$.ru') city_ru,
            json_extract_string(country,'$.en') country_en, json_extract_string(country,'$.ru') country_ru,
            split_part(trim(coordinates,'()'),',',1) longitude, split_part(trim(coordinates,'()'),',',2) latitude,
            timezone from {raw(src,'airports_data')}""")
        con.execute(f"""create table airplanes as select airplane_code, json_extract_string(model,'$.en') model_en,
            json_extract_string(model,'$.ru') model_ru, range range_km, speed speed_kmh from {raw(src,'airplanes_data')}""")
        con.execute(f"create table seats as select airplane_code, seat_no, fare_conditions from {raw(src,'seats')}")
        con.execute(f"""create table routes as select route_no,
            epoch(replace(split_part(trim(validity,'[)'),',',1),'"','')::timestamptz)::bigint valid_from,
            epoch(replace(split_part(trim(validity,'[)'),',',2),'"','')::timestamptz)::bigint valid_to,
            departure_airport, arrival_airport, airplane_code, trim(days_of_week,'{{}}') days_of_week,
            scheduled_time, (epoch(duration::interval)/60)::int duration_min from {raw(src,'routes')}""")
        con.execute(f"""create table flights as select flight_id::int flight_id, route_no, status,
            epoch(scheduled_departure::timestamptz)::bigint scheduled_departure, epoch(scheduled_arrival::timestamptz)::bigint scheduled_arrival,
            epoch(actual_departure::timestamptz)::bigint actual_departure, epoch(actual_arrival::timestamptz)::bigint actual_arrival
            from {raw(src,'flights')}""")
        for t in ['bookings', 'tickets', 'segments', 'boarding_passes']:
            con.execute(f"create table raw_{t} as select * from {raw(src,t)}")
        # первый вылет брони: аэропорт и местная дата
        con.execute("""create table first_dep as
            with fs as (select t.book_ref, s.flight_id from raw_tickets t join raw_segments s using(ticket_no)),
            fl as (select f.flight_id, f.scheduled_departure, r.departure_airport from flights f join routes r
                   on r.route_no=f.route_no and f.scheduled_departure>=r.valid_from and f.scheduled_departure<r.valid_to)
            select book_ref, arg_min(departure_airport, scheduled_departure) dep_airport, min(scheduled_departure) dep_at
            from fs join fl on fl.flight_id = fs.flight_id::int group by book_ref""")
        con.execute("create table region(country text, region text)")
        con.executemany("insert into region values (?, ?)", list(REGION.items()))
        con.execute("create table month_f(region text, month int, f double)")
        con.executemany("insert into month_f values (?, ?, ?)", [(r, i + 1, v) for r, vs in MONTH.items() for i, v in enumerate(vs)])
        con.execute("create table weekday_f(dow int, f double)")
        con.executemany("insert into weekday_f values (?, ?)", list(WEEKDAY.items()))
        con.execute("create table holiday(who text, d_from date, d_to date, name text)")
        con.executemany("insert into holiday values (?, ?::date, ?::date, ?)", HOLIDAYS)
        con.execute("""create table keep as
            with b as (select fd.book_ref, a.country_en country, r.region,
                       (to_timestamp(fd.dep_at) at time zone a.timezone)::date d
                       from first_dep fd join airports a on a.airport_code=fd.dep_airport join region r on r.country=a.country_en),
            s as (select b.*, case when exists (select 1 from holiday h where (h.who=b.region or h.who=b.country) and b.d between h.d_from and h.d_to)
                                   then 1.0 else m.f * w.f end share
                  from b join month_f m on m.region=b.region and m.month=month(b.d) join weekday_f w on w.dow=isodow(b.d))
            select book_ref from s where (md5_number(book_ref) % 4294967296)::double / 4294967296.0 < share""")
        n_all = con.execute("select count(*) from raw_bookings").fetchone()[0]
        n_keep = con.execute("select count(*) from keep").fetchone()[0]
        n_nofirst = con.execute("select count(*) from raw_bookings where book_ref not in (select book_ref from first_dep)").fetchone()[0]
        print(f'броней: {n_all:,}, оставлено {n_keep:,} ({n_keep / n_all:.1%}), без рейсов: {n_nofirst}', flush=True)
        con.execute("create table keep_t as select ticket_no from raw_tickets where book_ref in (select book_ref from keep)")
        queries = {
            'airports': "select * from airports order by airport_code",
            'airplanes': "select * from airplanes order by airplane_code",
            'seats': "select * from seats order by airplane_code, seat_no",
            'routes': "select * from routes order by route_no, valid_from",
            'flights': "select * from flights order by flight_id",
            'bookings': """select book_ref, epoch(book_date::timestamptz)::bigint book_date, total_amount
                           from raw_bookings where book_ref in (select book_ref from keep) order by book_date, book_ref""",
            'tickets': """select ticket_no, book_ref, passenger_id, passenger_name, outbound
                          from raw_tickets where book_ref in (select book_ref from keep) order by ticket_no""",
            'segments': """select ticket_no, flight_id::int flight_id, fare_conditions, price
                           from raw_segments where ticket_no in (select ticket_no from keep_t) order by ticket_no, flight_id""",
            'boarding_passes': """select ticket_no, flight_id::int flight_id, seat_no, boarding_no::int boarding_no,
                                  epoch(boarding_time::timestamptz)::bigint boarding_time
                                  from raw_boarding_passes where ticket_no in (select ticket_no from keep_t)
                                  order by flight_id, boarding_no nulls last, ticket_no""",
        }
        files = []
        for t in TABLES:
            plain = work / f'{t}.out.tsv'
            con.execute(f"copy ({queries[t]}) to '{plain.as_posix()}' (delimiter '\t', header true, nullstr '', quote '', escape '')")
            sha_plain, rows = hashlib.sha256(), -1
            gz_path = out / f'{t}.tsv.gz'
            with open(plain, 'rb') as fi, open(gz_path, 'wb') as raw_out:
                with gzip.GzipFile(filename='', mode='wb', fileobj=raw_out, compresslevel=9, mtime=0) as fo:
                    for chunk in iter(lambda: fi.read(1 << 20), b''):
                        sha_plain.update(chunk); rows += chunk.count(b'\n'); fo.write(chunk)
            plain.unlink()
            sha_gz = hashlib.sha256(gz_path.read_bytes()).hexdigest()
            files.append({'name': gz_path.name, 'table': t, 'rows': rows, 'bytes': gz_path.stat().st_size,
                          'sha256': sha_gz, 'sha256_tsv': sha_plain.hexdigest()})
            print(f'  {t}: {rows:,} строк, {gz_path.stat().st_size / 1e6:.1f} МБ', flush=True)
        now = con.execute("select max(book_date) from raw_bookings").fetchone()[0]
        manifest = {
            'version': VERSION,
            'source': 'Postgres Pro demo database «Airlines» 2.0, demo-20250901-2y (730 days), MIT © 2025 Postgres Professional',
            'now': '2027-09-01T00:00:00Z',
            'last_booking': str(now),
            'bookings_total': n_all, 'bookings_kept': n_keep,
            'selection': {'unit': 'booking', 'key': 'md5_number(book_ref) % 2^32 / 2^32 < share',
                          'share': 'holiday ? 1.0 : month[region][month] * weekday[isodow]',
                          'by': 'country, local date and weekday of the first departure of the booking',
                          'region': REGION, 'month': MONTH, 'weekday': WEEKDAY,
                          'holidays': [{'who': w, 'from': f, 'to': to, 'name': n} for w, f, to, n in HOLIDAYS]},
            'format': {'encoding': 'UTF-8', 'delimiter': 'TAB', 'header': True, 'null': 'empty field',
                       'time': 'Unix seconds, UTC (fractions dropped)', 'compression': 'gzip, mtime=0'},
            'files': files,
        }
        (out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding='utf-8')
        print('готово:', out, flush=True)
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == '__main__':
    main()
