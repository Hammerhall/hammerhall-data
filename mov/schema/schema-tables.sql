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
    id           integer NOT NULL,
    title        text NOT NULL,
    title_ru     text,
    year         smallint NOT NULL,
    release_date date,
    runtime      integer,
    rank         numeric(3,1) NOT NULL
);
COMMENT ON TABLE movies IS 'Фильмы Wikidata: художественные, короткометражные, анимационные, документальные, телефильмы, немые — без фильмов для взрослых; с годом выхода и названием';
COMMENT ON COLUMN movies.id IS 'Фильм — номер элемента Wikidata (Q42 → 42)';
COMMENT ON COLUMN movies.title IS 'Название: метка en, иначе mul, иначе P1476, иначе метка запасного языка';
COMMENT ON COLUMN movies.title_ru IS 'Название по-русски (метка ru)';
COMMENT ON COLUMN movies.year IS 'Год выхода: самая ранняя дата P577 с точностью не ниже года';
COMMENT ON COLUMN movies.release_date IS 'Дата выхода: самая ранняя дата P577 с точностью до дня';
COMMENT ON COLUMN movies.runtime IS 'Хронометраж, минуты (P2047)';
COMMENT ON COLUMN movies.rank IS 'Рейтинг 1.0–9.9 — синтетический: из открытых признаков Wikidata и шума от seed, не оценка качества';

CREATE TABLE actors (
    id         integer NOT NULL,
    first_name text,
    last_name  text NOT NULL,
    gender     text NOT NULL,
    name_ru    text
);
COMMENT ON TABLE actors IS 'Актёры: люди из состава (P161, P725) фильмов профиля';
COMMENT ON COLUMN actors.id IS 'Актёр — номер элемента Wikidata';
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
COMMENT ON COLUMN directors.id IS 'Режиссёр — номер элемента Wikidata';
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
