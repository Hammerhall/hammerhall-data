package io.hammerhall.data.auc.auction;

import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.core.time.Period;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Таблицы подготовки торгов на {@code small}: правила регистрации (таблички, сроки, 70 % —
 * в последние 72 ч, лимит T11, допуск T13), заочные — не позже чем за 2 ч, телефон — от 30 000 ₽, цепочки редакций
 * {@code bid_orders} (максимум растёт, одна активная), договоры; самопроверка выпуска зелёная.
 */
class PrepTablesTest {
    static final long H = 3_600;

    static PrepRuns.Run run() throws IOException {
        return PrepRuns.small(null);
    }

    @Test
    void selfCheckPasses() throws IOException {
        PrepRuns.Run r = run();
        assertTrue(r.result().ok(), "самопроверка small:\n" + r.result().report().render());
    }

    /** Торги по id: формат, открытие ставок, начало. */
    static Map<Integer, long[]> sales(PrepRuns.Run r) throws IOException {
        PrepRuns.Table s = PrepRuns.read(r.dir(), "sales");
        Map<Integer, long[]> out = new HashMap<>();
        for (String[] row : s.rows()) {
            out.put((int) s.num(row, "id"), new long[]{s.get(row, "format").equals("live") ? 1 : 0, s.num(row, "bidding_opens_at"),
                    s.num(row, "starts_at"), s.num(row, "published_at")});
        }
        return out;
    }

    @Test
    void registrationRules() throws IOException {
        PrepRuns.Run r = run();
        Map<Integer, long[]> sales = sales(r);
        PrepRuns.Table t = PrepRuns.read(r.dir(), "registrations");
        long n = 0, last72 = 0, sameDay = 0, limited = 0;
        Map<Integer, Set<Long>> paddles = new HashMap<>();
        long cut = r.prep().view().slice();
        long prevAt = Long.MIN_VALUE;
        int prevId = 0;
        for (String[] row : t.rows()) {
            int id = (int) t.num(row, "id");
            assertEquals(prevId + 1, id, "id подряд");
            prevId = id;
            long at = t.num(row, "registered_at");
            assertTrue(at >= prevAt, "id — по моменту регистрации");
            prevAt = at;
            assertTrue(at < cut, "регистрация до среза");
            long[] s = sales.get((int) t.num(row, "sale_id"));
            boolean live = s[0] == 1;
            assertTrue(s[3] > 0 && at >= s[1], "регистрация — после открытия ставок: " + String.join(" ", row));
            assertTrue(at < s[2], "регистрация — до начала торгов");
            String mode = t.get(row, "mode");
            long p = t.num(row, "paddle_no");
            if (!live) {
                assertEquals("online", mode);
                assertTrue(p >= 10_000 && p <= 99_999, "табличка онлайн-торгов " + p);
            } else {
                switch (mode) {
                    case "room" -> assertTrue(p >= 1 && p <= 1_999, "зал " + p);
                    // онлайн на живых — 10000–99999, как у онлайн-торгов: в full онлайн-регистраций на живых торгах —
                    // до 9 000, больше 7 000 номеров 3000–9999
                    case "online" -> assertTrue(p >= 10_000 && p <= 99_999, "онлайн " + p);
                    default -> assertTrue(p >= 2_000 && p <= 2_999, mode + " " + p);
                }
                if (mode.equals("phone")) {
                    long dayBefore18 = Math.floorDiv(s[2] + 3 * H, 86_400) * 86_400 - 3 * H - 6 * H;
                    assertTrue(at < dayBefore18, "телефон — заявка до 18:00 накануне");
                }
            }
            assertTrue(paddles.computeIfAbsent((int) t.num(row, "sale_id"), k -> new HashSet<>()).add(p), "табличка уникальна");
            String status = t.get(row, "status");
            assertTrue(Set.of("approved", "rejected", "pending").contains(status), status);
            assertEquals(status.equals("pending"), t.get(row, "decided_at").isEmpty(), "решение ↔ статус");
            assertEquals(status.equals("rejected"), !t.get(row, "reject_reason").isEmpty(), "отказ ↔ причина");
            if (!status.equals("pending")) {
                long dec = t.num(row, "decided_at");
                assertTrue(dec >= at && dec < cut, "решение после подачи");
                assertEquals(live, !t.get(row, "decided_by_id").isEmpty(), "живые — сотрудник, онлайн — автоматически");
                if (live) assertTrue(dec - at <= 86_400 + 60, "живые — в течение суток");
            }
            String lim = t.get(row, "bid_limit");
            if (!lim.isEmpty()) {
                assertEquals(HouseRules.NEW_CLIENT_LIMIT, t.num(row, "bid_limit"), "лимит нового клиента — 300 000 ₽");
                limited++;
            }
            n++;
            if (s[2] - at <= 72 * H) last72++;
            if (Math.floorDiv(s[2] + 3 * H, 86_400) == Math.floorDiv(at + 3 * H, 86_400)) sameDay++;
        }
        assertTrue(n > 10_000, "регистраций " + n);
        assertEquals(0.70, (double) last72 / n, 0.04, "70 % — в последние 72 ч");
        assertEquals(0.10, (double) sameDay / n, 0.03, "10 % — в день торгов");
        assertTrue(limited > 0, "лимит есть у новых клиентов");
    }

