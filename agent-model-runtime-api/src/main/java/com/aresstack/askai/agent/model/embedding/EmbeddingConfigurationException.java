package com.aresstack.askai.agent.model.embedding;

/**
 * Thrown when a session's embedding endpoint cannot be prepared: no usable embedding model is selected, its
 * runtime cannot be started, or the snapshot cannot be written. Never a silent "first found" fallback — the
 * caller may treat it as "no embedding descriptor" and degrade honestly.
 */
public final class EmbeddingConfigurationException extends Exception {

    public EmbeddingConfigurationException(String message) {
        super(message);
    }

    public EmbeddingConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
