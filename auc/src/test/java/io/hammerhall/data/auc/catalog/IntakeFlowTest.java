package io.hammerhall.data.auc.catalog;

import io.hammerhall.data.auc.AucDataset;
import io.hammerhall.data.auc.house.CitiesInput;
import io.hammerhall.data.auc.house.HouseModule;
import io.hammerhall.data.auc.people.ClientPopulation;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Intake;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.random.RandomStreams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Поток приёма: контракт {@link Intake} (визиты, готовность, счёт по времени) и стабильность при продлении. */
class IntakeFlowTest {
    static HouseRules house;
    static ClientPopulation clients;
    static final RandomStreams STREAMS = new RandomStreams(AucDataset.DEFAULT_SEED);
    static final double SCALE = 0.01;

    @BeforeAll
    static void load() throws IOException {
        AucDataset d = new AucDataset();
        DatasetContract.assumeInputs(d);
        CitiesInput c = CitiesInput.load(Cli.inputPaths(d).get("cities"));
        List<HouseRules.Office> offices = HouseRules.standardOffices(c::idByNameRu);
        house = new HouseRules(HouseRules.standardDepartments(), HouseModule.categories(), c.cities(), offices,
                List.of(), HouseRules.standardIncrements(), HouseRules.standardFees());
        clients = new ClientPopulation(STREAMS, house, SCALE, LocalDate.of(2028, 3, 1).atStartOfDay(HouseRules.ZONE).toEpochSecond());
    }

    static IntakeFlow flow(LocalDate to) throws IOException {
        StubTexts t = new StubTexts(STREAMS, house);
        return new IntakeFlow(STREAMS, house, clients, t, CatalogModule.tiers(STREAMS, t.makers()), SCALE,
                LocalDate.of(2025, 6, 1), to, null);
    }

    @Test
    void contractHolds() throws IOException {
        IntakeFlow f = flow(LocalDate.of(2026, 6, 30));
        assertTrue(f.size() > 3000, "вещей " + f.size());
        int inVisits = 0;
        for (LocalDate d = f.firstDay(); !d.isAfter(f.lastDay()); d = d.plusDays(1)) {
            long prev = Long.MIN_VALUE;
            for (Intake.Visit v : f.visits(d)) {
                assertTrue(v.receivedAt() >= prev, "визиты суток — по времени");
                prev = v.receivedAt();
                assertTrue(clients.registeredAt(v.consignorId()) < v.receivedAt(), "комитент зарегистрирован раньше визита");
                assertTrue(clients.consignor(v.consignorId()));
                for (int i = 0; i < v.itemIds().length; i++) {
                    int id = v.itemIds()[i];
                    if (i > 0) assertTrue(id > v.itemIds()[i - 1]);
                    Intake.Item it = f.item(id);
                    assertEquals(v.key(), it.visitKey());
                    assertEquals(v.consignorId(), it.consignorId());
                    assertEquals(v.decidedAt(), it.decidedAt());
                    assertTrue(it.decidedAt() > it.receivedAt(), "решение после приёма вещи " + id);
                    inVisits++;
                }
            }
        }
        assertEquals(f.size(), inVisits, "каждая вещь — ровно в одном визите");
        long t = LocalDate.of(2026, 1, 15).atStartOfDay(HouseRules.ZONE).toEpochSecond();
        int k = f.countReceivedBefore(t);
        assertTrue(f.item(k).receivedAt() < t && (k == f.size() || f.item(k + 1).receivedAt() >= t));
        for (Dept d : Dept.values()) {
            int[] r = f.ready(d, t, t + 30 * 86_400L);
            assertTrue(r.length > 0, d.code());
            for (int id : r) {
                assertEquals(d, f.dept(id));
                assertTrue(f.item(id).accepted() && f.readyAt(id) >= t && f.readyAt(id) < t + 30 * 86_400L);
            }
        }
    }

    @Test
    void longerFlowKeepsThePrefix() throws IOException {
        IntakeFlow s = flow(LocalDate.of(2026, 3, 31)), l = flow(LocalDate.of(2026, 9, 30));
        assertTrue(l.size() > s.size());
        for (int id = 1; id <= s.size(); id++) assertEquals(s.item(id), l.item(id), "вещь " + id);
    }

    /** Решение визита — после последней вещи: большой визит (21 вещь, осмотр дольше «1–4 ч») и граница «ровно в момент». */
    @Test
    void decisionComesAfterTheLastItemOfALargeVisit() {
        long at = 1_763_472_919L;
        // большой визит full: план решения — через 3 608 с, последняя из 21 вещи — через 4 075 с
        assertEquals(1_763_476_994L + 1_800 + 2, IntakeFlow.decisionAfter(at, 1_763_476_527L, 1_763_476_994L));
        // решение ровно в момент последней вещи — тоже позже
        assertTrue(IntakeFlow.decisionAfter(at, at + 3_600, at + 3_600) > at + 3_600);
        // самый поздний план «через 4 ч» и ещё более долгий визит
        long d = IntakeFlow.decisionAfter(at, at + 4 * 3_600 - 1, at + 5 * 3_600);
        assertTrue(d >= at + 5 * 3_600 + 1_800 && d <= at + 5 * 3_600 + 4_500, String.valueOf(d - at));
        // решение и так позже последней вещи — не меняется (визиты small и medium остаются прежними)
        assertEquals(at + 3_700, IntakeFlow.decisionAfter(at, at + 3_700, at + 3_699));
        assertEquals(at + 5 * 86_400, IntakeFlow.decisionAfter(at, at + 5 * 86_400, at + 4_000));
    }

    /**
     * Большой визит профиля {@code full} (комитент 30847, офис 5, 18.11.2025, 21 вещь) — без прогона {@code full}: сутки
     * потока в масштабе 1 с настоящими домом, базой и словарём. Решение по визиту — позже последней принятой вещи.
     */
    @Test
    void fullScaleLargeVisitIsDecidedAfterLastItem() throws IOException {
        AucDataset ds = new AucDataset();
        CitiesInput c = CitiesInput.load(Cli.inputPaths(ds).get("cities"));
        HouseRules h = HouseModule.build(STREAMS, c);
        ClientPopulation full = new ClientPopulation(STREAMS, h, 1.0,
                io.hammerhall.data.auc.world.Horizon.end(AucDataset.PERIOD));
        VocabTexts texts = new VocabTexts(STREAMS);
        LocalDate day = LocalDate.of(2025, 11, 18);
        IntakeFlow f = new IntakeFlow(STREAMS, h, full, texts, CatalogModule.tiers(STREAMS, texts.makers()), 1.0, day, day, null);
        Intake.Visit big = null;
        int late = 0;
        for (Intake.Visit v : f.visits(day)) {
            if (v.consignorId() == 30_847 && v.officeId() == 5) big = v;
            for (int id : v.itemIds()) if (f.item(id).decidedAt() <= f.item(id).receivedAt()) late++;
        }
        assertTrue(big != null && big.itemIds().length == 21, "визит full воспроизведён");
        long last = f.item(big.itemIds()[big.itemIds().length - 1]).receivedAt();
        assertEquals(1_763_476_994L, last, "последняя вещь — как в full");
        assertTrue(big.decidedAt() > last, "решение после последней вещи");
        assertEquals(0, late, "вещей с решением не позже приёма");
    }
}
