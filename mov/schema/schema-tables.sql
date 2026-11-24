-- mov-data-v1 — таблицы. Сгенерировано из описания схемы; руками не править.
-- Порядок развёртывания: schema-tables.sql → загрузка TSV (COPY) → schema-constraints.sql.
-- Моменты — timestamptz; в TSV они — секунды Unix (UTC): загрузчик переводит to_timestamp(...).
-- Деньги — numeric(14,2), рубли; пустое поле TSV — NULL.

-- ======================================================================
-- Кино: фильмы и жанры, актёры и роли, режиссёры, страны и языки фильмов

CREATE TABLE genres (
    id   integer NOT NULL,
    name text NOT NULL
);
COMMENT ON TABLE genres IS 'Жанры: словарь из 24 жанров (id и названия — как в прежнем наборе фильмов)';
COMMENT ON COLUMN genres.id IS 'Жанр';
COMMENT ON COLUMN genres.name IS 'Название по-английски';

CREATE TABLE movies (
    id             integer NOT NULL,
    title          text NOT NULL,
    title_ru       text,
    year           smallint NOT NULL,
    release_date   date,
    runtime        integer,
    rank           numeric(3,1) NOT NULL,
    budget_usd     bigint,
    box_office_usd bigint
);
COMMENT ON TABLE movies IS 'Фильмы Wikidata: художественные, короткометражные, анимационные, документальные, телефильмы, немые — без фильмов для взрослых; с годом выхода и названием';
COMMENT ON COLUMN movies.id IS 'Фильм — номер элемента Wikidata (Q42 → 42)';
COMMENT ON COLUMN movies.title IS 'Название: метка en, иначе mul, иначе P1476, иначе метка запасного языка, иначе метка ru, иначе метка на любом языке';
COMMENT ON COLUMN movies.title_ru IS 'Название по-русски (метка ru)';
COMMENT ON COLUMN movies.year IS 'Год выхода: самая ранняя дата P577 с точностью не ниже года; нет — P1191, P571, P2754';
COMMENT ON COLUMN movies.release_date IS 'Дата выхода: самая ранняя дата P577 с точностью до дня';
COMMENT ON COLUMN movies.runtime IS 'Хронометраж, минуты (P2047)';
COMMENT ON COLUMN movies.rank IS 'Рейтинг 1.0–9.9 — синтетический: из открытых признаков Wikidata и шума от seed, не оценка качества';
COMMENT ON COLUMN movies.budget_usd IS 'Бюджет, долларов США (P2130 в долларах США, наибольший из лучшего ранга)';
COMMENT ON COLUMN movies.box_office_usd IS 'Сборы, долларов США (P2142 в долларах США, наибольшие из лучшего ранга)';

CREATE TABLE actors (
    id         integer NOT NULL,
    first_name text,
    last_name  text NOT NULL,
    gender     text NOT NULL,
    name_ru    text
);
COMMENT ON TABLE actors IS 'Актёры: люди из состава (P161, P725) фильмов профиля';
COMMENT ON COLUMN actors.id IS 'Актёр — номер элемента Wikidata (он же — в people)';
COMMENT ON COLUMN actors.first_name IS 'Имя (у мононима — пусто)';
COMMENT ON COLUMN actors.last_name IS 'Фамилия (у мононима — имя целиком)';
COMMENT ON COLUMN actors.gender IS 'Пол: по P21, иначе по первому имени P735, иначе от seed и id: M, F';
COMMENT ON COLUMN actors.name_ru IS 'Имя по-русски (метка ru целиком)';

CREATE TABLE directors (
    id         integer NOT NULL,
    first_name text,
    last_name  text NOT NULL,
    name_ru    text
);
COMMENT ON TABLE directors IS 'Режиссёры: люди из P57 фильмов профиля';
COMMENT ON COLUMN directors.id IS 'Режиссёр — номер элемента Wikidata (он же — в people)';
COMMENT ON COLUMN directors.first_name IS 'Имя (у мононима — пусто)';
COMMENT ON COLUMN directors.last_name IS 'Фамилия (у мононима — имя целиком)';
COMMENT ON COLUMN directors.name_ru IS 'Имя по-русски (метка ru целиком)';

