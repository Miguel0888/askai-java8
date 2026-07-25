package com.aresstack.askai.java8.speech;

import com.aresstack.audio.application.NormalizationResult;
import com.aresstack.audio.dsp.AudioLevelMeter;
import com.aresstack.audio.domain.PcmAudioFormat;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The dictation flow, driven synchronously (inline executor) with fakes for every port: success,
 * failures at each stage, retry reusing the same recording, temp-file lifecycle and the
 * exactly-one-terminal-callback guarantee.
 */
public class SpeechDictationServiceTest {

    private static final Executor INLINE = new Executor() {
        public void execute(Runnable command) {
            command.run();
        }
    };

    private File workDir;
    private FakeRecorder recorder;
    private FakeNormalizer normalizer;
    private FakeResolver resolver;
    private FakeTranscriber transcriber;
    private RecordingListener listener;

    @Before
    public void setUp() throws IOException {
        workDir = File.createTempFile("askai-dictation-test", "");
        workDir.delete();
        workDir.mkdirs();
        recorder = new FakeRecorder();
        normalizer = new FakeNormalizer(goodMetrics());
        resolver = new FakeResolver(AudioModelResolver.AudioModelResolution.resolved("voxtral:latest"));
        transcriber = new FakeTranscriber();
        listener = new RecordingListener();
    }

    private SpeechDictationService service() {
        return new SpeechDictationService(INLINE, recorder, normalizer, resolver, transcriber,
                null, workDir, null, listener);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    public void fullSuccessInsertsTextAndDeletesTemps() {
        transcriber.text = "hallo welt";
        SpeechDictationService service = service();
        service.startRecording("");
        assertEquals(DictationState.RECORDING, service.getState());
        service.stopAndTranscribe("Automatic", "de", "");

        assertEquals(1, listener.results.size());
        assertEquals("hallo welt", listener.results.get(0).getText());
        assertEquals(0, listener.failures.size());
        assertEquals(1, listener.terminalCount());
        assertEquals(DictationState.TRANSCRIPTION_READY, service.getState());
        // On success the temp files are gone.
        assertFalse(recorder.lastRawFile.isFile());
        assertFalse(normalizer.lastTarget.isFile());
    }

    // ------------------------------------------------------------------ stage failures

    @Test
    public void microphoneOpenFailure() {
        recorder.failOnStart = true;
        SpeechDictationService service = service();
        service.startRecording("");
        assertEquals(1, listener.failures.size());
        assertEquals(DictationErrorKind.MICROPHONE_OPEN_FAILED, listener.failures.get(0).getKind());
        assertEquals(1, listener.terminalCount());
    }

    @Test
    public void finalizeFailureKeepsNothingButReportsError() {
        recorder.failOnStop = true;
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.FINALIZE_FAILED, listener.failures.get(0).getKind());
    }

