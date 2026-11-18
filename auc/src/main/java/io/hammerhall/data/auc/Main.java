package io.hammerhall.data.auc;

import io.hammerhall.data.core.gen.Cli;

/**
 * Генератор набора «Дом Одина» ({@code auc-data-v1}): команды — {@link Cli} ({@code generate}, {@code schema},
 * {@code verify}, {@code freeze}, {@code check}, {@code names}), набор — {@link AucDataset}. Запуск —
 * {@code ./build.sh auc run КОМАНДА …} из корня репозитория.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Cli.main(new AucDataset(), args);
    }
}