CREATE TABLE roles (
    actor_id integer NOT NULL,
    movie_id integer NOT NULL,
    role     text
);
COMMENT ON TABLE roles IS 'Роли: актёр в фильме; несколько ролей актёра в фильме — одной строкой через « / »';
COMMENT ON COLUMN roles.actor_id IS 'Актёр';
COMMENT ON COLUMN roles.movie_id IS 'Фильм';
COMMENT ON COLUMN roles.role IS 'Роль: P4633, иначе метка персонажа P453; нет — пусто';

CREATE TABLE movies_directors (
    director_id integer NOT NULL,
    movie_id    integer NOT NULL
);
COMMENT ON TABLE movies_directors IS 'Режиссёры фильмов (P57)';
COMMENT ON COLUMN movies_directors.director_id IS 'Режиссёр';
COMMENT ON COLUMN movies_directors.movie_id IS 'Фильм';

CREATE TABLE movies_genres (
    movie_id integer NOT NULL,
    genre_id integer NOT NULL
);
COMMENT ON TABLE movies_genres IS 'Жанры фильмов: по классу P31, хронометражу и P136 через якоря словаря';
COMMENT ON COLUMN movies_genres.movie_id IS 'Фильм';
COMMENT ON COLUMN movies_genres.genre_id IS 'Жанр';

CREATE TABLE directors_genres (
    director_id integer NOT NULL,
    genre_id    integer NOT NULL,
    prob        numeric(5,4) NOT NULL
);
COMMENT ON TABLE directors_genres IS 'Жанры режиссёров: доля его фильмов профиля с жанром среди его фильмов с жанрами';
COMMENT ON COLUMN directors_genres.director_id IS 'Режиссёр';
COMMENT ON COLUMN directors_genres.genre_id IS 'Жанр';
COMMENT ON COLUMN directors_genres.prob IS 'Доля фильмов режиссёра с этим жанром, 4 знака';

CREATE TABLE movies_countries (
    movie_id     integer NOT NULL,
    country_code text NOT NULL
);
COMMENT ON TABLE movies_countries IS 'Страны фильмов (P495): код ISO 3166-1 alpha-3';
COMMENT ON COLUMN movies_countries.movie_id IS 'Фильм';
COMMENT ON COLUMN movies_countries.country_code IS 'Страна — ISO 3166-1 alpha-3';

CREATE TABLE movies_languages (
    movie_id      integer NOT NULL,
    language_code text NOT NULL
);
COMMENT ON TABLE movies_languages IS 'Языки фильмов (P364): код ISO 639-1, иначе ISO 639-3';
COMMENT ON COLUMN movies_languages.movie_id IS 'Фильм';
COMMENT ON COLUMN movies_languages.language_code IS 'Язык — ISO 639-1, иначе ISO 639-3';

-- ======================================================================
-- Люди: все люди фильмов профиля, их гражданство и съёмочные группы фильмов

CREATE TABLE people (
    id            integer NOT NULL,
    first_name    text,
    last_name     text NOT NULL,
    name_ru       text,
    gender        text,
    birth_date    date,
    birth_year    smallint,
    death_date    date,
    death_year    smallint,
    birth_city_id integer
);
COMMENT ON TABLE people IS 'Люди: актёры, режиссёры и съёмочная группа фильмов профиля — каждый один раз';
COMMENT ON COLUMN people.id IS 'Человек — номер элемента Wikidata';
COMMENT ON COLUMN people.first_name IS 'Имя (у мононима — пусто)';
COMMENT ON COLUMN people.last_name IS 'Фамилия (у мононима — имя целиком)';
COMMENT ON COLUMN people.name_ru IS 'Имя по-русски (метка ru целиком)';
COMMENT ON COLUMN people.gender IS 'Пол: по P21 (иное значение — пусто), иначе по первому имени P735, иначе от seed и id; у актёра — как в actors: M, F';
COMMENT ON COLUMN people.birth_date IS 'Дата рождения: самая ранняя P569 с точностью до дня';
COMMENT ON COLUMN people.birth_year IS 'Год рождения: самая ранняя P569 с точностью не ниже года';
COMMENT ON COLUMN people.death_date IS 'Дата смерти (P570) — при дате рождения не позже';
COMMENT ON COLUMN people.death_year IS 'Год смерти (P570) — при годе рождения не больше';
COMMENT ON COLUMN people.birth_city_id IS 'Город рождения — GeoNames ID места P19, он же id города набора «Мир» (wld-data-v1, cities.id); связь мягкая, без внешнего ключа';

