package io.hammerhall.data.air;

import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;

import java.util.List;

import static io.hammerhall.data.core.schema.ColType.BOOL;
import static io.hammerhall.data.core.schema.ColType.DECIMAL;
import static io.hammerhall.data.core.schema.ColType.INT;
import static io.hammerhall.data.core.schema.ColType.MOMENT;
import static io.hammerhall.data.core.schema.ColType.MONEY;
import static io.hammerhall.data.core.schema.ColType.TEXT;

/**
 * Схема {@code air-data-v1} — единственное описание таблиц набора «Авиаперевозки». Поля, их порядок и порядок строк —
 * те же, что у выпуска, собранного прежней сборкой на Python: тот же вход даёт те же {@code sha256_tsv}. Значения —
 * из выгрузки демобазы как есть; преобразованы только время (секунды Unix), JSON-поля ({@code en} / {@code ru}),
 * координаты, период маршрута, дни недели и длительность (минуты).
 *
 * <p>{@code verbatimSource()} — у полей, чьё содержание взято из выгрузки без изменений (названия, города и страны,
 * модели, коды, номера броней и билетов, документы и имена пассажиров, места, классы, статусы, суммы; JSON-поле,
 * точка и массив только разобраны на части): это не наши слова, и проверка внутренних имён выпуска их значения
 * пропускает. Без пометки — поля, которые сборка вычисляет: моменты (секунды Unix) и длительность в минутах.
 */
public final class AirSchema {
    private AirSchema() {
    }

    public static final String VERSION = "air-data-v1";
    /** Порядок таблиц — порядок файлов в манифесте выпуска. */
    public static final List<String> TABLES = List.of("airports", "airplanes", "seats", "routes", "flights",
            "bookings", "tickets", "segments", "boarding_passes");
    public static final String[] FARES = {"Economy", "Comfort", "Business"};
    public static final String[] STATUSES = {"Scheduled", "On Time", "Delayed", "Boarding", "Departed", "Arrived",
            "Cancelled"};
    private static final String SRC = "demo-20250901-2y";

