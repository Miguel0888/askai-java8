package com.aresstack.askai.java8.batch.service;

import com.aresstack.askai.java8.audio.preview.WavAudioTestSource;
import com.aresstack.audio.application.AudioProcessingPreviewService;
import com.aresstack.audio.application.ProcessedAudioPreview;
import com.aresstack.audio.application.ProcessedWaveExportService;
import com.aresstack.audio.profile.AudioProcessingProfile;

import java.io.File;
import java.io.IOException;

/** Apply one DSP profile and materialize a temporary WAV for the STT backend. */
public final class BatchAudioPreparationService {

    private final AudioProcessingPreviewService processingService;
    private final ProcessedWaveExportService exportService;

    public BatchAudioPreparationService(AudioProcessingPreviewService processingService,
                                        ProcessedWaveExportService exportService) {
        this.processingService = processingService;
        this.exportService = exportService;
    }

    public PreparedBatchAudio prepare(File sourceFile, AudioProcessingProfile profile) throws IOException {
        requireWaveFile(sourceFile);
        WavAudioTestSource source = new WavAudioTestSource(sourceFile, false);
        ProcessedAudioPreview preview = processingService.process(source.readBuffer(), profile, source.getId());
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

    private void requireWaveFile(File sourceFile) {
        String name = sourceFile == null ? "" : sourceFile.getName().toLowerCase();
        if (!name.endsWith(".wav")) {
            throw new IllegalArgumentException("DSP batch processing currently requires WAV input: " + name);
        }
    }

    public static final class PreparedBatchAudio implements AutoCloseable {
        private final File file;
        private PreparedBatchAudio(File file) { this.file = file; }
        public File getFile() { return file; }
        public void close() { if (file.exists()) file.delete(); }
    }
}
