package io.hammerhall.data.auc.auction.engine;

import io.hammerhall.data.auc.bid.Channel;
import io.hammerhall.data.auc.bid.Strategy;

/**
 * Участник лота для обёртки движка: намерение (канал, стратегия, максимум) и то, что нужно для строк ставок и
 * поручений. Деньги — копейки, время — миллисекунды Unix (UTC). Ключи — естественные (не {@code id} выпуска):
 * {@code id} регистрации ставится при записи по моменту её появления.
 *
 * @param registrationKey ключ регистрации участника в торгах (у подготовки торгов) — {@code bids.registration_id} при записи
 * @param clientId        клиент ({@code clients.id} — постоянный, от модуля клиентов)
 * @param channel         канал ставок в движке: онлайн-торги — {@link Channel#ONLINE}; живые — зал, телефон, онлайн,
 *                        заочная
 * @param strategy        стратегия движка; у заочной и страховочной телефонной — {@link Strategy#BOOK}
 * @param maxKop          максимум для движка (своя цена с долей лимита; у телефона без ответа — страховочная), копейки
 * @param orderMaxKop     заявка до торгов (заочная, телефон): максимум редакции 1 поручения — у заочной он же
 *                        {@code maxKop}, у телефона — страховочная (0 — без неё, {@code max_amount} пусто)
 * @param placedAtMs      заочная и телефонная заявка — когда оставлена (момент редакции 1 поручения); онлайн — с какого
 *                        момента участник действует (0 — с открытия)
 * @param via             откуда подана заявка или поручение ({@link Codes#VIA})
 * @param clerkId         телефон — сотрудник на линии ({@code staff.id}); иначе 0
 * @param phone           телефон: {@link Codes#PO_CONNECTED} — дозвонились (ставит сам), {@link Codes#PO_NO_ANSWER} —
 *                        нет (страховочная «из книги», если есть); иначе {@link Codes#PO_NONE}
 * @param inEngine        идёт ли участник в движок; {@code false} — только отказы до движка (нет регистрации, свой лот…)
 *                        или телефон без ответа и без страховочной
 */
public record Participant(long registrationKey, int clientId, Channel channel, Strategy strategy, long maxKop,
                          long orderMaxKop, long placedAtMs, byte via, int clerkId, byte phone, boolean inEngine) {
    public Participant {
        if (registrationKey <= 0) throw new IllegalArgumentException("ключ регистрации " + registrationKey);
        if (channel == null || channel == Channel.AUCTIONEER) throw new IllegalArgumentException("канал " + channel);
        if (inEngine && maxKop <= 0) throw new IllegalArgumentException("максимум " + maxKop);
        if (inEngine && channel == Channel.ABSENTEE && strategy != Strategy.BOOK) {
            throw new IllegalArgumentException("заочная — только из книги");
        }
    }

    /** Онлайн-участник (стратегия, максимум) через сайт или приложение. */
    public static Participant online(long registrationKey, int clientId, Strategy strategy, long maxKop, byte via) {
        return new Participant(registrationKey, clientId, Channel.ONLINE, strategy, maxKop, 0, 0, via, 0, Codes.PO_NONE,
                true);
    }

    /** Заочная ставка живых торгов: максимум и момент, когда оставлена. */
    public static Participant absentee(long registrationKey, int clientId, long maxKop, long placedAtMs, byte via) {
        return new Participant(registrationKey, clientId, Channel.ABSENTEE, Strategy.BOOK, maxKop, maxKop, placedAtMs, via,
                0, Codes.PO_NONE, true);
    }

    /** Участник в зале или онлайн-участник живых торгов. */
    public static Participant live(long registrationKey, int clientId, Channel channel, Strategy strategy, long maxKop,
                                   byte via) {
        return new Participant(registrationKey, clientId, channel, strategy, maxKop, 0, 0, via, 0, Codes.PO_NONE, true);
    }

    /**
     * Телефонная заявка: дозвонились — участник ставит сам (стратегия, максимум); нет — страховочная {@code safetyKop}
     * «из книги» (0 — страховочной нет: в движок не идёт). Линия забронирована в {@code bookedAtMs}.
     */
    public static Participant phone(long registrationKey, int clientId, Strategy strategy, long maxKop, long safetyKop,
                                    long bookedAtMs, int clerkId, boolean connected) {
        if (connected) {
            return new Participant(registrationKey, clientId, Channel.PHONE, strategy, maxKop, safetyKop, bookedAtMs,
                    Codes.VIA_PHONE, clerkId, Codes.PO_CONNECTED, true);
        }
        return new Participant(registrationKey, clientId, Channel.PHONE, Strategy.BOOK, safetyKop, safetyKop, bookedAtMs,
                Codes.VIA_PHONE, clerkId, Codes.PO_NO_ANSWER, safetyKop > 0);
    }

    /** Только попытки до движка (их причины — у {@link Attempt}). */
    public static Participant rejectedOnly(long registrationKey, int clientId, Channel channel, byte via) {
        return new Participant(registrationKey, clientId, channel, Strategy.SCRIPTED, 0, 0, 0, via, 0, Codes.PO_NONE, false);
    }

    /** Заявка до торгов (редакция 1 поручения до первой ставки): заочная или телефонная. */
    public boolean presetOrder() {
        return channel == Channel.ABSENTEE || channel == Channel.PHONE;
    }

    /** Вид поручения-заявки ({@link Codes#ORDER_KINDS}). */
    public byte presetKind() {
        return channel == Channel.PHONE ? Codes.OK_PHONE : Codes.OK_ABSENTEE;
    }
}
