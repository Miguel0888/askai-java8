package com.aresstack.askai.java8.audio.preview;

import org.junit.Test;

import javax.sound.sampled.Mixer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AudioOutputDeviceTest {

    @Test
    public void representsTheSystemDefaultWithoutInventingAMixer() {
        AudioOutputDevice device = AudioOutputDevice.systemDefault();

        assertTrue(device.isSystemDefault());
        assertEquals("System default", device.getDisplayName());
        assertSame(device, AudioOutputDevice.systemDefault());
    }

    @Test
    public void preservesTheConcreteMixerIdentity() {
        Mixer.Info mixerInfo = new TestMixerInfo("Output A", "Vendor", "Description", "1");
        AudioOutputDevice device = AudioOutputDevice.forMixer(mixerInfo, "Output A");

        assertFalse(device.isSystemDefault());
        assertEquals("Output A", device.getDisplayName());
        assertSame(mixerInfo, device.getMixerInfo());
    }

    private static final class TestMixerInfo extends Mixer.Info {
        private TestMixerInfo(String name, String vendor, String description, String version) {
            super(name, vendor, description, version);
        }
    }
}
