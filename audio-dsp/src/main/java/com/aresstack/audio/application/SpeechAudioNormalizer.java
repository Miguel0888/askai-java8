package com.aresstack.audio.application;

import com.aresstack.audio.dsp.AudioLevelMeter;
import com.aresstack.audio.dsp.AudioProcessingPipeline;
import com.aresstack.audio.dsp.Pcm16Resampler;
import com.aresstack.audio.dsp.PcmChannelConverter;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.infrastructure.WavFileAudioSink;
import com.aresstack.audio.infrastructure.WavFileReader;

import java.io.File;
import java.io.IOException;

/**
 * Convert an arbitrary-format raw recording WAV into the canonical speech WAV expected by the
 * transcription endpoint: <b>16 kHz, mono, 16-bit signed little-endian PCM</b>.
 *
 * <p>Steps: read the raw WAV → down-mix to mono → resample to 16 kHz → run the standard speech DSP
 * chain at the canonical rate → write the target WAV. Level statistics for the quality check are
 * measured on the <em>raw</em> captured samples (before the DSP limiter, which would otherwise mask
 * clipping). The negotiated capture format and the canonical target format are kept distinct.</p>
 */
public final class SpeechAudioNormalizer {

    public static final PcmAudioFormat TARGET_FORMAT = new PcmAudioFormat(16000, 1, 16);

    /**
     * @param rawWav    the recorded WAV in the negotiated capture format
     * @param targetWav where to write the canonical 16 kHz mono WAV
     * @return the written file plus source/target formats, duration and raw-signal level stats
     */
    public NormalizationResult normalize(File rawWav, File targetWav) throws IOException {
        WavFileReader.WavData raw = WavFileReader.read(rawWav);
        PcmAudioFormat sourceFormat = raw.getFormat();
        short[] rawSamples = raw.getSamples();

        // Quality statistics from the raw signal (true clipping/level, before any DSP).
        AudioLevelMeter rawMeter = new AudioLevelMeter();
        rawMeter.process(rawSamples, rawSamples.length, sourceFormat);

        int channels = sourceFormat.getChannels();
        long frames = channels > 0 ? (long) rawSamples.length / channels : 0L;
        long durationMillis = sourceFormat.getSampleRateHz() > 0
                ? frames * 1000L / sourceFormat.getSampleRateHz() : 0L;

        short[] mono = PcmChannelConverter.downmixToMono(rawSamples, rawSamples.length, channels);
        short[] resampled = Pcm16Resampler.resample(mono, sourceFormat.getSampleRateHz(),
                TARGET_FORMAT.getSampleRateHz());

        // Clean the canonical-rate signal with the standard speech chain (no meter needed here).
        AudioProcessingPipeline dsp = RecordSpeechInputUseCase.buildSpeechPipeline(
                SpeechCaptureConfiguration.speechDefaults(), null);
        dsp.process(resampled, resampled.length, TARGET_FORMAT);

        WavFileAudioSink sink = new WavFileAudioSink(targetWav);
        sink.open(TARGET_FORMAT);
        try {
            sink.write(resampled, resampled.length);
        } finally {
            sink.close();
        }

        // Clipping fraction is clippedSamples / totalSamples in the SAME domain: the level meter counts
        // clipped samples across every (interleaved) sample, so the denominator must be the total sample
        // count, not the frame count — otherwise stereo would report ~double the clipping.
        return new NormalizationResult(targetWav, sourceFormat, TARGET_FORMAT, durationMillis,
                rawMeter.getOverallRms(), rawMeter.getPeak(), rawMeter.getClippedSampleCount(),
                rawMeter.getTotalSampleCount());
    }
}
