package io.hammerhall.data.mov;

import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;
import io.hammerhall.data.mov.build.Rules;

import java.util.List;

import static io.hammerhall.data.core.schema.ColType.DATE;
import static io.hammerhall.data.core.schema.ColType.DECIMAL;
import static io.hammerhall.data.core.schema.ColType.INT;
import static io.hammerhall.data.core.schema.ColType.SMALLINT;
import static io.hammerhall.data.core.schema.ColType.TEXT;

/**
 * Схема {@code mov-data-v1} — единственное описание таблиц набора «Кино»: жанры, фильмы, актёры, режиссёры, роли и
 * связи фильмов с режиссёрами, жанрами, странами и языками. Значения — из снимка Wikidata ({@code mov-source-*}); что
 * откуда и по какому правилу — в README набора и SOURCE.md выпуска.
 *
 * <p>{@code verbatimSource()} — у полей, чьё содержание взято из источника без изменений: названия фильмов, имена
 * людей, роли, коды стран и языков (настоящие названия и имена могут содержать любые сочетания букв — это не наши
 * слова). Без пометки — поля, которые сборка вычисляет: жанры (словарь набора), числа, рейтинг, пол, доли.
 *
 * <p>id фильмов и людей — номер элемента Wikidata ({@code Q42} → 42): один человек может быть и актёром, и режиссёром
 * под одним id. Ключи DDL ставятся после загрузки ({@code schema-constraints.sql}), ссылки проверяет ядро.
 */
public final class MovSchema {
    private MovSchema() {
    }

    public static final String VERSION = "mov-data-v1";
    /** Порядок таблиц — порядок файлов в манифесте выпуска. */
    public static final List<String> TABLES = List.of("genres", "movies", "actors", "directors", "roles", "movies_directors",
            "movies_genres", "directors_genres", "movies_countries", "movies_languages");

    static final String WD = "Wikidata";
    static final String WD_RULE = "Wikidata, правило набора";

    public static Schema build() {
        Schema s = new Schema("mov", VERSION)
                .group("cinema", "Кино: фильмы и жанры, актёры и роли, режиссёры, страны и языки фильмов");

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
                .col("title", TEXT, "Название: метка en, иначе mul, иначе P1476, иначе метка запасного языка").source(WD).verbatimSource()
                .col("title_ru", TEXT, "Название по-русски (метка ru)").nullable().source(WD).verbatimSource()
                .col("year", SMALLINT, "Год выхода: самая ранняя дата P577 с точностью не ниже года").source(WD_RULE)
                .col("release_date", DATE, "Дата выхода: самая ранняя дата P577 с точностью до дня").nullable().source(WD_RULE)
                .col("runtime", INT, "Хронометраж, минуты (P2047)").nullable().source(WD_RULE)
                .col("rank", DECIMAL, "Рейтинг 1.0–9.9 — синтетический: из открытых признаков Wikidata и шума от seed, "
                        + "не оценка качества").scale(3, 1)
                .pk("id")
                .check("year", "year >= " + Rules.FIRST_YEAR)
                .check("runtime", "runtime BETWEEN " + Rules.MIN_RUNTIME + " AND " + Rules.MAX_RUNTIME)
                .check("rank", "rank BETWEEN 1.0 AND 9.9"));

        s.add(Table.of("actors", "cinema", "Актёры: люди из состава (P161, P725) фильмов профиля")
                .title("Актёры")
                .col("id", INT, "Актёр — номер элемента Wikidata").source(WD)
                .col("first_name", TEXT, "Имя (у мононима — пусто)").nullable().source(WD_RULE).verbatimSource()
                .col("last_name", TEXT, "Фамилия (у мононима — имя целиком)").source(WD_RULE).verbatimSource()
                .col("gender", TEXT, "Пол: по P21, иначе по первому имени P735, иначе от seed и id").values("M", "F")
                .col("name_ru", TEXT, "Имя по-русски (метка ru целиком)").nullable().source(WD).verbatimSource()
                .pk("id"));

        s.add(Table.of("directors", "cinema", "Режиссёры: люди из P57 фильмов профиля")
                .title("Режиссёры")
                .col("id", INT, "Режиссёр — номер элемента Wikidata").source(WD)
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
        return s;
    }
}
