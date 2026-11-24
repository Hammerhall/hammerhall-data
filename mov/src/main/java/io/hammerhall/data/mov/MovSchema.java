package io.hammerhall.data.mov;

import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;
import io.hammerhall.data.mov.build.Rules;
import io.hammerhall.data.mov.site.SiteSchema;

import java.util.List;

import static io.hammerhall.data.core.schema.ColType.BIGINT;
import static io.hammerhall.data.core.schema.ColType.BOOL;
import static io.hammerhall.data.core.schema.ColType.DATE;
import static io.hammerhall.data.core.schema.ColType.DECIMAL;
import static io.hammerhall.data.core.schema.ColType.INT;
import static io.hammerhall.data.core.schema.ColType.SMALLINT;
import static io.hammerhall.data.core.schema.ColType.TEXT;

/**
 * Схема {@code mov-data-v1} — единственное описание таблиц набора «Кино»: жанры, фильмы, актёры, режиссёры, роли и
 * связи фильмов с режиссёрами, жанрами, странами и языками; люди (даты жизни, город рождения, гражданство) и
 * съёмочные группы; студии и прокатчики, серии и продолжения, награды и номинации; выход по странам, названия на
 * языках, места съёмок. Значения — из снимка Wikidata ({@code mov-source-*}); что откуда и по какому правилу — в
 * README набора и SOURCE.md выпуска.
 *
 * <p>{@code verbatimSource()} — у полей, чьё содержание взято из источника без изменений: названия фильмов (и на
 * языках), имена людей, роли, названия студий, серий и наград, коды стран и языков (настоящие названия и имена могут
 * содержать любые сочетания букв — это не наши слова). Без пометки — поля, которые сборка вычисляет или переводит в
 * свои слова: жанры (словарь набора), числа, даты, рейтинг, пол, доли, должности в группе, роли компаний.
 *
 * <p>id фильмов, людей, студий, серий и наград — номер элемента Wikidata ({@code Q42} → 42): один человек может быть и
 * актёром, и режиссёром, и в съёмочной группе под одним id; в {@code people} он один раз. Город
 * ({@code people.birth_city_id}, {@code movies_locations.city_id}) — GeoNames ID, он же id города набора «Мир»
 * ({@code wld-data-v1}, {@code cities.id}): связь между наборами мягкая, внешнего ключа нет (город может быть не в
 * «Мире»). Ключи DDL ставятся после загрузки ({@code schema-constraints.sql}), ссылки проверяет ядро.
 */
public final class MovSchema {
    private MovSchema() {
    }

    public static final String VERSION = "mov-data-v1";
    /** Порядок таблиц — порядок файлов в манифесте выпуска. */
    public static final List<String> TABLES = List.of("genres", "movies", "actors", "directors", "roles", "movies_directors",
            "movies_genres", "directors_genres", "movies_countries", "movies_languages", "people", "people_countries",
            "movies_crew", "companies", "movies_companies", "series", "movies_series", "movies_sequels", "awards",
            "movies_awards", "movies_releases", "movies_titles", "movies_locations");

    static final String WD = "Wikidata";
    static final String WD_RULE = "Wikidata, правило набора";
    static final String WD_GN = "Wikidata (GeoNames ID, P1566)";

