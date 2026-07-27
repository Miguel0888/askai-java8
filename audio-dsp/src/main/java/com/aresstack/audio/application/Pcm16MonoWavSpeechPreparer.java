package com.aresstack.audio.application;

import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.infrastructure.WavFileAudioSink;
import com.aresstack.audio.pipeline.AudioBlockRegistry;
import com.aresstack.audio.pipeline.AudioProfileProcessor;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The proven STT transport format: <b>16 kHz, mono, signed 16-bit little-endian PCM WAV</b>. This is the
 * final, explicit speech-to-text preparation that used to be baked into the batch service's removed
 * {@code ensureSpeechFormat} — now a dedicated component shared by the microphone and batch paths.
 *
 * <p>Only what is actually needed is applied: a channel mixer when the input is not mono, a resampler when
 * the input is not 16 kHz (the resampler requires mono, so the mixer always precedes it). Audio that is
 * already 16 kHz mono is written straight through — no redundant resampling cascade.</p>
 */
public final class Pcm16MonoWavSpeechPreparer implements SpeechToTextAudioPreparer {

    public static final PcmAudioFormat STT_FORMAT = new PcmAudioFormat(16000, 1, 16);

    private final AudioProfileProcessor processor = new AudioProfileProcessor();

    public File prepare(AudioBuffer source, File targetWav) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("Source buffer must not be null.");
        }
        if (targetWav == null) {
            throw new IllegalArgumentException("Target WAV must not be null.");
        }
        AudioBuffer prepared = toSttFormat(source);
        WavFileAudioSink sink = new WavFileAudioSink(targetWav);
        sink.open(prepared.getFormat());
        try {
            sink.write(prepared.getSamples(), prepared.getSamples().length);
        } finally {
            sink.close();
        }
        return targetWav;
    }

    private AudioBuffer toSttFormat(AudioBuffer source) {
        PcmAudioFormat format = source.getFormat();
        boolean alreadyMono = format.getChannels() == 1;
        boolean already16k = format.getSampleRateHz() == STT_FORMAT.getSampleRateHz();
        if (alreadyMono && already16k) {
            return source; // proven format already — no resampling cascade
        }
        AudioBlockRegistry registry = AudioBlockRegistry.getInstance();
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        if (!alreadyMono) {
            blocks.add(registry.defaultDefinition(AudioBlockType.CHANNEL_MIXER, "stt-mono"));
        }
        if (!already16k) {
            blocks.add(registry.defaultDefinition(AudioBlockType.RESAMPLER, "stt-resample-16k")
                    .withParameter("targetRateHz", Integer.toString(STT_FORMAT.getSampleRateHz())));
        }
        AudioProcessingProfile profile =
                new AudioProcessingProfile("stt-transport", "STT transport", true, blocks);
        return processor.process(source, profile);
    }
}
