package com.aresstack.askai.research.runtime.team;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link MainModelChat} whose underlying client can be hot-swapped at a safe turn boundary. The
 * {@link ResearchTeamAgent} holds ONE of these for the whole session, so a central main-model change (picked up
 * from the inference descriptor between turns) replaces only the transport underneath — the agent's conversation
 * history, proposed/confirmed scope and any pending turn are never touched.
 *
 * <p>The swap is a single atomic reference set: a call already in flight finishes on the old client, and the
 * next {@link #complete(List, double, int)} uses the new one.</p>
 */
public final class ReloadableMainModelChat implements MainModelChat {

    private final AtomicReference<MainModelChat> delegate;

    public ReloadableMainModelChat(MainModelChat initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial must not be null");
        }
        this.delegate = new AtomicReference<MainModelChat>(initial);
    }

    /** Replace the underlying client. Called only between turns (idle), never mid-request. */
    public void swap(MainModelChat next) {
        if (next == null) {
            throw new IllegalArgumentException("next must not be null");
        }
        delegate.set(next);
    }

    public MainModelChat current() {
        return delegate.get();
    }

    @Override
    public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        return delegate.get().complete(messages, temperature, maxOutputTokens);
    }

    @Override
    public String modelName() {
        return delegate.get().modelName();
    }
}
