package com.aresstack.askai.java8.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

import java.util.function.Consumer;

/**
 * Routes playback to the backend that owns the selected device: OpenAL Soft for OpenAL endpoints (the
 * primary path on Windows), the Java Sound service for Java Sound / system-default targets (legacy). The
 * device's backend decides the path — nothing is matched by name and there is no cross-backend fallback.
 */
public final class DispatchingAudioPreviewPlaybackService implements AudioPreviewPlaybackService {

    private final JavaSoundAudioPreviewPlaybackService javaSound;
    private final OpenAlAudioPreviewPlaybackService openAl;

    private volatile AudioOutputDevice device = AudioOutputDevice.systemDefault();

    public DispatchingAudioPreviewPlaybackService() {
        this(new JavaSoundAudioPreviewPlaybackService(), new OpenAlAudioPreviewPlaybackService());
    }

    DispatchingAudioPreviewPlaybackService(JavaSoundAudioPreviewPlaybackService javaSound,
                                           OpenAlAudioPreviewPlaybackService openAl) {
        this.javaSound = javaSound;
        this.openAl = openAl;
    }

    public void setErrorHandler(Consumer<String> handler) {
        javaSound.setErrorHandler(handler);
        openAl.setErrorHandler(handler);
    }

    public void setInfoHandler(Consumer<String> handler) {
        javaSound.setInfoHandler(handler);
        openAl.setInfoHandler(handler);
    }

    public void setOutputDevice(AudioOutputDevice device) {
        AudioOutputDevice target = device == null ? AudioOutputDevice.systemDefault() : device;
        this.device = target;
        javaSound.setOutputDevice(target);
        openAl.setOutputDevice(target);
    }

    public void play(short[] samples, PcmAudioFormat format, Runnable onFinished) {
        // Stop whichever backend might still be running before starting the selected one.
        AudioPreviewPlaybackService selected = serviceFor(device);
        AudioPreviewPlaybackService other = selected == javaSound ? openAl : javaSound;
        other.stop();
        selected.play(samples, format, onFinished);
    }

    public void stop() {
        javaSound.stop();
        openAl.stop();
    }

    public boolean isPlaying() {
        return javaSound.isPlaying() || openAl.isPlaying();
    }

    private AudioPreviewPlaybackService serviceFor(AudioOutputDevice target) {
        return target != null && target.isOpenAl() ? openAl : javaSound;
    }
}
