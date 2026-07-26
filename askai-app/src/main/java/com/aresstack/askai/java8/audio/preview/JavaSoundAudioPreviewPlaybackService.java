package com.aresstack.askai.java8.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Java Sound playback of 16-bit PCM preview audio on a daemon background thread, so the Swing EDT is never
 * blocked. A new {@link #play} stops any running playback first; {@link #stop()} suppresses the completion
 * callback.
 *
 * <p>Windows output lines are notoriously inconsistent: {@code isLineSupported} may claim a format works
 * while {@code open} then rejects it, and many endpoints refuse mono or non-44.1-kHz formats. So instead of
 * trusting the capability check, this service actually <em>tries to open</em> each (device, format)
 * combination — the requested format first, then stereo/44.1-kHz conversions — across the selected device
 * and, as a fallback, every playback device, and plays through the first that truly opens. Failures are
 * reported through an optional error handler instead of being swallowed.</p>
 */
public final class JavaSoundAudioPreviewPlaybackService implements AudioPreviewPlaybackService {

    private Thread thread;
    private SourceDataLine line;
    private volatile boolean stopped;
    private volatile String outputDeviceName = "";
    private volatile Consumer<String> errorHandler;
    private volatile Consumer<String> infoHandler;

    public void setOutputDeviceName(String deviceName) {
        this.outputDeviceName = deviceName == null ? "" : deviceName;
    }

    /** Report playback errors (e.g. to the status line) instead of swallowing them; may be null. */
    public void setErrorHandler(Consumer<String> handler) {
        this.errorHandler = handler;
    }

    /** Report which device/format actually got opened (for diagnosing inaudible output); may be null. */
    public void setInfoHandler(Consumer<String> handler) {
        this.infoHandler = handler;
    }

    public synchronized void play(short[] samples, PcmAudioFormat format, final Runnable onFinished) {
        stop();
        stopped = false;
        final AudioFormat sourceFormat = new AudioFormat(
                format.getSampleRateHz(), 16, format.getChannels(), true, false); // signed, little-endian
        final byte[] bytes = toLittleEndianBytes(samples);
        thread = new Thread(new Runnable() {
            public void run() {
                playBytes(sourceFormat, bytes, onFinished);
            }
        }, "askai-audio-preview-playback");
        thread.setDaemon(true);
        thread.start();
    }

    private void playBytes(AudioFormat sourceFormat, byte[] bytes, Runnable onFinished) {
        Opened opened = openAnywhere(sourceFormat, bytes);
        if (opened == null) {
            reportError("No output device could play this audio (tried "
                    + describe(sourceFormat) + " and stereo/44.1 kHz conversions).");
            return;
        }
        SourceDataLine local = opened.line;
        boolean failed = false;
        try {
            synchronized (this) {
                line = local;
            }
            reportInfo(opened);
            local.start();
            byte[] buffer = new byte[4096];
            int read;
            while (!stopped && (read = opened.stream.read(buffer)) > 0) {
                local.write(buffer, 0, read);
            }
            if (!stopped) {
                local.drain();
            }
        } catch (Exception ex) {
            failed = true;
            reportError(ex.getMessage() == null ? ex.toString() : ex.getMessage());
        } finally {
            closeQuietly(opened.stream);
            closeQuietly(local);
            synchronized (this) {
                if (line == local) {
                    line = null;
                }
            }
            if (!stopped && !failed && onFinished != null) {
                onFinished.run(); // completion callback only on a clean finish, never masking an error
            }
        }
    }

    /** An opened, ready-to-start line together with the stream to read and a label of what got opened. */
    private static final class Opened {
        final SourceDataLine line;
        final AudioInputStream stream;
        final String deviceName;
        final AudioFormat format;
        final boolean converted;

        Opened(SourceDataLine line, AudioInputStream stream, String deviceName, AudioFormat format,
               boolean converted) {
            this.line = line;
            this.stream = stream;
            this.deviceName = deviceName;
            this.format = format;
            this.converted = converted;
        }
    }

    /** Try the selected device first, then every playback device; the requested format first, then conversions. */
    private Opened openAnywhere(AudioFormat source, byte[] bytes) {
        for (Mixer mixer : candidateMixers()) {
            for (AudioFormat target : candidateFormats(source)) {
                Opened opened = tryOpen(mixer, source, target, bytes);
                if (opened != null) {
                    return opened;
                }
            }
        }
        return null;
    }

    /** Actually acquire and open a line for {@code target}, converting from {@code source} if needed. */
    private Opened tryOpen(Mixer mixer, AudioFormat source, AudioFormat target, byte[] bytes) {
        SourceDataLine acquired = null;
        AudioInputStream stream = null;
        try {
            AudioInputStream sourceStream = new AudioInputStream(
                    new ByteArrayInputStream(bytes), source, bytes.length / source.getFrameSize());
            boolean converted = !target.matches(source);
            if (converted) {
                if (!AudioSystem.isConversionSupported(target, source)) {
                    sourceStream.close();
                    return null;
                }
                stream = AudioSystem.getAudioInputStream(target, sourceStream);
            } else {
                stream = sourceStream;
            }
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, target);
            acquired = (SourceDataLine) (mixer == null ? AudioSystem.getLine(info) : mixer.getLine(info));
            acquired.open(target); // the real test — may throw even when isLineSupported claimed support
            String device = mixer == null ? "system default" : mixer.getMixerInfo().getName();
            return new Opened(acquired, stream, device, target, converted);
        } catch (Exception ex) {
            closeQuietly(acquired);
            closeQuietly(stream);
            return null;
        }
    }

    /** The selected device (if any) first, then the system default and every playback-capable device. */
    private List<Mixer> candidateMixers() {
        List<Mixer> mixers = new ArrayList<Mixer>();
        String wanted = outputDeviceName == null ? "" : outputDeviceName.trim();
        Mixer[] playback = playbackMixers();
        if (wanted.length() > 0) {
            for (int i = 0; i < playback.length; i++) {
                Mixer.Info info = playback[i].getMixerInfo();
                if (info.getName().equals(wanted) || info.getName().toLowerCase().contains(wanted.toLowerCase())) {
                    mixers.add(playback[i]);
                }
            }
        }
        mixers.add(null); // system default line
        for (int i = 0; i < playback.length; i++) {
            mixers.add(playback[i]);
        }
        return mixers;
    }

    private static Mixer[] playbackMixers() {
        List<Mixer> list = new ArrayList<Mixer>();
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        DataLine.Info sourceLine = new DataLine.Info(SourceDataLine.class, null);
        for (int i = 0; i < infos.length; i++) {
            Mixer mixer = AudioSystem.getMixer(infos[i]);
            if (mixer.isLineSupported(sourceLine)) {
                list.add(mixer);
            }
        }
        return list.toArray(new Mixer[0]);
    }

    /** The requested format first, then widely-supported stereo / 44.1-kHz conversions. */
    private static List<AudioFormat> candidateFormats(AudioFormat source) {
        float rate = source.getSampleRate();
        List<AudioFormat> formats = new ArrayList<AudioFormat>();
        formats.add(source);
        formats.add(new AudioFormat(rate, 16, 2, true, false));    // same rate, stereo (mono often refused)
        formats.add(new AudioFormat(44100f, 16, 2, true, false));  // CD stereo — the safest bet
        formats.add(new AudioFormat(48000f, 16, 2, true, false));
        formats.add(new AudioFormat(44100f, 16, 1, true, false));
        return formats;
    }

    private void reportInfo(Opened opened) {
        Consumer<String> handler = infoHandler;
        if (handler == null) {
            return;
        }
        String wanted = outputDeviceName == null ? "" : outputDeviceName.trim();
        String fellBack = "";
        if (wanted.length() > 0 && !wanted.equalsIgnoreCase(opened.deviceName)) {
            fellBack = " — \"" + wanted + "\" could not be opened (driver), using this device instead";
        }
        handler.accept("Output: " + opened.deviceName + " @ " + describe(opened.format)
                + (opened.converted ? " (converted)" : "") + fellBack);
    }

    private void reportError(String message) {
        Consumer<String> handler = errorHandler;
        if (handler != null) {
            handler.accept(message);
        }
    }

    private static String describe(AudioFormat format) {
        return (int) format.getSampleRate() + " Hz, " + format.getChannels() + " ch";
    }

    public synchronized void stop() {
        stopped = true;
        if (line != null) {
            closeQuietly(line);
            line = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    public synchronized boolean isPlaying() {
        return thread != null && thread.isAlive();
    }

    private static void closeQuietly(SourceDataLine line) {
        if (line == null) {
            return;
        }
        try {
            line.stop();
            line.flush();
            line.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static void closeQuietly(AudioInputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static byte[] toLittleEndianBytes(short[] samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2] = (byte) (samples[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return bytes;
    }
}
