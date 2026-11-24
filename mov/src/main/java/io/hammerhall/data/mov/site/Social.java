package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.mov.site.Events.Intent;
import io.hammerhall.data.mov.site.Events.Review;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Связи между людьми: подписки друг на друга и голоса за рецензии. Идут по времени по всем пользователям сразу (§5,
 * узор 7): подписка — предпочтительное присоединение (на кого уже подписаны), рецензенты по весу их рецензий (у
 * сильного рецензента — больше подписчиков) и соседи по городу; голоса за рецензию — их число тяжёлым хвостом (Парето:
 * 5 % рецензий — около половины голосов), голосуют зарегистрированные к моменту голоса. Каждое решение видит только
 * более раннее — продление не меняет прежнего.
 */
final class Social {
    private Social() {
    }

    /** Подписка: кто, на кого, когда, отписка ({@code -1} — нет). */
    record Follow(int follower, int followee, long at, long unfollowAt) {
    }

    /** Голос: рецензия (индекс в упорядоченном списке), голосующий, полезна ли, когда. */
    record Vote(int review, int user, boolean useful, long at) {
    }

    static final double P_PREFERENTIAL = 0.45, P_REVIEWER = 0.30, P_UNFOLLOW = 0.15;
    /** Медиана голосов за рецензию (до веса автора и известности фильма). */
    static final double VOTES = 3.5;

    static List<Follow> follows(List<Intent> intents, Audience aud, Cities cities, List<Review> reviews, RandomStreams st) {
        RandomStreams.Stream fs = st.stream("site.follows");
        intents.sort(Events.BY_TIME);
        // рецензии по времени: накопленный вес автора — для выбора рецензента к моменту
        int nr = reviews.size();
        long[] rAt = new long[nr];
        double[] rCum = new double[nr];
        double s = 0;
        for (int i = 0; i < nr; i++) {
            Review v = reviews.get(i);
            rAt[i] = v.at;
            rCum[i] = s += v.weight;
        }
        // пользователи по городам в порядке регистрации
        int nc = cities.size();
        int[] cnt = new int[nc + 1];
        for (int u = 0; u < aud.n; u++) cnt[aud.city[u] + 1]++;
        for (int c = 0; c < nc; c++) cnt[c + 1] += cnt[c];
        int[] byCity = new int[aud.n];
        int[] fill = Arrays.copyOf(cnt, nc + 1);
        for (int u = 0; u < aud.n; u++) byCity[fill[aud.city[u]]++] = u;
        Set<Long> pairs = new HashSet<>();
        List<Integer> followees = new ArrayList<>();
        List<Follow> out = new ArrayList<>();
        for (Intent x : intents) {
            Rng r = fs.at(aud.key[x.user], x.seq);
            long t = x.at;
            int chosen = -1;
            for (int attempt = 0; attempt < 8 && chosen < 0; attempt++) {
                double p = r.nextDouble();
                int cand = -1;
                if (p < P_PREFERENTIAL && !followees.isEmpty()) {
                    cand = followees.get(r.nextInt(followees.size()));
                } else if (p < P_PREFERENTIAL + P_REVIEWER) {
                    int k = upper(rAt, t);
                    if (k > 0) {
                        double y = r.nextDouble() * rCum[k - 1];
                        int lo = 0, hi = k - 1;
                        while (lo < hi) {
                            int mid = (lo + hi) >>> 1;
                            if (rCum[mid] > y) hi = mid;
                            else lo = mid + 1;
                        }
                        cand = reviews.get(lo).user;
                    }
                } else {
                    int c = aud.city[x.user], from = cnt[c], to = cnt[c + 1];
                    // соседи, зарегистрированные раньше: в порядке регистрации — префикс
                    int lo = from, hi = to;
                    while (lo < hi) {
                        int mid = (lo + hi) >>> 1;
                        if (aud.regAt[byCity[mid]] < t) lo = mid + 1;
                        else hi = mid;
                    }
                    if (lo > from) cand = byCity[from + r.nextInt(lo - from)];
                }
                if (cand < 0 || cand == x.user || aud.regAt[cand] >= t || aud.deletedAt[cand] <= t) continue;
                if (pairs.contains(((long) x.user << 32) | cand)) continue;
                chosen = cand;
            }
            if (chosen < 0) continue;
            pairs.add(((long) x.user << 32) | chosen);
            followees.add(chosen);
            long un = r.chance(P_UNFOLLOW) ? t + SiteRules.HOUR + (long) (r.exponential(150) * SiteRules.DAY) : -1;
            out.add(new Follow(x.user, chosen, t, un));
        }
        return out;
    }

    /** Число элементов отсортированного {@code a}, меньших {@code t}. */
    static int upper(long[] a, long t) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /**
     * Голоса за рецензии ({@code reviews} — по времени, как в выпуске): число — логнормальное с тяжёлым хвостом от веса
     * автора и известности фильма; моменты — от рецензии, чаще в первые недели; голосует зарегистрированный к моменту,
     * не автор, не удалённый, чаще — оценщик.
     */
    static List<Vote> votes(List<Review> reviews, Audience aud, SiteInput in, RandomStreams st) {
        RandomStreams.Stream vs = st.stream("site.review_votes");
        List<Vote> out = new ArrayList<>();
        Set<Integer> voted = new HashSet<>();
        int size = in.movies().size();
        for (int i = 0; i < reviews.size(); i++) {
            Review v = reviews.get(i);
            Rng r = vs.at(aud.key[v.user], v.seq);
            double pop = StrictMath.pow(1.0 / (1.0 + in.movies().get(v.movie).popularity() / Math.max(20.0, size / 100.0)), 0.5);
            double median = VOTES * StrictMath.pow(v.weight, 0.8) * (0.4 + pop) * (v.hidden ? 0.2 : 1.0);
            int k = (int) Math.min(3000, Math.floor(r.logNormal(median, 1.55)));
            long t = v.at;
            voted.clear();
            double useful = 0.45 + 0.45 * Math.min(1.0, v.weight / 2.0);
            for (int j = 0; j < k; j++) {
                t += 60 + (long) (r.exponential(j < 3 ? 2 : 12) * SiteRules.DAY / Math.max(1, k / 20.0));
                if (aud.deletedAt[v.user] <= t) break;
                int n = upperReg(aud, t);
                int voter = -1;
                for (int attempt = 0; attempt < 10 && voter < 0 && n > 0; attempt++) {
                    int c = r.nextInt(n);
                    if (c == v.user || aud.deletedAt[c] <= t || voted.contains(c)) continue;
                    if (!aud.has(c, Audience.RATER) && !r.chance(0.3)) continue;
                    voter = c;
                }
                boolean ok = r.chance(useful);
                if (voter < 0) continue;
                voted.add(voter);
                out.add(new Vote(i, voter, ok, t));
            }
        }
        return out;
    }

    /** Пользователей, зарегистрированных раньше {@code t} (индексы — по регистрации). */
    static int upperReg(Audience aud, long t) {
        int lo = 0, hi = aud.n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (aud.regAt[mid] < t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
