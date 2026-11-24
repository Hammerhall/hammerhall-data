package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.Planted;
import io.hammerhall.data.core.gen.Release;
import io.hammerhall.data.core.io.GzipTsvFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Самопроверка ловит порчу: у готового {@code small} (выдуманные фильмы) портится одна таблица — проваливается ровно
 * нужная проверка (прочие могут тоже, но эта — обязательно). Нетронутый выпуск — все проверки проходят.
 */
class SiteAuditTest {
    static Path root, base;
    static SiteAudit.Inputs inputs;
    static Planted planted;
    static int copies;

    @BeforeAll
    static void build() throws IOException {
        root = Files.createTempDirectory("mov-site-audit-");
        base = root.resolve("base");
        SiteOnlyDataset d = new SiteOnlyDataset(StubInput.build(11, false));
        Release.Result r = SiteOnlyDataset.run(d, "small", base, null);
        assertTrue(r.ok(), SiteReleaseTest.failures(r));
        inputs = d.site.auditInputs();
        planted = r.context().planted();
    }

    @AfterAll
    static void clean() throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    // ---------------------------------------------------------------- таблицы как строки

    /** Таблица: заголовок и строки полями. */
    static final class T {
        final String[] head;
        final List<String[]> rows = new ArrayList<>();

        T(String[] head) {
            this.head = head;
        }

        int col(String name) {
            for (int i = 0; i < head.length; i++) if (head[i].equals(name)) return i;
            throw new IllegalArgumentException(name);
        }

        String[] find(java.util.function.Predicate<String[]> p) {
            for (String[] r : rows) if (p.test(r)) return r;
            throw new IllegalStateException("нет строки");
        }
    }

