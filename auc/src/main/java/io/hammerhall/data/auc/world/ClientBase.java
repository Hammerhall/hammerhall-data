package io.hammerhall.data.auc.world;

import io.hammerhall.data.auc.bid.Strategy;
import io.hammerhall.data.core.random.Rng;

import java.util.List;

/**
 * База клиентов ({@code CLIENTS}, модуль {@code people} → торги, деньги, сайт): люди и организации по дате
 * регистрации, их скрытые черты, устройства и связи учётных записей.
 *
 * <p>База — функция даты: рост — кривая с опорой на постоянную дату, клиент — от ({@code seed}, номер по времени
 * регистрации), поэтому {@code id} (по {@code registered_at}) при продлении не меняются, новые дописываются в хвост.
 * База построена по срез + горизонт; таблица {@code clients} — те, кто зарегистрирован раньше среза. Поля «на
 * „сейчас“» таблицы ({@code blocked_at}, {@code block_reason}, {@code enhanced_verified_at}) зависят от торгов —
 * их пишет модуль {@code clients} в конце, по {@link AuctionWorld#clientOutcome}.
 *
 * <p>Скрытые черты в выпуск не идут (в {@code SOURCE.md} — словами). Доступ — по {@code id} без записи на клиента:
 * база в {@code full} — ≈ 0,9 млн клиентов, массивы примитивов.
 */
public interface ClientBase {
    /**
     * Связь учётных записей.
     *
     * @param kind     {@code shill} — подставной участник {@code clientId} привязан к комитенту {@code otherId} (общее
     *                 устройство); {@code ring} — {@code clientId} входит в сговор торговцев номер {@code group} отдела
     *                 {@code dept} ({@code otherId} = 0); {@code reregistration} — {@code clientId} — новая учётная
     *                 запись неплательщика {@code otherId} с того же устройства
     * @param clientId учётная запись
     * @param otherId  связанная учётная запись; 0 — нет (сговор)
     * @param group    номер группы сговора с 1; 0 — не сговор
     * @param dept     отдел сговора; {@code null} — не сговор
     */
    record Link(String kind, int clientId, int otherId, int group, Dept dept) {
    }

    /** Клиентов в базе (по срез + горизонт); {@code id} — 1…{@code size()}. */
    int size();

    /** Сколько клиентов зарегистрировано раньше момента — это первые {@code id}. */
    int countRegisteredBefore(long at);

    // ---- поля таблицы clients (кроме «на сейчас»)

    long registeredAt(int id);

    /** {@code company} — организация (галерея, салон, музей, библиотека); иначе человек. */
    boolean company(int id);

    /** Организация-учреждение (музей, библиотека) — договоры {@code institution}. */
    boolean institution(int id);

    boolean dealer(int id);

    int cityId(int id);

    /** Документ и карта проверены; 0 — нет. */
    long verifiedAt(int id);

    /** Год рождения у людей; 0 — организация. */
    int birthYear(int id);

    /** {@code ru} или {@code en}. */
    String language(int id);

    boolean marketingConsent(int id);

    /** Согласие на аналитику: визиты сайта пишутся только у таких (как у {@code sto}). */
    boolean analyticsConsent(int id);

    // ---- скрытые черты

    /** Интерес к отделу: доля внимания 0…1, сумма по отделам — 1 (у не покупающих — 0 везде). */
    float interest(int id, Dept d);

    /** Покупает (участвует в торгах); комитент без покупок — {@code false}. */
    boolean buyer(int id);

    /** Торгов в год, в которых клиент участвует (медиана 2, среднее 5, p90 12, p99 60). */
    float salesPerYear(int id);

    /** До какого момента клиент покупает (потом — «спящий»: участников из спящих не выбирают); секунды Unix. */
    long activeUntil(int id);

    /** Типичный бюджет на лот — верх своей цены, копейки (своя цена участника — V × вкус, не выше бюджета). */
    long budgetKop(int id);

    /** Узор поведения в онлайн-торгах для движка: {@code EARLY}, {@code SNIPER}, {@code CAUTIOUS}, {@code MAX}. */
    Strategy strategy(int id);

    /** Доля лотов, на которые ставит максимумом (онлайн) или заочно (живые) — 45 % онлайн-ставок. */
    float maxShare(int id);

    /** Живые торги: склонность к телефону (дорогие лоты) и к залу; остальное — онлайн. */
    float phoneShare(int id);

    float roomShare(int id);

    /**
     * Вероятность оплатить счёт (надёжность): {@code 1 − payReliability} по покупателям — логнормально со средним ≈ 0,03
     * (у планировщика оплаты отказ = доля правила × (1 − p) / 0,03). У новых клиентов отказ чаще — правило планировщика.
     */
    float payReliability(int id);

    /** Средняя задержка оплаты, дней: экспоненциально со средним 4 дня (доли дней оплаты — у планировщика оплаты). */
    float payDelayDays(int id);

    /** Скрытая метка {@code serial_non_payer}: не платит (≥ 2 неоплат на учётную запись или на устройство). */
    boolean serialNonPayer(int id);

    /** Сдаёт вещи на комиссию; приём выбирает комитентов из таких. */
    boolean consignor(int id);

    /**
     * Блокировка помимо неуплаты (её решают торги): по просьбе клиента ({@code own_request}) или по подозрению в
     * подставных ставках ({@code shill_suspected}) — момент, секунды Unix; 0 — нет. Оркестр учитывает её в
     * {@link ClientState#blocked}, модуль {@code clients} — в {@code blocked_at}, если она раньше блокировки за неуплату.
     */
    long otherBlockAt(int id);

    /** Причина {@link #otherBlockAt}: {@code own_request}, {@code shill_suspected}; {@code null} — нет. */
    String otherBlockReason(int id);

    // ---- устройства и связи

    /** Устройства клиента (1–3): ключ устройства — {@code sessions.device_key} (16 hex, {@link #deviceHex}). */
    long[] devices(int id);

    /** Ключ устройства для данных: 16 шестнадцатеричных цифр. */
    static String deviceHex(long device) {
        return String.format(java.util.Locale.ROOT, "%016x", device);
    }

    /** Все связи учётных записей — по виду, затем {@code clientId}. */
    List<Link> links();

    /** Подставной ли клиент ({@code shill}) — и для какого комитента; 0 — нет. */
    int shillFor(int id);

    /** Номер группы сговора; 0 — не в сговоре. */
    int ring(int id);

    // ---- выбор участников

    /**
     * Случайный покупатель с интересом к отделу, зарегистрированный раньше момента, — с весом «интерес × торгов в год».
     * 0 — таких нет. Случайность — от вызывающего.
     */
    int sampleBuyer(Dept d, long before, Rng r);
}
