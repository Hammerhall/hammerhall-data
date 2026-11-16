package io.hammerhall.data.sto.stock;

import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.io.TableSink;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Куда движок пишет: журнал движений (сразу, строго по возрастанию id — он же порядок времени), заказы поставщикам
 * и партии (в конце, по id). {@link #open} — приёмники выпуска (таблицы должен объявить модуль, который ведёт
 * движок); в тестах — своя реализация в памяти.
 */
public interface StockLedger {
    enum Kind {
        RECEIPT("receipt"), MOVE("move"), TRANSFER_OUT("transfer_out"), TRANSFER_IN("transfer_in"), PICK("pick"),
        SHIP("ship"), CUSTOMER_RETURN("customer_return"), WRITEOFF("writeoff"), INVENTORY("inventory");
        public final String code;

        Kind(String code) {
            this.code = code;
        }
    }

    enum Ref {
        NONE(null), PURCHASE_ORDER("purchase_order"), ORDER("order"), SHIPMENT("shipment"), RETURN("return"),
        TRANSFER("transfer"), INVENTORY("inventory");
        public final String code;

        Ref(String code) {
            this.code = code;
        }
    }

    /** Движение; {@code cell}, {@code batch} = 0 — NULL; {@code ref} = NONE — без ссылки. */
    void movement(long id, long at, int warehouseId, int cell, int productId, int batch, Kind kind, int qty, Ref ref,
                  long refId);

    /** Заказ поставщику; {@code receivedAt} &lt; 0 — NULL. */
    void purchaseOrder(int id, int supplierId, int warehouseId, long orderedAt, long expectedAt, long receivedAt,
                       String status, String purpose);

    /** Партия; {@code poId}/{@code sellerId} = 0 — NULL, {@code receivedAt} &lt; 0 — в пути. */
    void batch(int id, int poId, int sellerId, int productId, int warehouseId, int qty, long unitCostKop,
               long receivedAt, int orderedQty);

    /** Заказы поставщикам записаны (до партий — чтобы ссылки партий проверялись сразу). */
    void purchaseOrdersDone();

    void close();

    /** Приёмники трёх таблиц выпуска; вызывать из {@code generate} модуля, объявившего эти таблицы. */
    static StockLedger open(GenContext ctx) {
        try {
            return new SinkLedger(ctx.streamSink("stock_movements"), ctx.streamSink("purchase_orders"),
                    ctx.streamSink("batches"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Запись в таблицы выпуска. */
    final class SinkLedger implements StockLedger {
        private final TableSink mv, po, b;

        SinkLedger(TableSink mv, TableSink po, TableSink b) {
            this.mv = mv;
            this.po = po;
            this.b = b;
        }

        @Override
        public void movement(long id, long at, int warehouseId, int cell, int productId, int batch, Kind kind, int qty,
                             Ref ref, long refId) {
            try {
                mv.add(id, at, warehouseId, cell == 0 ? null : cell, productId, batch == 0 ? null : batch, kind.code,
                        qty, ref.code, ref == Ref.NONE ? null : refId);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void purchaseOrder(int id, int supplierId, int warehouseId, long orderedAt, long expectedAt,
                                  long receivedAt, String status, String purpose) {
            try {
                po.add(id, supplierId, warehouseId, orderedAt, expectedAt, receivedAt < 0 ? null : receivedAt, status,
                        purpose);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void batch(int id, int poId, int sellerId, int productId, int warehouseId, int qty, long unitCostKop,
                          long receivedAt, int orderedQty) {
            try {
                b.add(id, poId == 0 ? null : poId, sellerId == 0 ? null : sellerId, productId, warehouseId, qty,
                        unitCostKop, receivedAt < 0 ? null : receivedAt, orderedQty);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void purchaseOrdersDone() {
            try {
                po.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            try {
                po.close();
                b.close();
                mv.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
