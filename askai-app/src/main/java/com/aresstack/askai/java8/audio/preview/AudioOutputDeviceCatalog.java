package com.aresstack.askai.java8.audio.preview;

import com.aresstack.audio.openal.OpenAlDevice;
import com.aresstack.audio.openal.OpenAlPlayback;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discover playback devices across backends. On Windows OpenAL Soft (WASAPI) is the primary path and is
 * listed first; Java Sound mixers follow as a clearly labelled legacy option. Each device keeps its own
 * backend identity — OpenAL specifiers and Java Sound mixers are never merged or matched by name.
 */
public final class AudioOutputDeviceCatalog {

    /** Enumerates OpenAL endpoints; abstracted so the catalog is testable without the native library. */
    interface OpenAlDeviceSource {
        List<OpenAlDevice> list();
    }

    private final OpenAlDeviceSource openAlSource;

    public AudioOutputDeviceCatalog() {
        this(new NativeOpenAlDeviceSource());
    }

    AudioOutputDeviceCatalog(OpenAlDeviceSource openAlSource) {
        this.openAlSource = openAlSource;
    }

    public List<AudioOutputDevice> findAll() {
        List<AudioOutputDevice> devices = new ArrayList<AudioOutputDevice>();
        // Primary path: OpenAL Soft endpoints (WASAPI on Windows).
        for (OpenAlDevice device : openAlSource.list()) {
            devices.add(AudioOutputDevice.forOpenAl(device.getSpecifier(), device.getDisplayName()));
        }
        // Legacy path: Java Sound. Kept distinct, never linked to the OpenAL entries.
        devices.add(AudioOutputDevice.systemDefault());
        List<Mixer.Info> mixers = findPlaybackMixers();
        Map<String, Integer> nameCounts = countNames(mixers);
        for (Mixer.Info info : mixers) {
            devices.add(AudioOutputDevice.forMixer(info, createDisplayName(info, nameCounts) + " (Java Sound)"));
        }
        return devices;
    }

    private static List<Mixer.Info> findPlaybackMixers() {
        List<Mixer.Info> result = new ArrayList<Mixer.Info>();
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        DataLine.Info streaming = new DataLine.Info(SourceDataLine.class, null);
        DataLine.Info clip = new DataLine.Info(Clip.class, null);
        for (Mixer.Info info : infos) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (mixer.isLineSupported(streaming) || mixer.isLineSupported(clip)) {
                result.add(info);
            }
        }
        return result;
    }

    private static Map<String, Integer> countNames(List<Mixer.Info> infos) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (Mixer.Info info : infos) {
            String name = normalize(info.getName());
            Integer count = counts.get(name);
            counts.put(name, count == null ? 1 : count + 1);
        }
        return counts;
    }

    private static String createDisplayName(Mixer.Info info, Map<String, Integer> nameCounts) {
        String name = safe(info.getName(), "Unnamed output");
        Integer count = nameCounts.get(normalize(name));
        if (count == null || count.intValue() <= 1) {
            return name;
        }
        String description = safe(info.getDescription(), "Java Sound mixer");
        String vendor = safe(info.getVendor(), "unknown vendor");
        return name + " — " + description + " (" + vendor + ")";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ENGLISH);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().length() == 0 ? fallback : value.trim();
    }

    /** Real OpenAL enumeration; degrades to an empty list if the native library cannot be loaded. */
    private static final class NativeOpenAlDeviceSource implements OpenAlDeviceSource {
        public List<OpenAlDevice> list() {
            try {
                return new OpenAlPlayback().listPlaybackDevices();
            } catch (Throwable t) {
                // No OpenAL natives (non-Windows, headless test JVM, …): fall back to Java Sound only.
                return new ArrayList<OpenAlDevice>();
            }
        }
    }
}