    public static Schema build() {
        Schema s = new Schema("mov", VERSION)
                .group("cinema", "Кино: фильмы и жанры, актёры и роли, режиссёры, страны и языки фильмов")
                .group("people", "Люди: все люди фильмов профиля, их гражданство и съёмочные группы фильмов")
                .group("studios", "Студии и награды: студии и прокатчики, серии фильмов и продолжения, награды и номинации")
                .group("releases", "Выход и названия: даты выхода по странам, названия на языках, места съёмок");

        s.add(Table.of("genres", "cinema", "Жанры: словарь из 24 жанров (id и названия — как в прежнем наборе фильмов)")
                .title("Жанры")
                .col("id", INT, "Жанр")
                .col("name", TEXT, "Название по-английски")
                .pk("id")
                .unique("name"));

        s.add(Table.of("movies", "cinema", "Фильмы Wikidata: художественные, короткометражные, анимационные, документальные, "
                        + "телефильмы, немые — без фильмов для взрослых; с годом выхода и названием")
                .title("Фильмы")
                .col("id", INT, "Фильм — номер элемента Wikidata (Q42 → 42)").source(WD)
                .col("title", TEXT, "Название: метка en, иначе mul, иначе P1476, иначе метка запасного языка, иначе метка "
                        + "ru, иначе метка на любом языке").source(WD).verbatimSource()
                .col("title_ru", TEXT, "Название по-русски (метка ru)").nullable().source(WD).verbatimSource()
                .col("year", SMALLINT, "Год выхода: самая ранняя дата P577 с точностью не ниже года; нет — P1191, P571, P2754")
                .source(WD_RULE)
                .col("release_date", DATE, "Дата выхода: самая ранняя дата P577 с точностью до дня").nullable().source(WD_RULE)
                .col("runtime", INT, "Хронометраж, минуты (P2047)").nullable().source(WD_RULE)
                .col("rank", DECIMAL, "Рейтинг 1.0–9.9 — синтетический: из открытых признаков Wikidata и шума от seed, "
                        + "не оценка качества").scale(3, 1)
                .col("budget_usd", BIGINT, "Бюджет, долларов США (P2130 в долларах США, наибольший из лучшего ранга)")
                .nullable().source(WD_RULE)
                .col("box_office_usd", BIGINT, "Сборы, долларов США (P2142 в долларах США, наибольшие из лучшего ранга)")
                .nullable().source(WD_RULE)
                .pk("id")
                .check("year", "year >= " + Rules.FIRST_YEAR)
                .check("runtime", "runtime BETWEEN " + Rules.MIN_RUNTIME + " AND " + Rules.MAX_RUNTIME)
                .check("rank", "rank BETWEEN 1.0 AND 9.9")
                .check("budget_usd", "budget_usd > 0")
                .check("box_office_usd", "box_office_usd > 0"));

        s.add(Table.of("actors", "cinema", "Актёры: люди из состава (P161, P725) фильмов профиля")
                .title("Актёры")
                .col("id", INT, "Актёр — номер элемента Wikidata (он же — в people)").ref("people").source(WD)
                .col("first_name", TEXT, "Имя (у мононима — пусто)").nullable().source(WD_RULE).verbatimSource()
                .col("last_name", TEXT, "Фамилия (у мононима — имя целиком)").source(WD_RULE).verbatimSource()
                .col("gender", TEXT, "Пол: по P21, иначе по первому имени P735, иначе от seed и id").values("M", "F")
                .col("name_ru", TEXT, "Имя по-русски (метка ru целиком)").nullable().source(WD).verbatimSource()
                .pk("id"));

        s.add(Table.of("directors", "cinema", "Режиссёры: люди из P57 фильмов профиля")
                .title("Режиссёры")
                .col("id", INT, "Режиссёр — номер элемента Wikidata (он же — в people)").ref("people").source(WD)
                .col("first_name", TEXT, "Имя (у мононима — пусто)").nullable().source(WD_RULE).verbatimSource()
                .col("last_name", TEXT, "Фамилия (у мононима — имя целиком)").source(WD_RULE).verbatimSource()
                .col("name_ru", TEXT, "Имя по-русски (метка ru целиком)").nullable().source(WD).verbatimSource()
                .pk("id"));

        s.add(Table.of("roles", "cinema", "Роли: актёр в фильме; несколько ролей актёра в фильме — одной строкой через « / »")
                .title("Роли")
                .col("actor_id", INT, "Актёр").ref("actors").source(WD)
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("role", TEXT, "Роль: P4633, иначе метка персонажа P453; нет — пусто").nullable().source(WD).verbatimSource()
                .pk("actor_id", "movie_id"));

        s.add(Table.of("movies_directors", "cinema", "Режиссёры фильмов (P57)")
                .title("Режиссёры фильмов")
                .col("director_id", INT, "Режиссёр").ref("directors").source(WD)
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .pk("director_id", "movie_id"));

        s.add(Table.of("movies_genres", "cinema", "Жанры фильмов: по классу P31, хронометражу и P136 через якоря словаря")
                .title("Жанры фильмов")
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("genre_id", INT, "Жанр").ref("genres")
                .pk("movie_id", "genre_id"));

        s.add(Table.of("directors_genres", "cinema", "Жанры режиссёров: доля его фильмов профиля с жанром среди его фильмов "
                        + "с жанрами")
                .title("Жанры режиссёров")
                .col("director_id", INT, "Режиссёр").ref("directors")
                .col("genre_id", INT, "Жанр").ref("genres")
                .col("prob", DECIMAL, "Доля фильмов режиссёра с этим жанром, 4 знака").scale(5, 4)
                .pk("director_id", "genre_id")
                .check("prob", "prob > 0 AND prob <= 1"));

        s.add(Table.of("movies_countries", "cinema", "Страны фильмов (P495): код ISO 3166-1 alpha-3")
                .title("Страны фильмов")
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("country_code", TEXT, "Страна — ISO 3166-1 alpha-3").source(WD).verbatimSource()
                .pk("movie_id", "country_code"));

        s.add(Table.of("movies_languages", "cinema", "Языки фильмов (P364): код ISO 639-1, иначе ISO 639-3")
                .title("Языки фильмов")
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("language_code", TEXT, "Язык — ISO 639-1, иначе ISO 639-3").source(WD).verbatimSource()
                .pk("movie_id", "language_code"));

        s.add(Table.of("people", "people", "Люди: актёры, режиссёры и съёмочная группа фильмов профиля — каждый один раз")
                .title("Люди")
                .col("id", INT, "Человек — номер элемента Wikidata").source(WD)
                .col("first_name", TEXT, "Имя (у мононима — пусто)").nullable().source(WD_RULE).verbatimSource()
                .col("last_name", TEXT, "Фамилия (у мононима — имя целиком)").source(WD_RULE).verbatimSource()
                .col("name_ru", TEXT, "Имя по-русски (метка ru целиком)").nullable().source(WD).verbatimSource()
                .col("gender", TEXT, "Пол: по P21 (иное значение — пусто), иначе по первому имени P735, иначе от seed и id; "
                        + "у актёра — как в actors").values("M", "F").nullable()
                .col("birth_date", DATE, "Дата рождения: самая ранняя P569 с точностью до дня").nullable().source(WD_RULE)
                .col("birth_year", SMALLINT, "Год рождения: самая ранняя P569 с точностью не ниже года").nullable().source(WD_RULE)
                .col("death_date", DATE, "Дата смерти (P570) — при дате рождения не позже").nullable().source(WD_RULE)
                .col("death_year", SMALLINT, "Год смерти (P570) — при годе рождения не больше").nullable().source(WD_RULE)
                .col("birth_city_id", INT, "Город рождения — GeoNames ID места P19, он же id города набора «Мир» "
                        + "(wld-data-v1, cities.id); связь мягкая, без внешнего ключа").nullable().source(WD_GN)
                .pk("id")
                .check("birth_year", "birth_year >= " + Rules.FIRST_LIFE_YEAR)
                .check("death_year", "death_year >= birth_year")
                .check("death_date", "death_date >= birth_date"));

        s.add(Table.of("people_countries", "people", "Гражданство людей (P27): код ISO 3166-1 alpha-3")
                .title("Гражданство людей")
                .col("person_id", INT, "Человек").ref("people").source(WD)
                .col("country_code", TEXT, "Страна — ISO 3166-1 alpha-3").source(WD).verbatimSource()
                .pk("person_id", "country_code"));

        s.add(Table.of("movies_crew", "people", "Съёмочные группы фильмов: сценаристы (P58), композиторы (P86), операторы "
                        + "(P344), продюсеры (P162), монтажёры (P1040)")
                .title("Съёмочные группы")
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("person_id", INT, "Человек").ref("people").source(WD)
                .col("job", TEXT, "Должность").values(Rules.CREW_JOBS.toArray(String[]::new)).source(WD_RULE)
                .pk("movie_id", "person_id", "job"));

        s.add(Table.of("companies", "studios", "Студии (P272) и прокатчики (P750) фильмов профиля с названием")
                .title("Студии и прокатчики")
                .col("id", INT, "Компания — номер элемента Wikidata").source(WD)
                .col("name", TEXT, "Название: метка en, иначе mul").source(WD).verbatimSource()
                .col("name_ru", TEXT, "Название по-русски (метка ru)").nullable().source(WD).verbatimSource()
                .col("country_code", TEXT, "Страна (P17) — ISO 3166-1 alpha-3").nullable().source(WD).verbatimSource()
                .pk("id"));

        s.add(Table.of("movies_companies", "studios", "Студии и прокатчики фильмов")
                .title("Студии фильмов")
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("company_id", INT, "Компания").ref("companies").source(WD)
                .col("role", TEXT, "Роль: студия (P272) или прокатчик (P750)").values(Rules.PRODUCTION, Rules.DISTRIBUTION)
                .source(WD_RULE)
                .pk("movie_id", "company_id", "role"));

        s.add(Table.of("series", "studios", "Серии фильмов (P179) фильмов профиля с названием")
                .title("Серии")
                .col("id", INT, "Серия — номер элемента Wikidata").source(WD)
                .col("name", TEXT, "Название: метка en, иначе mul").source(WD).verbatimSource()
                .col("name_ru", TEXT, "Название по-русски (метка ru)").nullable().source(WD).verbatimSource()
                .pk("id"));

        s.add(Table.of("movies_series", "studios", "Фильмы в сериях (P179) и номер в серии (P1545)")
                .title("Фильмы в сериях")
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("series_id", INT, "Серия").ref("series").source(WD)
                .col("ordinal", INT, "Номер в серии: наименьшее целое P1545 от 1 до " + Rules.MAX_ORDINAL).nullable()
                .source(WD_RULE)
                .pk("movie_id", "series_id")
                .check("ordinal", "ordinal BETWEEN 1 AND " + Rules.MAX_ORDINAL));

        s.add(Table.of("movies_sequels", "studios", "Продолжения: фильм и следующий за ним (P156 и обратное P155), оба — "
                        + "фильмы профиля")
                .title("Продолжения")
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("next_movie_id", INT, "Следующий фильм").ref("movies").source(WD)
                .pk("movie_id", "next_movie_id")
                .check("next_movie_id", "next_movie_id <> movie_id"));

        s.add(Table.of("awards", "studios", "Награды (P166) и номинации (P1411) фильмов профиля с названием")
                .title("Награды")
                .col("id", INT, "Награда — номер элемента Wikidata").source(WD)
                .col("name", TEXT, "Название: метка en, иначе mul").source(WD).verbatimSource()
                .col("name_ru", TEXT, "Название по-русски (метка ru)").nullable().source(WD).verbatimSource()
                .pk("id"));

        s.add(Table.of("movies_awards", "studios", "Награды и номинации фильмов; выиграл и был номинирован — две строки")
                .title("Награды фильмов")
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("award_id", INT, "Награда").ref("awards").source(WD)
                .col("won", BOOL, "Награда получена (P166) — t, номинация (P1411) — f").source(WD_RULE)
                .col("year", SMALLINT, "Год: самый ранний год P585 с точностью не ниже года").nullable().source(WD_RULE)
                .pk("movie_id", "award_id", "won"));

        s.add(Table.of("movies_releases", "releases", "Выход по странам: самая ранняя дата P577 с местом P291 и точностью "
                        + "до дня на страну")
                .title("Выход по странам")
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("country_code", TEXT, "Страна — ISO 3166-1 alpha-3").source(WD).verbatimSource()
                .col("release_date", DATE, "Дата выхода в стране").source(WD_RULE)
                .pk("movie_id", "country_code"));

        s.add(Table.of("movies_titles", "releases", "Названия фильмов по языкам: метки Wikidata")
                .title("Названия фильмов")
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("lang", TEXT, "Язык — код языка метки Wikidata").source(WD).verbatimSource()
                .col("title", TEXT, "Название (метка)").source(WD).verbatimSource()
                .pk("movie_id", "lang"));

        s.add(Table.of("movies_locations", "releases", "Места съёмок (P915) — города с GeoNames ID")
                .title("Места съёмок")
                .col("movie_id", INT, "Фильм").ref("movies").source(WD)
                .col("city_id", INT, "Город — GeoNames ID места P915, он же id города набора «Мир» (wld-data-v1, "
                        + "cities.id); связь мягкая, без внешнего ключа").source(WD_GN)
                .pk("movie_id", "city_id"));
        SiteSchema.addTo(s);
        return s;
    }
}
