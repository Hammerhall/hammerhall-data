package io.hammerhall.data.wld;

import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.Table;
import io.hammerhall.data.wld.build.Rules;
import io.hammerhall.data.wld.build.World;

import java.util.List;

import static io.hammerhall.data.core.schema.ColType.BIGINT;
import static io.hammerhall.data.core.schema.ColType.BOOL;
import static io.hammerhall.data.core.schema.ColType.DECIMAL;
import static io.hammerhall.data.core.schema.ColType.INT;
import static io.hammerhall.data.core.schema.ColType.SMALLINT;
import static io.hammerhall.data.core.schema.ColType.TEXT;

/**
 * Схема {@code wld-data-v1} — единственное описание таблиц набора «Мир»: страны, города, языки стран, соседство и ряды
 * по годам. Значения — из открытых источников снимка ({@code wld-source-*}); что откуда и по какому правилу — в README
 * набора и SOURCE.md выпуска.
 *
 * <p>{@code verbatimSource()} — у полей, чьё содержание взято из источника без изменений: коды, названия стран, городов,
 * регионов и языков, часовые пояса (настоящие названия мест могут содержать любые сочетания букв — это не наши
 * слова). Без пометки — поля, которые сборка вычисляет или переводит в свои слова: часть света, числа, флаги.
 *
 * <p>У стран и городов связь в обе стороны: {@code countries.capital → cities}, {@code cities.country_code →
 * countries}. Ключи DDL ставятся после загрузки ({@code schema-constraints.sql}), ссылки проверяет ядро, когда обе
 * таблицы записаны.
 */
public final class WldSchema {
    private WldSchema() {
    }

    public static final String VERSION = "wld-data-v1";
    /** Порядок таблиц — порядок файлов в манифесте выпуска. */
    public static final List<String> TABLES = List.of("countries", "cities", "country_languages", "country_borders",
            "country_years");

    static final String GN = "GeoNames";
    static final String WB = "World Bank";
    static final String WB_GN = "World Bank, иначе GeoNames";
    static final String CLDR = "Unicode CLDR";
    static final String WD = "Wikidata";
    static final String CLDR_WD = "Unicode CLDR, иначе Wikidata";

