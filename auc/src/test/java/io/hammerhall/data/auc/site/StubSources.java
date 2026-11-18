package io.hammerhall.data.auc.site;

import java.util.Arrays;

/**
 * Источники уведомлений упрощённого мира ({@link StubWorld}) — то, что в наборе дают {@code AuctionWorld.notices()} и
 * {@code MoneyWorld.events()}: решения по регистрациям живых торгов, «перебита», выигрыш, итоги торгов,
 * счёт, напоминания, оплата, отмена, возврат депозита, отчёт и выплата комитенту. Порядок — по моменту, равные — по
 * порядку сбора (виды по порядку, внутри — по ключу).
 */
final class StubSources {
    /** Напоминания об оплате: сдвиг от срока оплаты. */
    static final long[] REMIND = {-2 * 86_400L, 86_400L, 8 * 86_400L, 18 * 86_400L};

    private final SiteInput in;
    private int n;
    private long[] at = new long[1024], a = new long[1024], b = new long[1024];
    private byte[] kind = new byte[1024];
    private int[] client = new int[1024], sale = new int[1024], lot = new int[1024], inv = new int[1024];

    StubSources(SiteInput in) {
        this.in = in;
    }

    private void add(long t, byte k, int c, int s, int l, int i, long ka, long kb) {
        if (t >= in.cutoff) return;
        if (n == at.length) {
            int m = n * 2;
            at = Arrays.copyOf(at, m);
            a = Arrays.copyOf(a, m);
            b = Arrays.copyOf(b, m);
            kind = Arrays.copyOf(kind, m);
            client = Arrays.copyOf(client, m);
            sale = Arrays.copyOf(sale, m);
            lot = Arrays.copyOf(lot, m);
            inv = Arrays.copyOf(inv, m);
        }
        at[n] = t;
        kind[n] = k;
        client[n] = c;
        sale[n] = s;
        lot[n] = l;
        inv[n] = i;
        a[n] = ka;
        b[n] = kb;
        n++;
    }

    void build() {
        // регистрация: решение сотрудника или отказ
        for (int i = 0; i < in.nRegs; i++) {
            if (in.rDecided[i] == 0 || (in.rFlags[i] & (SiteInput.R_MANUAL | SiteInput.R_REJECTED)) == 0) continue;
            add(in.rDecided[i], Rules.N_REG, in.rClient[i], in.rSale[i], 0, 0, in.rId[i], -1);
        }
        outbid();
        // выигрыш
        for (int l = 1; l <= in.nLots; l++) {
            if (in.lBuyer[l] == 0 || in.lClosed[l] == 0) continue;
            add(in.lClosed[l], Rules.N_WON, in.lBuyer[l], in.lSale[l], l, 0, l, -1);
        }
        // итоги торгов — участникам со ставками без выигрыша
        for (int i = 0; i < in.nRegs; i++) {
            int s = in.rSale[i];
            if (in.sEnd[s] == 0 || (in.rFlags[i] & SiteInput.R_BIDS) == 0 || (in.rFlags[i] & SiteInput.R_WON) != 0) continue;
            add(in.sEnd[s] + 1800, Rules.N_SUMMARY, in.rClient[i], s, 0, 0, s, in.rClient[i]);
        }
        // деньги
        for (int i = 0; i < in.nInv; i++) {
            add(in.iIssued[i], Rules.N_INVOICE, in.iClient[i], in.iSale[i], 0, in.iId[i], in.iId[i], -1);
            for (int r = 0; r < REMIND.length; r++) {
                long t = in.iDue[i] + REMIND[r];
                if ((in.iPaid[i] != 0 && in.iPaid[i] <= t) || (in.iCancelled[i] != 0 && in.iCancelled[i] <= t)) continue;
                add(t, Rules.N_REMINDER, in.iClient[i], in.iSale[i], 0, in.iId[i], in.iId[i], r + 1);
            }
            if (in.iCancelled[i] != 0) add(in.iCancelled[i], Rules.N_CANCELLED, in.iClient[i], in.iSale[i], 0, in.iId[i], in.iId[i], -1);
        }
        for (int j = 0; j < in.nPay; j++) {
            int ii = Arrays.binarySearch(in.iId, 0, in.nInv, in.payInvoice[j]);
            add(in.payAt[j], Rules.N_PAID, in.payClient[j], ii >= 0 ? in.iSale[ii] : 0, 0, in.payInvoice[j], in.payId[j], -1);
        }
        for (int j = 0; j < in.nRefund; j++) add(in.refAt[j], Rules.N_REFUND, in.refClient[j], in.refSale[j], 0, 0, in.refId[j], -1);
        for (int j = 0; j < in.nSet; j++) add(in.stCreated[j], Rules.N_SETTLEMENT, in.stClient[j], in.stSale[j], 0, 0, in.stId[j], -1);
        for (int j = 0; j < in.nPayout; j++) add(in.poAt[j], Rules.N_PAYOUT, in.poClient[j], in.poSale[j], 0, 0, in.poId[j], -1);

        int[] idx = new int[n];
        long[] key = Arrays.copyOf(at, n);
        for (int i = 0; i < n; i++) idx[i] = i;
        Radix.sort(key, idx, n, 40);
        in.nSrc = n;
        in.srcAt = new int[n];
        in.srcKind = new byte[n];
        in.srcClient = new int[n];
        in.srcSale = new int[n];
        in.srcLot = new int[n];
        in.srcInvoice = new int[n];
        in.srcKeyA = new int[n];
        in.srcKeyB = new int[n];
        for (int j = 0; j < n; j++) {
            int i = idx[j];
            in.srcAt[j] = Math.toIntExact(key[j]);
            in.srcKind[j] = kind[i];
            in.srcClient[j] = client[i];
            in.srcSale[j] = sale[i];
            in.srcLot[j] = lot[i];
            in.srcInvoice[j] = inv[i];
            in.srcKeyA[j] = Math.toIntExact(a[i]);
            in.srcKeyB[j] = Math.toIntExact(b[i]);
        }
    }

