package io.hammerhall.data.auc.auction.audit;

import io.hammerhall.data.auc.auction.engine.Codes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Коридоры «последних секунд» — к концу, действовавшему в момент ставки: поздняя ставка сама сдвигает итоговый конец не
 * раньше t + 2 мин, поэтому к {@code closed_at} гонка и снайпер в последние 10 с не видны никогда.
 */
class CorridorsTest {
    static final long P = 1_773_158_400_000L, H = 3_600_000;

    @Test
    void lastSecondsAreMeasuredToTheCloseInEffect() {
        AuditLot a = new AuditLot();
        a.clear();
        a.lotId = 1;
        a.plannedMs = P;
        a.start = 100_000;
        a.low = 200_000;
        a.high = 300_000;
        a.result = Codes.RES_SOLD;
        a.closedMs = P - 5_000 + 120_000;
        a.hammer = 120_000;
        a.extensions = 1;
        a.bid(1, 1, 11, 1, Codes.CH_ONLINE, Codes.K_MANUAL, 0, 0, 100_000, P - 3 * H, true, Codes.R_NONE, false, true, 1, 1, 0);
        a.bid(2, 2, 12, 2, Codes.CH_ONLINE, Codes.K_MANUAL, 0, 0, 120_000, P - 5_000, true, Codes.R_NONE, true, true, 2, 2, 0);
        a.bid(3, 3, 13, 3, Codes.CH_ONLINE, Codes.K_MANUAL, 0, 0, 120_000, P - 5_000, false, Codes.R_RACE_LOST, false, true, 3, 3, 0);
        a.event(1, Codes.E_PUBLISHED, P - 10 * 24 * H, 0, 0, 0, 0);
        a.event(2, Codes.E_EXTENDED, P - 5_000, 2, P - 5_000 + 120_000, 0, 0);
        a.event(3, Codes.E_SOLD, a.closedMs, 2, 0, 120_000, 0);
        a.winningBid = 2;
        Corridors c = new Corridors(1.0);
        c.lot(a, 0);
        // гонка двух ставок за 5 с до действовавшего конца (плана) — в последних 10 с; к closed_at было бы 0 %
        assertEquals(100.0, c.racedLast10Share(), 1e-9);
        assertEquals(P, Corridors.inEffect(a, P - 5_000));
        assertEquals(P - 5_000 + 120_000, Corridors.inEffect(a, P));
        // снайперы: двое из трёх онлайн-участников поставили больше половины ставок в последние 10 с
        assertEquals(200.0 / 3, c.sniperShare(), 1e-9);
    }
}
