package com.aresstack.askai.research.agent.narration;

/**
 * Asynchronous text generator behind the narration lifecycle: takes a request, later delivers exactly one
 * terminal callback (narration or failure) on ANY thread — the {@link NarrationCoordinator} marshals,
 * guards against staleness and enforces the fallback. Implementations: the LLM narrator (production) and
 * scripted/delaying fakes (tests).
 */
public interface AsyncNarrator {

    interface Callback {
        void onNarration(String text);

        void onFailure(String reason);
    }

    NarrationHandle narrate(NarrationRequest request, Callback callback);
}