    /**
     * «Перебита» — по принятым ставкам онлайн-лота в порядке поступления: ставка другого клиента сменила лидера; если
     * тем же мгновением (та же мс) ответила автоставка прежнего лидера — лидер не сменился, уведомления нет (зато
     * перебит тот, кто ставил, — ответом автоставки). Так же решает оркестр торгов для мира.
     */
    private void outbid() {
        int[] leader = new int[in.nLots + 1];
        int[] pendBid = new int[in.nLots + 1];
        int[] pendClient = new int[in.nLots + 1];
        Arrays.fill(pendBid, -1);
        int cap = Math.max(16, in.nBids / 2);
        int[] ob = new int[cap], oc = new int[cap];
        int m = 0;
        for (int i = 0; i < in.nBids; i++) {
            if ((in.bFlags[i] & SiteInput.B_ACCEPTED) == 0) continue;
            int l = in.bLot[i];
            if (in.sFormat[in.lSale[l]] != SiteInput.F_TIMED) continue;
            int c = in.bClient[i];
            int p = pendBid[l];
            if (p >= 0) {
                boolean answered = c == pendClient[l] && in.bAt[i] == in.bAt[p] && in.bMs[i] == in.bMs[p];
                if (!answered) {
                    if (m == ob.length) {
                        ob = Arrays.copyOf(ob, m * 2);
                        oc = Arrays.copyOf(oc, m * 2);
                    }
                    ob[m] = p;
                    oc[m++] = pendClient[l];
                }
                pendBid[l] = -1;
            }
            int prev = leader[l];
            if (prev != 0 && prev != c && c != 0) {
                pendBid[l] = i;
                pendClient[l] = prev;
            }
            leader[l] = c;
        }
        for (int l = 1; l <= in.nLots; l++) {
            if (pendBid[l] < 0) continue;
            if (m == ob.length) {
                ob = Arrays.copyOf(ob, m * 2);
                oc = Arrays.copyOf(oc, m * 2);
            }
            ob[m] = pendBid[l];
            oc[m++] = pendClient[l];
        }
        long[] key = new long[m];
        int[] idx = new int[m];
        for (int j = 0; j < m; j++) {
            key[j] = ob[j];
            idx[j] = j;
        }
        Radix.sort(key, idx, m, 32);
        for (int j = 0; j < m; j++) {
            int bi = ob[idx[j]], l = in.bLot[bi];
            add(in.bAt[bi], Rules.N_OUTBID, oc[idx[j]], in.lSale[l], l, 0, l, in.bidId(bi));
        }
    }
}
