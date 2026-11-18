package io.hammerhall.data.auc.bid;

/**
 * Участник лота и его намерение. Деньги — копейки, время — миллисекунды Unix (UTC).
 *
 * @param id       ключ участника у генератора (номер регистрации, таблички) — переходит в события; больше 0
 * @param channel  канал: у онлайн-лота — только {@link Channel#ONLINE}; у живого — зал, телефон, онлайн или заочная
 * @param strategy узор поведения ({@link Strategy}); {@link Strategy#SHILL} — подставной, в событиях не виден
 * @param max      наибольшая сумма, которую участник готов дать (у подставного — потолок разгона); приводится к сетке
 *                 шагов вниз; молоток у победителя никогда не выше её
 * @param placedAt заочная ставка и поручение «из книги» — когда оставлены (раньше оставленное побеждает при равных);
 *                 онлайн — с какого момента участник может действовать (0 — с открытия лота); у прочих живых — не
 *                 используется
 */
public record Bidder(long id, Channel channel, Strategy strategy, long max, long placedAt) {
    public Bidder {
        if (id <= 0) throw new IllegalArgumentException("участник " + id + ": ключ должен быть больше 0");
        if (channel == null || channel == Channel.AUCTIONEER) {
            throw new IllegalArgumentException("участник " + id + ": канал " + channel);
        }
        if (strategy == null) throw new IllegalArgumentException("участник " + id + ": нет стратегии");
        if (max <= 0) throw new IllegalArgumentException("участник " + id + ": максимум " + max);
    }

    public static Bidder online(long id, Strategy strategy, long max) {
        return new Bidder(id, Channel.ONLINE, strategy, max, 0);
    }

    public static Bidder of(long id, Channel channel, Strategy strategy, long max) {
        return new Bidder(id, channel, strategy, max, 0);
    }

    /** Заочная ставка: максимум и момент, когда её оставили. */
    public static Bidder absentee(long id, long max, long placedAt) {
        return new Bidder(id, Channel.ABSENTEE, Strategy.BOOK, max, placedAt);
    }

    /**
     * Телефонная заявка, по которой не дозвонились: сотрудник исполняет страховочную ставку как заочную — из книги, по
     * моменту, когда её оставили, но в канале {@link Channel#PHONE}.
     */
    public static Bidder phoneStandIn(long id, long max, long placedAt) {
        return new Bidder(id, Channel.PHONE, Strategy.BOOK, max, placedAt);
    }

    /** Исполняется аукционистом из книги (заочная или страховочная телефонная). */
    public boolean book() {
        return channel == Channel.ABSENTEE || strategy == Strategy.BOOK;
    }

    /** Подставной участник (связан с продавцом) — скрытая метка для проверки. */
    public boolean shill() {
        return strategy == Strategy.SHILL;
    }
}
