package com.aresstack.askai.research.text.opennlp;

/**
 * A present-but-unusable OpenNLP sentence model: the artifact exists at the resolved path but could not be
 * loaded (corrupt, truncated or an unsupported format). This is DELIBERATELY NOT the same as "no model for this
 * language" — a missing model is an expected state that permits the deterministic regex fallback, whereas a
 * broken deployed model is a typed error that must surface rather than silently degrade. Unchecked so it can
 * propagate through the neutral {@code SentenceSegmentationPort} boundary without leaking OpenNLP types.
 */
public final class OpenNlpModelException extends RuntimeException {

    public OpenNlpModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
