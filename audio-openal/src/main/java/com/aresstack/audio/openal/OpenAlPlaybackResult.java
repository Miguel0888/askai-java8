package com.aresstack.audio.openal;

/** The outcome of a successful (or cancelled) OpenAL playback, for status reporting. */
public final class OpenAlPlaybackResult {

    private final String backend;
    private final String deviceSpecifier;
    private final int channels;
    private final int sampleRateHz;
    private final long durationMillis;
    private final int bytesPlayed;
    private final boolean cancelled;

    public OpenAlPlaybackResult(String backend, String deviceSpecifier, int channels, int sampleRateHz,
                                long durationMillis, int bytesPlayed, boolean cancelled) {
        this.backend = backend;
        this.deviceSpecifier = deviceSpecifier;
        this.channels = channels;
        this.sampleRateHz = sampleRateHz;
        this.durationMillis = durationMillis;
        this.bytesPlayed = bytesPlayed;
        this.cancelled = cancelled;
    }

    public String getBackend() {
        return backend;
    }

    public String getDeviceSpecifier() {
        return deviceSpecifier;
    }

    public int getChannels() {
        return channels;
    }

    public int getSampleRateHz() {
        return sampleRateHz;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public int getBytesPlayed() {
        return bytesPlayed;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public String describe() {
        return backend + " -> \"" + deviceSpecifier + "\" @ " + sampleRateHz + " Hz, "
                + (channels == 1 ? "mono" : channels + " ch") + ", " + bytesPlayed + " bytes, "
                + durationMillis + " ms" + (cancelled ? " (cancelled)" : "");
    }
}