    /**
     * Регистрация — до первой ставки участника всегда: онлайн — {@code Bidder.placedAt} не раньше регистрации и решения;
     * живые — решение до начала сессии лота, заявки из книги — после регистрации; попытки до движка — после регистрации.
     * Момент регистрации, отданный в {@code demand()}, потом не меняется (он же — в таблице).
     */
    @Test
    void registrationPrecedesEveryBidAndIsFixedAfterDemand() throws IOException {
        PrepRuns.Run r = run();
        assertTrue(r.early().isEmpty(), "участник или попытка раньше регистрации: " + r.early().subList(0, Math.min(5, r.early().size())));
        assertTrue(r.regAtDemand().size() > 10_000, "участников " + r.regAtDemand().size());
        PrepRuns.Table t = PrepRuns.read(r.dir(), "registrations");
        Map<Long, Long> written = new HashMap<>();
        for (String[] row : t.rows()) {
            written.put(io.hammerhall.data.auc.world.Keys.registration((int) t.num(row, "sale_id"), (int) t.num(row, "client_id")),
                    t.num(row, "registered_at"));
        }
        int checked = 0;
        for (Map.Entry<Long, Long> e : r.regAtDemand().entrySet()) {
            var g = r.prep().registrations().get(e.getKey());
            assertEquals(e.getValue(), g.registeredAt(), "регистрация " + e.getKey() + " сменила момент после demand()");
            Long w = written.get(e.getKey());
            if (w != null) {
                assertEquals(e.getValue(), w, "в таблице — тот же момент");
                checked++;
            }
        }
        assertTrue(checked > 10_000, "сверено с таблицей " + checked);
    }

    @Test
    void limitAndPremiumKeptByDemand() throws IOException {
        PrepRuns.Run r = run();
        // T11 и T13 проверяет самопроверка по участникам движка (PrepAudit): нарушений — ноль
        String rep = r.result().report().render();
        for (String rule : new String[]{"T11 лимит", "T13 допуск", "заочные — не позже чем за 2 ч", "телефон — лоты от 30 000",
                "регистрация до первой ставки", "таблички"}) {
            assertTrue(rep.lines().anyMatch(l -> l.contains(rule) && l.contains("OK")), "проверка «" + rule + "» прошла");
        }
    }

