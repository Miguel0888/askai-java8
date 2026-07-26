package com.aresstack.askai.java8.audio.preview;

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
 * Discover playback-capable Java Sound mixers and keep their native mixer identity intact.
 */
public final class AudioOutputDeviceCatalog {

    public List<AudioOutputDevice> findAll() {
        List<Mixer.Info> candidates = findPlaybackMixers();
        Map<String, Integer> nameCounts = countNames(candidates);
        List<AudioOutputDevice> devices = new ArrayList<AudioOutputDevice>();
        devices.add(AudioOutputDevice.systemDefault());
        for (Mixer.Info info : candidates) {
            devices.add(AudioOutputDevice.forMixer(info, createDisplayName(info, nameCounts)));
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
}