    @Test
    public void noSignalBlocksUploadAndKeepsRecording() {
        normalizer.result = silentMetrics();
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.QUALITY_NO_SIGNAL, listener.failures.get(0).getKind());
        assertTrue(listener.failures.get(0).keepsRecording());
        assertTrue("raw kept for retry", recorder.lastRawFile.isFile());
        assertEquals(0, transcriber.calls);   // never uploaded
    }

    @Test
    public void modelNotAudioCapableFails() {
        resolver.resolution = new AudioModelResolver.AudioModelResolution(
                AudioModelResolver.AudioModelResolution.Status.NOT_AUDIO_CAPABLE, "llava", "completion, vision");
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("llava", "", "");
        assertEquals(DictationErrorKind.MODEL_NOT_AUDIO, listener.failures.get(0).getKind());
        assertEquals(0, transcriber.calls);
    }

    @Test
    public void unknownCapabilityFails() {
        resolver.resolution = new AudioModelResolver.AudioModelResolution(
                AudioModelResolver.AudioModelResolution.Status.CAPABILITY_UNKNOWN, "old", "");
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("old", "", "");
        assertEquals(DictationErrorKind.MODEL_CAPABILITY_UNKNOWN, listener.failures.get(0).getKind());
    }

    @Test
    public void noAudioModelFails() {
        resolver.resolution = new AudioModelResolver.AudioModelResolution(
                AudioModelResolver.AudioModelResolution.Status.NO_AUDIO_MODEL, "", "");
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.NO_AUDIO_MODEL, listener.failures.get(0).getKind());
    }

    @Test
    public void emptyTranscriptionFailsAndKeepsRecording() {
        transcriber.text = "   ";
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.TRANSCRIPTION_EMPTY, listener.failures.get(0).getKind());
        assertTrue(normalizer.lastTarget.isFile());   // kept for retry
    }

    @Test
    public void transcriptionFailureIsReportedStructured() {
        transcriber.failWith = DictationErrorKind.SERVER_ENDPOINT_UNAVAILABLE;
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.SERVER_ENDPOINT_UNAVAILABLE, listener.failures.get(0).getKind());
    }

    @Test
    public void cancelledTranscriptionIsNotATechnicalError() {
        transcriber.failWith = DictationErrorKind.CANCELLED;
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.CANCELLED, listener.failures.get(0).getKind());
        assertEquals(DictationState.CANCELLED, service.getState());
    }

    // ------------------------------------------------------------------ retry & discard

    @Test
    public void retryReusesTheSameRecording() {
        transcriber.failWith = DictationErrorKind.TRANSCRIPTION_FAILED;
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(1, listener.failures.size());
        File firstUpload = transcriber.lastFile;

        transcriber.failWith = null;
        transcriber.text = "second try";
        service.retryTranscription();

        assertEquals(1, listener.results.size());
        assertEquals("second try", listener.results.get(0).getText());
        assertEquals("retry uploads the same normalized file", firstUpload, transcriber.lastFile);
        assertEquals(2, transcriber.calls);
    }

    @Test
    public void discardDuringRecordingReturnsToIdleWithoutTerminal() {
        SpeechDictationService service = service();
        service.startRecording("");
        service.discard();
        assertEquals(DictationState.IDLE, service.getState());
        assertEquals(0, listener.terminalCount());
        assertTrue(recorder.discarded);
    }

    @Test
    public void exactlyOneTerminalCallbackOnSuccess() {
        transcriber.text = "once";
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        // Extra user actions after completion must not produce more terminal callbacks.
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(1, listener.terminalCount());
    }

    // ------------------------------------------------------------------ fakes

    private static NormalizationResult goodMetrics() {
        return metrics(2000L, 3000.0d, 20000, 0L, 32000L);
    }

    private static NormalizationResult silentMetrics() {
        return metrics(2000L, 5.0d, 40, 0L, 32000L);
    }

    private static NormalizationResult metrics(long duration, double rms, int peak, long clipped, long total) {
        return new NormalizationResult(null, new PcmAudioFormat(48000, 2, 16),
                new PcmAudioFormat(16000, 1, 16), duration, rms, peak, clipped, total);
    }

    private static final class RecordingListener implements DictationListener {
        final List<DictationResult> results = new ArrayList<DictationResult>();
        final List<DictationFailure> failures = new ArrayList<DictationFailure>();

        public void onState(DictationState state, String message) {
        }

        public void onResult(DictationResult result) {
            results.add(result);
        }

        public void onFailure(DictationFailure failure) {
            failures.add(failure);
        }

        int terminalCount() {
            return results.size() + failures.size();
        }
    }

    private final class FakeRecorder implements MicrophoneRecorder {
        boolean failOnStart;
        boolean failOnStop;
        boolean discarded;
        File lastRawFile;

        public Session start(String deviceName, File workingDirectory) throws Exception {
            if (failOnStart) {
                throw new IOException("microphone busy");
            }
            return new Session() {
                public AudioLevelMeter getMeter() {
                    return new AudioLevelMeter();
                }

                public String getDeviceName() {
                    return deviceName == null ? "" : deviceName;
                }

                public RawRecording stop() throws Exception {
                    if (failOnStop) {
                        throw new IOException("could not finalize WAV");
                    }
                    lastRawFile = new File(workingDirectory, "raw-" + System.nanoTime() + ".wav");
                    writeStub(lastRawFile);
                    return new RawRecording(lastRawFile, new PcmAudioFormat(48000, 2, 16), 0L, getDeviceName());
                }

                public void discard() {
                    discarded = true;
                }
            };
        }
    }

    private final class FakeNormalizer implements RecordingNormalizer {
        NormalizationResult result;
        File lastTarget;

        FakeNormalizer(NormalizationResult result) {
            this.result = result;
        }

        public NormalizationResult normalize(File rawWav, File targetWav) throws Exception {
            lastTarget = targetWav;
            writeStub(targetWav); // so isFile()/length() work like a real normalized WAV
            NormalizationResult r = result;
            return new NormalizationResult(targetWav, r.getSourceFormat(), r.getTargetFormat(),
                    r.getDurationMillis(), r.getOverallRms(), r.getPeak(), r.getClippedSamples(),
                    r.getTotalSamples());
        }
    }

    private static final class FakeResolver implements AudioModelResolver {
        AudioModelResolution resolution;

        FakeResolver(AudioModelResolution resolution) {
            this.resolution = resolution;
        }

        public AudioModelResolution resolve(String requestedModel) {
            return resolution;
        }
    }

    private static final class FakeTranscriber implements SpeechTranscriber {
        String text = "text";
        DictationErrorKind failWith;
        int calls;
        File lastFile;

        public String transcribe(TranscriptionInput input) throws SpeechTranscriberException {
            calls++;
            lastFile = input.getAudioFile();
            if (failWith != null) {
                throw new SpeechTranscriberException(failWith, "transcription problem");
            }
            return text;
        }

        public void cancel() {
        }

        public int lastHttpStatus() {
            return 200;
        }
    }

    private static void writeStub(File file) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            out.write(new byte[]{0, 0, 0, 0});
            out.close();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