CREATE TABLE people_countries (
    person_id    integer NOT NULL,
    country_code text NOT NULL
);
COMMENT ON TABLE people_countries IS 'Гражданство людей (P27): код ISO 3166-1 alpha-3';
COMMENT ON COLUMN people_countries.person_id IS 'Человек';
COMMENT ON COLUMN people_countries.country_code IS 'Страна — ISO 3166-1 alpha-3';

CREATE TABLE movies_crew (
    movie_id  integer NOT NULL,
    person_id integer NOT NULL,
    job       text NOT NULL
);
COMMENT ON TABLE movies_crew IS 'Съёмочные группы фильмов: сценаристы (P58), композиторы (P86), операторы (P344), продюсеры (P162), монтажёры (P1040)';
COMMENT ON COLUMN movies_crew.movie_id IS 'Фильм';
COMMENT ON COLUMN movies_crew.person_id IS 'Человек';
COMMENT ON COLUMN movies_crew.job IS 'Должность: writer, composer, cinematographer, producer, editor';

-- ======================================================================
-- Студии и награды: студии и прокатчики, серии фильмов и продолжения, награды и номинации

CREATE TABLE companies (
    id           integer NOT NULL,
    name         text NOT NULL,
    name_ru      text,
    country_code text
);
COMMENT ON TABLE companies IS 'Студии (P272) и прокатчики (P750) фильмов профиля с названием';
COMMENT ON COLUMN companies.id IS 'Компания — номер элемента Wikidata';
COMMENT ON COLUMN companies.name IS 'Название: метка en, иначе mul';
COMMENT ON COLUMN companies.name_ru IS 'Название по-русски (метка ru)';
COMMENT ON COLUMN companies.country_code IS 'Страна (P17) — ISO 3166-1 alpha-3';

CREATE TABLE movies_companies (
    movie_id   integer NOT NULL,
    company_id integer NOT NULL,
    role       text NOT NULL
);
COMMENT ON TABLE movies_companies IS 'Студии и прокатчики фильмов';
COMMENT ON COLUMN movies_companies.movie_id IS 'Фильм';
COMMENT ON COLUMN movies_companies.company_id IS 'Компания';
COMMENT ON COLUMN movies_companies.role IS 'Роль: студия (P272) или прокатчик (P750): production, distribution';

CREATE TABLE series (
    id      integer NOT NULL,
    name    text NOT NULL,
    name_ru text
);
COMMENT ON TABLE series IS 'Серии фильмов (P179) фильмов профиля с названием';
COMMENT ON COLUMN series.id IS 'Серия — номер элемента Wikidata';
COMMENT ON COLUMN series.name IS 'Название: метка en, иначе mul';
COMMENT ON COLUMN series.name_ru IS 'Название по-русски (метка ru)';

CREATE TABLE movies_series (
    movie_id  integer NOT NULL,
    series_id integer NOT NULL,
    ordinal   integer
);
COMMENT ON TABLE movies_series IS 'Фильмы в сериях (P179) и номер в серии (P1545)';
COMMENT ON COLUMN movies_series.movie_id IS 'Фильм';
COMMENT ON COLUMN movies_series.series_id IS 'Серия';
COMMENT ON COLUMN movies_series.ordinal IS 'Номер в серии: наименьшее целое P1545 от 1 до 10000';

CREATE TABLE movies_sequels (
    movie_id      integer NOT NULL,
    next_movie_id integer NOT NULL
);
COMMENT ON TABLE movies_sequels IS 'Продолжения: фильм и следующий за ним (P156 и обратное P155), оба — фильмы профиля';
COMMENT ON COLUMN movies_sequels.movie_id IS 'Фильм';
COMMENT ON COLUMN movies_sequels.next_movie_id IS 'Следующий фильм';

CREATE TABLE awards (
    id      integer NOT NULL,
    name    text NOT NULL,
    name_ru text
);
COMMENT ON TABLE awards IS 'Награды (P166) и номинации (P1411) фильмов профиля с названием';
COMMENT ON COLUMN awards.id IS 'Награда — номер элемента Wikidata';
COMMENT ON COLUMN awards.name IS 'Название: метка en, иначе mul';
COMMENT ON COLUMN awards.name_ru IS 'Название по-русски (метка ru)';

