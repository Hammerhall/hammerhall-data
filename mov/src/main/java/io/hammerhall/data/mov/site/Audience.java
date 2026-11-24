package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.mov.site.text.SiteTexts;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Пользователи (§1.4): регистрации сутки за сутками с {@link SiteRules#SERVICE_START} — число в сутки от кривой роста
 * с опорой на постоянную дату, сезона и масштаба профиля; у каждого — поля таблицы и скрытая модель (вкус — 3–5
 * любимых жанров с весами, чувствительность к рейтингу, щедрость оценок, активность, роли: оценщик, рецензент, теги,
 * подборки, список «посмотреть», подписки на людей; размер семьи, склонность к подписке, срок интереса к сервису).
 * Индекс пользователя = {@code id − 1} (порядок регистрации); случайность — от суток регистрации и номера в сутках.
 */
final class Audience {
    static final int RATER = 1, REVIEWER = 2, TAGGER = 4, COLLECTOR = 8, WATCHLISTER = 16, EXTREME = 32, SOCIAL = 64,
            FRAGILE = 128;
    static final int ORGANIC = 0, ADS = 1, PARTNER = 2, REFERRAL = 3;
    static final int FAV = 5;
    static final long NEVER = Long.MAX_VALUE;
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9_]{4,20}");

    /** Вес жанра словаря в любимых (индекс — id жанра). */
    static final double[] GENRE_PRIOR = {0, 8, 0, 5, 4, 2, 9, 5, 3, 10, 4, 4, 0.5, 2, 4, 1.5, 1, 4, 5, 5, 0.5, 1, 7, 2, 1};

    int n;
    long[] key, regAt, deletedAt, lifeEnd;
    int[] city, referrer, promo;
    byte[] gender, channel, household, method;
    int[] flags;
    short[] birthYear;
    float[] generosity, rankSens, activity, quality, subProp;
    byte[] fav;
    float[] favW;
    String[] login, displayName;

    private Audience(int cap) {
        key = new long[cap];
        regAt = new long[cap];
        deletedAt = new long[cap];
        lifeEnd = new long[cap];
        city = new int[cap];
        referrer = new int[cap];
        promo = new int[cap];
        gender = new byte[cap];
        channel = new byte[cap];
        household = new byte[cap];
        method = new byte[cap];
        flags = new int[cap];
        birthYear = new short[cap];
        generosity = new float[cap];
        rankSens = new float[cap];
        activity = new float[cap];
        quality = new float[cap];
        subProp = new float[cap];
        fav = new byte[cap * FAV];
        favW = new float[cap * FAV];
        login = new String[cap];
        displayName = new String[cap];
    }

    private void grow() {
        int cap = key.length * 2 + 1024;
        key = Arrays.copyOf(key, cap);
        regAt = Arrays.copyOf(regAt, cap);
        deletedAt = Arrays.copyOf(deletedAt, cap);
        lifeEnd = Arrays.copyOf(lifeEnd, cap);
        city = Arrays.copyOf(city, cap);
        referrer = Arrays.copyOf(referrer, cap);
        promo = Arrays.copyOf(promo, cap);
        gender = Arrays.copyOf(gender, cap);
        channel = Arrays.copyOf(channel, cap);
        household = Arrays.copyOf(household, cap);
        method = Arrays.copyOf(method, cap);
        flags = Arrays.copyOf(flags, cap);
        birthYear = Arrays.copyOf(birthYear, cap);
        generosity = Arrays.copyOf(generosity, cap);
        rankSens = Arrays.copyOf(rankSens, cap);
        activity = Arrays.copyOf(activity, cap);
        quality = Arrays.copyOf(quality, cap);
        subProp = Arrays.copyOf(subProp, cap);
        fav = Arrays.copyOf(fav, cap * FAV);
        favW = Arrays.copyOf(favW, cap * FAV);
        login = Arrays.copyOf(login, cap);
        displayName = Arrays.copyOf(displayName, cap);
    }

    boolean has(int u, int flag) {
        return (flags[u] & flag) != 0;
    }

    /** Сродство к фильму: сумма весов любимых жанров фильма, к шкале −0.6…+1.4 (§1.5). */
    double affinity(int u, int[] genres) {
        double s = 0;
        for (int k = 0; k < FAV; k++) {
            int g = fav[u * FAV + k];
            if (g == 0) continue;
            for (int x : genres) if (x == g) s += favW[u * FAV + k];
        }
        return Math.min(1.4, 2.0 * s - 0.6);
    }

    /** Любимый жанр по весам (id 1–24) или {@code 0}, если любимых нет. */
    int favoriteGenre(int u, Rng r) {
        double x = r.nextDouble(), c = 0;
        for (int k = 0; k < FAV; k++) {
            c += favW[u * FAV + k];
            if (x < c) return fav[u * FAV + k];
        }
        return fav[u * FAV];
    }

    /** Регистраций в сутки в {@code full}: кривая роста × месяц × день недели. */
    static double regRate(LocalDate d) {
        double years = (d.toEpochDay() - SiteRules.GROWTH_ANCHOR.toEpochDay()) / 365.25;
        double f = SiteRules.REG_PER_DAY * StrictMath.exp(SiteRules.REG_GROWTH * years);
        return f * SiteRules.MONTH[d.getMonthValue() - 1] * (SiteRules.weekendOrHoliday(d) ? 1.15 : 0.95);
    }

    /** Доля аудитории профиля: масштаб профиля × поправка на размер снимка. */
    static double share(double profileScale, int moviesFull) {
        return profileScale * Math.max(SiteRules.AUDIENCE_MIN_SHARE, Math.min(1.0, moviesFull / SiteRules.AUDIENCE_MOVIES));
    }

    static Audience build(RandomStreams streams, Cities cities, List<Promos.Promo> promos, SiteTexts texts, double share,
                          long cutoff) {
        RandomStreams.Stream days = streams.stream("site.users.days"), st = streams.stream("site.users"),
                lg = streams.stream("site.users.login"), nm = streams.stream("site.users.name");
        Audience a = new Audience(1024);
        Set<String> logins = new HashSet<>();
        int[] uses = new int[promos.size() + 1];
        for (LocalDate d = SiteRules.SERVICE_START; SiteRules.mskMidnight(d) < cutoff; d = d.plusDays(1)) {
            long day = d.toEpochDay(), mid = SiteRules.mskMidnight(d);
            int k = days.at(day).poisson(share * regRate(d));
            if (k == 0) continue;
            long[] at = new long[k];
            Integer[] ord = new Integer[k];
            for (int i = 0; i < k; i++) {
                at[i] = mid + SiteRules.secondOfDay(st.at(day, 1_000_000L + i), true);
                ord[i] = i;
            }
            Arrays.sort(ord, (x, y) -> at[x] != at[y] ? Long.compare(at[x], at[y]) : Integer.compare(x, y));
            for (int oi = 0; oi < k; oi++) {
                int i = ord[oi];
                if (at[i] >= cutoff) continue;
                if (a.n == a.key.length) a.grow();
                int u = a.n++;
                a.key[u] = day * 100_000L + i;
                a.regAt[u] = at[i];
                a.traits(u, st.at(day, i), cities, promos, uses, d);
                a.names(u, lg, nm, texts, logins);
            }
        }
        return a;
    }

    private void traits(int u, Rng r, Cities cities, List<Promos.Promo> promos, int[] uses, LocalDate d) {
        long t = regAt[u];
        gender[u] = (byte) (r.chance(0.52) ? 1 : 0);
        int age = (int) Math.min(75, 16 + Math.round(r.logNormal(15, 0.55)));
        birthYear[u] = (short) (d.getYear() - age);
        city[u] = cities.pick(r);
        double ch = r.nextDouble();
        int c = ch < 0.45 ? ORGANIC : ch < 0.75 ? ADS : ch < 0.85 ? PARTNER : REFERRAL;
        referrer[u] = -1;
        if (c == REFERRAL) {
            for (int tries = 0; tries < 8 && u > 0 && referrer[u] < 0; tries++) {
                int j = r.nextInt(u);
                if (regAt[j] < t && lifeEnd[j] > t && deletedAt[j] > t) referrer[u] = j;
            }
            if (referrer[u] < 0) c = ORGANIC;
        }
        channel[u] = (byte) c;
        double hh = r.nextDouble();
        household[u] = (byte) (hh < 0.50 ? 1 : hh < 0.78 ? 2 : hh < 0.92 ? 3 : 4);
        double m = r.nextDouble();
        method[u] = (byte) (m < 0.68 ? 0 : m < 0.88 ? 1 : m < 0.96 ? 2 : 3);
        int f = 0;
        double act = r.logNormal(1.0, 0.7);
        if (r.chance(0.30)) {
            f |= RATER;
            act *= 2.2;
            if (r.chance(0.067)) {
                f |= REVIEWER;
                act *= 1.5;
            }
            if (r.chance(0.4)) f |= TAGGER;
            if (r.chance(0.25)) f |= COLLECTOR;
            if (r.chance(0.06)) f |= EXTREME;
        }
        if (r.chance(0.45)) f |= WATCHLISTER;
        if (r.chance(0.35) || (f & REVIEWER) != 0) f |= SOCIAL;
        if (r.chance(0.03)) f |= FRAGILE;
        flags[u] = f;
        activity[u] = (float) Math.min(8.0, act);
        generosity[u] = (float) r.uniform(-1.5, 1.5);
        rankSens[u] = (float) r.nextDouble();
        quality[u] = (float) r.logNormal(1.0, 0.8);
        double base = switch (c) {
            case ADS -> 0.50;
            case PARTNER -> 0.75;
            case REFERRAL -> 0.65;
            default -> 0.55;
        };
        subProp[u] = (float) Math.max(0.05, Math.min(0.98, base + 0.15 * r.gaussian()));
        lifeEnd[u] = t + (long) (r.exponential(1.6 * 365.25 * StrictMath.sqrt(Math.min(4.0, act))) * SiteRules.DAY) + SiteRules.DAY;
        deletedAt[u] = r.chance(0.04) ? t + SiteRules.DAY + (long) (r.exponential(500) * SiteRules.DAY) : NEVER;
        // вкус: 3–5 любимых жанров (наклон по полу и возрасту), веса — от логнормальных
        double[] w = GENRE_PRIOR.clone();
        if (gender[u] == 1) {
            w[18] *= 2.0;
            w[10] *= 1.5;
            w[9] *= 1.2;
        } else {
            w[1] *= 1.5;
            w[19] *= 1.4;
            w[23] *= 1.5;
            w[14] *= 1.2;
        }
        if (age < 25) {
            w[4] *= 1.5;
            w[14] *= 1.4;
        } else if (age > 50) {
            w[13] *= 1.8;
            w[23] *= 1.6;
            w[9] *= 1.3;
        }
        int nf = r.between(3, 5);
        double sum = 0;
        double[] ws = new double[nf];
        for (int k = 0; k < nf; k++) {
            int g = r.weighted(w);
            fav[u * FAV + k] = (byte) g;
            w[g] = 0;
            ws[k] = r.logNormal(1.0, 0.5) * (nf - k);
            sum += ws[k];
        }
        for (int k = 0; k < nf; k++) favW[u * FAV + k] = (float) (ws[k] / sum);
        // промокод при регистрации: по каналу, из действующих в эти сутки, пока не исчерпан
        promo[u] = -1;
        double pp = switch (c) {
            case ADS -> 0.35;
            case PARTNER -> 0.85;
            case REFERRAL -> 0.05;
            default -> 0.12;
        };
        if (r.chance(pp)) {
            List<Promos.Promo> ok = new ArrayList<>();
            for (Promos.Promo p : promos) {
                if (p.from().isAfter(d)) break;
                if (p.validOn(d) && p.partner() == (c == PARTNER) && (p.maxUses() == null || uses[p.id()] < p.maxUses())) ok.add(p);
            }
            if (!ok.isEmpty()) {
                Promos.Promo p = ok.get(r.nextInt(ok.size()));
                promo[u] = p.id() - 1;
                uses[p.id()]++;
            }
        }
    }

    private void names(int u, RandomStreams.Stream lg, RandomStreams.Stream nm, SiteTexts texts, Set<String> logins) {
        String l = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            String x = texts.login(lg.at(key[u], attempt));
            if (x != null && LOGIN.matcher(x).matches() && !logins.contains(x) && !PublicNames.isInternal(x)) {
                l = x;
                break;
            }
        }
        if (l == null) l = "user_" + key[u];
        logins.add(l);
        login[u] = l;
        String dn = null;
        for (int attempt = 0; attempt < 20 && dn == null; attempt++) {
            String x = texts.displayName(nm.at(key[u], attempt), gender[u] == 1 ? 'F' : 'M');
            if (x != null && !x.isBlank() && !PublicNames.isInternal(x)) dn = x;
        }
        displayName[u] = dn != null ? dn : "Зритель " + (u + 1);
    }
}
