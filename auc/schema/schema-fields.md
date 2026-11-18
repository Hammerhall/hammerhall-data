# auc-data-v1 — поля

### Дом и правила

#### `departments` — Отделы дома

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | smallint |  | Отдел | генератор |
| `code` | text |  | Код отдела: painting, jewelry, coins, books, decorative, collectibles | генератор |
| `name_ru` | text |  | Название по-русски | генератор |
| `name_en` | text |  | Название по-английски | генератор |
| `sort_order` | smallint |  | Порядок в каталоге | генератор |

#### `categories` — Категории — разделы отдела и подразделы, дерево до 3 уровней

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Категория | генератор |
| `department_id` | smallint → `departments` |  | Отдел (у ребёнка — тот же, что у родителя) | генератор |
| `parent_id` | int → `categories` | да | Родительская категория; NULL — верхний уровень отдела | генератор |
| `level` | smallint |  | Уровень: 1 — раздел отдела, 2–3 — подразделы | генератор |
| `code` | text |  | Код категории (латиница) | генератор |
| `name_ru` | text |  | Название по-русски | генератор |
| `name_en` | text |  | Название по-английски | генератор |

#### `cities` — Города — как в sto-data-v1 (те же id): набор соединяется с «Кременъ» по городу

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Город (id sto-data-v1) | sto-data-v1 cities.id |
| `name_ru` | text |  | Название по-русски | sto-data-v1 cities.name_ru |
| `name_en` | text |  | Название по-английски | sto-data-v1 cities.name_en |
| `region_ru` | text |  | Субъект РФ | sto-data-v1 cities.region_ru |
| `federal_district` | text |  | Федеральный округ: `central`, `northwest`, `south`, `north_caucasus`, `volga`, `ural`, `siberia`, `far_east` | sto-data-v1 cities.federal_district |
| `lat` | double |  | Широта центра | sto-data-v1 cities.lat |
| `lon` | double |  | Долгота центра | sto-data-v1 cities.lon |
| `timezone` | text |  | Часовой пояс IANA — местное время участников | sto-data-v1 cities.timezone |
| `population` | int |  | Население, оценка — вес клиентов по городам | sto-data-v1 cities.population |

#### `offices` — Офисы: главный, залы торгов, приёмные

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | smallint |  | Офис | генератор |
| `code` | text |  | Код: msk, spb, kzn, nnv, ekb, nsk, krd, vvo | генератор |
| `kind` | text |  | Вид: `headquarters`, `saleroom`, `intake` | генератор |
| `city_id` | int → `cities` |  | Город | генератор |
| `name_ru` | text |  | Название по-русски | генератор |
| `name_en` | text |  | Название по-английски | генератор |
| `has_saleroom` | bool |  | Есть зал торгов | генератор |
| `opened_on` | date |  | Открыт | генератор |
| `closed_on` | date | да | Закрыт; NULL — работает | генератор |

#### `staff` — Сотрудники и их роли (имена выдуманные)

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Сотрудник; id — по дате приёма | генератор |
| `office_id` | smallint → `offices` |  | Офис | генератор |
| `department_id` | smallint → `departments` | да | Отдел; NULL — не за отделом | генератор |
| `role` | text |  | Роль: `specialist`, `head_of_department`, `auctioneer`, `phone_clerk`, `client_service`, `accountant`, `administrator` | генератор |
| `first_name` | text |  | Имя | генератор |
| `last_name` | text |  | Фамилия | генератор |
| `hired_on` | date |  | Принят | генератор |
| `left_on` | date | да | Уволился; NULL — работает | генератор |

#### `increment_bands` — Таблица шагов ставки: от текущей цены — шаг (T1)

Ключ — `valid_from, from_amount`; строки упорядочены по `valid_from, from_amount`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `valid_from` | date |  | Версия таблицы шагов (v1 — одна, 2024-09-01) | генератор |
| `from_amount` | money |  | Цена от (включительно) — полоса до следующего порога, руб. | генератор |
| `step` | money |  | Шаг ставки в полосе, руб. | генератор |

