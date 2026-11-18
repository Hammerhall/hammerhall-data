package io.hammerhall.data.auc.text;

import io.hammerhall.data.core.random.Rng;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Словари — ресурсы генератора ({@code io/hammerhall/data/auc/vocab/<файл>.txt}, UTF-8). Формат файла:
 * <pre>
 *   # комментарий
 *   [СЕКЦИЯ]
 *   элемент | элемент | элемент          — элементы через «|», переводы строк — как пробел
 *   по-русски = in English | …            — пара: русское и английское (у названий)
 * </pre>
 * Правка словаря меняет содержимое выпуска — новая версия генератора; суммы файлов — в тесте ({@code VocabTest}).
 */
final class V {
    private V() {
    }

    /** Пара «по-русски — по-английски». */
    record P(String ru, String en) {
    }

    /** Файлы словарей в пакете ресурсов (для проверки сумм). */
    static final List<String> FILES = List.of("names", "real-names", "categories", "common", "painting", "jewellery",
            "coins", "books", "decorative", "collectibles", "extra");

    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    static String resource(String file) {
        String path = "/io/hammerhall/data/auc/vocab/" + file + ".txt";
        try (InputStream in = V.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("нет словаря " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("словарь " + path, e);
        }
    }

    private static Map<String, String> sections(String file) {
        return CACHE.computeIfAbsent(file, f -> {
            Map<String, String> out = new LinkedHashMap<>();
            String cur = null;
            StringBuilder sb = new StringBuilder();
            for (String line : resource(f).split("\n", -1)) {
                String t = line.strip();
                if (t.startsWith("#")) continue;
                if (t.startsWith("[") && t.endsWith("]")) {
                    if (cur != null) out.put(cur, sb.toString());
                    cur = t.substring(1, t.length() - 1);
                    if (out.containsKey(cur)) throw new IllegalStateException(f + ": секция дважды: " + cur);
                    sb.setLength(0);
                    continue;
                }
                if (cur == null) {
                    if (!t.isEmpty()) throw new IllegalStateException(f + ": текст вне секции: " + t);
                    continue;
                }
                sb.append(line).append('\n');
            }
            if (cur != null) out.put(cur, sb.toString());
            return out;
        });
    }

    static String raw(String file, String section) {
        String s = sections(file).get(section);
        if (s == null) throw new IllegalStateException("нет секции [" + section + "] в словаре " + file);
        return s;
    }

    static List<String> sectionNames(String file) {
        return new ArrayList<>(sections(file).keySet());
    }

    /** Элементы секции (у пар — русская часть). */
    static String[] list(String file, String section) {
        P[] p = pairs(file, section, false);
        String[] out = new String[p.length];
        for (int i = 0; i < p.length; i++) out[i] = p[i].ru();
        return out;
    }

    /** Пары секции: у каждого элемента обязателен английский. */
    static P[] pairs(String file, String section) {
        return pairs(file, section, true);
    }

    private static P[] pairs(String file, String section, boolean needEn) {
        List<P> out = new ArrayList<>();
        for (String item : W.list(raw(file, section))) {
            int eq = item.indexOf(" = ");
            String ru = eq < 0 ? item : item.substring(0, eq).strip();
            String en = eq < 0 ? null : item.substring(eq + 3).strip();
            if (needEn && (en == null || en.isEmpty())) throw new IllegalStateException(file + " [" + section + "]: нет английского у «" + ru + "»");
            out.add(new P(ru, en));
        }
        if (out.isEmpty()) throw new IllegalStateException(file + " [" + section + "] пуста");
        return out.toArray(new P[0]);
    }

    /** Слова секции через пробелы. */
    static String[] words(String file, String section) {
        return W.words(raw(file, section));
    }

    /** Строки секции (непустые, обрезанные). */
    static String[] lines(String file, String section) {
        List<String> out = new ArrayList<>();
        for (String l : raw(file, section).split("\n")) {
            String t = l.strip();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    static P pick(Rng r, P[] a) {
        return a[r.nextInt(a.length)];
    }
}
