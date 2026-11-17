-- air-data-v1 — таблицы. Сгенерировано из описания схемы; руками не править.
-- Порядок развёртывания: schema-tables.sql → загрузка TSV (COPY) → schema-constraints.sql.
-- Моменты — timestamptz; в TSV они — секунды Unix (UTC): загрузчик переводит to_timestamp(...).
-- Деньги — numeric(14,2), рубли; пустое поле TSV — NULL.

-- ======================================================================
-- Сеть: аэропорты, самолёты, маршруты, рейсы

CREATE TABLE airports (
    airport_code text NOT NULL,
    name_en      text NOT NULL,
    name_ru      text NOT NULL,
    city_en      text NOT NULL,
    city_ru      text NOT NULL,
    country_en   text NOT NULL,
    country_ru   text NOT NULL,
    longitude    numeric(9,6) NOT NULL,
    latitude     numeric(9,6) NOT NULL,
    timezone     text NOT NULL
);
COMMENT ON TABLE airports IS 'Аэропорты мира (в маршрутной сети — часть из них)';
COMMENT ON COLUMN airports.airport_code IS 'Код аэропорта ИАТА';
COMMENT ON COLUMN airports.name_en IS 'Название по-английски';
COMMENT ON COLUMN airports.name_ru IS 'Название по-русски';
COMMENT ON COLUMN airports.city_en IS 'Город по-английски';
COMMENT ON COLUMN airports.city_ru IS 'Город по-русски';
COMMENT ON COLUMN airports.country_en IS 'Страна по-английски';
COMMENT ON COLUMN airports.country_ru IS 'Страна по-русски';
COMMENT ON COLUMN airports.longitude IS 'Долгота, градусы';
COMMENT ON COLUMN airports.latitude IS 'Широта, градусы';
COMMENT ON COLUMN airports.timezone IS 'Часовой пояс (IANA)';

CREATE TABLE airplanes (
    airplane_code text NOT NULL,
    model_en      text NOT NULL,
    model_ru      text NOT NULL,
    range_km      integer NOT NULL,
    speed_kmh     integer NOT NULL
);
COMMENT ON TABLE airplanes IS 'Модели самолётов парка';
COMMENT ON COLUMN airplanes.airplane_code IS 'Код модели ИАТА';
COMMENT ON COLUMN airplanes.model_en IS 'Модель по-английски';
COMMENT ON COLUMN airplanes.model_ru IS 'Модель по-русски';
COMMENT ON COLUMN airplanes.range_km IS 'Дальность, км';
COMMENT ON COLUMN airplanes.speed_kmh IS 'Крейсерская скорость, км/ч';

CREATE TABLE seats (
    airplane_code   text NOT NULL,
    seat_no         text NOT NULL,
    fare_conditions text NOT NULL
);
COMMENT ON TABLE seats IS 'Места в салоне модели';
COMMENT ON COLUMN seats.airplane_code IS 'Модель самолёта';
COMMENT ON COLUMN seats.seat_no IS 'Номер места';
COMMENT ON COLUMN seats.fare_conditions IS 'Класс обслуживания: Economy, Comfort, Business';

CREATE TABLE routes (
    route_no          text NOT NULL,
    valid_from        timestamptz NOT NULL,
    valid_to          timestamptz NOT NULL,
    departure_airport text NOT NULL,
    arrival_airport   text NOT NULL,
    airplane_code     text NOT NULL,
    days_of_week      text NOT NULL,
    scheduled_time    text NOT NULL,
    duration_min      integer NOT NULL
);
COMMENT ON TABLE routes IS 'Маршруты: версия маршрута действует в периоде [valid_from, valid_to)';
COMMENT ON COLUMN routes.route_no IS 'Номер маршрута (рейса)';
COMMENT ON COLUMN routes.valid_from IS 'Начало периода действия версии (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN routes.valid_to IS 'Конец периода действия (не включая) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN routes.departure_airport IS 'Аэропорт вылета';
COMMENT ON COLUMN routes.arrival_airport IS 'Аэропорт прилёта';
COMMENT ON COLUMN routes.airplane_code IS 'Модель самолёта';
COMMENT ON COLUMN routes.days_of_week IS 'Дни недели вылета через запятую: 1 — пн … 7 — вс';
COMMENT ON COLUMN routes.scheduled_time IS 'Местное время вылета, ЧЧ:ММ:СС';
COMMENT ON COLUMN routes.duration_min IS 'Время в пути, минуты';

