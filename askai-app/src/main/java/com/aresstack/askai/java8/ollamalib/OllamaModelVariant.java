package com.aresstack.askai.java8.ollamalib;

import java.util.Locale;

/**
 * One installable tag of an Ollama library model, e.g. {@code devstral-small-2:24b}, with the
 * details ollama.com shows for it. Immutable.
 */
public final class OllamaModelVariant {

    private final String tag;
    private final String size;
    private final String contextWindow;
    private final String inputTypes;
    private final String updatedText;

    public OllamaModelVariant(String tag, String size, String contextWindow, String inputTypes,
                              String updatedText) {
        this.tag = tag == null ? "" : tag;
        this.size = size == null ? "" : size;
        this.contextWindow = contextWindow == null ? "" : contextWindow;
        this.inputTypes = inputTypes == null ? "" : inputTypes;
        this.updatedText = updatedText == null ? "" : updatedText;
    }

    /** @return the full model tag passed to {@code /api/pull}, e.g. "devstral-small-2:24b". */
    public String getTag() {
        return tag;
    }

    /** @return the short tag after the colon, e.g. "24b", or the whole tag when there is no colon. */
    public String getShortTag() {
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    public String getSize() {
        return size;
    }

    public String getContextWindow() {
        return contextWindow;
    }

    public String getInputTypes() {
        return inputTypes;
    }

    public String getUpdatedText() {
        return updatedText;
    }

    /** @return true for a cloud-only tag ("…-cloud"), which runs on Ollama's cloud, not a local pull. */
    public boolean isCloud() {
        return getShortTag().toLowerCase(Locale.ROOT).endsWith("-cloud")
                || getShortTag().toLowerCase(Locale.ROOT).equals("cloud");
    }

    @Override
    public String toString() {
        return tag;
    }
}
