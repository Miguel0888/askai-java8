package com.aresstack.audio.dsp;

/**
 * Resample a mono 16-bit PCM buffer from one sample rate to another by linear interpolation. Pure and
 * stateless. Handles non-integer rate ratios (e.g. 44.1 kHz → 16 kHz). The output length is
 * {@code round(inputLength * dstRate / srcRate)}, i.e. it reflects the full input duration and is not
 * truncated; the final output samples are interpolated toward the last input sample (indices are
 * clamped, never read past the end).
 *
 * <p>When downsampling (e.g. 48 kHz → 16 kHz) the input is first low-pass filtered (a windowed-sinc FIR,
 * {@link Pcm16LowPassFilter}) to just below the destination Nyquist, so content above it is removed and
 * cannot alias into the audible band before the linear-interpolation stage.</p>
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
        // Anti-aliasing: when downsampling, remove everything above the destination Nyquist first (a small
        // guard below it for the transition band), so it cannot fold back into the band we keep.
        if (dstRate < srcRate) {
            mono = Pcm16LowPassFilter.filter(mono, srcRate, 0.45d * dstRate);
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
