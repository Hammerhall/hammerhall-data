package io.hammerhall.data.mov;

import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.SchemaDot;
import io.hammerhall.data.core.schema.SchemaExport;
import io.hammerhall.data.mov.build.Genres;
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
 * числе — что рейтинг синтетический и откуда пол актёра, с числом актёров по каждому пути), профиль, файлы, схема и
 * поля. LICENSE — CC0 1.0 Wikidata.
 */
public final class MovDocs implements Release.Docs {
    private final SnapshotSource source;

    public MovDocs(SnapshotSource source) {
        this.source = source;
    }

    static final String WIKIDATA = "https://www.wikidata.org/";
    static final String CC0 = "https://creativecommons.org/publicdomain/zero/1.0/";

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
        Map<String, Object> in = ((List<Map<String, Object>>) m.get("inputs")).getFirst();
        Map<String, Object> sel = (Map<String, Object>) m.get("selection");
        Map<String, Object> g = (Map<String, Object>) m.get("actors_gender");
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) m.get("files")) files.put((String) f.get("table"), f);
        String profile = String.valueOf(m.get("profile"));

        StringBuilder sb = new StringBuilder();
        sb.append("# Кино — набор данных `").append(m.get("version")).append("`\n\n");
        sb.append("Фильмы, актёры, режиссёры и роли для кузен Hammerhall — из Wikidata: художественные, короткометражные,\n")
                .append("анимационные, документальные фильмы, телефильмы и немое кино (без фильмов для взрослых), жанры по словарю\n")
                .append("из 24 жанров, страны и языки фильмов. Названия, имена, даты, хронометраж, коды — как в Wikidata на дату\n")
                .append("снимка; рейтинг фильма — синтетический (ниже).\n\n");
        sb.append("Снимок источников — `").append(in.get("release")).append("` (выпуск этого репозитория, файл `")
                .append(in.get("name")).append("`),\nснят ").append(in.get("fetched_at")).append(", sha256 `")
                .append(in.get("sha256")).append("`.\n\n");

        sb.append("""
                ## Источник и лицензия

                | источник | что взято | лицензия |
                |---|---|---|
                """);
        sb.append("| Wikidata — ").append(WIKIDATA).append(" | фильмы (классы P31, дата выхода P577, название P1476 и ")
                .append("метки, хронометраж P2047, жанр P136, режиссёр P57, страна P495, язык P364, состав P161 и P725 с ")
                .append("ролями P453 и P4633), признаки известности (число статей в разделах, число наград P166 и номинаций ")
                .append("P1411), люди (P31, P21, имя P735, фамилия P734, метки), коды стран (P297, P298) и языков (P218, P220) ")
                .append("| CC0 1.0 — ").append(CC0).append(" |\n\n");
        sb.append("""
                Оценки фильмов других сервисов (P444) не берутся; ID IMDb (P345) — только в снимке, для отсева дублей.
                Данные взяты как есть, что с ними сделано — ниже.

                ## Что сделано с данными

                1. **Фильмы** — элементы классов фильма Wikidata (снимок их уже отобрал), кроме клипов, трейлеров,
                   проектов и серий фильмов; с годом выхода и названием. Год — самой ранней даты P577 с точностью не ниже
                   года среди утверждений лучшего ранга (предпочтительных, если они есть), от 1874 до года снимка; дата
                   выхода — самая ранняя с точностью до дня среди тех же утверждений. Название — метка `en`, иначе
                   `mul`, иначе P1476, иначе метка запасного языка (`de fr es it pt nl sv pl`); по-русски — метка `ru`.
                   Хронометраж — наименьшее из значений P2047 в минутах (половина — к чётному) от 1 до 1 500. У
                   нескольких фильмов один ID IMDb — остаётся фильм с большим числом статей в разделах, затем с меньшим id.
                2. **Жанры** — словарь из 24 жанров (id и названия — как в прежнем наборе фильмов; `Adult` в нём есть,
                   фильмов в нём нет). Жанры фильма — объединение: по классу (анимация — `Animation`, документальный —
                   `Documentary`, короткометражный — `Short`), по хронометражу (до 40 минут — `Short`) и по P136: само
                   значение и его предки по P279 (вширь, до глубины 6) — первый уровень, где встретились якоря словаря,
                   даёт жанры всех якорей уровня; составной жанр (например, романтическая комедия) — два жанра.
                3. **Люди** — элементы-люди (P31 = человек): режиссёры — из P57, актёры — из P161 и P725 фильмов
                   профиля; id — номер элемента, один человек может быть и актёром, и режиссёром. Полное имя — метка `en`,
                   иначе `mul`, иначе метка запасного языка, без уточнения в скобках в конце; без имени человека нет.
                   Фамилия — первая P734, если имя оканчивается на её метку (или начинается с неё) по границе слова, иначе —
                   последнее слово с частицами перед ним (`van`, `von`, `de`, `del`, `la` …, со строчной буквы) и суффиксом
                   (`Jr.`, `III` …); одно слово — имени нет. `name_ru` — метка `ru` целиком.
                """);
        sb.append("4. **Пол актёра** — `M` или `F`, всегда: по P21 (мужской — `M`, женский — `F`) — ")
                .append(sp(g.get("p21"))).append(";\n   P21 нет — по первому имени P735 (мужское — `M`, женское — `F`) — ")
                .append(sp(g.get("given_name"))).append(";\n   источник молчит — детерминированно от seed набора и id: `F` с ")
                .append("вероятностью доли `F` среди актёров `full`\n   с полом из источника (").append(g.get("seeded_female_share"))
                .append(") — ").append(sp(g.get("seeded"))).append(". Человек с иным значением P21 в актёры не взят — ")
                .append(sp(g.get("not_taken_other_p21"))).append(".\n");
        sb.append("""
                5. **Роли** — строка на пару «актёр, фильм»: роль — P4633, иначе метка `en` (`mul`) персонажа P453, иначе
                   пусто; несколько ролей актёра в фильме — через « / » (по алфавиту, без повторов); роль со знаками,
                   которых нет в TSV, — пусто.
                6. **Страны и языки** — коды значений P495 (ISO 3166-1 alpha-3) и P364 (ISO 639-1, иначе ISO 639-3); без
                   кода — строки нет; без нескольких кодов стран.
                7. **Жанры режиссёров** — доля фильмов режиссёра в профиле с жанром среди его фильмов, у которых есть жанр
                   (4 знака, половина — к чётному); сумма по режиссёру не обязана быть 1.
                8. **Рейтинг** (`movies.rank`, 1.0–9.9) — **синтетический, не оценка качества фильма**: популярность
                   `p = ln(1 + статей) + 0.5·ln(1 + наград) + 0.25·ln(1 + номинаций) + 0.1·ln(1 + ролей)`, оценка
                   `0.6·z(p) + 0.4·шум` (`z` — по фильмам с `p > 0`; у фильма без признаков `z = 0`, его рейтинг —
                   примерный, шум вокруг середины; шум — нормальный, от seed и id фильма), место оценки среди всех фильмов
                   `full` — квантиль, рейтинг — по форме распределения прежнего набора (медиана 6.0), один знак. Считается
                   по `full`, у фильма он один во всех профилях; пол актёра — тоже.
                9. **Строки упорядочены** по ключу таблицы, файлы сжаты gzip без отметки времени. Тот же снимок, профиль и
                   seed — тот же выпуск.

                """);
        sb.append("## Профиль `").append(profile).append("`\n\n");
        if (profile.equals("full")) {
            sb.append("Все фильмы снимка, прошедшие правила, — ").append(sp(sel.get("movies"))).append(".");
        } else {
            sb.append("Фильмы — ").append(profile.equals("small") ? "1 %" : "10 %").append(" самых известных (")
                    .append(sp(sel.get("movies"))).append(" из ").append(sp(sel.get("movies_full")))
                    .append(", округление вверх): порядок — популярность `p`\nпо убыванию, затем id.");
        }
        sb.append(" Люди и связи — только по фильмам профиля; жанров — всегда ").append(Genres.NAMES.size())
                .append(";\nрейтинг считается по `full` и одинаков во всех профилях.\n\n");

        sb.append("## Файлы\n\nTSV, UTF-8, разделитель — табуляция, первая строка — заголовок, NULL — пустое поле, числа — ")
                .append("с точкой, без\nхвостовых нулей, даты — `YYYY-MM-DD`, gzip.\n\n| файл | строк | МБ |\n|---|---|---|\n");
        for (String t : MovSchema.TABLES) {
            Map<String, Object> f = files.get(t);
            if (f == null) continue;
            sb.append("| `").append(f.get("name")).append("` | ").append(sp(((Number) f.get("rows")).longValue()))
                    .append(" | ").append(mb(((Number) f.get("bytes")).longValue())).append(" |\n");
        }
        sb.append("\nКонтрольные суммы — в `manifest.json` (`sha256` — сжатого файла, `sha256_tsv` — содержимого).\n\n");
        sb.append("## Схема\n\n").append(SchemaDot.sourceMd(schema));
        sb.append("""

                id фильмов и людей — номер элемента Wikidata. Таблицы — `schema-tables.sql`, ключи, связи, проверки и
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
                Набор данных mov-data-v1 «Кино» (hammerhall-data) собран из данных Wikidata.

                """);
        sb.append("Снимок источников: ").append(d.snapshot()).append(" (").append(d.fetchedAt()).append("), sha256 ")
                .append(source.sha256()).append(".\n\n");
        sb.append("Wikidata — ").append(WIKIDATA).append("\n  Creative Commons CC0 1.0 Universal (CC0 1.0)\n  ")
                .append(CC0).append("legalcode\n\n");
        sb.append("""
                Структурированные данные Wikidata переданы в общественное достояние по CC0 1.0: их можно копировать,
                изменять и распространять, в том числе в коммерческих целях, без разрешения и без указания авторства.
                Указать источник — «Wikidata (wikidata.org)» — хороший тон.

                Рейтинг фильма (movies.rank) и пол актёра там, где источник молчит, — значения сборки, не данные Wikidata:
                как они получены — в SOURCE.md выпуска.
                """);
        return sb.toString();
    }
}
