package com.aresstack.askai.plugin.api.service;

/**
 * Abstraction over the Swing EDT so plugins never touch {@code SwingUtilities} directly. Backend events must
 * be marshalled through this before touching Swing components.
 */
public interface UiExecutor {

    boolean isUiThread();

    void execute(Runnable runnable);

    void assertUiThread();
}
