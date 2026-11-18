package io.hammerhall.data.air;

import io.hammerhall.data.air.dump.PgValues;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.SchemaDot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SOURCE.md и LICENSE выпуска — SOURCE.md тот же, что у прежней сборки на Python (из манифеста), с двумя
 * поправками: про время — как на самом деле ({@link PgValues#ROUNDING}); про аэропорты — без списка; плюс абзац о
 * профиле и о схеме (картинка схемы — {@link SchemaDot#sourceMd}). Числа форматируются как у Python
 * ({@code {:.1f}}, {@code round(…, 1)} — от точного значения {@code double}, половина — к чётному).
 */
public final class AirDocs implements Release.Docs {
    static final String[] MONTHS = {"янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"};
    static final Map<String, String> REGION_NAMES = new LinkedHashMap<>();
    static final String[] WEEKDAYS = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};

    static {
        String[][] r = {{"EU", "Европа"}, {"NA", "США и Канада"}, {"CN", "Китай"}, {"JP", "Япония"},
                {"IN", "Индия и Непал"}, {"RU", "Россия"}, {"SH", "Чили и Новая Зеландия"},
                {"United States", "США"}, {"Canada", "Канада"}, {"Chile", "Чили"}};
        for (String[] p : r) REGION_NAMES.put(p[0], p[1]);
    }

    private static String sp(long x) {
        return String.format(Locale.ROOT, "%,d", x).replace(',', ' ');
    }

    /** {@code f"{bytes / 1e6:.1f}"} с запятой. */
    private static String mb(long bytes) {
        return new BigDecimal(bytes / 1e6).setScale(1, RoundingMode.HALF_EVEN).toPlainString().replace('.', ',');
    }

    private static String two(Object v) {
        return new BigDecimal(v.toString()).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String sourceMd(Schema schema, Map<String, Object> m) {
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) m.get("files")) files.put((String) f.get("table"), f);
        Map<String, Object> sel = (Map<String, Object>) m.get("selection");
        String now = String.valueOf(m.get("now"));
        String last = String.valueOf(m.get("last_booking"));
        long total = m.get("bookings_total") == null ? 0 : ((Number) m.get("bookings_total")).longValue();
        long kept = m.get("bookings_kept") == null ? 0 : ((Number) m.get("bookings_kept")).longValue();
        String pct = total == 0 ? "0,0" : new BigDecimal((double) kept / total * 100).setScale(1, RoundingMode.HALF_EVEN)
                .toPlainString().replace('.', ',');
        boolean rounded = PgValues.ROUNDING == PgValues.Rounding.NEAREST_EVEN;

        StringBuilder sb = new StringBuilder();
        sb.append("# Авиаперевозки — набор данных `").append(m.get("version")).append("`\n\n");
        sb.append("""
                Данные домена «авиа» Stream Forge (Hammerhall). Источник — демонстрационная база Postgres Pro
                «Авиаперевозки» 2.0, выгрузка `demo-20250901-2y` (2 года, 730 дней), лицензия MIT,
                © 2025 Postgres Professional — текст в `LICENSE`. Данные сгенерированы: люди, документы
                и брони вымышлены.

                """);
        sb.append("«Сейчас» в данных — **").append(now, 0, Math.min(10, now.length()))
                .append("**: рейсы до этой даты прилетели (или отменены), после — по\nрасписанию; продажи — по ")
                .append(last, 0, Math.min(10, last.length())).append(".\n\n");
        sb.append("## Что изменено против выгрузки\n\nЗначения не менялись. Сделано четыре вещи:\n\n");
        sb.append(rounded
                ? "1. **Время** — секунды Unix, UTC; доли секунды округлены до ближайшей секунды (ровно половина — к чётной).\n"
                : "1. **Время** — секунды Unix, UTC; доли секунды отброшены.\n");
        sb.append("2. **Брони отобраны целиком** — со всеми билетами, сегментами и посадочными талонами: из\n   ")
                .append(sp(total)).append(" оставлено ").append(sp(kept)).append(" (").append(pct).append(" %).\n");
        sb.append("""
                   Отбор задаёт сезонность, которой у генератора нет: доля оставленных броней зависит от страны,
                   местной даты и дня недели **первого вылета брони**. В праздничные окна бронь остаётся всегда,
                   в остальные дни — с вероятностью «месяц × день недели». Решает не случай, а номер брони: первые
                   4 байта MD5(номер брони) как целое без знака, little-endian, делённые на 2^32, меньше доли —
                   бронь остаётся. Сборка повторяема.
                3. **Справочник аэропортов** — без нескольких аэропортов источника; в сеть маршрутов они не входят,
                   рейсы и брони это не задевает.
                4. **Строки упорядочены**, файлы сжаты gzip без отметки времени.

                Доля по месяцу — по региону страны вылета:

                """);
        sb.append("| регион | ").append(String.join(" | ", MONTHS)).append(" |\n|---|").append("---|".repeat(12)).append('\n');
        for (Map.Entry<String, Object> e : ((Map<String, Object>) sel.get("month")).entrySet()) {
            sb.append("| ").append(REGION_NAMES.get(e.getKey())).append(" |");
            for (Object v : (List<Object>) e.getValue()) sb.append(' ').append(two(v)).append(" |");
            sb.append('\n');
        }
        sb.append("\nДоля по дню недели местного вылета: ");
        int i = 0;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) sel.get("weekday")).entrySet()) {
            if (i++ > 0) sb.append(", ");
            sb.append(WEEKDAYS[Integer.parseInt(e.getKey()) - 1]).append(' ').append(two(e.getValue()));
        }
        sb.append(".\n\nПраздничные окна (местная дата вылета, включительно):\n\n| кто | с | по | что |\n|---|---|---|---|\n");
        for (Map<String, Object> h : (List<Map<String, Object>>) sel.get("holidays")) {
            String who = (String) h.get("who");
            sb.append("| ").append(REGION_NAMES.getOrDefault(who, who)).append(" | ").append(h.get("from")).append(" | ")
                    .append(h.get("to")).append(" | ").append(h.get("name")).append(" |\n");
        }
        sb.append("""

                Модель сверена с открытыми данными: загрузка рейсов в реальности — 74–89 % (провал — февраль, пик —
                июнь–июль), Европа сезонна сильнее всех, Азия ровнее всех, по дням недели: воскресенье > пятница >
                понедельник > четверг > суббота > среда > вторник.

                Что вышло (загрузка рейсов — проданные места к местам в самолёте, по прилетевшим рейсам):
                в среднем ≈ 73 % (в выгрузке без отбора — ≈ 86 %); провал к пику по регионам — в 1,2–1,4 раза;
                Чуньюнь 2026 у вылетов из Китая — 87 % против 74 % в ноябре; неделя Дня благодарения 2026
                у вылетов из США — 80 % против 73 % за неделю до неё; вторник и среда — 71 % против 76 %
                в воскресенье. Пассажиры обратных рейсов и пересадок летят из других стран, поэтому сезон
                одного региона виден и в чужих аэропортах — как в жизни.
                """);
        if (m.get("profile_sample") instanceof Map<?, ?> p) {
            sb.append("\n## Профиль `").append(m.get("profile")).append("`\n\n")
                    .append("Подмножество броней `full`: из оставленных моделью взята доля ").append(p.get("scale"))
                    .append(" — по отдельному хешу номера брони\n(`").append(p.get("key")).append("`, seed `")
                    .append(m.get("seed")).append("`), ").append(sp(((Number) p.get("bookings")).longValue()))
                    .append(" броней. Билеты, сегменты и посадочные талоны — со своими бронями;\n")
                    .append("аэропорты, самолёты, места, маршруты и рейсы — целиком, как в `full`.\n");
        }
        sb.append("""

                ## Файлы

                TSV, UTF-8, разделитель — табуляция, первая строка — заголовок, NULL — пустое поле, gzip.

                | файл | строк | МБ | поля |
                |---|---|---|---|
                """);
        String[][] rows = {
                {"airports", "`airport_code`, `name_en`, `name_ru`, `city_en`, `city_ru`, `country_en`, `country_ru`, `longitude`, `latitude`, `timezone` — аэропорты мира; в маршрутной сети — 73"},
                {"airplanes", "`airplane_code`, `model_en`, `model_ru`, `range_km`, `speed_kmh`"},
                {"seats", "`airplane_code`, `seat_no`, `fare_conditions` (Economy, Comfort, Business)"},
                {"routes", "`route_no`, `valid_from`, `valid_to` — период [с, по), `departure_airport`, `arrival_airport`, `airplane_code`, `days_of_week` (1 — пн … 7 — вс, через запятую), `scheduled_time` — местное время вылета, `duration_min`"},
                {"flights", "`flight_id`, `route_no`, `status`, `scheduled_departure`, `scheduled_arrival`, `actual_departure`, `actual_arrival`"},
                {"bookings", "`book_ref`, `book_date`, `total_amount`"},
                {"tickets", "`ticket_no`, `book_ref`, `passenger_id`, `passenger_name`, `outbound` (t / f)"},
                {"segments", "`ticket_no`, `flight_id`, `fare_conditions`, `price`"},
                {"boarding_passes", "`ticket_no`, `flight_id`, `seat_no`, `boarding_no`, `boarding_time`"}};
        for (String[] r : rows) {
            Map<String, Object> f = files.get(r[0]);
            if (f == null) continue;
            sb.append("| `").append(f.get("name")).append("` | ").append(sp(((Number) f.get("rows")).longValue()))
                    .append(" | ").append(mb(((Number) f.get("bytes")).longValue())).append(" | ").append(r[1]).append(" |\n");
        }
        sb.append("""

                Версия маршрута для рейса — та, чей период действия содержит плановый вылет рейса. Сумма брони
                равна сумме цен её сегментов. Контрольные суммы — в `manifest.json` (`sha256` — сжатого файла,
                `sha256_tsv` — содержимого).

                ## Схема

                """);
        sb.append(SchemaDot.sourceMd(schema));
        sb.append("""

                Таблицы — `schema-tables.sql`, ключи, связи, проверки и индексы — `schema-constraints.sql` (после
                загрузки), ER-диаграмма — `schema.mmd` (Mermaid).

                ## Загрузка в Postgres

                Из папки выпуска:
                `psql -v ON_ERROR_STOP=1 -d БАЗА -f schema-tables.sql -f load-postgres.psql -f schema-constraints.sql`
                (моменты переводятся `to_timestamp`).
                """);
        return sb.toString();
    }

    @Override
    public String license() {
        return """
                Copyright (c) 2025 Postgres Professional

                Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

                The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

                THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

                """;
    }
}