    public static Schema build() {
        Schema s = new Schema("air", VERSION)
                .group("network", "Сеть: аэропорты, самолёты, маршруты, рейсы")
                .group("sales", "Продажи: брони, билеты, перелёты, посадочные талоны");

        s.add(Table.of("airports", "network", "Аэропорты мира (в маршрутной сети — часть из них)").title("Аэропорты")
                .rows("≈ 5,5 тыс.")
                .col("airport_code", TEXT, "Код аэропорта ИАТА").source(SRC).verbatimSource()
                .col("name_en", TEXT, "Название по-английски").source(SRC).verbatimSource()
                .col("name_ru", TEXT, "Название по-русски").source(SRC).verbatimSource()
                .col("city_en", TEXT, "Город по-английски").source(SRC).verbatimSource()
                .col("city_ru", TEXT, "Город по-русски").source(SRC).verbatimSource()
                .col("country_en", TEXT, "Страна по-английски").source(SRC).verbatimSource()
                .col("country_ru", TEXT, "Страна по-русски").source(SRC).verbatimSource()
                .col("longitude", DECIMAL, "Долгота, градусы").scale(9, 6).source(SRC).verbatimSource()
                .col("latitude", DECIMAL, "Широта, градусы").scale(9, 6).source(SRC).verbatimSource()
                .col("timezone", TEXT, "Часовой пояс (IANA)").source(SRC).verbatimSource()
                .pk("airport_code"));

        s.add(Table.of("airplanes", "network", "Модели самолётов парка").title("Самолёты").rows("10")
                .col("airplane_code", TEXT, "Код модели ИАТА").source(SRC).verbatimSource()
                .col("model_en", TEXT, "Модель по-английски").source(SRC).verbatimSource()
                .col("model_ru", TEXT, "Модель по-русски").source(SRC).verbatimSource()
                .col("range_km", INT, "Дальность, км").source(SRC).verbatimSource()
                .col("speed_kmh", INT, "Крейсерская скорость, км/ч").source(SRC).verbatimSource()
                .pk("airplane_code"));

        s.add(Table.of("seats", "network", "Места в салоне модели").title("Места").rows("≈ 1,7 тыс.")
                .col("airplane_code", TEXT, "Модель самолёта").ref("airplanes").source(SRC).verbatimSource()
                .col("seat_no", TEXT, "Номер места").source(SRC).verbatimSource()
                .col("fare_conditions", TEXT, "Класс обслуживания").values(FARES).source(SRC).verbatimSource()
                .pk("airplane_code", "seat_no"));

        s.add(Table.of("routes", "network", "Маршруты: версия маршрута действует в периоде [valid_from, valid_to)")
                .title("Маршруты").rows("≈ 7 тыс.")
                .col("route_no", TEXT, "Номер маршрута (рейса)").source(SRC).verbatimSource()
                .col("valid_from", MOMENT, "Начало периода действия версии").source(SRC)
                .col("valid_to", MOMENT, "Конец периода действия (не включая)").source(SRC)
                .col("departure_airport", TEXT, "Аэропорт вылета").ref("airports").source(SRC).verbatimSource()
                .col("arrival_airport", TEXT, "Аэропорт прилёта").ref("airports").source(SRC).verbatimSource()
                .col("airplane_code", TEXT, "Модель самолёта").ref("airplanes").source(SRC).verbatimSource()
                .col("days_of_week", TEXT, "Дни недели вылета через запятую: 1 — пн … 7 — вс").source(SRC).verbatimSource()
                .col("scheduled_time", TEXT, "Местное время вылета, ЧЧ:ММ:СС").source(SRC).verbatimSource()
                .col("duration_min", INT, "Время в пути, минуты").source(SRC)
                .pk("route_no", "valid_from")
                .check("valid", "valid_from < valid_to"));

        s.add(Table.of("flights", "network", "Рейсы: вылеты по расписанию и фактически").title("Рейсы")
                .rows("≈ 136 тыс.")
                .col("flight_id", INT, "Рейс").source(SRC).verbatimSource()
                .col("route_no", TEXT, "Номер маршрута; версия — та, чей период содержит плановый вылет").source(SRC).verbatimSource()
                .col("status", TEXT, "Состояние рейса на «сейчас» выгрузки").values(STATUSES).source(SRC).verbatimSource()
                .col("scheduled_departure", MOMENT, "Вылет по расписанию").source(SRC)
                .col("scheduled_arrival", MOMENT, "Прилёт по расписанию").source(SRC)
                .col("actual_departure", MOMENT, "Фактический вылет; NULL — ещё не вылетел или отменён").nullable().source(SRC)
                .col("actual_arrival", MOMENT, "Фактический прилёт; NULL — ещё не прилетел или отменён").nullable().source(SRC)
                .pk("flight_id")
                // версия маршрута — по плановому вылету: ключ маршрута версионный (номер, начало действия), внешнего
                // ключа нет; связь по номеру — на картинке схемы
                .link(List.of("route_no"), "routes")
                .unique("route_no", "scheduled_departure")
                .index("scheduled_departure")
                .check("schedule", "scheduled_departure < scheduled_arrival"));

        s.add(Table.of("bookings", "sales", "Брони; отобраны целиком по модели сезонности (см. SOURCE.md)")
                .title("Бронирования").rows("≈ 8,5 млн")
                .col("book_ref", TEXT, "Номер брони").source(SRC).verbatimSource()
                .col("book_date", MOMENT, "Момент бронирования").source(SRC)
                .col("total_amount", MONEY, "Сумма брони — сумма цен её перелётов").source(SRC).verbatimSource()
                .pk("book_ref")
                .orderBy("book_date", "book_ref")
                .index("book_date"));

        s.add(Table.of("tickets", "sales", "Билеты: пассажир брони и направление").title("Билеты").rows("≈ 18 млн")
                .col("ticket_no", TEXT, "Номер билета").source(SRC).verbatimSource()
                .col("book_ref", TEXT, "Бронь").ref("bookings").source(SRC).verbatimSource()
                .col("passenger_id", TEXT, "Документ пассажира").source(SRC).verbatimSource()
                .col("passenger_name", TEXT, "Имя пассажира").source(SRC).verbatimSource()
                .col("outbound", BOOL, "Туда (t) или обратно (f)").source(SRC).verbatimSource()
                .pk("ticket_no"));

        s.add(Table.of("segments", "sales", "Перелёты билета: рейс, класс, цена").title("Перелёты").rows("≈ 24 млн")
                .col("ticket_no", TEXT, "Билет").ref("tickets").source(SRC).verbatimSource()
                .col("flight_id", INT, "Рейс").ref("flights").source(SRC).verbatimSource()
                .col("fare_conditions", TEXT, "Класс обслуживания").values(FARES).source(SRC).verbatimSource()
                .col("price", MONEY, "Цена перелёта").source(SRC).verbatimSource()
                .pk("ticket_no", "flight_id"));

        s.add(Table.of("boarding_passes", "sales", "Посадочные талоны").title("Посадочные талоны").rows("≈ 23 млн")
                .col("ticket_no", TEXT, "Билет").source(SRC).verbatimSource()
                .col("flight_id", INT, "Рейс").source(SRC).verbatimSource()
                .col("seat_no", TEXT, "Место").source(SRC).verbatimSource()
                .col("boarding_no", INT, "Номер посадки по порядку; NULL — посадки ещё не было").nullable().source(SRC).verbatimSource()
                .col("boarding_time", MOMENT, "Момент посадки; NULL — посадки ещё не было").nullable().source(SRC)
                .pk("ticket_no", "flight_id")
                .fk(List.of("ticket_no", "flight_id"), "segments")
                .unique("flight_id", "boarding_no")
                .unique("flight_id", "seat_no")
                .orderBy("flight_id", "boarding_no", "ticket_no")
                .nullsLast("boarding_no"));
        return s;
    }
}
