package com.aresstack.audio.dsp;

import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class AudioProfileProcessorTest {

    @Test
    public void structuralBlocksConvertStereoAndResampleInConfiguredOrder() {
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        blocks.add(AudioBlockDefinition.of("mix", AudioBlockType.CHANNEL_MIXER));
        blocks.add(AudioBlockDefinition.of("resample", AudioBlockType.RESAMPLER)
                .withParameter("targetRateHz", "16000")
                .withParameter("quality", "BALANCED"));
        AudioProcessingProfile profile = new AudioProcessingProfile("test", "Test", false, blocks);
        short[] stereo = new short[48000 * 2];

        AudioBuffer result = new AudioProfileProcessor().process(
                new AudioBuffer(stereo, new PcmAudioFormat(48000, 2, 16)), profile);

        assertEquals(16000, result.getFormat().getSampleRateHz());
        assertEquals(1, result.getFormat().getChannels());
        assertEquals(16000, result.getSamples().length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void resamplerRejectsStereoWhenMixerIsPlacedAfterIt() {
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        blocks.add(AudioBlockDefinition.of("resample", AudioBlockType.RESAMPLER));
        blocks.add(AudioBlockDefinition.of("mix", AudioBlockType.CHANNEL_MIXER));
        AudioProcessingProfile profile = new AudioProcessingProfile("invalid", "Invalid", false, blocks);

        new AudioProfileProcessor().process(
                new AudioBuffer(new short[200], new PcmAudioFormat(48000, 2, 16)), profile);
    }
}
