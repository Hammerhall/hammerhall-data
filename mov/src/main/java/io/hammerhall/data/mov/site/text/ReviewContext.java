package io.hammerhall.data.mov.site.text;

import java.util.List;

/**
 * Что рецензия знает о фильме и оценке. {@code rating} — 1–10 (вне — прижимается к краям); {@code title} — название
 * (обычно по-английски), {@code titleRu} — по-русски или {@code null} (тогда в тексте — {@code title}); {@code year} —
 * год выхода (0 — неизвестен); {@code genreIds} — жанры словаря (1–24); {@code director}, {@code actors} (первые —
 * главные роли) и {@code otherTitle} (другой фильм того же режиссёра или серии) могут быть {@code null} или пустыми.
 */
public record ReviewContext(int rating, long movieId, String title, String titleRu, int year, List<Integer> genreIds,
        String director, List<String> actors, String otherTitle) {
}
