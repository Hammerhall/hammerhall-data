package io.hammerhall.data.auc.text;

/**
 * Город — настоящий, нейтральный: место работы мастера, издания, собрания. Формы — для текстов: «План Парижа»,
 * «работал в Париже»; по-английски — для названий.
 *
 * @param name    именительный
 * @param gen     родительный
 * @param prep    предложный (без «в»)
 * @param country страна
 * @param en      по-английски
 */
public record City(String name, String gen, String prep, Country country, String en) {
}
