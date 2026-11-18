package io.hammerhall.data.auc.people;

import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.AuctionWorld;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Horizon;
import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Модуль {@code clients}: таблица {@code clients} — из базы ({@link ClientBase}) и исхода торгов на срез
 * ({@link AuctionWorld#clientOutcome}: блокировка, расширенная проверка). Идёт после {@code auction}; без него (сборка
 * части модулей) поля «на „сейчас“» пусты. Имена, названия организаций и адреса почты — от ключа клиента.
 */
public final class ClientsModule implements Module {
    public static final String NAME = "clients";
    /** Считать коридоры участников года — у набора с настоящими торгами; у сборок-заглушек — нет. */
    private final boolean corridors;

    /** Без коридоров участников — для сборок без настоящих торгов (тесты модулей). */
    public ClientsModule() {
        this(false);
    }

    private ClientsModule(boolean corridors) {
        this.corridors = corridors;
    }

    /** С коридорами участников года (новые, возраст, торгов на участника, концентрация) — для набора. */
    public static ClientsModule withCorridors() {
        return new ClientsModule(true);
    }
    static final String[] DOMAINS = {"example.com", "example.org", "example.net"};
    static final String[] FIRM_KINDS = {"Галерея", "Антикварный салон", "Арт-салон", "Антикварная лавка", "Букинистический магазин"};
    static final String[] INSTITUTION_KINDS = {"Музей", "Библиотека", "Фонд", "Музей-заповедник"};

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return List.of(PeopleModule.NAME);
    }

    @Override
    public List<String> tables() {
        return List.of("clients");
    }

    /** Строка клиента без полей «на „сейчас“»: имя, отчество, фамилия, организация, пол, почта. */
    record Identity(String first, String patronymic, String last, String company, String gender, String email) {
    }

    static Identity identity(ClientBase base, int id, Rng r) {
        String domain = DOMAINS[r.nextInt(DOMAINS.length)];
        if (base.company(id)) {
            String[] kinds = base.institution(id) ? INSTITUTION_KINDS : FIRM_KINDS;
            String firm = Names.firm(r, kinds[r.nextInt(kinds.length)]);
            String local = (base.institution(id) ? "office" : "info") + "." + id;
            return new Identity(null, null, null, firm, null, local + "@" + domain);
        }
        Names.Person p = Names.person(r, r.chance(0.47), 0.8);
        String local = Translit.latin(p.last()) + "." + Translit.latin(p.first()).charAt(0) + id;
        return new Identity(p.first(), p.patronymic(), p.last(), null, p.female() ? "f" : "m",
                local.toLowerCase(Locale.ROOT) + "@" + domain);
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        ClientBase base = ctx.get(AucKeys.CLIENTS);
        AuctionWorld auction = ctx.has(AucKeys.AUCTION) ? ctx.get(AucKeys.AUCTION) : null;
        long cut = Horizon.cut(ctx.period());
        int n = base.countRegisteredBefore(cut);
        RandomStreams.Stream st = ctx.stream("clients.names");
        TableSink t = ctx.streamSink("clients");
        for (int id = 1; id <= n; id++) {
            Rng r = st.at(base instanceof ClientPopulation p ? p.key(id) : id);
            Identity who = identity(base, id, r);
            AuctionWorld.ClientOutcome o = auction == null ? AuctionWorld.ClientOutcome.NONE : auction.clientOutcome(id);
            long verified = base.verifiedAt(id);
            long blocked = o.blockedAt() != 0 && o.blockedAt() < cut ? o.blockedAt() : 0;
            String reason = blocked == 0 ? null : o.blockReason();
            long other = base.otherBlockAt(id);
            if (other != 0 && other < cut && (blocked == 0 || other < blocked)) {
                blocked = other;
                reason = base.otherBlockReason(id);
            }
            long enhanced = o.enhancedVerifiedAt() != 0 && o.enhancedVerifiedAt() < cut ? o.enhancedVerifiedAt() : 0;
            t.add(id, base.company(id) ? "company" : "person", who.first(), who.patronymic(), who.last(), who.company(),
                    who.gender(), base.company(id) ? null : base.birthYear(id), base.cityId(id), who.email(),
                    base.language(id), base.dealer(id), base.registeredAt(id),
                    verified == 0 || verified >= cut ? null : verified, enhanced == 0 ? null : enhanced,
                    base.marketingConsent(id), blocked == 0 ? null : blocked, reason);
        }
        t.close();
    }

    @Override
    public List<Aggregate> aggregates() {
        return List.of(
                Aggregate.countEq("clients", "kind", "company"),
                Aggregate.countEq("clients", "is_dealer", "t"),
                Aggregate.countEq("clients", "language", "en"),
                Aggregate.countEq("clients", "marketing_consent", "t"),
                Aggregate.countEq("clients", "block_reason", "non_payment"),
                Aggregate.countEq("clients", "block_reason", "shill_suspected"),
                Aggregate.countEq("clients", "block_reason", "own_request"),
                Aggregate.distinct("clients", "city_id"),
                Aggregate.min("clients", "registered_at"),
                Aggregate.max("clients", "registered_at"),
                Aggregate.sum("clients", "birth_year"));
    }

    @Override
    public void check(GenContext ctx, CheckReport report) {
        ClientBase base = ctx.get(AucKeys.CLIENTS);
        long cut = Horizon.cut(ctx.period());
        int n = base.countRegisteredBefore(cut);
        boolean sorted = true;
        for (int id = 2; id <= n; id++) sorted &= base.registeredAt(id) >= base.registeredAt(id - 1);
        report.expect("клиенты: id — по моменту регистрации", sorted, n + " клиентов");
        if (corridors) ParticipantCorridors.check(ctx, report);
    }
}
