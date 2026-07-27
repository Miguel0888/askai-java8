package com.aresstack.askai.java8.batch.service;

import com.aresstack.askai.java8.batch.service.BatchAudioPreparationService.PreparedBatchAudio;
import com.aresstack.audio.application.DefaultAudioProcessingPreviewService;
import com.aresstack.audio.application.DefaultProcessedWaveExportService;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.infrastructure.WavFileAudioSink;
import com.aresstack.audio.infrastructure.WavFileReader;
import com.aresstack.audio.pipeline.AudioBlockRegistry;
import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Batch preparation: pass-through hands back the untouched original; a DSP profile exports the RESULT
 * format without any forced 16 kHz / mono normalization; temp files are owned and cleaned up.
 */
public class BatchAudioPreparationServiceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final BatchAudioPreparationService service = new BatchAudioPreparationService(
            new DefaultAudioProcessingPreviewService(), new DefaultProcessedWaveExportService());

    @Test
    public void passThroughReturnsTheOriginalFileUntouched() throws Exception {
        File source = writeWav("source.wav", new PcmAudioFormat(48000, 2, 16), stereoTone(48000));

        PreparedBatchAudio prepared = service.prepare(source, AudioProcessingProfiles.off());

        assertSame("pass-through must reuse the original file", source, prepared.getFile());
        prepared.close();
        assertTrue("the user's original file must never be deleted", source.isFile());
    }

    @Test
    public void passThroughIgnoresProfilesWhereEveryBlockIsDisabled() throws Exception {
        File source = writeWav("disabled.wav", new PcmAudioFormat(44100, 2, 16), stereoTone(44100));
        AudioBlockDefinition disabledGain =
                block(AudioBlockType.GAIN, "g").withEnabled(false);

        PreparedBatchAudio prepared = service.prepare(source, profile("all-off", disabledGain));

        assertSame(source, prepared.getFile());
        prepared.close();
    }

    @Test
    public void dspProfileExportsTheResultFormatWithoutForcing16kMono() throws Exception {
        File source = writeWav("stereo48.wav", new PcmAudioFormat(48000, 2, 16), stereoTone(48000));

        PreparedBatchAudio prepared = service.prepare(source, profile("gain", block(AudioBlockType.GAIN, "g")));
        File temp = prepared.getFile();

        assertNotEquals("a processed file must not be the original", source, temp);
        assertTrue(temp.isFile());
        WavFileReader.WavData out = WavFileReader.read(temp);
        assertEquals("sample rate preserved after gain", 48000, out.getFormat().getSampleRateHz());
        assertEquals("channel count preserved after gain", 2, out.getFormat().getChannels());

        prepared.close();
        assertFalse("the temp file is owned and deleted on close", temp.exists());
    }

    @Test
    public void explicitResamplerAndMixerProduceTheirConfiguredFormat() throws Exception {
        File source = writeWav("stereo44.wav", new PcmAudioFormat(44100, 2, 16), stereoTone(44100));
        AudioBlockDefinition mixer = block(AudioBlockType.CHANNEL_MIXER, "m");
        AudioBlockDefinition resampler = block(AudioBlockType.RESAMPLER, "r").withParameter("targetRateHz", "16000");

        PreparedBatchAudio prepared = service.prepare(source, profile("speech", mixer, resampler));
        File temp = prepared.getFile();

        WavFileReader.WavData out = WavFileReader.read(temp);
        assertEquals(16000, out.getFormat().getSampleRateHz());
        assertEquals(1, out.getFormat().getChannels());
        prepared.close();
        assertFalse(temp.exists());
    }

    private File writeWav(String name, PcmAudioFormat format, short[] samples) throws Exception {
        File file = folder.newFile(name);
        WavFileAudioSink sink = new WavFileAudioSink(file);
        sink.open(format);
        sink.write(samples, samples.length);
        sink.close();
        return file;
    }

    private static AudioBlockDefinition block(AudioBlockType type, String id) {
        return AudioBlockRegistry.getInstance().defaultDefinition(type, id);
    }

    private static AudioProcessingProfile profile(String id, AudioBlockDefinition... blocks) {
        List<AudioBlockDefinition> list = new ArrayList<AudioBlockDefinition>();
        for (AudioBlockDefinition block : blocks) {
            list.add(block);
        }
        return new AudioProcessingProfile(id, id, false, list);
    }

    private static short[] stereoTone(int frames) {
        short[] samples = new short[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            short value = (short) Math.round(6000.0d * Math.sin(2.0d * Math.PI * 200.0d * frame / 16000.0d));
            samples[frame * 2] = value;
            samples[frame * 2 + 1] = (short) (value / 2);
        }
        return samples;
    }
}
