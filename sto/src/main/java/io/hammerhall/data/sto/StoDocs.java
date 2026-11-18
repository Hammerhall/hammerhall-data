package io.hammerhall.data.sto;

import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.SchemaDot;
import io.hammerhall.data.core.schema.SchemaExport;
import io.hammerhall.data.core.schema.Table;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SOURCE.md и LICENSE выпуска. Узоры и заложенные нарушения описаны качественно — без долей и чисел, чтобы не
 * подсказывать ответы задач (спецификация, §6).
 */
public final class StoDocs implements Release.Docs {

    @Override
    @SuppressWarnings("unchecked")
    public String sourceMd(Schema schema, Map<String, Object> m) {
        Map<String, Object> period = (Map<String, Object>) m.get("period");
        List<Map<String, Object>> files = (List<Map<String, Object>>) m.get("files");
        StringBuilder sb = new StringBuilder();
        sb.append("# Магазин «Кременъ» — набор данных `").append(m.get("version")).append("`\n\n");
        sb.append("Синтетические данные магазина электроники и товаров для дома «Кременъ» (англ. Kremen): свой магазин со\n")
                .append("складами (1P) и продавцы маркетплейса (3P), Россия, рубли. Всё сгенерировано генератором `sto-gen`\n")
                .append("(MIT): магазин, бренды, продавцы, поставщики, перевозчики, склады и люди **выдуманы**; совпадения с\n")
                .append("настоящими случайны. Города — российские города аэропортов `air-data-v1` (MIT © 2025 Postgres\n")
                .append("Professional): название, координаты, часовой пояс.\n\n");
        sb.append("Период — **").append(period.get("from")).append(" … ").append(period.get("to"))
                .append("**; «сейчас» выгрузки — **").append(String.valueOf(period.get("now")), 0, 10)
                .append("**: до него — факты, после — только планы (акции по расписанию, плановые цены).\n")
                .append("Магазин живёт по московскому времени: цены и акции меняются в 00:00 по Москве.\n\n");
        sb.append("Профиль — `").append(m.get("profile")).append("` (масштаб живого потока ").append(m.get("scale"))
                .append(" от `full`), seed — `").append(m.get("seed")).append("`, генератор — `").append(m.get("generator"))
                .append("`. Тот же seed и версия генератора дают то же содержимое (sha256 разжатых TSV — в манифесте).\n")
                .append("Каталог, склады и календарь одинаковы во всех профилях; клиентов, заказов и сессий в `small` и `medium` меньше.\n\n");

        sb.append("## Формат\n\nTSV, UTF-8, разделитель — табуляция, первая строка — заголовок, NULL — пустое поле, gzip без\n")
                .append("отметки времени. Моменты — секунды Unix (UTC), даты — `YYYY-MM-DD`, деньги — десятичная строка с двумя\n")
                .append("знаками (рубли), логические — `t` / `f`. Схема — `schema-tables.sql` (таблицы) и `schema-constraints.sql`\n")
                .append("(ключи, проверки, связи, индексы — после загрузки); ER-диаграмма — `schema.mmd` (Mermaid). Загрузка в\n")
                .append("Postgres: `psql -v ON_ERROR_STOP=1 -d БАЗА -f schema-tables.sql -f load-postgres.psql -f schema-constraints.sql`\n")
                .append("из папки выпуска (моменты переводятся `to_timestamp`).\n\n");

        sb.append("## Схема\n\n").append(SchemaDot.sourceMd(schema)).append('\n');

        sb.append("## Файлы\n\n| файл | строк | МБ |\n|---|---|---|\n");
        for (Map<String, Object> f : files) {
            long rows = (Long) f.get("rows"), bytes = (Long) f.get("bytes");
            sb.append("| `").append(f.get("name")).append("` | ").append(String.format(Locale.ROOT, "%,d", rows).replace(',', ' '))
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", bytes / 1e6).replace('.', ',')).append(" |\n");
        }
        StringBuilder missing = new StringBuilder();
        for (Table t : schema.tables()) {
            boolean has = false;
            for (Map<String, Object> f : files) if (t.name().equals(f.get("table"))) has = true;
            if (!has) missing.append(missing.isEmpty() ? "" : ", ").append('`').append(t.name()).append('`');
        }
        if (!missing.isEmpty()) {
            sb.append("\nВ этом прогоне не сгенерированы (модули в работе): ").append(missing).append(".\n");
        }

        sb.append("\n## Мир\n\n");
        sb.append("* **Каталог** — дерево категорий до 6 уровней с одним корнем; часть листьев делится по характеристике\n")
                .append("  (диагональ, объём, мощность…). Товар — вариант поколения модельной линейки бренда: линейки живут\n")
                .append("  годами, поколение сменяет поколение, старое продаётся ещё несколько месяцев. Аксессуары (чехлы,\n")
                .append("  стёкла, картриджи…) сделаны под конкретные модели (`compatible_model`). Комплекты — тоже товары,\n")
                .append("  комплект может входить в комплект (без циклов). EAN-13 — с префиксом страны бренда, у комплектов —\n")
                .append("  внутренний (префикс 2). Бренды выдуманы, у части — уровень «премиум» или «бюджет»; две — собственные\n")
                .append("  марки магазина.\n");
        sb.append("* **Цены** — с НДС, «на 90 / на 990»; история — периоды без разрывов и наложений с запуска до снятия.\n")
                .append("  Электроника со временем дешевеет, товары для дома дорожают; перед снятием — распродажа.\n")
                .append("  01.01.2026 НДС вырос с 20 до 22 % — у большей части товаров ступенька цены. С 01.12.2026 —\n")
                .append("  технологический сбор с электроники (предварительно: смартфон 250 ₽, ноутбук 500 ₽ за единицу) —\n")
                .append("  ступенька у охваченных видов.\n");
        sb.append("* **Акции** — к событиям календаря (11.11, «чёрная пятница», Новый год, 23 февраля, 8 марта, майские,\n")
                .append("  летняя и зимняя распродажи, «Снова в школу»), скидки недели, недели брендов, «вместе дешевле»,\n")
                .append("  промокоды; периоды пересекаются, часть акций суммируется.\n");
        sb.append("* **Склады** — федеральный хаб в Подмосковье → 8 распределительных центров (по федеральным округам) →\n")
                .append("  городские склады → пункты выдачи; сеть растёт — пункты открываются и закрываются. Зоны доставки —\n")
                .append("  многоугольники вокруг складов по перевозчику: ближе — быстрее.\n");
        sb.append(io.hammerhall.data.sto.customers.PopulationDocs.world());
        sb.append("* **Календарь** — праздники и переносы РФ (2025–2026 — по постановлениям, 2027 — прогноз по правилу\n")
                .append("  переноса), сезоны спроса (кондиционеры летом, обогреватели зимой, школа в августе, подарки к праздникам).\n\n");

        sb.append("## Узоры и особенности\n\n");
        sb.append("Заложены нарочно — для задач на окна во времени, аномалии, сегментацию; доли не публикуются:\n\n");
        sb.append("* «фальшивая скидка» — у части товаров цену поднимают за одну-три недели до акции и возвращают после;\n");
        sb.append("* у части товаров продавцов маркетплейса неверная контрольная цифра EAN-13;\n");
        sb.append("* у нескольких поставщиков надёжность заметно ниже прочих;\n");
        sb.append(io.hammerhall.data.sto.customers.PopulationDocs.patterns());
        sb.append("* поколения моделей вытесняют друг друга; комплекты бывают дороже суммы частей.\n\n");
        sb.append(io.hammerhall.data.sto.stock.StockDocs.section(files));
        sb.append(io.hammerhall.data.sto.orders.OrderDocs.section(files));

        sb.append("## Поля\n").append(SchemaExport.fieldsMarkdown(schema));
        sb.append("\n## Происхождение полей\n\n");
        sb.append("«генератор» — значение придумано генератором; `air-data-v1 …` — взято из справочника аэропортов выпуска\n")
                .append("`air-data-v1` (город — среднее координат его аэропортов; аэропорт Лешуконского, помеченный в источнике\n")
                .append("Архангельском, исключён; опечатки английских названий исправлены). Регион, федеральный округ и население\n")
                .append("городов — справочник генератора (население — оценка, округлено). Входы и их sha256 — в `manifest.json`.\n");
        sb.append(io.hammerhall.data.sto.customers.PopulationDocs.fieldNotes());
        return sb.toString();
    }

    @Override
    public String license() {
        return """
                MIT License

                Copyright (c) 2026 Hammerhall

                Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

                The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

                THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

                ---

                City names, coordinates and time zones in cities.tsv.gz are derived from airports.tsv.gz of the
                air-data-v1 release, which is distributed under the MIT License, Copyright (c) 2025 Postgres Professional.
                """;
    }
}
