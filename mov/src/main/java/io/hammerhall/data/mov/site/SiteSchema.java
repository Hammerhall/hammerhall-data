package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;

import java.util.List;

import static io.hammerhall.data.core.schema.ColType.BOOL;
import static io.hammerhall.data.core.schema.ColType.DATE;
import static io.hammerhall.data.core.schema.ColType.INT;
import static io.hammerhall.data.core.schema.ColType.MOMENT;
import static io.hammerhall.data.core.schema.ColType.MONEY;
import static io.hammerhall.data.core.schema.ColType.SMALLINT;
import static io.hammerhall.data.core.schema.ColType.TEXT;

/**
 * Таблицы синтетики «Свартальвхейм» — онлайн-кинотеатр и его сообщество вокруг настоящих фильмов набора: 19 таблиц в
 * трёх группах (сервис, зрители, сообщество). Всё, что касается людей сервиса и их действий, выдумано генератором;
 * {@code movie_id} — фильм профиля ({@code movies}), {@code city_id} — город World ({@code wld-data-v1}
 * {@code cities.id} = geonameid) мягкой ссылкой, без внешнего ключа. Моменты — секунды Unix (UTC), деньги — рубли.
 * {@link #addTo} дописывает таблицы в схему набора после таблиц кино.
 */
public final class SiteSchema {
    private SiteSchema() {
    }

    /** Таблицы модуля {@code site} — в порядке описания. */
    public static final List<String> TABLES = List.of("plans", "plan_prices", "promo_codes", "users", "devices",
            "subscriptions", "payments", "catalog", "purchases", "views", "ratings", "reviews", "review_votes", "watchlist",
            "follows", "collections", "collection_items", "tags", "movie_tags");

    public static final String[] QUALITIES = {"SD", "HD", "FHD", "UHD"};
    public static final String[] CHANNELS = {"organic", "ads", "partner", "referral"};
    public static final String[] DEVICE_KINDS = {"tv", "phone", "tablet", "browser", "set_top_box"};
    public static final String[] OS = {"android", "ios", "ipados", "windows", "macos", "linux", "tizen", "webos",
            "android_tv", "tvos"};
    public static final String[] SUB_STATUS = {"trial", "active", "cancelled", "expired", "switched"};
    public static final String[] CANCEL_REASONS = {"price", "content", "pause", "technical", "other", "payment_failed",
            "plan_change", "account_deleted"};
    public static final String[] METHODS = {"card", "sbp", "wallet", "mobile"};
    public static final String[] PAY_STATUS = {"succeeded", "failed", "refunded"};
    public static final String[] ACCESS = {"subscription", "rent_buy", "free"};
    public static final String[] SOURCES = {"subscription", "rent", "buy", "free"};
    public static final String[] PROMO_KINDS = {"trial_days", "discount_pct"};
    public static final String WLD = "wld-data-v1";

