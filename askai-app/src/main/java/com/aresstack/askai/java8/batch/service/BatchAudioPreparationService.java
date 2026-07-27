package com.aresstack.askai.java8.batch.service;

import com.aresstack.askai.java8.audio.preview.AudioFileDecoder;
import com.aresstack.askai.java8.audio.preview.JavaSoundAudioFileDecoder;
import com.aresstack.audio.application.AudioProcessingPreviewService;
import com.aresstack.audio.application.ProcessedAudioPreview;
import com.aresstack.audio.application.ProcessedWaveExportService;
import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.pipeline.AudioBlockRegistry;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Apply one DSP profile and materialize a temporary WAV for the STT backend. */
public final class BatchAudioPreparationService {

    /** The audio models expect 16 kHz mono; anything else is normalized before transcription. */
    private static final int SPEECH_SAMPLE_RATE_HZ = 16000;

    private final AudioProcessingPreviewService processingService;
    private final ProcessedWaveExportService exportService;
    private final AudioFileDecoder audioFileDecoder;

    public BatchAudioPreparationService(AudioProcessingPreviewService processingService,
                                        ProcessedWaveExportService exportService) {
        this(processingService, exportService, new JavaSoundAudioFileDecoder());
    }

    public BatchAudioPreparationService(AudioProcessingPreviewService processingService,
                                        ProcessedWaveExportService exportService,
                                        AudioFileDecoder audioFileDecoder) {
        if (processingService == null) {
            throw new IllegalArgumentException("Processing service must not be null.");
        }
        if (exportService == null) {
            throw new IllegalArgumentException("Export service must not be null.");
        }
        if (audioFileDecoder == null) {
            throw new IllegalArgumentException("Audio file decoder must not be null.");
        }
        this.processingService = processingService;
        this.exportService = exportService;
        this.audioFileDecoder = audioFileDecoder;
    }

    public PreparedBatchAudio prepare(File sourceFile, AudioProcessingProfile profile) throws IOException {
        // Decode the source through the shared decoder so batch accepts the same formats as chat
        // (wav/mp3/m4a/ogg/flac) instead of WAV only — the DSP pipeline runs unchanged afterwards.
        AudioBuffer source = audioFileDecoder.decode(sourceFile);
        String sourceId = sourceId(sourceFile);
        ProcessedAudioPreview preview = processingService.process(source, profile, sourceId);
        preview = ensureSpeechFormat(preview, sourceId);
        File temporaryFile = File.createTempFile("askai-batch-", ".wav");
        boolean exported = false;
        try {
            exportService.export(preview, temporaryFile);
            exported = true;
            return new PreparedBatchAudio(temporaryFile);
        } finally {
            if (!exported && temporaryFile.exists()) temporaryFile.delete();
        }
    }

    /**
     * Guarantee the audio handed to the STT backend is 16 kHz mono. The speech profiles already resample
     * and downmix, but the pass-through "Off" profile keeps the source format, so raw high-rate/stereo
     * audio would reach the model directly. That can push small audio models into a runaway generation
     * (100% GPU, no response until the STT read timeout). When the processed format is not already
     * 16 kHz mono, a minimal mono + 16 kHz resampler pass is applied.
     */
    private ProcessedAudioPreview ensureSpeechFormat(ProcessedAudioPreview preview, String sourceId) {
        PcmAudioFormat format = preview.getFormat();
        if (format.getSampleRateHz() == SPEECH_SAMPLE_RATE_HZ && format.getChannels() == 1) {
            return preview;
        }
        AudioBuffer buffer = new AudioBuffer(preview.getSamples(), format);
        return processingService.process(buffer, speechNormalizationProfile(), sourceId);
    }

    /** A minimal profile that downmixes to mono and resamples to 16 kHz (mono must precede the resampler). */
    private static AudioProcessingProfile speechNormalizationProfile() {
        AudioBlockRegistry registry = AudioBlockRegistry.getInstance();
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        blocks.add(registry.defaultDefinition(AudioBlockType.CHANNEL_MIXER, "batch-mono"));
        blocks.add(registry.defaultDefinition(AudioBlockType.RESAMPLER, "batch-resample-16k")
                .withParameter("targetRateHz", Integer.toString(SPEECH_SAMPLE_RATE_HZ)));
        return new AudioProcessingProfile("batch-speech-normalize", "Batch speech normalize", true, blocks);
    }

    private static String sourceId(File sourceFile) {
        if (sourceFile == null) {
            return "missing-audio-file";
        }
        return sourceFile.getAbsolutePath() + "@" + sourceFile.length() + "@" + sourceFile.lastModified();
    }

    public static final class PreparedBatchAudio implements AutoCloseable {
        private final File file;
        private PreparedBatchAudio(File file) { this.file = file; }
        public File getFile() { return file; }
        public void close() { if (file.exists()) file.delete(); }
    }
}
