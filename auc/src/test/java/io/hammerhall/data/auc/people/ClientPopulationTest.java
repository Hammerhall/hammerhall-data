package io.hammerhall.data.auc.people;

import io.hammerhall.data.auc.AucDataset;
import io.hammerhall.data.auc.house.CitiesInput;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Horizon;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.DatasetContract;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** База клиентов: рост стабилен при продлении (префикс тот же), объём и доли — как у набора. */
class ClientPopulationTest {
    static HouseRules house;
    static RandomStreams streams = new RandomStreams(AucDataset.DEFAULT_SEED);

    @BeforeAll
    static void load() throws IOException {
        AucDataset d = new AucDataset();
        DatasetContract.assumeInputs(d);
        CitiesInput c = CitiesInput.load(Cli.inputPaths(d).get("cities"));
        house = new HouseRules(HouseRules.standardDepartments(), io.hammerhall.data.auc.house.HouseModule.categories(),
                c.cities(), HouseRules.standardOffices(c::idByNameRu), List.of(), HouseRules.standardIncrements(),
                HouseRules.standardFees());
    }

    static long at(LocalDate d) {
        return d.atStartOfDay(HouseRules.ZONE).toEpochSecond();
    }

    @Test
    void growthIsStableWhenThePeriodGrows() {
        ClientPopulation shortBase = new ClientPopulation(streams, house, 0.02, at(LocalDate.of(2027, 9, 28)));
        ClientPopulation longBase = new ClientPopulation(streams, house, 0.02, at(LocalDate.of(2028, 2, 28)));
        assertTrue(longBase.size() > shortBase.size());
        for (int id = 1; id <= shortBase.size(); id++) {
            assertEquals(shortBase.registeredAt(id), longBase.registeredAt(id), "клиент " + id);
            assertEquals(shortBase.flags(id), longBase.flags(id), "флаги " + id);
            assertEquals(shortBase.cityId(id), longBase.cityId(id));
            assertEquals(shortBase.budgetKop(id), longBase.budgetKop(id));
            assertArrayEquals(shortBase.devices(id), longBase.devices(id), "устройства " + id);
            assertEquals(shortBase.shillFor(id), longBase.shillFor(id));
            assertEquals(shortBase.ring(id), longBase.ring(id));
            for (Dept d : Dept.values()) assertEquals(shortBase.interest(id, d), longBase.interest(id, d));
        }
        // связи короткой базы — те же в длинной (кроме двойников, зарегистрированных позже конца короткой)
        List<ClientBase.Link> l = new ArrayList<>(longBase.links());
        for (ClientBase.Link x : shortBase.links()) assertTrue(l.contains(x), x.toString());
        // выбор участника — тот же при том же ключе и моменте
        Rng r1 = streams.stream("test").at(1), r2 = streams.stream("test").at(1);
        long t = at(LocalDate.of(2026, 5, 1));
        for (int i = 0; i < 200; i++) assertEquals(shortBase.sampleBuyer(Dept.COINS, t, r1), longBase.sampleBuyer(Dept.COINS, t, r2));
    }

    /**
     * Связи без повторов (T14 в full): подставной 13385 выпадал комитенту-торговцу 12351 дважды (вторая и третья
     * связь) — спрос сажал его на лоты 12351 двумя участниками одной регистрации, и у их ручных ставок совпадали ключи
     * запросов (лоты 148896, 213726, 227705). В medium таких связей две, в small — ни одной. База клиентов дешёвая
     * (масштаб 1 — ≈ 1 с), поэтому проверяется и масштаб full: та же база, что в выпуске full.
     */
    @Test
    void linksHaveNoRepeats() {
        long end = Horizon.end(AucDataset.PERIOD);
        for (double scale : new double[]{0.10, 1.0}) {
            ClientPopulation b = new ClientPopulation(new RandomStreams(AucDataset.DEFAULT_SEED), house, scale, end);
            Set<ClientBase.Link> seen = new HashSet<>();
            for (ClientBase.Link x : b.links()) assertTrue(seen.add(x), "масштаб " + scale + ": повтор связи " + x);
            if (scale == 1.0) {
                assertEquals(795_689, b.size(), "база full");
                ClientBase.Link repeated = new ClientBase.Link("shill", 13385, 12351, 0, null);
                assertEquals(1, b.links().stream().filter(repeated::equals).count(), "подставной 13385 у комитента 12351");
            }
        }
    }

    @Test
    void volumeAndSharesFollowTheSpec() {
        double scale = 0.05;
        ClientPopulation b = new ClientPopulation(streams, house, scale, at(LocalDate.of(2027, 9, 1)));
        // ≈ 0,73 млн к «сейчас» в full
        assertEquals(730_000 * scale, b.size(), 730_000 * scale * 0.05);
        int dealers = 0, buyers = 0, companies = 0, young = 0, persons = 0, nonPayers = 0;
        double spy = 0;
        int y2025 = b.countRegisteredBefore(at(LocalDate.of(2026, 1, 1))) - b.countRegisteredBefore(at(LocalDate.of(2025, 1, 1)));
        int y2026 = b.countRegisteredBefore(at(LocalDate.of(2027, 1, 1))) - b.countRegisteredBefore(at(LocalDate.of(2026, 1, 1)));
        float[] spys = new float[b.size()];
        for (int id = 1; id <= b.size(); id++) {
            if (b.dealer(id)) dealers++;
            if (b.buyer(id)) buyers++;
            if (b.company(id)) companies++;
            else {
                persons++;
                if (2026 - b.birthYear(id) < 40) young++;
            }
            if (b.serialNonPayer(id)) nonPayers++;
            spy += b.salesPerYear(id);
            spys[id - 1] = b.buyer(id) && !b.dealer(id) ? b.salesPerYear(id) : -1;
        }
        assertTrue(dealers > 0.004 * b.size() && dealers < 0.012 * b.size(), "торговцы " + dealers);
        assertTrue(buyers > 0.75 * b.size(), "покупатели " + buyers);
        assertTrue(companies > 0.04 * b.size() && companies < 0.08 * b.size(), "организации " + companies);
        double youngShare = (double) young / persons;
        assertTrue(youngShare > 0.17 && youngShare < 0.26, "моложе 40: " + youngShare);
        assertTrue(nonPayers > 0, "неплательщики");
        // рост ≈ 10 % в год
        double g = (double) y2026 / y2025;
        assertTrue(g > 1.02 && g < 1.2, "рост 2026 к 2025: " + g);
        // частота: медиана ≈ 2
        int k = 0;
        for (float x : spys) if (x >= 0) k++;
        float[] s = new float[k];
        k = 0;
        for (float x : spys) if (x >= 0) s[k++] = x;
        Arrays.sort(s);
        assertEquals(2.0, s[s.length / 2], 0.3, "медиана торгов в год");
        assertTrue(spy > 0);
        // связи: подставные, сговор, повторная регистрация
        long shills = b.links().stream().filter(x -> x.kind().equals("shill")).count();
        long rings = b.links().stream().filter(x -> x.kind().equals("ring")).count();
        assertTrue(shills > 0 && rings >= 3, "подставных связей " + shills + ", сговор " + rings);
    }
}
