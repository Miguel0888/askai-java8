package com.aresstack.audio.openal;

/**
 * A precise OpenAL failure: which initialization phase failed, on which device, and the raw
 * {@code alcGetError}/{@code alGetError} codes. No silent fallback ever hides these.
 */
public final class OpenAlException extends Exception {

    private final String phase;
    private final String deviceSpecifier;
    private final int alcError;
    private final int alError;

    public OpenAlException(String phase, String deviceSpecifier, int alcError, int alError, String detail) {
        super(buildMessage(phase, deviceSpecifier, alcError, alError, detail));
        this.phase = phase;
        this.deviceSpecifier = deviceSpecifier;
        this.alcError = alcError;
        this.alError = alError;
    }

    public String getPhase() {
        return phase;
    }

    public String getDeviceSpecifier() {
        return deviceSpecifier;
    }

    public int getAlcError() {
        return alcError;
    }

    public int getAlError() {
        return alError;
    }

    private static String buildMessage(String phase, String device, int alcError, int alError, String detail) {
        StringBuilder message = new StringBuilder();
        message.append("OpenAL playback failed in phase '").append(phase).append("'");
        message.append(" on device \"").append(device == null ? "system default" : device).append("\"");
        if (detail != null && detail.length() > 0) {
            message.append(": ").append(detail);
        }
        message.append(" [alcGetError=0x").append(Integer.toHexString(alcError));
        message.append(", alGetError=0x").append(Integer.toHexString(alError)).append("]");
        return message.toString();
    }
}
