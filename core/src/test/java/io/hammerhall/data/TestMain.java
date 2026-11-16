package io.hammerhall.data;

import io.hammerhall.data.core.gen.DatasetContract;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

/**
 * Прогон JUnit 5 без Maven: {@code java -cp … io.hammerhall.data.TestMain} — тесты ядра и набора (их собирает
 * {@code build.sh <код>}). У набора обязан быть тест контракта — наследник {@link DatasetContract}.
 */
public final class TestMain {
    private TestMain() {
    }

    public static void main(String[] args) {
        LauncherDiscoveryRequest req = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectPackage("io.hammerhall.data")).build();
        Launcher launcher = LauncherFactory.create();
        TestPlan plan = launcher.discover(req);
        boolean contract = false;
        for (TestIdentifier root : plan.getRoots()) {
            for (TestIdentifier t : plan.getDescendants(root)) {
                if (t.getSource().orElse(null) instanceof ClassSource cs && cs.getJavaClass() != DatasetContract.class
                        && DatasetContract.class.isAssignableFrom(cs.getJavaClass())) {
                    contract = true;
                }
            }
        }
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(plan, listener);
        TestExecutionSummary s = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out, true);
        s.printTo(out);
        s.printFailuresTo(out, 20);
        if (!contract) out.println("нет теста контракта набора: в <код>/src/test нужен класс, наследующий DatasetContract");
        // в CI пропуск по допущению (нет входа набора, нет Python) — тоже провал: иначе тесты молча не идут
        boolean strict = System.getenv("CI") != null;
        if (strict && s.getTestsAbortedCount() > 0) out.println("CI: пропущено по допущению " + s.getTestsAbortedCount() + " тестов");
        System.exit(contract && s.getTotalFailureCount() == 0 && s.getTestsFoundCount() > 0
                && !(strict && s.getTestsAbortedCount() > 0) ? 0 : 1);
    }
}