CREATE TABLE movies_awards (
    movie_id integer NOT NULL,
    award_id integer NOT NULL,
    won      boolean NOT NULL,
    year     smallint
);
COMMENT ON TABLE movies_awards IS 'Награды и номинации фильмов; выиграл и был номинирован — две строки';
COMMENT ON COLUMN movies_awards.movie_id IS 'Фильм';
COMMENT ON COLUMN movies_awards.award_id IS 'Награда';
COMMENT ON COLUMN movies_awards.won IS 'Награда получена (P166) — t, номинация (P1411) — f';
COMMENT ON COLUMN movies_awards.year IS 'Год: самый ранний год P585 с точностью не ниже года';

-- ======================================================================
-- Выход и названия: даты выхода по странам, названия на языках, места съёмок

CREATE TABLE movies_releases (
    movie_id     integer NOT NULL,
    country_code text NOT NULL,
    release_date date NOT NULL
);
COMMENT ON TABLE movies_releases IS 'Выход по странам: самая ранняя дата P577 с местом P291 и точностью до дня на страну';
COMMENT ON COLUMN movies_releases.movie_id IS 'Фильм';
COMMENT ON COLUMN movies_releases.country_code IS 'Страна — ISO 3166-1 alpha-3';
COMMENT ON COLUMN movies_releases.release_date IS 'Дата выхода в стране';

CREATE TABLE movies_titles (
    movie_id integer NOT NULL,
    lang     text NOT NULL,
    title    text NOT NULL
);
COMMENT ON TABLE movies_titles IS 'Названия фильмов по языкам: метки Wikidata';
COMMENT ON COLUMN movies_titles.movie_id IS 'Фильм';
COMMENT ON COLUMN movies_titles.lang IS 'Язык — код языка метки Wikidata';
COMMENT ON COLUMN movies_titles.title IS 'Название (метка)';

CREATE TABLE movies_locations (
    movie_id integer NOT NULL,
    city_id  integer NOT NULL
);
COMMENT ON TABLE movies_locations IS 'Места съёмок (P915) — города с GeoNames ID';
COMMENT ON COLUMN movies_locations.movie_id IS 'Фильм';
COMMENT ON COLUMN movies_locations.city_id IS 'Город — GeoNames ID места P915, он же id города набора «Мир» (wld-data-v1, cities.id); связь мягкая, без внешнего ключа';

-- ======================================================================
-- Сервис: тарифы и цены, промокоды, каталог «Свартальвхейма»

CREATE TABLE plans (
    id            integer NOT NULL,
    code          text NOT NULL,
    name          text NOT NULL,
    monthly_rub   numeric(14,2) NOT NULL,
    period_months smallint NOT NULL,
    max_streams   smallint NOT NULL,
    max_quality   text NOT NULL,
    ads           boolean NOT NULL,
    introduced_on date NOT NULL,
    retired_on    date
);
COMMENT ON TABLE plans IS 'Тарифы онлайн-кинотеатра «Свартальвхейм» (синтетика): месячные и годовые';
COMMENT ON COLUMN plans.id IS 'Тариф';
COMMENT ON COLUMN plans.code IS 'Код тарифа';
COMMENT ON COLUMN plans.name IS 'Название по-русски';
COMMENT ON COLUMN plans.monthly_rub IS 'Цена в месяц при вводе тарифа, ₽ (у годового — цена года / 12)';
COMMENT ON COLUMN plans.period_months IS 'Период оплаты, месяцев: 1 или 12 (у бесплатного — 1)';
COMMENT ON COLUMN plans.max_streams IS 'Одновременных просмотров';
COMMENT ON COLUMN plans.max_quality IS 'Наибольшее качество: SD, HD, FHD, UHD';
COMMENT ON COLUMN plans.ads IS 'С рекламой';
COMMENT ON COLUMN plans.introduced_on IS 'Введён';
COMMENT ON COLUMN plans.retired_on IS 'Снят с продажи';