    @Test
    void ordersAbsenteeBeforeCutoffPhoneFrom30kChains() throws IOException {
        PrepRuns.Run r = run();
        Map<Integer, long[]> sales = sales(r);
        PrepRuns.Table reg = PrepRuns.read(r.dir(), "registrations");
        Map<Integer, Integer> regSale = new HashMap<>();
        for (String[] row : reg.rows()) regSale.put((int) reg.num(row, "id"), (int) reg.num(row, "sale_id"));
        PrepRuns.Table t = PrepRuns.read(r.dir(), "bid_orders");
        Map<String, List<String[]>> chains = new TreeMap<>();
        Map<Integer, String[]> byId = new HashMap<>();
        int absentee = 0, phone = 0;
        for (String[] row : t.rows()) {
            byId.put((int) t.num(row, "id"), row);
            int lot = (int) t.num(row, "lot_id"), rg = (int) t.num(row, "registration_id");
            long[] s = sales.get(regSale.get(rg));
            assertEquals((long) r.lotSale().get(lot), (long) regSale.get(rg), "поручение — на лот своих торгов");
            String kind = t.get(row, "kind");
            if (kind.equals("absentee")) {
                absentee++;
                assertEquals(1, s[0], "заочные — только живые");
                assertTrue(t.num(row, "placed_at") <= s[2] - 2 * H, "заочная — не позже чем за 2 ч до начала");
            }
            if (kind.equals("phone")) {
                phone++;
                assertTrue(r.lotLow().get(lot) >= HouseRules.PHONE_MIN_LOW, "телефон — лоты от 30 000 ₽");
                assertFalse(t.get(row, "phone_outcome").isEmpty(), "телефон — исход звонка");
                assertFalse(t.get(row, "clerk_id").isEmpty(), "телефон — сотрудник на линии");
            }
            chains.computeIfAbsent(lot + ":" + rg, k -> new ArrayList<>()).add(row);
        }
        assertTrue(absentee > 0 && phone > 0, "есть заочные (" + absentee + ") и телефон (" + phone + ")");
        for (Map.Entry<String, List<String[]>> e : chains.entrySet()) {
            List<String[]> l = e.getValue();
            l.sort((a, b) -> Long.compare(t.num(a, "revision"), t.num(b, "revision")));
            long prevMax = -1;
            int active = 0;
            for (int i = 0; i < l.size(); i++) {
                String[] row = l.get(i);
                assertEquals(i + 1, t.num(row, "revision"), e.getKey() + ": редакции без пропусков");
                if (i == 0) assertTrue(t.get(row, "replaces_id").isEmpty(), "у первой — нет прежней");
                else {
                    assertEquals(t.num(l.get(i - 1), "id"), t.num(row, "replaces_id"), e.getKey() + ": ссылка на прежнюю");
                    assertFalse(t.get(row, "revision_reason").isEmpty(), "у редакций ≥ 2 — причина");
                }
                long max = t.num(row, "max_amount");
                if (!t.get(row, "status").equals("rejected") && max > 0) {
                    assertTrue(max > prevMax, e.getKey() + ": максимум растёт");
                    prevMax = max;
                }
                if (t.get(row, "status").equals("active")) active++;
                assertEquals(t.get(row, "status").equals("active"), t.get(row, "ended_at").isEmpty(), "активна ↔ без конца");
            }
            assertTrue(active <= 1, e.getKey() + ": не больше одной активной");
        }
    }

