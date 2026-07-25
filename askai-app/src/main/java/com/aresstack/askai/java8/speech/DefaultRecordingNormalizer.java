package com.aresstack.askai.java8.speech;

import com.aresstack.audio.application.NormalizationResult;
import com.aresstack.audio.application.SpeechAudioNormalizer;

import java.io.File;

/** {@link RecordingNormalizer} backed by the audio-dsp {@link SpeechAudioNormalizer}. */
public final class DefaultRecordingNormalizer implements RecordingNormalizer {

    private final SpeechAudioNormalizer normalizer = new SpeechAudioNormalizer();

    public NormalizationResult normalize(File rawWav, File targetWav) throws Exception {
        return normalizer.normalize(rawWav, targetWav);
    }
}
