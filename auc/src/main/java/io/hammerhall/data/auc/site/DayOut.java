package io.hammerhall.data.auc.site;

import java.util.Arrays;

/**
 * Итог одних суток (UTC): визиты, их события и уведомления — примитивными массивами по полям, без объекта на строку.
 * Визиты лежат в порядке генерации, {@link #sOrder} — их порядок по началу (по нему выдаются {@code id}); события
 * визита — отрезок {@code [sEvFrom, sEvFrom + sEvN)}. Пустое поле — 0 (у {@code results} — −1, у запроса — {@code null}).
 */
final class DayOut {
    final long day;

    /** Учёт по видам визитов: вокруг действий, «посмотреть» клиента, анонимные — визитов и событий. */
    final long[] mix = new long[6];

    // ---------------------------------------------------------------- визиты
    int ns;
    long[] sStart = new long[256], sEnd = new long[256], sDevKey = new long[256];
    int[] sClient = new int[256], sCity = new int[256], sEvFrom = new int[256], sEvN = new int[256];
    byte[] sDevice = new byte[256], sSource = new byte[256];
    int[] sOrder;

    // ---------------------------------------------------------------- события
    int ne;
    long[] eAt = new long[1024], eBid = new long[1024];
    byte[] eKind = new byte[1024];
    int[] eSale = new int[1024], eLot = new int[1024], eResults = new int[1024], eOrder = new int[1024];
    short[] ePage = new short[1024];
    String[] eQuery = new String[1024];

    // ---------------------------------------------------------------- уведомления
    int nn;
    long[] nCreated = new long[256], nSent = new long[256], nDelivered = new long[256], nKeyA = new long[256],
            nKeyB = new long[256];
    int[] nClient = new int[256], nSale = new int[256], nLot = new int[256], nInvoice = new int[256];
    byte[] nKind = new byte[256], nChannel = new byte[256], nStatus = new byte[256];
    short[] nAttempts = new short[256];
    boolean[] nDup = new boolean[256];
    int[] nOrder;

    DayOut(long day) {
        this.day = day;
    }

    int session(long start, int client, long devKey, byte device, byte source, int city) {
        if (ns == sStart.length) {
            int n = ns * 2;
            sStart = Arrays.copyOf(sStart, n);
            sEnd = Arrays.copyOf(sEnd, n);
            sDevKey = Arrays.copyOf(sDevKey, n);
            sClient = Arrays.copyOf(sClient, n);
            sCity = Arrays.copyOf(sCity, n);
            sEvFrom = Arrays.copyOf(sEvFrom, n);
            sEvN = Arrays.copyOf(sEvN, n);
            sDevice = Arrays.copyOf(sDevice, n);
            sSource = Arrays.copyOf(sSource, n);
        }
        int i = ns++;
        sStart[i] = start;
        sEnd[i] = start;
        sClient[i] = client;
        sDevKey[i] = devKey;
        sDevice[i] = device;
        sSource[i] = source;
        sCity[i] = city;
        sEvFrom[i] = ne;
        sEvN[i] = 0;
        return i;
    }

    /** Событие текущего (последнего начатого) визита. */
    void event(long at, byte kind, int sale, int lot) {
        if (ne == eAt.length) {
            int n = ne * 2;
            eAt = Arrays.copyOf(eAt, n);
            eBid = Arrays.copyOf(eBid, n);
            eKind = Arrays.copyOf(eKind, n);
            eSale = Arrays.copyOf(eSale, n);
            eLot = Arrays.copyOf(eLot, n);
            eResults = Arrays.copyOf(eResults, n);
            eOrder = Arrays.copyOf(eOrder, n);
            ePage = Arrays.copyOf(ePage, n);
            eQuery = Arrays.copyOf(eQuery, n);
        }
        int i = ne++;
        eAt[i] = at;
        eKind[i] = kind;
        eSale[i] = sale;
        eLot[i] = lot;
        eBid[i] = 0;
        eOrder[i] = 0;
        eResults[i] = -1;
        ePage[i] = 0;
        eQuery[i] = null;
        sEvN[ns - 1]++;
        if (kind == Rules.E_VIEW) views++;
    }

    /** Просмотров лотов, сгенерированных за сутки к этому моменту (для добора анонимными визитами). */
    long views;

    /** Момент последнего события текущего визита (или его начало). */
    long lastAt() {
        int s = ns - 1;
        return sEvN[s] == 0 ? sStart[s] : eAt[ne - 1];
    }

    /** Отменить пустой визит (последний). */
    void dropEmpty() {
        if (ns > 0 && sEvN[ns - 1] == 0) ns--;
    }

    int notice(long created, byte kind, int client, byte channel, int sale, int lot, int invoice, long keyA, long keyB) {
        if (nn == nCreated.length) {
            int n = nn * 2;
            nCreated = Arrays.copyOf(nCreated, n);
            nSent = Arrays.copyOf(nSent, n);
            nDelivered = Arrays.copyOf(nDelivered, n);
            nKeyA = Arrays.copyOf(nKeyA, n);
            nKeyB = Arrays.copyOf(nKeyB, n);
            nClient = Arrays.copyOf(nClient, n);
            nSale = Arrays.copyOf(nSale, n);
            nLot = Arrays.copyOf(nLot, n);
            nInvoice = Arrays.copyOf(nInvoice, n);
            nKind = Arrays.copyOf(nKind, n);
            nChannel = Arrays.copyOf(nChannel, n);
            nStatus = Arrays.copyOf(nStatus, n);
            nAttempts = Arrays.copyOf(nAttempts, n);
            nDup = Arrays.copyOf(nDup, n);
        }
        int i = nn++;
        nCreated[i] = created;
        nKind[i] = kind;
        nClient[i] = client;
        nChannel[i] = channel;
        nSale[i] = sale;
        nLot[i] = lot;
        nInvoice[i] = invoice;
        nKeyA[i] = keyA;
        nKeyB[i] = keyB;
        nSent[i] = 0;
        nDelivered[i] = 0;
        nAttempts[i] = 0;
        nStatus[i] = 0;
        nDup[i] = false;
        return i;
    }

    /** Порядок визитов по началу и уведомлений по постановке в очередь (равные — по порядку генерации). */
    void sort() {
        long ds = day * 86_400L;
        long[] k = new long[ns];
        sOrder = new int[ns];
        for (int i = 0; i < ns; i++) {
            k[i] = sStart[i] - ds;
            sOrder[i] = i;
        }
        Radix.sort(k, sOrder, ns, 18);
        k = new long[nn];
        nOrder = new int[nn];
        for (int i = 0; i < nn; i++) {
            k[i] = nCreated[i] - ds;
            nOrder[i] = i;
        }
        Radix.sort(k, nOrder, nn, 18);
    }
}
