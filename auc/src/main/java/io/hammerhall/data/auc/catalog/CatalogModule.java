package io.hammerhall.data.auc.catalog;

import io.hammerhall.data.auc.house.HouseModule;
import io.hammerhall.data.auc.people.PeopleModule;
import io.hammerhall.data.auc.world.AucKeys;
import io.hammerhall.data.auc.world.ClientBase;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Horizon;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.Intake;
import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.RandomStreams;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Модуль {@code catalog}: мастера, вещи, происхождение и поток приёма ({@link Intake}) в мир
 * ({@link AucKeys#CATALOG}). Поток — {@link IntakeFlow} до конца моделирования; в таблицы — вещи, принятые на осмотр
 * раньше среза, потоком по {@code id}. Тексты и справочник мастеров — пакет текстов ({@link VocabTexts}); уровень
 * рынка мастера A–D назначает этот модуль — от ключа мастера (A ≈ 3 %).
 */
public final class CatalogModule implements Module {
    public static final String NAME = "catalog";
    private final TextAudit audit = new TextAudit();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return List.of(HouseModule.NAME, PeopleModule.NAME);
    }

    @Override
    public List<String> tables() {
        return List.of("makers", "items", "provenance");
    }

    /** Уровень рынка мастеров по {@code id} (индекс 0 не используется): A 3 %, B 12 %, C 35 %, D 50 %. */
    static char[] tiers(RandomStreams streams, List<Texts.Maker> makers) {
        RandomStreams.Stream st = streams.stream("catalog.makers");
        char[] out = new char[makers.size() + 1];
        for (Texts.Maker m : makers) {
            double x = st.at(m.id(), 7).nextDouble();
            out[m.id()] = x < 0.03 ? 'A' : x < 0.15 ? 'B' : x < 0.50 ? 'C' : 'D';
        }
        return out;
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        HouseRules house = ctx.get(AucKeys.HOUSE);
        ClientBase clients = ctx.get(AucKeys.CLIENTS);
        Texts texts = new VocabTexts(ctx.streams());
        char[] tier = tiers(ctx.streams(), texts.makers());
        long cut = Horizon.cut(ctx.period());

        TableSink mk = ctx.sink("makers");
        for (Texts.Maker m : texts.makers()) {
            mk.add(m.id(), m.kind(), m.dept().id(), m.person(), m.nameRu(), m.nameEn(), m.firstName(), m.patronymic(),
                    m.lastName(), m.activeFrom(), m.activeTo(), m.country(), m.cityRu(), m.schoolRu(), m.specialtyRu(),
                    m.markRu(), String.valueOf(tier[m.id()]));
        }
        mk.close();

        TableSink items = ctx.streamSink("items");
        TableSink prov = ctx.streamSink("provenance");
        long[] written = new long[2];
        Texts.Maker[] byId = new Texts.Maker[texts.makers().size() + 1];
        for (Texts.Maker m : texts.makers()) byId[m.id()] = m;
        IntakeFlow flow = new IntakeFlow(ctx.streams(), house, clients, texts, tier, ctx.profile().scale(),
                Horizon.INTAKE_FROM, Horizon.endDay(ctx.period()), (it, t) -> {
                    if (it.receivedAt() >= cut) return;
                    items.add(it.id(), it.consignorId(), it.officeId(), it.specialistId(), it.receivedAt(), it.decidedAt(),
                            it.accepted(), it.declineReason(), it.dept().id(), t.categoryId(),
                            t.makerId() == 0 ? null : t.makerId(), t.attribution(), t.titleRu(), t.titleEn(),
                            t.descriptionRu(), t.conditionRu(), t.conditionGrade(), t.datingRu(), t.yearFrom(), t.yearTo(),
                            t.originPlaceRu(), t.materialRu(), t.techniqueRu(), t.heightMm(), t.widthMm(), t.depthMm(),
                            dec(t.diameterMm(), 1), dec(t.weightG(), 2), t.metalFineness(), t.pieces(), t.signed());
                    written[0]++;
                    audit.item(it.id(), t, t.makerId() == 0 ? null : byId[t.makerId()]);
                    int seq = 0;
                    for (Texts.Link l : t.provenance()) {
                        prov.add(it.id(), ++seq, l.kind(), l.textRu(), l.yearFrom(), l.yearTo(), l.placeRu(), l.ownerRu());
                        written[1]++;
                    }
                });
        items.close();
        prov.close();
        ctx.put(AucKeys.CATALOG, flow);
        ctx.log("приём: " + flow.size() + " вещей в потоке до " + flow.lastDay() + ", в выпуске " + written[0]
                + ", звеньев происхождения " + written[1] + ", мастеров " + texts.makers().size());
    }

    @Override
    public List<Aggregate> aggregates() {
        return List.of(
                Aggregate.countEq("items", "accepted", "f"),
                Aggregate.countEq("items", "attribution", "by"),
                Aggregate.countEq("items", "attribution", "unknown"),
                Aggregate.distinct("items", "consignor_id"),
                Aggregate.distinct("items", "maker_id"),
                Aggregate.min("items", "received_at"),
                Aggregate.max("items", "received_at"),
                Aggregate.sum("items", "pieces"),
                Aggregate.countEq("items", "office_id", "1"),
                Aggregate.countEq("makers", "tier", "A"),
                Aggregate.distinct("makers", "kind"),
                Aggregate.distinct("provenance", "item_id"),
                Aggregate.distinct("provenance", "kind"));
    }

    private static BigDecimal dec(Double v, int scale) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    @Override
    public void check(GenContext ctx, CheckReport report) {
        audit.report(report);
        HouseRules house = ctx.get(AucKeys.HOUSE);
        Intake in = ctx.get(AucKeys.CATALOG);
        boolean leaf = true, order = true, ready = true;
        int declined = 0;
        for (int id = 1; id <= in.size(); id++) {
            Intake.Item it = in.item(id);
            HouseRules.Category c = house.category(it.categoryId());
            leaf &= c.leaf() && c.dept() == it.dept();
            if (id > 1) order &= it.receivedAt() >= in.item(id - 1).receivedAt();
            ready &= it.decidedAt() > it.receivedAt()
                    && (it.accepted() ? it.readyAt() > it.decidedAt() : it.readyAt() == 0);
            if (!it.accepted()) declined++;
        }
        report.expect("вещи: категория — лист отдела вещи", leaf, in.size() + " вещей");
        report.expect("вещи: id — по моменту приёма", order, "");
        report.expect("вещи: приём → решение → готовность к каталогу", ready, "");
        double share = in.size() == 0 ? 0 : (double) declined / in.size();
        report.expect("вещи: не приняты ≈ 7 % (5–9 %)", share > 0.05 && share < 0.09, String.format(java.util.Locale.ROOT, "%.3f", share));
        // ready() отдаёт принятые вещи отдела по готовности
        boolean ok = true;
        for (Dept d : Dept.values()) {
            int[] ids = in.ready(d, Long.MIN_VALUE, Long.MAX_VALUE);
            for (int i = 1; i < ids.length; i++) ok &= in.readyAt(ids[i]) >= in.readyAt(ids[i - 1]);
        }
        report.expect("поток: готовые вещи отдела — по готовности", ok, "");
    }
}
