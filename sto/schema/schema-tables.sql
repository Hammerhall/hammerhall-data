-- sto-data-v1 — таблицы. Сгенерировано из описания схемы; руками не править.
-- Порядок развёртывания: schema-tables.sql → загрузка TSV (COPY) → schema-constraints.sql.
-- Моменты — timestamptz; в TSV они — секунды Unix (UTC): загрузчик переводит to_timestamp(...).
-- Деньги — numeric(14,2), рубли; пустое поле TSV — NULL.

-- ======================================================================
-- Каталог

CREATE TABLE categories (
    id        integer NOT NULL,
    parent_id integer,
    level     smallint NOT NULL,
    name_ru   text NOT NULL,
    name_en   text NOT NULL
);
COMMENT ON TABLE categories IS 'Категории — дерево с одним корнем, до 6 уровней';
COMMENT ON COLUMN categories.id IS 'Категория';
COMMENT ON COLUMN categories.parent_id IS 'Родительская категория; NULL — корень';
COMMENT ON COLUMN categories.level IS 'Уровень: 1 — корень, 6 — самый глубокий';
COMMENT ON COLUMN categories.name_ru IS 'Название по-русски';
COMMENT ON COLUMN categories.name_en IS 'Название по-английски';

CREATE TABLE spec_keys (
    key        text NOT NULL,
    name_ru    text NOT NULL,
    name_en    text NOT NULL,
    unit       text,
    value_type text NOT NULL
);
COMMENT ON TABLE spec_keys IS 'Справочник характеристик товаров (ключи EAV)';
COMMENT ON COLUMN spec_keys.key IS 'Код характеристики';
COMMENT ON COLUMN spec_keys.name_ru IS 'Название по-русски';
COMMENT ON COLUMN spec_keys.name_en IS 'Название по-английски';
COMMENT ON COLUMN spec_keys.unit IS 'Единица (латиницей: in, GB, W, mAh…); NULL — без единицы';
COMMENT ON COLUMN spec_keys.value_type IS 'Тип значения: num, text';

CREATE TABLE brands (
    id               integer NOT NULL,
    name             text NOT NULL,
    origin_country   text NOT NULL,
    tier             text NOT NULL,
    is_private_label boolean NOT NULL
);
COMMENT ON TABLE brands IS 'Бренды — все выдуманные';
COMMENT ON COLUMN brands.id IS 'Бренд';
COMMENT ON COLUMN brands.name IS 'Название (латиницей)';
COMMENT ON COLUMN brands.origin_country IS 'Страна бренда, код ISO 3166-1 alpha-2';
COMMENT ON COLUMN brands.tier IS 'Ценовой уровень: budget, mid, premium';
COMMENT ON COLUMN brands.is_private_label IS 'Собственная марка магазина';

CREATE TABLE products (
    id              integer NOT NULL,
    sku             text NOT NULL,
    ean13           text NOT NULL,
    brand_id        integer NOT NULL,
    category_id     integer NOT NULL,
    name_ru         text NOT NULL,
    name_en         text NOT NULL,
    model_line      text NOT NULL,
    generation      smallint NOT NULL,
    launched_on     date NOT NULL,
    discontinued_on date,
    weight_g        integer NOT NULL,
    length_mm       integer NOT NULL,
    width_mm        integer NOT NULL,
    height_mm       integer NOT NULL,
    warranty_months smallint NOT NULL,
    is_bundle       boolean NOT NULL
);
COMMENT ON TABLE products IS 'Товары: модели, их поколения и варианты; комплекты — тоже товары';
COMMENT ON COLUMN products.id IS 'Товар';
COMMENT ON COLUMN products.sku IS 'Артикул магазина';
COMMENT ON COLUMN products.ean13 IS 'Штрихкод EAN-13 (у комплектов — внутренний, префикс 2)';
COMMENT ON COLUMN products.brand_id IS 'Бренд';
COMMENT ON COLUMN products.category_id IS 'Категория — лист дерева';
COMMENT ON COLUMN products.name_ru IS 'Название по-русски';
COMMENT ON COLUMN products.name_en IS 'Название по-английски';
COMMENT ON COLUMN products.model_line IS 'Модельная линейка';
COMMENT ON COLUMN products.generation IS 'Поколение модели в линейке: 1, 2, …';
COMMENT ON COLUMN products.launched_on IS 'Начало продаж';
COMMENT ON COLUMN products.discontinued_on IS 'Снят с продажи; NULL — продаётся до конца периода';
COMMENT ON COLUMN products.weight_g IS 'Вес в упаковке, г';
COMMENT ON COLUMN products.length_mm IS 'Длина упаковки, мм';
COMMENT ON COLUMN products.width_mm IS 'Ширина упаковки, мм';
COMMENT ON COLUMN products.height_mm IS 'Высота упаковки, мм';
COMMENT ON COLUMN products.warranty_months IS 'Гарантия, месяцев';
COMMENT ON COLUMN products.is_bundle IS 'Комплект (состав — в bundle_items)';

