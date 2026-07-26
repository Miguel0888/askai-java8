package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.SwingUtilities;

/** {@link UiExecutor} backed by the Swing Event Dispatch Thread. */
public final class SwingUiExecutor implements UiExecutor {

    @Override
    public boolean isUiThread() {
        return SwingUtilities.isEventDispatchThread();
    }

    @Override
    public void execute(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (isUiThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    @Override
    public void assertUiThread() {
        if (!isUiThread()) {
            throw new IllegalStateException("This must be called on the Swing Event Dispatch Thread");
        }
    }
}
