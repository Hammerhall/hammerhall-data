package io.hammerhall.data.auc.auction.engine;

import io.hammerhall.data.auc.bid.Action;
import io.hammerhall.data.auc.bid.Faults;

import java.util.List;

/**
 * Лот для обёртки движка — из плана торгов ({@code SalePlan}) и участников ({@code Participants}) подготовки торгов. Деньги —
 * копейки, время — миллисекунды Unix (UTC).
 *
 * @param lotKey        естественный ключ лота (торги + номер) — случайность движка и ключи запросов от него, не от
 *                      {@code lots.id}
 * @param live          живые торги (иначе онлайн по времени)
 * @param startKop      стартовая цена (точка сетки)
 * @param reserveKop    резерв (точка сетки); 0 — без резерва
 * @param openMs        онлайн — открытие ставок; живые — начало слота лота
 * @param closeMs       онлайн — плановый конец по волне ({@code closes_at_planned}); живые — конец слота (исключая)
 * @param auctioneerId  живые — аукционист сессии ({@code staff.id}): ставки в пользу продавца, заочные, полшага, прыжки
 *                      и события зала; онлайн — 0
 * @param participants  участники (индекс — номер участника в {@link Attempt} и {@link Action}); ключи регистраций — разные
 * @param attempts      отказы до движка
 * @param script        сценарий движка (только онлайн): подъёмы максимума ({@code Action.raiseMax}), повторы, тесты
 * @param withdrawnAtMs снят до торгов (движок не запускается); 0 — нет
 * @param faults        заложенные сбои движка; {@code null} — по выбору обёртки ({@link FaultPlan})
 */
public record LotInput(long lotKey, boolean live, long startKop, long reserveKop, long openMs, long closeMs,
                       int auctioneerId, List<Participant> participants, List<Attempt> attempts, List<Action> script,
                       long withdrawnAtMs, Faults faults) {
    public LotInput {
        participants = List.copyOf(participants);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        script = script == null ? List.of() : List.copyOf(script);
        if (closeMs <= openMs) throw new IllegalArgumentException("лот " + lotKey + ": окно " + openMs + ".." + closeMs);
        if (live && !script.isEmpty()) throw new IllegalArgumentException("лот " + lotKey + ": сценарий — только онлайн");
        // одна регистрация — один участник лота: двойники в движке — два независимых участника одной регистрации
        // (ставят друг против друга, гонка сам с собой), а ключи запросов (лот, регистрация, номер попытки участника)
        // у них совпадают — повтор ключа без повтора запроса (T14)
        for (int i = 0; i < participants.size(); i++) {
            long reg = participants.get(i).registrationKey();
            for (int j = 0; j < i; j++) {
                if (participants.get(j).registrationKey() == reg) {
                    throw new IllegalArgumentException("лот " + lotKey + ": регистрация " + reg + " — участники " + j + " и " + i);
                }
            }
        }
        for (Attempt a : attempts) {
            if (a.participant() < 0 || a.participant() >= participants.size()) {
                throw new IllegalArgumentException("лот " + lotKey + ": попытка участника " + a.participant());
            }
        }
    }

    public static LotInput online(long lotKey, long startKop, long reserveKop, long openMs, long closeMs,
                                  List<Participant> participants) {
        return new LotInput(lotKey, false, startKop, reserveKop, openMs, closeMs, 0, participants, List.of(), List.of(), 0,
                null);
    }

    public static LotInput live(long lotKey, long startKop, long reserveKop, long slotStartMs, long slotEndMs,
                                int auctioneerId, List<Participant> participants) {
        return new LotInput(lotKey, true, startKop, reserveKop, slotStartMs, slotEndMs, auctioneerId, participants,
                List.of(), List.of(), 0, null);
    }

    public LotInput withAttempts(List<Attempt> a) {
        return new LotInput(lotKey, live, startKop, reserveKop, openMs, closeMs, auctioneerId, participants, a, script,
                withdrawnAtMs, faults);
    }

    public LotInput withScript(List<Action> s) {
        return new LotInput(lotKey, live, startKop, reserveKop, openMs, closeMs, auctioneerId, participants, attempts, s,
                withdrawnAtMs, faults);
    }

    public LotInput withFaults(Faults f) {
        return new LotInput(lotKey, live, startKop, reserveKop, openMs, closeMs, auctioneerId, participants, attempts,
                script, withdrawnAtMs, f);
    }

    public LotInput withdrawnAt(long ms) {
        return new LotInput(lotKey, live, startKop, reserveKop, openMs, closeMs, auctioneerId, participants, attempts,
                script, ms, faults);
    }
}
