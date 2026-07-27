package com.aresstack.askai.java8.batch.service;

import com.aresstack.askai.java8.audio.preview.AudioFileDecoder;
import com.aresstack.askai.java8.audio.preview.JavaSoundAudioFileDecoder;
import com.aresstack.audio.application.AudioProcessingPreviewService;
import com.aresstack.audio.application.ProcessedAudioPreview;
import com.aresstack.audio.application.ProcessedWaveExportService;
import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioProcessingProfile;

import java.io.File;
import java.io.IOException;

/**
 * Prepare one audio file for the STT backend under a single DSP profile.
 *
 * <p>Two paths, chosen by whether the profile actually does anything:</p>
 * <ul>
 *   <li><b>Pass-through</b> — when the profile has no enabled block, the user's ORIGINAL source file is
 *       handed to STT unchanged: no decode, no resample, no downmix, no temporary file. The Ollama STT
 *       client uploads the file bytes verbatim (Content-Type from the extension), so it accepts
 *       wav/mp3/m4a/ogg/flac directly — exactly like the chat path.</li>
 *   <li><b>DSP</b> — decode the container to PCM while preserving the source sample rate and channel
 *       count, run the selected profile, then export the RESULT to a temporary WAV in whatever format the
 *       pipeline produced. Sample rate and channel count are changed only by explicit RESAMPLER / channel
 *       blocks inside the profile — never implicitly here.</li>
 * </ul>
 *
 * <p>There is deliberately no forced 16&nbsp;kHz / mono normalization. Protecting a small audio model from
 * a runaway (100% GPU, no response) on unexpected input is the job of the per-item wall-clock timeout in
 * {@link BatchTranscriptionService}, not of a fixed output format here.</p>
 */
public final class BatchAudioPreparationService {

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
        if (!hasEnabledBlock(profile)) {
            // No DSP requested → true pass-through: send the untouched original file to STT.
            return PreparedBatchAudio.forOriginal(sourceFile);
        }
        AudioBuffer source = audioFileDecoder.decode(sourceFile);
        String sourceId = sourceId(sourceFile);
        ProcessedAudioPreview preview = processingService.process(source, profile, sourceId);
        // Export in the pipeline's resulting format. The rate/channel count is whatever the profile
        // produced (e.g. 48 kHz stereo stays 48 kHz stereo unless a RESAMPLER / channel block changed it).
        File temporaryFile = File.createTempFile("askai-batch-", ".wav");
        boolean exported = false;
        try {
            exportService.export(preview, temporaryFile);
            exported = true;
            return PreparedBatchAudio.forTemporary(temporaryFile);
        } finally {
            if (!exported && temporaryFile.exists()) {
                temporaryFile.delete();
            }
        }
    }

    /** @return true when the profile has at least one enabled block, i.e. it actually processes audio. */
    private static boolean hasEnabledBlock(AudioProcessingProfile profile) {
        if (profile == null) {
            return false;
        }
        for (AudioBlockDefinition block : profile.getBlocks()) {
            if (block.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    private static String sourceId(File sourceFile) {
        if (sourceFile == null) {
            return "missing-audio-file";
        }
        return sourceFile.getAbsolutePath() + "@" + sourceFile.length() + "@" + sourceFile.lastModified();
    }

    /**
     * The audio file handed to the STT backend. It is either the user's ORIGINAL source file
     * (pass-through — {@link #close()} must never delete it) or a temporary WAV this service created
     * (deleted on {@link #close()}).
     */
    public static final class PreparedBatchAudio implements AutoCloseable {
        private final File file;
        private final boolean temporary;

        private PreparedBatchAudio(File file, boolean temporary) {
            this.file = file;
            this.temporary = temporary;
        }

        static PreparedBatchAudio forOriginal(File original) {
            return new PreparedBatchAudio(original, false);
        }

        static PreparedBatchAudio forTemporary(File temporaryFile) {
            return new PreparedBatchAudio(temporaryFile, true);
        }

        public File getFile() {
            return file;
        }

        public void close() {
            if (temporary && file != null && file.exists()) {
                file.delete();
            }
        }
    }
}
