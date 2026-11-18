package io.hammerhall.data.auc.text;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Словари — ресурсы генератора: суммы sha256 зафиксированы (правка словаря меняет содержимое выпуска — новая версия
 * генератора, суммы правятся здесь же); дерево категорий — до трёх уровней, коды без повторов, у каждого отдела есть
 * листья, у каждого узла — английское название.
 */
class VocabTest {
    static final Map<String, String> SHA256 = new LinkedHashMap<>();

    static {
        // SHA256-BEGIN
        SHA256.put("names", "2d2c721fd5198b820e56cf364f1627e1eb6ecba88c97a7c46d7d26e566b36451");
        SHA256.put("real-names", "92ac0ae74bccebb91143ec122035c8eddecc55232a7339c1c12b43b0d69fc50b");
        SHA256.put("categories", "9f8da6621c7582a02fc7947af6c6bcd5033549dff47329930bfd5da18513baab");
        SHA256.put("common", "cca1b349379358d2d23ca2b322c41660e29b74eeb644828d8e904aa0f11dec58");
        SHA256.put("painting", "f64142e382ac6f9547b9b5c4c510ae4c22d3f3f8da2e1103bc7aa909708f6f89");
        SHA256.put("jewellery", "839edf2ce9ce8e183b227b75f4d4f82c1eab7bd0d01494550766d8bf2222a723");
        SHA256.put("coins", "207ef8c1f79c36e9491f9c6c804d5e49950275a55eb8be5758f3f16836090418");
        SHA256.put("books", "7bad4c5d2f4e8c5d34c81feb0d6282db0e74a68886fc4117d71b403ffbfde176");
        SHA256.put("decorative", "c3935a6f7958e6ad460bf8b4777060fc3dfc2249bf3ed415147483e66326dc71");
        SHA256.put("collectibles", "669c413967881a1a85f50b1f0cf535da0ca624becd76468f7be77b3ca44e09d3");
        SHA256.put("extra", "edca2fc8d8d03cf69af219da83a0902055944940ed34139ce94c0525e155436b");
        // SHA256-END
    }

    static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fingerprints() throws Exception {
        StringBuilder now = new StringBuilder();
        Map<String, String> actual = new LinkedHashMap<>();
        for (String f : V.FILES) {
            String h = sha256(V.resource(f));
            actual.put(f, h);
            now.append("        SHA256.put(\"").append(f).append("\", \"").append(h).append("\");\n");
        }
        assertEquals(SHA256, actual, "суммы словарей изменились — новая версия генератора; новые суммы:\n" + now);
    }

    /** Отпечаток образца текстов: 3000 вещей seed 20251001 (все поля, звенья происхождения, справочник мастеров). */
    static final String TEXTS_SHA256 = "fa8804388041c5b7ae11c8a774c6a77943dbf11c66e5715ab147efb62c8c38f3";

    @Test
    void textsFingerprint() throws Exception {
        LotTexts texts = new LotTexts(20251001L);
        StringBuilder sb = new StringBuilder();
        for (Maker m : texts.makers().all()) sb.append(java.util.Arrays.toString(Makers.row(m))).append('|').append(m.bioRu()).append('\n');
        Department[] ds = Department.values();
        for (int i = 0; i < 3000; i++) sb.append(texts.lot(ds[i % ds.length], i)).append('\n');
        assertEquals(TEXTS_SHA256, sha256(sb.toString()), "образец текстов изменился — новая версия генератора");
    }

    @Test
    void categoryTree() {
        Set<String> codes = new HashSet<>();
        int leaves = 0;
        for (Category c : Category.all()) {
            assertTrue(codes.add(c.code()), "код дважды: " + c.code());
            assertTrue(c.level() >= 1 && c.level() <= 3, c.code());
            assertTrue(c.nameEn() != null && !c.nameEn().isBlank() && c.nameRu().length() <= 60 && c.nameEn().length() <= 60, c.code());
            if (c.parent() != null) assertEquals(c.parent().department(), c.department(), c.code());
            if (c.isLeaf()) {
                leaves++;
                assertTrue(c.weight() > 0, c.code());
                if (c.makerKind() != null && c.makerKind().department != c.department()) {
                    // мастер другого отдела допустим у гравюр из книг (художник-гравёр) и у бронзы (скульптор)
                    assertTrue(c.kind() == Kind.PRINT || c.kind() == Kind.BRONZE || c.kind() == Kind.POSTCARD, "мастер чужого отдела: " + c.code());
                }
            }
        }
        for (Department d : Department.values()) assertTrue(!d.leaves().isEmpty(), d + ": нет листьев");
        System.out.println("категорий: " + Category.all().size() + ", листьев: " + leaves);
        assertTrue(Category.all().size() >= 100, "узлов " + Category.all().size());
    }

    @Test
    void everySectionUsed() {
        // каждый файл словаря читается без ошибок, секции не пусты
        for (String f : V.FILES) {
            List<String> secs = V.sectionNames(f);
            assertTrue(!secs.isEmpty(), f);
            for (String s : secs) assertTrue(!V.raw(f, s).isBlank(), f + " [" + s + "] пуста");
        }
    }
}
