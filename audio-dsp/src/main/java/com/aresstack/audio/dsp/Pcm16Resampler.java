package com.aresstack.audio.dsp;

/**
 * Resample a mono 16-bit PCM buffer from one sample rate to another by linear interpolation. Pure and
 * stateless. Handles non-integer rate ratios (e.g. 44.1 kHz → 16 kHz) and keeps the tail of the
 * signal: the output length is {@code round(inputLength * dstRate / srcRate)} and the last output
 * sample is interpolated up to the final input sample (clamped, never read past the end).
 *
 * <p>Linear interpolation is adequate for speech fed to a transcription model; it is not a
 * high-fidelity anti-aliasing resampler and is not meant for music.</p>
 */
public final class Pcm16Resampler {

    private Pcm16Resampler() {
    }

    /**
     * @param mono    mono 16-bit samples
     * @param srcRate source sample rate (Hz)
     * @param dstRate destination sample rate (Hz)
     * @return the resampled mono buffer (a copy when the rates are equal)
     */
    public static short[] resample(short[] mono, int srcRate, int dstRate) {
        if (srcRate <= 0 || dstRate <= 0) {
            throw new IllegalArgumentException("Sample rates must be positive.");
        }
        int inputLength = mono.length;
        if (srcRate == dstRate || inputLength == 0) {
            short[] copy = new short[inputLength];
            System.arraycopy(mono, 0, copy, 0, inputLength);
            return copy;
        }
        long rounded = Math.round((double) inputLength * dstRate / srcRate);
        int outputLength = (int) Math.max(1L, rounded);
        short[] output = new short[outputLength];
        double step = (double) srcRate / dstRate;
        for (int i = 0; i < outputLength; i++) {
            double sourcePosition = i * step;
            int index = (int) Math.floor(sourcePosition);
            double fraction = sourcePosition - index;
            int left = mono[Math.min(index, inputLength - 1)];
            int right = mono[Math.min(index + 1, inputLength - 1)];
            output[i] = (short) Math.round(left + (right - left) * fraction);
        }
        return output;
    }
}
