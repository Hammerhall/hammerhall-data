package io.hammerhall.data.auc.text;

/**
 * Звено происхождения вещи — строка таблицы {@code provenance}.
 *
 * @param seq       1 — самое раннее звено
 * @param kind      {@code by_descent, private_collection, acquired, auction, gallery, exhibition, literature, expertise}
 * @param textRu    запись, как в каталоге, 30–300 знаков
 * @param yearFrom  годы звена; {@code null} — не указаны
 * @param yearTo    годы звена
 * @param placeRu   город или страна; {@code null} — не указаны
 * @param ownerRu   владелец, собрание, галерея, торговый дом; у выставок, литературы и экспертиз — {@code null}
 */
public record Provenance(int seq, String kind, String textRu, Integer yearFrom, Integer yearTo, String placeRu,
                         String ownerRu) {
}
