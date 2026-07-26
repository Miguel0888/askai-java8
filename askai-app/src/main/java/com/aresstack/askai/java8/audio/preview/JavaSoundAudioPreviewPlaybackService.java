package com.aresstack.askai.java8.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Java Sound playback of 16-bit PCM preview audio on a daemon background thread, so the Swing EDT is never
 * blocked. A new {@link #play} stops any running playback first; {@link #stop()} suppresses the
 * completion callback.
 */
public final class JavaSoundAudioPreviewPlaybackService implements AudioPreviewPlaybackService {

    private Thread thread;
    private SourceDataLine line;
    private volatile boolean stopped;

    public synchronized void play(short[] samples, PcmAudioFormat format, final Runnable onFinished) {
        stop();
        stopped = false;
        final AudioFormat audioFormat = new AudioFormat(
                format.getSampleRateHz(), 16, format.getChannels(), true, false); // signed, little-endian
        final byte[] bytes = toLittleEndianBytes(samples);
        thread = new Thread(new Runnable() {
            public void run() {
                playBytes(audioFormat, bytes, onFinished);
            }
        }, "askai-audio-preview-playback");
        thread.setDaemon(true);
        thread.start();
    }

    private void playBytes(AudioFormat audioFormat, byte[] bytes, Runnable onFinished) {
        SourceDataLine local = null;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
            local = (SourceDataLine) AudioSystem.getLine(info);
            synchronized (this) {
                line = local;
            }
            local.open(audioFormat);
            local.start();
            int offset = 0;
            while (offset < bytes.length && !stopped) {
                int chunk = Math.min(4096, bytes.length - offset);
                int written = local.write(bytes, offset, chunk);
                if (written <= 0) {
                    break;
                }
                offset += written;
            }
            if (!stopped) {
                local.drain();
            }
        } catch (Exception ex) {
            // Playback errors must not crash the editor; the controller surfaces a compact message.
        } finally {
            closeQuietly(local);
            synchronized (this) {
                if (line == local) {
                    line = null;
                }
            }
            if (!stopped && onFinished != null) {
                onFinished.run();
            }
        }
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

    private static byte[] toLittleEndianBytes(short[] samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2] = (byte) (samples[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return bytes;
    }
}
