package io.hammerhall.data.auc.auction.outcome;

import io.hammerhall.data.auc.auction.Cols;

/**
 * Журнал вещи ({@code item_status_log}) компактно: строки копятся в массивах по мере моделирования (приём, лоты,
 * оплата, решения «повтор или возврат»), в конце сортируются по (вещь, момент, порядок появления) и получают
 * {@code seq}. Лот — естественным ключом ({@code Keys.lot}): {@code lots.id} подставляется при записи. Строки с
 * моментом не раньше среза не копятся.
 */
public final class ItemLog {
    private final long cut;
    private int n;
    private final Cols.Ints item = new Cols.Ints(), at = new Cols.Ints(), consignment = new Cols.Ints();
    private final Cols.Bytes status = new Cols.Bytes();
    private final Cols.Longs lotKey = new Cols.Longs();

    /** {@code cut} — срез фактов, секунды Unix. */
    public ItemLog(long cut) {
        this.cut = cut;
    }

    public void add(int itemId, long atSec, byte st, long lot, int consignmentId) {
        if (atSec >= cut || itemId <= 0) return;
        item.add(itemId);
        at.add(Math.toIntExact(atSec));
        status.add(st);
        lotKey.add(lot);
        consignment.add(consignmentId);
        n++;
    }

    public int size() {
        return n;
    }

    /** Порядок строк таблицы: индексы, отсортированные по (вещь, момент, порядок появления) — слиянием, 8 байт на строку. */
    public int[] order() {
        return Cols.sortedIndex(n, (a, b) -> Long.compare(key(a), key(b)));
    }

    private long key(int i) {
        return (long) item.get(i) << 32 | (at.get(i) & 0xFFFF_FFFFL);
    }

    public int item(int i) {
        return item.get(i);
    }

    public long at(int i) {
        return at.get(i) & 0xFFFF_FFFFL;
    }

    public byte status(int i) {
        return status.get(i);
    }

    public long lotKey(int i) {
        return lotKey.get(i);
    }

    public int consignment(int i) {
        return consignment.get(i);
    }
}
