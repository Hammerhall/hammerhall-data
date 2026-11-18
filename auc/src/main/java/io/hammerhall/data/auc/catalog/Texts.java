package io.hammerhall.data.auc.catalog;

import io.hammerhall.data.auc.world.Dept;

import java.util.List;

/**
 * Источник текстов вещей и справочника мастеров для модуля {@code catalog} — граница с пакетом текстов
 * ({@code io.hammerhall.data.auc.text}: {@code LotTexts.lot(Department, ключ)} → {@code LotText}, {@code Makers}):
 * в наборе — {@link VocabTexts}; в тестах модуля — простой источник с тем же интерфейсом. Текст — функция
 * ({@code seed}, отдел, ключ вещи): тот же ключ — тот же текст в любом порядке вызовов.
 */
interface Texts {
    /**
     * Поля вещи из текстов ({@code items}): всё, кроме приёма, эстимейта и V; {@code conditionLevel} — общий уровень
     * состояния 1–5 (1 — лучший) для модели цены, одна мера для всех шкал (в выпуск не идёт).
     */
    record ItemText(int categoryId, int makerId, String attribution, String titleRu, String titleEn,
                    String descriptionRu, String conditionRu, String conditionGrade, String datingRu, Integer yearFrom,
                    Integer yearTo, String originPlaceRu, String materialRu, String techniqueRu, Integer heightMm,
                    Integer widthMm, Integer depthMm, Double diameterMm, Double weightG, Integer metalFineness,
                    int pieces, Boolean signed, int conditionLevel, List<Link> provenance) {
    }

    /** Звено происхождения ({@code provenance}). */
    record Link(String kind, String textRu, Integer yearFrom, Integer yearTo, String placeRu, String ownerRu) {
    }

    /** Строка справочника мастеров ({@code makers}) без уровня рынка — его назначает {@code catalog}. */
    record Maker(int id, String kind, Dept dept, boolean person, String nameRu, String nameEn, String firstName,
                 String patronymic, String lastName, int activeFrom, Integer activeTo, String country, String cityRu,
                 String schoolRu, String specialtyRu, String markRu) {
    }

    /** Текст вещи отдела по ключу ({@code Keys.item}). */
    ItemText item(Dept d, long key);

    /** Справочник мастеров по {@code id} — постоянный, от {@code seed}. */
    List<Maker> makers();
}
