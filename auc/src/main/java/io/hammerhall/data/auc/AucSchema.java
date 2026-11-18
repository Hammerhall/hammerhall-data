package io.hammerhall.data.auc;

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
 * Схема {@code auc-data-v1} — единственное описание 31 таблицы «Дома Одина». Из него — TSV
 * (порядок и типы полей), DDL, ER-диаграмма и поля SOURCE.md. Это контракт домена: модуль пишет таблицу ровно по этому
 * описанию, поле добавляется здесь (и только добавляется — после публикации ничего не переименовываем).
 *
 * <p>Перечисления — латинские коды в snake_case (значения — в описании поля и CHECK). Идентификаторы — целые с 1, по
 * моменту появления сущности. Моменты — секунды Unix UTC; у ставок и событий лота — ещё миллисекунда
 * отдельным полем {@code *_ms}. Деньги — рубли с копейками. CHECK, которые нарушают заложенные отклонения, в
 * DDL не входят — их держит самопроверка.
 */
public final class AucSchema {
    private AucSchema() {
    }

    public static final String VERSION = "auc-data-v1";

    /** Шкалы состояния вещей: общая, нумизматическая, марки. */
    public static final String[] CONDITION_GRADES = {"excellent", "very_good", "good", "fair", "poor",
            "unc", "au", "xf", "vf", "f", "vg", "mnh", "mh", "used"};
    public static final String[] ATTRIBUTIONS = {"by", "attributed", "workshop", "circle", "follower", "manner", "after",
            "unknown"};
    /** Виды мастеров — коды словаря ({@code text.MakerKind}). */
    public static final String[] MAKER_KINDS = io.hammerhall.data.auc.text.MakerKind.codes();
    public static final String[] ITEM_STATUSES = {"received", "declined", "accepted", "catalogued", "sold", "bought_in",
            "withdrawn", "after_sale", "buyer_paid", "buyer_default", "released", "reoffer_planned", "returned",
            "resale_received"};
    public static final String[] VIA = {"site", "app", "phone", "office", "partner_platform"};
    public static final String[] REJECT_REASONS = {"below_minimum", "price_changed", "race_lost", "tied",
            "already_leading", "after_close", "off_increment", "over_limit", "own_lot", "not_registered", "blocked",
            "premium_not_approved", "not_open", "lot_withdrawn", "duplicate"};
    public static final String[] NOTIFICATION_KINDS = {"registration_decided", "outbid", "closing_soon", "lot_won",
            "sale_summary", "invoice_issued", "payment_reminder", "payment_received", "invoice_cancelled",
            "deposit_refunded", "settlement_ready", "settlement_paid"};

