package io.hammerhall.data.sto.orders;

import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.sto.catalog.Catalog;

import java.util.Locale;

/**
 * Тексты: отзывы — из шаблонов фраз по оценке (без имён людей и марок: вид товара — словами каталога), поисковые
 * запросы — из слов видов товаров, категорий и моделей, с опечатками, транслитом и неверной раскладкой.
 */
final class Texts {
    private Texts() {
    }

    private static final String[][] OPEN = {
            {},
            {"Очень разочарован.", "Не советую.", "Плохая покупка.", "Жаль потраченных денег.", "Не рекомендую."},
            {"Ожидал большего.", "Так себе.", "Есть недостатки.", "Средне, скорее плохо."},
            {"Нормально, но есть вопросы.", "За свои деньги — терпимо.", "Неплохо, но не идеально.", "В целом ничего."},
            {"Хорошая покупка.", "В целом доволен.", "Хороший вариант за эти деньги.", "Пользуюсь, всё устраивает."},
            {"Отличная покупка!", "Очень доволен.", "Рекомендую.", "Всё супер.", "Лучше, чем ожидал."}};
    private static final String[][] BODY = {
            {},
            {"%s перестал работать через пару дней.", "Пришёл с браком, оформил возврат.", "Описание не соответствует.",
                    "Качество сборки ужасное.", "Не включается.", "Упаковка вскрыта, комплект неполный."},
            {"%s шумит сильнее, чем хотелось бы.", "Материалы дешёвые.", "Работает, но с перебоями.",
                    "Инструкция непонятная.", "Батарея садится быстро."},
            {"%s работает, но есть мелкие недочёты.", "Для дома подойдёт.", "Цена могла бы быть ниже.",
                    "Пришлось разбираться с настройкой."},
            {"%s делает то, что нужно.", "Качество на уровне.", "Удобно пользоваться.", "Работает тихо.",
                    "Настроил за пять минут."},
            {"%s работает без нареканий.", "Качество отличное.", "Пользуюсь каждый день, всё нравится.",
                    "Выглядит аккуратно, работает отлично.", "Взял второй — в подарок."}};
    private static final String[] LATE = {"Доставка задержалась на несколько дней.", "Привезли позже обещанного.",
            "Ждал дольше, чем обещали."};
    private static final String[] FAST = {"Доставили быстро.", "Привезли вовремя.", "Упаковано надёжно."};

    /** Отзыв: 1–3 фразы по оценке; вид товара — словами каталога. */
    static String review(Rng r, int score, Catalog.Product p, boolean late) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.pick(OPEN[score]));
        if (r.chance(0.8)) {
            String b = r.pick(BODY[score]);
            sb.append(' ').append(b.contains("%s") ? b.replace("%s", capital(p.kind.nameRu)) : b);
        }
        if (late && r.chance(0.6)) sb.append(' ').append(r.pick(LATE));
        else if (!late && score >= 4 && r.chance(0.3)) sb.append(' ').append(r.pick(FAST));
        String s = sb.toString();
        return PublicNames.isInternal(s) ? r.pick(OPEN[score]) : s;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    // ---------------------------------------------------------------- поиск

    private static final String RU = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] LAT = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o",
            "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"};
    /** Те же клавиши в латинской раскладке (без символов, которые нельзя в TSV). */
    private static final String KEYS = "f,dult`;pbqrkvyjghcnea[wxio]sm'.z";

    /** Поисковый запрос о товаре; проверен на внутренние имена (иначе — название вида). */
    static String query(Rng r, Catalog.Product p) {
        String q;
        double u = r.nextDouble();
        if (u < 0.45) q = p.kind.nameRu.toLowerCase(Locale.ROOT);
        else if (u < 0.65) q = p.category.nameRu.toLowerCase(Locale.ROOT);
        else if (u < 0.85) q = p.display.toLowerCase(Locale.ROOT);
        else q = p.brand.name().toLowerCase(Locale.ROOT) + " " + p.kind.nameRu.toLowerCase(Locale.ROOT);
        if (q.length() > 40) q = p.kind.nameRu.toLowerCase(Locale.ROOT);
        double v = r.nextDouble();
        if (v < 0.09) q = typo(r, q);
        else if (v < 0.13) q = translit(q);
        else if (v < 0.16) q = layout(q);
        q = q.strip();
        if (q.isEmpty() || PublicNames.isInternal(q)) q = p.kind.nameRu.toLowerCase(Locale.ROOT);
        return q;
    }

    static String typo(Rng r, String s) {
        if (s.length() < 4) return s;
        StringBuilder sb = new StringBuilder(s);
        int i = 1 + r.nextInt(s.length() - 2);
        if (!Character.isLetter(sb.charAt(i))) return s;
        switch (r.nextInt(3)) {
            case 0 -> sb.deleteCharAt(i);
            case 1 -> {
                char c = sb.charAt(i);
                sb.setCharAt(i, sb.charAt(i + 1));
                sb.setCharAt(i + 1, c);
            }
            default -> sb.insert(i, sb.charAt(i));
        }
        return sb.toString();
    }

    static String translit(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int k = RU.indexOf(c);
            sb.append(k >= 0 ? LAT[k] : String.valueOf(c));
        }
        return sb.toString();
    }

    static String layout(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int k = RU.indexOf(c);
            sb.append(k >= 0 ? KEYS.charAt(k) : c);
        }
        return sb.toString();
    }
}
