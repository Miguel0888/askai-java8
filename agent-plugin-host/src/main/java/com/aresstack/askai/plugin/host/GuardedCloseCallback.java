package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.lifecycle.WorkspaceCloseCallback;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a {@link WorkspaceCloseCallback} so only the first of allow/veto takes effect. Later or duplicate
 * calls are ignored, which keeps a misbehaving plugin from answering a close request twice (or both ways).
 */
final class GuardedCloseCallback implements WorkspaceCloseCallback {

    private final AtomicBoolean answered = new AtomicBoolean(false);
    private final WorkspaceCloseCallback delegate;

    GuardedCloseCallback(WorkspaceCloseCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public void allowClose() {
        if (answered.compareAndSet(false, true)) {
            delegate.allowClose();
        }
    }

    @Override
    public void vetoClose(String reason) {
        if (answered.compareAndSet(false, true)) {
            delegate.vetoClose(reason);
        }
    }

    /** @return true once either outcome has been delivered. */
    boolean isAnswered() {
        return answered.get();
    }
}
