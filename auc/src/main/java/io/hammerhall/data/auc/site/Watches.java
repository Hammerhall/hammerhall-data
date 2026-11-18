package io.hammerhall.data.auc.site;

import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.util.Arrays;

/**
 * Наблюдение за лотами — до визитов, потому что события {@code watch_add} и {@code watch_remove} визиты берут отсюда.
 * Кто наблюдает: посетители — клиенты отдела лота (число — от интереса лота), и участники лота (онлайн — чаще, зал,
 * телефон и заочные — реже) — в сутки своей первой ставки, до неё; всего наблюдающих — около числа участников (
 * «воронка»). Часть наблюдающих убирает лот; убравших после среза в выпуске ещё нет ({@code removed_at} пусто).
 * Случайность — от (лот, клиент) и (лот, номер), ключи естественные: продление не меняет прежних строк.
 */
final class Watches {
    /** Доля участников, наблюдающих лот: онлайн и прочие каналы. */
    static final double PART_ONLINE = 0.72, PART_OTHER = 0.35;
    /** Наблюдающих без ставок — Пуассон с этим средним на (1 + участников). */
    static final double LURKERS = 0.2;
    /** Доля убравших: участник и наблюдающий без ставок. */
    static final double REMOVE_PART = 0.04, REMOVE_OTHER = 0.2;

    int n;
    int[] client, lot;
    long[] added, removed;
    byte[] via;
    /** Наблюдения лота: {@code [lotFrom[l], lotFrom[l + 1])} — строки идут по лоту. */
    int[] lotFrom;
    /** События наблюдения клиентов с согласием по времени: момент и {@code (строка << 1) | убрал}. */
    long[] evAt;
    int[] evRef;
    int nEv;
    /** Строки по (клиент, лот) — порядок таблицы; ключи для поиска. */
    int[] order;
    long[] sortedKey;

    static Watches build(SiteInput in, RandomStreams.Stream st, Rules rules) {
        Watches w = new Watches();
        int cap = Math.max(64, in.nPart + in.nLots);
        w.client = new int[cap];
        w.lot = new int[cap];
        w.added = new long[cap];
        w.removed = new long[cap];
        w.via = new byte[cap];
        w.lotFrom = new int[in.nLots + 2];
        int[] seen = new int[64];
        for (int l = 1; l <= in.nLots; l++) {
            w.lotFrom[l] = w.n;
            long pub = in.lPub[l];
            // конец — по плану, не по снятию: снятие — факт, при другом срезе его могло не быть, а время добавления
            // от него зависеть не должно; добавленное после снятия просто не пишется
            long end = in.lPlan[l];
            if (pub == 0 || end <= pub) continue;
            int m = 0;
            int pf = in.partFrom[l], pt = in.partFrom[l + 1];
            // наблюдающие без ставок — первыми: их число — от интереса лота (не от среза), клиенты — из пула отдела по
            // дате регистрации, время — по плану лота; поэтому они одни и те же при любом срезе
            Rng r = st.at(in.lKey[l], -1);
            int k = r.poisson(LURKERS * (1 + in.lPop[l]));
            int dept = in.sDept[in.lSale[l]];
            int[] pool = dept >= 0 && dept < in.deptPool.length ? in.deptPool[dept] : new int[0];
            long[] reg = dept >= 0 && dept < in.deptPool.length ? in.deptPoolReg[dept] : new long[0];
            long last = end - 3600;
            int bound = Radix.lowerBound(reg, 0, reg.length, last - 60);
            for (int j = 0; j < k && bound > 0; j++) {
                Rng rj = st.at(in.lKey[l], j);
                int c = pool[rj.nextInt(bound)];
                boolean dup = false;
                for (int x = 0; x < m && !dup; x++) dup = seen[x] == c;
                if (dup) continue;
                long lo = Math.max(pub, in.cRegAt[c] + 60);
                if (last <= lo) continue;
                long at = lo + (long) ((last - lo) * rj.nextDouble());
                long rem = 0;
                if (rj.chance(REMOVE_OTHER)) rem = at + 60 + (long) (rj.nextDouble() * (end + 3 * 86_400L - at));
                if (m == seen.length) seen = Arrays.copyOf(seen, m * 2);
                seen[m++] = c;
                w.add(in, rules, c, l, at, rem);
            }
            // участники: добавляют лот в наблюдение в сутки своей первой ставки, до неё. Сутки — UTC, а срез — всегда
            // полночь UTC: наблюдение и первая ставка — по одну сторону любого среза, и строка не зависит от того,
            // известны ли мир ставки после среза (участник, впервые ставящий после среза, наблюдает тоже после него)
            for (int p = pf; p < pt; p++) {
                int c = in.pClient[p];
                boolean dup = false;
                for (int x = 0; x < m && !dup; x++) dup = seen[x] == c;
                if (dup) continue;
                Rng rp = st.at(in.lKey[l], in.cKey[c]);
                if (!rp.chance(in.pOnline[p] ? PART_ONLINE : PART_OTHER)) continue;
                long fb = in.pFirstAt[p];
                long lo = Math.max(Math.max(pub, in.cRegAt[c] + 60), Math.floorDiv(fb, 86_400L) * 86_400L);
                long hi = Math.min(fb - 20, end);
                if (hi <= lo) continue;
                long at = lo + (long) ((hi - lo) * StrictMath.pow(rp.nextDouble(), 0.8));
                long rem = 0;
                if (rp.chance(REMOVE_PART)) rem = at + 60 + (long) (rp.nextDouble() * (end + 3 * 86_400L - at));
                if (m == seen.length) seen = Arrays.copyOf(seen, m * 2);
                seen[m++] = c;
                w.add(in, rules, c, l, at, rem);
            }
        }
        w.lotFrom[in.nLots + 1] = w.n;
        w.lotFrom[0] = 0;
        w.index(in);
        return w;
    }

