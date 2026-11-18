package io.hammerhall.data.auc.text;

/**
 * Вид вещи для генератора текстов: от него — шаблоны названия и описания, шкала состояния, кто мастер и формула
 * заголовка. Лист дерева категорий ({@link Category}) указывает вид и вариант («пейзаж», «кольца», «серебряные
 * монеты»).
 */
public enum Kind {
    PAINTING_OIL(MakerKind.ARTIST, Formula.ART),
    DRAWING(MakerKind.ARTIST, Formula.ART),
    PRINT(MakerKind.ARTIST, Formula.ART),
    PHOTO(MakerKind.PHOTOGRAPHER, Formula.ART),
    SCULPTURE(MakerKind.SCULPTOR, Formula.ART),
    JEWELRY(MakerKind.JEWELER, Formula.OBJECT),
    WATCH(MakerKind.WATCHMAKER, Formula.OBJECT),
    GEMSTONE(null, Formula.OBJECT),
    COIN(MakerKind.MINT, Formula.COIN),
    MEDAL(MakerKind.MEDALIST, Formula.OBJECT),
    BANKNOTE(MakerKind.ISSUER, Formula.OBJECT),
    BOOK(MakerKind.AUTHOR, Formula.BOOK),
    MANUSCRIPT(null, Formula.OBJECT),
    MAP(MakerKind.CARTOGRAPHER, Formula.OBJECT),
    PORCELAIN(MakerKind.PORCELAIN, Formula.OBJECT),
    SILVER(MakerKind.SILVERSMITH, Formula.OBJECT),
    FURNITURE(MakerKind.FURNITURE, Formula.OBJECT),
    GLASS(MakerKind.GLASS, Formula.OBJECT),
    BRONZE(MakerKind.SCULPTOR, Formula.OBJECT),
    CLOCK(MakerKind.FOUNDRY, Formula.OBJECT),
    CARPET(MakerKind.CARPET, Formula.OBJECT),
    STAMP(MakerKind.POSTAL, Formula.OBJECT),
    POSTCARD(MakerKind.PRINTER, Formula.OBJECT),
    TOY(MakerKind.TOYMAKER, Formula.OBJECT),
    MEMORABILIA(null, Formula.OBJECT);

    /** Формула заголовка: «Мастер. Название. Год», «Предмет. Мастер, год», «Номинал год. Двор», «Автор. Название». */
    enum Formula {ART, OBJECT, COIN, BOOK}

    /** Кто обычно мастер; {@code null} — мастера в справочнике нет. */
    public final MakerKind makerKind;
    final Formula formula;

    Kind(MakerKind makerKind, Formula formula) {
        this.makerKind = makerKind;
        this.formula = formula;
    }
}
