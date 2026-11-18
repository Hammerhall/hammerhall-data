package io.hammerhall.data.auc.world;

import java.time.LocalDate;
import java.util.List;

/**
 * Календарь торгов ({@code CALENDAR}, пакет {@code auction.calendar} → торги, сайт): функция даты — торги выпуска по
 * {@code sales.id}, в том числе назначенные после «сейчас». В выпуске — торги,
 * чей каталог по плану публикуется внутри {@code [начало, конец + 1 сутки)}; {@code id} — по (плановая публикация,
 * {@code starts_at}, код): так набор замкнут по порядку публикации и при {@code --to}, и при продлении. Случайное в
 * календаре — от ключа торгов ({@link Keys#sale}), не от периода. Состояние на срез ({@code status}, {@code ends_at})
 * — у оркестра торгов.
 */
public interface Calendar {
    /**
     * Торги. Моменты — секунды Unix; 0 — нет (у назначенных без каталога).
     *
     * @param id                {@code sales.id} (по плановой публикации, затем началу)
     * @param key               {@link Keys#sale}
     * @param code              {@code T-2026-0312}, {@code L-2026-014}
     * @param format            живые или онлайн
     * @param tier              вид
     * @param dept              отдел (у тематических — отдел темы)
     * @param officeId          зал (живые) или Москва (онлайн)
     * @param themeCategoryId   тематические — категория темы ({@code categories.id}); 0 — весь отдел
     * @param titleRu           название по-русски
     * @param titleEn           название по-английски
     * @param publishedAt       каталог опубликован (0 — назначены, каталога нет и не будет до конца периода)
     * @param biddingOpensAt    открыты ставки и регистрация
     * @param previewFrom       выставка (живые); {@code null} — нет
     * @param previewTo         выставка до (включительно)
     * @param startsAt          живые — начало первой сессии; онлайн — плановое закрытие первой группы волны
     * @param days              живые — дней торгов (сессии), онлайн — вечеров волны
     * @param targetLots        лотов в каталоге по плану календаря (логнормально; × масштаб, не меньше пола)
     * @param premiumScheduleId {@code fee_schedules.id} — по дате торгов, у монет — {@code BP-COIN}
     * @param paymentDueDays    срок оплаты (10)
     */
    record Sale(int id, long key, String code, Format format, SaleTier tier, Dept dept, int officeId,
                int themeCategoryId, String titleRu, String titleEn, long publishedAt, long biddingOpensAt,
                LocalDate previewFrom, LocalDate previewTo, long startsAt, int days, int targetLots,
                int premiumScheduleId, int paymentDueDays) {
        public boolean live() {
            return format == Format.LIVE;
        }

        /** Дата торгов по Москве — для шкалы премии и НДС. */
        public LocalDate saleDate() {
            return java.time.Instant.ofEpochSecond(startsAt).atZone(HouseRules.ZONE).toLocalDate();
        }
    }

    /** Все торги в порядке {@code id}. */
    List<Sale> sales();

    Sale sale(int id);

    /** Торги, открывающие ставки в {@code [fromAt, toAt)}, — по {@code biddingOpensAt}, затем {@code id} (порядок оркестра). */
    List<Sale> openingBetween(long fromAt, long toAt);
}
