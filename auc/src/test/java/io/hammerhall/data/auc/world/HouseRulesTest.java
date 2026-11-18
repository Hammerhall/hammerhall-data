package io.hammerhall.data.auc.world;

import io.hammerhall.data.auc.bid.IncrementTable;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Правила денег дома — контрольные примеры до копейки; таблица шагов дома; ключи — обратимы. */
class HouseRulesTest {
    private static final List<HouseRules.FeeSchedule> FEES = HouseRules.standardFees();

    private static HouseRules.FeeSchedule fee(String code) {
        for (HouseRules.FeeSchedule f : FEES) if (f.code().equals(code)) return f;
        throw new AssertionError(code);
    }

    /** Строка счёта: премия, сбор площадки, НДС, итог (M1–M3). */
    private static long[] line(String schedule, long hammerRub, boolean platform, LocalDate saleDate) {
        long hammer = hammerRub * 100;
        long premium = HouseRules.premium(fee(schedule), hammer);
        long plat = platform ? HouseRules.percent(hammer, fee(HouseRules.PLT_2024).rateMilliPct()) : 0;
        long vat = HouseRules.vat(premium + plat, saleDate);
        return new long[]{premium, plat, vat, hammer + premium + plat + vat};
    }

    @Test
    void invoiceExamplesOfSection2_8() {
        assertEquals(List.of(57_500_00L, 0L, 11_500_00L, 319_000_00L),
                List.of(box(line("BP-2024", 250_000, false, LocalDate.of(2025, 11, 20)))));
        assertEquals(List.of(57_500_00L, 0L, 12_650_00L, 320_150_00L),
                List.of(box(line("BP-2024", 250_000, false, LocalDate.of(2026, 3, 14)))));
        assertEquals(List.of(4_500_00L, 0L, 990_00L, 23_490_00L),
                List.of(box(line("BP-2026", 18_000, false, LocalDate.of(2026, 11, 19)))));
        assertEquals(List.of(700_000_00L, 0L, 154_000_00L, 3_854_000_00L),
                List.of(box(line("BP-2026", 3_000_000, false, LocalDate.of(2026, 11, 19)))));
        assertEquals(List.of(4_700_000_00L, 0L, 1_034_000_00L, 30_734_000_00L),
                List.of(box(line("BP-2026", 25_000_000, false, LocalDate.of(2026, 11, 19)))));
        assertEquals(List.of(1_241_00L, 0L, 273_02L, 8_814_02L),
                List.of(box(line("BP-COIN", 7_300, false, LocalDate.of(2026, 5, 5)))));
        assertEquals(List.of(30_000_00L, 4_200_00L, 7_524_00L, 161_724_00L),
                List.of(box(line("BP-2026", 120_000, true, LocalDate.of(2026, 11, 19)))));
    }

    @Test
    void consignorExampleOfSection2_8() {
        HouseRules h = new HouseRules(HouseRules.standardDepartments(), List.of(
                cat(1, Dept.PAINTING), cat(2, Dept.JEWELRY), cat(3, Dept.COINS), cat(4, Dept.BOOKS), cat(5, Dept.DECORATIVE),
                cat(6, Dept.COLLECTIBLES)), List.of(), List.of(), List.of(), HouseRules.standardIncrements(), FEES);
        long hammer = 1_000_000_00L;
        long commission = h.commission(hammer, false, null);
        long insurance = HouseRules.percent(hammer, h.fee(HouseRules.INS_2024).rateMilliPct());
        long catalogue = h.fee(HouseRules.CAT_LIVE).fixedKop();
        long vat = HouseRules.vat(commission + insurance + catalogue, LocalDate.of(2026, 11, 19));
        assertEquals(130_500_00L, commission);
        assertEquals(15_000_00L, insurance);
        assertEquals(32_098_00L, vat);
        assertEquals(822_002_00L, hammer - commission - insurance - catalogue - vat);
        assertEquals(80_500_00L, h.commission(hammer, true, null));
        assertEquals(70_000_00L, h.commission(hammer, false, 7_000));
        // непроданный онлайн-лот: каталог 150 + НДС 33
        long cat = h.fee(HouseRules.CAT_TIMED).fixedKop();
        assertEquals(183_00L, cat + HouseRules.vat(cat, LocalDate.of(2026, 11, 19)));
        // шкала премии по дате торгов; у монет — своя
        assertEquals("BP-2024", h.premiumSchedule(Dept.PAINTING, LocalDate.of(2026, 8, 31)).code());
        assertEquals("BP-2026", h.premiumSchedule(Dept.PAINTING, LocalDate.of(2026, 9, 1)).code());
        assertEquals("BP-COIN", h.premiumSchedule(Dept.COINS, LocalDate.of(2026, 9, 1)).code());
    }

    @Test
    void incrementsAreTheHouseTable() {
        IncrementTable t = HouseRules.standardIncrements();
        assertEquals(14, t.bands());
        assertEquals(IncrementTable.standard(), t);
        assertEquals(250_00L, t.step(3_000_00L));
        assertEquals(11, FEES.size(), "шкал и сборов — 11");
        int tiers = 0;
        for (HouseRules.FeeSchedule f : FEES) tiers += f.tiers().size();
        assertEquals(10, tiers);
    }

    @Test
    void keysRoundTrip() {
        long lot = Keys.lot(812, 1234, 1);
        assertEquals(812, Keys.lotSale(lot));
        assertEquals(1234, Keys.lotNo(lot));
        assertEquals("1234A", Keys.lotLabel(lot));
        long reg = Keys.registration(812, 654_321);
        assertEquals(812, Keys.registrationSale(reg));
        assertEquals(654_321, Keys.registrationClient(reg));
        assertTrue(reg > 0 && Keys.order(lot, 654_321, 3) > 0 && Keys.invoice(812, 654_321, InvoiceKind.REISSUE, 2) > 0);
        assertTrue(Keys.item(Dept.COLLECTIBLES, LocalDate.of(2027, 12, 31), 99_999) > 0);
    }

    @Test
    void dueAtIsEndOfMoscowDay() {
        long issued = LocalDate.of(2026, 11, 19).atTime(22, 30).atZone(HouseRules.ZONE).toEpochSecond();
        long due = HouseRules.dueAt(issued, 10);
        assertEquals(LocalDate.of(2026, 11, 30).atStartOfDay(HouseRules.ZONE).toEpochSecond() - 1, due);
    }

    private static HouseRules.Category cat(int id, Dept d) {
        return new HouseRules.Category(id, d, 0, 1, "c" + id, "к" + id, "c" + id, true);
    }

    private static Long[] box(long[] a) {
        Long[] out = new Long[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i];
        return out;
    }
}
