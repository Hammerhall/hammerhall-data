-- wld-data-v1 — таблицы. Сгенерировано из описания схемы; руками не править.
-- Порядок развёртывания: schema-tables.sql → загрузка TSV (COPY) → schema-constraints.sql.
-- Моменты — timestamptz; в TSV они — секунды Unix (UTC): загрузчик переводит to_timestamp(...).
-- Деньги — numeric(14,2), рубли; пустое поле TSV — NULL.

-- ======================================================================
-- Мир: страны, города, языки, соседство, ряды по годам

CREATE TABLE countries (
    code            text NOT NULL,
    code2           text NOT NULL,
    name            text NOT NULL,
    name_ru         text,
    local_name      text,
    continent       text NOT NULL,
    region          text,
    region_ru       text,
    surface_area    numeric(16,4),
    population      bigint,
    life_expectancy numeric(6,3),
    gnp             numeric(16,2),
    gnp_old         numeric(16,2),
    capital         integer
);
COMMENT ON TABLE countries IS 'Страны и территории: коды ISO, названия, часть света, регион ООН, площадь, население, продолжительность жизни, ВНП, столица';
COMMENT ON COLUMN countries.code IS 'Код ISO 3166-1 alpha-3';
COMMENT ON COLUMN countries.code2 IS 'Код ISO 3166-1 alpha-2';
COMMENT ON COLUMN countries.name IS 'Название по-английски';
COMMENT ON COLUMN countries.name_ru IS 'Название по-русски';
COMMENT ON COLUMN countries.local_name IS 'Название на главном официальном языке страны';
COMMENT ON COLUMN countries.continent IS 'Часть света: Africa, Antarctica, Asia, Europe, North America, Oceania, South America';
COMMENT ON COLUMN countries.region IS 'Подрегион ООН (M49) по-английски';
COMMENT ON COLUMN countries.region_ru IS 'Подрегион ООН (M49) по-русски';
COMMENT ON COLUMN countries.surface_area IS 'Площадь, км², последний год с данными';
COMMENT ON COLUMN countries.population IS 'Население, последний год с данными';
COMMENT ON COLUMN countries.life_expectancy IS 'Ожидаемая продолжительность жизни при рождении, лет, последний год с данными';
COMMENT ON COLUMN countries.gnp IS 'ВНП (GNI), млн долларов США в текущих ценах, последний год с данными';
COMMENT ON COLUMN countries.gnp_old IS 'ВНП (GNI) за год до последнего, млн долларов США';
COMMENT ON COLUMN countries.capital IS 'Столица';

CREATE TABLE cities (
    id           integer NOT NULL,
    name         text NOT NULL,
    name_ru      text,
    country_code text NOT NULL,
    district     text,
    population   integer NOT NULL,
    latitude     numeric(10,7) NOT NULL,
    longitude    numeric(10,7) NOT NULL,
    timezone     text
);
COMMENT ON TABLE cities IS 'Города: населённые пункты GeoNames от порога населения профиля и все столицы';
COMMENT ON COLUMN cities.id IS 'Город — geonameid GeoNames';
COMMENT ON COLUMN cities.name IS 'Название (GeoNames, латиница)';
COMMENT ON COLUMN cities.name_ru IS 'Название по-русски';
COMMENT ON COLUMN cities.country_code IS 'Страна';
COMMENT ON COLUMN cities.district IS 'Регион первого уровня (GeoNames admin1)';
COMMENT ON COLUMN cities.population IS 'Население';
COMMENT ON COLUMN cities.latitude IS 'Широта, градусы (WGS 84), как в источнике';
COMMENT ON COLUMN cities.longitude IS 'Долгота, градусы (WGS 84), как в источнике';
COMMENT ON COLUMN cities.timezone IS 'Часовой пояс (IANA)';

CREATE TABLE country_languages (
    country_code  text NOT NULL,
    language_code text NOT NULL,
    language      text NOT NULL,
    language_ru   text,
    official      boolean NOT NULL,
    percentage    numeric(8,4) NOT NULL
);
COMMENT ON TABLE country_languages IS 'Языки стран: доля населения и официальный статус (CLDR); языки без английского названия не берутся';
COMMENT ON COLUMN country_languages.country_code IS 'Страна';
COMMENT ON COLUMN country_languages.language_code IS 'Код языка CLDR (ISO 639; с письменностью — через дефис: zh-Hant)';
COMMENT ON COLUMN country_languages.language IS 'Язык по-английски';
COMMENT ON COLUMN country_languages.language_ru IS 'Язык по-русски';
COMMENT ON COLUMN country_languages.official IS 'Официальный: статус CLDR official или de_facto_official';
COMMENT ON COLUMN country_languages.percentage IS 'Доля населения страны, %';

CREATE TABLE country_borders (
    country_code   text NOT NULL,
    neighbour_code text NOT NULL
);
COMMENT ON TABLE country_borders IS 'Соседство стран: пара в обе стороны (GeoNames)';
COMMENT ON COLUMN country_borders.country_code IS 'Страна';
COMMENT ON COLUMN country_borders.neighbour_code IS 'Соседняя страна';

CREATE TABLE country_years (
    country_code    text NOT NULL,
    year            smallint NOT NULL,
    population      bigint,
    gnp             numeric(16,2),
    gdp             numeric(16,2),
    life_expectancy numeric(6,3)
);
COMMENT ON TABLE country_years IS 'Страны по годам: население, ВНП, ВВП, продолжительность жизни (World Bank)';
COMMENT ON COLUMN country_years.country_code IS 'Страна';
COMMENT ON COLUMN country_years.year IS 'Год';
COMMENT ON COLUMN country_years.population IS 'Население';
COMMENT ON COLUMN country_years.gnp IS 'ВНП (GNI), млн долларов США в текущих ценах';
COMMENT ON COLUMN country_years.gdp IS 'ВВП (GDP), млн долларов США в текущих ценах';
COMMENT ON COLUMN country_years.life_expectancy IS 'Ожидаемая продолжительность жизни при рождении, лет';
