package io.hammerhall.data.auc.bid;

/**
 * Доли случайного поведения, не зависящие от стратегии участника: ошибки, повторы, гонки, решения аукциониста. Каждая
 * доля — вероятность на подходящее действие. Времена реакции стратегий и длительности событий зала — постоянные
 * движка ({@link BidEngine}), описаны там.
 *
 * @param typoShare           ручная ставка набрана ниже минимальной (отклоняется {@link RejectReason#BELOW_MINIMUM},
 *                            участник через несколько секунд ставит верно)
 * @param duplicateShare      принятая ручная ставка онлайн отправлена повторно (двойное нажатие, повтор после обрыва):
 *                            повтор отклоняется ({@link RejectReason#ALREADY_LEADING} или ниже минимальной) и скрыто
 *                            помечен ({@link LotOutcome#hiddenRepeat}) — в данных это причина «повтор»
 * @param raceShare           онлайн: на ту же сумму в ту же миллисекунду ставит ещё один участник — одна из двух
 *                            проигрывает гонку ({@link RejectReason#RACE_LOST}); на ручную ставку раньше последних 10 с
 * @param liveRaceShare       зал: двое подняли таблички одновременно — аукционист берёт одну, вторая ставка отклонена
 * @param liveLateOnlineShare зал: онлайн-ставка опоздала — аукционист уже взял ставку в зале
 *                            ({@link RejectReason#PRICE_CHANGED})
 * @param vendorOpenShare     зал: при резерве выше стартовой и без заочных ставок аукционист открывает лот ставкой
 *                            от имени продавца (если {@link AuctionRules#vendorBids()})
 * @param liveJumpShare       зал: ведущий просит прыжок на 2–5 шагов (на шаг аукциониста; вдвое чаще, когда среди
 *                            желающих есть воюющий) — по отраслевой практике 4 % ставок
 * @param liveHalfStepShare   зал: ведущий принимает полшага, если цена не ниже {@link AuctionRules#halfStepFrom()}
 *                            (на шаг аукциониста) — по отраслевой практике 3 % ставок выше 100 000 ₽
 * @param lateRaceShare       онлайн: то же, что {@code raceShare}, но для ручной ставки в последние 10 с до текущего
 *                            конца лота — гонки сгущаются к концу (≈ 70 % гонок — в последние 10 с)
 */
public record Behavior(double typoShare, double duplicateShare, double raceShare, double liveRaceShare,
                       double liveLateOnlineShare, double vendorOpenShare, double liveJumpShare, double liveHalfStepShare,
                       double lateRaceShare) {
    /** Доля гонок конца лота по умолчанию (подобрана по прогону набора {@code small}). */
    public static final double LATE_RACE_SHARE = 0.25;

    public Behavior {
        double[] all = {typoShare, duplicateShare, raceShare, liveRaceShare, liveLateOnlineShare, vendorOpenShare, liveJumpShare,
                liveHalfStepShare, lateRaceShare};
        for (double d : all) if (!(d >= 0 && d <= 1)) throw new IllegalArgumentException("доля вне [0, 1]: " + d);
    }

    /** Прежние восемь долей; гонки конца лота — {@link #LATE_RACE_SHARE}. */
    public Behavior(double typoShare, double duplicateShare, double raceShare, double liveRaceShare, double liveLateOnlineShare,
                    double vendorOpenShare, double liveJumpShare, double liveHalfStepShare) {
        this(typoShare, duplicateShare, raceShare, liveRaceShare, liveLateOnlineShare, vendorOpenShare, liveJumpShare,
                liveHalfStepShare, LATE_RACE_SHARE);
    }

    /**
     * Доли по умолчанию — те, что берёт набор (подобраны по прогону набора {@code small} под коридоры): опечатки 1 %,
     * повторы 0,5 %, гонки 0,12 % (в конце — 25 %), гонки в зале 4 %, опоздание онлайн в зале 15 %, открытие от имени
     * продавца 50 %, прыжки 4 %, полшага 6,5 %.
     */
    public static Behavior standard() {
        return new Behavior(0.01, 0.005, 0.0012, 0.04, 0.15, 0.5, 0.04, 0.065, LATE_RACE_SHARE);
    }

    /** Без случайных ошибок, повторов, гонок и решений аукциониста — для ручных примеров в тестах. */
    public static Behavior none() {
        return new Behavior(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public Behavior withLateRaceShare(double v) {
        return new Behavior(typoShare, duplicateShare, raceShare, liveRaceShare, liveLateOnlineShare, vendorOpenShare, liveJumpShare,
                liveHalfStepShare, v);
    }

    public Behavior withTypoShare(double v) {
        return new Behavior(v, duplicateShare, raceShare, liveRaceShare, liveLateOnlineShare, vendorOpenShare, liveJumpShare, liveHalfStepShare, lateRaceShare);
    }

    public Behavior withDuplicateShare(double v) {
        return new Behavior(typoShare, v, raceShare, liveRaceShare, liveLateOnlineShare, vendorOpenShare, liveJumpShare, liveHalfStepShare, lateRaceShare);
    }

    public Behavior withRaceShare(double v) {
        return new Behavior(typoShare, duplicateShare, v, liveRaceShare, liveLateOnlineShare, vendorOpenShare, liveJumpShare, liveHalfStepShare, lateRaceShare);
    }

    public Behavior withLiveRaceShare(double v) {
        return new Behavior(typoShare, duplicateShare, raceShare, v, liveLateOnlineShare, vendorOpenShare, liveJumpShare, liveHalfStepShare, lateRaceShare);
    }

    public Behavior withLiveLateOnlineShare(double v) {
        return new Behavior(typoShare, duplicateShare, raceShare, liveRaceShare, v, vendorOpenShare, liveJumpShare, liveHalfStepShare, lateRaceShare);
    }

    public Behavior withVendorOpenShare(double v) {
        return new Behavior(typoShare, duplicateShare, raceShare, liveRaceShare, liveLateOnlineShare, v, liveJumpShare, liveHalfStepShare, lateRaceShare);
    }

    public Behavior withLiveJumpShare(double v) {
        return new Behavior(typoShare, duplicateShare, raceShare, liveRaceShare, liveLateOnlineShare, vendorOpenShare, v, liveHalfStepShare, lateRaceShare);
    }

    public Behavior withLiveHalfStepShare(double v) {
        return new Behavior(typoShare, duplicateShare, raceShare, liveRaceShare, liveLateOnlineShare, vendorOpenShare, liveJumpShare, v, lateRaceShare);
    }
}
