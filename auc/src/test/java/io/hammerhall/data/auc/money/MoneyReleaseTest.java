package io.hammerhall.data.auc.money;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.Cli;
import io.hammerhall.data.core.gen.Release;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Деньги целиком на упрощённых торгах ({@link MoneyStubDataset}, профиль {@code small}): самопроверка проходит, и — по
 * готовым TSV, независимо от генератора — выплата комитенту не раньше полной оплаты (кроме заложенных), у депозита
 * ровно одна судьба, заложенное на месте. Папка выпуска — {@code -Dmoney.release.dir} (для {@code tools/pg-check.sh}),
 * иначе временная.
 */
class MoneyReleaseTest {
    private static Path dir;
    private static Release.Result result;

    @BeforeAll
    static void run() throws IOException {
        String d = System.getProperty("money.release.dir");
        dir = d != null ? Path.of(d) : Files.createTempDirectory("auc-money-");
        result = Cli.run(new MoneyStubDataset(), Map.of("profile", "small", "out", dir.toString()));
    }

    @Test
    void verifyPasses() {
        StringBuilder bad = new StringBuilder();
        for (CheckReport.Result r : result.report().results()) if (!r.ok()) bad.append(r.name()).append(" — ").append(r.detail()).append('\n');
        assertTrue(result.ok(), "самопроверка:\n" + bad);
        Map<String, Long> p = result.planted();
        assertTrue(p.getOrDefault("premium_old_schedule", 0L) > 0, "заложено: старая шкала " + p);
    }

    static List<Map<String, String>> read(String table) throws IOException {
        return read(dir, table);
    }

