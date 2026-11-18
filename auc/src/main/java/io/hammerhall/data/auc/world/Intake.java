package io.hammerhall.data.auc.world;

import java.time.LocalDate;
import java.util.List;

/**
 * Поток приёма вещей ({@code CATALOG}, модуль {@code catalog} → подготовка торгов): по (отдел, сутки) — вещи со
 * скрытой стоимостью рынка V, моментом готовности к каталогу и отказами при приёме.
 *
 * <p>Поток — функция даты (не периода и не очереди торгов): он построен с {@link #firstDay()} по {@link #lastDay()} —
 * срез фактов + постоянный горизонт. {@code id} вещи — по ({@code received_at}, ключ вещи) на всём
 * потоке, поэтому продление периода дописывает вещи в хвост, не трогая прежние; таблица {@code items} — те из них,
 * что приняты на осмотр раньше среза.
 *
 * <p>Визит ({@link Visit}) — один клиент принёс в один офис одну или несколько вещей: из принятых вещей визита
 * подготовка торгов делает один договор комиссии ({@code consignments}, {@code signed_at} = {@link Visit#decidedAt()}).
 * Сдачу купленного обратно ({@code resale}) поток не содержит — это решение подготовки торгов по итогам лотов.
 */
public interface Intake {
    /**
     * Наименьшая скрытая стоимость принятой вещи, копейки (1 500 ₽): дешевле — отказ «малая цена» или
     * вещь в лоте с подобными (V не ниже порога). Наименьший эстимейт онлайн — 1 000 ₽.
     */
    long MIN_VALUE_KOP = 1_500_00L;
    /**
     * Вещь потока. Деньги — копейки, моменты — секунды Unix.
     *
     * @param id           {@code items.id}
     * @param key          естественный ключ ({@link Keys#item}) — для случайности потребителя
     * @param dept         отдел
     * @param categoryId   {@code categories.id} — лист дерева отдела
     * @param makerId      {@code makers.id}; 0 — мастер неизвестен
     * @param makerTier    уровень рынка мастера {@code 'A'}–{@code 'D'}; {@code 'D'} — и у неизвестного
     * @param attribution  код атрибуции ({@code items.attribution}): {@code by} … {@code unknown}
     * @param visitKey     ключ визита ({@link Visit#key()})
     * @param consignorId  {@code clients.id} — кто принёс
     * @param officeId     {@code offices.id}
     * @param specialistId {@code staff.id} — кто смотрел
     * @param receivedAt   принята на осмотр
     * @param decidedAt    решение о приёме (у всех вещей визита — одно)
     * @param accepted     принята на комиссию
     * @param declineReason причина отказа ({@code low_value}, {@code condition}, {@code authenticity_doubt},
     *                     {@code duplicate}); {@code null} — принята
     * @param readyAt      готова к каталогу (описание, фото, эстимейт): решение + 5–20 дней; 0 — не принята
     * @param valueKop     скрытая стоимость рынка V — в выпуск не идёт
     */
    record Item(int id, long key, Dept dept, int categoryId, int makerId, char makerTier, String attribution,
                long visitKey, int consignorId, int officeId, int specialistId, long receivedAt, long decidedAt,
                boolean accepted, String declineReason, long readyAt, long valueKop) {
    }

    /**
     * Визит приёма.
     *
     * @param key          {@link Keys#visit}
     * @param consignorId  {@code clients.id}
     * @param officeId     {@code offices.id}
     * @param specialistId {@code staff.id} — специалист, подписывающий договор
     * @param kind         вид договора для принятых вещей: {@code private}, {@code dealer}, {@code estate},
     *                     {@code institution} ({@code consignments.kind}; {@code resale} поток не даёт)
     * @param receivedAt   принесены
     * @param decidedAt    решение и договор
     * @param itemIds      все вещи визита ({@code items.id}, по возрастанию), принятые и нет
     */
    record Visit(long key, int consignorId, int officeId, int specialistId, String kind, long receivedAt,
                 long decidedAt, int[] itemIds) {
    }

    /** Первые сутки потока (по Москве) — вещи для первых торгов (с 01.06.2025). */
    LocalDate firstDay();

    /** Последние сутки потока: срез + горизонт. */
    LocalDate lastDay();

    /** Сколько вещей в потоке; {@code id} — 1…{@code size()}. */
    int size();

    /** Сколько вещей принято на осмотр раньше момента (это первые {@code id}: они идут по времени). */
    int countReceivedBefore(long at);

    Item item(int id);

    /** Визиты суток (все офисы) — по {@code receivedAt}, затем ключу. */
    List<Visit> visits(LocalDate day);

    /**
     * Принятые вещи отдела, готовые к каталогу в {@code [fromAt, toAt)} — {@code id} по {@code readyAt}, затем {@code id}.
     * Подготовка торгов берёт их в очередь отдела по порядку готовности.
     */
    int[] ready(Dept d, long fromAt, long toAt);

    // ---- быстрый доступ без записи (те же значения, что в {@link #item})

    Dept dept(int id);

    int categoryId(int id);

    long valueKop(int id);

    long readyAt(int id);

    int consignorId(int id);
}
