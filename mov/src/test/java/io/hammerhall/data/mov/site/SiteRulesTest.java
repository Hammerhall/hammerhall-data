package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.mov.site.text.ReviewContext;
import io.hammerhall.data.mov.site.text.StubTexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Правила синтетики по одному: тарифы и цены, промокоды, города, аудитория, каталог, суточный ход, тексты. */
class SiteRulesTest {
    static final RandomStreams ST = new RandomStreams(20261123L);

    @Test
    @DisplayName("Тарифы: 7 тарифов, годовой — 10 месяцев, с 01.03.2026 цены выше на 10–12 %, скидка — до рубля вниз")
    void plansAndPrices() {
        assertEquals(7, Plans.ALL.size());
        assertEquals(0, Plans.priceKop(Plans.FREE, LocalDate.of(2026, 5, 1)));
        for (Plans.Plan p : Plans.ALL) {
            if (p.tier() == 0) continue;
            long before = Plans.priceKop(p.id(), LocalDate.of(2026, 2, 28)), after = Plans.priceKop(p.id(), SiteRules.PRICE_RISE);
            double rise = (double) after / before - 1;
            assertTrue(rise > 0.09 && rise < 0.12, p.code() + ": " + rise);
            if (p.months() == 12) assertEquals(10 * Plans.priceKop(Plans.of(p.tier(), 1).id(), SiteRules.PRICE_RISE), after, p.code());
        }
        assertEquals(299_00, Plans.priceKop(2, LocalDate.of(2025, 10, 1)));
        assertEquals(329_00, Plans.priceKop(2, LocalDate.of(2026, 3, 1)));
        assertEquals(1, Plans.get(2).streams());
        assertEquals(4, Plans.get(4).streams());
        assertEquals(349_00, Plans.discounted(499_00, 30));
        assertEquals(224_00, Plans.discounted(449_00, 50));
    }

    @Test
    @DisplayName("Прокат: цена «на 9»; после 60 дней — на 40 % дешевле, не ниже 99 ₽")
    void rentPrices() {
        assertEquals(249_00, SiteRules.nine(249));
        assertEquals(199_00, SiteRules.nine(203));
        assertEquals(149_00, SiteRules.rentLater(249_00));
        assertEquals(119_00, SiteRules.rentLater(199_00));
        assertEquals(99_00, SiteRules.rentLater(149_00));
        Catalog c = Catalog.build(StubInput.build(3, false), ST);
        int rb = 0;
        for (int w = 0; w < c.n; w++) {
            if (c.access[w] != Catalog.RENT_BUY) continue;
            rb++;
            assertTrue(c.rentKop[w] >= 99_00 && c.rentKop[w] <= 249_00, "прокат " + c.rentKop[w]);
            assertTrue(c.buyKop[w] >= 299_00 && c.buyKop[w] <= 799_00, "покупка " + c.buyKop[w]);
            long early = c.rentPrice(w, c.added[w] + 59 * SiteRules.DAY), late = c.rentPrice(w, c.added[w] + 61 * SiteRules.DAY);
            assertTrue(early >= late, "первые 60 дней — не дешевле");
            long days = (c.subFrom[w] - c.added[w]) / SiteRules.DAY;
            assertTrue(days >= 60 && days <= 120, "в подписку через " + days);
        }
        assertTrue(rb > 20, "окон проката " + rb);
    }

    @Test
    @DisplayName("Промокоды: постоянный список, коды разные, пробные — 14 дней, партнёрские — P и 7 знаков, id — по началу")
    void promos() {
        List<Promos.Promo> a = Promos.build(ST), b = Promos.build(ST);
        assertEquals(a, b);
        Set<String> codes = new HashSet<>();
        LocalDate prev = LocalDate.MIN;
        for (Promos.Promo p : a) {
            assertTrue(codes.add(p.code()), p.code());
            assertFalse(PublicNames.isInternal(p.code()));
            if (p.kind() == Promos.TRIAL) assertEquals(14, p.value());
            else assertTrue(List.of(10, 20, 30, 50).contains(p.value()));
            if (p.partner()) assertTrue(p.code().matches("P[B-Z2-9]{7}"), p.code());
            assertFalse(p.from().isBefore(prev));
            assertTrue(!p.to().isBefore(p.from()));
            prev = p.from();
        }
        assertTrue(a.size() > 100);
    }

