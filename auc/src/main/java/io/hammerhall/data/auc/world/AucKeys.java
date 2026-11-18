package io.hammerhall.data.auc.world;

import io.hammerhall.data.core.gen.Key;

/** Ключи общего мира набора «Дом Одина»: что какой модуль кладёт для следующих. */
public final class AucKeys {
    private AucKeys() {
    }

    /** Модуль {@code house}: правила дома, города, офисы, сотрудники, шкалы и сборы. */
    public static final Key<HouseRules> HOUSE = Key.of("auc.house");
    /** Модуль {@code people}: база клиентов по дате регистрации, скрытые черты, устройства, связи. */
    public static final Key<ClientBase> CLIENTS = Key.of("auc.clients");
    /** Модуль {@code catalog}: поток приёма вещей по (отдел, сутки), мастера. */
    public static final Key<Intake> CATALOG = Key.of("auc.catalog");
    /** Модуль {@code auction}, пакет {@code auction.calendar}: торги на любую дату. */
    public static final Key<Calendar> CALENDAR = Key.of("auc.calendar");
    /** Модуль {@code auction}: итоги лотов, ставки компактно, планы счетов, события для уведомлений. */
    public static final Key<AuctionWorld> AUCTION = Key.of("auc.auction");
    /** Модуль {@code money}: счета, платежи, выплаты — для уведомлений. */
    public static final Key<MoneyWorld> MONEY = Key.of("auc.money");
}
