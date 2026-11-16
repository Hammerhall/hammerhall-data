package io.hammerhall.data.core.check;

import java.util.ArrayList;
import java.util.List;

/**
 * Итог самопроверки сборки: именованные проверки, у каждой — да/нет и пояснение. Провал любой — сборка не годна
 * (выход с ошибкой, манифест не пишется).
 */
public final class CheckReport {
    public record Result(String name, boolean ok, String detail) {
    }

    private final List<Result> results = new ArrayList<>();

    public synchronized void ok(String name, String detail) {
        results.add(new Result(name, true, detail));
    }

    public synchronized void fail(String name, String detail) {
        results.add(new Result(name, false, detail));
    }

    /** Проверка «ноль нарушений»: список нарушений пуст — да; иначе — первые примеры в пояснении. */
    public synchronized void expectNone(String name, List<String> violations, String okDetail) {
        if (violations.isEmpty()) {
            ok(name, okDetail);
        } else {
            StringBuilder sb = new StringBuilder(violations.size() + " нарушений; например: ");
            for (int i = 0; i < Math.min(5, violations.size()); i++) {
                if (i > 0) sb.append("; ");
                sb.append(violations.get(i));
            }
            fail(name, sb.toString());
        }
    }

    /** Проверка «ноль нарушений», когда нарушения считаются отдельно от примеров (примеров — первые несколько). */
    public synchronized void expectNone(String name, long count, List<String> examples, String okDetail) {
        if (count == 0) {
            ok(name, okDetail);
        } else {
            StringBuilder sb = new StringBuilder(count + " нарушений; например: ");
            for (int i = 0; i < Math.min(5, examples.size()); i++) {
                if (i > 0) sb.append("; ");
                sb.append(examples.get(i));
            }
            fail(name, sb.toString());
        }
    }

    public synchronized void expect(String name, boolean cond, String detail) {
        if (cond) ok(name, detail);
        else fail(name, detail);
    }

    public synchronized List<Result> results() {
        return List.copyOf(results);
    }

    public synchronized boolean allOk() {
        for (Result r : results) if (!r.ok) return false;
        return true;
    }

    public synchronized String render() {
        StringBuilder sb = new StringBuilder();
        int failed = 0;
        for (Result r : results) {
            sb.append(r.ok ? "  OK   " : "  НЕТ  ").append(r.name);
            if (r.detail != null && !r.detail.isEmpty()) sb.append(" — ").append(r.detail);
            sb.append('\n');
            if (!r.ok) failed++;
        }
        sb.append(failed == 0 ? "  проверок: " + results.size() + ", все пройдены\n"
                : "  проверок: " + results.size() + ", провалено: " + failed + "\n");
        return sb.toString();
    }
}
