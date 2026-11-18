package io.hammerhall.data.auc;

/**
 * Имя аукционного дома в падежах — для текстов и проверок словаря. Само имя — одно место в коде:
 * {@link AucDataset#HOUSE_NAME}, {@link AucDataset#HOUSE_NAME_EN}. В таблицах не встречается — только в паспорте и
 * {@code SOURCE.md}. Склоняется как обычное словосочетание: «торги Дома Одина», «в Доме Одина».
 */
public final class House {
    private House() {
    }

    /** Именительный: «Дом Одина». */
    public static final String NAME = AucDataset.HOUSE_NAME;
    /** По-английски: «Odin's House». */
    public static final String NAME_EN = AucDataset.HOUSE_NAME_EN;
    /** Родительный: «торги Дома Одина». */
    public static final String GEN = "Дома Одина";
    /** Предложный: «в Доме Одина». */
    public static final String PREP = "Доме Одина";
    /** С какого года дом проводит торги — раньше в происхождении лотов его нет (условность текстов словаря). */
    public static final int SINCE = 2008;
}
