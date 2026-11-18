package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.HashMap;
import java.util.Map;

/**
 * Добор описания до длины, выбранной для вещи (медиана ≈ 460 знаков, от 230 до 1000): предложения своего вида из
 * {@code vocab/extra.txt}, без повторов, с подстановкой десятилетия, века, страны и мастера.
 */
final class Extra {
    private Extra() {
    }

    private static final Map<Kind, String[]> POOLS = new HashMap<>();

    static {
        for (Kind k : Kind.values()) POOLS.put(k, V.list("extra", "extra." + k.name()));
    }

    static void pad(Ctx c) {
        Rng r = c.r;
        int target = (int) StrictMath.round(Math.max(230, Math.min(1000, r.logNormal(460, 0.28))));
        String[] pool = POOLS.get(c.kind);
        int len = W.join(c.desc).length();
        boolean byMaker = c.maker != null && c.attribution == Attribution.BY;
        boolean fine = c.dated == null || c.dated.exact() || c.dated.text().endsWith("-е годы");
        String decade = fine ? W.decadeGen(c.year) : W.roman(W.century(Math.max(1, c.year))) + " века";
        for (int i = 0; i < pool.length * 2 && len < target; i++) {
            String s = pool[r.nextInt(pool.length)];
            if (s.contains("{maker}") && !byMaker) continue;
            s = s.replace("{decade}", decade).replace("{century}", W.roman(W.century(Math.max(1, c.year))) + " века");
            if (c.country != null) s = s.replace("{country}", c.country.nameRu);
            if (byMaker) s = s.replace("{maker}", c.maker.nameGen());
            if (c.desc.contains(s) || !c.ok(s)) continue;
            c.desc.add(s);
            len += s.length() + 2;
        }
    }
}