#### `fee_schedules` — Шкалы и сборы: премия, комиссия, площадка, страхование, каталог, снятие, пени, депозит

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | smallint |  | Шкала или сбор | генератор |
| `code` | text |  | Код: BP-2024, BP-2026, BP-COIN, SC-2024, PLT-2024, INS-2024, CAT-LIVE, CAT-TIMED, WDR-2024, LATE-2024, DEP-2024 | генератор |
| `kind` | text |  | Вид: `buyer_premium`, `seller_commission`, `platform`, `insurance`, `catalogue`, `withdrawal`, `late_payment`, `deposit` | генератор |
| `basis` | text |  | От чего считается: `hammer`, `low_estimate`, `lot`, `invoice_total_per_day` | генератор |
| `applies_to` | text |  | Формат торгов: `all`, `live`, `timed` | генератор |
| `department_id` | smallint → `departments` | да | Только для отдела (BP-COIN); NULL — все | генератор |
| `valid_from` | date |  | Действует с | генератор |
| `valid_to` | date | да | Последний день действия (включительно); NULL — действует | генератор |
| `rate_pct` | decimal | да | Единая ставка, % (у пеней — в день); NULL — ступени в fee_tiers или фиксированная сумма | генератор |
| `fixed_amount` | money | да | Фиксированная сумма за лот, руб. | генератор |
| `name_ru` | text |  | Название для отчётов | генератор |

#### `fee_tiers` — Ступени премии и комиссии: ставка — на часть суммы от from_amount (не включая) до начала следующей ступени (включая)

Ключ — `schedule_id, tier_no`; строки упорядочены по `schedule_id, tier_no`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `schedule_id` | smallint → `fee_schedules` |  | Шкала (ступенчатые: BP-2024, BP-2026, SC-2024) | генератор |
| `tier_no` | smallint |  | Ступень: 1, 2, … | генератор |
| `from_amount` | money |  | Начало части, руб. | генератор |
| `rate_pct` | decimal |  | Ставка, % (скидка торговца — правило M4, не строка) | генератор |

### Мастера и вещи

#### `makers` — Мастера, мастерские, мануфактуры, монетные дворы, издательства — все выдуманные

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Мастер | генератор |
| `kind` | text |  | Вид: `artist`, `photographer`, `sculptor`, `jeweler`, `watchmaker`, `mint`, `issuer`, `medalist`, `author`, `publisher`, `printer`, `cartographer`, `porcelain`, `silversmith`, `furniture`, `glass`, `carpet`, `foundry`, `postal`, `toymaker` | генератор |
| `department_id` | smallint → `departments` |  | Отдел вида | генератор |
| `is_person` | bool |  | Человек (иначе — мастерская, мануфактура, двор) | генератор |
| `name_ru` | text |  | Имя для каталога | генератор |
| `name_en` | text |  | Имя по-английски | генератор |
| `first_name` | text | да | Имя (у людей) | генератор |
| `patronymic` | text | да | Отчество (у людей) | генератор |
| `last_name` | text | да | Фамилия (у людей) | генератор |
| `active_from` | smallint |  | Год рождения или основания | генератор |
| `active_to` | smallint | да | Год смерти или закрытия; NULL — жив, работает | генератор |
| `country` | text |  | Страна (код словаря) | генератор |
| `city_ru` | text | да | Город; NULL — у выдуманного монетного двора | генератор |
| `school_ru` | text | да | Направление (художники, скульпторы) | генератор |
| `specialty_ru` | text | да | Специализация; у мастерских — чем известна | генератор |
| `mark_ru` | text | да | Подпись, монограмма, клеймо, знак двора — как на предмете | генератор |
| `tier` | text |  | Уровень рынка: A — имена, ради которых приходят на торги, … D: `A`, `B`, `C`, `D` | генератор |

#### `items` — Вещи — факт приёма с описанием; при продлении периода не меняются

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Вещь; id — по моменту приёма | генератор |
| `consignor_id` | int → `clients` |  | Кто принёс (при первом приёме) | генератор |
| `office_id` | smallint → `offices` |  | Где принята | генератор |
| `specialist_id` | int → `staff` |  | Кто смотрел | генератор |
| `received_at` | moment |  | Принята на осмотр | генератор |
| `decided_at` | moment |  | Решение о приёме | генератор |
| `accepted` | bool |  | Принята на комиссию | генератор |
| `decline_reason` | text | да | Причина отказа — только у непринятых: `low_value`, `condition`, `authenticity_doubt`, `duplicate` | генератор |
| `department_id` | smallint → `departments` |  | Отдел | генератор |
| `category_id` | int → `categories` |  | Категория — лист дерева | генератор |
| `maker_id` | int → `makers` | да | Мастер; NULL — неизвестен | генератор |
| `attribution` | text |  | Атрибуция, как в каталогах: работа мастера, приписывается, мастерская, круг, последователь, в манере, по оригиналу, неизвестный мастер: `by`, `attributed`, `workshop`, `circle`, `follower`, `manner`, `after`, `unknown` | генератор |
| `title_ru` | text |  | Заголовок лота по-русски, 20–120 знаков | генератор |
| `title_en` | text |  | Заголовок лота по-английски | генератор |
| `description_ru` | text |  | Описание, 200–1 200 знаков | генератор |
| `condition_ru` | text |  | Состояние словами, 30–400 знаков | генератор |
| `condition_grade` | text |  | Состояние по шкале раздела: общая excellent … poor; монеты и медали unc … vg; марки mnh, mh, used: `excellent`, `very_good`, `good`, `fair`, `poor`, `unc`, `au`, `xf`, `vf`, `f`, `vg`, `mnh`, `mh`, `used` | генератор |
| `dating_ru` | text | да | Датировка, как в каталоге | генератор |
| `year_from` | smallint | да | Год создания — начало диапазона датировки | генератор |
| `year_to` | smallint | да | Год создания — конец диапазона | генератор |
| `origin_place_ru` | text | да | Место создания | генератор |
| `material_ru` | text | да | Материал | генератор |
| `technique_ru` | text | да | Техника | генератор |
| `height_mm` | int | да | Высота, мм | генератор |
| `width_mm` | int | да | Ширина, мм | генератор |
| `depth_mm` | int | да | Глубина, мм | генератор |
| `diameter_mm` | decimal | да | Диаметр, мм (монеты, медали, часы, тарелки) | генератор |
| `weight_g` | decimal | да | Вес, г | генератор |
| `metal_fineness` | smallint | да | Проба металла, метрическая: 375 … 999 | генератор |
| `pieces` | smallint |  | Предметов в вещи (комплект, лот марок) | генератор |
| `signed` | bool | да | Подпись, клеймо или марка мастера есть | генератор |

