package io.hammerhall.data.auc.auction.calendar;

/**
 * Отделы и темы календаря — по индексу отдела (порядок словаря {@code text.Department}: живопись, ювелирное, монеты,
 * книги, декоративно-прикладное, коллекционные). {@code department_id} выпуска — индекс + 1 (порядок в каталоге).
 * Названия — как у словаря; тема тематических онлайн-торгов — раздел верхнего уровня дерева категорий.
 */
public final class Depts {
    private Depts() {
    }

    public static final int PAINTINGS = 0, JEWELLERY = 1, COINS = 2, BOOKS = 3, DECORATIVE = 4, COLLECTIBLES = 5;
    public static final int COUNT = 6;

    public static final String[] CODE = {"paintings", "jewellery", "coins", "books", "decorative", "collectibles"};
    public static final String[] NAME_RU = {"Живопись и графика", "Ювелирные украшения и часы", "Монеты и медали",
            "Книги, рукописи, карты", "Декоративно-прикладное искусство", "Коллекционные предметы"};
    public static final String[] NAME_EN = {"Paintings and Works on Paper", "Jewellery and Watches", "Coins and Medals",
            "Books, Manuscripts and Maps", "Decorative Arts", "Collectibles"};

    /** Тема тематических торгов: раздел верхнего уровня (код словаря) и его отдел. */
    public record Theme(String category, int dept, String nameRu, String nameEn) {
    }

    /**
     * Темы по кругу — по два тематических торга в воскресенье (пары — разных отделов): часы, серебро, карты и атласы,
     * марки, игрушки, фотография, … Круг — 6 недель.
     */
    public static final Theme[] THEMES = {
            new Theme("watches", JEWELLERY, "Часы", "Watches"),
            new Theme("silver", DECORATIVE, "Серебро", "Silver"),
            new Theme("maps", BOOKS, "Карты и атласы", "Maps and Atlases"),
            new Theme("stamps", COLLECTIBLES, "Марки и почтовая история", "Stamps and Postal History"),
            new Theme("toys", COLLECTIBLES, "Игрушки и модели", "Toys and Models"),
            new Theme("photography", PAINTINGS, "Фотография", "Photography"),
            new Theme("porcelain", DECORATIVE, "Фарфор и фаянс", "Porcelain and Faience"),
            new Theme("medals", COINS, "Медали и жетоны", "Medals and Tokens"),
            new Theme("manuscripts", BOOKS, "Рукописи и автографы", "Manuscripts and Autographs"),
            new Theme("prints", PAINTINGS, "Эстамп", "Prints"),
            new Theme("banknotes", COINS, "Боны", "Banknotes"),
            new Theme("memorabilia", COLLECTIBLES, "Памятные вещи", "Memorabilia")};

    public static int themeIndex(String category) {
        for (int i = 0; i < THEMES.length; i++) if (THEMES[i].category.equals(category)) return i;
        return -1;
    }

    public static int byCode(String code) {
        for (int i = 0; i < COUNT; i++) if (CODE[i].equals(code)) return i;
        throw new IllegalArgumentException("нет отдела " + code);
    }
}
