package io.hammerhall.data.mov;

import io.hammerhall.data.core.gen.Cli;

/**
 * Генератор набора «Кино» ({@code mov-data-v1}): команды — {@link Cli} ({@code generate}, {@code schema},
 * {@code verify}, {@code freeze}, {@code check}, {@code names}), набор — {@link MovDataset}. Запуск —
 * {@code ./build.sh mov run КОМАНДА …} из корня репозитория.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Cli.main(new MovDataset(), args);
    }
}