#### `provenance` — Происхождение вещи — звенья цепочки, литература, выставки

Ключ — `item_id, seq`; строки упорядочены по `item_id, seq`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `item_id` | int → `items` |  | Вещь | генератор |
| `seq` | smallint |  | Порядок: 1 — самое раннее звено | генератор |
| `kind` | text |  | Вид звена; chain — вся цепочка одной записью: `by_descent`, `private_collection`, `acquired`, `auction`, `gallery`, `exhibition`, `literature`, `expertise`, `chain` | генератор |
| `text_ru` | text |  | Запись, как в каталоге, 30–300 знаков (chain — до 1 000) | генератор |
| `year_from` | smallint | да | Год начала звена | генератор |
| `year_to` | smallint | да | Год конца звена | генератор |
| `place_ru` | text | да | Город или страна | генератор |
| `owner_name_ru` | text | да | Владелец, собрание, галерея, издание (выдуманные) | генератор |

#### `consignments` — Договоры комиссии (и сдача купленного обратно)

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Договор; id — по моменту подписания | генератор |
| `consignor_id` | int → `clients` |  | Комитент | генератор |
| `office_id` | smallint → `offices` |  | Офис | генератор |
| `specialist_id` | int → `staff` |  | Специалист | генератор |
| `kind` | text |  | Вид: частный, торговец, наследство, учреждение, сдача купленного в доме: `private`, `dealer`, `estate`, `institution`, `resale` | генератор |
| `signed_at` | moment |  | Подписан | генератор |
| `commission_schedule_id` | smallint → `fee_schedules` |  | Шкала комиссии (SC-2024) | генератор |
| `commission_rate` | decimal | да | Договорная единая ставка, % (5–10); NULL — по шкале | генератор |
| `dealer_discount` | bool |  | Скидка торговца 5 п. п. (M4) | генератор |
| `unsold_policy` | text |  | Что делать с непроданным: `return`, `reoffer`, `ask` | генератор |
| `expires_on` | date |  | Срок договора (12 месяцев) | генератор |

#### `consignment_items` — Вещи в договоре: условия приёма, итог

Ключ — `consignment_id, line_no`; строки упорядочены по `consignment_id, line_no`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `consignment_id` | int → `consignments` |  | Договор | генератор |
| `line_no` | smallint |  | Строка договора | генератор |
| `item_id` | int → `items` |  | Вещь (только принятые) | генератор |
| `agreed_low` | money |  | Нижняя оценка при приёме, руб. | генератор |
| `agreed_high` | money |  | Верхняя оценка при приёме, руб. | генератор |
| `agreed_reserve` | money | да | Резерв по договору, руб.; NULL — без резерва | генератор |
| `ended_at` | moment | да | Вещь вышла из договора; NULL — в работе на «сейчас» | генератор |
| `outcome` | text | да | Итог: `sold`, `returned`, `withdrawn` | генератор |

### Клиенты

