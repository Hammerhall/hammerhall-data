package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.InvoiceKind;
import io.hammerhall.data.core.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static io.hammerhall.data.auc.money.MoneyFx.rub;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Контрольные примеры — до копейки: строки счёта (M1–M3) и строки отчёта комитенту (M4, M5). */
class ExamplesTest {
    private final MoneyRules m = new MoneyRules(MoneyFx.house());

    private void line(Dept dept, LocalDate saleDate, String schedule, long hammerRub, boolean platform,
                      String premium, String fee, String vat, String total) {
        HouseRules.FeeSchedule s = m.premiumSchedule(dept, saleDate);
        assertEquals(schedule, s.code(), "шкала на " + saleDate);
        MoneyRules.Amounts a = m.invoice(InvoiceKind.SALE, s, saleDate, new long[]{rub(hammerRub)}, new boolean[]{platform});
        assertEquals(premium, Money.format(a.premium()[0]), "премия " + hammerRub);
        assertEquals(fee, Money.format(a.platformFee()[0]), "сбор площадки " + hammerRub);
        assertEquals(vat, Money.format(a.vat()[0]), "НДС " + hammerRub);
        assertEquals(total, Money.format(a.lineTotal()[0]), "итог строки " + hammerRub);
        assertEquals(total, Money.format(a.total()), "итог счёта " + hammerRub);
    }

    @Test
    void invoiceLines() {
        line(Dept.PAINTING, LocalDate.of(2025, 11, 20), "BP-2024", 250_000, false, "57500.00", "0.00", "11500.00", "319000.00");
        line(Dept.PAINTING, LocalDate.of(2026, 3, 14), "BP-2024", 250_000, false, "57500.00", "0.00", "12650.00", "320150.00");
        line(Dept.JEWELRY, LocalDate.of(2026, 11, 19), "BP-2026", 18_000, false, "4500.00", "0.00", "990.00", "23490.00");
        line(Dept.PAINTING, LocalDate.of(2026, 11, 19), "BP-2026", 3_000_000, false, "700000.00", "0.00", "154000.00",
                "3854000.00");
        line(Dept.PAINTING, LocalDate.of(2026, 11, 19), "BP-2026", 25_000_000, false, "4700000.00", "0.00", "1034000.00",
                "30734000.00");
        line(Dept.COINS, LocalDate.of(2026, 5, 12), "BP-COIN", 7_300, false, "1241.00", "0.00", "273.02", "8814.02");
        line(Dept.DECORATIVE, LocalDate.of(2026, 11, 19), "BP-2026", 120_000, true, "30000.00", "4200.00", "7524.00",
                "161724.00");
    }

    @Test
    void scheduleChangesByDate() {
        assertEquals("BP-2024", m.premiumSchedule(Dept.BOOKS, LocalDate.of(2026, 8, 31)).code());
        assertEquals("BP-2026", m.premiumSchedule(Dept.BOOKS, LocalDate.of(2026, 9, 1)).code());
        assertEquals("BP-COIN", m.premiumSchedule(Dept.COINS, LocalDate.of(2025, 10, 1)).code());
        assertEquals(20, HouseRules.vatPct(LocalDate.of(2025, 12, 31)));
        assertEquals(22, HouseRules.vatPct(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void multiLineInvoiceTotals() {
        LocalDate d = LocalDate.of(2026, 11, 19);
        MoneyRules.Amounts a = m.invoice(InvoiceKind.SALE, m.premiumSchedule(Dept.PAINTING, d), d,
                new long[]{rub(18_000), rub(3_000_000), rub(120_000)}, new boolean[]{false, false, true});
        assertEquals("3138000.00", Money.format(a.hammerTotal()));
        assertEquals("734500.00", Money.format(a.premiumTotal()));
        assertEquals("4200.00", Money.format(a.platformTotal()));
        assertEquals("162514.00", Money.format(a.vatTotal()));
        assertEquals("4039214.00", Money.format(a.total()));
        MoneyRules.Amounts s = m.invoice(InvoiceKind.SHORTFALL, m.premiumSchedule(Dept.PAINTING, d), d,
                new long[]{rub(40_000)}, new boolean[]{true});
        assertEquals("0.00", Money.format(s.premiumTotal()), "у недостачи премии нет");
        assertEquals("40000.00", Money.format(s.total()));
    }

    @Test
    void settlementLines() {
        LocalDate d = LocalDate.of(2026, 11, 19);
        MoneyRules.SettlementAmounts p = m.sold(rub(1_000_000), false, null, Format.LIVE, true, d);
        assertEquals("130500.00", Money.format(p.commission()));
        assertEquals("15000.00", Money.format(p.insurance()));
        assertEquals("400.00", Money.format(p.catalogue()));
        assertEquals("32098.00", Money.format(p.vat()));
        assertEquals("822002.00", Money.format(p.net()));
        assertEquals("80500.00", Money.format(m.sold(rub(1_000_000), true, null, Format.LIVE, true, d).commission()));
        assertEquals("70000.00", Money.format(m.sold(rub(1_000_000), false, 7_000, Format.LIVE, true, d).commission()),
                "договорная единая ставка 7 %");
        MoneyRules.SettlementAmounts u = m.boughtIn(Format.TIMED, d);
        assertEquals("150.00", Money.format(u.catalogue()));
        assertEquals("33.00", Money.format(u.vat()));
        assertEquals("-183.00", Money.format(u.net()));
        MoneyRules.SettlementAmounts w = m.withdrawn(false, rub(50_000), Format.LIVE, d);
        assertEquals("6000.00", Money.format(w.withdrawal()));
        assertEquals("-7808.00", Money.format(w.net()), "12 % нижней оценки + каталог + НДС 22 %");
        assertEquals(0, m.withdrawn(true, rub(50_000), Format.LIVE, d).net(), "снят домом — без сборов");
    }

    @Test
    void lateFee() {
        assertEquals("319.00", Money.format(MoneyRules.lateFee(rub(319_000), 1)));
        assertEquals("6699.00", Money.format(MoneyRules.lateFee(rub(319_000), 21)));
        assertEquals("0.18", Money.format(MoneyRules.lateFee(18_350, 1)), "до копейки, половина вверх");
        assertEquals(0, MoneyRules.lateFee(rub(319_000), 0));
    }

    @Test
    void dueDate() {
        long issued = MoneyFx.msk(2026, 11, 19, 21, 40);
        assertEquals(MoneyFx.msk(2026, 11, 29, 23, 59) + 59, MoneyTime.dueAt(issued, 10));
        long due = MoneyTime.dueAt(issued, 10);
        assertEquals(0, MoneyTime.lateDays(due, due));
        assertEquals(1, MoneyTime.lateDays(due, due + 1));
        assertEquals(LocalDate.of(2026, 11, 19), MoneyTime.date(issued));
    }
}
