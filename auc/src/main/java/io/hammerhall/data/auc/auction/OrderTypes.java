package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.world.OrderEvents;

/** Вид события «редакция отклонена» — {@code OrderEvents.Type.REJECTED}. */
final class OrderTypes {
    private OrderTypes() {
    }

    static OrderEvents.Type rejected() {
        return OrderEvents.Type.REJECTED;
    }
}
