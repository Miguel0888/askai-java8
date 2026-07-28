package com.aresstack.askai.java8.localmodels;

/** Capabilities the LOCAL model runtime supports. First slice: reranking only. */
public enum LocalRuntimeCapability {

    RERANK("rerank");

    private final String ollamaTag;

    LocalRuntimeCapability(String ollamaTag) {
        this.ollamaTag = ollamaTag;
    }

    /** The tag as it appears in the virtual container's {@code /api/show} capabilities. */
    public String getOllamaTag() {
        return ollamaTag;
    }
}
