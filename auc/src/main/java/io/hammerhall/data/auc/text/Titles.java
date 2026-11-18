package io.hammerhall.data.auc.text;

/**
 * Заголовок лота, 20–120 знаков, по формуле каталога: живопись и графика — «Мастер. Название. Год» (атрибуция —
 * «Круг Ивана Сухотина», «Circle of Ivan Sukhotin»); вещи — «Предмет. Мастер, год» или «Предмет. Страна, год».
 * Монеты и книги собирают заголовок сами.
 */
final class Titles {
    private Titles() {
    }

    static final int MIN = 20, MAX = 120;

    private static String artistRu(Ctx c) {
        Maker m = c.maker;
        if (m == null || c.attribution == Attribution.UNKNOWN) {
            String who = switch (c.kind) {
                case PHOTO -> "Неизвестный фотограф";
                case SCULPTURE, BRONZE -> "Неизвестный скульптор";
                default -> "Неизвестный художник";
            };
            return c.country != null ? who + " " + c.country.adjFemGen + " школы" : who;
        }
        String gen = m.nameGen();
        return switch (c.attribution) {
            case ATTRIBUTED -> m.shortName() + " (приписывается)";
            case WORKSHOP -> "Мастерская " + gen;
            case CIRCLE -> "Круг " + gen;
            case FOLLOWER -> "Последователь " + gen;
            case MANNER -> "В манере " + gen;
            case AFTER -> "По оригиналу " + gen;
            default -> m.shortName();
        };
    }

    private static String artistEn(Ctx c) {
        Maker m = c.maker;
        if (m == null || c.attribution == Attribution.UNKNOWN) {
            return c.country != null ? c.country.adjEn + " School" : "Unknown Artist";
        }
        String n = m.shortNameEn();
        return switch (c.attribution) {
            case ATTRIBUTED -> "Attributed to " + n;
            case WORKSHOP -> "Workshop of " + n;
            case CIRCLE -> "Circle of " + n;
            case FOLLOWER -> "Follower of " + n;
            case MANNER -> "Manner of " + n;
            case AFTER -> "After " + n;
            default -> n;
        };
    }

    static void assemble(Ctx c) {
        String dRu = c.dated != null ? c.dated.shortRu() : Integer.toString(c.year);
        String dEn = c.dated != null ? c.dated.en() : Integer.toString(c.year);
        if (c.kind.formula == Kind.Formula.ART) {
            c.titleRu = fit(artistRu(c) + ". " + c.objectRu + ". " + W.cap(dRu), c.objectRu + ". " + W.cap(dRu), c.objectRu);
            c.titleEn = fit(artistEn(c) + ". " + c.objectEn + ". " + W.cap(dEn), c.objectEn + ". " + W.cap(dEn), c.objectEn);
            if (c.titleRu.length() < MIN || c.titleEn.length() < MIN) {
                String[] w = switch (c.kind) {
                    case SCULPTURE -> new String[]{"Скульптура", "Sculpture"};
                    case PHOTO -> new String[]{"Фотография", "Photograph"};
                    case DRAWING -> new String[]{"Рисунок", "Drawing"};
                    case PRINT -> new String[]{"Лист", "Print"};
                    default -> new String[]{"Картина", "Painting"};
                };
                c.titleRu = fit(artistRu(c) + ". " + w[0] + " «" + c.objectRu + "». " + W.cap(dRu), c.titleRu);
                c.titleEn = fit(artistEn(c) + ". " + w[1] + " “" + c.objectEn + "”. " + W.cap(dEn), c.titleEn);
            }
            return;
        }
        String whoRu, whoEn;
        if (c.maker != null) {
            whoRu = c.maker.shortName();
            whoEn = c.maker.shortNameEn();
        } else {
            Country k = c.country != null ? c.country : Country.RU;
            whoRu = k.nameRu;
            whoEn = k.nameEn;
        }
        c.titleRu = fit(W.dot(c.objectRu) + whoRu + ", " + dRu, c.objectRu + ", " + dRu, c.objectRu);
        c.titleEn = fit(W.dot(c.objectEn) + whoEn + ", " + dEn, c.objectEn + ", " + dEn, c.objectEn);
        if (c.maker == null) {
            Country k = c.country != null ? c.country : Country.RU;
            if (c.titleRu.length() < MIN) c.titleRu = fit(W.dot(c.objectRu) + W.cap(k.adjFem) + " работа, " + dRu, c.titleRu);
            if (c.titleEn.length() < MIN) c.titleEn = fit(W.dot(c.objectEn) + k.adjEn + " Work, " + dEn, c.titleEn);
        }
    }

    /** Первый вариант, что влезает в 120 знаков; короче 20 — дополняется последним годом из первого. */
    static String fit(String... variants) {
        for (String v : variants) if (v.length() <= MAX && v.length() >= MIN) return v;
        String last = variants[variants.length - 1];
        if (last.length() > MAX) return last.substring(0, MAX - 1).strip() + "…";
        String first = variants[0];
        return first.length() <= MAX ? first : last;
    }
}
