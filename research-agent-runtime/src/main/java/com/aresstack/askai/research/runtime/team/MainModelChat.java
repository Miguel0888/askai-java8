package com.aresstack.askai.research.runtime.team;

import java.util.List;

/**
 * The seam the TeamAgent talks to for main-model chat completions. The productive implementation is
 * {@link HttpMainModelChatClient} (Ollama {@code /api/chat} over the host-published inference descriptor);
 * tests inject a scripted fake so the whole conversation engine runs without a real model. The agent performs
 * NO model management — which model answers is entirely the descriptor AskAI wrote.
 */
public interface MainModelChat {

    /**
     * Complete a chat exchange. {@code messages} is the full ordered context (a leading system message, then
     * the alternating user/assistant history ending with the latest user turn). Never throws for a model or
     * transport problem — every such failure comes back as a non-OK {@link MainModelChatResult}.
     */
    MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens);

    /** The model name this client will call (for the honest readiness line and diagnostics). */
    String modelName();
}
