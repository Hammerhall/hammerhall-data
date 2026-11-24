package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.check.PublicNames;
import io.hammerhall.data.core.random.RandomStreams;
import io.hammerhall.data.core.random.Rng;
import io.hammerhall.data.mov.site.text.Tpl.Slot;
import io.hammerhall.data.mov.site.text.Tpl.Slots;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Словари текстов: суммы sha256 зафиксированы (правка словаря меняет тексты выпуска — новая версия генератора, суммы
 * правятся здесь же), как и отпечаток образца текстов; каждый шаблон разбирается; в словарях нет запретов (PublicNames —
 * проверкой папки ресурсов целиком); вердикты всех тонов несут свой маркер в каждом раскрытии; зритель о себе — без рода.
 */
class TextVocabTest {
    static final Map<String, String> SHA256 = new LinkedHashMap<>();

    static {
        // SHA256-BEGIN
        SHA256.put("reviews.txt", "9d3939519ba586de4efba139fdb999622b02c402a8e9d27fbe1da05ca2ab57c3");
        SHA256.put("review-titles.txt", "11f0c4dc293e59515e1d8c13d0f6e7624d909e9ae8aac8a1f20563dcc053a519");
        SHA256.put("names.txt", "e90e325e95318dcfccc9cfc2a378ad011f39d1395735a04f6c1c3613030fdbc5");
        SHA256.put("logins.txt", "acdcd6960c08a265d70ea7c984cd3ba3d7c5ca51d9c00e55ad1d003a555b2ffd");
        SHA256.put("collections.txt", "4d7df207190925a0a07d8b66d51cb726a08f43662ecda40f07be9ae3dd61da38");
        SHA256.put("tags.txt", "c36cd9c4f801c35826ec5de5d19353750c6e594e83baace9760bf2bd7d176320");
        SHA256.put("genres.tsv", "ff81a0d786e0e859c0b525302cad6ae9f8b667a2d80509200a0a9a0721155ea0");
        // SHA256-END
    }

    static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Суммы словарей зафиксированы")
    void fingerprints() throws Exception {
        StringBuilder now = new StringBuilder();
        Map<String, String> actual = new LinkedHashMap<>();
        for (String f : Vocab.FILES) {
            String h = sha256(Vocab.resource(f));
            actual.put(f, h);
            now.append("        SHA256.put(\"").append(f).append("\", \"").append(h).append("\");\n");
        }
        assertEquals(SHA256, actual, "суммы словарей изменились — новая версия генератора; новые суммы:\n" + now);
    }

    /** Отпечаток образца: 3000 рецензий (заголовок и текст), логины, имена, подборки, теги — seed 20251001. */
    static final String TEXTS_SHA256 = "f6b23d9199f4a308e7a4803492f935797c2c0f467e9fdf67181d50574cb42899";

