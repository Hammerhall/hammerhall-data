package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.Rng;

import java.util.List;

/**
 * Тексты людей «Свартальвхейма» по-русски: логины и отображаемые имена пользователей, рецензии (заголовок и текст, тон —
 * по оценке), названия подборок, справочник тегов. Вся случайность — из переданного {@link Rng}: тот же генератор в том
 * же состоянии даёт тот же текст на любой ОС. Реализация — {@link SiteTextsRu}.
 */
public interface SiteTexts {
    /** Логин: строчная латиница, цифры и «_», 4–20 знаков, с буквы. Повторы возможны — разводит генератор. */
    String login(Rng r);

    /** Отображаемое имя: «Анна К.» (имя по полу и первая буква фамилии), иногда — ник одним словом; {@code gender} — 'M' или 'F'. */
    String displayName(Rng r, char gender);

    /** Заголовок рецензии, 3–8 слов, тон — по {@code c.rating()}. */
    String reviewTitle(Rng r, ReviewContext c);

    /** Текст рецензии: 2–6 предложений, 150–900 знаков, тон — по {@code c.rating()}, говорит о фильме из {@code c}. */
    String reviewBody(Rng r, ReviewContext c);

    /** Название подборки по жанру словаря (id 1–24); жанр вне словаря — подборка без жанра. */
    String collectionTitle(Rng r, int genreId);

    /** Постоянный список тегов (≈ 200, без повторов); id тега = индекс + 1. */
    List<String> tags();
}
