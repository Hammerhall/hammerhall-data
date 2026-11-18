package io.hammerhall.data.auc.world;

import java.time.LocalDate;
import java.util.List;

/**
 * Планировщик оплаты (модуль денег → торги): функция без состояния — счёт → план (части, неудачные попытки, день полной
 * оплаты или отказ с отменой на 31-й день, второй шанс), ключ случайности — счёт ({@link Keys#invoice}). Вызывается из
 * цикла {@code auction}; модуль {@code money} пишет по тем же планам суммы M1–M7. Реализация берёт
 * {@link HouseRules} и {@link ClientBase} в конструкторе.
 */
public interface PaymentPlanner {
    /**
     * Строка будущего счёта — лот.
     *
     * @param lotKey   {@link Keys#lot}
     * @param priceKop молоток, цена продажи после торгов или второй ставки; у {@code shortfall} — недостача
     * @param platform ставка-победитель пришла через партнёрскую площадку (сбор {@code PLT-2024})
     */
    record Line(long lotKey, long priceKop, boolean platform) {
    }

    /**
     * Счёт до выставления.
     *
     * @param key        {@link Keys#invoice}
     * @param kind       вид
     * @param saleId     {@code sales.id}
     * @param format     формат торгов (отказ: живые 1,5 %, онлайн 3 %)
     * @param dept       отдел торгов (шкала премии — у монет своя)
     * @param saleDate   дата торгов по Москве (шкала премии и НДС)
     * @param regKey     {@link Keys#registration} покупателя
     * @param clientId   {@code clients.id}
     * @param newClient  меньше двух оплаченных счетов на момент выставления (отказ — 8 %)
     * @param issuedAt   выставлен (секунды Unix)
     * @param dueAt      срок оплаты
     * @param lines      строки по лотам
     * @param depositKop депозит регистрации в зачёт; 0 — нет
     * @param relatedKey у {@code second_chance} и {@code shortfall} — счёт неплательщика, у {@code reissue} —
     *                   заменённый; 0 — нет
     */
    record InvoiceDraft(long key, InvoiceKind kind, int saleId, Format format, Dept dept, LocalDate saleDate,
                        long regKey, int clientId, boolean newClient, long issuedAt, long dueAt, List<Line> lines,
                        long depositKop, long relatedKey) {
        public InvoiceDraft {
            lines = List.copyOf(lines);
        }
    }

    /**
     * Попытка платежа.
     *
     * @param at            момент (секунды Unix)
     * @param amountKop     сумма
     * @param method        способ
     * @param ok            прошла
     * @param failureReason {@code declined}, {@code insufficient_funds}, {@code timeout}, {@code limit}; {@code null} — прошла
     */
    record Attempt(long at, long amountKop, PayMethod method, boolean ok, String failureReason) {
    }

    /**
     * План оплаты счёта.
     *
     * @param invoiceKey           {@link Keys#invoice}
     * @param attempts             попытки по времени (с неудачными; депозит в зачёт — способом {@code DEPOSIT})
     * @param paidAt               полная оплата; 0 — не оплачен
     * @param defaults             окончательный отказ: отмена {@code non_payment} в {@code cancelAt}
     * @param cancelAt             момент отмены (31-й день); 0 — нет
     * @param secondChanceOffered  после отказа лот предложен второй ставке (25 % отказов)
     * @param secondChanceAccepted вторая ставка согласилась (половина предложений)
     */
    record PaymentPlan(long invoiceKey, List<Attempt> attempts, long paidAt, boolean defaults, long cancelAt,
                       boolean secondChanceOffered, boolean secondChanceAccepted) {
        public PaymentPlan {
            attempts = List.copyOf(attempts);
        }
    }

    PaymentPlan plan(InvoiceDraft invoice);
}
