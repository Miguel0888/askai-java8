package com.aresstack.askai.research.agent;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mints the ids under which this session's messages are PERSISTED — and therefore the keys the
 * {@link ResearchPhaseJournal} attributes phases to.
 * <p>
 * The counters alone are not enough: they restart at 1 in every session run, so after a restart the first
 * new message of a chat would reuse an id an OLDER persisted message already carries. The journal would then
 * re-attribute that old message to the new phase — a silently wrong history. Every run therefore gets its own
 * token, which makes ids unique across restarts of the same chat.
 * <p>
 * Ids coming from elsewhere (runtime events, approvals) are qualified through {@link #qualify(String)} for
 * the same reason: their numbering is per agent run, not per chat.
 */
public final class ResearchMessageIds {

    private final String runToken;
    private final AtomicLong counter = new AtomicLong();

    public ResearchMessageIds() {
        this(UUID.randomUUID().toString().substring(0, 8));
    }

    ResearchMessageIds(String runToken) {
        this.runToken = runToken;
    }

    /** A fresh id for a message this session produces, e.g. {@code 4f2a9c01-user-3}. */
    public String next(String kind) {
        return runToken + "-" + kind + "-" + counter.incrementAndGet();
    }

    /** Bind a foreign id (runtime event, approval) to THIS run so it cannot collide with an earlier one. */
    public String qualify(String foreignId) {
        return foreignId == null || foreignId.trim().isEmpty()
                ? runToken + "-anon-" + counter.incrementAndGet()
                : runToken + "-" + foreignId.trim();
    }
}
