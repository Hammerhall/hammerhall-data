package io.hammerhall.data.air;

import io.hammerhall.data.core.gen.Cli;

/**
 * Генератор набора «Авиаперевозки» ({@code air-data-v1}): команды — {@link Cli} ({@code generate}, {@code schema},
 * {@code verify}, {@code freeze}, {@code check}, {@code names}), набор — {@link AirDataset}. Запуск —
 * {@code ./build.sh air run КОМАНДА …} из корня репозитория.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Cli.main(new AirDataset(), args);
    }
}
