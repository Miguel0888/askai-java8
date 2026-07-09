package com.aresstack.audio.infrastructure;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * List and look up audio capture devices via Java Sound. Only mixers offering a
 * {@link TargetDataLine} (i.e. recording) are reported.
 */
public final class AvailableAudioDevices {

    private AvailableAudioDevices() {
    }

    /** @return the names of every capture-capable device, in system order. */
    public static List<String> listCaptureDeviceNames() {
        List<String> names = new ArrayList<String>();
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (int i = 0; i < mixers.length; i++) {
            Mixer mixer = AudioSystem.getMixer(mixers[i]);
            if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, null))) {
                names.add(mixers[i].getName());
            }
        }
        return names;
    }

    /**
     * Find the mixer info of the capture device with the given name (exact match first, then
     * case-insensitive substring), or {@code null} when {@code deviceName} is null/empty so the
     * caller falls back to the system default.
     */
    public static Mixer.Info findCaptureDevice(String deviceName, AudioFormat format)
            throws IllegalArgumentException {
        if (deviceName == null || deviceName.trim().length() == 0) {
            return null;
        }
        String wanted = deviceName.trim();
        Mixer.Info fallback = null;
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
        for (int i = 0; i < mixers.length; i++) {
            Mixer mixer = AudioSystem.getMixer(mixers[i]);
            if (!mixer.isLineSupported(lineInfo)) {
                continue;
            }
            if (mixers[i].getName().equals(wanted)) {
                return mixers[i];
            }
            if (fallback == null
                    && mixers[i].getName().toLowerCase().contains(wanted.toLowerCase())) {
                fallback = mixers[i];
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalArgumentException("No capture device named \"" + wanted
                + "\" supports " + describe(format) + ". Available devices: "
                + listCaptureDeviceNames());
    }

    private static String describe(AudioFormat format) {
        return (int) format.getSampleRate() + " Hz, " + format.getChannels() + " channel(s), "
                + format.getSampleSizeInBits() + "-bit PCM";
    }
}