#### `clients` — Покупатели и комитенты — одни люди и организации (все выдуманные)

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Клиент; id — по моменту регистрации | генератор |
| `kind` | text |  | Человек или организация: `person`, `company` | генератор |
| `first_name` | text | да | Имя (у людей) | генератор |
| `patronymic` | text | да | Отчество (у людей, не у всех) | генератор |
| `last_name` | text | да | Фамилия (у людей) | генератор |
| `company_name` | text | да | Название (у организаций) | генератор |
| `gender` | text | да | Пол (у людей): `m`, `f` | генератор |
| `birth_year` | smallint | да | Год рождения (у людей) | генератор |
| `city_id` | int → `cities` |  | Город | генератор |
| `email` | text |  | E-mail на example.com, example.org, example.net | генератор |
| `language` | text |  | Язык общения: `ru`, `en` | генератор |
| `is_dealer` | bool |  | Дилерский договор (торговец) | генератор |
| `registered_at` | moment |  | Зарегистрирован (с 2012 года) | генератор |
| `verified_at` | moment | да | Документ и карта проверены | генератор |
| `enhanced_verified_at` | moment | да | Расширенная проверка (счёт от 1 млн ₽, драгоценные металлы) | генератор |
| `marketing_consent` | bool |  | Согласие на рассылки (без него — только служебные уведомления) | генератор |
| `blocked_at` | moment | да | Заблокирован (на «сейчас») | генератор |
| `block_reason` | text | да | Причина блокировки: `non_payment`, `shill_suspected`, `own_request` | генератор |

### Торги

#### `sales` — Торги: формат, отдел, сроки, волна закрытий

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Торги; id — по starts_at | генератор |
| `code` | text |  | Код: T-2026-0312 (онлайн), L-2026-014 (живые) | генератор |
| `format` | text |  | Формат: живые в зале или онлайн по времени: `live`, `timed` | генератор |
| `tier` | text |  | Вид: обычные, тематические (онлайн), главная неделя, вечерние: `regular`, `thematic`, `major`, `evening` | генератор |
| `department_id` | smallint → `departments` |  | Отдел | генератор |
| `office_id` | smallint → `offices` |  | Зал (живые) или Москва (онлайн) | генератор |
| `title_ru` | text |  | Название по-русски | генератор |
| `title_en` | text |  | Название по-английски | генератор |
| `published_at` | moment | да | Каталог опубликован; NULL — назначены, каталога ещё нет | генератор |
| `bidding_opens_at` | moment | да | Открыты ставки и регистрация | генератор |
| `preview_from` | date | да | Выставка с (живые) | генератор |
| `preview_to` | date | да | Выставка по (живые) | генератор |
| `starts_at` | moment |  | Живые — начало первой сессии; онлайн — плановое закрытие первой группы | генератор |
| `ends_at` | moment | да | Закрылся последний лот; NULL — не завершены | генератор |
| `lots_per_group` | smallint | да | Онлайн: лотов в группе волны (5) | генератор |
| `group_interval_s` | smallint | да | Онлайн: интервал групп, с (60) | генератор |
| `extension_window_s` | smallint | да | Онлайн: окно мягкого закрытия, с (120) | генератор |
| `extension_s` | smallint | да | Онлайн: продление, с (120) | генератор |
| `extension_cap_s` | smallint | да | Онлайн: потолок продлений сверх плана, с (3600) | генератор |
| `buyer_premium_schedule_id` | smallint → `fee_schedules` |  | Шкала премии — по дате торгов, у монет BP-COIN | генератор |
| `payment_due_days` | smallint |  | Срок оплаты, дней (10) | генератор |
| `status` | text |  | На «сейчас»: `scheduled`, `published`, `open`, `closed` | генератор |

#### `sale_sessions` — Сессии живых торгов

Ключ — `sale_id, session_no`; строки упорядочены по `sale_id, session_no`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `sale_id` | int → `sales` |  | Торги | генератор |
| `session_no` | smallint |  | Сессия: 1, 2, … | генератор |
| `starts_at` | moment |  | Начало | генератор |
| `ended_at` | moment | да | Конец (последний молоток); NULL — не прошла | генератор |
| `first_lot_no` | int |  | Первый лот сессии | генератор |
| `last_lot_no` | int |  | Последний лот сессии (не больше 250 лотов) | генератор |
| `auctioneer_id` | int → `staff` |  | Аукционист | генератор |

