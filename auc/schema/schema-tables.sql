-- auc-data-v1 — таблицы. Сгенерировано из описания схемы; руками не править.
-- Порядок развёртывания: schema-tables.sql → загрузка TSV (COPY) → schema-constraints.sql.
-- Моменты — timestamptz; в TSV они — секунды Unix (UTC): загрузчик переводит to_timestamp(...).
-- Деньги — numeric(14,2), рубли; пустое поле TSV — NULL.

-- ======================================================================
-- Дом и правила

CREATE TABLE departments (
    id         smallint NOT NULL,
    code       text NOT NULL,
    name_ru    text NOT NULL,
    name_en    text NOT NULL,
    sort_order smallint NOT NULL
);
COMMENT ON TABLE departments IS 'Отделы дома';
COMMENT ON COLUMN departments.id IS 'Отдел';
COMMENT ON COLUMN departments.code IS 'Код отдела: painting, jewelry, coins, books, decorative, collectibles';
COMMENT ON COLUMN departments.name_ru IS 'Название по-русски';
COMMENT ON COLUMN departments.name_en IS 'Название по-английски';
COMMENT ON COLUMN departments.sort_order IS 'Порядок в каталоге';

CREATE TABLE categories (
    id            integer NOT NULL,
    department_id smallint NOT NULL,
    parent_id     integer,
    level         smallint NOT NULL,
    code          text NOT NULL,
    name_ru       text NOT NULL,
    name_en       text NOT NULL
);
COMMENT ON TABLE categories IS 'Категории — разделы отдела и подразделы, дерево до 3 уровней';
COMMENT ON COLUMN categories.id IS 'Категория';
COMMENT ON COLUMN categories.department_id IS 'Отдел (у ребёнка — тот же, что у родителя)';
COMMENT ON COLUMN categories.parent_id IS 'Родительская категория; NULL — верхний уровень отдела';
COMMENT ON COLUMN categories.level IS 'Уровень: 1 — раздел отдела, 2–3 — подразделы';
COMMENT ON COLUMN categories.code IS 'Код категории (латиница)';
COMMENT ON COLUMN categories.name_ru IS 'Название по-русски';
COMMENT ON COLUMN categories.name_en IS 'Название по-английски';

CREATE TABLE cities (
    id               integer NOT NULL,
    name_ru          text NOT NULL,
    name_en          text NOT NULL,
    region_ru        text NOT NULL,
    federal_district text NOT NULL,
    lat              double precision NOT NULL,
    lon              double precision NOT NULL,
    timezone         text NOT NULL,
    population       integer NOT NULL
);
COMMENT ON TABLE cities IS 'Города — как в sto-data-v1 (те же id): набор соединяется с «Кременъ» по городу';
COMMENT ON COLUMN cities.id IS 'Город (id sto-data-v1)';
COMMENT ON COLUMN cities.name_ru IS 'Название по-русски';
COMMENT ON COLUMN cities.name_en IS 'Название по-английски';
COMMENT ON COLUMN cities.region_ru IS 'Субъект РФ';
COMMENT ON COLUMN cities.federal_district IS 'Федеральный округ: central, northwest, south, north_caucasus, volga, ural, siberia, far_east';
COMMENT ON COLUMN cities.lat IS 'Широта центра';
COMMENT ON COLUMN cities.lon IS 'Долгота центра';
COMMENT ON COLUMN cities.timezone IS 'Часовой пояс IANA — местное время участников';
COMMENT ON COLUMN cities.population IS 'Население, оценка — вес клиентов по городам';

CREATE TABLE offices (
    id           smallint NOT NULL,
    code         text NOT NULL,
    kind         text NOT NULL,
    city_id      integer NOT NULL,
    name_ru      text NOT NULL,
    name_en      text NOT NULL,
    has_saleroom boolean NOT NULL,
    opened_on    date NOT NULL,
    closed_on    date
);
COMMENT ON TABLE offices IS 'Офисы: главный, залы торгов, приёмные';
COMMENT ON COLUMN offices.id IS 'Офис';
COMMENT ON COLUMN offices.code IS 'Код: msk, spb, kzn, nnv, ekb, nsk, krd, vvo';
COMMENT ON COLUMN offices.kind IS 'Вид: headquarters, saleroom, intake';
COMMENT ON COLUMN offices.city_id IS 'Город';
COMMENT ON COLUMN offices.name_ru IS 'Название по-русски';
COMMENT ON COLUMN offices.name_en IS 'Название по-английски';
COMMENT ON COLUMN offices.has_saleroom IS 'Есть зал торгов';
COMMENT ON COLUMN offices.opened_on IS 'Открыт';
COMMENT ON COLUMN offices.closed_on IS 'Закрыт; NULL — работает';

CREATE TABLE staff (
    id            integer NOT NULL,
    office_id     smallint NOT NULL,
    department_id smallint,
    role          text NOT NULL,
    first_name    text NOT NULL,
    last_name     text NOT NULL,
    hired_on      date NOT NULL,
    left_on       date
);
COMMENT ON TABLE staff IS 'Сотрудники и их роли (имена выдуманные)';
COMMENT ON COLUMN staff.id IS 'Сотрудник; id — по дате приёма';
COMMENT ON COLUMN staff.office_id IS 'Офис';
COMMENT ON COLUMN staff.department_id IS 'Отдел; NULL — не за отделом';
COMMENT ON COLUMN staff.role IS 'Роль: specialist, head_of_department, auctioneer, phone_clerk, client_service, accountant, administrator';
COMMENT ON COLUMN staff.first_name IS 'Имя';
COMMENT ON COLUMN staff.last_name IS 'Фамилия';
COMMENT ON COLUMN staff.hired_on IS 'Принят';
COMMENT ON COLUMN staff.left_on IS 'Уволился; NULL — работает';

CREATE TABLE increment_bands (
    valid_from  date NOT NULL,
    from_amount numeric(14,2) NOT NULL,
    step        numeric(14,2) NOT NULL
);
COMMENT ON TABLE increment_bands IS 'Таблица шагов ставки: от текущей цены — шаг (T1)';
COMMENT ON COLUMN increment_bands.valid_from IS 'Версия таблицы шагов (v1 — одна, 2024-09-01)';
COMMENT ON COLUMN increment_bands.from_amount IS 'Цена от (включительно) — полоса до следующего порога, руб.';
COMMENT ON COLUMN increment_bands.step IS 'Шаг ставки в полосе, руб.';

