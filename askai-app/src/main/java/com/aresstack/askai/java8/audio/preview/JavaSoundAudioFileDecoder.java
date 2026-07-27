package com.aresstack.askai.java8.audio.preview;

import com.aresstack.askai.java8.audio.format.SupportedAudioFormats;
import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/** Decode WAV, MP3, M4A/AAC, OGG and FLAC through the installed Java Sound providers. */
public final class JavaSoundAudioFileDecoder implements AudioFileDecoder {

    private static final int BUFFER_SIZE = 16384;

    public AudioBuffer decode(File file) throws IOException {
        requireSupportedFile(file);
        AudioInputStream source = null;
        AudioInputStream pcm = null;
        try {
            source = AudioSystem.getAudioInputStream(file);
            AudioFormat targetFormat = targetFormat(source.getFormat());
            pcm = AudioSystem.getAudioInputStream(targetFormat, source);
            return new AudioBuffer(toSamples(readAllBytes(pcm)), new PcmAudioFormat(
                    Math.round(targetFormat.getSampleRate()),
                    targetFormat.getChannels(),
                    targetFormat.getSampleSizeInBits()));
        } catch (UnsupportedAudioFileException ex) {
            throw new IOException("Unsupported or unreadable audio file: " + file.getName(), ex);
        } catch (IllegalArgumentException ex) {
            throw new IOException("No decoder is available for audio file: " + file.getName(), ex);
        } finally {
            closeQuietly(pcm);
            if (pcm != source) {
                closeQuietly(source);
            }
        }
    }

    private static AudioFormat targetFormat(AudioFormat sourceFormat) {
        float sampleRate = sourceFormat.getSampleRate();
        if (sampleRate <= 0.0f) {
            sampleRate = sourceFormat.getFrameRate();
        }
        int channels = sourceFormat.getChannels();
        if (sampleRate <= 0.0f || channels <= 0) {
            throw new IllegalArgumentException("Audio stream does not expose a usable PCM format.");
        }
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false);
    }

    private static byte[] readAllBytes(AudioInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static short[] toSamples(byte[] bytes) throws IOException {
        if ((bytes.length & 1) != 0) {
            throw new IOException("Decoded PCM data has an incomplete 16-bit sample.");
        }
        short[] samples = new short[bytes.length / 2];
        for (int index = 0; index < samples.length; index++) {
            int offset = index * 2;
            samples[index] = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
        }
        return samples;
    }

    private static void requireSupportedFile(File file) {
        if (file == null) {
            throw new IllegalArgumentException("Audio file must not be null.");
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("Audio file does not exist: " + file);
        }
        if (!SupportedAudioFormats.supports(file)) {
            throw new IllegalArgumentException("Unsupported audio format: " + file.getName());
        }
    }

    private static void closeQuietly(AudioInputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Preserve the primary decoding result or failure.
        }
    }
}
