package io.hammerhall.data.mov.site;

import io.hammerhall.data.core.check.Aggregate;
import io.hammerhall.data.core.check.CheckReport;
import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.gen.GenContext;
import io.hammerhall.data.core.gen.Key;
import io.hammerhall.data.core.gen.Module;
import io.hammerhall.data.core.io.TableSink;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.mov.build.CinemaModule;
import io.hammerhall.data.mov.site.Events.Collection;
import io.hammerhall.data.mov.site.Events.Device;
import io.hammerhall.data.mov.site.Events.Pay;
import io.hammerhall.data.mov.site.Events.Purchase;
import io.hammerhall.data.mov.site.Events.Review;
import io.hammerhall.data.mov.site.Events.Sub;
import io.hammerhall.data.mov.site.Events.View;
import io.hammerhall.data.mov.site.text.ReviewContext;
import io.hammerhall.data.mov.site.text.SiteTexts;
import io.hammerhall.data.mov.site.text.SiteTextsRu;
import io.hammerhall.data.mov.source.SnapshotSource;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.hammerhall.data.core.check.Aggregate.countEq;
import static io.hammerhall.data.core.check.Aggregate.max;
import static io.hammerhall.data.core.check.Aggregate.min;
import static io.hammerhall.data.core.check.Aggregate.nulls;
import static io.hammerhall.data.core.check.Aggregate.sum;

/**
 * Модуль {@code site} — синтетика «Свартальвхейм» (онлайн-кинотеатр и сообщество вокруг настоящих фильмов профиля):
 * 19 таблиц {@link SiteSchema}. Идёт после {@code cinema}; вход — {@link SiteInput} из собранного кино. Порядок: тарифы
 * и цены → промокоды → теги → каталог → пользователи → жизнь каждого пользователя ({@link Life}: устройства, подписки и
 * списания, прокат и покупки, просмотры, оценки, рецензии, список, теги, подборки) → подписки на людей и голоса
 * ({@link Social}) → тексты рецензий по порядку времени. {@code id} — по моменту появления (сущности упорядочены по
 * моменту, пользователю и номеру у пользователя).
 *
 * <p>Время (§4): срез фактов = min(«сейчас», конец периода + 1 сутки); в выпуске нет событий после среза, у сущностей
 * «в пути» — состояние на срез. Самопроверка — {@link SiteAudit} по записанным таблицам.
 */
public final class SiteModule implements Module {
    public static final String NAME = "site";
    /** Итоги прогона — для манифеста и тестов. */
    public static final Key<Stats> STATS = Key.of("mov.site");

    private final List<String> requires;
    private final Function<GenContext, SiteInput> input;
    private final SiteTexts texts;
    private SiteAudit.Inputs auditIn;

    /**
     * @param requires модули-поставщики ({@code cinema})
     * @param input    вход модуля из мира
     * @param texts    тексты людей сервиса
     */
    public SiteModule(List<String> requires, Function<GenContext, SiteInput> input, SiteTexts texts) {
        this.requires = List.copyOf(requires);
        this.input = input;
        this.texts = texts;
    }

    /** Тексты по умолчанию — словарь фраз по-русски {@link SiteTextsRu}. */
    public static SiteTexts defaultTexts() {
        return new SiteTextsRu();
    }

    /** В наборе: после {@code cinema}, вход — из собранного кино и снимка ({@link SiteInput#of}). */
    public static SiteModule ofCinema(SnapshotSource source) {
        return new SiteModule(List.of("cinema"), ctx -> SiteInput.of(ctx.get(CinemaModule.CINEMA), source.loaded()), defaultTexts());
    }

    /** Поле манифеста о синтетике: имя, срез, масштаб аудитории, доля каталога, тексты; {@code null} — модуль не шёл. */
    public static Map<String, Object> manifest(GenContext ctx) {
        if (!ctx.has(STATS)) return null;
        Stats s = ctx.get(STATS);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Svartalfheim");
        m.put("kind", "synthetic online cinema and its community around the real movies of the profile");
        m.put("cutoff", Instant.ofEpochSecond(s.cutoff()).toString());
        m.put("audience_share_of_full", String.format(Locale.ROOT, "%.4f", s.audienceShare()));
        m.put("catalog_share_by_2031", String.valueOf(Catalog.SHARE));
        m.put("catalog_movies", (long) s.catalogMovies());
        m.put("texts", s.texts());
        return m;
    }

