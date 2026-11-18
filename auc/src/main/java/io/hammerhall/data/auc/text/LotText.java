package io.hammerhall.data.auc.text;

import java.util.List;

/**
 * Тексты и поля вещи для каталога (таблица {@code items} и её {@code provenance}). Эстимейта нет — числа даёт другой
 * модуль. Описания, состояние и происхождение — по-русски; название — по-русски и по-английски.
 *
 * @param key            ключ вещи, как его передал генератор
 * @param department     отдел
 * @param category       лист дерева категорий
 * @param makerId        мастер ({@link Makers}); {@code null} — неизвестен или его нет у категории
 * @param attribution    атрибуция
 * @param titleRu        заголовок лота, 20–120 знаков: «Иван Сухотин. Пейзаж с мельницей. 1912»
 * @param titleEn        он же по-английски: «Ivan Sukhotin. Landscape with a Mill. 1912»
 * @param descriptionRu  описание, 200–1200 знаков
 * @param conditionRu    состояние словами, 30–400 знаков
 * @param conditionGrade код шкалы раздела: {@code excellent, very_good, good, fair, poor}; у монет, медалей и бон —
 *                       {@code unc, au, xf, vf, f, vg}; у марок — {@code mnh, mh, used}
 * @param conditionLevel общий уровень состояния 1–5 (1 — лучший) — один для всех шкал, для модели цены
 * @param datingRu       датировка, как в каталоге: «1912», «около 1910 года», «1880-е годы», «начало XX века»
 * @param yearFrom       годы создания: с
 * @param yearTo         по
 * @param originPlaceRu  место создания; {@code null} — неизвестно
 * @param materialRu     материал; {@code null} — нет
 * @param techniqueRu    техника; {@code null} — нет
 * @param heightMm       высота, мм; {@code null} — не указана (так же ширина, глубина)
 * @param widthMm        ширина, мм
 * @param depthMm        глубина, мм
 * @param diameterMm     диаметр, мм, с десятыми; {@code null} — нет
 * @param weightG        вес, г, с сотыми; {@code null} — нет
 * @param metalFineness  проба (метрическая, 375…999); {@code null} — нет
 * @param pieces         предметов в вещи (≥ 1)
 * @param signed         есть подпись, клеймо или марка мастера; {@code null} — неприменимо
 * @param provenance     происхождение — звенья по времени (может быть пусто)
 */
public record LotText(long key, Department department, Category category, Integer makerId, Attribution attribution,
                      String titleRu, String titleEn, String descriptionRu, String conditionRu, String conditionGrade,
                      int conditionLevel, String datingRu, int yearFrom, int yearTo, String originPlaceRu, String materialRu,
                      String techniqueRu, Integer heightMm, Integer widthMm, Integer depthMm, Double diameterMm,
                      Double weightG, Integer metalFineness, int pieces, Boolean signed, List<Provenance> provenance) {
}
