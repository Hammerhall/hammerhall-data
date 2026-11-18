package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.money.PaymentPlanner.Detail;
import io.hammerhall.data.auc.money.PaymentPlanner.Pay;
import io.hammerhall.data.auc.money.PaymentPlanner.PayKind;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.InvoiceKind;
import io.hammerhall.data.auc.world.PayMethod;
import io.hammerhall.data.auc.world.PaymentPlanner.InvoiceDraft;
import io.hammerhall.data.auc.world.PaymentPlanner.PaymentPlan;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Планировщик оплаты: функция без состояния (тот же счёт — тот же план), коридоры (35 / 70 / 90 / 96 %, отказы
 * 1,5 / 3 / 8 %, второй шанс 25 % и половина), правила M6 на каждом плане, заложенные старая шкала и дубли платежей.
 */
class PlannerTest {
    private static final HouseRules HOUSE = MoneyFx.house();
    private static final LocalDate DAY = LocalDate.of(2026, 3, 12);

    private static PaymentPlanner planner(long seed) {
        return new PaymentPlanner(new RandomStreams(seed), HOUSE, null);
    }

    /** Синтетическая волна счетов: 10 % живых, 70 % онлайн, 20 % — новые клиенты онлайн; суммы — логнормально. */
    private static InvoiceDraft draft(int i) {
        Rng r = new RandomStreams(7).stream("test.drafts").at(i);
        int group = i % 10;
        Format f = group == 0 ? Format.LIVE : Format.TIMED;
        boolean fresh = group >= 8;
        int sale = 1 + i / 5000;
        int client = 1 + i % 40_000;
        long issued = MoneyFx.msk(2026, 3, 12, 21, 0) + r.nextInt(5 * 3600);
        int lines = 1 + (r.chance(0.35) ? r.between(1, 4) : 0);
        long[] prices = new long[lines];
        for (int k = 0; k < lines; k++) prices[k] = Math.max(1_000, Math.round(r.logNormal(12_000, 1.3) / 100) * 100) * 100;
        long dep = r.chance(0.005) ? prices[0] / 10 : 0;
        return MoneyFx.draft(sale, client, InvoiceKind.SALE, f, Dept.PAINTING, DAY, issued, fresh, dep, prices);
    }

    @Test
    void sameInvoiceSamePlan() {
        PaymentPlanner a = planner(42), b = planner(42), c = planner(43);
        int differs = 0;
        for (int i = 0; i < 2000; i++) {
            InvoiceDraft d = draft(i);
            PaymentPlan p = a.plan(d);
            assertEquals(p, b.plan(d), "план — функция (seed, счёт)");
            assertEquals(p, a.plan(d), "повторный вызов — тот же план");
            if (!p.equals(c.plan(d))) differs++;
        }
        assertTrue(differs > 1500, "другой seed — другие планы: " + differs);
    }

