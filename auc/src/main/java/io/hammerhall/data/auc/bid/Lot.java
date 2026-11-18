package io.hammerhall.data.auc.bid;

/**
 * Лот на торгах — то, что движку нужно о нём знать. Деньги — копейки, время — миллисекунды Unix (UTC).
 *
 * @param id         ключ лота у генератора (в событиях не участвует, нужен для сообщений об ошибках)
 * @param mode       онлайн или живые торги
 * @param startPrice стартовая цена: первая ставка — не ниже её (приводится к сетке шагов вверх)
 * @param reserve    резерв — наименьшая цена, за которую продавец согласен продать; 0 — без резерва
 * @param startAt    онлайн — открытие лота для ставок; живые — начало слота лота
 * @param endAt      онлайн — назначенное закрытие (без продлений), например по волне {@link Schedule#waveClose};
 *                   живые — конец слота (исключая), например {@link Schedule#liveSlotEnd}
 */
public record Lot(long id, Mode mode, long startPrice, long reserve, long startAt, long endAt) {
    public Lot {
        if (mode == null) throw new IllegalArgumentException("лот " + id + ": нет вида торгов");
        if (startPrice <= 0) throw new IllegalArgumentException("лот " + id + ": стартовая цена " + startPrice);
        if (reserve < 0) throw new IllegalArgumentException("лот " + id + ": резерв " + reserve);
        if (endAt <= startAt) throw new IllegalArgumentException("лот " + id + ": окно " + startAt + ".." + endAt);
    }

    public static Lot online(long id, long startPrice, long reserve, long openAt, long closeAt) {
        return new Lot(id, Mode.ONLINE, startPrice, reserve, openAt, closeAt);
    }

    public static Lot live(long id, long startPrice, long reserve, long slotStart, long slotEnd) {
        return new Lot(id, Mode.LIVE, startPrice, reserve, slotStart, slotEnd);
    }

    public boolean hasReserve() {
        return reserve > 0;
    }
}
