package com.aresstack.askai.java8.audio.preview;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

/**
 * Identify one Java Sound playback target without reducing it to an ambiguous display name.
 */
public final class AudioOutputDevice {

    private static final AudioOutputDevice SYSTEM_DEFAULT =
            new AudioOutputDevice(null, "System default");

    private final Mixer.Info mixerInfo;
    private final String displayName;

    private AudioOutputDevice(Mixer.Info mixerInfo, String displayName) {
        this.mixerInfo = mixerInfo;
        this.displayName = displayName;
    }

    public static AudioOutputDevice systemDefault() {
        return SYSTEM_DEFAULT;
    }

    public static AudioOutputDevice forMixer(Mixer.Info mixerInfo, String displayName) {
        if (mixerInfo == null) {
            throw new IllegalArgumentException("Mixer info must not be null.");
        }
        if (displayName == null || displayName.trim().length() == 0) {
            throw new IllegalArgumentException("Display name must not be empty.");
        }
        return new AudioOutputDevice(mixerInfo, displayName.trim());
    }

    public boolean isSystemDefault() {
        return mixerInfo == null;
    }

    public String getDisplayName() {
        return displayName;
    }

    Mixer getMixer() {
        return mixerInfo == null ? null : AudioSystem.getMixer(mixerInfo);
    }

    Mixer.Info getMixerInfo() {
        return mixerInfo;
    }

    public String toString() {
        return displayName;
    }
}
