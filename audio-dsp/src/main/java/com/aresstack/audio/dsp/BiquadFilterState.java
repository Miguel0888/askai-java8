package com.aresstack.audio.dsp;

/** Per-channel delay memory for a transposed Direct-Form-II biquad. Reset to clear any tail. */
final class BiquadFilterState {

    double z1;
    double z2;

    void reset() {
        z1 = 0.0d;
        z2 = 0.0d;
    }
}
