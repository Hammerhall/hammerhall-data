package io.hammerhall.data.auc.text;

import java.util.ArrayList;
import java.util.List;

/** Отделы дома — как у настоящих аукционных домов. Вина, крепкого алкоголя и оружия нет. */
public enum Department {
    PAINTINGS("painting", "Живопись и графика", "Paintings and Works on Paper"),
    JEWELLERY("jewelry", "Ювелирные украшения и часы", "Jewellery and Watches"),
    COINS("coins", "Монеты и медали", "Coins and Medals"),
    BOOKS("books", "Книги, рукописи, карты", "Books, Manuscripts and Maps"),
    DECORATIVE("decorative", "Декоративно-прикладное искусство", "Decorative Arts"),
    COLLECTIBLES("collectibles", "Коллекционные предметы", "Collectibles");

    /** Код латиницей — для таблиц. */
    public final String code;
    public final String nameRu;
    public final String nameEn;

    Department(String code, String nameRu, String nameEn) {
        this.code = code;
        this.nameRu = nameRu;
        this.nameEn = nameEn;
    }

    /** Порядок в каталоге, с 1. */
    public int sortOrder() {
        return ordinal() + 1;
    }

    /** Листья дерева категорий отдела — в порядке словаря. */
    public List<Category> leaves() {
        List<Category> out = new ArrayList<>();
        for (Category c : Category.all()) if (c.department() == this && c.isLeaf()) out.add(c);
        return out;
    }
}