#### `lots` — Лоты: выставление вещи на торги, итог

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Лот; id — по моменту публикации | генератор |
| `sale_id` | int → `sales` |  | Торги | генератор |
| `lot_no` | int |  | Номер в каталоге | генератор |
| `lot_label` | text |  | Как в каталоге: «123»; добавленный после публикации — «123A» | генератор |
| `item_id` | int → `items` |  | Вещь | генератор |
| `consignment_id` | int → `consignments` |  | По какому договору выставлена | генератор |
| `offering_no` | smallint |  | 1 — впервые в этом договоре, 2–3 — повторно | генератор |
| `previous_lot_id` | int → `lots` | да | Прежнее выставление этой вещи (в любом договоре) | генератор |
| `session_no` | smallint | да | Живые: сессия | генератор |
| `published_at` | moment |  | В каталоге | генератор |
| `low_estimate` | money |  | Нижняя оценка, руб. | генератор |
| `high_estimate` | money |  | Верхняя оценка, руб. | генератор |
| `starting_price` | money |  | Стартовая цена, руб. | генератор |
| `reserve` | money | да | Конфиденциально: резерв, руб.; NULL — без резерва | генератор |
| `closes_at_planned` | moment | да | Онлайн: место в волне — плановый конец | генератор |
| `opened_at` | moment | да | Живые: аукционист открыл лот; онлайн — открытие ставок | генератор |
| `closed_at` | moment | да | Фактическое закрытие (онлайн — с продлениями, живые — молоток); NULL — не закрыт | генератор |
| `closed_ms` | smallint | да | Миллисекунда закрытия | генератор |
| `extensions` | smallint |  | Продлений (живые — 0) | генератор |
| `bid_count` | int |  | Принятых ставок (с автоставками и ставками в пользу продавца) | генератор |
| `result` | text |  | Итог на «сейчас»: `open`, `sold`, `bought_in`, `withdrawn`, `sold_after_sale` | генератор |
| `hammer_price` | money | да | Молоток, руб. — только sold | генератор |
| `winning_bid_id` | bigint → `bids` | да | Ставка-победитель — только sold | генератор |
| `buyer_registration_id` | int → `registrations` | да | Покупатель — sold, sold_after_sale | генератор |
| `underbidder_registration_id` | int → `registrations` | да | Вторая ставка | генератор |
| `after_sale_price` | money | да | Продажа после торгов: цена, руб. | генератор |
| `after_sale_at` | moment | да | Продажа после торгов: момент | генератор |
| `withdrawn_at` | moment | да | Снят | генератор |
| `withdrawn_by` | text | да | Кто снял: `consignor`, `house` | генератор |

#### `registrations` — Регистрации на торги: табличка, лимит, депозит

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Регистрация; id — по моменту | генератор |
| `sale_id` | int → `sales` |  | Торги | генератор |
| `client_id` | int → `clients` |  | Клиент | генератор |
| `paddle_no` | int |  | Номер таблички: зал 1–1999, телефон и заочные 2000–2999, онлайн (и на живых, и на онлайн-торгах) 10000–99999 | генератор |
| `registered_at` | moment |  | Подана | генератор |
| `via` | text |  | Откуда: `site`, `app`, `phone`, `office`, `partner_platform` | генератор |
| `mode` | text |  | Заявленный способ участия (онлайн-торги — online): `room`, `phone`, `online`, `absentee` | генератор |
| `status` | text |  | На «сейчас»: `approved`, `pending`, `rejected`, `revoked` | генератор |
| `decided_at` | moment | да | Решение | генератор |
| `decided_by_id` | int → `staff` | да | Кто решил; NULL — автоматически | генератор |
| `reject_reason` | text | да | Причина отказа: `no_verification`, `unpaid_invoice`, `blocked` | генератор |
| `bid_limit` | money | да | Лимит на торги (T11), руб.; NULL — без лимита | генератор |
| `premium_approved_at` | moment | да | Допуск к дорогим лотам (T13) | генератор |
| `deposit_required` | money | да | Депозит за дорогие лоты, руб. | генератор |

#### `bid_orders` — Поручения и их редакции: максимум (онлайн), заочная, телефон со страховочной

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Редакция поручения; id — по моменту подачи | генератор |
| `lot_id` | int → `lots` |  | Лот | генератор |
| `registration_id` | int → `registrations` |  | Регистрация участника | генератор |
| `kind` | text |  | Вид: максимум (онлайн), заочная (живые), телефон (максимум — страховочная): `auto`, `absentee`, `phone` | генератор |
| `revision` | smallint |  | Редакция: 1 — первая; каждая следующая — +1 | генератор |
| `replaces_id` | int → `bid_orders` | да | Предыдущая редакция того же участника на этом лоте | генератор |
| `max_amount` | money | да | Максимум (точка сетки, T4), руб.; NULL — телефон без страховочной | генератор |
| `placed_at` | moment |  | Подано | генератор |
| `placed_ms` | smallint |  | Миллисекунда | генератор |
| `via` | text |  | Откуда: `site`, `app`, `phone`, `office`, `partner_platform` | генератор |
| `revision_reason` | text | да | У редакций ≥ 2: лидер поднял максимум; прежний исчерпан — новый: `raise_leading`, `rebid_after_outbid` | генератор |
| `status` | text |  | На «сейчас»: `active`, `won`, `outbid`, `exceeded_limit`, `superseded`, `cancelled`, `rejected`, `unused` | генератор |
| `ended_at` | moment | да | Когда статус стал окончательным | генератор |
| `phone_outcome` | text | да | Телефон: дозвонились или нет (страховочная — из книги, T8): `connected`, `no_answer` | генератор |
| `clerk_id` | int → `staff` | да | Сотрудник на линии | генератор |

