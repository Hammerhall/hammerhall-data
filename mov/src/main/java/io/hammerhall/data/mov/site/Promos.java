package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Промокоды (§1.2): каждый квартал с запуска сервиса до {@link SiteRules#HORIZON} — один-три открытых кода (пробные
 * дни или скидка на первую оплату) и до двух партнёрских (выдуманные коды без названий партнёров). Список — функция
 * seed и квартала; в выпуск идут коды, начавшие действовать до среза, {@code id} — по началу действия.
 */
final class Promos {
    private Promos() {
    }

    static final int TRIAL = 0, DISCOUNT = 1;
    /** Пробных дней по промокоду (без кода — 7, у партнёров и приглашённых — 14). */
    static final int TRIAL_DAYS = 14;

    record Promo(int id, String code, int kind, int value, boolean partner, LocalDate from, LocalDate to, Integer maxUses) {
        boolean validOn(LocalDate d) {
            return !d.isBefore(from) && !d.isAfter(to);
        }
    }

    private static final String[] WORDS = {"KINO", "FILM", "START", "VECHER", "ZIMA", "LETO", "OSEN", "VESNA", "PREMIERA",
            "DOMA", "SEANS", "KADR", "EKRAN", "TITRY"};
    private static final String PARTNER_CHARS = "BCDFGHJKLMNPQRSTVWXZ23456789";

    static List<Promo> build(RandomStreams streams) {
        RandomStreams.Stream st = streams.stream("site.promos");
        record Draft(String code, int kind, int value, boolean partner, LocalDate from, LocalDate to, Integer maxUses) {
        }
        List<Draft> drafts = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (LocalDate q = LocalDate.of(2019, 4, 1); q.isBefore(SiteRules.HORIZON); q = q.plusMonths(3)) {
            Rng r = st.at(q.getYear() * 10L + (q.getMonthValue() - 1) / 3);
            int pub = 1 + r.nextInt(3), par = r.nextInt(3);
            for (int k = 0; k < pub + par; k++) {
                boolean partner = k >= pub;
                LocalDate from = q.plusDays(r.nextInt(80));
                LocalDate to = from.plusDays(r.between(14, 180) - 1);
                int kind, value;
                if (r.chance(partner ? 0.6 : 0.4)) {
                    kind = TRIAL;
                    value = TRIAL_DAYS;
                } else {
                    kind = DISCOUNT;
                    value = partner ? r.pick(new Integer[]{20, 30, 50}) : r.pick(new Integer[]{10, 20, 30, 50});
                }
                String code;
                int attempt = 0;
                do {
                    if (partner) {
                        StringBuilder sb = new StringBuilder("P");
                        for (int i = 0; i < 7; i++) sb.append(PARTNER_CHARS.charAt(r.nextInt(PARTNER_CHARS.length())));
                        code = sb.toString();
                    } else {
                        code = r.pick(WORDS) + value + (attempt > 0 ? String.valueOf(attempt) : "");
                    }
                    attempt++;
                } while (codes.contains(code) || PublicNames.isInternal(code));
                codes.add(code);
                Integer max = partner ? Integer.valueOf(r.between(2, 16) * 500)
                        : r.chance(0.5) ? null : Integer.valueOf(r.between(6, 60) * 500);
                drafts.add(new Draft(code, kind, value, partner, from, to, max));
            }
        }
        drafts.sort(Comparator.comparing(Draft::from).thenComparing(Draft::code));
        List<Promo> out = new ArrayList<>(drafts.size());
        for (Draft d : drafts) {
            out.add(new Promo(out.size() + 1, d.code(), d.kind(), d.value(), d.partner(), d.from(), d.to(), d.maxUses()));
        }
        return List.copyOf(out);
    }
}
