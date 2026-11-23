package io.hammerhall.data.wld;

import io.hammerhall.data.core.gen.Cli;

/**
 * Генератор набора «Мир» ({@code wld-data-v1}): команды — {@link Cli} ({@code generate}, {@code schema},
 * {@code verify}, {@code freeze}, {@code check}, {@code names}), набор — {@link WldDataset}. Запуск —
 * {@code ./build.sh wld run КОМАНДА …} из корня репозитория.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Cli.main(new WldDataset(), args);
    }
}
