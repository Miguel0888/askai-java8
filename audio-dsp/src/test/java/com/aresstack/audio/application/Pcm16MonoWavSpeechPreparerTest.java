package com.aresstack.audio.application;

import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.infrastructure.WavFileReader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The STT preparer always emits 16 kHz mono PCM16 WAV and skips resampling when the input already matches. */
public class Pcm16MonoWavSpeechPreparerTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final Pcm16MonoWavSpeechPreparer preparer = new Pcm16MonoWavSpeechPreparer();

    @Test
    public void convert48kStereoToSixteenKMonoWav() throws Exception {
        AudioBuffer source = new AudioBuffer(stereoTone(48000), new PcmAudioFormat(48000, 2, 16));
        File out = preparer.prepare(source, folder.newFile("out48.wav"));

        WavFileReader.WavData data = WavFileReader.read(out); // throws if the RIFF/WAVE header is malformed
        assertEquals(16000, data.getFormat().getSampleRateHz());
        assertEquals(1, data.getFormat().getChannels());
        assertEquals(16, data.getFormat().getBitsPerSample());
        // ~1 s of 48 kHz downsampled to 16 kHz mono -> ~16000 frames, matching the mono sample count.
        assertTrue("frames=" + data.getSamples().length,
                Math.abs(data.getSamples().length - 16000) <= 8);
    }

    @Test
    public void alreadySixteenKMonoIsWrittenUnchangedNoResampleCascade() throws Exception {
        short[] samples = monoTone(16000);
        AudioBuffer source = new AudioBuffer(samples, new PcmAudioFormat(16000, 1, 16));
        File out = preparer.prepare(source, folder.newFile("out16.wav"));

        WavFileReader.WavData data = WavFileReader.read(out);
        assertEquals(16000, data.getFormat().getSampleRateHz());
        assertEquals(1, data.getFormat().getChannels());
        assertArrayEquals("no resampling when the format already matches", samples, data.getSamples());
    }

    @Test
    public void monoNon16kIsOnlyResampled() throws Exception {
        AudioBuffer source = new AudioBuffer(monoTone(8000), new PcmAudioFormat(8000, 1, 16));
        WavFileReader.WavData data = WavFileReader.read(preparer.prepare(source, folder.newFile("out8.wav")));
        assertEquals(16000, data.getFormat().getSampleRateHz());
        assertEquals(1, data.getFormat().getChannels());
    }

    @Test
    public void stereo16kIsOnlyDownmixed() throws Exception {
        AudioBuffer source = new AudioBuffer(stereoTone(16000), new PcmAudioFormat(16000, 2, 16));
        WavFileReader.WavData data = WavFileReader.read(preparer.prepare(source, folder.newFile("out16s.wav")));
        assertEquals(16000, data.getFormat().getSampleRateHz());
        assertEquals(1, data.getFormat().getChannels());
    }

    private static short[] monoTone(int frames) {
        short[] samples = new short[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (short) Math.round(6000.0d * Math.sin(2.0d * Math.PI * 200.0d * i / 16000.0d));
        }
        return samples;
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
