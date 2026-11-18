package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.ClientState;
import io.hammerhall.data.auc.world.LotResult;
import io.hammerhall.data.auc.world.OrderEvents;
import io.hammerhall.data.auc.world.Participants;
import io.hammerhall.data.auc.world.Registrations;
import io.hammerhall.data.auc.world.SalePlan;
import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;

import java.io.IOException;
import java.util.List;

/**
 * Подготовка торгов (пакеты {@code auction.calendar}, {@code auction.intake}, {@code auction.demand}) — что вызывает
 * оркестр модуля {@code auction}, неделя за неделей. Порядок вызовов:
 * <ol>
 *   <li>{@link #open} — один раз; {@link #calendar} — торги на любую дату;</li>
 *   <li>для каждой недели (пн 00:00 по Москве), торги — по {@code Calendar.openingBetween}:
 *     <ol type="a">
 *       <li>{@link #catalog} — каталог каждых торгов недели, по порядку, в одном потоке (очередь готовых вещей);</li>
 *       <li>{@link #demand} — участники лотов и регистрации; торги недели — <b>параллельно</b> (метод потокобезопасен и
 *           зависит только от плана, снимков {@link ClientStates} и своих потоков случайности по ключам);</li>
 *       <li>торги — движок у оркестра; затем по торгам и лотам по порядку, в одном потоке: {@link #orders} и
 *           {@link #result} (решение «повтор или возврат»), в конце торгов — {@link #saleClosed};</li>
 *       <li>{@link #flush} — водораздел записи;</li>
 *     </ol></li>
 *   <li>{@link #finish} — остаток до среза и состояние на срез.</li>
 * </ol>
 * Моделирование — до среза + 180 суток ({@code Horizon}); строки — только с моментом раньше среза.
 */
public interface Prep {
    /**
     * Начало модуля: открыть свои таблицы ({@code sales}, {@code sale_sessions}, {@code consignments},
     * {@code consignment_items}, {@code registrations}, {@code bid_orders}). {@code ids} — {@code lots.id} по ключу лота
     * от оркестра (для {@code bid_orders.lot_id}): выдан к тому моменту, как водораздел прошёл публикацию лота.
     */
    void open(GenContext ctx, LotIds ids) throws IOException;

    Calendar calendar();

    /** Шаг «а»: каталог торгов — лоты по номеру (ключ {@code Keys.lot}), сессии живых торгов. */
    SalePlan catalog(Calendar.Sale sale);

    /**
     * Шаг «б»: участники каждого лота каталога (в порядке лотов плана) и регистрации торгов (видны через
     * {@link #registrations}). Регистрация решается по снимку на понедельник недели её подачи — {@code states.at(...)}.
     * Поручения до торгов (заочные, телефон) — редакция 1 ({@code Participants.Participant.orderKey}); онлайн-максимумы
     * рождаются в торгах ({@link #orders}).
     */
    List<Participants> demand(SalePlan plan, ClientStates states);

    /** Регистрации и редакции поручений: ключ → {@code id} (выдан, когда водораздел прошёл момент). */
    Registrations registrations();

    /** Шаг «в»: что движок сделал с максимумами лота (новые редакции и окончательные статусы). */
    void orders(OrderEvents events);

    /** Шаг «г»: моделируемый итог лота (без среза) → повтор или возврат. */
    Disposition result(LotResult result);

    /** Торги отторговались: конец последнего лота и концы сессий (последний молоток) — секунды; модель, без среза. */
    void saleClosed(int saleId, long endsAt, long[] sessionEndsAt);

    /** Водораздел: строки своих таблиц с моментом раньше {@code watershed} (секунды) окончательны — записать. */
    void flush(long watershed) throws IOException;

    /** Конец модуля: дописать строки до среза, поля «на „сейчас“» — на срез. */
    void finish() throws IOException;

    /** Лот записан оркестром (строка {@code lots}, все его ставки до среза): его данные для записи можно отпустить. */
    default void lotWritten(long lotKey) {
    }

    /**
     * Торги записаны оркестром целиком (все лоты и ставки до среза, {@code lots.id} выданы): каталог торгов можно
     * отпустить. После этого {@link #result} по лотам торгов не вызывается.
     */
    default void saleWritten(int saleId) {
    }

    /**
     * Генераторы закончили ({@code AuctionWorld.generatorsDone}): регистрации и договоры больше не спросят — итоги для
     * своих проверок посчитать сейчас, книги отпустить.
     */
    default void generatorsDone() {
    }

    /** Договор ({@code consignments.id}) — для отчётов комитентам и журнала вещи ({@code resale_received}). */
    AuctionWorld.Consignment consignment(int id);

    /** Договор, по которому вещь принята при приёме (для {@code accepted} журнала вещи); 0 — не принята. */
    int intakeConsignment(int itemId);

    /** Доменные проверки своих таблиц (регистрации ↔ ставки — тоже здесь). */
    default void check(GenContext ctx, CheckReport report) {
    }

    default List<Aggregate> aggregates() {
        return List.of();
    }

    /** {@code lots.id} по ключу лота ({@code Keys.lot}); 0 — ещё не выдан. */
    interface LotIds {
        int lotId(long lotKey);
    }

    /** Снимки состояния клиентов на понедельник 00:00 по Москве. */
    interface ClientStates {
        /** Снимок на понедельник {@code mondayAt} (секунды): по итогам торгов, обработанных до текущей недели. */
        ClientState at(long mondayAt);
    }

    /**
     * Решение по вещи после торгов: выставить снова или вернуть комитенту — с момента {@code at} (секунды);
     * {@link #NONE} — решать нечего (продан и оплачен, торги ещё идут).
     */
    record Disposition(Kind kind, long at) {
        public enum Kind {NONE, REOFFER, RETURN}

        public static final Disposition NONE = new Disposition(Kind.NONE, 0);

        public static Disposition reoffer(long at) {
            return new Disposition(Kind.REOFFER, at);
        }

        public static Disposition giveBack(long at) {
            return new Disposition(Kind.RETURN, at);
        }
    }
}