    public static void addTo(Schema s) {
        s.group("service", "Сервис: тарифы и цены, промокоды, каталог «Свартальвхейма»")
                .group("viewers", "Зрители: пользователи, устройства, подписки, оплаты, покупки, просмотры")
                .group("community", "Сообщество: оценки, рецензии, голоса, списки, подписки на людей, подборки, теги");

        s.add(Table.of("plans", "service", "Тарифы онлайн-кинотеатра «Свартальвхейм» (синтетика): месячные и годовые")
                .title("Тарифы")
                .col("id", INT, "Тариф")
                .col("code", TEXT, "Код тарифа")
                .col("name", TEXT, "Название по-русски")
                .col("monthly_rub", MONEY, "Цена в месяц при вводе тарифа, ₽ (у годового — цена года / 12)")
                .col("period_months", SMALLINT, "Период оплаты, месяцев: 1 или 12 (у бесплатного — 1)")
                .col("max_streams", SMALLINT, "Одновременных просмотров")
                .col("max_quality", TEXT, "Наибольшее качество").values(QUALITIES)
                .col("ads", BOOL, "С рекламой")
                .col("introduced_on", DATE, "Введён")
                .col("retired_on", DATE, "Снят с продажи").nullable()
                .pk("id")
                .unique("code")
                .check("period", "period_months IN (1, 12)"));

        s.add(Table.of("plan_prices", "service", "История цен тарифов: цена за период оплаты с даты (по Москве)")
                .title("Цены тарифов")
                .col("plan_id", INT, "Тариф").ref("plans")
                .col("valid_from", DATE, "Действует с (00:00 по Москве)")
                .col("price_rub", MONEY, "Цена за период оплаты, ₽")
                .pk("plan_id", "valid_from"));

        s.add(Table.of("promo_codes", "service", "Промокоды: пробные дни или скидка на первую оплату подписки; часть — "
                        + "партнёрские (выдуманные коды без названий партнёров)")
                .title("Промокоды")
                .col("id", INT, "Промокод")
                .col("code", TEXT, "Код")
                .col("kind", TEXT, "Вид: пробные дни или скидка в процентах").values(PROMO_KINDS)
                .col("value", SMALLINT, "Пробных дней или процент скидки")
                .col("partner", BOOL, "Партнёрский")
                .col("valid_from", DATE, "Действует с")
                .col("valid_to", DATE, "Действует по (включительно)")
                .col("max_uses", INT, "Предел применений; пусто — без предела").nullable()
                .pk("id")
                .unique("code")
                .check("dates", "valid_to >= valid_from"));

        s.add(Table.of("users", "viewers", "Пользователи сервиса (выдуманы): логин, имя, пол, год рождения, город World, "
                        + "регистрация, канал прихода; регистрация — с 2019 года")
                .title("Пользователи")
                .col("id", INT, "Пользователь — по порядку регистрации")
                .col("login", TEXT, "Логин")
                .col("display_name", TEXT, "Отображаемое имя: имя и первая буква фамилии")
                .col("gender", TEXT, "Пол").values("M", "F")
                .col("birth_year", SMALLINT, "Год рождения")
                .col("city_id", INT, "Город — id города World (wld-data-v1, cities.id = geonameid); мягкая ссылка").source(WLD)
                .col("registered_at", MOMENT, "Регистрация")
                .col("channel", TEXT, "Канал прихода").values(CHANNELS)
                .col("referrer_id", INT, "Пригласивший пользователь (канал referral)").ref("users").nullable()
                .col("promo_code_id", INT, "Промокод при регистрации").ref("promo_codes").nullable()
                .col("deleted_at", MOMENT, "Учётная запись удалена").nullable()
                .pk("id")
                .unique("login")
                .index("city_id")
                .check("referral", "(channel = 'referral') = (referrer_id IS NOT NULL)")
                .check("deleted", "deleted_at IS NULL OR deleted_at > registered_at"));

        s.add(Table.of("devices", "viewers", "Устройства пользователей: вид, ОС, первое и последнее появление")
                .title("Устройства")
                .col("id", INT, "Устройство — по первому появлению")
                .col("user_id", INT, "Пользователь").ref("users")
                .col("kind", TEXT, "Вид").values(DEVICE_KINDS)
                .col("os", TEXT, "Операционная система").values(OS)
                .col("first_seen_at", MOMENT, "Первое появление")
                .col("last_seen_at", MOMENT, "Последнее появление (на срез выпуска)")
                .pk("id")
                .index("user_id")
                .check("seen", "last_seen_at >= first_seen_at"));

        s.add(Table.of("subscriptions", "viewers", "Подписки: тариф, пробный период, текущий оплаченный период, состояние на "
                        + "срез; подписки пользователя не пересекаются")
                .title("Подписки")
                .col("id", INT, "Подписка — по началу")
                .col("user_id", INT, "Пользователь").ref("users")
                .col("plan_id", INT, "Тариф").ref("plans")
                .col("started_at", MOMENT, "Начало (у подписок, шедших на начало окна, — настоящее, раньше окна)")
                .col("trial_ends_at", MOMENT, "Конец пробного периода; пусто — без пробного").nullable()
                .col("ends_at", MOMENT, "Конец доступа: конец текущего оплаченного периода (у идущей — после среза) или "
                        + "момент окончания")
                .col("status", TEXT, "Состояние на срез").values(SUB_STATUS)
                .col("auto_renew", BOOL, "Продлевается сама")
                .col("cancelled_at", MOMENT, "Отменена (или истекла после трёх неудачных списаний)").nullable()
                .col("cancel_reason", TEXT, "Причина отмены").values(CANCEL_REASONS).nullable()
                .pk("id")
                .index("user_id")
                .check("trial", "trial_ends_at IS NULL OR (trial_ends_at >= started_at AND trial_ends_at <= ends_at)")
                .check("ends", "ends_at >= started_at"));

        s.add(Table.of("payments", "viewers", "Оплаты: списания за подписку и покупки фильмов, неудачные попытки, возвраты")
                .title("Оплаты")
                .col("id", INT, "Оплата — по моменту")
                .col("user_id", INT, "Пользователь").ref("users")
                .col("subscription_id", INT, "Подписка (списание за период)").ref("subscriptions").nullable()
                .col("purchase_id", INT, "Покупка или прокат").ref("purchases").nullable()
                .col("amount_rub", MONEY, "Сумма, ₽")
                .col("method", TEXT, "Способ оплаты").values(METHODS)
                .col("status", TEXT, "Итог").values(PAY_STATUS)
                .col("created_at", MOMENT, "Попытка списания")
                .col("refunded_at", MOMENT, "Возврат").nullable()
                .pk("id")
                .index("user_id")
                .index("subscription_id")
                .check("one_target", "(subscription_id IS NULL) <> (purchase_id IS NULL)")
                .check("refund", "(status = 'refunded') = (refunded_at IS NOT NULL) AND (refunded_at IS NULL OR refunded_at > created_at)"));

        s.add(Table.of("catalog", "service", "Каталог сервиса: окна прав на фильм (приход и уход), доступ, цены проката и "
                        + "покупки, с какого момента фильм входит в подписку")
                .title("Каталог")
                .col("movie_id", INT, "Фильм").ref("movies")
                .col("added_at", MOMENT, "Пришёл в каталог")
                .col("removed_at", MOMENT, "Ушёл из каталога; пусто — в каталоге на срез").nullable()
                .col("access", TEXT, "Доступ: по подписке, прокат и покупка, бесплатно с рекламой").values(ACCESS)
                .col("rent_price_rub", MONEY, "Прокат на 48 ч в первые 60 дней окна, ₽; позже — на 40 % дешевле (не "
                        + "ниже 99 ₽, «на 9»)").nullable()
                .col("buy_price_rub", MONEY, "Покупка, ₽").nullable()
                .col("subscription_from", MOMENT, "С этого момента фильм окна rent_buy — и по подписке").nullable()
                .pk("movie_id", "added_at")
                .check("window", "removed_at IS NULL OR removed_at > added_at")
                .check("prices", "(access = 'rent_buy') = (rent_price_rub IS NOT NULL)"));

        s.add(Table.of("purchases", "viewers", "Прокат (48 ч) и покупка фильмов")
                .title("Покупки")
                .col("id", INT, "Покупка — по моменту")
                .col("user_id", INT, "Пользователь").ref("users")
                .col("movie_id", INT, "Фильм").ref("movies")
                .col("kind", TEXT, "Прокат или покупка").values("rent", "buy")
                .col("price_rub", MONEY, "Цена, ₽")
                .col("created_at", MOMENT, "Момент покупки")
                .col("expires_at", MOMENT, "Конец проката (через 48 ч); у покупки — пусто").nullable()
                .pk("id")
                .index("user_id"));

        s.add(Table.of("views", "viewers", "Просмотры: кто, на каком устройстве, что, когда, сколько минут, досмотрел ли, "
                        + "в каком качестве и по какому доступу")
                .title("Просмотры")
                .col("id", INT, "Просмотр — по началу")
                .col("user_id", INT, "Пользователь").ref("users")
                .col("device_id", INT, "Устройство").ref("devices")
                .col("movie_id", INT, "Фильм").ref("movies")
                .col("started_at", MOMENT, "Начало")
                .col("watched_min", INT, "Просмотрено минут (идущий на срез — до среза)")
                .col("completed", BOOL, "Досмотрен")
                .col("quality", TEXT, "Качество").values(QUALITIES)
                .col("source", TEXT, "Доступ: подписка, прокат, покупка, бесплатно").values(SOURCES)
                .pk("id")
                .index("user_id")
                .index("movie_id"));

        s.add(Table.of("ratings", "community", "Оценки фильмов: последняя оценка пользователя и число её правок")
                .title("Оценки")
                .col("user_id", INT, "Пользователь").ref("users")
                .col("movie_id", INT, "Фильм").ref("movies")
                .col("rating", SMALLINT, "Оценка 1–10")
                .col("rated_at", MOMENT, "Момент последней оценки")
                .col("changes", SMALLINT, "Сколько раз оценку меняли")
                .pk("user_id", "movie_id")
                .index("movie_id")
                .check("rating", "rating BETWEEN 1 AND 10"));

        s.add(Table.of("reviews", "community", "Рецензии по-русски: оценка автора на момент рецензии, заголовок, текст")
                .title("Рецензии")
                .col("id", INT, "Рецензия — по моменту")
                .col("user_id", INT, "Автор").ref("users")
                .col("movie_id", INT, "Фильм").ref("movies")
                .col("rating", SMALLINT, "Оценка автора на момент рецензии")
                .col("title", TEXT, "Заголовок")
                .col("body", TEXT, "Текст")
                .col("created_at", MOMENT, "Написана")
                .col("edited_at", MOMENT, "Последняя правка").nullable()
                .col("status", TEXT, "Видна или скрыта").values("published", "hidden")
                .pk("id")
                .unique("user_id", "movie_id")
                .check("rating", "rating BETWEEN 1 AND 10"));

        s.add(Table.of("review_votes", "community", "Голоса за рецензии: полезна или нет")
                .title("Голоса за рецензии")
                .col("review_id", INT, "Рецензия").ref("reviews")
                .col("user_id", INT, "Голосующий").ref("users")
                .col("useful", BOOL, "Полезна")
                .col("voted_at", MOMENT, "Момент голоса")
                .pk("review_id", "user_id"));

        s.add(Table.of("watchlist", "community", "Списки «посмотреть»: фильм добавлен, убран (посмотрел или передумал)")
                .title("Списки «посмотреть»")
                .col("user_id", INT, "Пользователь").ref("users")
                .col("movie_id", INT, "Фильм").ref("movies")
                .col("added_at", MOMENT, "Добавлен")
                .col("removed_at", MOMENT, "Убран; пусто — в списке на срез").nullable()
                .col("removed_reason", TEXT, "Почему убран").values("watched", "dropped").nullable()
                .pk("user_id", "movie_id", "added_at")
                .check("removed", "(removed_at IS NULL) = (removed_reason IS NULL)"));

        s.add(Table.of("follows", "community", "Подписки пользователей друг на друга")
                .title("Подписки на людей")
                .col("follower_id", INT, "Кто подписан").ref("users")
                .col("followee_id", INT, "На кого").ref("users")
                .col("followed_at", MOMENT, "Подписался")
                .col("unfollowed_at", MOMENT, "Отписался").nullable()
                .pk("follower_id", "followee_id")
                .index("followee_id")
                .check("not_self", "follower_id <> followee_id")
                .check("unfollow", "unfollowed_at IS NULL OR unfollowed_at > followed_at"));

        s.add(Table.of("collections", "community", "Подборки пользователей")
                .title("Подборки")
                .col("id", INT, "Подборка — по созданию")
                .col("user_id", INT, "Автор").ref("users")
                .col("title", TEXT, "Название")
                .col("is_public", BOOL, "Открыта всем")
                .col("created_at", MOMENT, "Создана")
                .pk("id"));

        s.add(Table.of("collection_items", "community", "Фильмы подборок")
                .title("Фильмы подборок")
                .col("collection_id", INT, "Подборка").ref("collections")
                .col("movie_id", INT, "Фильм").ref("movies")
                .col("position", SMALLINT, "Место в подборке, с 1")
                .col("added_at", MOMENT, "Добавлен")
                .pk("collection_id", "movie_id"));

        s.add(Table.of("tags", "community", "Теги: словарь по-русски")
                .title("Теги")
                .col("id", INT, "Тег")
                .col("name", TEXT, "Тег")
                .pk("id")
                .unique("name"));

        s.add(Table.of("movie_tags", "community", "Теги фильмов от пользователей")
                .title("Теги фильмов")
                .col("user_id", INT, "Пользователь").ref("users")
                .col("movie_id", INT, "Фильм").ref("movies")
                .col("tag_id", INT, "Тег").ref("tags")
                .col("tagged_at", MOMENT, "Момент")
                .pk("user_id", "movie_id", "tag_id"));
    }
}
