package io.hammerhall.data.auc.text;

/**
 * Вид мастера в справочнике (для генератора) и его код в таблице {@code makers.kind}: люди — художники, фотографы,
 * скульпторы, медальеры, ювелиры, авторы книг; организации — ювелирные дома, часовые мануфактуры, монетные дворы и
 * эмитенты (выдуманные земли и города), почтовые ведомства, издательства, типографии, картографические заведения,
 * мануфактуры фарфора, стекла и ковров, серебряные и мебельные мастерские, бронзолитейни, фабрики игрушек. Всё —
 * выдуманное.
 */
public enum MakerKind {
    ARTIST(Department.PAINTINGS, "artist", 1800),
    PHOTOGRAPHER(Department.PAINTINGS, "photographer", 120),
    SCULPTOR(Department.PAINTINGS, "sculptor", 160),
    JEWELER(Department.JEWELLERY, "jeweler", 260),
    WATCHMAKER(Department.JEWELLERY, "watchmaker", 130),
    MINT(Department.COINS, "mint", 48),
    ISSUER(Department.COINS, "issuer", 60),
    MEDALIST(Department.COINS, "medalist", 90),
    AUTHOR(Department.BOOKS, "author", 420),
    PUBLISHER(Department.BOOKS, "publisher", 200),
    PRINTER(Department.BOOKS, "printer", 150),
    CARTOGRAPHER(Department.BOOKS, "cartographer", 50),
    PORCELAIN(Department.DECORATIVE, "porcelain", 110),
    SILVERSMITH(Department.DECORATIVE, "silversmith", 150),
    FURNITURE(Department.DECORATIVE, "furniture", 130),
    GLASS(Department.DECORATIVE, "glass", 70),
    CARPET(Department.DECORATIVE, "carpet", 40),
    FOUNDRY(Department.DECORATIVE, "foundry", 60),
    POSTAL(Department.COLLECTIBLES, "postal", 50),
    TOYMAKER(Department.COLLECTIBLES, "toymaker", 110);

    /** Основной отдел мастеров вида. */
    public final Department department;
    /** Код для таблицы ({@code makers.kind}). */
    public final String code;
    /** Сколько таких мастеров в справочнике. */
    public final int count;

    MakerKind(Department department, String code, int count) {
        this.department = department;
        this.code = code;
        this.count = count;
    }

    /** Всегда человек (а не организация). */
    public boolean alwaysPerson() {
        return this == ARTIST || this == PHOTOGRAPHER || this == SCULPTOR || this == MEDALIST || this == AUTHOR;
    }

    /** Коды таблицы — без повторов, в порядке объявления. */
    public static String[] codes() {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
        for (MakerKind k : values()) s.add(k.code);
        return s.toArray(new String[0]);
    }
}