    @Test
    void corridorsAndRules() {
        PaymentPlanner p = planner(20261018);
        int n = 200_000;
        long[] byDay = new long[32];
        long paid = 0, all = 0;
        long[] grp = new long[3], grpDef = new long[3];
        long defaults = 0, offered = 0, accepted = 0, okProvider = 0, dups = 0, refunds = 0, failed = 0, parts = 0;
        for (int i = 0; i < n; i++) {
            InvoiceDraft d = draft(i);
            Detail x = p.detail(d);
            PaymentPlanner.Doc doc = x.payDoc();
            all++;
            int g = d.newClient() ? 2 : d.format() == Format.LIVE ? 0 : 1;
            grp[g]++;
            for (Pay q : x.payments()) {
                assertTrue(q.at() > doc.issuedAt(), "платёж после выставления");
                if (!q.ok()) {
                    failed++;
                    assertTrue(q.failure() != null && q.providerRef() != 0);
                }
                if (q.ok() && q.kind() == PayKind.INVOICE && !q.duplicate()
                        && (q.method() == PayMethod.CARD || q.method() == PayMethod.SBP)) okProvider++;
                if (q.duplicate()) dups++;
                if (q.kind() == PayKind.REFUND) refunds++;
            }
            if (x.defaults()) {
                defaults++;
                grpDef[g]++;
                assertEquals(0, x.paidAt());
                assertEquals(MoneyTime.epochDay(doc.issuedAt()) + 31, MoneyTime.epochDay(x.cancelAt()), "отмена — на 31-й день");
                for (Pay q : x.payments()) assertFalse(q.ok(), "отказавшийся не платит");
                if (x.secondChanceOffered()) offered++;
                if (x.secondChanceAccepted()) accepted++;
                continue;
            }
            assertFalse(x.secondChanceOffered());
            long sum = 0;
            long last = 0;
            int okParts = 0;
            for (Pay q : x.payments()) {
                if (!q.ok() || q.duplicate()) continue;
                if (q.kind() == PayKind.REFUND) continue;
                sum += q.amountKop();
                last = Math.max(last, q.at());
                if (q.kind() == PayKind.INVOICE) okParts++;
            }
            if (okParts > 1) parts++;
            long total = doc.amounts().total();
            assertEquals(last, x.paidAt(), "полная оплата — последний успешный платёж");
            assertEquals(MoneyTime.lateDays(doc.dueAt(), x.paidAt()), x.lateDays());
            assertEquals(MoneyRules.lateFee(total, x.lateDays()), x.lateFeeKop(), "пени M6");
            assertEquals(total + x.lateFeeKop(), sum, "оплачено = сумма + пени");
            int day = (int) (MoneyTime.epochDay(x.paidAt()) - MoneyTime.epochDay(doc.issuedAt()));
            assertTrue(day >= 0 && day <= 30, "платящие — к 30-му дню: " + day);
            byDay[day]++;
            paid++;
            assertEquals(x.plan().attempts().size(), x.payments().stream().filter(q -> !q.duplicate()
                    && q.kind() != PayKind.REFUND).count());
        }
        long c2 = 0, c5 = 0, c10 = 0, c30 = 0;
        for (int k = 0; k <= 30; k++) {
            if (k <= 2) c2 += byDay[k];
            if (k <= 5) c5 += byDay[k];
            if (k <= 10) c10 += byDay[k];
            c30 += byDay[k];
        }
        double expDefault = (grp[0] * 0.015 + grp[1] * 0.03 + grp[2] * 0.08) / all;
        // доли среди всех счетов: при отказах ≈ 3,85 % (а не 4 %) — чуть выше целей, в пределах коридора ±2 п. п.
        near(0.35, c2 / (double) all, 0.01, "оплачено к 2-му дню");
        near(0.70, c5 / (double) all, 0.015, "к 5-му");
        near(0.90, c10 / (double) all, 0.015, "к 10-му");
        near(1 - expDefault, c30 / (double) all, 0.003, "к 30-му");
        near(0.015, grpDef[0] / (double) grp[0], 0.003, "отказы, живые");
        near(0.03, grpDef[1] / (double) grp[1], 0.002, "отказы, онлайн");
        near(0.08, grpDef[2] / (double) grp[2], 0.004, "отказы, новые");
        near(0.25, offered / (double) defaults, 0.03, "второй шанс предложен");
        near(0.5, accepted / (double) offered, 0.06, "второй шанс принят");
        near(0.0005, dups / (double) okProvider, 0.0002, "дубли платежей");
        near(0.7, refunds / (double) Math.max(1, dups), 0.25, "возвраты дублей");
        assertTrue(failed > all * 0.08 && failed < all * 0.25, "неудачных попыток: " + failed);
        assertTrue(parts > 0, "есть оплаты частями");
        assertEquals(paid + defaults, all);
    }

