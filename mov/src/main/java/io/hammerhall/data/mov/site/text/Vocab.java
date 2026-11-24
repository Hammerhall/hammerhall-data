package io.hammerhall.data.mov.site.text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Словари текстов — ресурсы {@code io/hammerhall/data/mov/site/text/} (UTF-8). Формат {@code .txt}: строки «#» —
 * комментарии, {@code [секция]} — начало секции, дальше — непустые строки секции (как их читать — решает словарь: шаблон
 * на строку, слова через пробел, элементы через «|»). Файл без секций — просто строки ({@code tags.txt}). {@code .tsv} —
 * строки через табуляцию, первая — заголовок. Правка словаря меняет тексты выпуска — новая версия генератора (суммы
 * файлов — в тесте).
 */
final class Vocab {
    private Vocab() {
    }

    static final String DIR = "/io/hammerhall/data/mov/site/text/";
    /** Все файлы словарей (для проверки сумм и внутренних имён). */
    static final List<String> FILES = List.of("reviews.txt", "review-titles.txt", "names.txt", "logins.txt",
            "collections.txt", "tags.txt", "genres.tsv");

    static String resource(String file) {
        String path = DIR + file;
        try (InputStream in = Vocab.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("нет словаря " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("словарь " + path, e);
        }
    }

    /** Секции файла: имя → непустые строки (обрезанные), в порядке файла. */
    static Map<String, List<String>> sections(String file) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> cur = null;
        int no = 0;
        for (String line : resource(file).split("\n", -1)) {
            no++;
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("[") && t.endsWith("]") && t.indexOf(' ') < 0 && t.indexOf('|') < 0) {
                String name = t.substring(1, t.length() - 1);
                if (out.containsKey(name)) throw new IllegalStateException(file + ": секция дважды: " + name);
                cur = new ArrayList<>();
                out.put(name, cur);
                continue;
            }
            if (cur == null) throw new IllegalStateException(file + ":" + no + ": текст вне секции: " + t);
            cur.add(t);
        }
        Map<String, List<String>> ro = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : out.entrySet()) {
            if (e.getValue().isEmpty()) throw new IllegalStateException(file + ": секция [" + e.getKey() + "] пуста");
            ro.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(ro);
    }

    /** Строки файла без секций (комментарии и пустые — мимо). */
    static List<String> lines(String file) {
        List<String> out = new ArrayList<>();
        for (String line : resource(file).split("\n", -1)) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return List.copyOf(out);
    }

    /** Строки {@code .tsv} без заголовка и комментариев: поля через табуляцию. */
    static List<String[]> tsv(String file) {
        List<String[]> out = new ArrayList<>();
        boolean header = true;
        for (String line : resource(file).split("\n", -1)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            if (header) {
                header = false;
                continue;
            }
            String[] f = line.split("\t", -1);
            for (int i = 0; i < f.length; i++) f[i] = f[i].strip();
            out.add(f);
        }
        return out;
    }

    /** Элементы через «|» во всех строках секции. */
    static List<String> items(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            for (String p : l.split("\\|")) {
                String t = p.strip();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /** Слова через пробел во всех строках секции. */
    static List<String> words(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) for (String w : l.split("\\s+")) if (!w.isEmpty()) out.add(w);
        return List.copyOf(out);
    }
}
