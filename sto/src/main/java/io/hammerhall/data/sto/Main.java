package io.hammerhall.data.sto;

import io.hammerhall.data.core.gen.Cli;

/**
 * Генератор набора «Кременъ» ({@code sto-data-v1}): команды — {@link Cli} ({@code generate}, {@code schema},
 * {@code verify}, {@code freeze}, {@code check}, {@code names}), набор — {@link StoDataset}. Запуск —
 * {@code ./build.sh sto run КОМАНДА …} из корня репозитория.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Cli.main(new StoDataset(), args);
    }
}