    @Test
    void oldScheduleAfterSwitch() {
        PaymentPlanner p = planner(5);
        LocalDate sale = LocalDate.of(2026, 9, 2);
        int reissued = 0, n = 2000;
        for (int i = 0; i < n; i++) {
            long issued = MoneyFx.msk(2026, 9, 2, 21, 30) + i;
            InvoiceDraft d = MoneyFx.draft(900, 1 + i, InvoiceKind.SALE, Format.TIMED, Dept.JEWELRY, sale, issued, false, 0,
                    MoneyFx.rub(10_000));
            Detail x = p.detail(d);
            assertTrue(x.oldSchedule());
            assertEquals(HOUSE.fee(HouseRules.BP_2024).id(), x.original().amounts().scheduleId());
            assertEquals("2300.00", io.hammerhall.data.core.money.Money.format(x.original().amounts().premiumTotal()));
            if (x.reissue() != null) {
                reissued++;
                assertEquals(HOUSE.fee(HouseRules.BP_2026).id(), x.reissue().amounts().scheduleId());
                assertEquals(250_000, x.reissue().amounts().premiumTotal());
                assertTrue(x.reissue().issuedAt() > x.original().issuedAt());
                assertNotEquals(x.original().key(), x.reissue().key());
            }
        }
        near(0.6, reissued / (double) n, 0.04, "переиздано");
        long late = MoneyFx.msk(2026, 9, 4, 0, 30);
        assertFalse(p.detail(MoneyFx.draft(901, 1, InvoiceKind.SALE, Format.TIMED, Dept.JEWELRY, sale, late, false, 0,
                MoneyFx.rub(10_000))).oldSchedule(), "с 04.09 — правильная шкала");
        assertFalse(p.detail(MoneyFx.draft(902, 1, InvoiceKind.SALE, Format.TIMED, Dept.COINS, sale,
                MoneyFx.msk(2026, 9, 2, 22, 0), false, 0, MoneyFx.rub(10_000))).oldSchedule(), "у монет своя шкала");
        assertFalse(p.detail(MoneyFx.draft(903, 1, InvoiceKind.SALE, Format.TIMED, Dept.BOOKS, LocalDate.of(2026, 8, 30),
                MoneyFx.msk(2026, 9, 1, 1, 0), false, 0, MoneyFx.rub(10_000))).oldSchedule(), "торги до смены — шкала верна");
    }

    @Test
    void depositApplied() {
        PaymentPlanner p = planner(9);
        int applied = 0;
        for (int i = 0; i < 500; i++) {
            long issued = MoneyFx.msk(2026, 4, 16, 22, 10);
            InvoiceDraft d = MoneyFx.draft(77, 10 + i, InvoiceKind.SALE, Format.LIVE, Dept.PAINTING, LocalDate.of(2026, 4, 16),
                    issued, false, MoneyFx.rub(350_000), MoneyFx.rub(3_600_000));
            Detail x = p.detail(d);
            if (x.defaults()) {
                assertEquals(0, x.depositKop(), "у отказа депозит не зачтён — удержан");
                continue;
            }
            applied++;
            assertEquals(MoneyFx.rub(350_000), x.depositKop());
            Pay first = x.payments().stream().filter(q -> q.kind() == PayKind.DEPOSIT_APPLIED).findFirst().orElseThrow();
            assertEquals(PayMethod.DEPOSIT, first.method());
        }
        assertTrue(applied > 450);
        // депозит больше суммы счёта — не зачитывается (возврат)
        Detail small = p.detail(MoneyFx.draft(78, 5, InvoiceKind.SALE, Format.LIVE, Dept.PAINTING, LocalDate.of(2026, 4, 16),
                MoneyFx.msk(2026, 4, 16, 22, 10), false, MoneyFx.rub(350_000), MoneyFx.rub(20_000)));
        assertEquals(0, small.depositKop());
    }

    private static void near(double want, double got, double tol, String what) {
        assertTrue(Math.abs(want - got) <= tol, what + ": " + got + " (цель " + want + " ± " + tol + ")");
    }
}