    static List<Map<String, String>> read(Path dir, String table) throws IOException {
        List<Map<String, String>> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(dir.resolve(table + ".tsv.gz"))), StandardCharsets.UTF_8))) {
            String[] head = r.readLine().split("\t", -1);
            for (String line; (line = r.readLine()) != null; ) {
                String[] v = line.split("\t", -1);
                Map<String, String> m = new HashMap<>();
                for (int i = 0; i < head.length; i++) m.put(head[i], v[i]);
                out.add(m);
            }
        }
        return out;
    }

    @Test
    void payoutNotBeforePayment() throws IOException {
        // лот → момент полной оплаты по счёту покупателя (строки счетов sale/reissue/after_sale/second_chance)
        Map<String, Long> invPaid = new HashMap<>();
        Map<String, String> invKind = new HashMap<>();
        for (Map<String, String> i : read("invoices")) {
            invKind.put(i.get("id"), i.get("kind"));
            if (!i.get("paid_at").isEmpty()) invPaid.put(i.get("id"), Long.parseLong(i.get("paid_at")));
        }
        Map<String, Map<String, Long>> lotPaid = new HashMap<>();
        for (Map<String, String> l : read("invoice_lines")) {
            Long p = invPaid.get(l.get("invoice_id"));
            if (p == null) continue;
            String kind = invKind.get(l.get("invoice_id"));
            lotPaid.computeIfAbsent(l.get("lot_id"), k -> new HashMap<>()).put(kind.equals("reissue") ? "sale" : kind, p);
        }
        int checked = 0, early = 0;
        for (Map<String, String> s : read("settlement_lines")) {
            if (s.get("paid_at").isEmpty() || Long.parseLong(s.get("net")
                    .replace(".", "")) <= 0) continue;
            String kind = switch (s.get("outcome")) {
                case "sold" -> "sale";
                case "sold_after_sale" -> "after_sale";
                case "second_chance" -> "second_chance";
                default -> null;
            };
            if (kind == null) continue;
            Long buyer = lotPaid.getOrDefault(s.get("lot_id"), Map.of()).get(kind);
            checked++;
            if (buyer == null || Long.parseLong(s.get("paid_at")) < buyer) early++;
        }
        assertTrue(checked > 50, "выплаченных строк: " + checked);
        assertEquals((long) result.planted().getOrDefault("payout_before_payment", 0L), early,
                "раньше оплаты — ровно заложенные");
    }

    @Test
    void depositHasExactlyOneFate() throws IOException {
        Map<String, Integer> fates = new TreeMap<>();
        Map<String, Long> paidIn = new TreeMap<>();
        for (Map<String, String> p : read("payments")) {
            String reg = p.get("registration_id");
            switch (p.get("kind")) {
                case "deposit" -> paidIn.put(reg, Long.parseLong(p.get("at")));
                case "deposit_applied", "deposit_refund", "deposit_forfeit" -> fates.merge(reg, 1, Integer::sum);
                default -> {
                }
            }
        }
        assertTrue(!paidIn.isEmpty(), "депозитов нет — в упрощённых торгах нет дорогих лотов?");
        long cut = 1_819_756_800L; // 2027-09-01T00:00Z
        for (var e : paidIn.entrySet()) {
            int f = fates.getOrDefault(e.getKey(), 0);
            assertTrue(f <= 1, "регистрация " + e.getKey() + ": судеб " + f);
            if (e.getValue() < cut - 100 * 86_400L) assertEquals(1, f, "регистрация " + e.getKey() + ": судьбы нет");
        }
        for (String reg : fates.keySet()) assertTrue(paidIn.containsKey(reg), "судьба без депозита: " + reg);
    }

    /**
     * Продление: короткий прогон ({@code --to 2027-03-31}) — начало длинного. Платежи — строка в строку;
     * счета, их строки, отчёты и строки отчётов есть в длинном по ключу с теми же полями, кроме состояния на срез
     * (строки отчётов комитентам — на срез); строки второго шанса дописываются в хвост отчёта.
     */
    @Test
    void extendingThePeriodKeepsHistory() throws IOException {
        Path shortDir = Files.createTempDirectory("auc-money-short-");
        Release.Result r = Cli.run(new MoneyStubDataset(), Map.of("profile", "small", "out", shortDir.toString(),
                "to", "2027-03-31"));
        assertTrue(r.ok(), "короткий прогон — самопроверка");
        List<Map<String, String>> ps = read(shortDir, "payments"), pl = read("payments");
        assertTrue(ps.size() > 100 && ps.size() < pl.size(), "платежей: " + ps.size() + " / " + pl.size());
        for (int i = 0; i < ps.size(); i++) assertEquals(pl.get(i), ps.get(i), "платёж " + (i + 1));
        same("invoices", List.of("id"), List.of("status", "late_fee", "paid_total", "paid_at", "cancelled_at", "cancel_reason"),
                shortDir);
        same("invoice_lines", List.of("invoice_id", "line_no"), List.of(), shortDir);
        same("settlements", List.of("id"), List.of("hammer_total", "commission_total", "fees_total", "vat_total", "net_total",
                "paid_total", "status"), shortDir);
        int changed = same("settlement_lines", List.of("settlement_id", "line_no"), List.of("outcome", "price", "commission",
                "insurance_fee", "catalogue_fee", "withdrawal_fee", "vat", "net", "payable_at", "paid_at"), shortDir);
        System.out.println("продление: строк отчётов «в пути» на коротком срезе — " + changed);
    }

    /** Каждая строка короткого — в длинном по ключу, поля те же, кроме {@code free}; возвращает число строк «в пути». */
    private static int same(String table, List<String> key, List<String> free, Path shortDir) throws IOException {
        Map<String, Map<String, String>> longRows = new HashMap<>();
        for (Map<String, String> m : read(table)) longRows.put(keyOf(m, key), m);
        int changed = 0;
        for (Map<String, String> m : read(shortDir, table)) {
            Map<String, String> l = longRows.get(keyOf(m, key));
            assertTrue(l != null, table + ": нет строки " + keyOf(m, key) + " в длинном прогоне");
            boolean moved = false;
            for (String c : m.keySet()) {
                if (free.contains(c)) {
                    if (!m.get(c).equals(l.get(c))) moved = true;
                    continue;
                }
                assertEquals(m.get(c), l.get(c), table + " " + keyOf(m, key) + "." + c);
            }
            if (moved) changed++;
        }
        return changed;
    }

    private static String keyOf(Map<String, String> m, List<String> key) {
        StringBuilder sb = new StringBuilder();
        for (String k : key) sb.append(m.get(k)).append('/');
        return sb.toString();
    }
}
