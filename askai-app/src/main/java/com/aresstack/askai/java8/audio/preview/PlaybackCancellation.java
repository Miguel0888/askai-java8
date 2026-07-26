package com.aresstack.askai.java8.audio.preview;

/** Expose cancellation without coupling playback sessions to the service thread model. */
interface PlaybackCancellation {

    boolean isCancelled();
}
