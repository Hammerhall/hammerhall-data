package io.hammerhall.data.mov.site.text;

import io.hammerhall.data.core.random.Rng;

import java.util.ArrayList;
import java.util.List;

/**
 * Шаблон фразы (разметка — в шапке {@code reviews.txt}): {@code [а|б]} — вариант на выбор, {@code <м|ж|с>} — по роду
 * жанра, {@code {X}} — поле ({@link Slot}). Разбирается один раз при чтении словаря; {@link #need} — какие поля нужны
 * (шаблон без поля у фильма не берётся). Экземпляр неизменяем.
 */
final class Tpl {
    /** Поля шаблонов. */
    enum Slot {
        /** Название фильма в «ёлочках». */
        T,
        /** Другой фильм в «ёлочках». */
        O,
        /** Режиссёр. */
        D,
        /** Актёр. */
        A,
        /** Второй актёр. */
        B,
        /** Год. */
        Y,
        /** Десятилетие: «1990-х». */
        DEC,
        /** Жанр (им.). */
        G,
        /** Жанр (род.). */
        Gg,
        /** Жанры (мн. им.). */
        Gp,
        /** Жанры (мн. род.). */
        Gpg,
        /** Оценка. */
        N,
        /** Оценка с «баллом». */
        Nb;

        int bit() {
            return 1 << ordinal();
        }

        static Slot of(String name, String src) {
            for (Slot s : values()) if (s.name().equals(name)) return s;
            throw new IllegalArgumentException("нет поля {" + name + "} в шаблоне: " + src);
        }
    }

    /** Значения полей для одного текста. */
    static final class Slots {
        private final String[] v = new String[Slot.values().length];
        private int have;
        /** Род жанра: 0 — мужской, 1 — женский, 2 — средний. */
        int gender;

        void set(Slot s, String value) {
            if (value == null || value.isEmpty()) {
                v[s.ordinal()] = null;
                have &= ~s.bit();
            } else {
                v[s.ordinal()] = value;
                have |= s.bit();
            }
        }

        String get(Slot s) {
            return v[s.ordinal()];
        }

        boolean has(Slot s) {
            return (have & s.bit()) != 0;
        }

        /** Есть все поля маски. */
        boolean fits(int need) {
            return (need & ~have) == 0;
        }

        /** Первое ли слово текста — значение поля (название, имя): такое слово не пишется со строчной. */
        boolean startsWithValue(String s) {
            if (s.startsWith("«")) return true;
            for (Slot x : new Slot[]{Slot.A, Slot.B, Slot.D}) {
                String val = v[x.ordinal()];
                if (val != null && s.startsWith(val)) return true;
            }
            return false;
        }
    }

    private sealed interface Node permits Lit, Var, Alt, Gen {
    }

    private record Lit(String text) implements Node {
    }

    private record Var(Slot slot) implements Node {
    }

    /** Варианты: каждый — последовательность узлов. */
    private record Alt(List<List<Node>> options) implements Node {
    }

    /** По роду жанра: мужской, женский, средний. */
    private record Gen(List<List<Node>> forms) implements Node {
    }

    final String source;
    /** Маска нужных полей ({@link Slot#bit()}). */
    final int need;
    private final List<Node> root;

    private Tpl(String source, int need, List<Node> root) {
        this.source = source;
        this.need = need;
        this.root = root;
    }

    static Tpl parse(String src) {
        Parser p = new Parser(src);
        List<Node> root = p.seq();
        if (p.pos < src.length()) throw new IllegalArgumentException("лишний «" + src.charAt(p.pos) + "» в шаблоне: " + src);
        return new Tpl(src, p.need, root);
    }

    /** Раскрыть шаблон: варианты — из {@code r}, поля — из {@code s} (должны быть все из {@link #need}). */
    String expand(Rng r, Slots s) {
        StringBuilder sb = new StringBuilder(160);
        emit(root, r, s, sb);
        return sb.toString();
    }

    private static void emit(List<Node> seq, Rng r, Slots s, StringBuilder sb) {
        for (Node n : seq) {
            switch (n) {
                case Lit l -> sb.append(l.text());
                case Var v -> {
                    String val = s.get(v.slot());
                    if (val == null) throw new IllegalStateException("нет поля " + v.slot());
                    sb.append(val);
                }
                case Alt a -> emit(a.options().get(r.nextInt(a.options().size())), r, s, sb);
                case Gen g -> emit(g.forms().get(s.gender), r, s, sb);
            }
        }
    }

    /** Число разных раскрытий без учёта полей (для оценки разнообразия словаря). */
    long variants() {
        return count(root);
    }

    private static long count(List<Node> seq) {
        long n = 1;
        for (Node x : seq) {
            long k = switch (x) {
                case Alt a -> {
                    long sum = 0;
                    for (List<Node> o : a.options()) sum += count(o);
                    yield sum;
                }
                default -> 1;
            };
            n = Math.multiplyExact(n, k);
        }
        return n;
    }

    @Override
    public String toString() {
        return source;
    }

    private static final class Parser {
        final String src;
        int pos;
        int need;

        Parser(String src) {
            this.src = src;
        }

        /** Последовательность до «|», «]», «>» или конца. */
        List<Node> seq() {
            List<Node> out = new ArrayList<>();
            StringBuilder lit = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '|' || c == ']' || c == '>') break;
                if (c == '[' || c == '<' || c == '{') {
                    if (!lit.isEmpty()) {
                        out.add(new Lit(lit.toString()));
                        lit.setLength(0);
                    }
                    pos++;
                    if (c == '[') {
                        out.add(new Alt(options(']')));
                    } else if (c == '<') {
                        List<List<Node>> f = options('>');
                        if (f.size() != 3) throw new IllegalArgumentException("<м|ж|с> — три формы: " + src);
                        need |= Slot.G.bit();
                        out.add(new Gen(f));
                    } else {
                        int end = src.indexOf('}', pos);
                        if (end < 0) throw new IllegalArgumentException("нет «}» в шаблоне: " + src);
                        Slot s = Slot.of(src.substring(pos, end), src);
                        need |= s.bit();
                        out.add(new Var(s));
                        pos = end + 1;
                    }
                    continue;
                }
                lit.append(c);
                pos++;
            }
            if (!lit.isEmpty()) out.add(new Lit(lit.toString()));
            return List.copyOf(out);
        }

        List<List<Node>> options(char close) {
            List<List<Node>> opts = new ArrayList<>();
            while (true) {
                opts.add(seq());
                if (pos >= src.length()) throw new IllegalArgumentException("нет «" + close + "» в шаблоне: " + src);
                char c = src.charAt(pos++);
                if (c == close) break;
                if (c != '|') throw new IllegalArgumentException("«" + c + "» вместо «" + close + "» в шаблоне: " + src);
            }
            return List.copyOf(opts);
        }
    }
}
