package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.auction.engine.LotRows;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.SalePlan;

import java.util.Arrays;

/**
 * Торги в работе у оркестра: план (от подготовки торгов), строки торгов всех лотов ({@link LotRows}) и то, что выдаётся при записи
 * ({@code lots.id}, {@code bids.id}). Живёт, пока не записаны все его лоты и ставки до среза. Индекс лота — порядок
 * плана (= индекс в {@link LotRows}).
 */
final class SaleRun {
    final int serial;
    final Calendar.Sale sale;
    final SalePlan plan;
    final LotRows rows;
    final int lots;
    /** Модельный итог (без среза): {@code Codes.RES_*}. */
    final byte[] result;
    /** Продажа после торгов (модель): момент (с), цена, регистрация и клиент покупателя. */
    final long[] afterSaleAt, afterSalePrice, afterSaleReg;
    final int[] afterSaleClient;
    /**
     * Модель исхода (без среза): цена продажи (молоток или после торгов), покупатель (регистрация, клиент), отказ
     * покупателя (момент отмены), второй шанс (клиент, цена), полная оплата (вещь передана), с какого момента вещь
     * свободна для решения «повтор или возврат».
     */
    final long[] price, buyerReg, defaultAt, scPrice, paidAt, freeAt;
    final int[] buyerClient, scClient;
    final boolean[] buyerDefault;
    /** Ставка-победитель — через партнёрскую площадку (сбор PLT). */
    final boolean[] platform;
    /** Слот живого лота (мс) — начало; онлайн — открытие. */
    final long[] slotStart;
    /** {@code lots.id}; 0 — ещё не выдан или лот не в выпуске (опубликован не раньше среза). */
    final int[] lotId;
    /** Последний момент (мс) ставки лота раньше среза; -1 — ставок до среза нет. */
    final long[] lastBidMs;
    final boolean[] written;
    int lotsWritten, lotsInRelease;
    /** {@code bids.id}; 0 — не записана (ещё или после среза). */
    final long[] bidId;
    /** Ставки торгов по времени: (мс &lt;&lt; 21 | индекс); курсор — первая не записанная. */
    final long[] bidOrder;
    int bidCursor;
    /** Ставок до среза; записано из них. */
    int bidsInRelease, bidsWritten;
    /**
     * Смоделированные участники лотов (без среза) — для наблюдения на сайте ({@code ParticipantLog}): участники лота
     * {@code i} — индексы {@code [partFrom[i], partFrom[i + 1])}.
     */
    int[] partFrom;
    long[] pReg;
    int[] pClient;
    boolean[] pOnline;
    /** Лоты по (публикация, ключ) — курсор выдачи {@code lots.id}. */
    final int[] pubOrder;
    int pubCursor;

    SaleRun(int serial, Calendar.Sale sale, SalePlan plan, LotRows rows, long[] slotStart, boolean[] platform, long cutMs) {
        this.serial = serial;
        this.sale = sale;
        this.plan = plan;
        this.rows = rows;
        this.lots = plan.lots().size();
        if (rows.lots != lots) throw new IllegalStateException("торги " + sale.id() + ": лотов в плане " + lots + ", в строках " + rows.lots);
        this.result = new byte[lots];
        this.afterSaleAt = new long[lots];
        this.afterSalePrice = new long[lots];
        this.afterSaleReg = new long[lots];
        this.afterSaleClient = new int[lots];
        this.price = new long[lots];
        this.buyerReg = new long[lots];
        this.defaultAt = new long[lots];
        this.scPrice = new long[lots];
        this.paidAt = new long[lots];
        this.freeAt = new long[lots];
        this.buyerClient = new int[lots];
        this.scClient = new int[lots];
        this.buyerDefault = new boolean[lots];
        this.platform = platform;
        this.slotStart = slotStart;
        this.lotId = new int[lots];
        this.lastBidMs = new long[lots];
        this.written = new boolean[lots];
        Arrays.fill(lastBidMs, -1);
        int nb = rows.bids;
        this.bidId = new long[nb];
        if (nb >= 1 << 21) throw new IllegalStateException("торги " + sale.id() + ": ставок больше 2 млн");
        long[] ord = new long[nb];
        int k = 0;
        for (int b = 0; b < nb; b++) {
            long t = rows.bTime[b];
            if (t >= cutMs) continue;
            ord[k++] = t << 21 | b;
            int l = rows.bLot[b];
            if (t > lastBidMs[l]) lastBidMs[l] = t;
        }
        Arrays.sort(ord, 0, k);
        this.bidOrder = Arrays.copyOf(ord, k);
        this.bidsInRelease = k;
        // лоты по (публикация, ключ)
        long[] pk = new long[lots], pv = new long[lots];
        for (int i = 0; i < lots; i++) {
            pk[i] = plan.lots().get(i).publishedAt();
            pv[i] = (long) i;
        }
        // при равной публикации — порядок ключа = порядок плана (номер, буква)
        PairSort.sort(pk, pv, lots);
        this.pubOrder = new int[lots];
        for (int i = 0; i < lots; i++) pubOrder[i] = (int) pv[i];
    }

    SalePlan.Lot lot(int i) {
        return plan.lots().get(i);
    }

    boolean done() {
        return lotsWritten == lotsInRelease && bidsWritten == bidsInRelease && pubCursor == lots;
    }
}
