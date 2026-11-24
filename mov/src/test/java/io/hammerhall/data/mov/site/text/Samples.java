package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.List;

/**
 * Выдуманные фильмы для проверок текстов: названия по-русски и по-английски (разные для разных номеров — до 100 000),
 * год, 1–3 жанра словаря, режиссёр и 0–6 актёров (имена по-русски и латиницей), иногда — другой фильм для сравнения.
 * Все названия и имена выдуманы.
 */
final class Samples {
    private Samples() {
    }

    static final String[] ADJ = {"Тихий", "Последний", "Северный", "Белый", "Долгий", "Чужой", "Ночной", "Пятый",
            "Летний", "Зимний", "Дальний", "Красный", "Седой", "Старый", "Новый", "Тёмный", "Ясный", "Синий", "Сонный",
            "Морской", "Лесной", "Горный", "Степной", "Южный", "Западный", "Восточный", "Медный", "Серебряный", "Хрупкий",
            "Тайный", "Шумный", "Пустой", "Тёплый", "Ледяной", "Бумажный", "Каменный", "Стеклянный", "Весёлый",
            "Грустный", "Странный"};
    static final String[] NOUN = {"берег", "рейс", "ветер", "дом", "путь", "город", "сад", "мост", "свет", "остров",
            "поезд", "маяк", "двор", "лес", "вечер", "берег реки", "перевал", "причал", "сон", "голос", "след", "огонь",
            "шторм", "полдень", "рассвет", "дождь", "снег", "переулок", "вокзал", "чердак", "оркестр", "портрет",
            "маршрут", "квартал", "сигнал", "трамвай", "телефон", "почтальон", "фонарь", "компас", "рояль", "архив",
            "театр", "кадр", "закат", "залив", "хутор", "пруд", "сосед", "гость"};
    static final String[] TAIL = {"", "навсегда", "на рассвете", "в июле", "после дождя", "и тишина", "у моря",
            "в темноте", "на краю", "без названия", "и другие", "зимой", "в огне", "до утра", "на север", "в небе",
            "и полночь", "за стеной", "под снегом", "в пути", "у реки", "на ветру", "в тумане", "наяву", "во сне",
            "и эхо", "в пустыне", "в горах", "у причала", "на площади", "издалека", "в городе", "навстречу", "без конца",
            "до весны", "после полуночи", "и музыка", "в кадре", "вдвоём", "один"};
    static final String[] EN_ADJ = {"Silent", "Last", "Northern", "White", "Long", "Strange", "Night", "Fifth", "Summer",
            "Winter", "Distant", "Red", "Grey", "Old", "New", "Dark", "Clear", "Blue", "Sleepy", "Hidden"};
    static final String[] EN_NOUN = {"Harbor", "Flight", "Wind", "House", "Road", "City", "Garden", "Bridge", "Light",
            "Island", "Train", "Lighthouse", "Yard", "Forest", "Evening", "River", "Pass", "Pier", "Dream", "Voice", "Trace",
            "Fire", "Storm", "Noon", "Dawn"};
    static final String[] FIRST = {"Анна", "Мария", "Иван", "Пётр", "Ольга", "Глеб", "Вера", "Савва", "Лидия", "Арсений",
            "Tom", "Emma", "Jean", "Lena", "Oskar", "Clara", "Hugo", "Ines", "Marta", "Paolo"};
    static final String[] LAST = {"Сухотина", "Ветров", "Ланская", "Карпенко", "Остроумов", "Белозерская", "Градов",
            "Липатова", "Мещерский", "Звягина", "Mertens", "Holloway", "Dubois", "Lindqvist", "Ferreira", "Novak",
            "Kowalczyk", "Brandt", "Oliveira", "Halden"};

    /** Название по-русски, своё для каждого {@code i} < 40 × 50 × 40 = 80 000 (дальше — с номером части). */
    static String titleRu(int i) {
        int a = i % ADJ.length, b = (i / ADJ.length) % NOUN.length, c = (i / (ADJ.length * NOUN.length)) % TAIL.length;
        int part = i / (ADJ.length * NOUN.length * TAIL.length);
        String t = ADJ[a] + " " + NOUN[b] + (TAIL[c].isEmpty() ? "" : " " + TAIL[c]);
        return part == 0 ? t : t + " " + (part + 1);
    }

    /** Название по-английски, своё для каждого {@code i} (номер — в конце). */
    static String title(int i) {
        return "The " + EN_ADJ[i % EN_ADJ.length] + " " + EN_NOUN[(i / EN_ADJ.length) % EN_NOUN.length] + " " + (i / 500 + 1);
    }

    static String person(Rng r) {
        return FIRST[r.nextInt(FIRST.length)] + " " + LAST[r.nextInt(LAST.length)];
    }

    /** Фильм номер {@code i} с оценкой {@code rating}: поля — из {@code r}. */
    static ReviewContext film(Rng r, int i, int rating) {
        List<Integer> genres = new ArrayList<>();
        int ng = 1 + r.nextInt(3);
        for (int k = 0; k < ng; k++) genres.add(1 + r.nextInt(24));
        List<String> actors = new ArrayList<>();
        int na = r.nextInt(7);
        for (int k = 0; k < na; k++) actors.add(person(r));
        String director = r.chance(0.9) ? person(r) : null;
        String other = r.chance(0.35) ? titleRu((i * 7919 + 13) % 80_000) + " 2" : null;
        int year = r.chance(0.97) ? r.between(1920, 2026) : 0;
        return new ReviewContext(rating, 1000 + i, title(i), r.chance(0.75) ? titleRu(i) : null, year, genres, director,
                actors, other);
    }
}