    private void add(SiteInput in, Rules rules, int c, int l, long at, long rem) {
        if (at >= in.cutoff) return;
        if (in.lWithdrawn[l] != 0 && at >= in.lWithdrawn[l]) return;
        if (n == client.length) {
            int k = n * 2;
            client = Arrays.copyOf(client, k);
            lot = Arrays.copyOf(lot, k);
            added = Arrays.copyOf(added, k);
            removed = Arrays.copyOf(removed, k);
            via = Arrays.copyOf(via, k);
        }
        client[n] = c;
        lot[n] = l;
        added[n] = at;
        removed[n] = rem != 0 && rem < in.cutoff ? rem : 0;
        via[n] = rules.via(in, c, SiteInput.day(at));
        n++;
    }

    private void index(SiteInput in) {
        // события клиентов с согласием — по времени
        int k = 0;
        for (int i = 0; i < n; i++) {
            if ((in.cFlags[client[i]] & SiteInput.C_ANALYTICS) == 0) continue;
            k += removed[i] != 0 ? 2 : 1;
        }
        evAt = new long[k];
        evRef = new int[k];
        nEv = 0;
        for (int i = 0; i < n; i++) {
            if ((in.cFlags[client[i]] & SiteInput.C_ANALYTICS) == 0) continue;
            evAt[nEv] = added[i];
            evRef[nEv++] = i << 1;
            if (removed[i] != 0) {
                evAt[nEv] = removed[i];
                evRef[nEv++] = (i << 1) | 1;
            }
        }
        Radix.sort(evAt, evRef, nEv, 40);
        // порядок таблицы
        long[] key = new long[n];
        for (int i = 0; i < n; i++) key[i] = ((long) client[i] << 32) | lot[i];
        order = Radix.order(key, n, 64);
        sortedKey = new long[n];
        for (int i = 0; i < n; i++) sortedKey[i] = key[order[i]];
    }

    /** Строка наблюдения (клиент, лот); −1 — нет. */
    int find(int c, int l) {
        long key = ((long) c << 32) | l;
        int i = Radix.lowerBound(sortedKey, 0, n, key);
        return i < n && sortedKey[i] == key ? order[i] : -1;
    }
}
