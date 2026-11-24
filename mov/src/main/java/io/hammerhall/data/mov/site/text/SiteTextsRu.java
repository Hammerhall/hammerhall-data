package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.Rng;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Тексты «Свартальвхейма» по-русски из словарей генератора ({@code io/hammerhall/data/mov/site/text/}): логины и имена —
 * {@link People}, рецензии — {@link Reviews}, подборки — {@link CollectionTitles}, теги — {@code tags.txt}.
 *
 * <pre>{@code
 * SiteTexts texts = new SiteTextsRu();                       // словари читаются один раз на JVM
 * Rng r = streams.stream("site.reviews").at(userId, movieId);
 * String title = texts.reviewTitle(r, ctx), body = texts.reviewBody(r, ctx);
 * }</pre>
 *
 * Детерминизм: результат зависит только от состояния {@code Rng} и аргументов. Экземпляр неизменяем, вызывать можно из
 * разных потоков (у каждого — свой {@code Rng}).
 */
public final class SiteTextsRu implements SiteTexts {
    /** Словари — один раз на JVM (ленивая загрузка при первом экземпляре). */
    private static final class Holder {
        static final List<List<GenreRu>> GENRES = GenreRu.load();
        static final People PEOPLE = new People();
        static final Reviews REVIEWS = new Reviews(GENRES);
        static final CollectionTitles COLLECTIONS = new CollectionTitles(GENRES);
        static final List<String> TAGS = tags();

        private static List<String> tags() {
            List<String> t = Vocab.lines("tags.txt");
            Set<String> seen = new HashSet<>();
            for (String s : t) {
                if (!seen.add(s.toLowerCase(Locale.ROOT).replace('ё', 'е'))) throw new IllegalStateException("tags.txt: тег дважды: " + s);
            }
            return List.copyOf(t);
        }
    }

    private final People people;
    private final Reviews reviews;
    private final CollectionTitles collections;
    private final List<String> tags;

    public SiteTextsRu() {
        this.people = Holder.PEOPLE;
        this.reviews = Holder.REVIEWS;
        this.collections = Holder.COLLECTIONS;
        this.tags = Holder.TAGS;
    }

    @Override
    public String login(Rng r) {
        return people.login(r);
    }

    @Override
    public String displayName(Rng r, char gender) {
        return people.displayName(r, gender);
    }

    @Override
    public String reviewTitle(Rng r, ReviewContext c) {
        return reviews.title(r, c);
    }

    @Override
    public String reviewBody(Rng r, ReviewContext c) {
        return Tx.join(reviews.sentences(r, c));
    }

    /** Текст рецензии по предложениям (для проверок): {@link #reviewBody} — они же через пробел. */
    List<String> reviewSentences(Rng r, ReviewContext c) {
        return reviews.sentences(r, c);
    }

    @Override
    public String collectionTitle(Rng r, int genreId) {
        return collections.title(r, genreId);
    }

    @Override
    public List<String> tags() {
        return tags;
    }

    People people() {
        return people;
    }

    Reviews reviews() {
        return reviews;
    }

    CollectionTitles collections() {
        return collections;
    }
}
