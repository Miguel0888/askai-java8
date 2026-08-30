package com.aresstack.askai.java8.tts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Speaks text through the installed Piper engine: one {@code piper.exe --output_file} process per
 * utterance (text over stdin, UTF-8), then the finished WAV is played by an EXTERNAL PowerShell
 * {@code System.Media.SoundPlayer} process. Playback deliberately does NOT use the app JVM's
 * javax.sound line: on the field it was proven to route into silence (Windows keeps PER-APP audio
 * device/volume overrides keyed by the exe — piper synthesized fine, 100k+ PCM bytes went into
 * the app's line, nothing was audible, while every EXTERNAL process, e.g. the SAPI Windows voice,
 * played normally). A child process has its own audio session on the system default device — the
 * same proven route the Windows voice uses. Runs entirely on the CPU. {@link #speak} blocks until
 * playback finishes; {@link #stop()} (any thread) kills the current child immediately. A new
 * {@code speak} implicitly stops the previous one.
 */
public final class PiperSpeechSynthesizer {

    private final Object lock = new Object();
    /** The CURRENT child (first piper, then the player) — stop() kills whatever is running. */
    private Process child;
    /**
     * Utterance GENERATION instead of a boolean stop flag: a new speak (which stops the previous
     * one) bumps it, so an overlapped older utterance can never mistake the newer speak's reset
     * for "keep going" — and a stop() during the newer speak's setup can never be swallowed.
     */
    private long generation;

    /** What one utterance actually did — the diagnosis payload for "silent but no error". */
    public static final class Utterance {
        private final long pcmBytes;
        private final int sampleRate;
        private final String engineLogTail;

        Utterance(long pcmBytes, int sampleRate, String engineLogTail) {
            this.pcmBytes = pcmBytes;
            this.sampleRate = sampleRate;
            this.engineLogTail = engineLogTail;
        }

        /** WAV payload bytes handed to the external player (0 = the engine said nothing). */
        public long getPcmBytes() {
            return pcmBytes;
        }

        public int getSampleRate() {
            return sampleRate;
        }

        /** The tail of piper's own stderr log — names model-load/phoneme problems directly. */
        public String getEngineLogTail() {
            return engineLogTail;
        }

        @Override
        public String toString() {
            return pcmBytes + " PCM bytes @ " + sampleRate + " Hz (external player)";
        }
    }

    /** Blocking synthesis + playback; call OFF the EDT. */
    public Utterance speak(PiperTtsStore store, PiperVoice voice, String text,
                           int startupTimeoutSeconds) throws IOException {
        String prepared = prepareText(text);
        if (prepared.isEmpty()) {
            return new Utterance(0, 0, "empty text");
        }
        stop();
        int sampleRate = readSampleRate(store.voiceConfigFile(voice));
        Path wav = Files.createTempFile("askai-tts-", ".wav");
        final long myGeneration;
        synchronized (lock) {
            myGeneration = ++generation; // this utterance's identity; any stop outdates it
        }
        try {
            StringBuilder engineLog = synthesize(store, voice, prepared, wav,
                    startupTimeoutSeconds, myGeneration);
            long payload = Files.isRegularFile(wav) ? Math.max(0, Files.size(wav) - 44) : 0;
            if (payload > 0 && isCurrent(myGeneration)) {
                play(wav, myGeneration);
            }
            return new Utterance(payload, sampleRate, logTail(engineLog));
        } finally {
            synchronized (lock) {
                if (generation == myGeneration) {
                    child = null;
                }
            }
            try {
                Files.deleteIfExists(wav);
            } catch (IOException stillHeld) {
                wav.toFile().deleteOnExit(); // player teardown may lag a moment on Windows
            }
        }
    }

    /** Interrupt the current utterance; safe from any thread, no-op when silent. */
    public void stop() {
        Process toKill;
        synchronized (lock) {
            generation++; // outdate the running utterance
            toKill = child;
            child = null;
        }
        if (toKill != null) {
            toKill.destroy();
        }
    }

    // ------------------------------------------------------------------ internals

    /** Run piper into the WAV; returns its collected stderr log. */
    private StringBuilder synthesize(PiperTtsStore store, PiperVoice voice, String prepared,
                                     Path wav, int timeoutSeconds, long myGeneration)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                store.engineExecutable().toString(),
                "--model", store.voiceModelFile(voice).toString(),
                "--config", store.voiceConfigFile(voice).toString(),
                "--output_file", wav.toString());
        // Working directory = engine directory, so piper finds its bundled espeak-ng data.
        builder.directory(store.engineDirectory().toFile());
        Process piper = builder.start();
        adopt(piper, myGeneration);
        StringBuilder engineLog = drainAsync(piper.getErrorStream());
        drainAsync(piper.getInputStream()); // piper echoes the output path on stdout
        writeTextAsync(piper.getOutputStream(), prepared);
        if (!waitBounded(piper, timeoutSeconds, myGeneration)) {
            piper.destroy();
            throw new IOException("piper did not finish within " + timeoutSeconds
                    + "s | piper log: " + logTail(engineLog));
        }
        if (isCurrent(myGeneration) && piper.exitValue() != 0) {
            throw new IOException("piper failed (exit " + piper.exitValue()
                    + ") | piper log: " + logTail(engineLog));
        }
        return engineLog;
    }

    /**
     * Play the WAV through an EXTERNAL process (own audio session on the system default device —
     * immune to the app JVM's per-app routing). Blocks until playback ends or stop() kills it.
     */
    private void play(Path wav, long myGeneration) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "(New-Object System.Media.SoundPlayer('" + wav.toString().replace("'", "''")
                        + "')).PlaySync()");
        builder.redirectErrorStream(true);
        Process player = builder.start();
        adopt(player, myGeneration);
        drainAsync(player.getInputStream());
        waitBounded(player, Integer.MAX_VALUE, myGeneration); // playback runs to its end or stop()
    }

    /** Register the child so stop() can kill it — unless this utterance is already outdated. */
    private void adopt(Process process, long myGeneration) {
        boolean outdated;
        synchronized (lock) {
            outdated = generation != myGeneration;
            if (!outdated) {
                child = process;
            }
        }
        if (outdated) {
            process.destroy();
        }
    }

    private boolean isCurrent(long myGeneration) {
        synchronized (lock) {
            return generation == myGeneration;
        }
    }

    /** @return true when the process ended on its own; false only on timeout. */
    private boolean waitBounded(Process process, int timeoutSeconds, long myGeneration) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (process.isAlive()) {
            if (!isCurrent(myGeneration)) {
                return true; // stop() already destroyed it; nothing left to wait for
            }
            if (timeoutSeconds != Integer.MAX_VALUE && System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return true;
    }

    private static String logTail(StringBuilder engineLog) {
        synchronized (engineLog) {
            String text = engineLog.toString().trim();
            return text.length() <= 600 ? text : "…" + text.substring(text.length() - 600);
        }
    }

    /** Piper synthesizes per input LINE — blank lines are skipped, CRLF normalized. */
    static String prepareText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder prepared = new StringBuilder();
        for (String rawLine : text.replace("\r\n", "\n").split("\n")) {
            String trimmed = rawLine.trim();
            if (!trimmed.isEmpty()) {
                prepared.append(trimmed).append('\n');
            }
        }
        return prepared.toString().trim();
    }

    /** The voice config's sample rate; piper voices are typically 22050 Hz. */
    static int readSampleRate(Path configFile) {
        try {
            String json = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            Matcher rate = Pattern.compile("\"sample_rate\"\\s*:\\s*(\\d+)").matcher(json);
            if (rate.find()) {
                return Integer.parseInt(rate.group(1));
            }
        } catch (IOException | NumberFormatException unreadable) {
            // fall through to the format's default
        }
        return 22050;
    }

    private void writeTextAsync(final OutputStream stdin, final String prepared) {
        Thread writer = new Thread(new Runnable() {
            public void run() {
                try {
                    stdin.write(prepared.getBytes(StandardCharsets.UTF_8));
                    stdin.write('\n');
                    stdin.close();
                } catch (IOException closedEarly) {
                    // stop() killed the process mid-write — expected, nothing to report
                }
            }
        }, "askai-tts-stdin");
        writer.setDaemon(true);
        writer.start();
    }

    /** Drain a child stream AND keep it (bounded) — piper's stderr names problems directly. */
    private StringBuilder drainAsync(final InputStream stream) {
        final StringBuilder collected = new StringBuilder();
        Thread drainer = new Thread(new Runnable() {
            public void run() {
                byte[] buffer = new byte[4 * 1024];
                try {
                    int read;
                    while ((read = stream.read(buffer)) >= 0) {
                        synchronized (collected) {
                            collected.append(new String(buffer, 0, read,
                                    StandardCharsets.UTF_8));
                            if (collected.length() > 8 * 1024) {
                                collected.delete(0, collected.length() - 8 * 1024);
                            }
                        }
                    }
                } catch (IOException closed) {
                    // process ended
                }
            }
        }, "askai-tts-drain");
        drainer.setDaemon(true);
        drainer.start();
        return collected;
    }
}
