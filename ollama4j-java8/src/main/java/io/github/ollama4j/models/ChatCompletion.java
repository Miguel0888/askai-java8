package io.github.ollama4j.models;

public final class ChatCompletion {

    private final String content;
    private final long evalCount;
    private final long evalDurationNanos;

    public ChatCompletion(String content, long evalCount, long evalDurationNanos) {
        this.content = content == null ? "" : content;
        this.evalCount = evalCount;
        this.evalDurationNanos = evalDurationNanos;
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

    public double tokensPerSecond() {
        if (!hasMetrics()) {
            return 0.0d;
        }
        return evalCount / (evalDurationNanos / 1000000000.0d);
    }
}
