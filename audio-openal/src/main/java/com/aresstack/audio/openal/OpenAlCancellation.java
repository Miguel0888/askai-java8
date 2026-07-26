package com.aresstack.audio.openal;

/** Cooperative cancellation for a running OpenAL playback. */
public interface OpenAlCancellation {

    OpenAlCancellation NEVER = new OpenAlCancellation() {
        public boolean isCancelled() {
            return false;
        }
    };

    boolean isCancelled();
}