CREATE TABLE plan_prices (
    plan_id    integer NOT NULL,
    valid_from date NOT NULL,
    price_rub  numeric(14,2) NOT NULL
);
COMMENT ON TABLE plan_prices IS 'История цен тарифов: цена за период оплаты с даты (по Москве)';
COMMENT ON COLUMN plan_prices.plan_id IS 'Тариф';
COMMENT ON COLUMN plan_prices.valid_from IS 'Действует с (00:00 по Москве)';
COMMENT ON COLUMN plan_prices.price_rub IS 'Цена за период оплаты, ₽';

CREATE TABLE promo_codes (
    id         integer NOT NULL,
    code       text NOT NULL,
    kind       text NOT NULL,
    value      smallint NOT NULL,
    partner    boolean NOT NULL,
    valid_from date NOT NULL,
    valid_to   date NOT NULL,
    max_uses   integer
);
COMMENT ON TABLE promo_codes IS 'Промокоды: пробные дни или скидка на первую оплату подписки; часть — партнёрские (выдуманные коды без названий партнёров)';
COMMENT ON COLUMN promo_codes.id IS 'Промокод';
COMMENT ON COLUMN promo_codes.code IS 'Код';
COMMENT ON COLUMN promo_codes.kind IS 'Вид: пробные дни или скидка в процентах: trial_days, discount_pct';
COMMENT ON COLUMN promo_codes.value IS 'Пробных дней или процент скидки';
COMMENT ON COLUMN promo_codes.partner IS 'Партнёрский';
COMMENT ON COLUMN promo_codes.valid_from IS 'Действует с';
COMMENT ON COLUMN promo_codes.valid_to IS 'Действует по (включительно)';
COMMENT ON COLUMN promo_codes.max_uses IS 'Предел применений; пусто — без предела';

-- ======================================================================
-- Зрители: пользователи, устройства, подписки, оплаты, покупки, просмотры

CREATE TABLE users (
    id            integer NOT NULL,
    login         text NOT NULL,
    display_name  text NOT NULL,
    gender        text NOT NULL,
    birth_year    smallint NOT NULL,
    city_id       integer NOT NULL,
    registered_at timestamptz NOT NULL,
    channel       text NOT NULL,
    referrer_id   integer,
    promo_code_id integer,
    deleted_at    timestamptz
);
COMMENT ON TABLE users IS 'Пользователи сервиса (выдуманы): логин, имя, пол, год рождения, город World, регистрация, канал прихода; регистрация — с 2019 года';
COMMENT ON COLUMN users.id IS 'Пользователь — по порядку регистрации';
COMMENT ON COLUMN users.login IS 'Логин';
COMMENT ON COLUMN users.display_name IS 'Отображаемое имя: имя и первая буква фамилии';
COMMENT ON COLUMN users.gender IS 'Пол: M, F';
COMMENT ON COLUMN users.birth_year IS 'Год рождения';
COMMENT ON COLUMN users.city_id IS 'Город — id города World (wld-data-v1, cities.id = geonameid); мягкая ссылка';
COMMENT ON COLUMN users.registered_at IS 'Регистрация (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN users.channel IS 'Канал прихода: organic, ads, partner, referral';
COMMENT ON COLUMN users.referrer_id IS 'Пригласивший пользователь (канал referral)';
COMMENT ON COLUMN users.promo_code_id IS 'Промокод при регистрации';
COMMENT ON COLUMN users.deleted_at IS 'Учётная запись удалена (в TSV — секунды Unix, UTC)';

CREATE TABLE devices (
    id            integer NOT NULL,
    user_id       integer NOT NULL,
    kind          text NOT NULL,
    os            text NOT NULL,
    first_seen_at timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL
);
COMMENT ON TABLE devices IS 'Устройства пользователей: вид, ОС, первое и последнее появление';
COMMENT ON COLUMN devices.id IS 'Устройство — по первому появлению';
COMMENT ON COLUMN devices.user_id IS 'Пользователь';
COMMENT ON COLUMN devices.kind IS 'Вид: tv, phone, tablet, browser, set_top_box';
COMMENT ON COLUMN devices.os IS 'Операционная система: android, ios, ipados, windows, macos, linux, tizen, webos, android_tv, tvos';
COMMENT ON COLUMN devices.first_seen_at IS 'Первое появление (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN devices.last_seen_at IS 'Последнее появление (на срез выпуска) (в TSV — секунды Unix, UTC)';

