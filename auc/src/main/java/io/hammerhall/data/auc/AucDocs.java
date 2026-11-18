package io.hammerhall.data.auc;

import io.hammerhall.data.auc.auction.AuctionDocs;
import io.hammerhall.data.auc.auction.intake.PrepDocs;
import io.hammerhall.data.auc.money.MoneyDocs;
import io.hammerhall.data.auc.site.SiteDocs;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.SchemaDot;
import io.hammerhall.data.core.schema.SchemaExport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Тексты выпуска: {@code SOURCE.md} и {@code LICENSE} (MIT). {@code SOURCE.md} собирается из разделов: общий (дом,
 * период, вход) — здесь; разделы модулей — константы {@code public static final String SOURCE_MD} (Markdown, начинается
 * с заголовка {@code ## …}: что в таблицах, узоры словами, как сделано) в классе {@code <пакет>.<Имя>Docs} пакета
 * модуля; их список — {@link #sections()} по порядку сборки. Скрытые числа модели и доли заложенных отклонений в
 * тексты не идут (чтобы не подсказывать ответы).
 */
public final class AucDocs implements Release.Docs {
    /** Разделы модулей по порядку сборки ({@code ...Docs.SOURCE_MD}). */
    static List<String> sections() {
        List<String> out = new ArrayList<>();
        out.add(HOUSE_MD);
        out.add(CATALOG_MD);
        out.add(CLIENTS_MD);
        out.add(PrepDocs.SOURCE_MD);
        out.add(AuctionDocs.SOURCE_MD);
        out.add("## " + MoneyDocs.TITLE + "\n\n" + MoneyDocs.SECTION);
        out.add(SiteDocs.SOURCE_MD);
        return out;
    }

    static final String HOUSE_MD = """
            ## Дом и правила

            Аукционный дом искусства и коллекционных предметов: шесть отделов (`departments`) — живопись и графика; \
            ювелирные украшения и часы; монеты и медали; книги, рукописи и карты; декоративно-прикладное искусство; \
            коллекционные предметы — и дерево категорий (`categories`, до трёх уровней; у вещи категория — всегда лист). \
            Восемь офисов (`offices`): залы торгов в Москве и Санкт-Петербурге, приёмные в шести городах, две из них открыты \
            внутри периода (`opened_on`). Города (`cities`) — из `sto-data-v1` как есть, с теми же `id`. Штат (`staff`) — \
            специалисты отделов, руководители отделов, аукционисты, сотрудники на телефонной линии, работа с клиентами, \
            бухгалтерия, администраторы; внутри периода дом только нанимает (новые приёмные, рост отделов).

            Правила торгов и денег — таблицами, одни для генератора, самопроверки и задач: шаг ставки по цене \
            (`increment_bands`, одна версия на период); шкалы и сборы (`fee_schedules`) — премия покупателя по ступеням \
            (`fee_tiers`, ставка ступени — на свою часть молотка) со сменой шкалы 01.09.2026 по дате торгов и своей единой \
            ставкой у монет и медалей, комиссия продавца по ступеням (у торговцев — со скидкой на каждой ступени), сбор \
            партнёрской площадки, страхование, каталог, снятие лота, пени, депозит на дорогой лот. НДС — сверху на услуги \
            дома, по дате торгов: 20 % по 31.12.2025, 22 % с 01.01.2026; на молоток НДС нет. Время бизнеса — московское.
            """;

    static final String CATALOG_MD = """
            ## Мастера и вещи

            Мастера (`makers`) — художники, скульпторы, медальеры, ювелиры, часовщики, монетные дворы, издательства, \
            типографии, картографические заведения, мануфактуры и мастерские; все выдуманные. Справочник постоянный: \
            одинаков во всех профилях и при продлении периода. Уровень рынка (`tier`, A–D) назначает генератор: A — \
            немногие имена, ради которых приходят на торги.

            Вещь (`items`) — факт приёма: кто принёс, в какой офис, какой специалист смотрел, когда, принята ли (у части \
            вещей — отказ с причиной), описание, состояние по шкале раздела, датировка, материал, размеры, проба. Строка \
            вещи при продлении периода не меняется: где вещь сейчас и что с ней было — журнал `item_status_log` и её лоты. \
            Вещи приносят визитами — клиент сдаёт в офис одну или несколько вещей, из принятых вещей визита составляется \
            один договор комиссии; торговцы приносят чаще и больше, наследства — большими собраниями. Приём идёт по будням \
            (в субботу — меньше), летом тише, в январские праздники офисы закрыты; темп приёма — под календарь торгов \
            отделов, поэтому часть принятых вещей на срез ещё ждёт своих торгов. Происхождение (`provenance`) — звенья \
            цепочки, литература, выставки; у живописи оно есть почти всегда, у коллекционных предметов — редко.

            У каждой вещи есть скрытая «стоимость рынка» — от уровня мастера, атрибуции (работа мастера дороже, чем «круг» \
            или «в манере»), состояния, категории и случая; в выпуск она не идёт, по ней специалист ставит эстимейт, а \
            участники — свои цены.
            """;

    static final String CLIENTS_MD = """
            ## Клиенты

            Клиенты (`clients`) — люди и организации (галереи, антикварные салоны, музеи, библиотеки); один клиент и \
            покупает, и сдаёт вещи на комиссию. База растёт с 2012 года по постоянной кривой (осень — активнее, лето — \
            тише), `id` — по моменту регистрации. Торговцы (`is_dealer`) — немногие клиенты с дилерским договором: \
            сдают и покупают много, выигрывают заметную долю лотов. Контакты — только адреса на `example.com`, \
            `example.org`, `example.net`; телефонов, адресов и документов нет. Проверка документа и карты (`verified_at`) \
            — у большинства в первые часы после регистрации; расширенная проверка — при крупном счёте.

            Скрытое поведение (в выпуск не идёт): интересы по отделам, частота участия в торгах (у большинства — пара \
            торгов в год, у торговцев — десятки), бюджет, привычки — ставить заранее максимумом, в последние секунды, \
            по телефону, из зала, — надёжность и темп оплаты, «спящие» клиенты, которые давно не покупают. Возраст и \
            город — в таблице.

            Связи учётных записей видны только по данным: у подставного участника общее устройство (`sessions.device_key`) \
            с комитентом, на чьи лоты он ставит; торговцы одного отдела в сговоре не встречаются на одних лотах; \
            неплательщик заводит новую учётную запись с того же устройства. Блокировка (`blocked_at`, `block_reason`) — \
            за неуплату, по подозрению в подставных ставках или по просьбе клиента — состояние на «сейчас».
            """;

    @Override
    public String sourceMd(Schema schema, Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(AucDataset.HOUSE_NAME).append(" (").append(AucDataset.HOUSE_NAME_EN).append(") — набор данных `")
                .append(m.get("version")).append("`\n\n");
        sb.append("Синтетические данные аукционного дома: торги, ставки, счета и расчёты с комитентами — журнал жизни дома ")
                .append("за два года. Всё сгенерировано генератором `").append(m.get("generator")).append("` (MIT) от `seed` ")
                .append(m.get("seed")).append(", профиль `").append(m.get("profile")).append("`. Все имена выдуманные; ")
                .append("настоящие — только география и годы.\n\n");
        sb.append("## Период и «сейчас»\n\n")
                .append("Период — с ").append(period(m, "from")).append(" по ").append(period(m, "to")).append(", «сейчас» — ")
                .append(period(m, "now")).append(". В выпуске — факты раньше среза и назначенное после него (торги с каталогом, ")
                .append("регистрации и поручения на будущие лоты); поля «на „сейчас“» — состояние на срез. Моменты — секунды ")
                .append("Unix (UTC), у ставок и событий лота — ещё миллисекунда отдельным полем.\n\n");
        sb.append("## Вход\n\n")
                .append("Города — таблица `cities` выпуска `sto-data-v1` (MIT) как есть, с теми же `id`: набор соединяется с ")
                .append("набором магазина по городу. Сам `sto-data-v1` берёт города из `air-data-v1` (MIT, Copyright (c) 2025 ")
                .append("Postgres Professional).\n\n");
        sb.append("## Схема\n\n").append(SchemaDot.sourceMd(schema)).append('\n');
        for (String s : sections()) sb.append(s.strip()).append("\n\n");
        sb.append("## Поля\n").append(SchemaExport.fieldsMarkdown(schema));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object period(Map<String, Object> m, String k) {
        Object p = m.get("period");
        return p instanceof Map<?, ?> map ? ((Map<String, Object>) map).get(k) : "?";
    }

    @Override
    public String license() {
        return """
                MIT License

                Copyright (c) 2026 Hammerhall

                Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

                The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

                THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
                """;
    }
}
