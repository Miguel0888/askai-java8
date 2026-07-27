package com.aresstack.askai.java8.batch.service;

/**
 * One transcription result to place into a batch Markdown document, identified by the stable pair
 * {@code modelId + profileId}. {@code profileName} is the visible heading text (may be renamed without
 * creating a new section, as long as the id is stable); {@code modelId} doubles as the visible model
 * heading (a model tag like {@code gemma4:e2b} is its own display name).
 */
public final class TranscriptionDocumentEntry {

    private final String modelId;
    private final String profileId;
    private final String profileName;
    private final String transcription;

    public TranscriptionDocumentEntry(String modelId, String profileId, String profileName,
                                      String transcription) {
        this.modelId = modelId == null ? "" : modelId.trim();
        this.profileId = profileId == null ? "" : profileId.trim();
        this.profileName = profileName == null ? "" : profileName.trim();
        this.transcription = transcription == null ? "" : transcription;
    }

    public String getModelId() {
        return modelId;
    }

    public String getProfileId() {
        return profileId;
    }

    public String getProfileName() {
        return profileName;
    }

    public String getTranscription() {
        return transcription;
    }
}
