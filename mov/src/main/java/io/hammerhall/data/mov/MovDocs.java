package io.hammerhall.data.mov;

import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.SchemaDot;
import io.hammerhall.data.core.schema.SchemaExport;
import io.hammerhall.data.mov.build.Genres;
import io.hammerhall.data.mov.site.SiteDocs;
import io.hammerhall.data.mov.source.SnapshotSource;
import io.hammerhall.data.mov.source.SourceData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SOURCE.md и LICENSE выпуска: источник и его лицензия, снимок (имя, время, sha256), что сделано с данными (в том
 * числе — что рейтинг синтетический, откуда год и название фильма и пол актёра и человека, с числом по каждому пути),
 * профиль, файлы, схема и поля. LICENSE — CC0 1.0 Wikidata и CC BY 4.0 GeoNames (города «Мира» для города места).
 */
public final class MovDocs implements Release.Docs {
    private final SnapshotSource source;

    public MovDocs(SnapshotSource source) {
        this.source = source;
    }

    static final String WIKIDATA = "https://www.wikidata.org/";
    static final String CC0 = "https://creativecommons.org/publicdomain/zero/1.0/";
    static final String GEONAMES = "https://www.geonames.org/";
    static final String CC_BY = "https://creativecommons.org/licenses/by/4.0/";

    private static String sp(long x) {
        return String.format(Locale.ROOT, "%,d", x).replace(',', ' ');
    }

    private static String sp(Object x) {
        return x instanceof Number n ? sp(n.longValue()) : "—";
    }

    private static String mb(long bytes) {
        return new BigDecimal(bytes / 1e6).setScale(1, RoundingMode.HALF_EVEN).toPlainString().replace('.', ',');
    }