    /** Срез фактов: min(«сейчас», конец периода + 1 сутки). */
    public static long cutoff(GenContext ctx) {
        return Math.min(ctx.period().nowEpoch(), ctx.period().endEpochExclusive());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> requires() {
        return requires;
    }

    @Override
    public List<String> tables() {
        return SiteSchema.TABLES;
    }

    /** Итоги прогона: число строк по таблицам, заложенное, время — в журнал, манифест и тесты. */
    public record Stats(long cutoff, double audienceShare, int movies, int catalogMovies, Map<String, Long> rows,
                        String texts) {
    }

    @Override
    public void generate(GenContext ctx) throws IOException {
        long t0 = System.nanoTime();
        SiteInput in = input.apply(ctx);
        long cutoff = cutoff(ctx), w0 = ctx.period().startEpoch();
        RandomStreams st = ctx.streams();
        Cities cities = Cities.load();
        List<Promos.Promo> promos = Promos.build(st);
        List<String> tagNames = texts.tags();
        double share = Audience.share(ctx.profile().scale(), in.moviesFull());
        Map<String, Long> rows = new LinkedHashMap<>();

        // ---- справочники
        TableSink s = ctx.sink("plans");
        for (Plans.Plan p : Plans.ALL) {
            s.add(p.id(), p.code(), p.name(), p.monthlyKop(), (short) p.months(), (short) p.streams(),
                    SiteSchema.QUALITIES[p.quality()], p.ads(), p.introduced(), null);
        }
        rows.put("plans", ctx.close("plans").rows());
        s = ctx.sink("plan_prices");
        for (Plans.Plan p : Plans.ALL) {
            for (int i = 0; i < p.from().length; i++) {
                if (SiteRules.mskMidnight(p.from()[i]) < cutoff) s.add(p.id(), p.from()[i], p.priceKop()[i]);
            }
        }
        rows.put("plan_prices", ctx.close("plan_prices").rows());
        s = ctx.sink("promo_codes");
        for (Promos.Promo p : promos) {
            if (SiteRules.mskMidnight(p.from()) >= cutoff) continue;
            s.add(p.id(), p.code(), SiteSchema.PROMO_KINDS[p.kind()], (short) p.value(), p.partner(), p.from(), p.to(),
                    p.maxUses());
        }
        rows.put("promo_codes", ctx.close("promo_codes").rows());
        s = ctx.sink("tags");
        Set<String> seenTags = new HashSet<>();
        for (int i = 0; i < tagNames.size(); i++) {
            String tg = tagNames.get(i);
            if (!seenTags.add(tg)) throw new IllegalStateException("тег дважды: " + tg);
            s.add(i + 1, tg);
        }
        rows.put("tags", ctx.close("tags").rows());

        // ---- каталог
        Catalog cat = Catalog.build(in, st);
        s = ctx.streamSink("catalog");
        int catalogMovies = 0, lastMovie = -1;
        for (int w = 0; w < cat.n; w++) {
            if (cat.added[w] >= cutoff) continue;
            boolean rb = cat.access[w] == Catalog.RENT_BUY;
            s.add(in.movies().get(cat.movie[w]).id(), cat.added[w], cat.removed[w] < cutoff ? cat.removed[w] : null,
                    SiteSchema.ACCESS[cat.access[w]], rb ? cat.rentKop[w] : null, rb ? cat.buyKop[w] : null,
                    rb ? cat.subFrom[w] : null);
            if (cat.movie[w] != lastMovie) catalogMovies++;
            lastMovie = cat.movie[w];
        }
        rows.put("catalog", ctx.close("catalog").rows());

        // ---- пользователи
        Audience aud = Audience.build(st, cities, promos, texts, share, cutoff);
        s = ctx.streamSink("users");
        for (int u = 0; u < aud.n; u++) {
            s.add(u + 1, aud.login[u], aud.displayName[u], aud.gender[u] == 1 ? "F" : "M", aud.birthYear[u],
                    cities.id[aud.city[u]], aud.regAt[u], SiteSchema.CHANNELS[aud.channel[u]],
                    aud.referrer[u] >= 0 ? aud.referrer[u] + 1 : null, aud.promo[u] >= 0 ? aud.promo[u] + 1 : null,
                    aud.deletedAt[u] < cutoff ? aud.deletedAt[u] : null);
        }
        rows.put("users", ctx.close("users").rows());
        long t1 = System.nanoTime();

        // ---- жизнь пользователей: оценки, список, теги — по пользователю сразу
        Life life = new Life(in, cat, aud, cities, promos, tagNames.size(), st, w0, cutoff);
        TableSink ratings = ctx.streamSink("ratings"), watch = ctx.streamSink("watchlist"), mtags = ctx.streamSink("movie_tags");
        Map<Long, int[]> finalRating = new HashMap<>();
        long noView = 0;
        for (int u = 0; u < aud.n; u++) {
            Life.UserOut o = life.simulate(u);
            long end = Math.min(aud.deletedAt[u], cutoff);
            List<Integer> ms = new ArrayList<>(o.ratings.keySet());
            ms.sort((a, b) -> Integer.compare(in.movies().get(a).id(), in.movies().get(b).id()));
            for (int m : ms) {
                Life.Rating rt = o.ratings.get(m);
                int last = -1;
                for (int i = 0; i < rt.n; i++) if (rt.at[i] < end) last = i;
                if (last < 0) continue;
                ratings.add(u + 1, in.movies().get(m).id(), (short) rt.value[last], rt.at[last], (short) last);
                if (rt.noView) {
                    ctx.planted().add("rating_without_view", ((long) (u + 1) << 32) | in.movies().get(m).id());
                    noView++;
                }
                finalRating.put(((long) u << 32) | m, new int[]{rt.value[last]});
            }
            o.watch.sort((a, b) -> {
                int c = Integer.compare(in.movies().get(a.movie).id(), in.movies().get(b.movie).id());
                return c != 0 ? c : Long.compare(a.added, b.added);
            });
            for (Life.Watch e : o.watch) {
                if (e.added >= end) continue;
                Long rm = null;
                String why = null;
                if (e.removed >= 0 && e.removed < end) {
                    rm = e.removed;
                    why = "watched";
                } else if (e.removed < 0 && e.dropAt < end) {
                    rm = e.dropAt;
                    why = "dropped";
                }
                watch.add(u + 1, in.movies().get(e.movie).id(), e.added, rm, why);
            }
            o.tags.sort((a, b) -> {
                int c = Integer.compare(in.movies().get(a.movie()).id(), in.movies().get(b.movie()).id());
                return c != 0 ? c : Integer.compare(a.tag(), b.tag());
            });
            for (Life.Tag tg : o.tags) if (tg.at() < end) mtags.add(u + 1, in.movies().get(tg.movie()).id(), tg.tag() + 1, tg.at());
        }
        rows.put("ratings", ctx.close("ratings").rows());
        rows.put("watchlist", ctx.close("watchlist").rows());
        rows.put("movie_tags", ctx.close("movie_tags").rows());
        long t2 = System.nanoTime();

        // ---- устройства, подписки, покупки, оплаты, просмотры — по моменту
        List<Device> devices = life.devices;
        devices.sort(Events.BY_TIME);
        s = ctx.streamSink("devices");
        int id = 0;
        for (Device d : devices) {
            d.id = ++id;
            s.add(d.id, d.user + 1, SiteSchema.DEVICE_KINDS[d.kind], SiteSchema.OS[d.os], d.at, Math.max(d.at, d.last));
        }
        rows.put("devices", ctx.close("devices").rows());
        List<Sub> subs = life.subs;
        subs.sort(Events.BY_TIME);
        s = ctx.streamSink("subscriptions");
        id = 0;
        for (Sub x : subs) {
            x.id = ++id;
            boolean c = x.cancelledAt >= 0 && x.cancelledAt < cutoff;
            s.add(x.id, x.user + 1, x.plan, x.at, x.trialEnd >= 0 ? x.trialEnd : null, x.ends, SiteSchema.SUB_STATUS[x.status],
                    x.autoRenew, c ? x.cancelledAt : null, c && x.reason >= 0 ? SiteSchema.CANCEL_REASONS[x.reason] : null);
        }
        rows.put("subscriptions", ctx.close("subscriptions").rows());
        List<Purchase> purs = new ArrayList<>();
        for (Purchase p : life.purchases) if (p.at < Math.min(aud.deletedAt[p.user], cutoff)) purs.add(p);
        purs.sort(Events.BY_TIME);
        s = ctx.streamSink("purchases");
        id = 0;
        for (Purchase p : purs) {
            p.id = ++id;
            s.add(p.id, p.user + 1, in.movies().get(p.movie).id(), p.buy ? "buy" : "rent", p.price, p.at, p.buy ? null : p.expires);
        }
        rows.put("purchases", ctx.close("purchases").rows());
        List<Pay> pays = new ArrayList<>();
        for (Pay p : life.pays) if (p.at < Math.min(aud.deletedAt[p.user], cutoff) && (p.purchase == null || p.purchase.id > 0)) pays.add(p);
        pays.sort(Events.BY_TIME);
        s = ctx.streamSink("payments");
        id = 0;
        for (Pay p : pays) {
            p.id = ++id;
            boolean refunded = p.status == 2 && p.refundedAt < cutoff;
            String status = p.status == 1 ? "failed" : refunded ? "refunded" : "succeeded";
            s.add(p.id, p.user + 1, p.sub != null ? p.sub.id : null, p.purchase != null ? p.purchase.id : null, p.amount,
                    SiteSchema.METHODS[p.method], status, p.at, refunded ? p.refundedAt : null);
            if (p.duplicate) ctx.planted().add("double_charge", p.id);
        }
        rows.put("payments", ctx.close("payments").rows());
        List<View> views = new ArrayList<>();
        for (View v : life.views) if (v.at < Math.min(aud.deletedAt[v.user], cutoff)) views.add(v);
        views.sort(Events.BY_TIME);
        s = ctx.streamSink("views");
        id = 0;
        long truncated = 0;
        for (View v : views) {
            v.id = ++id;
            long end = Math.min(aud.deletedAt[v.user], cutoff);
            int watched = v.watched;
            boolean completed = v.completed;
            if (v.at + watched * SiteRules.MINUTE > end) {
                watched = (int) ((end - v.at) / SiteRules.MINUTE);
                completed = false;
                truncated++;
            }
            s.add(v.id, v.user + 1, v.device.id, in.movies().get(v.movie).id(), v.at, watched, completed,
                    SiteSchema.QUALITIES[v.quality], SiteSchema.SOURCES[v.source]);
            if (v.mark == Events.V_LONG) ctx.planted().add("long_view", v.id);
            if (v.mark == Events.V_GRACE) ctx.planted().add("rent_grace", v.id);
        }
        rows.put("views", ctx.close("views").rows());
        long t3 = System.nanoTime();

        // ---- рецензии: тексты по порядку времени, без повторов
        List<Review> reviews = new ArrayList<>();
        for (Review v : life.reviews) {
            if (v.at >= Math.min(aud.deletedAt[v.user], cutoff)) continue;
            if (!reviewable(in.movies().get(v.movie))) continue;
            reviews.add(v);
        }
        reviews.sort(Events.BY_TIME);
        Map<Integer, Integer> otherOf = otherFilms(in);
        RandomStreams.Stream rt = st.stream("site.reviews.text");
        s = ctx.streamSink("reviews");
        Set<Long> bodies = new HashSet<>();
        id = 0;
        long hiddenDeleted = 0, changed = 0, retries = 0;
        for (Review v : reviews) {
            v.id = ++id;
            int[] fin = finalRating.get(((long) v.user << 32) | v.movie);
            SiteInput.Movie m = in.movies().get(v.movie);
            ReviewContext c = context(in, m, v.rating, otherOf);
            String title = null, body = null;
            for (int attempt = 0; attempt < 30; attempt++) {
                Rng r = rt.at(aud.key[v.user], v.seq, attempt);
                String tt = texts.reviewTitle(r, c), bb = texts.reviewBody(r, c);
                if (tt == null || bb == null || PublicNames.isInternal(tt) || PublicNames.isInternal(bb)) continue;
                title = tt;
                body = bb;
                if (bodies.add(RandomStreams.fnv64(bb))) break;
                retries++;
                body = null;
            }
            if (body == null) throw new IllegalStateException("рецензия " + v.id + ": текст повторяется и после 30 попыток");
            boolean deleted = aud.deletedAt[v.user] < cutoff;
            boolean hidden = v.hidden || deleted;
            if (deleted) {
                ctx.planted().add("deleted_user_review", v.id);
                hiddenDeleted++;
            }
            if (fin != null && fin[0] != v.rating) {
                ctx.planted().add("review_rating_changed", v.id);
                changed++;
            }
            long end = Math.min(aud.deletedAt[v.user], cutoff);
            s.add(v.id, v.user + 1, m.id(), (short) v.rating, title, body, v.at, v.edited >= 0 && v.edited < end ? v.edited : null,
                    hidden ? "hidden" : "published");
        }
        rows.put("reviews", ctx.close("reviews").rows());
        List<Social.Vote> votes = Social.votes(reviews, aud, in, st);
        votes.sort((a, b) -> a.review() != b.review() ? Integer.compare(a.review(), b.review()) : Integer.compare(a.user(), b.user()));
        s = ctx.streamSink("review_votes");
        for (Social.Vote x : votes) {
            if (x.at() >= cutoff || x.at() >= aud.deletedAt[x.user()]) continue;
            s.add(reviews.get(x.review()).id, x.user() + 1, x.useful(), x.at());
        }
        rows.put("review_votes", ctx.close("review_votes").rows());
        List<Social.Follow> follows = Social.follows(life.intents, aud, cities, reviews, st);
        follows.sort((a, b) -> a.follower() != b.follower() ? Integer.compare(a.follower(), b.follower())
                : Integer.compare(a.followee(), b.followee()));
        s = ctx.streamSink("follows");
        for (Social.Follow f : follows) {
            if (f.at() >= cutoff) continue;
            long un = f.unfollowAt();
            long fEnd = Math.min(Math.min(aud.deletedAt[f.follower()], aud.deletedAt[f.followee()]), cutoff);
            s.add(f.follower() + 1, f.followee() + 1, f.at(), un >= 0 && un < fEnd ? un : null);
        }
        rows.put("follows", ctx.close("follows").rows());

        // ---- подборки
        List<Collection> cols = new ArrayList<>();
        for (Collection c : life.collections) if (c.at < Math.min(aud.deletedAt[c.user], cutoff)) cols.add(c);
        cols.sort(Events.BY_TIME);
        RandomStreams.Stream ct = st.stream("site.collections.text");
        s = ctx.streamSink("collections");
        TableSink items = ctx.sink("collection_items");
        id = 0;
        for (Collection c : cols) {
            c.id = ++id;
            String title = null;
            for (int attempt = 0; attempt < 20 && title == null; attempt++) {
                String x = texts.collectionTitle(ct.at(aud.key[c.user], c.seq, attempt), c.genre);
                if (x != null && !x.isBlank() && !PublicNames.isInternal(x)) title = x;
            }
            if (title == null) throw new IllegalStateException("подборка " + c.id + ": нет названия");
            s.add(c.id, c.user + 1, title, c.open, c.at);
            long end = Math.min(aud.deletedAt[c.user], cutoff);
            int pos = 0;
            for (long[] it : c.items) {
                if (it[1] >= end) continue;
                items.add(c.id, in.movies().get((int) it[0]).id(), (short) ++pos, it[1]);
            }
        }
        rows.put("collections", ctx.close("collections").rows());
        rows.put("collection_items", ctx.close("collection_items").rows());
        long t4 = System.nanoTime();


        Stats stats = new Stats(cutoff, share, in.movies().size(), catalogMovies, rows, texts.getClass().getSimpleName());
        ctx.put(STATS, stats);
        auditIn = new SiteAudit.Inputs(in, cities, w0, cutoff, ctx.profile(), tagNames.size());
        ctx.log(String.format(Locale.ROOT, "site: срез %s, аудитория %.4f full, фильмов %,d, в каталоге %,d (окон %,d); "
                        + "пользователей %,d", Instant.ofEpochSecond(cutoff), share, in.movies().size(), catalogMovies, cat.n, aud.n));
        ctx.log(String.format(Locale.ROOT, "site: подготовка %.1f с, жизнь пользователей %.1f с, запись событий %.1f с, "
                        + "сообщество %.1f с; рецензий с повтором текста %d, просмотров на срезе %d, оценок без просмотра %d, "
                        + "скрытых рецензий удалённых %d, рецензий с изменённой оценкой %d", (t1 - t0) / 1e9, (t2 - t1) / 1e9,
                (t3 - t2) / 1e9, (t4 - t3) / 1e9, retries, truncated, noView, hiddenDeleted, changed));
    }

    /**
     * Фильм рецензируют: есть название для текстов и это не документальное кино (в нём «актёры» — настоящие люди в роли
     * себя; рецензии о них не пишутся).
     */
    static boolean reviewable(SiteInput.Movie m) {
        return titleFor(m) != null && !m.hasGenre(DOCUMENTARY);
    }

    static final int DOCUMENTARY = 8;

    /** Название для текстов: если в названии внутреннее имя — по-русски, иначе фильм не рецензируется ({@code null}). */
    static String titleFor(SiteInput.Movie m) {
        if (!PublicNames.isInternal(m.title())) return m.title();
        if (m.titleRu() != null && !PublicNames.isInternal(m.titleRu())) return m.titleRu();
        return null;
    }

    private static String clean(String s) {
        return s == null || PublicNames.isInternal(s) ? null : s;
    }

    /**
     * Другой фильм для сравнения в рецензии: соседний фильм серии или продолжение (предыдущий, иначе следующий), иначе
     * самый известный другой фильм того же (первого) режиссёра в профиле.
     */
    static Map<Integer, Integer> otherFilms(SiteInput in) {
        Map<Integer, Integer> out = directorFilms(in);
        Map<Integer, Integer> series = new HashMap<>();
        for (int[] p : in.successions()) {
            series.putIfAbsent(p[1], p[0]);
            series.putIfAbsent(p[0], p[1]);
        }
        out.putAll(series);
        return out;
    }

    private static Map<Integer, Integer> directorFilms(SiteInput in) {
        Map<Integer, Integer> best = new HashMap<>(), second = new HashMap<>();
        List<SiteInput.Movie> ms = in.movies();
        for (int i = 0; i < ms.size(); i++) {
            SiteInput.Movie m = ms.get(i);
            if (m.directorIds().length == 0) continue;
            int d = m.directorIds()[0];
            Integer b = best.get(d);
            if (b == null || ms.get(b).popularity() > m.popularity()) {
                if (b != null) second.put(d, b);
                best.put(d, i);
            } else {
                Integer s2 = second.get(d);
                if (s2 == null || ms.get(s2).popularity() > m.popularity()) second.put(d, i);
            }
        }
        Map<Integer, Integer> out = new HashMap<>();
        for (int i = 0; i < ms.size(); i++) {
            SiteInput.Movie m = ms.get(i);
            if (m.directorIds().length == 0) continue;
            int d = m.directorIds()[0];
            Integer o = best.get(d) == i ? second.get(d) : best.get(d);
            if (o != null) out.put(i, o);
        }
        return out;
    }

    static ReviewContext context(SiteInput in, SiteInput.Movie m, int rating, Map<Integer, Integer> otherOf) {
        String title = titleFor(m);
        String titleRu = clean(m.titleRu());
        String director = m.directors().isEmpty() ? null : clean(m.directors().getFirst());
        List<String> actors = new ArrayList<>();
        for (String a : m.actors()) if (clean(a) != null) actors.add(a);
        Integer o = otherOf.get(in.indexOf(m.id()));
        String other = null;
        if (o != null) {
            SiteInput.Movie om = in.movies().get(o);
            other = clean(om.titleRu() != null ? om.titleRu() : om.title());
        }
        List<Integer> g = new ArrayList<>();
        for (int x : m.genres()) g.add(x);
        return new ReviewContext(rating, m.id(), title, titleRu, m.year(), List.copyOf(g), director, List.copyOf(actors), other);
    }


    /** Вход самопроверки последнего прогона (для тестов порчи таблиц). */
    SiteAudit.Inputs auditInputs() {
        return auditIn;
    }

    @Override
    public void check(GenContext ctx, CheckReport report) {
        if (auditIn == null) {
            report.fail("site: самопроверка", "модуль не прогнан");
            return;
        }
        try {
            SiteAudit.run(ctx.config().out(), auditIn, ctx.planted(), report);
        } catch (IOException e) {
            report.fail("site: самопроверка", "таблицы не прочитаны: " + e.getMessage());
        }
    }

    @Override
    public List<Aggregate> aggregates() {
        return List.of(
                countEq("users", "channel", "referral"), nulls("users", "deleted_at"), min("users", "registered_at"),
                max("users", "registered_at"),
                countEq("subscriptions", "status", "active"), countEq("subscriptions", "status", "trial"),
                countEq("subscriptions", "status", "cancelled"), countEq("subscriptions", "status", "expired"),
                countEq("subscriptions", "status", "switched"),
                sum("payments", "amount_rub"), countEq("payments", "status", "failed"), countEq("payments", "status", "refunded"),
                countEq("catalog", "access", "rent_buy"), countEq("catalog", "access", "free"), nulls("catalog", "removed_at"),
                sum("purchases", "price_rub"), countEq("purchases", "kind", "buy"),
                sum("views", "watched_min"), countEq("views", "completed", "t"), countEq("views", "source", "subscription"),
                countEq("views", "source", "free"), countEq("views", "quality", "UHD"), max("views", "started_at"),
                sum("ratings", "rating"), sum("ratings", "changes"),
                countEq("reviews", "status", "hidden"), countEq("review_votes", "useful", "t"),
                countEq("watchlist", "removed_reason", "watched"), countEq("watchlist", "removed_reason", "dropped"),
                nulls("follows", "unfollowed_at"), countEq("collections", "is_public", "t"), max("collection_items", "position"));
    }
}