CREATE TABLE product_specs (
    product_id integer NOT NULL,
    key        text NOT NULL,
    value_text text,
    value_num  numeric(14,3),
    unit       text
);
COMMENT ON TABLE product_specs IS 'Характеристики товаров (EAV): одно значение — текст или число';
COMMENT ON COLUMN product_specs.product_id IS 'Товар';
COMMENT ON COLUMN product_specs.key IS 'Характеристика';
COMMENT ON COLUMN product_specs.value_text IS 'Текстовое значение';
COMMENT ON COLUMN product_specs.value_num IS 'Числовое значение';
COMMENT ON COLUMN product_specs.unit IS 'Единица числового значения (как в spec_keys)';

CREATE TABLE bundle_items (
    bundle_id    integer NOT NULL,
    component_id integer NOT NULL,
    qty          smallint NOT NULL
);
COMMENT ON TABLE bundle_items IS 'Состав комплектов; комплект может входить в комплект (DAG без циклов)';
COMMENT ON COLUMN bundle_items.bundle_id IS 'Комплект';
COMMENT ON COLUMN bundle_items.component_id IS 'Составная часть — товар или комплект';
COMMENT ON COLUMN bundle_items.qty IS 'Штук в комплекте';

CREATE TABLE prices (
    product_id integer NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_to   timestamptz,
    price      numeric(14,2) NOT NULL
);
COMMENT ON TABLE prices IS 'История розничных цен магазина: периоды [valid_from, valid_to) без разрывов и наложений';
COMMENT ON COLUMN prices.product_id IS 'Товар';
COMMENT ON COLUMN prices.valid_from IS 'Цена действует с (включительно) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN prices.valid_to IS 'Цена действует по (исключая); NULL — без конца в периоде (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN prices.price IS 'Цена с НДС, руб.';

CREATE TABLE promotions (
    id               integer NOT NULL,
    kind             text NOT NULL,
    name_ru          text NOT NULL,
    name_en          text NOT NULL,
    event            text,
    discount_pct     numeric(5,2),
    discount_amount  numeric(14,2),
    buy_qty          smallint,
    free_qty         smallint,
    promo_code       text,
    min_order_amount numeric(14,2),
    valid_from       timestamptz NOT NULL,
    valid_to         timestamptz NOT NULL,
    stackable        boolean NOT NULL
);
COMMENT ON TABLE promotions IS 'Акции: скидки, промокоды, «n+m», комплекты; периоды пересекаются';
COMMENT ON COLUMN promotions.id IS 'Акция';
COMMENT ON COLUMN promotions.kind IS 'Механика: percent, amount, n_plus_m, bundle, promocode';
COMMENT ON COLUMN promotions.name_ru IS 'Название по-русски';
COMMENT ON COLUMN promotions.name_en IS 'Название по-английски';
COMMENT ON COLUMN promotions.event IS 'Торговое событие календаря; NULL — обычная акция: eleven_eleven, black_friday, new_year, winter_sale, feb23, mar8, may_holidays, summer_sale, back_to_school';
COMMENT ON COLUMN promotions.discount_pct IS 'Скидка, % (percent, bundle, promocode в процентах)';
COMMENT ON COLUMN promotions.discount_amount IS 'Скидка, руб. (amount — с единицы товара; promocode без целей — с заказа)';
COMMENT ON COLUMN promotions.buy_qty IS 'n+m: сколько купить';
COMMENT ON COLUMN promotions.free_qty IS 'n+m: сколько из них бесплатно (самые дешёвые)';
COMMENT ON COLUMN promotions.promo_code IS 'Промокод (только promocode)';
COMMENT ON COLUMN promotions.min_order_amount IS 'Минимальная сумма заказа';
COMMENT ON COLUMN promotions.valid_from IS 'Начало (включительно) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN promotions.valid_to IS 'Конец (исключая) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN promotions.stackable IS 'Суммируется с другими суммируемыми акциями';

CREATE TABLE promotion_targets (
    promo_id    integer NOT NULL,
    seq         integer NOT NULL,
    product_id  integer,
    category_id integer
);
COMMENT ON TABLE promotion_targets IS 'На что действует акция: товар или категория (с поддеревом); у акции без строк — весь заказ';
COMMENT ON COLUMN promotion_targets.promo_id IS 'Акция';
COMMENT ON COLUMN promotion_targets.seq IS 'Номер цели в акции: 1, 2, …';
COMMENT ON COLUMN promotion_targets.product_id IS 'Товар';
COMMENT ON COLUMN promotion_targets.category_id IS 'Категория (действует на всё поддерево)';

-- ======================================================================
-- География и склады

CREATE TABLE cities (
    id               integer NOT NULL,
    name_ru          text NOT NULL,
    name_en          text NOT NULL,
    region_ru        text NOT NULL,
    region_en        text NOT NULL,
    federal_district text NOT NULL,
    lat              double precision NOT NULL,
    lon              double precision NOT NULL,
    timezone         text NOT NULL,
    population       integer NOT NULL,
    airport_code     text NOT NULL
);
COMMENT ON TABLE cities IS 'Города — российские города аэропортов air-data-v1';
COMMENT ON COLUMN cities.id IS 'Город';
COMMENT ON COLUMN cities.name_ru IS 'Название по-русски';
COMMENT ON COLUMN cities.name_en IS 'Название по-английски (опечатки источника исправлены)';
COMMENT ON COLUMN cities.region_ru IS 'Субъект РФ по-русски';
COMMENT ON COLUMN cities.region_en IS 'Субъект РФ по-английски';
COMMENT ON COLUMN cities.federal_district IS 'Федеральный округ: central, northwest, south, north_caucasus, volga, ural, siberia, far_east';
COMMENT ON COLUMN cities.lat IS 'Широта центра (среднее аэропортов города)';
COMMENT ON COLUMN cities.lon IS 'Долгота центра (среднее аэропортов города)';
COMMENT ON COLUMN cities.timezone IS 'Часовой пояс IANA';
COMMENT ON COLUMN cities.population IS 'Население, человек — оценка, округлено';
COMMENT ON COLUMN cities.airport_code IS 'Главный аэропорт города — ключ к air-data-v1 airports';

CREATE TABLE warehouses (
    id        integer NOT NULL,
    parent_id integer,
    kind      text NOT NULL,
    code      text NOT NULL,
    name_ru   text NOT NULL,
    name_en   text NOT NULL,
    city_id   integer NOT NULL,
    lat       double precision NOT NULL,
    lon       double precision NOT NULL,
    opened_on date NOT NULL,
    closed_on date
);
COMMENT ON TABLE warehouses IS 'Склады и пункты выдачи — дерево: хаб → РЦ → городские склады → пункты выдачи';
COMMENT ON COLUMN warehouses.id IS 'Склад или пункт выдачи';
COMMENT ON COLUMN warehouses.parent_id IS 'Кто снабжает; NULL — хаб';
COMMENT ON COLUMN warehouses.kind IS 'Вид: hub, dc, warehouse, pickup_point';
COMMENT ON COLUMN warehouses.code IS 'Код';
COMMENT ON COLUMN warehouses.name_ru IS 'Название по-русски';
COMMENT ON COLUMN warehouses.name_en IS 'Название по-английски';
COMMENT ON COLUMN warehouses.city_id IS 'Город';
COMMENT ON COLUMN warehouses.lat IS 'Широта';
COMMENT ON COLUMN warehouses.lon IS 'Долгота';
COMMENT ON COLUMN warehouses.opened_on IS 'Открыт';
COMMENT ON COLUMN warehouses.closed_on IS 'Закрыт; NULL — работает';

CREATE TABLE storage_cells (
    id            integer NOT NULL,
    warehouse_id  integer NOT NULL,
    zone          text NOT NULL,
    code          text NOT NULL,
    rack          smallint NOT NULL,
    shelf         smallint NOT NULL,
    cell          smallint NOT NULL,
    max_weight_kg integer NOT NULL,
    max_volume_l  integer NOT NULL
);
COMMENT ON TABLE storage_cells IS 'Ячейки хранения хаба, РЦ и городских складов';
COMMENT ON COLUMN storage_cells.id IS 'Ячейка';
COMMENT ON COLUMN storage_cells.warehouse_id IS 'Склад';
COMMENT ON COLUMN storage_cells.zone IS 'Зона хранения: pallet, shelf, mezzanine, oversize, returns';
COMMENT ON COLUMN storage_cells.code IS 'Адрес ячейки: зона-стеллаж-полка-ячейка';
COMMENT ON COLUMN storage_cells.rack IS 'Стеллаж';
COMMENT ON COLUMN storage_cells.shelf IS 'Полка (ярус), 1 — нижняя';
COMMENT ON COLUMN storage_cells.cell IS 'Ячейка на полке';
COMMENT ON COLUMN storage_cells.max_weight_kg IS 'Предельный вес, кг';
COMMENT ON COLUMN storage_cells.max_volume_l IS 'Предельный объём, л';

CREATE TABLE carriers (
    id      integer NOT NULL,
    name_ru text NOT NULL,
    name_en text NOT NULL,
    kind    text NOT NULL
);
COMMENT ON TABLE carriers IS 'Перевозчики — все выдуманные';
COMMENT ON COLUMN carriers.id IS 'Перевозчик';
COMMENT ON COLUMN carriers.name_ru IS 'Название по-русски';
COMMENT ON COLUMN carriers.name_en IS 'Название по-английски';
COMMENT ON COLUMN carriers.kind IS 'Вид: own_courier, courier, express, pickup_network, post, freight, air';

CREATE TABLE delivery_zones (
    id           integer NOT NULL,
    warehouse_id integer NOT NULL,
    carrier_id   integer NOT NULL,
    ring         smallint NOT NULL,
    polygon      text NOT NULL,
    base_days    smallint NOT NULL
);
COMMENT ON TABLE delivery_zones IS 'Зоны доставки: многоугольники вокруг склада по перевозчику; зоны одного склада и перевозчика не перекрываются';
COMMENT ON COLUMN delivery_zones.id IS 'Зона';
COMMENT ON COLUMN delivery_zones.warehouse_id IS 'Склад отгрузки';
COMMENT ON COLUMN delivery_zones.carrier_id IS 'Перевозчик';
COMMENT ON COLUMN delivery_zones.ring IS 'Пояс: 0 — ближний, дальше — дальше';
COMMENT ON COLUMN delivery_zones.polygon IS 'Многоугольник WKT POLYGON((lon lat, …)), против часовой стрелки';
COMMENT ON COLUMN delivery_zones.base_days IS 'Базовый срок доставки, дней';

-- ======================================================================
-- Поставки и складские движения

CREATE TABLE suppliers (
    id             integer NOT NULL,
    name_ru        text NOT NULL,
    name_en        text NOT NULL,
    kind           text NOT NULL,
    city_id        integer NOT NULL,
    lead_time_days smallint NOT NULL,
    reliability    numeric(4,3) NOT NULL
);
COMMENT ON TABLE suppliers IS 'Поставщики магазина (1P) — все выдуманные';
COMMENT ON COLUMN suppliers.id IS 'Поставщик';
COMMENT ON COLUMN suppliers.name_ru IS 'Название по-русски';
COMMENT ON COLUMN suppliers.name_en IS 'Название по-английски';
COMMENT ON COLUMN suppliers.kind IS 'Вид: brand_office, distributor, importer, manufacturer';
COMMENT ON COLUMN suppliers.city_id IS 'Город';
COMMENT ON COLUMN suppliers.lead_time_days IS 'Обычный срок поставки, дней';
COMMENT ON COLUMN suppliers.reliability IS 'Доля поставок в срок, 0–1';

CREATE TABLE purchase_orders (
    id           integer NOT NULL,
    supplier_id  integer NOT NULL,
    warehouse_id integer NOT NULL,
    ordered_at   timestamptz NOT NULL,
    expected_at  timestamptz NOT NULL,
    received_at  timestamptz,
    status       text NOT NULL,
    purpose      text NOT NULL
);
COMMENT ON TABLE purchase_orders IS 'Заказы поставщикам';
COMMENT ON COLUMN purchase_orders.id IS 'Заказ поставщику';
COMMENT ON COLUMN purchase_orders.supplier_id IS 'Поставщик';
COMMENT ON COLUMN purchase_orders.warehouse_id IS 'Склад приёмки (хаб или РЦ)';
COMMENT ON COLUMN purchase_orders.ordered_at IS 'Размещён (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN purchase_orders.expected_at IS 'Ожидается (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN purchase_orders.received_at IS 'Принят; NULL — не принят (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN purchase_orders.status IS 'Статус: placed, confirmed, in_transit, received, partially_received, cancelled';
COMMENT ON COLUMN purchase_orders.purpose IS 'Назначение: входящий остаток на начало периода (на любой склад, даты = начало), первая поставка товара на склад (новинки), пополнение: opening, launch, replenishment';

CREATE TABLE batches (
    id           integer NOT NULL,
    po_id        integer,
    seller_id    integer,
    product_id   integer NOT NULL,
    warehouse_id integer NOT NULL,
    qty          integer NOT NULL,
    unit_cost    numeric(14,2) NOT NULL,
    received_at  timestamptz,
    ordered_qty  integer NOT NULL
);
COMMENT ON TABLE batches IS 'Партии товара на складах: закупка магазина (po_id) или поставка продавца FBO (seller_id)';
COMMENT ON COLUMN batches.id IS 'Партия';
COMMENT ON COLUMN batches.po_id IS 'Заказ поставщику; NULL — партия продавца';
COMMENT ON COLUMN batches.seller_id IS 'Продавец FBO; NULL — партия магазина';
COMMENT ON COLUMN batches.product_id IS 'Товар';
COMMENT ON COLUMN batches.warehouse_id IS 'Склад приёмки';
COMMENT ON COLUMN batches.qty IS 'Штук';
COMMENT ON COLUMN batches.unit_cost IS 'Закупочная цена за штуку без НДС, руб.';
COMMENT ON COLUMN batches.received_at IS 'Принята; NULL — в пути (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN batches.ordered_qty IS 'Заказано штук (qty — принято; у партии в пути qty = ordered_qty)';

CREATE TABLE stock_movements (
    id           bigint NOT NULL,
    at           timestamptz NOT NULL,
    warehouse_id integer NOT NULL,
    cell_id      integer,
    product_id   integer NOT NULL,
    batch_id     integer,
    kind         text NOT NULL,
    qty          integer NOT NULL,
    ref_type     text,
    ref_id       bigint
);
COMMENT ON TABLE stock_movements IS 'Журнал складских движений: количество со знаком';
COMMENT ON COLUMN stock_movements.id IS 'Движение (по времени)';
COMMENT ON COLUMN stock_movements.at IS 'Момент (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN stock_movements.warehouse_id IS 'Склад или пункт выдачи';
COMMENT ON COLUMN stock_movements.cell_id IS 'Ячейка; NULL — без адреса (пункт выдачи, зона приёмки)';
COMMENT ON COLUMN stock_movements.product_id IS 'Товар';
COMMENT ON COLUMN stock_movements.batch_id IS 'Партия; NULL — не партионный учёт (FBS)';
COMMENT ON COLUMN stock_movements.kind IS 'Вид движения: receipt, move, transfer_out, transfer_in, pick, ship, customer_return, writeoff, inventory';
COMMENT ON COLUMN stock_movements.qty IS 'Количество: + приход, − расход';
COMMENT ON COLUMN stock_movements.ref_type IS 'К чему относится: purchase_order, order, shipment, return, transfer, inventory';
COMMENT ON COLUMN stock_movements.ref_id IS 'Id документа по ref_type';

-- ======================================================================
-- Маркетплейс

CREATE TABLE sellers (
    id         integer NOT NULL,
    name       text NOT NULL,
    city_id    integer NOT NULL,
    model      text NOT NULL,
    joined_at  timestamptz NOT NULL,
    left_at    timestamptz,
    tax_regime text NOT NULL
);
COMMENT ON TABLE sellers IS 'Продавцы маркетплейса (3P) — все выдуманные';
COMMENT ON COLUMN sellers.id IS 'Продавец';
COMMENT ON COLUMN sellers.name IS 'Название магазина продавца';
COMMENT ON COLUMN sellers.city_id IS 'Город';
COMMENT ON COLUMN sellers.model IS 'Модель: товар на нашем складе (fbo) или отгружает сам (fbs): fbo, fbs';
COMMENT ON COLUMN sellers.joined_at IS 'Подключился (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN sellers.left_at IS 'Ушёл; NULL — работает (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN sellers.tax_regime IS 'Налоговый режим: общий (osn, НДС 20/22 %) или упрощённый (usn: без НДС до порога выручки, который снижается по годам; выше — 5 или 7 %): osn, usn';

CREATE TABLE offers (
    id         integer NOT NULL,
    seller_id  integer NOT NULL,
    product_id integer NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_to   timestamptz,
    price      numeric(14,2) NOT NULL
);
COMMENT ON TABLE offers IS 'Предложения продавцов: товар, цена, период';
COMMENT ON COLUMN offers.id IS 'Предложение';
COMMENT ON COLUMN offers.seller_id IS 'Продавец';
COMMENT ON COLUMN offers.product_id IS 'Товар';
COMMENT ON COLUMN offers.valid_from IS 'С (включительно) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN offers.valid_to IS 'По (исключая); NULL — без конца в периоде (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN offers.price IS 'Цена с НДС, руб.';

-- ======================================================================
-- Клиенты

CREATE TABLE customers (
    id            integer NOT NULL,
    first_name    text NOT NULL,
    last_name     text NOT NULL,
    gender        text NOT NULL,
    birth_year    smallint NOT NULL,
    registered_at timestamptz NOT NULL,
    city_id       integer NOT NULL,
    acquired_via  text NOT NULL,
    is_b2b        boolean NOT NULL,
    company_name  text
);
COMMENT ON TABLE customers IS 'Клиенты; имена и фамилии собраны генератором';
COMMENT ON COLUMN customers.id IS 'Клиент';
COMMENT ON COLUMN customers.first_name IS 'Имя';
COMMENT ON COLUMN customers.last_name IS 'Фамилия';
COMMENT ON COLUMN customers.gender IS 'Пол: f, m';
COMMENT ON COLUMN customers.birth_year IS 'Год рождения';
COMMENT ON COLUMN customers.registered_at IS 'Регистрация (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN customers.city_id IS 'Город';
COMMENT ON COLUMN customers.acquired_via IS 'Откуда пришёл: organic, search, ads, social, referral, offline';
COMMENT ON COLUMN customers.is_b2b IS 'Юрлицо или ИП';
COMMENT ON COLUMN customers.company_name IS 'Название организации (b2b)';

CREATE TABLE addresses (
    id          integer NOT NULL,
    customer_id integer NOT NULL,
    city_id     integer NOT NULL,
    lat         double precision NOT NULL,
    lon         double precision NOT NULL,
    kind        text NOT NULL,
    created_at  timestamptz NOT NULL
);
COMMENT ON TABLE addresses IS 'Адреса доставки клиентов';
COMMENT ON COLUMN addresses.id IS 'Адрес';
COMMENT ON COLUMN addresses.customer_id IS 'Клиент';
COMMENT ON COLUMN addresses.city_id IS 'Город';
COMMENT ON COLUMN addresses.lat IS 'Широта';
COMMENT ON COLUMN addresses.lon IS 'Долгота';
COMMENT ON COLUMN addresses.kind IS 'Вид: home, work, other';
COMMENT ON COLUMN addresses.created_at IS 'Добавлен (в TSV — секунды Unix, UTC)';

-- ======================================================================
-- Заказы

CREATE TABLE orders (
    id              integer NOT NULL,
    customer_id     integer NOT NULL,
    address_id      integer,
    pickup_point_id integer,
    session_id      integer,
    channel         text NOT NULL,
    delivery_method text NOT NULL,
    created_at      timestamptz NOT NULL,
    paid_at         timestamptz,
    assembled_at    timestamptz,
    shipped_at      timestamptz,
    delivered_at    timestamptz,
    cancelled_at    timestamptz,
    estimated_at    timestamptz,
    status          text NOT NULL,
    promo_code      text,
    items_amount    numeric(14,2) NOT NULL,
    discount_amount numeric(14,2) NOT NULL,
    delivery_amount numeric(14,2) NOT NULL,
    vat_amount      numeric(14,2) NOT NULL,
    total_amount    numeric(14,2) NOT NULL
);
COMMENT ON TABLE orders IS 'Заказы: воронка статусов с моментами, суммы';
COMMENT ON COLUMN orders.id IS 'Заказ (по времени создания)';
COMMENT ON COLUMN orders.customer_id IS 'Клиент';
COMMENT ON COLUMN orders.address_id IS 'Адрес курьерской или почтовой доставки';
COMMENT ON COLUMN orders.pickup_point_id IS 'Пункт выдачи';
COMMENT ON COLUMN orders.session_id IS 'Сессия сайта или приложения; NULL — заказ по телефону или сессия не попала в журнал (посетитель не дал согласия на аналитику)';
COMMENT ON COLUMN orders.channel IS 'Канал: web, app, phone';
COMMENT ON COLUMN orders.delivery_method IS 'Способ получения: courier, pickup, post';
COMMENT ON COLUMN orders.created_at IS 'Создан (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN orders.paid_at IS 'Оплачен (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN orders.assembled_at IS 'Собран (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN orders.shipped_at IS 'Отгружен (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN orders.delivered_at IS 'Получен клиентом (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN orders.cancelled_at IS 'Отменён (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN orders.estimated_at IS 'Обещанная дата получения (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN orders.status IS 'Текущий статус на «сейчас» выгрузки: created, paid, assembling, assembled, shipped, ready_for_pickup, delivered, cancelled, returned';
COMMENT ON COLUMN orders.promo_code IS 'Введённый промокод';
COMMENT ON COLUMN orders.items_amount IS 'Сумма строк до скидок, руб.';
COMMENT ON COLUMN orders.discount_amount IS 'Скидки, руб.';
COMMENT ON COLUMN orders.delivery_amount IS 'Доставка, руб.';
COMMENT ON COLUMN orders.vat_amount IS 'НДС в итоге, руб.';
COMMENT ON COLUMN orders.total_amount IS 'Итого к оплате, руб.';

CREATE TABLE order_items (
    order_id     integer NOT NULL,
    line_no      smallint NOT NULL,
    product_id   integer NOT NULL,
    seller_id    integer,
    offer_id     integer,
    qty          smallint NOT NULL,
    unit_price   numeric(14,2) NOT NULL,
    discount     numeric(14,2) NOT NULL,
    vat_rate     smallint NOT NULL,
    vat_amount   numeric(14,2) NOT NULL,
    warehouse_id integer,
    promo_id     integer
);
COMMENT ON TABLE order_items IS 'Строки заказов';
COMMENT ON COLUMN order_items.order_id IS 'Заказ';
COMMENT ON COLUMN order_items.line_no IS 'Номер строки: 1, 2, …';
COMMENT ON COLUMN order_items.product_id IS 'Товар';
COMMENT ON COLUMN order_items.seller_id IS 'Продавец; NULL — товар магазина (1P)';
COMMENT ON COLUMN order_items.offer_id IS 'Предложение продавца';
COMMENT ON COLUMN order_items.qty IS 'Штук';
COMMENT ON COLUMN order_items.unit_price IS 'Цена за штуку на момент заказа, руб.';
COMMENT ON COLUMN order_items.discount IS 'Скидка на строку, руб.';
COMMENT ON COLUMN order_items.vat_rate IS 'Ставка НДС, % (на дату заказа; 0 — продавец без НДС)';
COMMENT ON COLUMN order_items.vat_amount IS 'НДС строки, руб.';
COMMENT ON COLUMN order_items.warehouse_id IS 'Склад отгрузки; NULL — продавец FBS отгружает сам';
COMMENT ON COLUMN order_items.promo_id IS 'Применённая акция';

CREATE TABLE order_status_log (
    order_id integer NOT NULL,
    seq      smallint NOT NULL,
    at       timestamptz NOT NULL,
    status   text NOT NULL
);
COMMENT ON TABLE order_status_log IS 'Журнал статусов заказов — все переходы, в том числе откаты';
COMMENT ON COLUMN order_status_log.order_id IS 'Заказ';
COMMENT ON COLUMN order_status_log.seq IS 'Номер перехода: 1, 2, …';
COMMENT ON COLUMN order_status_log.at IS 'Момент (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN order_status_log.status IS 'Новый статус: created, paid, assembling, assembled, shipped, ready_for_pickup, delivered, cancelled, returned';

CREATE TABLE payments (
    id           integer NOT NULL,
    order_id     integer NOT NULL,
    seq          smallint NOT NULL,
    method       text NOT NULL,
    installments smallint,
    amount       numeric(14,2) NOT NULL,
    status       text NOT NULL,
    at           timestamptz NOT NULL
);
COMMENT ON TABLE payments IS 'Платежи по заказам, в том числе неуспешные и возвраты';
COMMENT ON COLUMN payments.id IS 'Платёж';
COMMENT ON COLUMN payments.order_id IS 'Заказ';
COMMENT ON COLUMN payments.seq IS 'Номер платежа в заказе';
COMMENT ON COLUMN payments.method IS 'Способ: card, sbp, installment, cash_on_delivery, gift_card, points';
COMMENT ON COLUMN payments.installments IS 'Месяцев рассрочки';
COMMENT ON COLUMN payments.amount IS 'Сумма, руб. (возврат — со знаком минус)';
COMMENT ON COLUMN payments.status IS 'Статус: authorized, succeeded, failed, refunded, cancelled';
COMMENT ON COLUMN payments.at IS 'Момент (в TSV — секунды Unix, UTC)';

CREATE TABLE shipments (
    id           integer NOT NULL,
    order_id     integer NOT NULL,
    warehouse_id integer NOT NULL,
    carrier_id   integer NOT NULL,
    zone_id      integer,
    planned_at   timestamptz NOT NULL,
    shipped_at   timestamptz,
    delivered_at timestamptz,
    status       text NOT NULL,
    weight_g     integer NOT NULL,
    boxes        smallint NOT NULL
);
COMMENT ON TABLE shipments IS 'Отгрузки заказов перевозчикам';
COMMENT ON COLUMN shipments.id IS 'Отгрузка';
COMMENT ON COLUMN shipments.order_id IS 'Заказ';
COMMENT ON COLUMN shipments.warehouse_id IS 'Склад отгрузки';
COMMENT ON COLUMN shipments.carrier_id IS 'Перевозчик';
COMMENT ON COLUMN shipments.zone_id IS 'Зона доставки; NULL — вне зон (почта по стране)';
COMMENT ON COLUMN shipments.planned_at IS 'План отгрузки (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN shipments.shipped_at IS 'Отгружено (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN shipments.delivered_at IS 'Доставлено (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN shipments.status IS 'Статус: planned, shipped, in_transit, delivered, returned, lost';
COMMENT ON COLUMN shipments.weight_g IS 'Вес, г';
COMMENT ON COLUMN shipments.boxes IS 'Мест';

CREATE TABLE returns (
    id           integer NOT NULL,
    order_id     integer NOT NULL,
    line_no      smallint NOT NULL,
    qty          smallint NOT NULL,
    requested_at timestamptz NOT NULL,
    received_at  timestamptz,
    reason       text NOT NULL,
    condition    text,
    decision     text,
    refund       numeric(14,2)
);
COMMENT ON TABLE returns IS 'Возвраты по строкам заказов';
COMMENT ON COLUMN returns.id IS 'Возврат';
COMMENT ON COLUMN returns.order_id IS 'Заказ';
COMMENT ON COLUMN returns.line_no IS 'Строка заказа';
COMMENT ON COLUMN returns.qty IS 'Штук';
COMMENT ON COLUMN returns.requested_at IS 'Заявлен (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN returns.received_at IS 'Товар получен магазином (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN returns.reason IS 'Причина: defect, not_as_described, changed_mind, wrong_item, damaged_in_delivery, late_delivery';
COMMENT ON COLUMN returns.condition IS 'Состояние товара: new, opened, used, damaged';
COMMENT ON COLUMN returns.decision IS 'Решение: refund, replace, repair, rejected';
COMMENT ON COLUMN returns.refund IS 'Возвращено, руб.';

CREATE TABLE reviews (
    id          integer NOT NULL,
    order_id    integer NOT NULL,
    product_id  integer NOT NULL,
    customer_id integer NOT NULL,
    score       smallint NOT NULL,
    created_at  timestamptz NOT NULL,
    answered_at timestamptz,
    text        text
);
COMMENT ON TABLE reviews IS 'Отзывы о товарах; текст — из шаблонов фраз';
COMMENT ON COLUMN reviews.id IS 'Отзыв';
COMMENT ON COLUMN reviews.order_id IS 'Заказ';
COMMENT ON COLUMN reviews.product_id IS 'Товар';
COMMENT ON COLUMN reviews.customer_id IS 'Клиент';
COMMENT ON COLUMN reviews.score IS 'Оценка 1–5';
COMMENT ON COLUMN reviews.created_at IS 'Написан (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN reviews.answered_at IS 'Ответ магазина или продавца (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN reviews.text IS 'Текст';

-- ======================================================================
-- Сайт и приложение

CREATE TABLE sessions (
    id          integer NOT NULL,
    customer_id integer,
    started_at  timestamptz NOT NULL,
    ended_at    timestamptz NOT NULL,
    device      text NOT NULL,
    source      text NOT NULL,
    city_id     integer
);
COMMENT ON TABLE sessions IS 'Сессии сайта и приложения — посетителей, согласившихся на аналитику';
COMMENT ON COLUMN sessions.id IS 'Сессия (по времени начала)';
COMMENT ON COLUMN sessions.customer_id IS 'Клиент; NULL — не вошёл';
COMMENT ON COLUMN sessions.started_at IS 'Начало (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN sessions.ended_at IS 'Конец (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN sessions.device IS 'Устройство: desktop, mobile_web, tablet, app';
COMMENT ON COLUMN sessions.source IS 'Источник перехода: direct, search, ads, social, email, push, referral';
COMMENT ON COLUMN sessions.city_id IS 'Город по геолокации';

CREATE TABLE session_events (
    session_id integer NOT NULL,
    seq        integer NOT NULL,
    at         timestamptz NOT NULL,
    kind       text NOT NULL,
    product_id integer,
    query      text
);
COMMENT ON TABLE session_events IS 'События сессий: воронка от просмотра до оплаты';
COMMENT ON COLUMN session_events.session_id IS 'Сессия';
COMMENT ON COLUMN session_events.seq IS 'Номер события в сессии';
COMMENT ON COLUMN session_events.at IS 'Момент (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN session_events.kind IS 'Событие: view, search, add_to_cart, remove_from_cart, checkout, payment';
COMMENT ON COLUMN session_events.product_id IS 'Товар';
COMMENT ON COLUMN session_events.query IS 'Поисковый запрос';
