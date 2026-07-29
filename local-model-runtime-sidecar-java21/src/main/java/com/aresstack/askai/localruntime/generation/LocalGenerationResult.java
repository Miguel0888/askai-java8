package com.aresstack.askai.localruntime.generation;

/**
 * The terminal result of a non-streaming generation. AskAI-owned. {@code doneReason} is a stable token
 * (e.g. {@code stop}, {@code length}, {@code cancel}).
 */
public record LocalGenerationResult(String text, int promptTokens, int generatedTokens, String doneReason) {

    public LocalGenerationResult {
        text = text == null ? "" : text;
        doneReason = doneReason == null || doneReason.isEmpty() ? "stop" : doneReason;
    }
}