    @Test
    void consignments() throws IOException {
        PrepRuns.Run r = run();
        PrepRuns.Table c = PrepRuns.read(r.dir(), "consignments");
        PrepRuns.Table li = PrepRuns.read(r.dir(), "consignment_items");
        Map<Integer, Integer> lines = new HashMap<>();
        Map<Integer, List<String>> outcomesByItem = new HashMap<>();
        for (String[] row : li.rows()) {
            lines.merge((int) li.num(row, "consignment_id"), 1, Integer::sum);
            assertEquals(li.get(row, "ended_at").isEmpty(), li.get(row, "outcome").isEmpty(), "итог ↔ момент");
            assertTrue(li.num(row, "agreed_low") < li.num(row, "agreed_high"), "эстимейт при приёме");
            assertTrue(li.num(row, "agreed_low") >= 1_000_00L, "нижняя оценка от 1 000 ₽");
            outcomesByItem.computeIfAbsent((int) li.num(row, "item_id"), k -> new ArrayList<>())
                    .add(li.get(row, "consignment_id") + ":" + li.get(row, "outcome"));
        }
        long prev = Long.MIN_VALUE, resale = 0;
        Map<Integer, String> kind = new HashMap<>();
        for (String[] row : c.rows()) {
            int id = (int) c.num(row, "id");
            long signed = c.num(row, "signed_at");
            assertTrue(signed >= prev, "id — по моменту подписания");
            prev = signed;
            assertTrue(lines.getOrDefault(id, 0) >= 1, "в договоре — хотя бы одна вещь");
            LocalDate d = Period.localDate(signed, HouseRules.ZONE);
            assertEquals(d.plusMonths(12).toString(), c.get(row, "expires_on"), "срок — 12 месяцев");
            String rate = c.get(row, "commission_rate");
            if (!rate.isEmpty()) {
                double x = Double.parseDouble(rate);
                assertTrue(x >= 5 && x <= 10, "договорная ставка 5–10 %: " + x);
                assertEquals("f", c.get(row, "dealer_discount"), "скидка торговца — только по шкале");
            }
            kind.put(id, c.get(row, "kind"));
            if (c.get(row, "kind").equals("resale")) resale++;
        }
        assertTrue(c.rows().size() > 1_000, "договоров " + c.rows().size());
        // сдача обратно: вещь до того продана по другому договору
        for (Map.Entry<Integer, List<String>> e : outcomesByItem.entrySet()) {
            List<String> l = e.getValue();
            for (int i = 1; i < l.size(); i++) {
                int cid = Integer.parseInt(l.get(i).split(":")[0]);
                assertEquals("resale", kind.get(cid), "второй договор вещи — сдача обратно");
                assertTrue(l.get(i - 1).endsWith(":sold"), "до сдачи обратно вещь продана: " + l);
            }
        }
        assertTrue(resale > 0, "есть сдача купленного обратно");
    }

    @Test
    void salesAndSessions() throws IOException {
        PrepRuns.Run r = run();
        long cut = r.prep().view().slice();
        PrepRuns.Table s = PrepRuns.read(r.dir(), "sales");
        Map<String, Integer> status = new TreeMap<>();
        for (String[] row : s.rows()) {
            boolean timed = s.get(row, "format").equals("timed");
            assertEquals(timed, !s.get(row, "lots_per_group").isEmpty(), "волна — только у онлайн");
            assertEquals(!timed, !s.get(row, "preview_from").isEmpty(), "выставка — только у живых");
            String st = s.get(row, "status");
            status.merge(st, 1, Integer::sum);
            boolean pub = !s.get(row, "published_at").isEmpty();
            assertEquals(st.equals("scheduled"), !pub, "назначенные — без каталога");
            if (pub) assertTrue(s.num(row, "published_at") < cut);
            assertEquals(st.equals("closed"), !s.get(row, "ends_at").isEmpty(), "закрыты ↔ конец");
            if (st.equals("closed")) assertTrue(s.num(row, "ends_at") >= s.num(row, "starts_at") - 1, "конец после начала");
        }
        assertTrue(status.getOrDefault("closed", 0) > 800 && status.getOrDefault("scheduled", 0) > 50, "статусы " + status);
        PrepRuns.Table ss = PrepRuns.read(r.dir(), "sale_sessions");
        for (String[] row : ss.rows()) {
            long lots = ss.num(row, "last_lot_no") - ss.num(row, "first_lot_no") + 1;
            assertTrue(lots >= 1 && lots <= 250, "в сессии ≤ 250 лотов");
            int min = Period.localDateTime(ss.num(row, "starts_at"), HouseRules.ZONE).getHour();
            assertTrue(min == 12 || min == 16 || min == 19, "начало сессии 12:00, 16:00 или 19:00");
        }
    }
}