CREATE TABLE flights (
    flight_id           integer NOT NULL,
    route_no            text NOT NULL,
    status              text NOT NULL,
    scheduled_departure timestamptz NOT NULL,
    scheduled_arrival   timestamptz NOT NULL,
    actual_departure    timestamptz,
    actual_arrival      timestamptz
);
COMMENT ON TABLE flights IS 'Рейсы: вылеты по расписанию и фактически';
COMMENT ON COLUMN flights.flight_id IS 'Рейс';
COMMENT ON COLUMN flights.route_no IS 'Номер маршрута; версия — та, чей период содержит плановый вылет';
COMMENT ON COLUMN flights.status IS 'Состояние рейса на «сейчас» выгрузки: Scheduled, On Time, Delayed, Boarding, Departed, Arrived, Cancelled';
COMMENT ON COLUMN flights.scheduled_departure IS 'Вылет по расписанию (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN flights.scheduled_arrival IS 'Прилёт по расписанию (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN flights.actual_departure IS 'Фактический вылет; NULL — ещё не вылетел или отменён (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN flights.actual_arrival IS 'Фактический прилёт; NULL — ещё не прилетел или отменён (в TSV — секунды Unix, UTC)';

-- ======================================================================
-- Продажи: брони, билеты, перелёты, посадочные талоны

CREATE TABLE bookings (
    book_ref     text NOT NULL,
    book_date    timestamptz NOT NULL,
    total_amount numeric(14,2) NOT NULL
);
COMMENT ON TABLE bookings IS 'Брони; отобраны целиком по модели сезонности (см. SOURCE.md)';
COMMENT ON COLUMN bookings.book_ref IS 'Номер брони';
COMMENT ON COLUMN bookings.book_date IS 'Момент бронирования (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN bookings.total_amount IS 'Сумма брони — сумма цен её перелётов';

CREATE TABLE tickets (
    ticket_no      text NOT NULL,
    book_ref       text NOT NULL,
    passenger_id   text NOT NULL,
    passenger_name text NOT NULL,
    outbound       boolean NOT NULL
);
COMMENT ON TABLE tickets IS 'Билеты: пассажир брони и направление';
COMMENT ON COLUMN tickets.ticket_no IS 'Номер билета';
COMMENT ON COLUMN tickets.book_ref IS 'Бронь';
COMMENT ON COLUMN tickets.passenger_id IS 'Документ пассажира';
COMMENT ON COLUMN tickets.passenger_name IS 'Имя пассажира';
COMMENT ON COLUMN tickets.outbound IS 'Туда (t) или обратно (f)';

CREATE TABLE segments (
    ticket_no       text NOT NULL,
    flight_id       integer NOT NULL,
    fare_conditions text NOT NULL,
    price           numeric(14,2) NOT NULL
);
COMMENT ON TABLE segments IS 'Перелёты билета: рейс, класс, цена';
COMMENT ON COLUMN segments.ticket_no IS 'Билет';
COMMENT ON COLUMN segments.flight_id IS 'Рейс';
COMMENT ON COLUMN segments.fare_conditions IS 'Класс обслуживания: Economy, Comfort, Business';
COMMENT ON COLUMN segments.price IS 'Цена перелёта';

CREATE TABLE boarding_passes (
    ticket_no     text NOT NULL,
    flight_id     integer NOT NULL,
    seat_no       text NOT NULL,
    boarding_no   integer,
    boarding_time timestamptz
);
COMMENT ON TABLE boarding_passes IS 'Посадочные талоны';
COMMENT ON COLUMN boarding_passes.ticket_no IS 'Билет';
COMMENT ON COLUMN boarding_passes.flight_id IS 'Рейс';
COMMENT ON COLUMN boarding_passes.seat_no IS 'Место';
COMMENT ON COLUMN boarding_passes.boarding_no IS 'Номер посадки по порядку; NULL — посадки ещё не было';
COMMENT ON COLUMN boarding_passes.boarding_time IS 'Момент посадки; NULL — посадки ещё не было (в TSV — секунды Unix, UTC)';
