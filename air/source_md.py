"""SOURCE.md для набора air-data-v1 — из manifest.json."""
import json, sys, pathlib

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

out = pathlib.Path(sys.argv[1])
m = json.loads((out / 'manifest.json').read_text(encoding='utf-8'))
rows = {f['table']: f for f in m['files']}
mb = lambda t: f"{rows[t]['bytes'] / 1e6:.1f}".replace('.', ',')
sp = lambda x: f'{x:,}'.replace(',', ' ')
n = lambda t: f"{rows[t]['rows']:,}".replace(',', ' ')
sel = m['selection']
month_names = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек']
region_names = {'EU': 'Европа', 'NA': 'США и Канада', 'CN': 'Китай', 'JP': 'Япония', 'IN': 'Индия и Непал',
                'RU': 'Россия', 'SH': 'Чили и Новая Зеландия',
                'United States': 'США', 'Canada': 'Канада', 'Chile': 'Чили'}
wd_names = {1: 'пн', 2: 'вт', 3: 'ср', 4: 'чт', 5: 'пт', 6: 'сб', 7: 'вс'}

text = f"""# Авиаперевозки — набор данных `{m['version']}`

Данные домена «авиа» Stream Forge (Hammerhall). Источник — демонстрационная база Postgres Pro
«Авиаперевозки» 2.0, выгрузка `demo-20250901-2y` (2 года, 730 дней), лицензия MIT,
© 2025 Postgres Professional — текст в `LICENSE`. Данные сгенерированы: люди, документы
и брони вымышлены.

«Сейчас» в данных — **{m['now'][:10]}**: рейсы до этой даты прилетели (или отменены), после — по
расписанию; продажи — по {m['last_booking'][:10]}.

## Что изменено против выгрузки

Значения не менялись. Сделано три вещи:

1. **Время** — секунды Unix, UTC; доли секунды отброшены.
2. **Брони отобраны целиком** — со всеми билетами, сегментами и посадочными талонами: из
   {sp(m['bookings_total'])} оставлено {sp(m['bookings_kept'])} ({str(round(m['bookings_kept'] / m['bookings_total'] * 100, 1)).replace('.', ',')} %).
   Отбор задаёт сезонность, которой у генератора нет: доля оставленных броней зависит от страны,
   местной даты и дня недели **первого вылета брони**. В праздничные окна бронь остаётся всегда,
   в остальные дни — с вероятностью «месяц × день недели». Решает не случай, а номер брони: первые
   4 байта MD5(номер брони) как целое без знака, little-endian, делённые на 2^32, меньше доли —
   бронь остаётся. Сборка повторяема.
3. **Строки упорядочены**, файлы сжаты gzip без отметки времени.

Доля по месяцу — по региону страны вылета:

| регион | {' | '.join(month_names)} |
|---|{'---|' * 12}
"""
for r, vs in sel['month'].items():
    text += f"| {region_names[r]} | " + ' | '.join(f'{v:.2f}' for v in vs) + ' |\n'
text += "\nДоля по дню недели местного вылета: " + ', '.join(f"{wd_names[int(k)]} {v:.2f}" for k, v in sel['weekday'].items()) + ".\n\n"
text += "Праздничные окна (местная дата вылета, включительно):\n\n| кто | с | по | что |\n|---|---|---|---|\n"
for h in sel['holidays']:
    text += f"| {region_names.get(h['who'], h['who'])} | {h['from']} | {h['to']} | {h['name']} |\n"
text += f"""
Модель сверена с открытыми данными: загрузка рейсов в реальности — 74–89 % (провал — февраль, пик —
июнь–июль), Европа сезонна сильнее всех, Азия ровнее всех, по дням недели: воскресенье > пятница >
понедельник > четверг > суббота > среда > вторник.

Что вышло (загрузка рейсов — проданные места к местам в самолёте, по прилетевшим рейсам):
в среднем ≈ 73 % (в выгрузке без отбора — ≈ 86 %); провал к пику по регионам — в 1,2–1,4 раза;
Чуньюнь 2026 у вылетов из Китая — 87 % против 74 % в ноябре; неделя Дня благодарения 2026
у вылетов из США — 80 % против 73 % за неделю до неё; вторник и среда — 71 % против 76 %
в воскресенье. Пассажиры обратных рейсов и пересадок летят из других стран, поэтому сезон
одного региона виден и в чужих аэропортах — как в жизни.

## Файлы

TSV, UTF-8, разделитель — табуляция, первая строка — заголовок, NULL — пустое поле, gzip.

| файл | строк | МБ | поля |
|---|---|---|---|
| `airports.tsv.gz` | {n('airports')} | {mb('airports')} | `airport_code`, `name_en`, `name_ru`, `city_en`, `city_ru`, `country_en`, `country_ru`, `longitude`, `latitude`, `timezone` — все аэропорты мира; в маршрутной сети — 73 |
| `airplanes.tsv.gz` | {n('airplanes')} | {mb('airplanes')} | `airplane_code`, `model_en`, `model_ru`, `range_km`, `speed_kmh` |
| `seats.tsv.gz` | {n('seats')} | {mb('seats')} | `airplane_code`, `seat_no`, `fare_conditions` (Economy, Comfort, Business) |
| `routes.tsv.gz` | {n('routes')} | {mb('routes')} | `route_no`, `valid_from`, `valid_to` — период [с, по), `departure_airport`, `arrival_airport`, `airplane_code`, `days_of_week` (1 — пн … 7 — вс, через запятую), `scheduled_time` — местное время вылета, `duration_min` |
| `flights.tsv.gz` | {n('flights')} | {mb('flights')} | `flight_id`, `route_no`, `status`, `scheduled_departure`, `scheduled_arrival`, `actual_departure`, `actual_arrival` |
| `bookings.tsv.gz` | {n('bookings')} | {mb('bookings')} | `book_ref`, `book_date`, `total_amount` |
| `tickets.tsv.gz` | {n('tickets')} | {mb('tickets')} | `ticket_no`, `book_ref`, `passenger_id`, `passenger_name`, `outbound` (t / f) |
| `segments.tsv.gz` | {n('segments')} | {mb('segments')} | `ticket_no`, `flight_id`, `fare_conditions`, `price` |
| `boarding_passes.tsv.gz` | {n('boarding_passes')} | {mb('boarding_passes')} | `ticket_no`, `flight_id`, `seat_no`, `boarding_no`, `boarding_time` |

Версия маршрута для рейса — та, чей период действия содержит плановый вылет рейса. Сумма брони
равна сумме цен её сегментов. Контрольные суммы — в `manifest.json` (`sha256` — сжатого файла,
`sha256_tsv` — содержимого).
"""
(out / 'SOURCE.md').write_text(text, encoding='utf-8')
print('SOURCE.md ok')
