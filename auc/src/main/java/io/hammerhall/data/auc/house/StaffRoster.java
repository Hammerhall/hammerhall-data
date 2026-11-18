package io.hammerhall.data.auc.house;

import io.hammerhall.data.auc.people.Names;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.auc.world.StaffRole;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Штат дома (≈ 230 строк {@code staff}): места по офисам, ролям и отделам; на каждом месте — нынешний сотрудник и
 * изредка предшественник, ушедший до периода. Штат — постоянный список от {@code seed} (не от периода): внутри
 * периода только принимают (новые приёмные, рост штата Москвы), никто не уходит — так строки при продлении не
 * меняются, а принятые после среза просто не попадают в выпуск ({@code id} — по дате приёма, они в хвосте).
 */
final class StaffRoster {
    private StaffRoster() {
    }

    /** Место в штате: офис, роль, отдел (или {@code null}), номер места; {@code growthFrom} — место открыто внутри периода. */
    private record Seat(int officeId, StaffRole role, Dept dept, int no, LocalDate growthFrom) {
        long key() {
            return (long) officeId << 40 | (long) role.ordinal() << 32 | (long) (dept == null ? 0 : dept.id()) << 16 | no;
        }
    }

    private record Draft(Seat seat, int gen, Names.Person person, LocalDate hired, LocalDate left) {
    }

    static List<HouseRules.Staff> build(RandomStreams streams, List<HouseRules.Office> offices) {
        List<Seat> seats = new ArrayList<>();
        for (HouseRules.Office o : offices) {
            switch (o.code()) {
                case "msk" -> {
                    for (Dept d : Dept.values()) {
                        seats.add(new Seat(o.id(), StaffRole.HEAD_OF_DEPARTMENT, d, 1, null));
                        for (int i = 1; i <= 8; i++) seats.add(new Seat(o.id(), StaffRole.SPECIALIST, d, i, null));
                        // рост штата: девятый специалист отдела — с весны 2026 или 2027 года
                        seats.add(new Seat(o.id(), StaffRole.SPECIALIST, d, 9,
                                LocalDate.of(2026 + d.ordinal() % 2, 2 + d.ordinal(), 1)));
                    }
                    addSeats(seats, o.id(), StaffRole.AUCTIONEER, 6);
                    addSeats(seats, o.id(), StaffRole.PHONE_CLERK, 20);
                    addSeats(seats, o.id(), StaffRole.CLIENT_SERVICE, 16);
                    addSeats(seats, o.id(), StaffRole.ACCOUNTANT, 10);
                    addSeats(seats, o.id(), StaffRole.ADMINISTRATOR, 8);
                }
                case "spb" -> {
                    for (Dept d : Dept.values()) {
                        for (int i = 1; i <= 4; i++) seats.add(new Seat(o.id(), StaffRole.SPECIALIST, d, i, null));
                    }
                    addSeats(seats, o.id(), StaffRole.AUCTIONEER, 4);
                    addSeats(seats, o.id(), StaffRole.PHONE_CLERK, 10);
                    addSeats(seats, o.id(), StaffRole.CLIENT_SERVICE, 8);
                    addSeats(seats, o.id(), StaffRole.ACCOUNTANT, 3);
                    addSeats(seats, o.id(), StaffRole.ADMINISTRATOR, 3);
                }
                default -> {
                    Dept[] cycle = {Dept.PAINTING, Dept.JEWELRY, Dept.DECORATIVE, Dept.COLLECTIBLES};
                    for (int i = 0; i < 4; i++) seats.add(new Seat(o.id(), StaffRole.SPECIALIST, cycle[(i + o.id()) % 4], 1, null));
                    addSeats(seats, o.id(), StaffRole.CLIENT_SERVICE, 1);
                    addSeats(seats, o.id(), StaffRole.ADMINISTRATOR, 1);
                }
            }
        }
        RandomStreams.Stream st = streams.stream("house.staff");
        List<Draft> drafts = new ArrayList<>();
        for (Seat s : seats) {
            Rng r = st.at(s.key());
            HouseRules.Office o = offices.get(s.officeId() - 1);
            LocalDate hired;
            if (s.growthFrom() != null) {
                hired = s.growthFrom().plusDays(r.nextInt(20));
            } else if (o.openedOn().isAfter(LocalDate.of(2025, 6, 1))) {
                hired = o.openedOn().minusDays(10 + r.nextInt(30)); // новая приёмная: набор перед открытием
            } else {
                LocalDate from = o.openedOn();
                long span = LocalDate.of(2025, 6, 30).toEpochDay() - from.toEpochDay();
                hired = from.plusDays((long) (span * Math.pow(r.nextDouble(), 0.7)));
                if (hired.getDayOfWeek().getValue() >= 6) hired = hired.plusDays(8 - hired.getDayOfWeek().getValue());
            }
            boolean female = r.chance(s.role() == StaffRole.ACCOUNTANT || s.role() == StaffRole.CLIENT_SERVICE ? 0.75 : 0.5);
            drafts.add(new Draft(s, 0, Names.person(r, female, 0.0), hired, null));
            // предшественник — ушёл до приёма нынешнего (и до периода)
            if (s.growthFrom() == null && r.chance(0.12) && hired.isAfter(o.openedOn().plusDays(400))) {
                LocalDate left = hired.minusDays(r.nextInt(25));
                long span = left.toEpochDay() - 200 - o.openedOn().toEpochDay();
                LocalDate pHired = o.openedOn().plusDays((long) (r.nextDouble() * Math.max(1, span)));
                drafts.add(new Draft(s, 1, Names.person(r, r.chance(0.5), 0.0), pHired, left));
            }
        }
        drafts.sort(Comparator.comparing(Draft::hired).thenComparingLong(d -> d.seat().key()).thenComparingInt(Draft::gen));
        List<HouseRules.Staff> out = new ArrayList<>();
        for (Draft d : drafts) {
            out.add(new HouseRules.Staff(out.size() + 1, d.seat().officeId(), d.seat().dept(), d.seat().role(),
                    d.person().first(), d.person().last(), d.hired(), d.left()));
        }
        return out;
    }

    private static void addSeats(List<Seat> seats, int officeId, StaffRole role, int n) {
        for (int i = 1; i <= n; i++) seats.add(new Seat(officeId, role, null, i, null));
    }
}