CREATE TABLE fee_schedules (
    id            smallint NOT NULL,
    code          text NOT NULL,
    kind          text NOT NULL,
    basis         text NOT NULL,
    applies_to    text NOT NULL,
    department_id smallint,
    valid_from    date NOT NULL,
    valid_to      date,
    rate_pct      numeric(6,3),
    fixed_amount  numeric(14,2),
    name_ru       text NOT NULL
);
COMMENT ON TABLE fee_schedules IS 'Шкалы и сборы: премия, комиссия, площадка, страхование, каталог, снятие, пени, депозит';
COMMENT ON COLUMN fee_schedules.id IS 'Шкала или сбор';
COMMENT ON COLUMN fee_schedules.code IS 'Код: BP-2024, BP-2026, BP-COIN, SC-2024, PLT-2024, INS-2024, CAT-LIVE, CAT-TIMED, WDR-2024, LATE-2024, DEP-2024';
COMMENT ON COLUMN fee_schedules.kind IS 'Вид: buyer_premium, seller_commission, platform, insurance, catalogue, withdrawal, late_payment, deposit';
COMMENT ON COLUMN fee_schedules.basis IS 'От чего считается: hammer, low_estimate, lot, invoice_total_per_day';
COMMENT ON COLUMN fee_schedules.applies_to IS 'Формат торгов: all, live, timed';
COMMENT ON COLUMN fee_schedules.department_id IS 'Только для отдела (BP-COIN); NULL — все';
COMMENT ON COLUMN fee_schedules.valid_from IS 'Действует с';
COMMENT ON COLUMN fee_schedules.valid_to IS 'Последний день действия (включительно); NULL — действует';
COMMENT ON COLUMN fee_schedules.rate_pct IS 'Единая ставка, % (у пеней — в день); NULL — ступени в fee_tiers или фиксированная сумма';
COMMENT ON COLUMN fee_schedules.fixed_amount IS 'Фиксированная сумма за лот, руб.';
COMMENT ON COLUMN fee_schedules.name_ru IS 'Название для отчётов';

CREATE TABLE fee_tiers (
    schedule_id smallint NOT NULL,
    tier_no     smallint NOT NULL,
    from_amount numeric(14,2) NOT NULL,
    rate_pct    numeric(6,3) NOT NULL
);
COMMENT ON TABLE fee_tiers IS 'Ступени премии и комиссии: ставка — на часть суммы от from_amount (не включая) до начала следующей ступени (включая)';
COMMENT ON COLUMN fee_tiers.schedule_id IS 'Шкала (ступенчатые: BP-2024, BP-2026, SC-2024)';
COMMENT ON COLUMN fee_tiers.tier_no IS 'Ступень: 1, 2, …';
COMMENT ON COLUMN fee_tiers.from_amount IS 'Начало части, руб.';
COMMENT ON COLUMN fee_tiers.rate_pct IS 'Ставка, % (скидка торговца — правило M4, не строка)';

-- ======================================================================
-- Мастера и вещи

CREATE TABLE makers (
    id            integer NOT NULL,
    kind          text NOT NULL,
    department_id smallint NOT NULL,
    is_person     boolean NOT NULL,
    name_ru       text NOT NULL,
    name_en       text NOT NULL,
    first_name    text,
    patronymic    text,
    last_name     text,
    active_from   smallint NOT NULL,
    active_to     smallint,
    country       text NOT NULL,
    city_ru       text,
    school_ru     text,
    specialty_ru  text,
    mark_ru       text,
    tier          text NOT NULL
);
COMMENT ON TABLE makers IS 'Мастера, мастерские, мануфактуры, монетные дворы, издательства — все выдуманные';
COMMENT ON COLUMN makers.id IS 'Мастер';
COMMENT ON COLUMN makers.kind IS 'Вид: artist, photographer, sculptor, jeweler, watchmaker, mint, issuer, medalist, author, publisher, printer, cartographer, porcelain, silversmith, furniture, glass, carpet, foundry, postal, toymaker';
COMMENT ON COLUMN makers.department_id IS 'Отдел вида';
COMMENT ON COLUMN makers.is_person IS 'Человек (иначе — мастерская, мануфактура, двор)';
COMMENT ON COLUMN makers.name_ru IS 'Имя для каталога';
COMMENT ON COLUMN makers.name_en IS 'Имя по-английски';
COMMENT ON COLUMN makers.first_name IS 'Имя (у людей)';
COMMENT ON COLUMN makers.patronymic IS 'Отчество (у людей)';
COMMENT ON COLUMN makers.last_name IS 'Фамилия (у людей)';
COMMENT ON COLUMN makers.active_from IS 'Год рождения или основания';
COMMENT ON COLUMN makers.active_to IS 'Год смерти или закрытия; NULL — жив, работает';
COMMENT ON COLUMN makers.country IS 'Страна (код словаря)';
COMMENT ON COLUMN makers.city_ru IS 'Город; NULL — у выдуманного монетного двора';
COMMENT ON COLUMN makers.school_ru IS 'Направление (художники, скульпторы)';
COMMENT ON COLUMN makers.specialty_ru IS 'Специализация; у мастерских — чем известна';
COMMENT ON COLUMN makers.mark_ru IS 'Подпись, монограмма, клеймо, знак двора — как на предмете';
COMMENT ON COLUMN makers.tier IS 'Уровень рынка: A — имена, ради которых приходят на торги, … D: A, B, C, D';