    static T read(Path dir, String table) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(dir.resolve(table + ".tsv.gz"))),
                StandardCharsets.UTF_8))) {
            T t = new T(r.readLine().split("\t", -1));
            for (String s; (s = r.readLine()) != null; ) t.rows.add(s.split("\t", -1));
            return t;
        }
    }

    static void write(Path dir, String table, T t) throws IOException {
        try (GzipTsvFile f = new GzipTsvFile(dir.resolve(table + ".tsv.gz"), 1)) {
            f.line(String.join("\t", t.head));
            for (String[] r : t.rows) f.line(String.join("\t", r));
        }
    }

    /** Копия выпуска с испорченной таблицей. */
    static Path corrupt(String table, Consumer<T> change) throws IOException {
        Path dir = root.resolve("c" + (++copies));
        Files.createDirectories(dir);
        try (Stream<Path> s = Files.list(base)) {
            for (Path p : s.toList()) Files.copy(p, dir.resolve(p.getFileName()));
        }
        T t = read(dir, table);
        change.accept(t);
        write(dir, table, t);
        return dir;
    }

    static CheckReport audit(Path dir) throws IOException {
        CheckReport r = new CheckReport();
        SiteAudit.run(dir, inputs, planted, r, s -> s.toLowerCase(java.util.Locale.ROOT).contains("qwex"));
        return r;
    }

    static void fails(Path dir, String check) throws IOException {
        CheckReport r = audit(dir);
        for (CheckReport.Result x : r.results()) {
            if (x.name().equals(check)) {
                assertFalse(x.ok(), check + " прошла на испорченном: " + x.detail());
                return;
            }
        }
        fail("нет проверки «" + check + "»");
    }

    static T base(String table) throws IOException {
        return read(base, table);
    }

    // ---------------------------------------------------------------- проверки

    @Test
    @DisplayName("Нетронутый выпуск: все проверки site проходят")
    void cleanPasses() throws IOException {
        CheckReport r = audit(base);
        StringBuilder bad = new StringBuilder();
        for (CheckReport.Result x : r.results()) if (!x.ok()) bad.append(x.name()).append(" — ").append(x.detail()).append('\n');
        assertTrue(r.allOk(), bad.toString());
        assertTrue(r.results().size() >= 40, "проверок " + r.results().size());
    }

    @Test
    void ddlChecks() throws IOException {
        fails(corrupt("ratings", t -> t.rows.getFirst()[t.col("rating")] = "11"), "site: ограничения DDL (CHECK) соблюдены");
        fails(corrupt("follows", t -> t.rows.getFirst()[t.col("followee_id")] = t.rows.getFirst()[t.col("follower_id")]),
                "site: ограничения DDL (CHECK) соблюдены");
    }

    @Test
    void users() throws IOException {
        fails(corrupt("users", t -> t.rows.get(3)[t.col("city_id")] = "999999999"), "site: город пользователя — из справочника городов World");
        fails(corrupt("users", t -> t.rows.get(5)[t.col("registered_at")] = "1000"), "site: пользователи — по порядку регистрации, пригласивший — раньше");
        fails(corrupt("users", t -> {
            String[] u = null;
            for (String[] r : t.rows) if (!r[t.col("promo_code_id")].isEmpty()) u = r;
            u[t.col("promo_code_id")] = "1";
        }), "site: промокод при регистрации — действовал, вид по каналу, не сверх предела");
        fails(corrupt("users", t -> t.rows.get(7)[t.col("login")] = "ab"), "site: логин — латиница, цифры, «_», 4–20 знаков");
        fails(corrupt("users", t -> t.rows.get(9)[t.col("birth_year")] = "2024"), "site: возраст пользователя при регистрации — 16–75 лет");
        fails(corrupt("devices", t -> t.rows.get(10)[t.col("first_seen_at")] = "1000"),
                "site: устройство — после регистрации пользователя, id по первому появлению");
    }

    @Test
    void subscriptions() throws IOException {
        T s = base("subscriptions");
        Map<String, List<String[]>> byUser = new HashMap<>();
        for (String[] r : s.rows) byUser.computeIfAbsent(r[1], k -> new ArrayList<>()).add(r);
        String[] second = null;
        for (List<String[]> l : byUser.values()) if (l.size() >= 2 && second == null) second = l.get(1);
        String id2 = second[0];
        String[] first = byUser.get(second[1]).getFirst();
        String start = first[3];
        fails(corrupt("subscriptions", t -> t.find(r -> r[0].equals(id2))[3] = String.valueOf(Long.parseLong(start) + 60)),
                "site: подписки пользователя не пересекаются");
        fails(corrupt("subscriptions", t -> {
            String[] r = t.find(x -> !x[4].isEmpty());
            r[4] = String.valueOf(Long.parseLong(r[3]) + 10 * 86400);
        }), "site: подписка — пробный 7 или 14 дней, состояние на срез согласовано с моментами");
        fails(corrupt("subscriptions", t -> t.find(x -> x[6].equals("cancelled"))[7] = "t"),
                "site: подписка — пробный 7 или 14 дней, состояние на срез согласовано с моментами");
    }

    @Test
    void payments() throws IOException {
        T p = base("payments");
        fails(corrupt("payments", t -> {
            String[] r = t.find(x -> !x[2].isEmpty() && x[6].equals("succeeded"));
            r[7] = "1000";
        }), "site: оплата подписки — её пользователя, внутри её периода");
        fails(corrupt("payments", t -> {
            String[] r = t.find(x -> !x[2].isEmpty() && x[6].equals("succeeded"));
            r[4] = new java.math.BigDecimal(r[4]).add(java.math.BigDecimal.ONE).setScale(2).toPlainString();
        }), "site: сумма списания = цена тарифа на дату (со скидкой промокода на первый период)");
        // повтор после неудачи — не через сутки
        String failedSub = p.find(x -> !x[2].isEmpty() && x[6].equals("failed"))[2];
        long failedAt = Long.parseLong(p.find(x -> x[2].equals(failedSub) && x[6].equals("failed"))[7]);
        fails(corrupt("payments", t -> {
            for (String[] r : t.rows) {
                long at = Long.parseLong(r[7]);
                if (r[2].equals(failedSub) && at > failedAt && at < failedAt + 4 * 86400) r[7] = String.valueOf(at + 7200);
            }
        }), "site: неудачное списание — повтор через 1 и 3 дня, три неудачи — подписка истекает");
        // вторая оплата того же периода
        fails(corrupt("payments", t -> {
            String[] r = t.find(x -> !x[2].isEmpty() && x[6].equals("succeeded"));
            String[] c = r.clone();
            c[0] = String.valueOf(t.rows.size() + 1);
            c[7] = String.valueOf(Long.parseLong(r[7]) + 30);
            t.rows.add(c);
        }), "site: за период подписки — одна оплата (кроме заложенных двойных списаний)");
        fails(corrupt("payments", t -> {
            String[] r = t.find(x -> !x[3].isEmpty());
            r[4] = new java.math.BigDecimal(r[4]).add(java.math.BigDecimal.TEN).setScale(2).toPlainString();
        }), "site: оплата покупки — её сумма, пользователь и момент, одна на покупку");
    }

    @Test
    void catalogAndPurchases() throws IOException {
        fails(corrupt("catalog", t -> {
            String[] r = t.find(x -> x[3].equals("rent_buy"));
            r[6] = String.valueOf(Long.parseLong(r[1]) + 10 * 86400);
        }), "site: каталог — фильмы профиля, окна не пересекаются, у проката — цены и вход в подписку через 60–120 дней");
        fails(corrupt("purchases", t -> {
            String[] r = t.rows.getFirst();
            r[4] = new java.math.BigDecimal(r[4]).add(java.math.BigDecimal.TEN).setScale(2).toPlainString();
        }), "site: покупка — фильм в прокате, цена окна (премьерная — первые 60 дней), прокат — 48 ч");
    }

    @Test
    void views() throws IOException {
        Set<String> inCatalog = new HashSet<>();
        for (String[] r : base("catalog").rows) inCatalog.add(r[0]);
        String notInCatalog = null;
        for (SiteInput.Movie m : inputs.in().movies()) {
            if (!inCatalog.contains(String.valueOf(m.id()))) {
                notInCatalog = String.valueOf(m.id());
                break;
            }
        }
        String nic = notInCatalog;
        fails(corrupt("views", t -> t.find(x -> !planted.has("rent_grace", Long.parseLong(x[0])))[3] = nic),
                "site: просмотр — фильм в каталоге в момент начала (кроме льготы проката)");
        fails(corrupt("views", t -> t.find(x -> x[8].equals("free"))[8] = "buy"),
                "site: просмотр — доступ был (подписка, прокат, покупка, бесплатно), качество — по тарифу");
        fails(corrupt("views", t -> {
            String[] r = t.find(x -> !planted.has("long_view", Long.parseLong(x[0])));
            r[5] = "100000";
        }), "site: просмотр — не дольше хронометража + 30 % (кроме паузы)");
        T dev = base("devices");
        fails(corrupt("views", t -> {
            String[] r = t.rows.get(20);
            r[2] = dev.find(x -> !x[1].equals(r[1]))[0];
        }), "site: просмотр — устройство пользователя, пользователь зарегистрирован и не удалён");
        fails(corrupt("views", t -> {
            String[] r = t.find(x -> x[8].equals("subscription") && Integer.parseInt(x[5]) > 30);
            String[] c = r.clone();
            c[0] = String.valueOf(t.rows.size() + 1);
            c[4] = String.valueOf(Long.parseLong(r[4]) + 120);
            t.rows.add(c);
        }), "site: одновременных просмотров по подписке — не больше потоков тарифа, на устройстве — один");
    }

    @Test
    void community() throws IOException {
        fails(corrupt("ratings", t -> {
            String[] r = t.find(x -> !planted.has("rating_without_view", (Long.parseLong(x[0]) << 32) | Long.parseLong(x[1])));
            r[3] = String.valueOf(inputs.w0() + 1);
        }), "site: оценка — после просмотра фильма (кроме «смотрел раньше»)");
        fails(corrupt("reviews", t -> {
            String[] r = t.find(x -> !planted.has("review_rating_changed", Long.parseLong(x[0])));
            r[3] = String.valueOf(Integer.parseInt(r[3]) % 10 + 1);
        }), "site: рецензия — есть оценка фильма, оценка та же (кроме изменённой позже)");
        String author = base("reviews").find(x -> x[8].equals("published"))[1];
        fails(corrupt("users", t -> {
            String[] u = t.find(x -> x[0].equals(author));
            u[t.col("deleted_at")] = String.valueOf(inputs.cutoff() - 3600);
        }), "site: рецензии удалённого пользователя — скрыты");
        T rv = base("reviews");
        fails(corrupt("review_votes", t -> {
            String[] v = t.rows.getFirst();
            v[1] = rv.find(x -> x[0].equals(v[0]))[1];
        }), "site: голос — не за свою рецензию, после неё, голосующий зарегистрирован");
        fails(corrupt("follows", t -> t.rows.getFirst()[2] = "1000"),
                "site: подписка на людей — не на себя, после регистрации обоих, отписка позже");
        fails(corrupt("watchlist", t -> {
            String[] r = t.find(x -> x[4].equals("watched"));
            r[3] = String.valueOf(Long.parseLong(r[3]) + 1);
        }), "site: список «посмотреть» — после регистрации, «посмотрел» — в момент просмотра");
        fails(corrupt("collection_items", t -> t.rows.getFirst()[2] = "99"),
                "site: подборки и теги — после регистрации, фильмы подборки — после её создания, места 1…n по порядку");
    }

    @Test
    void timeAndTexts() throws IOException {
        fails(corrupt("ratings", t -> t.rows.getFirst()[3] = String.valueOf(inputs.cutoff() + 100)),
                "site: нет событий после среза, события — внутри окна (предыстория — у пользователей, устройств, подписок, каталога)");
        fails(corrupt("views", t -> t.rows.getFirst()[4] = String.valueOf(inputs.w0() - 86400)),
                "site: нет событий после среза, события — внутри окна (предыстория — у пользователей, устройств, подписок, каталога)");
        fails(corrupt("reviews", t -> t.rows.getFirst()[5] = t.rows.getFirst()[5] + " Qwex."),
                "site: тексты (логины, имена, рецензии, подборки, теги) — без внутренних имён платформы");
        fails(corrupt("reviews", t -> t.rows.get(1)[5] = t.rows.getFirst()[5]), "site: рецензии не повторяются целиком");
        fails(corrupt("reviews", t -> t.rows.getFirst()[4] = "Да"), "site: рецензия — заголовок 3–8 слов, текст 2–6 предложений");
    }

    @Test
    void patterns() throws IOException {
        fails(corrupt("views", t -> {
            for (String[] r : t.rows) r[4] = String.valueOf(Long.parseLong(r[4]) - 10 * 3600);
        }), "site: узор — просмотры вечером (19–23 ч по поясу города)");
        fails(corrupt("ratings", t -> {
            for (String[] r : t.rows) r[2] = "5";
        }), "site: узор — оценки J-образные: доля 8–10");
        fails(corrupt("payments", t -> {
            for (String[] r : t.rows) if (!r[2].isEmpty() && r[6].equals("succeeded")) r[6] = "failed";
        }), "site: узор — пробный период → платная подписка");
        fails(corrupt("watchlist", t -> {
            for (String[] r : t.rows) if (r[4].equals("watched")) r[4] = "dropped";
        }), "site: узор — из списка «посмотреть» потом смотрят");
    }
}
