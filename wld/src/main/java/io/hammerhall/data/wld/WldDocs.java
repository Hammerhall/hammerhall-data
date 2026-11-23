package io.hammerhall.data.wld;

import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.schema.Schema;
import io.hammerhall.data.core.schema.SchemaDot;
import io.hammerhall.data.core.schema.SchemaExport;
import io.hammerhall.data.wld.build.Rules;
import io.hammerhall.data.wld.source.SnapshotSource;
import io.hammerhall.data.wld.source.SourceData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SOURCE.md и LICENSE выпуска: источники, их лицензии и указание авторства, снимок (имя, время, sha256), что сделано
 * с данными, профиль, файлы, схема и поля. LICENSE — лицензии всех четырёх источников; текст лицензии Unicode — из
 * снимка ({@code cldr/cldr-core/LICENSE}) как есть.
 */
public final class WldDocs implements Release.Docs {
    private final SnapshotSource source;

    public WldDocs(SnapshotSource source) {
        this.source = source;
    }

    static final String GEONAMES = "https://www.geonames.org/";
    static final String WORLD_BANK = "https://data.worldbank.org/";
    static final String WORLD_BANK_TERMS = "https://www.worldbank.org/en/about/legal/terms-of-use-for-datasets";
    static final String CLDR = "https://cldr.unicode.org/";
    static final String WIKIDATA = "https://www.wikidata.org/";
    static final String CC_BY = "https://creativecommons.org/licenses/by/4.0/";
    static final String CC0 = "https://creativecommons.org/publicdomain/zero/1.0/";

    private static String sp(long x) {
        return String.format(Locale.ROOT, "%,d", x).replace(',', ' ');
    }

    private static String mb(long bytes) {
        return new BigDecimal(bytes / 1e6).setScale(1, RoundingMode.HALF_EVEN).toPlainString().replace('.', ',');
    }

