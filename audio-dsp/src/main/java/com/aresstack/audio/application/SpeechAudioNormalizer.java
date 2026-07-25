package com.aresstack.audio.application;

import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.dsp.AudioLevelMeter;
import com.aresstack.audio.dsp.AudioProfileProcessor;
import com.aresstack.audio.infrastructure.WavFileAudioSink;
import com.aresstack.audio.infrastructure.WavFileReader;
import com.aresstack.audio.profile.AudioProcessingProfile;
import com.aresstack.audio.profile.AudioProcessingProfiles;

import java.io.File;
import java.io.IOException;

/** Convert a raw recording WAV through the selected reusable audio-processing profile. */
public final class SpeechAudioNormalizer {

    public static final PcmAudioFormat TARGET_FORMAT = new PcmAudioFormat(16000, 1, 16);

    private final AudioProcessingProfile profile;
    private final AudioProfileProcessor processor;

    /** Use the immutable built-in speech profile. */
    public SpeechAudioNormalizer() {
        this(AudioProcessingProfiles.defaultSpeech());
    }

    public SpeechAudioNormalizer(AudioProcessingProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Profile must not be null.");
        }
        this.profile = profile;
        this.processor = new AudioProfileProcessor();
    }

    public AudioProcessingProfile getProfile() {
        return profile;
    }

    /**
     * @param rawWav    the recorded WAV in the negotiated capture format
     * @param targetWav where to write the profile output
     * @return the written file plus source/target formats, duration and raw-signal level stats
     */
    public NormalizationResult normalize(File rawWav, File targetWav) throws IOException {
        WavFileReader.WavData raw = WavFileReader.read(rawWav);
        PcmAudioFormat sourceFormat = raw.getFormat();
        short[] rawSamples = raw.getSamples();

        AudioLevelMeter rawMeter = new AudioLevelMeter();
        rawMeter.process(rawSamples, rawSamples.length, sourceFormat);

        int channels = sourceFormat.getChannels();
        long frames = channels > 0 ? (long) rawSamples.length / channels : 0L;
        long durationMillis = sourceFormat.getSampleRateHz() > 0
                ? frames * 1000L / sourceFormat.getSampleRateHz() : 0L;

        AudioBuffer processed = processor.process(new AudioBuffer(rawSamples, sourceFormat), profile);

        WavFileAudioSink sink = new WavFileAudioSink(targetWav);
        sink.open(processed.getFormat());
        try {
            sink.write(processed.getSamples(), processed.getSamples().length);
        } finally {
            sink.close();
        }

        return new NormalizationResult(targetWav, sourceFormat, processed.getFormat(), durationMillis,
                rawMeter.getOverallRms(), rawMeter.getPeak(), rawMeter.getClippedSampleCount(),
                rawMeter.getTotalSampleCount());
    }
}
