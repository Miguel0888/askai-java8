package com.aresstack.askai.java8.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

/**
 * Play raw or processed preview audio away from the Swing event-dispatch thread.
 */
public interface AudioPreviewPlaybackService {

    /**
     * Start the supplied 16-bit PCM samples and stop any previous playback first.
     * Run {@code onFinished} only after normal completion.
     */
    void play(short[] samples, PcmAudioFormat format, Runnable onFinished);

    /** Select one output device by its exact Java Sound mixer name; use empty text for the system default. */
    void setOutputDeviceName(String deviceName);

    void stop();

    boolean isPlaying();
}
