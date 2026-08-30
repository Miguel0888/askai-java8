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
    private boolean stopRequested;

    /** Blocking synthesis + playback; call OFF the EDT. */
    public void speak(PiperTtsStore store, PiperVoice voice, String text,
                      int startupTimeoutSeconds) throws IOException {
        String prepared = prepareText(text);
        if (prepared.isEmpty()) {
            return;
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
        synchronized (lock) {
            stopRequested = false;
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
        drainAsync(current.getErrorStream()); // piper logs to stderr; never let it block
        writeTextAsync(current.getOutputStream(), prepared);
        try {
            pump(current, currentLine, startupTimeoutSeconds);
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

    /** Interrupt the current utterance; safe from any thread, no-op when silent. */
    public void stop() {
        Process toKill;
        SourceDataLine toClose;
        synchronized (lock) {
            stopRequested = true;
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

    private void pump(Process current, SourceDataLine out, int startupTimeoutSeconds)
            throws IOException {
        InputStream audio = current.getInputStream();
        byte[] buffer = new byte[8 * 1024];
        long startupDeadline = System.currentTimeMillis() + startupTimeoutSeconds * 1000L;
        boolean heardAnything = false;
        while (true) {
            synchronized (lock) {
                if (stopRequested) {
                    return;
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
                    return; // line closed by stop()
                }
                written += chunk;
            }
        }
        synchronized (lock) {
            if (stopRequested) {
                return;
            }
        }
        out.drain(); // let the tail of the utterance finish playing
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

    private void drainAsync(final InputStream stderr) {
        Thread drainer = new Thread(new Runnable() {
            public void run() {
                byte[] buffer = new byte[4 * 1024];
                try {
                    while (stderr.read(buffer) >= 0) {
                        // discard; piper's progress logging is not user-facing here
                    }
                } catch (IOException closed) {
                    // process ended
                }
            }
        }, "askai-tts-stderr");
        drainer.setDaemon(true);
        drainer.start();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
