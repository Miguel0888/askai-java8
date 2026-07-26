package com.aresstack.audio.openal;

/**
 * One OpenAL playback endpoint, identified by its exact device specifier (never a fuzzy display name).
 * The specifier is what {@code alcOpenDevice} must receive to open precisely this endpoint.
 */
public final class OpenAlDevice {

    private final String specifier;
    private final String displayName;

    public OpenAlDevice(String specifier, String displayName) {
        if (specifier == null || specifier.trim().length() == 0) {
            throw new IllegalArgumentException("OpenAL device specifier must not be empty.");
        }
        this.specifier = specifier;
        this.displayName = displayName == null || displayName.trim().length() == 0
                ? specifier : displayName.trim();
    }

    /** The exact string to pass to {@code alcOpenDevice}. */
    public String getSpecifier() {
        return specifier;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String toString() {
        return displayName;
    }
}
