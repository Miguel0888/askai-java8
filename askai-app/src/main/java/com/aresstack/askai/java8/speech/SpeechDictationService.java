package com.aresstack.askai.java8.speech;

import com.aresstack.audio.application.NormalizationResult;
import com.aresstack.audio.application.RecordingQuality;
import com.aresstack.audio.application.RecordingQualityAnalyzer;
import com.aresstack.audio.dsp.AudioLevelMeter;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Swing-free coordinator for a single dictation: open microphone → record → stop → finalize →
 * normalize → check quality → verify audio model → upload → transcribe → return text (or a structured
 * failure). Owns the {@link DictationState}, guarantees exactly one terminal callback per operation,
 * and keeps the temporary recording after a failure so it can be retried or saved. All work runs on an
 * injected {@link Executor} (a synchronous one in tests), so the flow is deterministically testable.
 */
public final class SpeechDictationService {

    private final Executor executor;
    private final MicrophoneRecorder recorder;
    private final RecordingNormalizer normalizer;
    private final AudioModelResolver modelResolver;
    private final SpeechTranscriber transcriber;
    private final RecordingQualityAnalyzer qualityAnalyzer;
    private final File workingDirectory;
    private final ServerProbe serverProbe; // optional, for the version in diagnostics
    private final DictationListener listener;

    private final AtomicLong tempCounter = new AtomicLong();

    // Guarded by this:
    private DictationState state = DictationState.IDLE;
    private MicrophoneRecorder.Session session;
    private RawRecording lastRaw;
    private File lastNormalized;
    private NormalizationResult lastNormalization;
    private String requestedModel = "";
    private String language = "";
    private String prompt = "";
    private boolean terminalDelivered;
    private boolean cancelRequested;

    public SpeechDictationService(Executor executor, MicrophoneRecorder recorder,
                                  RecordingNormalizer normalizer, AudioModelResolver modelResolver,
                                  SpeechTranscriber transcriber, RecordingQualityAnalyzer qualityAnalyzer,
                                  File workingDirectory, ServerProbe serverProbe, DictationListener listener) {
        this.executor = executor;
        this.recorder = recorder;
        this.normalizer = normalizer;
        this.modelResolver = modelResolver;
        this.transcriber = transcriber;
        this.qualityAnalyzer = qualityAnalyzer == null ? RecordingQualityAnalyzer.withDefaults() : qualityAnalyzer;
        this.workingDirectory = workingDirectory;
        this.serverProbe = serverProbe;
        this.listener = listener;
    }

    public synchronized DictationState getState() {
        return state;
    }

    /** @return the live level meter of the active recording, or {@code null} when not recording. */
    public synchronized AudioLevelMeter getActiveMeter() {
        return session == null ? null : session.getMeter();
    }

    // ------------------------------------------------------------------ user actions

    /** Begin a recording from {@code deviceName} (empty = system default). */
    public void startRecording(final String deviceName) {
        synchronized (this) {
            if (!state.canStartRecording()) {
                return;
            }
            beginOperation();
            deleteTempsLocked(); // a fresh recording supersedes any kept one
            transition(DictationState.OPENING_MICROPHONE, null);
        }
        executor.execute(new Runnable() {
            public void run() {
                try {
                    MicrophoneRecorder.Session opened = recorder.start(deviceName, workingDirectory);
                    synchronized (SpeechDictationService.this) {
                        if (cancelRequested) {
                            opened.discard();
                            transition(DictationState.IDLE, null);
                            return;
                        }
                        session = opened;
                        transition(DictationState.RECORDING, null);
                    }
                } catch (Exception ex) {
                    fail(DictationErrorKind.MICROPHONE_OPEN_FAILED, message(ex));
                }
            }
        });
    }

    /** Stop the recording and run the full transcription flow for the given model/language/prompt. */
    public void stopAndTranscribe(String requestedModel, String language, String prompt) {
        final MicrophoneRecorder.Session active;
        synchronized (this) {
            if (state != DictationState.RECORDING || session == null) {
                return;
            }
            this.requestedModel = requestedModel == null ? "" : requestedModel;
            this.language = language == null ? "" : language;
            this.prompt = prompt == null ? "" : prompt;
            active = session;
            session = null;
            transition(DictationState.FINALIZING_RECORDING, null);
        }
        executor.execute(new Runnable() {
            public void run() {
                RawRecording raw;
                try {
                    raw = active.stop();
                } catch (Exception ex) {
                    fail(DictationErrorKind.FINALIZE_FAILED, message(ex));
                    return;
                }
                synchronized (SpeechDictationService.this) {
                    lastRaw = raw;
                }
                if (!normalizeAndCheckQuality(raw)) {
                    return;
                }
                transcribeNormalized();
            }
        });
    }

