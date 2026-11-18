package io.hammerhall.data.auc.catalog;

import io.hammerhall.data.auc.text.Category;
import io.hammerhall.data.auc.text.Department;
import io.hammerhall.data.auc.text.LotText;
import io.hammerhall.data.auc.text.LotTexts;
import io.hammerhall.data.auc.text.Provenance;
import io.hammerhall.data.auc.world.Dept;
import io.hammerhall.data.auc.world.HouseRules;
import io.hammerhall.data.core.random.RandomStreams;

import java.util.ArrayList;
import java.util.List;

/**
 * Переходник над пакетом текстов ({@code io.hammerhall.data.auc.text}) — реализация {@link Texts} в наборе.
 * Отделы — по порядку ({@code Department.ordinal()} = {@code Dept.ordinal()}); дерево категорий — {@code Category.all()}
 * (те же {@code id}); шкала состояния — словаря (общая, нумизматическая, марки).
 */
public final class VocabTexts implements Texts {
    private final LotTexts texts;
    private final List<Texts.Maker> rows = new ArrayList<>();

    VocabTexts(RandomStreams streams) {
        this.texts = new LotTexts(streams);
        for (io.hammerhall.data.auc.text.Maker m : texts.makers().all()) {
            var p = m.person();
            boolean person = m.isPerson();
            rows.add(new Texts.Maker(m.id(), m.kind().code, Dept.values()[m.department().ordinal()], person, m.name(),
                    m.nameEn(), person && p != null ? p.first() : null, person && p != null ? p.patronymic() : null,
                    person && p != null ? p.last() : null, m.yearFrom(), m.yearTo(), m.country().code(),
                    m.city() == null ? null : m.city().name(), m.school(), m.specialty(), m.mark()));
        }
    }

    /** Дерево категорий дома из словаря. */
    public static List<HouseRules.Category> categories() {
        List<HouseRules.Category> out = new ArrayList<>();
        for (Category c : Category.all()) {
            out.add(new HouseRules.Category(c.id(), Dept.values()[c.department().ordinal()],
                    c.parent() == null ? 0 : c.parent().id(), c.level(), c.code(), c.nameRu(), c.nameEn(), c.isLeaf()));
        }
        return out;
    }

    @Override
    public List<Texts.Maker> makers() {
        return rows;
    }

    @Override
    public ItemText item(Dept d, long key) {
        LotText t = texts.lot(Department.values()[d.ordinal()], key);
        List<Link> prov = new ArrayList<>();
        for (Provenance p : t.provenance()) {
            prov.add(new Link(p.kind(), p.textRu(), p.yearFrom(), p.yearTo(), p.placeRu(), p.ownerRu()));
        }
        return new ItemText(t.category().id(), t.makerId() == null ? 0 : t.makerId(), t.attribution().code, t.titleRu(),
                t.titleEn(), t.descriptionRu(), t.conditionRu(), grade(d, t), t.datingRu(), t.yearFrom(), t.yearTo(),
                t.originPlaceRu(), t.materialRu(), t.techniqueRu(), t.heightMm(), t.widthMm(), t.depthMm(), t.diameterMm(),
                t.weightG(), t.metalFineness(), t.pieces(), t.signed(), t.conditionLevel(), prov);
    }

    /** Шкала раздела — у словаря уже своя (общая, нумизматическая, марки). */
    static String grade(Dept d, LotText t) {
        return t.conditionGrade();
    }
}