    @Test
    @DisplayName("Города: справочник World — 1 584 города, 1 409 в России, пояс у каждого; страна по весам — Россия ≈ 85 %")
    void cities() {
        Cities c = Cities.load();
        assertEquals(1584, c.size());
        int rus = 0;
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < c.size(); i++) {
            ids.add(c.id[i]);
            if (c.country[i] == 0) rus++;
        }
        assertEquals(1409, rus);
        assertEquals(c.size(), ids.size());
        // пояс — города, не страны: в России их 23, Москва — московский, Новосибирск — свой
        Set<java.time.ZoneId> ruZones = new HashSet<>();
        for (int i = 0; i < c.size(); i++) if (c.country[i] == 0) ruZones.add(c.zoneOf(i));
        assertEquals(23, ruZones.size());
        for (int i = 0; i < c.size(); i++) {
            if (c.id[i] == 524901) assertEquals(java.time.ZoneId.of("Europe/Moscow"), c.zoneOf(i));
            if (c.id[i] == 1496747) assertEquals(java.time.ZoneId.of("Asia/Novosibirsk"), c.zoneOf(i));
        }
        Rng r = new Rng(5);
        int[] by = new int[SiteRules.COUNTRIES.length];
        int n = 200_000;
        for (int i = 0; i < n; i++) by[c.country[c.pick(r)]]++;
        assertEquals(0.85, by[0] / (double) n, 0.01);
        for (int k = 0; k < by.length; k++) assertTrue(by[k] > 0, SiteRules.COUNTRIES[k]);
        assertEquals(0.03, by[1] / (double) n, 0.005, "KAZ");
    }

    @Test
    @DisplayName("Аудитория: масштаб профиля; на крошечном снимке — пропорционально фильмам, не меньше 0,2 %; ≈ 150 тыс. в full")
    void audience() {
        assertEquals(1.0, Audience.share(1.0, 356_088));
        assertEquals(0.01, Audience.share(0.01, 356_088), 1e-12);
        assertEquals(0.002, Audience.share(1.0, 12), 1e-12);
        double sum = 0;
        for (LocalDate d = SiteRules.SERVICE_START; d.isBefore(LocalDate.of(2027, 9, 1)); d = d.plusDays(1)) sum += Audience.regRate(d);
        assertEquals(150_000, sum, 7_500);
        double g = Audience.regRate(LocalDate.of(2026, 10, 7)) / Audience.regRate(LocalDate.of(2025, 10, 8));
        assertEquals(StrictMath.exp(SiteRules.REG_GROWTH), g, 0.03);
    }

    @Test
    @DisplayName("Каталог: доля фильмов с окнами ≈ 19 % (к 2031), окна фильма не пересекаются, приход — 00:00 по Москве")
    void catalog() {
        SiteInput in = StubInput.build(3, false);
        Catalog c = Catalog.build(in, ST);
        Set<Integer> movies = new HashSet<>();
        for (int w = 0; w < c.n; w++) {
            movies.add(c.movie[w]);
            assertEquals(c.added[w], SiteRules.mskMidnight(SiteRules.mskDate(c.added[w])));
            if (w > 0 && c.movie[w - 1] == c.movie[w]) assertTrue(c.removed[w - 1] < c.added[w], "окна фильма пересекаются");
            assertTrue(c.removed[w] > c.added[w]);
        }
        assertEquals(Catalog.SHARE, movies.size() / (double) in.movies().size(), 0.02);
        // известные — чаще
        int top = 0, rest = 0;
        for (int m : movies) if (in.movies().get(m).popularity() < in.movies().size() / 10) top++;
        else rest++;
        assertTrue(top / (in.movies().size() / 10.0) > 2 * rest / (in.movies().size() * 0.9), top + " / " + rest);
        assertEquals(Catalog.build(in, ST).n, c.n, "детерминированно");
    }

    @Test
    @DisplayName("Суточный ход и сезон: вечер 19–23 — около половины начал; каникулы выше декабря, июль ниже июня и августа")
    void dayAndSeason() {
        double e = SiteRules.eveningShareModel();
        assertTrue(e > 0.45 && e < 0.56, "вечер " + e);
        assertTrue(SiteRules.season(LocalDate.of(2026, 1, 5)) > SiteRules.season(LocalDate.of(2025, 12, 3)));
        assertTrue(SiteRules.MONTH[6] < SiteRules.MONTH[5] && SiteRules.MONTH[6] < SiteRules.MONTH[7]);
        assertTrue(SiteRules.WEEKDAY[5] > SiteRules.WEEKDAY[1] && SiteRules.WEEKDAY[6] > SiteRules.WEEKDAY[2]);
        Rng r = new Rng(9);
        int ev = 0, n = 100_000;
        for (int i = 0; i < n; i++) if (SiteRules.secondOfDay(r, false) >= SiteRules.EVENING_FROM * 3600) ev++;
        assertTrue(ev / (double) n > 0.5, "будни — вечер чаще");
    }

    @Test
    @DisplayName("Счёт предложений рецензии: инициалы, сокращения и названия в «…» — не конец предложения")
    void sentences() {
        assertEquals(3, SiteAudit.Run.sentences("Первое предложение. Второе здесь! Третье?", true));
        assertEquals(3, SiteAudit.Run.sentences("Шедевр. Смотреть! Всем?", false));
        assertEquals(2, SiteAudit.Run.sentences("Режиссёр Дж. Р. Мартин хорош. Оценка — 9 из 10.", true));
        assertEquals(2, SiteAudit.Run.sentences("Смотрел «Мистер Н. Икс. Часть 2» вчера. Понравилось.", true));
        assertTrue(SiteAudit.Run.sentences("Джоди Ста. Мария играет. Хорошо.", false) >= SiteAudit.Run.sentences("Джоди Ста. Мария играет. Хорошо.", true));
    }

    @Test
    @DisplayName("Заглушка текстов: 200 разных тегов, логины по правилу, заголовок 3–8 слов, текст 2–6 предложений, тон — по оценке")
    void stubTexts() {
        StubTexts t = new StubTexts();
        assertEquals(200, t.tags().size());
        assertEquals(200, new HashSet<>(t.tags()).size());
        RandomStreams.Stream s = ST.stream("test.texts");
        for (int i = 0; i < 3000; i++) {
            Rng r = s.at(i);
            assertTrue(t.login(r).matches("[A-Za-z0-9_]{4,20}"));
            String dn = t.displayName(r, i % 2 == 0 ? 'M' : 'F');
            assertTrue(dn.matches("\\p{IsCyrillic}+ \\p{IsCyrillic}\\."), dn);
            int rating = 1 + i % 10;
            ReviewContext c = new ReviewContext(rating, 42, "Some Film", i % 3 == 0 ? null : "Фильм", 1999, List.of(1 + i % 24),
                    i % 4 == 0 ? null : "Дж. Р. Режиссёр", i % 5 == 0 ? List.of() : List.of("Актёр Первый", "Актриса Вторая"),
                    i % 2 == 0 ? "Другой фильм" : null);
            String title = t.reviewTitle(r, c), body = t.reviewBody(r, c);
            int words = title.split("\\s+").length;
            assertTrue(words >= 3 && words <= 8, title);
            int least = SiteAudit.Run.sentences(body, true), most = SiteAudit.Run.sentences(body, false);
            assertTrue(most >= 2 && least <= 6, least + "…" + most + ": " + body);
            assertTrue(body.contains("1999") && body.contains(rating + " из 10"), body);
            assertNotNull(t.collectionTitle(r, 1 + i % 24));
        }
    }

    @Test
    @DisplayName("Рецензии не пишутся о документальном кино (в нём «актёры» — настоящие люди в роли себя)")
    void documentariesAreNotReviewed() {
        SiteInput.Movie m = StubInput.build(3, false).movies().getFirst();
        SiteInput.Movie doc = new SiteInput.Movie(m.id(), m.title(), m.titleRu(), m.year(), m.releaseDate(), m.runtime(), m.rank(),
                m.popularity(), new int[]{SiteModule.DOCUMENTARY}, m.languages(), m.countries(), m.directorIds(), m.directors(),
                m.actors(), null, null, new int[0]);
        assertFalse(SiteModule.reviewable(doc));
        SiteInput.Movie drama = new SiteInput.Movie(m.id(), m.title(), m.titleRu(), m.year(), m.releaseDate(), m.runtime(), m.rank(),
                m.popularity(), new int[]{9}, m.languages(), m.countries(), m.directorIds(), m.directors(), m.actors(), null, null, new int[0]);
        assertTrue(SiteModule.reviewable(drama));
    }
}
