package com.aresstack.askai.java8.tts;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Speaks text through the installed Piper engine: one {@code piper.exe --output-raw} process per
 * utterance, text over stdin (UTF-8, one line per paragraph), raw 16-bit mono PCM streamed from
 * stdout straight into a {@link SourceDataLine} — audible from the first synthesized sentence, no
 * temp WAV files. Runs entirely on the CPU. {@link #speak} blocks until playback finishes;
 * {@link #stop()} (any thread) kills the process and the line immediately. A new {@code speak}
 * implicitly stops the previous one.
 */
public final class PiperSpeechSynthesizer {

    private final Object lock = new Object();
    private Process process;
    private SourceDataLine line;
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

        /** Raw PCM bytes actually written to the audio line (0 = the engine said nothing). */
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
            return pcmBytes + " PCM bytes @ " + sampleRate + " Hz";
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
        ProcessBuilder builder = new ProcessBuilder(
                store.engineExecutable().toString(),
                "--model", store.voiceModelFile(voice).toString(),
                "--config", store.voiceConfigFile(voice).toString(),
                "--output-raw");
        // Working directory = engine directory, so piper finds its bundled espeak-ng data.
        builder.directory(store.engineDirectory().toFile());
        Process current = builder.start();
        SourceDataLine currentLine;
        final long myGeneration;
        synchronized (lock) {
            myGeneration = ++generation; // this utterance's identity; any stop/new speak outdates it
            process = current;
            try {
                currentLine = openLine(sampleRate);
            } catch (LineUnavailableException noAudio) {
                current.destroy();
                process = null;
                throw new IOException("no audio output line available", noAudio);
            }
            line = currentLine;
        }
        StringBuilder engineLog = drainAsync(current.getErrorStream()); // stderr = piper's log
        writeTextAsync(current.getOutputStream(), prepared);
        try {
            long pcmBytes = pump(current, currentLine, startupTimeoutSeconds, myGeneration);
            return new Utterance(pcmBytes, sampleRate, logTail(engineLog));
        } catch (IOException failed) {
            // Attach the engine's own words to the failure — that names the real cause.
            throw new IOException(failed.getMessage() + " | piper log: " + logTail(engineLog),
                    failed);
        } finally {
            synchronized (lock) {
                if (process == current) {
                    process = null;
                    line = null;
                }
            }
            current.destroy();
            currentLine.close();
        }
    }

    private static String logTail(StringBuilder engineLog) {
        synchronized (engineLog) {
            String text = engineLog.toString().trim();
            return text.length() <= 600 ? text : "…" + text.substring(text.length() - 600);
        }
    }

    /** Interrupt the current utterance; safe from any thread, no-op when silent. */
    public void stop() {
        Process toKill;
        SourceDataLine toClose;
        synchronized (lock) {
            generation++; // outdate the running utterance
            toKill = process;
            toClose = line;
            process = null;
            line = null;
        }
        if (toKill != null) {
            toKill.destroy();
        }
        if (toClose != null) {
            toClose.stop();
            toClose.flush();
            toClose.close(); // unblocks a write() stuck in speak()'s pump loop
        }
    }

    // ------------------------------------------------------------------ internals

    /** @return the PCM bytes actually written to the line (0 = engine produced nothing). */
    private long pump(Process current, SourceDataLine out, int startupTimeoutSeconds,
                      long myGeneration) throws IOException {
        InputStream audio = current.getInputStream();
        byte[] buffer = new byte[8 * 1024];
        long startupDeadline = System.currentTimeMillis() + startupTimeoutSeconds * 1000L;
        long pcmBytes = 0;
        boolean heardAnything = false;
        while (true) {
            synchronized (lock) {
                if (generation != myGeneration) {
                    return pcmBytes; // stopped or replaced by a newer utterance
                }
            }
            // Before the first byte, poll instead of blocking so a hung engine start honours the
            // configured startup timeout instead of waiting forever.
            if (!heardAnything && audio.available() == 0) {
                if (!current.isAlive() && audio.available() == 0) {
                    throw new IOException("piper exited before producing audio (exit "
                            + current.exitValue() + ")");
                }
                if (System.currentTimeMillis() > startupDeadline) {
                    throw new IOException("piper produced no audio within "
                            + startupTimeoutSeconds + "s");
                }
                sleepQuietly(50);
                continue;
            }
            int read = audio.read(buffer);
            if (read < 0) {
                break;
            }
            heardAnything = true;
            int written = 0;
            while (written < read) {
                int chunk = out.write(buffer, written, read - written);
                if (chunk <= 0) {
                    return pcmBytes; // line closed by stop()
                }
                written += chunk;
                pcmBytes += chunk;
            }
        }
        synchronized (lock) {
            if (generation != myGeneration) {
                return pcmBytes;
            }
        }
        out.drain(); // let the tail of the utterance finish playing
        return pcmBytes;
    }

    private static SourceDataLine openLine(int sampleRate) throws LineUnavailableException {
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, 16, 1, 2, sampleRate, false); // piper: S16LE mono
        SourceDataLine line = AudioSystem.getSourceDataLine(format);
        line.open(format);
        line.start();
        return line;
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

    /** Drain piper's stderr AND keep it — it names model-load/phoneme problems directly. */
    private StringBuilder drainAsync(final InputStream stderr) {
        final StringBuilder collected = new StringBuilder();
        Thread drainer = new Thread(new Runnable() {
            public void run() {
                byte[] buffer = new byte[4 * 1024];
                try {
                    int read;
                    while ((read = stderr.read(buffer)) >= 0) {
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
        }, "askai-tts-stderr");
        drainer.setDaemon(true);
        drainer.start();
        return collected;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
