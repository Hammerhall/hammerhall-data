package io.hammerhall.data.auc.world;

import java.util.List;

/**
 * Итоги модуля {@code auction} ({@code AUCTION} → модули {@code money}, {@code site}, {@code clients}): лоты и их
 * итоги, договоры, регистрации, планы счетов, ставки компактно и события-источники уведомлений, исход клиентов на срез.
 * Только факты до среза и состояние на срез.
 */
public interface AuctionWorld {
    /** Счёт: черновик и план оплаты — по ним модуль {@code money} пишет счета, строки и платежи. */
    record InvoicePlan(PaymentPlanner.InvoiceDraft draft, PaymentPlanner.PaymentPlan plan) {
    }

    /**
     * Договор комиссии — для отчётов комитентам (M4, M5).
     *
     * @param id                {@code consignments.id}
     * @param consignorId       {@code clients.id}
     * @param kind              {@code private}, {@code dealer}, {@code estate}, {@code institution}, {@code resale}
     * @param dealerDiscount    скидка торговца 5 п. п.
     * @param commissionMilliPct договорная единая ставка (тысячные доли %); {@code null} — по шкале
     * @param signedAt          подписан
     */
    record Consignment(int id, int consignorId, String kind, boolean dealerDiscount, Integer commissionMilliPct,
                       long signedAt) {
    }

    /**
     * Событие-источник уведомления ({@code notifications}).
     *
     * @param kind     {@code registration_decided}, {@code outbid}, {@code closing_soon}, {@code lot_won},
     *                 {@code sale_summary}
     * @param clientId адресат
     * @param saleId   {@code sales.id}; 0 — нет
     * @param lotId    {@code lots.id}; 0 — нет
     * @param bidId    {@code bids.id} — ставка-причина; 0 — нет
     * @param atMs     момент события, миллисекунды Unix
     * @param eventKey ключ события ({@code outbid:<lot>:<bid>}) — для идемпотентного потребителя
     */
    record Notice(String kind, int clientId, int saleId, int lotId, long bidId, long atMs, String eventKey) {
    }

    /**
     * Исход клиента на срез — поля «на „сейчас“» таблицы {@code clients}.
     *
     * @param blockedAt          заблокирован; 0 — нет
     * @param blockReason        {@code non_payment}, {@code shill_suspected}, {@code own_request}; {@code null} — нет
     * @param enhancedVerifiedAt расширенная проверка (счёт от 1 млн ₽, драгоценные металлы); 0 — нет
     */
    record ClientOutcome(long blockedAt, String blockReason, long enhancedVerifiedAt) {
        public static final ClientOutcome NONE = new ClientOutcome(0, null, 0);
    }

    /** Ставки компактно (для сайта): по {@code bids.id}; индекс {@code i} — 0…{@code size()-1}. */
    interface BidLog {
        int size();

        long id(int i);

        int lotId(int i);

        /** {@code clients.id}; 0 — ставка за продавца. */
        int clientId(int i);

        long atMs(int i);

        long amountKop(int i);

        boolean accepted(int i);

        /**
         * Ставка подана формой сайта или приложения дома — у неё есть {@code bid_submit} в визите: {@code channel = online},
         * {@code kind = manual}, регистрация не через {@code partner_platform}, причина отказа не {@code off_increment}
         * (это запрос мимо формы, через API). Повтор запроса ({@code duplicate}) — тоже да (второе нажатие).
         */
        boolean manualOnline(int i);
    }

    /**
     * Редакции поручений-максимумов онлайн, поданные с сайта или приложения ({@code via} ∈ {@code site}, {@code app}) —
     * факты до среза, по {@code bid_orders.id}: {@code PLACED}, {@code REBID}, {@code RAISED}, {@code REJECTED} (форма
     * отправлена). Для {@code order_set} ↔ {@code bid_orders} на сайте.
     */
    interface OrderLog {
        OrderLog EMPTY = new OrderLog() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public int id(int i) {
                throw new IndexOutOfBoundsException(i);
            }