CREATE TABLE subscriptions (
    id            integer NOT NULL,
    user_id       integer NOT NULL,
    plan_id       integer NOT NULL,
    started_at    timestamptz NOT NULL,
    trial_ends_at timestamptz,
    ends_at       timestamptz NOT NULL,
    status        text NOT NULL,
    auto_renew    boolean NOT NULL,
    cancelled_at  timestamptz,
    cancel_reason text
);
COMMENT ON TABLE subscriptions IS 'Подписки: тариф, пробный период, текущий оплаченный период, состояние на срез; подписки пользователя не пересекаются';
COMMENT ON COLUMN subscriptions.id IS 'Подписка — по началу';
COMMENT ON COLUMN subscriptions.user_id IS 'Пользователь';
COMMENT ON COLUMN subscriptions.plan_id IS 'Тариф';
COMMENT ON COLUMN subscriptions.started_at IS 'Начало (у подписок, шедших на начало окна, — настоящее, раньше окна) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN subscriptions.trial_ends_at IS 'Конец пробного периода; пусто — без пробного (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN subscriptions.ends_at IS 'Конец доступа: конец текущего оплаченного периода (у идущей — после среза) или момент окончания (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN subscriptions.status IS 'Состояние на срез: trial, active, cancelled, expired, switched';
COMMENT ON COLUMN subscriptions.auto_renew IS 'Продлевается сама';
COMMENT ON COLUMN subscriptions.cancelled_at IS 'Отменена (или истекла после трёх неудачных списаний) (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN subscriptions.cancel_reason IS 'Причина отмены: price, content, pause, technical, other, payment_failed, plan_change, account_deleted';

CREATE TABLE payments (
    id              integer NOT NULL,
    user_id         integer NOT NULL,
    subscription_id integer,
    purchase_id     integer,
    amount_rub      numeric(14,2) NOT NULL,
    method          text NOT NULL,
    status          text NOT NULL,
    created_at      timestamptz NOT NULL,
    refunded_at     timestamptz
);
COMMENT ON TABLE payments IS 'Оплаты: списания за подписку и покупки фильмов, неудачные попытки, возвраты';
COMMENT ON COLUMN payments.id IS 'Оплата — по моменту';
COMMENT ON COLUMN payments.user_id IS 'Пользователь';
COMMENT ON COLUMN payments.subscription_id IS 'Подписка (списание за период)';
COMMENT ON COLUMN payments.purchase_id IS 'Покупка или прокат';
COMMENT ON COLUMN payments.amount_rub IS 'Сумма, ₽';
COMMENT ON COLUMN payments.method IS 'Способ оплаты: card, sbp, wallet, mobile';
COMMENT ON COLUMN payments.status IS 'Итог: succeeded, failed, refunded';
COMMENT ON COLUMN payments.created_at IS 'Попытка списания (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN payments.refunded_at IS 'Возврат (в TSV — секунды Unix, UTC)';

-- ======================================================================
-- Сервис: тарифы и цены, промокоды, каталог «Свартальвхейма»

CREATE TABLE catalog (
    movie_id          integer NOT NULL,
    added_at          timestamptz NOT NULL,
    removed_at        timestamptz,
    access            text NOT NULL,
    rent_price_rub    numeric(14,2),
    buy_price_rub     numeric(14,2),
    subscription_from timestamptz
);
COMMENT ON TABLE catalog IS 'Каталог сервиса: окна прав на фильм (приход и уход), доступ, цены проката и покупки, с какого момента фильм входит в подписку';
COMMENT ON COLUMN catalog.movie_id IS 'Фильм';
COMMENT ON COLUMN catalog.added_at IS 'Пришёл в каталог (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN catalog.removed_at IS 'Ушёл из каталога; пусто — в каталоге на срез (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN catalog.access IS 'Доступ: по подписке, прокат и покупка, бесплатно с рекламой: subscription, rent_buy, free';
COMMENT ON COLUMN catalog.rent_price_rub IS 'Прокат на 48 ч в первые 60 дней окна, ₽; позже — на 40 % дешевле (не ниже 99 ₽, «на 9»)';
COMMENT ON COLUMN catalog.buy_price_rub IS 'Покупка, ₽';
COMMENT ON COLUMN catalog.subscription_from IS 'С этого момента фильм окна rent_buy — и по подписке (в TSV — секунды Unix, UTC)';

-- ======================================================================
-- Зрители: пользователи, устройства, подписки, оплаты, покупки, просмотры

CREATE TABLE purchases (
    id         integer NOT NULL,
    user_id    integer NOT NULL,
    movie_id   integer NOT NULL,
    kind       text NOT NULL,
    price_rub  numeric(14,2) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz
);
COMMENT ON TABLE purchases IS 'Прокат (48 ч) и покупка фильмов';
COMMENT ON COLUMN purchases.id IS 'Покупка — по моменту';
COMMENT ON COLUMN purchases.user_id IS 'Пользователь';
COMMENT ON COLUMN purchases.movie_id IS 'Фильм';
COMMENT ON COLUMN purchases.kind IS 'Прокат или покупка: rent, buy';
COMMENT ON COLUMN purchases.price_rub IS 'Цена, ₽';
COMMENT ON COLUMN purchases.created_at IS 'Момент покупки (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN purchases.expires_at IS 'Конец проката (через 48 ч); у покупки — пусто (в TSV — секунды Unix, UTC)';

CREATE TABLE views (
    id          integer NOT NULL,
    user_id     integer NOT NULL,
    device_id   integer NOT NULL,
    movie_id    integer NOT NULL,
    started_at  timestamptz NOT NULL,
    watched_min integer NOT NULL,
    completed   boolean NOT NULL,
    quality     text NOT NULL,
    source      text NOT NULL
);
COMMENT ON TABLE views IS 'Просмотры: кто, на каком устройстве, что, когда, сколько минут, досмотрел ли, в каком качестве и по какому доступу';
COMMENT ON COLUMN views.id IS 'Просмотр — по началу';
COMMENT ON COLUMN views.user_id IS 'Пользователь';
COMMENT ON COLUMN views.device_id IS 'Устройство';
COMMENT ON COLUMN views.movie_id IS 'Фильм';
COMMENT ON COLUMN views.started_at IS 'Начало (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN views.watched_min IS 'Просмотрено минут (идущий на срез — до среза)';
COMMENT ON COLUMN views.completed IS 'Досмотрен';
COMMENT ON COLUMN views.quality IS 'Качество: SD, HD, FHD, UHD';
COMMENT ON COLUMN views.source IS 'Доступ: подписка, прокат, покупка, бесплатно: subscription, rent, buy, free';

-- ======================================================================
-- Сообщество: оценки, рецензии, голоса, списки, подписки на людей, подборки, теги

CREATE TABLE ratings (
    user_id  integer NOT NULL,
    movie_id integer NOT NULL,
    rating   smallint NOT NULL,
    rated_at timestamptz NOT NULL,
    changes  smallint NOT NULL
);
COMMENT ON TABLE ratings IS 'Оценки фильмов: последняя оценка пользователя и число её правок';
COMMENT ON COLUMN ratings.user_id IS 'Пользователь';
COMMENT ON COLUMN ratings.movie_id IS 'Фильм';
COMMENT ON COLUMN ratings.rating IS 'Оценка 1–10';
COMMENT ON COLUMN ratings.rated_at IS 'Момент последней оценки (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN ratings.changes IS 'Сколько раз оценку меняли';

CREATE TABLE reviews (
    id         integer NOT NULL,
    user_id    integer NOT NULL,
    movie_id   integer NOT NULL,
    rating     smallint NOT NULL,
    title      text NOT NULL,
    body       text NOT NULL,
    created_at timestamptz NOT NULL,
    edited_at  timestamptz,
    status     text NOT NULL
);
COMMENT ON TABLE reviews IS 'Рецензии по-русски: оценка автора на момент рецензии, заголовок, текст';
COMMENT ON COLUMN reviews.id IS 'Рецензия — по моменту';
COMMENT ON COLUMN reviews.user_id IS 'Автор';
COMMENT ON COLUMN reviews.movie_id IS 'Фильм';
COMMENT ON COLUMN reviews.rating IS 'Оценка автора на момент рецензии';
COMMENT ON COLUMN reviews.title IS 'Заголовок';
COMMENT ON COLUMN reviews.body IS 'Текст';
COMMENT ON COLUMN reviews.created_at IS 'Написана (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN reviews.edited_at IS 'Последняя правка (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN reviews.status IS 'Видна или скрыта: published, hidden';

CREATE TABLE review_votes (
    review_id integer NOT NULL,
    user_id   integer NOT NULL,
    useful    boolean NOT NULL,
    voted_at  timestamptz NOT NULL
);
COMMENT ON TABLE review_votes IS 'Голоса за рецензии: полезна или нет';
COMMENT ON COLUMN review_votes.review_id IS 'Рецензия';
COMMENT ON COLUMN review_votes.user_id IS 'Голосующий';
COMMENT ON COLUMN review_votes.useful IS 'Полезна';
COMMENT ON COLUMN review_votes.voted_at IS 'Момент голоса (в TSV — секунды Unix, UTC)';

CREATE TABLE watchlist (
    user_id        integer NOT NULL,
    movie_id       integer NOT NULL,
    added_at       timestamptz NOT NULL,
    removed_at     timestamptz,
    removed_reason text
);
COMMENT ON TABLE watchlist IS 'Списки «посмотреть»: фильм добавлен, убран (посмотрел или передумал)';
COMMENT ON COLUMN watchlist.user_id IS 'Пользователь';
COMMENT ON COLUMN watchlist.movie_id IS 'Фильм';
COMMENT ON COLUMN watchlist.added_at IS 'Добавлен (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN watchlist.removed_at IS 'Убран; пусто — в списке на срез (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN watchlist.removed_reason IS 'Почему убран: watched, dropped';

CREATE TABLE follows (
    follower_id   integer NOT NULL,
    followee_id   integer NOT NULL,
    followed_at   timestamptz NOT NULL,
    unfollowed_at timestamptz
);
COMMENT ON TABLE follows IS 'Подписки пользователей друг на друга';
COMMENT ON COLUMN follows.follower_id IS 'Кто подписан';
COMMENT ON COLUMN follows.followee_id IS 'На кого';
COMMENT ON COLUMN follows.followed_at IS 'Подписался (в TSV — секунды Unix, UTC)';
COMMENT ON COLUMN follows.unfollowed_at IS 'Отписался (в TSV — секунды Unix, UTC)';

CREATE TABLE collections (
    id         integer NOT NULL,
    user_id    integer NOT NULL,
    title      text NOT NULL,
    is_public  boolean NOT NULL,
    created_at timestamptz NOT NULL
);
COMMENT ON TABLE collections IS 'Подборки пользователей';
COMMENT ON COLUMN collections.id IS 'Подборка — по созданию';
COMMENT ON COLUMN collections.user_id IS 'Автор';
COMMENT ON COLUMN collections.title IS 'Название';
COMMENT ON COLUMN collections.is_public IS 'Открыта всем';
COMMENT ON COLUMN collections.created_at IS 'Создана (в TSV — секунды Unix, UTC)';

CREATE TABLE collection_items (
    collection_id integer NOT NULL,
    movie_id      integer NOT NULL,
    position      smallint NOT NULL,
    added_at      timestamptz NOT NULL
);
COMMENT ON TABLE collection_items IS 'Фильмы подборок';
COMMENT ON COLUMN collection_items.collection_id IS 'Подборка';
COMMENT ON COLUMN collection_items.movie_id IS 'Фильм';
COMMENT ON COLUMN collection_items.position IS 'Место в подборке, с 1';
COMMENT ON COLUMN collection_items.added_at IS 'Добавлен (в TSV — секунды Unix, UTC)';

CREATE TABLE tags (
    id   integer NOT NULL,
    name text NOT NULL
);
COMMENT ON TABLE tags IS 'Теги: словарь по-русски';
COMMENT ON COLUMN tags.id IS 'Тег';
COMMENT ON COLUMN tags.name IS 'Тег';

CREATE TABLE movie_tags (
    user_id   integer NOT NULL,
    movie_id  integer NOT NULL,
    tag_id    integer NOT NULL,
    tagged_at timestamptz NOT NULL
);
COMMENT ON TABLE movie_tags IS 'Теги фильмов от пользователей';
COMMENT ON COLUMN movie_tags.user_id IS 'Пользователь';
COMMENT ON COLUMN movie_tags.movie_id IS 'Фильм';
COMMENT ON COLUMN movie_tags.tag_id IS 'Тег';
COMMENT ON COLUMN movie_tags.tagged_at IS 'Момент (в TSV — секунды Unix, UTC)';
