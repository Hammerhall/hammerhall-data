package io.hammerhall.data.sto;

import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;

import static io.hammerhall.data.core.schema.ColType.BIGINT;
import static io.hammerhall.data.core.schema.ColType.BOOL;
import static io.hammerhall.data.core.schema.ColType.DATE;
import static io.hammerhall.data.core.schema.ColType.DECIMAL;
import static io.hammerhall.data.core.schema.ColType.DOUBLE;
import static io.hammerhall.data.core.schema.ColType.INT;
import static io.hammerhall.data.core.schema.ColType.MOMENT;
import static io.hammerhall.data.core.schema.ColType.MONEY;
import static io.hammerhall.data.core.schema.ColType.SMALLINT;
import static io.hammerhall.data.core.schema.ColType.TEXT;

/**
 * Схема {@code sto-data-v1} — единственное описание таблиц магазина «Кременъ». Из него — TSV (порядок и типы полей),
 * DDL, ER-диаграмма и поля SOURCE.md. Это контракт домена: модуль пишет таблицу ровно по этому описанию, поле
 * добавляется здесь (и только добавляется — после публикации ничего не переименовываем).
 *
 * <p>Перечисления — латинские коды в snake_case (значения — в описании поля и CHECK). Идентификаторы — целые с 1,
 * выдаются по времени появления сущности. Моменты — секунды Unix UTC, даты — ISO.
 */
public final class StoSchema {
    private StoSchema() {
    }

    public static final String VERSION = "sto-data-v1";

    public static final String[] EVENTS = {"eleven_eleven", "black_friday", "new_year", "winter_sale", "feb23", "mar8",
            "may_holidays", "summer_sale", "back_to_school"};
    public static final String[] ORDER_STATUSES = {"created", "paid", "assembling", "assembled", "shipped",
            "ready_for_pickup", "delivered", "cancelled", "returned"};