#### `bids` — Все попытки ставок — принятые и отклонённые, с миллисекундой и причиной

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | bigint |  | Ставка; id — по (placed_at, placed_ms, lot_id, seq) | генератор |
| `lot_id` | int → `lots` |  | Лот | генератор |
| `seq` | int |  | Порядок поступления в лоте, с 1 (T15) | генератор |
| `registration_id` | int → `registrations` | да | Регистрация; NULL — в пользу продавца | генератор |
| `channel` | text |  | Откуда: `online`, `room`, `phone`, `absentee`, `auctioneer` | генератор |
| `kind` | text |  | Как: руками, поставлен максимум, автоставка системы, заочная из книги, в пользу продавца: `manual`, `max`, `auto`, `absentee`, `vendor` | генератор |
| `bid_order_id` | int → `bid_orders` | да | По какому поручению | генератор |
| `amount` | money |  | Сумма, руб. | генератор |
| `placed_at` | moment |  | Момент (секунды, вниз) | генератор |
| `placed_ms` | smallint |  | Миллисекунда: точный момент = placed_at + placed_ms/1000 | генератор |
| `accepted` | bool |  | Принята | генератор |
| `reject_reason` | text | да | Причина отказа: от движка или от проверок до движка: `below_minimum`, `price_changed`, `race_lost`, `tied`, `already_leading`, `after_close`, `off_increment`, `over_limit`, `own_lot`, `not_registered`, `blocked`, `premium_not_approved`, `not_open`, `lot_withdrawn`, `duplicate` | генератор |
| `extended_close` | bool |  | Сдвинула конец онлайн-лота (T10) | генератор |
| `request_key` | text | да | Ключ запроса ручной онлайн-ставки (32 hex); повтор — тот же ключ | генератор |
| `clerk_id` | int → `staff` | да | Сотрудник: телефон — на линии; заочная, полшага, прыжок, за продавца — аукционист | генератор |

#### `lot_events` — Журнал лота: публикация, открытие, резерв достигнут, продление, предупреждение, итог

Ключ — `lot_id, seq`; строки упорядочены по `lot_id, seq`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `lot_id` | int → `lots` |  | Лот | генератор |
| `seq` | smallint |  | Порядок в лоте | генератор |
| `at` | moment |  | Момент | генератор |
| `at_ms` | smallint |  | Миллисекунда | генератор |
| `kind` | text |  | Событие: `published`, `opened`, `reserve_met`, `extended`, `extension_capped`, `fair_warning`, `sold`, `bought_in`, `withdrawn`, `after_sale` | генератор |
| `bid_id` | bigint → `bids` | да | Ставка-причина (reserve_met, extended, sold) | генератор |
| `new_close_at` | moment | да | extended: новый конец | генератор |
| `new_close_ms` | smallint | да | extended: его миллисекунда | генератор |
| `price` | money | да | sold, after_sale, reserve_met — цена в момент события, руб. | генератор |
| `staff_id` | int → `staff` | да | Аукционист (живые) | генератор |

#### `item_status_log` — Журнал вещи: от приёма до передачи покупателю или возврата

Ключ — `item_id, seq`; строки упорядочены по `item_id, seq`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `item_id` | int → `items` |  | Вещь | генератор |
| `seq` | smallint |  | Порядок | генератор |
| `at` | moment |  | Момент | генератор |
| `status` | text |  | Состояние: `received`, `declined`, `accepted`, `catalogued`, `sold`, `bought_in`, `withdrawn`, `after_sale`, `buyer_paid`, `buyer_default`, `released`, `reoffer_planned`, `returned`, `resale_received` | генератор |
| `lot_id` | int → `lots` | да | Лот перехода | генератор |
| `consignment_id` | int → `consignments` | да | Договор перехода | генератор |

### Деньги

