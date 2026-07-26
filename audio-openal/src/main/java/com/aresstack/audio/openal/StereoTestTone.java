package com.aresstack.audio.openal;

/**
 * A clearly audible ~1.1 s stereo test signal: a tone on the left channel, then a tone on the right,
 * so the listener can confirm both playback and correct channel routing. Interleaved PCM16 (L,R,L,R…).
 */
public final class StereoTestTone {

    public static final int CHANNELS = 2;

    private StereoTestTone() {
    }

    /** @return interleaved stereo PCM16 samples for the given sample rate. */
    public static short[] interleaved(int sampleRateHz) {
        int segment = sampleRateHz / 2;        // 0.5 s per tone
        int gap = sampleRateHz / 20;           // 0.05 s gaps
        int total = segment + gap + segment + gap;
        short[] out = new short[total * CHANNELS];
        writeTone(out, 0, segment, sampleRateHz, 440.0, true);          // left
        writeTone(out, segment + gap, segment, sampleRateHz, 660.0, false); // right
        return out;
    }

    private static void writeTone(short[] out, int startFrame, int frames, int rate, double freq,
                                  boolean left) {
        double fade = rate * 0.01; // 10 ms fade in/out to avoid clicks
        for (int i = 0; i < frames; i++) {
            double t = (double) i / rate;
            double env = Math.min(1.0, Math.min(i / fade, (frames - i) / fade));
            double value = Math.sin(2.0 * Math.PI * freq * t) * env * 0.6;
            short sample = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, value * Short.MAX_VALUE));
            int frame = startFrame + i;
            out[frame * CHANNELS] = left ? sample : 0;
            out[frame * CHANNELS + 1] = left ? 0 : sample;
        }
    }
}