    @Test
    @DisplayName("Отпечаток образца текстов зафиксирован")
    void textsFingerprint() throws Exception {
        SiteTextsRu t = new SiteTextsRu();
        RandomStreams streams = new RandomStreams(20251001L);
        RandomStreams.Stream fs = streams.stream("films"), rs = streams.stream("reviews"), us = streams.stream("users");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            ReviewContext c = Samples.film(fs.at(i), i, 1 + i % 10);
            Rng r = rs.at(i);
            sb.append(t.reviewTitle(r, c)).append('|').append(t.reviewBody(r, c)).append('\n');
            Rng u = us.at(i);
            sb.append(t.login(u)).append('|').append(t.displayName(u, i % 2 == 0 ? 'M' : 'F')).append('|')
                    .append(t.collectionTitle(u, 1 + i % 24)).append('\n');
        }
        sb.append(String.join("|", t.tags()));
        assertEquals(TEXTS_SHA256, sha256(sb.toString()), "образец текстов изменился — новая версия генератора");
    }

    @Test
    @DisplayName("Шаблоны разбираются, секции не пусты; у каждого тона есть начала и вердикты без полей фильма")
    void templates() {
        SiteTextsRu t = new SiteTextsRu();
        int n = 0;
        long variants = 0;
        Map<String, Integer> byTone = new LinkedHashMap<>();
        for (Section s : t.reviews().allSections()) {
            assertTrue(!s.items.isEmpty(), s.name);
            for (Tpl x : s.items) {
                n++;
                variants += x.variants();
                for (String tone : new String[]{"R", "G", "M", "D", "H"}) {
                    if (s.name.endsWith("." + tone)) byTone.merge(tone, 1, Integer::sum);
                }
            }
        }
        Slots none = new Slots();
        none.set(Slot.N, "5");
        none.set(Slot.Nb, "5 баллов");
        for (Section s : t.reviews().allSections()) {
            if (s.name.startsWith("open.") || s.name.startsWith("close.") || s.name.startsWith("title.")) {
                assertTrue(s.any(none), s.name + ": нет шаблона без полей фильма");
            }
        }
        for (Section s : t.collections().allSections()) assertTrue(!s.items.isEmpty(), s.name);
        System.out.println("шаблонов рецензий: " + n + " (раскрытий без полей — " + variants + "); по тонам (начала, вердикты, "
                + "сравнения, заголовки): " + byTone);
        assertTrue(n > 350, "шаблонов " + n);
    }

    @Test
    @DisplayName("Вердикты несут тон в каждом раскрытии; зритель о себе — без рода во всех шаблонах")
    void verdictMarkersEverywhere() {
        SiteTextsRu t = new SiteTextsRu();
        Slots s = new Slots();
        s.set(Slot.T, "«Икс»");
        s.set(Slot.O, "«Игрек»");
        s.set(Slot.D, "Пётр Ветров");
        s.set(Slot.A, "Анна Ланская");
        s.set(Slot.B, "Tom Holloway");
        s.set(Slot.Y, "1994");
        s.set(Slot.DEC, "1990-х");
        s.set(Slot.G, "комедия");
        s.set(Slot.Gg, "комедии");
        s.set(Slot.Gp, "комедии");
        s.set(Slot.Gpg, "комедий");
        s.gender = 1;
        s.set(Slot.N, "5");
        s.set(Slot.Nb, "5 баллов");
        Map<String, Pattern> marker = Map.of("close.R", ReviewTextsTest.RAPTURE, "close.G", ReviewTextsTest.GOOD,
                "close.M", ReviewTextsTest.MID, "close.D", ReviewTextsTest.NEG, "close.H", ReviewTextsTest.NEG);
        List<String> bad = new ArrayList<>();
        Rng r = new Rng(7);
        for (Section sec : t.reviews().allSections()) {
            for (Tpl x : sec.items) {
                for (int i = 0; i < 60; i++) {
                    String e = Tx.tidy(x.expand(r, s));
                    Pattern p = marker.get(sec.name);
                    if (p != null && !p.matcher(e).find()) bad.add(sec.name + " без маркера: " + e);
                    Matcher g = Forbidden.REVIEWER_GENDER.matcher(e);
                    if (g.find()) bad.add(sec.name + ": зритель с родом (" + g.group() + "): " + e);
                    // жанровые плюсы и минусы идут и как сдержанные, и как сильные
                    boolean gsPlus = sec.name.matches("gs\\.\\d+\\.p"), gsMinus = sec.name.matches("gs\\.\\d+\\.n");
                    if (sec.name.endsWith(".p1") || gsPlus || sec.name.startsWith("tail.p") || sec.name.startsWith("conn.")) {
                        if (ReviewTextsTest.RAPTURE_STRICT.matcher(e).find()) bad.add(sec.name + ": восторг в сдержанной похвале: " + e);
                    }
                    if (sec.name.endsWith(".p1") || sec.name.endsWith(".p2") || gsPlus || sec.name.startsWith("tail.p")
                            || sec.name.equals("tail.open.p") || sec.name.equals("open.R") || sec.name.equals("close.R")
                            || sec.name.equals("compare.R")) {
                        if (ReviewTextsTest.NEG.matcher(e).find()) bad.add(sec.name + ": критика в похвале: " + e);
                    }
                    if ((sec.name.endsWith(".n1") || gsMinus || sec.name.equals("tail.n")) && ReviewTextsTest.STRONG_NEG.matcher(e).find()) {
                        bad.add(sec.name + ": разгром в мягком минусе: " + e);
                    }
                }
                if (bad.size() > 30) break;
            }
        }
        assertEquals(List.of(), bad.stream().distinct().toList());
    }

    @Test
    @DisplayName("Имена людей ({A}, {B}, {D}) — только в шаблонах похвалы: ни в минусах, ни в критике, ни в плохих вердиктах и заголовках")
    void namesOnlyInPraiseTemplates() {
        SiteTextsRu t = new SiteTextsRu();
        int people = Slot.A.bit() | Slot.B.bit() | Slot.D.bit();
        List<String> bad = new ArrayList<>();
        int withNames = 0;
        for (Section sec : t.reviews().allSections()) {
            String n = sec.name;
            boolean praise = n.endsWith(".p1") || n.endsWith(".p2") || n.equals("title.R") || n.equals("title.G");
            for (Tpl x : sec.items) {
                if ((x.need & people) == 0) continue;
                withNames++;
                if (!praise) bad.add(n + ": " + x.source);
            }
        }
        assertEquals(List.of(), bad);
        assertTrue(withNames >= 20, "шаблонов похвалы с именем: " + withNames);
        System.out.println("шаблонов с именем человека: " + withNames + " — все в похвале");
    }

    @Test
    @DisplayName("Словари без запретов: PublicNames по папке ресурсов; политика, брань, компании, национальности — по словам и фразам")
    void vocabClean() throws URISyntaxException, java.io.IOException {
        Path dir = Path.of(SiteTextsRu.class.getResource(Vocab.DIR).toURI());
        PublicNames.Scan scan = PublicNames.scan(dir);
        assertTrue(scan.clean(), "PublicNames.scan: " + scan.hits());
        // в папке пакета — и словари, и классы (у классов проверяется имя файла)
        assertTrue(scan.files() >= Vocab.FILES.size(), "файлов в папке словарей: " + scan.files());
        List<String> bad = new ArrayList<>();
        for (String f : Vocab.FILES) {
            for (String line : Files.readAllLines(dir.resolve(f), StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) continue;
                String why = Forbidden.check(line);
                if (why != null) bad.add(f + ": " + why);
            }
        }
        assertEquals(List.of(), bad);
        System.out.println("словари: " + scan.files() + " файлов, " + scan.words() + " разных слов — чисто");
    }
}
