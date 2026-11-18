package io.hammerhall.data.auc.text;

/**
 * Мастер справочника — человек или организация; все выдуманные. Строка таблицы мастеров (см. {@link Makers#row}).
 *
 * @param id        ключ, с 1
 * @param kind      вид
 * @param name      имя для каталога: «Иван Петрович Сухотин», «Фарфоровый завод А. Ф. Сухотина»
 * @param nameEn    по-английски: «Ivan Petrovich Sukhotin», «A. F. Sukhotin Porcelain Factory»
 * @param nameGen   родительный для текстов: «Ивана Сухотина», «фарфорового завода А. Ф. Сухотина»
 * @param namePrep  с предлогом места: «на фарфоровом заводе А. Ф. Сухотина», «в мастерской …»; у людей — {@code null}
 * @param person    человек (у мастерской — владелец, по имени которого названа; {@code null} — нет)
 * @param yearFrom  год рождения или основания
 * @param yearTo    год смерти или закрытия; {@code null} — жив или работает
 * @param country   страна
 * @param city      город; {@code null} — у выдуманных монетных дворов и эмитентов
 * @param placeRu   место работы для таблицы: город или выдуманный город двора
 * @param school    направление (у художников и скульпторов), иначе {@code null}
 * @param specialty специализация: «пейзажист», «график»; у мастерских — чем известна; может быть {@code null}
 * @param mark      что на предмете: подпись («И. Сухотин»), монограмма («ИС»), клеймо («АС», «Бр. Сухотины»,
 *                  «Орвель»); у монетного двора — буквы («ВР») или знак («колос»)
 * @param tier      уровень рынка: A (≈ 3 %), B, C, D
 * @param bioRu     справка 120–400 знаков — у уровней A и B, иначе {@code null}
 */
public record Maker(int id, MakerKind kind, String name, String nameEn, String nameGen, String namePrep, Person person,
                    int yearFrom, Integer yearTo, Country country, City city, String placeRu, String school,
                    String specialty, String mark, String tier, String bioRu) {

    public Department department() {
        return kind.department;
    }

    /** Человек, а не организация (у организаций есть форма места {@link #namePrep}). */
    public boolean isPerson() {
        return namePrep == null;
    }

    /** Годы для каталога: «1860–1925», «род. 1958»; у организаций — «осн. 1812», «1812–1918». */
    public String years() {
        if (isPerson()) return yearTo == null ? "род. " + yearFrom : yearFrom + "–" + yearTo;
        return yearTo == null ? "осн. " + yearFrom : yearFrom + "–" + yearTo;
    }

    /** Короткое имя для заголовка: у людей — «Иван Сухотин», у организаций — полное. */
    public String shortName() {
        return isPerson() ? person.firstLast() : name;
    }

    public String shortNameEn() {
        return isPerson() ? person.firstLastEn() : nameEn;
    }

    /** Последний год работы (не позже {@code cap}). */
    int activeTo(int cap) {
        return yearTo == null ? cap : Math.min(yearTo, cap);
    }

    /** Первый год работы: у людей — с 18 лет. */
    int activeFrom() {
        return isPerson() ? yearFrom + 18 : yearFrom;
    }

    /** Подпись для текста: у русских — кириллицей («И. Сухотин»), иначе {@code null}. */
    String signatureRu() {
        return person != null && isPerson() ? person.signature() : null;
    }
}