#### `invoices` — Счета покупателям

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Счёт; id — по моменту выставления | генератор |
| `number` | text |  | Номер: INV-2026-000123 | генератор |
| `sale_id` | int → `sales` |  | Торги | генератор |
| `registration_id` | int → `registrations` |  | Покупатель в этих торгах | генератор |
| `client_id` | int → `clients` |  | Клиент | генератор |
| `kind` | text |  | Вид: по торгам, после торгов, второй ставке, недостача, переиздание: `sale`, `after_sale`, `second_chance`, `shortfall`, `reissue` | генератор |
| `issued_at` | moment |  | Выставлен | генератор |
| `due_at` | moment |  | Срок оплаты | генератор |
| `hammer_total` | money |  | Итог строк: цены, руб. | генератор |
| `premium_total` | money |  | Итог строк: премия, руб. | генератор |
| `platform_fee_total` | money |  | Итог строк: сбор партнёрской площадки, руб. | генератор |
| `vat_total` | money |  | Итог строк: НДС, руб. | генератор |
| `total` | money |  | Итого по строкам (M3), руб. | генератор |
| `late_fee` | money |  | Пени на «сейчас» (M6), руб. | генератор |
| `paid_total` | money |  | Оплачено на «сейчас», руб. | генератор |
| `status` | text |  | На «сейчас»: `issued`, `part_paid`, `paid`, `overdue`, `cancelled` | генератор |
| `paid_at` | moment | да | Полная оплата | генератор |
| `cancelled_at` | moment | да | Отменён | генератор |
| `cancel_reason` | text | да | Причина отмены: `non_payment`, `buyer_withdrew`, `house_error` | генератор |
| `related_invoice_id` | int → `invoices` | да | У reissue — заменённый счёт, у second_chance и shortfall — отменённый счёт неплательщика | генератор |

#### `invoice_lines` — Строки счёта: цена, премия, сбор площадки, НДС

Ключ — `invoice_id, line_no`; строки упорядочены по `invoice_id, line_no`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `invoice_id` | int → `invoices` |  | Счёт | генератор |
| `line_no` | smallint |  | Строка | генератор |
| `lot_id` | int → `lots` |  | Лот | генератор |
| `price` | money |  | Молоток, цена после торгов или второй ставки; у shortfall — недостача, руб. | генератор |
| `premium_schedule_id` | smallint → `fee_schedules` |  | Шкала премии | генератор |
| `premium` | money |  | Премия по ступеням (M1); у shortfall — 0, руб. | генератор |
| `platform_fee` | money |  | Сбор партнёрской площадки (0 — своя), руб. | генератор |
| `vat_rate` | decimal |  | Ставка НДС, %: 20,00 или 22,00 | генератор |
| `vat` | money |  | НДС на премию и сбор (M2), руб. | генератор |
| `line_total` | money |  | Итог строки (M3), руб. | генератор |

#### `payments` — Платежи, депозиты, возвраты, выплаты комитентам, неудачные попытки

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Платёж; id — по моменту | генератор |
| `client_id` | int → `clients` |  | Клиент | генератор |
| `kind` | text |  | Вид (направление — по виду): `deposit`, `deposit_applied`, `deposit_refund`, `deposit_forfeit`, `invoice`, `refund`, `payout` | генератор |
| `method` | text |  | Способ: `card`, `sbp`, `bank_transfer`, `cash`, `deposit` | генератор |
| `invoice_id` | int → `invoices` | да | Счёт | генератор |
| `registration_id` | int → `registrations` | да | Регистрация — депозиты | генератор |
| `settlement_id` | int → `settlements` | да | Отчёт комитенту — выплаты | генератор |
| `amount` | money |  | Сумма, руб. | генератор |
| `at` | moment |  | Момент | генератор |
| `status` | text |  | Прошёл или нет: `succeeded`, `failed` | генератор |
| `failure_reason` | text | да | Причина неудачи: `declined`, `insufficient_funds`, `timeout`, `limit` | генератор |
| `provider_ref` | text | да | Номер операции провайдера (карта, СБП) | генератор |

#### `settlements` — Отчёты комитентам по торгам

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Отчёт; id — по created_at | генератор |
| `sale_id` | int → `sales` |  | Торги | генератор |
| `consignor_id` | int → `clients` |  | Комитент | генератор |
| `created_at` | moment |  | Сформирован (следующий рабочий день после торгов) | генератор |
| `hammer_total` | money |  | Итог строк на «сейчас»: цены, руб. | генератор |
| `commission_total` | money |  | Итог строк на «сейчас»: комиссия, руб. | генератор |
| `fees_total` | money |  | Итог строк на «сейчас»: сборы, руб. | генератор |
| `vat_total` | money |  | Итог строк на «сейчас»: НДС, руб. | генератор |
| `net_total` | money |  | К выплате по строкам на «сейчас», руб.; может быть < 0 | генератор |
| `paid_total` | money |  | Выплачено на «сейчас», руб. | генератор |
| `status` | text |  | На «сейчас»: `awaiting_buyers`, `part_paid`, `paid`, `owed_by_consignor` | генератор |

#### `settlement_lines` — Строки отчёта: лот, комиссия, сборы, НДС, выплата — итог лота на «сейчас»

