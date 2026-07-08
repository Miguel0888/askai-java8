package com.aresstack.askai.java8.client;

/**
 * Final result of an Ollama chat call, mapped from ollama4j metadata.
 *
 * <p>Carries the assembled assistant content plus the token metrics the UI needs,
 * so the UI no longer parses raw chat JSON for {@code eval_count}/{@code eval_duration}.</p>
 */
public final class OllamaChatCompletion {

    private final String content;
    private final long evalCount;
    private final long evalDurationNanos;

    public OllamaChatCompletion(String content, long evalCount, long evalDurationNanos) {
        this.content = content == null ? "" : content;
        this.evalCount = evalCount;
        this.evalDurationNanos = evalDurationNanos;
    }

    public static OllamaChatCompletion empty() {
        return new OllamaChatCompletion("", 0L, 0L);
    }

    public String getContent() {
        return content;
    }

    public long getEvalCount() {
        return evalCount;
    }

    public long getEvalDurationNanos() {
        return evalDurationNanos;
    }

    public boolean hasMetrics() {
        return evalCount > 0L && evalDurationNanos > 0L;
    }

    /**
     * Output tokens per second, or {@code 0} when metrics are unavailable.
     */
    public double tokensPerSecond() {
        if (!hasMetrics()) {
            return 0.0d;
        }
        return evalCount / (evalDurationNanos / 1_000_000_000.0d);
    }
}
