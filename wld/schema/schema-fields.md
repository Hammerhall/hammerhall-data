# wld-data-v1 — поля

### Мир: страны, города, языки, соседство, ряды по годам

#### `countries` — Страны и территории: коды ISO, названия, часть света, регион ООН, площадь, население, продолжительность жизни, ВНП, столица

Ключ — `code`; строки упорядочены по `code`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `code` | text |  | Код ISO 3166-1 alpha-3 | GeoNames, как есть |
| `code2` | text |  | Код ISO 3166-1 alpha-2 | GeoNames, как есть |
| `name` | text |  | Название по-английски | Unicode CLDR, как есть |
| `name_ru` | text | да | Название по-русски | Unicode CLDR, как есть |
| `local_name` | text | да | Название на главном официальном языке страны | Unicode CLDR, как есть |
| `continent` | text |  | Часть света: `Africa`, `Antarctica`, `Asia`, `Europe`, `North America`, `Oceania`, `South America` | GeoNames |
| `region` | text | да | Подрегион ООН (M49) по-английски | Unicode CLDR, как есть |
| `region_ru` | text | да | Подрегион ООН (M49) по-русски | Unicode CLDR, как есть |
| `surface_area` | decimal | да | Площадь, км², последний год с данными | World Bank, иначе GeoNames |
| `population` | bigint | да | Население, последний год с данными | World Bank, иначе GeoNames |
| `life_expectancy` | decimal | да | Ожидаемая продолжительность жизни при рождении, лет, последний год с данными | World Bank |
| `gnp` | decimal | да | ВНП (GNI), млн долларов США в текущих ценах, последний год с данными | World Bank |
| `gnp_old` | decimal | да | ВНП (GNI) за год до последнего, млн долларов США | World Bank |
| `capital` | int → `cities` | да | Столица | GeoNames |

#### `cities` — Города: населённые пункты GeoNames от порога населения профиля и все столицы

Ключ — `id`; строки упорядочены по `id`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `id` | int |  | Город — geonameid GeoNames | GeoNames |
| `name` | text |  | Название (GeoNames, латиница) | GeoNames, как есть |
| `name_ru` | text | да | Название по-русски | Wikidata, как есть |
| `country_code` | text → `countries` |  | Страна | GeoNames, как есть |
| `district` | text | да | Регион первого уровня (GeoNames admin1) | GeoNames, как есть |
| `population` | int |  | Население | GeoNames |
| `latitude` | decimal |  | Широта, градусы (WGS 84), как в источнике | GeoNames |
| `longitude` | decimal |  | Долгота, градусы (WGS 84), как в источнике | GeoNames |
| `timezone` | text | да | Часовой пояс (IANA) | GeoNames, как есть |

#### `country_languages` — Языки стран: доля населения и официальный статус (CLDR); языки без английского названия не берутся

Ключ — `country_code, language_code`; строки упорядочены по `country_code, language_code`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `country_code` | text → `countries` |  | Страна | GeoNames, как есть |
| `language_code` | text |  | Код языка CLDR (ISO 639; с письменностью — через дефис: zh-Hant) | Unicode CLDR, как есть |
| `language` | text |  | Язык по-английски | Unicode CLDR, иначе Wikidata, как есть |
| `language_ru` | text | да | Язык по-русски | Unicode CLDR, иначе Wikidata, как есть |
| `official` | bool |  | Официальный: статус CLDR official или de_facto_official | Unicode CLDR |
| `percentage` | decimal |  | Доля населения страны, % | Unicode CLDR |

#### `country_borders` — Соседство стран: пара в обе стороны (GeoNames)

Ключ — `country_code, neighbour_code`; строки упорядочены по `country_code, neighbour_code`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `country_code` | text → `countries` |  | Страна | GeoNames, как есть |
| `neighbour_code` | text → `countries` |  | Соседняя страна | GeoNames, как есть |

#### `country_years` — Страны по годам: население, ВНП, ВВП, продолжительность жизни (World Bank)

Ключ — `country_code, year`; строки упорядочены по `country_code, year`.

| поле | тип | NULL | что | откуда |
|---|---|---|---|---|
| `country_code` | text → `countries` |  | Страна | World Bank, как есть |
| `year` | smallint |  | Год | World Bank |
| `population` | bigint | да | Население | World Bank |
| `gnp` | decimal | да | ВНП (GNI), млн долларов США в текущих ценах | World Bank |
| `gdp` | decimal | да | ВВП (GDP), млн долларов США в текущих ценах | World Bank |
| `life_expectancy` | decimal | да | Ожидаемая продолжительность жизни при рождении, лет | World Bank |