Ключ — `settlement_id, line_no`; строки упорядочены по `settlement_id, line_no`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `settlement_id` | int → `settlements` |  | Отчёт | генератор |
| `line_no` | smallint |  | Строка | генератор |
| `lot_id` | int → `lots` |  | Лот | генератор |
| `outcome` | text |  | Итог лота для комитента на «сейчас»: `sold`, `sold_after_sale`, `second_chance`, `bought_in`, `withdrawn`, `buyer_default` | генератор |
| `price` | money | да | Цена продажи на «сейчас», руб. | генератор |
| `commission` | money |  | Комиссия (M4), руб. | генератор |
| `insurance_fee` | money |  | Страхование, руб. | генератор |
| `catalogue_fee` | money |  | Каталог и фото, руб. | генератор |
| `withdrawal_fee` | money |  | Сбор за снятие, руб. | генератор |
| `vat` | money |  | НДС на удержания (M5), руб. | генератор |
| `net` | money |  | К выплате (M5; у непроданных — минус), руб. | генератор |
| `payable_at` | moment | да | Когда положена выплата (M7); NULL — лот не оплачен | генератор |
| `paid_at` | moment | да | Выплачено (платёж payout) | генератор |

### Сайт и уведомления

#### `watchlist` — Наблюдение за лотами

Ключ — `client_id, lot_id`; строки упорядочены по `client_id, lot_id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `client_id` | int → `clients` |  | Клиент | генератор |
| `lot_id` | int → `lots` |  | Лот | генератор |
| `added_at` | moment |  | Добавил | генератор |
| `removed_at` | moment | да | Убрал | генератор |
| `via` | text |  | Откуда: `site`, `app` | генератор |

#### `sessions` — Визиты сайта и приложения — посетители с согласием на аналитику

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | bigint |  | Визит; id — по started_at | генератор |
| `client_id` | int → `clients` | да | Клиент; NULL — не вошёл | генератор |
| `device_key` | text |  | Отпечаток устройства (16 hex), постоянный у устройства | генератор |
| `device` | text |  | Устройство: `desktop`, `mobile_web`, `app` | генератор |
| `source` | text |  | Откуда пришёл: `direct`, `search`, `email`, `push`, `referral` | генератор |
| `city_id` | int → `cities` | да | Город по адресу сети | генератор |
| `started_at` | moment |  | Начало | генератор |
| `ended_at` | moment |  | Конец | генератор |

#### `session_events` — События визита: поиск, страница каталога, просмотр лота, наблюдение, ставка

Ключ — `session_id, seq`; строки упорядочены по `session_id, seq`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `session_id` | bigint → `sessions` |  | Визит | генератор |
| `seq` | smallint |  | Порядок | генератор |
| `at` | moment |  | Момент | генератор |
| `kind` | text |  | Событие: `search`, `catalogue_page`, `lot_view`, `watch_add`, `watch_remove`, `bid_form`, `bid_submit`, `order_set` | генератор |
| `sale_id` | int → `sales` | да | Торги | генератор |
| `lot_id` | int → `lots` | да | Лот | генератор |
| `query` | text | да | search: запрос, как набран | генератор |
| `results` | int | да | search: найдено | генератор |
| `page` | smallint | да | catalogue_page: страница (по 20) | генератор |
| `bid_id` | bigint → `bids` | да | bid_submit: ставка | генератор |
| `bid_order_id` | int → `bid_orders` | да | order_set: поручение | генератор |

#### `notifications` — Уведомления: перебита ставка, лот закрывается, выигрыш, счёт, оплата, выплата

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | bigint |  | Уведомление; id — по created_at | генератор |
| `client_id` | int → `clients` |  | Получатель | генератор |
| `kind` | text |  | Вид: `registration_decided`, `outbid`, `closing_soon`, `lot_won`, `sale_summary`, `invoice_issued`, `payment_reminder`, `payment_received`, `invoice_cancelled`, `deposit_refunded`, `settlement_ready`, `settlement_paid` | генератор |
| `channel` | text |  | Канал: `email`, `push` | генератор |
| `event_key` | text |  | Ключ события-источника (outbid:<лот>:<ставка>) — для идемпотентного потребителя | генератор |
| `sale_id` | int → `sales` | да | Торги | генератор |
| `lot_id` | int → `lots` | да | Лот | генератор |
| `invoice_id` | int → `invoices` | да | Счёт | генератор |
| `created_at` | moment |  | Событие поставлено в очередь | генератор |
| `sent_at` | moment | да | Отправлено | генератор |
| `delivered_at` | moment | да | Доставлено | генератор |
| `attempts` | smallint |  | Попыток отправки | генератор |
| `status` | text |  | На «сейчас»: доставлено, ошибка, подавлено (тихие часы push 23:00–08:00 по местному, отписка), в очереди: `delivered`, `failed`, `suppressed`, `pending` | генератор |