            @Override
            public int lotId(int i) {
                throw new IndexOutOfBoundsException(i);
            }

            @Override
            public int clientId(int i) {
                throw new IndexOutOfBoundsException(i);
            }

            @Override
            public long atMs(int i) {
                throw new IndexOutOfBoundsException(i);
            }

            @Override
            public boolean app(int i) {
                throw new IndexOutOfBoundsException(i);
            }
        };

        int size();

        /** {@code bid_orders.id}. */
        int id(int i);

        int lotId(int i);

        int clientId(int i);

        long atMs(int i);

        /** Подано из приложения ({@code via = app}); иначе — с сайта. */
        boolean app(int i);
    }

    /**
     * Смоделированные участники лотов выпуска — без обрезки срезом (горизонт 180 суток), чтобы наблюдение до среза
     * не зависело от среза: участник, первая ставка которого после среза, наблюдает уже до него. Только лоты, опубликованные
     * до среза, и клиенты, зарегистрированные до среза. Для {@code watchlist}.
     */
    interface ParticipantLog {
        ParticipantLog EMPTY = new ParticipantLog() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public int lotId(int i) {
                throw new IndexOutOfBoundsException(i);
            }

            @Override
            public int clientId(int i) {
                throw new IndexOutOfBoundsException(i);
            }

            @Override
            public long firstBidAt(int i) {
                throw new IndexOutOfBoundsException(i);
            }

            @Override
            public boolean online(int i) {
                throw new IndexOutOfBoundsException(i);
            }
        };

        int size();

        int lotId(int i);

        int clientId(int i);

        /** Первая ставка, секунды Unix (может быть после среза); без ставок — план конца лота. */
        long firstBidAt(int i);

        /** Онлайн-канал (иначе зал, телефон, заочно). */
        boolean online(int i);
    }

    /**
     * План лота из каталога ({@code SalePlan.Lot}) — для сайта: публикация, плановый момент (онлайн — место в волне,
     * живые — плановое начало слота по темпу сессии), номер (страницы каталога по 20).
     */
    record LotPlan(long publishedAt, long plannedAt, int lotNo) {
    }

    /** Поручения-максимумы с сайта и приложения; по умолчанию — пусто, мир торгов переопределяет. */
    default OrderLog orders() {
        return OrderLog.EMPTY;
    }

    /** Смоделированные участники лотов; по умолчанию — пусто, мир торгов переопределяет. */
    default ParticipantLog participants() {
        return ParticipantLog.EMPTY;
    }

    /** План лота; {@code null} — неизвестен (по умолчанию), мир торгов переопределяет. */
    default LotPlan lotPlan(int lotId) {
        return null;
    }

    /** Лотов в выпуске; {@code lots.id} — 1…{@code lotCount()}. */
    int lotCount();

    LotResult lot(int lotId);

    /** Лоты торгов — по {@code lots.id}. */
    List<LotResult> lotsOfSale(int saleId);

    Consignment consignment(int id);

    Registrations registrations();

    /** Счета по моменту выставления, затем ключу. */
    List<InvoicePlan> invoices();

    BidLog bids();

    /** События для уведомлений — по моменту, затем ключу. */
    List<Notice> notices();

    /** Исход клиента на срез; {@link ClientOutcome#NONE} — ничего. */
    ClientOutcome clientOutcome(int clientId);

    /**
     * Генераторы взяли своё (вызывает сайт — последний генератор, — собрав вход): уведомления ({@link #notices}),
     * поручения ({@link #orders}), участники ({@link #participants}), планы лотов ({@link #lotPlan}), а также то, что
     * раньше взяли модули клиентов и денег, — счета ({@link #invoices}), регистрации ({@link #registrations}), договоры
     * ({@link #consignment}) и исход клиентов ({@link #clientOutcome}) — мир может отпустить (память {@code full}).
     * После вызова эти методы бросают {@link IllegalStateException}. Лоты и ставки остаются — для проверок модулей. По
     * умолчанию — ничего.
     */
    default void generatorsDone() {
    }
}
