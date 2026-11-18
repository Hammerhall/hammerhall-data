package io.hammerhall.data.auc.text;

/**
 * Страны мастеров и предметов — код ISO 3166-1 alpha-2 и русские формы: название, прилагательное школы («русская
 * школа», «работа русской школы»), язык издания («на французском языке»). Список нарочно короткий и нейтральный.
 */
public enum Country {
    RU("Россия", "русская", "русской", "русский", "русском", "Russia", "Russian"),
    FR("Франция", "французская", "французской", "французский", "французском", "France", "French"),
    IT("Италия", "итальянская", "итальянской", "итальянский", "итальянском", "Italy", "Italian"),
    DE("Германия", "немецкая", "немецкой", "немецкий", "немецком", "Germany", "German"),
    AT("Австрия", "австрийская", "австрийской", "австрийский", "немецком", "Austria", "Austrian"),
    CH("Швейцария", "швейцарская", "швейцарской", "швейцарский", "немецком", "Switzerland", "Swiss"),
    NL("Нидерланды", "голландская", "голландской", "голландский", "нидерландском", "The Netherlands", "Dutch"),
    BE("Бельгия", "бельгийская", "бельгийской", "бельгийский", "французском", "Belgium", "Belgian"),
    GB("Великобритания", "английская", "английской", "английский", "английском", "Great Britain", "British"),
    US("США", "американская", "американской", "американский", "английском", "USA", "American"),
    SE("Швеция", "шведская", "шведской", "шведский", "шведском", "Sweden", "Swedish"),
    NO("Норвегия", "норвежская", "норвежской", "норвежский", "норвежском", "Norway", "Norwegian"),
    DK("Дания", "датская", "датской", "датский", "датском", "Denmark", "Danish"),
    FI("Финляндия", "финская", "финской", "финский", "финском", "Finland", "Finnish"),
    ES("Испания", "испанская", "испанской", "испанский", "испанском", "Spain", "Spanish"),
    PL("Польша", "польская", "польской", "польский", "польском", "Poland", "Polish"),
    CZ("Чехия", "чешская", "чешской", "чешский", "чешском", "Czech Republic", "Czech");

    /** Название по-русски. */
    public final String nameRu;
    /** «русская» (школа). */
    public final String adjFem;
    /** «русской» (школы). */
    public final String adjFemGen;
    /** «русский» (художник). */
    public final String adjMasc;
    /** «русском» (языке). */
    public final String langPrep;
    /** Название по-английски. */
    public final String nameEn;
    /** Прилагательное по-английски: «Russian» (school). */
    public final String adjEn;

    Country(String nameRu, String adjFem, String adjFemGen, String adjMasc, String langPrep, String nameEn, String adjEn) {
        this.nameRu = nameRu;
        this.adjFem = adjFem;
        this.adjFemGen = adjFemGen;
        this.adjMasc = adjMasc;
        this.langPrep = langPrep;
        this.nameEn = nameEn;
        this.adjEn = adjEn;
    }

    /** Код ISO 3166-1 alpha-2. */
    public String code() {
        return name();
    }
}