    public static Schema build() {
        Schema s = new Schema("sto", VERSION)
                .group("catalog", "Каталог")
                // справочник: на города, склады и перевозчиков ссылаются почти все группы — на карте схемы эти связи
                // не рисуются (они — на картинках групп)
                .group("geo", "География и склады", true)
                .group("supply", "Поставки и складские движения")
                .group("market", "Маркетплейс")
                .group("customers", "Клиенты")
                .group("orders", "Заказы")
                .group("site", "Сайт и приложение");

        // ============================================================ каталог
        s.add(Table.of("categories", "catalog", "Категории — дерево с одним корнем, до 6 уровней").title("Категории")
                .rows("≈ 600")
                .col("id", INT, "Категория")
                .col("parent_id", INT, "Родительская категория; NULL — корень").nullable().ref("categories")
                .col("level", SMALLINT, "Уровень: 1 — корень, 6 — самый глубокий")
                .col("name_ru", TEXT, "Название по-русски")
                .col("name_en", TEXT, "Название по-английски")
                .pk("id").check("level", "level BETWEEN 1 AND 6"));

        s.add(Table.of("spec_keys", "catalog", "Справочник характеристик товаров (ключи EAV)").title("Характеристики")
                .rows("≈ 130")
                .col("key", TEXT, "Код характеристики")
                .col("name_ru", TEXT, "Название по-русски")
                .col("name_en", TEXT, "Название по-английски")
                .col("unit", TEXT, "Единица (латиницей: in, GB, W, mAh…); NULL — без единицы").nullable()
                .col("value_type", TEXT, "Тип значения").values("num", "text")
                .pk("key"));

        s.add(Table.of("brands", "catalog", "Бренды — все выдуманные").title("Бренды").rows("≈ 150")
                .col("id", INT, "Бренд")
                .col("name", TEXT, "Название (латиницей)")
                .col("origin_country", TEXT, "Страна бренда, код ISO 3166-1 alpha-2")
                .col("tier", TEXT, "Ценовой уровень").values("budget", "mid", "premium")
                .col("is_private_label", BOOL, "Собственная марка магазина")
                .pk("id").unique("name"));

        s.add(Table.of("products", "catalog", "Товары: модели, их поколения и варианты; комплекты — тоже товары")
                .title("Товары").rows("≈ 50 тыс.")
                .col("id", INT, "Товар")
                .col("sku", TEXT, "Артикул магазина")
                .col("ean13", TEXT, "Штрихкод EAN-13 (у комплектов — внутренний, префикс 2)")
                .col("brand_id", INT, "Бренд").ref("brands")
                .col("category_id", INT, "Категория — лист дерева").ref("categories")
                .col("name_ru", TEXT, "Название по-русски")
                .col("name_en", TEXT, "Название по-английски")
                .col("model_line", TEXT, "Модельная линейка")
                .col("generation", SMALLINT, "Поколение модели в линейке: 1, 2, …")
                .col("launched_on", DATE, "Начало продаж")
                .col("discontinued_on", DATE, "Снят с продажи; NULL — продаётся до конца периода").nullable()
                .col("weight_g", INT, "Вес в упаковке, г")
                .col("length_mm", INT, "Длина упаковки, мм")
                .col("width_mm", INT, "Ширина упаковки, мм")
                .col("height_mm", INT, "Высота упаковки, мм")
                .col("warranty_months", SMALLINT, "Гарантия, месяцев")
                .col("is_bundle", BOOL, "Комплект (состав — в bundle_items)")
                .pk("id").unique("sku").unique("ean13").index("launched_on")
                .check("dates", "discontinued_on IS NULL OR discontinued_on > launched_on")
                .check("ean13_format", "ean13 ~ '^[0-9]{13}$'"));

        s.add(Table.of("product_specs", "catalog", "Характеристики товаров (EAV): одно значение — текст или число")
                .title("Значения характеристик").rows("≈ 500 тыс.")
                .col("product_id", INT, "Товар").ref("products")
                .col("key", TEXT, "Характеристика").ref("spec_keys")
                .col("value_text", TEXT, "Текстовое значение").nullable()
                .col("value_num", DECIMAL, "Числовое значение").nullable().scale(14, 3)
                .col("unit", TEXT, "Единица числового значения (как в spec_keys)").nullable()
                .pk("product_id", "key").index("key")
                .check("one_value", "(value_text IS NULL) <> (value_num IS NULL)"));

        s.add(Table.of("bundle_items", "catalog", "Состав комплектов; комплект может входить в комплект (DAG без циклов)")
                .title("Состав комплектов").rows("≈ 8 тыс.")
                .col("bundle_id", INT, "Комплект").ref("products")
                .col("component_id", INT, "Составная часть — товар или комплект").ref("products")
                .col("qty", SMALLINT, "Штук в комплекте")
                .pk("bundle_id", "component_id")
                .check("qty", "qty > 0").check("not_self", "bundle_id <> component_id"));

        s.add(Table.of("prices", "catalog",
                        "История розничных цен магазина: периоды [valid_from, valid_to) без разрывов и наложений")
                .title("Цены").rows("≈ 1,5 млн")
                .col("product_id", INT, "Товар").ref("products")
                .col("valid_from", MOMENT, "Цена действует с (включительно)")
                .col("valid_to", MOMENT, "Цена действует по (исключая); NULL — без конца в периоде").nullable()
                .col("price", MONEY, "Цена с НДС, руб.")
                .pk("product_id", "valid_from").index("valid_from")
                .check("period", "valid_to IS NULL OR valid_to > valid_from").check("price", "price > 0"));

        s.add(Table.of("promotions", "catalog", "Акции: скидки, промокоды, «n+m», комплекты; периоды пересекаются")
                .title("Акции").rows("≈ 3 тыс.")
                .col("id", INT, "Акция")
                .col("kind", TEXT, "Механика").values("percent", "amount", "n_plus_m", "bundle", "promocode")
                .col("name_ru", TEXT, "Название по-русски")
                .col("name_en", TEXT, "Название по-английски")
                .col("event", TEXT, "Торговое событие календаря; NULL — обычная акция").nullable().values(EVENTS)
                .col("discount_pct", DECIMAL, "Скидка, % (percent, bundle, promocode в процентах)").nullable().scale(5, 2)
                .col("discount_amount", MONEY, "Скидка, руб. (amount — с единицы товара; promocode без целей — с заказа)").nullable()
                .col("buy_qty", SMALLINT, "n+m: сколько купить").nullable()
                .col("free_qty", SMALLINT, "n+m: сколько из них бесплатно (самые дешёвые)").nullable()
                .col("promo_code", TEXT, "Промокод (только promocode)").nullable()
                .col("min_order_amount", MONEY, "Минимальная сумма заказа").nullable()
                .col("valid_from", MOMENT, "Начало (включительно)")
                .col("valid_to", MOMENT, "Конец (исключая)")
                .col("stackable", BOOL, "Суммируется с другими суммируемыми акциями")
                .pk("id").unique("promo_code").index("valid_from")
                .check("period", "valid_to > valid_from")
                .check("one_discount", "kind = 'n_plus_m' OR (discount_pct IS NULL) <> (discount_amount IS NULL)"));

        s.add(Table.of("promotion_targets", "catalog",
                        "На что действует акция: товар или категория (с поддеревом); у акции без строк — весь заказ")
                .title("Цели акций").rows("≈ 200 тыс.")
                .col("promo_id", INT, "Акция").ref("promotions")
                .col("seq", INT, "Номер цели в акции: 1, 2, …")
                .col("product_id", INT, "Товар").nullable().ref("products")
                .col("category_id", INT, "Категория (действует на всё поддерево)").nullable().ref("categories")
                .pk("promo_id", "seq")
                .check("one_target", "(product_id IS NULL) <> (category_id IS NULL)"));

        // ============================================================ география и склады
        s.add(Table.of("cities", "geo", "Города — российские города аэропортов air-data-v1").title("Города").rows("149")
                .col("id", INT, "Город")
                .col("name_ru", TEXT, "Название по-русски").source("air-data-v1 airports.city_ru")
                .col("name_en", TEXT, "Название по-английски (опечатки источника исправлены)").source("air-data-v1 airports.city_en")
                .col("region_ru", TEXT, "Субъект РФ по-русски")
                .col("region_en", TEXT, "Субъект РФ по-английски")
                .col("federal_district", TEXT, "Федеральный округ").values("central", "northwest", "south",
                        "north_caucasus", "volga", "ural", "siberia", "far_east")
                .col("lat", DOUBLE, "Широта центра (среднее аэропортов города)").source("air-data-v1 airports.latitude")
                .col("lon", DOUBLE, "Долгота центра (среднее аэропортов города)").source("air-data-v1 airports.longitude")
                .col("timezone", TEXT, "Часовой пояс IANA").source("air-data-v1 airports.timezone")
                .col("population", INT, "Население, человек — оценка, округлено")
                .col("airport_code", TEXT, "Главный аэропорт города — ключ к air-data-v1 airports").source("air-data-v1 airports.airport_code")
                .pk("id").unique("name_en"));

        s.add(Table.of("warehouses", "geo",
                        "Склады и пункты выдачи — дерево: хаб → РЦ → городские склады → пункты выдачи")
                .title("Склады и пункты выдачи").rows("≈ 3 тыс.")
                .col("id", INT, "Склад или пункт выдачи")
                .col("parent_id", INT, "Кто снабжает; NULL — хаб").nullable().ref("warehouses")
                .col("kind", TEXT, "Вид").values("hub", "dc", "warehouse", "pickup_point")
                .col("code", TEXT, "Код")
                .col("name_ru", TEXT, "Название по-русски")
                .col("name_en", TEXT, "Название по-английски")
                .col("city_id", INT, "Город").ref("cities")
                .col("lat", DOUBLE, "Широта")
                .col("lon", DOUBLE, "Долгота")
                .col("opened_on", DATE, "Открыт")
                .col("closed_on", DATE, "Закрыт; NULL — работает").nullable()
                .pk("id").unique("code")
                .check("dates", "closed_on IS NULL OR closed_on > opened_on"));

        s.add(Table.of("storage_cells", "geo", "Ячейки хранения хаба, РЦ и городских складов").title("Ячейки хранения")
                .rows("≈ 290 тыс.")
                .col("id", INT, "Ячейка")
                .col("warehouse_id", INT, "Склад").ref("warehouses")
                .col("zone", TEXT, "Зона хранения").values("pallet", "shelf", "mezzanine", "oversize", "returns")
                .col("code", TEXT, "Адрес ячейки: зона-стеллаж-полка-ячейка")
                .col("rack", SMALLINT, "Стеллаж")
                .col("shelf", SMALLINT, "Полка (ярус), 1 — нижняя")
                .col("cell", SMALLINT, "Ячейка на полке")
                .col("max_weight_kg", INT, "Предельный вес, кг")
                .col("max_volume_l", INT, "Предельный объём, л")
                .pk("id").unique("warehouse_id", "code"));

        s.add(Table.of("carriers", "geo", "Перевозчики — все выдуманные").title("Перевозчики").rows("8")
                .col("id", INT, "Перевозчик")
                .col("name_ru", TEXT, "Название по-русски")
                .col("name_en", TEXT, "Название по-английски")
                .col("kind", TEXT, "Вид").values("own_courier", "courier", "express", "pickup_network", "post",
                        "freight", "air")
                .pk("id"));

        s.add(Table.of("delivery_zones", "geo",
                        "Зоны доставки: многоугольники вокруг склада по перевозчику; зоны одного склада и перевозчика не перекрываются")
                .title("Зоны доставки").rows("≈ 1,5 тыс.")
                .col("id", INT, "Зона")
                .col("warehouse_id", INT, "Склад отгрузки").ref("warehouses")
                .col("carrier_id", INT, "Перевозчик").ref("carriers")
                .col("ring", SMALLINT, "Пояс: 0 — ближний, дальше — дальше")
                .col("polygon", TEXT, "Многоугольник WKT POLYGON((lon lat, …)), против часовой стрелки")
                .col("base_days", SMALLINT, "Базовый срок доставки, дней")
                .pk("id").index("warehouse_id", "carrier_id"));

        // ============================================================ поставки
        s.add(Table.of("suppliers", "supply", "Поставщики магазина (1P) — все выдуманные").title("Поставщики")
                .rows("≈ 300")
                .col("id", INT, "Поставщик")
                .col("name_ru", TEXT, "Название по-русски")
                .col("name_en", TEXT, "Название по-английски")
                .col("kind", TEXT, "Вид").values("brand_office", "distributor", "importer", "manufacturer")
                .col("city_id", INT, "Город").ref("cities")
                .col("lead_time_days", SMALLINT, "Обычный срок поставки, дней")
                .col("reliability", DECIMAL, "Доля поставок в срок, 0–1").scale(4, 3)
                .pk("id").unique("name_ru"));

        s.add(Table.of("purchase_orders", "supply", "Заказы поставщикам").title("Заказы поставщикам").rows("≈ 60 тыс.")
                .col("id", INT, "Заказ поставщику")
                .col("supplier_id", INT, "Поставщик").ref("suppliers")
                .col("warehouse_id", INT, "Склад приёмки (хаб или РЦ)").ref("warehouses")
                .col("ordered_at", MOMENT, "Размещён")
                .col("expected_at", MOMENT, "Ожидается")
                .col("received_at", MOMENT, "Принят; NULL — не принят").nullable()
                .col("status", TEXT, "Статус").values("placed", "confirmed", "in_transit", "received",
                        "partially_received", "cancelled")
                .col("purpose", TEXT, "Назначение: входящий остаток на начало периода (на любой склад, даты = начало), "
                        + "первая поставка товара на склад (новинки), пополнение").values("opening", "launch", "replenishment")
                .pk("id").index("ordered_at"));

        s.add(Table.of("batches", "supply",
                        "Партии товара на складах: закупка магазина (po_id) или поставка продавца FBO (seller_id)")
                .title("Партии").rows("≈ 400 тыс.")
                .col("id", INT, "Партия")
                .col("po_id", INT, "Заказ поставщику; NULL — партия продавца").nullable().ref("purchase_orders")
                .col("seller_id", INT, "Продавец FBO; NULL — партия магазина").nullable().ref("sellers")
                .col("product_id", INT, "Товар").ref("products")
                .col("warehouse_id", INT, "Склад приёмки").ref("warehouses")
                .col("qty", INT, "Штук")
                .col("unit_cost", MONEY, "Закупочная цена за штуку без НДС, руб.")
                .col("received_at", MOMENT, "Принята; NULL — в пути").nullable()
                .col("ordered_qty", INT, "Заказано штук (qty — принято; у партии в пути qty = ordered_qty)")
                .pk("id")
                .check("owner", "(po_id IS NULL) <> (seller_id IS NULL)").check("qty", "qty > 0")
                .check("ordered_qty", "ordered_qty >= qty"));

        s.add(Table.of("stock_movements", "supply", "Журнал складских движений: количество со знаком")
                .title("Складские движения").rows("≈ 15 млн")
                .col("id", BIGINT, "Движение (по времени)")
                .col("at", MOMENT, "Момент")
                .col("warehouse_id", INT, "Склад или пункт выдачи").ref("warehouses")
                .col("cell_id", INT, "Ячейка; NULL — без адреса (пункт выдачи, зона приёмки)").nullable().ref("storage_cells")
                .col("product_id", INT, "Товар").ref("products")
                .col("batch_id", INT, "Партия; NULL — не партионный учёт (FBS)").nullable().ref("batches")
                .col("kind", TEXT, "Вид движения").values("receipt", "move", "transfer_out", "transfer_in", "pick",
                        "ship", "customer_return", "writeoff", "inventory")
                .col("qty", INT, "Количество: + приход, − расход")
                .col("ref_type", TEXT, "К чему относится").nullable().values("purchase_order", "order", "shipment",
                        "return", "transfer", "inventory")
                .col("ref_id", BIGINT, "Id документа по ref_type").nullable()
                .pk("id").index("product_id", "at").index("warehouse_id", "at"));

        // ============================================================ маркетплейс
        s.add(Table.of("sellers", "market", "Продавцы маркетплейса (3P) — все выдуманные").title("Продавцы")
                .rows("≈ 3 тыс.")
                .col("id", INT, "Продавец")
                .col("name", TEXT, "Название магазина продавца")
                .col("city_id", INT, "Город").ref("cities")
                .col("model", TEXT, "Модель: товар на нашем складе (fbo) или отгружает сам (fbs)").values("fbo", "fbs")
                .col("joined_at", MOMENT, "Подключился")
                .col("left_at", MOMENT, "Ушёл; NULL — работает").nullable()
                .col("tax_regime", TEXT, "Налоговый режим: общий (osn, НДС 20/22 %) или упрощённый (usn: без НДС до порога "
                        + "выручки, который снижается по годам; выше — 5 или 7 %)").values("osn", "usn")
                .pk("id"));

        s.add(Table.of("offers", "market", "Предложения продавцов: товар, цена, период").title("Предложения продавцов")
                .rows("≈ 150 тыс.")
                .col("id", INT, "Предложение")
                .col("seller_id", INT, "Продавец").ref("sellers")
                .col("product_id", INT, "Товар").ref("products")
                .col("valid_from", MOMENT, "С (включительно)")
                .col("valid_to", MOMENT, "По (исключая); NULL — без конца в периоде").nullable()
                .col("price", MONEY, "Цена с НДС, руб.")
                .pk("id").index("product_id", "valid_from"));

        // ============================================================ клиенты
        s.add(Table.of("customers", "customers", "Клиенты; имена и фамилии собраны генератором").title("Клиенты")
                .rows("≈ 2 млн")
                .col("id", INT, "Клиент")
                .col("first_name", TEXT, "Имя")
                .col("last_name", TEXT, "Фамилия")
                .col("gender", TEXT, "Пол").values("f", "m")
                .col("birth_year", SMALLINT, "Год рождения")
                .col("registered_at", MOMENT, "Регистрация")
                .col("city_id", INT, "Город").ref("cities")
                .col("acquired_via", TEXT, "Откуда пришёл").values("organic", "search", "ads", "social", "referral",
                        "offline")
                .col("is_b2b", BOOL, "Юрлицо или ИП")
                .col("company_name", TEXT, "Название организации (b2b)").nullable()
                .pk("id").index("registered_at"));

        s.add(Table.of("addresses", "customers", "Адреса доставки клиентов").title("Адреса").rows("≈ 2,5 млн")
                .col("id", INT, "Адрес")
                .col("customer_id", INT, "Клиент").ref("customers")
                .col("city_id", INT, "Город").ref("cities")
                .col("lat", DOUBLE, "Широта")
                .col("lon", DOUBLE, "Долгота")
                .col("kind", TEXT, "Вид").values("home", "work", "other")
                .col("created_at", MOMENT, "Добавлен")
                .pk("id"));

        // ============================================================ заказы
        s.add(Table.of("orders", "orders", "Заказы: воронка статусов с моментами, суммы").title("Заказы")
                .rows("≈ 5 млн")
                .col("id", INT, "Заказ (по времени создания)")
                .col("customer_id", INT, "Клиент").ref("customers")
                .col("address_id", INT, "Адрес курьерской или почтовой доставки").nullable().ref("addresses")
                .col("pickup_point_id", INT, "Пункт выдачи").nullable().ref("warehouses")
                .col("session_id", INT, "Сессия сайта или приложения; NULL — заказ по телефону или сессия не попала в журнал "
                        + "(посетитель не дал согласия на аналитику)").nullable().ref("sessions")
                .col("channel", TEXT, "Канал").values("web", "app", "phone")
                .col("delivery_method", TEXT, "Способ получения").values("courier", "pickup", "post")
                .col("created_at", MOMENT, "Создан")
                .col("paid_at", MOMENT, "Оплачен").nullable()
                .col("assembled_at", MOMENT, "Собран").nullable()
                .col("shipped_at", MOMENT, "Отгружен").nullable()
                .col("delivered_at", MOMENT, "Получен клиентом").nullable()
                .col("cancelled_at", MOMENT, "Отменён").nullable()
                .col("estimated_at", MOMENT, "Обещанная дата получения").nullable()
                .col("status", TEXT, "Текущий статус на «сейчас» выгрузки").values(ORDER_STATUSES)
                .col("promo_code", TEXT, "Введённый промокод").nullable()
                .col("items_amount", MONEY, "Сумма строк до скидок, руб.")
                .col("discount_amount", MONEY, "Скидки, руб.")
                .col("delivery_amount", MONEY, "Доставка, руб.")
                .col("vat_amount", MONEY, "НДС в итоге, руб.")
                .col("total_amount", MONEY, "Итого к оплате, руб.")
                .pk("id").index("created_at")
                // введённый промокод — акция с этим кодом (UNIQUE, не ключ): связь только на картинке схемы
                .link(java.util.List.of("promo_code"), "promotions", java.util.List.of("promo_code")));

        s.add(Table.of("order_items", "orders", "Строки заказов").title("Строки заказов").rows("≈ 12 млн")
                .col("order_id", INT, "Заказ").ref("orders")
                .col("line_no", SMALLINT, "Номер строки: 1, 2, …")
                .col("product_id", INT, "Товар").ref("products")
                .col("seller_id", INT, "Продавец; NULL — товар магазина (1P)").nullable().ref("sellers")
                .col("offer_id", INT, "Предложение продавца").nullable().ref("offers")
                .col("qty", SMALLINT, "Штук")
                .col("unit_price", MONEY, "Цена за штуку на момент заказа, руб.")
                .col("discount", MONEY, "Скидка на строку, руб.")
                .col("vat_rate", SMALLINT, "Ставка НДС, % (на дату заказа; 0 — продавец без НДС)")
                .col("vat_amount", MONEY, "НДС строки, руб.")
                .col("warehouse_id", INT, "Склад отгрузки; NULL — продавец FBS отгружает сам").nullable().ref("warehouses")
                .col("promo_id", INT, "Применённая акция").nullable().ref("promotions")
                .pk("order_id", "line_no").check("qty", "qty > 0"));

        s.add(Table.of("order_status_log", "orders", "Журнал статусов заказов — все переходы, в том числе откаты")
                .title("Статусы заказов").rows("≈ 20 млн")
                .col("order_id", INT, "Заказ").ref("orders")
                .col("seq", SMALLINT, "Номер перехода: 1, 2, …")
                .col("at", MOMENT, "Момент")
                .col("status", TEXT, "Новый статус").values(ORDER_STATUSES)
                .pk("order_id", "seq").index("at"));

        s.add(Table.of("payments", "orders", "Платежи по заказам, в том числе неуспешные и возвраты").title("Платежи")
                .rows("≈ 5,4 млн")
                .col("id", INT, "Платёж")
                .col("order_id", INT, "Заказ").ref("orders")
                .col("seq", SMALLINT, "Номер платежа в заказе")
                .col("method", TEXT, "Способ").values("card", "sbp", "installment", "cash_on_delivery", "gift_card",
                        "points")
                .col("installments", SMALLINT, "Месяцев рассрочки").nullable()
                .col("amount", MONEY, "Сумма, руб. (возврат — со знаком минус)")
                .col("status", TEXT, "Статус").values("authorized", "succeeded", "failed", "refunded", "cancelled")
                .col("at", MOMENT, "Момент")
                .pk("id").unique("order_id", "seq").index("at"));

        s.add(Table.of("shipments", "orders", "Отгрузки заказов перевозчикам").title("Отгрузки").rows("≈ 5,2 млн")
                .col("id", INT, "Отгрузка")
                .col("order_id", INT, "Заказ").ref("orders")
                .col("warehouse_id", INT, "Склад отгрузки").ref("warehouses")
                .col("carrier_id", INT, "Перевозчик").ref("carriers")
                .col("zone_id", INT, "Зона доставки; NULL — вне зон (почта по стране)").nullable().ref("delivery_zones")
                .col("planned_at", MOMENT, "План отгрузки")
                .col("shipped_at", MOMENT, "Отгружено").nullable()
                .col("delivered_at", MOMENT, "Доставлено").nullable()
                .col("status", TEXT, "Статус").values("planned", "shipped", "in_transit", "delivered", "returned", "lost")
                .col("weight_g", INT, "Вес, г")
                .col("boxes", SMALLINT, "Мест")
                .pk("id"));

        s.add(Table.of("returns", "orders", "Возвраты по строкам заказов").title("Возвраты").rows("≈ 0,5 млн")
                .col("id", INT, "Возврат")
                .col("order_id", INT, "Заказ")
                .col("line_no", SMALLINT, "Строка заказа")
                .col("qty", SMALLINT, "Штук")
                .col("requested_at", MOMENT, "Заявлен")
                .col("received_at", MOMENT, "Товар получен магазином").nullable()
                .col("reason", TEXT, "Причина").values("defect", "not_as_described", "changed_mind", "wrong_item",
                        "damaged_in_delivery", "late_delivery")
                .col("condition", TEXT, "Состояние товара").nullable().values("new", "opened", "used", "damaged")
                .col("decision", TEXT, "Решение").nullable().values("refund", "replace", "repair", "rejected")
                .col("refund", MONEY, "Возвращено, руб.").nullable()
                .pk("id").fk(java.util.List.of("order_id", "line_no"), "order_items"));

        s.add(Table.of("reviews", "orders", "Отзывы о товарах; текст — из шаблонов фраз").title("Отзывы")
                .rows("≈ 1,6 млн")
                .col("id", INT, "Отзыв")
                .col("order_id", INT, "Заказ").ref("orders")
                .col("product_id", INT, "Товар").ref("products")
                .col("customer_id", INT, "Клиент").ref("customers")
                .col("score", SMALLINT, "Оценка 1–5")
                .col("created_at", MOMENT, "Написан")
                .col("answered_at", MOMENT, "Ответ магазина или продавца").nullable()
                .col("text", TEXT, "Текст").nullable()
                .pk("id").check("score", "score BETWEEN 1 AND 5"));

        // ============================================================ сайт
        s.add(Table.of("sessions", "site", "Сессии сайта и приложения — посетителей, согласившихся на аналитику")
                .title("Сессии").rows("≈ 6 млн")
                .col("id", INT, "Сессия (по времени начала)")
                .col("customer_id", INT, "Клиент; NULL — не вошёл").nullable().ref("customers")
                .col("started_at", MOMENT, "Начало")
                .col("ended_at", MOMENT, "Конец")
                .col("device", TEXT, "Устройство").values("desktop", "mobile_web", "tablet", "app")
                .col("source", TEXT, "Источник перехода").values("direct", "search", "ads", "social", "email", "push",
                        "referral")
                .col("city_id", INT, "Город по геолокации").nullable().ref("cities")
                .pk("id").index("started_at"));

        s.add(Table.of("session_events", "site", "События сессий: воронка от просмотра до оплаты")
                .title("События сессий").rows("≈ 12 млн")
                .col("session_id", INT, "Сессия").ref("sessions")
                .col("seq", INT, "Номер события в сессии")
                .col("at", MOMENT, "Момент")
                .col("kind", TEXT, "Событие").values("view", "search", "add_to_cart", "remove_from_cart", "checkout",
                        "payment")
                .col("product_id", INT, "Товар").nullable().ref("products")
                .col("query", TEXT, "Поисковый запрос").nullable()
                .pk("session_id", "seq"));
        return s;
    }
}