    public static Schema build() {
        Schema s = new Schema("auc", VERSION)
                // справочник: на отделы, офисы, сотрудников, города и шкалы ссылаются почти все группы — на карте
                // схемы эти связи не рисуются (они — на картинках групп)
                .group("house", "Дом и правила", true)
                .group("catalog", "Мастера и вещи")
                .group("clients", "Клиенты")
                .group("auction", "Торги")
                .group("money", "Деньги")
                .group("site", "Сайт и уведомления");

        // ============================================================ дом и правила
        s.add(Table.of("departments", "house", "Отделы дома").title("Отделы").rows("6")
                .col("id", SMALLINT, "Отдел")
                .col("code", TEXT, "Код отдела: painting, jewelry, coins, books, decorative, collectibles")
                .col("name_ru", TEXT, "Название по-русски")
                .col("name_en", TEXT, "Название по-английски")
                .col("sort_order", SMALLINT, "Порядок в каталоге")
                .pk("id").unique("code"));

        s.add(Table.of("categories", "house", "Категории — разделы отдела и подразделы, дерево до 3 уровней")
                .title("Категории").rows("18–140")
                .col("id", INT, "Категория")
                .col("department_id", SMALLINT, "Отдел (у ребёнка — тот же, что у родителя)").ref("departments")
                .col("parent_id", INT, "Родительская категория; NULL — верхний уровень отдела").nullable().ref("categories")
                .col("level", SMALLINT, "Уровень: 1 — раздел отдела, 2–3 — подразделы")
                .col("code", TEXT, "Код категории (латиница)")
                .col("name_ru", TEXT, "Название по-русски")
                .col("name_en", TEXT, "Название по-английски")
                .pk("id").unique("code").check("level", "level BETWEEN 1 AND 3"));

        s.add(Table.of("cities", "house", "Города — как в sto-data-v1 (те же id): набор соединяется с «Кременъ» по городу")
                .title("Города").rows("149")
                .col("id", INT, "Город (id sto-data-v1)").source("sto-data-v1 cities.id")
                .col("name_ru", TEXT, "Название по-русски").source("sto-data-v1 cities.name_ru")
                .col("name_en", TEXT, "Название по-английски").source("sto-data-v1 cities.name_en")
                .col("region_ru", TEXT, "Субъект РФ").source("sto-data-v1 cities.region_ru")
                .col("federal_district", TEXT, "Федеральный округ").values("central", "northwest", "south",
                        "north_caucasus", "volga", "ural", "siberia", "far_east").source("sto-data-v1 cities.federal_district")
                .col("lat", DOUBLE, "Широта центра").source("sto-data-v1 cities.lat")
                .col("lon", DOUBLE, "Долгота центра").source("sto-data-v1 cities.lon")
                .col("timezone", TEXT, "Часовой пояс IANA — местное время участников").source("sto-data-v1 cities.timezone")
                .col("population", INT, "Население, оценка — вес клиентов по городам").source("sto-data-v1 cities.population")
                .pk("id"));

        s.add(Table.of("offices", "house", "Офисы: главный, залы торгов, приёмные").title("Офисы").rows("8")
                .col("id", SMALLINT, "Офис")
                .col("code", TEXT, "Код: msk, spb, kzn, nnv, ekb, nsk, krd, vvo")
                .col("kind", TEXT, "Вид").values("headquarters", "saleroom", "intake")
                .col("city_id", INT, "Город").ref("cities")
                .col("name_ru", TEXT, "Название по-русски")
                .col("name_en", TEXT, "Название по-английски")
                .col("has_saleroom", BOOL, "Есть зал торгов")
                .col("opened_on", DATE, "Открыт")
                .col("closed_on", DATE, "Закрыт; NULL — работает").nullable()
                .pk("id").unique("code"));

        s.add(Table.of("staff", "house", "Сотрудники и их роли (имена выдуманные)").title("Сотрудники").rows("≈ 230")
                .col("id", INT, "Сотрудник; id — по дате приёма")
                .col("office_id", SMALLINT, "Офис").ref("offices")
                .col("department_id", SMALLINT, "Отдел; NULL — не за отделом").nullable().ref("departments")
                .col("role", TEXT, "Роль").values("specialist", "head_of_department", "auctioneer", "phone_clerk",
                        "client_service", "accountant", "administrator")
                .col("first_name", TEXT, "Имя")
                .col("last_name", TEXT, "Фамилия")
                .col("hired_on", DATE, "Принят")
                .col("left_on", DATE, "Уволился; NULL — работает").nullable()
                .pk("id").check("dates", "left_on IS NULL OR left_on > hired_on"));

        s.add(Table.of("increment_bands", "house", "Таблица шагов ставки: от текущей цены — шаг (T1)")
                .title("Шаги ставки").rows("14")
                .col("valid_from", DATE, "Версия таблицы шагов (v1 — одна, 2024-09-01)")
                .col("from_amount", MONEY, "Цена от (включительно) — полоса до следующего порога, руб.")
                .col("step", MONEY, "Шаг ставки в полосе, руб.")
                .pk("valid_from", "from_amount").check("step", "step > 0"));

        s.add(Table.of("fee_schedules", "house",
                        "Шкалы и сборы: премия, комиссия, площадка, страхование, каталог, снятие, пени, депозит")
                .title("Шкалы и сборы").rows("11")
                .col("id", SMALLINT, "Шкала или сбор")
                .col("code", TEXT, "Код: BP-2024, BP-2026, BP-COIN, SC-2024, PLT-2024, INS-2024, CAT-LIVE, CAT-TIMED, "
                        + "WDR-2024, LATE-2024, DEP-2024")
                .col("kind", TEXT, "Вид").values("buyer_premium", "seller_commission", "platform", "insurance",
                        "catalogue", "withdrawal", "late_payment", "deposit")
                .col("basis", TEXT, "От чего считается").values("hammer", "low_estimate", "lot", "invoice_total_per_day")
                .col("applies_to", TEXT, "Формат торгов").values("all", "live", "timed")
                .col("department_id", SMALLINT, "Только для отдела (BP-COIN); NULL — все").nullable().ref("departments")
                .col("valid_from", DATE, "Действует с")
                .col("valid_to", DATE, "Последний день действия (включительно); NULL — действует").nullable()
                .col("rate_pct", DECIMAL, "Единая ставка, % (у пеней — в день); NULL — ступени в fee_tiers или "
                        + "фиксированная сумма").nullable().scale(6, 3)
                .col("fixed_amount", MONEY, "Фиксированная сумма за лот, руб.").nullable()
                .col("name_ru", TEXT, "Название для отчётов")
                .pk("id").unique("code")
                .check("one_rate", "rate_pct IS NULL OR fixed_amount IS NULL")
                .check("dates", "valid_to IS NULL OR valid_to >= valid_from"));

        s.add(Table.of("fee_tiers", "house",
                        "Ступени премии и комиссии: ставка — на часть суммы от from_amount (не включая) до начала "
                                + "следующей ступени (включая)").title("Ступени шкал").rows("10")
                .col("schedule_id", SMALLINT, "Шкала (ступенчатые: BP-2024, BP-2026, SC-2024)").ref("fee_schedules")
                .col("tier_no", SMALLINT, "Ступень: 1, 2, …")
                .col("from_amount", MONEY, "Начало части, руб.")
                .col("rate_pct", DECIMAL, "Ставка, % (скидка торговца — правило M4, не строка)").scale(6, 3)
                .pk("schedule_id", "tier_no"));

        // ============================================================ мастера и вещи
        s.add(Table.of("makers", "catalog",
                        "Мастера, мастерские, мануфактуры, монетные дворы, издательства — все выдуманные")
                .title("Мастера").rows("≈ 3 900")
                .col("id", INT, "Мастер")
                .col("kind", TEXT, "Вид").values(MAKER_KINDS)
                .col("department_id", SMALLINT, "Отдел вида").ref("departments")
                .col("is_person", BOOL, "Человек (иначе — мастерская, мануфактура, двор)")
                .col("name_ru", TEXT, "Имя для каталога")
                .col("name_en", TEXT, "Имя по-английски")
                .col("first_name", TEXT, "Имя (у людей)").nullable()
                .col("patronymic", TEXT, "Отчество (у людей)").nullable()
                .col("last_name", TEXT, "Фамилия (у людей)").nullable()
                .col("active_from", SMALLINT, "Год рождения или основания")
                .col("active_to", SMALLINT, "Год смерти или закрытия; NULL — жив, работает").nullable()
                .col("country", TEXT, "Страна (код словаря)")
                .col("city_ru", TEXT, "Город; NULL — у выдуманного монетного двора").nullable()
                .col("school_ru", TEXT, "Направление (художники, скульпторы)").nullable()
                .col("specialty_ru", TEXT, "Специализация; у мастерских — чем известна").nullable()
                .col("mark_ru", TEXT, "Подпись, монограмма, клеймо, знак двора — как на предмете").nullable()
                .col("tier", TEXT, "Уровень рынка: A — имена, ради которых приходят на торги, … D").values("A", "B", "C", "D")
                .pk("id").unique("name_ru")
                .check("years", "active_to IS NULL OR active_to >= active_from"));

        s.add(Table.of("items", "catalog", "Вещи — факт приёма с описанием; при продлении периода не меняются")
                .title("Вещи").rows("≈ 1,33 млн")
                .col("id", INT, "Вещь; id — по моменту приёма")
                .col("consignor_id", INT, "Кто принёс (при первом приёме)").ref("clients")
                .col("office_id", SMALLINT, "Где принята").ref("offices")
                .col("specialist_id", INT, "Кто смотрел").ref("staff")
                .col("received_at", MOMENT, "Принята на осмотр")
                .col("decided_at", MOMENT, "Решение о приёме")
                .col("accepted", BOOL, "Принята на комиссию")
                .col("decline_reason", TEXT, "Причина отказа — только у непринятых").nullable()
                .values("low_value", "condition", "authenticity_doubt", "duplicate")
                .col("department_id", SMALLINT, "Отдел").ref("departments")
                .col("category_id", INT, "Категория — лист дерева").ref("categories")
                .col("maker_id", INT, "Мастер; NULL — неизвестен").nullable().ref("makers")
                .col("attribution", TEXT, "Атрибуция, как в каталогах: работа мастера, приписывается, мастерская, "
                        + "круг, последователь, в манере, по оригиналу, неизвестный мастер").values(ATTRIBUTIONS)
                .col("title_ru", TEXT, "Заголовок лота по-русски, 20–120 знаков")
                .col("title_en", TEXT, "Заголовок лота по-английски")
                .col("description_ru", TEXT, "Описание, 200–1 200 знаков")
                .col("condition_ru", TEXT, "Состояние словами, 30–400 знаков")
                .col("condition_grade", TEXT, "Состояние по шкале раздела: общая excellent … poor; монеты и медали "
                        + "unc … vg; марки mnh, mh, used").values(CONDITION_GRADES)
                .col("dating_ru", TEXT, "Датировка, как в каталоге").nullable()
                .col("year_from", SMALLINT, "Год создания — начало диапазона датировки").nullable()
                .col("year_to", SMALLINT, "Год создания — конец диапазона").nullable()
                .col("origin_place_ru", TEXT, "Место создания").nullable()
                .col("material_ru", TEXT, "Материал").nullable()
                .col("technique_ru", TEXT, "Техника").nullable()
                .col("height_mm", INT, "Высота, мм").nullable()
                .col("width_mm", INT, "Ширина, мм").nullable()
                .col("depth_mm", INT, "Глубина, мм").nullable()
                .col("diameter_mm", DECIMAL, "Диаметр, мм (монеты, медали, часы, тарелки)").nullable().scale(6, 1)
                .col("weight_g", DECIMAL, "Вес, г").nullable().scale(9, 2)
                .col("metal_fineness", SMALLINT, "Проба металла, метрическая: 375 … 999").nullable()
                .col("pieces", SMALLINT, "Предметов в вещи (комплект, лот марок)")
                .col("signed", BOOL, "Подпись, клеймо или марка мастера есть").nullable()
                .pk("id").index("maker_id").index("category_id").index("consignor_id")
                .check("decline", "accepted = (decline_reason IS NULL)")
                .check("years", "year_to >= year_from")
                .check("pieces", "pieces >= 1"));

        s.add(Table.of("provenance", "catalog", "Происхождение вещи — звенья цепочки, литература, выставки")
                .title("Происхождение вещей").rows("≈ 1,0 млн")
                .col("item_id", INT, "Вещь").ref("items")
                .col("seq", SMALLINT, "Порядок: 1 — самое раннее звено")
                .col("kind", TEXT, "Вид звена; chain — вся цепочка одной записью").values("by_descent",
                        "private_collection", "acquired", "auction", "gallery", "exhibition", "literature", "expertise", "chain")
                .col("text_ru", TEXT, "Запись, как в каталоге, 30–300 знаков (chain — до 1 000)")
                .col("year_from", SMALLINT, "Год начала звена").nullable()
                .col("year_to", SMALLINT, "Год конца звена").nullable()
                .col("place_ru", TEXT, "Город или страна").nullable()
                .col("owner_name_ru", TEXT, "Владелец, собрание, галерея, издание (выдуманные)").nullable()
                .pk("item_id", "seq"));

        s.add(Table.of("consignments", "catalog", "Договоры комиссии (и сдача купленного обратно)")
                .title("Договоры комиссии").rows("≈ 0,22 млн")
                .col("id", INT, "Договор; id — по моменту подписания")
                .col("consignor_id", INT, "Комитент").ref("clients")
                .col("office_id", SMALLINT, "Офис").ref("offices")
                .col("specialist_id", INT, "Специалист").ref("staff")
                .col("kind", TEXT, "Вид: частный, торговец, наследство, учреждение, сдача купленного в доме")
                .values("private", "dealer", "estate", "institution", "resale")
                .col("signed_at", MOMENT, "Подписан")
                .col("commission_schedule_id", SMALLINT, "Шкала комиссии (SC-2024)").ref("fee_schedules")
                .col("commission_rate", DECIMAL, "Договорная единая ставка, % (5–10); NULL — по шкале").nullable().scale(6, 3)
                .col("dealer_discount", BOOL, "Скидка торговца 5 п. п. (M4)")
                .col("unsold_policy", TEXT, "Что делать с непроданным").values("return", "reoffer", "ask")
                .col("expires_on", DATE, "Срок договора (12 месяцев)")
                .pk("id").index("consignor_id"));

        s.add(Table.of("consignment_items", "catalog", "Вещи в договоре: условия приёма, итог")
                .title("Вещи в договорах").rows("≈ 1,27 млн")
                .col("consignment_id", INT, "Договор").ref("consignments")
                .col("line_no", SMALLINT, "Строка договора")
                .col("item_id", INT, "Вещь (только принятые)").ref("items")
                .col("agreed_low", MONEY, "Нижняя оценка при приёме, руб.")
                .col("agreed_high", MONEY, "Верхняя оценка при приёме, руб.")
                .col("agreed_reserve", MONEY, "Резерв по договору, руб.; NULL — без резерва").nullable()
                .col("ended_at", MOMENT, "Вещь вышла из договора; NULL — в работе на «сейчас»").nullable()
                .col("outcome", TEXT, "Итог").nullable().values("sold", "returned", "withdrawn")
                .pk("consignment_id", "line_no").unique("consignment_id", "item_id").index("item_id"));

        // ============================================================ клиенты
        s.add(Table.of("clients", "clients", "Покупатели и комитенты — одни люди и организации (все выдуманные)")
                .title("Клиенты").rows("≈ 0,73 млн")
                .col("id", INT, "Клиент; id — по моменту регистрации")
                .col("kind", TEXT, "Человек или организация").values("person", "company")
                .col("first_name", TEXT, "Имя (у людей)").nullable()
                .col("patronymic", TEXT, "Отчество (у людей, не у всех)").nullable()
                .col("last_name", TEXT, "Фамилия (у людей)").nullable()
                .col("company_name", TEXT, "Название (у организаций)").nullable()
                .col("gender", TEXT, "Пол (у людей)").nullable().values("m", "f")
                .col("birth_year", SMALLINT, "Год рождения (у людей)").nullable()
                .col("city_id", INT, "Город").ref("cities")
                .col("email", TEXT, "E-mail на example.com, example.org, example.net")
                .col("language", TEXT, "Язык общения").values("ru", "en")
                .col("is_dealer", BOOL, "Дилерский договор (торговец)")
                .col("registered_at", MOMENT, "Зарегистрирован (с 2012 года)")
                .col("verified_at", MOMENT, "Документ и карта проверены").nullable()
                .col("enhanced_verified_at", MOMENT, "Расширенная проверка (счёт от 1 млн ₽, драгоценные металлы)").nullable()
                .col("marketing_consent", BOOL, "Согласие на рассылки (без него — только служебные уведомления)")
                .col("blocked_at", MOMENT, "Заблокирован (на «сейчас»)").nullable()
                .col("block_reason", TEXT, "Причина блокировки").nullable().values("non_payment", "shill_suspected", "own_request")
                .pk("id").unique("email")
                .check("person", "(kind = 'person') = (last_name IS NOT NULL)")
                .check("company", "(kind = 'company') = (company_name IS NOT NULL)")
                .check("blocked", "(blocked_at IS NULL) = (block_reason IS NULL)"));

        // ============================================================ торги
        s.add(Table.of("sales", "auction", "Торги: формат, отдел, сроки, волна закрытий").title("Торги").rows("≈ 960")
                .col("id", INT, "Торги; id — по starts_at")
                .col("code", TEXT, "Код: T-2026-0312 (онлайн), L-2026-014 (живые)")
                .col("format", TEXT, "Формат: живые в зале или онлайн по времени").values("live", "timed")
                .col("tier", TEXT, "Вид: обычные, тематические (онлайн), главная неделя, вечерние")
                .values("regular", "thematic", "major", "evening")
                .col("department_id", SMALLINT, "Отдел").ref("departments")
                .col("office_id", SMALLINT, "Зал (живые) или Москва (онлайн)").ref("offices")
                .col("title_ru", TEXT, "Название по-русски")
                .col("title_en", TEXT, "Название по-английски")
                .col("published_at", MOMENT, "Каталог опубликован; NULL — назначены, каталога ещё нет").nullable()
                .col("bidding_opens_at", MOMENT, "Открыты ставки и регистрация").nullable()
                .col("preview_from", DATE, "Выставка с (живые)").nullable()
                .col("preview_to", DATE, "Выставка по (живые)").nullable()
                .col("starts_at", MOMENT, "Живые — начало первой сессии; онлайн — плановое закрытие первой группы")
                .col("ends_at", MOMENT, "Закрылся последний лот; NULL — не завершены").nullable()
                .col("lots_per_group", SMALLINT, "Онлайн: лотов в группе волны (5)").nullable()
                .col("group_interval_s", SMALLINT, "Онлайн: интервал групп, с (60)").nullable()
                .col("extension_window_s", SMALLINT, "Онлайн: окно мягкого закрытия, с (120)").nullable()
                .col("extension_s", SMALLINT, "Онлайн: продление, с (120)").nullable()
                .col("extension_cap_s", SMALLINT, "Онлайн: потолок продлений сверх плана, с (3600)").nullable()
                .col("buyer_premium_schedule_id", SMALLINT, "Шкала премии — по дате торгов, у монет BP-COIN").ref("fee_schedules")
                .col("payment_due_days", SMALLINT, "Срок оплаты, дней (10)")
                .col("status", TEXT, "На «сейчас»").values("scheduled", "published", "open", "closed")
                .pk("id").unique("code")
                .check("timed_wave", "(format = 'timed') = (lots_per_group IS NOT NULL)"));

        s.add(Table.of("sale_sessions", "auction", "Сессии живых торгов").title("Сессии торгов").rows("≈ 190")
                .col("sale_id", INT, "Торги").ref("sales")
                .col("session_no", SMALLINT, "Сессия: 1, 2, …")
                .col("starts_at", MOMENT, "Начало")
                .col("ended_at", MOMENT, "Конец (последний молоток); NULL — не прошла").nullable()
                .col("first_lot_no", INT, "Первый лот сессии")
                .col("last_lot_no", INT, "Последний лот сессии (не больше 250 лотов)")
                .col("auctioneer_id", INT, "Аукционист").ref("staff")
                .pk("sale_id", "session_no")
                .check("lots", "last_lot_no >= first_lot_no"));

        s.add(Table.of("lots", "auction", "Лоты: выставление вещи на торги, итог").title("Лоты").rows("≈ 1,34 млн")
                .col("id", INT, "Лот; id — по моменту публикации")
                .col("sale_id", INT, "Торги").ref("sales")
                .col("lot_no", INT, "Номер в каталоге")
                .col("lot_label", TEXT, "Как в каталоге: «123»; добавленный после публикации — «123A»")
                .col("item_id", INT, "Вещь").ref("items")
                .col("consignment_id", INT, "По какому договору выставлена").ref("consignments")
                .col("offering_no", SMALLINT, "1 — впервые в этом договоре, 2–3 — повторно")
                .col("previous_lot_id", INT, "Прежнее выставление этой вещи (в любом договоре)").nullable().ref("lots")
                .col("session_no", SMALLINT, "Живые: сессия").nullable()
                .col("published_at", MOMENT, "В каталоге")
                .col("low_estimate", MONEY, "Нижняя оценка, руб.")
                .col("high_estimate", MONEY, "Верхняя оценка, руб.")
                .col("starting_price", MONEY, "Стартовая цена, руб.")
                .col("reserve", MONEY, "Конфиденциально: резерв, руб.; NULL — без резерва").nullable()
                .col("closes_at_planned", MOMENT, "Онлайн: место в волне — плановый конец").nullable()
                .col("opened_at", MOMENT, "Живые: аукционист открыл лот; онлайн — открытие ставок").nullable()
                .col("closed_at", MOMENT, "Фактическое закрытие (онлайн — с продлениями, живые — молоток); NULL — не закрыт").nullable()
                .col("closed_ms", SMALLINT, "Миллисекунда закрытия").nullable()
                .col("extensions", SMALLINT, "Продлений (живые — 0)")
                .col("bid_count", INT, "Принятых ставок (с автоставками и ставками в пользу продавца)")
                .col("result", TEXT, "Итог на «сейчас»").values("open", "sold", "bought_in", "withdrawn", "sold_after_sale")
                .col("hammer_price", MONEY, "Молоток, руб. — только sold").nullable()
                .col("winning_bid_id", BIGINT, "Ставка-победитель — только sold").nullable().ref("bids")
                .col("buyer_registration_id", INT, "Покупатель — sold, sold_after_sale").nullable().ref("registrations")
                .col("underbidder_registration_id", INT, "Вторая ставка").nullable().ref("registrations")
                .col("after_sale_price", MONEY, "Продажа после торгов: цена, руб.").nullable()
                .col("after_sale_at", MOMENT, "Продажа после торгов: момент").nullable()
                .col("withdrawn_at", MOMENT, "Снят").nullable()
                .col("withdrawn_by", TEXT, "Кто снял").nullable().values("consignor", "house")
                .pk("id").unique("sale_id", "lot_label").index("sale_id", "lot_no").index("item_id")
                // сессия живых торгов — (торги, номер сессии); у онлайн-лотов её нет, внешнего ключа нет: связь только на
                // картинке схемы (первым — номер сессии: стрелка идёт к нему)
                .link(java.util.List.of("session_no", "sale_id"), "sale_sessions", java.util.List.of("session_no", "sale_id"))
                .check("estimate", "low_estimate < high_estimate")
                .check("closed_ms", "closed_ms BETWEEN 0 AND 999"));

        s.add(Table.of("registrations", "auction", "Регистрации на торги: табличка, лимит, депозит")
                .title("Регистрации").rows("≈ 4,0 млн")
                .col("id", INT, "Регистрация; id — по моменту")
                .col("sale_id", INT, "Торги").ref("sales")
                .col("client_id", INT, "Клиент").ref("clients")
                .col("paddle_no", INT, "Номер таблички: зал 1–1999, телефон и заочные 2000–2999, онлайн (и на живых, и на онлайн-торгах) "
                        + "10000–99999")
                .col("registered_at", MOMENT, "Подана")
                .col("via", TEXT, "Откуда").values(VIA)
                .col("mode", TEXT, "Заявленный способ участия (онлайн-торги — online)").values("room", "phone", "online", "absentee")
                .col("status", TEXT, "На «сейчас»").values("approved", "pending", "rejected", "revoked")
                .col("decided_at", MOMENT, "Решение").nullable()
                .col("decided_by_id", INT, "Кто решил; NULL — автоматически").nullable().ref("staff")
                .col("reject_reason", TEXT, "Причина отказа").nullable().values("no_verification", "unpaid_invoice", "blocked")
                .col("bid_limit", MONEY, "Лимит на торги (T11), руб.; NULL — без лимита").nullable()
                .col("premium_approved_at", MOMENT, "Допуск к дорогим лотам (T13)").nullable()
                .col("deposit_required", MONEY, "Депозит за дорогие лоты, руб.").nullable()
                .pk("id").unique("sale_id", "client_id").unique("sale_id", "paddle_no"));

        s.add(Table.of("bid_orders", "auction",
                        "Поручения и их редакции: максимум (онлайн), заочная, телефон со страховочной")
                .title("Поручения").rows("≈ 2,0 млн")
                .col("id", INT, "Редакция поручения; id — по моменту подачи")
                .col("lot_id", INT, "Лот").ref("lots")
                .col("registration_id", INT, "Регистрация участника").ref("registrations")
                .col("kind", TEXT, "Вид: максимум (онлайн), заочная (живые), телефон (максимум — страховочная)")
                .values("auto", "absentee", "phone")
                .col("revision", SMALLINT, "Редакция: 1 — первая; каждая следующая — +1")
                .col("replaces_id", INT, "Предыдущая редакция того же участника на этом лоте").nullable().ref("bid_orders")
                .col("max_amount", MONEY, "Максимум (точка сетки, T4), руб.; NULL — телефон без страховочной").nullable()
                .col("placed_at", MOMENT, "Подано")
                .col("placed_ms", SMALLINT, "Миллисекунда")
                .col("via", TEXT, "Откуда").values(VIA)
                .col("revision_reason", TEXT, "У редакций ≥ 2: лидер поднял максимум; прежний исчерпан — новый")
                .nullable().values("raise_leading", "rebid_after_outbid")
                .col("status", TEXT, "На «сейчас»").values("active", "won", "outbid", "exceeded_limit", "superseded",
                        "cancelled", "rejected", "unused")
                .col("ended_at", MOMENT, "Когда статус стал окончательным").nullable()
                .col("phone_outcome", TEXT, "Телефон: дозвонились или нет (страховочная — из книги, T8)").nullable()
                .values("connected", "no_answer")
                .col("clerk_id", INT, "Сотрудник на линии").nullable().ref("staff")
                .pk("id").unique("lot_id", "registration_id", "revision").index("lot_id").index("registration_id")
                .check("revision", "(revision = 1) = (replaces_id IS NULL)")
                .check("placed_ms", "placed_ms BETWEEN 0 AND 999"));

        s.add(Table.of("bids", "auction", "Все попытки ставок — принятые и отклонённые, с миллисекундой и причиной")
                .title("Ставки").rows("≈ 9,9 млн")
                .col("id", BIGINT, "Ставка; id — по (placed_at, placed_ms, lot_id, seq)")
                .col("lot_id", INT, "Лот").ref("lots")
                .col("seq", INT, "Порядок поступления в лоте, с 1 (T15)")
                .col("registration_id", INT, "Регистрация; NULL — в пользу продавца").nullable().ref("registrations")
                .col("channel", TEXT, "Откуда").values("online", "room", "phone", "absentee", "auctioneer")
                .col("kind", TEXT, "Как: руками, поставлен максимум, автоставка системы, заочная из книги, в пользу продавца")
                .values("manual", "max", "auto", "absentee", "vendor")
                .col("bid_order_id", INT, "По какому поручению").nullable().ref("bid_orders")
                .col("amount", MONEY, "Сумма, руб.")
                .col("placed_at", MOMENT, "Момент (секунды, вниз)")
                .col("placed_ms", SMALLINT, "Миллисекунда: точный момент = placed_at + placed_ms/1000")
                .col("accepted", BOOL, "Принята")
                .col("reject_reason", TEXT, "Причина отказа: от движка или от проверок до движка").nullable()
                .values(REJECT_REASONS)
                .col("extended_close", BOOL, "Сдвинула конец онлайн-лота (T10)")
                .col("request_key", TEXT, "Ключ запроса ручной онлайн-ставки (32 hex); повтор — тот же ключ").nullable()
                .col("clerk_id", INT, "Сотрудник: телефон — на линии; заочная, полшага, прыжок, за продавца — аукционист")
                .nullable().ref("staff")
                .pk("id").unique("lot_id", "seq").index("registration_id").index("placed_at")
                .check("reject", "accepted = (reject_reason IS NULL)")
                .check("placed_ms", "placed_ms BETWEEN 0 AND 999")
                .check("vendor_reg", "(kind = 'vendor') = (registration_id IS NULL)")
                .check("vendor_channel", "(kind = 'vendor') = (channel = 'auctioneer')")
                .check("order", "kind NOT IN ('max', 'auto', 'absentee') OR bid_order_id IS NOT NULL"));

        s.add(Table.of("lot_events", "auction",
                        "Журнал лота: публикация, открытие, резерв достигнут, продление, предупреждение, итог")
                .title("События лотов").rows("≈ 5,0 млн")
                .col("lot_id", INT, "Лот").ref("lots")
                .col("seq", SMALLINT, "Порядок в лоте")
                .col("at", MOMENT, "Момент")
                .col("at_ms", SMALLINT, "Миллисекунда")
                .col("kind", TEXT, "Событие").values("published", "opened", "reserve_met", "extended", "extension_capped",
                        "fair_warning", "sold", "bought_in", "withdrawn", "after_sale")
                .col("bid_id", BIGINT, "Ставка-причина (reserve_met, extended, sold)").nullable().ref("bids")
                .col("new_close_at", MOMENT, "extended: новый конец").nullable()
                .col("new_close_ms", SMALLINT, "extended: его миллисекунда").nullable()
                .col("price", MONEY, "sold, after_sale, reserve_met — цена в момент события, руб.").nullable()
                .col("staff_id", INT, "Аукционист (живые)").nullable().ref("staff")
                .pk("lot_id", "seq").index("at")
                .check("at_ms", "at_ms BETWEEN 0 AND 999"));

        s.add(Table.of("item_status_log", "auction", "Журнал вещи: от приёма до передачи покупателю или возврата")
                .title("Статусы вещей").rows("≈ 6,8 млн")
                .col("item_id", INT, "Вещь").ref("items")
                .col("seq", SMALLINT, "Порядок")
                .col("at", MOMENT, "Момент")
                .col("status", TEXT, "Состояние").values(ITEM_STATUSES)
                .col("lot_id", INT, "Лот перехода").nullable().ref("lots")
                .col("consignment_id", INT, "Договор перехода").nullable().ref("consignments")
                .pk("item_id", "seq"));

        // ============================================================ деньги
        s.add(Table.of("invoices", "money", "Счета покупателям").title("Счета").rows("≈ 0,67 млн")
                .col("id", INT, "Счёт; id — по моменту выставления")
                .col("number", TEXT, "Номер: INV-2026-000123")
                .col("sale_id", INT, "Торги").ref("sales")
                .col("registration_id", INT, "Покупатель в этих торгах").ref("registrations")
                .col("client_id", INT, "Клиент").ref("clients")
                .col("kind", TEXT, "Вид: по торгам, после торгов, второй ставке, недостача, переиздание")
                .values("sale", "after_sale", "second_chance", "shortfall", "reissue")
                .col("issued_at", MOMENT, "Выставлен")
                .col("due_at", MOMENT, "Срок оплаты")
                .col("hammer_total", MONEY, "Итог строк: цены, руб.")
                .col("premium_total", MONEY, "Итог строк: премия, руб.")
                .col("platform_fee_total", MONEY, "Итог строк: сбор партнёрской площадки, руб.")
                .col("vat_total", MONEY, "Итог строк: НДС, руб.")
                .col("total", MONEY, "Итого по строкам (M3), руб.")
                .col("late_fee", MONEY, "Пени на «сейчас» (M6), руб.")
                .col("paid_total", MONEY, "Оплачено на «сейчас», руб.")
                .col("status", TEXT, "На «сейчас»").values("issued", "part_paid", "paid", "overdue", "cancelled")
                .col("paid_at", MOMENT, "Полная оплата").nullable()
                .col("cancelled_at", MOMENT, "Отменён").nullable()
                .col("cancel_reason", TEXT, "Причина отмены").nullable().values("non_payment", "buyer_withdrew", "house_error")
                .col("related_invoice_id", INT, "У reissue — заменённый счёт, у second_chance и shortfall — отменённый "
                        + "счёт неплательщика").nullable().ref("invoices")
                .pk("id").unique("number").index("client_id"));

        s.add(Table.of("invoice_lines", "money", "Строки счёта: цена, премия, сбор площадки, НДС")
                .title("Строки счетов").rows("≈ 1,06 млн")
                .col("invoice_id", INT, "Счёт").ref("invoices")
                .col("line_no", SMALLINT, "Строка")
                .col("lot_id", INT, "Лот").ref("lots")
                .col("price", MONEY, "Молоток, цена после торгов или второй ставки; у shortfall — недостача, руб.")
                .col("premium_schedule_id", SMALLINT, "Шкала премии").ref("fee_schedules")
                .col("premium", MONEY, "Премия по ступеням (M1); у shortfall — 0, руб.")
                .col("platform_fee", MONEY, "Сбор партнёрской площадки (0 — своя), руб.")
                .col("vat_rate", DECIMAL, "Ставка НДС, %: 20,00 или 22,00").scale(5, 2)
                .col("vat", MONEY, "НДС на премию и сбор (M2), руб.")
                .col("line_total", MONEY, "Итог строки (M3), руб.")
                .pk("invoice_id", "line_no").index("lot_id"));

        s.add(Table.of("payments", "money", "Платежи, депозиты, возвраты, выплаты комитентам, неудачные попытки")
                .title("Платежи").rows("≈ 1,27 млн")
                .col("id", INT, "Платёж; id — по моменту")
                .col("client_id", INT, "Клиент").ref("clients")
                .col("kind", TEXT, "Вид (направление — по виду)").values("deposit", "deposit_applied", "deposit_refund",
                        "deposit_forfeit", "invoice", "refund", "payout")
                .col("method", TEXT, "Способ").values("card", "sbp", "bank_transfer", "cash", "deposit")
                .col("invoice_id", INT, "Счёт").nullable().ref("invoices")
                .col("registration_id", INT, "Регистрация — депозиты").nullable().ref("registrations")
                .col("settlement_id", INT, "Отчёт комитенту — выплаты").nullable().ref("settlements")
                .col("amount", MONEY, "Сумма, руб.")
                .col("at", MOMENT, "Момент")
                .col("status", TEXT, "Прошёл или нет").values("succeeded", "failed")
                .col("failure_reason", TEXT, "Причина неудачи").nullable().values("declined", "insufficient_funds", "timeout", "limit")
                .col("provider_ref", TEXT, "Номер операции провайдера (карта, СБП)").nullable()
                .pk("id").index("invoice_id").index("client_id").index("provider_ref")
                .check("amount", "amount > 0")
                .check("failure", "(status = 'failed') = (failure_reason IS NOT NULL)"));

        s.add(Table.of("settlements", "money", "Отчёты комитентам по торгам").title("Отчёты комитентам")
                .rows("≈ 0,37 млн")
                .col("id", INT, "Отчёт; id — по created_at")
                .col("sale_id", INT, "Торги").ref("sales")
                .col("consignor_id", INT, "Комитент").ref("clients")
                .col("created_at", MOMENT, "Сформирован (следующий рабочий день после торгов)")
                .col("hammer_total", MONEY, "Итог строк на «сейчас»: цены, руб.")
                .col("commission_total", MONEY, "Итог строк на «сейчас»: комиссия, руб.")
                .col("fees_total", MONEY, "Итог строк на «сейчас»: сборы, руб.")
                .col("vat_total", MONEY, "Итог строк на «сейчас»: НДС, руб.")
                .col("net_total", MONEY, "К выплате по строкам на «сейчас», руб.; может быть < 0")
                .col("paid_total", MONEY, "Выплачено на «сейчас», руб.")
                .col("status", TEXT, "На «сейчас»").values("awaiting_buyers", "part_paid", "paid", "owed_by_consignor")
                .pk("id").unique("sale_id", "consignor_id"));

        s.add(Table.of("settlement_lines", "money",
                        "Строки отчёта: лот, комиссия, сборы, НДС, выплата — итог лота на «сейчас»")
                .title("Строки отчётов").rows("≈ 1,30 млн")
                .col("settlement_id", INT, "Отчёт").ref("settlements")
                .col("line_no", SMALLINT, "Строка")
                .col("lot_id", INT, "Лот").ref("lots")
                .col("outcome", TEXT, "Итог лота для комитента на «сейчас»").values("sold", "sold_after_sale", "second_chance",
                        "bought_in", "withdrawn", "buyer_default")
                .col("price", MONEY, "Цена продажи на «сейчас», руб.").nullable()
                .col("commission", MONEY, "Комиссия (M4), руб.")
                .col("insurance_fee", MONEY, "Страхование, руб.")
                .col("catalogue_fee", MONEY, "Каталог и фото, руб.")
                .col("withdrawal_fee", MONEY, "Сбор за снятие, руб.")
                .col("vat", MONEY, "НДС на удержания (M5), руб.")
                .col("net", MONEY, "К выплате (M5; у непроданных — минус), руб.")
                .col("payable_at", MOMENT, "Когда положена выплата (M7); NULL — лот не оплачен").nullable()
                .col("paid_at", MOMENT, "Выплачено (платёж payout)").nullable()
                .pk("settlement_id", "line_no").index("lot_id"));

        // ============================================================ сайт и уведомления
        s.add(Table.of("watchlist", "site", "Наблюдение за лотами").title("Наблюдения за лотами").rows("≈ 4,3 млн")
                .col("client_id", INT, "Клиент").ref("clients")
                .col("lot_id", INT, "Лот").ref("lots")
                .col("added_at", MOMENT, "Добавил")
                .col("removed_at", MOMENT, "Убрал").nullable()
                .col("via", TEXT, "Откуда").values("site", "app")
                .pk("client_id", "lot_id").index("lot_id"));

        s.add(Table.of("sessions", "site", "Визиты сайта и приложения — посетители с согласием на аналитику")
                .title("Визиты").rows("≈ 8,0 млн")
                .col("id", BIGINT, "Визит; id — по started_at")
                .col("client_id", INT, "Клиент; NULL — не вошёл").nullable().ref("clients")
                .col("device_key", TEXT, "Отпечаток устройства (16 hex), постоянный у устройства")
                .col("device", TEXT, "Устройство").values("desktop", "mobile_web", "app")
                .col("source", TEXT, "Откуда пришёл").values("direct", "search", "email", "push", "referral")
                .col("city_id", INT, "Город по адресу сети").nullable().ref("cities")
                .col("started_at", MOMENT, "Начало")
                .col("ended_at", MOMENT, "Конец")
                .pk("id").index("client_id").index("device_key")
                .check("time", "ended_at >= started_at"));

        s.add(Table.of("session_events", "site",
                        "События визита: поиск, страница каталога, просмотр лота, наблюдение, ставка")
                .title("События визитов").rows("≈ 58,7 млн")
                .col("session_id", BIGINT, "Визит").ref("sessions")
                .col("seq", SMALLINT, "Порядок")
                .col("at", MOMENT, "Момент")
                .col("kind", TEXT, "Событие").values("search", "catalogue_page", "lot_view", "watch_add", "watch_remove",
                        "bid_form", "bid_submit", "order_set")
                .col("sale_id", INT, "Торги").nullable().ref("sales")
                .col("lot_id", INT, "Лот").nullable().ref("lots")
                .col("query", TEXT, "search: запрос, как набран").nullable()
                .col("results", INT, "search: найдено").nullable()
                .col("page", SMALLINT, "catalogue_page: страница (по 20)").nullable()
                .col("bid_id", BIGINT, "bid_submit: ставка").nullable().ref("bids")
                .col("bid_order_id", INT, "order_set: поручение").nullable().ref("bid_orders")
                .pk("session_id", "seq"));

        s.add(Table.of("notifications", "site",
                        "Уведомления: перебита ставка, лот закрывается, выигрыш, счёт, оплата, выплата")
                .title("Уведомления").rows("≈ 13,7 млн")
                .col("id", BIGINT, "Уведомление; id — по created_at")
                .col("client_id", INT, "Получатель").ref("clients")
                .col("kind", TEXT, "Вид").values(NOTIFICATION_KINDS)
                .col("channel", TEXT, "Канал").values("email", "push")
                .col("event_key", TEXT, "Ключ события-источника (outbid:<лот>:<ставка>) — для идемпотентного потребителя")
                .col("sale_id", INT, "Торги").nullable().ref("sales")
                .col("lot_id", INT, "Лот").nullable().ref("lots")
                .col("invoice_id", INT, "Счёт").nullable().ref("invoices")
                .col("created_at", MOMENT, "Событие поставлено в очередь")
                .col("sent_at", MOMENT, "Отправлено").nullable()
                .col("delivered_at", MOMENT, "Доставлено").nullable()
                .col("attempts", SMALLINT, "Попыток отправки")
                .col("status", TEXT, "На «сейчас»: доставлено, ошибка, подавлено (тихие часы push 23:00–08:00 по "
                        + "местному, отписка), в очереди").values("delivered", "failed", "suppressed", "pending")
                .pk("id").index("client_id").index("event_key"));

        return s;
    }

    /** Все 31 таблица — в порядке описания (для тестов). */
    public static final int TABLES = 31;
}
