package io.hammerhall.data.auc.money;

import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.schema.Table;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Пять таблиц денег: порядок полей, в котором их кладёт модуль. В выпуск строка идёт в порядке полей описания
 * схемы ({@code AucSchema}) — поля сопоставляются по имени, поэтому модуль не зависит от порядка полей в описании.
 */
public final class MoneyTables {
    private MoneyTables() {
    }

    public static final Map<String, List<String>> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("invoices", List.of("id", "number", "sale_id", "registration_id", "client_id", "kind", "issued_at",
                "due_at", "hammer_total", "premium_total", "platform_fee_total", "vat_total", "total", "late_fee",
                "paid_total", "status", "paid_at", "cancelled_at", "cancel_reason", "related_invoice_id"));
        COLUMNS.put("invoice_lines", List.of("invoice_id", "line_no", "lot_id", "price", "premium_schedule_id", "premium",
                "platform_fee", "vat_rate", "vat", "line_total"));
        COLUMNS.put("settlements", List.of("id", "sale_id", "consignor_id", "created_at", "hammer_total",
                "commission_total", "fees_total", "vat_total", "net_total", "paid_total", "status"));
        COLUMNS.put("settlement_lines", List.of("settlement_id", "line_no", "lot_id", "outcome", "price", "commission",
                "insurance_fee", "catalogue_fee", "withdrawal_fee", "vat", "net", "payable_at", "paid_at"));
        COLUMNS.put("payments", List.of("id", "client_id", "kind", "method", "invoice_id", "registration_id",
                "settlement_id", "amount", "at", "status", "failure_reason", "provider_ref"));
    }

    public static final List<String> TABLES = List.copyOf(COLUMNS.keySet());

    /** Куда модуль кладёт строки (в тестах — память, в генераторе — приёмники ядра). */
    public interface RowOut {
        /** Строка — значения в порядке {@link #COLUMNS}. */
        void add(String table, Object... values) throws IOException;

        /** Таблица дописана (у приёмника ядра — закрыть: ссылки на неё дальше проверяются сразу). */
        default void close(String table) throws IOException {
        }
    }

    /** Приёмник ядра: переставляет значения в порядок полей описания таблицы. */
    static final class SinkOut implements RowOut {
        private final Map<String, TableSink> sinks;
        private final Map<String, int[]> perm = new LinkedHashMap<>();

        SinkOut(Map<String, TableSink> sinks) {
            this.sinks = sinks;
            for (Map.Entry<String, TableSink> e : sinks.entrySet()) {
                Table t = e.getValue().table();
                List<String> mine = COLUMNS.get(e.getKey());
                if (t.width() != mine.size()) {
                    throw new IllegalStateException(e.getKey() + ": в описании схемы " + t.width() + " полей, у модуля денег "
                            + mine.size() + " — сверить со схемой выпуска");
                }
                int[] p = new int[mine.size()];
                for (int i = 0; i < p.length; i++) {
                    int k = t.indexOf(mine.get(i));
                    if (k < 0) throw new IllegalStateException(e.getKey() + ": нет поля " + mine.get(i) + " в описании схемы");
                    p[i] = k;
                }
                perm.put(e.getKey(), p);
            }
        }

        @Override
        public void add(String table, Object... values) throws IOException {
            int[] p = perm.get(table);
            Object[] row = new Object[values.length];
            for (int i = 0; i < values.length; i++) row[p[i]] = values[i];
            sinks.get(table).add(row);
        }

        @Override
        public void close(String table) throws IOException {
            sinks.get(table).close();
        }
    }
}
