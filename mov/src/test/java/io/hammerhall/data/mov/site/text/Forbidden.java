package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.check.PublicNames;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Запреты для текстов «Свартальвхейма» (общие для тестов пакета).
 *
 * <p>Скрытые темы — отпечатками, как внутренние имена в {@link PublicNames} и запреты «Дома Одина»: сам список в
 * открытом коде не пишется. Отпечаток — первые 16 знаков sha256 слова в нижнем регистре. {@link #STEMS} — основы с
 * начала кириллического слова (сверяются начала длиной {@link #STEM_MIN}–{@link #STEM_MAX} знаков): политика и власть,
 * названия мест вне набора, брань, оскорбления, внешность, уничижительные слова о народах, прочие внутренние имена набора и
 * соседних наборов (их нет в отпечатках ядра); {@link #WORDS} — кириллические слова целиком; {@link #LATIN} — латиница
 * внутри слова (длиной {@link #LATIN_MIN}–{@link #LATIN_MAX}): брань, политики, те же внутренние имена.
 *
 * <p>Открытым текстом — нейтральные слова, которых в текстах тоже быть не должно: реальные компании, сервисы и
 * премии ({@link #BRANDS}), национальности ({@link #NATIONS}), прошедшее время зрителя о себе — род зрителя
 * неизвестен ({@link #REVIEWER_GENDER}).
 */
final class Forbidden {
    private Forbidden() {
    }

    static final Set<String> STEMS = Set.of(
            "026889985ad55f1e", "058846ee14355a91", "08d0edd5dda185f0", "0b33277ef23ec508", "0c1a598b3548b0a3",
            "0ee3c4723efb3340", "108ddf9c491f91c6", "178a6dd7ead83f5b", "1cc9e59df7b1642c", "20c4a228d958c8ac",
            "250ca5f634761438", "28425b37e4be84c5", "28ec67bfcd9e143b", "2d80d1d92a464421", "314bfef2209142de",
            "352e0c1e738ee097", "3546da8f08cca825", "3961f61cfc8cb7ed", "398fca9f07d21520", "3d4095ffdf3332d3",
            "3e68c774864ddfa3", "3fcece1fc23ac715", "40716fcc5c3cb025", "457fc58508e73e1e", "4d1814cad7b80d76",
            "4fe0c3ea16a028d5", "501287a22b23071e", "542c3c630f506623", "562b901b61ecb786", "5649610c8bcb519b",
            "5922d540fb39357d", "5980d80720743c95", "5a8e3d439aea0c3d", "5ef794a6966763df", "603e4cbc80b34fa7",
            "613d62b53b374044", "642b70258f06e7b9", "6b4a9e6d7e2bc7a8", "6f6deffa9126d6d0", "719e14f27d779c10",
            "7bfdfbaea5200cbd", "7cbe447815e023ac", "7df2d68f45aaaf0c", "804a8c96b1e93d27", "81dd6bd9f68207b8",
            "83797b4f3450a625", "865cae5063d2e139", "874520d48ba21983", "8c617695cc2b7ea8", "997d44c81bb0c250",
            "9b3b73c7ac5925c7", "9c84b7def28ca583", "a2f5314a85aa5eb4", "a8797bcd386ec8e6", "a9d73a4c878e53ed",
            "ac092ab0b10c0501", "ae804cb6de2dc82c", "aeb6d1fc5ec80490", "b62253218f9976ad", "b79c4a4df06df348",
            "b81582cd8f821b43", "bc8236e4feda2893", "c248c8193aed64c3", "c420e68f6f668050", "c716f3a396f176c2",
            "c74a47868ec6814b", "c84cc59223a57a3b", "c88e50e2e37bdcca", "c98db0f49a1aec1d", "cac84fe669d53683",
            "cbec2ffb5668d39d", "cbefee800e850839", "cd727563977ee841", "ce020a07447d0d32", "d05661c81df53f54",
            "d33a8b73c3f739c9", "d3a345400cbee22e", "d41067b4c37b7d4e", "d4d8457a44dcf61c", "d69453b77ee3703f",
            "d74a2fa10ca4be11", "d792139dd724a281", "d8f5fda488169474", "e5e02c7d09fe18d8", "e6c71fbe42a456aa",
            "e70b15fb5b49e2c7", "e735609cc609efe6", "e891c3e4360d128a", "ebc715d63ee3fa91", "ecd272c8149e6d82",
            "eef7e783d8059423", "f1240105f54c03fe", "f3dcd5e6ce37de3e", "f4d98a5bf56521e0", "f71c0462f9b2ce2b",
            "f9c07040053a305d", "f9f0f735efcac4e5", "fa77563e108959e4", "fd6f0a815733b600");
    static final Set<String> WORDS = Set.of(
            "0ee93aa04ef62b82", "167e82b2507758d4", "2d3bc04310fd9aa7", "32d07f3dd97a1c3b", "36b0c8c76ed6b9e3",
            "4016248f03a1818e", "43aa53816900e29f", "4ff67414271923dc", "55f97c6b58ed8f2b", "6832a820b53c9915",
            "9b3512e8e53c91a1", "ac684dd2cf25df84", "b0134561c4b13168", "b9131c4b8ca2d6fe", "c161f3c870767286",
            "d247490f2b2391a0", "d298016494cb64a6", "ea182fd05c627e44", "ea6d4563fdacd35f");
    static final Set<String> LATIN = Set.of(
            "038c9ccdd226f572", "0631a609edb7c79f", "0951caa80b947130", "10fa8018c94a0cd9", "2a6937d721b14cdd",
            "32ded712566e352e", "566f532d486c9477", "59cc3cb8a4297301", "66a343906358730f", "6ac3c336e4094835",
            "85fc17f7069acd39", "8c5c04391361cbf4", "98d44e13f455d916", "9ae315a94e428a7e", "a60a52382d707771",
            "ab080737bac518b3", "ad505b0be8a49b89", "c2c3b68b48832afd", "d75a838dc758ba17", "e43b64ac515180de",
            "ebf1cce064da7d55", "fd70ad909b94deb2");
    static final int STEM_MIN = 3, STEM_MAX = 12, LATIN_MIN = 3, LATIN_MAX = 7;

    /** Реальные компании, сервисы, студии, премии. */
    static final Pattern BRANDS = Pattern.compile("(?iu)(?<!\\p{L})(netflix|youtube|ютуб|кинопоиск|imdb|okko|окко|"
            + "амедиатек|hbo|amazon|амазон|apple|google|гугл|яндекс|yandex|сбер|тинькофф|tinkoff|вконтакт|telegram|"
            + "телеграм|disney|дисней|warner|pixar|пиксар|marvel|марвел|oscar|оскароносн|cannes|берлинале|ivi|иви)(?!\\p{L})");
    /** Национальности и народы (критика — только работы, о людях — ни слова). */
    static final Pattern NATIONS = Pattern.compile("(?iu)(?<!\\p{L})(русск|американ|англичан|британ|француз|немец|немц|"
            + "немецк|итальян|испанец|испанск|китай|китаец|японск|японц|корейск|индийск|индиец|еврей|евре|грузин|армян|"
            + "азербайдж|украин|белорус|казах|узбек|таджик|татар|чечен|цыган|кавказ|азиат|африкан|арабск|негрит|славян|"
            + "мексикан|турецк|турок)");
    /** Зритель о себе в прошедшем времени: «посмотрел», «ждала», «доволен» — род зрителя неизвестен. */
    static final Pattern REVIEWER_GENDER = Pattern.compile("(?iu)(?<!\\p{L})((пере)?посмотрел(а)?|ожидал(а)?|ждал(а)?|"
            + "пожалел(а)?|доволен|довольна|разочарован(а)?|понял(а)?|увидел(а)?|плакал(а)?|смеял(ся|ась)|уснул(а)?|"
            + "заснул(а)?|включил(а)?|выбрал(а)?|пошёл|пошла|решил(а)?|подумал(а)?|согласен|согласна|уверен(а)?|"
            + "рад|рада|благодарен|благодарна|готов(а)?)(?!\\p{L})");

    static String fingerprint(String word) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(word.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Что не так с текстом: {@code null} — всё чисто, иначе — пометка (скрытые слова не печатаются: только длина).
     * Внутренние имена ядра — словом и латиницей ({@link Tx#internal}).
     */
    static String check(String text) {
        if (text == null) return null;
        Matcher m = BRANDS.matcher(text);
        if (m.find()) return "компания или сервис: " + m.group(1);
        m = NATIONS.matcher(text);
        if (m.find()) return "национальность: " + m.group(1);
        String s = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        String raw = text.toLowerCase(Locale.ROOT);
        int n = s.length();
        for (int i = 0; i < n; ) {
            if (!Character.isLetter(s.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && Character.isLetter(s.charAt(j))) j++;
            String w = s.substring(i, j), wr = raw.substring(i, j);
            if (Tx.internal(wr)) return "внутреннее имя #" + w.length();
            if (WORDS.contains(fingerprint(w)) || WORDS.contains(fingerprint(wr))) return "скрытое слово #" + w.length();
            for (int len = STEM_MIN; len <= Math.min(STEM_MAX, w.length()); len++) {
                if (STEMS.contains(fingerprint(w.substring(0, len))) || STEMS.contains(fingerprint(wr.substring(0, len)))) {
                    return "скрытая основа #" + len + " в слове #" + w.length();
                }
            }
            // латиница — только у латинских слов (транслитерация русских слов дала бы ложные совпадения внутри слова)
            for (int a = 0; a < wr.length(); a++) {
                for (int b = a + LATIN_MIN; b <= Math.min(wr.length(), a + LATIN_MAX); b++) {
                    String sub = wr.substring(a, b);
                    if (sub.chars().allMatch(c -> c >= 'a' && c <= 'z') && LATIN.contains(fingerprint(sub))) {
                        return "скрытая латиница #" + sub.length() + " в слове #" + w.length();
                    }
                }
            }
            i = j;
        }
        return null;
    }
}
