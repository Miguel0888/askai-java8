package com.aresstack.askai.java8.service;

/**
 * UI descriptor for a future Ollama capability.
 */
public final class FeatureAction {

    private final String id;
    private final String title;
    private final String description;

    public FeatureAction(String id, String title, String description) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }
}
