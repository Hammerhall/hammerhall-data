package io.hammerhall.data.auc.auction.calendar;

import java.time.LocalDate;
import java.util.List;

/**
 * Торги в календаре — план, известный заранее (функция даты и {@code seed}): формат, отдел, сроки, размер
 * каталога в {@code full}, волна или дни сессий. Моменты — секунды Unix (UTC); даты — по Москве.
 *
 * @param key            естественный ключ: формат, вид, отдел или слот темы, год, номер ({@link Keys}) — от него
 *                       случайность торгов и всё, что от торгов зависит
 * @param code           {@code T-2026-0312} (онлайн) или {@code L-2026-014} (живые): номер — по порядку начала в году
 * @param number         номер в году (часть кода и названия)
 * @param live           живые торги в зале; иначе онлайн по времени ({@code timed})
 * @param tier           {@code regular}, {@code thematic}, {@code major}, {@code evening}
 * @param dept           индекс отдела ({@link Depts})
 * @param theme          индекс темы ({@link Depts#THEMES}) у тематических; −1 — без темы
 * @param office         офис: 1 — Москва (онлайн и главный зал), 2 — Санкт-Петербург (по порядку {@code offices})
 * @param publishedAt    публикация каталога (план; в выпуске — когда наступила)
 * @param biddingOpensAt открытие ставок и регистрации: онлайн — через 3 дня после публикации; живые — с публикацией
 * @param previewFrom    выставка (живые): первый день; {@code null} у онлайн
 * @param previewTo      выставка: последний день
 * @param startsAt       живые — начало первой сессии; онлайн — плановое закрытие первой группы волны (19:00 МСК)
 * @param durationDays   онлайн: ставки открыты 7, 10 или 14 суток; живые — 0
 * @param days           живые: дни торгов подряд (план по размеру каталога в {@code full}); онлайн — вечеров волны
 *                       в {@code full}
 * @param saleDays       живые: даты дней торгов (план); онлайн — пусто
 * @param firstMinute    живые: начало первой сессии, минут от полуночи по Москве (720, 960, 1140)
 * @param pace           живые: темп, лотов в час (40 — дорогие, 75 — общие, 110 — недорогие); онлайн — 0
 * @param targetLots     размер каталога в {@code full} (логнормально); в профиле — {@link #targetLots(double)}
 * @param premium        шкала премии покупателя по дате торгов: {@code BP-2024}, {@code BP-2026}, {@code BP-COIN}
 */
public record SaleSpec(long key, String code, int number, boolean live, String tier, int dept, int theme, int office,
                       long publishedAt, long biddingOpensAt, LocalDate previewFrom, LocalDate previewTo, long startsAt,
                       int durationDays, int days, List<LocalDate> saleDays, int firstMinute, int pace, int targetLots,
                       String premium) {

    /** Наименьший каталог в профиле: онлайн — 5, живые — 10. */
    public static final int MIN_LOTS_TIMED = 5, MIN_LOTS_LIVE = 10;
    /** Срок оплаты счёта, дней. */
    public static final int PAYMENT_DUE_DAYS = 10;

    public String format() {
        return live ? "live" : "timed";
    }

    /** Размер каталога в профиле: {@code full} × масштаб, не меньше 5 (онлайн) или 10 (живые). */
    public int targetLots(double scale) {
        int floor = live ? MIN_LOTS_LIVE : MIN_LOTS_TIMED;
        return Math.max(floor, (int) StrictMath.round(targetLots * scale));
    }

    public int year() {
        return Keys.year(key);
    }

    public boolean thematic() {
        return theme >= 0;
    }

    /** Название торгов по-русски: «Монеты и медали. Онлайн-торги № 312». */
    public String titleRu() {
        String what = theme >= 0 ? Depts.THEMES[theme].nameRu() : Depts.NAME_RU[dept];
        return switch (tier) {
            case "evening" -> "Вечерние торги. " + what + ". Торги № " + number;
            case "major" -> what + ". Главные торги сезона № " + number;
            default -> what + (live ? ". Торги № " : ". Онлайн-торги № ") + number;
        };
    }

    /** Название по-английски: «Coins and Medals. Online Auction No. 312». */
    public String titleEn() {
        String what = theme >= 0 ? Depts.THEMES[theme].nameEn() : Depts.NAME_EN[dept];
        return switch (tier) {
            case "evening" -> "Evening Sale. " + what + ". Auction No. " + number;
            case "major" -> what + ". Major Season Sale No. " + number;
            default -> what + (live ? ". Auction No. " : ". Online Auction No. ") + number;
        };
    }

    /** Ключ торгов: формат, вид, отдел (или слот темы), год, номер — упаковано в {@code long}. */
    public static final class Keys {
        private Keys() {
        }

        /** Вид в ключе: 0 — отдел еженедельно / живые обычные, 1 — тематические, 2 — главные, 3 — вечерние. */
        public static long of(boolean live, int kind, int sub, int year, int number) {
            if (kind < 0 || kind > 3 || sub < 0 || sub > 255 || year < 0 || year > 16383 || number < 0 || number > 0xFFFFF) {
                throw new IllegalArgumentException("ключ торгов: " + kind + " " + sub + " " + year + " " + number);
            }
            return (live ? 1L << 44 : 0) | ((long) kind << 42) | ((long) sub << 34) | ((long) year << 20) | number;
        }

        public static int year(long key) {
            return (int) ((key >>> 20) & 0x3FFF);
        }

        public static boolean live(long key) {
            return ((key >>> 44) & 1) != 0;
        }
    }
}
