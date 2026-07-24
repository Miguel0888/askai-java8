package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.HuggingFaceSearchSuggestion.Modality;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A model capability, named after ollama.com's capability tags and extended with the HuggingFace
 * input modalities, so both search sources share one vocabulary and one icon set ({@link
 * CapabilityIcons}). The enum order is also the icon display order.
 */
public enum ModelCapability {

    TEXT,
    VISION,
    AUDIO,
    TOOLS,
    THINKING,
    EMBEDDING,
    CLOUD;

    /** @return the capability for an ollama.com tag (vision/tools/thinking/embedding/cloud/...), or null. */
    public static ModelCapability fromOllamaTag(String tag) {
        if (tag == null) {
            return null;
        }
        String value = tag.trim().toLowerCase(Locale.ROOT);
        if (value.equals("vision")) {
            return VISION;
        }
        if (value.equals("tools")) {
            return TOOLS;
        }
        if (value.equals("thinking")) {
            return THINKING;
        }
        if (value.equals("embedding")) {
            return EMBEDDING;
        }
        if (value.equals("cloud")) {
            return CLOUD;
        }
        if (value.equals("audio")) {
            return AUDIO;
        }
        if (value.equals("text")) {
            return TEXT;
        }
        return null;
    }

    /** @return the capabilities for a list of ollama.com capability tags (unknown tags skipped). */
    public static Set<ModelCapability> fromOllamaTags(List<String> tags) {
        EnumSet<ModelCapability> capabilities = EnumSet.noneOf(ModelCapability.class);
        if (tags != null) {
            for (int i = 0; i < tags.size(); i++) {
                ModelCapability capability = fromOllamaTag(tags.get(i));
                if (capability != null) {
                    capabilities.add(capability);
                }
            }
        }
        return capabilities;
    }

    /** @return the capabilities for a set of HuggingFace suggestion modalities (text/audio/vision). */
    public static Set<ModelCapability> fromModalities(Set<Modality> modalities) {
        EnumSet<ModelCapability> capabilities = EnumSet.noneOf(ModelCapability.class);
        if (modalities != null) {
            for (Modality modality : modalities) {
                capabilities.add(fromModality(modality));
            }
        }
        return capabilities;
    }

    private static ModelCapability fromModality(Modality modality) {
        switch (modality) {
            case AUDIO:
                return AUDIO;
            case VISION:
                return VISION;
            case TEXT:
            default:
                return TEXT;
        }
    }
}
