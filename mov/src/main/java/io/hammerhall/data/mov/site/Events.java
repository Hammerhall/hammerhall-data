package io.hammerhall.data.mov.site;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Записи событий прогона до выдачи {@code id}: каждая знает момент, пользователя и свой номер у него (по порядку
 * появления) — по ним строки упорядочиваются ({@link #BY_TIME}), а {@code id} выдаётся по порядку. Ссылки между
 * записями — объектами (оплата → подписка или покупка, просмотр → устройство), номера подставляются при записи.
 */
final class Events {
    private Events() {
    }

    /** Общее у событий: момент, пользователь (индекс), номер у пользователя; {@code id} — после упорядочения. */
    abstract static class Ev {
        long at;
        int user;
        int seq;
        int id;
    }

    static final Comparator<Ev> BY_TIME = (a, b) -> {
        if (a.at != b.at) return Long.compare(a.at, b.at);
        if (a.user != b.user) return Integer.compare(a.user, b.user);
        return Integer.compare(a.seq, b.seq);
    };

    static final class Device extends Ev {
        byte kind, os;
        long retire;
        long last;
    }

    static final class Sub extends Ev {
        int plan;
        long trialEnd = -1, ends;
        byte status;
        boolean autoRenew;
        long cancelledAt = -1;
        byte reason = -1;
        /** Доступ до (для просмотров): у идущей на конце моделирования — без конца. */
        long accessEnd;
    }

    static final class Purchase extends Ev {
        int movie;
        boolean buy;
        long price;
        long expires = -1;
        int window;
    }

    static final class Pay extends Ev {
        Sub sub;
        Purchase purchase;
        long amount;
        byte method, status;
        long refundedAt = -1;
        boolean duplicate;
    }

    static final byte V_NORMAL = 0, V_LONG = 1, V_GRACE = 2;

    static final class View extends Ev {
        Device device;
        int movie;
        int watched;
        boolean completed;
        byte quality, source, mark;
    }

    static final class Review extends Ev {
        int movie;
        int rating;
        long edited = -1;
        boolean hidden, byDeleted, ratingChanged;
        double weight;
    }

    static final class Collection extends Ev {
        int genre;
        boolean open;
        final List<long[]> items = new ArrayList<>();
    }

    /** Намерение подписаться на кого-то: момент, кто, номер намерения. */
    static final class Intent extends Ev {
    }
}
