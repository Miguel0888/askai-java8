package com.aresstack.audio.dsp;

import com.aresstack.audio.domain.PcmAudioFormat;

/**
 * Measure the signal without changing it: running RMS of the last frame, overall peak magnitude
 * and the number of clipped samples (magnitude at the 16-bit limit). Place it anywhere in the
 * pipeline to observe levels for logging and debugging.
 */
public final class AudioLevelMeter implements Pcm16Processor {

    private volatile double lastFrameRms;
    private volatile int peak;
    private volatile long clippedSampleCount;
    private volatile long totalSampleCount;

    @Override
    public void process(short[] samples, int sampleCount, PcmAudioFormat format) {
        double sumOfSquares = 0;
        int framePeak = peak;
        long clipped = clippedSampleCount;
        for (int i = 0; i < sampleCount; i++) {
            int magnitude = Math.abs((int) samples[i]);
            sumOfSquares += (double) magnitude * magnitude;
            if (magnitude > framePeak) {
                framePeak = magnitude;
            }
            if (magnitude >= Short.MAX_VALUE) {
                clipped++;
            }
        }
        lastFrameRms = sampleCount == 0 ? 0 : Math.sqrt(sumOfSquares / sampleCount);
        peak = framePeak;
        clippedSampleCount = clipped;
        totalSampleCount += sampleCount;
    }

    public double getLastFrameRms() {
        return lastFrameRms;
    }

    public int getPeak() {
        return peak;
    }

    public long getClippedSampleCount() {
        return clippedSampleCount;
    }

    public long getTotalSampleCount() {
        return totalSampleCount;
    }

    /** Reset all measurements, e.g. before a new recording. */
    public void reset() {
        lastFrameRms = 0;
        peak = 0;
        clippedSampleCount = 0;
        totalSampleCount = 0;
    }

    public String describe() {
        return String.format("rms=%.0f peak=%d clipped=%d samples=%d",
                lastFrameRms, peak, clippedSampleCount, totalSampleCount);
    }
}