    @Override
    @SuppressWarnings("unchecked")
    public String sourceMd(Schema schema, Map<String, Object> m) {
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) m.get("inputs");
        Map<String, Object> in = inputs.getFirst();
        Map<String, Object> ci = inputs.size() > 1 ? inputs.get(1) : Map.of();
        Map<String, Object> sel = (Map<String, Object>) m.get("selection");
        Map<String, Object> g = (Map<String, Object>) m.get("actors_gender");
        Map<String, Object> pg = (Map<String, Object>) m.get("people_gender");
        Map<String, Object> y = (Map<String, Object>) m.get("movies_year");
        Map<String, Object> tp = (Map<String, Object>) m.get("movies_title");
        Map<String, Object> pc = (Map<String, Object>) m.get("places_city");
        Map<String, Object> pe = (Map<String, Object>) m.get("places_excluded");
        Map<String, Object> dm = (Map<String, Object>) m.get("documentaries");
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) m.get("files")) files.put((String) f.get("table"), f);
        String profile = String.valueOf(m.get("profile"));

        StringBuilder sb = new StringBuilder();
        sb.append("# Кино — набор данных `").append(m.get("version")).append("`\n\n");
        sb.append("Фильмы, актёры, режиссёры и роли для кузен Hammerhall — из Wikidata: художественные, короткометражные,\n")
                .append("анимационные, документальные фильмы, телефильмы и немое кино (без фильмов для взрослых), жанры по словарю\n")
                .append("из 24 жанров, страны и языки фильмов; люди (даты жизни, город рождения, гражданство) и съёмочные группы,\n")
                .append("студии и прокатчики, серии и продолжения, награды и номинации, бюджет и сборы, выход по странам, названия\n")
                .append("на языках; места съёмок и действия фильмов, рождения людей и штаб-квартиры студий — с координатами и\n")
                .append("ближайшим городом набора «Мир». Названия, имена, даты, хронометраж, суммы, координаты, коды — как в\n")
                .append("Wikidata на дату снимка; рейтинг фильма — синтетический (ниже).\n\n");
        sb.append("Снимок источников — `").append(in.get("release")).append("` (выпуск этого репозитория, файл `")
                .append(in.get("name")).append("`),\nснят ").append(in.get("fetched_at")).append(", sha256 `")
                .append(in.get("sha256")).append("`.\n");
        sb.append("Города — `").append(ci.get("name")).append("` выпуска `").append(ci.get("release"))
                .append("` (набор «Мир» этого репозитория), городов — ").append(sp(ci.get("rows")))
                .append(",\nsha256 содержимого `").append(ci.get("sha256_tsv")).append("`.\n\n");

        sb.append("""
                ## Источник и лицензия

                | источник | что взято | лицензия |
                |---|---|---|
                """);
        sb.append("| Wikidata — ").append(WIKIDATA).append(" | фильмы (классы P31, дата выхода P577 и место публикации P291, ")
                .append("первый показ P1191, создание P571, дата производства P2754, название P1476 и метки, хронометраж P2047, ")
                .append("жанр P136, режиссёр P57, страна P495, язык P364, состав P161 и P725 с ролями P453 и P4633, сценарист ")
                .append("P58, композитор P86, оператор P344, продюсер P162, монтажёр P1040, студия P272, прокатчик P750, место ")
                .append("съёмок P915, место действия P840, предыдущий и следующий фильм P155 и P156, серия P179 с номером P1545, ")
                .append("бюджет P2130, сборы ")
                .append("P2142, награды P166 и номинации P1411 с датой P585), признаки известности (число статей в разделах, ")
                .append("число наград и номинаций), люди (P31, P21, имя P735, фамилия P734, рождение P569 и место рождения P19, ")
                .append("смерть P570, гражданство P27, метки), места (GeoNames ID P1566, страна P17, координаты P625, метки), ")
                .append("студии (страна P17, штаб-квартира P159, метки), метки серий и наград, коды стран (P297, P298) и языков ")
                .append("(P218, P220) | CC0 1.0 — ").append(CC0).append(" |\n");
        sb.append("| GeoNames — ").append(GEONAMES).append(", через набор «Мир» (`wld-data-v1`, `cities.tsv.gz`) | города: ")
                .append("geonameid, страна, координаты — для города места (`city_id`, `city_km`) | CC BY 4.0 — ").append(CC_BY)
                .append(" |\n\n");
        sb.append("""
                Оценки фильмов других сервисов (P444) не берутся; ID IMDb (P345) — только в снимке, для отсева дублей.
                Данные взяты как есть, что с ними сделано — ниже.

                ## Что сделано с данными

                """);
        sb.append("""
                1. **Фильмы** — элементы классов фильма Wikidata (снимок их уже отобрал), кроме клипов, трейлеров,
                   проектов и серий фильмов; с годом выхода и названием. Год — самой ранней даты P577 с точностью не ниже
                   года среди утверждений лучшего ранга (предпочтительных, если они есть), от 1874 до года снимка; такой нет —
                   по очереди первый показ P1191, создание P571, дата производства P2754, каждое — тем же правилом. Дата
                   выхода — только из P577: самая ранняя с точностью до дня среди тех же утверждений. Хронометраж —
                   наименьшее из значений P2047 в минутах (половина — к чётному) от 1 до 1 500. У нескольких фильмов один ID
                   IMDb — остаётся фильм с большим числом статей в разделах, затем с меньшим id.
                """);
        sb.append("   Фильмов профиля по пути года: P577 — ").append(sp(y.get("p577"))).append(", P1191 — ")
                .append(sp(y.get("p1191"))).append(", P571 — ").append(sp(y.get("p571"))).append(", P2754 — ")
                .append(sp(y.get("p2754"))).append(".\n");
        sb.append("""
                2. **Название** — метка `en`, иначе `mul`, иначе P1476 (сначала `en`, затем по языку), иначе метка запасного
                   языка (`de fr es it pt nl sv pl`), иначе метка `ru`, иначе метка на любом другом языке (языки — по
                   коду, первая годная); по-русски — метка `ru`.
                """);
        sb.append("   Фильмов профиля по пути названия: `en` — ").append(sp(tp.get("label_en"))).append(", `mul` — ")
                .append(sp(tp.get("label_mul"))).append(", P1476 — ").append(sp(tp.get("p1476")))
                .append(", запасной язык — ").append(sp(tp.get("label_latin"))).append(", `ru` — ")
                .append(sp(tp.get("label_ru"))).append(", другой язык — ")
                .append(sp(tp.get("label_any"))).append(".\n");
        sb.append("""
                3. **Бюджет и сборы** (`budget_usd`, `box_office_usd`) — P2130 и P2142 в долларах США: из сумм в долларах —
                   лучшего ранга, наибольшая, целое (половина — к чётному); не больше нуля — пусто. Суммы в других валютах
                   не пересчитываются и не берутся.
                4. **Жанры** — словарь из 24 жанров (id и названия — как в прежнем наборе фильмов; `Adult` в нём есть,
                   фильмов в нём нет). Жанры фильма — объединение: по классу (анимация — `Animation`, документальный —
                   `Documentary`, короткометражный — `Short`), по хронометражу (до 40 минут — `Short`) и по P136: само
                   значение и его предки по P279 (вширь, до глубины 6) — первый уровень, где встретились якоря словаря,
                   даёт жанры всех якорей уровня; составной жанр (например, романтическая комедия) — два жанра.
                5. **Люди** — элементы-люди (P31 = человек): режиссёры — из P57, актёры — из P161 и P725, съёмочная группа
                   (`movies_crew`) — из P58 (`writer`), P86 (`composer`), P344 (`cinematographer`), P162 (`producer`),
                   P1040 (`editor`) фильмов профиля; id — номер элемента, один человек может быть и актёром, и режиссёром, и в
                   группе; `people` — каждый из них один раз. Полное имя — метка `en`, иначе `mul`, иначе метка запасного
                   языка, без уточнения в скобках в конце; без имени человека нет. Фамилия — первая P734, если имя
                   оканчивается на её метку (или начинается с неё) по границе слова, иначе — последнее слово с частицами
                   перед ним (`van`, `von`, `de`, `del`, `la` …, со строчной буквы) и суффиксом (`Jr.`, `III` …); одно
                   слово — имени нет. `name_ru` — метка `ru` целиком.
                """);
        sb.append("6. **Пол актёра** — `M` или `F`, всегда: по P21 (мужской — `M`, женский — `F`) — ")
                .append(sp(g.get("p21"))).append(";\n   P21 нет — по первому имени P735 (мужское — `M`, женское — `F`) — ")
                .append(sp(g.get("given_name"))).append(";\n   источник молчит — детерминированно от seed набора и id: `F` с ")
                .append("вероятностью доли `F` среди актёров `full`\n   с полом из источника (").append(g.get("seeded_female_share"))
                .append(") — ").append(sp(g.get("seeded"))).append(". Человек с иным значением P21 в актёры не взят — ")
                .append(sp(g.get("not_taken_other_p21"))).append(".\n");
        sb.append("   Пол в `people` — так же (у актёра — как в `actors`), но при ином значении P21 — пусто. Людей по пути\n")
                .append("   пола: P21 — ").append(sp(pg.get("p21"))).append(", имя — ").append(sp(pg.get("given_name")))
                .append(", от seed — ").append(sp(pg.get("seeded"))).append(", пусто — ").append(sp(pg.get("null_other_p21")))
                .append(".\n");
        sb.append("""
                7. **Даты жизни** — P569 и P570 лучшего ранга: дата — самая ранняя с точностью до дня, год — самой ранней с
                   точностью не ниже года; год вне 1700 … года снимка — пусто; смерть — только при рождении не позже неё.
                   Место рождения (`birth_place_id`) — первое по номеру место P19 (нет в `places` — пусто), город рождения
                   (`birth_city_id`) — город этого места (п. 15); гражданство (`people_countries`) — коды ISO 3166-1
                   alpha-3 значений P27.
                8. **Роли** — строка на пару «актёр, фильм»: роль — P4633, иначе метка `en` (`mul`) персонажа P453, иначе
                   пусто; несколько ролей актёра в фильме — через « / » (по алфавиту, без повторов); роль со знаками,
                   которых нет в TSV, — пусто. В документальных фильмах актёрский состав не берётся: там снимаются
                   реальные люди в роли самих себя; актёр — только с ролью в другом фильме, человек в `people` — только
                   если на него есть другая ссылка (режиссёр, съёмочная группа).
                """);
        sb.append("   Документальных фильмов профиля — ").append(sp(dm.get("movies"))).append(", не взято ролей — ")
                .append(sp(dm.get("roles_not_taken"))).append(", выпало актёров — ").append(sp(dm.get("actors_dropped")))
                .append(", людей — ").append(sp(dm.get("people_dropped"))).append(".\n");
        sb.append("""
                9. **Страны и языки** — коды значений P495 (ISO 3166-1 alpha-3) и P364 (ISO 639-1, иначе ISO 639-3); без
                   кода — строки нет; без нескольких кодов стран (и в гражданстве, странах студий и выхода по странам).
                10. **Студии и прокатчики** (`companies`) — значения P272 (`production`) и P750 (`distribution`) фильмов
                    профиля с меткой `en` или `mul`; страна — ISO3 первой по номеру P17; штаб-квартира (`hq_place_id`) —
                    первое по номеру место P159, её город (`hq_city_id`) — город места (п. 15).
                11. **Серии и продолжения** — серии P179 с меткой `en` или `mul`; номер в серии — наименьшее целое P1545 от 1
                    до 10 000, иначе пусто. Продолжение (`movies_sequels`) — пара «фильм, следующий» из P156 и обратного
                    P155, оба фильма — в профиле, не сам себе.
                12. **Награды и номинации** — значения P166 (`won` = `t`) и P1411 (`f`) с меткой `en` или `mul`; год — самый
                    ранний год P585 с точностью не ниже года среди утверждений; выиграл и был номинирован — две строки.
                13. **Выход по странам** — утверждения P577 с местом публикации P291 и точностью до дня: место — страна с
                    кодом ISO3 (город или фестиваль — строки нет), на страну — самая ранняя дата.
                14. **Названия** (`movies_titles`) — метки фильма на языках `en mul ru de fr es it pt nl sv pl`, у фильма
                    без них и без P1476 — на всех языках; годные для TSV, одна на язык.
                15. **Места** (`places`) — каждое место, на которое ссылается профиль: места съёмок P915
                    (`movies_locations`), места действия P840 (`movies_settings`), места рождения, штаб-квартиры. Название —
                    метка `en` (иначе `mul`), без названия места нет (и ссылок на него). Так же нет мест по спискам
                    исключений: страны с кодом из списка кодов стран, мест в такой стране (P17) и мест из списка мест
                    набора. Место рождения — первое по номеру P19, которое берётся, штаб-квартира — P159, если берётся.
                    Вид — `country`, если у элемента есть код ISO3 (место — сама страна), иначе `place`; страна — свой
                    код у страны, код страны P17 у места.
                    Координаты — P625 на глобусе Земли (7 знаков, половина — к чётному). Город (`city_id`, только у
                    `place`) — id города набора «Мир» (`cities.id` = geonameid): GeoNames ID места, если такой город есть
                    (`city_km` = 0), иначе ближайший город той же страны не дальше 30 км по большому кругу (радиус
                    6 371,0088 км; при равенстве — меньший id), `city_km` — расстояние до 0,1 км (половина — к чётному);
                    иначе пусто. Связь с «Миром» мягкая, внешнего ключа нет; тот же город — в ссылках на место.
                """);
        sb.append("    Мест профиля по пути города: по GeoNames ID — ").append(sp(pc.get("geonames"))).append(", ближайший — ")
                .append(sp(pc.get("nearest"))).append(", нет — ").append(sp(pc.get("none"))).append(", страна — ")
                .append(sp(pc.get("country"))).append(";\n    мест, на которые ссылается профиль, нет по спискам исключений: ")
                .append("страна — ").append(sp(pe.get("excluded_country"))).append(", место в такой стране — ")
                .append(sp(pe.get("in_excluded_country"))).append(", из списка мест — ").append(sp(pe.get("listed"))).append(".\n");
        sb.append("""
                16. **Жанры режиссёров** — доля фильмов режиссёра в профиле с жанром среди его фильмов, у которых есть жанр
                    (4 знака, половина — к чётному); сумма по режиссёру не обязана быть 1.
                17. **Рейтинг** (`movies.rank`, 1.0–9.9) — **синтетический, не оценка качества фильма**: популярность
                    `p = ln(1 + статей) + 0.5·ln(1 + наград) + 0.25·ln(1 + номинаций) + 0.1·ln(1 + ролей)`, оценка
                    `0.6·z(p) + 0.4·шум` (`z` — по фильмам с `p > 0`; у фильма без признаков `z = 0`, его рейтинг —
                    примерный, шум вокруг середины; шум — нормальный, от seed и id фильма), место оценки среди всех
                    фильмов `full` — квантиль, рейтинг — по форме распределения прежнего набора (медиана 6.0), один знак.
                    Считается по `full`, у фильма он один во всех профилях; пол актёра — тоже.
                18. **Строки упорядочены** по ключу таблицы, файлы сжаты gzip без отметки времени. Тот же снимок, профиль и
                    seed — тот же выпуск.

                """);
        sb.append(SiteDocs.section((Map<String, Object>) m.get("synthetic"), profile));
        sb.append("## Профиль `").append(profile).append("`\n\n");
        if (profile.equals("full")) {
            sb.append("Все фильмы снимка, прошедшие правила, — ").append(sp(sel.get("movies"))).append(".");
        } else {
            sb.append("Фильмы — ").append(profile.equals("small") ? "1 %" : "10 %").append(" самых известных (")
                    .append(sp(sel.get("movies"))).append(" из ").append(sp(sel.get("movies_full")))
                    .append(", округление вверх): порядок — популярность `p`\nпо убыванию, затем id.");
        }
        sb.append(" Люди и связи — только по фильмам профиля;\nстудии, серии и награды — только те, на кого ссылаются ")
                .append("фильмы профиля; жанров — всегда ").append(Genres.NAMES.size())
                .append("; рейтинг\nсчитается по `full` и одинаков во всех профилях.\n\n");

        sb.append("## Файлы\n\nTSV, UTF-8, разделитель — табуляция, первая строка — заголовок, NULL — пустое поле, числа — ")
                .append("с точкой, без\nхвостовых нулей, даты — `YYYY-MM-DD`, gzip.\n\n| файл | строк | МБ |\n|---|---|---|\n");
        for (io.hammerhall.data.core.schema.Table tb : schema.tables()) {
            Map<String, Object> f = files.get(tb.name());
            if (f == null) continue;
            sb.append("| `").append(f.get("name")).append("` | ").append(sp(((Number) f.get("rows")).longValue()))
                    .append(" | ").append(mb(((Number) f.get("bytes")).longValue())).append(" |\n");
        }
        sb.append("\nКонтрольные суммы — в `manifest.json` (`sha256` — сжатого файла, `sha256_tsv` — содержимого).\n\n");
        sb.append("## Схема\n\n").append(SchemaDot.sourceMd(schema));
        sb.append("""

                id фильмов, людей, студий, серий, наград и мест — номер элемента Wikidata; город — id города набора «Мир»
                (geonameid; мягкая связь, без внешнего ключа). Таблицы — `schema-tables.sql`, ключи, связи, проверки и
                индексы — `schema-constraints.sql` (после загрузки), ER-диаграмма — `schema.mmd` (Mermaid).

                ## Загрузка в Postgres

                Из папки выпуска:
                `psql -v ON_ERROR_STOP=1 -d БАЗА -f schema-tables.sql -f load-postgres.psql -f schema-constraints.sql`.

                ## Поля
                """);
        sb.append(SchemaExport.fieldsMarkdown(schema));
        return sb.toString();
    }

    @Override
    public String license() {
        SourceData d = source.loaded();
        StringBuilder sb = new StringBuilder();
        sb.append("""
                Набор данных mov-data-v1 «Кино» (hammerhall-data) собран из данных Wikidata; город места — по городам
                набора «Мир» (wld-data-v1), они — из GeoNames.

                """);
        sb.append("Снимок источников: ").append(d.snapshot()).append(" (").append(d.fetchedAt()).append("), sha256 ")
                .append(source.sha256()).append(".\n\n");
        sb.append("Wikidata — ").append(WIKIDATA).append("\n  Creative Commons CC0 1.0 Universal (CC0 1.0)\n  ")
                .append(CC0).append("legalcode\n\n");
        sb.append("""
                Структурированные данные Wikidata переданы в общественное достояние по CC0 1.0: их можно копировать,
                изменять и распространять, в том числе в коммерческих целях, без разрешения и без указания авторства.
                Указать источник — «Wikidata (wikidata.org)» — хороший тон.

                GeoNames — https://www.geonames.org/
                  Creative Commons Attribution 4.0 International (CC BY 4.0)
                  https://creativecommons.org/licenses/by/4.0/legalcode

                Город места (places.city_id, places.city_km и их копии в ссылках на место) выбран по городам GeoNames из
                набора «Мир» (wld-data-v1): geonameid, страна и координаты города. Указание авторства: GeoNames
                (geonames.org), изменения — выбор ближайшего города и расстояние до него.

                Рейтинг фильма (movies.rank) и пол актёра и человека там, где источник молчит, — значения сборки, не данные
                Wikidata: как они получены — в SOURCE.md выпуска.

                Таблицы онлайн-кинотеатра «Свартальвхейм» (пользователи, подписки, оплаты, каталог, просмотры, оценки,
                рецензии и прочие) — синтетика генератора набора: люди сервиса и их действия выдуманы. Они передаются на
                тех же условиях CC0 1.0.
                """);
        return sb.toString();
    }
}