    /** Re-run the transcription using the same (already recorded and normalized) audio. */
    public void retryTranscription() {
        synchronized (this) {
            if (lastNormalized == null || !lastNormalized.isFile()) {
                return;
            }
            beginOperation();
        }
        executor.execute(new Runnable() {
            public void run() {
                transcribeNormalized();
            }
        });
    }

    /** Cancel the current recording or transcription; a kept recording survives for retry. */
    public void cancel() {
        MicrophoneRecorder.Session toDiscard = null;
        synchronized (this) {
            cancelRequested = true;
            if (state == DictationState.RECORDING && session != null) {
                toDiscard = session;
                session = null;
            }
        }
        if (toDiscard != null) {
            final MicrophoneRecorder.Session s = toDiscard;
            executor.execute(new Runnable() {
                public void run() {
                    s.discard();
                }
            });
            deliverCancelled();
        } else {
            // Transcription in flight: abort it; the transcribe call will surface CANCELLED.
            transcriber.cancel();
        }
    }

    /** Throw away any recording and reset to idle (Discard recording). */
    public void discard() {
        MicrophoneRecorder.Session toDiscard = null;
        synchronized (this) {
            cancelRequested = true;
            if (session != null) {
                toDiscard = session;
                session = null;
            }
            deleteTempsLocked();
            state = DictationState.IDLE;
        }
        emitState(DictationState.IDLE, null);
        if (toDiscard != null) {
            final MicrophoneRecorder.Session s = toDiscard;
            executor.execute(new Runnable() {
                public void run() {
                    s.discard();
                }
            });
        }
    }

    /** Copy the raw recording to {@code destination} for the user (Save recording); keeps the temp. */
    public synchronized File savedRecordingSource() {
        return lastRaw == null ? null : lastRaw.getFile();
    }

    // ------------------------------------------------------------------ flow steps

    private boolean normalizeAndCheckQuality(RawRecording raw) {
        File target = new File(workingDirectory,
                "askai-speech-norm-" + System.nanoTime() + "-" + tempCounter.incrementAndGet() + ".wav");
        NormalizationResult norm;
        try {
            norm = normalizer.normalize(raw.getFile(), target);
        } catch (Exception ex) {
            fail(DictationErrorKind.NORMALIZE_FAILED, message(ex));
            return false;
        }
        synchronized (this) {
            lastNormalized = target;
            lastNormalization = norm;
        }
        RecordingQuality quality = qualityAnalyzer.analyze(norm.getDurationMillis(), norm.getOverallRms(),
                norm.getPeak(), norm.getClippedSamples(), norm.getTotalSamples(), raw.getDroppedFrames());
        if (quality == RecordingQuality.TOO_SHORT) {
            fail(DictationErrorKind.QUALITY_TOO_SHORT, "Recording too short.");
            return false;
        }
        if (quality == RecordingQuality.NO_SIGNAL) {
            fail(DictationErrorKind.QUALITY_NO_SIGNAL, "No speech signal detected.");
            return false;
        }
        return true; // VALID / CLIPPED / DROPPED_FRAMES may proceed (clipping is a warning)
    }

    private void transcribeNormalized() {
        transition(DictationState.VERIFYING_MODEL, null);
        AudioModelResolver.AudioModelResolution resolution = modelResolver.resolve(requestedModel);
        if (!resolution.isResolved()) {
            fail(mapResolution(resolution.getStatus()), "Audio model not confirmed: " + resolution.getStatus());
            return;
        }
        String model = resolution.getModelName();

        transition(DictationState.UPLOADING_AUDIO, null);
        transition(DictationState.TRANSCRIBING, null);
        long startedAt = System.currentTimeMillis();
        String text;
        try {
            text = transcriber.transcribe(new SpeechTranscriber.TranscriptionInput(
                    lastNormalized, model, language, prompt));
        } catch (SpeechTranscriber.SpeechTranscriberException ex) {
            if (ex.getKind() == DictationErrorKind.CANCELLED) {
                deliverCancelled();
            } else {
                fail(ex.getKind(), message(ex));
            }
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            fail(DictationErrorKind.TRANSCRIPTION_EMPTY, "The model returned an empty transcription.");
            return;
        }
        long transcriptionMillis = System.currentTimeMillis() - startedAt;
        DictationDiagnostics diagnostics = buildDiagnostics(model, resolution.getCapabilityStatus(),
                transcriber.lastHttpStatus(), transcriptionMillis);
        deleteTemps();
        succeed(new DictationResult(text, diagnostics));
    }

