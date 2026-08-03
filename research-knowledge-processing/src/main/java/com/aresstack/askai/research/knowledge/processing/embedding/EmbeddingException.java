package com.aresstack.askai.research.knowledge.processing.embedding;

/**
 * A strict embedding failure (transport error, malformed response, wrong count/dimension, non-finite value).
 * Unchecked so it fits the pipeline's {@code EmbeddingPort.embed} signature; the worker treats it as a
 * retryable EMBEDDING stage failure. There is deliberately NO zero-vector or alternate-model fallback.
 */
public final class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
