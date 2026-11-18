package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.HouseRules.FeeSchedule;
import io.hammerhall.data.auc.world.InvoiceKind;
import io.hammerhall.data.auc.world.PayMethod;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Планировщик оплаты: функция без состояния — счёт → план. Случайность — только от ключа счёта
 * ({@code money.payments} под {@link io.hammerhall.data.auc.world.Keys#invoice}) и черт клиента; от периода, «сейчас»
 * и порядка вызовов план не зависит, поэтому цикл торгов и модуль денег получают один и тот же план, а
 * продление периода его не меняет.
 *
 * <h2>Модель</h2>
 * <ul>
 *   <li><b>Отказ</b> — окончательно не платят: живые 1,5 %, онлайн 3 %, новые клиенты (меньше двух оплаченных счетов)
 *       8 % — × надёжность клиента; заложенные неплательщики ({@code serial_non_payer}) — всегда; недостача
 *       ({@code shortfall}) — 65 % (её выставляют уже заблокированному). Отмена {@code non_payment} — на 31-й день,
 *       9:00–12:00 по Москве; 30 % отказавшихся перед этим пробовали платить (неудачные попытки).</li>
 *   <li><b>Второй шанс</b> (только счёт {@code sale}): 25 % отказов — лоты предлагают вторым ставкам, половина
 *       соглашается.</li>
 *   <li><b>День полной оплаты</b> у платящих: доли 35 / 70 / 90 / 96 % счетов к 2 / 5 / 10 / 30-му дню — делённые
 *       на долю платящих (0,96): все платящие платят к 30-му дню. Половина случайности — темп клиента
 *       ({@link ClientBase#payDelayDays}), половина — случай счёта; доли от этого не сдвигаются.</li>
 *   <li><b>Способ и части</b>: карта, СБП, перевод (дорогие счета, организации), наличные в офисе; дорогие переводом —
 *       часто частями (2–3), прочие — редко. Перед картой и СБП — неудачные попытки (≈ 15 %: отказ банка, нет денег,
 *       тайм-аут, лимит). Депозит регистрации — в зачёт сразу после выставления (если меньше суммы счёта).</li>
 *   <li><b>Пени</b> — 0,1 % суммы счёта за каждый день после срока; последняя часть покрывает их.</li>
 *   <li><b>Заложено</b>: {@code premium_old_schedule} — счета 01–03.09.2026 (торги с 01.09.2026, кроме монет) со
 *       старой шкалой {@code BP-2024}; 60 % из них переиздаётся через 1–3 дня ({@code reissue}, прежний отменён
 *       {@code house_error}), платят по переизданному; {@code duplicate_payment} — ≈ 0,05 % успешных платежей картой и
 *       СБП записаны дважды (тот же номер провайдера), 70 % дублей возвращены ({@code refund}).</li>
 * </ul>
 */
public final class PaymentPlanner implements io.hammerhall.data.auc.world.PaymentPlanner {
    // ---------------------------------------------------------------- доли

    static final double DEFAULT_LIVE = 0.015, DEFAULT_TIMED = 0.03, DEFAULT_NEW = 0.08, DEFAULT_SHORTFALL = 0.65;
    /** Среднее (1 − надёжность) у покупателей — опора множителя надёжности (без нормировки по базе — продление). */
    static final double RELIABILITY_MEAN_RISK = 0.03;
    static final double SECOND_CHANCE_OFFER = 0.25, SECOND_CHANCE_ACCEPT = 0.5;
    static final double DEFAULTER_TRIED = 0.30;
    /** Доли счетов, оплаченных к 2 / 5 / 10 / 30-му дню (среди всех; платящих — 0,96). */
    static final double PAID_BY_2 = 0.35, PAID_BY_5 = 0.70, PAID_BY_10 = 0.90, PAID_BY_30 = 0.96;
    static final double CLIENT_PACE_WEIGHT = 0.5;
    static final double REISSUE_SHARE = 0.6;
    static final double DUPLICATE_SHARE = 0.0005, DUPLICATE_REFUNDED = 0.7;
    /** Заложенная старая шкала: торги с этой даты, счета по {@link #OLD_SCHEDULE_LAST_DAY} включительно. */
    static final LocalDate OLD_SCHEDULE_FROM = HouseRules.BP_2026_FROM, OLD_SCHEDULE_LAST_DAY = LocalDate.of(2026, 9, 3);
    /** Последний день оплаты (все платящие — к нему), день отмены. */
    static final int LAST_PAY_DAY = 30;

    /** P(день полной оплаты = k | платит), k = 0…30. */
    static final double[] DAY_CDF = dayCdf();

    private final MoneyRules money;
    private final HouseRules rules;
    private final ClientBase clients;
    private final RandomStreams.Stream stream;
    private final FeeSchedule oldSchedule;

    /**
     * @param streams потоки случайности прогона ({@code ctx.streams()})
     * @param rules   правила дома ({@code HOUSE})
     * @param clients база клиентов ({@code CLIENTS}); {@code null} — черты нейтральные (тесты)
     */
    public PaymentPlanner(RandomStreams streams, HouseRules rules, ClientBase clients) {
        this.rules = rules;
        this.money = new MoneyRules(rules);
        this.clients = clients;
        this.stream = streams.stream("money.payments");
        this.oldSchedule = rules.fee(HouseRules.BP_2024);
    }

    public MoneyRules money() {
        return money;
    }

    /** Срок оплаты (M6): 23:59:59 по Москве дня «дата выставления + {@code dueDays}» — {@link HouseRules#dueAt}. */
    public static long dueAt(long issuedAt, int dueDays) {
        return HouseRules.dueAt(issuedAt, dueDays);
    }

    // ---------------------------------------------------------------- план

    /** Платёж плана (и неудачная попытка): вид, способ, сумма, момент, номер провайдера (0 — нет). */
    public enum PayKind {INVOICE, DEPOSIT_APPLIED, REFUND}

    /**
     * Платёж или попытка.
     *
     * @param seq       порядок в плане счёта с 0 (ключ платежа — счёт и попытка)
     * @param duplicate запись-дубль успешного платежа (тот же {@code providerRef}) — {@code duplicate_payment}
     */
    public record Pay(int seq, PayKind kind, long at, long amountKop, PayMethod method, boolean ok, String failure,
                      long providerRef, boolean duplicate) {
    }

    /** Документ счёта: исходный или переизданный. */
    public record Doc(long key, InvoiceKind kind, long issuedAt, long dueAt, MoneyRules.Amounts amounts) {
    }

    /**
     * Полный план счёта — то, что пишет модуль денег.
     *
     * @param original      исходный счёт (у заложенной старой шкалы — с ней)
     * @param oldSchedule   заложено {@code premium_old_schedule}
     * @param reissue       переизданный счёт; {@code null} — нет (тогда исходный и есть счёт оплаты)
     * @param payments      платежи счёта оплаты по времени (с неудачными, дублями и возвратами дублей)
     * @param depositKop    депозит в зачёт (0 — не зачтён)
     * @param paidAt        полная оплата; 0 — нет
     * @param lateDays      дней просрочки к полной оплате
     * @param lateFeeKop    пени к полной оплате
     * @param defaults      окончательный отказ
     * @param cancelAt      отмена {@code non_payment} (31-й день); 0 — нет
     * @param plan          план для цикла торгов ({@code world.PaymentPlanner.PaymentPlan})
     */
    public record Detail(Doc original, boolean oldSchedule, Doc reissue, List<Pay> payments, long depositKop,
                         long paidAt, int lateDays, long lateFeeKop, boolean defaults, long cancelAt,
                         boolean secondChanceOffered, boolean secondChanceAccepted, PaymentPlan plan) {
        /** Счёт, который платят (переизданный или исходный). */
        public Doc payDoc() {
            return reissue != null ? reissue : original;
        }
    }

    @Override
    public PaymentPlan plan(InvoiceDraft d) {
        return detail(d).plan();
    }

    /** Полный план (модулю денег): суммы, переиздание, все записи платежей. */
    public Detail detail(InvoiceDraft d) {
        if (d.lines().isEmpty()) throw new IllegalArgumentException("счёт без строк: " + d.key());
        int n = d.lines().size();
        long[] price = new long[n];
        boolean[] plat = new boolean[n];
        for (int i = 0; i < n; i++) {
            price[i] = d.lines().get(i).priceKop();
            plat[i] = d.lines().get(i).platform();
        }
        FeeSchedule right = rules.premiumSchedule(d.dept(), d.saleDate());
        boolean old = oldSchedulePlanted(d.kind(), d.dept(), d.saleDate(), d.issuedAt());
        Doc original = new Doc(d.key(), d.kind(), d.issuedAt(), MoneyTime.dueAt(d.issuedAt(), HouseRules.PAYMENT_DUE_DAYS),
                money.invoice(d.kind(), old ? oldSchedule : right, d.saleDate(), price, plat));
        Rng r = stream.at(d.key());
        Doc reissue = null;
        if (old && r.chance(REISSUE_SHARE)) {
            long day = MoneyTime.epochDay(d.issuedAt()) + r.between(1, 2);
            long at = MoneyTime.at(day, r.between(10 * 3600, 18 * 3600));
            long key = (d.key() & ~0xFF00L) | ((long) InvoiceKind.REISSUE.ordinal() << 8);
            reissue = new Doc(key, InvoiceKind.REISSUE, at, MoneyTime.dueAt(at, HouseRules.PAYMENT_DUE_DAYS),
                    money.invoice(InvoiceKind.REISSUE, right, d.saleDate(), price, plat));
        }
        Doc pay = reissue != null ? reissue : original;
        return payPlan(d, r, original, old, reissue, pay);
    }

    /** Заложенная старая шкала: счёт торгов с 01.09.2026, выставлен 01–03.09.2026, не монеты. */
    static boolean oldSchedulePlanted(InvoiceKind kind, Dept dept, LocalDate saleDate, long issuedAt) {
        if (kind != InvoiceKind.SALE || dept == Dept.COINS || saleDate.isBefore(OLD_SCHEDULE_FROM)) return false;
        LocalDate issued = MoneyTime.date(issuedAt);
        return !issued.isAfter(OLD_SCHEDULE_LAST_DAY);
    }

    /** Вероятность окончательного отказа. */
    double defaultProbability(InvoiceDraft d) {
        if (clients != null && clients.serialNonPayer(d.clientId())) return 1.0;
        if (d.kind() == InvoiceKind.SHORTFALL) return DEFAULT_SHORTFALL;
        double base = d.newClient() ? DEFAULT_NEW : d.format() == Format.LIVE ? DEFAULT_LIVE : DEFAULT_TIMED;
        double mult = 1.0;
        if (clients != null) {
            double risk = 1.0 - clients.payReliability(d.clientId());
            mult = Math.min(4.0, Math.max(0.2, risk / RELIABILITY_MEAN_RISK));
        }
        return Math.min(0.95, base * mult);
    }

    /** Темп оплаты клиента — квантиль 0…1 (0 — платит сразу). */
    private double clientPace(int clientId) {
        if (clients != null) {
            double days = clients.payDelayDays(clientId);
            if (days >= 0 && !Double.isNaN(days)) return 1.0 - StrictMath.exp(-days / 4.0);
        }
        return stream.unit(0x5eed_0000_0000L ^ clientId);
    }

    private Detail payPlan(InvoiceDraft d, Rng r, Doc original, boolean old, Doc reissue, Doc doc) {
        long total = doc.amounts().total();
        long issued = doc.issuedAt();
        long issueDay = MoneyTime.epochDay(issued);
        boolean defaults = r.nextDouble() < defaultProbability(d);
        List<Pay> pays = new ArrayList<>();
        int[] seq = {0};
        if (defaults) {
            long cancelAt = MoneyTime.at(issueDay + HouseRules.CANCEL_DAY, 9 * 3600 + r.nextInt(3 * 3600));
            if (r.chance(DEFAULTER_TRIED)) {
                int tries = r.chance(0.4) ? 2 : 1;
                long[] days = new long[tries];
                for (int i = 0; i < tries; i++) days[i] = r.between(1, 20);
                java.util.Arrays.sort(days);
                for (int i = 0; i < tries; i++) {
                    PayMethod m = r.chance(0.6) ? PayMethod.CARD : PayMethod.SBP;
                    long at = cardTime(r, issueDay + days[i], issued);
                    pays.add(new Pay(seq[0]++, PayKind.INVOICE, at, total, m, false, failure(r, m), ref(r), false));
                }
            }
            boolean offered = d.kind() == InvoiceKind.SALE && r.chance(SECOND_CHANCE_OFFER);
            boolean accepted = offered && r.chance(SECOND_CHANCE_ACCEPT);
            pays.sort(PAY_ORDER);
            return detail(original, old, reissue, pays, 0, 0, 0, 0, true, cancelAt, offered, accepted, d.key());
        }
        // день полной оплаты
        double u = r.nextDouble();
        if (r.nextDouble() < CLIENT_PACE_WEIGHT) {
            double v = clientPace(d.clientId()) + 0.1 * (u - 0.5);
            u = v < 0 ? -v : v >= 1 ? Math.nextDown(2.0 - v) : v;
            if (u >= 1) u = Math.nextDown(1.0);
        }
        int t = dayOf(u);
        // депозит в зачёт
        long dep = 0, prevAt = issued;
        if (d.depositKop() > 0 && d.depositKop() < total) {
            dep = d.depositKop();
            prevAt = issued + 600 + r.nextInt(2 * 3600);
            pays.add(new Pay(seq[0]++, PayKind.DEPOSIT_APPLIED, prevAt, dep, PayMethod.DEPOSIT, true, null, 0, false));
        }
        long rest = total - dep;
        boolean company = clients != null && clients.company(d.clientId());
        PayMethod method = method(r, rest, company);
        int parts = 1;
        if (method == PayMethod.BANK_TRANSFER && rest >= 300_000_00L) {
            double x = r.nextDouble();
            parts = x < 0.30 ? 2 : x < 0.40 ? 3 : 1;
        } else if (rest >= 50_000_00L && r.chance(0.04)) {
            parts = 2;
        }
        // части: суммы (кроме последней — круглые, до 1 000 ₽), дни (последняя — в день t)
        long[] amount = new long[parts];
        long[] day = new long[parts];
        long left = rest;
        for (int i = 0; i < parts - 1; i++) {
            double share = parts == 2 ? r.uniform(0.3, 0.7) : r.uniform(0.2, 0.45);
            long a = Math.max(1_000_00L, (long) StrictMath.floor(rest * share / 1_000_00.0) * 1_000_00L);
            if (a >= left) a = left / 2;
            amount[i] = a;
            left -= a;
        }
        for (int i = 0; i < parts - 1; i++) day[i] = r.between(0, Math.min(t, LAST_PAY_DAY - 1));
        java.util.Arrays.sort(day, 0, parts - 1);
        day[parts - 1] = t;
        long paidAt = 0;
        int lateDays = 0;
        long lateFee = 0;
        long paid = dep;
        for (int i = 0; i < parts; i++) {
            PayMethod m = i == parts - 1 && parts > 1 && r.chance(0.15) ? PayMethod.CARD : method;
            long at = payTime(r, m, issueDay + day[i], Math.max(prevAt, issued));
            prevAt = at;
            long a;
            if (i == parts - 1) {
                lateDays = MoneyTime.lateDays(doc.dueAt(), at);
                lateFee = MoneyRules.lateFee(total, lateDays);
                a = total + lateFee - paid;
                paidAt = at;
            } else {
                a = amount[i];
            }
            paid += a;
            addPart(r, pays, seq, m, at, a, issued);
        }
        pays.sort(PAY_ORDER);
        return detail(original, old, reissue, pays, dep, paidAt, lateDays, lateFee, false, 0, false, false, d.key());
    }

    /** Успешная часть с неудачными попытками перед ней (карта, СБП), дублем и его возвратом. */
    private void addPart(Rng r, List<Pay> pays, int[] seq, PayMethod m, long at, long amount, long issued) {
        boolean provider = m == PayMethod.CARD || m == PayMethod.SBP;
        if (provider) {
            double x = r.nextDouble();
            int fails = x < 0.03 ? 2 : x < 0.15 ? 1 : 0;
            long t = at;
            for (int k = 0; k < fails; k++) {
                long gap = r.chance(0.75) ? 60 + r.nextInt(1740) : 20 * 3600 + r.nextInt(8 * 3600);
                if (t - gap <= issued + 30) gap = Math.max(1, Math.min(gap, (t - issued) / 2));
                t -= gap;
                PayMethod fm = r.chance(0.7) ? m : (m == PayMethod.CARD ? PayMethod.SBP : PayMethod.CARD);
                pays.add(new Pay(seq[0]++, PayKind.INVOICE, t, amount, fm, false, failure(r, fm), ref(r), false));
            }
        }
        long ref = provider ? ref(r) : 0;
        pays.add(new Pay(seq[0]++, PayKind.INVOICE, at, amount, m, true, null, ref, false));
        if (provider && r.nextDouble() < DUPLICATE_SHARE) {
            long dupAt = at + r.between(1, 5);
            pays.add(new Pay(seq[0]++, PayKind.INVOICE, dupAt, amount, m, true, null, ref, true));
            if (r.chance(DUPLICATE_REFUNDED)) {
                long refundAt = MoneyTime.at(MoneyTime.epochDay(dupAt) + r.between(1, 5), r.between(10 * 3600, 18 * 3600));
                pays.add(new Pay(seq[0]++, PayKind.REFUND, refundAt, amount, m, true, null, ref(r), false));
            }
        }
    }

    private Detail detail(Doc original, boolean old, Doc reissue, List<Pay> pays, long dep, long paidAt, int lateDays,
                          long lateFee, boolean defaults, long cancelAt, boolean offered, boolean accepted, long key) {
        List<Attempt> attempts = new ArrayList<>();
        for (Pay p : pays) {
            if (p.duplicate() || p.kind() == PayKind.REFUND) continue;
            attempts.add(new Attempt(p.at(), p.amountKop(), p.method(), p.ok(), p.failure()));
        }
        PaymentPlan plan = new PaymentPlan(key, attempts, paidAt, defaults, cancelAt, offered, accepted);
        return new Detail(original, old, reissue, Collections.unmodifiableList(pays), dep, paidAt, lateDays, lateFee,
                defaults, cancelAt, offered, accepted, plan);
    }

    static final Comparator<Pay> PAY_ORDER = Comparator.comparingLong(Pay::at).thenComparingInt(Pay::seq);

    // ---------------------------------------------------------------- распределения

    private static double[] dayCdf() {
        double paying = PAID_BY_30;
        double[][] buckets = {
                {PAID_BY_2 / paying, 0.22, 0.43, 0.35},
                {(PAID_BY_5 - PAID_BY_2) / paying, 0.38, 0.33, 0.29},
                {(PAID_BY_10 - PAID_BY_5) / paying, 0.16, 0.15, 0.15, 0.20, 0.34},
                {(PAID_BY_30 - PAID_BY_10) / paying}};
        double[] w = new double[LAST_PAY_DAY + 1];
        int k = 0;
        for (int b = 0; b < 3; b++) {
            double s = 0;
            for (int i = 1; i < buckets[b].length; i++) s += buckets[b][i];
            for (int i = 1; i < buckets[b].length; i++) w[k++] = buckets[b][0] * buckets[b][i] / s;
        }
        // дни 11…30: спад после срока, всплеск к 14-му (напоминание) и к 30-му (последнее предупреждение)
        double[] tail = new double[LAST_PAY_DAY - 10];
        double s = 0;
        for (int i = 0; i < tail.length; i++) {
            int day = 11 + i;
            tail[i] = 0.12 * StrictMath.exp(-0.12 * i) + (day == 14 ? 0.05 : 0) + (day == 30 ? 0.08 : 0);
            s += tail[i];
        }
        for (double v : tail) w[k++] = buckets[3][0] * v / s;
        double[] cdf = new double[w.length];
        double acc = 0;
        for (int i = 0; i < w.length; i++) {
            acc += w[i];
            cdf[i] = acc;
        }
        cdf[cdf.length - 1] = 1.0;
        return cdf;
    }

    static int dayOf(double u) {
        for (int i = 0; i < DAY_CDF.length; i++) if (u < DAY_CDF[i]) return i;
        return LAST_PAY_DAY;
    }

    private static PayMethod method(Rng r, long kop, boolean company) {
        double x = r.nextDouble();
        if (company) return x < 0.8 ? PayMethod.BANK_TRANSFER : x < 0.9 ? PayMethod.CARD : PayMethod.SBP;
        if (kop >= 1_000_000_00L) return x < 0.65 ? PayMethod.BANK_TRANSFER : x < 0.85 ? PayMethod.CARD : PayMethod.SBP;
        if (kop >= 100_000_00L) {
            return x < 0.25 ? PayMethod.BANK_TRANSFER : x < 0.62 ? PayMethod.CARD : x < 0.95 ? PayMethod.SBP : PayMethod.CASH;
        }
        return x < 0.04 ? PayMethod.BANK_TRANSFER : x < 0.54 ? PayMethod.CARD : x < 0.93 ? PayMethod.SBP : PayMethod.CASH;
    }

    private static final String[] CARD_FAIL = {"declined", "insufficient_funds", "timeout", "limit"};
    private static final double[] CARD_FAIL_W = {0.40, 0.30, 0.10, 0.20};
    private static final double[] SBP_FAIL_W = {0.30, 0.30, 0.30, 0.10};

    private static String failure(Rng r, PayMethod m) {
        return CARD_FAIL[r.weighted(m == PayMethod.SBP ? SBP_FAIL_W : CARD_FAIL_W)];
    }

    private static long ref(Rng r) {
        long v;
        do v = r.nextLong(); while (v == 0);
        return v;
    }

    /** Час суток (по Москве) — вес платежей картой и СБП: ночью мало, вечером больше. */
    private static final double[] HOUR_W = {0.5, 0.3, 0.2, 0.1, 0.1, 0.2, 0.4, 1, 2, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4.5, 5, 5,
            4.5, 3, 1.5};

    /** Момент части в сутки {@code day} не раньше {@code after} + 2 мин; не помещается — следующие сутки. */
    private static long payTime(Rng r, PayMethod m, long day, long after) {
        if (m == PayMethod.CARD || m == PayMethod.SBP) return cardTime(r, day, after);
        int from = m == PayMethod.CASH ? 10 * 3600 : 9 * 3600, to = 19 * 3600;
        for (long d = day; ; d++) {
            long lo = Math.max(MoneyTime.at(d, from), after + 120), hi = MoneyTime.at(d, to);
            if (hi - lo >= 600) return lo + r.nextInt((int) (hi - lo));
        }
    }

    private static long cardTime(Rng r, long day, long after) {
        for (long d = day; ; d++) {
            long start = MoneyTime.dayStart(d), lo = Math.max(start, after + 120);
            if (start + MoneyTime.DAY - lo < 600) continue;
            double[] w = new double[24];
            for (int h = 0; h < 24; h++) {
                long hs = start + h * 3600L, he = hs + 3600;
                long ov = Math.min(he, start + MoneyTime.DAY) - Math.max(hs, lo);
                w[h] = ov <= 0 ? 0 : HOUR_W[h] * ov / 3600.0;
            }
            int h = r.weighted(w);
            long hs = Math.max(start + h * 3600L, lo), he = start + (h + 1) * 3600L;
            return hs + r.nextInt((int) Math.max(1, he - hs));
        }
    }
}
