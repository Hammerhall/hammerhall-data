package io.hammerhall.data.auc.auction.engine;

/**
 * Попытка ставки, отклонённая до движка: нет регистрации или она не одобрена,
 * блокировка, нет допуска к дорогому лоту, свой лот, лимит, сумма не на сетке, ставка до открытия, снятый лот. В
 * движок не идёт — вливается в журнал ставок лота по времени ({@code bids.seq}); поставленный так максимум — отклонённая
 * редакция поручения.
 *
 * @param atMs        момент, миллисекунды Unix (UTC)
 * @param participant индекс участника в {@link LotInput#participants()}
 * @param amountKop   сумма (у {@code off_increment} — не на сетке), копейки
 * @param max         поставлен максимум (онлайн), иначе ручная ставка
 * @param reason      причина ({@link Codes#REASONS}: {@code off_increment} … {@code lot_withdrawn})
 */
public record Attempt(long atMs, int participant, long amountKop, boolean max, byte reason) {
    public Attempt {
        if (amountKop <= 0) throw new IllegalArgumentException("сумма " + amountKop);
        if (reason < Codes.R_OFF_INCREMENT || reason > Codes.R_LOT_WITHDRAWN) {
            throw new IllegalArgumentException("причина до движка — off_increment … lot_withdrawn, а не "
                    + Codes.REASONS.get(reason));
        }
    }
}