    private DictationDiagnostics buildDiagnostics(String model, String capabilityStatus,
                                                  int httpStatus, long transcriptionMillis) {
        RawRecording raw;
        NormalizationResult norm;
        File normalized;
        synchronized (this) {
            raw = lastRaw;
            norm = lastNormalization;
            normalized = lastNormalized;
        }
        DictationDiagnostics.Builder builder = DictationDiagnostics.builder()
                .model(model)
                .capabilityStatus(capabilityStatus)
                .httpStatus(httpStatus)
                .transcriptionMillis(transcriptionMillis)
                .ollamaVersion(safeVersion());
        if (raw != null) {
            builder.device(raw.getDeviceName().isEmpty() ? "system default" : raw.getDeviceName())
                    .droppedFrames(raw.getDroppedFrames());
            if (raw.getCaptureFormat() != null) {
                builder.captureFormat(raw.getCaptureFormat().toString());
            }
        }
        if (norm != null) {
            builder.targetFormat(norm.getTargetFormat().toString())
                    .durationMillis(norm.getDurationMillis())
                    .rms(norm.getOverallRms())
                    .peak(norm.getPeak())
                    .clippedSamples(norm.getClippedSamples());
        }
        if (normalized != null && normalized.isFile()) {
            builder.wavBytes(normalized.length());
        }
        return builder.build();
    }

    private String safeVersion() {
        if (serverProbe == null) {
            return "";
        }
        try {
            return serverProbe.version();
        } catch (Exception ex) {
            return "";
        }
    }

    // ------------------------------------------------------------------ terminal transitions

    private void beginOperation() {
        terminalDelivered = false;
        cancelRequested = false;
    }

    private void succeed(DictationResult result) {
        synchronized (this) {
            if (terminalDelivered) {
                return;
            }
            terminalDelivered = true;
            state = DictationState.TRANSCRIPTION_READY;
        }
        emitState(DictationState.TRANSCRIPTION_READY, null);
        listener.onResult(result);
    }

    private void fail(DictationErrorKind kind, String detail) {
        synchronized (this) {
            if (terminalDelivered) {
                return;
            }
            terminalDelivered = true;
            state = DictationState.FAILED;
            if (!kind.keepRecording()) {
                deleteTempsLocked();
            }
        }
        emitState(DictationState.FAILED, null);
        listener.onFailure(new DictationFailure(kind, detail));
    }

    private void deliverCancelled() {
        synchronized (this) {
            if (terminalDelivered) {
                return;
            }
            terminalDelivered = true;
            state = DictationState.CANCELLED;
        }
        emitState(DictationState.CANCELLED, null);
        listener.onFailure(new DictationFailure(DictationErrorKind.CANCELLED, "Cancelled."));
    }

    private void transition(DictationState next, String message) {
        synchronized (this) {
            state = next;
        }
        emitState(next, message);
    }

    private void emitState(DictationState next, String message) {
        listener.onState(next, message);
    }

    // ------------------------------------------------------------------ temp handling

    private void deleteTemps() {
        synchronized (this) {
            deleteTempsLocked();
        }
    }

    private void deleteTempsLocked() {
        if (lastRaw != null) {
            deleteQuietly(lastRaw.getFile());
            lastRaw = null;
        }
        if (lastNormalized != null) {
            deleteQuietly(lastNormalized);
            lastNormalized = null;
        }
        lastNormalization = null;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.isFile()) {
            file.delete();
        }
    }

    private static DictationErrorKind mapResolution(AudioModelResolver.AudioModelResolution.Status status) {
        switch (status) {
            case CAPABILITY_UNKNOWN:
                return DictationErrorKind.MODEL_CAPABILITY_UNKNOWN;
            case NOT_AUDIO_CAPABLE:
                return DictationErrorKind.MODEL_NOT_AUDIO;
            case NO_AUDIO_MODEL:
            default:
                return DictationErrorKind.NO_AUDIO_MODEL;
        }
    }

    private static String message(Throwable ex) {
        return ex.getMessage() == null ? ex.toString() : ex.getMessage();
    }
}
