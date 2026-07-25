package com.aresstack.audio.profile;

/** Define every processing function that can be placed in an audio profile. */
public enum AudioBlockType {
    CHANNEL_MIXER("Channel mixer"),
    LOW_PASS("Low-pass filter"),
    HIGH_PASS("High-pass filter"),
    BAND_PASS("Band-pass filter"),
    BAND_STOP("Band-stop filter"),
    RESAMPLER("Resampler"),
    DC_OFFSET_REMOVAL("DC offset removal"),
    NOISE_GATE("Noise gate"),
    COMPRESSOR("Compressor"),
    LIMITER("Limiter");

    private final String displayName;

    AudioBlockType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
