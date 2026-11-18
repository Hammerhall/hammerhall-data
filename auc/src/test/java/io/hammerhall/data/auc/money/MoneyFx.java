package io.hammerhall.data.auc.money;

import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.Format;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.InvoiceKind;
import io.hammerhall.data.auc.world.Keys;
import io.hammerhall.data.auc.world.PaymentPlanner.InvoiceDraft;
import io.hammerhall.data.auc.world.PaymentPlanner.Line;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Общее тестов денег: правила дома без справочников (шкалы и сборы — стандартные), черновики счетов. */
final class MoneyFx {
    private MoneyFx() {
    }

    static HouseRules house() {
        List<HouseRules.Category> cats = new ArrayList<>();
        for (Dept d : Dept.values()) {
            cats.add(new HouseRules.Category(d.id(), d, 0, 1, d.code(), d.code(), d.code(), true));
        }
        return new HouseRules(HouseRules.standardDepartments(), cats, List.of(), List.of(), List.of(),
                HouseRules.standardIncrements(), HouseRules.standardFees());
    }

    static long rub(long r) {
        return r * 100;
    }

    /** Момент по Москве. */
    static long msk(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(HouseRules.ZONE).toEpochSecond();
    }

    static InvoiceDraft draft(int saleId, int clientId, InvoiceKind kind, Format f, Dept dept, LocalDate saleDate,
                              long issuedAt, boolean newClient, long depositKop, long... pricesKop) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < pricesKop.length; i++) lines.add(new Line(Keys.lot(saleId, i + 1, 0), pricesKop[i], false));
        return new InvoiceDraft(Keys.invoice(saleId, clientId, kind, 0), kind, saleId, f, dept, saleDate,
                Keys.registration(saleId, clientId), clientId, newClient, issuedAt,
                PaymentPlanner.dueAt(issuedAt, 10), lines, depositKop, 0);
    }
}
