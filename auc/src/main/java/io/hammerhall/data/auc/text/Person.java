package io.hammerhall.data.auc.text;

/**
 * Выдуманный человек: имя, отчество (у русских), фамилия в форме своего пола, страна — по-русски и по-английски.
 * Формы для каталога и текстов: полное имя, инициалы, библиографическая запись и родительный падеж.
 *
 * @param first      имя
 * @param patronymic отчество; {@code null} — нет (иностранцы)
 * @param last       фамилия в форме пола
 * @param lastMale   мужская форма фамилии (для «братья …», «семьи …»)
 * @param female     женщина
 * @param country    страна
 * @param firstEn    имя по-английски
 * @param lastEn     фамилия по-английски (в форме пола)
 * @param lastMaleEn мужская форма фамилии по-английски
 */
public record Person(String first, String patronymic, String last, String lastMale, boolean female, Country country,
                     String firstEn, String lastEn, String lastMaleEn) {

    /** «Иван Петрович Сухотин», «Пьер Бастид». */
    public String full() {
        return patronymic == null ? first + " " + last : first + " " + patronymic + " " + last;
    }

    /** «Ivan Petrovich Sukhotin», «Pierre Bastide». */
    public String fullEn() {
        return patronymic == null ? firstEn + " " + lastEn : firstEn + " " + Translit.ru(patronymic) + " " + lastEn;
    }

    /** «Иван Сухотин». */
    public String firstLast() {
        return first + " " + last;
    }

    /** «Ivan Sukhotin». */
    public String firstLastEn() {
        return firstEn + " " + lastEn;
    }

    /** «И. П. Сухотин», «П. Бастид». */
    public String initials() {
        return initialsOnly() + " " + last;
    }

    /** «I. P. Sukhotin». */
    public String initialsEn() {
        return initialsOnlyEn() + " " + lastEn;
    }

    /** «И. П.», «П.». */
    public String initialsOnly() {
        return patronymic == null ? W.initial(first) : W.initial(first) + " " + W.initial(patronymic);
    }

    String initialsOnlyEn() {
        return patronymic == null ? W.initial(firstEn) : W.initial(firstEn) + " " + W.initial(Translit.ru(patronymic));
    }

    /** Библиографическая запись: «Сухотин И. П.». */
    public String biblio() {
        return last + " " + initialsOnly();
    }

    /** «Sukhotin, I. P.». */
    public String biblioEn() {
        return lastEn + ", " + initialsOnlyEn();
    }

    /** «Ивана Петровича Сухотина». */
    public String fullGen() {
        String g = Ru.genFirst(first, female);
        if (patronymic != null) g += " " + Ru.genPatronymic(patronymic);
        return g + " " + Ru.genSurname(last, female);
    }

    /** «Ивана Сухотина». */
    public String firstLastGen() {
        return Ru.genFirst(first, female) + " " + Ru.genSurname(last, female);
    }

    /** «И. П. Сухотина». */
    public String initialsGen() {
        return initialsOnly() + " " + Ru.genSurname(last, female);
    }

    /** Фамилия в родительном: «Сухотина». */
    public String lastGen() {
        return Ru.genSurname(last, female);
    }

    /** Монограмма: «ИС», «ПБ». */
    public String monogram() {
        String l = last.substring(last.lastIndexOf(' ') + 1);
        return first.substring(0, 1) + l.substring(0, 1);
    }

    /** Подпись кириллицей — только у русских; у прочих {@code null} (подпись латиницей не воспроизводится). */
    public String signature() {
        return country == Country.RU ? W.initial(first) + " " + last : null;
    }
}