CREATE TABLE items (
    id              integer NOT NULL,
    consignor_id    integer NOT NULL,
    office_id       smallint NOT NULL,
    specialist_id   integer NOT NULL,
    received_at     timestamptz NOT NULL,
    decided_at      timestamptz NOT NULL,
    accepted        boolean NOT NULL,
    decline_reason  text,
    department_id   smallint NOT NULL,
    category_id     integer NOT NULL,
    maker_id        integer,
    attribution     text NOT NULL,
    title_ru        text NOT NULL,
    title_en        text NOT NULL,
    description_ru  text NOT NULL,
    condition_ru    text NOT NULL,
    condition_grade text NOT NULL,
    dating_ru       text,
    year_from       smallint,
    year_to         smallint,
    origin_place_ru text,
    material_ru     text,
    technique_ru    text,
    height_mm       integer,
    width_mm        integer,
    depth_mm        integer,
    diameter_mm     numeric(6,1),
    weight_g        numeric(9,2),
    metal_fineness  smallint,
    pieces          smallint NOT NULL,
    signed          boolean
);
COMMENT ON TABLE items IS 'Вещи — факт приёма с описанием; при продлении периода не меняются';
COMMENT ON COLUMN items.id IS 'Вещь; id — по моменту приёма';
COMMENT ON COLUMN items.consignor_id IS 'Кто принёс (при первом приёме)';
COMMENT ON COLUMN items.office_id IS 'Где принята';
COMMENT ON COLUMN items.specialist_id IS 'Кто смотрел';
COMMENT ON COLUMN items.received_at IS 'Принята на осмотр (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN items.decided_at IS 'Решение о приёме (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN items.accepted IS 'Принята на комиссию';
COMMENT ON COLUMN items.decline_reason IS 'Причина отказа — только у непринятых: low_value, condition, authenticity_doubt, duplicate';
COMMENT ON COLUMN items.department_id IS 'Отдел';
COMMENT ON COLUMN items.category_id IS 'Категория — лист дерева';
COMMENT ON COLUMN items.maker_id IS 'Мастер; NULL — неизвестен';
COMMENT ON COLUMN items.attribution IS 'Атрибуция, как в каталогах: работа мастера, приписывается, мастерская, круг, последователь, в манере, по оригиналу, неизвестный мастер: by, attributed, workshop, circle, follower, manner, after, unknown';
COMMENT ON COLUMN items.title_ru IS 'Заголовок лота по-русски, 20–120 знаков';
COMMENT ON COLUMN items.title_en IS 'Заголовок лота по-английски';
COMMENT ON COLUMN items.description_ru IS 'Описание, 200–1 200 знаков';
COMMENT ON COLUMN items.condition_ru IS 'Состояние словами, 30–400 знаков';
COMMENT ON COLUMN items.condition_grade IS 'Состояние по шкале раздела: общая excellent … poor; монеты и медали unc … vg; марки mnh, mh, used: excellent, very_good, good, fair, poor, unc, au, xf, vf, f, vg, mnh, mh, used';
COMMENT ON COLUMN items.dating_ru IS 'Датировка, как в каталоге';
COMMENT ON COLUMN items.year_from IS 'Год создания — начало диапазона датировки';
COMMENT ON COLUMN items.year_to IS 'Год создания — конец диапазона';
COMMENT ON COLUMN items.origin_place_ru IS 'Место создания';
COMMENT ON COLUMN items.material_ru IS 'Материал';
COMMENT ON COLUMN items.technique_ru IS 'Техника';
COMMENT ON COLUMN items.height_mm IS 'Высота, мм';
COMMENT ON COLUMN items.width_mm IS 'Ширина, мм';
COMMENT ON COLUMN items.depth_mm IS 'Глубина, мм';
COMMENT ON COLUMN items.diameter_mm IS 'Диаметр, мм (монеты, медали, часы, тарелки)';
COMMENT ON COLUMN items.weight_g IS 'Вес, г';
COMMENT ON COLUMN items.metal_fineness IS 'Проба металла, метрическая: 375 … 999';
COMMENT ON COLUMN items.pieces IS 'Предметов в вещи (комплект, лот марок)';
COMMENT ON COLUMN items.signed IS 'Подпись, клеймо или марка мастера есть';

CREATE TABLE provenance (
    item_id       integer NOT NULL,
    seq           smallint NOT NULL,
    kind          text NOT NULL,
    text_ru       text NOT NULL,
    year_from     smallint,
    year_to       smallint,
    place_ru      text,
    owner_name_ru text
);
COMMENT ON TABLE provenance IS 'Происхождение вещи — звенья цепочки, литература, выставки';
COMMENT ON COLUMN provenance.item_id IS 'Вещь';
COMMENT ON COLUMN provenance.seq IS 'Порядок: 1 — самое раннее звено';
COMMENT ON COLUMN provenance.kind IS 'Вид звена; chain — вся цепочка одной записью: by_descent, private_collection, acquired, auction, gallery, exhibition, literature, expertise, chain';
COMMENT ON COLUMN provenance.text_ru IS 'Запись, как в каталоге, 30–300 знаков (chain — до 1 000)';
COMMENT ON COLUMN provenance.year_from IS 'Год начала звена';
COMMENT ON COLUMN provenance.year_to IS 'Год конца звена';
COMMENT ON COLUMN provenance.place_ru IS 'Город или страна';
COMMENT ON COLUMN provenance.owner_name_ru IS 'Владелец, собрание, галерея, издание (выдуманные)';

CREATE TABLE consignments (
    id                     integer NOT NULL,
    consignor_id           integer NOT NULL,
    office_id              smallint NOT NULL,
    specialist_id          integer NOT NULL,
    kind                   text NOT NULL,
    signed_at              timestamptz NOT NULL,
    commission_schedule_id smallint NOT NULL,
    commission_rate        numeric(6,3),
    dealer_discount        boolean NOT NULL,
    unsold_policy          text NOT NULL,
    expires_on             date NOT NULL
);
COMMENT ON TABLE consignments IS 'Договоры комиссии (и сдача купленного обратно)';
COMMENT ON COLUMN consignments.id IS 'Договор; id — по моменту подписания';
COMMENT ON COLUMN consignments.consignor_id IS 'Комитент';
COMMENT ON COLUMN consignments.office_id IS 'Офис';
COMMENT ON COLUMN consignments.specialist_id IS 'Специалист';
COMMENT ON COLUMN consignments.kind IS 'Вид: частный, торговец, наследство, учреждение, сдача купленного в доме: private, dealer, estate, institution, resale';
COMMENT ON COLUMN consignments.signed_at IS 'Подписан (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN consignments.commission_schedule_id IS 'Шкала комиссии (SC-2024)';
COMMENT ON COLUMN consignments.commission_rate IS 'Договорная единая ставка, % (5–10); NULL — по шкале';
COMMENT ON COLUMN consignments.dealer_discount IS 'Скидка торговца 5 п. п. (M4)';
COMMENT ON COLUMN consignments.unsold_policy IS 'Что делать с непроданным: return, reoffer, ask';
COMMENT ON COLUMN consignments.expires_on IS 'Срок договора (12 месяцев)';

CREATE TABLE consignment_items (
    consignment_id integer NOT NULL,
    line_no        smallint NOT NULL,
    item_id        integer NOT NULL,
    agreed_low     numeric(14,2) NOT NULL,
    agreed_high    numeric(14,2) NOT NULL,
    agreed_reserve numeric(14,2),
    ended_at       timestamptz,
    outcome        text
);
COMMENT ON TABLE consignment_items IS 'Вещи в договоре: условия приёма, итог';
COMMENT ON COLUMN consignment_items.consignment_id IS 'Договор';
COMMENT ON COLUMN consignment_items.line_no IS 'Строка договора';
COMMENT ON COLUMN consignment_items.item_id IS 'Вещь (только принятые)';
COMMENT ON COLUMN consignment_items.agreed_low IS 'Нижняя оценка при приёме, руб.';
COMMENT ON COLUMN consignment_items.agreed_high IS 'Верхняя оценка при приёме, руб.';
COMMENT ON COLUMN consignment_items.agreed_reserve IS 'Резерв по договору, руб.; NULL — без резерва';
COMMENT ON COLUMN consignment_items.ended_at IS 'Вещь вышла из договора; NULL — в работе на «сейчас» (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN consignment_items.outcome IS 'Итог: sold, returned, withdrawn';

-- ======================================================================
-- Клиенты

CREATE TABLE clients (
    id                   integer NOT NULL,
    kind                 text NOT NULL,
    first_name           text,
    patronymic           text,
    last_name            text,
    company_name         text,
    gender               text,
    birth_year           smallint,
    city_id              integer NOT NULL,
    email                text NOT NULL,
    language             text NOT NULL,
    is_dealer            boolean NOT NULL,
    registered_at        timestamptz NOT NULL,
    verified_at          timestamptz,
    enhanced_verified_at timestamptz,
    marketing_consent    boolean NOT NULL,
    blocked_at           timestamptz,
    block_reason         text
);
COMMENT ON TABLE clients IS 'Покупатели и комитенты — одни люди и организации (все выдуманные)';
COMMENT ON COLUMN clients.id IS 'Клиент; id — по моменту регистрации';
COMMENT ON COLUMN clients.kind IS 'Человек или организация: person, company';
COMMENT ON COLUMN clients.first_name IS 'Имя (у людей)';
COMMENT ON COLUMN clients.patronymic IS 'Отчество (у людей, не у всех)';
COMMENT ON COLUMN clients.last_name IS 'Фамилия (у людей)';
COMMENT ON COLUMN clients.company_name IS 'Название (у организаций)';
COMMENT ON COLUMN clients.gender IS 'Пол (у людей): m, f';
COMMENT ON COLUMN clients.birth_year IS 'Год рождения (у людей)';
COMMENT ON COLUMN clients.city_id IS 'Город';
COMMENT ON COLUMN clients.email IS 'E-mail на example.com, example.org, example.net';
COMMENT ON COLUMN clients.language IS 'Язык общения: ru, en';
COMMENT ON COLUMN clients.is_dealer IS 'Дилерский договор (торговец)';
COMMENT ON COLUMN clients.registered_at IS 'Зарегистрирован (с 2012 года) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN clients.verified_at IS 'Документ и карта проверены (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN clients.enhanced_verified_at IS 'Расширенная проверка (счёт от 1 млн ₽, драгоценные металлы) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN clients.marketing_consent IS 'Согласие на рассылки (без него — только служебные уведомления)';
COMMENT ON COLUMN clients.blocked_at IS 'Заблокирован (на «сейчас») (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN clients.block_reason IS 'Причина блокировки: non_payment, shill_suspected, own_request';

-- ======================================================================
-- Торги

CREATE TABLE sales (
    id                        integer NOT NULL,
    code                      text NOT NULL,
    format                    text NOT NULL,
    tier                      text NOT NULL,
    department_id             smallint NOT NULL,
    office_id                 smallint NOT NULL,
    title_ru                  text NOT NULL,
    title_en                  text NOT NULL,
    published_at              timestamptz,
    bidding_opens_at          timestamptz,
    preview_from              date,
    preview_to                date,
    starts_at                 timestamptz NOT NULL,
    ends_at                   timestamptz,
    lots_per_group            smallint,
    group_interval_s          smallint,
    extension_window_s        smallint,
    extension_s               smallint,
    extension_cap_s           smallint,
    buyer_premium_schedule_id smallint NOT NULL,
    payment_due_days          smallint NOT NULL,
    status                    text NOT NULL
);
COMMENT ON TABLE sales IS 'Торги: формат, отдел, сроки, волна закрытий';
COMMENT ON COLUMN sales.id IS 'Торги; id — по starts_at';
COMMENT ON COLUMN sales.code IS 'Код: T-2026-0312 (онлайн), L-2026-014 (живые)';
COMMENT ON COLUMN sales.format IS 'Формат: живые в зале или онлайн по времени: live, timed';
COMMENT ON COLUMN sales.tier IS 'Вид: обычные, тематические (онлайн), главная неделя, вечерние: regular, thematic, major, evening';
COMMENT ON COLUMN sales.department_id IS 'Отдел';
COMMENT ON COLUMN sales.office_id IS 'Зал (живые) или Москва (онлайн)';
COMMENT ON COLUMN sales.title_ru IS 'Название по-русски';
COMMENT ON COLUMN sales.title_en IS 'Название по-английски';
COMMENT ON COLUMN sales.published_at IS 'Каталог опубликован; NULL — назначены, каталога ещё нет (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN sales.bidding_opens_at IS 'Открыты ставки и регистрация (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN sales.preview_from IS 'Выставка с (живые)';
COMMENT ON COLUMN sales.preview_to IS 'Выставка по (живые)';
COMMENT ON COLUMN sales.starts_at IS 'Живые — начало первой сессии; онлайн — плановое закрытие первой группы (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN sales.ends_at IS 'Закрылся последний лот; NULL — не завершены (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN sales.lots_per_group IS 'Онлайн: лотов в группе волны (5)';
COMMENT ON COLUMN sales.group_interval_s IS 'Онлайн: интервал групп, с (60)';
COMMENT ON COLUMN sales.extension_window_s IS 'Онлайн: окно мягкого закрытия, с (120)';
COMMENT ON COLUMN sales.extension_s IS 'Онлайн: продление, с (120)';
COMMENT ON COLUMN sales.extension_cap_s IS 'Онлайн: потолок продлений сверх плана, с (3600)';
COMMENT ON COLUMN sales.buyer_premium_schedule_id IS 'Шкала премии — по дате торгов, у монет BP-COIN';
COMMENT ON COLUMN sales.payment_due_days IS 'Срок оплаты, дней (10)';
COMMENT ON COLUMN sales.status IS 'На «сейчас»: scheduled, published, open, closed';

CREATE TABLE sale_sessions (
    sale_id       integer NOT NULL,
    session_no    smallint NOT NULL,
    starts_at     timestamptz NOT NULL,
    ended_at      timestamptz,
    first_lot_no  integer NOT NULL,
    last_lot_no   integer NOT NULL,
    auctioneer_id integer NOT NULL
);
COMMENT ON TABLE sale_sessions IS 'Сессии живых торгов';
COMMENT ON COLUMN sale_sessions.sale_id IS 'Торги';
COMMENT ON COLUMN sale_sessions.session_no IS 'Сессия: 1, 2, …';
COMMENT ON COLUMN sale_sessions.starts_at IS 'Начало (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN sale_sessions.ended_at IS 'Конец (последний молоток); NULL — не прошла (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN sale_sessions.first_lot_no IS 'Первый лот сессии';
COMMENT ON COLUMN sale_sessions.last_lot_no IS 'Последний лот сессии (не больше 250 лотов)';
COMMENT ON COLUMN sale_sessions.auctioneer_id IS 'Аукционист';

CREATE TABLE lots (
    id                          integer NOT NULL,
    sale_id                     integer NOT NULL,
    lot_no                      integer NOT NULL,
    lot_label                   text NOT NULL,
    item_id                     integer NOT NULL,
    consignment_id              integer NOT NULL,
    offering_no                 smallint NOT NULL,
    previous_lot_id             integer,
    session_no                  smallint,
    published_at                timestamptz NOT NULL,
    low_estimate                numeric(14,2) NOT NULL,
    high_estimate               numeric(14,2) NOT NULL,
    starting_price              numeric(14,2) NOT NULL,
    reserve                     numeric(14,2),
    closes_at_planned           timestamptz,
    opened_at                   timestamptz,
    closed_at                   timestamptz,
    closed_ms                   smallint,
    extensions                  smallint NOT NULL,
    bid_count                   integer NOT NULL,
    result                      text NOT NULL,
    hammer_price                numeric(14,2),
    winning_bid_id              bigint,
    buyer_registration_id       integer,
    underbidder_registration_id integer,
    after_sale_price            numeric(14,2),
    after_sale_at               timestamptz,
    withdrawn_at                timestamptz,
    withdrawn_by                text
);
COMMENT ON TABLE lots IS 'Лоты: выставление вещи на торги, итог';
COMMENT ON COLUMN lots.id IS 'Лот; id — по моменту публикации';
COMMENT ON COLUMN lots.sale_id IS 'Торги';
COMMENT ON COLUMN lots.lot_no IS 'Номер в каталоге';
COMMENT ON COLUMN lots.lot_label IS 'Как в каталоге: «123»; добавленный после публикации — «123A»';
COMMENT ON COLUMN lots.item_id IS 'Вещь';
COMMENT ON COLUMN lots.consignment_id IS 'По какому договору выставлена';
COMMENT ON COLUMN lots.offering_no IS '1 — впервые в этом договоре, 2–3 — повторно';
COMMENT ON COLUMN lots.previous_lot_id IS 'Прежнее выставление этой вещи (в любом договоре)';
COMMENT ON COLUMN lots.session_no IS 'Живые: сессия';
COMMENT ON COLUMN lots.published_at IS 'В каталоге (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN lots.low_estimate IS 'Нижняя оценка, руб.';
COMMENT ON COLUMN lots.high_estimate IS 'Верхняя оценка, руб.';
COMMENT ON COLUMN lots.starting_price IS 'Стартовая цена, руб.';
COMMENT ON COLUMN lots.reserve IS 'Конфиденциально: резерв, руб.; NULL — без резерва';
COMMENT ON COLUMN lots.closes_at_planned IS 'Онлайн: место в волне — плановый конец (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN lots.opened_at IS 'Живые: аукционист открыл лот; онлайн — открытие ставок (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN lots.closed_at IS 'Фактическое закрытие (онлайн — с продлениями, живые — молоток); NULL — не закрыт (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN lots.closed_ms IS 'Миллисекунда закрытия';
COMMENT ON COLUMN lots.extensions IS 'Продлений (живые — 0)';
COMMENT ON COLUMN lots.bid_count IS 'Принятых ставок (с автоставками и ставками в пользу продавца)';
COMMENT ON COLUMN lots.result IS 'Итог на «сейчас»: open, sold, bought_in, withdrawn, sold_after_sale';
COMMENT ON COLUMN lots.hammer_price IS 'Молоток, руб. — только sold';
COMMENT ON COLUMN lots.winning_bid_id IS 'Ставка-победитель — только sold';
COMMENT ON COLUMN lots.buyer_registration_id IS 'Покупатель — sold, sold_after_sale';
COMMENT ON COLUMN lots.underbidder_registration_id IS 'Вторая ставка';
COMMENT ON COLUMN lots.after_sale_price IS 'Продажа после торгов: цена, руб.';
COMMENT ON COLUMN lots.after_sale_at IS 'Продажа после торгов: момент (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN lots.withdrawn_at IS 'Снят (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN lots.withdrawn_by IS 'Кто снял: consignor, house';

CREATE TABLE registrations (
    id                  integer NOT NULL,
    sale_id             integer NOT NULL,
    client_id           integer NOT NULL,
    paddle_no           integer NOT NULL,
    registered_at       timestamptz NOT NULL,
    via                 text NOT NULL,
    mode                text NOT NULL,
    status              text NOT NULL,
    decided_at          timestamptz,
    decided_by_id       integer,
    reject_reason       text,
    bid_limit           numeric(14,2),
    premium_approved_at timestamptz,
    deposit_required    numeric(14,2)
);
COMMENT ON TABLE registrations IS 'Регистрации на торги: табличка, лимит, депозит';
COMMENT ON COLUMN registrations.id IS 'Регистрация; id — по моменту';
COMMENT ON COLUMN registrations.sale_id IS 'Торги';
COMMENT ON COLUMN registrations.client_id IS 'Клиент';
COMMENT ON COLUMN registrations.paddle_no IS 'Номер таблички: зал 1–1999, телефон и заочные 2000–2999, онлайн (и на живых, и на онлайн-торгах) 10000–99999';
COMMENT ON COLUMN registrations.registered_at IS 'Подана (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN registrations.via IS 'Откуда: site, app, phone, office, partner_platform';
COMMENT ON COLUMN registrations.mode IS 'Заявленный способ участия (онлайн-торги — online): room, phone, online, absentee';
COMMENT ON COLUMN registrations.status IS 'На «сейчас»: approved, pending, rejected, revoked';
COMMENT ON COLUMN registrations.decided_at IS 'Решение (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN registrations.decided_by_id IS 'Кто решил; NULL — автоматически';
COMMENT ON COLUMN registrations.reject_reason IS 'Причина отказа: no_verification, unpaid_invoice, blocked';
COMMENT ON COLUMN registrations.bid_limit IS 'Лимит на торги (T11), руб.; NULL — без лимита';
COMMENT ON COLUMN registrations.premium_approved_at IS 'Допуск к дорогим лотам (T13) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN registrations.deposit_required IS 'Депозит за дорогие лоты, руб.';

CREATE TABLE bid_orders (
    id              integer NOT NULL,
    lot_id          integer NOT NULL,
    registration_id integer NOT NULL,
    kind            text NOT NULL,
    revision        smallint NOT NULL,
    replaces_id     integer,
    max_amount      numeric(14,2),
    placed_at       timestamptz NOT NULL,
    placed_ms       smallint NOT NULL,
    via             text NOT NULL,
    revision_reason text,
    status          text NOT NULL,
    ended_at        timestamptz,
    phone_outcome   text,
    clerk_id        integer
);
COMMENT ON TABLE bid_orders IS 'Поручения и их редакции: максимум (онлайн), заочная, телефон со страховочной';
COMMENT ON COLUMN bid_orders.id IS 'Редакция поручения; id — по моменту подачи';
COMMENT ON COLUMN bid_orders.lot_id IS 'Лот';
COMMENT ON COLUMN bid_orders.registration_id IS 'Регистрация участника';
COMMENT ON COLUMN bid_orders.kind IS 'Вид: максимум (онлайн), заочная (живые), телефон (максимум — страховочная): auto, absentee, phone';
COMMENT ON COLUMN bid_orders.revision IS 'Редакция: 1 — первая; каждая следующая — +1';
COMMENT ON COLUMN bid_orders.replaces_id IS 'Предыдущая редакция того же участника на этом лоте';
COMMENT ON COLUMN bid_orders.max_amount IS 'Максимум (точка сетки, T4), руб.; NULL — телефон без страховочной';
COMMENT ON COLUMN bid_orders.placed_at IS 'Подано (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN bid_orders.placed_ms IS 'Миллисекунда';
COMMENT ON COLUMN bid_orders.via IS 'Откуда: site, app, phone, office, partner_platform';
COMMENT ON COLUMN bid_orders.revision_reason IS 'У редакций ≥ 2: лидер поднял максимум; прежний исчерпан — новый: raise_leading, rebid_after_outbid';
COMMENT ON COLUMN bid_orders.status IS 'На «сейчас»: active, won, outbid, exceeded_limit, superseded, cancelled, rejected, unused';
COMMENT ON COLUMN bid_orders.ended_at IS 'Когда статус стал окончательным (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN bid_orders.phone_outcome IS 'Телефон: дозвонились или нет (страховочная — из книги, T8): connected, no_answer';
COMMENT ON COLUMN bid_orders.clerk_id IS 'Сотрудник на линии';

CREATE TABLE bids (
    id              bigint NOT NULL,
    lot_id          integer NOT NULL,
    seq             integer NOT NULL,
    registration_id integer,
    channel         text NOT NULL,
    kind            text NOT NULL,
    bid_order_id    integer,
    amount          numeric(14,2) NOT NULL,
    placed_at       timestamptz NOT NULL,
    placed_ms       smallint NOT NULL,
    accepted        boolean NOT NULL,
    reject_reason   text,
    extended_close  boolean NOT NULL,
    request_key     text,
    clerk_id        integer
);
COMMENT ON TABLE bids IS 'Все попытки ставок — принятые и отклонённые, с миллисекундой и причиной';
COMMENT ON COLUMN bids.id IS 'Ставка; id — по (placed_at, placed_ms, lot_id, seq)';
COMMENT ON COLUMN bids.lot_id IS 'Лот';
COMMENT ON COLUMN bids.seq IS 'Порядок поступления в лоте, с 1 (T15)';
COMMENT ON COLUMN bids.registration_id IS 'Регистрация; NULL — в пользу продавца';
COMMENT ON COLUMN bids.channel IS 'Откуда: online, room, phone, absentee, auctioneer';
COMMENT ON COLUMN bids.kind IS 'Как: руками, поставлен максимум, автоставка системы, заочная из книги, в пользу продавца: manual, max, auto, absentee, vendor';
COMMENT ON COLUMN bids.bid_order_id IS 'По какому поручению';
COMMENT ON COLUMN bids.amount IS 'Сумма, руб.';
COMMENT ON COLUMN bids.placed_at IS 'Момент (секунды, вниз) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN bids.placed_ms IS 'Миллисекунда: точный момент = placed_at + placed_ms/1000';
COMMENT ON COLUMN bids.accepted IS 'Принята';
COMMENT ON COLUMN bids.reject_reason IS 'Причина отказа: от движка или от проверок до движка: below_minimum, price_changed, race_lost, tied, already_leading, after_close, off_increment, over_limit, own_lot, not_registered, blocked, premium_not_approved, not_open, lot_withdrawn, duplicate';
COMMENT ON COLUMN bids.extended_close IS 'Сдвинула конец онлайн-лота (T10)';
COMMENT ON COLUMN bids.request_key IS 'Ключ запроса ручной онлайн-ставки (32 hex); повтор — тот же ключ';
COMMENT ON COLUMN bids.clerk_id IS 'Сотрудник: телефон — на линии; заочная, полшага, прыжок, за продавца — аукционист';

CREATE TABLE lot_events (
    lot_id       integer NOT NULL,
    seq          smallint NOT NULL,
    at           timestamptz NOT NULL,
    at_ms        smallint NOT NULL,
    kind         text NOT NULL,
    bid_id       bigint,
    new_close_at timestamptz,
    new_close_ms smallint,
    price        numeric(14,2),
    staff_id     integer
);
COMMENT ON TABLE lot_events IS 'Журнал лота: публикация, открытие, резерв достигнут, продление, предупреждение, итог';
COMMENT ON COLUMN lot_events.lot_id IS 'Лот';
COMMENT ON COLUMN lot_events.seq IS 'Порядок в лоте';
COMMENT ON COLUMN lot_events.at IS 'Момент (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN lot_events.at_ms IS 'Миллисекунда';
COMMENT ON COLUMN lot_events.kind IS 'Событие: published, opened, reserve_met, extended, extension_capped, fair_warning, sold, bought_in, withdrawn, after_sale';
COMMENT ON COLUMN lot_events.bid_id IS 'Ставка-причина (reserve_met, extended, sold)';
COMMENT ON COLUMN lot_events.new_close_at IS 'extended: новый конец (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN lot_events.new_close_ms IS 'extended: его миллисекунда';
COMMENT ON COLUMN lot_events.price IS 'sold, after_sale, reserve_met — цена в момент события, руб.';
COMMENT ON COLUMN lot_events.staff_id IS 'Аукционист (живые)';

CREATE TABLE item_status_log (
    item_id        integer NOT NULL,
    seq            smallint NOT NULL,
    at             timestamptz NOT NULL,
    status         text NOT NULL,
    lot_id         integer,
    consignment_id integer
);
COMMENT ON TABLE item_status_log IS 'Журнал вещи: от приёма до передачи покупателю или возврата';
COMMENT ON COLUMN item_status_log.item_id IS 'Вещь';
COMMENT ON COLUMN item_status_log.seq IS 'Порядок';
COMMENT ON COLUMN item_status_log.at IS 'Момент (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN item_status_log.status IS 'Состояние: received, declined, accepted, catalogued, sold, bought_in, withdrawn, after_sale, buyer_paid, buyer_default, released, reoffer_planned, returned, resale_received';
COMMENT ON COLUMN item_status_log.lot_id IS 'Лот перехода';
COMMENT ON COLUMN item_status_log.consignment_id IS 'Договор перехода';

-- ======================================================================
-- Деньги

CREATE TABLE invoices (
    id                 integer NOT NULL,
    number             text NOT NULL,
    sale_id            integer NOT NULL,
    registration_id    integer NOT NULL,
    client_id          integer NOT NULL,
    kind               text NOT NULL,
    issued_at          timestamptz NOT NULL,
    due_at             timestamptz NOT NULL,
    hammer_total       numeric(14,2) NOT NULL,
    premium_total      numeric(14,2) NOT NULL,
    platform_fee_total numeric(14,2) NOT NULL,
    vat_total          numeric(14,2) NOT NULL,
    total              numeric(14,2) NOT NULL,
    late_fee           numeric(14,2) NOT NULL,
    paid_total         numeric(14,2) NOT NULL,
    status             text NOT NULL,
    paid_at            timestamptz,
    cancelled_at       timestamptz,
    cancel_reason      text,
    related_invoice_id integer
);
COMMENT ON TABLE invoices IS 'Счета покупателям';
COMMENT ON COLUMN invoices.id IS 'Счёт; id — по моменту выставления';
COMMENT ON COLUMN invoices.number IS 'Номер: INV-2026-000123';
COMMENT ON COLUMN invoices.sale_id IS 'Торги';
COMMENT ON COLUMN invoices.registration_id IS 'Покупатель в этих торгах';
COMMENT ON COLUMN invoices.client_id IS 'Клиент';
COMMENT ON COLUMN invoices.kind IS 'Вид: по торгам, после торгов, второй ставке, недостача, переиздание: sale, after_sale, second_chance, shortfall, reissue';
COMMENT ON COLUMN invoices.issued_at IS 'Выставлен (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN invoices.due_at IS 'Срок оплаты (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN invoices.hammer_total IS 'Итог строк: цены, руб.';
COMMENT ON COLUMN invoices.premium_total IS 'Итог строк: премия, руб.';
COMMENT ON COLUMN invoices.platform_fee_total IS 'Итог строк: сбор партнёрской площадки, руб.';
COMMENT ON COLUMN invoices.vat_total IS 'Итог строк: НДС, руб.';
COMMENT ON COLUMN invoices.total IS 'Итого по строкам (M3), руб.';
COMMENT ON COLUMN invoices.late_fee IS 'Пени на «сейчас» (M6), руб.';
COMMENT ON COLUMN invoices.paid_total IS 'Оплачено на «сейчас», руб.';
COMMENT ON COLUMN invoices.status IS 'На «сейчас»: issued, part_paid, paid, overdue, cancelled';
COMMENT ON COLUMN invoices.paid_at IS 'Полная оплата (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN invoices.cancelled_at IS 'Отменён (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN invoices.cancel_reason IS 'Причина отмены: non_payment, buyer_withdrew, house_error';
COMMENT ON COLUMN invoices.related_invoice_id IS 'У reissue — заменённый счёт, у second_chance и shortfall — отменённый счёт неплательщика';

CREATE TABLE invoice_lines (
    invoice_id          integer NOT NULL,
    line_no             smallint NOT NULL,
    lot_id              integer NOT NULL,
    price               numeric(14,2) NOT NULL,
    premium_schedule_id smallint NOT NULL,
    premium             numeric(14,2) NOT NULL,
    platform_fee        numeric(14,2) NOT NULL,
    vat_rate            numeric(5,2) NOT NULL,
    vat                 numeric(14,2) NOT NULL,
    line_total          numeric(14,2) NOT NULL
);
COMMENT ON TABLE invoice_lines IS 'Строки счёта: цена, премия, сбор площадки, НДС';
COMMENT ON COLUMN invoice_lines.invoice_id IS 'Счёт';
COMMENT ON COLUMN invoice_lines.line_no IS 'Строка';
COMMENT ON COLUMN invoice_lines.lot_id IS 'Лот';
COMMENT ON COLUMN invoice_lines.price IS 'Молоток, цена после торгов или второй ставки; у shortfall — недостача, руб.';
COMMENT ON COLUMN invoice_lines.premium_schedule_id IS 'Шкала премии';
COMMENT ON COLUMN invoice_lines.premium IS 'Премия по ступеням (M1); у shortfall — 0, руб.';
COMMENT ON COLUMN invoice_lines.platform_fee IS 'Сбор партнёрской площадки (0 — своя), руб.';
COMMENT ON COLUMN invoice_lines.vat_rate IS 'Ставка НДС, %: 20,00 или 22,00';
COMMENT ON COLUMN invoice_lines.vat IS 'НДС на премию и сбор (M2), руб.';
COMMENT ON COLUMN invoice_lines.line_total IS 'Итог строки (M3), руб.';

CREATE TABLE payments (
    id              integer NOT NULL,
    client_id       integer NOT NULL,
    kind            text NOT NULL,
    method          text NOT NULL,
    invoice_id      integer,
    registration_id integer,
    settlement_id   integer,
    amount          numeric(14,2) NOT NULL,
    at              timestamptz NOT NULL,
    status          text NOT NULL,
    failure_reason  text,
    provider_ref    text
);
COMMENT ON TABLE payments IS 'Платежи, депозиты, возвраты, выплаты комитентам, неудачные попытки';
COMMENT ON COLUMN payments.id IS 'Платёж; id — по моменту';
COMMENT ON COLUMN payments.client_id IS 'Клиент';
COMMENT ON COLUMN payments.kind IS 'Вид (направление — по виду): deposit, deposit_applied, deposit_refund, deposit_forfeit, invoice, refund, payout';
COMMENT ON COLUMN payments.method IS 'Способ: card, sbp, bank_transfer, cash, deposit';
COMMENT ON COLUMN payments.invoice_id IS 'Счёт';
COMMENT ON COLUMN payments.registration_id IS 'Регистрация — депозиты';
COMMENT ON COLUMN payments.settlement_id IS 'Отчёт комитенту — выплаты';
COMMENT ON COLUMN payments.amount IS 'Сумма, руб.';
COMMENT ON COLUMN payments.at IS 'Момент (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN payments.status IS 'Прошёл или нет: succeeded, failed';
COMMENT ON COLUMN payments.failure_reason IS 'Причина неудачи: declined, insufficient_funds, timeout, limit';
COMMENT ON COLUMN payments.provider_ref IS 'Номер операции провайдера (карта, СБП)';

CREATE TABLE settlements (
    id               integer NOT NULL,
    sale_id          integer NOT NULL,
    consignor_id     integer NOT NULL,
    created_at       timestamptz NOT NULL,
    hammer_total     numeric(14,2) NOT NULL,
    commission_total numeric(14,2) NOT NULL,
    fees_total       numeric(14,2) NOT NULL,
    vat_total        numeric(14,2) NOT NULL,
    net_total        numeric(14,2) NOT NULL,
    paid_total       numeric(14,2) NOT NULL,
    status           text NOT NULL
);
COMMENT ON TABLE settlements IS 'Отчёты комитентам по торгам';
COMMENT ON COLUMN settlements.id IS 'Отчёт; id — по created_at';
COMMENT ON COLUMN settlements.sale_id IS 'Торги';
COMMENT ON COLUMN settlements.consignor_id IS 'Комитент';
COMMENT ON COLUMN settlements.created_at IS 'Сформирован (следующий рабочий день после торгов) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN settlements.hammer_total IS 'Итог строк на «сейчас»: цены, руб.';
COMMENT ON COLUMN settlements.commission_total IS 'Итог строк на «сейчас»: комиссия, руб.';
COMMENT ON COLUMN settlements.fees_total IS 'Итог строк на «сейчас»: сборы, руб.';
COMMENT ON COLUMN settlements.vat_total IS 'Итог строк на «сейчас»: НДС, руб.';
COMMENT ON COLUMN settlements.net_total IS 'К выплате по строкам на «сейчас», руб.; может быть < 0';
COMMENT ON COLUMN settlements.paid_total IS 'Выплачено на «сейчас», руб.';
COMMENT ON COLUMN settlements.status IS 'На «сейчас»: awaiting_buyers, part_paid, paid, owed_by_consignor';

CREATE TABLE settlement_lines (
    settlement_id  integer NOT NULL,
    line_no        smallint NOT NULL,
    lot_id         integer NOT NULL,
    outcome        text NOT NULL,
    price          numeric(14,2),
    commission     numeric(14,2) NOT NULL,
    insurance_fee  numeric(14,2) NOT NULL,
    catalogue_fee  numeric(14,2) NOT NULL,
    withdrawal_fee numeric(14,2) NOT NULL,
    vat            numeric(14,2) NOT NULL,
    net            numeric(14,2) NOT NULL,
    payable_at     timestamptz,
    paid_at        timestamptz
);
COMMENT ON TABLE settlement_lines IS 'Строки отчёта: лот, комиссия, сборы, НДС, выплата — итог лота на «сейчас»';
COMMENT ON COLUMN settlement_lines.settlement_id IS 'Отчёт';
COMMENT ON COLUMN settlement_lines.line_no IS 'Строка';
COMMENT ON COLUMN settlement_lines.lot_id IS 'Лот';
COMMENT ON COLUMN settlement_lines.outcome IS 'Итог лота для комитента на «сейчас»: sold, sold_after_sale, second_chance, bought_in, withdrawn, buyer_default';
COMMENT ON COLUMN settlement_lines.price IS 'Цена продажи на «сейчас», руб.';
COMMENT ON COLUMN settlement_lines.commission IS 'Комиссия (M4), руб.';
COMMENT ON COLUMN settlement_lines.insurance_fee IS 'Страхование, руб.';
COMMENT ON COLUMN settlement_lines.catalogue_fee IS 'Каталог и фото, руб.';
COMMENT ON COLUMN settlement_lines.withdrawal_fee IS 'Сбор за снятие, руб.';
COMMENT ON COLUMN settlement_lines.vat IS 'НДС на удержания (M5), руб.';
COMMENT ON COLUMN settlement_lines.net IS 'К выплате (M5; у непроданных — минус), руб.';
COMMENT ON COLUMN settlement_lines.payable_at IS 'Когда положена выплата (M7); NULL — лот не оплачен (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN settlement_lines.paid_at IS 'Выплачено (платёж payout) (в TSV — секунды Unix, UTC)';

-- ======================================================================
-- Сайт и уведомления

CREATE TABLE watchlist (
    client_id  integer NOT NULL,
    lot_id     integer NOT NULL,
    added_at   timestamptz NOT NULL,
    removed_at timestamptz,
    via        text NOT NULL
);
COMMENT ON TABLE watchlist IS 'Наблюдение за лотами';
COMMENT ON COLUMN watchlist.client_id IS 'Клиент';
COMMENT ON COLUMN watchlist.lot_id IS 'Лот';
COMMENT ON COLUMN watchlist.added_at IS 'Добавил (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN watchlist.removed_at IS 'Убрал (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN watchlist.via IS 'Откуда: site, app';

CREATE TABLE sessions (
    id         bigint NOT NULL,
    client_id  integer,
    device_key text NOT NULL,
    device     text NOT NULL,
    source     text NOT NULL,
    city_id    integer,
    started_at timestamptz NOT NULL,
    ended_at   timestamptz NOT NULL
);
COMMENT ON TABLE sessions IS 'Визиты сайта и приложения — посетители с согласием на аналитику';
COMMENT ON COLUMN sessions.id IS 'Визит; id — по started_at';
COMMENT ON COLUMN sessions.client_id IS 'Клиент; NULL — не вошёл';
COMMENT ON COLUMN sessions.device_key IS 'Отпечаток устройства (16 hex), постоянный у устройства';
COMMENT ON COLUMN sessions.device IS 'Устройство: desktop, mobile_web, app';
COMMENT ON COLUMN sessions.source IS 'Откуда пришёл: direct, search, email, push, referral';
COMMENT ON COLUMN sessions.city_id IS 'Город по адресу сети';
COMMENT ON COLUMN sessions.started_at IS 'Начало (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN sessions.ended_at IS 'Конец (в TSV — секунды Unix, UTC)';

CREATE TABLE session_events (
    session_id   bigint NOT NULL,
    seq          smallint NOT NULL,
    at           timestamptz NOT NULL,
    kind         text NOT NULL,
    sale_id      integer,
    lot_id       integer,
    query        text,
    results      integer,
    page         smallint,
    bid_id       bigint,
    bid_order_id integer
);
COMMENT ON TABLE session_events IS 'События визита: поиск, страница каталога, просмотр лота, наблюдение, ставка';
COMMENT ON COLUMN session_events.session_id IS 'Визит';
COMMENT ON COLUMN session_events.seq IS 'Порядок';
COMMENT ON COLUMN session_events.at IS 'Момент (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN session_events.kind IS 'Событие: search, catalogue_page, lot_view, watch_add, watch_remove, bid_form, bid_submit, order_set';
COMMENT ON COLUMN session_events.sale_id IS 'Торги';
COMMENT ON COLUMN session_events.lot_id IS 'Лот';
COMMENT ON COLUMN session_events.query IS 'search: запрос, как набран';
COMMENT ON COLUMN session_events.results IS 'search: найдено';
COMMENT ON COLUMN session_events.page IS 'catalogue_page: страница (по 20)';
COMMENT ON COLUMN session_events.bid_id IS 'bid_submit: ставка';
COMMENT ON COLUMN session_events.bid_order_id IS 'order_set: поручение';

CREATE TABLE notifications (
    id           bigint NOT NULL,
    client_id    integer NOT NULL,
    kind         text NOT NULL,
    channel      text NOT NULL,
    event_key    text NOT NULL,
    sale_id      integer,
    lot_id       integer,
    invoice_id   integer,
    created_at   timestamptz NOT NULL,
    sent_at      timestamptz,
    delivered_at timestamptz,
    attempts     smallint NOT NULL,
    status       text NOT NULL
);
COMMENT ON TABLE notifications IS 'Уведомления: перебита ставка, лот закрывается, выигрыш, счёт, оплата, выплата';
COMMENT ON COLUMN notifications.id IS 'Уведомление; id — по created_at';
COMMENT ON COLUMN notifications.client_id IS 'Получатель';
COMMENT ON COLUMN notifications.kind IS 'Вид: registration_decided, outbid, closing_soon, lot_won, sale_summary, invoice_issued, payment_reminder, payment_received, invoice_cancelled, deposit_refunded, settlement_ready, settlement_paid';
COMMENT ON COLUMN notifications.channel IS 'Канал: email, push';
COMMENT ON COLUMN notifications.event_key IS 'Ключ события-источника (outbid:<лот>:<ставка>) — для идемпотентного потребителя';
COMMENT ON COLUMN notifications.sale_id IS 'Торги';
COMMENT ON COLUMN notifications.lot_id IS 'Лот';
COMMENT ON COLUMN notifications.invoice_id IS 'Счёт';
COMMENT ON COLUMN notifications.created_at IS 'Событие поставлено в очередь (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN notifications.sent_at IS 'Отправлено (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN notifications.delivered_at IS 'Доставлено (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN notifications.attempts IS 'Попыток отправки';
COMMENT ON COLUMN notifications.status IS 'На «сейчас»: доставлено, ошибка, подавлено (тихие часы push 23:00–08:00 по местному, отписка), в очереди: delivered, failed, suppressed, pending';
