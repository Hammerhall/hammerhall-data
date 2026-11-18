package io.hammerhall.data.auc.text;

import io.hammerhall.data.auc.House;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Образец для просмотра глазами: {@code java … io.hammerhall.data.auc.text.SampleLots ФАЙЛ [ЧИСЛО] [SEED]} — вещи по
 * всем отделам поровну (ключи 0…ЧИСЛО−1, отдел — ключ по модулю 6), Markdown. Не тест: запускается руками.
 */
public final class SampleLots {
    private SampleLots() {
    }

    private static String mm(Integer v) {
        return v == null ? "—" : Integer.toString(v);
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 20251001L;
        LotTexts texts = new LotTexts(seed);
        Department[] ds = Department.values();
        Map<Department, StringBuilder> by = new EnumMap<>(Department.class);
        for (int i = 0; i < n; i++) {
            Department d = ds[i % ds.length];
            LotText t = texts.lot(d, i);
            StringBuilder sb = by.computeIfAbsent(d, k -> new StringBuilder());
            sb.append("### ").append(i + 1).append(". ").append(t.titleRu()).append("\n\n");
            sb.append("*").append(t.titleEn()).append("*\n\n");
            sb.append("`").append(t.category().code()).append("` · ").append(t.category().nameRu()).append(" · атрибуция `")
                    .append(t.attribution().code).append("` · датировка «").append(t.datingRu()).append("» (").append(t.yearFrom())
                    .append("–").append(t.yearTo()).append(")");
            if (t.makerId() != null) {
                Maker m = texts.makers().byId(t.makerId());
                sb.append(" · мастер №").append(m.id()).append(" — ").append(m.name()).append(" / ").append(m.nameEn()).append(" (")
                        .append(m.years()).append(", ").append(m.kind().code).append(", уровень ").append(m.tier()).append(")");
            }
            sb.append("\n\n").append(t.descriptionRu()).append("\n\n");
            sb.append("**Состояние** (`").append(t.conditionGrade()).append("`, уровень ").append(t.conditionLevel()).append("): ")
                    .append(t.conditionRu()).append("\n\n");
            sb.append("Поля: место — ").append(t.originPlaceRu() == null ? "—" : t.originPlaceRu()).append("; материал — ")
                    .append(t.materialRu() == null ? "—" : t.materialRu()).append("; техника — ").append(t.techniqueRu() == null ? "—" : t.techniqueRu())
                    .append("; В×Ш×Г, мм — ").append(mm(t.heightMm())).append("×").append(mm(t.widthMm())).append("×").append(mm(t.depthMm()))
                    .append("; диаметр — ").append(t.diameterMm() == null ? "—" : t.diameterMm()).append(" мм; вес — ")
                    .append(t.weightG() == null ? "—" : t.weightG()).append(" г; проба — ").append(t.metalFineness() == null ? "—" : t.metalFineness())
                    .append("; предметов — ").append(t.pieces()).append("; подпись — ").append(t.signed() == null ? "—" : t.signed() ? "да" : "нет")
                    .append("\n\n");
            if (!t.provenance().isEmpty()) {
                sb.append("**Происхождение:**\n\n");
                for (Provenance p : t.provenance()) {
                    sb.append(p.seq()).append(". `").append(p.kind()).append("` ").append(p.textRu());
                    if (p.ownerRu() != null) sb.append(" — владелец: ").append(p.ownerRu());
                    sb.append("\n");
                }
                sb.append("\n");
            }
        }
        StringBuilder doc = new StringBuilder();
        doc.append("# Образец вещей каталога (").append(n).append(")\n\n");
        doc.append("Сгенерировано `io.hammerhall.data.auc.text.LotTexts`, seed `").append(seed).append("`, ключи 0…")
                .append(n - 1).append(" (отдел — ключ по модулю 6). Все мастера, люди, мастерские, дворы, эмитенты, издательства, ")
                .append("галереи и аукционы выдуманы. Справочник: ").append(texts.makers().all().size()).append(" мастеров. ")
                .append("Имя дома («").append(House.NAME).append("») в данных не встречается.\n\n");
        for (Department d : ds) {
            doc.append("## ").append(d.nameRu).append(" / ").append(d.nameEn).append("\n\n").append(by.getOrDefault(d, new StringBuilder()));
        }
        Files.writeString(out, doc.toString(), StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "образец: %d вещей → %s%n", n, out);
    }
}
