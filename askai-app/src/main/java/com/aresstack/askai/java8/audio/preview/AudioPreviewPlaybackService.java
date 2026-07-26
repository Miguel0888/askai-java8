package com.aresstack.askai.java8.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

/**
 * Plays raw or processed preview audio off the Swing EDT. Abstracted so the controller can be tested with a
 * fake and the real Java Sound implementation stays in the app/infrastructure layer.
 */
public interface AudioPreviewPlaybackService {

    /**
     * Start playing the given 16-bit PCM samples; a running playback is stopped first. {@code onFinished}
     * runs when playback completes normally (not when explicitly stopped).
     */
    void play(short[] samples, PcmAudioFormat format, Runnable onFinished);

    void stop();

    boolean isPlaying();
}
