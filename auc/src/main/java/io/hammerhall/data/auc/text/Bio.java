package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.List;

/**
 * Справка о мастере (у уровней A и B), 120–400 знаков: годы, место, чем известен, манера, — без оценок рынка.
 * Фразы — словарь {@code vocab/common.txt}, секции {@code bio.*}.
 */
final class Bio {
    private Bio() {
    }

    /** «Чем известен» — по жанру специализации (пейзаж, портрет, абстракция, гравюра…). */
    private static final java.util.Map<String, String> KNOWN_ART = new java.util.HashMap<>();

    static {
        for (String s : V.list("common", "bio.art.known")) {
            int i = s.indexOf(" : ");
            KNOWN_ART.put(s.substring(0, i), s.substring(i + 3));
        }
    }

    /** Чем известен мастер — согласно его специализации. */
    static String known(Maker m) {
        String key = switch (m.kind()) {
            case SCULPTOR -> "sculpture";
            case PHOTOGRAPHER -> "photo";
            default -> {
                Makers.Specialty sp = Makers.specialty(m.specialty());
                yield sp == null ? "landscape" : sp.genre();
            }
        };
        return KNOWN_ART.getOrDefault(key, KNOWN_ART.get("landscape"));
    }
    private static final String[] MANNER_ART = V.list("common", "bio.art.manner");
    private static final String[] CAREER = V.list("common", "bio.career");
    private static final String[] CAREER_NOTE = V.list("common", "bio.person.note");
    private static final String[] FIRM_NOTE = V.list("common", "bio.firm.note");
    private static final String[] FIRM_MARK = V.list("common", "bio.firm.mark");

    static String of(Maker m, Rng r) {
        List<String> s = new ArrayList<>();
        if (m.isPerson()) {
            Person p = m.person();
            String who = p.female() ? m.country().adjFem + " " + femaleRole(m) : m.country().adjMasc + " " + m.specialty();
            String years = m.yearTo() == null ? "род. " + m.yearFrom() : m.yearFrom() + "–" + m.yearTo();
            s.add(m.name() + " (" + years + ") — " + who
                    + (m.school() != null ? ", представитель" + (p.female() ? "ница " : " ") + Makers.movementGen(m.school()) : ""));
            s.add((p.female() ? "Работала" : "Работал") + " в " + m.city().prep() + "; " + W.pick(r, CAREER));
            if (m.kind() == MakerKind.ARTIST || m.kind() == MakerKind.SCULPTOR || m.kind() == MakerKind.PHOTOGRAPHER) {
                s.add(known(m));
                if (r.chance(0.6)) s.add(W.pick(r, MANNER_ART));
            } else {
                s.add("Специализация — " + m.specialty() + "; " + W.pick(r, CAREER_NOTE));
            }
        } else {
            String years = m.yearTo() == null ? "с " + m.yearFrom() + " года" : "в " + m.yearFrom() + "–" + m.yearTo() + " годах";
            s.add(m.name() + " работал" + suffix(m.name()) + " " + years + (m.city() != null ? " в " + m.city().prep() : "")
                    + "; специализация — " + m.specialty());
            s.add(W.pick(r, FIRM_NOTE));
            s.add(W.pick(r, FIRM_MARK).replace("{m}", m.mark()));
        }
        String text = W.join(s);
        while (text.length() > 400 && s.size() > 1) {
            s.removeLast();
            text = W.join(s);
        }
        if (text.length() < 120) text = W.join(List.of(text, W.pick(r, m.isPerson() ? MANNER_ART : FIRM_NOTE)));
        return text;
    }

    private static String femaleRole(Maker m) {
        return switch (m.kind()) {
            case ARTIST -> "художница";
            case PHOTOGRAPHER -> "фотограф";
            case SCULPTOR -> "скульптор";
            case MEDALIST -> "медальер";
            case AUTHOR -> "писательница";
            default -> "мастер";
        };
    }

    /** Окончание глагола по роду главного слова названия: «Фабрика … работала», «Завод … работал», «Ателье … работало». */
    static String suffix(String name) {
        String w = name.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("Фарфоровый") || name.startsWith("Стекольный") || name.startsWith("Хрустальный")
                || name.startsWith("Ювелирный") || name.startsWith("Торговый") || name.startsWith("Издательский")
                || name.startsWith("Вольный") || name.startsWith("Банк") || name.startsWith("Кантон") || name.contains("монетный двор")
                || name.startsWith("Монетный")) return "";
        if (w.endsWith("о") || w.endsWith("е") || name.startsWith("Ателье") || name.startsWith("Товарищество")
                || name.startsWith("Издательство") || name.startsWith("Книгоиздательство") || name.startsWith("Издание")
                || name.startsWith("Казначейство") || name.startsWith("Почтовое")) return "о";
        return "а";
    }
}
