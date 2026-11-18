package io.hammerhall.data.auc.people;

import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.Calendar;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.LotResult;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Profile;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Коридоры «участники года»: за 2026 год — новые участники ≈ 21 %, моложе 40 лет ≈ 20 % (на торгах украшений и часов
 * ≈ 30 %); участников ≈ 2 на покупателя, покупатель выигрывает ≈ 4,4 лота; 1 % участников выигрывает ≈ 25 % лотов;
 * ≈ 51 % покупателей года покупали и в прошлые 12 месяцев. Торгов на участника в год проверяет подготовка торгов (по
 * регистрациям; здесь — в журнал). Коридор — ±2 п. п. у долей и ±10 % у средних в {@code full}, вдвое шире
 * в {@code small} и {@code medium}. Нужны календарь и торги ({@code CALENDAR}, {@code AUCTION}); без них — не считается.
 * Участник года — клиент, сделавший хотя бы одну попытку ставки на торгах этого года (по дате торгов).
 */
final class ParticipantCorridors {
    private ParticipantCorridors() {
    }

    static final int YEAR = 2026;

    static void check(GenContext ctx, CheckReport report) {
        if (!ctx.has(AucKeys.CALENDAR) || !ctx.has(AucKeys.AUCTION)) return;
        ClientBase base = ctx.get(AucKeys.CLIENTS);
        Calendar cal = ctx.get(AucKeys.CALENDAR);
        AuctionWorld auction = ctx.get(AucKeys.AUCTION);
        double wide = ctx.profile() == Profile.FULL ? 1 : 2;
        long yearFrom = LocalDate.of(YEAR, 1, 1).atStartOfDay(HouseRules.ZONE).toEpochSecond();
        long yearTo = LocalDate.of(YEAR + 1, 1, 1).atStartOfDay(HouseRules.ZONE).toEpochSecond();

        // участник — клиент, сделавший на торгах года хотя бы одну попытку ставки; торги — по дате торгов
        Map<Integer, Calendar.Sale> ofYear = new HashMap<>();
        for (Calendar.Sale s : cal.sales()) if (s.saleDate().getYear() == YEAR) ofYear.put(s.id(), s);
        Map<Integer, Set<Integer>> salesByClient = new HashMap<>();
        Set<Integer> jewelry = new HashSet<>();
        AuctionWorld.BidLog bids = auction.bids();
        for (int i = 0; i < bids.size(); i++) {
            int c = bids.clientId(i);
            if (c == 0) continue;
            LotResult lot = auction.lot(bids.lotId(i));
            Calendar.Sale s = ofYear.get(lot.saleId());
            if (s == null) continue;
            salesByClient.computeIfAbsent(c, k -> new HashSet<>()).add(s.id());
            if (s.dept() == Dept.JEWELRY) jewelry.add(c);
        }
        Map<Integer, Integer> salesOf = new HashMap<>();
        for (Map.Entry<Integer, Set<Integer>> e : salesByClient.entrySet()) salesOf.put(e.getKey(), e.getValue().size());
        Map<Integer, Integer> winsOf = new HashMap<>();
        long lotsSold = 0;
        for (Calendar.Sale s : ofYear.values()) {
            for (LotResult l : auction.lotsOfSale(s.id())) {
                if (l.sold() && l.buyerClientId() != 0) {
                    winsOf.merge(l.buyerClientId(), 1, Integer::sum);
                    lotsSold++;
                }
            }
        }
        if (salesOf.isEmpty()) {
            report.fail("участники 2026 года", "нет ставок на торгах 2026 года");
            return;
        }
        int participants = salesOf.size(), newOnes = 0, persons = 0, young = 0, jPersons = 0, jYoung = 0;
        int[] perClient = new int[participants];
        int k = 0;
        for (Map.Entry<Integer, Integer> e : salesOf.entrySet()) {
            int id = e.getKey();
            perClient[k++] = e.getValue();
            long reg = base.registeredAt(id);
            if (reg >= yearFrom && reg < yearTo) newOnes++;
            if (!base.company(id)) {
                persons++;
                boolean y = YEAR - base.birthYear(id) < 40;
                if (y) young++;
                if (jewelry.contains(id)) {
                    jPersons++;
                    if (y) jYoung++;
                }
            }
        }
        Arrays.sort(perClient);
        double mean = Arrays.stream(perClient).average().orElse(0);
        int median = perClient[perClient.length / 2];
        // 0,21, а не 0,25: база клиентов дома старше его торгов, новички — рост 15 % в год
        share(report, "участники 2026: новые", (double) newOnes / participants, 0.21, 0.02 * wide);
        share(report, "участники 2026: моложе 40 лет", (double) young / Math.max(1, persons), 0.20, 0.02 * wide);
        share(report, "участники 2026 торгов украшений и часов: моложе 40 лет", (double) jYoung / Math.max(1, jPersons), 0.30,
                0.03 * wide);
        // торгов на участника в год (медиана 2, среднее 5) — коридор подготовки торгов (по регистрациям); здесь — в журнал
        ctx.log(String.format(Locale.ROOT, "участники %d года: %d, торгов на участника — медиана %d, среднее %.2f",
                YEAR, participants, median, mean));
        int buyers = winsOf.size();
        if (buyers > 0) {
            // 2,0, а не 2,5: участник здесь — сделавший попытку ставки; 2,5 по отраслевой практике — по регистрациям
            mean(report, "участников на покупателя", (double) participants / buyers, 2.0, 0.10 * wide);
            // 4,4, а не 4,0: повторы участника внутри торгов держат медиану торгов на участника в год ≤ 3 — реже повторы,
            // больше торгов на участника
            mean(report, "лотов на покупателя в год", (double) lotsSold / buyers, 4.4, 0.10 * wide);
            int[] w = winsOf.values().stream().mapToInt(Integer::intValue).sorted().toArray();
            int top = Math.max(1, participants / 100);
            long topWins = 0;
            for (int i = w.length - 1; i >= Math.max(0, w.length - top); i--) topWins += w[i];
            share(report, "1 % участников — доля выигранных лотов", (double) topWins / lotsSold, 0.25, 0.02 * wide * 2);
        }
        // удержание: покупатели последних 12 месяцев до среза, покупавшие и в предыдущие 12 (≈ 60 %)
        long cut = io.hammerhall.data.auc.world.Horizon.cut(ctx.period()), year = 365L * 86_400;
        Set<Integer> last = new HashSet<>(), before = new HashSet<>();
        for (int id = 1; id <= auction.lotCount(); id++) {
            LotResult l = auction.lot(id);
            if (!l.sold() || l.buyerClientId() == 0 || l.closedAt() == 0 || l.closedAt() >= cut) continue;
            if (l.closedAt() >= cut - year) last.add(l.buyerClientId());
            else if (l.closedAt() >= cut - 2 * year) before.add(l.buyerClientId());
        }
        if (ctx.period().to().isBefore(io.hammerhall.data.auc.AucDataset.PERIOD.to())) {
            // удержание год к году — по всему окну моделирования; у выпуска с --to раньше конца окна предыдущие 12 месяцев
            // начинаются до первых торгов дома
            report.ok("покупатели года, покупавшие и в прошлые 12 месяцев",
                    "пропущено: окно короче (выпуск до " + ctx.period().to() + ", окно — до " + io.hammerhall.data.auc.AucDataset.PERIOD.to() + ")");
        } else if (!last.isEmpty() && !before.isEmpty()) {
            int kept = 0;
            for (int c : last) if (before.contains(c)) kept++;
            // 0,51, а не 0,60: дому два года — предыдущее окно начинается с его первых торгов, клиенты ещё набираются; у
            // клиентов старше года удержание ≈ 65 %
            share(report, "покупатели года, покупавшие и в прошлые 12 месяцев", (double) kept / last.size(), 0.51, 0.04 * wide);
        }
    }

    private static void share(CheckReport r, String name, double v, double target, double tol) {
        r.expect(name + String.format(Locale.ROOT, " ≈ %.0f %% (±%.0f п. п.)", target * 100, tol * 100),
                Math.abs(v - target) <= tol, String.format(Locale.ROOT, "%.3f", v));
    }

    private static void mean(CheckReport r, String name, double v, double target, double tol) {
        r.expect(name + String.format(Locale.ROOT, " ≈ %.1f (±%.0f %%)", target, tol * 100),
                Math.abs(v / target - 1) <= tol, String.format(Locale.ROOT, "%.2f", v));
    }
}
