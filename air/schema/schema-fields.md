# air-data-v1 — поля

### Сеть: аэропорты, самолёты, маршруты, рейсы

#### `airports` — Аэропорты мира (в маршрутной сети — часть из них)

Ключ — `airport_code`; строки упорядочены по `airport_code`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `airport_code` | text |  | Код аэропорта ИАТА | demo-20250901-2y, как есть |
| `name_en` | text |  | Название по-английски | demo-20250901-2y, как есть |
| `name_ru` | text |  | Название по-русски | demo-20250901-2y, как есть |
| `city_en` | text |  | Город по-английски | demo-20250901-2y, как есть |
| `city_ru` | text |  | Город по-русски | demo-20250901-2y, как есть |
| `country_en` | text |  | Страна по-английски | demo-20250901-2y, как есть |
| `country_ru` | text |  | Страна по-русски | demo-20250901-2y, как есть |
| `longitude` | decimal |  | Долгота, градусы | demo-20250901-2y, как есть |
| `latitude` | decimal |  | Широта, градусы | demo-20250901-2y, как есть |
| `timezone` | text |  | Часовой пояс (IANA) | demo-20250901-2y, как есть |

#### `airplanes` — Модели самолётов парка

Ключ — `airplane_code`; строки упорядочены по `airplane_code`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `airplane_code` | text |  | Код модели ИАТА | demo-20250901-2y, как есть |
| `model_en` | text |  | Модель по-английски | demo-20250901-2y, как есть |
| `model_ru` | text |  | Модель по-русски | demo-20250901-2y, как есть |
| `range_km` | int |  | Дальность, км | demo-20250901-2y, как есть |
| `speed_kmh` | int |  | Крейсерская скорость, км/ч | demo-20250901-2y, как есть |

#### `seats` — Места в салоне модели

Ключ — `airplane_code, seat_no`; строки упорядочены по `airplane_code, seat_no`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `airplane_code` | text → `airplanes` |  | Модель самолёта | demo-20250901-2y, как есть |
| `seat_no` | text |  | Номер места | demo-20250901-2y, как есть |
| `fare_conditions` | text |  | Класс обслуживания: `Economy`, `Comfort`, `Business` | demo-20250901-2y, как есть |

#### `routes` — Маршруты: версия маршрута действует в периоде [valid_from, valid_to)

Ключ — `route_no, valid_from`; строки упорядочены по `route_no, valid_from`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `route_no` | text |  | Номер маршрута (рейса) | demo-20250901-2y, как есть |
| `valid_from` | moment |  | Начало периода действия версии | demo-20250901-2y |
| `valid_to` | moment |  | Конец периода действия (не включая) | demo-20250901-2y |
| `departure_airport` | text → `airports` |  | Аэропорт вылета | demo-20250901-2y, как есть |
| `arrival_airport` | text → `airports` |  | Аэропорт прилёта | demo-20250901-2y, как есть |
| `airplane_code` | text → `airplanes` |  | Модель самолёта | demo-20250901-2y, как есть |
| `days_of_week` | text |  | Дни недели вылета через запятую: 1 — пн … 7 — вс | demo-20250901-2y, как есть |
| `scheduled_time` | text |  | Местное время вылета, ЧЧ:ММ:СС | demo-20250901-2y, как есть |
| `duration_min` | int |  | Время в пути, минуты | demo-20250901-2y |

#### `flights` — Рейсы: вылеты по расписанию и фактически

Ключ — `flight_id`; строки упорядочены по `flight_id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `flight_id` | int |  | Рейс | demo-20250901-2y, как есть |
| `route_no` | text |  | Номер маршрута; версия — та, чей период содержит плановый вылет | demo-20250901-2y, как есть |
| `status` | text |  | Состояние рейса на «сейчас» выгрузки: `Scheduled`, `On Time`, `Delayed`, `Boarding`, `Departed`, `Arrived`, `Cancelled` | demo-20250901-2y, как есть |
| `scheduled_departure` | moment |  | Вылет по расписанию | demo-20250901-2y |
| `scheduled_arrival` | moment |  | Прилёт по расписанию | demo-20250901-2y |
| `actual_departure` | moment | да | Фактический вылет; NULL — ещё не вылетел или отменён | demo-20250901-2y |
| `actual_arrival` | moment | да | Фактический прилёт; NULL — ещё не прилетел или отменён | demo-20250901-2y |

### Продажи: брони, билеты, перелёты, посадочные талоны

#### `bookings` — Брони; отобраны целиком по модели сезонности (см. SOURCE.md)

Ключ — `book_ref`; строки упорядочены по `book_date, book_ref`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `book_ref` | text |  | Номер брони | demo-20250901-2y, как есть |
| `book_date` | moment |  | Момент бронирования | demo-20250901-2y |
| `total_amount` | money |  | Сумма брони — сумма цен её перелётов | demo-20250901-2y, как есть |

#### `tickets` — Билеты: пассажир брони и направление

Ключ — `ticket_no`; строки упорядочены по `ticket_no`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `ticket_no` | text |  | Номер билета | demo-20250901-2y, как есть |
| `book_ref` | text → `bookings` |  | Бронь | demo-20250901-2y, как есть |
| `passenger_id` | text |  | Документ пассажира | demo-20250901-2y, как есть |
| `passenger_name` | text |  | Имя пассажира | demo-20250901-2y, как есть |
| `outbound` | bool |  | Туда (t) или обратно (f) | demo-20250901-2y, как есть |

#### `segments` — Перелёты билета: рейс, класс, цена

Ключ — `ticket_no, flight_id`; строки упорядочены по `ticket_no, flight_id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `ticket_no` | text → `tickets` |  | Билет | demo-20250901-2y, как есть |
| `flight_id` | int → `flights` |  | Рейс | demo-20250901-2y, как есть |
| `fare_conditions` | text |  | Класс обслуживания: `Economy`, `Comfort`, `Business` | demo-20250901-2y, как есть |
| `price` | money |  | Цена перелёта | demo-20250901-2y, как есть |

#### `boarding_passes` — Посадочные талоны

Ключ — `ticket_no, flight_id`; строки упорядочены по `flight_id, boarding_no, ticket_no` (NULL в `boarding_no` — в конце).

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `ticket_no` | text |  | Билет | demo-20250901-2y, как есть |
| `flight_id` | int |  | Рейс | demo-20250901-2y, как есть |
| `seat_no` | text |  | Место | demo-20250901-2y, как есть |
| `boarding_no` | int | да | Номер посадки по порядку; NULL — посадки ещё не было | demo-20250901-2y, как есть |
| `boarding_time` | moment | да | Момент посадки; NULL — посадки ещё не было | demo-20250901-2y |

Составная ссылка: `(ticket_no, flight_id)` → `segments`.
