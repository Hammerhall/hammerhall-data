package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.List;

/** Заготовка одной вещи: что знает генератор вида и что он заполняет. */
final class Ctx {
    final LotTexts lt;
    final Rng r;
    final Category cat;
    final Kind kind;
    /** Вариант листа категории («landscape», «ring»); {@code null} — любой. */
    final String variant;
    final long key;
    /** Мастер; {@code null} — неизвестен или его нет у категории. */
    Maker maker;
    Attribution attribution = Attribution.BY;
    /** Предмет — середина заголовка («Пейзаж с мельницей», «Ваза с крышкой»), по-русски и по-английски. */
    String objectRu, objectEn;
    /** Готовый заголовок целиком (монеты, книги) — если задан, формула не применяется. */
    String titleRu, titleEn;
    final List<String> desc = new ArrayList<>();
    Common.Dated dated;
    int year, yearFrom, yearTo;
    /** Страна предмета (для датировки, происхождения, заголовка без мастера). */
    Country country;
    String originPlaceRu, materialRu, techniqueRu;
    Integer heightMm, widthMm, depthMm, fineness;
    Double diameterMm, weightG;
    int pieces = 1;
    Boolean signed;
    String grade, condition;
    int level;
    List<Provenance> provenance = List.of();
    /** Стиль происхождения: «art», «deco», «book», «coin», «stamp», «jewel», «toy». */
    String provenanceStyle = "art";
    /** Особые замечания о состоянии: «tech», «jewel_plain», «pearl»; {@code null} — по виду. */
    String defects;
    /** Работа, купленная у автора, уместна в происхождении. */
    boolean fromArtist;
    /** Жанр живописи и графики («landscape», «abstract»); {@code null} — не картина. */
    String genre;
    /** Слова, которых в добираемых фразах быть не должно (основа без холста — без «подрамника»; малый формат — без «крупноформатного»). */
    final java.util.Set<String> forbid = new java.util.HashSet<>();

    /** Фраза годится: в ней нет запрещённых для этой вещи слов. */
    boolean ok(String s) {
        if (forbid.isEmpty()) return true;
        String l = s.toLowerCase(java.util.Locale.ROOT);
        for (String f : forbid) if (l.contains(f)) return false;
        return true;
    }

    /** Случайная подходящая фраза из словаря; {@code null} — подходящих нет. */
    String pick(String[] pool) {
        for (int i = 0; i < pool.length * 2; i++) {
            String s = pool[r.nextInt(pool.length)];
            if (ok(s)) return s;
        }
        for (String s : pool) if (ok(s)) return s;
        return null;
    }

    Ctx(LotTexts lt, Rng r, Category cat, Maker maker, long key) {
        this.lt = lt;
        this.r = r;
        this.cat = cat;
        this.kind = cat.kind();
        this.variant = cat.variant();
        this.maker = maker;
        this.key = key;
    }

    void add(String sentence) {
        if (sentence != null && !sentence.isBlank()) desc.add(sentence);
    }

    void dated(Common.Dated d) {
        dated = d;
        year = d.year();
        yearFrom = d.from();
        yearTo = Math.min(d.to(), Makers.NOW_YEAR);
    }

    boolean chance(double p) {
        return r.chance(p);
    }

    /** Размеры в сантиметрах → поля в миллиметрах. */
    void sizeCm(Double h, Double w, Double d) {
        heightMm = h == null ? null : (int) StrictMath.round(h * 10);
        widthMm = w == null ? null : (int) StrictMath.round(w * 10);
        depthMm = d == null ? null : (int) StrictMath.round(d * 10);
    }

    LotText build() {
        if (yearTo > Makers.NOW_YEAR) yearTo = Makers.NOW_YEAR;
        if (yearFrom > yearTo) yearFrom = yearTo;
        if (titleRu == null) Titles.assemble(this);
        Extra.pad(this);
        String datingRu = dated != null ? dated.text() : yearFrom == yearTo ? Integer.toString(yearFrom) : yearFrom + "–" + yearTo;
        return new LotText(key, cat.department(), cat, maker == null ? null : maker.id(), attribution, titleRu, titleEn,
                W.join(desc), condition, grade, level, datingRu, yearFrom, yearTo, originPlaceRu, materialRu, techniqueRu,
                heightMm, widthMm, depthMm, diameterMm == null ? null : StrictMath.round(diameterMm * 10) / 10.0,
                weightG == null ? null : StrictMath.round(weightG * 100) / 100.0, fineness, pieces, signed, provenance);
    }
}