    public static Schema build() {
        Schema s = new Schema("wld", VERSION)
                .group("world", "Мир: страны, города, языки, соседство, ряды по годам");

        s.add(Table.of("countries", "world", "Страны и территории: коды ISO, названия, часть света, регион ООН, площадь, "
                        + "население, продолжительность жизни, ВНП, столица").title("Страны").rows("≈ 245")
                .col("code", TEXT, "Код ISO 3166-1 alpha-3").source(GN).verbatimSource()
                .col("code2", TEXT, "Код ISO 3166-1 alpha-2").source(GN).verbatimSource()
                .col("name", TEXT, "Название по-английски").source(CLDR).verbatimSource()
                .col("name_ru", TEXT, "Название по-русски").nullable().source(CLDR).verbatimSource()
                .col("local_name", TEXT, "Название на главном официальном языке страны").nullable().source(CLDR).verbatimSource()
                .col("continent", TEXT, "Часть света").values(Rules.CONTINENTS.values().toArray(String[]::new)).source(GN)
                .col("region", TEXT, "Подрегион ООН (M49) по-английски").nullable().source(CLDR).verbatimSource()
                .col("region_ru", TEXT, "Подрегион ООН (M49) по-русски").nullable().source(CLDR).verbatimSource()
                .col("surface_area", DECIMAL, "Площадь, км², последний год с данными").scale(16, World.AREA_SCALE).nullable().source(WB_GN)
                .col("population", BIGINT, "Население, последний год с данными").nullable().source(WB_GN)
                .col("life_expectancy", DECIMAL, "Ожидаемая продолжительность жизни при рождении, лет, последний год с данными")
                .scale(6, 3).nullable().source(WB)
                .col("gnp", DECIMAL, "ВНП (GNI), млн долларов США в текущих ценах, последний год с данными").scale(16, 2).nullable().source(WB)
                .col("gnp_old", DECIMAL, "ВНП (GNI) за год до последнего, млн долларов США").scale(16, 2).nullable().source(WB)
                .col("capital", INT, "Столица").ref("cities").nullable().source(GN)
                .pk("code")
                .unique("code2"));

        s.add(Table.of("cities", "world", "Города: населённые пункты GeoNames от порога населения профиля и все столицы")
                .title("Города").rows("≈ 150 тыс.")
                .col("id", INT, "Город — geonameid GeoNames").source(GN)
                .col("name", TEXT, "Название (GeoNames, латиница)").source(GN).verbatimSource()
                .col("name_ru", TEXT, "Название по-русски").nullable().source(WD).verbatimSource()
                .col("country_code", TEXT, "Страна").ref("countries").source(GN).verbatimSource()
                .col("district", TEXT, "Регион первого уровня (GeoNames admin1)").nullable().source(GN).verbatimSource()
                .col("population", INT, "Население").source(GN)
                .col("latitude", DECIMAL, "Широта, градусы (WGS 84), как в источнике").scale(10, World.COORD_SCALE).source(GN)
                .col("longitude", DECIMAL, "Долгота, градусы (WGS 84), как в источнике").scale(10, World.COORD_SCALE).source(GN)
                .col("timezone", TEXT, "Часовой пояс (IANA)").nullable().source(GN).verbatimSource()
                .pk("id")
                .check("latitude", "latitude BETWEEN -90 AND 90")
                .check("longitude", "longitude BETWEEN -180 AND 180"));

        s.add(Table.of("country_languages", "world", "Языки стран: доля населения и официальный статус (CLDR); языки без "
                        + "английского названия не берутся")
                .title("Языки стран").rows("≈ 1,4 тыс.")
                .col("country_code", TEXT, "Страна").ref("countries").source(GN).verbatimSource()
                .col("language_code", TEXT, "Код языка CLDR (ISO 639; с письменностью — через дефис: zh-Hant)").source(CLDR).verbatimSource()
                .col("language", TEXT, "Язык по-английски").source(CLDR_WD).verbatimSource()
                .col("language_ru", TEXT, "Язык по-русски").nullable().source(CLDR_WD).verbatimSource()
                .col("official", BOOL, "Официальный: статус CLDR official или de_facto_official").source(CLDR)
                .col("percentage", DECIMAL, "Доля населения страны, %").scale(8, World.PERCENT_SCALE).source(CLDR)
                .pk("country_code", "language_code"));

        s.add(Table.of("country_borders", "world", "Соседство стран: пара в обе стороны (GeoNames)").title("Соседство стран")
                .rows("≈ 640")
                .col("country_code", TEXT, "Страна").ref("countries").source(GN).verbatimSource()
                .col("neighbour_code", TEXT, "Соседняя страна").ref("countries").source(GN).verbatimSource()
                .pk("country_code", "neighbour_code")
                .check("not_self", "country_code <> neighbour_code"));

        s.add(Table.of("country_years", "world", "Страны по годам: население, ВНП, ВВП, продолжительность жизни (World Bank)")
                .title("Страны по годам").rows("≈ 14 тыс.")
                .col("country_code", TEXT, "Страна").ref("countries").source(WB).verbatimSource()
                .col("year", SMALLINT, "Год").source(WB)
                .col("population", BIGINT, "Население").nullable().source(WB)
                .col("gnp", DECIMAL, "ВНП (GNI), млн долларов США в текущих ценах").scale(16, 2).nullable().source(WB)
                .col("gdp", DECIMAL, "ВВП (GDP), млн долларов США в текущих ценах").scale(16, 2).nullable().source(WB)
                .col("life_expectancy", DECIMAL, "Ожидаемая продолжительность жизни при рождении, лет").scale(6, 3).nullable().source(WB)
                .pk("country_code", "year")
                .check("any_value", "population IS NOT NULL OR gnp IS NOT NULL OR gdp IS NOT NULL OR life_expectancy IS NOT NULL"));
        return s;
    }
}