    @Override
    @SuppressWarnings("unchecked")
    public String sourceMd(Schema schema, Map<String, Object> m) {
        Map<String, Object> in = ((List<Map<String, Object>>) m.get("inputs")).getFirst();
        Map<String, Object> cities = (Map<String, Object>) m.get("cities");
        long min = ((Number) cities.get("min_population")).longValue();
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) m.get("files")) files.put((String) f.get("table"), f);

        StringBuilder sb = new StringBuilder();
        sb.append("# Мир — набор данных `").append(m.get("version")).append("`\n\n");
        sb.append("Справочник стран и городов мира для кузен Hammerhall — из открытых источников: страны и территории,\n")
                .append("города с координатами, языки стран, соседство и ряды по годам. Названия и числа — настоящие, как в\n")
                .append("источниках на дату снимка.\n\n");
        sb.append("Снимок источников — `").append(in.get("release")).append("` (выпуск этого репозитория, файл `")
                .append(in.get("name")).append("`),\nснят ").append(in.get("fetched_at")).append(", sha256 `")
                .append(in.get("sha256")).append("`.\n\n");

        sb.append("""
                ## Источники и лицензии

                | источник | что взято | лицензия |
                |---|---|---|
                """);
        sb.append("| GeoNames — ").append(GEONAMES).append(" | страны (`countryInfo.txt`: коды ISO, столица, площадь, ")
                .append("население, соседи), города (`cities500`: название, регион, население, координаты, пояс), регионы ")
                .append("первого уровня (`admin1CodesASCII.txt`) | CC BY 4.0 — ").append(CC_BY).append(" |\n");
        sb.append("| World Bank Open Data — ").append(WORLD_BANK).append(" | население, ВНП (GNI), ВВП, ")
                .append("продолжительность жизни, площадь — по годам | CC BY 4.0 — ").append(CC_BY).append(" (условия — ")
                .append(WORLD_BANK_TERMS).append(") |\n");
        sb.append("| Unicode CLDR ").append(cldrVersion()).append(" — ").append(CLDR).append(" | названия стран, ")
                .append("регионов ООН и языков (по-английски, по-русски, на языке страны), языки стран с долей и статусом, ")
                .append("подрегионы ООН (M49) | Unicode License v3 — текст в `LICENSE` |\n");
        sb.append("| Wikidata — ").append(WIKIDATA).append(" | русские названия городов, названия языков, которых нет в CLDR | CC0 1.0 — ")
                .append(CC0).append(" |\n\n");
        sb.append("""
                Указание авторства: GeoNames (geonames.org), World Bank Open Data (data.worldbank.org), Unicode CLDR
                (unicode.org), Wikidata (wikidata.org); данные взяты как есть, что с ними сделано — ниже. Метаданные и
                условия каждого показателя World Bank — в снимке (`worldbank/metadata/`, `worldbank/indicators/`).

                ## Что сделано с данными

                Значения не правятся: где источник пуст — пусто, без подстановки. Сделано вот что:

                1. **Страны** — записи `countryInfo.txt`, которые есть среди территорий CLDR (так отпадают исторические
                   записи справочника), без нескольких записей источников. Название — CLDR (английское, русское), название
                   на главном официальном языке страны — CLDR той локали; часть света — по коду GeoNames; подрегион ООН —
                   по вложенности регионов CLDR.
                2. **Числа World Bank** — последнего года с данными у каждой страны и каждого показателя: население,
                   продолжительность жизни (3 знака), ВНП (GNI) — в миллионах долларов (2 знака), и за год до него;
                   округление — половина к чётному. Площадь — World Bank, а нет у страны данных совсем — GeoNames;
                   население — так же (GeoNames — в обоих случаях, если больше нуля).
                3. **Столица** — город набора (без учёта порога населения): `PPLC` с названием столицы из
                   `countryInfo.txt`, иначе единственный `PPLC` страны, иначе город с этим названием (больше население,
                   затем меньше geonameid).
                4. **Города** — населённые пункты GeoNames (класс `P`, кроме частей городов, исторических, заброшенных и
                   разрушенных) взятых стран, без нескольких регионов и городов источников; русское название — метка
                   Wikidata (элемент с наименьшим номером), регион — `admin1CodesASCII.txt`, координаты — значение как в
                   источнике (запись — без хвостовых нулей).
                5. **Языки** — все языки страны из CLDR с долей населения; официальный — статус `official` или
                   `de_facto_official`. Название — CLDR (по коду, иначе базовый язык и письменность), иначе метка
                   Wikidata по коду ISO 639-3; языки без английского названия не берутся.
                6. **Соседство** — пара в обе стороны, если хотя бы одна страна перечисляет другую и обе в наборе.
                7. **Ряды по годам** — с 1960 года, строка — если есть хоть одно из четырёх значений.
                8. **Строки упорядочены** по ключу таблицы, файлы сжаты gzip без отметки времени. Случайности нет: тот же
                   снимок и профиль — тот же выпуск.

                """);
        sb.append("## Профиль `").append(m.get("profile")).append("`\n\nГорода — с населением от ").append(sp(min))
                .append(" и все столицы (на них ссылается `countries.capital`), какое бы ни\nбыло у них население. ")
                .append("Страны, языки, соседство и ряды по годам — одни во всех профилях. Пороги: `full` —\n")
                .append(sp(Rules.minPopulation(io.hammerhall.data.core.gen.Profile.FULL))).append(", `medium` — ")
                .append(sp(Rules.minPopulation(io.hammerhall.data.core.gen.Profile.MEDIUM))).append(", `small` — ")
                .append(sp(Rules.minPopulation(io.hammerhall.data.core.gen.Profile.SMALL))).append(".\n\n");

        sb.append("## Файлы\n\nTSV, UTF-8, разделитель — табуляция, первая строка — заголовок, NULL — пустое поле, числа — ")
                .append("с точкой, без\nхвостовых нулей, логические — `t` / `f`, gzip.\n\n| файл | строк | МБ |\n|---|---|---|\n");
        for (String t : WldSchema.TABLES) {
            Map<String, Object> f = files.get(t);
            if (f == null) continue;
            sb.append("| `").append(f.get("name")).append("` | ").append(sp(((Number) f.get("rows")).longValue()))
                    .append(" | ").append(mb(((Number) f.get("bytes")).longValue())).append(" |\n");
        }
        sb.append("\nКонтрольные суммы — в `manifest.json` (`sha256` — сжатого файла, `sha256_tsv` — содержимого).\n\n");
        sb.append("## Схема\n\n").append(SchemaDot.sourceMd(schema));
        sb.append("""

                У стран и городов связь в обе стороны: `countries.capital` → `cities`, `cities.country_code` →
                `countries`. Таблицы — `schema-tables.sql`, ключи, связи, проверки и индексы — `schema-constraints.sql`
                (после загрузки), ER-диаграмма — `schema.mmd` (Mermaid).

                ## Загрузка в Postgres

                Из папки выпуска:
                `psql -v ON_ERROR_STOP=1 -d БАЗА -f schema-tables.sql -f load-postgres.psql -f schema-constraints.sql`.

                ## Поля
                """);
        sb.append(SchemaExport.fieldsMarkdown(schema));
        return sb.toString();
    }

    private String cldrVersion() {
        SourceData d = source.isLoaded() ? source.loaded() : null;
        return d == null || d.cldrVersion() == null ? "" : d.cldrVersion();
    }

    @Override
    public String license() {
        SourceData d = source.loaded();
        StringBuilder sb = new StringBuilder();
        sb.append("""
                Набор данных wld-data-v1 «Мир» (hammerhall-data) собран из открытых данных четырёх источников. У каждого —
                своя лицензия; при использовании набора указывайте источники, как требует их лицензия.

                """);
        sb.append("Снимок источников: ").append(d.snapshot()).append(" (").append(d.fetchedAt()).append("), sha256 ")
                .append(source.sha256()).append(".\n\n");
        sb.append("GeoNames — ").append(GEONAMES).append("\n  Creative Commons Attribution 4.0 International (CC BY 4.0)\n  ")
                .append(CC_BY).append("legalcode\n\n");
        sb.append("World Bank Open Data — ").append(WORLD_BANK).append("\n  Creative Commons Attribution 4.0 International ")
                .append("(CC BY 4.0)\n  ").append(CC_BY).append("legalcode\n  ").append(WORLD_BANK_TERMS).append("\n\n");
        sb.append("Wikidata — ").append(WIKIDATA).append("\n  Creative Commons CC0 1.0 Universal\n  ").append(CC0)
                .append("legalcode\n\n");
        sb.append("Unicode CLDR ").append(d.cldrVersion() == null ? "" : d.cldrVersion()).append(" — ").append(CLDR)
                .append("\n  Unicode License v3; текст — ниже, как в пакете npm cldr-core\n\n");
        sb.append("-".repeat(72)).append('\n');
        sb.append(d.cldrLicense().strip()).append('\n');
        if (!d.cldrLicenseNames().strip().equals(d.cldrLicense().strip())) {
            sb.append("-".repeat(72)).append("\nТекст из пакета npm cldr-localenames-full:\n\n").append(d.cldrLicenseNames().strip())
                    .append('\n');
        }
        return sb.toString();
    }
}
